/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;
import net.forbric.kernel.interop.protocol.modmenu.ModMenuApiStandIn;

import static org.junit.jupiter.api.Assertions.*;

import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import net.forbric.kernel.classloading.ForbricClassLoader;

/**
 * The Mod Menu API stand-in exists exactly when no installed jar has the real API.
 *
 * <p>Driven against a real {@link ForbricClassLoader} with synthetic jars: one shaped like the kernel's game-side jar
 * (the stand-in's class files as {@code .class.bin} resources) and one shaped like Mod Menu (the real class files).
 * Every class carries an {@code ORIGIN} constant, so which copy the loader defined is read off the class itself.
 */
@ResourceLock("system-properties")
class ModMenuApiStandInTest {
	private static final String API = "com.terraformersmc.modmenu.api.ModMenuApi";
	private static final String CHANNEL = "com.terraformersmc.modmenu.api.UpdateChannel";

	@TempDir Path dir;

	@AfterEach void reset() {
		System.clearProperty(ModMenuApiStandIn.SWITCH);
	}

	@Test void withoutModMenuTheStandInIsWhatTheLoaderDefines() throws Exception {
		try (ForbricClassLoader loader = loader(kernelJar())) {
			assertEquals(ModMenuApiStandIn.Outcome.OFFERED, ModMenuApiStandIn.install(loader));
			for (String name : ModMenuApiStandIn.CLASSES) {
				assertEquals("stand-in", origin(loader.loadClass(name.replace('/', '.'))), name);
			}
		}
	}

	/** The failure this exists for: before the stand-in, a Fabric mod's Mod Menu entrypoint could not even link. */
	@Test void aModsEntrypointClassLinksAgainstTheStandIn() throws Exception {
		Path mod = jar("mod.jar", List.of("example/ModMenuIntegration"), name -> implementer(name, API));
		try (ForbricClassLoader without = loader(kernelJar(), mod)) {
			assertThrows(NoClassDefFoundError.class, () -> without.loadClass("example.ModMenuIntegration").getInterfaces(),
					"no Mod Menu and no stand-in: the entrypoint class has no interface to link");
		}
		try (ForbricClassLoader with = loader(kernelJar(), mod)) {
			ModMenuApiStandIn.install(with);
			Class<?> entrypoint = with.loadClass("example.ModMenuIntegration");
			assertSame(with.loadClass(API), entrypoint.getInterfaces()[0]);
		}
	}

	@Test void anInstalledModMenuIsWhatTheLoaderDefinesAndNothingIsOffered() throws Exception {
		Path modMenu = jar("modmenu.jar", List.of(ModMenuApiStandIn.API), name -> marked(name, "jar"));
		try (ForbricClassLoader loader = loader(kernelJar(), modMenu)) {
			assertEquals(ModMenuApiStandIn.Outcome.PROVIDED, ModMenuApiStandIn.install(loader));
			assertEquals("jar", origin(loader.loadClass(API)));
			// Nothing at all was offered: a stand-in class the jar does not carry stays absent rather than mixing in.
			assertThrows(ClassNotFoundException.class, () -> loader.loadClass(CHANNEL));
		}
	}

	/** The second line of defence: an offered stand-in cannot shadow a jar's copy even if it is offered anyway. */
	@Test void offeredBytesNeverShadowAnOwnedJar() throws Exception {
		Path modMenu = jar("modmenu.jar", List.of(ModMenuApiStandIn.API), name -> marked(name, "jar"));
		try (ForbricClassLoader loader = loader(kernelJar(), modMenu)) {
			loader.putGeneratedClass(ModMenuApiStandIn.API, marked(ModMenuApiStandIn.API, "stand-in"));
			assertEquals("jar", origin(loader.loadClass(API)));
		}
	}

	@Test void theSwitchOffersNothing() throws Exception {
		System.setProperty(ModMenuApiStandIn.SWITCH, "off");
		try (ForbricClassLoader loader = loader(kernelJar())) {
			assertEquals(ModMenuApiStandIn.Outcome.OFF, ModMenuApiStandIn.install(loader));
			assertThrows(ClassNotFoundException.class, () -> loader.loadClass(API));
		}
	}

	@Test void aKernelBuiltWithoutTheGameSideOffersNothing() throws Exception {
		Path empty = jar("forbric-kernel-runtime.jar", List.of(), name -> new byte[0]);
		try (ForbricClassLoader loader = loader(empty)) {
			assertEquals(ModMenuApiStandIn.Outcome.UNAVAILABLE, ModMenuApiStandIn.install(loader));
			assertThrows(ClassNotFoundException.class, () -> loader.loadClass(API));
		}
	}

	/**
	 * Mixin reads classes through {@code getPreMixinClassBytes}, and it must be shown the class the loader will define:
	 * the stand-in's bytes when it was offered, the jar's otherwise. It used to answer nothing for an offered class.
	 */
	@Test void mixinIsShownTheBytesTheLoaderDefines() throws Exception {
		try (ForbricClassLoader loader = loader(kernelJar())) {
			ModMenuApiStandIn.install(loader);
			byte[] shown = loader.getPreMixinClassBytes(API);
			assertNotNull(shown, "an offered class has bytes for Mixin to read");
			assertEquals("stand-in", origin(defineAlone(API, shown)));
		}
		Path modMenu = jar("modmenu.jar", List.of(ModMenuApiStandIn.API), name -> marked(name, "jar"));
		try (ForbricClassLoader loader = loader(kernelJar(), modMenu)) {
			loader.putGeneratedClass(ModMenuApiStandIn.API, marked(ModMenuApiStandIn.API, "stand-in"));
			assertEquals("jar", origin(defineAlone(API, loader.getPreMixinClassBytes(API))));
		}
	}

	private Path kernelJar() throws Exception {
		return jar("forbric-kernel-runtime.jar", ModMenuApiStandIn.CLASSES, name -> marked(name, "stand-in"),
				ModMenuApiStandIn.RESOURCES, ".class.bin");
	}

	private static ForbricClassLoader loader(Path... jars) throws Exception {
		URL[] urls = new URL[jars.length];
		for (int i = 0; i < jars.length; i++) urls[i] = jars[i].toUri().toURL();
		return new ForbricClassLoader(urls, ModMenuApiStandInTest.class.getClassLoader());
	}

	private Path jar(String file, List<String> classes, java.util.function.Function<String, byte[]> bytes) throws Exception {
		return jar(file, classes, bytes, "", ".class");
	}

	private Path jar(String file, List<String> classes, java.util.function.Function<String, byte[]> bytes, String prefix,
			String suffix) throws Exception {
		Path jar = dir.resolve(file);
		try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(out)) {
			jos.putNextEntry(new ZipEntry("META-INF/marker"));
			jos.closeEntry();
			for (String name : classes) {
				jos.putNextEntry(new ZipEntry(prefix + name + suffix));
				jos.write(bytes.apply(name));
				jos.closeEntry();
			}
		}
		return jar;
	}

	/** A public interface named {@code internalName} with {@code String ORIGIN = origin}. */
	private static byte[] marked(String internalName, String origin) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, internalName, null,
				"java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "ORIGIN", "Ljava/lang/String;", null,
				origin).visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A public class implementing {@code iface}, as a mod's {@code "modmenu"} entrypoint does. */
	private static byte[] implementer(String internalName, String iface) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object",
				new String[] {iface.replace('.', '/')});
		var init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static String origin(Class<?> type) throws Exception {
		return (String) type.getField("ORIGIN").get(null);
	}

	/** Defines {@code bytes} in a throwaway loader, to read which copy they are. */
	private static Class<?> defineAlone(String name, byte[] bytes) {
		return new ClassLoader(null) {
			Class<?> define() {
				return defineClass(name, bytes, 0, bytes.length);
			}
		}.define();
	}
}
