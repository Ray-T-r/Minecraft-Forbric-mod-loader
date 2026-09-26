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
import java.util.Set;

import net.forbric.api.Ecosystem;

/**
 * Descriptor-identical callee swaps the merge made inside vanilla method bodies, and the few a guest mixin's
 * {@code @At(INVOKE)} may follow.
 *
 * <p>A second, narrower table, {@link #SUBSTITUTED}, holds the swaps that change the callee's owner, name or return
 * type as well: see {@link Substitution}. Only an {@code @Inject} may follow one of those.
 *
 * <p>Where NeoForge's patch of a vanilla method replaces one call with another of the same descriptor on the
 * same owner — {@code BlockState.isAir()Z} → {@code isEmpty()Z} in {@code LevelChunkSection.setBlockState} —
 * a mixin anchored on the vanilla callee misses. The census in {@code MergedBaseCalleeSwapTest} finds every such
 * swap between stock 26.2 and the merged base and classifies each with a safety test: is the merged callee a
 * pure delegate of the vanilla one (or vice versa)? Today NONE is — every swap is a NeoForge behaviour change
 * ({@code isEmpty} is an overridable {@code IBlockStateExtension} default, not a rename) — so no base-side
 * call-site rewrite exists, and the census asserts that it must not.
 *
 * <p>What CAN be argued is per mixin, not per call site: fabric-block-api-v1's {@code modifyAirCheck} handler
 * body is exactly {@code is(AIR) || is(CAVE_AIR) || is(VOID_AIR)}, byte-for-byte the predicate NeoForge's
 * default {@code isEmpty} computes. Retargeting that {@code @Redirect} to {@code isEmpty} keeps NeoForge's
 * semantics on every block and only overrides a block-level {@code isEmpty} override — which the Fabric handler
 * could never honour on any loader. Each row here carries that argument in {@code because}; the census asserts
 * the rows are a subset of the swaps it finds, so a rebuild that removes the swap turns the row red.
 */
public final class MergedBaseCalleeSwaps {
	/**
	 * @param target      the class the mixin targets (internal name)
	 * @param method      the target method, {@code name + descriptor}
	 * @param owner       the callee's owner (internal name)
	 * @param vanillaName the callee name the mixin anchors on
	 * @param mergedName  the callee name the merged body calls instead
	 * @param desc        the callee descriptor, identical on both sides
	 * @param because     why following the swap is sound for the mixins that anchor here
	 */
	public record Swap(String target, String method, String owner, String vanillaName, String mergedName, String desc,
			String because) {
		public String vanillaMember() {
			return "L" + owner + ";" + vanillaName + desc;
		}

		public String mergedMember() {
			return "L" + owner + ";" + mergedName + desc;
		}
	}

	private static final String BLOCK_STATE = "net/minecraft/world/level/block/state/BlockState";
	private static final String WHY = "fabric-block-api-v1's redirect handler is is(AIR)||is(CAVE_AIR)||is(VOID_AIR), "
			+ "byte-for-byte NeoForge's default isEmpty; following the swap keeps NeoForge's block-level override";

	public static final List<Swap> KNOWN = List.of(
			new Swap("net/minecraft/world/level/chunk/LevelChunkSection",
					"setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
					BLOCK_STATE, "isAir", "isEmpty", "()Z", WHY),
			new Swap("net/minecraft/world/level/chunk/LevelChunkSection$1BlockCounter",
					"accept(Lnet/minecraft/world/level/block/state/BlockState;I)V",
					BLOCK_STATE, "isAir", "isEmpty", "()Z", WHY));

	/**
	 * One call the surviving carrier SUBSTITUTED for another inside a method whose body is otherwise the one the
	 * listed ecosystems' mods were compiled against: same instructions, same local variable table, and at exactly one
	 * instruction {@code member} there, {@code replacement} here, taking the same arguments. The callee's owner, name
	 * and return type may all differ, which is why {@link Swap}'s rule (the handler's shape is the callee's, identical
	 * on both sides) cannot cover it; what does not change is the program point, and an {@code @Inject} is bound to
	 * nothing else. {@code MergedBaseCalleeSwapTest} proves every row against the reference jars instruction by
	 * instruction, so a base or carrier rebuild that changes anything but that one call turns the row red.
	 *
	 * @param target      the class the mixin targets (internal name)
	 * @param method      the target method, {@code name + descriptor}
	 * @param member      the call the listed ecosystems' own jars make there, as an {@code @At} target
	 * @param replacement the call the merged body makes at the same instruction instead, as an {@code @At} target
	 * @param ecosystems  the mods compiled against {@code member} in this method
	 * @param because     why the point before and after {@code replacement} means what it meant around {@code member}
	 */
	public record Substitution(String target, String method, String member, String replacement, Set<Ecosystem> ecosystems,
			String because) {
	}

	public static final List<Substitution> SUBSTITUTED = List.of(
			new Substitution("net/minecraft/client/resources/model/ModelManager",
					"lambda$loadBlockModels$2(Ljava/util/Map$Entry;)Lcom/mojang/datafixers/util/Pair;",
					"Lnet/minecraft/client/resources/model/cuboid/CuboidModel;fromStream(Ljava/io/Reader;)"
							+ "Lnet/minecraft/client/resources/model/cuboid/CuboidModel;",
					"Lnet/neoforged/neoforge/client/model/UnbakedModelParser;parse(Ljava/io/Reader;)"
							+ "Lnet/minecraft/client/resources/model/UnbakedModel;",
					Set.of(Ecosystem.FABRIC, Ecosystem.FORGE),
					"both calls turn the model file's Reader into the model, on the thread that loads it, and that is the "
							+ "whole of what the lambda does with them; NeoForge's parse reads it through CuboidModel.GSON, "
							+ "whose UnbakedModel adapter is NeoForge's loader dispatch, and a model it does not own reaches "
							+ "the same vanilla CuboidModel$Deserializer fromStream used (ModelFormatFunnelInjector). So "
							+ "BEFORE the call is still 'this model's file is about to be parsed': fusion (MinecraftForge) "
							+ "stores the model's id there, and its hook in that deserializer reads it back to name every "
							+ "connected-texture model it builds"));

	private MergedBaseCalleeSwaps() {
	}

	/**
	 * The {@link #SUBSTITUTED} row for {@code anchor} (as the mod wrote it) in {@code target#method}, for a mod of
	 * {@code ecosystem}; null when none. A mod of an ecosystem the row does not list was compiled against the
	 * replacement, or against neither, and its anchor missing is what it would do natively.
	 */
	public static Substitution substitution(String target, String method, String anchor, Ecosystem ecosystem) {
		if (ecosystem == null) return null;
		MixinFit.Member want = MixinFit.parseMember(anchor);
		if (want == null) return null;
		for (Substitution row : SUBSTITUTED) {
			if (!row.target().equals(target) || !row.method().equals(method) || !row.ecosystems().contains(ecosystem)) continue;
			MixinFit.Member have = MixinFit.parseMember(row.member());
			if (want.name().equals(have.name()) && (want.owner() == null || want.owner().equals(have.owner()))
					&& (want.desc() == null || want.desc().equals(have.desc()))) return row;
		}
		return null;
	}

	/** The row for a miss of {@code owner.vanillaName desc} inside {@code target.method}, or null. */
	public static Swap find(String target, String method, String owner, String vanillaName, String desc) {
		for (Swap swap : KNOWN) {
			if (swap.target().equals(target) && swap.method().equals(method) && swap.owner().equals(owner)
					&& swap.vanillaName().equals(vanillaName) && swap.desc().equals(desc)) {
				return swap;
			}
		}
		return null;
	}
}
