package fixture.pebble;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.pebble.PebbleMesher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.client.model.data.ModelDataManager;

/**
 * Meshes four blocks through the vanilla signature, as the merged game does, then one through the overload, as a mod
 * may: a tile with two tile neighbours and a block entity's tint, a block the guest simulates, a plain block.
 */
public class Probe {
	public String mesh() {
		BlockState tile = new BlockState("tile"), simulated = new BlockState("simulated"), stone = new BlockState("stone");
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		BlockPos at = new BlockPos(0, 0, 0), sim = new BlockPos(5, 0, 0), plain = new BlockPos(9, 0, 0);
		blocks.put(at, tile);
		blocks.put(new BlockPos(1, 0, 0), tile);
		blocks.put(new BlockPos(0, 1, 0), tile);
		blocks.put(sim, simulated);
		blocks.put(plain, stone);
		ModelDataManager manager = new ModelDataManager();
		manager.byPos.put(at, ModelData.EMPTY.with(JoiningTileModel.TINT, "red"));
		BlockAndTintGetter level = new BlockAndTintGetter() {
			@Override public BlockState getBlockState(BlockPos pos) {
				return blocks.get(pos);
			}

			@Override public ModelDataManager getModelDataManager() {
				return manager;
			}
		};
		PebbleMesher mesher = new PebbleMesher();
		List<String> drawn = new ArrayList<>();
		mesher.mesh(drawn, 1f, level, at, tile, new JoiningTileModel(), 7L);
		mesher.mesh(drawn, 1f, level, sim, simulated, new JoiningTileModel(), 7L);
		mesher.mesh(drawn, 1f, level, plain, stone, new PlainModel(), 7L);
		mesher.mesh(drawn, 1f, level, at, tile, new JoiningTileModel(), 7L, ModelData.EMPTY);
		String particle = new JoiningTileModel().particleMaterial(level, at, tile).name;
		return String.join(" | ", drawn) + " || particle " + particle;
	}
}
