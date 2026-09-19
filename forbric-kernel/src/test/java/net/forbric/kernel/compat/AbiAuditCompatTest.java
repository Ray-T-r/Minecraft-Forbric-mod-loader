package net.forbric.kernel.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AbiAuditCompatTest {
	@TempDir Path temporary;

	@Test void reportsMissingTypesAndNestedConsumersButNotCarrierTypes() throws Exception {
		Path mods = Files.createDirectory(temporary.resolve("mods"));
		CompatProbeJars.write(mods.resolve("consumer.jar"), Map.of(
				"mod/Entry.class", CompatProbeJars.type("mod/Entry", "net/minecraftforge/Known", "net/minecraftforge/Missing"),
				"META-INF/jars/inner.jar", CompatProbeJars.bytes(Map.of(
						"mod/Nested.class", CompatProbeJars.type("mod/Nested", "[Lnet/neoforged/Absent;")))));
		Path carrier = CompatProbeJars.write(temporary.resolve("carrier.jar"), Map.of(
				"net/minecraftforge/Known.class", CompatProbeJars.type("net/minecraftforge/Known")));
		var result = CompatProbeProcess.run(temporary, "python3", "abi-audit.py", mods.toString(), carrier.toString());
		assertEquals(0, result.exitCode(), result.output());
		assertTrue(result.output().contains("net/minecraftforge/Missing"), result.output());
		assertTrue(result.output().contains("consumer.jar :: META-INF/jars/inner.jar"), result.output());
		assertTrue(result.output().contains("net/neoforged/Absent"), result.output());
		assertFalse(result.output().contains("net/minecraftforge/Known"), result.output());
	}

	@Test void addingTheMissingCarrierClassClearsTheFinding() throws Exception {
		Path jar = CompatProbeJars.write(temporary.resolve("consumer.jar"), Map.of(
				"mod/Entry.class", CompatProbeJars.type("mod/Entry", "net/minecraftforge/Available")));
		Path carrier = CompatProbeJars.write(temporary.resolve("carrier.jar"), Map.of(
				"net/minecraftforge/Available.class", CompatProbeJars.type("net/minecraftforge/Available")));
		var result = CompatProbeProcess.run(temporary, "python3", "abi-audit.py", jar.toString(), carrier.toString());
		assertEquals(0, result.exitCode(), result.output());
		assertTrue(result.output().contains("finding groups: 0"), result.output());
	}

	@Test void corruptNestedBytecodeCannotMasqueradeAsAnEmptyAudit() throws Exception {
		Path jar = CompatProbeJars.write(temporary.resolve("consumer.jar"), Map.of("bad.class", new byte[] {1, 2, 3}));
		Path carrier = CompatProbeJars.write(temporary.resolve("carrier.jar"), Map.of());
		var result = CompatProbeProcess.run(temporary, "python3", "abi-audit.py", jar.toString(), carrier.toString());
		assertEquals(2, result.exitCode(), result.output());
		assertTrue(result.output().contains("unreadable:"), result.output());
	}
}
