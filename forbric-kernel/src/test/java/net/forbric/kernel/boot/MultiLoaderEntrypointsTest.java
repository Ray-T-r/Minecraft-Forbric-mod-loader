package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import net.forbric.api.Ecosystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/** Leftover metadata must not discard the only registration code a real mod ships. */
class MultiLoaderEntrypointsTest {
	@TempDir Path temporary;
	@BeforeEach @AfterEach void reset() {
		MultiLoaderArbiter.reset();
		System.clearProperty("forbric.multiLoaderPreference");
		System.clearProperty(MultiLoaderArbiter.ENTRYPOINT_SWITCH);
	}

	@Test void aForgeConstructorWinsOverBothLeftoverManifests() throws Exception {
		Path jar = fixture("forge.jar", "example.ForgeEntry", Map.of("example/ForgeEntry.class",
				type("example/ForgeEntry", Ecosystem.FORGE, "example", null, false)));
		assertEquals(Ecosystem.FORGE, MultiLoaderArbiter.ownerOf(jar));
		assertEquals(List.of(Ecosystem.NEOFORGE, Ecosystem.FORGE, Ecosystem.FABRIC), MultiLoaderArbiter.declaredBy(jar),
				"declarations still provide presence aliases; only initialization ownership changes");
		assertFalse(MultiLoaderArbiter.suppressedFor(jar, Ecosystem.FORGE));
		assertTrue(MultiLoaderArbiter.suppressedFor(jar, Ecosystem.NEOFORGE));
		MultiLoaderArbiter.reset();
		System.setProperty("forbric.multiLoaderPreference", "fabric");
		assertEquals(Ecosystem.FORGE, MultiLoaderArbiter.ownerOf(jar),
				"a Forge-only constructor cannot be instantiated by Fabric's default adapter");
		MultiLoaderArbiter.reset();
		System.setProperty(MultiLoaderArbiter.ENTRYPOINT_SWITCH, "off");
		System.clearProperty("forbric.multiLoaderPreference");
		assertEquals(Ecosystem.NEOFORGE, MultiLoaderArbiter.ownerOf(jar), "the control restores the original loss");
	}

	@Test void aFabricInitializerWinsOverLowcodeAndJavaManifestTemplates() throws Exception {
		Path jar = fixture("fabric.jar", "example.FabricEntry", Map.of("example/FabricEntry.class",
				type("example/FabricEntry", null, null, "net/fabricmc/api/ModInitializer", true)));
		assertEquals(Ecosystem.FABRIC, MultiLoaderArbiter.ownerOf(jar));
		assertFalse(MultiLoaderArbiter.suppressedFor(jar, Ecosystem.FABRIC));
	}

	@Test void aNoArgForgeClassStillDoesNotImplementTheFabricMainContract() throws Exception {
		Path jar = fixture("wrong-interface.jar", "example.ForgeEntry", Map.of("example/ForgeEntry.class",
				type("example/ForgeEntry", Ecosystem.FORGE, "example", null, true)));
		System.setProperty("forbric.multiLoaderPreference", "fabric");
		assertEquals(Ecosystem.FORGE, MultiLoaderArbiter.ownerOf(jar));
	}

	@Test void theFabricContractMayBeInheritedFromAnOwnSuperclass() throws Exception {
		Path jar = fixture("inherited.jar", "example.Child", Map.of(
				"example/Parent.class", type("example/Parent", null, null, "net/fabricmc/api/ModInitializer", true),
				"example/Child.class", type("example/Child", null, null, null, true, "example/Parent")));
		assertEquals(Ecosystem.FABRIC, MultiLoaderArbiter.ownerOf(jar));
	}

	@Test void preferenceStillChoosesAmongRealImplementations() throws Exception {
		Path jar = fixture("universal.jar", "example.FabricEntry", Map.of(
				"example/ForgeEntry.class", type("example/ForgeEntry", Ecosystem.FORGE, "example", null, true),
				"example/NeoEntry.class", type("example/NeoEntry", Ecosystem.NEOFORGE, "example", null, true),
				"example/FabricEntry.class", type("example/FabricEntry", null, null, "net/fabricmc/api/ModInitializer", true)));
		assertEquals(Ecosystem.NEOFORGE, MultiLoaderArbiter.ownerOf(jar));
		MultiLoaderArbiter.reset();
		System.setProperty("forbric.multiLoaderPreference", "fabric,minecraftforge,neoforge");
		assertEquals(Ecosystem.FABRIC, MultiLoaderArbiter.ownerOf(jar));
	}

	@Test void anUnrelatedShadedModAnnotationDoesNotValidateTheWrongManifest() throws Exception {
		Path jar = fixture("shaded.jar", null, Map.of(
				"example/ForgeEntry.class", type("example/ForgeEntry", Ecosystem.FORGE, "example", null, true),
				"shaded/Library.class", type("shaded/Library", Ecosystem.NEOFORGE, "another_library", null, true)));
		assertEquals(Ecosystem.FORGE, MultiLoaderArbiter.ownerOf(jar));
	}

	@Test void dataOnlyJarsKeepTheirExistingPreference() throws Exception {
		Path jar = fixture("data.jar", null, Map.of());
		assertEquals(Ecosystem.NEOFORGE, MultiLoaderArbiter.ownerOf(jar));
		MultiLoaderArbiter.reset();
		System.setProperty("forbric.multiLoaderPreference", "fabric");
		assertEquals(Ecosystem.FABRIC, MultiLoaderArbiter.ownerOf(jar));
	}

	@Test void aDependencyMaySupplyTheFabricInitializerHierarchy() throws Exception {
		Path jar = fixture("external-parent.jar", "example.DependentEntry", Map.of("example/DependentEntry.class",
				type("example/DependentEntry", null, null, "dependency/InitializerContract", true)));
		assertEquals(Ecosystem.FABRIC, MultiLoaderArbiter.ownerOf(jar),
				"absence of dependency class bytes is unknown, not proof an entrypoint cannot run");
	}

	@Test void actualBisonAquariusAndBannerstoneUseTheirExecutableFamilies() throws Exception {
		Path mods = Path.of("build/compat-inputs/random/mods");
		List<String> names = List.of("animalgarden-bison-1.0.0-forge-26.2-65.0.0.jar",
				"aquariuslibs-1.2.0-forge-26.2-65.0.0.jar", "bannerstone-0.1.2.jar");
		boolean available = names.stream().allMatch(name -> Files.isRegularFile(mods.resolve(name)));
		if ("1".equals(System.getenv("FORBRIC_COMPAT_FIXTURES_REQUIRED"))) assertTrue(available, "real baseline fixtures absent");
		assumeTrue(available, "requires the prepared random baseline jars");
		for (int i = 0; i < names.size(); i++) {
			Path jar = mods.resolve(names.get(i));
			assertEquals(i < 2 ? Ecosystem.FORGE : Ecosystem.FABRIC, MultiLoaderArbiter.ownerOf(jar), names.get(i));
			MultiLoaderArbiter.reset();
			System.setProperty(MultiLoaderArbiter.ENTRYPOINT_SWITCH, "off");
			assertEquals(Ecosystem.NEOFORGE, MultiLoaderArbiter.ownerOf(jar), "negative control: " + names.get(i));
			System.clearProperty(MultiLoaderArbiter.ENTRYPOINT_SWITCH);
			MultiLoaderArbiter.reset();
		}
	}

	private Path fixture(String name, String fabricEntry, Map<String, byte[]> classes) throws Exception {
		Map<String, byte[]> entries = new LinkedHashMap<>(classes);
		entries.put("META-INF/mods.toml", ("modLoader=\"lowcodefml\"\nloaderVersion=\"[1,)\"\n[[mods]]\nmodId=\"example\"\nversion=\"1\"\n").getBytes(StandardCharsets.UTF_8));
		entries.put("META-INF/neoforge.mods.toml", ("modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\n[[mods]]\nmodId=\"example\"\nversion=\"1\"\n").getBytes(StandardCharsets.UTF_8));
		entries.put("fabric.mod.json", ("{\"schemaVersion\":1,\"id\":\"example\",\"version\":\"1\",\"entrypoints\":"
				+ (fabricEntry == null ? "{}" : "{\"main\":[\"" + fabricEntry + "\"]}") + "}").getBytes(StandardCharsets.UTF_8));
		Path path = temporary.resolve(name);
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(path))) {
			for (var entry : entries.entrySet()) {
				out.putNextEntry(new JarEntry(entry.getKey())); out.write(entry.getValue()); out.closeEntry();
			}
		}
		return path;
	}

	private static byte[] type(String name, Ecosystem family, String modId, String implemented, boolean noArg) {
		return type(name, family, modId, implemented, noArg, "java/lang/Object");
	}

	private static byte[] type(String name, Ecosystem family, String modId, String implemented, boolean noArg, String parent) {
		ClassWriter out = new ClassWriter(0);
		out.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, parent,
				implemented == null ? null : new String[] {implemented});
		if (family != null) {
			String descriptor = family == Ecosystem.FORGE ? "Lnet/minecraftforge/fml/common/Mod;" : "Lnet/neoforged/fml/common/Mod;";
			var annotation = out.visitAnnotation(descriptor, true); annotation.visit("value", modId); annotation.visitEnd();
		}
		var ctor = out.visitMethod(Opcodes.ACC_PUBLIC, "<init>", noArg ? "()V"
				: "(Lnet/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext;)V", null, null);
		ctor.visitCode(); ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, parent, "<init>", "()V", false);
		ctor.visitInsn(Opcodes.RETURN); ctor.visitMaxs(1, noArg ? 1 : 2); ctor.visitEnd();
		if ("net/fabricmc/api/ModInitializer".equals(implemented)) {
			var initializer = out.visitMethod(Opcodes.ACC_PUBLIC, "onInitialize", "()V", null, null);
			initializer.visitCode(); initializer.visitInsn(Opcodes.RETURN); initializer.visitMaxs(0, 1); initializer.visitEnd();
		}
		out.visitEnd(); return out.toByteArray();
	}
}
