/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.ExpectCaught;

/**
 * The offline report asks {@link NativeAbsentTargets} about a unit's mod as the boot does: with what discovery reads from
 * that unit's own manifest, so a mod built for another game is not answered for here either. A manifest that declares
 * two mods, or none for the platform, gives no mod, and nothing is answered — the boot leaves such a config unowned.
 */
class MixinFitReportManifestTest {
	@Test void aFabricUnitIsReadWithItsMinecraftRange() {
		DiscoveredMod mod = MixinFitReport.modOf(Map.of("fabric.mod.json", bytes(
				"{\"schemaVersion\":1,\"id\":\"nec\",\"version\":\"4.4.9\",\"depends\":{\"minecraft\":[\"26.2\",\"26.3\"]}}")),
				Ecosystem.FABRIC, "nec.jar");
		assertEquals("nec", mod.getId());
		assertEquals(Ecosystem.FABRIC, mod.getEcosystem());
		assertEquals("26.2 || 26.3", mod.getDependencies().get(0).getVersionConstraint());
	}

	@Test void aForgeFamilyUnitIsReadAsItsOwnPlatformWithItsRangeTranslated() {
		String toml = "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"one\"\nversion=\"1\"\n"
				+ "[[dependencies.one]]\nmodId=\"neoforge\"\ntype=\"required\"\nversionRange=\"[26.2.0.90,)\"\n";
		DiscoveredMod mod = MixinFitReport.modOf(Map.of("META-INF/neoforge.mods.toml", bytes(toml)), Ecosystem.NEOFORGE, "one.jar");
		assertEquals(Ecosystem.NEOFORGE, mod.getEcosystem());
		assertEquals(">=26.2.0.90", mod.getDependencies().get(0).getVersionConstraint());
		assertEquals("neoforge >=26.2.0.90", NativeAbsentTargets.unmetRequirement(
				NativeAbsentTargets.shipped().of(Ecosystem.NEOFORGE), mod), "the game the table describes fails it");
	}

	@ExpectCaught("could not read the FABRIC manifest of broken.jar")
	@Test void twoModsOrNoManifestGiveNoMod() {
		String toml = "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"one\"\nversion=\"1\"\n"
				+ "[[mods]]\nmodId=\"two\"\nversion=\"1\"\n";
		assertNull(MixinFitReport.modOf(Map.of("META-INF/mods.toml", bytes(toml)), Ecosystem.FORGE, "two.jar"));
		assertNull(MixinFitReport.modOf(Map.of("META-INF/mods.toml", bytes(toml)), Ecosystem.NEOFORGE, "two.jar"));
		assertNull(MixinFitReport.modOf(Map.of(), Ecosystem.FABRIC, "none.jar"));
		assertNull(MixinFitReport.modOf(Map.of("fabric.mod.json", bytes("{")), Ecosystem.FABRIC, "broken.jar"));
		assertNull(MixinFitReport.modOf(Map.of("fabric.mod.json", bytes("{}")), null, "universal.jar"));
	}

	private static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}
}
