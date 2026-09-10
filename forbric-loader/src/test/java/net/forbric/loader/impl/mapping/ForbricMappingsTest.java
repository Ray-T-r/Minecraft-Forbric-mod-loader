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

package net.forbric.loader.impl.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ForbricMappingsTest {
	// Tiny v1: official -> intermediary. Obf class 'a', field 'x' (I), method 'm' (I)V.
	private static final String INTERMEDIARY_TINY =
			"v1\tofficial\tintermediary\n"
			+ "CLASS\ta\tnet/minecraft/class_100\n"
			+ "FIELD\ta\tI\tx\tfield_200\n"
			+ "METHOD\ta\t(I)V\tm\tmethod_300\n";

	// ProGuard: named -> official(obf). com.example.Foo -> a, counter -> x, tick(int) -> m.
	private static final String MOJMAP_PROGUARD =
			"com.example.Foo -> a:\n"
			+ "    int counter -> x\n"
			+ "    void tick(int) -> m\n";

	private ForbricMappings load(Path dir) throws Exception {
		Path inter = dir.resolve("intermediary.tiny");
		Path moj = dir.resolve("client.txt");
		Files.write(inter, INTERMEDIARY_TINY.getBytes(StandardCharsets.UTF_8));
		Files.write(moj, MOJMAP_PROGUARD.getBytes(StandardCharsets.UTF_8));
		return ForbricMappings.load(inter, moj);
	}

	@Test
	void composesNamedToIntermediaryAcrossClassFieldMethod(@TempDir Path dir) throws Exception {
		ForbricMappings m = load(dir);

		// named -> official -> intermediary, joined on the shared obf column
		assertEquals("net/minecraft/class_100", m.mapClass("com/example/Foo"));
		assertEquals("field_200", m.mapField("com/example/Foo", "counter", "I"));
		assertEquals("method_300", m.mapMethod("com/example/Foo", "tick", "(I)V"));
	}

	@Test
	void unmappedNamesPassThrough(@TempDir Path dir) throws Exception {
		ForbricMappings m = load(dir);

		assertEquals("com/example/Unknown", m.mapClass("com/example/Unknown"));
		assertEquals("missingField", m.mapField("com/example/Foo", "missingField", "I"));
		assertEquals("missingMethod", m.mapMethod("com/example/Foo", "missingMethod", "()V"));
		assertEquals(1, m.classCount());
	}
}
