/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.forbric.kernel.util.Caught;

/**
 * Which implementation a virtual call selects for a receiver: whether it is still the one a given class or interface
 * declares, or one a subtype put in front of it.
 *
 * <p>Asked by the redirect handlers {@code CarrierDefaultChains} writes. A carrier routed a vanilla call through a method of
 * its own whose inherited implementation makes that very call; the moved redirect runs the inherited implementation with the
 * mod's handler in place of the vanilla call — but only for a receiver that still inherits it. A receiver whose class (a
 * carrier mod's block, sound, item) overrides the method gets its override, untouched, as without the mod.
 *
 * <p>The answer is the JVM's selection: the first class from the receiver's up that declares a concrete method of that name
 * and descriptor, else the one maximally specific interface default. A class whose reflective members cannot be read (a
 * signature naming a class that is absent) answers "overridden": the receiver then gets the carrier's own call, which is
 * what the game would have done without the mod.
 *
 * <p>One declaration below the declarer is not an override: a method whose whole body hands its own arguments, unchanged,
 * to an interface's default with {@code invokespecial} and returns its answer, when that default is the very implementation
 * the declarer's method is (an interface default) or forwards to the same way. The merged base writes such a forwarder into
 * a class that inherits two carriers' defaults of one signature, or re-declares the carrier's interface, and it runs exactly
 * that default: a bed that inherits NeoForge's {@code setBedOccupied} through {@code BedBlock}'s forwarder still inherits it.
 * The forwarder is read from the class's bytes as its loader serves them; one it cannot read is an override.
 */
public final class KernelDispatch {
	private static final ClassValue<Map<String, Boolean>> ANSWERS = new ClassValue<>() {
		@Override
		protected Map<String, Boolean> computeValue(Class<?> type) {
			return new ConcurrentHashMap<>();
		}
	};

	private KernelDispatch() {
	}

	/**
	 * Whether {@code receiver}'s class selects the implementation {@code declarer} (a binary class name) declares for
	 * {@code name} and the JVM {@code descriptor}.
	 */
	public static boolean inherits(Object receiver, String declarer, String name, String descriptor) {
		Class<?> type = receiver.getClass();
		return ANSWERS.get(type).computeIfAbsent(declarer + '#' + name + descriptor, key -> selects(type, declarer, name, descriptor));
	}

	static boolean selects(Class<?> type, String declarer, String name, String descriptor) {
		try {
			String implementation = null;
			for (Class<?> c = type; c != null; c = c.getSuperclass()) {
				if (c.getName().equals(declarer)) return true;
				for (Method method : c.getDeclaredMethods()) {
					if (!matches(method, name, descriptor) || Modifier.isStatic(method.getModifiers()) || Modifier.isPrivate(method.getModifiers())) continue;
					// The first declaration from the receiver's up is what the JVM selects: a forwarder to the very default the
					// declarer's implementation is, or forwards to, runs exactly that default.
					if (!Modifier.isAbstract(method.getModifiers())) {
						if (implementation == null) implementation = implementation(type, declarer, name, descriptor);
						String forwarded = forwardedDefault(c, name, descriptor);
						if (forwarded != null && forwarded.equals(implementation)) return true;
					}
					// A concrete one below the declarer is what the JVM selects; an abstract one re-declares it, and the class that
					// implements it again is below — both mean the declarer's is not the one run.
					return false;
				}
			}
			// No class declares it: the selected method is the maximally specific interface default.
			Set<Class<?>> candidates = new LinkedHashSet<>();
			Set<Class<?>> seen = new HashSet<>();
			Deque<Class<?>> pending = new ArrayDeque<>();
			for (Class<?> c = type; c != null; c = c.getSuperclass()) for (Class<?> each : c.getInterfaces()) pending.add(each);
			while (!pending.isEmpty()) {
				Class<?> each = pending.poll();
				if (!seen.add(each)) continue;
				for (Method method : each.getDeclaredMethods())
					if (matches(method, name, descriptor) && !Modifier.isStatic(method.getModifiers()) && !Modifier.isPrivate(method.getModifiers())) {
						candidates.add(each);
						break;
					}
				for (Class<?> parent : each.getInterfaces()) pending.add(parent);
			}
			candidates.removeIf(candidate -> candidates.stream().anyMatch(other -> other != candidate && candidate.isAssignableFrom(other)));
			return candidates.size() == 1 && candidates.iterator().next().getName().equals(declarer)
					&& !Modifier.isAbstract(declared(candidates.iterator().next(), name, descriptor).getModifiers());
		} catch (LinkageError | SecurityException unreadable) {
			Caught.expected(unreadable);
			return false;
		}
	}

	/**
	 * The implementation the declarer's method runs, as the binary name of the class or interface whose code it is: the
	 * declarer itself, or the interface whose default the declarer's method only forwards to. "" when the declarer is not
	 * among {@code type}'s supertypes.
	 */
	private static String implementation(Class<?> type, String declarer, String name, String descriptor) {
		Deque<Class<?>> pending = new ArrayDeque<>();
		Set<Class<?>> seen = new HashSet<>();
		pending.add(type);
		while (!pending.isEmpty()) {
			Class<?> each = pending.poll();
			if (!seen.add(each)) continue;
			if (each.getName().equals(declarer)) {
				if (each.isInterface()) return declarer;
				String forwarded = forwardedDefault(each, name, descriptor);
				return forwarded != null ? forwarded : declarer;
			}
			if (each.getSuperclass() != null) pending.add(each.getSuperclass());
			for (Class<?> parent : each.getInterfaces()) pending.add(parent);
		}
		return "";
	}

	/**
	 * The binary name of the interface whose default {@code owner}'s method {@code name}{@code descriptor} hands its own
	 * arguments to, in order and unchanged, with {@code invokespecial}, returning its answer and doing nothing else; null
	 * when the method does anything else, or its bytes cannot be read.
	 */
	static String forwardedDefault(Class<?> owner, String name, String descriptor) {
		ClassLoader loader = owner.getClassLoader();
		String resource = owner.getName().replace('.', '/') + ".class";
		try (java.io.InputStream in = loader == null ? ClassLoader.getSystemResourceAsStream(resource) : loader.getResourceAsStream(resource)) {
			if (in == null) return null;
			String[] found = {null};
			new org.objectweb.asm.ClassReader(in.readAllBytes()).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
				@Override
				public org.objectweb.asm.MethodVisitor visitMethod(int access, String method, String desc, String signature, String[] exceptions) {
					if (!method.equals(name) || !desc.equals(descriptor) || (access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0) return null;
					return new Forwarder(desc, found);
				}
			}, org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
			return found[0];
		} catch (java.io.IOException | RuntimeException unreadable) {
			Caught.expected(unreadable);
			return null;
		}
	}

	/** Reads one method: {@code this}, each argument in order, one {@code invokespecial} of an interface's same method, return. */
	private static final class Forwarder extends org.objectweb.asm.MethodVisitor {
		private final org.objectweb.asm.Type[] arguments;
		private final org.objectweb.asm.Type answer;
		private final String desc;
		private final String[] found;
		private int step, slot = 1;
		private String target;
		private boolean broken;

		Forwarder(String desc, String[] found) {
			super(org.objectweb.asm.Opcodes.ASM9);
			this.desc = desc;
			this.arguments = org.objectweb.asm.Type.getArgumentTypes(desc);
			this.answer = org.objectweb.asm.Type.getReturnType(desc);
			this.found = found;
		}

		private void expect(boolean ok) {
			if (!ok) broken = true;
		}

		@Override
		public void visitVarInsn(int opcode, int var) {
			if (broken) return;
			if (step == 0) expect(opcode == org.objectweb.asm.Opcodes.ALOAD && var == 0);
			else if (step <= arguments.length) {
				org.objectweb.asm.Type argument = arguments[step - 1];
				expect(opcode == argument.getOpcode(org.objectweb.asm.Opcodes.ILOAD) && var == slot);
				slot += argument.getSize();
			} else broken = true;
			step++;
		}

		@Override
		public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
			if (broken) return;
			expect(step == arguments.length + 1 && opcode == org.objectweb.asm.Opcodes.INVOKESPECIAL && itf && descriptor.equals(desc) && target == null);
			target = owner.replace('/', '.');
			step++;
		}

		@Override
		public void visitInsn(int opcode) {
			if (broken) return;
			expect(step == arguments.length + 2 && opcode == answer.getOpcode(org.objectweb.asm.Opcodes.IRETURN));
			step++;
		}

		@Override
		public void visitFieldInsn(int opcode, String owner, String name, String descriptor) { broken = true; }

		@Override
		public void visitTypeInsn(int opcode, String type) { broken = true; }

		@Override
		public void visitIntInsn(int opcode, int operand) { broken = true; }

		@Override
		public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) { broken = true; }

		@Override
		public void visitLdcInsn(Object value) { broken = true; }

		@Override
		public void visitIincInsn(int var, int increment) { broken = true; }

		@Override
		public void visitInvokeDynamicInsn(String name, String descriptor, org.objectweb.asm.Handle bootstrap, Object... arguments) { broken = true; }

		@Override
		public void visitTableSwitchInsn(int min, int max, org.objectweb.asm.Label dflt, org.objectweb.asm.Label... labels) { broken = true; }

		@Override
		public void visitLookupSwitchInsn(org.objectweb.asm.Label dflt, int[] keys, org.objectweb.asm.Label[] labels) { broken = true; }

		@Override
		public void visitMultiANewArrayInsn(String descriptor, int dimensions) { broken = true; }

		@Override
		public void visitTryCatchBlock(org.objectweb.asm.Label start, org.objectweb.asm.Label end, org.objectweb.asm.Label handler, String type) { broken = true; }

		@Override
		public void visitEnd() {
			if (!broken && step == arguments.length + 3 && target != null) found[0] = target;
		}
	}

	private static Method declared(Class<?> owner, String name, String descriptor) {
		for (Method method : owner.getDeclaredMethods()) if (matches(method, name, descriptor)) return method;
		throw new IllegalStateException(owner + " declares no " + name + descriptor);
	}

	private static boolean matches(Method method, String name, String descriptor) {
		return method.getName().equals(name) && MethodType.methodType(method.getReturnType(), method.getParameterTypes()).toMethodDescriptorString().equals(descriptor);
	}
}
