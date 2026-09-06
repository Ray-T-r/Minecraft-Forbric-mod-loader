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

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Lets NeoForge's registry sync remap the registries that MinecraftForge wraps.
 *
 * <p>On the merged base, {@code BuiltInRegistries.BLOCK}, {@code ITEM}, {@code ENTITY_TYPE} and fourteen more are
 * {@code net.minecraftforge.registries.NamespacedWrapper}s — the {@code MappedRegistry} subclass that makes a Forge
 * mod's {@code ForgeRegistries.BLOCKS} and vanilla's registry ONE store. It does that by delegating every lookup
 * to its {@code ForgeRegistry}; the {@code MappedRegistry} fields it inherits ({@code byId}, {@code toId},
 * {@code byKey}) stay empty for life.
 *
 * <p>NeoForge's {@code RegistryManager.applySnapshot} — the client half of its configuration-phase registry sync —
 * remaps a registry to the server's ids through exactly those fields: {@code unfreeze(false)}, {@code clear(false)},
 * {@code registerIdMapping(key, id)} per entry, {@code freeze()}. Two of the four are NeoForge additions the wrapper
 * never heard of, so it inherits the merged {@code MappedRegistry}'s versions, and {@code registerIdMapping} does
 * {@code byKey.get(key).value()} on the empty map: {@code NullPointerException: "holder" is null}, and the client
 * is dropped with "Failed to sync registries from the server". The check right before it, {@code containsKey(key)},
 * passed — the wrapper overrides THAT one to ask the delegate. gate-m12 is the first thing that ever ran this path;
 * singleplayer syncs nothing.
 *
 * <p>Forge has its own remap path for the same job — {@code GameData.injectSnapshot}, what a Forge client runs on
 * login: {@code ForgeRegistry.sync} re-adds every entry at its new id and {@code NamespacedWrapper.onAdded} re-indexes
 * the wrapper's holders, reusing the same {@code Holder.Reference} objects so nothing that captured one goes stale.
 * So this injector gives the wrapper NeoForge's contract and routes it there:
 * <ul>
 *   <li>{@code clear(boolean)} and {@code registerIdMapping(ResourceKey, int)} are ADDED to {@code NamespacedWrapper}
 *       (inherited by {@code NamespacedDefaultedWrapper}), forwarding to {@code KernelForgeWrapperSync}, which only
 *       STAGES the server's ids per registry;</li>
 *   <li>{@code RegistryManager.applySnapshot(Map, boolean)} gets a prologue that resets the staging and, before each
 *       return, a call that FLUSHES it — one {@code injectSnapshot} for every staged registry, then a rebuild of the
 *       block-state id map, which on the merged base is NeoForge's — unless the snapshot had missing entries, in which
 *       case NeoForge is about to disconnect and the Forge registries are left untouched.</li>
 * </ul>
 * The wrapper's own {@code freeze()} returns early while its {@code frozen} flag is set, and NeoForge's
 * {@code unfreeze(false)} clears the INHERITED flag, not that one — so NeoForge's per-registry freeze is a no-op
 * here, and the flush after the loop is the only write.
 *
 * <p>Frame-safe by construction: the added methods are straight-line, and the {@code applySnapshot} splices add no
 * branch target — a static call at the head, and {@code DUP; INVOKESTATIC} in front of an {@code ARETURN} whose
 * operand stays on the stack. {@code COMPUTE_MAXS} for the extra stack slot.
 */
public final class RegistrySyncParityInjector implements ClassTransformer {
	private static final String WRAPPER = "net.minecraftforge.registries.NamespacedWrapper";
	private static final String NEO_REGISTRY_MANAGER = "net.neoforged.neoforge.registries.RegistryManager";
	private static final String FABRIC_CLIENT_SYNC = "net.fabricmc.fabric.impl.client.registry.sync.ClientRegistrySyncHandler";

	private static final String HOOK_OWNER = "net/forbric/kernel/boot/KernelForgeWrapperSync";
	private static final String RESOURCE_KEY = "Lnet/minecraft/resources/ResourceKey;";

	private static final String REGISTER_ID_MAPPING = "registerIdMapping";
	private static final String REGISTER_ID_MAPPING_DESC = "(" + RESOURCE_KEY + "I)V";
	private static final String CLEAR = "clear";
	private static final String CLEAR_DESC = "(Z)V";

	private static final String APPLY_SNAPSHOT = "applySnapshot";
	private static final String APPLY_SNAPSHOT_DESC = "(Ljava/util/Map;Z)Ljava/util/Set;";
	private static final String REVERT_TO_FROZEN = "revertToFrozen";
	private static final String REVERT_OWNER = "net/forbric/kernel/boot/KernelRegistryRevert";

	/** fabric-api's {@code RemappableRegistry.remap}, which its mixin adds to {@code MappedRegistry} and the wrapper inherits. */
	private static final String REMAP = "remap";
	private static final String REMAP_DESC =
			"(Lit/unimi/dsi/fastutil/objects/Object2IntMap;Lnet/fabricmc/fabric/impl/registry/sync/RemappableRegistry$RemapMode;)V";
	private static final String FABRIC_APPLY = "apply";
	private static final String FABRIC_APPLY_DESC = "(Lnet/fabricmc/fabric/impl/registry/sync/packet/RegistrySyncPayload;)V";

	@Override
	public String name() {
		return "forbric-registry-sync-parity";
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		if (WRAPPER.equals(className)) return giveWrapperBothContracts(className, classBytes);
		if (NEO_REGISTRY_MANAGER.equals(className)) return flushAroundApplySnapshot(className, classBytes);
		if (FABRIC_CLIENT_SYNC.equals(className)) return flushAroundFabricApply(className, classBytes);
		return classBytes;
	}

	/**
	 * Adds NeoForge's {@code clear(Z)} / {@code registerIdMapping(ResourceKey, I)} and fabric-api's
	 * {@code remap(Object2IntMap, RemapMode)} to the wrapper — unless it grew its own.
	 *
	 * <p>fabric-api's half is the same disease with a quieter symptom: its {@code remap} is a mixin method that
	 * rewrites the {@code MappedRegistry} fields it shadows, and on the wrapper those are the same empty fields —
	 * so against a PURE Fabric server (where only fabric-api's sync runs) the seventeen wrapped registries kept
	 * their local ids and nothing said so. With mod sets that differ between client and server, every block, item,
	 * entity type and sound in those registries then decodes to the wrong one. The override stages the server's
	 * ids exactly as the NeoForge one does, and {@code ClientRegistrySyncHandler.apply} flushes them.
	 */
	private static byte[] giveWrapperBothContracts(String className, byte[] classBytes) {
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		for (MethodNode m : node.methods) {
			if ((m.name.equals(REGISTER_ID_MAPPING) && m.desc.equals(REGISTER_ID_MAPPING_DESC))
					|| (m.name.equals(CLEAR) && m.desc.equals(CLEAR_DESC))
					|| (m.name.equals(REMAP) && m.desc.equals(REMAP_DESC))) {
				// A wrapper that overrides these itself has closed the gap natively; adding a second copy would
				// be a duplicate-method ClassFormatError, so leave it be and say so.
				ForbricLog.warn("[Forbric/RegistrySync] %s already declares %s%s — leaving Forge's own version in "
						+ "charge of NeoForge's registry sync", className, m.name, m.desc);
				return classBytes;
			}
		}

		// protected void registerIdMapping(ResourceKey<T> key, int id) { KernelForgeWrapperSync.stageIdMapping(this, key, id); }
		MethodNode stage = new MethodNode(Opcodes.ACC_PROTECTED, REGISTER_ID_MAPPING, REGISTER_ID_MAPPING_DESC, null, null);
		stage.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		stage.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
		stage.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
		stage.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, "stageIdMapping",
				"(Ljava/lang/Object;Ljava/lang/Object;I)V", false));
		stage.instructions.add(new InsnNode(Opcodes.RETURN));
		stage.maxStack = 3;
		stage.maxLocals = 3;
		node.methods.add(stage);

		// protected void clear(boolean full) { KernelForgeWrapperSync.clear(this, full); }
		MethodNode clear = new MethodNode(Opcodes.ACC_PROTECTED, CLEAR, CLEAR_DESC, null, null);
		clear.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		clear.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
		clear.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, "clear",
				"(Ljava/lang/Object;Z)V", false));
		clear.instructions.add(new InsnNode(Opcodes.RETURN));
		clear.maxStack = 2;
		clear.maxLocals = 2;
		node.methods.add(clear);

		// public void remap(Object2IntMap<Identifier> ids, RemapMode mode) { KernelForgeWrapperSync.stageFabricRemap(this, ids, mode); }
		MethodNode remap = new MethodNode(Opcodes.ACC_PUBLIC, REMAP, REMAP_DESC, null, null);
		remap.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		remap.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
		remap.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
		remap.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, "stageFabricRemap",
				"(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", false));
		remap.instructions.add(new InsnNode(Opcodes.RETURN));
		remap.maxStack = 3;
		remap.maxLocals = 3;
		node.methods.add(remap);

		ForbricLog.info("[Forbric/RegistrySync] gave %s NeoForge's id-remap contract (clear, registerIdMapping) and "
				+ "fabric-api's (remap) — the server's ids are staged and applied through Forge's own injectSnapshot",
				className);
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** Resets the staging at the head of {@code applySnapshot(Map, boolean)} and flushes it before every return. */
	private static byte[] flushAroundApplySnapshot(String className, byte[] classBytes) {
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		MethodNode target = null;
		for (MethodNode m : node.methods) {
			if (m.name.equals(APPLY_SNAPSHOT) && m.desc.equals(APPLY_SNAPSHOT_DESC)
					&& (m.access & Opcodes.ACC_STATIC) != 0) {
				target = m;
				break;
			}
		}
		if (target == null) {
			ForbricLog.warn("[Forbric/RegistrySync] %s has no static %s%s — NeoForge's registry sync will stage ids "
					+ "on the Forge-wrapped registries and never apply them; re-derive RegistrySyncParityInjector",
					className, APPLY_SNAPSHOT, APPLY_SNAPSHOT_DESC);
			return classBytes;
		}

		InsnList head = new InsnList();
		head.add(new LdcInsnNode(org.objectweb.asm.Type.getObjectType(node.name))); // a game class, for its loader
		head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, "beginSnapshotApplication", "(Ljava/lang/Class;)V", false));
		target.instructions.insert(head);

		int returns = 0;
		for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() != Opcodes.ARETURN) continue;
			// The Set of missing keys is on the stack; hand a copy to the flush and leave the original to return.
			InsnList flush = new InsnList();
			flush.add(new InsnNode(Opcodes.DUP));
			flush.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, "finishSnapshotApplication",
					"(Ljava/util/Set;)V", false));
			target.instructions.insertBefore(insn, flush);
			returns++;
		}
		target.maxStack = Math.max(target.maxStack, 2);

		// revertToFrozen(): NeoForge's disconnect-time revert, which re-applies a snapshot GameData.freezeData takes
		// — a freeze the kernel owns, so the snapshot never existed and the call used to be neutered. The kernel
		// captures its own at the first remap (the head hook above) and this body now puts it back.
		for (MethodNode m : node.methods) {
			if (!m.name.equals(REVERT_TO_FROZEN) || !m.desc.equals("()V") || (m.access & Opcodes.ACC_STATIC) == 0) continue;
			InsnList body = new InsnList();
			body.add(new LdcInsnNode(org.objectweb.asm.Type.getObjectType(node.name)));
			body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, REVERT_OWNER, "revertToPreConnection", "(Ljava/lang/Class;)V", false));
			body.add(new InsnNode(Opcodes.RETURN));
			m.instructions = body;
			m.tryCatchBlocks = null;
			m.localVariables = null;
			m.maxStack = 1;
			m.maxLocals = 0;
			ForbricLog.info("[Forbric/RegistrySync] %s.%s() now restores the kernel's own pre-connection snapshot", className,
					REVERT_TO_FROZEN);
		}

		ForbricLog.info("[Forbric/RegistrySync] flushing the Forge-wrapped registries' staged ids at %d return(s) of "
				+ "%s.%s", returns, className, APPLY_SNAPSHOT);
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/**
	 * The fabric-api twin of {@link #flushAroundApplySnapshot}: {@code ClientRegistrySyncHandler.apply(payload)} is
	 * where the client calls {@code remap} on every synced registry, so its returns are where the wrapped ones get
	 * theirs applied. {@code void}, so a bare call before each {@code RETURN}; an exception path leaves the staging
	 * to the next {@code beginSnapshotApplication}.
	 */
	private static byte[] flushAroundFabricApply(String className, byte[] classBytes) {
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		MethodNode target = null;
		for (MethodNode m : node.methods) {
			if (m.name.equals(FABRIC_APPLY) && m.desc.equals(FABRIC_APPLY_DESC) && (m.access & Opcodes.ACC_STATIC) != 0) {
				target = m;
				break;
			}
		}
		if (target == null) {
			ForbricLog.warn("[Forbric/RegistrySync] %s has no static %s%s — fabric-api's registry sync will stage ids on "
					+ "the Forge-wrapped registries and never apply them; re-derive RegistrySyncParityInjector",
					className, FABRIC_APPLY, FABRIC_APPLY_DESC);
			return classBytes;
		}

		InsnList head = new InsnList();
		head.add(new LdcInsnNode(org.objectweb.asm.Type.getObjectType(node.name)));
		head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, "beginSnapshotApplication", "(Ljava/lang/Class;)V", false));
		target.instructions.insert(head);
		int returns = 0;
		for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() != Opcodes.RETURN) continue;
			InsnList flush = new InsnList();
			flush.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, "finishFabricRemap", "()V", false));
			target.instructions.insertBefore(insn, flush);
			returns++;
		}

		ForbricLog.info("[Forbric/RegistrySync] flushing the Forge-wrapped registries' staged ids at %d return(s) of "
				+ "%s.%s", returns, className, FABRIC_APPLY);
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}
}
