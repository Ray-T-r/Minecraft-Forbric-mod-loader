/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.kernel.fabric.DispatchFixtures;
import net.forbric.kernel.fabric.UndeterminedKeyFixtures;

/**
 * A library's losing Fabric build that asks Fabric Loader for a key the kernel cannot follow to a constant, while it
 * holds a key a loaded mod declares: what it dispatched cannot be derived, so nothing is dispatched in its place — but
 * that is said, as a SUSPECTED finding, rather than left to a consumer failing later with nothing pointing here.
 *
 * <p>The libraries ("lumen", "vesper") exist nowhere. One reads its key through a Kotlin object's getter; the other keeps
 * it in an instance field and hands it to a helper from its {@code client} entrypoint.
 */
@ResourceLock("ModCatalog")
class ArbitratedAwayUndeterminedTest {
	private static final String KEY = "lumen:tuning";
	private static final String HOOK = "dev/lumen/api/TuningHook";

	@TempDir Path dir;

	@BeforeEach @AfterEach void reset() {
		ArbitratedAwayDispatchers.reset();
		CompatibilityFindings.reset();
	}

	private static Map<String, byte[]> getterBuild(String key) {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("fabric.mod.json", DispatchFixtures.fabricModJson("lumen", Map.of("main", List.of("dev.lumen.fabric.LumenMain"))));
		entries.put(HOOK, DispatchFixtures.contract(HOOK, "tune()V"));
		entries.put("dev/lumen/fabric/LumenChannels", UndeterminedKeyFixtures.kotlinObjectKeys("dev/lumen/fabric/LumenChannels", "getTuning", key));
		entries.put("dev/lumen/fabric/LumenMain", UndeterminedKeyFixtures.getterKeyedMain("dev/lumen/fabric/LumenMain",
				"dev/lumen/fabric/LumenChannels", "getTuning", HOOK));
		return entries;
	}

	private Path neoForge(String id, Map<String, byte[]> extra) throws Exception {
		Map<String, byte[]> entries = new LinkedHashMap<>(extra);
		entries.put("META-INF/neoforge.mods.toml", DispatchFixtures.neoForgeToml(id));
		entries.put("org/" + id + "/neoforge/Entry", DispatchFixtures.plain("org/" + id + "/neoforge/Entry"));
		return DispatchFixtures.write(dir.resolve(id + "-neoforge.jar"), entries);
	}

	private static Optional<CompatibilityFinding> finding(String key) {
		return CompatibilityFindings.all().stream().filter(f -> f.id().equals("arbitration:entrypoint:" + key)).findFirst();
	}

	@Test void aKeyTheLosingBuildHoldsButReadsThroughAGetterIsReportedNotDispatched() throws Exception {
		Path losing = DispatchFixtures.write(dir.resolve("lumen-fabric.jar"), getterBuild(KEY));
		Path winning = neoForge("lumen", Map.of());

		var orphans = ArbitratedAwayDispatchers.derive(Set.of(losing), Map.of("lumen", winning), Set.of("main", KEY), Set.of("lumen"));

		assertTrue(orphans.isEmpty(), "nothing it cannot derive is dispatched: " + orphans);
		CompatibilityFinding found = finding(KEY).orElseThrow(() -> new AssertionError(CompatibilityFindings.all().toString()));
		assertEquals("lumen", found.modId());
		assertEquals(CompatibilityFinding.Confidence.SUSPECTED, found.confidence());
		assertFalse(found.required());
		assertTrue(found.evidence().contains("undetermined query=dev.lumen.fabric.LumenMain.onInitialize"), found::toString);
	}

	/** Written differently: the key sits in an instance field and a helper queries it, from the client entrypoint. */
	@Test void aKeyKeptInAFieldAndHandedToAHelperIsReportedToo() throws Exception {
		String hook = "io/vesper/api/VesperPlugin";
		Map<String, byte[]> build = new LinkedHashMap<>();
		build.put("fabric.mod.json", DispatchFixtures.fabricModJson("vesper", Map.of("client", List.of("io.vesper.fabric.VesperClient"))));
		build.put(hook, DispatchFixtures.contract(hook, "ready()V"));
		build.put("io/vesper/fabric/Fanout", UndeterminedKeyFixtures.staticFanout("io/vesper/fabric/Fanout"));
		build.put("io/vesper/fabric/VesperClient", UndeterminedKeyFixtures.fieldKeyedClient("io/vesper/fabric/VesperClient",
				"vesper-plugins", "io/vesper/fabric/Fanout", hook));
		Path losing = DispatchFixtures.write(dir.resolve("vesper-fabric.jar"), build);
		Path winning = neoForge("vesper", Map.of());

		assertTrue(ArbitratedAwayDispatchers.derive(Set.of(losing), Map.of("vesper", winning), Set.of("vesper-plugins")).isEmpty());
		assertEquals("vesper", finding("vesper-plugins").orElseThrow().modId());
	}

	/** The look-alike: every query of the build is derived, and it names a declared key only to see a mod is there. */
	@Test void aKeyHeldByABuildWhoseEveryQueryIsDerivedIsNotSuspected() throws Exception {
		Map<String, byte[]> build = new LinkedHashMap<>();
		build.put("fabric.mod.json", DispatchFixtures.fabricModJson("lumen", Map.of("main", List.of("dev.lumen.fabric.LumenMain"))));
		build.put(HOOK, DispatchFixtures.contract(HOOK, "tune()V"));
		build.put("dev/lumen/fabric/LumenMain", DispatchFixtures.directDispatcher("dev/lumen/fabric/LumenMain", KEY, HOOK));
		build.put("dev/lumen/fabric/Compat", UndeterminedKeyFixtures.presenceCheck("dev/lumen/fabric/Compat", "halo:hooks"));
		Path losing = DispatchFixtures.write(dir.resolve("lumen-fabric.jar"), build);
		Path winning = neoForge("lumen", Map.of());

		var orphans = ArbitratedAwayDispatchers.derive(Set.of(losing), Map.of("lumen", winning), Set.of(KEY, "halo:hooks"), Set.of("lumen"));

		assertEquals(List.of(KEY), orphans.stream().map(ArbitratedAwayDispatchers.Orphan::key).toList());
		assertTrue(CompatibilityFindings.all().isEmpty(), CompatibilityFindings.all()::toString);
	}

	/**
	 * A key the build's own code is seen to dispatch — here for another mod's type, which is not the kernel's to stand
	 * in for — is accounted for by that dispatch, even though another query of the build cannot be followed.
	 */
	@Test void aKeyTheBuildIsSeenToDispatchIsNotSuspectedBecauseAnotherQueryIsUndetermined() throws Exception {
		Map<String, byte[]> build = new LinkedHashMap<>(getterBuild(KEY));
		build.put("dev/lumen/fabric/HaloBridge", DispatchFixtures.directDispatcher("dev/lumen/fabric/HaloBridge", "halo:hooks",
				"org/halo/api/HaloHooks"));
		Path losing = DispatchFixtures.write(dir.resolve("lumen-fabric.jar"), build);
		Path winning = neoForge("lumen", Map.of());

		assertTrue(ArbitratedAwayDispatchers.derive(Set.of(losing), Map.of("lumen", winning), Set.of(KEY, "halo:hooks"), Set.of("lumen")).isEmpty());
		assertTrue(finding(KEY).isPresent(), "its own key, read through the getter, is still suspected");
		assertTrue(finding("halo:hooks").isEmpty(), CompatibilityFindings.all()::toString);
	}

	/** A key spelled like another installed mod is that mod's own protocol, whatever the library does with the string. */
	@Test void aKeySpelledLikeAnotherInstalledModIsThatModsProtocol() throws Exception {
		Path losing = DispatchFixtures.write(dir.resolve("lumen-fabric.jar"), getterBuild("halo"));
		Path winning = neoForge("lumen", Map.of());

		assertTrue(ArbitratedAwayDispatchers.derive(Set.of(losing), Map.of("lumen", winning), Set.of("halo"), Set.of("lumen", "halo")).isEmpty());
		assertTrue(CompatibilityFindings.all().isEmpty(), CompatibilityFindings.all()::toString);

		// The same build with no mod of that id installed: then it is the library's to have dispatched.
		assertTrue(ArbitratedAwayDispatchers.derive(Set.of(losing), Map.of("lumen", winning), Set.of("halo"), Set.of("lumen")).isEmpty());
		assertTrue(finding("halo").isPresent(), CompatibilityFindings.all()::toString);
	}

	/** A winner that asks Fabric Loader for the key itself (a universal jar's common code, say) lost nothing. */
	@Test void aWinnerThatDispatchesTheKeyItselfLostNothing() throws Exception {
		Path losing = DispatchFixtures.write(dir.resolve("lumen-fabric.jar"), getterBuild(KEY));
		Path winning = neoForge("lumen", Map.of(HOOK, DispatchFixtures.contract(HOOK, "tune()V"),
				"org/lumen/common/Bridge", DispatchFixtures.directDispatcher("org/lumen/common/Bridge", KEY, HOOK)));

		assertTrue(ArbitratedAwayDispatchers.derive(Set.of(losing), Map.of("lumen", winning), Set.of(KEY), Set.of("lumen")).isEmpty());
		assertTrue(CompatibilityFindings.all().isEmpty(), CompatibilityFindings.all()::toString);
	}

	/**
	 * A winner that only holds the string — a NeoForge build reading the declaration its own way, or a common module's
	 * constant both builds carry, which look the same — does not settle what an underivable query did: the suspicion
	 * stands, and says so.
	 */
	@Test void aWinnerThatOnlyHoldsTheKeyIsEvidenceNotAnExemption() throws Exception {
		Path losing = DispatchFixtures.write(dir.resolve("lumen-fabric.jar"), getterBuild(KEY));
		Path winning = neoForge("lumen", Map.of("org/lumen/common/LumenConstants",
				UndeterminedKeyFixtures.kotlinObjectKeys("org/lumen/common/LumenConstants", "getTuning", KEY)));

		assertTrue(ArbitratedAwayDispatchers.derive(Set.of(losing), Map.of("lumen", winning), Set.of(KEY), Set.of("lumen")).isEmpty());
		CompatibilityFinding found = finding(KEY).orElseThrow(() -> new AssertionError(CompatibilityFindings.all().toString()));
		assertTrue(found.evidence().stream().anyMatch(line -> line.startsWith("the build that loaded holds 'lumen:tuning' too")),
				found::toString);
	}

	@Test void aKeyNoLoadedModDeclaresIsNotSuspected() throws Exception {
		Path losing = DispatchFixtures.write(dir.resolve("lumen-fabric.jar"), getterBuild(KEY));
		Path winning = neoForge("lumen", Map.of());

		assertTrue(ArbitratedAwayDispatchers.derive(Set.of(losing), Map.of("lumen", winning), Set.of("lumen:elsewhere"), Set.of("lumen")).isEmpty());
		assertTrue(CompatibilityFindings.all().isEmpty(), CompatibilityFindings.all()::toString);
	}

	/** Another losing build's dispatch of the same key was derived: the kernel runs it, and nothing is left to suspect. */
	@Test void aKeyAnotherLosingBuildDispatchesDerivablyIsNotSuspected() throws Exception {
		Path undetermined = DispatchFixtures.write(dir.resolve("lumen-fabric.jar"), getterBuild(KEY));
		Map<String, byte[]> derivable = new LinkedHashMap<>();
		derivable.put("fabric.mod.json", DispatchFixtures.fabricModJson("lumenkit", Map.of("main", List.of("dev.lumenkit.KitMain"))));
		derivable.put(HOOK, DispatchFixtures.contract(HOOK, "tune()V"));
		derivable.put("dev/lumenkit/KitMain", DispatchFixtures.directDispatcher("dev/lumenkit/KitMain", KEY, HOOK));
		Path kit = DispatchFixtures.write(dir.resolve("lumenkit-fabric.jar"), derivable);

		var orphans = ArbitratedAwayDispatchers.derive(Set.of(undetermined, kit),
				Map.of("lumen", neoForge("lumen", Map.of()), "lumenkit", neoForge("lumenkit", Map.of())), Set.of(KEY), Set.of("lumen", "lumenkit"));

		assertEquals(List.of("lumenkit"), orphans.stream().map(ArbitratedAwayDispatchers.Orphan::library).toList());
		assertTrue(CompatibilityFindings.all().isEmpty(), CompatibilityFindings.all()::toString);
	}

	@Test void withTheFabricBuildLoadedNothingIsSuspected() throws Exception {
		Path fabric = DispatchFixtures.write(dir.resolve("lumen-fabric.jar"), getterBuild(KEY));
		Path neo = neoForge("lumen", Map.of());

		assertTrue(ArbitratedAwayDispatchers.derive(Set.of(neo), Map.of("lumen", fabric), Set.of(KEY), Set.of("lumen")).isEmpty());
		assertTrue(ArbitratedAwayDispatchers.derive(Set.of(fabric), Map.of(), Set.of(KEY), Set.of("lumen")).isEmpty(), "not installed as another build");
		assertTrue(CompatibilityFindings.all().isEmpty(), CompatibilityFindings.all()::toString);
	}
}
