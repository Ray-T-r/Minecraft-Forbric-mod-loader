package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

class ForgeClientCanaryTest {
	@TempDir Path temporary;

	@Test
	void clientFixtureDropsOnlyDatapackEntriesAndLeavesItsSourceJarUnchanged() throws Exception {
		Path source = temporary.resolve("source with spaces.jar");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(source))) {
			for (String entry : List.of("data/forbriclive/forge/biome_modifier/probe.json",
					"data/forbriclive/worldgen/placed_feature/probe.json", "forbric/live/ForbricLiveClient.class",
					"META-INF/mods.toml", "assets/forbriclive/retained.txt")) {
				zip.putNextEntry(new ZipEntry(entry));
				zip.write(("fixture: " + entry).getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}
		byte[] original = Files.readAllBytes(source);
		Path rundir = temporary.resolve("instance with spaces");
		Files.createDirectories(rundir.resolve("mods"));
		String script = Files.readString(Path.of("run/gate-m26-forgeclient.sh"));
		int begin = script.indexOf("# CLIENT_CANARY_STAGE_BEGIN");
		int end = script.indexOf("# CLIENT_CANARY_STAGE_END", begin);
		assertTrue(begin >= 0 && end > begin, "missing executable client fixture staging contract");
		ProcessBuilder builder = new ProcessBuilder("bash", "-c", script.substring(begin, end));
		// Since Stage 3 of dropping MinecraftForge the block stages the NeoForge canary: the MinecraftForge one is refused.
		builder.environment().put("NEOLIVE", source.toString());
		builder.environment().put("RUNDIR", rundir.toString());
		assertSuccessful(builder);
		assertArrayEquals(original, Files.readAllBytes(source), "worldgen canary source must remain byte-for-byte intact");
		try (ZipFile staged = new ZipFile(rundir.resolve("mods/forbricneolive.jar").toFile());
				ZipFile unmodified = new ZipFile(source.toFile())) {
			assertEquals(List.of("forbric/live/ForbricLiveClient.class", "META-INF/mods.toml", "assets/forbriclive/retained.txt"),
					staged.stream().map(ZipEntry::getName).toList());
			for (ZipEntry entry : staged.stream().toList()) {
				assertArrayEquals(unmodified.getInputStream(unmodified.getEntry(entry.getName())).readAllBytes(),
						staged.getInputStream(entry).readAllBytes(), "staging changed " + entry.getName());
			}
		}
	}

	@Test
	void clientGateParsesAndNoLongerCarriesAnExpectedRedEscape() throws Exception {
		Path gate = Path.of("run/gate-m26-forgeclient.sh");
		String script = Files.readString(gate);
		// Phase 1 A landed: a red here is a regression, and gates-all must classify it as one.
		assertFalse(script.contains("EXPECTED: RED"), "the expected-red header must go when the gap closes");
		assertFalse(script.contains("EXPECTED-RED"), "no exit-2 reclassification may survive the fix");
		assertFalse(script.contains("CONTROL_FAIL"));
		assertTrue(script.contains("make-test-world.sh"));
		assertTrue(script.contains("SRC_MODS=\"$RUNDIR/empty-mods\""));
		assertSuccessful(new ProcessBuilder("bash", "-n", gate.toString()));
	}

	private static AbstractInsnNode nextCode(AbstractInsnNode instruction) {
		do instruction = instruction.getNext(); while (instruction != null && instruction.getOpcode() < 0);
		return instruction;
	}

	private static byte[] bytes(ZipFile zip, String name) throws Exception {
		var entry = zip.getEntry(name);
		assertNotNull(entry, "missing canary class: " + name);
		return zip.getInputStream(entry).readAllBytes();
	}

	private static void assertSuccessful(ProcessBuilder builder) throws Exception {
		Process process = builder.redirectErrorStream(true).start();
		assertTrue(process.waitFor(10, TimeUnit.SECONDS), "client canary contract subprocess timed out");
		assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
	}
}
