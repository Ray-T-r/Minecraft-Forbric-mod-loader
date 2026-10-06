/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/** Materializes an ordinary own virtual lambda reference as one exact invocation, preserving its receiver and ABI. */
public final class LambdaInvocationThunkInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.lambdaInvocationThunks";
	public static final String PREFIX = "$forbric$lambdaInvoke$";

	@Override public AnchorSet anchors() {
		return AnchorSet.scanned("ordinary own virtual LambdaMetafactory references, materialized without changing their method type");
	}

	@Override public byte[] transform(String name, byte[] bytes, TransformContext context) {
		if ("off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"))) return bytes;
		ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
		// A private interface method's dispatch constraints differ. Special/interface/static/constructor references
		// are left unchanged until their separate invocation contracts have their own proof.
		if ((node.access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION)) != 0) return bytes;
		boolean changed = false;
		for (MethodNode method : new ArrayList<>(node.methods)) for (AbstractInsnNode instruction : method.instructions) {
			if (!(instruction instanceof InvokeDynamicInsnNode indy) || !ordinaryFactory(indy)
					|| !(indy.bsmArgs[1] instanceof Handle original) || original.getTag() != Opcodes.H_INVOKEVIRTUAL
					|| original.isInterface() || !original.getOwner().equals(node.name)
					|| original.getName().equals("<init>") || original.getName().equals("<clinit>")) continue;
			MethodNode already = declared(node, original.getName(), original.getDesc());
			if (already != null && original.getName().startsWith(PREFIX) && isThunk(node.name, already)) continue;
			// A compiler lambda-expression implementation already contains ordinary invocations. Its handle is
			// also the provenance of captures/control flow used by other adapters; materializing it adds no site.
			if (already != null && (already.access & Opcodes.ACC_SYNTHETIC) != 0) continue;
			String thunkName = nameFor(node.name, original);
			for (int suffix = 0; ; suffix++) {
				String candidate = suffix == 0 ? thunkName : thunkName + "$" + suffix;
				MethodNode existing = declared(node, candidate, original.getDesc());
				if (existing != null && !callsExactly(node.name, existing, original)) continue;
				if (existing == null) node.methods.add(thunk(candidate, original, already));
				indy.bsmArgs[1] = new Handle(original.getTag(), original.getOwner(), candidate, original.getDesc(), false);
				changed = true;
				break;
			}
		}
		if (!changed) return bytes;
		ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray();
	}

	private static boolean ordinaryFactory(InvokeDynamicInsnNode indy) {
		// altMetafactory may request serialization, marker interfaces or bridge methods. Its descriptor and
		// serialized implementation identity are not part of this ordinary-method-reference contract.
		return indy.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory") && indy.bsm.getName().equals("metafactory")
				&& indy.bsmArgs.length == 3 && indy.bsmArgs[0] instanceof Type sam && sam.getSort() == Type.METHOD
				&& indy.bsmArgs[2] instanceof Type actual && actual.getSort() == Type.METHOD;
	}

	private static MethodNode thunk(String name, Handle original, MethodNode declaration) {
		int access = Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
		if (declaration != null && (declaration.access & Opcodes.ACC_VARARGS) != 0) access |= Opcodes.ACC_VARARGS;
		MethodNode thunk = new MethodNode(access, name, original.getDesc(), null,
				declaration == null || declaration.exceptions == null ? null : declaration.exceptions.toArray(String[]::new));
		thunk.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		int slot = 1;
		for (Type argument : Type.getArgumentTypes(original.getDesc())) {
			thunk.instructions.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), slot)); slot += argument.getSize();
		}
		thunk.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, original.getOwner(), original.getName(), original.getDesc(), false));
		thunk.instructions.add(new InsnNode(Type.getReturnType(original.getDesc()).getOpcode(Opcodes.IRETURN)));
		thunk.maxLocals = slot; thunk.maxStack = Math.max(slot, Type.getReturnType(original.getDesc()).getSize());
		return thunk;
	}

	/** A private, nonstatic thunk is identified from its complete body rather than its name alone. */
	public static boolean isThunk(String owner, MethodNode method) {
		MethodInsnNode call = invocation(method);
		return call != null && call.owner.equals(owner) && !call.name.equals(method.name)
				&& call.getOpcode() == Opcodes.INVOKEVIRTUAL
				&& (method.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_STATIC)) == (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC)
				&& loadsAndReturns(method, call);
	}

	/** Returns the sole call only when it has the thunk's exact descriptor; useful to a separately proven retarget. */
	public static MethodInsnNode invocation(MethodNode method) {
		MethodInsnNode result = null;
		for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode call) {
			if (result != null || !call.desc.equals(method.desc)) return null; result = call;
		}
		return result;
	}

	private static boolean callsExactly(String owner, MethodNode method, Handle original) {
		MethodInsnNode call = invocation(method);
		return isThunk(owner, method) && call.name.equals(original.getName()) && call.owner.equals(original.getOwner())
				&& call.desc.equals(original.getDesc()) && !call.itf;
	}

	private static boolean loadsAndReturns(MethodNode method, MethodInsnNode call) {
		List<AbstractInsnNode> code = new ArrayList<>();
		for (AbstractInsnNode instruction : method.instructions) if (instruction.getOpcode() >= 0) code.add(instruction);
		Type[] arguments = Type.getArgumentTypes(method.desc);
		if (code.size() != arguments.length + 3 || !(code.getFirst() instanceof VarInsnNode receiver)
				|| receiver.getOpcode() != Opcodes.ALOAD || receiver.var != 0 || code.get(code.size() - 2) != call
				|| code.getLast().getOpcode() != Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN)) return false;
		int slot = 1;
		for (int i = 0; i < arguments.length; i++) {
			if (!(code.get(i + 1) instanceof VarInsnNode argument) || argument.getOpcode() != arguments[i].getOpcode(Opcodes.ILOAD)
					|| argument.var != slot) return false;
			slot += arguments[i].getSize();
		}
		return method.tryCatchBlocks == null || method.tryCatchBlocks.isEmpty();
	}

	private static MethodNode declared(ClassNode owner, String name, String desc) {
		return owner.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElse(null);
	}

	public static String nameFor(String owner, Handle original) {
		String identity = owner + "\0" + original.getTag() + "\0" + original.getName() + "\0" + original.getDesc();
		try { return PREFIX + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8))).substring(0, 24); }
		catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
	}
}
