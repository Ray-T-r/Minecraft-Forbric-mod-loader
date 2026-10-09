package fixture.breathingsingle;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import org.example.scuba.Gills;

/** A swimmer ticks three times under water: the air it has left, and what the mod was asked, in order. */
public class Probe {
	public String probe() {
		LivingEntity swimmer = new Swimmer(new ServerLevel(), "water");
		for (int tick = 0; tick < 3; tick++) swimmer.baseTick();
		return "air " + swimmer.getAirSupply() + " | asked " + (Gills.ASKED.isEmpty() ? "nothing" : String.join(", ", Gills.ASKED));
	}
}
