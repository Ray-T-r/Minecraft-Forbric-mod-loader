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

package net.forbric.kernel.mixin;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Vanilla anonymous classes ({@code Outer$N}) whose NAME the merge kept but whose CLASS it did not.
 *
 * <p>javac numbers anonymous classes per outer class in source order. A NeoForge patch that adds an anonymous
 * class before vanilla's renumbers every later one, and the merge keeps one class per name — so
 * {@code ByteBufCodecs$13} on this base is a different codec from vanilla's, whose body now lives at
 * {@code $12}. A mixin targeting {@code ByteBufCodecs$13} applies cleanly, every anchor resolves, and its
 * injections bind to unrelated code. Nothing else can see that.
 *
 * <p>Three buckets, derived from vanilla and the merged base by {@code MergedBaseAnonymousDriftTest}. The
 * identity of an anonymous class is its superclass plus its non-{@code <init>} method set, and a patch may ADD
 * methods to it (NeoForge gives {@code MappedRegistry$2} data-map accessors): {@code $N} still holds vanilla's
 * class while every vanilla method is there. Only a vanilla method that is GONE from {@code $N} says otherwise:
 * <ul>
 *   <li>{@link #RELOCATED}: vanilla's method set exists at another {@code $M} of the same outer class — the
 *       candidates are listed, several when the body is duplicated.</li>
 *   <li>{@link #RESHAPED}: vanilla's method set exists nowhere in the outer class any more (a real method lost,
 *       or the superclass changed).</li>
 *   <li>{@link #CAPTURE_ONLY}: same methods, only the constructor descriptor or the captured {@code val$}
 *       fields differ. Pinned for the record, never flagged — chat_heads targets {@code ChatComponent$1} this way
 *       and its anchors are exactly where vanilla put them.</li>
 * </ul>
 * {@link MixinFit} adds a SOFT unresolved anchor for a {@code @Mixin} target in the first two sets: listed in
 * the reason, PARTIAL at most, never UNFIT — so nothing that works today is dropped, and the adapter names the
 * mod and where vanilla's body went.
 */
public final class MergedBaseAnonymousDrift {
	public static final Map<String, List<String>> RELOCATED = Map.ofEntries(
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$12", List.of("net/minecraft/network/codec/ByteBufCodecs$11", "net/minecraft/network/codec/ByteBufCodecs$19")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$13", List.of("net/minecraft/network/codec/ByteBufCodecs$12")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$14", List.of("net/minecraft/network/codec/ByteBufCodecs$20")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$15", List.of("net/minecraft/network/codec/ByteBufCodecs$13", "net/minecraft/network/codec/ByteBufCodecs$21", "net/minecraft/network/codec/ByteBufCodecs$25")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$16", List.of("net/minecraft/network/codec/ByteBufCodecs$22")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$17", List.of("net/minecraft/network/codec/ByteBufCodecs$23")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$18", List.of("net/minecraft/network/codec/ByteBufCodecs$24")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$19", List.of("net/minecraft/network/codec/ByteBufCodecs$13", "net/minecraft/network/codec/ByteBufCodecs$21", "net/minecraft/network/codec/ByteBufCodecs$25")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$20", List.of("net/minecraft/network/codec/ByteBufCodecs$14")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$21", List.of("net/minecraft/network/codec/ByteBufCodecs$15")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$22", List.of("net/minecraft/network/codec/ByteBufCodecs$16", "net/minecraft/network/codec/ByteBufCodecs$18", "net/minecraft/network/codec/ByteBufCodecs$4", "net/minecraft/network/codec/ByteBufCodecs$5", "net/minecraft/network/codec/ByteBufCodecs$6")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$23", List.of("net/minecraft/network/codec/ByteBufCodecs$13", "net/minecraft/network/codec/ByteBufCodecs$21", "net/minecraft/network/codec/ByteBufCodecs$25")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$24", List.of("net/minecraft/network/codec/ByteBufCodecs$26")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$25", List.of("net/minecraft/network/codec/ByteBufCodecs$27")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$26", List.of("net/minecraft/network/codec/ByteBufCodecs$28")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$27", List.of("net/minecraft/network/codec/ByteBufCodecs$29", "net/minecraft/network/codec/ByteBufCodecs$30")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$28", List.of("net/minecraft/network/codec/ByteBufCodecs$29", "net/minecraft/network/codec/ByteBufCodecs$30")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$29", List.of("net/minecraft/network/codec/ByteBufCodecs$31")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$30", List.of("net/minecraft/network/codec/ByteBufCodecs$32")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$32", List.of("net/minecraft/network/codec/ByteBufCodecs$17")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$33", List.of("net/minecraft/network/codec/ByteBufCodecs$16", "net/minecraft/network/codec/ByteBufCodecs$18", "net/minecraft/network/codec/ByteBufCodecs$4", "net/minecraft/network/codec/ByteBufCodecs$5", "net/minecraft/network/codec/ByteBufCodecs$6")),
			Map.entry("net/minecraft/server/commands/FunctionCommand$1", List.of("net/minecraft/server/commands/FunctionCommand$2", "net/minecraft/server/commands/FunctionCommand$3", "net/minecraft/server/commands/FunctionCommand$4", "net/minecraft/server/commands/FunctionCommand$5")),
			Map.entry("net/minecraft/server/commands/FunctionCommand$5", List.of("net/minecraft/server/commands/FunctionCommand$1")),
			Map.entry("net/minecraft/util/BoundedFloatFunction$2", List.of("net/minecraft/util/BoundedFloatFunction$1")));

	public static final Set<String> RESHAPED = Set.of(
			"net/minecraft/data/recipes/RecipeProvider$Runner$1",
			"net/minecraft/network/codec/ByteBufCodecs$31");

	public static final Set<String> CAPTURE_ONLY = Set.of(
			"net/minecraft/client/gui/components/ChatComponent$1",
			"net/minecraft/data/tags/TagAppender$1",
			"net/minecraft/locale/Language$1",
			"net/minecraft/network/codec/ByteBufCodecs$11",
			"net/minecraft/resources/RegistryDataLoader$1",
			"net/minecraft/server/commands/FunctionCommand$3",
			"net/minecraft/server/network/ServerLoginPacketListenerImpl$1",
			"net/minecraft/util/BoundedFloatFunction$1",
			"net/minecraft/world/item/Item$TooltipContext$2",
			"net/minecraft/world/item/ItemStack$1",
			"net/minecraft/world/item/ItemStack$2",
			"net/minecraft/world/level/block/entity/SpawnerBlockEntity$1");

	private MergedBaseAnonymousDrift() {
	}

	/** Whether {@code internalName} is a renumbered or reshaped anonymous class. */
	public static boolean drifted(String internalName) {
		return RELOCATED.containsKey(internalName) || RESHAPED.contains(internalName);
	}

	/** One sentence saying what {@code internalName} is on this base, for the mixin log. */
	public static String describe(String internalName) {
		List<String> candidates = RELOCATED.get(internalName);
		if (candidates != null) {
			return "vanilla's body now lives at " + String.join(" or ", candidates);
		}
		return RESHAPED.contains(internalName) ? "the class was reshaped by the merge" : "";
	}
}
