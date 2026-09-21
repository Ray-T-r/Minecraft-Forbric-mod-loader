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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The shape that killed the 97-jar client: EntityCulling's mixin adds an interface to
 * {@code BlockEntityRenderer}, the merged base already gave it NeoForge's, both default
 * {@code getRenderBoundingBox}, and {@code CampfireRenderer} overrides neither — so the first campfire threw
 * {@code IncompatibleClassChangeError: Conflicting default methods}.
 *
 * <p>Real classes are built here rather than mocked, and the negative case is loaded by a real class loader and
 * CALLED: the error only exists at invocation, so a test that merely inspects bytes cannot tell a fixed conflict
 * from one that was never there.
 */
class InterfaceDefaultConflictRepairTest {
	private final Map<String, byte[]> world = new HashMap<>();
	private final InterfaceDefaultConflictRepair repair = new InterfaceDefaultConflictRepair(world::get);

	/** An interface with one {@code default String who()} returning {@code answer}. */
	private byte[] iface(String name, String answer, String... supers) {
		ClassNode node = new ClassNode();
		node.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
				name, null, "java/lang/Object", supers.length == 0 ? null : supers);
		if (answer != null) {
			MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, "who", "()Ljava/lang/String;", null, null);
			m.visitLdcInsn(answer);
			m.visitInsn(Opcodes.ARETURN);
			m.visitMaxs(1, 1);
			node.methods.add(m);
		}
		return write(node, name);
	}

	/** A concrete class implementing {@code interfaces} and declaring nothing of its own. */
	private byte[] implementor(String name, String... interfaces) {
		ClassNode node = new ClassNode();
		node.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", interfaces);
		MethodNode ctor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		ctor.visitInsn(Opcodes.RETURN);
		ctor.visitMaxs(1, 1);
		node.methods.add(ctor);
		return write(node, name);
	}

	private byte[] write(ClassNode node, String name) {
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		byte[] bytes = writer.toByteArray();
		world.put(name, bytes);
		return bytes;
	}

	private static MethodNode find(byte[] bytes, String name) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		for (MethodNode m : node.methods) {
			if (m.name.equals(name)) return m;
		}
		return null;
	}

	private Class<?> define(byte[] bytes, String name) {
		ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
			@Override protected Class<?> findClass(String binary) throws ClassNotFoundException {
				byte[] found = world.get(binary.replace('.', '/'));
				if (found == null) throw new ClassNotFoundException(binary);
				return defineClass(binary, found, 0, found.length);
			}
		};
		try {
			return loader.loadClass(name.replace('/', '.'));
		} catch (ClassNotFoundException e) {
			throw new AssertionError(e);
		}
	}

	@Test
	void aConflictWeavingIntroducedGetsTheOverrideTheJvmAskedFor() throws Exception {
		byte[] base = iface("net/neoforged/Ext", "neoforge");
		byte[] added = iface("mod/FabricExt", "mod");
		assertNotNull(base);
		assertNotNull(added);
		byte[] before = implementor("game/Renderer", "net/neoforged/Ext");
		byte[] after = implementor("game/Renderer", "net/neoforged/Ext", "mod/FabricExt");

		byte[] fixed = repair.transform("game.Renderer", before, after);

		MethodNode added0 = find(fixed, "who");
		assertNotNull(added0, "the implementor must be given the override it was missing");
		world.put("game/Renderer", fixed);
		// The mod's, not the base's: a mod that adds an interface with a default added it to supply behaviour.
		Class<?> type = define(fixed, "game/Renderer");
		assertEquals("mod", type.getMethod("who").invoke(type.getDeclaredConstructor().newInstance()));
	}

	@Test
	void withoutTheRepairThatSameClassThrowsWhenTheMethodIsCalled() throws Exception {
		iface("net/neoforged/Ext", "neoforge");
		iface("mod/FabricExt", "mod");
		byte[] after = implementor("game/Renderer", "net/neoforged/Ext", "mod/FabricExt");

		// Not at load — at CALL. That is the whole reason this defect reached a running world rather than boot.
		Class<?> type = define(after, "game/Renderer");
		Object instance = type.getDeclaredConstructor().newInstance();
		var thrown = assertThrows(java.lang.reflect.InvocationTargetException.class,
				() -> type.getMethod("who").invoke(instance));
		assertTrue(thrown.getCause() instanceof IncompatibleClassChangeError, String.valueOf(thrown.getCause()));
	}

	@Test
	void aConflictTheLibraryAlreadyHadIsLeftAlone() {
		// fastutil's FloatList inherits removeIf/forEach/stream from both java.util.List and FloatCollection and
		// works. Nothing wove it; rewriting it would swap its primitive-specialised bodies for the boxing ones.
		iface("java/util/List", "boxed");
		iface("fastutil/FloatCollection", "primitive");
		byte[] both = implementor("fastutil/FloatList", "java/util/List", "fastutil/FloatCollection");

		assertNull(find(repair.transform("fastutil.FloatList", both, both), "who"),
				"a conflict that predates weaving is the library's own shape, not merge damage");
	}

	@Test
	void anInterfaceThatRefinesTheOtherNeedsNoHelp() {
		// Specificity already orders these, and adding an override would pin the less specific one forever.
		iface("base/Parent", "parent");
		byte[] child = iface("base/Child", "child", "base/Parent");
		assertNotNull(child);
		byte[] before = implementor("game/Thing");
		byte[] after = implementor("game/Thing", "base/Parent", "base/Child");

		assertNull(find(repair.transform("game.Thing", before, after), "who"));
	}

	@Test
	void aClassThatAlreadyOverridesIsUntouched() {
		iface("net/neoforged/Ext", "neoforge");
		iface("mod/FabricExt", "mod");
		ClassNode node = new ClassNode();
		node.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "game/Own", null, "java/lang/Object",
				new String[] { "net/neoforged/Ext", "mod/FabricExt" });
		MethodNode own = new MethodNode(Opcodes.ACC_PUBLIC, "who", "()Ljava/lang/String;", null, null);
		own.visitLdcInsn("its own");
		own.visitInsn(Opcodes.ARETURN);
		own.visitMaxs(1, 1);
		node.methods.add(own);
		byte[] after = write(node, "game/Own");

		byte[] out = repair.transform("game.Own", implementor("game/Own"), after);
		ClassNode read = new ClassNode();
		new ClassReader(out).accept(read, 0);
		assertEquals(1, read.methods.stream().filter(m -> m.name.equals("who")).count(),
				"the class had settled it already; a second copy would not even verify");
	}

	@Test
	void theSwitchLeavesTheConflictInPlace() {
		iface("net/neoforged/Ext", "neoforge");
		iface("mod/FabricExt", "mod");
		byte[] before = implementor("game/Renderer", "net/neoforged/Ext");
		byte[] after = implementor("game/Renderer", "net/neoforged/Ext", "mod/FabricExt");

		System.setProperty(InterfaceDefaultConflictRepair.SWITCH, "off");
		try {
			assertNull(find(repair.transform("game.Renderer", before, after), "who"));
		} finally {
			System.clearProperty(InterfaceDefaultConflictRepair.SWITCH);
		}
	}
}
