package fixture.privatedefault;

import net.minecraft.world.entity.animal.axolotl.Axolotl;
import net.neoforged.neoforge.common.extensions.ILivingEntityExtension;

/**
 * Ticks an axolotl (the guest's handler, which calls its own self()) and asks it NeoForge's interface default that reaches
 * the entity through the interface's self(): what each saw, or the error the interface call raised.
 */
public class Probe {
	public String probe() {
		Axolotl axolotl = new Axolotl();
		axolotl.tick();
		String tick = Trail.take();
		String neoforge;
		try {
			neoforge = ((ILivingEntityExtension) axolotl).describe();
		} catch (AbstractMethodError hidden) {
			neoforge = "AbstractMethodError";
		}
		return "tick=" + tick + " neoforge=" + neoforge;
	}
}
