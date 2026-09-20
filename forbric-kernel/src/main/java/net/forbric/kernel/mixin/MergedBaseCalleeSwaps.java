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

/**
 * Descriptor-identical callee swaps the merge made inside vanilla method bodies, and the few a guest mixin's
 * {@code @At(INVOKE)} may follow.
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

	private MergedBaseCalleeSwaps() {
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
