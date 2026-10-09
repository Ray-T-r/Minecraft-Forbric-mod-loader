/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MixinPlatformIdentity;

/**
 * A multi-loader mod's Mixin config plugin, under the real Mixin bootstrap: it reads the Mixin service's name to pick
 * its platform half and, as its Fabric half, decorates Knot's weaver — and both work on Forbric, unrewritten.
 *
 * <p>The plugin ({@code weave/pluginplatform}) asks through a helper class with a prefix test and two equalities, and
 * reaches Knot from the thread's context loader. Its decorator rewrites {@code Stamp}'s constant after letting the
 * weaver run, and {@code StampMixin} appends to the same method, so {@code stamp=decorated+mixin} means a class loaded
 * after the plugin went through the decorator AND Mixin still wove it inside it.
 */
class MixinPlatformIdentityWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/pluginplatform");
	private static final String CONFIG = "pluginplatform.mixins.json";

	@TempDir static Path work;
	private static Path fixture;

	@BeforeAll static void compile() throws Exception {
		List<Path> sources = new java.util.ArrayList<>();
		for (String name : List.of("SplitPlugin", "Platforms", "KnotDecoration", "Report", "Stamp", "Probe")) {
			sources.add(SOURCES.resolve("fixture/pluginplatform/" + name + ".java"));
		}
		sources.add(SOURCES.resolve("fixture/pluginplatform/mixin/StampMixin.java"));
		fixture = WeaveHarness.fixture(work, "pluginplatform", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)));
	}

	private static WeaveHarness.Result run(String label, Ecosystem owner, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, "splitmod", owner, EnvType.SERVER,
				"fixture.pluginplatform.Probe", "report", properties);
	}

	private static Map<String, String> owned(String... more) {
		Map<String, String> properties = new java.util.LinkedHashMap<>();
		properties.put(WeaveHarnessMain.ORIGINS, "on");
		for (int i = 0; i + 1 < more.length; i += 2) properties.put(more[i], more[i + 1]);
		return properties;
	}

	private static void returned(WeaveHarness.Result run, String report) {
		assertTrue(run.printedLine(WeaveHarnessMain.DONE + " " + report), run.describe());
	}

	@Test void eachBuildOfTheModPicksItsOwnHalfAndTheFabricHalfDecoratesTheWeaver() throws Exception {
		returned(run("fabric", Ecosystem.FABRIC, owned()), "platform=fabric knot=wrapped stamp=decorated+mixin");
		returned(run("forge", Ecosystem.FORGE, owned()), "platform=forge knot=not tried stamp=plain+mixin");
		returned(run("neoforge", Ecosystem.NEOFORGE, owned()), "platform=neoforge knot=not tried stamp=plain+mixin");
	}

	/** A jar the boot attributes to no single mod is told the kernel's own name, as before. */
	@Test void anUnattributedJarIsToldTheKernelsName() throws Exception {
		returned(run("unattributed", Ecosystem.FABRIC, Map.of()), "platform=unknown:Forbric knot=not tried stamp=plain+mixin");
	}

	/**
	 * Off, the plugin is told "Forbric" again — and Knot's field, which the decorator can still reach, is neither seeded
	 * nor read, so writing a decorator into it changes nothing.
	 */
	@Test void switchedOffNeitherTheNameNorKnotsFieldIsNative() throws Exception {
		returned(run("off", Ecosystem.FABRIC, owned(MixinPlatformIdentity.PROPERTY, "off")),
				"platform=unknown:Forbric knot=not tried stamp=plain+mixin");
		returned(run("off-knot", Ecosystem.FABRIC, owned(MixinPlatformIdentity.PROPERTY, "off", "fixture.pluginplatform.alwaysKnot", "true")),
				"platform=unknown:Forbric knot=empty stamp=plain+mixin");
	}
}
