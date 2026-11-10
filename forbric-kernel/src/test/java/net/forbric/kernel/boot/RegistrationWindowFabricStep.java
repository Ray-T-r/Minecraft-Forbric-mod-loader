/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * What the registration window hands {@code dispatchRegisterEvents} as its Fabric step, followed through the
 * {@code invokedynamic} that builds it.
 *
 * <p>Since K3 the dedicated server's Fabric mains (and Fabric's HEAD freeze point) run from {@code runFabricMainsInTheWindow},
 * which {@code registerNeoForgeContent} reaches only through a lambda passed to {@code dispatchRegisterEvents}. A bytecode
 * walk of {@code registerNeoForgeContent} does not enter an {@code invokedynamic}, so a walk of each method on its own stays
 * green when the lambda stops calling it ({@code dispatchRegisterEvents(cl, () -> {})}: no Fabric main on any dedicated
 * server). This follows the link the walks cannot.
 */
final class RegistrationWindowFabricStep {
	private RegistrationWindowFabricStep() {
	}

	/**
	 * The methods the Runnable handed to {@code registerNeoForgeContent}'s one {@code dispatchRegisterEvents} call calls, in
	 * order; empty when that argument is not a lambda built right at the call (a field, a no-op method reference, …) or the
	 * compiled class is absent.
	 */
	static List<String> calls() throws Exception {
		ClassNode node = lifecycle();
		if (node == null) return List.of();
		MethodNode content = method(node, "registerNeoForgeContent");
		if (content == null) return List.of();
		MethodInsnNode dispatch = null;
		for (AbstractInsnNode insn : content.instructions) {
			if (insn instanceof MethodInsnNode call && call.name.equals("dispatchRegisterEvents")) {
				if (dispatch != null) return List.of();
				dispatch = call;
			}
		}
		if (dispatch == null) return List.of();
		AbstractInsnNode arg = dispatch.getPrevious();
		while (arg != null && arg.getOpcode() < 0) arg = arg.getPrevious();
		if (!(arg instanceof InvokeDynamicInsnNode indy) || !"java/lang/invoke/LambdaMetafactory".equals(indy.bsm.getOwner())
				|| indy.bsmArgs.length < 2 || !(indy.bsmArgs[1] instanceof Handle impl) || !impl.getOwner().equals(node.name)) {
			return List.of();
		}
		MethodNode lambda = null;
		for (MethodNode m : node.methods) {
			if (m.name.equals(impl.getName()) && m.desc.equals(impl.getDesc())) lambda = m;
		}
		if (lambda == null) return List.of();
		List<String> calls = new ArrayList<>();
		for (AbstractInsnNode insn : lambda.instructions) {
			if (insn instanceof MethodInsnNode call) calls.add(call.name);
		}
		return calls;
	}

	private static ClassNode lifecycle() throws Exception {
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main",
				"net", "forbric", "kernel", "boot", "KernelLifecycle.class");
		if (!Files.isRegularFile(compiled)) return null;
		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(compiled)).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name) {
		for (MethodNode m : node.methods) {
			if (m.name.equals(name)) return m;
		}
		return null;
	}
}
