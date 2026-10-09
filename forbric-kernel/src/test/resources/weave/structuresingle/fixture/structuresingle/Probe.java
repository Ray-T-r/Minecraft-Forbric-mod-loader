package fixture.structuresingle;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureEntityInfo;

/** Places a template holding a pig and a cow: what the level was given. */
public class Probe {
	public String probe() {
		StructureTemplate template = new StructureTemplate(List.of(new StructureEntityInfo("pig"), new StructureEntityInfo("cow")));
		Level level = new Level();
		template.placeInWorld(level, new BlockPos(), new BlockPos(), new StructurePlaceSettings(List.of()), new RandomSource(), 2);
		return "placed " + String.join(", ", level.entities);
	}
}
