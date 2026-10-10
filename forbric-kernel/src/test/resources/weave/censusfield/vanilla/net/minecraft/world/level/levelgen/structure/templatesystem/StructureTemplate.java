package net.minecraft.world.level.levelgen.structure.templatesystem;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * A stand-in for vanilla's StructureTemplate, the class a mod is compiled against: placeInWorld calls placeEntities,
 * which adds each entity and writes the template's running count.
 */
public class StructureTemplate {
	private final List<StructureEntityInfo> entityInfoList;
	public int added;

	public StructureTemplate(List<StructureEntityInfo> entityInfoList) {
		this.entityInfoList = entityInfoList;
	}

	public boolean placeInWorld(ServerLevelAccessor level, BlockPos pos, BlockPos referencePos, StructurePlaceSettings settings,
			RandomSource random, int flags) {
		placeEntities(level, pos, settings.getMirror(), settings.getRotation(), settings.getRotationPivot(), settings.getBoundingBox(),
				settings.shouldFinalizeEntities(), new ProblemReporter());
		return true;
	}

	private void placeEntities(ServerLevelAccessor level, BlockPos pos, Mirror mirror, Rotation rotation, BlockPos pivot, BoundingBox box,
			boolean finalizeEntities, ProblemReporter reporter) {
		for (StructureEntityInfo info : entityInfoList) {
			level.addFreshEntity(info.name());
			this.added = this.added + 1;
		}
	}

	public record StructureEntityInfo(String name) {
	}
}
