/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.util.List;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.client.model.data.ModelDataManager;
import net.forbric.kernel.util.Caught;

/**
 * MinecraftForge's model-data contract, asked where the merged game asks NeoForge's contextual model questions
 * ({@code ForgeModelDataFlowTransformer}).
 *
 * <p>MinecraftForge hands a block model its context through model data: the section compiler takes the block entity's
 * data from the level's {@code ModelDataManager}, lets the model add its own with
 * {@code getModelData(level, pos, state, data)} — a connected-texture model reads its neighbours there — and collects
 * the parts with {@code collectParts(random, parts, data)}. NeoForge asks one contextual question instead,
 * {@code collectParts(level, pos, state, random, parts)}, and the merged section compiler, renderers and particle code
 * are NeoForge's. A MinecraftForge model answers only MinecraftForge's questions, so on the merged base it drew without
 * context: fusion's connected textures never connected.
 *
 * <p>Every answer here reduces to NeoForge's for a model that does not take MinecraftForge's model data: MinecraftForge's
 * defaults pass the data through ({@code getModelData}) and drop it ({@code collectParts}, {@code particleMaterial}),
 * so such a model is asked exactly what NeoForge asks it. The block entity's data is looked up only for a model whose
 * class takes part in the contract, which keeps the per-block cost of vanilla models what it was.
 */
public final class KernelForgeModelData {
	/**
	 * Whether a model class takes part in MinecraftForge's model-data contract: some type of it other than the game's own
	 * {@code BlockStateModel} and MinecraftForge's extension (whose defaults pass the data through or drop it) declares
	 * {@code getModelData}, {@code collectParts} or {@code particleMaterial} taking model data — a class, a superclass, a
	 * mod's own interface default, or a method a mixin added.
	 */
	private static final ClassValue<Boolean> TAKES_MODEL_DATA = new ClassValue<>() {
		@Override protected Boolean computeValue(Class<?> type) {
			java.util.ArrayDeque<Class<?>> pending = new java.util.ArrayDeque<>(List.of(type));
			java.util.Set<Class<?>> seen = new java.util.HashSet<>();
			while (!pending.isEmpty()) {
				Class<?> c = pending.pop();
				if (c == null || c == Object.class || !seen.add(c)) continue;
				if (c != BlockStateModel.class && !c.getName().equals("net.minecraftforge.client.extensions.IForgeBlockStateModel")) {
					java.lang.reflect.Method[] declared;
					try {
						declared = c.getDeclaredMethods();
					} catch (LinkageError unresolvable) {
						Caught.expected(unresolvable);
						return true;    // a signature names a class that is not there: ask it the full contract, as Forge would
					}
					for (var method : declared) {
						Class<?>[] params = method.getParameterTypes();
						if (params.length == 0 || params[params.length - 1] != ModelData.class) continue;
						if (method.getName().equals("getModelData") || method.getName().equals("collectParts")
								|| method.getName().equals("particleMaterial")) return true;
					}
				}
				pending.push(c.getSuperclass() == null ? Object.class : c.getSuperclass());
				for (Class<?> i : c.getInterfaces()) pending.push(i);
			}
			return false;
		}
	};

	private KernelForgeModelData() {
	}

	/**
	 * The block entity's model data at {@code pos}, as MinecraftForge's {@code ModelDataManager} of {@code level} tracks
	 * it; {@code ModelData.EMPTY} where the level has no manager or nothing is tracked there.
	 */
	public static ModelData at(BlockAndTintGetter level, BlockPos pos) {
		if (level == null || pos == null) return ModelData.EMPTY;
		ModelDataManager manager = level.getModelDataManager();
		return manager == null ? ModelData.EMPTY : manager.getAtOrEmpty(pos);
	}

	/** What MinecraftForge's section compiler hands the block's tessellation: the model's data, from the block entity's. */
	public static ModelData of(BlockStateModel model, BlockAndTintGetter level, BlockPos pos, BlockState state) {
		return model.getModelData(level, pos, state, at(level, pos));
	}

	/** NeoForge's contextual {@code collectParts}, answered through MinecraftForge's model-data contract. */
	public static void collectParts(BlockStateModel model, BlockAndTintGetter level, BlockPos pos, BlockState state,
			RandomSource random, List<BlockStateModelPart> parts) {
		ModelData data = TAKES_MODEL_DATA.get(model.getClass()) ? of(model, level, pos, state) : ModelData.EMPTY;
		model.collectParts(random, parts, data);
	}
}
