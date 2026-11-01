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
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * A NeoForge data-map lookup on a block, item or entity type that is not registered yet answers "no data" again,
 * so the vanilla lookup NeoForge routed through it falls back to vanilla's own table, as on Fabric.
 *
 * <p>Vanilla's oxidation, waxing and stripping lookups read static maps keyed by the {@code Block} object:
 * {@code WeatheringCopper.getNext} is {@code NEXT_BY_BLOCK.get().get(block)}, {@code HoneycombItem.getWaxed}
 * {@code WAXABLES}, {@code AxeItem.getStripped} {@code STRIPPABLES}. They answer for any block, registered or not. The
 * base asks NeoForge's data map first ({@code DataMapHooks.getNextOxidizedStage} / {@code getBlockWaxed},
 * {@code BlockState.getData(STRIPPABLES)}, and {@code ItemStack.getData(COMPOSTABLES)} in the composter), and each of
 * them ends in {@code Holder.Reference.getData}, which asks {@code key()} — and an intrusive holder has no key until
 * its value is registered, so {@code key()} throws {@code Trying to access unbound value}. fabric-api's
 * {@code OxidizableBlocksRegistry.registerNextStage} refreshes the blocks' random-tick cache, which calls
 * {@code isRandomlyTicking}, which calls {@code getNext}; moreladders calls it in {@code onInitialize} before it
 * registers its ladders, so its whole main entrypoint failed, where native Fabric's map lookup answered.
 *
 * <p>The edit is a guard at the head of {@code getData}: an unbound reference ({@code key == null}) goes to the
 * {@code return null} the method already has for an owner that is not a registry. Data maps are keyed by registry key,
 * so a value with no key can be listed in none of them; {@code null} is the true answer, and the one NeoForge's own
 * {@code IWithData.getData} gives every holder that cannot carry data. Every caller already handles it —
 * {@code DataMapHooks} and {@code AxeItem} fall back to vanilla's maps, the composter to its own fallback — and the
 * data maps once loaded are untouched: a registered holder has its key and takes the path it always took.
 *
 * <p>Native NeoForge throws there, at any time. While the value's registry is open that throw is the defect this
 * repairs; once the registry has closed, an unbound value is one somebody constructed and had not registered when
 * its registry's {@code freeze()} ran, and the throw was the report. So before going to {@code return null} the guard
 * hands the value and its owner to {@link net.forbric.kernel.util.KernelUnboundHolderData#unbound}, which says nothing
 * while the owner is open and WARNs once per value, with the asking stack, once it is closed. The answer is
 * {@code null} either way.
 *
 * <pre>
 *   aload_0; getfield key; ifnonnull BOUND
 *   aload_0; getfield value; aload_0; getfield owner; invokestatic KernelUnboundHolderData.unbound(Object, Object)
 *   goto NO_DATA                       // the method's own `aconst_null; areturn`
 * BOUND: (F_SAME frame)               // the method as the base has it
 * </pre>
 *
 * <p>All or nothing, on the reviewed shape: {@code key}, {@code value} and {@code owner} fields, one {@code key()} call
 * in {@code getData}, and the {@code aconst_null; areturn} behind a first frame that is the arguments, so both
 * {@code BOUND} and {@code NO_DATA} hold exactly the arguments and an empty stack. {@code -Dforbric.unboundHolderData=off}
 * leaves the class as the base has it.
 */
public final class UnboundHolderDataInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.unboundHolderData";
	static final String TARGET = "net.minecraft.core.Holder$Reference";
	static final String OWNER = "net/minecraft/core/Holder$Reference";
	static final String KEY_DESC = "Lnet/minecraft/resources/ResourceKey;";
	static final String GET_DATA_DESC = "(Lnet/neoforged/neoforge/registries/datamaps/DataMapType;)Ljava/lang/Object;";
	static final String VALUE_DESC = "Ljava/lang/Object;";
	static final String HOLDER_OWNER_DESC = "Lnet/minecraft/core/HolderOwner;";
	static final String HOOK = "net/forbric/kernel/util/KernelUnboundHolderData";
	static final String HOOK_NAME = "unbound";
	static final String HOOK_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)V";

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override public String name() { return "forbric-unbound-holder-data"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("data-map lookups on an unregistered holder throw with -D" + PROPERTY + "=off");
		return AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.REQUIRED,
				"a Fabric mod that asks for a block's next oxidation stage, waxed or stripped form before registering it "
						+ "(fabric-api's OxidizableBlocksRegistry does) fails its initializer with \"Trying to access unbound value\""));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || !TARGET.equals(className) || bytes == null || bytes.length == 0) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		if (repair(node) <= 0) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/DataMaps] Holder.Reference.getData answers null (no data-map entry) for a value not registered "
				+ "yet — NeoForge's lookups that replaced vanilla's oxidation, waxing, stripping and compostable maps threw "
				+ "there (the holder has no value bound yet), and now fall back to vanilla's maps as on Fabric (a WARN names any "
				+ "such value still unregistered after its registry closed)");
		return writer.toByteArray();
	}

	/** Puts the unbound guard at the head of {@code getData}; 1 when it did, 0 when it was there, -1 when declined. */
	static int repair(ClassNode reference) {
		if (!hasField(reference, "key", KEY_DESC)) return declined("Holder.Reference has no key field of type ResourceKey");
		if (!hasField(reference, "value", VALUE_DESC) || !hasField(reference, "owner", HOLDER_OWNER_DESC)) {
			return declined("Holder.Reference has no value field of type Object or no owner field of type HolderOwner");
		}
		MethodNode getData = null;
		for (MethodNode method : reference.methods) {
			if (method.name.equals("getData") && method.desc.equals(GET_DATA_DESC) && (method.access & Opcodes.ACC_STATIC) == 0) {
				getData = method;
			}
		}
		if (getData == null) return declined("Holder.Reference declares no getData(DataMapType)");
		if (guarded(getData)) return 0;
		int keyCalls = 0;
		for (AbstractInsnNode insn : getData.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(OWNER) && call.name.equals("key")) keyCalls++;
		}
		if (keyCalls != 1) return declined("getData does not call key() exactly once");
		LabelNode noData = noDataLabel(getData);
		if (noData == null) return declined("getData has no `return null` behind a first frame that holds only its arguments");
		LabelNode bound = new LabelNode();
		InsnList head = new InsnList();
		head.add(new VarInsnNode(Opcodes.ALOAD, 0));
		head.add(new FieldInsnNode(Opcodes.GETFIELD, OWNER, "key", KEY_DESC));
		head.add(new JumpInsnNode(Opcodes.IFNONNULL, bound));
		head.add(new VarInsnNode(Opcodes.ALOAD, 0));
		head.add(new FieldInsnNode(Opcodes.GETFIELD, OWNER, "value", VALUE_DESC));
		head.add(new VarInsnNode(Opcodes.ALOAD, 0));
		head.add(new FieldInsnNode(Opcodes.GETFIELD, OWNER, "owner", HOLDER_OWNER_DESC));
		head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, HOOK_NAME, HOOK_DESC, false));
		head.add(new JumpInsnNode(Opcodes.GOTO, noData));
		head.add(bound);
		// The head of the method: its arguments, an empty stack. NO_DATA's F_SAME, now the second frame, says the same.
		head.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
		// After the method's opening label, so its first line number and the arguments' local ranges cover the guard.
		AbstractInsnNode start = getData.instructions.getFirst();
		if (start instanceof LabelNode) getData.instructions.insert(start, head);
		else getData.instructions.insert(head);
		return 1;
	}

	/**
	 * The label of {@code aconst_null; areturn} whose frame is the method's first and a {@code F_SAME}: the arguments,
	 * an empty stack — what the head of the method has, so a jump from there needs no frame of its own.
	 */
	private static LabelNode noDataLabel(MethodNode method) {
		FrameNode firstFrame = null;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof FrameNode frame) {
				firstFrame = frame;
				break;
			}
		}
		if (firstFrame == null || firstFrame.type != Opcodes.F_SAME) return null;
		AbstractInsnNode label = firstFrame.getPrevious();
		while (label != null && !(label instanceof LabelNode)) {
			if (label.getOpcode() >= 0) return null;
			label = label.getPrevious();
		}
		AbstractInsnNode push = next(firstFrame);
		if (label == null || push == null || push.getOpcode() != Opcodes.ACONST_NULL) return null;
		AbstractInsnNode ret = next(push);
		return ret != null && ret.getOpcode() == Opcodes.ARETURN ? (LabelNode) label : null;
	}

	/**
	 * The method already opens with this injector's whole guard: the {@code key} test, the report of the value and its
	 * owner, the jump to a {@code aconst_null; areturn}, and the bound path behind it. A look-alike that only starts
	 * the same way is not taken for it.
	 */
	static boolean guarded(MethodNode method) {
		List<AbstractInsnNode> head = new ArrayList<>(9);
		for (AbstractInsnNode insn = first(method); insn != null && head.size() < 9; insn = next(insn)) head.add(insn);
		if (head.size() < 9) return false;
		if (!(load0(head.get(0)) && field(head.get(1), "key", KEY_DESC) && head.get(2).getOpcode() == Opcodes.IFNONNULL
				&& load0(head.get(3)) && field(head.get(4), "value", VALUE_DESC)
				&& load0(head.get(5)) && field(head.get(6), "owner", HOLDER_OWNER_DESC)
				&& head.get(7) instanceof MethodInsnNode hook && hook.getOpcode() == Opcodes.INVOKESTATIC && hook.owner.equals(HOOK)
				&& hook.name.equals(HOOK_NAME) && hook.desc.equals(HOOK_DESC)
				&& head.get(8).getOpcode() == Opcodes.GOTO)) return false;
		AbstractInsnNode push = next(((JumpInsnNode) head.get(8)).label);
		AbstractInsnNode ret = next(push);
		return push != null && push.getOpcode() == Opcodes.ACONST_NULL && ret != null && ret.getOpcode() == Opcodes.ARETURN
				&& next(((JumpInsnNode) head.get(2)).label) == next(head.get(8));
	}

	private static boolean load0(AbstractInsnNode insn) {
		return insn instanceof VarInsnNode var && var.getOpcode() == Opcodes.ALOAD && var.var == 0;
	}

	private static boolean field(AbstractInsnNode insn, String name, String desc) {
		return insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD && field.owner.equals(OWNER)
				&& field.name.equals(name) && field.desc.equals(desc);
	}

	private static AbstractInsnNode next(AbstractInsnNode from) {
		if (from == null) return null;
		AbstractInsnNode insn = from.getNext();
		while (insn != null && insn.getOpcode() < 0) insn = insn.getNext();
		return insn;
	}

	private static AbstractInsnNode first(MethodNode method) {
		AbstractInsnNode insn = method.instructions.getFirst();
		while (insn != null && insn.getOpcode() < 0) insn = insn.getNext();
		return insn;
	}

	private static boolean hasField(ClassNode node, String name, String desc) {
		for (FieldNode field : node.fields) if (field.name.equals(name) && field.desc.equals(desc)) return true;
		return false;
	}

	private static int declined(String reason) {
		ForbricLog.warn("[Forbric/DataMaps] left Holder.Reference as the base has it: %s — a data-map lookup on a value not registered "
				+ "yet still throws \"Trying to access unbound value\" where vanilla's map answers", reason);
		return -1;
	}
}
