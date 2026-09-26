/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.List;

import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
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
 *
 * <p>The other half is the mixins on {@code vanillaBurnTimes}' RETURN, torrential's Angling Table among them: right
 * after that {@code build()}, the table goes through {@code KernelFabricFuel.throughVanillaReturnHooks}, which calls
 * {@code vanillaBurnTimes(Provider, FeatureFlagSet)} — the call native Fabric and MinecraftForge servers make, which
 * forwards to {@code (Provider, FeatureFlagSet, int)} — with it pending; and the merge-added body
 * {@code vanillaBurnTimes(Builder, int)} starts by returning the pending table when there is one. The short-circuit
 * sits in that body, never in the forwarding overload: MixinStubRebind recognises the overload as the carrier's stub
 * only while it is a pure forward, and moves Fabric injectors into the body by that recognition. It jumps to the
 * body's single {@code areturn} rather than adding a return of its own, so a RETURN or TAIL injector there — whatever
 * its ordinal — runs on both paths. {@code -Dforbric.fabricFuel.returnHooks=off} leaves both methods without it.
 */
public final class FabricFuelValuesInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.fabricFuel";
	/** Mirrors {@code KernelFabricFuel.RETURN_HOOKS}, which the game side reads; the boot side cannot name it. */
	public static final String RETURN_HOOKS_PROPERTY = "forbric.fabricFuel.returnHooks";
	static final String HOOKS = "net.neoforged.neoforge.common.DataMapHooks";
	static final String FUEL_VALUES_CLASS = "net.minecraft.world.level.block.entity.FuelValues";
	static final String FUEL_VALUES = "net/minecraft/world/level/block/entity/FuelValues";
	static final String BUILDER = "net/minecraft/world/level/block/entity/FuelValues$Builder";
	static final String DESC = "(Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/flag/FeatureFlagSet;)Lnet/minecraft/world/level/block/entity/FuelValues;";
	/** The merge-added body the forwarding {@code vanillaBurnTimes(Provider, FeatureFlagSet, int)} calls. */
	static final String BODY_DESC = "(L" + BUILDER + ";I)L" + FUEL_VALUES + ";";
	static final String KERNEL_FUEL = "net/forbric/kernel/runtime/KernelFabricFuel";
	static final String THROUGH_DESC = "(L" + FUEL_VALUES + ";Lnet/minecraft/core/HolderLookup$Provider;"
			+ "Lnet/minecraft/world/flag/FeatureFlagSet;)L" + FUEL_VALUES + ";";

	/** What {@link #repair} inserted into populateFuelValues. */
	static final int APPLIED = 1, RETURN_HOOKED = 2;

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	static boolean returnHooksEnabled() {
		return enabled() && !"off".equalsIgnoreCase(System.getProperty(RETURN_HOOKS_PROPERTY, "on"));
	}

	@Override public String name() { return "forbric-fabric-fuel"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("Fabric fuels left out of NeoForge's fuel values with -D" + PROPERTY + "=off");
		List<AnchorSet.Anchor> anchors = new ArrayList<>(List.of(new AnchorSet.Anchor(HOOKS, AnchorSet.Severity.REQUIRED,
				"a Fabric mod's fuels cannot go in a furnace")));
		if (returnHooksEnabled()) {
			anchors.add(new AnchorSet.Anchor(FUEL_VALUES_CLASS, AnchorSet.Severity.REQUIRED,
					"a mod's mixin on vanillaBurnTimes' return (torrential's Angling Table fuel) never reaches the fuel "
							+ "values the merged server uses"));
		}
		return AnchorSet.of(anchors.toArray(new AnchorSet.Anchor[0]));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0) return bytes;
		if (FUEL_VALUES_CLASS.equals(className)) return shortCircuitTheBody(bytes);
		if (!HOOKS.equals(className)) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		int inserted = repair(node);
		if (inserted == 0) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		if ((inserted & APPLIED) != 0) {
			ForbricLog.info("[Forbric/Fuel] DataMapHooks.populateFuelValues runs fabric-content-registries' fuel events before it "
					+ "builds — the merged game builds its fuels there and never reached Fabric's hook in vanillaBurnTimes");
		}
		if ((inserted & RETURN_HOOKED) != 0) {
			ForbricLog.info("[Forbric/Fuel] DataMapHooks.populateFuelValues hands the table it built through "
					+ "FuelValues.vanillaBurnTimes' return — a mod's mixin there (torrential's Angling Table) now reaches "
					+ "the fuels the merged server uses");
		}
		return writer.toByteArray();
	}

	/** What was inserted into populateFuelValues, as {@link #APPLIED} / {@link #RETURN_HOOKED} bits; 0 for nothing. */
	static int repair(ClassNode hooks) {
		for (MethodNode method : hooks.methods) {
			if (!method.name.equals("populateFuelValues") || !method.desc.equals(DESC) || (method.access & Opcodes.ACC_STATIC) == 0) continue;
			MethodInsnNode build = null;
			int builds = 0, news = 0;
			boolean applied = false, hooked = false;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW && type.desc.equals(BUILDER)) news++;
				if (insn instanceof MethodInsnNode call) {
					if (call.owner.equals(KERNEL_FUEL) && call.name.equals("apply")) applied = true;
					if (call.owner.equals(KERNEL_FUEL) && call.name.equals("throughVanillaReturnHooks")) hooked = true;
					if (call.owner.equals(BUILDER) && call.name.equals("build")) { build = call; builds++; }
				}
			}
			if (news != 1 || builds != 1) return 0;
			int inserted = 0;
			if (!applied) {
				InsnList apply = new InsnList();
				apply.add(new VarInsnNode(Opcodes.ALOAD, 0));
				apply.add(new VarInsnNode(Opcodes.ALOAD, 1));
				apply.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KERNEL_FUEL, "apply",
						"(L" + BUILDER + ";Lnet/minecraft/core/HolderLookup$Provider;Lnet/minecraft/world/flag/FeatureFlagSet;)L" + BUILDER + ";", false));
				method.instructions.insertBefore(build, apply);
				inserted |= APPLIED;
			}
			if (!hooked && returnHooksEnabled()) {
				// [table] -> [table, registries, features] -> [table'] : the built table, then what the hooks make of it.
				InsnList through = new InsnList();
				through.add(new VarInsnNode(Opcodes.ALOAD, 0));
				through.add(new VarInsnNode(Opcodes.ALOAD, 1));
				through.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KERNEL_FUEL, "throughVanillaReturnHooks", THROUGH_DESC, false));
				method.instructions.insert(build, through);
				inserted |= RETURN_HOOKED;
			}
			return inserted;
		}
		return 0;
	}

	/**
	 * Puts {@code KernelFabricFuel.takePending()} at the head of {@code vanillaBurnTimes(Builder, int)}: a pending table
	 * goes straight to the body's own {@code areturn}, everything else runs the body as shipped.
	 *
	 * <p>Only while the forwarding overload still calls this body, and the body still ends in its one {@code areturn}
	 * after building its builder; anything else is left alone, and the anchor audit names it. The frame at the join is
	 * written expanded, so the class is read with its frames expanded too.
	 */
	static byte[] shortCircuitTheBody(byte[] bytes) {
		if (!returnHooksEnabled()) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
		MethodNode body = null;
		boolean forwarded = false;
		for (MethodNode method : node.methods) {
			if (method.name.equals("vanillaBurnTimes") && method.desc.equals(BODY_DESC)
					&& (method.access & Opcodes.ACC_STATIC) != 0) body = method;
			if (method.name.equals("vanillaBurnTimes") && !method.desc.equals(BODY_DESC)) {
				for (AbstractInsnNode insn : method.instructions) {
					if (insn instanceof MethodInsnNode call && call.owner.equals(FUEL_VALUES)
							&& call.name.equals("vanillaBurnTimes") && call.desc.equals(BODY_DESC)) forwarded = true;
				}
			}
		}
		if (body == null || !forwarded) return bytes;
		AbstractInsnNode exit = null;
		for (AbstractInsnNode insn : body.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(KERNEL_FUEL)) return bytes;   // already in
			if (insn.getOpcode() == Opcodes.ARETURN) {
				if (exit != null) return bytes;
				exit = insn;
			}
			if (insn.getOpcode() >= Opcodes.IRETURN && insn.getOpcode() <= Opcodes.RETURN && insn.getOpcode() != Opcodes.ARETURN) return bytes;
		}
		if (exit == null || !(realPrevious(exit) instanceof MethodInsnNode built) || !built.owner.equals(BUILDER)
				|| !built.name.equals("build")) return bytes;

		LabelNode join = new LabelNode();
		InsnList head = new InsnList();
		head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KERNEL_FUEL, "takePending", "()L" + FUEL_VALUES + ";", false));
		head.add(new InsnNode(Opcodes.DUP));
		head.add(new JumpInsnNode(Opcodes.IFNONNULL, join));
		head.add(new InsnNode(Opcodes.POP));
		body.instructions.insert(head);
		InsnList tail = new InsnList();
		tail.add(join);
		tail.add(new FrameNode(Opcodes.F_NEW, 2, new Object[] { BUILDER, Opcodes.INTEGER }, 1, new Object[] { FUEL_VALUES }));
		body.instructions.insertBefore(exit, tail);

		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/Fuel] FuelValues.vanillaBurnTimes(Builder, int) returns the fuel table populateFuelValues "
				+ "hands it instead of rebuilding vanilla's, so the return hooks of both vanillaBurnTimes run on the table "
				+ "the merged server uses");
		return writer.toByteArray();
	}

	private static AbstractInsnNode realPrevious(AbstractInsnNode insn) {
		AbstractInsnNode previous = insn.getPrevious();
		while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
		return previous;
	}
}
