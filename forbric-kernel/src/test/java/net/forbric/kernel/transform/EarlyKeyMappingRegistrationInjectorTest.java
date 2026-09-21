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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.fabricmc.api.EnvType;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * fabric-api's key-mapping registry rejects registration that is too LATE by reading
 * {@code Minecraft.getInstance().options}, and so it also dies — on a {@code NullPointerException} — when
 * registration is too EARLY, which is the one case that is provably safe. Under Forbric a guest mixin that waits
 * for the first registry freeze wakes a mod's {@code <clinit>} before {@code Minecraft} exists, so the early case
 * is reachable here and was a whole-client crash in {@code Bootstrap} (Flashback).
 *
 * <p>Behaviour runs on a stand-in with the real names, descriptors and instruction shape — loading the real
 * fabric-api module would drag in {@code KeyMapping}, {@code Options} and half the client. The three states that
 * matter are all exercised through a real invocation of the transformed method, not asserted against bytecode
 * text: no instance at all, an instance whose options are not built, and an instance whose options ARE built —
 * the last one still has to throw, or the repair has traded one silent failure for another.
 */
@org.junit.jupiter.api.parallel.ResourceLock("system-properties")
class EarlyKeyMappingRegistrationInjectorTest {
	private static final String IMPL = "net.fabricmc.fabric.impl.client.keymapping.KeyMappingRegistryImpl";
	private static final String IMPL_INTERNAL = IMPL.replace('.', '/');
	private static final String MINECRAFT = "net/minecraft/client/Minecraft";
	private static final String OPTIONS = "net/minecraft/client/Options";
	private static final String KEY_MAPPING = "net/minecraft/client/KeyMapping";
	private static final String REGISTER_DESC = "(L" + KEY_MAPPING + ";)L" + KEY_MAPPING + ";";
	private static final TransformContext CTX = new TransformContext(EnvType.CLIENT, false, "intermediary");

	@Test
	void unrepairedANullMinecraftIsTheCrashTheClientDiedOn() throws Exception {
		Fixture f = new Fixture(standIn());
		InvocationTargetException boom =
				assertThrows(InvocationTargetException.class, () -> f.register(f.newMapping()));
		assertEquals(NullPointerException.class, boom.getCause().getClass(),
				"this NPE is the Bootstrap crash: getInstance() is null and the guard dereferences it");
	}

	@Test
	void repairedANullMinecraftRegistersInsteadOfThrowing() throws Exception {
		Fixture f = new Fixture(transform(standIn()));
		Object mapping = f.newMapping();
		assertSame(mapping, f.register(mapping), "registerKeyMapping returns the mapping it was given");
		assertEquals(List.of(mapping), f.registered(),
				"the mapping must actually reach the static list Options drains later — returning without "
						+ "recording it would lose the keybind silently, which is worse than the crash");
	}

	@Test
	void repairedTheOrdinaryFabricWindowIsUnchanged() throws Exception {
		Fixture f = new Fixture(transform(standIn()));
		f.setInstance(f.newMinecraft());
		Object mapping = f.newMapping();
		assertSame(mapping, f.register(mapping), "instance live, options not built — Fabric's own legal window");
		assertEquals(List.of(mapping), f.registered());
	}

	@Test
	void repairedRegisteringTooLateStillThrows() throws Exception {
		Fixture f = new Fixture(transform(standIn()));
		Object minecraft = f.newMinecraft();
		f.setOptions(minecraft, f.newOptions());
		f.setInstance(minecraft);

		InvocationTargetException boom =
				assertThrows(InvocationTargetException.class, () -> f.register(f.newMapping()));
		assertEquals(IllegalStateException.class, boom.getCause().getClass(),
				"the check exists to reject LATE registration and must keep doing exactly that");
		assertEquals("GameOptions has already been initialised", boom.getCause().getMessage());
		assertEquals(List.of(), f.registered(), "a rejected registration must not have been recorded");
	}

	@Test
	void theRepairedMethodVerifies() throws Exception {
		ClassNode node = parse(transform(standIn()));
		MethodNode register = null;
		for (MethodNode m : node.methods) {
			if ("registerKeyMapping".equals(m.name) && REGISTER_DESC.equals(m.desc)) register = m;
		}
		assertNotNull(register, "the transformer must not have renamed the method");
		// The added jump reuses the existing IFNULL's label, so no frame moves. A verifier pass is what says so.
		new Analyzer<>(new BasicVerifier()).analyze(node.name, register);
	}

	@Test
	void aSecondPassAddsNothing() {
		byte[] in = standIn();
		byte[] once = transform(in);
		assertNotSame(in, once, "the first pass must actually edit, or the rest of this test proves nothing");
		assertSame(once, transform(once), "an already-guarded method must not collect a second guard");
	}

	@Test
	void theSwitchRestoresTheUnguardedRead() {
		byte[] in = standIn();
		System.setProperty(EarlyKeyMappingRegistrationInjector.PROPERTY, "off");
		try {
			assertSame(in, transform(in), "-Dforbric.earlyKeyMapping=off must leave the class alone");
		} finally {
			System.clearProperty(EarlyKeyMappingRegistrationInjector.PROPERTY);
		}
	}

	@Test
	void anotherClassIsNotTouched() {
		byte[] in = standIn();
		assertSame(in, new EarlyKeyMappingRegistrationInjector()
				.transform("net.minecraft.client.Minecraft", in, CTX));
	}

	@Test
	void afabricApiThatStoppedReadingTheInstanceIsLeftAlone() {
		byte[] in = implWithoutTheInstanceRead();
		assertSame(in, transform(in),
				"no read to guard is not a failure — the anchor ledger reports the no-edit on its own");
	}

	private static byte[] transform(byte[] in) {
		return new EarlyKeyMappingRegistrationInjector().transform(IMPL, in, CTX);
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new org.objectweb.asm.ClassReader(bytes).accept(node, 0);
		return node;
	}

	/**
	 * {@code KeyMappingRegistryImpl} with fabric-api's own instruction shape:
	 * {@code if (Minecraft.getInstance().options != null) throw …; MODDED.add(binding); return binding;}
	 */
	private static byte[] standIn() {
		return impl(true);
	}

	/** The same class with the guard removed — the shape this transformer must decline. */
	private static byte[] implWithoutTheInstanceRead() {
		return impl(false);
	}

	private static byte[] impl(boolean withGuard) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, IMPL_INTERNAL, null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "MODDED_KEY_BINDINGS", "Ljava/util/List;", null, null)
				.visitEnd();

		MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
		clinit.visitCode();
		clinit.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
		clinit.visitInsn(Opcodes.DUP);
		clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
		clinit.visitFieldInsn(Opcodes.PUTSTATIC, IMPL_INTERNAL, "MODDED_KEY_BINDINGS", "Ljava/util/List;");
		clinit.visitInsn(Opcodes.RETURN);
		clinit.visitMaxs(0, 0);
		clinit.visitEnd();

		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "registerKeyMapping",
				REGISTER_DESC, null, null);
		mv.visitCode();
		if (withGuard) {
			Label ok = new Label();
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, MINECRAFT, "getInstance", "()L" + MINECRAFT + ";", false);
			mv.visitFieldInsn(Opcodes.GETFIELD, MINECRAFT, "options", "L" + OPTIONS + ";");
			mv.visitJumpInsn(Opcodes.IFNULL, ok);
			mv.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
			mv.visitInsn(Opcodes.DUP);
			mv.visitLdcInsn("GameOptions has already been initialised");
			mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>",
					"(Ljava/lang/String;)V", false);
			mv.visitInsn(Opcodes.ATHROW);
			mv.visitLabel(ok);
		}
		mv.visitFieldInsn(Opcodes.GETSTATIC, IMPL_INTERNAL, "MODDED_KEY_BINDINGS", "Ljava/util/List;");
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
		mv.visitInsn(Opcodes.POP);
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();

		cw.visitEnd();
		return cw.toByteArray();
	}

	/** {@code Minecraft} with the one static and the one field the guard reads, and nothing else. */
	private static byte[] minecraftStandIn() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, MINECRAFT, null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "instance", "L" + MINECRAFT + ";", null, null).visitEnd();
		cw.visitField(Opcodes.ACC_PUBLIC, "options", "L" + OPTIONS + ";", null, null).visitEnd();

		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();

		MethodVisitor get = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getInstance",
				"()L" + MINECRAFT + ";", null, null);
		get.visitCode();
		get.visitFieldInsn(Opcodes.GETSTATIC, MINECRAFT, "instance", "L" + MINECRAFT + ";");
		get.visitInsn(Opcodes.ARETURN);
		get.visitMaxs(0, 0);
		get.visitEnd();

		cw.visitEnd();
		return cw.toByteArray();
	}

	private static byte[] plainClass(String internalName) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** The four stand-ins in one loader, so each test gets a fresh {@code Minecraft.instance}. */
	private static final class Fixture {
		private final Class<?> impl;
		private final Class<?> minecraft;
		private final Class<?> options;
		private final Class<?> keyMapping;

		Fixture(byte[] implBytes) throws Exception {
			Map<String, byte[]> classes = new HashMap<>();
			classes.put(IMPL, implBytes);
			classes.put("net.minecraft.client.Minecraft", minecraftStandIn());
			classes.put("net.minecraft.client.Options", plainClass(OPTIONS));
			classes.put("net.minecraft.client.KeyMapping", plainClass(KEY_MAPPING));

			ClassLoader loader = new ClassLoader(EarlyKeyMappingRegistrationInjectorTest.class.getClassLoader()) {
				@Override
				protected Class<?> findClass(String name) throws ClassNotFoundException {
					byte[] bytes = classes.get(name);
					if (bytes == null) throw new ClassNotFoundException(name);
					return defineClass(name, bytes, 0, bytes.length);
				}
			};
			this.impl = loader.loadClass(IMPL);
			this.minecraft = loader.loadClass("net.minecraft.client.Minecraft");
			this.options = loader.loadClass("net.minecraft.client.Options");
			this.keyMapping = loader.loadClass("net.minecraft.client.KeyMapping");
		}

		Object newMapping() throws Exception {
			return keyMapping.getDeclaredConstructor().newInstance();
		}

		Object newMinecraft() throws Exception {
			return minecraft.getDeclaredConstructor().newInstance();
		}

		Object newOptions() throws Exception {
			return options.getDeclaredConstructor().newInstance();
		}

		void setInstance(Object value) throws Exception {
			Field field = minecraft.getDeclaredField("instance");
			field.setAccessible(true);
			field.set(null, value);
		}

		void setOptions(Object target, Object value) throws Exception {
			Field field = minecraft.getDeclaredField("options");
			field.setAccessible(true);
			field.set(target, value);
		}

		Object register(Object mapping) throws Exception {
			Method m = impl.getDeclaredMethod("registerKeyMapping", keyMapping);
			m.setAccessible(true);
			return m.invoke(null, mapping);
		}

		List<?> registered() throws Exception {
			Field field = impl.getDeclaredField("MODDED_KEY_BINDINGS");
			field.setAccessible(true);
			return (List<?>) field.get(null);
		}
	}
}
