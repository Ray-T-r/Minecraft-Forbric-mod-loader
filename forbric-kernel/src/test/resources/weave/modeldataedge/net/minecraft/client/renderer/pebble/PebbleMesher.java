package net.minecraft.client.renderer.pebble;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;

/**
 * A mesher with the merged shape and names unrelated to the game's: the vanilla signature carries one family's body
 * (the contextual question), and the carrier's model-data overload beside it carries the other's, called by nothing.
 */
public class PebbleMesher {
	private final List<BlockStateModelPart> parts = new ArrayList<>();
	private final RandomSource random = seed -> { };

	public void mesh(List<String> out, float shade, BlockAndTintGetter level, BlockPos pos, BlockState state, BlockStateModel model,
			long seed) {
		random.setSeed(seed);
		model.collectParts(level, pos, state, random, parts);
		for (BlockStateModelPart part : parts) out.add("contextual:" + part.describe());
		parts.clear();
	}

	public void mesh(List<String> out, float shade, BlockAndTintGetter level, BlockPos pos, BlockState state, BlockStateModel model,
			long seed, ModelData data) {
		random.setSeed(seed);
		model.collectParts(random, parts, data);
		for (BlockStateModelPart part : parts) out.add("with-data:" + part.describe());
		parts.clear();
	}
}
