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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Reading fabric-api's alias map from a Forge-wrapped registry.
 *
 * <p>The stand-ins mirror the runtime shape that matters: an {@code aliases} map declared on a SUPERCLASS (that is
 * where fabric-api's mixin puts it — on {@code MappedRegistry}, not on the wrapper the game actually holds), and a
 * key type with {@code identifier()} plus a static {@code create}.
 */
class KernelRegistryAliasesTest {
	@BeforeEach
	@AfterEach
	void reset() {
		KernelRegistryAliases.resetForTests();
		System.clearProperty(KernelRegistryAliases.PROPERTY);
	}

	@Test
	void resolvesAnAliasToItsTarget() {
		Wrapper registry = new Wrapper();
		registry.aliases.put("waystones:mossy_waystone", "waystones:mossy_andesite_waystone");
		assertEquals("waystones:mossy_andesite_waystone",
				KernelRegistryAliases.resolveId(registry, "waystones:mossy_waystone"));
	}

	@Test
	void leavesEverythingElseExactlyAsItWas() {
		Wrapper registry = new Wrapper();
		registry.aliases.put("a:b", "a:c");
		// Identity, not just equality: a lookup that is not an alias must not allocate or substitute anything.
		String plain = "a:d";
		assertSame(plain, KernelRegistryAliases.resolveId(registry, plain));
		assertNull(KernelRegistryAliases.resolveId(registry, null));
	}

	@Test
	void aRegistryWithNoAliasMapIsUntouched() {
		// The kernel has to run without fabric-api, and then the field simply is not there.
		Object noMixin = new Object();
		String id = "a:b";
		assertSame(id, KernelRegistryAliases.resolveId(noMixin, id));
		assertSame(id, KernelRegistryAliases.resolveId(new Wrapper(), id)); // present but empty
	}

	@Test
	void theKillSwitchStopsResolving() {
		Wrapper registry = new Wrapper();
		registry.aliases.put("a:b", "a:c");
		System.setProperty(KernelRegistryAliases.PROPERTY, "off");
		assertEquals("a:b", KernelRegistryAliases.resolveId(registry, "a:b"));
	}

	@Test
	void resolvesTheResourceKeyFormToTheSameRegistry() {
		Wrapper registry = new Wrapper();
		registry.aliases.put("waystones:waystone", "waystones:andesite_waystone");

		Object resolved = KernelRegistryAliases.resolveKey(registry, new Key("waystones:waystone"));

		assertEquals(new Key("waystones:andesite_waystone"), resolved);
		assertEquals("minecraft:block", ((Key) resolved).registryKey);
	}

	@Test
	void aResourceKeyThatIsNotAnAliasComesBackUnchanged() {
		Wrapper registry = new Wrapper();
		registry.aliases.put("a:b", "a:c");
		Key key = new Key("a:d");
		assertSame(key, KernelRegistryAliases.resolveKey(registry, key));
		assertNull(KernelRegistryAliases.resolveKey(registry, null));
	}

	/** fabric-api declares {@code aliases} on MappedRegistry; the game holds the Forge subclass. */
	static class Base {
		final Map<Object, Object> aliases = new HashMap<>();
	}

	static class Wrapper extends Base {
		public Object key() {
			return "minecraft:block";
		}
	}

	static final class Key {
		final Object registryKey;
		final Object id;

		Key(Object id) {
			this("minecraft:block", id);
		}

		Key(Object registryKey, Object id) {
			this.registryKey = registryKey;
			this.id = id;
		}

		public Object identifier() {
			return id;
		}

		public static Key create(Object registryKey, Object id) {
			return new Key(registryKey, id);
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof Key k && k.id.equals(id);
		}

		@Override
		public int hashCode() {
			return id.hashCode();
		}
	}
}
