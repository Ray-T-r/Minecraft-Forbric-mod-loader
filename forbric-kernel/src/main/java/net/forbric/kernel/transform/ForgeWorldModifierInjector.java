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
import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;

/**
 * The three carrier-side edits that let MinecraftForge biome/structure modifiers run inside NeoForge's pass.
 *
 * <p>Two repairs on the Forge carrier, so Forge's own worldgen builders link against the merged
 * {@code WeightedList$Builder} (which implements no Forge interface, so the defaults Forge's code calls are gone):
 * {@code MobSpawnSettingsBuilder.lambda$new$0} calls {@code addAll(Iterable)} with a {@code List} on the stack —
 * the descriptor becomes vanilla's {@code addAll(Collection)}; {@code RemoveSpawnsBiomeModifier.modify} calls
 * {@code removeIf(Predicate<E>)}, which links to the merged {@code removeIf(Predicate<Weighted<E>>)} and then
 * ClassCastExceptions inside Forge's lambda — it becomes {@code KernelForgeWorldgen.removeIfValue}, the lost
 * default's one line as kernel code.
 *
 * <p>One splice on NeoForge's {@code ServerLifecycleHooks.runModifiers}: after each of the two
 * {@code Stream.toList} that materialise its biome and structure modifier lists (immediately before
 * {@code astore_2} / {@code astore_3}), one {@code invokestatic KernelForgeWorldgen.withMinecraftForge*Modifiers
 * (List)List} — List in, List out, no frame moves. Exactly two matches or nothing is edited.
 *
 * <p>{@code -Dforbric.forgeWorldgen=off}: all three targets are returned untouched and the anchors are
 * declared as scanned, not missed.
 */
public final class ForgeWorldModifierInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.forgeWorldgen";
	static final String RUNTIME = "net/forbric/kernel/runtime/KernelForgeWorldgen";
	static final String WEIGHTED_BUILDER = "net/minecraft/util/random/WeightedList$Builder";
	static final String BUILDER_DESC = "L" + WEIGHTED_BUILDER + ";";
	static final String LIST_TO_LIST = "(Ljava/util/List;)Ljava/util/List;";
	private static final String SPAWN_BUILDER = ForeignType.MOB_SPAWN_SETTINGS_BUILDER.binary(Ecosystem.FORGE);
	private static final String REMOVE_SPAWNS = ForeignType.REMOVE_SPAWNS_BIOME_MODIFIER.binary(Ecosystem.FORGE);
	private static final String LIFECYCLE_HOOKS = ForeignType.SERVER_LIFECYCLE_HOOKS.binary(Ecosystem.NEOFORGE);
	private static final String NEO_KEYS = ForeignType.MODIFIER_REGISTRY_KEYS.internal(Ecosystem.NEOFORGE);
	private static volatile boolean warnedOff;

	@Override
	public String name() {
		return "forbric-forge-world-modifiers";
	}

	@Override
	public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("switched off by -D" + PROPERTY);
		return AnchorSet.of(
				new AnchorSet.Anchor(SPAWN_BUILDER, AnchorSet.Severity.REQUIRED,
						"MinecraftForge's spawn-settings builder cannot link, so any Forge biome modifier touching spawns throws"),
				new AnchorSet.Anchor(REMOVE_SPAWNS, AnchorSet.Severity.REQUIRED,
						"forge:remove_spawns ClassCastExceptions inside Forge's own lambda"),
				new AnchorSet.Anchor(LIFECYCLE_HOOKS, AnchorSet.Severity.REQUIRED,
						"MinecraftForge biome/structure modifiers never reach the world"));
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		boolean target = SPAWN_BUILDER.equals(className) || REMOVE_SPAWNS.equals(className) || LIFECYCLE_HOOKS.equals(className);
		if (!target) return classBytes;
		if (!enabled()) {
			if (!warnedOff) {
				warnedOff = true;
				ForbricLog.warn("[Forbric/Worldgen] -D%s=off — MinecraftForge biome/structure modifiers are not bridged "
						+ "into NeoForge's pass and Forge's worldgen builders are left unlinked", PROPERTY);
			}
			return classBytes;
		}
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		boolean changed;
		if (SPAWN_BUILDER.equals(className)) changed = widenAddAllToCollection(node);
		else if (REMOVE_SPAWNS.equals(className)) changed = routeRemoveIfThroughTheLostDefault(node);
		else changed = spliceBothModifierLists(node);
		if (!changed) return classBytes;
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** {@code addAll(Iterable)} → {@code addAll(Collection)}: the value on the stack is already a List. */
	private static boolean widenAddAllToCollection(ClassNode node) {
		int repaired = 0;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
						&& WEIGHTED_BUILDER.equals(call.owner) && "addAll".equals(call.name)
						&& ("(Ljava/lang/Iterable;)" + BUILDER_DESC).equals(call.desc)) {
					call.desc = "(Ljava/util/Collection;)" + BUILDER_DESC;
					repaired++;
				}
			}
		}
		if (repaired == 0) return false;
		ForbricLog.info("[Forbric/Worldgen] applied %d call-site repair(s) in %s — WeightedList$Builder.addAll(Iterable) "
				+ "was a MinecraftForge interface default the merge lost; vanilla's addAll(Collection) takes the same List",
				repaired, node.name.replace('/', '.'));
		return true;
	}

	/** {@code removeIf(Predicate<E>)} → the kernel's copy of Forge's lost default. */
	private static boolean routeRemoveIfThroughTheLostDefault(ClassNode node) {
		int repaired = 0;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
						&& WEIGHTED_BUILDER.equals(call.owner) && "removeIf".equals(call.name)
						&& ("(Ljava/util/function/Predicate;)" + BUILDER_DESC).equals(call.desc)) {
					call.setOpcode(Opcodes.INVOKESTATIC);
					call.owner = RUNTIME;
					call.name = "removeIfValue";
					call.desc = "(" + BUILDER_DESC + "Ljava/util/function/Predicate;)" + BUILDER_DESC;
					call.itf = false;
					repaired++;
				}
			}
		}
		if (repaired == 0) return false;
		ForbricLog.info("[Forbric/Worldgen] applied %d call-site repair(s) in %s — WeightedList$Builder.removeIf(Predicate<E>) "
				+ "was a MinecraftForge interface default the merge lost; the merged same-descriptor method takes "
				+ "Predicate<Weighted<E>> and would ClassCastException inside Forge's lambda", repaired, node.name.replace('/', '.'));
		return true;
	}

	/** Both lists or nothing: a Forge biome modifier without its structure twin would be a half-bridged world. */
	private static boolean spliceBothModifierLists(ClassNode node) {
		MethodNode run = null;
		for (MethodNode method : node.methods) {
			if ("runModifiers".equals(method.name) && "(Lnet/minecraft/server/MinecraftServer;)V".equals(method.desc)) run = method;
		}
		if (run == null) return false;
		List<MethodInsnNode> materialisations = new ArrayList<>();
		List<String> helpers = new ArrayList<>();
		String lastKey = null;
		for (AbstractInsnNode insn = run.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode call && RUNTIME.equals(call.owner)) return false;   // already spliced
			if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC && NEO_KEYS.equals(field.owner)) {
				lastKey = field.name;
			}
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEINTERFACE
					&& "java/util/stream/Stream".equals(call.owner) && "toList".equals(call.name)) {
				AbstractInsnNode next = call.getNext();
				while (next != null && next.getOpcode() < 0) next = next.getNext();
				if (!(next instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE) continue;
				if (store.var == 2 && "BIOME_MODIFIERS".equals(lastKey)) {
					materialisations.add(call);
					helpers.add("withMinecraftForgeBiomeModifiers");
				} else if (store.var == 3 && "STRUCTURE_MODIFIERS".equals(lastKey)) {
					materialisations.add(call);
					helpers.add("withMinecraftForgeStructureModifiers");
				}
			}
		}
		if (materialisations.size() != 2) {
			ForbricLog.warn("[Forbric/Worldgen] %s.runModifiers materialises %d recognisable modifier list(s), not two — "
					+ "MinecraftForge modifiers are not spliced in, because bridging one family's list and not the "
					+ "other would be a half-bridged world", node.name.replace('/', '.'), materialisations.size());
			return false;
		}
		for (int i = 0; i < materialisations.size(); i++) {
			run.instructions.insert(materialisations.get(i),
					new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, helpers.get(i), LIST_TO_LIST, false));
		}
		ForbricLog.info("[Forbric/Worldgen] NeoForge's runModifiers now appends MinecraftForge's biome and structure "
				+ "modifiers to its own lists (2 splice(s)) — Forge's own pass links against accessors the merged "
				+ "base does not declare and is never run");
		return true;
	}
}
