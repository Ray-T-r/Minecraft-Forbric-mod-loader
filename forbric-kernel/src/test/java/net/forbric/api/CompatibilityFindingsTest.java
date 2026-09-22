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
