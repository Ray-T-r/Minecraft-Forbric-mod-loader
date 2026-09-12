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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * The {@code IConfigurable} the seeder gives a mod that declares no config section.
 *
 * <p>There were two of these, one per ecosystem, written at different times, and only one was right. The Forge
 * copy answered everything but {@code getConfigList} with {@code Optional.empty()} — including the three
 * {@code Object} methods a dynamic {@link Proxy} routes to the handler. Asking it for a hash code returned an
 * {@code Optional} where the signature says {@code int}, and the proxy threw {@link ClassCastException} on the
 * way out, from a call nobody writes on purpose: a record hashing its components, a log line, a set membership
 * test.
 *
 * <p>The handler is driven here through a stand-in interface rather than the real SPI, because the real one is a
 * game type and this suite runs without the staged jars. That costs nothing: the handler dispatches on the method
 * NAME and never sees the interface, so the stand-in exercises exactly the code that ships. What it must
 * reproduce faithfully is the SHAPE — in particular traditional Forge's two extra DEFAULT methods, which a Proxy
 * routes to the handler as well, their default bodies never running.
 */
class EmptyConfigurableTest {

	/** Both families' shapes at once: the two abstract varargs methods, plus Forge's two extra defaults. */
	interface StandInConfigurable {
		<T> Optional<T> getConfigElement(String... key);

		List<?> getConfigList(String... key);

		default <T> Optional<T> getConfigElement(String key) {
			throw new AssertionError("a Proxy must route default methods to the handler, not run this body");
		}

		default List<?> getConfigList(String key) {
			throw new AssertionError("a Proxy must route default methods to the handler, not run this body");
		}
	}

	private static StandInConfigurable seeded() {
		return (StandInConfigurable) Proxy.newProxyInstance(
				EmptyConfigurableTest.class.getClassLoader(),
				new Class<?>[] {StandInConfigurable.class},
				PassiveSeeder.emptyConfigurableHandler());
	}

	@Test
	void everyLookupFindsNothing() {
		StandInConfigurable config = seeded();

		assertEquals(Optional.empty(), config.getConfigElement("credits"));
		assertEquals(Optional.empty(), config.getConfigElement(new String[] {"a", "b"}));
		assertEquals(List.of(), config.getConfigList("dependencies"));
		assertEquals(List.of(), config.getConfigList(new String[] {"a", "b"}));
	}

	/** The defect. A hash code is an int; the old Forge handler returned an Optional and the proxy threw. */
	@Test
	void hashCodeIsAnIntAndNotAnOptional() {
		StandInConfigurable config = seeded();

		int once = config.hashCode();
		assertEquals(once, config.hashCode(), "a seeded config's hash code must be stable — it goes into maps");
	}

	@Test
	void toStringIsAStringAndSaysWhatItIs() {
		String rendered = seeded().toString();

		assertNotNull(rendered);
		assertTrue(rendered.contains("Kernel"),
				"a seeded config prints inside mod-loading diagnostics; it should be identifiable there");
	}

	@Test
	void equalsIsIdentityAndAnswersBoolean() {
		StandInConfigurable a = seeded();
		StandInConfigurable b = seeded();

		assertTrue(a.equals(a));
		assertFalse(a.equals(b), "two seeded configs are distinct objects and must not merge in a set");
		assertFalse(a.equals("not a config"));
	}

	/**
	 * The realistic trigger, not a synthetic one: the seeded config is a COMPONENT of a real
	 * {@code moddiscovery.ModInfo} record, and a record's generated {@code hashCode}/{@code equals}/
	 * {@code toString} walk their components. Anything that puts such a record in a map walks through here.
	 */
	@Test
	void survivesBeingAComponentOfSomethingHashed() {
		record ModInfoLike(String modId, Object config) {}

		ModInfoLike one = new ModInfoLike("alpha", seeded());
		ModInfoLike two = new ModInfoLike("beta", seeded());

		Map<ModInfoLike, String> byInfo = new HashMap<>();
		byInfo.put(one, "alpha");
		byInfo.put(two, "beta");

		assertEquals(2, byInfo.size());
		assertEquals("alpha", byInfo.get(one));
		assertNotNull(one.toString());
		assertEquals(2, new HashSet<>(List.of(one, two)).size());
	}

	/** One handler, not one per family — the families differ in which interface, and in nothing else here. */
	@Test
	void bothFamiliesShareTheOneHandler() {
		assertSame(PassiveSeeder.emptyConfigurableHandler(), PassiveSeeder.emptyConfigurableHandler(),
				"a second copy is how the two answers drifted apart in the first place");
	}
}
