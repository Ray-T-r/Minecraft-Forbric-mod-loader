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

import java.util.ArrayList;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.boot.KernelPackMetadata;
import net.forbric.kernel.util.ForbricLog;

/**
 * Stops ONE unparseable {@code pack.mcmeta} section from deleting the whole resource pack.
 *
 * <p>{@code ResourceMetadata}'s JSON-backed implementation answers {@code Optional.empty()} for a section that is
 * absent from the file, but hard-throws through {@code DataResult.getOrThrow} for a section that is present and
 * will not parse. {@code Pack.readPackMetadata} catches {@code Exception} across its whole body and returns
 * {@code null}, so that throw costs the caller the entire pack. Why a multiloader mod's metadata reaches a parser
 * it was never built for — and why treating the section as absent is the faithful answer rather than a
 * compromise — is in {@link KernelPackMetadata}.
 *
 * <p>The parse itself is moved, not reimplemented. The kernel is BOOT-side and its {@code JsonObject},
 * {@code Codec}, {@code JsonOps} and {@code DataResult} would be different classes from the game's — those
 * libraries are loaded child-first as {@code owned} — so an injected call naming them would fail to link. Instead
 * the original body goes into {@code forbric$getSectionStrict} verbatim, frames and all, and {@code getSection}
 * gets a five-instruction body that calls it inside a {@code try}.
 *
 * <p>That shape also keeps the hand-authored bytecode to a minimum. Splicing a {@code try}/{@code catch} around
 * the original body in place would mean authoring a handler frame valid at every throw point in a method that
 * already branches; extracting it leaves exactly one frame to write, and because it is {@code F_FULL} it is
 * self-contained rather than delta-encoded against a predecessor, so no existing compressed frame changes
 * meaning.
 *
 * <p>{@code RuntimeException} rather than {@code Throwable}: the throw is a {@code JsonParseException}
 * (vanilla's {@code getOrThrow} handler constructs one), {@code getSection} declares no checked exceptions, and
 * catching {@code Throwable} here would swallow {@code StackOverflowError} and {@code OutOfMemoryError}.
 *
 * <p>The target is matched STRUCTURALLY, never by name. It is an anonymous class — {@code ResourceMetadata$2}
 * today — and anonymous numbering renumbers whenever the enclosing source shifts, which on a byte-merged base is
 * a thing that happens. The three implementors in the nest are told apart by the {@code DataResult.getOrThrow}
 * call, which only the JSON-backed one makes: the {@code EMPTY} singleton returns {@code Optional.empty()}
 * unconditionally, and the map-backed one reads an already-parsed map.
 */
public final class PackMetadataFailSoftInjector implements ClassTransformer {
	/** The anonymous implementors live in this nest; the dotted form is what the transform chain passes. */
	private static final String NEST_PREFIX = "net.minecraft.server.packs.resources.ResourceMetadata$";
	private static final String INTERFACE = "net/minecraft/server/packs/resources/ResourceMetadata";

	private static final String METHOD = "getSection";
	private static final String DESC = "(Lnet/minecraft/server/packs/metadata/MetadataSectionType;)Ljava/util/Optional;";
	private static final String ALIAS = "forbric$getSectionStrict";

	/** The instruction that tells the JSON-backed implementor apart from its two siblings. */
	private static final String RESULT = "com/mojang/serialization/DataResult";
	private static final String GET_OR_THROW = "getOrThrow";

	private static final String HOOK_OWNER = "net/forbric/kernel/boot/KernelPackMetadata";
	private static final String HOOK_NAME = "sectionFailed";
	// Boot-side: cannot name MetadataSectionType at compile time. Passing it into an Object parameter is a widening
	// reference conversion, which the verifier accepts — same trick as ClientPackHookInjector.HOOK_DESC.
	private static final String HOOK_DESC = "(Ljava/lang/Object;Ljava/lang/RuntimeException;)Ljava/util/Optional;";

	private static final String CAUGHT = "java/lang/RuntimeException";

	private int wrapped;

	@Override
	public String name() {
		return "forbric-pack-metadata-fail-soft";
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		if (!className.startsWith(NEST_PREFIX)) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		if (node.interfaces == null || !node.interfaces.contains(INTERFACE)) return classBytes;
		if (findMethod(node, ALIAS) != null) return classBytes; // already wrapped

		MethodNode getSection = findMethod(node, METHOD);
		if (getSection == null || !DESC.equals(getSection.desc) || !parses(getSection)) return classBytes;

		String argType = Type.getArgumentTypes(getSection.desc)[0].getInternalName();
		node.methods.add(extract(node, getSection));
		wrap(node, getSection, argType);

		wrapped++;
		if (wrapped == 1) {
			ForbricLog.info("[Forbric/PackMeta] %s.%s now fails soft — a pack.mcmeta section that will not parse is "
					+ "treated as absent instead of dropping the whole pack", className, METHOD);
		} else {
			// Two implementors answered the structural test. Both are wrapped (failing soft twice is harmless), but
			// the seam has drifted and whoever reads this should re-derive it before trusting the match.
			ForbricLog.warn("[Forbric/PackMeta] %s is the %d nest member matching the ResourceMetadata JSON-parse "
					+ "shape — expected exactly one; re-check PackMetadataFailSoftInjector's structural match",
					className, wrapped);
		}

		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** Moves {@code getSection}'s body — instructions, handlers, frames, locals — into a private alias, untouched. */
	private static MethodNode extract(ClassNode node, MethodNode getSection) {
		MethodNode strict = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC, ALIAS,
				getSection.desc, getSection.signature,
				getSection.exceptions == null ? null : getSection.exceptions.toArray(new String[0]));
		strict.instructions = getSection.instructions;
		strict.tryCatchBlocks = getSection.tryCatchBlocks;
		strict.localVariables = getSection.localVariables;
		strict.maxStack = getSection.maxStack;
		strict.maxLocals = getSection.maxLocals;
		return strict;
	}

	/** Gives {@code getSection} a body that calls the alias and hands a {@code RuntimeException} to the kernel. */
	private static void wrap(ClassNode node, MethodNode getSection, String argType) {
		LabelNode start = new LabelNode();
		LabelNode end = new LabelNode();
		LabelNode handler = new LabelNode();

		InsnList body = new InsnList();
		body.add(start);
		body.add(new VarInsnNode(Opcodes.ALOAD, 0));
		body.add(new VarInsnNode(Opcodes.ALOAD, 1));
		// The alias is private, so invokespecial is both correct and immune to a subclass overriding it.
		body.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name, ALIAS, getSection.desc, false));
		body.add(new InsnNode(Opcodes.ARETURN));
		body.add(end);

		body.add(handler);
		// Reachable only by the handler edge: the locals this method was entered with, one exception on the stack.
		body.add(new FrameNode(Opcodes.F_FULL, 2, new Object[] {node.name, argType}, 1, new Object[] {CAUGHT}));
		body.add(new VarInsnNode(Opcodes.ASTORE, 2));
		body.add(new VarInsnNode(Opcodes.ALOAD, 1));
		body.add(new VarInsnNode(Opcodes.ALOAD, 2));
		body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_NAME, HOOK_DESC, false));
		body.add(new InsnNode(Opcodes.ARETURN));

		getSection.instructions = body;
		getSection.tryCatchBlocks = new ArrayList<>();
		getSection.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, CAUGHT));
		getSection.localVariables = null;
		getSection.maxStack = 2;
		getSection.maxLocals = 3;
	}

	/** True when the method reaches {@code DataResult.getOrThrow} — i.e. it is the one that parses JSON. */
	private static boolean parses(MethodNode method) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && RESULT.equals(call.owner) && GET_OR_THROW.equals(call.name)) {
				return true;
			}
		}
		return false;
	}

	private static MethodNode findMethod(ClassNode node, String name) {
		for (MethodNode m : node.methods) {
			if (m.name.equals(name)) return m;
		}
		return null;
	}

	/** How many implementors were wrapped, for the boot summary and for the "expected exactly one" check. */
	public int wrappedMethods() {
		return wrapped;
	}
}
