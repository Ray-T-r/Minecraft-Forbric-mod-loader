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
	}

	/** {@code NAME='value'} as lib.sh assigns it, unquoted the way bash unquotes a single-quoted word. */
	private static String libValue(String name) throws Exception {
		String lib = Files.readString(RUN.resolve("lib.sh"), StandardCharsets.UTF_8);
		Matcher assignment = Pattern.compile("(?m)^" + name + "='([^']*)'$").matcher(lib);
		assertTrue(assignment.find(), "lib.sh no longer assigns " + name);
		return assignment.group(1);
	}

	private static Path jar(Path file, String entry) throws Exception {
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
			out.putNextEntry(new ZipEntry(entry));
			out.write("# forbric-native-reference-v1\n".getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
		}
		return file;
	}
}
