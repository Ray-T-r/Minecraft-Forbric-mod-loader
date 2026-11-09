/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.boot.KernelRuntimeClasses;
import net.forbric.kernel.mixin.NativeGameReferences;

/**
 * Every game-side kernel class {@link KernelRuntimeClasses} lists has a live caller on NeoForge's own base and carrier
 * (plan C5, lane S item 2): either the transform chain a default launch builds writes a call to it into a class of the
 * base or carrier — read off the chain's own output, the replay {@link ChainAnchorCensusStagedTest} runs, on either side —
 * or it is in {@link #ELSEWHERE} with the kind of caller it has and the boot-side source that spells it.
 *
 * <p>The chain half is measured, not listed: a class the chain stops reaching (its injector declines, is unregistered or
 * is deleted) drops out of it and fails here unless someone states where else it is called from. The listed half is
 * checked for what a unit test can see: its site still spells the class, and nothing listed is also a chain target
 * (a stale row). Whether a listed boot-side call runs is the boot's own business ({@code [Forbric/Runtime] game-side
 * kernel classes: N/N linked}, and the record's class-initialisation evidence).
 *
 * <p>Red checks: a registry name nothing calls is unexplained; silencing {@code FeaturesPerStepRefreshInjector} in the
 * replay leaves exactly {@code KernelFeaturesPerStep} unexplained.
 */
@ResourceLock("native-game-references")
class RuntimeSeamLivenessStagedTest {
	/** How a listed class is reached when no class of the base or carrier calls it. */
	enum Kind {
		/** Reflectively from boot-side kernel code on the launch path ({@code Class.forName} + {@code getMethod}). */
		BOOT,
		/** From bytecode a kernel adapter writes into an installed guest mod's class (a mixin, a NeoForge mod's class). */
		GUEST,
		/** Only when a particular mod is installed (named in the reason); its loader names it as a string. */
		MOD_INSTALLED,
		/** Emitted at run time by a boot-side ClassWriter; not in the runtime jar at all. */
		GENERATED,
		/** Packed, never loaded by the kernel: constructed by a mod through that mod's own API. */
		PACKED,
	}

	record Elsewhere(Kind kind, String site, String why) {
	}

	private static final String RT = "net.forbric.kernel.runtime.";

	/** Registry classes no class of the base or carrier calls after the chain, each with its caller. Site: src/main/java path. */
	static final Map<String, Elsewhere> ELSEWHERE = Map.ofEntries(
			boot("KernelContainers", "boot/KernelModContainerFactory.java", "builds every mod's NeoForge ModContainer"),
			boot("KernelDataPackSource", "boot/KernelDataPacks.java", "serves a NeoForge mod jar's data/ to the server repository"),
			boot("KernelClientPackSource", "boot/KernelClientPacks.java", "serves an ecosystem jar's assets/ to the client repository"),
			boot("KernelRegistryContent", "boot/KernelLifecycle.java", "the repairs run when the registration window closes"),
			boot("KernelForeignShimKeys", "boot/KernelForeignShimContext.java", "key mappings registered on a bus outside ModList"),
			boot("KernelConfigLoad", "boot/KernelLifecycle.java", "NeoForge's early and late config passes"),
			boot("KernelNeoSetup", "boot/KernelLifecycle.java", "NeoForge's setup phases on each mod's bus"),
			boot("KernelNeoRegistries", "boot/KernelLifecycle.java", "NewRegistryEvent and RegisterEvent"),
			boot("KernelScanData", "discovery/ModFileScanner.java", "NeoForge's ModFileScanData from the kernel's scan"),
			boot("KernelNeoDataMapWatch", "boot/KernelServerDutiesInstaller.java", "the data-map watch the server duties install"),
			boot("KernelModConfigScreens", "boot/KernelClientSmoke.java", "the client smoke's config-screen walk"),
			guest("KernelWrapOperations", "mixin/MixinWrapOperationShim.java", "the outer handler written into a guest's @WrapOperation"),
			guest("KernelDispatch", "mixin/CarrierDefaultChains.java", "redirect handlers written into a guest mixin"),
			guest("KernelShears", "mixin/MixinShearsRelay.java", "a Fabric shears wrap at the carrier's canPerformAction"),
			guest("KernelMobShears", "mixin/MixinShearsRelay.java", "a Fabric shears wrap of a sheep's or snow golem's mobInteract"),
			guest("KernelFluidReactions", "mixin/MixinFluidReactionAdapter.java", "a guest's neighbour-change wrap of LiquidBlock"),
			guest("KernelItemDamage", "mixin/FabricItemDamageMixinAdapter.java", "a Fabric damage wrap at NeoForge's hurtAndBreak overload"),
			guest("KernelFabricTagViews", "mixin/NativeTagSourceFacadeInjector.java", "the tag facade a guest's predicate reads"),
			guest("KernelTagFacadeSources", "mixin/NativeTagSourceFacadeInjector.java", "the tag facade a guest's predicate reads"),
			guest("KernelFluidPredicateSeams", "mixin/MixinNativePredicateSeam.java", "a guest's fluid predicate seam"),
			guest("KernelFluidPredicateIslands", "mixin/MixinCrossHostPredicateIsland.java", "a guest's cross-host predicate island"),
			guest("KernelSourceDecodeScopes", "mixin/MixinDecodeScopeAdapter.java", "a guest's decode scope"),
			guest("KernelBlockStateModelFormats", "mixin/FabricBlockStateCodecMixinAdapter.java", "fabric-model-loading's codec redirects"),
			guest("KernelConfigApiBridge", "transform/ConfigApiAbiInjector.java", "ForgeConfigAPIPort's registrations, re-aimed"),
			guest("KernelDeclarationReaders", "transform/DeclarationReaderModListInjector.java", "a NeoForge mod reading [modproperties] from ModList"),
			guest("KernelFmlTransformerView", "transform/FmlContextLoaderRewriter.java", "a NeoForge mod casting its context loader to FML's"),
			entry(RT + "soak.ClientSoakController", Kind.MOD_INSTALLED, "boot/KernelSoakHooks.java",
					"only on a soak launch (-Dforbric.clientSoak), gate-m34"),
			entry("net.forbric.kernel.runtime.transfer.TransferIssues", Kind.MOD_INSTALLED, "boot/KernelTransferInterop.java",
					"installed with the transfer bridge"),
			entry("net.forbric.kernel.runtime.transfer.RebornEnergyBridge", Kind.MOD_INSTALLED, "boot/KernelTransferInterop.java",
					"installed when Team Reborn Energy is"),
			entry(RT + "KernelCreativePager", Kind.MOD_INSTALLED, "transform/CreativePagerBridgeInjector.java",
					"written into CreativeModeInventoryScreen only when fabric-creative-tab-api is installed (client)"),
			entry(RT + "KernelCreativePagerScreen", Kind.MOD_INSTALLED, "transform/CreativePagerBridgeInjector.java",
					"implemented by CreativeModeInventoryScreen only when fabric-creative-tab-api is installed (client)"),
			entry(RT + "modmenu.KernelModMenuPlugin", Kind.PACKED, "boot/KernelFabricEcosystem.java",
					"Mod Menu constructs the kernel's modmenu entrypoint"),
			entry(RT + "generated.LootCallbacks$", Kind.GENERATED, "boot/LootSourceCallbacks.java",
					"emitted on demand for the closed loot source group"));

	private static final Set<String> CHAIN = new TreeSet<>();
	/** Chain target → the game classes the chain made call it, both sides. Written to build/chain-anchor-census/runtime-seams.tsv. */
	private static final Map<String, Set<String>> CALLERS = new java.util.TreeMap<>();

	@BeforeAll static void replay() throws Exception {
		Path base = TestFixtures.stagedBase(), carrier = TestFixtures.neoCarrier();
		TestFixtures.requireFiles(Fixture.STAGED, "NeoForge's base and carrier", base, carrier);
		NativeGameReferences.configure(TestFixtures.launchSources(base));
		for (EnvType side : EnvType.values()) {
			ChainAnchorCensusStagedTest.census(side, List.of(base, carrier), null).runtimeRefs()
					.forEach((k, v) -> CALLERS.computeIfAbsent(k, x -> new TreeSet<>()).addAll(v));
		}
		CHAIN.addAll(CALLERS.keySet());
		Path dir = Path.of(System.getProperty("user.dir"), "build", "chain-anchor-census");
		Files.createDirectories(dir);
		List<String> rows = new ArrayList<>(List.of("class\tkind\tcaller"));
		for (String name : KernelRuntimeClasses.all().keySet()) {
			Elsewhere e = ELSEWHERE.get(name);
			rows.add(name + "\t" + (CALLERS.containsKey(name) ? "CHAIN" : e == null ? "NONE" : e.kind().name()) + "\t"
					+ (CALLERS.containsKey(name) ? String.join(",", CALLERS.get(name)) : e == null ? "" : e.site() + ": " + e.why()));
		}
		Files.write(dir.resolve("runtime-seams.tsv"), rows);
	}

	@AfterAll static void forget() {
		NativeGameReferences.configure(null);
	}

	@Test void everyRegisteredGameSideClassHasALiveCaller() {
		assertEquals(List.of(), unexplained(KernelRuntimeClasses.all().keySet(), CHAIN, ELSEWHERE.keySet()),
				"registered game-side classes with no caller: neither the chain writes a call to them into NeoForge's base or carrier, "
						+ "nor does ELSEWHERE say who calls them");
	}

	/** A row for a class the chain does call is stale; a row for a class the registry no longer lists is stale too. */
	@Test void everyListedCallerIsCurrent() throws IOException {
		Set<String> both = new TreeSet<>(ELSEWHERE.keySet());
		both.retainAll(CHAIN);
		assertEquals(Set.of(), both, "listed as called from elsewhere, but the chain calls them: drop the rows");
		Set<String> unknown = new TreeSet<>(ELSEWHERE.keySet());
		unknown.removeAll(KernelRuntimeClasses.all().keySet());
		assertEquals(Set.of(), unknown, "listed, but not in KernelRuntimeClasses");
		List<String> unspelled = new ArrayList<>();
		Path main = Path.of(System.getProperty("user.dir"), "src", "main", "java", "net", "forbric", "kernel");
		for (Map.Entry<String, Elsewhere> row : ELSEWHERE.entrySet()) {
			String simple = row.getKey().substring(row.getKey().lastIndexOf('.') + 1);
			Path site = main.resolve(row.getValue().site());
			if (!Files.isRegularFile(site) || !Files.readString(site).contains(simple)) unspelled.add(row.getKey() + " @ " + site);
		}
		assertEquals(List.of(), unspelled, "the named site does not spell the class it is said to call");
	}

	/** Red check: a name nothing calls, added to the registry's, is the one thing reported. */
	@Test void aRegisteredClassNothingCallsIsNamed() {
		String fake = RT + "KernelNobodyCalls";
		Set<String> registry = new LinkedHashSet<>(KernelRuntimeClasses.all().keySet());
		registry.add(fake);
		assertEquals(List.of(fake), unexplained(registry, CHAIN, ELSEWHERE.keySet()));
	}

	/** Red check on the measured half: the replay with one injector silenced loses exactly that injector's class. */
	@Test void aSilencedInjectorLeavesItsClassUnexplained() throws Exception {
		String silenced = new FeaturesPerStepRefreshInjector().name();
		Set<String> chain = new TreeSet<>();
		for (EnvType side : EnvType.values()) {
			chain.addAll(ChainAnchorCensusStagedTest.census(side, List.of(TestFixtures.stagedBase(), TestFixtures.neoCarrier()), silenced)
					.runtimeRefs().keySet());
		}
		assertEquals(List.of(RT + "KernelFeaturesPerStep"), unexplained(KernelRuntimeClasses.all().keySet(), chain, ELSEWHERE.keySet()));
	}

	static List<String> unexplained(Collection<String> registry, Set<String> chain, Set<String> elsewhere) {
		List<String> out = new ArrayList<>();
		for (String name : registry) if (!chain.contains(name) && !elsewhere.contains(name)) out.add(name);
		return out;
	}

	private static Map.Entry<String, Elsewhere> boot(String simple, String site, String why) {
		return entry(RT + simple, Kind.BOOT, site, why);
	}

	private static Map.Entry<String, Elsewhere> guest(String simple, String site, String why) {
		return entry(RT + simple, Kind.GUEST, site, why);
	}

	private static Map.Entry<String, Elsewhere> entry(String binary, Kind kind, String site, String why) {
		return Map.entry(binary, new Elsewhere(kind, site, why));
	}
}
