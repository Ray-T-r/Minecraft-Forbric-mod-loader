package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** The pure seams of the Forge→NeoForge modifier bridge, driven through the game-side class loader. */
class KernelForgeWorldgenSeamsTest {
	@Test
	void theTwoFamiliesPhasesShareNamesAndOrder() throws Exception {
		try (URLClassLoader loader = gameSideLoader()) {
			Class<?> seams = loader.loadClass("net.forbric.kernel.runtime.KernelForgeWorldgen");
			Method names = seams.getDeclaredMethod("phaseNames");
			names.setAccessible(true);
			assertEquals(List.of("BEFORE_EVERYTHING", "ADD", "REMOVE", "MODIFY", "AFTER_EVERYTHING"), names.invoke(null));
			Method forgePhase = seams.getDeclaredMethod("forgePhaseFor", String.class);
			forgePhase.setAccessible(true);
			for (Object name : (List<?>) names.invoke(null)) {
				Object phase = forgePhase.invoke(null, (String) name);
				assertNotNull(phase);
				assertEquals(name, ((Enum<?>) phase).name());
			}
		}
	}

	@Test
	void theLenientDecisionSkipsOnlyAParseableTypeTheRegistryLacks() throws Exception {
		try (URLClassLoader loader = gameSideLoader()) {
			Class<?> seams = loader.loadClass("net.forbric.kernel.runtime.KernelForgeWorldgen");
			Method decide = seams.getDeclaredMethod("lenientDecision", String.class, Predicate.class);
			decide.setAccessible(true);
			Predicate<String> known = id -> id.equals("forge:add_features");
			assertEquals("STRICT", decide.invoke(null, "forge:add_features", known), "a known type goes to Forge's codec");
			assertEquals("SKIP:bop", decide.invoke(null, "bop:custom", known), "an unknown serializer is skipped, attributed to its namespace");
			assertEquals("STRICT", decide.invoke(null, null, known), "no type: Forge's own error");
			assertEquals("STRICT", decide.invoke(null, "Not An Id!", known), "malformed: Forge's own error, never a silent skip");
			assertEquals("SKIP:minecraft", decide.invoke(null, "unqualified", known), "vanilla's default namespace");
			Predicate<String> throwing = id -> { throw new IllegalStateException("registry not built"); };
			assertEquals("STRICT", decide.invoke(null, "bop:custom", throwing), "a registry that cannot answer keeps Forge's codec");
		}
	}

	@Test
	void theAboutToStartBridgeNoLongerInvokesForgesOwnPass() throws Exception {
		Path compiled = Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"),
				"net/forbric/kernel/runtime/KernelGameServerAboutToStart.class");
		assumeTrue(Files.isRegularFile(compiled), "runtime helper not compiled");
		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(compiled)).accept(node, 0);
		for (MethodNode method : node.methods) {
			for (var insn : method.instructions) {
				assertFalse(insn instanceof LdcInsnNode ldc && "runModifiers".equals(ldc.cst),
						"KernelGameServerAboutToStart still reaches for Forge's runModifiers — two passes, two sources of truth");
			}
		}
		String source = Files.readString(Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelForgeWorldgen.java"), StandardCharsets.UTF_8);
		assertTrue(source.contains("standDown("), "the bridge must be able to stand down whole");
		assertTrue(source.indexOf("auditRoundTrip") > 0);
	}

	/** The runtime output plus the staged carriers and merged base, which is what these classes link against. */
	private static URLClassLoader gameSideLoader() throws Exception {
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "runtime").normalize();
		Path run = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"), "run").normalize();
		Path forgeRt = run.resolve("forge-runtime/forge-runtime.jar");
		Path neoRt = run.resolve("neoforge-runtime/neoforge-runtime.jar");
		Path merged = run.resolve("merged-base/patched-mc-merged-26.2.jar");
		assumeTrue(Files.isDirectory(compiled) && Files.isRegularFile(forgeRt) && Files.isRegularFile(neoRt) && Files.isRegularFile(merged),
				"the game-side set is not compiled, or the staged artifacts are absent");
		List<URL> urls = new java.util.ArrayList<>(List.of(compiled.toUri().toURL(), forgeRt.toUri().toURL(), neoRt.toUri().toURL(), merged.toUri().toURL()));
		// The bridge's signatures name DataFixerUpper and gson types; the game supplies both at runtime, and the
		// build takes them from the local Minecraft install by the same last-name-per-pattern rule as build.gradle.
		for (String pattern : List.of("com/mojang/datafixerupper", "com/google/code/gson", "com/mojang/brigadier")) {
			Path library = lastByNameUnder(pattern);
			assumeTrue(library != null, "no staged " + pattern + " jar in the local Minecraft libraries");
			urls.add(library.toUri().toURL());
		}
		return new URLClassLoader(urls.toArray(new URL[0]), KernelForgeWorldgenSeamsTest.class.getClassLoader());
	}

	private static Path lastByNameUnder(String pattern) throws Exception {
		String env = System.getenv("MC_DIR");
		Path root = Path.of(env != null ? env + "/libraries" : System.getProperty("user.home") + "/Library/Application Support/minecraft/libraries");
		Path under = root.resolve(pattern);
		if (!Files.isDirectory(under)) return null;
		try (var stream = Files.walk(under)) {
			return stream.filter(f -> f.toString().endsWith(".jar")).sorted(java.util.Comparator.comparing(f -> f.getFileName().toString()))
					.reduce((a, b) -> b).orElse(null);
		}
	}
}
