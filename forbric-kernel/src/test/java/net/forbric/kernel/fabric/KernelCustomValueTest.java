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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.electronwill.nightconfig.json.JsonFormat;

import org.junit.jupiter.api.Test;

import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.CustomValue.CvType;

/**
 * Covers the {@code fabric.mod.json} {@code "custom"} block as mods actually read it.
 *
 * <p>This is the kernel's implementation of a Fabric API contract other people's code calls, and it had no test.
 * It cannot fail loudly either: every wrong answer here is a well-typed value of the wrong kind. A mod asking
 * {@code getAsString()} on something this built as a number gets an exception from inside ITS code, and a mod
 * whose feature key silently reads as absent just quietly turns the feature off.
 */
class KernelCustomValueTest {
	@Test
	void everyJsonShapeBecomesItsOwnType() {
		assertEquals(CvType.STRING, KernelCustomValue.of("stable").getType());
		assertEquals(CvType.NUMBER, KernelCustomValue.of(3).getType());
		assertEquals(CvType.BOOLEAN, KernelCustomValue.of(true).getType());
		assertEquals(CvType.ARRAY, KernelCustomValue.of(List.of(1, 2)).getType());
		assertEquals(CvType.OBJECT, KernelCustomValue.of(Map.of("a", 1)).getType());
	}

	/**
	 * A Forge-family mod's {@code [modproperties]} reach Fabric mods as custom values (Indigo asks Sodium's NeoForge
	 * build for {@code fabric-renderer-api-v1:contains_renderer} that way). The table now keeps FML's nested
	 * night-config {@code Config}s rather than flattened maps; the Fabric side must read the SAME tree either way.
	 * Pinned on LibJF Translate's real manifest, whose tables are nested two levels and hold arrays of tables.
	 */
	@Test
	void aForgeFamilyModsNestedTablesReadTheSameAsCustomValuesInEitherShape() throws Exception {
		Map<String, Object> asFmlGivesIt = libjfTranslateProperties();
		System.setProperty(net.forbric.kernel.metadata.forge.ModsTomlParser.NIGHT_CONFIG_TABLES, "off");
		Map<String, Object> flattened;
		try {
			flattened = libjfTranslateProperties();
		} finally {
			System.clearProperty(net.forbric.kernel.metadata.forge.ModsTomlParser.NIGHT_CONFIG_TABLES);
		}
		assertTrue(asFmlGivesIt.get("libjf:config") instanceof com.electronwill.nightconfig.core.Config);
		assertTrue(flattened.get("libjf:config") instanceof LinkedHashMap);

		assertEquals(asFmlGivesIt.keySet(), flattened.keySet());
		for (String key : asFmlGivesIt.keySet()) {
			assertEquals(render(KernelCustomValue.of(flattened.get(key))), render(KernelCustomValue.of(asFmlGivesIt.get(key))),
					key);
		}
		CustomValue previous = KernelCustomValue.of(asFmlGivesIt.get("libjf:config")).getAsObject().get("previous_names");
		assertEquals("libjf_translate_v1", previous.getAsArray().get(0).getAsObject().get("name").getAsString());
	}

	private static Map<String, Object> libjfTranslateProperties() throws Exception {
		try (java.io.InputStream in = KernelCustomValueTest.class.getResourceAsStream(
				"/forge/libjf-translate-v1.neoforge.mods.toml")) {
			return net.forbric.kernel.metadata.forge.ModsTomlParser.parse(in).getMods().get(0).getProperties();
		}
	}

	/** The whole tree as text, keys sorted, so two trees compare by content rather than by identity. */
	private static String render(CustomValue value) {
		switch (value.getType()) {
			case OBJECT: {
				java.util.TreeMap<String, String> sorted = new java.util.TreeMap<>();
				for (Map.Entry<String, CustomValue> entry : value.getAsObject()) sorted.put(entry.getKey(), render(entry.getValue()));
				return "{" + sorted + "}";
			}
			case ARRAY: {
				List<String> out = new ArrayList<>();
				for (CustomValue element : value.getAsArray()) out.add(render(element));
				return out.toString();
			}
			case STRING: return "\"" + value.getAsString() + "\"";
			case NUMBER: return String.valueOf(value.getAsNumber());
			case BOOLEAN: return String.valueOf(value.getAsBoolean());
			default: return "null";
		}
	}

	/**
	 * A JSON {@code null} becomes a CustomValue of type NULL, never a Java {@code null}. The difference is the
	 * whole reason the type exists: a caller that got {@code null} back would NPE on {@code getType()} instead of
	 * being told the key is present and empty.
	 */
	@Test
	void aJsonNullIsAValueOfTypeNullNotAJavaNull() {
		CustomValue value = KernelCustomValue.of(null);
		assertEquals(CvType.NULL, value.getType());
	}

	/**
	 * Asking for the wrong shape throws {@link ClassCastException} — Fabric's contract, and what mods write their
	 * {@code instanceof}/try-catch against. Returning a default instead would let a misread key look like a real
	 * answer.
	 */
	@Test
	void askingForTheWrongShapeThrowsRatherThanImprovising() {
		CustomValue string = KernelCustomValue.of("stable");
		assertThrows(ClassCastException.class, string::getAsNumber);
		assertThrows(ClassCastException.class, string::getAsObject);
		assertThrows(ClassCastException.class, string::getAsArray);
		assertThrows(ClassCastException.class, string::getAsBoolean);
		assertThrows(ClassCastException.class, () -> KernelCustomValue.of(1).getAsString());
	}

	@Test
	void anUnknownScalarDegradesToItsStringFormRatherThanFailingTheWholeMod() {
		CustomValue value = KernelCustomValue.of(new StringBuilder("odd"));
		assertEquals(CvType.STRING, value.getType());
		assertEquals("odd", value.getAsString());
	}

	@Test
	void anObjectKeepsItsKeysAndTheirDeclaredOrder() {
		Map<String, Object> declared = new LinkedHashMap<>();
		declared.put("first", 1);
		declared.put("second", 2);
		declared.put("third", 3);
		CustomValue.CvObject object = KernelCustomValue.of(declared).getAsObject();

		assertEquals(3, object.size());
		assertTrue(object.containsKey("second"));
		assertFalse(object.containsKey("absent"));
		assertNull(object.get("absent"), "an absent key is null, not a NULL-typed value — those mean different things");

		List<String> keys = new ArrayList<>();
		object.forEach(e -> keys.add(e.getKey()));
		assertEquals(List.of("first", "second", "third"), keys);
	}

	@Test
	void anArrayKeepsItsOrderAndIsIndexable() {
		CustomValue.CvArray array = KernelCustomValue.of(List.of("a", "b", "c")).getAsArray();
		assertEquals(3, array.size());
		assertEquals("b", array.get(1).getAsString());

		List<String> seen = new ArrayList<>();
		array.forEach(v -> seen.add(v.getAsString()));
		assertEquals(List.of("a", "b", "c"), seen);
	}

	/**
	 * The real path: night-config parses {@code fabric.mod.json}, and nested blocks arrive as its own
	 * {@code UnmodifiableConfig} rather than as a {@code Map} — a separate branch, and the one every actual mod
	 * takes. Reading a nested key is exactly what fabric-api's module tags and ModMenu's metadata do.
	 */
	@Test
	void aNestedBlockParsedByNightConfigReadsBackKeyByKey() {
		Object parsed = JsonFormat.fancyInstance().createParser().parse(new StringReader("""
				{ "fabric-api:module-lifecycle": "stable",
				  "modmenu": { "links": { "issues": "https://example.invalid" }, "badges": ["library"] },
				  "enabled": true }
				"""));

		CustomValue.CvObject root = KernelCustomValue.of(parsed).getAsObject();
		assertEquals("stable", root.get("fabric-api:module-lifecycle").getAsString());
		assertTrue(root.get("enabled").getAsBoolean());

		CustomValue.CvObject modmenu = root.get("modmenu").getAsObject();
		assertEquals("https://example.invalid",
				modmenu.get("links").getAsObject().get("issues").getAsString());
		assertEquals("library", modmenu.get("badges").getAsArray().get(0).getAsString());
	}
}
