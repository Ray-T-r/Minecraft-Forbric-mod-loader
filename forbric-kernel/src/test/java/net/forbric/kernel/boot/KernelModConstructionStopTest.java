/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.forbric.api.Side;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * A NeoForge mod whose {@code @Mod} constructor throws stops the boot once construction is over, as native NeoForge
 * 26.2.0.93 stops it (D82 "stop like native", extended to every phase by D87): {@code FMLModContainer.constructMod} throws
 * {@code ModLoadingException(fml.modloadingissue.failedtoloadmod)} naming the mod, the other mods are still constructed
 * ({@code ModLoader.dispatchParallelTask}), and {@code waitForFuture} ends the "Mod Construction" task on it (javap of
 * FMLModContainer, ModLoader). Real {@code KernelModLoader.constructMods} over NeoForge's real carrier and a mod jar with one
 * healthy and one throwing {@code @Mod}; the stop is asked for the way {@code KernelLifecycle} asks for it once construction
 * is over.
 *
 * <p>Until D87 the kernel withdrew the failing mod's container and the boot carried on without it. Reached through reflection
 * so the same test runs against a kernel without the construction stop, and fails there.
 */
class KernelModConstructionStopTest {
	@TempDir
	static Path shared;
	private static URLClassLoader gameLoader;
	private static Path modsJar;

	@BeforeAll
	static void forgetAnotherClassesGameLoader() throws Exception {
		forgetFactoryMemo();
	}

	@AfterEach
	void forgetTheStop() throws Exception {
		Field stopping = RegistrationStop.class.getDeclaredField("stopping");
		stopping.setAccessible(true);
		stopping.set(null, null);
		MultiLoaderArbiter.reset();
	}

	@AfterAll
	static void forgetTheGameLoader() throws Exception {
		if (gameLoader != null) gameLoader.close();
		gameLoader = null;
		forgetFactoryMemo();
	}

	// The constructor's throw is handed to "Mod Construction"'s stop: not a Caught failure site (lane STOP review A-7).
	@Test
	void aConstructorThatThrowsStopsTheBootOnceEveryModIsConstructed() throws Exception {
		ClassLoader game = game();
		KernelModLoader.constructMods(game, List.of(modsJar), Side.DEDICATED_SERVER);
		assertTrue(container(game, "ctorstop_healthy").isPresent(), "the healthy mod was still constructed");

		Throwable stop = stopAfterConstruction(game);

		assertSame(Class.forName("net.neoforged.fml.ModLoadingException", false, game), stop.getClass(),
				"NeoForge's own ModLoadingException: " + stop);
		List<?> issues = (List<?>) stop.getClass().getMethod("getIssues").invoke(stop);
		assertEquals(1, issues.size(), issues.toString());
		Object issue = issues.get(0);
		assertEquals("fml.modloadingissue.failedtoloadmod", issue.getClass().getMethod("translationKey").invoke(issue),
				"FMLModContainer.constructMod's issue for a constructor that threw");
		Object mod = issue.getClass().getMethod("affectedMod").invoke(issue);
		assertNotNull(mod, "the issue names the mod");
		assertEquals("ctorstop_broken", mod.getClass().getMethod("getModId").invoke(mod));
		Throwable cause = (Throwable) issue.getClass().getMethod("cause").invoke(issue);
		assertTrue(cause instanceof IllegalStateException && cause.getMessage().contains("fails on purpose"),
				"the constructor's own throw is the cause: " + cause);
		assertTrue(RegistrationStop.isStop(stop));
	}

	/** {@code RegistrationStop.stopPending(cl, "Mod Construction")}: what the lifecycle calls once construction is over. */
	private static Throwable stopAfterConstruction(ClassLoader game) throws Exception {
		Method stopPending;
		try {
			stopPending = RegistrationStop.class.getDeclaredMethod("stopPending", ClassLoader.class, String.class);
		} catch (NoSuchMethodException none) {
			return fail("the kernel has no stop for a failed construction: a throwing @Mod constructor is contained and the "
					+ "boot carries on");
		}
		stopPending.setAccessible(true);
		try {
			stopPending.invoke(null, game, "Mod Construction");
		} catch (InvocationTargetException thrown) {
			return thrown.getCause();
		}
		return fail("construction in which a constructor threw must stop the boot");
	}

	private static ClassLoader game() throws Exception {
		if (gameLoader == null) {
			modsJar = modsJar(shared.resolve("mods/ctorstop.jar"));
			Path compiled = Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"));
			Path neo = TestFixtures.stagedRoot().normalize().resolve(TestFixtures.NEO_CARRIER_JAR);
			TestFixtures.requireFiles(Fixture.STAGED, "staged carrier absent", neo);
			TestFixtures.require(Fixture.GAME_SIDE, Files.isDirectory(compiled), "the compiled game side is absent");
			Path stubs = PassiveSeederLoadingModListTest.loggingStubs(shared.resolve("stubs"));
			Set<URL> urls = new LinkedHashSet<>(List.of(compiled.toUri().toURL(), stubs.toUri().toURL(),
					neo.toUri().toURL(), modsJar.toUri().toURL()));
			// NeoForge's ModLoader builds its error through FMLTranslations, which needs Minecraft's own libraries.
			urls.addAll(net.forbric.kernel.runtime.StagedGameClassLoader.urls());
			String log4j = System.getProperty("forbric.log4jApiForTests", "");
			assertTrue(!log4j.isEmpty() && Files.isRegularFile(Path.of(log4j)), "log4j-api for the event bus absent: '" + log4j + "'");
			urls.add(Path.of(log4j).toUri().toURL());
			gameLoader = new URLClassLoader(urls.toArray(URL[]::new), KernelModConstructionStopTest.class.getClassLoader());
			PassiveSeeder.seedNeoForgeModList(gameLoader);
		}
		return gameLoader;
	}

	private static Optional<?> container(ClassLoader game, String id) throws Exception {
		Class<?> modList = Class.forName("net.neoforged.fml.ModList", false, game);
		Object list = modList.getMethod("get").invoke(null);
		return (Optional<?>) modList.getMethod("getModContainerById", String.class).invoke(list, id);
	}

	private static void forgetFactoryMemo() throws Exception {
		for (String memo : new String[] {"containerMethod", "modInfoMethod", "minecraftMethod"}) {
			Field field = KernelModContainerFactory.class.getDeclaredField(memo);
			field.setAccessible(true);
			field.set(null, null);
		}
	}

	/** Two NeoForge mods in one jar: {@code ctorstop_healthy}, and {@code ctorstop_broken} whose constructor throws. */
	private static Path modsJar(Path jar) throws IOException {
		Files.createDirectories(jar.getParent());
		String toml = "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n"
				+ "[[mods]]\nmodId=\"ctorstop_healthy\"\nversion=\"1.0\"\n"
				+ "[[mods]]\nmodId=\"ctorstop_broken\"\nversion=\"1.0\"\n";
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			KernelModLoaderDeclaredTest.put(zip, "META-INF/neoforge.mods.toml", toml);
			zip.putNextEntry(new ZipEntry("ctorstop/Healthy.class"));
			zip.write(modClass("ctorstop/Healthy", "ctorstop_healthy", false));
			zip.closeEntry();
			zip.putNextEntry(new ZipEntry("ctorstop/Broken.class"));
			zip.write(modClass("ctorstop/Broken", "ctorstop_broken", true));
			zip.closeEntry();
		}
		return jar;
	}

	/** {@code @Mod(id) public class <name> { public <name>() { [throw new IllegalStateException(...)] } }} */
	private static byte[] modClass(String internalName, String modId, boolean throwing) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
		AnnotationVisitor mod = cw.visitAnnotation("Lnet/neoforged/fml/common/Mod;", true);
		mod.visit("value", modId);
		mod.visitEnd();
		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		if (throwing) {
			init.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
			init.visitInsn(Opcodes.DUP);
			init.visitLdcInsn(modId + "'s constructor fails on purpose");
			init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false);
			init.visitInsn(Opcodes.ATHROW);
		} else {
			init.visitInsn(Opcodes.RETURN);
		}
		init.visitMaxs(0, 0);
		init.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}
}
