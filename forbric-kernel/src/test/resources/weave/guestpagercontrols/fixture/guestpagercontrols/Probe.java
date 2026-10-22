package fixture.guestpagercontrols;

import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;

/** Opens the screen, reads which page buttons it was given, presses the guest's next button, reads the drawn page. */
public class Probe {
	public String run() throws Exception {
		CreativeModeInventoryScreen screen = new CreativeModeInventoryScreen();
		screen.open();
		String widgets = String.join(",", screen.widgets);
		int before = screen.drawnPage();
		boolean key = screen.keyPressed(267);
		return "widgets=" + widgets + " drawn=" + before + " key=" + key + " drawn=" + screen.drawnPage();
	}
}
