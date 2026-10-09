/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.fabric;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The half of {@link EntrypointDispatchScan#report} the dispatch list does not carry: the queries whose key or type no
 * constant reaches, and which of the keys asked about the jar holds as a constant. Libraries here exist nowhere.
 */
class EntrypointDispatchReportTest {
	private static final String HOOK = "dev/lumen/api/TuningHook";
	private static final String KEY = "lumen:tuning";

	/** The key a Kotlin object's getter returns: held as a constant, but the query sees a method's return value. */
	@Test void aKeyReadThroughAGetterIsAnUndeterminedQueryAndTheKeyIsHeld() {
		Map<String, byte[]> classes = new LinkedHashMap<>();
		classes.put(HOOK, DispatchFixtures.contract(HOOK, "tune()V"));
		classes.put("dev/lumen/fabric/LumenChannels", UndeterminedKeyFixtures.kotlinObjectKeys("dev/lumen/fabric/LumenChannels", "getTuning", KEY));
		classes.put("dev/lumen/fabric/LumenMain", UndeterminedKeyFixtures.getterKeyedMain("dev/lumen/fabric/LumenMain",
				"dev/lumen/fabric/LumenChannels", "getTuning", HOOK));

		var report = EntrypointDispatchScan.report(classes, Map.of("main", List.of("dev.lumen.fabric.LumenMain")), Set.of(KEY, "lumen:other"));

		assertTrue(report.dispatches().isEmpty(), report::toString);
		assertEquals(List.of("dev.lumen.fabric.LumenMain.onInitialize"), report.undetermined());
		assertEquals(Set.of(KEY), report.named(), "held by the object's <clinit>; the other key is not in the jar");
	}

	/** An instance field handed to a static helper: the helper forwards, the caller binds nothing it can name. */
	@Test void aKeyFromAnInstanceFieldHandedToAHelperIsUndeterminedAtTheCaller() {
		String hook = "io/vesper/api/VesperPlugin";
		Map<String, byte[]> classes = new LinkedHashMap<>();
		classes.put(hook, DispatchFixtures.contract(hook, "ready()V"));
		classes.put("io/vesper/fabric/Fanout", UndeterminedKeyFixtures.staticFanout("io/vesper/fabric/Fanout"));
		classes.put("io/vesper/fabric/VesperClient", UndeterminedKeyFixtures.fieldKeyedClient("io/vesper/fabric/VesperClient",
				"vesper-plugins", "io/vesper/fabric/Fanout", hook));

		var report = EntrypointDispatchScan.report(classes, Map.of("client", List.of("io.vesper.fabric.VesperClient")), Set.of("vesper-plugins"));

		assertTrue(report.dispatches().isEmpty());
		assertEquals(List.of("io.vesper.fabric.VesperClient.onInitializeClient"), report.undetermined());
		assertEquals(Set.of("vesper-plugins"), report.named());
	}

	@Test void aConstantKeyWithATypeLookedUpByNameIsUndetermined() {
		Map<String, byte[]> classes = Map.of("dev/lumen/fabric/LumenMain",
				UndeterminedKeyFixtures.reflectiveTypeMain("dev/lumen/fabric/LumenMain", KEY, "dev.lumen.api.TuningHook"));

		var report = EntrypointDispatchScan.report(classes, Map.of("main", List.of("dev.lumen.fabric.LumenMain")), Set.of(KEY));

		assertTrue(report.dispatches().isEmpty());
		assertEquals(List.of("dev.lumen.fabric.LumenMain.onInitialize"), report.undetermined());
	}

	/** A forwarding helper no caller in the jar binds — an API for other mods — is a query this cannot name. */
	@Test void aForwardingHelperNothingInTheJarCallsIsUndetermined() {
		Map<String, byte[]> classes = Map.of("io/vesper/fabric/Fanout", UndeterminedKeyFixtures.staticFanout("io/vesper/fabric/Fanout"));

		var report = EntrypointDispatchScan.report(classes, Map.of(), null);

		assertTrue(report.dispatches().isEmpty());
		assertEquals(List.of("io.vesper.fabric.Fanout.each"), report.undetermined());
	}

	/** The look-alike: every query binds a constant, and the jar names another key only to check a mod is there. */
	@Test void aJarWhoseEveryQueryIsDerivedHasNothingUndeterminedWhateverElseItNames() {
		Map<String, byte[]> classes = new LinkedHashMap<>();
		classes.put(HOOK, DispatchFixtures.contract(HOOK, "tune()V"));
		classes.put("dev/lumen/fabric/LumenMain", DispatchFixtures.directDispatcher("dev/lumen/fabric/LumenMain", KEY, HOOK));
		classes.put("dev/lumen/fabric/Compat", UndeterminedKeyFixtures.presenceCheck("dev/lumen/fabric/Compat", "halo"));

		var report = EntrypointDispatchScan.report(classes, Map.of("main", List.of("dev.lumen.fabric.LumenMain")), Set.of(KEY, "halo"));

		assertEquals(List.of(KEY), report.dispatches().stream().map(EntrypointDispatchScan.Dispatch::key).toList());
		assertTrue(report.undetermined().isEmpty(), report::toString);
		assertEquals(Set.of(KEY, "halo"), report.named());
	}

	/** A key that is only a name the class declares (a method called that), not a string constant, is not held. */
	@Test void aMemberNameSpelledLikeAKeyIsNotAHeldKey() {
		Map<String, byte[]> classes = new LinkedHashMap<>();
		classes.put(HOOK, DispatchFixtures.contract(HOOK, "tune()V"));
		classes.put("dev/lumen/fabric/LumenMain", UndeterminedKeyFixtures.reflectiveTypeMain("dev/lumen/fabric/LumenMain", KEY, "x.Y"));

		var report = EntrypointDispatchScan.report(classes, Map.of("main", List.of("dev.lumen.fabric.LumenMain")), Set.of(KEY, "tune"));

		assertEquals(Set.of(KEY), report.named(), "'tune' is the contract's method name, not a constant");
	}

	@Test void aJarWithoutAQueryReportsNothing() {
		var report = EntrypointDispatchScan.report(Map.of("dev/lumen/fabric/Compat",
				UndeterminedKeyFixtures.presenceCheck("dev/lumen/fabric/Compat", KEY)), Map.of(), Set.of(KEY));

		assertTrue(report.dispatches().isEmpty());
		assertTrue(report.undetermined().isEmpty());
		assertTrue(report.named().isEmpty(), "not analysed at all");
	}
}
