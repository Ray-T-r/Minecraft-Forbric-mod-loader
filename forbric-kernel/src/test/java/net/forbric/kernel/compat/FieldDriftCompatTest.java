package net.forbric.kernel.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Opcodes;

class FieldDriftCompatTest {
	@TempDir Path temporary;

	@Test void distinguishesRemovedDescriptorsAndSurvivingTwins() throws Exception {
		Path vanilla = CompatProbeJars.write(temporary.resolve("vanilla.jar"), Map.of(
				"net/minecraft/Fixture.class", fields("I", "I"),
				"net/minecraft/Missing.class", CompatProbeJars.type("net/minecraft/Missing")));
		Path merged = CompatProbeJars.write(temporary.resolve("merged.jar"), Map.of(
				"net/minecraft/Fixture.class", fields("J", "I", "J")));
		var result = CompatProbeProcess.run(temporary, "python3", "field-drift.py", vanilla.toString(), merged.toString());
		assertEquals(0, result.exitCode(), result.output());
		assertTrue(result.output().contains("vanilla classes absent from merged base: 1"), result.output());
		assertTrue(result.output().contains("GONE: vanilla descriptor no longer exists (1)"), result.output());
		assertTrue(result.output().contains("SHADOWED: vanilla descriptor survives beside another (1)"), result.output());
		assertTrue(result.output().lines().anyMatch(line -> line.contains("changed") && line.contains("I   ->  J")), result.output());
		assertTrue(result.output().lines().anyMatch(line -> line.contains("twin") && line.contains("I   beside  J")), result.output());
	}

	@Test void unchangedDescriptorsAreNotFindings() throws Exception {
		Path jar = CompatProbeJars.write(temporary.resolve("same.jar"), Map.of("net/minecraft/Fixture.class", fields("I", "I")));
		var result = CompatProbeProcess.run(temporary, "python3", "field-drift.py", jar.toString(), jar.toString());
		assertEquals(0, result.exitCode(), result.output());
		assertTrue(result.output().contains("GONE: vanilla descriptor no longer exists (0)"), result.output());
		assertTrue(result.output().contains("SHADOWED: vanilla descriptor survives beside another (0)"), result.output());
	}

	private static byte[] fields(String changed, String... twins) {
		var writer = CompatProbeJars.writer("net/minecraft/Fixture");
		writer.visitField(Opcodes.ACC_PUBLIC, "changed", changed, null, null).visitEnd();
		for (String twin : twins) writer.visitField(Opcodes.ACC_PUBLIC, "twin", twin, null, null).visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}
}
