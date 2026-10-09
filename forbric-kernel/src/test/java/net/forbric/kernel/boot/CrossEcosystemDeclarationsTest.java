/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import com.electronwill.nightconfig.core.CommentedConfig;

import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.boot.CrossEcosystemDeclarations.Read;
import net.forbric.kernel.fabric.FabricModMetadataParser;
import net.forbric.kernel.fabric.KernelModMetadata;
import net.forbric.kernel.fabric.KernelModMetadata.EntrypointDecl;

/**
 * A mod's declarations to other mods, carried into the other family's spelling — with names that are none of the mods
 * the convention was measured on, so what is tested is the rule and not a list of keys.
 */
@ResourceLock("system-properties")
@ResourceLock("CrossEcosystemDeclarations")
class CrossEcosystemDeclarationsTest {
	private static final String SWITCH = CrossEcosystemDeclarations.SWITCH;
	private static final String FORMER = "forbric.sodiumConfigUsers";
	private String savedSwitch, savedFormer;

	@BeforeEach
	void clear() {
		savedSwitch = System.getProperty(SWITCH);
		savedFormer = System.getProperty(FORMER);
		System.clearProperty(SWITCH);
		System.clearProperty(FORMER);
		CrossEcosystemDeclarations.resetForTests();
	}

	@AfterEach
	void restore() {
		restore(SWITCH, savedSwitch);
		restore(FORMER, savedFormer);
		CrossEcosystemDeclarations.resetForTests();
	}

	private static void restore(String name, String value) {
		if (value == null) System.clearProperty(name);
		else System.setProperty(name, value);
	}

	private static KernelModMetadata fabric(String json) {
		return FabricModMetadataParser.read(new StringReader(json));
	}

	/** The Fabric mod as the boot publishes it: custom values on the mod, entrypoint names beside it. */
	private static DiscoveredMod publish(KernelModMetadata meta) {
		CrossEcosystemDeclarations.publishFabricEntrypointNames(
				Map.of(meta.getId(), CrossEcosystemDeclarations.entrypointNames(meta.getEntrypoints())));
		return new DiscoveredMod(Ecosystem.FABRIC, meta.getId(), "1", meta.getId(), List.of(), List.of(), null, null)
				.withModProperties(CrossEcosystemDeclarations.customProperties(meta.getCustomValues()));
	}

	private static final String LUMEN = """
			{"schemaVersion":1,"id":"lumen_panels","version":"1.0.0",
			 "custom":{"glowkit:theme":{"accent":"amber"}},
			 "entrypoints":{
			   "glowkit:plugin":["fixture.lumen.GlowPlugin"],
			   "glowkit:hooks":["fixture.lumen.HookA", {"adapter":"kotlin","value":"fixture.lumen.HookB"}],
			   "glowkit:table":["fixture.lumen.NotATable"],
			   "main":["fixture.lumen.Main"],"client":["fixture.lumen.Client"],
			   "glowplugin":["fixture.lumen.Bare"]}}
			""";

	@Test
	void customValuesArriveWithTheTypesTheTomlReaderGives() {
		Map<String, Object> table = CrossEcosystemDeclarations.customProperties(fabric("""
				{"schemaVersion":1,"id":"lumen_panels","version":"1.0.0",
				 "custom":{
				   "lumen:theme":{"accent":"amber","levels":[1,2,3],"nested":{"on":true},"dotted.key":"one key",
				                  "gone":null},
				   "flag":true,"ratio":0.5,"big":4000000000,"label":"text"}}
				""").getCustomValues());

		CommentedConfig theme = assertInstanceOf(CommentedConfig.class, table.get("lumen:theme"),
				"an object is the night-config table a TOML reader hands a mod, which it casts to (CommentedConfig)");
		assertEquals("amber", theme.get(List.of("accent")));
		assertEquals(List.of(1, 2, 3), theme.get(List.of("levels")));
		assertInstanceOf(Integer.class, ((List<?>) theme.get(List.of("levels"))).get(0));
		assertEquals(Boolean.TRUE, assertInstanceOf(CommentedConfig.class, theme.get(List.of("nested"))).get(List.of("on")));
		assertEquals("one key", theme.get(List.of("dotted.key")), "a key with a dot is one key, not a path");
		assertFalse(theme.contains(List.of("gone")), "JSON null has no TOML spelling");
		assertEquals(Boolean.TRUE, table.get("flag"));
		assertEquals(0.5, table.get("ratio"));
		assertEquals(4000000000L, table.get("big"));
		assertEquals("text", table.get("label"));
	}

	@Test
	void onlyNamespacedEntrypointKeysCarryNames() {
		assertEquals(Map.of(
				"glowkit:plugin", "fixture.lumen.GlowPlugin",
				"glowkit:hooks", List.of("fixture.lumen.HookA", "fixture.lumen.HookB"),
				"glowkit:table", "fixture.lumen.NotATable"),
				CrossEcosystemDeclarations.entrypointNames(fabric(LUMEN).getEntrypoints()),
				"one declaration is a single name, several a list in declaration order whatever their adapter; Fabric "
						+ "Loader's own keys and a bare plugin key stay out: no namespace owns them");
	}

	@Test
	void aNameIsOfferedOnlyUnderAKeyAReaderReadsAsAName() {
		DiscoveredMod lumen = publish(fabric(LUMEN));
		Map<String, Object> table = CrossEcosystemDeclarations.declarationsOf(lumen);
		assertEquals(List.of("glowkit:theme"), List.copyOf(table.keySet()),
				"before any reader has said how it reads a key, only the custom values are there");

		CrossEcosystemDeclarations.noteRead("glowkit:plugin", Read.NAME, "fixture.Reader");
		CrossEcosystemDeclarations.noteRead("glowkit:table", Read.OTHER, "fixture.TableReader");
		CrossEcosystemDeclarations.noteRead("glowkit:hooks", Read.NAME, "fixture.Reader");
		CrossEcosystemDeclarations.noteRead("glowkit:hooks", Read.OTHER, "fixture.OtherReader");

		assertEquals("fixture.lumen.GlowPlugin", table.get("glowkit:plugin"),
				"the same table, read again: it is a view, so a reader defined later is served too");
		assertTrue(table.containsKey("glowkit:plugin"));
		assertNull(table.get("glowkit:table"), "a key a reader reads as a table never holds a class name");
		assertNull(table.get("glowkit:hooks"), "nor does a key one reader reads as a name and another as anything else");
		assertEquals(Map.of("glowkit:theme", table.get("glowkit:theme"), "glowkit:plugin", "fixture.lumen.GlowPlugin"),
				new LinkedHashMap<>(table));
	}

	@Test
	void aCustomValueOutranksAnEntrypointUnderTheSameKey() {
		DiscoveredMod mod = publish(fabric("""
				{"schemaVersion":1,"id":"lumen_panels","version":"1.0.0",
				 "custom":{"glowkit:plugin":{"mode":"table"}},
				 "entrypoints":{"glowkit:plugin":["fixture.lumen.GlowPlugin"]}}
				"""));
		CrossEcosystemDeclarations.noteRead("glowkit:plugin", Read.NAME, "fixture.Reader");
		assertInstanceOf(CommentedConfig.class, CrossEcosystemDeclarations.declarationsOf(mod).get("glowkit:plugin"));
	}

	@Test
	void anotherFamilysModKeepsItsOwnTable() {
		DiscoveredMod neo = new DiscoveredMod(Ecosystem.NEOFORGE, "lumen_panels", "1", "Lumen", List.of(), List.of(),
				null, null).withModProperties(Map.of("glowkit:plugin", "fixture.neo.Plugin"));
		CrossEcosystemDeclarations.publishFabricEntrypointNames(Map.of("lumen_panels", Map.of("glowkit:other", "x.Y")));
		CrossEcosystemDeclarations.noteRead("glowkit:other", Read.NAME, "fixture.Reader");
		assertSame(neo.getModProperties(), CrossEcosystemDeclarations.declarationsOf(neo),
				"Fabric names are published by Fabric mod id; a NeoForge mod of the same id is not that mod");
	}

	@Test
	void aModThatDeclaresNothingCrossesWithAnEmptyTable() {
		DiscoveredMod quiet = publish(fabric("""
				{"schemaVersion":1,"id":"quiet_mod","version":"1.0.0","entrypoints":{"main":["a.B"]}}
				"""));
		assertTrue(CrossEcosystemDeclarations.declarationsOf(quiet).isEmpty());
		assertFalse(CrossEcosystemDeclarations.mayDeclare(quiet));
	}

	@Test
	void aClassNamedUnderANamespacedPropertyIsAnEntrypointOfThatKey() {
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("glowkit:plugin", "fixture.neo.GlowPlugin");
		properties.put("glowkit:hooks", List.of("fixture.neo.HookA", "fixture.neo.HookB"));
		properties.put("glowkit:mixed", List.of("fixture.neo.HookA", 3));
		properties.put("glowkit:enabled", true);
		properties.put("glowkit:table", CommentedConfig.inMemory());
		properties.put("fabric:provides", List.of("other_id"));
		properties.put("catalogueImageIcon", "icon.png");
		properties.put("glowkit:blank", " ");

		Map<String, List<EntrypointDecl>> entrypoints = CrossEcosystemDeclarations.fabricEntrypoints(properties);
		assertEquals(Map.of(
				"glowkit:plugin", List.of(new EntrypointDecl("default", "fixture.neo.GlowPlugin")),
				"glowkit:hooks", List.of(new EntrypointDecl("default", "fixture.neo.HookA"),
						new EntrypointDecl("default", "fixture.neo.HookB"))), entrypoints,
				"a list with a non-name, a flag, a table, a blank, Fabric's own metadata fields and an unnamespaced "
						+ "key are not class declarations");
	}

	@Test
	void namespacedMeansBothHalvesArePresent() {
		assertTrue(CrossEcosystemDeclarations.namespaced("a:b"));
		assertTrue(CrossEcosystemDeclarations.namespaced("adventure-internal:sidedproxy/client"));
		assertFalse(CrossEcosystemDeclarations.namespaced(":b"));
		assertFalse(CrossEcosystemDeclarations.namespaced("a:"));
		assertFalse(CrossEcosystemDeclarations.namespaced("ab"));
		assertFalse(CrossEcosystemDeclarations.namespaced(null));
	}

	@Test
	void switchedOffNothingCrossesEitherWay() {
		KernelModMetadata meta = fabric(LUMEN);
		DiscoveredMod lumen = publish(meta);
		CrossEcosystemDeclarations.noteRead("glowkit:plugin", Read.NAME, "fixture.Reader");
		System.setProperty(SWITCH, "off");
		assertEquals(Map.of(), CrossEcosystemDeclarations.customProperties(meta.getCustomValues()));
		assertNull(CrossEcosystemDeclarations.declarationsOf(lumen).get("glowkit:plugin"));
		assertEquals(Map.of(), CrossEcosystemDeclarations.fabricEntrypoints(Map.of("glowkit:plugin", "a.B")));
	}

	@Test
	void theSwitchTheSodiumBridgeUsedToHaveStillTurnsItOff() {
		System.setProperty(FORMER, "off");
		assertFalse(CrossEcosystemDeclarations.enabled());
		assertEquals(Map.of(), CrossEcosystemDeclarations.fabricEntrypoints(Map.of("glowkit:plugin", "a.B")));
		System.setProperty(SWITCH, "on");
		assertTrue(CrossEcosystemDeclarations.enabled(), "the current name wins");
	}
}
