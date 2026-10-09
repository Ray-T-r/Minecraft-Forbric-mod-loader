package fixture.settail;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;

/** The mod's settings: positions placed without neighbour updates, and whether unticked chunks still get updates. */
public final class Quiet {
	public static final Set<BlockPos> POSITIONS = new HashSet<>();
	public static boolean EVERYWHERE = true;

	private Quiet() {
	}
}
