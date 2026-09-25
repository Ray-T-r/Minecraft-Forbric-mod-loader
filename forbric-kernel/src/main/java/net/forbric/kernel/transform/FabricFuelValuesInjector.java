/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * A Fabric mod's fuels go into the fuel values the merged game builds.
 *
 * <p>fabric-content-registries fires {@code FuelValueEvents.BUILD}/{@code EXCLUSIONS} from a wrap in vanilla's
 * {@code FuelValues.vanillaBurnTimes}; the merged server (and a client on a NeoForge connection) builds its fuels in
 * NeoForge's {@code DataMapHooks.populateFuelValues} instead and never calls it, so a Fabric mod's fuel could not go in a
 * furnace. Right before that method's one {@code Builder.build()}, the builder goes through {@code KernelFabricFuel.apply}
 * with the method's own registries and feature flags. Applied only when the method builds one builder from its two
 * parameters and builds it once. {@code -Dforbric.fabricFuel=off} leaves it as shipped.
 */
public final class FabricFuelValuesInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.fabricFuel";
	static final String HOOKS = "net.neoforged.neoforge.common.DataMapHooks";
	static final String BUILDER = "net/minecraft/world/level/block/entity/FuelValues$Builder";
	static final String DESC = "(Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/flag/FeatureFlagSet;)Lnet/minecraft/world/level/block/entity/FuelValues;";

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override public String name() { return "forbric-fabric-fuel"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("Fabric fuels left out of NeoForge's fuel values with -D" + PROPERTY + "=off");
		return AnchorSet.of(new AnchorSet.Anchor(HOOKS, AnchorSet.Severity.REQUIRED,
				"a Fabric mod's fuels cannot go in a furnace"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0 || !HOOKS.equals(className)) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		if (!repair(node)) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/Fuel] DataMapHooks.populateFuelValues runs fabric-content-registries' fuel events before it "
				+ "builds — the merged game builds its fuels there and never reached Fabric's hook in vanillaBurnTimes");
		return writer.toByteArray();
	}

	static boolean repair(ClassNode hooks) {
		for (MethodNode method : hooks.methods) {
			if (!method.name.equals("populateFuelValues") || !method.desc.equals(DESC) || (method.access & Opcodes.ACC_STATIC) == 0) continue;
			MethodInsnNode build = null;
			int builds = 0, news = 0;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW && type.desc.equals(BUILDER)) news++;
				if (insn instanceof MethodInsnNode call) {
					if (call.name.equals("apply") && call.owner.equals("net/forbric/kernel/runtime/KernelFabricFuel")) return false;
					if (call.owner.equals(BUILDER) && call.name.equals("build")) { build = call; builds++; }
				}
			}
			if (news != 1 || builds != 1) return false;
			InsnList apply = new InsnList();
			apply.add(new VarInsnNode(Opcodes.ALOAD, 0));
			apply.add(new VarInsnNode(Opcodes.ALOAD, 1));
			apply.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/forbric/kernel/runtime/KernelFabricFuel", "apply",
					"(L" + BUILDER + ";Lnet/minecraft/core/HolderLookup$Provider;Lnet/minecraft/world/flag/FeatureFlagSet;)L" + BUILDER + ";", false));
			method.instructions.insertBefore(build, apply);
			return true;
		}
		return false;
	}
}
