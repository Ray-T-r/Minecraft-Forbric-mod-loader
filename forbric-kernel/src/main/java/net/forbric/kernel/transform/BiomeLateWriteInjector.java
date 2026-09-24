/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.List;

import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * A biome's climate or effects written after NeoForge's modifier pass win over the pass, as they would on Fabric.
 *
 * <p>Once biome reads go through NeoForge's modified view (NativeCoremodParity), the view is fixed when the pass runs
 * — and BiomeInfoRebaseInjector makes the pass start from what the biome held then. A Fabric mod can still replace
 * {@code Biome.climateSettings} or {@code specialEffects} afterwards: lithostitched-fabric's {@code replace_climate}
 * and {@code replace_effects} do, before the level loads. Before the view those writes counted; through it they would
 * silently not. So the biome remembers what it held at the pass ({@code forbric$markPass}, called from
 * KernelBiomeView.startFrom), and each getter returns the field itself when it has since been replaced. A value
 * mutated in place, without replacing the object, is not seen — the view holds a copy made at the pass.
 *
 * <p>Same switch as the view ({@code -Dforbric.biomeModifiedView=off}).
 */
public final class BiomeLateWriteInjector implements ClassTransformer {
	static final String TARGET = "net.minecraft.world.level.biome.Biome";
	static final String OWNER = "net/minecraft/world/level/biome/Biome";
	static final String CLIMATE = "Lnet/minecraft/world/level/biome/Biome$ClimateSettings;";
	static final String EFFECTS = "Lnet/minecraft/world/level/biome/BiomeSpecialEffects;";
	static final String MARK = "forbric$markPass", MARK_DESC = "(" + CLIMATE + EFFECTS + ")V";

	/** Field, its pass-time twin, and the getter that answers for it. */
	record Guarded(String field, String desc, String atPass, String getter) {
	}

	static final List<Guarded> GUARDED = List.of(
			new Guarded("climateSettings", CLIMATE, "forbric$climateAtPass", "getModifiedClimateSettings"),
			new Guarded("specialEffects", EFFECTS, "forbric$effectsAtPass", "getModifiedSpecialEffects"));

	static boolean enabled() {
		return NativeCoremodParity.on(NativeCoremodParity.BIOME);
	}

	@Override public String name() { return "forbric-biome-late-write"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("biome modified view explicitly disabled with -D" + NativeCoremodParity.BIOME + "=off");
		return AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.REQUIRED,
				"a Fabric mod's climate or effects written after NeoForge's biome pass is ignored by the modified view"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || !TARGET.equals(className) || bytes == null || bytes.length == 0) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		for (MethodNode method : node.methods) if (method.name.equals(MARK)) return bytes;   // already done
		for (Guarded guarded : GUARDED) {
			if (!hasField(node, guarded.field(), guarded.desc()) || getter(node, guarded) == null) {
				ForbricLog.warn("[Forbric/Worldgen] left Biome's getters as they are: %s or %s() is not NeoForge's", guarded.field(), guarded.getter());
				return bytes;
			}
		}
		for (Guarded guarded : GUARDED) {
			node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC, guarded.atPass(), guarded.desc(), null, null));
			MethodNode getter = getter(node, guarded);
			LabelNode view = new LabelNode();
			InsnList check = new InsnList();
			check.add(new VarInsnNode(Opcodes.ALOAD, 0));
			check.add(new FieldInsnNode(Opcodes.GETFIELD, OWNER, guarded.atPass(), guarded.desc()));
			check.add(new JumpInsnNode(Opcodes.IFNULL, view));                 // before the pass: NeoForge's own answer
			check.add(new VarInsnNode(Opcodes.ALOAD, 0));
			check.add(new FieldInsnNode(Opcodes.GETFIELD, OWNER, guarded.field(), guarded.desc()));
			check.add(new VarInsnNode(Opcodes.ALOAD, 0));
			check.add(new FieldInsnNode(Opcodes.GETFIELD, OWNER, guarded.atPass(), guarded.desc()));
			check.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, view));              // unchanged since the pass: the view
			check.add(new VarInsnNode(Opcodes.ALOAD, 0));
			check.add(new FieldInsnNode(Opcodes.GETFIELD, OWNER, guarded.field(), guarded.desc()));
			check.add(new InsnNode(Opcodes.ARETURN));                          // replaced after the pass: the new value
			check.add(view);
			check.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
			getter.instructions.insert(check);
		}
		MethodNode mark = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, MARK, MARK_DESC, null, null);
		mark.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		mark.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
		mark.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, OWNER, GUARDED.get(0).atPass(), CLIMATE));
		mark.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		mark.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
		mark.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, OWNER, GUARDED.get(1).atPass(), EFFECTS));
		mark.instructions.add(new InsnNode(Opcodes.RETURN));
		node.methods.add(mark);
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/Worldgen] Biome's modified view now yields to a climate or effects replaced after "
				+ "NeoForge's pass, as those writes count on Fabric");
		return writer.toByteArray();
	}

	private static MethodNode getter(ClassNode node, Guarded guarded) {
		for (MethodNode method : node.methods) {
			if (method.name.equals(guarded.getter()) && method.desc.equals("()" + guarded.desc())
					&& (method.access & Opcodes.ACC_STATIC) == 0 && !readsRaw(method, guarded)) return method;
		}
		return null;
	}

	/** NeoForge's getter asks the modifiable info; one that reads the raw field already is not its shape. */
	private static boolean readsRaw(MethodNode method, Guarded guarded) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof FieldInsnNode read && read.name.equals(guarded.field())) return true;
		}
		return false;
	}

	private static boolean hasField(ClassNode node, String name, String desc) {
		for (FieldNode field : node.fields) if (field.name.equals(name) && field.desc.equals(desc)) return true;
		return false;
	}
}
