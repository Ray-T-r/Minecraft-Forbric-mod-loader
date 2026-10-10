package net.minecraft.world.level.levelgen.structure.templatesystem;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ServerLevelAccessor;

/**
 * A stand-in for the merged base's StructureTemplate: NeoForge's placeInWorld hands the entities to
 * addEntitiesToWorld(level, pos, settings, reporter), which adds each and writes the template's running count. Vanilla's
 * placeEntities is not here at all.
 */
public class StructureTemplate {
	private final List<StructureEntityInfo> entityInfoList;
	public int added;

	public StructureTemplate(List<StructureEntityInfo> entityInfoList) {
		this.entityInfoList = entityInfoList;
	}

	public boolean placeInWorld(ServerLevelAccessor level, BlockPos pos, BlockPos referencePos, StructurePlaceSettings settings,
			RandomSource random, int flags) {
		addEntitiesToWorld(level, pos, settings, new ProblemReporter());
		return true;
	}

	private void addEntitiesToWorld(ServerLevelAccessor level, BlockPos pos, StructurePlaceSettings settings, ProblemReporter reporter) {
		for (StructureEntityInfo info : entityInfoList) {
			level.addFreshEntity(info.name());
			this.added = this.added + 1;
		}
	}

	public record StructureEntityInfo(String name) {
	}
}
