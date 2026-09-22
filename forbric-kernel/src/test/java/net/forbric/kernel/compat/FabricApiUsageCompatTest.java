package net.forbric.kernel.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FabricApiUsageCompatTest {
	@TempDir Path temporary;
	private static final String PREFIX = "net/fabricmc/fabric/api/";

	@Test void namesAllFourPinnedApiSurfacesIncludingNestedJars() throws Exception {
		Path jar = CompatProbeJars.write(temporary.resolve("candidate.jar"), Map.of(
				"mod/Loot.class", CompatProbeJars.type("mod/Loot", PREFIX + "loot/v3/LootTableEvents"),
				"mod/Tabs.class", CompatProbeJars.type("mod/Tabs", PREFIX + "creativetab/v1/Tab", PREFIX + "client/creativetab/v1/Screen"),
				"META-INF/jars/nested.jar", CompatProbeJars.bytes(Map.of(
						"mod/Models.class", CompatProbeJars.type("mod/Models", PREFIX + "client/model/loading/v1/ModelLoadingPlugin")))));
		var result = CompatProbeProcess.run(temporary, "python3", "fapi-usage.py", jar.toString());
		assertEquals(0, result.exitCode(), result.output());
		for (String surface : new String[] {"loot/v3/", "creativetab/v1/", "client/creativetab/v1/", "client/model/loading/v1/"}) {
			assertTrue(result.output().contains(PREFIX + surface), result.output());
		}
		assertTrue(result.output().contains("candidate.jar :: META-INF/jars/nested.jar"), result.output());
		assertTrue(result.output().contains("mod/Models.class"), result.output());
	}

	@Test void textAndBundledApiDefinitionsDoNotProveModUsage() throws Exception {
		var decoy = CompatProbeJars.writer("mod/Decoy");
		decoy.newUTF8(PREFIX + "loot/v3/LootTableEvents");
		decoy.visitEnd();
		Path jar = CompatProbeJars.write(temporary.resolve("candidate.jar"), Map.of(
				"mod/Decoy.class", decoy.toByteArray(),
				"fabric.mod.json", ("{\"api\":\"" + PREFIX + "loot/v3/LootTableEvents\"}").getBytes(StandardCharsets.UTF_8),
				"META-INF/jars/api.jar", CompatProbeJars.bytes(Map.of(
						PREFIX + "loot/v3/LootTableEvents.class", CompatProbeJars.type(PREFIX + "loot/v3/LootTableEvents")))));
		var result = CompatProbeProcess.run(temporary, "python3", "fapi-usage.py", jar.toString());
		assertEquals(0, result.exitCode(), result.output());
		assertTrue(result.output().contains("API consumer groups: 0"), result.output());
		assertFalse(result.output().contains("LootTableEvents"), result.output());
	}

	// The symbol set is a parameter now, so the three things that could quietly stop being true about it
	// each get an assertion: the default did not move, another set actually selects different symbols, and
	// "this class DEFINES the API" is still data rather than a guess derived from the surface prefixes.

	@Test void theDefaultSymbolSetIsStillTheFabricOneAndSaysSo() throws Exception {
		Path jar = CompatProbeJars.write(temporary.resolve("candidate.jar"), Map.of(
				"mod/Loot.class", CompatProbeJars.type("mod/Loot", PREFIX + "loot/v3/LootTableEvents")));
		var result = CompatProbeProcess.run(temporary, "python3", "fapi-usage.py", jar.toString());
		assertEquals(0, result.exitCode(), result.output());
		assertTrue(result.output().contains("symbol set: fabric-api-suppressed (4 surface(s)"), result.output());
		assertTrue(result.output().contains("API consumer groups: 1"), result.output());
	}

	@Test void anotherPresetFindsForgeEventListenersAndNotTheFabricOnes() throws Exception {
		Path jar = CompatProbeJars.write(temporary.resolve("candidate.jar"), Map.of(
				"mod/Chat.class", CompatProbeJars.type("mod/Chat", "net/minecraftforge/event/ServerChatEvent"),
				"mod/Loot.class", CompatProbeJars.type("mod/Loot", PREFIX + "loot/v3/LootTableEvents")));
		var result = CompatProbeProcess.run(temporary, "python3", "fapi-usage.py",
				"--preset", "minecraftforge-events", jar.toString());
		assertEquals(0, result.exitCode(), result.output());
		assertTrue(result.output().contains("net/minecraftforge/event/ServerChatEvent <- mod/Chat.class"), result.output());
		// The Fabric consumer is in the same jar and must NOT be reported under this set — otherwise the
		// preset is decoration and the tool is still answering its old question.
		assertFalse(result.output().contains("LootTableEvents"), result.output());
	}

	@Test void aSymbolFileDrivesBothTheSurfacesAndWhatCountsAsDefiningThem() throws Exception {
		Path symbols = temporary.resolve("symbols.txt");
		Files.writeString(symbols, "# one surface, one definer\nzoo/api/\n!zoo/impl/\n", StandardCharsets.UTF_8);
		Path jar = CompatProbeJars.write(temporary.resolve("candidate.jar"), Map.of(
				"mod/User.class", CompatProbeJars.type("mod/User", "zoo/api/Feeder"),
				// Ships the API itself: named by the `!` line, so it is a definer, not a consumer.
				"zoo/impl/Bundled.class", CompatProbeJars.type("zoo/impl/Bundled", "zoo/api/Feeder")));
		var result = CompatProbeProcess.run(temporary, "python3", "fapi-usage.py",
				"--symbols", symbols.toString(), jar.toString());
		assertEquals(0, result.exitCode(), result.output());
		assertTrue(result.output().contains("zoo/api/Feeder <- mod/User.class"), result.output());
		assertFalse(result.output().contains("zoo/impl/Bundled.class"), result.output());
	}

	@Test void aSymbolSetWithNoSurfacesIsRefusedRatherThanReadingClean() throws Exception {
		Path symbols = temporary.resolve("empty.txt");
		Files.writeString(symbols, "# nothing but a definer\n!zoo/impl/\n", StandardCharsets.UTF_8);
		Path jar = CompatProbeJars.write(temporary.resolve("candidate.jar"), Map.of(
				"mod/User.class", CompatProbeJars.type("mod/User", "zoo/api/Feeder")));
		var result = CompatProbeProcess.run(temporary, "python3", "fapi-usage.py",
				"--symbols", symbols.toString(), jar.toString());
		// Zero surfaces would make every jar read clean, which is the shape of a green that means nothing.
		assertEquals(2, result.exitCode(), result.output());
	}
}
