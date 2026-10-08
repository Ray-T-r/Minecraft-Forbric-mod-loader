package net.minecraft.client.gui.screens.inventory;

/**
 * Hand-written stand-in, not game code: the merged creative screen after {@code CreativePagerBridgeInjector}. Its page
 * state is NeoForge's, the one the screen draws, and the bridge has given it Fabric's two pager calls, answered from
 * that state.
 */
public class CreativeModeInventoryScreen {
	private static final int PAGES = 3;
	private int currentPage;

	public boolean keyPressed(int key) {
		return false;
	}

	public boolean switchToPreviousPage() {
		if (currentPage == 0) return false;
		currentPage--;
		return true;
	}

	public boolean switchToNextPage() {
		if (currentPage + 1 >= PAGES) return false;
		currentPage++;
		return true;
	}

    public int getCurrentPage(){return currentPage;}
    public boolean switchToPage(int page){if(page<0||page>=PAGES)return false;currentPage=page;return true;}

	/** The page the screen draws. */
	public int drawnPage() {
		return currentPage;
	}
}
