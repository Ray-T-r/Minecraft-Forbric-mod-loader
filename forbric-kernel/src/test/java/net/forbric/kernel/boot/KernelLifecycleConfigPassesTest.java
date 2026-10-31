/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.*;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.forbric.api.Side;
import net.forbric.kernel.ExpectCaught;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Invokes the real boot config entrypoints ({@code loadEarlyConfigs}, {@code openLateConfigs}) against a cold spy of the
 * game-side {@code KernelConfigLoad}; no native game class is initialised.
 *
 * <p>Pins what {@code KernelLifecycleLateConfigTest} reads only from the type table or the bytecode: the early list each side
 * actually hands NeoForge's helper through the real entrypoint, that a failing helper is caught in both passes, and that
 * {@code -Dforbric.earlyConfigs=off} stops both passes before the helper is even linked.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class KernelLifecycleConfigPassesTest {
	private static final String LIFECYCLE = "net.forbric.kernel.boot.KernelLifecycle";
	private static final String NEO = "net.forbric.kernel.runtime.KernelConfigLoad";
	private static final String STATE = "configfixture.Trace";
	private static final String PROPERTY = "forbric.earlyConfigs";
	private String previous;

	@BeforeEach void enableConfigs() { previous = System.getProperty(PROPERTY); System.clearProperty(PROPERTY); }
	@AfterEach void restoreConfigs() {
		if (previous == null) System.clearProperty(PROPERTY); else System.setProperty(PROPERTY, previous);
	}

	@Test
	void earlyHandsNeoForgeItsOwnTypesPerSideEvenWithoutGuestMods() throws Exception {
		for (Side side : Side.values()) {
			ColdLoader loader = new ColdLoader();
			assertEmptyGuestList(loader);
			invoke(loader, "loadEarlyConfigs", side);
			assertEquals(List.of("neo.early:" + neoEarly(side)), loader.calls(), side.toString());
		}
	}

	@Test
	void lateHandsNeoForgeItsTypesPerSideStartupIncluded() throws Exception {
		for (Side side : Side.values()) {
			ColdLoader loader = new ColdLoader();
			invoke(loader, "openLateConfigs", side);
			assertEquals(List.of("neo.late:" + neoLate(side)), loader.calls(), side.toString());
		}
	}

	@ExpectCaught("could not load NeoForge configs")
	@Test
	void aThrowingEarlyHelperIsCaught() throws Exception {
		ColdLoader loader = new ColdLoader();
		loader.fail("neoEarly");
		invoke(loader, "loadEarlyConfigs", Side.CLIENT);
		assertEquals(List.of("neo.early:[COMMON, CLIENT]"), loader.calls());
	}

	@ExpectCaught("could not open late-registered NeoForge configs")
	@Test
	void aThrowingLateHelperIsCaught() throws Exception {
		ColdLoader loader = new ColdLoader();
		loader.fail("neoLate");
		invoke(loader, "openLateConfigs", Side.DEDICATED_SERVER);
		assertEquals(List.of("neo.late:[STARTUP, COMMON]"), loader.calls());
	}

	@ExpectCaught({"could not load NeoForge configs", "could not open late-registered NeoForge configs"})
	@Test
	void aHelperThatCannotLinkIsCaughtInBothPasses() throws Exception {
		ColdLoader loader = new ColdLoader();
		loader.broken = NEO;
		invoke(loader, "loadEarlyConfigs", Side.CLIENT);
		invoke(loader, "openLateConfigs", Side.CLIENT);
		assertTrue(loader.calls().isEmpty());
	}

	@Test
	void theOffSwitchStopsBothPassesBeforeTheHelperIsLinked() throws Exception {
		System.setProperty(PROPERTY, "oFf");
		for (Side side : Side.values()) {
			ColdLoader loader = new ColdLoader();
			invoke(loader, "loadEarlyConfigs", side);
			invoke(loader, "openLateConfigs", side);
			assertTrue(loader.calls().isEmpty());
			assertFalse(loader.requests.contains(NEO), NEO + " linked with earlyConfigs=off");
		}
	}

	private static List<String> neoEarly(Side side) { return side.isClient() ? List.of("COMMON", "CLIENT") : List.of("COMMON"); }
	private static List<String> neoLate(Side side) { return side.isClient() ? List.of("STARTUP", "COMMON", "CLIENT") : List.of("STARTUP", "COMMON"); }

	private static void invoke(ColdLoader loader, String name, Side side) throws Exception {
		boolean late = name.equals("openLateConfigs");
		Method method = late ? loader.lifecycle().getDeclaredMethod(name, ClassLoader.class, Side.class, String.class)
				: loader.lifecycle().getDeclaredMethod(name, ClassLoader.class, Side.class);
		method.setAccessible(true);
		if (late) method.invoke(null, loader, side, "cold boot fixture"); else method.invoke(null, loader, side);
	}

	private static void assertEmptyGuestList(ColdLoader loader) throws Exception {
		var field = loader.lifecycle().getDeclaredField("modJars");
		field.setAccessible(true);
		assertEquals(List.of(), field.get(null), "empty guest set must still initialize the carrier's own configs");
		assertSame(loader, loader.lifecycle().getClassLoader(), "the lifecycle must be cold, not shared by other tests");
	}

	private static final class ColdLoader extends ClassLoader {
		private final Map<String, byte[]> definitions = new HashMap<>();
		final List<String> requests = new ArrayList<>();
		String broken;

		ColdLoader() {
			super(KernelLifecycleConfigPassesTest.class.getClassLoader());
			definitions.put(STATE, trace());
			definitions.put(NEO, spy(NEO, "neo"));
		}

		Class<?> lifecycle() throws ClassNotFoundException { return Class.forName(LIFECYCLE, true, this); }
		Class<?> state() throws ClassNotFoundException { return Class.forName(STATE, true, this); }
		@SuppressWarnings("unchecked") List<String> calls() throws Exception { return List.copyOf((List<String>) state().getField("calls").get(null)); }
		void fail(String name) throws Exception { state().getField(name).setBoolean(null, true); }

		@Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			requests.add(name);
			if (name.equals(broken)) throw new NoClassDefFoundError("deliberate cold linkage failure: " + name);
			Class<?> result = findLoadedClass(name);
			if (result == null) {
				byte[] bytes = definitions.get(name);
				if (name.equals(LIFECYCLE) || name.startsWith(LIFECYCLE + "$")) {
					try (var in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
						if (in == null) throw new ClassNotFoundException(name);
						bytes = in.readAllBytes();
					} catch (IOException e) { throw new ClassNotFoundException(name, e); }
				}
				if (bytes != null) result = defineClass(name, bytes, 0, bytes.length);
				else if (name.startsWith("net.minecraftforge.") || name.startsWith("net.neoforged.")) throw new ClassNotFoundException(name);
				else result = super.loadClass(name, false);
			}
			if (resolve) resolveClass(result);
			return result;
		}
	}

	private static ClassWriter writer(String name) {
		ClassWriter out = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		out.visit(V17, ACC_PUBLIC, name.replace('.', '/'), null, "java/lang/Object", null);
		return out;
	}

	private static byte[] trace() {
		ClassWriter out = writer(STATE);
		out.visitField(ACC_PUBLIC | ACC_STATIC, "calls", "Ljava/util/List;", null, null).visitEnd();
		for (String flag : List.of("neoEarly", "neoLate"))
			out.visitField(ACC_PUBLIC | ACC_STATIC, flag, "Z", null, null).visitEnd();
		MethodVisitor mv = out.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
		mv.visitCode(); mv.visitTypeInsn(NEW, "java/util/ArrayList"); mv.visitInsn(DUP);
		mv.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
		mv.visitFieldInsn(PUTSTATIC, STATE.replace('.', '/'), "calls", "Ljava/util/List;");
		mv.visitInsn(RETURN); mv.visitMaxs(0, 0); mv.visitEnd(); out.visitEnd(); return out.toByteArray();
	}

	private static byte[] spy(String name, String family) {
		ClassWriter out = writer(name);
		for (boolean late : new boolean[] { false, true }) {
			String method = late ? "openLate" : "loadEarly";
			MethodVisitor mv = out.visitMethod(ACC_PUBLIC | ACC_STATIC, method,
					late ? "(Ljava/util/List;)Ljava/util/List;" : "(Ljava/util/List;)V", null, null);
			mv.visitCode(); mv.visitFieldInsn(GETSTATIC, STATE.replace('.', '/'), "calls", "Ljava/util/List;");
			mv.visitTypeInsn(NEW, "java/lang/StringBuilder"); mv.visitInsn(DUP);
			mv.visitLdcInsn(family + (late ? ".late:" : ".early:"));
			mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
			mv.visitVarInsn(ALOAD, 0); mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/Object;)Ljava/lang/StringBuilder;", false);
			mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
			mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true); mv.visitInsn(POP);
			mv.visitFieldInsn(GETSTATIC, STATE.replace('.', '/'), family + (late ? "Late" : "Early"), "Z");
			Label good = new Label(); mv.visitJumpInsn(IFEQ, good); throwFailure(mv, family + " deliberately threw"); mv.visitLabel(good);
			if (late) { mv.visitLdcInsn(family + ":COMMON"); mv.visitMethodInsn(INVOKESTATIC, "java/util/List", "of", "(Ljava/lang/Object;)Ljava/util/List;", true); mv.visitInsn(ARETURN); }
			else mv.visitInsn(RETURN);
			mv.visitMaxs(0, 0); mv.visitEnd();
		}
		out.visitEnd(); return out.toByteArray();
	}

	private static void throwFailure(MethodVisitor mv, String message) {
		mv.visitTypeInsn(NEW, "java/lang/AssertionError"); mv.visitInsn(DUP); mv.visitLdcInsn(message);
		mv.visitMethodInsn(INVOKESPECIAL, "java/lang/AssertionError", "<init>", "(Ljava/lang/Object;)V", false); mv.visitInsn(ATHROW);
	}
}
