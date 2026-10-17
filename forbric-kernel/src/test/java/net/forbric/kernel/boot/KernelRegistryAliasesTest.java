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

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import net.forbric.kernel.transform.InjectorExecution;

/**
 * Reading a mixin's alias map from a Forge-wrapped registry.
 *
 * <p>The stand-ins mirror the runtime shape that matters: the alias map is a field a mixin merged into a SUPERCLASS
 * (fabric-api's mixin puts it on {@code MappedRegistry}, not on the wrapper the game actually holds), marked
 * {@code @MixinMerged} as Mixin marks every field it merges, and declared {@code Map<Identifier, Identifier>}. Here it
 * is called {@code quarry$links}, from a mixin no loader knows; beside it sit the registry's own (unmerged) id map and
 * a merged map of ids to numbers, neither of which is an alias map. Keys have {@code identifier()} plus a static
 * {@code create}.
 */
class KernelRegistryAliasesTest {
	private static final String MIXIN_MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";
	private static final Map<String, String> STAND_INS = Map.of(
			"net.minecraft.resources.Identifier", "package net.minecraft.resources; public record Identifier(String id) { }",
			"quarry.Base", """
					package quarry;

					import java.util.HashMap;
					import java.util.Map;
					import net.minecraft.resources.Identifier;

					public class Base {
						/** The registry's own id map: not merged, not an alias map. */
						public final Map<Identifier, Object> byLocation = new HashMap<>();
						/** A mixin's map of ids to numbers: merged, but not ids to ids. */
						public final Map<Identifier, Integer> quarry$numbers = new HashMap<>();
						/** A mixin's alias map. */
						public final Map<Identifier, Identifier> quarry$links = new HashMap<>();
					}
					""",
			"quarry.Wrapper", """
					package quarry;

					public class Wrapper extends Base {
						public Object key() {
							return "minecraft:block";
						}
					}
					""",
			"quarry.Key", """
					package quarry;

					import net.minecraft.resources.Identifier;

					public final class Key {
						public final Object registryKey;
						public final Identifier id;

						public Key(Identifier id) {
							this("minecraft:block", id);
						}

						public Key(Object registryKey, Identifier id) {
							this.registryKey = registryKey;
							this.id = id;
						}

						public Identifier identifier() {
							return id;
						}

						public static Key create(Object registryKey, Identifier id) {
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
					""");

	private static ClassLoader game;

	@BeforeAll
	static void compile(@TempDir Path work) throws Exception {
		Map<String, byte[]> classes = new HashMap<>(InjectorExecution.compile(work, STAND_INS));
		ClassNode base = new ClassNode();
		new ClassReader(classes.get("quarry/Base")).accept(base, 0);
		for (FieldNode field : base.fields) {
			if (!field.name.startsWith("quarry$")) continue;
			AnnotationNode merged = new AnnotationNode(MIXIN_MERGED);
			merged.values = new java.util.ArrayList<>(List.of("mixin", "org.quarry.registry.mixin.LinksMixin", "priority", 1000, "sessionId", "test"));
			field.visibleAnnotations = new java.util.ArrayList<>(List.of(merged));
		}
		ClassWriter writer = new ClassWriter(0);
		base.accept(writer);
		classes.put("quarry/Base", writer.toByteArray());
		game = InjectorExecution.load(classes, KernelRegistryAliasesTest.class.getClassLoader());
	}

	@BeforeEach
	@AfterEach
	void reset() {
		KernelRegistryAliases.resetForTests();
		System.clearProperty(KernelRegistryAliases.PROPERTY);
	}

	private static Object id(String id) throws Throwable {
		return InjectorExecution.construct(game.loadClass("net.minecraft.resources.Identifier"), id);
	}

	private static Object key(String id) throws Throwable {
		return InjectorExecution.construct(game.loadClass("quarry.Key"), id(id));
	}

	@SuppressWarnings("unchecked")
	private static Map<Object, Object> map(Object registry, String field) throws ReflectiveOperationException {
		return (Map<Object, Object>) game.loadClass("quarry.Base").getField(field).get(registry);
	}

	/** A wrapper whose alias map says {@code from} stands for {@code to}. */
	private static Object wrapper(String from, String to) throws Throwable {
		Object registry = InjectorExecution.construct(game.loadClass("quarry.Wrapper"));
		if (from != null) map(registry, "quarry$links").put(id(from), id(to));
		return registry;
	}

	@Test
	void resolvesAnAliasToItsTarget() throws Throwable {
		Object registry = wrapper("waystones:mossy_waystone", "waystones:mossy_andesite_waystone");
		assertEquals(id("waystones:mossy_andesite_waystone"), KernelRegistryAliases.resolveId(registry, id("waystones:mossy_waystone")));
	}

	@Test
	void leavesEverythingElseExactlyAsItWas() throws Throwable {
		Object registry = wrapper("a:b", "a:c");
		// Identity, not just equality: a lookup that is not an alias must not allocate or substitute anything.
		Object plain = id("a:d");
		assertSame(plain, KernelRegistryAliases.resolveId(registry, plain));
		assertNull(KernelRegistryAliases.resolveId(registry, null));
	}

	/** The registry's own id map and a merged map of ids to numbers are not read as aliases, whatever they hold. */
	@Test
	void onlyTheMergedIdToIdMapIsTheAliasMap() throws Throwable {
		Object registry = wrapper(null, null);
		map(registry, "byLocation").put(id("a:b"), id("a:c"));
		map(registry, "quarry$numbers").put(id("a:b"), 7);
		Object asked = id("a:b");
		assertSame(asked, KernelRegistryAliases.resolveId(registry, asked));
		map(registry, "quarry$links").put(id("a:b"), id("a:c"));
		assertEquals(id("a:c"), KernelRegistryAliases.resolveId(registry, asked));
	}

	@Test
	void aRegistryWithNoAliasMapIsUntouched() throws Throwable {
		// The kernel has to run without the mod that adds it, and then the field simply is not there.
		Object noMixin = new Object();
		Object id = id("a:b");
		assertSame(id, KernelRegistryAliases.resolveId(noMixin, id));
		assertSame(id, KernelRegistryAliases.resolveId(wrapper(null, null), id)); // present but empty
	}

	@Test
	void theKillSwitchStopsResolving() throws Throwable {
		Object registry = wrapper("a:b", "a:c");
		System.setProperty(KernelRegistryAliases.PROPERTY, "off");
		assertEquals(id("a:b"), KernelRegistryAliases.resolveId(registry, id("a:b")));
	}

	@Test
	void resolvesTheResourceKeyFormToTheSameRegistry() throws Throwable {
		Object registry = wrapper("waystones:waystone", "waystones:andesite_waystone");

		Object resolved = KernelRegistryAliases.resolveKey(registry, key("waystones:waystone"));

		assertEquals(key("waystones:andesite_waystone"), resolved);
		assertEquals("minecraft:block", resolved.getClass().getField("registryKey").get(resolved));
	}

	@Test
	void aResourceKeyThatIsNotAnAliasComesBackUnchanged() throws Throwable {
		Object registry = wrapper("a:b", "a:c");
		Object key = key("a:d");
		assertSame(key, KernelRegistryAliases.resolveKey(registry, key));
		assertNull(KernelRegistryAliases.resolveKey(registry, null));
	}
}
