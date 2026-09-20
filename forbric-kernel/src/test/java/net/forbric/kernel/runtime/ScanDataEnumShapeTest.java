package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.annotation.ElementType;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

import net.forbric.kernel.discovery.ModFileScanner;

/**
 * The two index builders must wrap an enum-valued annotation member in the shape their OWN ecosystem's scanner
 * uses. The shapes differ, and neither is a String.
 *
 * <p>This is not a style point. The MinecraftForge index was empty for the kernel's whole life, so nothing ever
 * read a value out of it; the first boot that filled it died in SuperMartijn642's Core Lib with
 * {@code ClassCastException: String cannot be cast to ModFileScanData$EnumData}, which Core Lib reports as
 * "Failed to register @RegistryEntryAcceptor annotation target" — text that names the mod, not the kernel. The
 * NeoForge half carried the same bare String and was one enum-reading mod away from the same thing.
 *
 * <p>The builders are loaded from the compiled game-side output with the carrier on the classpath. Everything
 * else — {@code ModFileScanner}, {@code org.objectweb.asm.Type} — resolves through the parent, so the records
 * crossing the seam are the same classes on both sides, exactly as at runtime.
 */
class ScanDataEnumShapeTest {
	private static final Path RUNTIME =
			Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"));
	private static final Path OLD = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", "../forbric-loader"));
	private static final Path FORGE = OLD.resolve("run/forge-runtime/forge-runtime.jar");
	private static final Path NEO = OLD.resolve("run/neoforge-runtime/neoforge-runtime.jar");
	private static final String ENUM_DESC = "Lcom/supermartijn642/core/registry/RegistryEntryAcceptor$Registry;";

	@Test
	void minecraftForgeGetsEnumDataOfTypeAndConstantName() throws Exception {
		assumeTrue(Files.isRegularFile(FORGE), "staged Forge carrier absent");
		Object value = registryMember("net.forbric.kernel.runtime.KernelForgeScanData", FORGE);

		assertEquals("net.minecraftforge.forgespi.language.ModFileScanData$EnumData", value.getClass().getName(),
				"Core Lib casts this member straight to EnumData without an instanceof check");
		assertEquals(Type.getType(ENUM_DESC), value.getClass().getMethod("clazz").invoke(value));
		assertEquals("MENU_TYPES", value.getClass().getMethod("value").invoke(value));
	}

	@Test
	void neoForgeGetsEnumHolderOfDescriptorAndConstantName() throws Exception {
		assumeTrue(Files.isRegularFile(NEO), "staged NeoForge carrier absent");
		Object value = registryMember("net.forbric.kernel.runtime.KernelScanData", NEO);

		assertEquals("net.neoforged.fml.loading.modscan.ModAnnotation$EnumHolder", value.getClass().getName(),
				"NeoForge's own scanner stores an EnumHolder, and it holds the DESCRIPTOR, not a Type");
		assertEquals(ENUM_DESC, value.getClass().getMethod("desc").invoke(value));
		assertEquals("MENU_TYPES", value.getClass().getMethod("value").invoke(value));
	}

	/** Builds a one-annotation index through {@code builder} and returns the wrapped {@code registry} member. */
	private static Object registryMember(String builder, Path carrier) throws Exception {
		assumeTrue(Files.isDirectory(RUNTIME), "game-side classes not compiled: " + RUNTIME);
		ModFileScanner.Found found = new ModFileScanner.Found(
				"Lcom/supermartijn642/core/registry/RegistryEntryAcceptor;",
				ElementType.FIELD,
				"com/supermartijn642/packedup/PackedUp",
				"container",
				Map.of("registry", new ModFileScanner.EnumValue(ENUM_DESC, "MENU_TYPES")));

		try (URLClassLoader loader = new URLClassLoader(new URL[] {
				RUNTIME.toUri().toURL(), carrier.toUri().toURL()},
				ScanDataEnumShapeTest.class.getClassLoader())) {
			Object scanData = Class.forName(builder, true, loader)
					.getMethod("build", List.class, List.class)
					.invoke(null, List.of(found), List.of());
			Set<?> annotations = (Set<?>) scanData.getClass().getMethod("getAnnotations").invoke(scanData);
			assertEquals(1, annotations.size());
			Object annotation = annotations.iterator().next();
			Map<?, ?> values = (Map<?, ?>) annotation.getClass().getMethod("annotationData").invoke(annotation);
			Object value = values.get("registry");
			assertInstanceOf(Object.class, value, "the registry member is missing from the built index");
			return value;
		}
	}
}
