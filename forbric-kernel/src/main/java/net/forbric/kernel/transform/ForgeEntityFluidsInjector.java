/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.List;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Gives the merged {@code EntityFluidInteraction} back MinecraftForge's half of its {@code update}: what MinecraftForge's
 * entity-fluid queries read, and the fluid tags MinecraftForge decides "in water" and "in lava" by.
 *
 * <p>The merged class is NeoForge's. Its {@code update(Entity, Predicate)} walks the cells around an entity and fills
 * trackers keyed by NeoForge fluid type. MinecraftForge's {@code update} walks the same cells and fills MinecraftForge's
 * own state (whether any fluid is touched, the MinecraftForge type the eyes are in, a tracker per MinecraftForge type) and
 * trackers per fluid tag. The merge kept MinecraftForge's queries of that state — {@code getEyeFluid()},
 * {@code isInFluid()}, {@code isInFluid(BiPredicate, boolean)}, {@code getFluidHeight}/{@code isEyeInFluid} of a
 * MinecraftForge type, {@code getMaxHeightFluid()} — and no code that writes it: every MinecraftForge mod's question
 * about the fluid an entity is in, and the merged client's overlay of a MinecraftForge fluid, answered "none", in vanilla
 * water too. And a MinecraftForge mod's fluid in {@code minecraft:water} was never water to NeoForge's entity code, which
 * asks its water type where vanilla and MinecraftForge ask the tag.
 *
 * <p>The edits, on the reviewed shape only (MinecraftForge's queries present, NeoForge's {@code update} reading exactly
 * one cell per step with {@code getFluidState}, and nothing outside the constructor writing MinecraftForge's state):
 * <ul>
 *   <li>a field holding the kernel's {@code KernelForgeEntityFluids.State}, made at the end of the constructor from the
 *       tags the interaction was made with;</li>
 *   <li>NeoForge's {@code update} calls {@code begin} first and {@code cell} right after each cell it reads, so
 *       MinecraftForge's record is made over the same cells, under the same guard, in the same order;</li>
 *   <li>MinecraftForge's six queries answer from that record;</li>
 *   <li>NeoForge's {@code isInFluid}, {@code isEyeInFluid} and {@code getFluidHeight} of a fluid type pass their answer
 *       through the kernel, which adds a MinecraftForge mod's fluid in {@code minecraft:water} to NeoForge's water type and
 *       one in {@code minecraft:lava} to its lava type, and leaves every other answer as NeoForge gave it.</li>
 * </ul>
 * Not made twice. {@code -Dforbric.forgeEntityFluids=off} leaves the class as merged.
 */
public final class ForgeEntityFluidsInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.forgeEntityFluids";
	static final String INTERACTION = "net.minecraft.world.entity.EntityFluidInteraction";
	static final String OWNER = INTERACTION.replace('.', '/');
	static final String FORGE_TYPE = "L" + ForeignType.FLUID_TYPE.internal(Ecosystem.FORGE) + ";";
	static final String NEO_TYPE = "L" + ForeignType.FLUID_TYPE.internal(Ecosystem.NEOFORGE) + ";";
	static final String RUNTIME = "net/forbric/kernel/runtime/KernelForgeEntityFluids";
	static final String FIELD = "forbric$forgeFluids";
	static final String UPDATE_DESC = "(Lnet/minecraft/world/entity/Entity;Ljava/util/function/Predicate;)V";
	static final String CONSTRUCTOR_DESC = "(Ljava/util/Set;)V";
	static final String STATE = "Ljava/lang/Object;";

	/** MinecraftForge's six queries of its own state: name, descriptor, the kernel method answering it. */
	static final String[][] FORGE_QUERIES = {
		{"getFluidHeight", "(" + FORGE_TYPE + ")D", "forgeHeight"},
		{"isEyeInFluid", "(" + FORGE_TYPE + ")Z", "forgeEyes"},
		{"getEyeFluid", "()" + FORGE_TYPE, "forgeEye"},
		{"isInFluid", "()Z", "forgeInFluid"},
		{"isInFluid", "(Ljava/util/function/BiPredicate;Z)Z", "forgeMatching"},
		{"getMaxHeightFluid", "()" + FORGE_TYPE, "forgeMaxHeight"},
	};
	/** NeoForge's three type queries the tags widen: name, descriptor, the kernel method passing the answer through. */
	static final String[][] NEO_QUERIES = {
		{"isInFluid", "(" + NEO_TYPE + ")Z", "neoIn"},
		{"isEyeInFluid", "(" + NEO_TYPE + ")Z", "neoEyes"},
		{"getFluidHeight", "(" + NEO_TYPE + ")D", "neoHeight"},
	};

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override public String name() { return "forbric-forge-entity-fluids"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("MinecraftForge's entity-fluid state left unwritten with -D" + PROPERTY + "=off");
		return AnchorSet.of(new AnchorSet.Anchor(INTERACTION, AnchorSet.Severity.REQUIRED,
				"MinecraftForge mods' entity-fluid queries answer \"no fluid\", and a MinecraftForge fluid in minecraft:water is not water"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0 || !INTERACTION.equals(className)) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		if (repair(node) <= 0) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/Fluid] EntityFluidInteraction records MinecraftForge's half of its update again — MinecraftForge "
				+ "mods' entity-fluid queries see the fluids an entity is in, and a MinecraftForge fluid in minecraft:water or "
				+ "minecraft:lava is water or lava to NeoForge's entity code");
		return writer.toByteArray();
	}

	/** 1 when edited, 0 when already edited, -1 (logged) when the class is not the reviewed shape. */
	static int repair(ClassNode node) {
		if (node.fields.stream().anyMatch(f -> f.name.equals(FIELD))) return 0;
		MethodNode constructor = find(node, "<init>", CONSTRUCTOR_DESC);
		MethodNode update = find(node, "update", UPDATE_DESC);
		if (constructor == null || update == null) return declined("NeoForge's update(Entity, Predicate) or the (Set) constructor is missing");
		List<MethodNode> forge = new ArrayList<>();
		for (String[] query : FORGE_QUERIES) {
			MethodNode m = find(node, query[0], query[1]);
			if (m == null || (m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0) return declined("MinecraftForge's " + query[0] + query[1] + " is missing");
			forge.add(m);
		}
		List<MethodNode> neo = new ArrayList<>();
		for (String[] query : NEO_QUERIES) {
			MethodNode m = find(node, query[0], query[1]);
			if (m == null || (m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0) return declined("NeoForge's " + query[0] + query[1] + " is missing");
			neo.add(m);
		}
		// MinecraftForge's state is orphaned: the fields its queries read are written by the constructor alone. A merged
		// base that kept MinecraftForge's update already maintains them, and two writers would disagree.
		for (MethodNode m : forge) {
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof FieldInsnNode read && read.getOpcode() == Opcodes.GETFIELD && read.owner.equals(node.name) && writtenOutsideConstructors(node, read.name)) {
					return declined("MinecraftForge's state (" + read.name + ") is still written by an update");
				}
			}
		}
		// The one cell read per step of NeoForge's walk: aload level; aload pos; getFluidState; astore state.
		AbstractInsnNode read = null;
		for (AbstractInsnNode insn : update.instructions) {
			if (insn instanceof MethodInsnNode call && call.name.equals("getFluidState")
					&& call.desc.equals("(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;")) {
				if (read != null) return declined("NeoForge's update reads more than one cell per step");
				read = insn;
			}
		}
		if (read == null) return declined("NeoForge's update reads no cell");
		AbstractInsnNode posLoad = previous(read), levelLoad = posLoad == null ? null : previous(posLoad), store = next(read);
		if (!(posLoad instanceof VarInsnNode pos) || pos.getOpcode() != Opcodes.ALOAD || !(levelLoad instanceof VarInsnNode level)
				|| level.getOpcode() != Opcodes.ALOAD || !(store instanceof VarInsnNode cellState) || cellState.getOpcode() != Opcodes.ASTORE) {
			return declined("NeoForge's update does not read its cell as aload level; aload pos; getFluidState; astore");
		}

		node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, FIELD, STATE, null, null));
		// The record, made with the tags the interaction tracks.
		for (AbstractInsnNode insn : constructor.instructions.toArray()) {
			if (insn.getOpcode() != Opcodes.RETURN) continue;
			InsnList make = new InsnList();
			make.add(new VarInsnNode(Opcodes.ALOAD, 0));
			make.add(new VarInsnNode(Opcodes.ALOAD, 1));
			make.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "state", "(Ljava/util/Set;)" + STATE, false));
			make.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, FIELD, STATE));
			constructor.instructions.insertBefore(insn, make);
		}
		// MinecraftForge resets before it walks, then records each cell the walk reads.
		InsnList begin = new InsnList();
		begin.add(state(node));
		begin.add(new VarInsnNode(Opcodes.ALOAD, 1));
		begin.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "begin", "(" + STATE + "Lnet/minecraft/world/entity/Entity;)V", false));
		update.instructions.insert(begin);
		InsnList cell = new InsnList();
		cell.add(state(node));
		cell.add(new VarInsnNode(Opcodes.ALOAD, cellState.var));
		cell.add(new VarInsnNode(Opcodes.ALOAD, ((VarInsnNode) levelLoad).var));
		cell.add(new VarInsnNode(Opcodes.ALOAD, ((VarInsnNode) posLoad).var));
		cell.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "cell", "(" + STATE
				+ "Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)V", false));
		update.instructions.insert(store, cell);
		// MinecraftForge's queries answer from the record.
		for (int i = 0; i < forge.size(); i++) answerFromRecord(node, forge.get(i), FORGE_QUERIES[i][2]);
		// NeoForge's water and lava answers count a MinecraftForge mod's fluid in that tag.
		for (int i = 0; i < neo.size(); i++) passThrough(node, neo.get(i), NEO_QUERIES[i][2]);
		return 1;
	}

	private static InsnList state(ClassNode node) {
		InsnList load = new InsnList();
		load.add(new VarInsnNode(Opcodes.ALOAD, 0));
		load.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, FIELD, STATE));
		return load;
	}

	/** The body becomes {@code return KernelForgeEntityFluids.<answer>(this.state, args...)}. */
	private static void answerFromRecord(ClassNode node, MethodNode method, String answer) {
		Type type = Type.getMethodType(method.desc);
		InsnList body = state(node);
		int slot = 1;
		StringBuilder desc = new StringBuilder("(").append(STATE);
		for (Type argument : type.getArgumentTypes()) {
			body.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), slot));
			slot += argument.getSize();
			desc.append(argument.getDescriptor());
		}
		desc.append(')').append(type.getReturnType().getDescriptor());
		body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, answer, desc.toString(), false));
		body.add(new InsnNode(type.getReturnType().getOpcode(Opcodes.IRETURN)));
		method.instructions.clear();
		method.tryCatchBlocks.clear();
		if (method.localVariables != null) method.localVariables.clear();
		method.instructions.add(body);
	}

	/** Before each return of a one-argument query: {@code return KernelForgeEntityFluids.<pass>(this.state, type, answer)}. */
	private static void passThrough(ClassNode node, MethodNode method, String pass) {
		Type result = Type.getMethodType(method.desc).getReturnType();
		int temp = method.maxLocals;
		for (AbstractInsnNode insn : method.instructions.toArray()) {
			if (insn.getOpcode() != result.getOpcode(Opcodes.IRETURN)) continue;
			InsnList through = new InsnList();
			through.add(new VarInsnNode(result.getOpcode(Opcodes.ISTORE), temp));
			through.add(state(node));
			through.add(new VarInsnNode(Opcodes.ALOAD, 1));
			through.add(new VarInsnNode(result.getOpcode(Opcodes.ILOAD), temp));
			through.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, pass, "(" + STATE + NEO_TYPE + result.getDescriptor() + ")"
					+ result.getDescriptor(), false));
			method.instructions.insertBefore(insn, through);
		}
		method.maxLocals = temp + result.getSize();
	}

	private static boolean writtenOutsideConstructors(ClassNode node, String field) {
		for (MethodNode m : node.methods) {
			if (m.name.equals("<init>")) continue;
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof FieldInsnNode write && write.getOpcode() == Opcodes.PUTFIELD && write.owner.equals(node.name) && write.name.equals(field)) return true;
			}
		}
		return false;
	}

	private static AbstractInsnNode previous(AbstractInsnNode insn) {
		AbstractInsnNode p = insn.getPrevious();
		while (p != null && p.getOpcode() < 0) p = p.getPrevious();
		return p;
	}

	private static AbstractInsnNode next(AbstractInsnNode insn) {
		AbstractInsnNode n = insn.getNext();
		while (n != null && n.getOpcode() < 0) n = n.getNext();
		return n;
	}

	private static MethodNode find(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
		return null;
	}

	private static int declined(String reason) {
		ForbricLog.warn("[Forbric/Fluid] left EntityFluidInteraction as merged: %s — MinecraftForge mods' entity-fluid queries "
				+ "answer \"no fluid\", and a MinecraftForge fluid in minecraft:water is not water to NeoForge's entity code", reason);
		return -1;
	}
}
