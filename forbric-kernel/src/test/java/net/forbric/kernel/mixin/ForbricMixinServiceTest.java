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

package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Pins which registered configs get relaxed. The distinction is invisible until exactly one injector fails to
 * patch, at which point it decides between a soft skip and a fatal {@code MixinApplyError} that aborts the launch.
 */
class ForbricMixinServiceTest {
	private static final List<String> SAMPLE = List.of(
			"forge.mixins.json",
			"neoforge.mixins.json",
			"minecraft.mixins.json",
			"forbric-kernel.mixins.json",
			"bookshelf.common.mixins.json",
			"geckolib.mixins.json");

	@AfterEach
	void reset() {
		System.clearProperty("forbric.relaxGuestMixins");
		System.clearProperty("forbric.relaxMixinOverwrites");
		System.clearProperty("forbric.suppressMixins");
		System.clearProperty("forbric.keepMixins");
		System.clearProperty(net.forbric.kernel.transform.GuestInjectorPruner.PROPERTY);
		ForbricMixinService.setGuestConfigs(List.of());
	}

	/**
	 * The {@code ModelManagerMixin} pin is conditional on the pruner: trimmed to the injectors that fit by default,
	 * pinned whole only when {@code -Dforbric.guestInjectorPruner=off}. The kill switch has to reproduce the OLD
	 * behaviour (pinned, block models load, plugins dead) and never the half-applied one (4666 missingno models),
	 * which is what an unconditional removal of the pin would have shipped.
	 */
	@Test
	void theModelManagerMixinIsPinnedOnlyWhenThePrunerIsOff() {
		String config = "fabric-model-loading-api-v1.mixins.json";
		assertTrue(MergedBaseMixinCompat.SUPPRESSED_UNLESS_PRUNED.contains(config + ":ModelManagerMixin"),
				"precondition: the entry moved to SUPPRESSED_UNLESS_PRUNED");
		assertFalse(MergedBaseMixinCompat.SUPPRESSED_MIXINS.contains(config + ":ModelManagerMixin"),
				"and left the unconditional list");

		assertFalse(ForbricMixinService.suppressedMixinsFor(config).contains("ModelManagerMixin"),
				"by default the pruner trims the mixin, so it must NOT be suppressed");

		System.setProperty(net.forbric.kernel.transform.GuestInjectorPruner.PROPERTY, "off");
		assertTrue(ForbricMixinService.suppressedMixinsFor(config).contains("ModelManagerMixin"),
				"with the pruner off the whole-mixin pin returns");
	}

	/**
	 * {@code -Dforbric.keepMixins} has to reach the SHIPPED suppression list, not just the adapter's derived one.
	 * It did not, and the failure mode is the expensive kind: re-testing a hand-pinned entry changed nothing while
	 * looking exactly like the mixin having been tried and re-suppressed. Both former pins
	 * ({@code SynchronizeRegistriesTaskMixin}, jade's {@code FogRendererMixin}) were diagnosed only once this
	 * worked.
	 */
	/**
	 * A hand-listed or property-listed suppression removes the mixin before Mixin reads the config, so it never
	 * runs — and it used to leave one log line and nothing in the report. It is a confirmed removal the kernel
	 * made on purpose: in the ledger, on the mod's row, and not a continue-or-quit question.
	 */
	@Test
	void aSuppressionByNameIsAConfirmedFindingThatAsksNothing(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
			throws Exception {
		String config = "fabric-registry-sync-v0.mixins.json";
		String pkg = "net.fabricmc.fabric.mixin.registry.sync";
		assertTrue(MergedBaseMixinCompat.SUPPRESSED_MIXINS.contains(config + ":BootstrapMixin"), "precondition");
		java.nio.file.Path jar = dir.resolve("registry-sync.jar");
		try (var out = new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(jar))) {
			out.putNextEntry(new java.util.jar.JarEntry(config));
			out.write(("{\"required\":true,\"package\":\"" + pkg + "\",\"mixins\":[\"BootstrapMixin\",\"StillRunsMixin\","
					+ "\"PropertyListedMixin\"]}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
			out.closeEntry();
		}
		System.setProperty("forbric.suppressMixins", config + ":PropertyListedMixin");
		net.forbric.api.CompatibilityFindings.reset();
		try (var loader = new net.forbric.kernel.classloading.ForbricClassLoader(new java.net.URL[] {jar.toUri().toURL()},
				getClass().getClassLoader())) {
			ForbricMixinService.bind(loader, net.fabricmc.api.EnvType.CLIENT);
			String rewritten;
			try (var in = new ForbricMixinService().getResourceAsStream(config)) {
				rewritten = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
			}
			assertFalse(rewritten.contains("BootstrapMixin") || rewritten.contains("PropertyListedMixin"), rewritten);

			var findings = net.forbric.api.CompatibilityFindings.all();
			var hand = findings.stream().filter(f -> f.id().equals(MixinCompatibility.id(config, pkg + ".BootstrapMixin")))
					.findFirst().orElseThrow(() -> new AssertionError("no finding for the hand-listed mixin: " + findings));
			assertTrue(hand.confidence() == net.forbric.api.CompatibilityFinding.Confidence.CONFIRMED && !hand.required(),
					hand.toString());
			assertTrue(hand.evidence().contains("source=MergedBaseMixinCompat.SUPPRESSED_MIXINS"), hand.evidence().toString());
			assertTrue(hand.evidence().contains("config required=true"), "the mod's own declaration is kept: " + hand.evidence());
			var property = findings.stream().filter(f -> f.id().equals(MixinCompatibility.id(config, pkg + ".PropertyListedMixin")))
					.findFirst().orElseThrow();
			assertTrue(property.evidence().contains("source=-Dforbric.suppressMixins"), property.evidence().toString());
			assertTrue(findings.stream().noneMatch(f -> f.id().contains("StillRunsMixin")), "a kept mixin is not reported");
			assertTrue(net.forbric.api.CompatibilityFindings.confirmedRequired().isEmpty());
		} finally {
			ForbricMixinService.bind(null, net.fabricmc.api.EnvType.SERVER);
			net.forbric.api.CompatibilityFindings.reset();
		}
	}

	@Test
	void keepMixinsOverridesTheShippedSuppressionList() {
		String config = "fabric-registry-sync-v0.mixins.json";
		assertTrue(ForbricMixinService.suppressedMixinsFor(config).contains("BootstrapMixin"),
				"precondition: this entry ships in MergedBaseMixinCompat.SUPPRESSED_MIXINS");

		System.setProperty("forbric.keepMixins", config + ":BootstrapMixin");
		assertFalse(ForbricMixinService.suppressedMixinsFor(config).contains("BootstrapMixin"),
				"an explicit keepMixins must beat the shipped default");
		assertTrue(ForbricMixinService.suppressedMixinsFor(config).contains("MainMixin"),
				"and must not disturb its siblings — it names one mixin, not the config");
	}

	@Test
	void keepMixinsAlsoOverridesAnExplicitSuppressMixins() {
		String config = "example.mixins.json";
		System.setProperty("forbric.suppressMixins", config + ":SomeMixin");
		assertTrue(ForbricMixinService.suppressedMixinsFor(config).contains("SomeMixin"));

		System.setProperty("forbric.keepMixins", config + ":SomeMixin");
		assertFalse(ForbricMixinService.suppressedMixinsFor(config).contains("SomeMixin"),
				"keepMixins subtracts last, so it wins over suppressMixins too");
	}

	@Test
	void keepMixinsForAnUnrelatedConfigChangesNothing() {
		String config = "fabric-registry-sync-v0.mixins.json";
		System.setProperty("forbric.keepMixins", "other.mixins.json:BootstrapMixin");
		assertTrue(ForbricMixinService.suppressedMixinsFor(config).contains("BootstrapMixin"),
				"the config name is part of the key — a same-named mixin elsewhere must not unpin this one");
	}

	@Test
	void aGuestConfigNamedAfterAnEcosystemIsStillRelaxed() {
		// The exclusion used to be a PREFIX match on forge./neoforge./minecraft., which was harmless only while the
		// registered set was Fabric-only. A guest mod may legitimately name its config forge.mixins.json, and
		// leaving it strict turns one moved anchor into a fatal apply error.
		ForbricMixinService.setGuestConfigs(SAMPLE);

		assertTrue(ForbricMixinService.isRelaxedConfig("forge.mixins.json"));
		assertTrue(ForbricMixinService.isRelaxedConfig("neoforge.mixins.json"));
		assertTrue(ForbricMixinService.isRelaxedConfig("minecraft.mixins.json"));
		assertTrue(ForbricMixinService.isRelaxedConfig("bookshelf.common.mixins.json"));
		assertTrue(ForbricMixinService.isRelaxedConfig("geckolib.mixins.json"));
	}

	@Test
	void theKernelsOwnConfigIsNeverRelaxed() {
		// The kernel authors no mixins today, but a failure in one it did author must crash loudly rather than be
		// silently skipped.
		ForbricMixinService.setGuestConfigs(SAMPLE);

		assertFalse(ForbricMixinService.isRelaxedConfig("forbric-kernel.mixins.json"));
	}

	@Test
	void aConfigThatWasNeverRegisteredIsNotRelaxed() {
		ForbricMixinService.setGuestConfigs(SAMPLE);

		assertFalse(ForbricMixinService.isRelaxedConfig("something-else.mixins.json"));
	}

	@Test
	void relaxGuestMixinsOffRestoresStrictBehaviourForEveryone() {
		System.setProperty("forbric.relaxGuestMixins", "off");
		ForbricMixinService.setGuestConfigs(SAMPLE);

		assertFalse(ForbricMixinService.isRelaxedConfig("bookshelf.common.mixins.json"));
		assertFalse(ForbricMixinService.isRelaxedConfig("geckolib.mixins.json"));
	}

	@Test
	void relaxMixinOverwritesStillWorksAsAnExplicitOverride() {
		ForbricMixinService.setGuestConfigs(List.of());

		System.setProperty("forbric.relaxMixinOverwrites", "explicit.mixins.json,prefixed.*");
		assertTrue(ForbricMixinService.isRelaxedConfig("explicit.mixins.json"));
		assertTrue(ForbricMixinService.isRelaxedConfig("prefixed.anything.json"));
		assertFalse(ForbricMixinService.isRelaxedConfig("unlisted.mixins.json"));
	}
}
