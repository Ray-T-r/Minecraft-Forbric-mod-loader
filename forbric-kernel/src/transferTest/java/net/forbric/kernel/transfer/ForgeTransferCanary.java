package net.forbric.kernel.transfer;

import java.util.LinkedHashMap;
import java.util.Map;

import net.fabricmc.api.ModInitializer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/** Pack this fixture and ForgeTransferGameScenarios in a Fabric canary jar; run on a real kernel server. */
public final class ForgeTransferCanary implements ModInitializer {
	@Override public void onInitialize() {
		NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> runAll());
	}
	public static void runAll() {
		Map<String, Runnable> scenarios = new LinkedHashMap<>();
		scenarios.put("item deep/nested rollback", ForgeTransferGameScenarios::itemRollback);
		scenarios.put("one shared journal per native handler", ForgeTransferGameScenarios::sharedJournal);
		scenarios.put("shared list and external ItemStack aliases", ForgeTransferGameScenarios::itemAliases);
		scenarios.put("shared FluidStack and independent empty tanks", ForgeTransferGameScenarios::fluidAliases);
		scenarios.put("backing replacement rejects commit before mutation", ForgeTransferGameScenarios::backingReplacement);
		scenarios.put("legacy item simulate/execute and capacity", ForgeTransferGameScenarios::legacyItems);
		scenarios.put("legacy extraction returns at most one stack", ForgeTransferGameScenarios::legacyExtractionStopsAtOneStack);
		scenarios.put("fluid rollback and 81:1 conservation", ForgeTransferGameScenarios::fluidRollback);
		scenarios.put("legacy fluid simulate/execute", ForgeTransferGameScenarios::legacyFluids);
		scenarios.put("unknown subclass/proxy/validator denied", ForgeTransferGameScenarios::unknownHandlers);
		scenarios.put("unknown fluid metadata denied", ForgeTransferGameScenarios::metadataDenied);
		scenarios.put("explicit metadata codec round trip", ForgeTransferGameScenarios::metadataCodec);
		scenarios.put("final native watchdog dump replaces Fabric truncation patch", ForgeTransferGameScenarios::watchdogDump);
		int passed = 0;
		for (var scenario : scenarios.entrySet()) {
			try { scenario.getValue().run(); passed++; System.out.println("[Forbric/TransferCanary] PASS " + scenario.getKey()); }
			catch (Throwable failure) {
				System.out.println("[Forbric/TransferCanary] FAIL " + scenario.getKey() + ": " + failure);
				failure.printStackTrace();
			}
		}
		System.out.println("[Forbric/TransferCanary] " + passed + "/" + scenarios.size() + " passed");
	}
}
