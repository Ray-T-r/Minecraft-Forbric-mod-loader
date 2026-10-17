/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.NativeLocalPins;

/**
 * {@code NativeLocalPins}' {@code @ModifyVariable} ordinal pin through the real weave, on classes that share no name with any
 * game or mod.
 *
 * <p>{@code org.orchard.press.Mill.crush}, as the guest's loader has it, holds two booleans at the pour (ripeness, then
 * sweetness); as merged, the carrier asks its own sweetness question and keeps ripeness for after the pour, so sweetness is
 * the only boolean there. The guest {@code net.bramble.sip} modifies sweetness at every pour by the ordinal of the second
 * boolean. Pinned to the merged local of the same name and type, it is handed sweetness at every pour, as natively; switched
 * off, its ordinal names no local there and it does not run.
 */
class NativeVariablePinsWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/nativevariablepin");
	private static final String CONFIG = "nativevariablepin.mixins.json";
	private static final String MOD = "sip";
	private static final String TARGET = "org/orchard/press/Mill";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result pinned, off, natively;

	@BeforeAll static void weave() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = walk.filter(p -> p.toString().endsWith(".java") && !SOURCES.relativize(p).startsWith("native")).sorted().toList();
		}
		List<Path> nativeSources = new ArrayList<>(sources);
		nativeSources.remove(SOURCES.resolve("org/orchard/press/Mill.java"));
		nativeSources.add(SOURCES.resolve("native/org/orchard/press/Mill.java"));
		Path nativeFixture = WeaveHarness.fixture(work, "native", nativeSources, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g"));
		fixture = NativeWeaveReferences.with(work, WeaveHarness.fixture(work, "nativevariablepin", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g")),
				NativeWeaveReferences.classes(nativeFixture));
		pinned = run(fixture, "pinned", Map.of());
		off = run(fixture, "off", Map.of(NativeLocalPins.PROPERTY, "off"));
		natively = run(nativeFixture, "native", Map.of());
	}

	@Test void thePinnedModifierChangesTheSameLocalAsNatively() throws Exception {
		assertEquals(done(natively), done(pinned), pinned.describe() + "\n" + natively.describe());
		// The handler is handed sweetness each time (false, then true); as natively, the pour's argument was already read.
		assertEquals("apple: sip false, pour juice-apple sweet=false, got juice-apple | raspberry: sip true, pour juice-raspberry sweet=true, got juice-raspberry!",
				done(pinned), pinned.describe());
		assertTrue(pinned.printed("[Forbric/Mixin] net.bramble.sip.mixin.MillMixin.sip$sweeten: the local its ordinal 1 names at its point is another local in the merged"
				+ " org.orchard.press.Mill; pinned to slot"), pinned.describe());
		assertTrue(pinned.findings().stream().noneMatch(f -> f.modId().equals(MOD) && f.id().startsWith("mixin-injector:") && f.required()), pinned.findings().toString());
		WeaveHarness.assertWovenAndVerified(pinned, TARGET, fixture);
	}

	@Test void switchedOffTheModifierFindsNoSuchLocalAndDoesNotRun() {
		assertFalse(done(off).contains("sip "), off.describe());
		assertTrue(off.findings().stream().anyMatch(f -> f.modId().equals(MOD) && f.id().startsWith("mixin-injector:") && f.required()), off.findings().toString());
		assertNotEquals(done(natively), done(off), "the native oracle tells the runs apart");
	}

	private static String done(WeaveHarness.Result run) {
		String line = run.output().lines().filter(l -> l.startsWith(WeaveHarnessMain.DONE + " ")).findFirst().orElse("");
		return line.substring(Math.min(line.length(), WeaveHarnessMain.DONE.length() + 1));
	}

	private static WeaveHarness.Result run(Path fixture, String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER, "fixture.nativevariablepin.Probe", "probe", properties);
	}
}
