package fixture.breathingsingle;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

/** An entity the mod gave gills. */
public class Swimmer extends LivingEntity {
	public Swimmer(Level level, String fluid) {
		super(level, fluid);
	}
}
