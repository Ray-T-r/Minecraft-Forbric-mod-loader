/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.transform;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import net.forbric.kernel.util.ForbricLog;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Recomputes a mod's stack map frames when they name a superclass the merge took away.
 *
 * <p>A mod is compiled against ONE ecosystem's game jar, and the compiler bakes that hierarchy into the class
 * file: at a branch merge it writes the common supertype of the two incoming values into the
 * {@code StackMapTable}. The merged base has only one hierarchy, so wherever the two ecosystems gave a class a
 * different superclass, one side's mods carry a frame the merged base cannot satisfy — and the JVM rejects the
 * class at VERIFICATION, before a line of it runs, with a message that names a type the mod author never wrote.
 *
 * <p>Caught as {@code VerifyError: Inconsistent stackmap frames at branch target 46} in
 * InventoryProfilesNext's {@code ItemTypeExtensionsKt.initGroupIndex}: Kotlin had computed
 * {@code CapabilityProvider} as the common supertype of two {@code LocalPlayer} branches, because on
 * MinecraftForge {@code Entity extends CapabilityProvider$Entities}. The merged {@code Entity} extends
 * NeoForge's {@code AttachmentHolder}. The crash surfaced on opening the inventory and named Minecraft.
 *
 * <p><b>Recomputed, not patched.</b> Substituting one name for another cannot work: the merged
 * {@code ItemStack} still extends {@code CapabilityProvider$ItemStacks}, so {@code CapabilityProvider} is a
 * perfectly valid frame type for an ItemStack value and an invalid one for an Entity value, in the same class.
 * Only recomputing from the real hierarchy tells those apart. {@code COMPUTE_FRAMES} does exactly that, and its
 * answers are correct by construction rather than by the transformer's cleverness.
 *
 * <p><b>Why this may use {@code COMPUTE_FRAMES} when four other transformers refuse to.</b> They refuse because
 * {@code getCommonSuperClass} would have to resolve game types through a loader they do not have.
 * This one is handed a resolver over {@code ForbricClassLoader.getGameResourceAsStream}, which READS the class
 * file as a resource and never calls {@code loadClass} or {@code defineClass} — so the hierarchy walk cannot
 * re-enter the definition that is in progress.
 *
 * <p><b>The set of at-risk types is derived, not chosen.</b> For each class in the merged base, take its
 * ancestor chain in a single-ecosystem world (that ecosystem's patched base plus its carrier) and subtract its
 * chain in the merged world; whatever is left is an ancestor some mod may still name. Seven types come out of
 * today's artifacts. {@code CapabilityProvider$ItemStacks} is deliberately NOT among them — ItemStack kept it —
 * which is the case that proves the derivation is doing work.
 *
 * <p><b>A failure here abstains.</b> If the hierarchy cannot be walked, the original bytes are returned
 * unchanged. That is not timidity: {@code defineClass} does not verify, so a wrong recomputation would not fail
 * here either — it would fail at first link, somewhere else, with a message that no longer names the cause. The
 * original {@code VerifyError} at least says which class and which type.
 *
 * <p>For the same reason there is no "catch the VerifyError and retry" path, and there cannot be: by the time
 * the error fires the class is defined and the name is taken, so a second {@code defineClass} throws
 * "attempted duplicate class definition".
 *
 * <p>Known, deliberately not handled yet: {@code COMPUTE_FRAMES} also recomputes maxs and replaces unreachable
 * blocks with {@code NOP…ATHROW}, so a rewritten class's instruction list is not guaranteed byte-identical
 * outside the frames. Nothing observed this on the one class that needs it. The first time a rewritten class is
 * ALSO a mixin target, splice the original {@code Code} attributes back into the methods that carried no at-risk
 * frame.
 */
public final class MergedBaseFrameRecomputer implements ClassTransformer {

	/**
	 * Ancestors that exist in one ecosystem's hierarchy and not in the merged one.
	 *
	 * <p>Derived from the artifacts rather than picked: for every class C in the merged base,
	 * {@code chain(C, single-ecosystem world) MINUS chain(C, merged world)}, over both ecosystems. A test
	 * re-derives it from the staged jars and asserts EQUALITY, so a rebuilt base that changes the answer fails
	 * the build rather than the player's game.
	 */
	static final Set<String> LOST_ANCESTORS = Set.of(
			// Entity, Level and BlockEntity all took NeoForge's AttachmentHolder; 251 merged classes lost the
			// MinecraftForge chain above them, which is the one every Forge mod was compiled against.
			"net/minecraftforge/common/capabilities/CapabilityProvider",
			"net/minecraftforge/common/capabilities/CapabilityProvider$Entities",
			"net/minecraftforge/common/capabilities/CapabilityProvider$Levels",
			"net/minecraftforge/common/capabilities/CapabilityProvider$BlockEntities",
			// The mirror image, and the reason this is not a MinecraftForge-only repair: EnderDragonPart took
			// MinecraftForge's PartEntity, so a NeoForge mod can carry the same defect in the other direction.
			"net/neoforged/neoforge/entity/PartEntity",
			// Two anonymous-class supers the merge materialised from the other side.
			"net/minecraft/server/commands/FunctionCommand$FunctionCustomExecutor",
			"net/minecraft/commands/execution/CustomCommandExecutor$WithErrorHandling");

	/**
	 * What the cheap gate looks for. A PREFIX of the capability names, so all four nested forms match with one
	 * scan — including {@code $ItemStacks}, which the precise check below then rejects. Gate broad, confirm
	 * precisely: the gate runs on every class that loads, the confirmation on a handful.
	 */
	private static final String[] NEEDLES = {
			"net/minecraftforge/common/capabilities/CapabilityProvider",
			"net/neoforged/neoforge/entity/PartEntity",
			"net/minecraft/server/commands/FunctionCommand$FunctionCustomExecutor",
			"net/minecraft/commands/execution/CustomCommandExecutor$WithErrorHandling",
	};

	private final Function<String, byte[]> classBytes;
	/** {@code internalName -> {superName, "1" if interface}}. Process-wide; the hierarchy does not change. */
	private final Map<String, String[]> hierarchy = new ConcurrentHashMap<>();

	public MergedBaseFrameRecomputer(Function<String, byte[]> classBytes) {
		this.classBytes = classBytes;
	}

	@Override
	public String name() {
		return "forbric-merged-base-frame-recomputer";
	}

	@Override
	public byte[] transform(String className, byte[] input, TransformContext context) {
		if (input == null || input.length < 10) return input;
		if (!namesALostAncestor(input)) return input;
		// COMPUTE_FRAMES cannot process JSR/RET, and a class old enough to use them never had frames to begin
		// with — recomputing would ADD a StackMapTable the JVM does not expect at that version.
		if (majorVersion(input) < Opcodes.V1_6) return input;

		try {
			ClassNode node = new ClassNode();
			new ClassReader(input).accept(node, ClassReader.EXPAND_FRAMES | ClassReader.SKIP_DEBUG);
			List<String> named = atRiskFrameTypes(node);
			if (named.isEmpty()) return input; // named in the pool only, or only the type ItemStack kept

			ClassReader reader = new ClassReader(input);
			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
				@Override
				protected String getCommonSuperClass(String type1, String type2) {
					return commonSuperClass(type1, type2);
				}
			};
			// The (ClassReader, flags) constructor would be a SILENT no-op: ASM's method-copy fast path
			// re-emits each method's bytes verbatim, original StackMapTable included, so COMPUTE_FRAMES never
			// runs and the output is byte-identical to the input. A test pins this.
			reader.accept(writer, ClassReader.SKIP_FRAMES);
			byte[] out = writer.toByteArray();

			ForbricLog.info("[Forbric/Frames] recomputed %s — its frames named %s, which the merge took off that "
					+ "hierarchy, so the class could not pass verification", className, named);
			return out;
		} catch (Throwable t) {
			// Abstain. A wrong recomputation fails later and elsewhere; the original failure at least names
			// this class and the type it could not satisfy.
			ForbricLog.warn("[Forbric/Frames] could not recompute " + className + " — leaving its frames alone, "
					+ "so it will fail verification with its own message", t);
			return input;
		}
	}

	/** Raw-byte gate, before any parse. Every needle is ASCII, and ISO-8859-1 is a byte-for-byte widening. */
	private static boolean namesALostAncestor(byte[] bytes) {
		String text = new String(bytes, StandardCharsets.ISO_8859_1);
		for (String needle : NEEDLES) {
			if (text.indexOf(needle) >= 0) return true;
		}
		return false;
	}

	private static int majorVersion(byte[] bytes) {
		return ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
	}

	/** The lost ancestors this class names IN a frame — not merely somewhere in its constant pool. */
	private static List<String> atRiskFrameTypes(ClassNode node) {
		Set<String> found = new LinkedHashSet<>();
		for (MethodNode method : node.methods) {
			if (method.instructions == null) continue;
			for (var insn : method.instructions) {
				if (!(insn instanceof FrameNode frame)) continue;
				collect(frame.local, found);
				collect(frame.stack, found);
			}
		}
		return new ArrayList<>(found);
	}

	private static void collect(List<Object> entries, Set<String> into) {
		if (entries == null) return;
		for (Object entry : entries) {
			if (entry instanceof String type && LOST_ANCESTORS.contains(type)) into.add(type);
		}
	}

	/**
	 * The common supertype of two types, read out of the hierarchy that is actually loaded.
	 *
	 * <p>Interfaces short-circuit to {@code Object}, ahead of any assignability check and unlike ASM's default.
	 * The JVM verifier treats every object reference as assignable to an interface type, so an interface can
	 * never be what a frame got wrong; the cost is a wider frame than strictly necessary, which is safe.
	 */
	String commonSuperClass(String type1, String type2) {
		if (type1.equals(type2)) return type1;
		if (isInterface(type1) || isInterface(type2)) return "java/lang/Object";
		List<String> first = chain(type1);
		Set<String> second = new LinkedHashSet<>(chain(type2));
		for (String candidate : first) {
			if (second.contains(candidate)) return candidate;
		}
		return "java/lang/Object";
	}

	private List<String> chain(String type) {
		List<String> out = new ArrayList<>();
		String current = type;
		// Capped: a hierarchy that cycles would otherwise hang the class definition.
		for (int hop = 0; current != null && hop < 64; hop++) {
			out.add(current);
			if ("java/lang/Object".equals(current)) return out;
			current = header(current)[0];
		}
		out.add("java/lang/Object");
		return out;
	}

	private boolean isInterface(String type) {
		return "1".equals(header(type)[1]);
	}

	/** {@code {superName, isInterface}}. Throws rather than guessing, so the caller can abstain. */
	private String[] header(String type) {
		return hierarchy.computeIfAbsent(type, name -> {
			if ("java/lang/Object".equals(name)) return new String[] {null, "0"};
			byte[] bytes = classBytes.apply(name + ".class");
			if (bytes == null) {
				throw new TypeNotPresentException(name, null);
			}
			ClassReader reader = new ClassReader(bytes);
			boolean itf = (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0;
			return new String[] {reader.getSuperName(), itf ? "1" : "0"};
		});
	}
}
