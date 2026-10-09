package fixture.stubcapture;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

/** Puts an oversized stack through vanilla's setItem and reports what the capture saw and what the container did. */
public class Probe {
	public String probe() {
		SimpleContainer container = new SimpleContainer();
		container.setItem(1, new ItemStack("apple", 70));
		return "seen=" + History.SEEN + " trace=" + container.trace;
	}
}
