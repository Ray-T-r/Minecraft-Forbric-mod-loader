/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Checks that the hooks the kernel splices into guest bytecode actually exist.
 *
 * <p>An injector names its hook as three unrelated strings — owner, name, descriptor — and nothing connects them to
 * the Java method they are supposed to reach. Rename or re-sign that method and every injector test stays green:
 * they assert that the call was emitted and what it was passed, never that it resolves. The cost lands at runtime as
 * a {@code NoSuchMethodError} raised from inside the game, in a stack that names Minecraft rather than the kernel.
 *
 * <p>This became checkable when the three interop hooks moved out of the previous-generation loader and into
 * {@code net.forbric.kernel.interop}: they are now on the test classpath, so the triple can simply be resolved.
 * Hooks that are not kernel classes are skipped — there is nothing to resolve them against.
 */
final class InteropHookAssertions {
	static final String INTEROP_PACKAGE = "net/forbric/kernel/interop/";

	private InteropHookAssertions() {
	}

	/** Fails unless every {@code net.forbric.kernel.interop} call in {@code classBytes} names a real method. */
	static void assertEveryInteropCallResolves(byte[] classBytes) {
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		int checked = 0;
		for (MethodNode method : node.methods) {
			if (method.instructions == null) continue;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof MethodInsnNode call) || !call.owner.startsWith(INTEROP_PACKAGE)) continue;
				assertResolves(call);
				checked++;
			}
		}
		if (checked == 0) {
			fail("no interop call in " + node.name + " — the injection this asserts on did not happen");
		}
	}

	private static void assertResolves(MethodInsnNode call) {
		Class<?> owner;
		try {
			owner = Class.forName(call.owner.replace('/', '.'));
		} catch (ClassNotFoundException e) {
			throw new AssertionError("injected hook owner does not exist: " + call.owner, e);
		}

		List<String> sameName = new ArrayList<>();
		for (Method m : owner.getDeclaredMethods()) {
			if (!m.getName().equals(call.name)) continue;
			String desc = Type.getMethodDescriptor(m);
			sameName.add(desc + (Modifier.isStatic(m.getModifiers()) ? " (static)" : " (INSTANCE)")
					+ (Modifier.isPublic(m.getModifiers()) ? "" : " (NOT PUBLIC)"));
			if (!desc.equals(call.desc)) continue;
			if (call.getOpcode() == Opcodes.INVOKESTATIC && !Modifier.isStatic(m.getModifiers())) {
				fail("injected INVOKESTATIC " + describe(call) + " resolves to an instance method");
			}
			if (!Modifier.isPublic(m.getModifiers())) {
				// Guest bytecode lives in another package and another class loader; anything less than public
				// links here and throws IllegalAccessError there.
				fail("injected hook " + describe(call) + " is not public");
			}
			return;
		}
		fail("injected hook " + describe(call) + " does not exist. "
				+ (sameName.isEmpty() ? "No method of that name at all." : "Same name, other shapes: " + sameName));
	}

	private static String describe(MethodInsnNode call) {
		return call.owner + "." + call.name + call.desc;
	}
}
