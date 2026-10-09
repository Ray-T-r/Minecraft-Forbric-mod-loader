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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Composes MinecraftForge's capability provider into the three root types the merge put under NeoForge's
 * {@code AttachmentHolder}, and re-creates the call sites Forge's patches had on them.
 *
 * <p>Java has no multiple inheritance: the merge can never make {@code Entity} both an {@code AttachmentHolder}
 * and a {@code CapabilityProvider}. Composition is the only correct shape and it is Forge's own —
 * {@code LevelChunk} carries a {@code CapabilityProvider$AsField} exactly like this. Per root (Entity,
 * BlockEntity, Level): the {@code ICapabilityProviderImpl} interface, a lazily-written field of the carrier's
 * {@code AsField} type, a synthetic {@code forbric$caps()} accessor whose one branch lives in
 * {@code KernelForgeCapabilities}, and straight-line public delegates for every Forge capability method the
 * merged base or a Forge mod still calls ({@code getCapability(Capability, Direction)}, {@code invalidateCaps},
 * {@code reviveCaps}, {@code gatherCapabilities}, {@code getCapabilities}, {@code serializeCaps},
 * {@code deserializeCaps}, and on BlockEntity {@code serializeCaps(ValueOutput)}). No synthesised method has a
 * branch or a frame: the frame recomputer does not run over these classes.
 *
 * <p>Then the lost hooks, each an anchored, stack-neutral insert at the statement boundary Forge used:
 * {@code ServerLevel.<init>} → {@code initCapabilities()} after {@code LevelAttachmentsSavedData.init};
 * {@code LevelChunk.<init>} → Forge's exact {@code capProvider} sequence (the field is final, so only the
 * constructor may write it); {@code BlockEntity.setRemoved} / {@code Entity.remove} → {@code invalidateCaps};
 * {@code Entity.revive} → {@code reviveCaps}; the {@code "ForgeCaps"} save/load funnels before the single
 * RETURN of {@code BlockEntity.saveAdditional/loadAdditional} and {@code Entity.saveWithoutId/load}. Saves read
 * the field without creating it; loads create the provider and replay through Forge's own deserializer.
 * The verified native constructor gather is restored using the provider's actual eager mode.
 *
 * <p>Registered BEFORE the merged-base compat transformer, so its bare-return {@code invalidateCaps/reviveCaps}
 * stubs stand down on their own and stay the fallback for a root this composition could not reach. Each root
 * and call site stands down independently and is counted; the census line says how many landed.
 *
 * <p>{@code -Dforbric.forgeCapabilities=off} turns off capability DISPATCH, never the composition. The merged
 * base names each root whose stateful superclass it removed ({@code required-ancestor-compositions.tsv}), and the
 * loader refuses to define such a root unless a registered {@code AncestorComposition} proves its final bytes —
 * this transformer is that proof. So with the switch off the roots still get the interface, the field, the
 * accessor and every delegate, and the proof still runs; only the accessor's factory changes, to
 * {@code KernelForgeCapabilities.inert}, whose provider never fires {@code AttachCapabilitiesEvent} and answers
 * every {@code getCapability} empty. The lifecycle call sites (the constructor gather, invalidate/revive, the
 * {@code ForgeCaps} funnels, ServerLevel, LevelChunk, the lost initializers) are dispatch and are not inserted.
 */
public final class ForgeCapabilityCompositionTransformer implements ClassTransformer, net.forbric.api.AncestorComposition {
	public static final String PROPERTY = "forbric.forgeCapabilities";
	private final java.util.function.Function<String, byte[]> resources;
	private final net.forbric.kernel.mixin.NativeGameReferences nativeReferences;
	private final boolean transferFallback;
	private final boolean dispatch;
	private final Map<String, ForgeCapabilityProtocol.Certificate> certificates = new java.util.concurrent.ConcurrentHashMap<>();
	public ForgeCapabilityCompositionTransformer() { this(null); }
	public ForgeCapabilityCompositionTransformer(java.util.function.Function<String, byte[]> resources) { this(resources, false); }
	/** Dispatch follows {@code -Dforbric.forgeCapabilities}, read once, here. */
	public ForgeCapabilityCompositionTransformer(java.util.function.Function<String, byte[]> resources, boolean transferInterop) {
		this(resources, transferInterop, enabled());
	}
	/**
	 * @param transferInterop whether the kernel's transfer bridge is on; Forge's transfer fallback edits the composed
	 *                        {@code getCapability}, so it is expected (and registered) only when dispatch is on too
	 * @param dispatch        whether capabilities are attached and answered; the roots are composed either way
	 */
	public ForgeCapabilityCompositionTransformer(java.util.function.Function<String, byte[]> resources, boolean transferInterop,
			boolean dispatch) {
		this.resources = resources;
		this.dispatch = dispatch;
		this.transferFallback = transferInterop && dispatch;
		this.nativeReferences = resources == null ? null : new net.forbric.kernel.mixin.NativeGameReferences(resources);
	}
	/** A proof of the final definition, which the switch does not change: an inert root is still a composed one. */
	@Override public boolean proves(net.forbric.api.AncestorComposition.Requirement requirement, byte[] finalDefinition,
			java.util.function.Function<String, byte[]> reader) {
		ForgeCapabilityProtocol.Certificate certificate = certificates.get(requirement.owner());
		return certificate != null && certificate.proves(requirement, finalDefinition, reader);
	}
	/** Whether capabilities are attached and answered (false under {@code -Dforbric.forgeCapabilities=off}). */
	public boolean dispatches() {
		return dispatch;
	}
	/** Whether this instance expects {@link ForgeTransferCapabilityFallback} after it; the kernel registers it iff so. */
	public boolean transferFallback() {
		return transferFallback;
	}

	static final String ENTITY = "net/minecraft/world/entity/Entity";
	static final String BLOCK_ENTITY = "net/minecraft/world/level/block/entity/BlockEntity";
	static final String LEVEL = "net/minecraft/world/level/Level";
	static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
	static final String LEVEL_CHUNK = "net/minecraft/world/level/chunk/LevelChunk";
	static final String LIVING_ENTITY = "net/minecraft/world/entity/LivingEntity";
	static final String ABSTRACT_FURNACE = "net/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity";
	static final String CHISELED_BOOKSHELF = "net/minecraft/world/level/block/entity/ChiseledBookShelfBlockEntity";
	static final Set<String> ROOTS = Set.of(ENTITY, BLOCK_ENTITY, LEVEL);
	/**
	 * MinecraftForge-patched classes whose capability field lost its CONSTRUCTOR initializer to the merge while
	 * keeping the field, its readers and its {@code reviveCaps()} re-creation. NeoForge won each constructor whole,
	 * and the loser's field initializers went with it — so {@code LivingEntity.handlers} was null from construction
	 * until a revive that never comes, {@code invalidateCaps} NPE'd on every death once {@code Entity.remove} called
	 * it again, and {@code getCapability(ITEM_HANDLER)} NPE'd on every ask. The census in the test scans the whole
	 * merged base for this shape (a field assigned in {@code reviveCaps} but in no {@code <init>}) and pins this map
	 * to exactly what it finds.
	 */
	static final Map<String, String> LOST_INITIALIZERS = Map.of(
			LIVING_ENTITY, "handlers",
			ABSTRACT_FURNACE, "handlers",
			CHISELED_BOOKSHELF, "itemHandler");
	static final Set<String> TARGETS = Set.of(ENTITY, BLOCK_ENTITY, LEVEL, SERVER_LEVEL, LEVEL_CHUNK,
			LIVING_ENTITY, ABSTRACT_FURNACE, CHISELED_BOOKSHELF);

	/** Forge-only names, like the fluid-type bridge's: NeoForge has no provider superclass to pair them with. */
	static final String AS_FIELD = "net/minecraftforge/common/capabilities/CapabilityProvider$AsField";
	static final String AS_FIELD_DESC = "L" + AS_FIELD + ";";
	static final String LEVEL_CHUNKS_PROVIDER = "net/minecraftforge/common/capabilities/CapabilityProvider$AsField$LevelChunks";
	static final String PROVIDER_IMPL = "net/minecraftforge/common/capabilities/ICapabilityProviderImpl";
	static final String CAPABILITY = "Lnet/minecraftforge/common/capabilities/Capability;";
	static final String LAZY_OPTIONAL = "Lnet/minecraftforge/common/util/LazyOptional;";
	static final String DISPATCHER = "Lnet/minecraftforge/common/capabilities/CapabilityDispatcher;";
	static final String GET_CAPABILITY_DESC = "(" + CAPABILITY + "Lnet/minecraft/core/Direction;)" + LAZY_OPTIONAL;
	static final String HOLDER_LOOKUP = "Lnet/minecraft/core/HolderLookup$Provider;";
	static final String COMPOUND_TAG = "Lnet/minecraft/nbt/CompoundTag;";
	static final String VALUE_OUTPUT = "Lnet/minecraft/world/level/storage/ValueOutput;";
	static final String VALUE_INPUT = "Lnet/minecraft/world/level/storage/ValueInput;";
	static final String RUNTIME = "net/forbric/kernel/runtime/KernelForgeCapabilities";
	static final String FIELD = "forbric$forgeCaps";
	static final String ACCESSOR = "forbric$caps";
	static final String ACCESSOR_DESC = "()" + AS_FIELD_DESC;
	/** {@code KernelForgeCapabilities.inert}: the accessor's factory when dispatch is off. */
	static final String INERT_FACTORY = "inert";
	private static final String INERT_COST = "the root's merged definition requires the composed provider state, and "
			+ "without it the loader refuses to define the class at all";

	private static final Set<String> COMPOSED = Collections.synchronizedSet(new LinkedHashSet<>());
	private static final List<String> REWIRED = Collections.synchronizedList(new ArrayList<>());

	@Override
	public String name() {
		return "forbric-forge-capabilities";
	}

	@Override
	public AnchorSet anchors() {
		if (!dispatch) return AnchorSet.of(
				new AnchorSet.Anchor(ENTITY.replace('/', '.'), AnchorSet.Severity.REQUIRED, INERT_COST),
				new AnchorSet.Anchor(BLOCK_ENTITY.replace('/', '.'), AnchorSet.Severity.REQUIRED, INERT_COST),
				new AnchorSet.Anchor(LEVEL.replace('/', '.'), AnchorSet.Severity.REQUIRED, INERT_COST));
		return AnchorSet.of(
				new AnchorSet.Anchor(ENTITY.replace('/', '.'), AnchorSet.Severity.REQUIRED,
						"no Forge mod can attach or read a capability on an entity; Forge-patched players and horses link to nothing"),
				new AnchorSet.Anchor(BLOCK_ENTITY.replace('/', '.'), AnchorSet.Severity.REQUIRED,
						"no Forge mod can attach or read a capability on a block entity; Forge-patched chests and furnaces link to nothing"),
				new AnchorSet.Anchor(LEVEL.replace('/', '.'), AnchorSet.Severity.REQUIRED,
						"Level.getCapability is abstract on every merged level — AbstractMethodError for any Forge mod asking"),
				new AnchorSet.Anchor(SERVER_LEVEL.replace('/', '.'), AnchorSet.Severity.REQUIRED,
						"level capabilities are never gathered and forge:capabilities saved data is never created"),
				new AnchorSet.Anchor(LEVEL_CHUNK.replace('/', '.'), AnchorSet.Severity.REQUIRED,
						"every chunk getCapability/invalidateCaps/writeCapsToNBT NPEs on a null capProvider"),
				new AnchorSet.Anchor(LIVING_ENTITY.replace('/', '.'), AnchorSet.Severity.REQUIRED,
						"LivingEntity.handlers stays null: every mob death NPEs in invalidateCaps and every ITEM_HANDLER ask on a living entity NPEs"),
				new AnchorSet.Anchor(ABSTRACT_FURNACE.replace('/', '.'), AnchorSet.Severity.REQUIRED,
						"furnace.handlers stays null: every Forge pipe or hopper asking a furnace for ITEM_HANDLER NPEs"),
				new AnchorSet.Anchor(CHISELED_BOOKSHELF.replace('/', '.'), AnchorSet.Severity.REQUIRED,
						"bookshelf.itemHandler stays null: every Forge ITEM_HANDLER ask on a chiseled bookshelf NPEs"));
	}

	/** The switch: whether capability dispatch is on. It never decides whether the roots are composed. */
	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** The roots composed WITH dispatch so far this boot (for the audit that names mods losing the feature). */
	public static Set<String> composedRoots() {
		synchronized (COMPOSED) {
			return Set.copyOf(COMPOSED);
		}
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		String internal = className.replace('.', '/');
		// Off: only the roots, whose definition requires the composition. Every other target here is dispatch.
		if (!(dispatch ? TARGETS : ROOTS).contains(internal)) return classBytes;
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		ClassNode before = new ClassNode();
		new ClassReader(classBytes).accept(before, 0);
		boolean changed = false;
		if (ROOTS.contains(internal)) changed = compose(node, dispatch);
		if (dispatch) changed |= dispatchSites(node, internal);
		if (!changed) return classBytes;
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		if (ROOTS.contains(internal) && nativeReferences != null) {
			ClassNode original = nativeReferences.get(net.forbric.api.Ecosystem.FORGE, internal);
			ClassNode expected = node;
			if (transferFallback) {
				expected = new ClassNode();
				byte[] extended = new ForgeTransferCapabilityFallback().transform(className, writer.toByteArray(), context);
				new ClassReader(extended).accept(expected, 0);
			}
			ForgeCapabilityProtocol.Certificate certificate = ForgeCapabilityProtocol.certificate(before, expected, original, resources);
			if (certificate != null) certificates.put(internal, certificate);
		}
		return writer.toByteArray();
	}

	/** The call sites that attach, answer, invalidate and persist capabilities: dispatch, so only when it is on. */
	private boolean dispatchSites(ClassNode node, String internal) {
		boolean changed = false;
		if (BLOCK_ENTITY.equals(internal)) {
			changed |= insertAfterCall(node, "setRemoved", "()V", BLOCK_ENTITY, "invalidateCapabilities", "()V",
					callOnThis(BLOCK_ENTITY, "invalidateCaps"), "BlockEntity.setRemoved -> invalidateCaps");
			changed |= saveFunnel(node, "saveAdditional", "(" + VALUE_OUTPUT + ")V", "saveBlockEntity", "BlockEntity.saveAdditional -> ForgeCaps");
			changed |= loadFunnel(node, "loadAdditional", "(" + VALUE_INPUT + ")V", "BlockEntity.loadAdditional <- ForgeCaps");
		}
		if (ENTITY.equals(internal)) {
			changed |= insertAfterCall(node, "remove", "(Lnet/minecraft/world/entity/Entity$RemovalReason;)V", ENTITY, "setRemoved",
					"(Lnet/minecraft/world/entity/Entity$RemovalReason;)V", callOnThis(ENTITY, "invalidateCaps"), "Entity.remove -> invalidateCaps");
			changed |= insertAfterCall(node, "revive", "()V", ENTITY, "unsetRemoved", "()V", callOnThis(ENTITY, "reviveCaps"),
					"Entity.revive -> reviveCaps");
			changed |= saveFunnel(node, "saveWithoutId", "(" + VALUE_OUTPUT + ")V", "saveEntity", "Entity.saveWithoutId -> ForgeCaps");
			changed |= loadFunnel(node, "load", "(" + VALUE_INPUT + ")V", "Entity.load <- ForgeCaps");
		}
		if (ROOTS.contains(internal) && nativeReferences != null) {
			ClassNode original = nativeReferences.get(net.forbric.api.Ecosystem.FORGE, internal);
			changed |= ForgeCapabilityProtocol.restoreConstructorGather(node, original);
		}
		if (SERVER_LEVEL.equals(internal)) changed |= initServerLevelCapabilities(node);
		if (LEVEL_CHUNK.equals(internal)) changed |= initLevelChunkProvider(node);
		String lost = LOST_INITIALIZERS.get(internal);
		if (lost != null) changed |= replayLostInitializer(node, lost);
		return changed;
	}

	// ---- E3: the composition itself

	private static boolean compose(ClassNode node, boolean dispatch) {
		if (hasMethod(node, "getCapability", GET_CAPABILITY_DESC)) return false;   // rebuilt base, or second pass
		// The accessor's factory is the one thing the switch changes here: the inert provider never gathers.
		String helper = !dispatch ? INERT_FACTORY
				: ENTITY.equals(node.name) ? "entity" : BLOCK_ENTITY.equals(node.name) ? "blockEntity" : "level";
		if (!node.interfaces.contains(PROVIDER_IMPL)) node.interfaces.add(PROVIDER_IMPL);
		if (!hasField(node, FIELD)) node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, FIELD, AS_FIELD_DESC, null, null));
		int added = 0;

		// forbric$caps(): ALOAD 0; ALOAD 0; GETFIELD; ALOAD 0; INVOKESTATIC helper; DUP_X1; PUTFIELD; ARETURN
		MethodNode accessor = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, ACCESSOR, ACCESSOR_DESC, null, null);
		accessor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		accessor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		accessor.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, FIELD, AS_FIELD_DESC));
		accessor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		accessor.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, helper,
				"(" + AS_FIELD_DESC + "Ljava/lang/Object;)" + AS_FIELD_DESC, false));
		accessor.instructions.add(new InsnNode(Opcodes.DUP_X1));
		accessor.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, FIELD, AS_FIELD_DESC));
		accessor.instructions.add(new InsnNode(Opcodes.ARETURN));
		accessor.maxStack = 3;
		accessor.maxLocals = 1;
		node.methods.add(accessor);
		added++;

		// getCapability(Capability, Direction): forbric$caps().getCapability(cap, side)
		added += add(node, "getCapability", GET_CAPABILITY_DESC, 3, 3,
				new VarInsnNode(Opcodes.ALOAD, 0),
				new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, ACCESSOR, ACCESSOR_DESC, false),
				new VarInsnNode(Opcodes.ALOAD, 1), new VarInsnNode(Opcodes.ALOAD, 2),
				new MethodInsnNode(Opcodes.INVOKEVIRTUAL, AS_FIELD, "getCapability", GET_CAPABILITY_DESC, false),
				new InsnNode(Opcodes.ARETURN));
		// invalidateCaps / reviveCaps: read the field WITHOUT creating it
		for (String name : new String[] { "invalidateCaps", "reviveCaps" }) {
			added += add(node, name, "()V", 1, 1,
					new VarInsnNode(Opcodes.ALOAD, 0),
					new FieldInsnNode(Opcodes.GETFIELD, node.name, FIELD, AS_FIELD_DESC),
					new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "invalidateCaps".equals(name) ? "invalidate" : "revive",
							"(" + AS_FIELD_DESC + ")V", false),
					new InsnNode(Opcodes.RETURN));
		}
		// gatherCapabilities(): creating and initializing the eager provider performs the native gather
		added += add(node, "gatherCapabilities", "()V", 1, 1,
				new VarInsnNode(Opcodes.ALOAD, 0),
				new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, ACCESSOR, ACCESSOR_DESC, false),
				new InsnNode(Opcodes.POP), new InsnNode(Opcodes.RETURN));
		// getCapabilities(): the dispatcher
		added += add(node, "getCapabilities", "()" + DISPATCHER, 1, 1,
				new VarInsnNode(Opcodes.ALOAD, 0),
				new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, ACCESSOR, ACCESSOR_DESC, false),
				new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "dispatcher", "(" + AS_FIELD_DESC + ")" + DISPATCHER, false),
				new InsnNode(Opcodes.ARETURN));
		// serializeCaps(HolderLookup.Provider): read without creating
		added += add(node, "serializeCaps", "(" + HOLDER_LOOKUP + ")" + COMPOUND_TAG, 2, 2,
				new VarInsnNode(Opcodes.ALOAD, 0),
				new FieldInsnNode(Opcodes.GETFIELD, node.name, FIELD, AS_FIELD_DESC),
				new VarInsnNode(Opcodes.ALOAD, 1),
				new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "serialize", "(" + AS_FIELD_DESC + HOLDER_LOOKUP + ")" + COMPOUND_TAG, false),
				new InsnNode(Opcodes.ARETURN));
		// deserializeCaps(HolderLookup.Provider, CompoundTag): creating, then Forge's own replay
		added += add(node, "deserializeCaps", "(" + HOLDER_LOOKUP + COMPOUND_TAG + ")V", 3, 3,
				new VarInsnNode(Opcodes.ALOAD, 0),
				new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, ACCESSOR, ACCESSOR_DESC, false),
				new VarInsnNode(Opcodes.ALOAD, 1), new VarInsnNode(Opcodes.ALOAD, 2),
				new MethodInsnNode(Opcodes.INVOKEVIRTUAL, AS_FIELD, "deserializeInternal", "(" + HOLDER_LOOKUP + COMPOUND_TAG + ")V", false),
				new InsnNode(Opcodes.RETURN));
		if (BLOCK_ENTITY.equals(node.name)) {
			added += add(node, "serializeCaps", "(" + VALUE_OUTPUT + ")" + COMPOUND_TAG, 3, 2,
					new VarInsnNode(Opcodes.ALOAD, 0),
					new FieldInsnNode(Opcodes.GETFIELD, node.name, FIELD, AS_FIELD_DESC),
					new VarInsnNode(Opcodes.ALOAD, 1), new VarInsnNode(Opcodes.ALOAD, 0),
					new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "serializeBlockEntity",
							"(" + AS_FIELD_DESC + VALUE_OUTPUT + "Ljava/lang/Object;)" + COMPOUND_TAG, false),
					new InsnNode(Opcodes.ARETURN));
		}
		if (!dispatch) {
			ForbricLog.info("[Forbric/Capabilities] gave %s the composed MinecraftForge provider state its merged definition "
					+ "requires (+%d method(s)), with dispatch off by -D%s=off: no AttachCapabilitiesEvent is fired and every "
					+ "getCapability on it answers empty", node.name.replace('/', '.'), added, PROPERTY);
			return true;
		}
		COMPOSED.add(node.name);
		ForbricLog.info("[Forbric/Capabilities] composed MinecraftForge capabilities into %s: +%d method(s) — the merge put "
				+ "it under NeoForge's AttachmentHolder, so a Forge mod could neither attach nor read a capability on it",
				node.name.replace('/', '.'), added);
		return true;
	}

	private static int add(ClassNode node, String name, String desc, int maxStack, int maxLocals, AbstractInsnNode... body) {
		if (hasMethod(node, name, desc)) return 0;
		MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null);
		for (AbstractInsnNode insn : body) method.instructions.add(insn);
		method.maxStack = maxStack;
		method.maxLocals = maxLocals;
		node.methods.add(method);
		return 1;
	}

	// ---- E4: ServerLevel.<init> -> initCapabilities after LevelAttachmentsSavedData.init

	private static boolean initServerLevelCapabilities(ClassNode node) {
		int inits = 0;
		for (MethodNode method : node.methods) {
			if (!"<init>".equals(method.name)) continue;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof MethodInsnNode call && "initCapabilities".equals(call.name) && SERVER_LEVEL.equals(call.owner)) return false;
			}
		}
		MethodInsnNode anchor = null;
		MethodNode ctor = null;
		for (MethodNode method : node.methods) {
			if (!"<init>".equals(method.name)) continue;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
						&& "net/neoforged/neoforge/attachment/LevelAttachmentsSavedData".equals(call.owner) && "init".equals(call.name)) {
					inits++;
					anchor = call;
					ctor = method;
				}
			}
		}
		if (inits != 1 || !hasMethod(node, "initCapabilities", "()V")) {
			ForbricLog.warn("[Forbric/Capabilities] ServerLevel.<init> has %d LevelAttachmentsSavedData.init anchor(s) and "
					+ "initCapabilities %s — level capabilities are not re-created", inits, hasMethod(node, "initCapabilities", "()V") ? "present" : "absent");
			return false;
		}
		ctor.instructions.insert(anchor, callOnThis(SERVER_LEVEL, "initCapabilities"));
		ctor.maxStack = Math.max(ctor.maxStack, 1);
		REWIRED.add("ServerLevel.<init> -> initCapabilities");
		ForbricLog.info("[Forbric/Capabilities] ServerLevel.<init> gathers MinecraftForge level capabilities again (1 call site) "
				+ "— Forge's own call at the constructor's end was lost in the merge while initCapabilities itself survived");
		return true;
	}

	// ---- E5: LevelChunk.<init> writes Forge's AsField$LevelChunks into its final capProvider

	private static boolean initLevelChunkProvider(ClassNode node) {
		if (!hasField(node, "capProvider")) return false;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD && "capProvider".equals(field.name)) return false;
			}
		}
		String desc = "(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/chunk/UpgradeData;"
				+ "Lnet/minecraft/world/ticks/LevelChunkTicks;Lnet/minecraft/world/ticks/LevelChunkTicks;J[Lnet/minecraft/world/level/chunk/LevelChunkSection;"
				+ "Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;Lnet/minecraft/world/level/levelgen/blending/BlendingData;)V";
		MethodNode ctor = findMethod(node, "<init>", desc);
		if (ctor == null) {
			ForbricLog.warn("[Forbric/Capabilities] LevelChunk's main constructor has a different descriptor — capProvider stays null");
			return false;
		}
		FieldInsnNode anchor = null;
		int anchors = 0, returns = 0;
		for (AbstractInsnNode insn = ctor.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD && "unsavedListener".equals(field.name)) {
				anchors++;
				anchor = field;
			}
			if (insn.getOpcode() == Opcodes.RETURN) returns++;
		}
		if (anchors != 1 || returns != 1) {
			ForbricLog.warn("[Forbric/Capabilities] LevelChunk.<init> has %d unsavedListener store(s) and %d return(s), not one each — "
					+ "capProvider stays null", anchors, returns);
			return false;
		}
		InsnList create = new InsnList();
		create.add(new VarInsnNode(Opcodes.ALOAD, 0));
		create.add(new TypeInsnNode(Opcodes.NEW, LEVEL_CHUNKS_PROVIDER));
		create.add(new InsnNode(Opcodes.DUP));
		create.add(new VarInsnNode(Opcodes.ALOAD, 0));
		create.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, LEVEL_CHUNKS_PROVIDER, "<init>", "(L" + LEVEL_CHUNK + ";)V", false));
		create.add(new FieldInsnNode(Opcodes.PUTFIELD, LEVEL_CHUNK, "capProvider", "L" + LEVEL_CHUNKS_PROVIDER + ";"));
		ctor.instructions.insert(anchor, create);
		for (AbstractInsnNode insn = ctor.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() != Opcodes.RETURN) continue;
			InsnList init = new InsnList();
			init.add(new VarInsnNode(Opcodes.ALOAD, 0));
			init.add(new FieldInsnNode(Opcodes.GETFIELD, LEVEL_CHUNK, "capProvider", "L" + LEVEL_CHUNKS_PROVIDER + ";"));
			init.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LEVEL_CHUNKS_PROVIDER, "initInternal", "()V", false));
			ctor.instructions.insertBefore(insn, init);
			break;
		}
		ctor.maxStack = Math.max(ctor.maxStack, 4);
		REWIRED.add("LevelChunk.<init> -> capProvider");
		ForbricLog.info("[Forbric/Capabilities] LevelChunk.<init> writes Forge's own capability provider again (1 constructor) — "
				+ "the merged constructor left the final capProvider null, so every chunk capability call NPE'd");
		return true;
	}

	// ---- E6: invalidate/revive call sites; E7: ForgeCaps save/load funnels

	// ---- E7: replay a field initializer the merge dropped from the constructor

	/**
	 * Copies the assignment {@code reviveCaps()} makes to {@code field} into every constructor that calls
	 * {@code super(...)}, right after that call — where Forge's own field initializer ran before the merge.
	 *
	 * <p>{@code reviveCaps} is the one place the loser's initializer expression survived verbatim ({@code this.f =
	 * <factory>(this, ...)}); it is what MinecraftForge runs to re-create the field after a revive, so it is exactly
	 * the constructor's expression. Only a straight-line sequence is copied (no label, jump or frame between the
	 * super {@code reviveCaps} call and the {@code PUTFIELD}); the constructor's stack budget is raised to
	 * {@code reviveCaps}' own, which ran the same instructions from the same empty stack. Constructors that delegate
	 * with {@code this(...)} are left alone: the delegate assigns. A field already assigned in some constructor is
	 * the second pass, or a base that has stopped losing it — either way nothing to do.
	 */
	static boolean replayLostInitializer(ClassNode node, String field) {
		for (MethodNode ctor : node.methods) {
			if ("<init>".equals(ctor.name) && assigns(ctor, node.name, field)) return false;
		}
		MethodNode revive = findMethod(node, "reviveCaps", "()V");
		if (revive == null) {
			ForbricLog.warn("[Forbric/Capabilities] %s has no reviveCaps() to copy %s's initializer from — not repaired",
					node.name.replace('/', '.'), field);
			return false;
		}
		// The sequence: from the instruction after the super reviveCaps call to the PUTFIELD, straight-line.
		AbstractInsnNode start = null;
		FieldInsnNode put = null;
		for (AbstractInsnNode insn = revive.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (start == null && insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
					&& "reviveCaps".equals(call.name)) {
				start = call.getNext();
				continue;
			}
			if (start != null && insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD
					&& node.name.equals(f.owner) && field.equals(f.name)) {
				put = f;
				break;
			}
		}
		if (start == null || put == null) {
			ForbricLog.warn("[Forbric/Capabilities] %s.reviveCaps() no longer assigns %s after its super call — not repaired",
					node.name.replace('/', '.'), field);
			return false;
		}
		// Labels, line numbers and frames are not instructions and are not copied; a label that something JUMPS to
		// means control can enter the sequence sideways, which is the one thing that makes it not an initializer.
		Set<LabelNode> jumpedTo = new java.util.HashSet<>();
		for (AbstractInsnNode insn = revive.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof JumpInsnNode jump) jumpedTo.add(jump.label);
			if (insn instanceof TableSwitchInsnNode sw) { jumpedTo.add(sw.dflt); jumpedTo.addAll(sw.labels); }
			if (insn instanceof LookupSwitchInsnNode sw) { jumpedTo.add(sw.dflt); jumpedTo.addAll(sw.labels); }
		}
		if (revive.tryCatchBlocks != null) {
			for (TryCatchBlockNode block : revive.tryCatchBlocks) jumpedTo.add(block.handler);
		}
		List<AbstractInsnNode> sequence = new ArrayList<>();
		for (AbstractInsnNode insn = start; insn != put.getNext(); insn = insn.getNext()) {
			int op = insn.getOpcode();
			boolean sideways = insn instanceof LabelNode label && jumpedTo.contains(label);
			if (sideways || insn instanceof JumpInsnNode || insn instanceof TableSwitchInsnNode
					|| insn instanceof LookupSwitchInsnNode || op == Opcodes.ATHROW
					|| (op >= Opcodes.IRETURN && op <= Opcodes.RETURN)) {
				ForbricLog.warn("[Forbric/Capabilities] %s.reviveCaps() assigns %s through a branch — not a straight-line "
						+ "initializer, not repaired", node.name.replace('/', '.'), field);
				return false;
			}
			if (op < 0) continue;
			sequence.add(insn);
		}
		if (!(sequence.get(0) instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != 0) {
			ForbricLog.warn("[Forbric/Capabilities] %s.reviveCaps() does not assign %s from `this` — not repaired",
					node.name.replace('/', '.'), field);
			return false;
		}

		int ctors = 0;
		for (MethodNode ctor : node.methods) {
			if (!"<init>".equals(ctor.name)) continue;
			MethodInsnNode superCall = null;
			for (AbstractInsnNode insn = ctor.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
						&& "<init>".equals(call.name) && (node.superName.equals(call.owner) || node.name.equals(call.owner))) {
					superCall = call;
					break;
				}
			}
			if (superCall == null || node.name.equals(superCall.owner)) continue;    // this(...) delegates
			InsnList copy = new InsnList();
			for (AbstractInsnNode insn : sequence) copy.add(insn.clone(Map.of()));
			ctor.instructions.insert(superCall, copy);
			ctor.maxStack = Math.max(ctor.maxStack, revive.maxStack);
			ctors++;
		}
		if (ctors == 0) {
			ForbricLog.warn("[Forbric/Capabilities] %s has no constructor calling super(...) — %s's initializer not replayed",
					node.name.replace('/', '.'), field);
			return false;
		}
		REWIRED.add(node.name.replace('/', '.') + ".<init> assigns " + field);
		ForbricLog.info("[Forbric/Capabilities] %s.<init> assigns %s again (%d constructor(s)) — the merge kept "
				+ "MinecraftForge's field, its readers and its reviveCaps() re-creation but dropped the constructor "
				+ "initializer, so it read null from construction on: invalidateCaps NPE'd on every removal and "
				+ "getCapability(ITEM_HANDLER) on every ask", node.name.replace('/', '.'), field, ctors);
		return true;
	}

	private static boolean assigns(MethodNode method, String owner, String field) {
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && owner.equals(f.owner)
					&& field.equals(f.name)) {
				return true;
			}
		}
		return false;
	}

	private static InsnList callOnThis(String owner, String name) {
		InsnList list = new InsnList();
		list.add(new VarInsnNode(Opcodes.ALOAD, 0));
		list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, name, "()V", false));
		return list;
	}

	private static boolean insertAfterCall(ClassNode node, String method, String desc, String anchorOwner, String anchorName,
			String anchorDesc, InsnList insert, String label) {
		MethodNode target = findMethod(node, method, desc);
		if (target == null) return false;
		String inserted = ((MethodInsnNode) insert.getLast()).name;
		MethodInsnNode anchor = null;
		int anchors = 0;
		for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (!(insn instanceof MethodInsnNode call) || !node.name.equals(call.owner) && !anchorOwner.equals(call.owner)) continue;
			if (inserted.equals(call.name) && "()V".equals(call.desc)) return false;    // already there
			if (anchorName.equals(call.name) && anchorDesc.equals(call.desc)) {
				anchors++;
				anchor = call;
			}
		}
		if (anchors != 1) {
			ForbricLog.warn("[Forbric/Capabilities] %s: %d anchor(s), not one — not rewired", label, anchors);
			return false;
		}
		target.instructions.insert(anchor, insert);
		target.maxStack = Math.max(target.maxStack, 1);
		REWIRED.add(label);
		return true;
	}

	private static boolean saveFunnel(ClassNode node, String method, String desc, String helper, String label) {
		MethodNode target = findMethod(node, method, desc);
		if (target == null || callsRuntime(target)) return false;
		int returns = 0;
		for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() != Opcodes.RETURN) continue;
			InsnList save = new InsnList();
			save.add(new VarInsnNode(Opcodes.ALOAD, 0));
			save.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, FIELD, AS_FIELD_DESC));   // read, never create
			save.add(new VarInsnNode(Opcodes.ALOAD, 1));
			save.add(new VarInsnNode(Opcodes.ALOAD, 0));
			save.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, helper, "(" + AS_FIELD_DESC + VALUE_OUTPUT + "Ljava/lang/Object;)V", false));
			target.instructions.insertBefore(insn, save);
			returns++;
		}
		if (returns == 0) return false;
		target.maxStack = Math.max(target.maxStack, 3);
		REWIRED.add(label);
		return true;
	}

	private static boolean loadFunnel(ClassNode node, String method, String desc, String label) {
		MethodNode target = findMethod(node, method, desc);
		if (target == null || callsRuntime(target)) return false;
		int returns = 0;
		for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() != Opcodes.RETURN) continue;
			InsnList load = new InsnList();
			load.add(new VarInsnNode(Opcodes.ALOAD, 0));
			load.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, ACCESSOR, ACCESSOR_DESC, false));   // creating
			load.add(new VarInsnNode(Opcodes.ALOAD, 1));
			load.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "load", "(" + AS_FIELD_DESC + VALUE_INPUT + ")V", false));
			target.instructions.insertBefore(insn, load);
			returns++;
		}
		if (returns == 0) return false;
		target.maxStack = Math.max(target.maxStack, 2);
		REWIRED.add(label);
		return true;
	}

	private static boolean callsRuntime(MethodNode method) {
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode call && RUNTIME.equals(call.owner)) return true;
		}
		return false;
	}

	/** For the census line: every call site rewired this boot. */
	public static List<String> rewired() {
		synchronized (REWIRED) {
			return List.copyOf(REWIRED);
		}
	}

	private static boolean hasMethod(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) if (m.name.equals(name) && m.desc.equals(desc)) return true;
		return false;
	}

	private static boolean hasField(ClassNode node, String name) {
		for (FieldNode f : node.fields) if (f.name.equals(name)) return true;
		return false;
	}

	private static MethodNode findMethod(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
		return null;
	}
}
