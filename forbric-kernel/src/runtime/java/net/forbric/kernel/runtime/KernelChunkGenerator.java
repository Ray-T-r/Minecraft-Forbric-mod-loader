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

package net.forbric.kernel.runtime;

import java.util.function.Supplier;

import net.minecraftforge.common.util.ClearableLazy;

import net.forbric.kernel.util.ForbricLog;

/**
 * The invalidation half of {@code ChunkGenerator.featuresPerStep} once the field carries VANILLA's descriptor again.
 *
 * <p>MinecraftForge re-types that vanilla field from {@code Supplier} to its own {@code ClearableLazy} so that
 * {@code refreshFeaturesPerStep()} has something to invalidate; the byte merge keeps only MinecraftForge's
 * declaration, and vanilla's descriptor stops existing. That is not a dormant difference: fabric-api's
 * {@code BiomeModificationImpl.finalizeWorldGen} writes the field DIRECTLY — an access-widened
 * {@code putfield featuresPerStep : Ljava/util/function/Supplier;} — so it throws {@code NoSuchFieldError} and the
 * SERVER DOES NOT START as soon as any Fabric biome modification applies. balm is enough.
 *
 * <p>{@link net.forbric.kernel.transform.ForbricMergedBaseCompatTransformer} therefore gives the field vanilla's
 * descriptor back. That is sound for every reader — {@code ClearableLazy extends Lazy extends Supplier}, so the
 * value MinecraftForge's constructor stores still satisfies it — but it breaks the one WRITER-shaped use:
 * {@code refreshFeaturesPerStep()} called {@code ClearableLazy.invalidate()} on it, and after fabric-api has
 * replaced the value with a plain memoized {@code Supplier} that call has nothing to invalidate. A bare
 * {@code CHECKCAST} there would turn a Fabric biome modification into a {@code ClassCastException} in worldgen.
 *
 * <p>So the invalidation goes through here instead: invalidate when the value still is MinecraftForge's lazy,
 * and otherwise say so once. Saying so is the point — "a Fabric modification replaced the supplier" is exactly
 * the state in which MinecraftForge's refresh silently stops working, and it is invisible from either side.
 */
public final class KernelChunkGenerator {

	private static volatile boolean reportedForeignSupplier;

	private KernelChunkGenerator() {
	}

	/**
	 * {@code ChunkGenerator.refreshFeaturesPerStep()}'s body, after the field is vanilla-typed again.
	 *
	 * <p>Null-tolerant on purpose: the field is final and written in every constructor, but this runs on a merged
	 * base that another pass may yet change, and an NPE here would surface as a worldgen failure with no hint of
	 * its cause.
	 */
	public static void invalidate(Supplier<?> featuresPerStep) {
		if (featuresPerStep instanceof ClearableLazy<?> lazy) {
			lazy.invalidate();
			return;
		}
		if (featuresPerStep != null && !reportedForeignSupplier) {
			reportedForeignSupplier = true;
			ForbricLog.info("[Forbric/Worldgen] ChunkGenerator.featuresPerStep now holds %s, not MinecraftForge's "
					+ "ClearableLazy — a Fabric biome modification has replaced it, which is what that API does. "
					+ "The list is recomputed by whoever replaced it; MinecraftForge's refreshFeaturesPerStep() has "
					+ "nothing left to invalidate and is a no-op from here",
					featuresPerStep.getClass().getName());
		}
	}
}
