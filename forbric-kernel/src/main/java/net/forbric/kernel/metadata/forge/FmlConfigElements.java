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

package net.forbric.kernel.metadata.forge;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import com.electronwill.nightconfig.core.UnmodifiableConfig;

/**
 * What {@code IConfigurable.getConfigElement} answers, the way each FML's own {@code NightConfigWrapper} answers it,
 * for every object the kernel hands a mod in place of the one its FML would have built.
 *
 * <h2>Why this is one class and not four answers</h2>
 *
 * <p>A mod reads a {@code mods.toml} back through two different objects, and neither is optional. The mod's own
 * {@code [[mods]]} entry is {@code IModInfo.getConfig()}; the whole FILE is the owning {@code ModFileInfo}, which
 * natively delegates {@code getConfigElement} to a wrapper over the file's root table and answers
 * {@code getConfig()} with itself. The kernel builds four of each — the seeded NeoForge and MinecraftForge
 * {@code LoadingModList} entries, and its own {@code KernelModInfo}/{@code KernelForgeModInfo} — and the file side of
 * all four answered empty (or, on {@code KernelModFileInfo}, null).
 *
 * <p>That is not an abstract gap. Unlit Campfire keeps a lit campfire's burn time on its block entity, and declares a
 * top-level {@code ["lithium:options"]} table asking Lithium to switch off
 * {@code mixin.world.block_entity_ticking.sleeping.campfire}, the optimisation that stops idle campfire block
 * entities from ticking. Lithium's {@code NeoForgeMixinOverrides} walks {@code LoadingModList.getMods()} and asks
 * each mod's {@code getOwningFile().getConfigElement("lithium:options")}; the player's log said
 * {@code Loaded configuration file for Lithium: 171 options available, 0 override(s) found.}, and Lithium applied the
 * campfire mixins anyway. Not Enough Crashes asks {@code getOwningFile().getConfig()} for {@code issueTrackerURL} of
 * every mod it lists, and on a kernel mod that {@code getConfig()} was null.
 *
 * <h2>The two answers</h2>
 *
 * <p>Both FMLs look the path up as a LIST of literal keys ({@code getOptional(List)} / {@code get(List)}), so
 * {@code "lithium:options"} and iris' {@code "mixin.features.render.world.sky"} are one key each, never split on a
 * dot. Both answer a scalar or a list as itself and a missing path as empty. They differ in one thing, the table:
 * NeoForge ({@code fancymodloader} 11.0.x) answers its {@code valueMap()}; MinecraftForge ({@code fmlloader}
 * 26.2-65.0.x) answers an {@code ImmutableMap} of the same entries. Neither descends, so a table one level further
 * down is still night-config's own {@code Config}. Lithium only needs a {@code Map}, but a MinecraftForge mod is
 * entitled to the Guava type it has always been given, so the MinecraftForge answer takes the copy function from
 * the caller: this class is boot-side and does not link Guava, the game does.
 *
 * <p>Not modelled: both wrappers THROW {@code InvalidModFileException} for a path that lands on an array of tables
 * ({@code "mods"}, {@code "mixins"}); this answers the list. A mod that asked for one would crash natively, so none
 * does.
 */
public final class FmlConfigElements {
	/**
	 * {@code -Dforbric.fileConfigElements=off} answers every file-level {@code getConfigElement} empty again (and
	 * {@code KernelModFileInfo.getConfig()} null), gives the kernel's own {@code KernelModInfo} and the seeded
	 * MinecraftForge {@code ModInfo} their empty {@code [[mods]]} answers back, and returns
	 * {@code KernelForgeModInfo} to its strings-only reading of the jar — each object's answer before this class.
	 * The older {@code -Dforbric.configElements=off} covers only the seeded NeoForge {@code ModInfo}; it does not
	 * reach the {@code [[mods]]} answers this switch owns, so each switch restores just its own objects.
	 */
	public static final String SWITCH = "forbric.fileConfigElements";

	private FmlConfigElements() {
	}

	/** Whether the kernel's mod infos answer from the mod's {@code mods.toml}. On unless switched off. */
	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(SWITCH, "on"));
	}

	/** NeoForge's {@code NightConfigWrapper.getConfigElement}: a table comes back as its {@code valueMap()}. */
	public static Optional<Object> neoForge(Map<String, ?> elements, String... path) {
		Object value = at(elements, path);
		if (value == null) return Optional.empty();
		return Optional.of(value instanceof UnmodifiableConfig table ? table.valueMap() : value);
	}

	/**
	 * MinecraftForge's {@code NightConfigWrapper.getConfigElement}: a table comes back as {@code immutableCopy} of its
	 * entries, which the game side makes an {@code ImmutableMap} as MinecraftForge does.
	 */
	public static Optional<Object> minecraftForge(Map<String, ?> elements, UnaryOperator<Map<String, Object>> immutableCopy,
			String... path) {
		Object value = at(elements, path);
		if (value == null) return Optional.empty();
		if (value instanceof UnmodifiableConfig table) return Optional.of(immutableCopy.apply(table.valueMap()));
		return Optional.of(value);
	}

	/** The copy MinecraftForge's answer falls back to where Guava cannot be reached: unmodifiable, like the real one. */
	public static Map<String, Object> unmodifiableCopy(Map<String, Object> entries) {
		return Collections.unmodifiableMap(new LinkedHashMap<>(entries));
	}

	/**
	 * The raw value at {@code path}, or null: each element a LITERAL key.
	 *
	 * <p>{@code elements} is a table's shallow {@code valueMap()} copy (see {@code ModsTomlParser.tableValues}), so the
	 * first step reads a {@code Map} and every later one a night-config {@code Config} — or a {@code Map} again with
	 * {@code -Dforbric.nightConfigTables=off}, which flattens the tree. Anything else on the way down, a string or a
	 * list, ends the walk with nothing found, as night-config's own path lookup does.
	 */
	public static Object at(Map<String, ?> elements, String... path) {
		if (elements == null || path == null || path.length == 0) return null;
		Object current = elements;
		for (String key : path) {
			if (key == null) return null;
			if (current instanceof Map<?, ?> map) {
				current = map.get(key);
			} else if (current instanceof UnmodifiableConfig table) {
				// One literal key: get(String) would split iris' "mixin.features.render.world.sky" on its dots.
				current = table.get(Collections.singletonList(key));
			} else {
				return null;
			}
			if (current == null) return null;
		}
		return current;
	}
}
