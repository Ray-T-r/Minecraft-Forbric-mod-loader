package net.neoforged.neoforge.client.extensions;

import java.util.List;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/** Stand-in for NeoForge's extension: its contextual questions, whose defaults drop the context. */
public interface BlockStateModelExtension {
	private BlockStateModel self() {
		return (BlockStateModel) this;
	}

	default void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, List<BlockStateModelPart> parts) {
		self().collectParts(random, parts);
	}

	default Material.Baked particleMaterial(BlockAndTintGetter level, BlockPos pos, BlockState state) {
		return self().particleMaterial();
	}
}
