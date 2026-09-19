package net.forbric.kernel.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
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
}
