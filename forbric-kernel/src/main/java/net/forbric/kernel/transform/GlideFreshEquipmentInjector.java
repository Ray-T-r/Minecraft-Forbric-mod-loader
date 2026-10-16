/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.List;
import java.util.function.BiFunction;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.NativeGameReferences;
import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Answers "can this entity glide" from its equipment, as the native game does, while the attribute the carrier caches
 * that answer in has not caught up with the equipment yet.
 *
 * <p>Vanilla's {@code LivingEntity.canGlide()} — and MinecraftForge's, and so Fabric's — reads the equipment: any slot
 * holding a glider that fits it. NeoForge's, which the merge took, reads the {@code neoforge:gliding_flight} attribute
 * when its flag says so ({@code canGlide()} passes true) and the equipment only otherwise. That attribute is the same
 * answer cached: it is derived from the equipment in {@code collectEquipmentChanges}, once per entity tick. Between an
 * equipment change and the next tick the cache is stale, so a mod that equips an elytra and asks
 * {@code tryToStartFallFlying} in the same tick — the probe's elytra step, any mod that puts a glider on a player and
 * launches them — was refused where every native game but NeoForge lets it fly.
 *
 * <p>So {@code canGlide(boolean)} gains, right after its own "flag false: ask the equipment" branch, "the equipment has
 * moved on since it was last applied: ask the equipment" — the carrier's own equipment branch, the same one it takes when
 * its flag is false; everything before that branch (the movement checks, the attribute read) is untouched. Once the tick has applied the equipment the attribute answers again, so anything that changes the
 * attribute other than equipment (an effect, a NeoForge mod's modifier) is honoured as before; only the one-tick window
 * changes. "Moved on" is vanilla's own test: {@code equipmentHasChanged(last applied, now)} for any slot, against the map
 * {@code collectEquipmentChanges} keeps.
 *
 * <p>Applied only where it is proved: the native reference's {@code canGlide()} reads no attribute and asks every
 * equipment slot, while the merged {@code canGlide(Z)} reads one and branches on its flag; the class keeps the last
 * applied equipment in a {@code Map} field that its {@code equipmentHasChanged} test is run against.
 * {@code -Dforbric.glideFreshEquipment=off} leaves the carrier's body as shipped.
 */
public final class GlideFreshEquipmentInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.glideFreshEquipment";
	static final String TARGET = "net.minecraft.world.entity.LivingEntity";
	private static final String OWNER = "net/minecraft/world/entity/LivingEntity";
	private static final String SLOT = "net/minecraft/world/entity/EquipmentSlot";
	private static final String STACK = "net/minecraft/world/item/ItemStack";
	private static final String HELPER = "forbric$equipmentAheadOfAttributes";
	private final BiFunction<Ecosystem, String, ClassNode> originals;

	public GlideFreshEquipmentInjector() {
		this(NativeGameReferences::reference);
	}

	GlideFreshEquipmentInjector(BiFunction<Ecosystem, String, ClassNode> originals) {
		this.originals = originals;
	}

	@Override public String name() { return "forbric-glide-fresh-equipment"; }

	@Override public AnchorSet anchors() {
		return AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.REQUIRED,
				"an entity that has just put on a glider is refused flight for a tick (the carrier's cached attribute has not caught up)"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!TARGET.equals(className) || bytes == null || "off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"))) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		if (!relay(node)) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/Glide] LivingEntity.canGlide(boolean) asks the equipment while the equipment is ahead of the "
				+ "gliding attribute the carrier caches it in, as the native canGlide always does — an entity that has just put "
				+ "on a glider may take off that tick");
		return writer.toByteArray();
	}

	boolean relay(ClassNode node) {
		MethodNode flagged = method(node, "canGlide", "(Z)Z");
		if (flagged == null || method(node, HELPER, "()Z") != null || !readsAttributeThenBranchesOnFlag(flagged)) return false;
		if (!nativeAsksEquipment()) return false;
		FieldNode last = lastAppliedEquipment(node);
		if (last == null || method(node, "equipmentHasChanged", "(L" + STACK + ";L" + STACK + ";)Z") == null
				|| method(node, "getItemBySlot", "(L" + SLOT + ";)L" + STACK + ";") == null) return false;
		JumpInsnNode toEquipment = flagBranch(flagged);
		if (toEquipment == null) return false;
		node.methods.add(helper(last));
		// After the carrier's own "if (!flag) go to the equipment" branch: "if (forbric$equipmentAheadOfAttributes()) go there
		// too". The movement checks and the attribute read before it stay as they are, so whatever runs there (a guest's
		// injector moved onto the attribute read) runs on both ways as before; the jump reuses the branch's own target and
		// frame.
		InsnList ahead = new InsnList();
		ahead.add(new VarInsnNode(Opcodes.ALOAD, 0));
		ahead.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, OWNER, HELPER, "()Z", false));
		ahead.add(new JumpInsnNode(Opcodes.IFNE, toEquipment.label));
		flagged.instructions.insert(toEquipment, ahead);
		return true;
	}

	/** {@code private boolean forbric$equipmentAheadOfAttributes()}: any slot whose item moved on since it was applied. */
	private static MethodNode helper(FieldNode last) {
		MethodNode m = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC, HELPER, "()Z", null, null);
		InsnList c = m.instructions;
		LabelNode loop = new LabelNode(), done = new LabelNode();
		c.add(new FieldInsnNode(Opcodes.GETSTATIC, SLOT, "VALUES", "Ljava/util/List;"));
		c.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "iterator", "()Ljava/util/Iterator;", true));
		c.add(new VarInsnNode(Opcodes.ASTORE, 1));
		c.add(loop);
		c.add(new FrameNode(Opcodes.F_APPEND, 1, new Object[] { "java/util/Iterator" }, 0, null));
		c.add(new VarInsnNode(Opcodes.ALOAD, 1));
		c.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true));
		c.add(new JumpInsnNode(Opcodes.IFEQ, done));
		c.add(new VarInsnNode(Opcodes.ALOAD, 1));
		c.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true));
		c.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.CHECKCAST, SLOT));
		c.add(new VarInsnNode(Opcodes.ASTORE, 2));
		c.add(new VarInsnNode(Opcodes.ALOAD, 0));
		c.add(new VarInsnNode(Opcodes.ALOAD, 0));
		c.add(new FieldInsnNode(Opcodes.GETFIELD, OWNER, last.name, last.desc));
		c.add(new VarInsnNode(Opcodes.ALOAD, 2));
		c.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true));
		c.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.CHECKCAST, STACK));
		c.add(new VarInsnNode(Opcodes.ALOAD, 0));
		c.add(new VarInsnNode(Opcodes.ALOAD, 2));
		c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OWNER, "getItemBySlot", "(L" + SLOT + ";)L" + STACK + ";", false));
		c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OWNER, "equipmentHasChanged", "(L" + STACK + ";L" + STACK + ";)Z", false));
		c.add(new JumpInsnNode(Opcodes.IFEQ, loop));
		c.add(new InsnNode(Opcodes.ICONST_1));
		c.add(new InsnNode(Opcodes.IRETURN));
		c.add(done);
		c.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
		c.add(new InsnNode(Opcodes.ICONST_0));
		c.add(new InsnNode(Opcodes.IRETURN));
		m.maxStack = 4;
		m.maxLocals = 3;
		return m;
	}

	/** The merged {@code canGlide(Z)}: reads an attribute value, then decides on its flag (local 1). */
	private static boolean readsAttributeThenBranchesOnFlag(MethodNode flagged) {
		boolean attribute = false, flag = false;
		for (AbstractInsnNode insn : flagged.instructions) {
			if (insn instanceof MethodInsnNode call && "getAttributeValue".equals(call.name)) attribute = true;
			if (attribute && insn instanceof VarInsnNode load && load.getOpcode() == Opcodes.ILOAD && load.var == 1) flag = true;
		}
		return attribute && flag;
	}

	/** The {@code iload 1; ifeq equipment} that follows the attribute read: the carrier's own way to the equipment branch. */
	private static JumpInsnNode flagBranch(MethodNode flagged) {
		boolean attribute = false;
		for (AbstractInsnNode insn : flagged.instructions) {
			if (insn instanceof MethodInsnNode call && "getAttributeValue".equals(call.name)) attribute = true;
			if (attribute && insn instanceof VarInsnNode load && load.getOpcode() == Opcodes.ILOAD && load.var == 1) {
				AbstractInsnNode next = load.getNext();
				while (next != null && next.getOpcode() < 0) next = next.getNext();
				return next instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFEQ ? jump : null;
			}
		}
		return null;
	}

	/** The native {@code canGlide()} asks every equipment slot and reads no attribute. */
	private boolean nativeAsksEquipment() {
		for (Ecosystem ecosystem : List.of(Ecosystem.FABRIC, Ecosystem.FORGE)) {
			ClassNode reference;
			try {
				reference = originals.apply(ecosystem, OWNER);
			} catch (RuntimeException unavailable) {
				reference = null;
			}
			MethodNode plain = reference == null ? null : method(reference, "canGlide", "()Z");
			if (plain == null || plain.instructions == null) continue;
			boolean slots = false, attribute = false;
			for (AbstractInsnNode insn : plain.instructions) {
				if (insn instanceof FieldInsnNode field && SLOT.equals(field.owner) && "VALUES".equals(field.name)) slots = true;
				if (insn instanceof MethodInsnNode call && "getAttributeValue".equals(call.name)) attribute = true;
			}
			if (slots && !attribute) return true;
		}
		return false;
	}

	/**
	 * The {@code Map} field holding the equipment last applied: the map whose {@code get(slot)} feeds an
	 * {@code equipmentHasChanged} test, read either directly from the field or, as the merged body does, from the one
	 * {@code Map} parameter of the method that tests, which the class calls with that field.
	 */
	private static FieldNode lastAppliedEquipment(ClassNode node) {
		for (MethodNode method : node.methods) {
			if (method.instructions == null || !calls(method, "equipmentHasChanged")) continue;
			FieldInsnNode direct = mapRead(node, method);
			if (direct != null) return declared(node, direct);
			Type[] arguments = Type.getArgumentTypes(method.desc);
			if ((method.access & Opcodes.ACC_STATIC) != 0 || arguments.length != 1 || !"Ljava/util/Map;".equals(arguments[0].getDescriptor())) continue;
			// The class hands one Map field to this method: getfield this.F; invokevirtual this.method
			for (MethodNode caller : node.methods) {
				if (caller.instructions == null) continue;
				for (AbstractInsnNode insn : caller.instructions) {
					if (insn instanceof MethodInsnNode call && call.owner.equals(node.name) && call.name.equals(method.name)
							&& call.desc.equals(method.desc) && previous(call) instanceof FieldInsnNode field
							&& field.getOpcode() == Opcodes.GETFIELD && field.owner.equals(node.name) && "Ljava/util/Map;".equals(field.desc)) {
						return declared(node, field);
					}
				}
			}
		}
		return null;
	}

	/** A {@code getfield this.F : Map} whose {@code get} result is the first argument of the test, in {@code method}. */
	private static FieldInsnNode mapRead(ClassNode node, MethodNode method) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode get && "java/util/Map".equals(get.owner) && "get".equals(get.name)) {
				AbstractInsnNode key = previous(get), map = key == null ? null : previous(key);
				if (map instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD && field.owner.equals(node.name)
						&& "Ljava/util/Map;".equals(field.desc)) return field;
			}
		}
		return null;
	}

	private static FieldNode declared(ClassNode node, FieldInsnNode field) {
		for (FieldNode declared : node.fields) {
			if (declared.name.equals(field.name) && declared.desc.equals(field.desc) && (declared.access & Opcodes.ACC_STATIC) == 0) return declared;
		}
		return null;
	}

	private static boolean calls(MethodNode method, String name) {
		for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode call && name.equals(call.name)) return true;
		return false;
	}

	private static AbstractInsnNode previous(AbstractInsnNode insn) {
		do insn = insn.getPrevious(); while (insn != null && insn.getOpcode() < 0);
		return insn;
	}

	private static MethodNode method(ClassNode owner, String name, String desc) {
		for (MethodNode m : owner.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
		return null;
	}
}
