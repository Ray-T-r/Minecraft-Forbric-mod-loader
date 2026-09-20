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
 * the field without creating it (Forge's lazy {@code serializeCaps} answers the parked data likewise); loads
 * create it, parking the tag for replay on the first query — Forge's documented lazy semantics.
 *
 * <p>Registered BEFORE the merged-base compat transformer, so its bare-return {@code invalidateCaps/reviveCaps}
 * stubs stand down on their own and stay the fallback for {@code -Dforbric.forgeCapabilities=off}. Each root
 * and call site stands down independently and is counted; the census line says how many landed.
 */
public final class ForgeCapabilityCompositionTransformer implements ClassTransformer {
	public static final String PROPERTY = "forbric.forgeCapabilities";

	static final String ENTITY = "net/minecraft/world/entity/Entity";
	static final String BLOCK_ENTITY = "net/minecraft/world/level/block/entity/BlockEntity";
	static final String LEVEL = "net/minecraft/world/level/Level";
	static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
	static final String LEVEL_CHUNK = "net/minecraft/world/level/chunk/LevelChunk";
	static final Set<String> ROOTS = Set.of(ENTITY, BLOCK_ENTITY, LEVEL);
	static final Set<String> TARGETS = Set.of(ENTITY, BLOCK_ENTITY, LEVEL, SERVER_LEVEL, LEVEL_CHUNK);

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

	private static final Set<String> COMPOSED = Collections.synchronizedSet(new LinkedHashSet<>());
	private static final List<String> REWIRED = Collections.synchronizedList(new ArrayList<>());

	@Override
	public String name() {
		return "forbric-forge-capabilities";
	}

	@Override
	public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("switched off by -D" + PROPERTY);
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
						"every chunk getCapability/invalidateCaps/writeCapsToNBT NPEs on a null capProvider"));
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** The roots composed so far this boot (for the audit that names mods losing the feature). */
	public static Set<String> composedRoots() {
		synchronized (COMPOSED) {
			return Set.copyOf(COMPOSED);
		}
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (!enabled() || classBytes == null || classBytes.length == 0) return classBytes;
		String internal = className.replace('.', '/');
		if (!TARGETS.contains(internal)) return classBytes;
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		boolean changed = false;
		if (ROOTS.contains(internal)) changed = compose(node);
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
		if (SERVER_LEVEL.equals(internal)) changed |= initServerLevelCapabilities(node);
		if (LEVEL_CHUNK.equals(internal)) changed |= initLevelChunkProvider(node);
		if (!changed) return classBytes;
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	// ---- E3: the composition itself

	private static boolean compose(ClassNode node) {
		if (hasMethod(node, "getCapability", GET_CAPABILITY_DESC)) return false;   // rebuilt base, or second pass
		String helper = ENTITY.equals(node.name) ? "entity" : BLOCK_ENTITY.equals(node.name) ? "blockEntity" : "level";
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
		// gatherCapabilities(): creating the provider IS the gather in lazy mode
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
