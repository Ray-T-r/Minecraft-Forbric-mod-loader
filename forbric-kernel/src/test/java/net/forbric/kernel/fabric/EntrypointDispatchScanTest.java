/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.fabric;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.forbric.kernel.fabric.EntrypointDispatchScan.Dispatch;
import net.forbric.kernel.fabric.EntrypointDispatchScan.Phase;

/**
 * What a library's own code says about the custom entrypoint keys it dispatches. Every fixture is a library that
 * exists nowhere: different names, different keys, and each one reaching the query a different way.
 */
class EntrypointDispatchScanTest {
	private static final String HOOK = "io/nebula/api/WarmupHook";
	private static final String PLUGIN = "org/aurora/api/AuroraPlugin";

	@Test void aKeyHeldInAStaticFinalAndALocalIsDispatchedFromMainThroughTheTypesOnlyMethod() {
		Map<String, byte[]> classes = new LinkedHashMap<>();
		classes.put(HOOK, DispatchFixtures.contract(HOOK, "warmUp()V"));
		classes.put("io/nebula/fabric/NebulaInit", DispatchFixtures.mainLoopDispatcher("io/nebula/fabric/NebulaInit", "nebula:warmup", HOOK, "warmUp"));

		List<Dispatch> found = EntrypointDispatchScan.scan(classes, Map.of("main", List.of("io.nebula.fabric.NebulaInit")), null);

		assertEquals(1, found.size(), found::toString);
		Dispatch dispatch = found.getFirst();
		assertEquals("nebula:warmup", dispatch.key());
		assertEquals(HOOK, dispatch.type());
		assertTrue(dispatch.ownType());
		assertEquals("warmUp", dispatch.method());
		assertEquals("()V", dispatch.methodDescriptor());
		assertEquals(Set.of(Phase.MAIN), dispatch.phases());
		assertTrue(dispatch.derivable());
		assertEquals(Phase.MAIN, Phase.earliest(dispatch.phases(), true));
		assertEquals(Phase.MAIN, Phase.earliest(dispatch.phases(), false));
	}

	/**
	 * The key and type reach {@code invokeEntrypoints} as parameters of an instance helper, in the opposite order to
	 * the query's, after a DUP/POP; the contract has two methods and the method reference decides which one is called.
	 */
	@Test void aHelperForwardingSwappedParametersIsFollowedToTheConstantsItsCallerPasses() {
		Map<String, byte[]> classes = new LinkedHashMap<>();
		classes.put(PLUGIN, DispatchFixtures.contract(PLUGIN, "prepare()V", "describe()V"));
		classes.put("org/aurora/fabric/Relay", DispatchFixtures.forwardingHelper("org/aurora/fabric/Relay"));
		classes.put("org/aurora/fabric/AuroraEarly", DispatchFixtures.preLaunchHelperCaller("org/aurora/fabric/AuroraEarly",
				"org/aurora/fabric/Relay", "aurora-plugins", PLUGIN, "prepare"));

		List<Dispatch> found = EntrypointDispatchScan.scan(classes, Map.of("preLaunch", List.of("org.aurora.fabric.AuroraEarly")), null);

		assertEquals(1, found.size(), found::toString);
		Dispatch dispatch = found.getFirst();
		assertEquals("aurora-plugins", dispatch.key());
		assertEquals(PLUGIN, dispatch.type());
		assertEquals("prepare", dispatch.method(), "the method reference names it; the type alone has two");
		assertEquals(Set.of(Phase.PRE_INIT), dispatch.phases());
		assertEquals("org.aurora.fabric.AuroraEarly.onPreLaunch", dispatch.site());
	}

	@Test void withoutTheMethodReferenceATwoMethodContractIsNotGuessed() {
		Map<String, byte[]> classes = new LinkedHashMap<>();
		classes.put(PLUGIN, DispatchFixtures.contract(PLUGIN, "prepare()V", "describe()V"));
		classes.put("org/aurora/fabric/AuroraInit", DispatchFixtures.directDispatcher("org/aurora/fabric/AuroraInit", "aurora-plugins", PLUGIN));

		Dispatch dispatch = EntrypointDispatchScan.scan(classes, Map.of("main", List.of("org.aurora.fabric.AuroraInit")), null).getFirst();

		assertNull(dispatch.method());
		assertFalse(dispatch.derivable());
	}

	@Test void aContractThatTakesArgumentsIsNotSomethingTheKernelCanCall() {
		String registrar = "dev/quill/api/QuillRegistrar";
		Map<String, byte[]> classes = new LinkedHashMap<>();
		classes.put(registrar, DispatchFixtures.contract(registrar, "register(Ljava/lang/Object;)V"));
		classes.put("dev/quill/QuillInit", DispatchFixtures.directDispatcher("dev/quill/QuillInit", "quill:registrars", registrar));

		Dispatch dispatch = EntrypointDispatchScan.scan(classes, Map.of("main", List.of("dev.quill.QuillInit")), null).getFirst();

		assertEquals("quill:registrars", dispatch.key());
		assertNull(dispatch.method());
		assertNotNull(dispatch.obstacle());
	}

	@Test void aDispatchOnlyACallbackReachesHasNoPhase() {
		Map<String, byte[]> classes = new LinkedHashMap<>();
		classes.put(HOOK, DispatchFixtures.contract(HOOK, "warmUp()V"));
		classes.put("io/nebula/fabric/Deferred", DispatchFixtures.callbackDispatcher("io/nebula/fabric/Deferred", "nebula:warmup", HOOK));

		Dispatch dispatch = EntrypointDispatchScan.scan(classes, Map.of("main", List.of("io.nebula.fabric.Deferred")), null).getFirst();

		assertEquals("warmUp", dispatch.method());
		assertTrue(dispatch.phases().isEmpty(), "when the callback runs is not in the bytecode");
		assertFalse(dispatch.derivable());
	}

	@Test void aDispatchNoLifecycleEntrypointReachesHasNoPhase() {
		Map<String, byte[]> classes = new LinkedHashMap<>();
		classes.put(HOOK, DispatchFixtures.contract(HOOK, "warmUp()V"));
		classes.put("io/nebula/fabric/NebulaInit", DispatchFixtures.mainLoopDispatcher("io/nebula/fabric/NebulaInit", "nebula:warmup", HOOK, "warmUp"));

		Dispatch dispatch = EntrypointDispatchScan.scan(classes, Map.of(), null).getFirst();

		assertTrue(dispatch.phases().isEmpty());
	}

	@Test void anotherModsTypeIsNotThisJarsProtocol() {
		String foreign = "example/othermod/api/ScreenFactoryProvider";
		Map<String, byte[]> classes = Map.of("dev/quill/QuillInit", DispatchFixtures.directDispatcher("dev/quill/QuillInit", "othermod", foreign));

		Dispatch dispatch = EntrypointDispatchScan.scan(classes, Map.of("main", List.of("dev.quill.QuillInit")), null).getFirst();

		assertEquals("othermod", dispatch.key());
		assertFalse(dispatch.ownType(), "the type is declared by a different jar");
	}

	@Test void aKeyComputedAtRunTimeIsNotADispatchThisCanName() {
		Map<String, byte[]> classes = new LinkedHashMap<>();
		classes.put(HOOK, DispatchFixtures.contract(HOOK, "warmUp()V"));
		classes.put("io/nebula/fabric/Dynamic", DispatchFixtures.runtimeKeyDispatcher("io/nebula/fabric/Dynamic", HOOK));

		assertTrue(EntrypointDispatchScan.scan(classes, Map.of("main", List.of("io.nebula.fabric.Dynamic")), null).isEmpty());
	}

	@Test void onlyTheKeysAskedAboutAreReportedAndAJarNamingNoneIsNotAnalysed() {
		Map<String, byte[]> classes = new LinkedHashMap<>();
		classes.put(HOOK, DispatchFixtures.contract(HOOK, "warmUp()V"));
		classes.put("io/nebula/fabric/NebulaInit", DispatchFixtures.mainLoopDispatcher("io/nebula/fabric/NebulaInit", "nebula:warmup", HOOK, "warmUp"));
		Map<String, List<String>> entrypoints = Map.of("main", List.of("io.nebula.fabric.NebulaInit"));

		assertEquals(1, EntrypointDispatchScan.scan(classes, entrypoints, Set.of("nebula:warmup", "unrelated")).size());
		assertTrue(EntrypointDispatchScan.scan(classes, entrypoints, Set.of("unrelated")).isEmpty());
		assertTrue(EntrypointDispatchScan.scan(classes, entrypoints, Set.of()).isEmpty());
	}

	@Test void aJarWithoutAnEntrypointQueryHasNoDispatch() {
		assertTrue(EntrypointDispatchScan.scan(Map.of(HOOK, DispatchFixtures.contract(HOOK, "warmUp()V"),
				"io/nebula/Plain", DispatchFixtures.plain("io/nebula/Plain")), Map.of("main", List.of("io.nebula.Plain")), null).isEmpty());
	}
}
