/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MixinPlatformIdentity;

/**
 * A mod's Mixin config plugin whose platform sense lives entirely in a base class from a library the mod bundles —
 * a library with no loader manifest, which the boot attributes to no mod — under the real Mixin bootstrap. Mixin builds
 * the mod's plugin, the library base's constructor reads the service name, and the plugin lets its mixin apply only on
 * a platform it knows: {@code gear=plain+mixin} means the base was told the mod's own platform and Mixin acted on it.
 *
 * <p>The fixture's library half ({@code fixture.libraryplugin.lib}) is moved out of the mod's jar into a jar of its own
 * that the loader owns beside the mod's but records no mod for.
 */
class MixinPlatformLibraryPluginWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/libraryplugin");
	private static final String CONFIG = "libraryplugin.mixins.json";
	private static final String LIBRARY_PACKAGE = "fixture/libraryplugin/lib/";

	@TempDir static Path work;
	private static Path mod;
	private static Path library;

	@BeforeAll static void compile() throws Exception {
		List<Path> sources = List.of(
				SOURCES.resolve("fixture/libraryplugin/lib/LoaderSensingPlugin.java"),
				SOURCES.resolve("fixture/libraryplugin/mod/WidgetPlugin.java"),
				SOURCES.resolve("fixture/libraryplugin/mod/Seen.java"),
				SOURCES.resolve("fixture/libraryplugin/mod/Gear.java"),
				SOURCES.resolve("fixture/libraryplugin/mod/Probe.java"),
				SOURCES.resolve("fixture/libraryplugin/mod/mixin/GearMixin.java"));
		Path both = WeaveHarness.fixture(work, "libraryplugin-all", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		mod = split(both, work.resolve("widget.jar"), false);
		library = split(both, work.resolve("loader-sense-lib.jar"), true);
	}

	/** The entries of {@code from} inside the library package ({@code library}) or outside it, as a jar of their own. */
	private static Path split(Path from, Path to, boolean library) throws Exception {
		try (JarInputStream in = new JarInputStream(Files.newInputStream(from));
				JarOutputStream out = new JarOutputStream(Files.newOutputStream(to))) {
			for (JarEntry entry; (entry = in.getNextJarEntry()) != null; ) {
				if (entry.getName().startsWith(LIBRARY_PACKAGE) != library) continue;
				out.putNextEntry(new JarEntry(entry.getName()));
				in.transferTo(out);
				out.closeEntry();
			}
		}
		return to;
	}

	private static WeaveHarness.Result run(String label, Ecosystem owner, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, mod, CONFIG, "widgetmod", owner, EnvType.SERVER,
				"fixture.libraryplugin.mod.Probe", "report", properties);
	}

	/** The library beside the mod, unattributed; the mod's jar attributed to its config's owner unless {@code attributed} is off. */
	private static Map<String, String> withLibrary(boolean attributed, String... more) {
		Map<String, String> properties = new java.util.LinkedHashMap<>();
		properties.put(WeaveHarnessMain.LIBRARIES, library.toString());
		if (attributed) properties.put(WeaveHarnessMain.ORIGINS, "on");
		for (int i = 0; i + 1 < more.length; i += 2) properties.put(more[i], more[i + 1]);
		return properties;
	}

	private static void returned(WeaveHarness.Result run, String report) {
		assertTrue(run.printedLine(WeaveHarnessMain.DONE + " " + report), run.describe());
	}

	@Test void theLibraryBaseIsToldTheModsOwnPlatformAndThePluginActsOnIt() throws Exception {
		returned(run("fabric", Ecosystem.FABRIC, withLibrary(true)), "platform=fabric gear=plain+mixin");
		returned(run("forge", Ecosystem.FORGE, withLibrary(true)), "platform=forge gear=plain+mixin");
		returned(run("neoforge", Ecosystem.NEOFORGE, withLibrary(true)), "platform=neoforge gear=plain+mixin");
	}

	/** Neither the mod's jar nor the library attributed: no frame has a platform, and the answer is the kernel's, as before. */
	@Test void withNoAttributedFrameTheBaseIsToldTheKernelsName() throws Exception {
		returned(run("unattributed", Ecosystem.FABRIC, withLibrary(false)), "platform=unknown:Forbric gear=plain");
	}

	@Test void switchedOffTheBaseIsToldTheKernelsName() throws Exception {
		returned(run("off", Ecosystem.NEOFORGE, withLibrary(true, MixinPlatformIdentity.PROPERTY, "off")),
				"platform=unknown:Forbric gear=plain");
	}
}
