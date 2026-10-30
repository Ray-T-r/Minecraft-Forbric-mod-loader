/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.zip.ZipFile;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.mixin.NativeGameReferences;

/**
 * The staged merged base and its carriers as the game serves them: raw class files by resource path, and the native
 * reference index bound the way the kernel binds it at boot. What {@link NativeMergeShapeRepair} needs to prove a
 * merge delegate, so a test can ask the same proof the game asks. Hold {@code @ResourceLock("native-game-references")}
 * while one is open: binding is process-wide.
 */
final class StagedGameClasses implements AutoCloseable {
	private final List<Path> jars;
	private final ForbricClassLoader loader;

	private StagedGameClasses(List<Path> jars) throws ReflectiveOperationException {
		this.jars = jars;
		this.loader = new ForbricClassLoader(jars.stream().map(StagedGameClasses::url).toArray(URL[]::new),
				StagedGameClasses.class.getClassLoader());
		bind(loader);
	}

	/** Binds the staged jars' native references; {@link #close} unbinds them. */
	static StagedGameClasses open() throws ReflectiveOperationException {
		return new StagedGameClasses(jars());
	}

	/**
	 * The base, the MinecraftForge carrier (its interop jar when staged) and the NeoForge carrier, in the order the
	 * kernel owns them. The MinecraftForge carrier only when staged: the base and the NeoForge carrier are what every
	 * staged root has, and a root without the Forge carrier (the genuine base's layout) is served as those two.
	 */
	static List<Path> jars() {
		Path merged = TestFixtures.stagedBase().normalize();
		Path forge = TestFixtures.forgeCarrier().normalize();
		Path neo = TestFixtures.neoCarrier().normalize();
		TestFixtures.requireFiles(Fixture.STAGED, "the merged base and both carriers", merged, neo);
		return Files.isRegularFile(forge) ? List.of(merged, forge, neo) : List.of(merged, neo);
	}

	/**
	 * A game resource, asked for as {@code a/b/C.class} or as a class name in either spelling: the staged jars first,
	 * then the JDK and libraries, which the game loader serves too. Without the second, no hierarchy reaches
	 * java/lang/Object and the merged-base repair can prove nothing.
	 */
	static byte[] resource(List<Path> jars, String name) {
		String entry = name.endsWith(".class") || name.contains("/") && name.contains(".") ? name : name.replace('.', '/') + ".class";
		for (Path jar : jars) {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				var found = zip.getEntry(entry);
				if (found != null) try (InputStream in = zip.getInputStream(found)) { return in.readAllBytes(); }
			} catch (IOException unreadable) {
				return null;
			}
		}
		try (InputStream in = StagedGameClasses.class.getClassLoader().getResourceAsStream(entry)) {
			return in == null ? null : in.readAllBytes();
		} catch (IOException unreadable) {
			return null;
		}
	}

	List<Path> paths() { return jars; }
	/** {@code name} as the staged jars hold it, before any transformer. */
	byte[] bytes(String name) { return resource(jars, name); }
	/** The raw-class lookup KernelBoot gives the merged-base repair and KernelMixinBootstrap gives the transfer audit. */
	Function<String, byte[]> resources() { return name -> resource(jars, name); }
	/** The merged-base repair's proof over these jars and the bound native references. */
	NativeMergeShapeRepair proof() { return NativeMergeShapeRepair.production(null, resources()); }

	@Override public void close() throws IOException, ReflectiveOperationException {
		try { bind(null); } finally { loader.close(); }
	}

	static void bind(ForbricClassLoader loader) throws ReflectiveOperationException {
		Method bind = NativeGameReferences.class.getDeclaredMethod("bind", ForbricClassLoader.class);
		bind.setAccessible(true);
		bind.invoke(null, loader);
	}

	private static URL url(Path jar) {
		try { return jar.toUri().toURL(); } catch (java.net.MalformedURLException impossible) { throw new AssertionError(impossible); }
	}
}
