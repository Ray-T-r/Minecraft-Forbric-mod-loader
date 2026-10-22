package net.minecraft.client.gui.screens.inventory;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.gui.CreativeTabsScreenPage;

/**
 * Hand-written stand-in, not game code: the merged creative screen after the pager bridge, with the carrier's own page
 * controls in the shapes javac gives them — under {@code pages.size() > 1} two page buttons whose press handlers set the
 * page, under {@code pages.size() != 1} the "N / M" label — and Fabric's pager calls answered from the carrier's page.
 */
public class CreativeModeInventoryScreen {
	private final List<CreativeTabsScreenPage> pages = new ArrayList<>(List.of(new CreativeTabsScreenPage("one"),
			new CreativeTabsScreenPage("two"), new CreativeTabsScreenPage("three")));
	private CreativeTabsScreenPage currentPage = pages.get(0);
	public final List<String> widgets = new ArrayList<>();
	private final Font font = new Font();
	private int leftPos = 100, topPos = 50, imageWidth = 195;

	protected void init() {
		widgets.clear();
		if (pages.size() > 1) {
			addRenderableWidget(Button.builder(Component.literal("<"), button -> setCurrentPage(pages.get(Math.max(pages.indexOf(currentPage) - 1, 0))))
					.pos(leftPos, topPos - 50).size(20, 20).build());
			addRenderableWidget(Button.builder(Component.literal(">"), button -> setCurrentPage(pages.get(Math.min(pages.indexOf(currentPage) + 1, pages.size() - 1))))
					.pos(leftPos + imageWidth - 20, topPos - 50).size(20, 20).build());
		}
	}

	public void open() {
		init();
	}

	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
		if (pages.size() != 1) {
			Component page = Component.literal(String.format("%d / %d", pages.indexOf(currentPage) + 1, pages.size()));
			graphics.text(font, page.getVisualOrderText(), leftPos + imageWidth / 2 - font.width(page) / 2, topPos - 44, -1);
		}
	}

	protected Object addRenderableWidget(Object widget) {
		widgets.add(((Button) widget).label);
		return widget;
	}

	public boolean keyPressed(int key) {
		return false;
	}

	public void setCurrentPage(CreativeTabsScreenPage page) {
		currentPage = page;
	}

	// What the bridge gives the screen: Fabric's calls, answered from the carrier's page.
	public int getCurrentPage() {
		return pages.indexOf(currentPage);
	}

	public boolean switchToPage(int page) {
		if (page < 0 || page >= pages.size() || pages.get(page) == currentPage) return false;
		currentPage = pages.get(page);
		return true;
	}

	/** The page the screen draws. */
	public int drawnPage() {
		return pages.indexOf(currentPage);
	}
}
