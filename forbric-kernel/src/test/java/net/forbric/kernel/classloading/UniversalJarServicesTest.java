/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.classloading;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * A universal jar's {@code META-INF/services} file, served as the loader it was arbitrated to would read it — the
 * rrls shape: one config provider per platform, the Fabric one linking {@code FabricLoader}, both live on Forbric.
 */
class UniversalJarServicesTest {
	private static final String SERVICE = "fixture/Config";
	private static final String FABRIC_IMPL = "fixture/fabric/FabricConfigImpl";
	private static final String FALLBACK_IMPL = "fixture/FallbackConfigImpl";
	private static final String NEO_IMPL = "fixture/neoforge/NeoForgeConfigImpl";
	private static final String FABRIC_BASE = "fixture/fabric/FabricBase";
	private static final String VIA_BASE = "fixture/fabric/ViaBaseImpl";

	/** No class is read from another mod's jar. */
	private static final java.util.function.Function<String, LoaderProbePolicy.Family> NO_MOD_JAR = type -> null;

	@TempDir
	Path directory;

	@AfterEach
	void reset() {
		System.clearProperty(UniversalJarServices.PROPERTY);
		UniversalJarServices.forget();
	}

	@Test
	void aNeoForgeOwnerDropsTheProviderThatLinksFabric() {
		List<String> listed = List.of(dotted(FABRIC_IMPL), dotted(FALLBACK_IMPL), dotted(NEO_IMPL));
		assertEquals(List.of(dotted(FALLBACK_IMPL), dotted(NEO_IMPL)),
				UniversalJarServices.usable(LoaderProbePolicy.Family.NEOFORGE, listed, classes()::get, NO_MOD_JAR));
	}

	@Test
	void aFabricOwnerKeepsItAndDropsNothingThatOnlyNamesNeoForgesConfigTypes() {
		// NeoForgeConfigImpl names ModConfigSpec, which ForgeConfigAPIPort supplies on Fabric: still usable there.
		List<String> listed = List.of(dotted(FABRIC_IMPL), dotted(FALLBACK_IMPL), dotted(NEO_IMPL));
		assertSame(listed, UniversalJarServices.usable(LoaderProbePolicy.Family.FABRIC, listed, classes()::get, NO_MOD_JAR));
	}

	@Test
	void aSupertypeInTheSameJarCounts() {
		List<String> listed = List.of(dotted(VIA_BASE), dotted(NEO_IMPL));
		assertEquals(List.of(dotted(NEO_IMPL)), UniversalJarServices.usable(LoaderProbePolicy.Family.NEOFORGE, listed, classes()::get, NO_MOD_JAR));
	}

	@Test
	void itOnlyDisambiguatesNeverEmpties() {
		List<String> alone = List.of(dotted(FABRIC_IMPL));
		assertSame(alone, UniversalJarServices.usable(LoaderProbePolicy.Family.NEOFORGE, alone, classes()::get, NO_MOD_JAR), "one provider: untouched");
		List<String> allFabric = List.of(dotted(FABRIC_IMPL), dotted(VIA_BASE));
		assertSame(allFabric, UniversalJarServices.usable(LoaderProbePolicy.Family.NEOFORGE, allFabric, classes()::get, NO_MOD_JAR),
				"every provider would go: untouched");
		List<String> unreadable = List.of("fixture.Missing", dotted(NEO_IMPL));
		assertSame(unreadable, UniversalJarServices.usable(LoaderProbePolicy.Family.NEOFORGE, unreadable, classes()::get, NO_MOD_JAR),
				"a provider whose bytes cannot be read is kept");
	}

	/**
	 * A class a provider names that the class loader reads from another loader's mod jar is one its own loader lacks — the
	 * same verdict for fabric-api's package and for an unrelated Fabric mod's, since fabric-api is a mod like any other — and
	 * a class under fabric-api's package that no Fabric mod jar supplies is not Fabric Loader's, so it does not count.
	 */
	@Test
	void aClassFromAnotherFamilysModJarCountsWhateverItsPackage() {
		for (String type : List.of("net/fabricmc/fabric/api/qzx/Widget", "org/qzx/fabricmod/Widget")) {
			Map<String, byte[]> classes = classes();
			String impl = "fixture/fabric/WidgetConfigImpl";
			classes.put(impl, impl(impl, "java/lang/Object", type, "make", "()V"));
			List<String> listed = List.of(dotted(impl), dotted(NEO_IMPL));
			assertEquals(List.of(dotted(NEO_IMPL)), UniversalJarServices.usable(LoaderProbePolicy.Family.NEOFORGE, listed, classes::get,
					named -> named.equals(type) ? LoaderProbePolicy.Family.FABRIC : null), type);
			assertSame(listed, UniversalJarServices.usable(LoaderProbePolicy.Family.NEOFORGE, listed, classes::get, NO_MOD_JAR),
					type + " read from no mod jar is no loader's");
		}
	}

	/** For a Fabric owner, a NeoForge mod's class is missing natively; NeoForge's own config types stay usable (a port supplies them). */
	@Test
	void aFabricOwnerDropsAProviderThatNamesANeoForgeModsClass() {
		Map<String, byte[]> classes = classes();
		String impl = "fixture/neoforge/ModdedConfigImpl", type = "org/qzx/neomod/Settings";
		classes.put(impl, impl(impl, "java/lang/Object", type, "load", "()V"));
		List<String> listed = List.of(dotted(FALLBACK_IMPL), dotted(impl), dotted(NEO_IMPL));
		assertEquals(List.of(dotted(FALLBACK_IMPL), dotted(NEO_IMPL)), UniversalJarServices.usable(LoaderProbePolicy.Family.FABRIC, listed, classes::get,
				named -> named.equals(type) ? LoaderProbePolicy.Family.NEOFORGE : named.startsWith("net/neoforged/") ? LoaderProbePolicy.Family.FABRIC : null));
	}

	@Test
	void theLoaderServesTheNarrowedFileAndServiceLoaderPicksTheNeoForgeHalf() throws Exception {
		Path jar = universalJar();
		try (ForbricClassLoader loader = new ForbricClassLoader(new URL[] {jar.toUri().toURL()}, getClass().getClassLoader())) {
			loader.setJarFamilies(Map.of(jar, LoaderProbePolicy.Family.NEOFORGE));
			loader.setUniversalJars(List.of(jar));
			assertEquals(List.of(dotted(FALLBACK_IMPL), dotted(NEO_IMPL)), listed(loader));

			Class<?> service = Class.forName(dotted(SERVICE), false, loader);
			List<String> types = new ArrayList<>();
			ServiceLoader.load(service, loader).stream().forEach(provider -> types.add(provider.type().getName()));
			assertEquals(List.of(dotted(FALLBACK_IMPL), dotted(NEO_IMPL)), types);
		}
	}

	@Test
	void aSingleLoaderJarAndTheOffSwitchAreServedAsTheyAre() throws Exception {
		Path jar = universalJar();
		List<String> all = List.of(dotted(FABRIC_IMPL), dotted(FALLBACK_IMPL), dotted(NEO_IMPL));
		try (ForbricClassLoader loader = new ForbricClassLoader(new URL[] {jar.toUri().toURL()}, getClass().getClassLoader())) {
			loader.setJarFamilies(Map.of(jar, LoaderProbePolicy.Family.NEOFORGE));
			assertEquals(all, listed(loader), "not declared universal");
			loader.setUniversalJars(List.of(jar));
			System.setProperty(UniversalJarServices.PROPERTY, "off");
			assertEquals(all, listed(loader), "switched off");
		}
	}

	// --- fixtures ---

	private static List<String> listed(ForbricClassLoader loader) throws Exception {
		List<String> out = new ArrayList<>();
		for (URL url : Collections.list(loader.getResources(UniversalJarServices.PREFIX + dotted(SERVICE)))) {
			try (InputStream in = url.openStream()) {
				out.addAll(UniversalJarServices.providers(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
			}
		}
		return out;
	}

	private Path universalJar() throws Exception {
		Path jar = directory.resolve("universal.jar");
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
			for (Map.Entry<String, byte[]> entry : classes().entrySet()) {
				out.putNextEntry(new JarEntry(entry.getKey() + ".class"));
				out.write(entry.getValue());
			}
			out.putNextEntry(new JarEntry(UniversalJarServices.PREFIX + dotted(SERVICE)));
			out.write((dotted(FABRIC_IMPL) + "\n" + dotted(FALLBACK_IMPL) + "\n" + dotted(NEO_IMPL) + "\n").getBytes(StandardCharsets.UTF_8));
			out.putNextEntry(new JarEntry("fabric.mod.json"));
			out.write("{}".getBytes(StandardCharsets.UTF_8));
			out.putNextEntry(new JarEntry("META-INF/neoforge.mods.toml"));
			out.write("".getBytes(StandardCharsets.UTF_8));
		}
		return jar;
	}

	private static Map<String, byte[]> classes() {
		Map<String, byte[]> classes = new HashMap<>();
		ClassWriter service = new ClassWriter(0);
		service.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, SERVICE, null, "java/lang/Object", null);
		service.visitEnd();
		classes.put(SERVICE, service.toByteArray());
		classes.put(FABRIC_IMPL, impl(FABRIC_IMPL, "java/lang/Object", "net/fabricmc/loader/api/FabricLoader", "getInstance",
				"()Lnet/fabricmc/loader/api/FabricLoader;"));
		classes.put(FALLBACK_IMPL, impl(FALLBACK_IMPL, "java/lang/Object", null, null, null));
		classes.put(NEO_IMPL, impl(NEO_IMPL, "java/lang/Object", "net/neoforged/neoforge/common/ModConfigSpec", "builder", "()V"));
		classes.put(FABRIC_BASE, impl(FABRIC_BASE, "java/lang/Object", "net/fabricmc/loader/api/FabricLoader", "getInstance",
				"()Lnet/fabricmc/loader/api/FabricLoader;"));
		classes.put(VIA_BASE, impl(VIA_BASE, FABRIC_BASE, null, null, null));
		return classes;
	}

	/** A public provider of {@link #SERVICE} with a no-arg constructor and, optionally, one static call it never makes. */
	private static byte[] impl(String name, String superName, String callOwner, String callName, String callDesc) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, new String[] {SERVICE});
		MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();
		if (callOwner != null) {
			MethodVisitor probe = writer.visitMethod(Opcodes.ACC_PUBLIC, "isActive", "()V", null, null);
			probe.visitCode();
			probe.visitMethodInsn(Opcodes.INVOKESTATIC, callOwner, callName, callDesc, false);
			if (!callDesc.endsWith(")V")) probe.visitInsn(Opcodes.POP);
			probe.visitInsn(Opcodes.RETURN);
			probe.visitMaxs(0, 0);
			probe.visitEnd();
		}
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static String dotted(String internal) {
		return internal.replace('/', '.');
	}
}
