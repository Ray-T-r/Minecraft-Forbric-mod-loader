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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;

/**
 * Lets a MinecraftForge fluid supply its own render model and tint from the merged {@code FluidRenderer.tesselate}.
 *
 * <p>Vanilla 26.2's {@code FluidStateModelSet.bake()} hard-codes water and lava and answers the missing model for
 * anything else. Genuine Forge's only seam is inside {@code tesselate}: after the model lookup it asks
 * {@code IClientFluidTypeExtensions.of(fluidState).getModel(state, level, pos, model)}, and where the model has
 * no tint source it asks {@code getTintColor()} instead of {@code -1}. The byte merge kept NeoForge's tesselate,
 * which has neither ask, so every MinecraftForge modded fluid drew as the missing texture. The transformer
 * re-inserts both asks as calls here (six instructions after the model's {@code ASTORE}, and the {@code ICONST_M1}
 * replaced by a call pushing an int) — stack-shape identical to Forge's own.
 *
 * <p>Hot path: this runs once per fluid tesselation, the same cost genuine Forge pays. The count line is gated by
 * a contains-check before an add, and only the first sighting of a fluid logs. Forge's {@code DEFAULT} extension
 * (vanilla fluids, and any Forge fluid whose {@code initClient} never ran) short-circuits to the model by identity
 * and {@code -1}, so vanilla rendering is byte-for-byte what it was. {@code -Dforbric.forgeFluidModels=off}
 * returns the model by identity and {@code -1} at both sites.
 */
public final class KernelForgeFluids {
	public static final String PROPERTY = "forbric.forgeFluidModels";
	private static final Set<Fluid> ASKED = ConcurrentHashMap.newKeySet();
	private static final Set<Fluid> ANSWERED = ConcurrentHashMap.newKeySet();
	private static volatile boolean failureReported;

	private KernelForgeFluids() {
	}

	/** Site A: the model NeoForge's set chose, or what the fluid's own client extensions supply instead. */
	public static FluidModel model(FluidModel model, FluidState state, BlockAndTintGetter level, BlockPos pos) {
		if ("off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"))) return model;
		try {
			IClientFluidTypeExtensions extensions = IClientFluidTypeExtensions.of(state);
			Fluid fluid = state.getType();
			// Counted before the DEFAULT short-circuit, so a vanilla fluid in view proves the funnel is on the render
			// path even when no Forge fluid exists to answer; the contains-check keeps the hot path cheap.
			boolean first = !ASKED.contains(fluid) && ASKED.add(fluid);
			if (extensions == IClientFluidTypeExtensions.DEFAULT) {
				if (first) report(fluid);
				return model;
			}
			FluidModel own = extensions.getModel(state, level, pos, model);
			if (first) {
				if (own != null && own != model) ANSWERED.add(fluid);
				report(fluid);
			}
			return own == null ? model : own;
		} catch (Throwable t) {
			if (!failureReported) {
				failureReported = true;
				ForbricLog.warn("[Forbric/Fluids] MinecraftForge fluid extensions threw while choosing a model — "
						+ "NeoForge's model is used", Reflect.unwrap(t));
			}
			return model;
		}
	}

	private static void report(Fluid fluid) {
		ForbricLog.info("[Forbric/Fluids] MinecraftForge client extensions consulted for %d fluid(s) so far, %d "
				+ "supplied their own model (%s)", ASKED.size(), ANSWERED.size(), fluid);
	}

	/** Site B: the tint for a model without a tint source — Forge's extension answer, or vanilla's -1. */
	public static int tintColor(FluidState state) {
		if ("off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"))) return -1;
		try {
			return IClientFluidTypeExtensions.of(state).getTintColor();
		} catch (Throwable t) {
			return -1;
		}
	}
}
