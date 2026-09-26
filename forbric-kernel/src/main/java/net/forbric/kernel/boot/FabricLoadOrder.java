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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * The order Fabric mods are listed, initialised and have their mixin configs registered in: Fabric Loader's own,
 * which is by mod id.
 *
 * <h2>What Fabric Loader does (0.19.5, read from its bytecode)</h2>
 *
 * <p>{@code ModResolver.findCompatibleSet} ends with {@code uniqueSelectedMods.sort(Comparator.comparing(
 * ModCandidateImpl::getId))} and returns that list. {@code FabricLoaderImpl.setup} then calls {@code addMod} for
 * each entry in that order, so it is the order of {@code getAllMods()}. {@code setupMods} walks the same list and
 * appends every mod's entrypoints to {@code EntrypointStorage}, one list per key, in insertion order, so every key
 * ({@code preLaunch}, {@code main}, {@code client}, {@code server}, and every custom one) hands its entrypoints back
 * in that order. {@code FabricMixinBootstrap} registers mixin configs by walking the same list.
 *
 * <ul>
 * <li>The sort is a plain {@code String} comparison of the id, so {@code pets-mod} comes before
 *     {@code petsmod-nyan-cat} ({@code -} sorts before letters) and {@code fabric-api} before {@code fabricloader}.
 * <li>Dependencies play no part. They decide WHICH candidates are selected, not their order: nothing after the
 *     solver looks at them again.
 * <li>Nested (jar-in-jar) mods are in the same list and are sorted with everything else. A nested mod is not
 *     placed after the mod that carries it.
 * <li>The builtins {@code java}, {@code minecraft} and {@code fabricloader} are in the same list too, so they are
 *     listed between the mods, where their ids fall.
 * <li>The list is shuffled only in a development environment ({@code Collections.shuffle} runs when
 *     {@code isDevelopmentEnvironment()} holds and {@code fabric.debug.disableModShuffle} is not set). A player's
 *     game is not a development environment, so the id order is the order every Fabric mod ships against.
 * </ul>
 *
 * <h2>Why Forbric has to follow it</h2>
 *
 * <p>Forbric used to put Fabric mods in {@link ModConstructionOrder}: jar file name, moved so that every mod comes
 * after what it requires. That is a reasonable order, but it is not the one Fabric mods are written for, and some
 * of them depend on the real one without knowing it. fabric-api's event invokers are plain loops. The one behind
 * {@code ClientPlayConnectionEvents.JOIN} has no try/catch around each listener, so when one listener throws, every
 * listener registered after it is skipped. "After" means "registered by a client entrypoint that ran later".
 *
 * <p>Pets Mod's JOIN listener reads {@code Minecraft.getCurrentServer().ip}. In singleplayer that server is null,
 * so the listener throws in every singleplayer world, on native Fabric too. Natively that costs the mods whose ids
 * sort after {@code pets-mod}. bclib and OptiGUI sort before it, so they registered first and their join handlers
 * run. Under the dependency order, bclib waited for wover and wunderlib, and OptiGUI waited for
 * fabric-language-kotlin and five fabric-api modules. Both landed after Pets Mod, and both join handlers were skipped: bclib never sent its
 * client-to-server data handlers, and OptiGUI never set its interaction data. All three register in the default
 * phase, which is why registration order decides. Voice Chat registers its listener in its own earlier phase, so it
 * runs first under either order.
 *
 * <p>So the Fabric mods are registered as before and then put in Fabric Loader's order, just before the loader
 * freezes and before any of them runs. Registration still decides which container wins when two claim one id, or
 * when one mod's {@code provides} alias is another mod's id, so the same one wins as before. Only the order
 * changes.
 *
 * <h2>What it does not touch</h2>
 *
 * <ul>
 * <li>Identities registered for presence only, which have no native counterpart: a Forge-family mod a Fabric mod
 *     can ask about, or the Fabric jar of a mod whose other build won arbitration. They carry no entrypoints and no
 *     mixins, and they stay after the Fabric mods, in the order they were registered.
 * <li>The class path. Minecraft's libraries still come before every mod jar, and Fabric jars keep discovery order.
 * <li>NeoForge and MinecraftForge. Their construction order is drawn from the Fabric mods too (a Forge-family mod
 *     can require a Fabric one), and it keeps getting them in the order it always did.
 * </ul>
 *
 * <p>{@code -Dforbric.fabricOrder=off} puts the Fabric mods back in {@link ModConstructionOrder}'s order, which
 * {@code -Dforbric.modOrder=name} then turns back into jar file name order, as before.
 */
public final class FabricLoadOrder {
	/** {@code -Dforbric.fabricOrder=off}: Fabric mods keep {@link ModConstructionOrder}'s order. */
	public static final String SWITCH = "forbric.fabricOrder";

	private FabricLoadOrder() {
	}

	/** Whether Fabric mods follow Fabric Loader's order. */
	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(SWITCH, "on"));
	}

	/**
	 * {@code mods} sorted the way {@code ModResolver.findCompatibleSet} sorts: by id, as a plain {@code String}.
	 *
	 * <p>Stable, like the {@code List.sort} Fabric Loader calls, so two entries with the same id keep the order they
	 * came in. Fabric Loader never has two, because its solver selects one. Here the loader's own registration has
	 * already decided which one counts, and a stable sort cannot change that. An entry with no id has nothing to sort
	 * by and goes last, in the order it came in.
	 */
	public static <T> List<T> byModId(List<T> mods, Function<? super T, String> idOf) {
		List<T> sorted = new ArrayList<>(mods);
		sorted.sort(Comparator.comparing(idOf, Comparator.nullsLast(Comparator.<String>naturalOrder())));
		return sorted;
	}
}
