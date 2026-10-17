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
import net.forbric.kernel.transform.VanillaDamageReadInjector;

/**
 * A mod's damage modifier at actuallyHurt's absorption call, through the real weave, with a guest that shares no name with
 * any mod.
 *
 * <p>Natively the body holds the damage left after absorption when it calls {@code setAbsorptionAmount}, goes on to the health
 * with it, and calls {@code setAbsorptionAmount} a second time after the health (a no-op: the absorption is spent). The guest
 * {@code org.quill.mend} rewrites that damage there with a {@code @ModifyVariable(argsOnly = true)} — twice per hit natively,
 * the second time on a value nothing reads. As merged (NeoForge's damage container), the parameter holds the incoming
 * damage at the call and is overwritten right after it, and the second call is gone: the guest saw the wrong value once and
 * its answer reached nothing. With the absorbed read, every line of every hit — what the guest saw, each absorption set, the
 * health — is the native run's; switched off, the halved hit takes the whole damage again.
 */
@net.forbric.kernel.transform.ExecutesInjector(VanillaDamageReadInjector.class)
class AbsorbedDamageReadWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/absorbeddamage");
	private static final String CONFIG = "absorbeddamage.mixins.json";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result natively, merged, off;

	@BeforeAll static void weave() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = walk.filter(p -> p.toString().endsWith(".java") && !SOURCES.relativize(p).startsWith("native")).sorted().toList();
		}
		assertEquals(13, sources.size(), "the fixture's sources changed; update this test with it: " + sources);
		List<Path> current = new ArrayList<>(sources);
		current.add(Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelLivingDamage.java"));
		List<Path> natives = new ArrayList<>(current);
		for (String type : List.of("net/minecraft/world/entity/LivingEntity.java", "net/minecraft/world/entity/player/Player.java")) {
			natives.remove(SOURCES.resolve(type));
			natives.add(SOURCES.resolve("native").resolve(type));
		}
		Path nativeFixture = WeaveHarness.fixture(work, "absorbed-native", natives, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g"));
		fixture = NativeWeaveReferences.with(work, WeaveHarness.fixture(work, "absorbed", current, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g")),
				NativeWeaveReferences.classes(nativeFixture));
		natively = run(nativeFixture, "native", List.of(), Map.of());
		merged = run(fixture, "merged", List.of(VanillaDamageReadInjector.class), Map.of());
		off = run(fixture, "off", List.of(VanillaDamageReadInjector.class), Map.of(VanillaDamageReadInjector.ABSORBED_PROPERTY, "off"));
	}

	@Test void everyHitIsTheNativeOne() throws Exception {
		assertTrue(merged.printed("[Forbric/Damage] LivingEntity.actuallyHurt holds the damage left after absorption when it calls setAbsorptionAmount"),
				merged.describe());
		assertEquals(hits(natively), hits(merged), "native " + hits(natively) + "\nmerged " + hits(merged) + "\n" + merged.describe());
		assertTrue(hits(natively).contains("halved: mend:4.0, absorption=0.0, mend:2.0, absorption=0.0, health=18.0, end health=18.0 absorption=0.0"),
				hits(natively) + "\n" + natively.describe());
		assertTrue(hits(natively).contains("player-halved: mend-player:4.0, absorption=0.0, health=18.0, end health=18.0 absorption=0.0"),
				"the player's override calls it once, natively and merged: " + hits(natively));
		WeaveHarness.assertWovenAndVerified(merged, "net/minecraft/world/entity/LivingEntity", fixture);
		WeaveHarness.assertWovenAndVerified(merged, "net/minecraft/world/entity/player/Player", fixture);
	}

	@Test void theControlFlipsTheAssertions() {
		assertNotEquals(hits(natively), hits(off), off.describe());
		assertTrue(hits(off).stream().anyMatch(h -> h.startsWith("halved:") && h.endsWith("end health=16.0 absorption=0.0")), off.describe());
	}

	private static List<String> hits(WeaveHarness.Result run) {
		String line = run.output().lines().filter(l -> l.startsWith("[AbsorbedDamage] ")).findFirst().orElse("");
		return List.of(line.substring(Math.min(line.length(), "[AbsorbedDamage] ".length())).split(" \\| "));
	}

	private static WeaveHarness.Result run(Path jar, String label, List<Class<? extends net.forbric.kernel.transform.ClassTransformer>> chain,
			Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, jar, List.of(new WeaveHarness.Config(CONFIG, "mend", Ecosystem.FABRIC)), chain, EnvType.SERVER,
				"fixture.absorbeddamage.Probe", "probe", properties);
	}
}
