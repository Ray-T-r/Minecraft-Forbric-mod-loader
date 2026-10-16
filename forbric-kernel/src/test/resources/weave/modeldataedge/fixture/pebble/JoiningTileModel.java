package fixture.pebble;

import java.util.List;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.client.model.data.ModelProperty;

/**
 * A connected-texture model written for MinecraftForge only: it reads its neighbours in getModelData and draws from
 * the data in collectParts; the block entity's tint arrives in the data the level keeps. It never answers the
 * contextual question.
 */
public class JoiningTileModel implements BlockStateModel {
	public static final ModelProperty<Integer> JOINED = new ModelProperty<>();
	public static final ModelProperty<String> TINT = new ModelProperty<>();

	@Override public void collectParts(RandomSource random, List<BlockStateModelPart> parts) {
		parts.add(() -> "tile alone");
	}

	@Override public Material.Baked particleMaterial() {
		return new Material.Baked("tile plain");
	}

	@Override public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData data) {
		int joined = 0;
		for (BlockPos next : pos.neighbours()) {
			BlockState there = level.getBlockState(next);
			if (there != null && there.name().equals(state.name())) joined++;
		}
		return data.with(JOINED, joined);
	}

	@Override public void collectParts(RandomSource random, List<BlockStateModelPart> parts, ModelData data) {
		Integer joined = data.get(JOINED);
		String tint = data.get(TINT);
		parts.add(() -> (joined == null ? "tile alone" : "tile joined " + joined) + (tint == null ? "" : " " + tint));
	}

	@Override public Material.Baked particleMaterial(ModelData data) {
		String tint = data.get(TINT);
		return new Material.Baked(tint == null ? "tile plain" : "tile " + tint);
	}
}
