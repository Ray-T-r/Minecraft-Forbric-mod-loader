package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.objectweb.asm.Opcodes.*;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

import net.forbric.api.Side;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Invokes the real boot entrypoints against cold game-side spies; no native game classes are initialized. */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class KernelLifecycleForgeConfigTest {
	private static final String LIFECYCLE = "net.forbric.kernel.boot.KernelLifecycle";
	private static final String NEO = "net.forbric.kernel.runtime.KernelConfigLoad";
	private static final String FORGE = "net.forbric.kernel.runtime.KernelForgeConfigLoad";
	private static final String TRACKER = "net.minecraftforge.fml.config.ConfigTracker";
	private static final String STATE = "configfixture.Trace";
	private static final String PROPERTY = "forbric.earlyConfigs";
	private String previous;

	@BeforeEach void enableConfigs() { previous = System.getProperty(PROPERTY); System.clearProperty(PROPERTY); }
	@AfterEach void restoreConfigs() {
		if (previous == null) System.clearProperty(PROPERTY); else System.setProperty(PROPERTY, previous);
	}

	@Test
	void earlyCallsBothFamiliesWithTheirOwnOrderEvenWithoutGuestMods() throws Exception {
		for (Side side : Side.values()) {
			ColdLoader loader = new ColdLoader(true);
			assertEmptyGuestList(loader);
			invoke(loader, "loadEarlyConfigs", side);
			assertEquals(List.of("neo.early:" + neoEarly(side), "forge.early:" + forgeTypes(side)), loader.calls());
			assertFalse(loader.flag("trackerInitialized"), "a carrier-presence probe must not initialize ConfigTracker");
			assertTrue(loader.requests.indexOf(TRACKER) < loader.requests.indexOf(FORGE), "check the carrier before linking its helper");
		}
	}

	@Test
	void lateCallsBothFamiliesWithoutChangingNeosStartupCoverage() throws Exception {
		for (Side side : Side.values()) {
			ColdLoader loader = new ColdLoader(true);
			assertEmptyGuestList(loader);
			invoke(loader, "openLateConfigs", side);
			assertEquals(List.of("neo.late:" + neoLate(side), "forge.late:" + forgeTypes(side)), loader.calls());
		}
	}

	@Test
	void forgeTypesAreSeparateFromNeoAndExcludeStartupAndServer() throws Exception {
		ColdLoader loader = new ColdLoader(true);
		for (Side side : Side.values()) {
			for (String method : List.of("forgeEarlyConfigTypes", "forgeLateConfigTypes")) {
				Method types = loader.lifecycle().getDeclaredMethod(method, Side.class);
				types.setAccessible(true);
				assertEquals(forgeTypes(side), types.invoke(null, side));
			}
		}
		assertTrue(loader.calls().isEmpty(), "asking for the type table must not call a runtime helper");
	}

	@Test
	void throwingNeoEarlyStillCallsForge() throws Exception {
		ColdLoader loader = new ColdLoader(true);
		loader.fail("neoEarly");
		invoke(loader, "loadEarlyConfigs", Side.CLIENT);
		assertEquals(List.of("neo.early:[COMMON, CLIENT]", "forge.early:[CLIENT, COMMON]"), loader.calls());
	}

	@Test
	void throwingNeoLateStillCallsForge() throws Exception {
		ColdLoader loader = new ColdLoader(true);
		loader.fail("neoLate");
		invoke(loader, "openLateConfigs", Side.CLIENT);
		assertEquals(List.of("neo.late:[STARTUP, COMMON, CLIENT]", "forge.late:[CLIENT, COMMON]"), loader.calls());
	}

	@Test
	void throwingForgeDoesNotEscapeOrCostNeoEitherPass() throws Exception {
		ColdLoader loader = new ColdLoader(true);
		loader.fail("forgeEarly");
		loader.fail("forgeLate");
		invoke(loader, "loadEarlyConfigs", Side.DEDICATED_SERVER);
		invoke(loader, "openLateConfigs", Side.DEDICATED_SERVER);
		assertEquals(List.of("neo.early:[COMMON]", "forge.early:[COMMON]",
				"neo.late:[STARTUP, COMMON]", "forge.late:[COMMON]"), loader.calls());
	}

	@Test
	void brokenNeoHelperLinkageStillAllowsForgeInBothPasses() throws Exception {
		ColdLoader loader = new ColdLoader(true);
		loader.broken = NEO;
		invoke(loader, "loadEarlyConfigs", Side.CLIENT);
		invoke(loader, "openLateConfigs", Side.CLIENT);
		assertEquals(List.of("forge.early:[CLIENT, COMMON]", "forge.late:[CLIENT, COMMON]"), loader.calls());
	}

	@Test
	void brokenForgeHelperLinkageLeavesBothNeoPassesIntact() throws Exception {
		ColdLoader loader = new ColdLoader(true);
		loader.broken = FORGE;
		invoke(loader, "loadEarlyConfigs", Side.CLIENT);
		invoke(loader, "openLateConfigs", Side.CLIENT);
		assertEquals(List.of("neo.early:[COMMON, CLIENT]", "neo.late:[STARTUP, COMMON, CLIENT]"), loader.calls());
	}

	@Test
	void absentForgeCarrierNeverLinksItsRuntimeHelper() throws Exception {
		ColdLoader loader = new ColdLoader(false);
		invoke(loader, "loadEarlyConfigs", Side.CLIENT);
		invoke(loader, "openLateConfigs", Side.CLIENT);
		assertEquals(List.of("neo.early:[COMMON, CLIENT]", "neo.late:[STARTUP, COMMON, CLIENT]"), loader.calls());
		assertTrue(loader.requests.contains(TRACKER), "absence must be decided from the carrier");
		assertFalse(loader.requests.contains(FORGE), "a missing carrier must not resolve the typed Forge helper at all");
	}

	@Test
	void sharedOffSwitchStopsBothPassesBeforeAnyCarrierOrHelperLinkage() throws Exception {
		System.setProperty(PROPERTY, "oFf");
		for (Side side : Side.values()) {
			ColdLoader loader = new ColdLoader(true);
			invoke(loader, "loadEarlyConfigs", side);
			invoke(loader, "openLateConfigs", side);
			assertTrue(loader.calls().isEmpty());
			for (String name : List.of(NEO, FORGE, TRACKER)) assertFalse(loader.requests.contains(name), name + " linked with earlyConfigs=off");
		}
	}

	@Test
	void realForgeCarrierHasTheTwoArgumentPrivateStaticOpenerAndTheExpectedTypeOrder() throws Exception {
		String old = System.getenv("FORBRIC_OLD");
		Path jar = Path.of(old == null || old.isBlank() ? "../forbric-loader" : old).resolve("run/forge-runtime/forge-runtime.jar");
		assumeTrue(Files.isRegularFile(jar), "optional staged Forge carrier absent: " + jar);
		try (JarFile carrier = new JarFile(jar.toFile())) {
			ClassNode tracker = read(carrier, TRACKER.replace('.', '/'));
			var openers = tracker.methods.stream().filter(m -> m.name.equals("openConfig")).toList();
			assertEquals(1, openers.size(), "review the opener bridge if the real carrier changes overloads");
			var opener = openers.getFirst();
			assertEquals("(Lnet/minecraftforge/fml/config/ModConfig;Ljava/nio/file/Path;)V", opener.desc);
			assertEquals(ACC_PRIVATE | ACC_STATIC, opener.access & (ACC_PUBLIC | ACC_PRIVATE | ACC_PROTECTED | ACC_STATIC));
			ClassNode type = read(carrier, "net/minecraftforge/fml/config/ModConfig$Type");
			assertEquals(Set.of("CLIENT", "COMMON", "SERVER"), Set.copyOf(type.fields.stream()
					.filter(f -> (f.access & ACC_ENUM) != 0).map(f -> f.name).toList()));
			ClassNode provider = read(carrier, "net/minecraftforge/fml/core/ModStateProvider");
			var loaders = provider.methods.stream().filter(m -> Arrays.stream(m.instructions.toArray()).anyMatch(i ->
					i instanceof MethodInsnNode call && call.owner.equals(TRACKER.replace('.', '/')) && call.name.equals("loadConfigs"))).toList();
			assertEquals(1, loaders.size());
			List<String> nativeTypes = Arrays.stream(loaders.getFirst().instructions.toArray())
					.filter(i -> i instanceof FieldInsnNode field && field.getOpcode() == GETSTATIC
							&& field.owner.equals("net/minecraftforge/fml/config/ModConfig$Type"))
					.map(i -> ((FieldInsnNode) i).name).toList();
			assertEquals(List.of("CLIENT", "COMMON"), nativeTypes);
			ColdLoader loader = new ColdLoader(true);
			Method early = loader.lifecycle().getDeclaredMethod("forgeEarlyConfigTypes", Side.class);
			early.setAccessible(true);
			assertEquals(nativeTypes, early.invoke(null, Side.CLIENT), "kernel order must follow the actual Forge carrier");
		}
	}

	private static List<String> forgeTypes(Side side) { return side.isClient() ? List.of("CLIENT", "COMMON") : List.of("COMMON"); }
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

	private static ClassNode read(JarFile carrier, String name) throws Exception {
		var entry = carrier.getJarEntry(name + ".class");
		assertNotNull(entry, name);
		try (var in = carrier.getInputStream(entry)) {
			ClassNode node = new ClassNode(); new ClassReader(in).accept(node, 0); return node;
		}
	}

	private static final class ColdLoader extends ClassLoader {
		private final Map<String, byte[]> definitions = new HashMap<>();
		final List<String> requests = new ArrayList<>();
		String broken;

		ColdLoader(boolean forgePresent) {
			super(KernelLifecycleForgeConfigTest.class.getClassLoader());
			definitions.put(STATE, trace());
			definitions.put(NEO, spy(NEO, "neo"));
			definitions.put(FORGE, spy(FORGE, "forge"));
			if (forgePresent) definitions.put(TRACKER, tracker());
		}

		Class<?> lifecycle() throws ClassNotFoundException { return Class.forName(LIFECYCLE, true, this); }
		Class<?> state() throws ClassNotFoundException { return Class.forName(STATE, true, this); }
		@SuppressWarnings("unchecked") List<String> calls() throws Exception { return List.copyOf((List<String>) state().getField("calls").get(null)); }
		boolean flag(String name) throws Exception { return state().getField(name).getBoolean(null); }
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
		for (String flag : List.of("neoEarly", "neoLate", "forgeEarly", "forgeLate", "trackerInitialized"))
			out.visitField(ACC_PUBLIC | ACC_STATIC, flag, "Z", null, null).visitEnd();
		MethodVisitor mv = out.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
		mv.visitCode(); mv.visitTypeInsn(NEW, "java/util/ArrayList"); mv.visitInsn(DUP);
		mv.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
		mv.visitFieldInsn(PUTSTATIC, STATE.replace('.', '/'), "calls", "Ljava/util/List;");
		mv.visitInsn(RETURN); mv.visitMaxs(0, 0); mv.visitEnd(); out.visitEnd(); return out.toByteArray();
	}

	private static byte[] tracker() {
		ClassWriter out = writer(TRACKER);
		MethodVisitor mv = out.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
		mv.visitCode(); mv.visitInsn(ICONST_1); mv.visitFieldInsn(PUTSTATIC, STATE.replace('.', '/'), "trackerInitialized", "Z");
		throwFailure(mv, "presence check initialized ConfigTracker"); mv.visitMaxs(0, 0); mv.visitEnd();
		out.visitEnd(); return out.toByteArray();
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
