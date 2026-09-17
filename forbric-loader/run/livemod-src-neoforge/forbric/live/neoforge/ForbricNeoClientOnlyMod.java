package forbric.live.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/**
 * A NeoForge {@code @Mod} that declares it belongs to the CLIENT only.
 *
 * <p>Its whole job is to be constructed on one side and not the other. The kernel used to ignore the annotation's
 * {@code dist} and construct every {@code @Mod} on every side; Sodium's client-only entry point is the real case,
 * and on a dedicated server the first client type its constructor touches throws with the mod's name on it.
 */
@Mod(value = "forbricneoclientonly", dist = {Dist.CLIENT})
public class ForbricNeoClientOnlyMod {
	public ForbricNeoClientOnlyMod(IEventBus modBus) {
		System.out.println("[ForbricNeoClientOnly] client-only @Mod CONSTRUCTED");
	}
}
