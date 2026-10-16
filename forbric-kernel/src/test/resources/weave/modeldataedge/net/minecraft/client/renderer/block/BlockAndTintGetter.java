package net.minecraft.client.renderer.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelDataManager;

/** Stand-in level view; MinecraftForge's interface default answers no manager, as on the game. */
public interface BlockAndTintGetter {
	BlockState getBlockState(BlockPos pos);

	default ModelDataManager getModelDataManager() {
		return null;
	}
}
