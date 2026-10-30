package net.forbric.kernel.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.forbric.api.CompatibilityFindings;
import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;
import net.forbric.kernel.discovery.ForbricModDiscoverer;
import net.forbric.kernel.discovery.MetadataFailures;

/**
 * A client is ASKED about a refused MinecraftForge jar — the confirmation names the mod and says why — and only
 * Continue lets it play on without it. (The stops — strict, dedicated server — are in MinecraftForgeRefusalTest.)
 *
 * <p>Red-checked by recording the refusal as a suspicion: the window was never opened and the launch was approved.
 */
@ResourceLock("ModCatalog")
@ResourceLock("system-properties")
class MinecraftForgeRefusalPromptTest {
	@TempDir Path temporary;

	@BeforeEach @AfterEach void reset() {
		MetadataFailures.reset();
		CompatibilityFindings.reset();
		CompatibilityDecision.reset();
		ModCatalog.publish(List.of());
		System.clearProperty(DialogLang.SWITCH);
	}

	@Test void theClientIsAskedAndOnlyContinueApproves() throws Exception {
		Path mods = Files.createDirectories(temporary.resolve("mods"));
		Path jar = mods.resolve("onlyforge.jar");
		try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
			out.putNextEntry(new java.util.jar.JarEntry("META-INF/mods.toml"));
			out.write(("modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"onlyforge\"\n"
					+ "version=\"1\"\ndisplayName=\"Only Forge\"\n").getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
		}
		new ForbricModDiscoverer().discoverJar(jar);
		System.setProperty(DialogLang.SWITCH, "en");
		MetadataFailures.recordFindings(path -> Ecosystem.FORGE, List.of(), mods, path -> false);
		ModCatalog.publish(List.of(new ModCatalog.Entry(Ecosystem.FORGE, "onlyforge", "Only Forge", "", "", List.of(),
				"onlyforge.jar", "", "")));

		List<DependencyReport.CompatibilityRow> asked = new ArrayList<>();
		assertFalse(CompatibilityDecision.decide(CompatibilityFindings.confirmedRequired(), CompatibilityDecision.Policy.ASK,
				true, rows -> { asked.addAll(rows); return DependencyDialogMain.QUIT; }), "Quit stops the launch");
		assertEquals(1, asked.size());
		assertEquals("Only Forge", asked.get(0).modName());
		assertEquals("MinecraftForge mod", asked.get(0).feature());
		assertTrue(asked.get(0).detail().contains("Forbric no longer supports MinecraftForge"), asked.get(0).detail());

		assertTrue(CompatibilityDecision.decide(CompatibilityFindings.confirmedRequired(), CompatibilityDecision.Policy.ASK,
				true, rows -> DependencyDialogMain.CONTINUE), "Continue plays on without it");
	}
}
