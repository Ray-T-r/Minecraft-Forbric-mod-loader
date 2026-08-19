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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The rewrite that gives a Forge registry wrapper back the alias resolution its overrides hid.
 *
 * <p>The method table is the whole substance of this transformer, so it is what gets asserted: every lookup
 * fabric-api's mixin rewrites — plus the two the wrapper overrides out of {@code Registry}'s defaults — and
 * nothing else. The negative cases matter as much: a wrapper method keyed by something other than an id must not
 * be touched, or the hook is handed an object it cannot cast.
 */
class RegistryAliasParityInjectorTest {
	private static final String WRAPPER = "net/minecraftforge/registries/NamespacedWrapper";
	private static final String ID = "net/minecraft/resources/Identifier";
	private static final String KEY = "net/minecraft/resources/ResourceKey";
	private static final String HOOK = "net/forbric/kernel/boot/KernelRegistryAliases";

	@Test
	void rewritesEveryIdKeyedLookupTheWrapperOverrides() {
		byte[] out = transform(WRAPPER);
		assertNotNull(out);

		for (String name : List.of("get", "getValue", "containsKey", "getOptional", "getHolder")) {
			assertHookAtHead(out, name, "(L" + ID + ";)Ljava/lang/Object;", "resolveId", ID);
		}
		for (String name : List.of("get", "getValue", "containsKey", "registrationInfo", "getOrCreateHolderOrThrow")) {
			assertHookAtHead(out, name, "(L" + KEY + ";)Ljava/lang/Object;", "resolveKey", KEY);
		}
	}

	@Test
	void leavesLookupsKeyedBySomethingElseAlone() {
		// byId(int) and get(TagKey) are wrapper overrides too. Rewriting them would hand resolveId an int or a tag
		// and then CHECKCAST it to Identifier — a verifier error at best, wrong answers at worst.
		byte[] out = transform(WRAPPER);
		assertEquals(0, hookCalls(out, "byId", "(I)Ljava/lang/Object;"));
		assertEquals(0, hookCalls(out, "get", "(Lnet/minecraft/tags/TagKey;)Ljava/util/Optional;"));
		assertEquals(0, hookCalls(out, "keySet", "()Ljava/util/Set;"));
	}

	@Test
	void theDefaultedSubclassIsCoveredToo() {
		// NamespacedDefaultedWrapper overrides getValue(Identifier) a second time; the inherited rewrite does not
		// reach an override, so the subclass has to be a target in its own right.
		byte[] out = new RegistryAliasParityInjector().transform(
				"net.minecraftforge.registries.NamespacedDefaultedWrapper",
				wrapper("net/minecraftforge/registries/NamespacedDefaultedWrapper"), null);
		assertHookAtHead(out, "getValue", "(L" + ID + ";)Ljava/lang/Object;", "resolveId", ID);
	}

	@Test
	void leavesEveryOtherClassAlone() {
		byte[] original = wrapper("some/other/Registry");
		assertArrayEquals(original,
				new RegistryAliasParityInjector().transform("some.other.Registry", original, null));
	}

	@Test
	void failsSoftWhenNothingMatches() {
		// A Forge that stopped overriding the lookups: hand the class back untouched and warn, never throw.
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, WRAPPER, null, "java/lang/Object", null);
		cw.visitEnd();
		byte[] empty = cw.toByteArray();
		assertArrayEquals(empty, new RegistryAliasParityInjector().transform(WRAPPER.replace('/', '.'), empty, null));
	}

	// ---------------------------------------------------------------- helpers

	private static byte[] transform(String internalName) {
		return new RegistryAliasParityInjector().transform(
				internalName.replace('/', '.'), wrapper(internalName), null);
	}

	private static void assertHookAtHead(byte[] classBytes, String name, String desc, String hook, String cast) {
		List<AbstractInsnNode> real = realInstructions(classBytes, name, desc);
		assertTrue(real.size() >= 5, name + desc + " was not rewritten");

		assertEquals(Opcodes.ALOAD, real.get(0).getOpcode());
		assertEquals(0, ((VarInsnNode) real.get(0)).var, "arg0 is the registry itself");
		assertEquals(Opcodes.ALOAD, real.get(1).getOpcode());
		assertEquals(1, ((VarInsnNode) real.get(1)).var);

		MethodInsnNode call = (MethodInsnNode) real.get(2);
		assertEquals(HOOK, call.owner);
		assertEquals(hook, call.name);
		assertEquals("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", call.desc);

		assertEquals(cast, ((TypeInsnNode) real.get(3)).desc);
		assertEquals(Opcodes.ASTORE, real.get(4).getOpcode());
		assertEquals(1, ((VarInsnNode) real.get(4)).var, "the resolved value goes back into the slot it came from");
	}

	private static int hookCalls(byte[] classBytes, String name, String desc) {
		int n = 0;
		for (AbstractInsnNode insn : realInstructions(classBytes, name, desc)) {
			if (insn instanceof MethodInsnNode call && HOOK.equals(call.owner)) n++;
		}
		return n;
	}

	private static List<AbstractInsnNode> realInstructions(byte[] classBytes, String name, String desc) {
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		for (MethodNode m : node.methods) {
			if (name.equals(m.name) && desc.equals(m.desc)) {
				List<AbstractInsnNode> real = new ArrayList<>();
				for (AbstractInsnNode insn : m.instructions) {
					if (insn.getOpcode() >= 0) real.add(insn);
				}
				return real;
			}
		}
		throw new AssertionError(name + desc + " not found");
	}

	/** The wrapper's shape: every lookup it really overrides, plus three that must NOT be rewritten. */
	private static byte[] wrapper(String internalName) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "net/minecraft/core/MappedRegistry", null);
		for (String name : List.of("get", "getValue", "containsKey", "getOptional", "getHolder")) {
			body(cw, name, "(L" + ID + ";)Ljava/lang/Object;");
		}
		for (String name : List.of("get", "getValue", "containsKey", "registrationInfo", "getOrCreateHolderOrThrow")) {
			body(cw, name, "(L" + KEY + ";)Ljava/lang/Object;");
		}
		body(cw, "byId", "(I)Ljava/lang/Object;");
		body(cw, "get", "(Lnet/minecraft/tags/TagKey;)Ljava/util/Optional;");
		body(cw, "keySet", "()Ljava/util/Set;");
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static void body(ClassWriter cw, String name, String desc) {
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, desc, null, null);
		mv.visitCode();
		mv.visitInsn(Opcodes.ACONST_NULL);
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(1, 2);
		mv.visitEnd();
	}
}
