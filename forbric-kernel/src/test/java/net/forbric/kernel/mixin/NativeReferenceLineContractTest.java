/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The gates' assertion that a launch hands the kernel its vanilla-reference sidecar is a grep of one boot line
 * (lib.sh's {@code NATIVE_REFS_FROM_SIDECAR}, used by gate-m1 and gate-m17). Held here to what
 * {@link NativeReferenceSources#describe()} really prints, with and without a sidecar, so a reworded line turns this red
 * rather than every gate run — and a gate that stopped asserting it is named too.
 */
class NativeReferenceLineContractTest {
	private static final Path RUN = Path.of("run");

	@Test
	void theGatesPatternMatchesTheLineOnlyWhenASidecarIsGiven(@TempDir Path work) throws Exception {
		Pattern gate = Pattern.compile(libValue("NATIVE_REFS_FROM_SIDECAR"));
		Path base = jar(work.resolve("base.jar"), "META-INF/forbric/native-reference/FABRIC/index.tsv");
		Path sidecar = jar(work.resolve("vanilla-reference-26.2.jar"), "META-INF/forbric/native-reference/FABRIC/index.tsv");
		assertTrue(gate.matcher(NativeReferenceSources.open(base, sidecar).describe()).find(),
				NativeReferenceSources.open(base, sidecar).describe());
		assertFalse(gate.matcher(NativeReferenceSources.open(base, null).describe()).find(),
				"a launch that dropped the flag reads the embedded index, and the gate must see that");
		assertFalse(gate.matcher(NativeReferenceSources.open(jar(work.resolve("bare.jar"), "version.json"), null).describe())
				.find());
	}

	@Test
	void gateM1AndGateM17AssertIt() throws Exception {
		for (String gate : new String[] {"gate-m1.sh", "gate-m17-installer.sh"}) {
			String script = Files.readString(RUN.resolve(gate), StandardCharsets.UTF_8);
			assertTrue(script.contains("\"$NATIVE_REFS_FROM_SIDECAR\""), gate + " no longer asserts where FABRIC references come from");
		}
		String server = Files.readString(RUN.resolve("launch-kernel-server.sh"), StandardCharsets.UTF_8);
		String client = Files.readString(RUN.resolve("launch-kernel-client.sh"), StandardCharsets.UTF_8);
		for (String launcher : new String[] {server, client}) {
			assertTrue(launcher.contains("--nativeReference \"$NATIVE_REF\""), "a launcher stopped passing the sidecar");
			assertEquals(1, launcher.split("--gameJar ", -1).length - 1, "the sidecar is never a second --gameJar");
		}
		// gate-m34's soak builds its own java command: it hands the sidecar over too, under its own flag.
		String soak = Files.readString(RUN.resolve("compat/soak-run.py"), StandardCharsets.UTF_8);
		assertTrue(soak.contains("'--nativeReference', str(native_reference)"), "soak-run.py stopped passing the sidecar");
		assertTrue(soak.contains("run/vanilla-reference-jar.sh"), "soak-run.py no longer asks the script for the base's sidecar");
	}

	/**
	 * run/vanilla-reference-jar.sh, which both launchers ask for the sidecar, hands out the staged one by the base it
	 * records, never by age: one recording this base is used even when it is older than the base, and one recording
	 * another base is not used even when it is newer (with no vanilla jar to rebuild from, the script then names none).
	 */
	@Test
	void theSidecarScriptChoosesTheStagedSidecarByTheBaseItRecords(@TempDir Path work) throws Exception {
		Path base = jar(work.resolve("stage/merged-base/base.jar"), "net/minecraft/Fixture.class");
		Path staged = work.resolve("stage/vanilla-reference/vanilla-reference-26.2.jar");
		Path noVanilla = work.resolve("no-such/26.2.jar");

		provenance(staged, sha1(base));
		Files.setLastModifiedTime(staged, java.nio.file.attribute.FileTime.fromMillis(1_000_000L));
		Files.setLastModifiedTime(base, java.nio.file.attribute.FileTime.fromMillis(2_000_000L));
		assertEquals(staged.toString(), script(base, noVanilla, work), "older, but measured against this base");

		provenance(staged, "0".repeat(40));
		Files.setLastModifiedTime(staged, java.nio.file.attribute.FileTime.fromMillis(3_000_000L));
		assertEquals("", script(base, noVanilla, work), "newer, but measured against another base");
	}

	private static void provenance(Path sidecar, String baseSha1) throws Exception {
		Files.createDirectories(sidecar.getParent());
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(sidecar))) {
			out.putNextEntry(new ZipEntry("META-INF/forbric/native-reference/FABRIC/index.tsv"));
			out.write("# forbric-native-reference-v1\n".getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
			out.putNextEntry(new ZipEntry("META-INF/forbric/vanilla-reference.properties"));
			out.write(("format=1\nvanilla-sha1=" + "0".repeat(40) + "\nbase-sha1=" + baseSha1 + "\n").getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
		}
	}

	/** What run/vanilla-reference-jar.sh prints on stdout for {@code base}, with {@code vanilla} as the vanilla jar. */
	private static String script(Path base, Path vanilla, Path work) throws Exception {
		ProcessBuilder builder = new ProcessBuilder("bash", RUN.resolve("vanilla-reference-jar.sh").toAbsolutePath().toString(),
				base.toString(), vanilla.toString()).redirectError(work.resolve("script.err").toFile());
		builder.environment().remove("VANILLA_REFERENCE_JAR");
		Process process = builder.start();
		String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
		assertTrue(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS), "the script finished");
		return out;
	}

	private static String sha1(Path file) throws Exception {
		return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(file)));
	}

	/** {@code NAME='value'} as lib.sh assigns it, unquoted the way bash unquotes a single-quoted word. */
	private static String libValue(String name) throws Exception {
		String lib = Files.readString(RUN.resolve("lib.sh"), StandardCharsets.UTF_8);
		Matcher assignment = Pattern.compile("(?m)^" + name + "='([^']*)'$").matcher(lib);
		assertTrue(assignment.find(), "lib.sh no longer assigns " + name);
		return assignment.group(1);
	}

	private static Path jar(Path file, String entry) throws Exception {
		Files.createDirectories(file.toAbsolutePath().getParent());
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
			out.putNextEntry(new ZipEntry(entry));
			out.write("# forbric-native-reference-v1\n".getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
		}
		return file;
	}
}
