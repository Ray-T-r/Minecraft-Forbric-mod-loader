/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.boot.CrossEcosystemDeclarations.Read;

/**
 * Where {@link DeclarationReaderModListInjector} fires on real bytecode: nowhere in the platform, and in the mod
 * fixtures only in classes that read declarations out of {@code ModList}.
 *
 * <p>The platform half is the scoping claim. The merged base and both carriers enumerate {@code ModList} in a dozen
 * places — the Mods screen, the version checker, crash-report sections, MinecraftForge's handshake — and none of them
 * may ever start listing a Fabric mod; none of them reads a declaration, so none is edited.
 */
@org.junit.jupiter.api.parallel.ResourceLock("CrossEcosystemDeclarations")
class DeclarationReaderCensusTest {
	private final DeclarationReaderModListInjector injector = new DeclarationReaderModListInjector();

	/** Running the injector records how each reader reads its keys; a census must not leave that behind. */
	@org.junit.jupiter.api.BeforeEach
	@org.junit.jupiter.api.AfterEach
	void forgetReads() {
		net.forbric.kernel.boot.CrossEcosystemDeclarations.resetForTests();
	}

	@Test
	void noPlatformClassIsEdited() throws IOException {
		Path run = TestFixtures.stagedRoot();
		List<Path> platform = List.of(run.resolve("merged-base/patched-mc-merged-26.2.jar"),
				run.resolve("neoforge-runtime/neoforge-runtime.jar"), run.resolve("forge-runtime/forge-runtime.jar"));
		for (Path jar : platform) TestFixtures.require(Fixture.STAGED, Files.isRegularFile(jar), jar + " is absent");
		TreeSet<String> edited = new TreeSet<>();
		int classes = 0;
		for (Path jar : platform) classes += scan(jar.getFileName().toString(), Files.readAllBytes(jar), edited, 0);
		assertTrue(classes > 10_000, "the census read only " + classes + " classes");
		assertEquals(new TreeSet<>(), edited);
	}

	/**
	 * Every mod fixture, nested jars included. Prints what was edited (the measurement behind the injector's javadoc)
	 * and holds the two facts that do not depend on which fixtures happen to be present: only mod classes are edited,
	 * and Sodium's config loader is among them wherever a sodium-neoforge build is.
	 */
	@Test
	void inTheModFixturesOnlyDeclarationReadersAreEdited() throws IOException {
		Path root = Path.of("build/compat-inputs");
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isDirectory(root), "build/compat-inputs is absent");
		List<Path> jars = new ArrayList<>();
		// The real path: a worktree links build/compat-inputs to the main checkout's, and a walk does not enter a link.
		try (Stream<Path> walk = Files.walk(root.toRealPath(), 3)) {
			walk.filter(p -> p.getParent() != null && p.getParent().getFileName().toString().equals("mods")
					&& p.getFileName().toString().endsWith(".jar")).sorted().forEach(jars::add);
		}
		TestFixtures.require(Fixture.THIRD_PARTY, !jars.isEmpty(), "no mod jars under build/compat-inputs/*/mods");
		TreeSet<String> edited = new TreeSet<>();
		TreeSet<String> seen = new TreeSet<>();
		boolean sodium = false;
		for (Path jar : jars) {
			if (!seen.add(jar.getFileName().toString())) continue;
			sodium |= jar.getFileName().toString().startsWith("sodium-neoforge-");
			scan(jar.getFileName().toString(), Files.readAllBytes(jar), edited, 0);
		}
		System.out.println("[DeclarationReaderCensus] " + seen.size() + " fixture jar(s), " + edited.size()
				+ " edited class(es):");
		edited.forEach(entry -> System.out.println("  " + entry));
		for (String entry : edited) {
			String type = entry.substring(entry.indexOf(' ') + 1);
			assertTrue(!type.startsWith("net/minecraft/") && !type.startsWith("net/neoforged/")
					&& !type.startsWith("net/minecraftforge/") && !type.startsWith("net/fabricmc/"), entry);
		}
		if (sodium) {
			assertTrue(edited.stream().anyMatch(e -> e.endsWith(" net/caffeinemc/mods/sodium/neoforge/config/ConfigLoaderForge")),
					edited::toString);
		}
	}

	/**
	 * Every call the injector can write lands on a method that exists: for each family, each {@code ModList} member it
	 * redirects, as the real carrier declares it, has a public static twin with the rewritten descriptor on the compiled
	 * game-side hook. The execution tests run most of them; this holds all of them, both families.
	 */
	@Test
	void everyRedirectedModListMemberHasItsHook() throws IOException {
		Path run = TestFixtures.stagedRoot();
		Path compiled = Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"));
		TestFixtures.require(Fixture.GAME_SIDE, Files.isDirectory(compiled), "the game side is not compiled");
		java.util.Map<net.forbric.api.Ecosystem, Path> carriers = java.util.Map.of(
				net.forbric.api.Ecosystem.NEOFORGE, run.resolve("neoforge-runtime/neoforge-runtime.jar"),
				net.forbric.api.Ecosystem.FORGE, run.resolve("forge-runtime/forge-runtime.jar"));
		List<String> missing = new ArrayList<>();
		int checked = 0;
		for (DeclarationReaderModListInjector.Family family : DeclarationReaderModListInjector.FAMILIES) {
			Path carrier = carriers.get(family.ecosystem());
			TestFixtures.require(Fixture.STAGED, Files.isRegularFile(carrier), carrier + " is absent");
			org.objectweb.asm.tree.ClassNode modList = node(classIn(Files.readAllBytes(carrier), family.modList() + ".class"));
			org.objectweb.asm.tree.ClassNode hook = node(Files.readAllBytes(compiled.resolve(family.hook() + ".class")));
			for (var member : family.members().entrySet()) {
				var declared = modList.methods.stream().filter(m -> m.name.equals(member.getKey())
						&& m.desc.startsWith(member.getValue())).toList();
				if (declared.size() != 1) {
					missing.add(family.modList() + "." + member.getKey() + member.getValue() + " is declared "
							+ declared.size() + " time(s)");
					continue;
				}
				boolean isStatic = (declared.get(0).access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0;
				if (isStatic != family.staticList()) missing.add(family.modList() + "." + member.getKey() + " static=" + isStatic);
				String desc = DeclarationReaderModListInjector.hookDesc(declared.get(0).desc, family);
				boolean present = hook.methods.stream().anyMatch(m -> m.name.equals(member.getKey()) && m.desc.equals(desc)
						&& (m.access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0
						&& (m.access & org.objectweb.asm.Opcodes.ACC_PUBLIC) != 0);
				if (!present) missing.add(family.hook() + "." + member.getKey() + desc);
				checked++;
			}
		}
		assertEquals(List.of(), missing);
		assertEquals(14, checked, "seven ModList members in each of the two families");
	}

	private static org.objectweb.asm.tree.ClassNode node(byte[] bytes) {
		org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
		new org.objectweb.asm.ClassReader(bytes).accept(node, org.objectweb.asm.ClassReader.SKIP_CODE);
		return node;
	}

	/**
	 * The two real readers whose keys decide where a Fabric entrypoint name may go: Sodium's NeoForge config loader
	 * reads {@code sodium:config_api_user} as a class name, LibJF's NeoForge config core casts {@code libjf:config} to
	 * night-config's {@code Config}. So a Fabric mod's {@code sodium:config_api_user} entrypoint is offered to Sodium, and
	 * its {@code libjf:config} entrypoint — on Fabric, the class LibJF builds a config from — never reaches LibJF's
	 * migration table read, where a name would be a ClassCastException.
	 */
	@Test
	void theRealReadersSayHowTheyReadTheirKeys() throws IOException {
		Path root = Path.of("build/compat-inputs");
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isDirectory(root), "build/compat-inputs is absent");
		byte[] sodium = null, libjf = null;
		try (Stream<Path> walk = Files.walk(root.toRealPath(), 3)) {
			for (Path jar : walk.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList()) {
				String name = jar.getFileName().toString();
				if (sodium == null && name.startsWith("sodium-neoforge-")) {
					sodium = classIn(Files.readAllBytes(jar), "net/caffeinemc/mods/sodium/neoforge/config/ConfigLoaderForge.class");
				} else if (libjf == null && name.startsWith("libjf-") && name.contains("+forge")) {
					libjf = classIn(Files.readAllBytes(jar), "dev/jfronny/libjf/config/impl/dsl/DslConfigInstance.class");
				}
			}
		}
		TestFixtures.require(Fixture.THIRD_PARTY, sodium != null && libjf != null,
				"sodium-neoforge and LibJF's Forge build are not both under build/compat-inputs");
		assertEquals(java.util.Map.of("sodium:config_api_user", java.util.EnumSet.of(Read.NAME)), reads(sodium));
		assertEquals(java.util.Map.of("libjf:config", java.util.EnumSet.of(Read.OTHER)), reads(libjf));
	}

	private static java.util.Map<String, java.util.EnumSet<Read>> reads(byte[] bytes) {
		org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
		new org.objectweb.asm.ClassReader(bytes).accept(node, 0);
		return DeclarationReaderModListInjector.keyReads(node);
	}

	/** {@code entry} out of {@code jar} or any jar nested in it, or null. */
	private static byte[] classIn(byte[] jar, String entry) throws IOException {
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(jar))) {
			for (ZipEntry next; (next = in.getNextEntry()) != null; ) {
				if (next.getName().equals(entry)) return in.readAllBytes();
				if (next.getName().endsWith(".jar")) {
					byte[] found = classIn(in.readAllBytes(), entry);
					if (found != null) return found;
				}
			}
		}
		return null;
	}

	/** Runs the injector over every class in {@code jar} (and the jars inside it); returns how many classes it read. */
	private int scan(String name, byte[] jar, TreeSet<String> edited, int depth) throws IOException {
		int classes = 0;
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(jar))) {
			for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
				String path = entry.getName();
				if (path.endsWith(".jar") && depth < 3) {
					classes += scan(name + "!" + path, in.readAllBytes(), edited, depth + 1);
					continue;
				}
				if (!path.endsWith(".class") || path.endsWith("module-info.class") || path.startsWith("META-INF/")) continue;
				classes++;
				String type = path.substring(0, path.length() - ".class".length());
				byte[] bytes = in.readAllBytes();
				if (InjectorExecution.transform(injector, type.replace('/', '.'), bytes, EnvType.CLIENT) != bytes) {
					edited.add(name + " " + type);
				}
			}
		}
		return classes;
	}
}
