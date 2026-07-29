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

package net.forbric.loader.impl.metadata.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ModsTomlAccessTransformerTest {
	@Test
	void parsesDeclaredAccessTransformers() {
		String toml = String.join("\n",
				"modLoader=\"javafml\"",
				"loaderVersion=\"[1,)\"",
				"",
				"[[accessTransformers]]",
				"file=\"META-INF/accesstransformer.cfg\"",
				"",
				"[[accessTransformers]]",
				"file=\"META-INF/extra_at.cfg\"",
				"",
				"[[mods]]",
				"modId=\"examplemod\"",
				"version=\"1.0.0\"");

		ForgeModsToml parsed = ModsTomlParser.parse(toml);
		List<String> ats = parsed.getAccessTransformers();

		assertEquals(2, ats.size());
		assertTrue(ats.contains("META-INF/accesstransformer.cfg"));
		assertTrue(ats.contains("META-INF/extra_at.cfg"));
	}

	@Test
	void noAccessTransformersYieldsEmpty() {
		String toml = String.join("\n",
				"modLoader=\"javafml\"",
				"loaderVersion=\"[1,)\"",
				"[[mods]]",
				"modId=\"x\"",
				"version=\"1.0.0\"");

		assertTrue(ModsTomlParser.parse(toml).getAccessTransformers().isEmpty());
	}
}
