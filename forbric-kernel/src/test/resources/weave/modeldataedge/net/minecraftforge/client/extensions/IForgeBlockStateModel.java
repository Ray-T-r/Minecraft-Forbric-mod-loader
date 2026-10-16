package net.minecraftforge.client.extensions;

import java.util.List;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;

/** Stand-in for MinecraftForge's extension: its model-data contract, whose defaults pass the data through or drop it. */
public interface IForgeBlockStateModel {
	private BlockStateModel self() {
		return (BlockStateModel) this;
	}

	default ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData data) {
		return data;
	}

	default Material.Baked particleMaterial(ModelData data) {
		return self().particleMaterial();
	}

	default void collectParts(RandomSource random, List<BlockStateModelPart> parts, ModelData data) {
		self().collectParts(random, parts);
	}
}
