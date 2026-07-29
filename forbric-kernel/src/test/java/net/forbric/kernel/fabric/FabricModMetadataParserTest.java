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

package net.forbric.kernel.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModEnvironment;

import net.forbric.kernel.fabric.KernelModMetadata.EntrypointDecl;
import net.forbric.kernel.fabric.KernelModMetadata.MixinConfigDecl;

/**
 * The {@code fabric.mod.json} reader, exercised on the real shapes the shipped ecosystem uses — fabric-api's
 * per-side mixin configs and JiJ list, Jade's custom entrypoint keys, and the {@code {adapter, value}} form.
 */
class FabricModMetadataParserTest {
	/** Modeled on fabric-lifecycle-events-v1's manifest, plus Jade's custom key and an object-form entrypoint. */
	private static final String REAL_SHAPE = """
			{
			  "schemaVersion": 1,
			  "id": "fabric-lifecycle-events-v1",
			  "name": "Fabric Lifecycle Events (v1)",
			  "version": "4.1.3+4575b05f9e",
			  "environment": "*",
			  "license": "Apache-2.0",
			  "icon": "assets/fabric-lifecycle-events-v1/icon.png",
			  "authors": ["FabricMC", {"name": "Someone", "contact": {"homepage": "https://example.invalid"}}],
			  "contact": {"issues": "https://github.com/FabricMC/fabric/issues"},
			  "entrypoints": {
			    "main": ["net.fabricmc.fabric.impl.event.lifecycle.LifecycleEventsImpl"],
			    "client": ["net.fabricmc.fabric.impl.client.event.lifecycle.ClientLifecycleEventsImpl"],
			    "jade": ["snownee.jade.addon.core.CorePlugin", "snownee.jade.addon.vanilla.VanillaPlugin"],
			    "kotlinish": [{"adapter": "kotlin", "value": "com.example.Mod::INSTANCE"}]
			  },
			  "mixins": [
			    "fabric-lifecycle-events-v1.mixins.json",
			    {"config": "fabric-lifecycle-events-v1.client.mixins.json", "environment": "client"}
			  ],
			  "jars": [{"file": "META-INF/jars/nested.jar"}],
			  "depends": {"fabricloader": ">=0.18.4", "minecraft": ["~26.2-", "26.3"]},
			  "breaks": {"badmod": "*"},
			  "description": "Events for the game's lifecycle.",
			  "custom": {"fabric-api:module-lifecycle": "stable", "forbric:probe": {"kind": "fabric", "n": 3}},
			  "accessWidener": "fabric-lifecycle-events-v1.classtweaker"
			}
			""";

	private static KernelModMetadata parse(String json) {
		return FabricModMetadataParser.read(new StringReader(json));
	}

	@Test
	void readsIdentityAndVersion() {
		KernelModMetadata m = parse(REAL_SHAPE);

		assertEquals("fabric-lifecycle-events-v1", m.getId());
		assertEquals("4.1.3+4575b05f9e", m.getVersion().getFriendlyString());
		assertEquals("Fabric Lifecycle Events (v1)", m.getName());
		assertEquals("fabric", m.getType());
		assertEquals(ModEnvironment.UNIVERSAL, m.getEnvironment());
		assertTrue(m.getEnvironment().matches(EnvType.SERVER));
	}

	@Test
	void readsEntrypointsIncludingCustomKeysAndTheAdapterObjectForm() {
		KernelModMetadata m = parse(REAL_SHAPE);

		// Key ORDER is not asserted: the JSON reader backs objects with a hash map, and entrypoint keys are only
		// ever looked up by name. Order WITHIN a key is a different matter — it is the order the mod declared its
		// initializers in, and it is preserved below.
		assertEquals(Set.of("main", "client", "jade", "kotlinish"), m.getEntrypoints().keySet());

		List<EntrypointDecl> jade = m.getEntrypoints().get("jade");
		assertEquals(2, jade.size());
		assertEquals("snownee.jade.addon.core.CorePlugin", jade.get(0).value());
		assertEquals("snownee.jade.addon.vanilla.VanillaPlugin", jade.get(1).value());

		EntrypointDecl main = m.getEntrypoints().get("main").get(0);
		assertEquals("net.fabricmc.fabric.impl.event.lifecycle.LifecycleEventsImpl", main.value());
		assertTrue(main.isDefaultAdapter());

		EntrypointDecl kotlin = m.getEntrypoints().get("kotlinish").get(0);
		assertEquals("kotlin", kotlin.adapter());
		assertEquals("com.example.Mod::INSTANCE", kotlin.value());
		assertFalse(kotlin.isDefaultAdapter());
	}

	@Test
	void readsMixinConfigsWithTheirSide() {
		List<MixinConfigDecl> configs = parse(REAL_SHAPE).getMixinConfigs();

		assertEquals(2, configs.size());
		assertEquals("fabric-lifecycle-events-v1.mixins.json", configs.get(0).config());
		assertEquals(ModEnvironment.UNIVERSAL, configs.get(0).environment());
		assertEquals(ModEnvironment.CLIENT, configs.get(1).environment());
		// The client-only config must not be registered on a dedicated server.
		assertFalse(configs.get(1).environment().matches(EnvType.SERVER));
	}

	@Test
	void readsNestedJarsAndAccessWidener() {
		KernelModMetadata m = parse(REAL_SHAPE);

		assertEquals(List.of("META-INF/jars/nested.jar"), m.getNestedJars());
		assertEquals("fabric-lifecycle-events-v1.classtweaker", m.getAccessWidener());
	}

	@Test
	void readsDependenciesWithBothScalarAndArrayConstraints() {
		KernelModMetadata m = parse(REAL_SHAPE);

		ModDependency loader = m.getDependencies().stream()
				.filter(d -> d.getModId().equals("fabricloader")).findFirst().orElseThrow();
		assertEquals(ModDependency.Kind.DEPENDS, loader.getKind());

		ModDependency mc = m.getDependencies().stream()
				.filter(d -> d.getModId().equals("minecraft")).findFirst().orElseThrow();
		assertEquals(2, ((KernelMetadataSupport.SimpleModDependency) mc).getConstraints().size());

		ModDependency breaks = m.getDependencies().stream()
				.filter(d -> d.getModId().equals("badmod")).findFirst().orElseThrow();
		assertEquals(ModDependency.Kind.BREAKS, breaks.getKind());
		assertFalse(breaks.getKind().isPositive());
	}

	@Test
	void readsCustomValueTreeTheWayModsQueryIt() {
		KernelModMetadata m = parse(REAL_SHAPE);

		assertTrue(m.containsCustomValue("fabric-api:module-lifecycle"));
		assertEquals("stable", m.getCustomValue("fabric-api:module-lifecycle").getAsString());

		CustomValue.CvObject probe = m.getCustomValue("forbric:probe").getAsObject();
		assertEquals(CustomValue.CvType.OBJECT, probe.getType());
		assertEquals("fabric", probe.get("kind").getAsString());
		assertEquals(3, probe.get("n").getAsNumber().intValue());
		assertTrue(probe.containsKey("kind"));
		assertNull(probe.get("absent"));
	}

	@Test
	void readsAuthorsInBothStringAndObjectForm() {
		KernelModMetadata m = parse(REAL_SHAPE);

		assertEquals(2, m.getAuthors().size());
		assertEquals("https://github.com/FabricMC/fabric/issues", m.getContact().get("issues").orElseThrow());
	}

	@Test
	void anUnsizedIconAnswersEveryQuery() {
		assertEquals("assets/fabric-lifecycle-events-v1/icon.png", parse(REAL_SHAPE).getIconPath(16).orElseThrow());
	}

	@Test
	void sizedIconsPickTheSmallestAtLeastAsLargeAsRequested() {
		KernelModMetadata m = parse("""
				{"schemaVersion":1,"id":"m","version":"1.0.0","icon":{"16":"a.png","64":"b.png"}}
				""");

		assertEquals("a.png", m.getIconPath(16).orElseThrow());
		assertEquals("b.png", m.getIconPath(32).orElseThrow());
		// Nothing is large enough: fall back to the largest available.
		assertEquals("b.png", m.getIconPath(256).orElseThrow());
	}

	@Test
	void aMinimalManifestParsesWithSaneDefaults() {
		KernelModMetadata m = parse("{\"schemaVersion\":1,\"id\":\"tiny\",\"version\":\"1.0.0\"}");

		assertEquals("tiny", m.getId());
		assertEquals("tiny", m.getName());
		assertEquals(ModEnvironment.UNIVERSAL, m.getEnvironment());
		assertTrue(m.getEntrypoints().isEmpty());
		assertTrue(m.getMixinConfigs().isEmpty());
		assertTrue(m.getNestedJars().isEmpty());
		assertNull(m.getAccessWidener());
		assertEquals("", m.getDescription());
	}

	@Test
	void clientOnlyModIsRecognisedSoDiscoveryCanSkipItOnAServer() {
		KernelModMetadata m = parse("""
				{"schemaVersion":1,"id":"c","version":"1.0.0","environment":"client"}
				""");

		assertEquals(ModEnvironment.CLIENT, m.getEnvironment());
		assertFalse(m.getEnvironment().matches(EnvType.SERVER));
		assertTrue(m.getEnvironment().matches(EnvType.CLIENT));
	}
}
