/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.function.Function;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Hands fabric-networking's payload-codec wrappers the two arguments the merged base added.
 *
 * <p>{@code ClientboundCustomPayloadPacketMixin} wraps {@code CustomPacketPayload.codec(FallbackProvider, List)}
 * inside {@code ClientboundCustomPayloadPacket.<clinit>} and returns a {@code FabricCustomPayloadStreamCodec} over
 * the result. The merged base makes a different call: NeoForge added {@code ConnectionProtocol} and
 * {@code PacketFlow} parameters, so the body invokes {@code codec(FallbackProvider, List, ConnectionProtocol,
 * PacketFlow)} and the wrapper's {@code @At} names a method nothing calls. Both handlers bound nothing, so fabric's
 * custom payload codecs were silently unwrapped — a mod's own payload type never reached
 * {@code FabricCustomPayloadStreamCodec}, which is where its type provider is installed.
 *
 * <p>The two calls stay told apart by their ordinals (0 = PLAY, 1 = CONFIGURATION), so only the shape has to
 * change: the {@code @At} target moves to the four-argument call, the handler takes the two new arguments before
 * its {@code Operation}, and the varargs array it builds for {@code Operation.call(Object[])} grows from two
 * entries to four. The new values are passed straight through, so the wrapper still returns fabric's codec over
 * exactly what the game would have built.
 *
 * <p>Only that shape is touched: a {@code @WrapOperation} on {@code <clinit>} whose single point names the
 * two-argument call, against a target whose {@code <clinit>} makes the four-argument call twice and the
 * two-argument one never. {@code -Dforbric.customPayloadCodec=off} turns it off.
 */
public final class FabricCustomPayloadCodecMixinAdapter {
	public static final String PROPERTY = "forbric.customPayloadCodec";
	static final String MIXIN = "net/fabricmc/fabric/mixin/networking/ClientboundCustomPayloadPacketMixin";
	static final String TARGET = "net/minecraft/network/protocol/common/ClientboundCustomPayloadPacket";
	static final String PAYLOAD = "net/minecraft/network/protocol/common/custom/CustomPacketPayload";
	static final String OPERATION = "com/llamalad7/mixinextras/injector/wrapoperation/Operation";
	static final String PROTOCOL = "Lnet/minecraft/network/ConnectionProtocol;";
	static final String FLOW = "Lnet/minecraft/network/protocol/PacketFlow;";
	static final String VANILLA_DESC = "(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload$FallbackProvider;"
			+ "Ljava/util/List;)Lnet/minecraft/network/codec/StreamCodec;";
	static final String MERGED_DESC = "(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload$FallbackProvider;"
			+ "Ljava/util/List;" + PROTOCOL + FLOW + ")Lnet/minecraft/network/codec/StreamCodec;";
	static final String VANILLA_CALL = "L" + PAYLOAD + ";codec" + VANILLA_DESC;
	static final String MERGED_CALL = "L" + PAYLOAD + ";codec" + MERGED_DESC;
	/** The wrapper's own descriptor: the four arguments, then the {@code Operation} it forwards to. */
	static final String HANDLER_DESC = MERGED_DESC.substring(0, MERGED_DESC.indexOf(')'))
			+ "L" + OPERATION + ";)" + MERGED_DESC.substring(MERGED_DESC.indexOf(')') + 1);

	private FabricCustomPayloadCodecMixinAdapter() { }

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** @return the number of handlers adapted: 2, or 0 when anything differs from the shape this was written for */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		if (!enabled() || mixin == null || !MIXIN.equals(mixin.name)) return 0;
		ClassNode target = targets.apply(TARGET);
		if (target == null || !mergedCallsFourArgTwice(target)) return 0;

		int adapted = 0;
		for (MethodNode handler : mixin.methods) {
			AnnotationNode injector = MixinFit.injectorOf(handler);
			if (injector == null || !"Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;"
					.equals(injector.desc)) continue;
			if (MixinFit.value(injector, "slice") != null) continue;
			java.util.List<String> selectors = MixinFit.stringList(MixinFit.value(injector, "method"));
			if (selectors.size() != 1) continue;
			String selector = selectors.getFirst();
			// A bare name selects the only matching method; the descriptor form selects it too.
			if (!selector.equals("<clinit>") && !selector.equals("<clinit>()V")) continue;
			java.util.List<AnnotationNode> at = MixinFit.atNodes(injector);
			if (at.size() != 1) continue;
			AnnotationNode point = at.getFirst();
			if (!"INVOKE".equals(MixinFit.value(point, "value"))) continue;
			if (!VANILLA_CALL.equals(MixinFit.value(point, "target"))) continue;
			if (MixinFit.value(point, "ordinal") == null) continue;
			if (!widen(point, handler)) continue;
			adapted++;
		}
		if (adapted > 0) {
			ForbricLog.info("[Forbric/PayloadCodec] handed fabric-networking's " + adapted
					+ " payload-codec wrapper(s) the ConnectionProtocol and PacketFlow the merged base added to "
					+ "CustomPacketPayload.codec — they bound nothing before, so mod payload types never reached "
					+ "FabricCustomPayloadStreamCodec");
		}
		return adapted;
	}

	/** The merged {@code <clinit>} makes the four-argument call twice and the two-argument one never. */
	static boolean mergedCallsFourArgTwice(ClassNode target) {
		MethodNode clinit = target.methods.stream().filter(m -> "<clinit>".equals(m.name)).findFirst().orElse(null);
		if (clinit == null) return false;
		int four = 0;
		for (AbstractInsnNode insn : clinit.instructions) {
			if (!(insn instanceof MethodInsnNode call) || !PAYLOAD.equals(call.owner) || !"codec".equals(call.name)) continue;
			if (VANILLA_DESC.equals(call.desc)) return false;
			if (MERGED_DESC.equals(call.desc)) four++;
		}
		return four == 2;
	}

	/** Moves the annotation's target and rewrites the handler to take and forward the two new arguments. */
	static boolean widen(AnnotationNode point, MethodNode handler) {
		Type[] args = Type.getArgumentTypes(handler.desc);
		if (args.length != 3 || !OPERATION.equals(args[2].getInternalName())) return false;
		Type ret = Type.getReturnType(handler.desc);
		if (!"Lnet/minecraft/network/codec/StreamCodec;".equals(ret.getDescriptor())) return false;

		AnnotationNode target = null;
		for (int i = 0; i + 1 < point.values.size(); i += 2) {
			if ("target".equals(point.values.get(i))) {
				if (!VANILLA_CALL.equals(point.values.get(i + 1))) return false;
				point.values.set(i + 1, MERGED_CALL);
				target = point;
				break;
			}
		}
		if (target == null) return false;

		// The array Operation.call() receives grows from 2 to 4 entries, and the two new ones are appended after the
		// last aastore. Locals at or after the Operation slot move up by two first, so the inserts below address the
		// two brand-new slots.
		AbstractInsnNode lastAastore = null;
		InsnNode size = null;
		MethodInsnNode call = null;
		for (AbstractInsnNode insn : handler.instructions) {
			if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.ANEWARRAY
					&& "java/lang/Object".equals(type.desc)) {
				AbstractInsnNode before = insn.getPrevious();
				if (before != null && before.getOpcode() == Opcodes.ICONST_2) {
					handler.instructions.set(before, new InsnNode(Opcodes.ICONST_4));
					size = (InsnNode) before;
				} else if (before instanceof IntInsnNode push && push.getOpcode() == Opcodes.BIPUSH && push.operand == 2) {
					handler.instructions.set(before, new InsnNode(Opcodes.ICONST_4));
					size = (InsnNode) before;
				}
			}
			if (insn instanceof MethodInsnNode m && OPERATION.equals(m.owner) && "call".equals(m.name)) call = m;
		}
		if (size == null || call == null) return false;
		for (AbstractInsnNode insn = call.getPrevious(); insn != null; insn = insn.getPrevious()) {
			if (insn.getOpcode() == Opcodes.AASTORE) { lastAastore = insn; break; }
		}
		if (lastAastore == null) return false;

		for (AbstractInsnNode insn : handler.instructions) {
			if (insn instanceof VarInsnNode var && var.var >= 2) var.var += 2;
		}

		InsnList extra = new InsnList();
		extra.add(new InsnNode(Opcodes.DUP));
		extra.add(new InsnNode(Opcodes.ICONST_2));
		extra.add(new VarInsnNode(Opcodes.ALOAD, 2));
		extra.add(new InsnNode(Opcodes.AASTORE));
		extra.add(new InsnNode(Opcodes.DUP));
		extra.add(new InsnNode(Opcodes.ICONST_3));
		extra.add(new VarInsnNode(Opcodes.ALOAD, 3));
		extra.add(new InsnNode(Opcodes.AASTORE));
		handler.instructions.insert(lastAastore, extra);

		handler.desc = HANDLER_DESC;
		handler.maxLocals += 2;
		handler.maxStack = Math.max(handler.maxStack, 5);
		return true;
	}
}
