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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.NativeGameReferences;
import net.forbric.kernel.util.ForbricLog;

/**
 * Adds back the vanilla members only the merged base's MinecraftForge half kept, on a base that lacks them — and does
 * nothing on today's merged base, where every one of them is still declared.
 *
 * <p>The genuine NeoForge-patched jar replaced or dropped a handful of vanilla members that the merge kept because
 * MinecraftForge left them alone. Each has a measured consumer compiled against vanilla: a Fabric mixin that names it,
 * a {@code @Shadow}, a field fabric-api reads directly, or a kernel repair that finds the vanilla member and wires it
 * to NeoForge's live one. Without the member those turn from "binds, and the kernel connects it" into a mixin with no
 * target or a {@code NoSuchFieldError}. Every row is add-if-absent: a member already declared under its name and
 * descriptor is left alone, which is the whole of its behaviour on the merged base. Three shapes, by what the consumer
 * needs:
 * <ul>
 *   <li>FIELD — vanilla's declaration, no initializer. The repair that already serves the merged base's copy writes it:
 *       {@code GuiRenderer.pictureInPictureRenderers} is filled in the constructor by
 *       {@code ForbricMergedBaseCompatTransformer.bridgeOrphanedPipRenderers}, and
 *       {@code ParticleResources.providers:Int2ObjectMap} becomes a live view of NeoForge's identifier-keyed map in
 *       {@code giveTheVanillaParticleMapAViewOfTheLiveOne}. Both repairs need the vanilla member to exist, which is why
 *       this is registered before that transformer;</li>
 *   <li>DELEGATE — vanilla's signature as a pure stub that forwards to NeoForge's widened overload of the same name,
 *       its own arguments where the types match and null, zero or false for what NeoForge appended. Exactly the shape
 *       the merged base has for {@code AxeItem.evaluateNewBlockState}, so the {@code carrier-stubs.txt} row MixinStubRebind
 *       moves Fabric injectors along keeps working; a vanilla-compiled caller gets NeoForge's live body;</li>
 *   <li>VANILLA_BODY — vanilla's own method, copied from the vanilla reference ({@code native-reference/FABRIC}, the
 *       hash-pinned vanilla classes the kernel already resolves Fabric mixins against) where the consumers anchor
 *       INSIDE vanilla's body: fabric-rendering's seven {@code @WrapOperation}s on
 *       {@code Hud.extractHotbarAndDecorations}' calls, its {@code @ModifyVariable} on the renderer stored in
 *       {@code GuiRenderer.preparePictureInPictureState}, balm's wrap of the moisture read in
 *       {@code CropBlock.getGrowthSpeed(Block, …)}. A stub has none of those points, so a delegate would turn each into
 *       an unresolved injector. The copy calls only what the class itself still declares (checked: a reference to a
 *       member vanilla declared and this class does not stands the row down), its private lambdas come with it under a
 *       fresh name when the class already has a different body under theirs, and the live path reaches it where the
 *       merged base's does: the PiP repair clones this lookup into NeoForge's pooled one, and
 *       {@code PayloadCodecFunnelInjector} routes NeoForge's {@code CustomPacketPayload.codec} through vanilla's.</li>
 * </ul>
 * Not twinned, on purpose: {@code collectParts} (NeoForge's default method already bridges it), {@code dumpValue}
 * (datagen only), {@code ItemStack.hurtAndBreak}/{@code processDurabilityChange} with a {@code ServerPlayer} (NeoForge
 * declares both itself, as stubs onto its {@code LivingEntity} overloads — a stub-table question, not a missing member),
 * {@code LootPool}'s five-argument constructor (no consumer in any corpus) and {@code RegistryDataLoader}'s four-argument
 * loader (FabricRegistryLoaderMixinAdapter decides where those injectors go, and its lambda names decide where
 * fabric-registry-sync's name-only {@code lambda$load$0} selectors bind).
 *
 * <p>Every anchor is a HEDGE: on today's carriers nothing is missing, so nothing lands. {@code -Dforbric.vanillaMemberTwins=off}.
 */
public final class VanillaMemberTwinInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.vanillaMemberTwins";

	enum Kind { FIELD, DELEGATE, VANILLA_BODY }

	/**
	 * One member to add when absent.
	 *
	 * @param owner     the class (internal name)
	 * @param name      the member's name
	 * @param desc      vanilla's descriptor — the twin's
	 * @param live      for DELEGATE, the descriptor of NeoForge's same-named overload it forwards to
	 * @param access    the access used when the vanilla reference does not say
	 * @param consumers who needs it, for the log and the anchor's cost
	 */
	record Row(String owner, Kind kind, String name, String desc, String live, int access, String consumers) {
	}

	static final String GUI_RENDERER = "net/minecraft/client/gui/render/GuiRenderer";
	static final String HUD = "net/minecraft/client/gui/Hud";
	static final String PARTICLE_RESOURCES = "net/minecraft/client/particle/ParticleResources";
	static final String PAYLOAD = "net/minecraft/network/protocol/common/custom/CustomPacketPayload";
	static final String PAYLOAD_CODEC = PAYLOAD + "$1";
	static final String CROP = "net/minecraft/world/level/block/CropBlock";
	static final String AXE = "net/minecraft/world/item/AxeItem";
	private static final String FALLBACK = "L" + PAYLOAD + "$FallbackProvider;";
	private static final String AXE_ARGS = "Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
			+ "Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/block/state/BlockState;";

	static final List<Row> ROWS = List.of(
			new Row(GUI_RENDERER, Kind.FIELD, "pictureInPictureRenderers", "Ljava/util/Map;", null,
					Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
					"fabric-rendering's GuiRendererMixin shadows it, malilib and SuperMartijn642's core lib register into it"),
			new Row(GUI_RENDERER, Kind.VANILLA_BODY, "preparePictureInPictureState",
					"(Lnet/minecraft/client/renderer/state/gui/pip/PictureInPictureRenderState;I)V", null, Opcodes.ACC_PRIVATE,
					"fabric-rendering substitutes the renderer it stores, Xaero's minimap hooks it, and the kernel's PiP "
							+ "fallback is cloned from its lookup"),
			new Row(HUD, Kind.VANILLA_BODY, "extractHotbarAndDecorations",
					"(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V", null,
					Opcodes.ACC_PRIVATE, "fabric-rendering's HudMixin wraps seven of its calls; apoli, bettermounthud and "
							+ "tcdcommons hook it"),
			new Row(PARTICLE_RESOURCES, Kind.FIELD, "providers", "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;", null,
					Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
					"fabric-particles writes Fabric particle providers into it directly"),
			new Row(PARTICLE_RESOURCES, Kind.VANILLA_BODY, "getProviders", "()Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;",
					null, Opcodes.ACC_PUBLIC, "a vanilla-compiled reader of the int-keyed provider map"),
			new Row(PAYLOAD, Kind.VANILLA_BODY, "codec", "(" + FALLBACK + "Ljava/util/List;)Lnet/minecraft/network/codec/StreamCodec;",
					null, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
					"fabric-networking's packet mixins, KernelPayloadCodecs and PayloadCodecFunnelInjector build codecs "
							+ "through it"),
			new Row(PAYLOAD_CODEC, Kind.DELEGATE, "<init>", "(Ljava/util/Map;" + FALLBACK + ")V",
					"(Ljava/util/Map;Lnet/minecraft/network/ConnectionProtocol;Lnet/minecraft/network/protocol/PacketFlow;"
							+ FALLBACK + ")V", 0,
					"vanilla's CustomPacketPayload.codec constructs its stream codec this way"),
			new Row(CROP, Kind.VANILLA_BODY, "getGrowthSpeed",
					"(Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)F",
					null, Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC, "balm-fabric wraps its farmland moisture read"),
			new Row(AXE, Kind.DELEGATE, "evaluateNewBlockState", "(" + AXE_ARGS + ")Ljava/util/Optional;",
					"(" + AXE_ARGS + "Lnet/minecraft/world/item/context/UseOnContext;)Ljava/util/Optional;", Opcodes.ACC_PRIVATE,
					"Fabric injectors on vanilla's overload, which MixinStubRebind moves onto NeoForge's body"));

	private final Function<String, ClassNode> vanilla;
	private final Set<String> missingReferences = java.util.concurrent.ConcurrentHashMap.newKeySet();
	/** Bumped from transform(), which concurrent class loading may run on several threads at once. */
	private final java.util.concurrent.atomic.AtomicInteger twins = new java.util.concurrent.atomic.AtomicInteger();

	/**
	 * Vanilla's classes from the reader Mixin binds, frames expanded. Not what KernelBoot uses: the twins run before Mixin
	 * binds that reader, so production builds its own over the game's resources with {@link #vanillaReferences}.
	 */
	public VanillaMemberTwinInjector() {
		this(owner -> NativeGameReferences.referenceWithFrames(Ecosystem.FABRIC, owner));
	}

	/**
	 * What KernelBoot hands the twins as vanilla: the FABRIC native references over {@code gameResources}, through the
	 * sources this launch configured ({@link NativeGameReferences#over}) — the vanilla-reference sidecar wherever the
	 * launch has one, the base's embedded index otherwise — read with {@link ClassReader#EXPAND_FRAMES}. Over the resources
	 * alone (the reader's one-argument constructor) only an embedded index answers, and the genuine NeoForge base embeds
	 * none, so every copied body would stand down there and {@code PayloadCodecFunnelInjector}'s REQUIRED anchor would miss
	 * for want of vanilla's {@code codec} overload. Call it after {@link NativeGameReferences#configure}.
	 *
	 * @param gameResources the game's resources by path, as the loader serves them before any transformer
	 */
	public static Function<String, ClassNode> vanillaReferences(Function<String, byte[]> gameResources) {
		NativeGameReferences references = NativeGameReferences.over(gameResources);
		return owner -> references.getWithFrames(Ecosystem.FABRIC, owner);
	}

	/**
	 * @param vanilla vanilla's class by internal name, read with {@link ClassReader#EXPAND_FRAMES}, or null when there is
	 *                no reference for it; asked only for a row that copies vanilla's declaration
	 */
	public VanillaMemberTwinInjector(Function<String, ClassNode> vanilla) {
		this.vanilla = vanilla;
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override
	public String name() {
		return "forbric:vanilla-member-twins";
	}

	@Override
	public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("switched off by -D" + PROPERTY);
		Map<String, List<String>> byOwner = new java.util.LinkedHashMap<>();
		for (Row row : ROWS) byOwner.computeIfAbsent(row.owner(), o -> new ArrayList<>()).add(row.consumers());
		List<AnchorSet.Anchor> anchors = new ArrayList<>();
		for (var entry : byOwner.entrySet()) {
			anchors.add(new AnchorSet.Anchor(entry.getKey().replace('/', '.'), AnchorSet.Severity.HEDGE,
					"on a base without vanilla's member: " + String.join("; ", entry.getValue())));
		}
		return AnchorSet.of(anchors.toArray(new AnchorSet.Anchor[0]));
	}

	@Override
	public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0 || className == null) return bytes;
		String internal = className.replace('.', '/');
		List<Row> rows = new ArrayList<>();
		for (Row row : ROWS) if (row.owner().equals(internal)) rows.add(row);
		if (rows.isEmpty()) return bytes;

		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		// Fields first: a copied body may read the field a row adds.
		rows.sort(java.util.Comparator.comparing(row -> row.kind() != Kind.FIELD));
		List<String> added = new ArrayList<>();
		ClassNode reference = null;
		boolean referenceAsked = false;
		for (Row row : rows) {
			if (row.kind() == Kind.FIELD ? field(node, row.name(), row.desc()) != null : method(node, row.name(), row.desc()) != null) {
				continue;
			}
			if (!referenceAsked) {
				reference = vanilla == null ? null : vanilla.apply(internal);
				referenceAsked = true;
			}
			boolean landed = switch (row.kind()) {
				case FIELD -> addField(node, row, reference);
				case DELEGATE -> addDelegate(node, row, reference);
				case VANILLA_BODY -> addVanillaBody(node, row, reference);
			};
			if (landed) added.add(row.name() + row.desc());
		}
		if (added.isEmpty()) return bytes;
		twins.addAndGet(added.size());
		ForbricLog.info("[Forbric/VanillaTwins] %s: added vanilla's %s — this base lacks them, and vanilla-compiled code "
				+ "names them", className, String.join(", ", added));
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static boolean addField(ClassNode node, Row row, ClassNode reference) {
		FieldNode declared = reference == null ? null : field(reference, row.name(), row.desc());
		node.fields.add(new FieldNode(declared != null ? declared.access : row.access(), row.name(), row.desc(),
				declared != null ? declared.signature : null, null));
		return true;
	}

	private static boolean addDelegate(ClassNode node, Row row, ClassNode reference) {
		MethodNode live = method(node, row.name(), row.live());
		if (live == null) {
			ForbricLog.warn("[Forbric/VanillaTwins] %s declares neither vanilla's %s%s nor the overload %s it would forward to"
					+ " — no twin", node.name.replace('/', '.'), row.name(), row.desc(), row.live());
			return false;
		}
		MethodNode declared = reference == null ? null : method(reference, row.name(), row.desc());
		int access = declared != null ? declared.access : row.access();
		boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
		if (isStatic != ((live.access & Opcodes.ACC_STATIC) != 0)) return false;
		MethodNode stub = new MethodNode(access & ~(Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE), row.name(), row.desc(),
				declared != null ? declared.signature : null, declared != null && declared.exceptions != null
						? declared.exceptions.toArray(new String[0]) : null);
		int slot = 0;
		if (!isStatic) stub.instructions.add(new VarInsnNode(Opcodes.ALOAD, slot++));
		Type[] own = Type.getArgumentTypes(row.desc());
		int[] slots = new int[own.length];
		for (int i = 0; i < own.length; i++) { slots[i] = slot; slot += own[i].getSize(); }
		boolean[] used = new boolean[own.length];
		int stack = isStatic ? 0 : 1;
		for (Type wanted : Type.getArgumentTypes(row.live())) {
			int source = -1;
			for (int i = 0; i < own.length && source < 0; i++) if (!used[i] && own[i].equals(wanted)) source = i;
			if (source >= 0) {
				used[source] = true;
				stub.instructions.add(new VarInsnNode(wanted.getOpcode(Opcodes.ILOAD), slots[source]));
			} else {
				stub.instructions.add(defaultValue(wanted));
			}
			stack += wanted.getSize();
		}
		for (boolean taken : used) {
			if (!taken) return false;  // every vanilla argument must reach NeoForge's overload, or this is no stub
		}
		boolean isInterface = (node.access & Opcodes.ACC_INTERFACE) != 0;
		// javac's own choice for each (a private method of a class is invokevirtual since nestmates), so the stub reads
		// exactly as the merged base's forwarding stubs do.
		int opcode = isStatic ? Opcodes.INVOKESTATIC : row.name().equals("<init>") ? Opcodes.INVOKESPECIAL
				: isInterface ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
		stub.instructions.add(new MethodInsnNode(opcode, node.name, row.name(), row.live(), isInterface));
		stub.instructions.add(new InsnNode(Type.getReturnType(row.desc()).getOpcode(Opcodes.IRETURN)));
		stub.maxLocals = slot;
		stub.maxStack = Math.max(stack, Type.getReturnType(row.desc()).getSize());
		node.methods.add(stub);
		return true;
	}

	private boolean addVanillaBody(ClassNode node, Row row, ClassNode reference) {
		if (reference == null) {
			if (missingReferences.add(node.name)) {
				ForbricLog.warn("[Forbric/VanillaTwins] no vanilla reference for %s — vanilla's %s%s cannot be copied, so "
						+ "what names it (%s) finds nothing", node.name.replace('/', '.'), row.name(), row.desc(), row.consumers());
			}
			return false;
		}
		MethodNode original = method(reference, row.name(), row.desc());
		if (original == null || original.instructions.size() == 0) return false;
		Map<String, String> renamed = new HashMap<>();
		List<MethodNode> copies = new ArrayList<>();
		if (!copyWithLambdas(node, reference, original, original.name, copies, renamed, new LinkedHashSet<>())) return false;
		for (MethodNode copy : copies) {
			String unresolved = unresolvedOwnMember(node, reference, copy, copies);
			if (unresolved != null) {
				ForbricLog.warn("[Forbric/VanillaTwins] vanilla's %s.%s%s names %s, which this base's class does not declare "
						+ "— no twin", node.name.replace('/', '.'), row.name(), row.desc(), unresolved);
				return false;
			}
		}
		node.methods.addAll(copies);
		return true;
	}

	/**
	 * Copies {@code method} under {@code as}, and each private lambda of vanilla's class it bootstraps that this class
	 * does not hold with the same body, renaming one whose name and descriptor this class already uses for another body.
	 */
	private static boolean copyWithLambdas(ClassNode node, ClassNode reference, MethodNode method, String as,
			List<MethodNode> copies, Map<String, String> renamed, Set<String> seen) {
		if (!seen.add(method.name + method.desc)) return true;
		MethodNode copy = copy(method, as);
		copies.add(copy);
		for (AbstractInsnNode insn = copy.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (!(insn instanceof InvokeDynamicInsnNode indy)) continue;
			for (int i = 0; i < indy.bsmArgs.length; i++) {
				if (!(indy.bsmArgs[i] instanceof Handle handle) || !handle.getOwner().equals(reference.name)) continue;
				MethodNode lambda = method(reference, handle.getName(), handle.getDesc());
				if (lambda == null || (lambda.access & Opcodes.ACC_SYNTHETIC) == 0) continue;  // a real member: resolved later
				MethodNode present = method(node, handle.getName(), handle.getDesc());
				String key = handle.getName() + handle.getDesc();
				String name = renamed.get(key);
				if (name == null) {
					if (present != null && sameBody(present, lambda)) continue;
					name = present == null ? handle.getName() : "forbric$vanilla$" + handle.getName();
					if (method(node, name, handle.getDesc()) != null) return false;
					renamed.put(key, name);
					if (!copyWithLambdas(node, reference, lambda, name, copies, renamed, seen)) return false;
				}
				indy.bsmArgs[i] = new Handle(handle.getTag(), handle.getOwner(), name, handle.getDesc(), handle.isInterface());
			}
		}
		return true;
	}

	/** The first member of {@code node}'s own class {@code copy} references that resolves nowhere, or null. */
	private static String unresolvedOwnMember(ClassNode node, ClassNode reference, MethodNode copy, List<MethodNode> copies) {
		for (AbstractInsnNode insn = copy.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			String owner = null, name = null, desc = null;
			boolean isField = false;
			if (insn instanceof MethodInsnNode call) { owner = call.owner; name = call.name; desc = call.desc; }
			else if (insn instanceof FieldInsnNode field) { owner = field.owner; name = field.name; desc = field.desc; isField = true; }
			else if (insn instanceof InvokeDynamicInsnNode indy) {
				for (Object argument : indy.bsmArgs) {
					if (argument instanceof Handle handle && handle.getOwner().equals(node.name)
							&& !declares(node, copies, handle.getName(), handle.getDesc(), false)
							&& declares(reference, List.of(), handle.getName(), handle.getDesc(), false)) {
						return handle.getName() + handle.getDesc();
					}
				}
			}
			if (owner == null || !owner.equals(node.name)) continue;
			// Inherited in vanilla too (javac names the receiver's static type): the same supertypes are assumed to serve it.
			if (!declares(reference, List.of(), name, desc, isField)) continue;
			if (!declares(node, copies, name, desc, isField)) return name + (isField ? ":" : "") + desc;
		}
		return null;
	}

	private static boolean declares(ClassNode node, List<MethodNode> extra, String name, String desc, boolean isField) {
		if (isField) return field(node, name, desc) != null;
		if (method(node, name, desc) != null) return true;
		for (MethodNode m : extra) if (m.name.equals(name) && m.desc.equals(desc)) return true;
		return false;
	}

	private static MethodNode copy(MethodNode method, String as) {
		MethodNode copy = new MethodNode(Opcodes.ASM9, method.access, as, method.desc, method.signature,
				method.exceptions == null ? null : method.exceptions.toArray(new String[0]));
		method.accept(copy);
		return copy;
	}

	private static boolean sameBody(MethodNode one, MethodNode other) {
		return text(one).equals(text(other));
	}

	private static String text(MethodNode method) {
		Textifier printer = new Textifier();
		TraceMethodVisitor trace = new TraceMethodVisitor(printer);
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() >= 0) insn.accept(trace);
		}
		StringBuilder out = new StringBuilder();
		for (Object line : printer.getText()) out.append(line);
		return out.toString();
	}

	private static AbstractInsnNode defaultValue(Type type) {
		return switch (type.getSort()) {
			case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> new InsnNode(Opcodes.ICONST_0);
			case Type.LONG -> new InsnNode(Opcodes.LCONST_0);
			case Type.FLOAT -> new InsnNode(Opcodes.FCONST_0);
			case Type.DOUBLE -> new InsnNode(Opcodes.DCONST_0);
			default -> new InsnNode(Opcodes.ACONST_NULL);
		};
	}

	static FieldNode field(ClassNode node, String name, String desc) {
		for (FieldNode f : node.fields) if (f.name.equals(name) && f.desc.equals(desc)) return f;
		return null;
	}

	static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
		return null;
	}

	/** Twins added so far. */
	public int twinsAdded() {
		return twins.get();
	}
}
