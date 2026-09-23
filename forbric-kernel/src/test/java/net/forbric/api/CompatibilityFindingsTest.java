package net.forbric.api;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock("ModCatalog")
class CompatibilityFindingsTest {
	@BeforeEach
	@AfterEach
	void reset() {
		CompatibilityFindings.reset();
		ModCatalog.publish(List.of());
	}

	private static CompatibilityFinding finding(CompatibilityFinding.Confidence confidence, String evidence) {
		return new CompatibilityFinding("contract:item-use", "demo", "Use item", "event:finish", confidence,
				true, "item result was lost", List.of(evidence));
	}

	@Test
	void suspicionIsVisibleToToolsButDoesNotMarkAModOrBlockAGate() {
		ModCatalog.publish(List.of(entry()));
		CompatibilityFindings.record(finding(CompatibilityFinding.Confidence.SUSPECTED, "preflight"));
		assertEquals(1, CompatibilityFindings.all().size());
		assertTrue(ModCatalog.failures().isEmpty());
		assertTrue(CompatibilityFindings.confirmedRequired().isEmpty());
	}

	@Test
	void repeatedObservationPromotesOnceAndKeepsBothSourcesOfEvidence() {
		CompatibilityFindings.record(finding(CompatibilityFinding.Confidence.SUSPECTED, "preflight"));
		CompatibilityFindings.record(finding(CompatibilityFinding.Confidence.CONFIRMED, "actual apply failed"));
		CompatibilityFindings.record(finding(CompatibilityFinding.Confidence.SUSPECTED, "preflight"));
		ModCatalog.publish(List.of(entry()));
		assertEquals(1, CompatibilityFindings.confirmedRequired().size());
		assertEquals(List.of("preflight", "actual apply failed"), CompatibilityFindings.all().getFirst().evidence());
		assertEquals(ModCatalog.Status.DEGRADED, ModCatalog.all().getFirst().status());
		assertEquals("item result was lost", ModCatalog.all().getFirst().statusDetail());
	}

	@Test
	void theRevisionMovesOnlyWhenTheLedgerChanges() {
		// A spawner whose call site could not be upgraded records the same finding on every spawn. The revision is
		// what the client tick and the dedicated server watch before they re-decide and rewrite the reports, so the
		// same observation again must not look like news.
		long start = CompatibilityFindings.revision();
		CompatibilityFindings.record(finding(CompatibilityFinding.Confidence.CONFIRMED, "BaseSpawner.serverTick"));
		long recorded = CompatibilityFindings.revision();
		assertNotEquals(start, recorded, "a new finding is news");
		for (int i = 0; i < 3; i++) CompatibilityFindings.record(finding(CompatibilityFinding.Confidence.CONFIRMED, "BaseSpawner.serverTick"));
		assertEquals(recorded, CompatibilityFindings.revision(), "the same observation again is not");
		CompatibilityFindings.record(finding(CompatibilityFinding.Confidence.SUSPECTED, "BaseSpawner.serverTick"));
		assertEquals(recorded, CompatibilityFindings.revision(), "nor is a suspicion a confirmed loss already outranks");
		CompatibilityFindings.record(finding(CompatibilityFinding.Confidence.CONFIRMED, "a second call site"));
		long evidence = CompatibilityFindings.revision();
		assertNotEquals(recorded, evidence, "new evidence is");
		CompatibilityFindings.resolve("contract:item-use", "demo", "replacement proved");
		assertNotEquals(evidence, CompatibilityFindings.revision(), "and so is a resolution");
	}

	@Test
	void resolutionClearsOnlyTheStructuredLossAndPreservesAnUnrelatedFailure() {
		ModCatalog.publish(List.of(entry()));
		CompatibilityFindings.record(finding(CompatibilityFinding.Confidence.CONFIRMED, "apply"));
		CompatibilityFindings.resolve("contract:item-use", "demo", "replacement passed its behavior test");
		assertTrue(ModCatalog.failures().isEmpty());
		assertEquals(CompatibilityFinding.Confidence.RESOLVED, CompatibilityFindings.all().getFirst().confidence());
		ModCatalog.mark("demo", ModCatalog.Status.FAILED, "constructor threw");
		assertEquals(ModCatalog.Status.FAILED, ModCatalog.failures().getFirst().status());
		assertEquals("constructor threw", ModCatalog.failures().getFirst().statusDetail());
	}

	@Test
	void anotherPreflightCannotUndoAProvedResolutionButARealNewFailureCan() {
		CompatibilityFindings.record(finding(CompatibilityFinding.Confidence.CONFIRMED, "apply"));
		CompatibilityFindings.resolve("contract:item-use", "demo", "replacement proved");
		CompatibilityFindings.record(finding(CompatibilityFinding.Confidence.SUSPECTED, "preflight"));
		assertEquals(CompatibilityFinding.Confidence.RESOLVED, CompatibilityFindings.all().getFirst().confidence());
		CompatibilityFindings.record(finding(CompatibilityFinding.Confidence.CONFIRMED, "new runtime failure"));
		assertEquals(1, CompatibilityFindings.confirmedRequired().size());
	}

	@Test
	void legacyFailuresStayVisibleWithoutGuessingThatTheirFeaturesAreRequired() {
		ModCatalog.publish(List.of(entry()));
		ModCatalog.mark("demo", ModCatalog.Status.DEGRADED, "old diagnostic with no structured proof");
		CompatibilityFindings.observeInitializationFailures();
		var parsed = com.electronwill.nightconfig.json.JsonFormat.fancyInstance().createParser()
				.parse(new StringReader(CompatibilityFindings.toJson()));
		assertEquals(0, ((Number) parsed.get("confirmedRequired")).intValue());
		assertTrue(((List<?>) parsed.get("findings")).isEmpty());
		List<?> legacy = parsed.get("catalogFailures");
		assertEquals(1, legacy.size(), "zero confirmed findings is not proof that every mod worked");
		var row = (com.electronwill.nightconfig.core.UnmodifiableConfig) legacy.getFirst();
		assertEquals("UNCLASSIFIED", row.get("classification"));
		assertEquals("DEGRADED", row.get("status"));
		assertTrue(!row.contains("required"), "legacy prose must not be converted into invented necessity");
	}

	@Test
	void failedInitializationIsObservedOnlyAtAnExplicitBoundaryAndDoesNotReobserveItsProjection() {
		ModCatalog.publish(List.of(entry()));
		ModCatalog.mark("demo", ModCatalog.Status.FAILED, "its @Mod constructor threw");
		assertTrue(CompatibilityFindings.all().isEmpty(), "mark must not call back into the evidence catalogue");
		CompatibilityFindings.observeInitializationFailures();
		var failure = CompatibilityFindings.confirmedRequired().getFirst();
		assertEquals("initialization:constructor", failure.id());
		assertEquals("KernelModLoader @Mod construction", failure.source());
		assertTrue(failure.evidence().contains("ModCatalog.Status.FAILED"));
		long revision = CompatibilityFindings.revision();
		CompatibilityFindings.observeInitializationFailures();
		assertEquals(revision, CompatibilityFindings.revision());
		assertEquals("its @Mod constructor threw", ModCatalog.all().getFirst().statusDetail());
		var report = com.electronwill.nightconfig.json.JsonFormat.fancyInstance().createParser().parse(new StringReader(CompatibilityFindings.toJson()));
		assertTrue(((List<?>) report.get("catalogFailures")).isEmpty(), "the typed observed failure is no longer unclassified");
	}

	@Test
	void eachNecessaryLifecycleFailureKeepsItsOwnIdentityAndAnUnrelatedResolutionCannotClearIt() {
		ModCatalog.publish(List.of(entry()));
		ModCatalog.mark("demo", ModCatalog.Status.FAILED, "its main entrypoint threw");
		ModCatalog.mark("demo", ModCatalog.Status.FAILED, "its client entrypoint threw");
		CompatibilityFindings.record(finding(CompatibilityFinding.Confidence.CONFIRMED, "mixin failed"));
		CompatibilityFindings.observeInitializationFailures();
		CompatibilityFindings.resolve("contract:item-use", "demo", "mixin replacement proved");
		assertEquals(List.of("initialization:entrypoint:client", "initialization:entrypoint:main"),
				CompatibilityFindings.confirmedRequired().stream().map(CompatibilityFinding::id).toList());
		CompatibilityFindings.resolve("initialization:entrypoint:main", "demo", "an unrelated caller asserted recovery");
		CompatibilityFindings.observeInitializationFailures();
		assertEquals(2, CompatibilityFindings.confirmedRequired().size(), "the raw FAILED state is still proof that initialization did not complete");
		assertEquals("its main entrypoint threw; its client entrypoint threw", ModCatalog.all().getFirst().statusDetail());
	}

	@Test
	void unknownOwnerNeverInventsACatalogRow() {
		CompatibilityFindings.record(finding(CompatibilityFinding.Confidence.CONFIRMED, "apply"));
		assertTrue(ModCatalog.everything().isEmpty());
		assertEquals(1, CompatibilityFindings.confirmedRequired().size(), "the evidence must still reach release checks");
	}

	@Test
	void findingsNoRowCanCarryAreStillListedAndSuspicionsAreSeparateNotes() {
		ModCatalog.publish(List.of(entry()));
		CompatibilityFindings.record(finding(CompatibilityFinding.Confidence.CONFIRMED, "owned"));
		CompatibilityFindings.record(new CompatibilityFinding("transfer-initialization", "forbric", "Transfer", "kernel",
				CompatibilityFinding.Confidence.CONFIRMED, true, "bridge failed", List.of("threw")));
		CompatibilityFindings.record(new CompatibilityFinding("optional", "config:x.mixins.json", "Mixin X", "mixin:x",
				CompatibilityFinding.Confidence.CONFIRMED, false, "optional loss", List.of("no owner")));
		CompatibilityFindings.record(new CompatibilityFinding("suspect", "forbric", "Probe", "kernel",
				CompatibilityFinding.Confidence.SUSPECTED, true, "unproved", List.of("preflight")));
		CompatibilityFindings.record(new CompatibilityFinding("gone", "forbric", "Probe", "kernel",
				CompatibilityFinding.Confidence.CONFIRMED, true, "repaired", List.of("x")));
		CompatibilityFindings.resolve("gone", "forbric", "repair proved");
		assertEquals(List.of("config:x.mixins.json:optional", "forbric:transfer-initialization"),
				CompatibilityFindings.unattributed().stream().map(CompatibilityFinding::key).toList(),
				"confirmed findings owned by a catalogue row are projected there instead; resolved ones are gone");
		assertEquals(List.of("forbric:suspect"), CompatibilityFindings.suspected().stream().map(CompatibilityFinding::key).toList());
		assertEquals(1, ModCatalog.failures().size(), "the catalogue still invents no row for them");
	}

	@Test
	void machineReportIsValidJsonAndContainsAZeroDenominatorExplicitly() {
		String empty = CompatibilityFindings.toJson();
		assertTrue(empty.contains("\"confirmedRequired\":0"));
		CompatibilityFindings.record(new CompatibilityFinding("special", "demo", "quoted \"name\"", "test",
				CompatibilityFinding.Confidence.CONFIRMED, true, "line\nbreak\tbackslash\\", List.of("proof\r\u0001")));
		var parsed = com.electronwill.nightconfig.json.JsonFormat.fancyInstance().createParser()
				.parse(new StringReader(CompatibilityFindings.toJson()));
		assertEquals(1, ((Number) parsed.get("confirmedRequired")).intValue());
		assertEquals(1, ((List<?>) parsed.get("findings")).size());
	}

	private static ModCatalog.Entry entry() {
		return new ModCatalog.Entry(Ecosystem.FABRIC, "demo", "Demo", "1", "", List.of(), "demo.jar", "", "");
	}

	@Test void machineReportIncludesAllResolvedVersionsIncludingBundledModules() {
		ModCatalog.publish(List.of(entry(), new ModCatalog.Entry(Ecosystem.NEOFORGE, "child", "Child", "4.2.1-dev+26.2",
				"", List.of(), "child.jar", "", "parent")));
		var parsed = com.electronwill.nightconfig.json.JsonFormat.fancyInstance().createParser()
				.parse(new StringReader(CompatibilityFindings.toJson()));
		List<com.electronwill.nightconfig.core.UnmodifiableConfig> mods = parsed.get("mods");
		assertEquals(2, mods.size(), "successful and bundled mods remain in the evidence inventory");
		var child = mods.stream().filter(m -> "child".equals(m.get("modId"))).findFirst().orElseThrow();
		assertEquals("4.2.1-dev+26.2", child.get("version"));
		assertEquals("NEOFORGE", child.get("ecosystem"));
		assertEquals("child.jar", child.get("jar"));
		assertEquals("parent", child.get("bundledBy"));
		assertEquals(0, ((Number) parsed.get("confirmedRequired")).intValue());
	}

	@Test
	void anAggregateFailedRowDoesNotPromoteUnrelatedOptionalReasons() {
		ModCatalog.publish(List.of(entry().withStatus(ModCatalog.Status.FAILED,
				"its optional configuration failed; its client entrypoint threw")));
		CompatibilityFindings.observeInitializationFailures();
		assertEquals(List.of("initialization:entrypoint:client"),
				CompatibilityFindings.confirmedRequired().stream().map(CompatibilityFinding::id).toList());
		var parsed = com.electronwill.nightconfig.json.JsonFormat.fancyInstance().createParser()
				.parse(new StringReader(CompatibilityFindings.toJson()));
		List<?> legacy = parsed.get("catalogFailures");
		assertEquals(1, legacy.size(), "the optional unclassified reason must remain visible");
		assertTrue(((com.electronwill.nightconfig.core.UnmodifiableConfig) legacy.getFirst())
				.<String>get("detail").contains("optional configuration"));
	}
}
