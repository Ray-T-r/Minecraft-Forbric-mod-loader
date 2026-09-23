package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import net.fabricmc.api.EnvType;
import net.forbric.api.CompatibilityFindings;
import net.forbric.kernel.fabric.FabricModDiscovery;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.*;

@ResourceLock("ModCatalog") @ResourceLock("system-properties")
class NestedCandidateSelectionTest {
	@TempDir Path root;
	@BeforeEach @AfterEach void reset() {
		DuplicateModArbiter.reset(); MultiLoaderArbiter.reset(); CompatibilityFindings.reset();
		for (String property : List.of("forbric.modOwner", "forbric.dupeIdPreference", "forbric.nestedDupePreference",
				"forbric.multiLoaderPreference", "forbric.arbitrationMaxNodes", "forbric.arbitrationTimeoutMillis")) System.clearProperty(property);
	}
	private Path mods() throws Exception { Path mods = root.resolve("mods"); Files.createDirectories(mods); return mods; }
	private Path install(String name, byte[] bytes) throws Exception { Path path = mods().resolve(name); Files.write(path, bytes); return path; }
	private DuplicateModArbiter.Decision decide() throws Exception { return DuplicateModArbiter.arbitrate(mods(), EnvType.CLIENT); }

	@Test void sameBasenameWithDifferentContentGetsTwoStablePhysicalFiles() throws Exception {
		byte[] a = fabric("lib_a", "1", Map.of(), "", Map.of());
		byte[] b = fabric("lib_b", "1", Map.of(), "", Map.of());
		Path first = install("a.jar", fabric("a", "1", Map.of("META-INF/jars/shared.jar", a), "", Map.of()));
		Path second = install("b.jar", fabric("b", "1", Map.of("META-INF/jars/shared.jar", b), "", Map.of()));
		String firstHash = NestedCandidateInventory.digest(first), secondHash = NestedCandidateInventory.digest(second);
		decide(); NestedCandidatePlan plan = DuplicateModArbiter.currentPlan();
		assertEquals(2, plan.nestedFiles().size());
		assertNotEquals(plan.nestedFiles().get(0).getParent(), plan.nestedFiles().get(1).getParent());
		assertTrue(plan.nestedFiles().stream().allMatch(p -> p.getFileName().toString().equals("shared.jar")));
		assertEquals(firstHash, NestedCandidateInventory.digest(first)); assertEquals(secondHash, NestedCandidateInventory.digest(second));
		FabricModDiscovery discovery = new FabricModDiscovery(EnvType.CLIENT, root.resolve("legacy-cache")); discovery.discover(mods());
		assertEquals(Set.of("a", "b", "lib_a", "lib_b"), discovery.getContainers().stream().map(c -> c.getMetadata().getId()).collect(java.util.stream.Collectors.toSet()));
		assertTrue(plan.verify(discovery.getClasspathJars()));
		assertFalse(Files.exists(root.resolve("legacy-cache")), "planned discovery must not extract through the old name/size cache");
	}

	@Test void oneSharedChildKeepsBothParentEdgesButIsLoadedOnce() throws Exception {
		byte[] shared = fabric("shared", "1", Map.of(), "", Map.of());
		install("a.jar", fabric("a", "1", Map.of("META-INF/jars/shared.jar", shared), "", Map.of()));
		install("b.jar", fabric("b", "1", Map.of("META-INF/jars/shared.jar", shared), "", Map.of()));
		decide(); var plan = DuplicateModArbiter.currentPlan();
		assertEquals(1, plan.nestedFiles().size());
		Path child = plan.nestedFiles().getFirst();
		assertEquals(2, plan.inventory().edges().stream().filter(e -> e.child().equals(child)).count());
		assertEquals(KernelModCatalog.UNKNOWN_PARENT, KernelModCatalog.bundledBy(child.toString(), mods()), "shared content must not invent a single parent");
		FabricModDiscovery discovery = new FabricModDiscovery(EnvType.CLIENT, root.resolve("old")); discovery.discover(mods());
		assertEquals(1, discovery.getContainers().stream().filter(c -> c.getMetadata().getId().equals("shared")).count());
	}
	@Test void selectedParentIdentitySurvivesContentAddressedExtraction() throws Exception {
		Path parent = install("api.jar", fabric("fabric-api", "1", Map.of("META-INF/jars/module.jar", fabric("module", "1", Map.of(), "", Map.of())), "", Map.of()));
		decide(); var plan = DuplicateModArbiter.currentPlan(); Path child = plan.nestedFiles().getFirst();
		assertEquals("fabric-api", KernelModCatalog.bundledBy(child.toString(), mods()));
		assertEquals("", KernelModCatalog.bundledBy(parent.toString(), mods()));
		assertTrue(plan.bundledBy(root.resolve("never-selected.jar")).isEmpty());
	}

	@Test void aChildOfAnUnselectedParentCannotActivateItsParentOrLeakOntoTheClasspath() throws Exception {
		Path neo = install("host-neo.jar", neo("host", "1", Map.of(), Map.of(), Map.of()));
		Path fabric = install("host-fabric.jar", fabric("host", "1",
				Map.of("META-INF/jars/ghost.jar", fabric("ghost", "1", Map.of(), "", Map.of())), "", Map.of()));
		var decision = decide(); var plan = DuplicateModArbiter.currentPlan();
		assertFalse(decision.suppressed(neo)); assertTrue(decision.suppressed(fabric));
		assertTrue(plan.nestedFiles().isEmpty());
		assertTrue(plan.inventory().nodes().values().stream().anyMatch(n -> n.claim() != null && n.claim().modIds().contains("ghost")));
		FabricModDiscovery discovery = new FabricModDiscovery(EnvType.CLIENT, root.resolve("old")); discovery.discover(mods());
		assertTrue(discovery.getContainers().isEmpty());
	}

	@Test void aSodiumStyleWrapperAndSameIdPayloadStayTogetherWithoutCompetingForTheIdentity() throws Exception {
		byte[] payload = neo("sodium", "1", Map.of(), Map.of(), Map.of("payload.marker", new byte[] {1}));
		Path wrapper = install("sodium.jar", neo("sodium", "1", Map.of("META-INF/jarjar/implementation.jar", payload), Map.of(), Map.of()));
		var decision = decide(); var plan = DuplicateModArbiter.currentPlan();
		assertEquals(JointCandidateSelector.Status.SOLVED, plan.selection().status());
		assertFalse(decision.suppressed(wrapper)); assertEquals(1, plan.nestedFiles().size());
		assertFalse(decision.suppressed(plan.nestedFiles().getFirst()));
		assertTrue(plan.inventory().edges().getFirst().payload());
		assertTrue(plan.verify(plan.nestedFiles()));
		try (ZipFile zip = new ZipFile(plan.nestedFiles().getFirst().toFile())) { assertNotNull(zip.getEntry("payload.marker")); }
	}

	@Test void aNestedDependencyCanForceChangingTheTopLevelWinner() throws Exception {
		Path preferred = install("host-neo.jar", neo("host", "1", Map.of("META-INF/jarjar/shared.jar",
				neo("shared", "1", Map.of(), Map.of(), Map.of())), Map.of(), Map.of()));
		Path alternative = install("host-fabric.jar", fabric("host", "1", Map.of("META-INF/jars/shared.jar",
				fabric("shared", "2", Map.of(), "", Map.of())), "", Map.of()));
		install("consumer.jar", fabric("consumer", "1", Map.of(), ",\"depends\":{\"shared\":\">=2\"}", Map.of()));
		var decision = decide(); var plan = DuplicateModArbiter.currentPlan();
		assertEquals(JointCandidateSelector.Status.SOLVED, plan.selection().status());
		assertTrue(decision.suppressed(preferred)); assertFalse(decision.suppressed(alternative));
		assertEquals("2", plan.inventory().nodes().get(plan.nestedFiles().getFirst()).claim().versionOf("shared"));
	}

	@Test void jarJarRangesChooseOneArtifactAndActualDiscoveryDoesNotChooseTheHighestAgain() throws Exception {
		byte[] one = bytes(Map.of("version.txt", "one".getBytes(StandardCharsets.UTF_8)));
		byte[] two = bytes(Map.of("version.txt", "two".getBytes(StandardCharsets.UTF_8)));
		install("a.jar", neo("a", "1", Map.of("META-INF/jarjar/lib-1.jar", one),
				Map.of("META-INF/jarjar/lib-1.jar", new NestedCandidateInventory.Coordinate("example:lib", "[1,2)", "1")), Map.of()));
		install("b.jar", neo("b", "1", Map.of("META-INF/jarjar/lib-2.jar", two),
				Map.of("META-INF/jarjar/lib-2.jar", new NestedCandidateInventory.Coordinate("example:lib", "[1,3)", "2")), Map.of()));
		var decision = decide(); var plan = DuplicateModArbiter.currentPlan();
		assertEquals(JointCandidateSelector.Status.SOLVED, plan.selection().status());
		assertEquals(1, plan.nestedFiles().size()); assertEquals("lib-1.jar", plan.nestedFiles().getFirst().getFileName().toString());
		System.setProperty("forbric.nestedDupePreference", "fabric,minecraftforge,neoforge");
		assertSame(decision, DuplicateModArbiter.arbitrateNested(EnvType.CLIENT, plan.nestedFiles()));
		assertTrue(CompatibilityFindings.confirmedRequired().isEmpty());
	}

	@Test void aFabricParentAlsoDiscoversJarJarMetadataPathsOutsideTheUsualDirectories() throws Exception {
		String path = "private-libraries/unusual-location.jar";
		String metadata = "{\"jars\":[{\"path\":\"" + path + "\",\"identifier\":{\"group\":\"example\",\"artifact\":\"library\"},"
				+ "\"version\":{\"range\":\"[2,3)\",\"artifactVersion\":\"2\"}}]}";
		install("fabric-parent.jar", fabric("parent", "1", Map.of(), ",\"depends\":{\"library\":\">=2\"}",
				Map.of(path, fabric("library", "2", Map.of(), "", Map.of()),
						"META-INF/jarjar/metadata.json", metadata.getBytes(StandardCharsets.UTF_8))));
		decide(); var plan = DuplicateModArbiter.currentPlan();
		assertEquals(JointCandidateSelector.Status.SOLVED, plan.selection().status());
		assertEquals(1, plan.nestedFiles().size(), "JarJar declarations do not depend on which ecosystem owns the parent");
		assertEquals(path, plan.inventory().edges().getFirst().entry());
		FabricModDiscovery discovery = new FabricModDiscovery(EnvType.CLIENT, root.resolve("legacy")); discovery.discover(mods());
		assertEquals(Set.of("parent", "library"), discovery.getContainers().stream().map(c -> c.getMetadata().getId()).collect(java.util.stream.Collectors.toSet()));
		assertTrue(plan.verify(discovery.getClasspathJars()));
	}

	@Test void aRootCannotSupplyAClassThatOnlyItsSuppressedNestedCandidateContains() throws Exception {
		byte[] only = type("dep/OnlyInFabric");
		byte[] helper = fabric("helper", "1", Map.of(), "", Map.of("dep/OnlyInFabric.class", only));
		Path preferred = install("dep-neo.jar", neo("dep", "1", Map.of("META-INF/jarjar/helper.jar", helper), Map.of(), Map.of()));
		Path alternative = install("dep-fabric.jar", fabric("dep", "1", Map.of(), "", Map.of("dep/OnlyInFabric.class", only)));
		install("helper-neo.jar", neo("helper", "1", Map.of(), Map.of(), Map.of()));
		String config = "{\"required\":true,\"package\":\"app.mixin\",\"mixins\":[\"Need\"]}";
		install("app.jar", fabric("app", "1", Map.of(), ",\"depends\":{\"dep\":\"*\"},\"mixins\":[\"need.mixins.json\"]",
				Map.of("need.mixins.json", config.getBytes(StandardCharsets.UTF_8), "app/mixin/Need.class", mixin("app/mixin/Need", "dep/OnlyInFabric"))));
		System.setProperty("forbric.modOwner", "helper=neoforge");
		var decision = decide();
		assertTrue(decision.suppressed(preferred)); assertFalse(decision.suppressed(alternative));
		assertEquals(JointCandidateSelector.Status.SOLVED, DuplicateModArbiter.currentPlan().selection().status());
	}

	@Test void replacingASelectedCacheFileIsDetectedWithoutReArbitrating() throws Exception {
		install("a.jar", fabric("a", "1", Map.of("META-INF/jars/child.jar", fabric("child", "1", Map.of(), "", Map.of())), "", Map.of()));
		var decision = decide(); var plan = DuplicateModArbiter.currentPlan(); Path selected = plan.nestedFiles().getFirst();
		Files.write(selected, fabric("different", "2", Map.of(), "", Map.of()));
		assertFalse(plan.verify(plan.nestedFiles()));
		assertSame(decision, DuplicateModArbiter.arbitrateNested(EnvType.CLIENT, plan.nestedFiles()));
		assertTrue(CompatibilityFindings.confirmedRequired().stream().anyMatch(f -> f.id().equals("arbitration:materialization")));
	}

	@Test void aMissingOrOutOfRangeDependencyWithNoContestIsLeftToTheDependencyAudit() throws Exception {
		// gate-m20's shape: an ordinary pack whose only issue is a dependency nobody installed. DependencyAudit
		// warns and offers its dialog; arbitration has no choice to make and must not turn it into a launch stop.
		install("lonely.jar", fabric("lonely", "1", Map.of(), ",\"depends\":{\"forbricnosuchmod\":\"*\"}", Map.of()));
		install("wants-new.jar", fabric("wants_new", "1", Map.of(), ",\"depends\":{\"old\":\">=5\"}", Map.of()));
		install("old.jar", fabric("old", "4", Map.of(), "", Map.of()));
		var decision = decide(); var plan = DuplicateModArbiter.currentPlan();
		assertEquals(JointCandidateSelector.Status.SOLVED, plan.selection().status());
		assertTrue(decision.suppressedJars().isEmpty());
		assertTrue(CompatibilityFindings.confirmedRequired().isEmpty(), () -> CompatibilityFindings.all().toString());
		assertTrue(CompatibilityFindings.all().stream().noneMatch(f -> f.id().startsWith("arbitration:")), () -> CompatibilityFindings.all().toString());
	}

	@Test void aDependencySpelledTheOtherEcosystemsWayIsTheSameLibraryHereToo() throws Exception {
		// gate-m20's second canary: DependencyAudit already calls forbric_dep_canary and forbricdepcanary one mod.
		install("forbricdepcanary.jar", fabric("forbricdepcanary", "1.0.0", Map.of(), "", Map.of()));
		install("forbriccrosseco.jar", fabric("forbriccrosseco", "1", Map.of(), ",\"depends\":{\"forbric_dep_canary\":\">=1.0.0\"}", Map.of()));
		decide();
		assertEquals(JointCandidateSelector.Status.SOLVED, DuplicateModArbiter.currentPlan().selection().status());
		assertTrue(CompatibilityFindings.all().stream().noneMatch(f -> f.id().startsWith("arbitration:")), () -> CompatibilityFindings.all().toString());
	}

	@Test void aRespelledVersionRequirementStillSteersTheContestedChoice() throws Exception {
		Path preferred = install("foobar-neo.jar", neo("foobar", "1", Map.of(), Map.of(), Map.of()));
		Path wanted = install("foobar-fabric.jar", fabric("foobar", "2", Map.of(), "", Map.of()));
		install("consumer.jar", fabric("consumer", "1", Map.of(), ",\"depends\":{\"foo_bar\":\">=2\"}", Map.of()));
		var decision = decide();
		assertEquals(JointCandidateSelector.Status.SOLVED, DuplicateModArbiter.currentPlan().selection().status());
		assertTrue(decision.suppressed(preferred)); assertFalse(decision.suppressed(wanted));
		assertTrue(CompatibilityFindings.confirmedRequired().isEmpty());
	}

	@Test void anAmbiguousRespellingIsNotGuessedAt() throws Exception {
		// Two different mods collapse to the requested key: ModIds declines, so arbitration must not pick one.
		Path first = install("foobar.jar", fabric("foobar", "1", Map.of(), "", Map.of()));
		Path second = install("foo-dot-bar.jar", neo("foo.bar", "1", Map.of(), Map.of(), Map.of()));
		install("consumer.jar", fabric("consumer", "1", Map.of(), ",\"depends\":{\"foo_bar\":\">=2\"}", Map.of()));
		var decision = decide();
		assertEquals(JointCandidateSelector.Status.SOLVED, DuplicateModArbiter.currentPlan().selection().status());
		assertFalse(decision.suppressed(first)); assertFalse(decision.suppressed(second));
		assertTrue(CompatibilityFindings.all().stream().noneMatch(f -> f.id().startsWith("arbitration:")));
	}

	@Test void aMalformedJarJarRangeIsUnprovedInsteadOfAbortingTheBoot() throws Exception {
		for (String range : List.of("[1.0", "[]")) {
			reset();
			byte[] lib = bytes(Map.of("version.txt", range.getBytes(StandardCharsets.UTF_8)));
			install("parent.jar", neo("parent", "1", Map.of("META-INF/jarjar/lib.jar", lib),
					Map.of("META-INF/jarjar/lib.jar", new NestedCandidateInventory.Coordinate("example:lib", range, "1")), Map.of()));
			decide(); var plan = DuplicateModArbiter.currentPlan();
			assertEquals(JointCandidateSelector.Status.UNPROVED, plan.selection().status(), range);
			assertEquals(1, plan.nestedFiles().size(), "the library is still loaded, as the legacy extractor did");
			assertTrue(plan.selection().uncertain().stream().anyMatch(r -> r.detail().contains("malformed JarJar version range")), range);
			assertTrue(CompatibilityFindings.confirmedRequired().isEmpty());
			Files.delete(mods().resolve("parent.jar"));
		}
	}

	/** The real xaero pair: two Forge-family parents name their own platform artifact of one shared mod id. */
	private void xaeroPair() throws Exception {
		install("xaerominimap-forge.jar", forge("xaerominimap", "26.5.1", Map.of("META-INF/jarjar/xaerolib-forge.jar", forge("xaerolib", "1.7.3", Map.of(), Map.of(), Map.of())),
				Map.of("META-INF/jarjar/xaerolib-forge.jar", new NestedCandidateInventory.Coordinate("xaero.lib:xaerolib-forge-26.2", "[1.7.3,)", "1.7.3")), Map.of()));
		install("xaeroworldmap-neoforge.jar", neo("xaeroworldmap", "1.46.0", Map.of("META-INF/jarjar/xaerolib-neoforge.jar", neo("xaerolib", "1.7.3", Map.of(), Map.of(), Map.of())),
				Map.of("META-INF/jarjar/xaerolib-neoforge.jar", new NestedCandidateInventory.Coordinate("xaero.lib:xaerolib-neoforge-26.2", "[1.7.0,1.8)", "1.7.3")), Map.of()));
	}
	private net.forbric.api.Ecosystem selectedFamily(String id) {
		var plan = DuplicateModArbiter.currentPlan();
		var chosen = plan.inventory().nodes().values().stream().filter(n -> plan.selected().contains(n.path()) && n.claim() != null
				&& n.claim().modIds().contains(id)).toList();
		assertEquals(1, chosen.size(), () -> id + " selected " + chosen);
		return chosen.getFirst().claim().ecosystem();
	}

	@Test void samePlatformLibraryUnderTwoPlatformArtifactsFollowsTheNestedPreference() throws Exception {
		xaeroPair();
		decide();
		assertEquals(JointCandidateSelector.Status.SOLVED, DuplicateModArbiter.currentPlan().selection().status());
		assertEquals(net.forbric.api.Ecosystem.NEOFORGE, selectedFamily("xaerolib"));
		assertTrue(CompatibilityFindings.confirmedRequired().isEmpty(), () -> CompatibilityFindings.all().toString());
		// The order is a preference among satisfying builds, not an artifact the first-sorted identity forced.
		reset(); System.setProperty("forbric.nestedDupePreference", "minecraftforge,neoforge,fabric");
		xaeroPair(); decide();
		assertEquals(JointCandidateSelector.Status.SOLVED, DuplicateModArbiter.currentPlan().selection().status());
		assertEquals(net.forbric.api.Ecosystem.FORGE, selectedFamily("xaerolib"));
		reset(); System.setProperty("forbric.modOwner", "xaerolib=minecraftforge");
		xaeroPair(); decide();
		assertEquals(JointCandidateSelector.Status.SOLVED, DuplicateModArbiter.currentPlan().selection().status());
		assertEquals(net.forbric.api.Ecosystem.FORGE, selectedFamily("xaerolib"));
	}

	@Test void aFabricBuildWithoutJarJarMetadataCanStandInForAForgeCoordinate() throws Exception {
		// Real Fabric JiJ parents ship no META-INF/jarjar/metadata.json (xaerominimap-fabric), yet the MinecraftForge
		// parent's coordinate is met by any build of the same mod whose version is in range.
		install("parent-fabric.jar", fabric("parentfabric", "1", Map.of("META-INF/jars/lib-fabric.jar", fabric("lib", "2.0.0", Map.of(), "", Map.of())),
				",\"depends\":{\"lib\":\">=2.0.0\"}", Map.of()));
		install("parent-forge.jar", forge("parentforge", "1", Map.of("META-INF/jarjar/lib-forge.jar", forge("lib", "1.5.0", Map.of(), Map.of(), Map.of())),
				Map.of("META-INF/jarjar/lib-forge.jar", new NestedCandidateInventory.Coordinate("example:lib-forge", "[1.5,)", "1.5.0")), Map.of()));
		for (String pin : List.of("", "lib=fabric")) {
			reset(); if (!pin.isEmpty()) System.setProperty("forbric.modOwner", pin);
			decide();
			assertEquals(JointCandidateSelector.Status.SOLVED, DuplicateModArbiter.currentPlan().selection().status(), pin);
			assertEquals(net.forbric.api.Ecosystem.FABRIC, selectedFamily("lib"), pin);
			assertTrue(CompatibilityFindings.confirmedRequired().isEmpty(), () -> pin + CompatibilityFindings.all());
		}
	}

	@Test void aNewerTopLevelCopySatisfiesABundlingParentsCoordinate() throws Exception {
		install("a.jar", neo("a", "1", Map.of("META-INF/jarjar/lib-1.jar", neo("lib", "1.0", Map.of(), Map.of(), Map.of())),
				Map.of("META-INF/jarjar/lib-1.jar", new NestedCandidateInventory.Coordinate("example:lib", "[1.0,)", "1.0")), Map.of()));
		Path topLevel = install("lib-2.jar", neo("lib", "2.0", Map.of(), Map.of(), Map.of()));
		for (boolean consumer : List.of(false, true)) {
			reset();
			if (consumer) install("b.jar", fabric("b", "1", Map.of(), ",\"depends\":{\"lib\":\">=2.0\"}", Map.of()));
			var decision = decide(); var plan = DuplicateModArbiter.currentPlan();
			assertEquals(JointCandidateSelector.Status.SOLVED, plan.selection().status(), "consumer=" + consumer);
			assertFalse(decision.suppressed(topLevel), "the jar the player installed is not silently replaced");
			assertTrue(plan.nestedFiles().isEmpty(), "the older bundled copy stays out");
			assertTrue(CompatibilityFindings.confirmedRequired().isEmpty());
		}
	}

	@Test void sameFamilyNestedDuplicatesResolveToTheHighestVersionNotTheDigestOrder() throws Exception {
		// Distant Horizons nests fabric-api 0.149's modules next to the player's fabric-api 0.161. The candidate
		// directory is the content digest, so ordering by path picked whichever hash sorted first.
		byte[] newer = fabric("fabric-screen-api-v1", "2.0.4", Map.of(), "", Map.of());
		byte[] older = null;
		for (int pad = 0; older == null || sha(older).compareTo(sha(newer)) > 0; pad++)
			older = fabric("fabric-screen-api-v1", "2.0.3", Map.of(), "", Map.of("pad-" + pad, new byte[] {1}));
		assertTrue(sha(older).compareTo(sha(newer)) < 0, "the older copy's cache directory sorts first");
		install("fabric-api.jar", fabric("fabric-api", "0.161.0", Map.of("META-INF/jars/screen.jar", newer), "", Map.of()));
		install("distanthorizons.jar", fabric("distanthorizons", "3.3.0", Map.of("META-INF/jars/screen.jar", older), "", Map.of()));
		decide(); var plan = DuplicateModArbiter.currentPlan();
		assertEquals(JointCandidateSelector.Status.SOLVED, plan.selection().status());
		assertEquals(1, plan.nestedFiles().size());
		assertEquals("2.0.4", plan.inventory().nodes().get(plan.nestedFiles().getFirst()).claim().versionOf("fabric-screen-api-v1"));
	}

	/** jade has a NeoForge and a Fabric build; the addon's required Mixin needs the non-preferred Fabric one. */
	private Path[] jadePair() throws Exception {
		Path neo = install("jade-neo.jar", neo("jade", "1", Map.of(), Map.of(), Map.of("jade/Shared.class", type("jade/Shared"))));
		Path fabric = install("jade-fabric.jar", fabric("jade", "1", Map.of(), "", Map.of("jade/FabricOnly.class", type("jade/FabricOnly"))));
		requiredMixin("addon", "jade", "jade/FabricOnly");
		return new Path[] {neo, fabric};
	}
	private Path requiredMixin(String id, String dependency, String target) throws Exception {
		String config = "{\"required\":true,\"package\":\"" + id + ".mixin\",\"mixins\":[\"Need\"]}";
		return install(id + ".jar", fabric(id, "1", Map.of(), ",\"depends\":{\"" + dependency + "\":\"*\"},\"mixins\":[\"" + id + ".mixins.json\"]",
				Map.of(id + ".mixins.json", config.getBytes(StandardCharsets.UTF_8), id + "/mixin/Need.class", mixin(id + "/mixin/Need", target))));
	}

	@Test void oneUnsatisfiableContractDoesNotSwitchOffEveryOtherContract() throws Exception {
		Path[] jade = jadePair();
		install("dep-neo.jar", neo("dep", "1", Map.of(), Map.of(), Map.of("dep/NeoOnly.class", type("dep/NeoOnly"))));
		install("dep-fabric.jar", fabric("dep", "1", Map.of(), "", Map.of("dep/FabricOnly.class", type("dep/FabricOnly"))));
		Path first = requiredMixin("app1", "dep", "dep/FabricOnly");
		Path second = requiredMixin("app2", "dep", "dep/NeoOnly");
		var decision = decide(); var result = DuplicateModArbiter.currentPlan().selection();
		assertEquals(JointCandidateSelector.Status.UNSATISFIABLE, result.status());
		assertFalse(decision.suppressed(jade[1]), "the unrelated addon's satisfiable contract still picks jade-fabric");
		assertEquals(1, result.unsatisfied().size(), () -> result.unsatisfied().toString());
		assertTrue(Set.of(first, second).contains(result.unsatisfied().getFirst().consumer()));
		assertTrue(CompatibilityFindings.all().stream().noneMatch(f -> f.id().contains("addon.mixins.json")),
				"the addon's own contract is met, so it has no finding");
	}

	@Test void aStalePinForAnEcosystemWithNoCandidateIsWarnedAboutNotObeyedAtEveryContractsExpense() throws Exception {
		Path[] jade = jadePair();
		System.setProperty("forbric.modOwner", "jade=minecraftforge");
		var decision = decide(); var result = DuplicateModArbiter.currentPlan().selection();
		assertEquals(JointCandidateSelector.Status.SOLVED, result.status());
		assertFalse(decision.suppressed(jade[1]));
		assertTrue(result.unsatisfied().isEmpty());
		assertTrue(CompatibilityFindings.confirmedRequired().isEmpty(), () -> CompatibilityFindings.all().toString());
		assertTrue(CompatibilityFindings.all().stream().anyMatch(f -> f.modId().equals("jade")
				&& f.confidence() == net.forbric.api.CompatibilityFinding.Confidence.SUSPECTED), "the ignored pin stays visible");
	}

	@Test void theSelectionDoesNotDependOnTheMachinesSpeed() throws Exception {
		Path[] jade = jadePair();
		for (int i = 0; i < 24; i++) install("filler-" + i + ".jar", fabric("filler" + i, "1", Map.of(), "", Map.of()));
		System.setProperty("forbric.arbitrationTimeoutMillis", "1");
		var decision = decide();
		assertEquals(JointCandidateSelector.Status.SOLVED, DuplicateModArbiter.currentPlan().selection().status());
		assertFalse(decision.suppressed(jade[1]));
	}

	@Test void aSearchBoundKeepsTheBestModelAndNeverConfirmsItsOwnViolations() throws Exception {
		jadePair();
		System.setProperty("forbric.arbitrationMaxNodes", "1");
		decide(); var result = DuplicateModArbiter.currentPlan().selection();
		assertEquals(JointCandidateSelector.Status.SEARCH_LIMIT, result.status());
		assertTrue(CompatibilityFindings.confirmedRequired().isEmpty(), () -> CompatibilityFindings.all().toString());
		assertEquals(1, result.selected().stream().filter(p -> p.getFileName().toString().startsWith("jade-")).count(),
				"a bounded search still returns one build per id");
	}

	@Test void aCandidateThatProvablyLinksBeatsThePreferredOneThatOnlyMight() throws Exception {
		// dep-neo's member is private before transformation (UNKNOWN), dep-fabric's is public (YES).
		Path neo = install("dep-neo.jar", neo("dep", "1", Map.of(), Map.of(), Map.of("dep/Api.class", api("dep/Api", Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC))));
		Path fab = install("dep-fabric.jar", fabric("dep", "1", Map.of(), "", Map.of("dep/Api.class", api("dep/Api", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC))));
		install("app.jar", fabric("app", "1", Map.of(), ",\"depends\":{\"dep\":\"*\"},\"entrypoints\":{\"main\":[\"app.Main\"]}",
				Map.of("app/Main.class", caller("app/Main", "dep/Api"))));
		var decision = decide();
		assertEquals(JointCandidateSelector.Status.SOLVED, DuplicateModArbiter.currentPlan().selection().status());
		assertTrue(decision.suppressed(neo)); assertFalse(decision.suppressed(fab));
		// Preferring proof is not rejecting the unproved build: pinned, it runs and stays merely unproved.
		reset(); System.setProperty("forbric.modOwner", "dep=neoforge");
		decision = decide();
		assertEquals(JointCandidateSelector.Status.UNPROVED, DuplicateModArbiter.currentPlan().selection().status());
		assertFalse(decision.suppressed(neo));
		assertTrue(CompatibilityFindings.confirmedRequired().isEmpty(), () -> CompatibilityFindings.all().toString());
	}

	@Test void anUnsatisfiableCombinationIsFiledUnderItsPartiesNotUnderTheFirstJarInTheFolder() throws Exception {
		install("aaa-unrelated.jar", fabric("aaa", "1", Map.of(), "", Map.of()));
		install("dep-neo.jar", neo("dep", "1", Map.of(), Map.of(), Map.of("dep/NeoOnly.class", type("dep/NeoOnly"))));
		install("dep-fabric.jar", fabric("dep", "1", Map.of(), "", Map.of("dep/FabricOnly.class", type("dep/FabricOnly"))));
		requiredMixin("app1", "dep", "dep/FabricOnly");
		requiredMixin("app2", "dep", "dep/NeoOnly");
		decide();
		assertEquals(JointCandidateSelector.Status.UNSATISFIABLE, DuplicateModArbiter.currentPlan().selection().status());
		var selection = CompatibilityFindings.all().stream().filter(f -> f.id().equals("arbitration:selection")).toList();
		assertEquals(1, selection.size());
		assertEquals("forbric", selection.getFirst().modId(), "the aggregate row is the arbitration itself, not a mod");
		assertTrue(selection.getFirst().evidence().stream().anyMatch(e -> e.startsWith("involved=") && (e.contains("app1") || e.contains("app2"))));
		assertTrue(CompatibilityFindings.all().stream().noneMatch(f -> f.modId().equals("aaa")), () -> CompatibilityFindings.all().toString());
	}

	@Test void twoContradictoryPinsAreFiledUnderThePinnedMod() throws Exception {
		install("aaa-unrelated.jar", fabric("aaa", "1", Map.of(), "", Map.of()));
		install("x-fabric.jar", fabric("x", "1", Map.of(), "", Map.of()));
		install("bundle-neo.jar", neo("x", "1", Map.of(), Map.of(), Map.of(), "[[mods]]\nmodId=\"y\"\nversion=\"1\"\n"));
		System.setProperty("forbric.modOwner", "x=fabric,y=neoforge");
		decide();
		assertEquals(JointCandidateSelector.Status.UNSATISFIABLE, DuplicateModArbiter.currentPlan().selection().status());
		var refused = CompatibilityFindings.confirmedRequired().stream().filter(f -> f.id().startsWith("arbitration:override:")).toList();
		assertEquals(1, refused.size(), () -> CompatibilityFindings.all().toString());
		assertTrue(Set.of("x", "y").contains(refused.getFirst().modId()));
		assertTrue(CompatibilityFindings.all().stream().noneMatch(f -> f.modId().equals("aaa")));
	}

	@Test void aMaterializationMismatchIsFiledUnderTheModWhoseFileChanged() throws Exception {
		install("aaa-unrelated.jar", fabric("aaa", "1", Map.of(), "", Map.of()));
		install("zzz.jar", fabric("zzz", "1", Map.of("META-INF/jars/child.jar", fabric("zchild", "1", Map.of(), "", Map.of())), "", Map.of()));
		decide(); var plan = DuplicateModArbiter.currentPlan();
		Files.write(plan.nestedFiles().getFirst(), fabric("different", "2", Map.of(), "", Map.of()));
		assertFalse(plan.verify(plan.nestedFiles()));
		var findings = CompatibilityFindings.all().stream().filter(f -> f.id().equals("arbitration:materialization")).toList();
		assertFalse(findings.isEmpty());
		assertTrue(findings.stream().allMatch(f -> Set.of("zchild", "zzz").contains(f.modId())), findings::toString);
	}

	@Test void aKitchenSinkPackPastAThousandArchivesStillDiscoversEveryNestedLibrary() throws Exception {
		for (int i = 0; i < 1030; i++) install(String.format("a-%04d.jar", i), fabric("filler" + i, "1", Map.of(), "", Map.of("n", Integer.toString(i).getBytes(StandardCharsets.UTF_8))));
		install("zz-parent.jar", fabric("zzparent", "1", Map.of("META-INF/jars/lib.jar", fabric("zzlib", "1", Map.of(), "", Map.of())), "", Map.of()));
		decide(); var plan = DuplicateModArbiter.currentPlan();
		assertEquals(JointCandidateSelector.Status.SOLVED, plan.selection().status());
		assertEquals(1, plan.nestedFiles().size(), "the parents queued after 1024 archives lost their libraries");
	}

	@Test void aNestedJarLargerThanSixtyFourMegabytesIsStillExtracted() throws Exception {
		ByteArrayOutputStream big = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(big)) {
			zip.putNextEntry(new ZipEntry("fabric.mod.json"));
			zip.write("{\"schemaVersion\":1,\"id\":\"natives\",\"version\":\"1\"}".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
			byte[] payload = new byte[65 * 1024 * 1024]; java.util.zip.CRC32 crc = new java.util.zip.CRC32(); crc.update(payload);
			ZipEntry stored = new ZipEntry("natives.bin"); stored.setMethod(ZipEntry.STORED); stored.setSize(payload.length); stored.setCrc(crc.getValue());
			zip.putNextEntry(stored); zip.write(payload); zip.closeEntry();
		}
		install("host.jar", fabric("host", "1", Map.of("META-INF/jars/natives.jar", big.toByteArray()), "", Map.of()));
		decide(); var plan = DuplicateModArbiter.currentPlan();
		assertEquals(JointCandidateSelector.Status.SOLVED, plan.selection().status());
		assertEquals(1, plan.nestedFiles().size());
		assertTrue(plan.inventory().issues().isEmpty(), () -> plan.inventory().issues().toString());
	}

	@Test void aScanBoundIsAnExplicitFindingNotASilentlyTruncatedPlan() throws Exception {
		byte[] inner = fabric("level9", "1", Map.of(), "", Map.of());
		for (int level = 8; level >= 1; level--) inner = fabric("level" + level, "1", Map.of("META-INF/jars/l" + (level + 1) + ".jar", inner), "", Map.of());
		install("deep.jar", fabric("deep", "1", Map.of("META-INF/jars/l1.jar", inner), "", Map.of()));
		decide();
		assertTrue(CompatibilityFindings.confirmedRequired().stream().anyMatch(f -> f.id().equals("arbitration:inventory")
				&& !f.modId().equals("forbric")), () -> CompatibilityFindings.all().toString());
	}

	/** Loads {@code name} through a real kernel loader whose only rescue jars are the ones the boot would offer. */
	private boolean rescued(DuplicateModArbiter.Decision decision, String name) throws Exception {
		Path empty = root.resolve("owned-" + System.nanoTime() + ".jar"); Files.write(empty, bytes(Map.of("owned.txt", new byte[] {1})));
		try (var loader = new net.forbric.kernel.classloading.ForbricClassLoader(new java.net.URL[] {empty.toUri().toURL()}, getClass().getClassLoader())) {
			loader.setRescueJars(KernelBoot.rescueUrls(decision));
			try { loader.loadClass(name); return true; } catch (ClassNotFoundException absent) { return false; }
		}
	}

	@Test void onlyTheOtherEcosystemsBuildOfAWinningModIsOfferedAsARescueJar() throws Exception {
		// The one rescue the loader was built for: a mod on the losing side linking a class only the dropped build has.
		install("host-neo.jar", neo("host", "1", Map.of(), Map.of(), Map.of()));
		install("host-fabric.jar", fabric("host", "1", Map.of("META-INF/jars/ghost.jar",
				fabric("ghost", "1", Map.of(), "", Map.of("ghost/Only.class", type("ghost/Only")))), "", Map.of("hostfab/Only.class", type("hostfab/Only"))));
		var decision = decide();
		assertTrue(rescued(decision, "hostfab.Only"), "a class only the superseded other-ecosystem build has stays linkable");
		assertFalse(rescued(decision, "ghost.Only"), "a losing root's whole nested tree must not come back through rescue");
	}

	@Test void aSideExcludedOrVersionLosingJarIsNeverARescueJar() throws Exception {
		byte[] clientOnly = bytes(Map.of("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"keys\",\"version\":\"1\",\"environment\":\"client\"}".getBytes(StandardCharsets.UTF_8),
				"keys/ClientApi.class", type("keys/ClientApi")));
		install("api.jar", fabric("api", "1", Map.of("META-INF/jars/keys.jar", clientOnly), "", Map.of()));
		install("a.jar", neo("a", "1", Map.of("META-INF/jarjar/lib-1.jar", bytes(Map.of("lib/Core.class", type("lib/Core")))),
				Map.of("META-INF/jarjar/lib-1.jar", new NestedCandidateInventory.Coordinate("example:lib", "[1,2)", "1")), Map.of()));
		install("b.jar", neo("b", "1", Map.of("META-INF/jarjar/lib-2.jar", bytes(Map.of("lib/Core.class", type("lib/Core"), "lib/OnlyInTwo.class", type("lib/OnlyInTwo")))),
				Map.of("META-INF/jarjar/lib-2.jar", new NestedCandidateInventory.Coordinate("example:lib", "[1,3)", "2")), Map.of()));
		var decision = DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER);
		assertEquals(JointCandidateSelector.Status.SOLVED, DuplicateModArbiter.currentPlan().selection().status());
		assertFalse(rescued(decision, "keys.ClientApi"), "a client-only nested module must not become loadable on a server");
		assertFalse(rescued(decision, "lib.OnlyInTwo"), "two builds of one library must not be mixed through rescue");
	}

	private static byte[] api(String name, int access) {
		ClassWriter writer = new ClassWriter(0); writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		MethodVisitor method = writer.visitMethod(access, "needed", "()V", null, null);
		method.visitCode(); method.visitInsn(Opcodes.RETURN); method.visitMaxs(0, 0); method.visitEnd(); writer.visitEnd();
		return writer.toByteArray();
	}
	/** A Fabric main entrypoint whose straight-line body calls {@code target.needed()} statically. */
	private static byte[] caller(String name, String target) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", new String[] {"net/fabricmc/api/ModInitializer"});
		MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "onInitialize", "()V", null, null); method.visitCode();
		method.visitMethodInsn(Opcodes.INVOKESTATIC, target, "needed", "()V", false);
		method.visitInsn(Opcodes.RETURN); method.visitMaxs(1, 1); method.visitEnd(); writer.visitEnd();
		return writer.toByteArray();
	}

	private static String sha(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
	}
	private static byte[] forge(String id, String version, Map<String, byte[]> children, Map<String, NestedCandidateInventory.Coordinate> coordinates, Map<String, byte[]> resources) throws Exception {
		Map<String, byte[]> all = new LinkedHashMap<>();
		for (var entry : zip(neo(id, version, children, coordinates, resources)).entrySet()) {
			all.put(entry.getKey().equals("META-INF/neoforge.mods.toml") ? "META-INF/mods.toml" : entry.getKey(), entry.getValue());
		}
		return bytes(all);
	}
	private static Map<String, byte[]> zip(byte[] jar) throws Exception {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (ZipInputStream in = new ZipInputStream(new java.io.ByteArrayInputStream(jar))) {
			for (ZipEntry entry; (entry = in.getNextEntry()) != null;) entries.put(entry.getName(), in.readAllBytes());
		}
		return entries;
	}

	private static byte[] fabric(String id, String version, Map<String, byte[]> children, String extra, Map<String, byte[]> resources) throws Exception {
		Map<String, byte[]> all = new LinkedHashMap<>(resources); all.putAll(children);
		String jars = String.join(",", children.keySet().stream().map(name -> "{\"file\":\"" + name + "\"}").toList());
		all.put("fabric.mod.json", ("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"" + version + "\",\"jars\":[" + jars + "]" + extra + "}").getBytes(StandardCharsets.UTF_8));
		return bytes(all);
	}
	private static byte[] neo(String id, String version, Map<String, byte[]> children, Map<String, NestedCandidateInventory.Coordinate> coordinates, Map<String, byte[]> resources) throws Exception {
		return neo(id, version, children, coordinates, resources, "");
	}
	private static byte[] neo(String id, String version, Map<String, byte[]> children, Map<String, NestedCandidateInventory.Coordinate> coordinates, Map<String, byte[]> resources, String extraToml) throws Exception {
		Map<String, byte[]> all = new LinkedHashMap<>(resources); all.putAll(children);
		all.put("META-INF/neoforge.mods.toml", ("modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"" + id + "\"\nversion=\"" + version + "\"\n" + extraToml).getBytes(StandardCharsets.UTF_8));
		if (!coordinates.isEmpty()) {
			List<String> entries = new ArrayList<>();
			for (var entry : coordinates.entrySet()) { String[] parts = entry.getValue().id().split(":", 2);
				entries.add("{\"path\":\"" + entry.getKey() + "\",\"identifier\":{\"group\":\"" + parts[0] + "\",\"artifact\":\"" + parts[1] + "\"},\"version\":{\"range\":\"" + entry.getValue().range() + "\",\"artifactVersion\":\"" + entry.getValue().version() + "\"}}"); }
			all.put("META-INF/jarjar/metadata.json", ("{\"jars\":[" + String.join(",", entries) + "]}").getBytes(StandardCharsets.UTF_8));
		}
		return bytes(all);
	}
	private static byte[] bytes(Map<String, byte[]> resources) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			for (var entry : new TreeMap<>(resources).entrySet()) { ZipEntry part = new ZipEntry(entry.getKey()); part.setTime(0); zip.putNextEntry(part); zip.write(entry.getValue()); zip.closeEntry(); }
		}
		return bytes.toByteArray();
	}
	private static byte[] type(String name) { ClassWriter writer = new ClassWriter(0); writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null); writer.visitEnd(); return writer.toByteArray(); }
	private static byte[] mixin(String name, String target) {
		ClassWriter writer = new ClassWriter(0); writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		var annotation = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false); var values = annotation.visitArray("value");
		values.visit(null, Type.getObjectType(target)); values.visitEnd(); annotation.visitEnd(); writer.visitEnd(); return writer.toByteArray();
	}
}
