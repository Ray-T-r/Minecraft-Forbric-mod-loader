/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;
import net.forbric.api.Side;
import net.forbric.kernel.transform.GuestInjectorPruner;
import net.forbric.kernel.transform.LootTableEventBridgeInjector;

/** The attribution rows: recorded by needle, judged by side and by kill switch, marked by catalog identity. */
@org.junit.jupiter.api.parallel.ResourceLock("ModCatalog")
class FabricApiModuleLossAuditTest {
	private static final String LOOT = "net/fabricmc/fabric/api/loot/v3/LootTableEvents";
	private static final String MODEL_PLUGIN = "net/fabricmc/fabric/api/client/model/loading/v1/ModelLoadingPlugin";
	private static final String CREATIVE = "net/fabricmc/fabric/api/client/creativetab/v1/FabricCreativeModeInventoryScreen";
	private static final String DYNAMIC_REGISTRIES = "net/fabricmc/fabric/api/event/registry/DynamicRegistries";
	private static final String SETUP_CALLBACK = "net/fabricmc/fabric/api/event/registry/DynamicRegistrySetupCallback";

	private List<ModCatalog.Entry> previous;

	@AfterEach
	void forget() {
		FabricApiModuleLossAudit.reset();
		System.clearProperty(LootTableEventBridgeInjector.PROPERTY);
		System.clearProperty(GuestInjectorPruner.PROPERTY);
		System.clearProperty(FabricApiModuleLossAudit.SWITCH);
		if (previous != null) ModCatalog.publish(previous);
	}

	private void publish(ModCatalog.Entry... entries) {
		previous = ModCatalog.everything();
		ModCatalog.publish(List.of(entries));
	}

	private static ModCatalog.Entry entry(String modId, String jar, String bundledBy) {
		return new ModCatalog.Entry(Ecosystem.FABRIC, modId, modId, "1", "", List.of(), jar, "", bundledBy);
	}

	private static byte[] classNaming(String... types) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Listener", null, "java/lang/Object", null);
		MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, "init", "()V", null, null);
		m.visitCode();
		for (String type : types) {
			m.visitTypeInsn(Opcodes.NEW, type);
			m.visitInsn(Opcodes.POP);
		}
		m.visitInsn(Opcodes.RETURN);
		m.visitMaxs(2, 1);
		m.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static ModCatalog.Entry degraded(String modId) {
		return ModCatalog.failures().stream().filter(e -> e.modId().equals(modId)).findFirst().orElse(null);
	}

	@Test
	void aLootListenerIsRecordedAndIsNotALossWhileTheBridgeIsOn() {
		publish(entry("balm", "balm.jar", ""));
		FabricApiModuleLossAudit.note("balm.jar", classNaming(LOOT));
		assertEquals(Set.of("balm.jar"), FabricApiModuleLossAudit.users().get(FabricApiModuleLossAudit.LOSSES.get(0)));

		FabricApiModuleLossAudit.report(Side.DEDICATED_SERVER);
		assertTrue(ModCatalog.failures().isEmpty(), "the bridge fires the events, so nothing is lost: " + ModCatalog.failures());
	}

	@Test
	void withTheLootBridgeOffTheListenerIsDegradedNamingTheModule() {
		publish(entry("balm", "balm.jar", ""));
		System.setProperty(LootTableEventBridgeInjector.PROPERTY, "off");
		FabricApiModuleLossAudit.note("balm.jar", classNaming(LOOT));
		FabricApiModuleLossAudit.report(Side.DEDICATED_SERVER);
		ModCatalog.Entry balm = degraded("balm");
		assertTrue(balm != null && balm.status() == ModCatalog.Status.DEGRADED, String.valueOf(balm));
		assertTrue(balm.statusDetail().startsWith("fabric-loot-api-v3: "), balm.statusDetail());
	}

	@Test
	void aClientOnlySurfaceIsALossOnTheClientAndNotOnTheServer() {
		publish(entry("pager", "pager.jar", ""));
		FabricApiModuleLossAudit.note("pager.jar", classNaming(CREATIVE));
		FabricApiModuleLossAudit.report(Side.DEDICATED_SERVER);
		assertTrue(ModCatalog.failures().isEmpty(), "a dedicated server never opens the creative screen");
		FabricApiModuleLossAudit.report(Side.CLIENT);
		ModCatalog.Entry pager = degraded("pager");
		assertTrue(pager != null && pager.statusDetail().startsWith("fabric-creative-tab-api-v1: "), String.valueOf(pager));
	}

	@Test
	void theModelPluginRowFollowsThePrunerSwitch() {
		publish(entry("balm", "balm.jar", ""));
		FabricApiModuleLossAudit.note("balm.jar", classNaming(MODEL_PLUGIN));
		FabricApiModuleLossAudit.report(Side.CLIENT);
		assertTrue(ModCatalog.failures().isEmpty(), "the pruner lets ModelLoadingPlugins dispatch");
		System.setProperty(GuestInjectorPruner.PROPERTY, "off");
		FabricApiModuleLossAudit.report(Side.CLIENT);
		ModCatalog.Entry balm = degraded("balm");
		assertTrue(balm != null && balm.statusDetail().contains("ModelLoadingPlugin"), String.valueOf(balm));
	}

	@Test
	void theRegistryNeedleIsTheClassNotThePackage() {
		FabricApiModuleLossAudit.note("lithostitched.jar", classNaming(DYNAMIC_REGISTRIES));
		assertTrue(FabricApiModuleLossAudit.users().isEmpty(), "DynamicRegistries works — it must not be recorded");
		FabricApiModuleLossAudit.note("other.jar", classNaming(SETUP_CALLBACK));
		assertEquals(Set.of("other.jar"), FabricApiModuleLossAudit.users().get(FabricApiModuleLossAudit.LOSSES.get(4)));
	}

	@Test
	void fabricApisOwnModuleIsNeverAUserAndAThirdPartyBesideItIs() {
		publish(entry("balm", "balm.jar", ""),
				entry("fabric-loot-api-v3", "fabric-loot-api-v3-3.0.17.jar", "fabric-api"));
		System.setProperty(LootTableEventBridgeInjector.PROPERTY, "off");
		FabricApiModuleLossAudit.note("balm.jar", classNaming(LOOT));
		FabricApiModuleLossAudit.note("fabric-loot-api-v3-3.0.17.jar", classNaming(LOOT));
		FabricApiModuleLossAudit.report(Side.DEDICATED_SERVER);
		assertTrue(degraded("balm") != null, "the third party is named");
		assertTrue(degraded("fabric-loot-api-v3") == null, "the module that DEFINES the surface is not its user");
	}

	@Test
	void threeClassesFromOneJarNameItOnce() {
		FabricApiModuleLossAudit.note("balm.jar", classNaming(LOOT));
		FabricApiModuleLossAudit.note("balm.jar", classNaming(LOOT));
		FabricApiModuleLossAudit.note("balm.jar", classNaming(LOOT, SETUP_CALLBACK));
		assertEquals(Set.of("balm.jar"), FabricApiModuleLossAudit.users().get(FabricApiModuleLossAudit.LOSSES.get(0)));
		assertEquals(2, FabricApiModuleLossAudit.users().size(), "two surfaces, one jar each");
	}

	@Test void separatelyInstalledDefiningModuleIsNotItsOwnThirdPartyConsumer() {
		publish(entry("fabric-registry-sync-v0", "module.jar", ""), entry("consumer", "consumer.jar", ""));
		FabricApiModuleLossAudit.note("module.jar", classNaming(SETUP_CALLBACK));
		FabricApiModuleLossAudit.note("consumer.jar", classNaming(SETUP_CALLBACK));
		FabricApiModuleLossAudit.report(Side.DEDICATED_SERVER);
		assertTrue(degraded("fabric-registry-sync-v0") == null);
		assertTrue(degraded("consumer") != null);
	}

	@Test
	void switchedOffItRecordsAndMarksNothing() {
		publish(entry("balm", "balm.jar", ""));
		System.setProperty(FabricApiModuleLossAudit.SWITCH, "off");
		System.setProperty(LootTableEventBridgeInjector.PROPERTY, "off");
		FabricApiModuleLossAudit.note("balm.jar", classNaming(LOOT));
		FabricApiModuleLossAudit.report(Side.DEDICATED_SERVER);
		assertFalse(FabricApiModuleLossAudit.enabled());
		assertTrue(ModCatalog.failures().isEmpty(), "report only, and only when asked");
	}
}
