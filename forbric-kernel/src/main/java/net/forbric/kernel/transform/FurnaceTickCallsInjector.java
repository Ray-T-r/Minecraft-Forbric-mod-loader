/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.List;

import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * A furnace smelts again: NeoForge's {@code serverTick} calls MinecraftForge's instance methods on the furnace it ticks.
 *
 * <p>MinecraftForge made {@code AbstractFurnaceBlockEntity.canBurn}, {@code consumeFuel} and {@code burn} protected
 * instance methods, so a mod's furnace can override them; NeoForge kept vanilla's private static ones, with the same
 * descriptors. The merge kept MinecraftForge's three methods and NeoForge's {@code serverTick}, whose
 * {@code invokestatic} of each resolves to an instance method: the first tick of any furnace, smoker or blast furnace
 * with something to smelt threw {@code IncompatibleClassChangeError: Expected static method canBurn}.
 *
 * <p>Each such call now goes through a static bridge that takes the ticked furnace as one more argument and calls the
 * method on it, which is what MinecraftForge's own {@code serverTick} does ({@code invokevirtual} on its
 * {@code blockEntity} parameter). The call site only pushes that parameter after the arguments it already pushed, so
 * no frame changes. Applied only where the proof holds: the enclosing method is static, exactly one of its parameters
 * is this class, that slot is never written, and the called method is this class's own instance method of that
 * descriptor. {@code -Dforbric.furnaceTickCalls=off} leaves the class as merged.
 */
public final class FurnaceTickCallsInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.furnaceTickCalls";
	static final String FURNACE = "net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity";
	static final String BRIDGE_PREFIX = "forbric$";

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override public String name() { return "forbric-furnace-tick-calls"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("furnace tick calls explicitly left as merged with -D" + PROPERTY + "=off");
		return AnchorSet.of(new AnchorSet.Anchor(FURNACE, AnchorSet.Severity.REQUIRED,
				"every furnace, smoker and blast furnace throws on its first smelt"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0 || !FURNACE.equals(className)) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		List<String> bridged = repair(node);
		if (bridged.isEmpty()) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/Furnace] %s's tick calls %s on the furnace it ticks — they are MinecraftForge's instance "
				+ "methods, and calling them static threw on every furnace's first smelt", className, String.join(", ", bridged));
		return writer.toByteArray();
	}

	/** Bridges every static-shaped call to an own instance method from a static method holding one receiver. */
	static List<String> repair(ClassNode owner) {
		List<String> bridged = new ArrayList<>();
		List<MethodNode> added = new ArrayList<>();
		for (MethodNode method : owner.methods) {
			if ((method.access & Opcodes.ACC_STATIC) == 0) continue;
			List<MethodInsnNode> calls = new ArrayList<>();
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.equals(owner.name)) {
					MethodNode target = own(owner, call.name, call.desc);
					if (target != null && (target.access & Opcodes.ACC_STATIC) == 0) calls.add(call);
				}
			}
			if (calls.isEmpty()) continue;
			int receiver = receiverSlot(owner, method);
			if (receiver < 0) {
				ForbricLog.warn("[Forbric/Furnace] %s.%s calls an instance method as static but has no one furnace to call it on; "
						+ "left as merged", owner.name.replace('/', '.'), method.name);
				continue;
			}
			for (MethodInsnNode call : calls) {
				String bridgeName = BRIDGE_PREFIX + call.name;
				String bridgeDesc = bridgeDesc(owner.name, call.desc);
				if (own(owner, bridgeName, bridgeDesc) == null && added.stream().noneMatch(m -> m.name.equals(bridgeName) && m.desc.equals(bridgeDesc))) {
					added.add(bridge(owner.name, call.name, call.desc, bridgeName, bridgeDesc));
				}
				method.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, receiver));
				call.name = bridgeName;
				call.desc = bridgeDesc;
				if (!bridged.contains(bridgeName.substring(BRIDGE_PREFIX.length()))) bridged.add(bridgeName.substring(BRIDGE_PREFIX.length()));
			}
		}
		owner.methods.addAll(added);
		return bridged;
	}

	/** The one parameter slot of the class's own type that the method never writes, or -1. */
	static int receiverSlot(ClassNode owner, MethodNode method) {
		Type[] params = Type.getArgumentTypes(method.desc);
		int slot = 0, found = -1;
		for (Type param : params) {
			if (param.getSort() == Type.OBJECT && param.getInternalName().equals(owner.name)) {
				if (found >= 0) return -1;
				found = slot;
			}
			slot += param.getSize();
		}
		if (found < 0) return -1;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE && store.var == found) return -1;
		}
		return found;
	}

	/** {@code (args…)R} → {@code (args…, Owner)R}. */
	static String bridgeDesc(String owner, String desc) {
		Type[] args = Type.getArgumentTypes(desc);
		Type[] with = java.util.Arrays.copyOf(args, args.length + 1);
		with[args.length] = Type.getObjectType(owner);
		return Type.getMethodDescriptor(Type.getReturnType(desc), with);
	}

	/** {@code private static R forbric$m(args…, Owner self) { return self.m(args…); }} */
	static MethodNode bridge(String owner, String name, String desc, String bridgeName, String bridgeDesc) {
		MethodNode bridge = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
				bridgeName, bridgeDesc, null, null);
		Type[] args = Type.getArgumentTypes(desc);
		int self = 0;
		for (Type arg : args) self += arg.getSize();
		bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, self));
		int slot = 0;
		for (Type arg : args) {
			bridge.instructions.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD), slot));
			slot += arg.getSize();
		}
		bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, name, desc, false));
		bridge.instructions.add(new InsnNode(Type.getReturnType(desc).getOpcode(Opcodes.IRETURN)));
		bridge.maxLocals = self + 1;
		bridge.maxStack = self + 1;
		return bridge;
	}

	private static MethodNode own(ClassNode owner, String name, String desc) {
		for (MethodNode method : owner.methods) if (method.name.equals(name) && method.desc.equals(desc)) return method;
		return null;
	}
}
