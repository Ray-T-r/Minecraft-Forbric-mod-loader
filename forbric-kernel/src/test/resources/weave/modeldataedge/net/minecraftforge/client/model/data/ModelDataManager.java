package net.minecraftforge.client.model.data;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;

/** Stand-in: the block entities' model data a level keeps, by position. */
public class ModelDataManager {
	public final Map<BlockPos, ModelData> byPos = new HashMap<>();

	public ModelData getAtOrEmpty(BlockPos pos) {
		return byPos.getOrDefault(pos, ModelData.EMPTY);
	}
}
