/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;

/**
 * The Mods screen for an instance that runs three loaders.
 *
 * <p>It replaces NeoForge's, which is reached from the pause menu's mods button. NeoForge's screen is not broken —
 * it lists {@code ModList.get()}, which is every mod NeoForge loaded, and that is the right answer on a NeoForge
 * instance. Here it is three mods out of sixteen, and the same is true of every other family's screen: each is
 * complete for its own loader and none of them can answer for the instance. So the button now opens the one list
 * that can, {@link ModCatalog}.
 *
 * <p>Drawn rather than composed out of a vanilla layout on purpose: what a player needs from this screen is which
 * mods are here and which loader each came from, and the second half has no vanilla widget because no vanilla
 * screen has ever had to say it. Each row carries its ecosystem as a coloured tag.
 */
public final class KernelModListScreen extends Screen {
	private static final int TAG_FABRIC = 0xFFDBB03B;
	private static final int TAG_NEOFORGE = 0xFFE5834C;
	private static final int TAG_FORGE = 0xFF8B9BC4;
	private static final int DIM = 0xFFA0A0A0;
	private static final int BRIGHT = 0xFFFFFFFF;
	/** Panel wash, so mod names stay legible over whatever the world happens to look like behind them. */
	private static final int PANEL = 0xC0000000;
	private static final int SELECTED = 0x60FFFFFF;
	private static final int PAD = 6;
	private static final int SEARCH_Y = 32;
	private static final int LIST_TOP = 54;

	private final Screen parent;
	private ModList list;
	private EditBox search;

	/**
	 * Frames actually drawn, and rows actually built — read by the client smoke harness.
	 *
	 * <p>A screen is the one thing in this repo that no unit test can prove works: its init and its draw run only
	 * when a player clicks, and a mistake in either is a crash in the middle of a frame. The harness opens it on a
	 * real client, holds it, and reads these two numbers back, so "it renders" is a measurement rather than a
	 * hope. A frame count of zero with a live screen means it was constructed and never drawn.
	 */
	private static volatile int framesDrawn;
	private static volatile int rowsBuilt;

	/** Frames this screen has drawn since the process started. */
	public static int framesDrawn() {
		return framesDrawn;
	}

	/** Rows the last build produced. */
	public static int rowsBuilt() {
		return rowsBuilt;
	}

	public KernelModListScreen(Screen parent) {
		super(Component.literal("Mods"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int listWidth = Math.max(150, Math.min(280, this.width / 3));
		this.search = new EditBox(this.font, PAD, SEARCH_Y, listWidth - PAD, 18, Component.literal("Search"));
		this.search.setHint(Component.literal("Search"));
		this.search.setResponder(this::rebuild);
		addRenderableWidget(this.search);

		this.list = new ModList(this.minecraft, listWidth, this.height - LIST_TOP - 34, LIST_TOP, 26);
		this.list.setX(0);
		addRenderableWidget(this.list);

		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds(this.width / 2 - 100, this.height - 26, 200, 20).build());

		rebuild(this.search.getValue());
	}

	private void rebuild(String query) {
		if (this.list == null) return;
		String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
		List<Row> rows = new ArrayList<>();
		for (ModCatalog.Entry e : ModCatalog.all()) {
			if (!needle.isEmpty() && !matches(e, needle)) continue;
			rows.add(new Row(e));
		}
		this.list.replaceEntries(rows);
		rowsBuilt = rows.size();
		if (!rows.isEmpty() && this.list.getSelected() == null) this.list.setSelected(rows.get(0));
	}

	private static boolean matches(ModCatalog.Entry e, String needle) {
		return e.name().toLowerCase(Locale.ROOT).contains(needle)
				|| e.modId().toLowerCase(Locale.ROOT).contains(needle)
				|| label(e.ecosystem()).toLowerCase(Locale.ROOT).contains(needle);
	}

	/**
	 * The two panels, drawn BEFORE the widgets.
	 *
	 * <p>{@code extractRenderState} runs after the widget pass, so a wash painted there would cover the list it is
	 * supposed to sit behind. This screen is opened over a live world and the mod names are the whole point of it.
	 */
	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(g, mouseX, mouseY, partialTick);
		if (this.list == null) return;
		g.fill(0, 0, this.list.getWidth(), this.height, PANEL);
		g.fill(this.list.getWidth() + 1, 0, this.width, this.height, PANEL);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		framesDrawn++;
		g.text(this.font, this.title.copy().append(Component.literal(" (" + ModCatalog.all().size() + ")")),
				PAD, 8, BRIGHT);
		g.text(this.font, Component.literal(summary()), PAD, 20, DIM);
		ModCatalog.Entry selected = this.list == null || this.list.getSelected() == null
				? null : this.list.getSelected().entry;
		if (selected != null) detail(g, selected);
	}

	/** The right-hand pane. Word-wrapped so a long description does not run off the edge of the screen. */
	private void detail(GuiGraphicsExtractor g, ModCatalog.Entry e) {
		int x = this.list.getWidth() + 12;
		int wrap = Math.max(80, this.width - x - 10);
		// Below the header band, not level with it: the counts line is as wide as it needs to be and runs past
		// the list column, and the first measurement of this screen had it drawn straight through the mod's name.
		int y = LIST_TOP - 8;
		g.text(this.font, Component.literal(e.name()), x, y, BRIGHT);
		y += 12;
		g.text(this.font, Component.literal(e.modId() + (e.version().isEmpty() ? "" : "  " + e.version())),
				x, y, DIM);
		y += 12;
		g.text(this.font, Component.literal(label(e.ecosystem())), x, y, tag(e.ecosystem()));
		y += 14;
		if (!e.authors().isEmpty()) {
			g.text(this.font, Component.literal("by " + String.join(", ", e.authors())), x, y, DIM);
			y += 12;
		}
		if (!e.jar().isEmpty()) {
			g.text(this.font, Component.literal(e.jar()), x, y, DIM);
			y += 14;
		}
		if (!e.description().isEmpty()) {
			g.textWithWordWrap(this.font, FormattedText.of(e.description()), x, y, wrap, BRIGHT);
		}
	}

	private static String summary() {
		return ModCatalog.count(Ecosystem.FABRIC) + " Fabric   "
				+ ModCatalog.count(Ecosystem.NEOFORGE) + " NeoForge   "
				+ ModCatalog.count(Ecosystem.FORGE) + " MinecraftForge";
	}

	private static String label(Ecosystem ecosystem) {
		return switch (ecosystem) {
			case FABRIC -> "Fabric";
			case NEOFORGE -> "NeoForge";
			case FORGE -> "MinecraftForge";
		};
	}

	private static int tag(Ecosystem ecosystem) {
		return switch (ecosystem) {
			case FABRIC -> TAG_FABRIC;
			case NEOFORGE -> TAG_NEOFORGE;
			case FORGE -> TAG_FORGE;
		};
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}

	/** The scrolling list. Vanilla's widget, so scrolling, selection and keyboard navigation are not reinvented. */
	private static final class ModList extends ObjectSelectionList<Row> {
		ModList(Minecraft minecraft, int width, int height, int y, int entryHeight) {
			super(minecraft, width, height, y, entryHeight);
		}

		/**
		 * Vanilla centres a fixed-width row inside the list, which for a narrow column puts the row's left edge
		 * at a NEGATIVE x — the first measurement of this screen showed every mod name with its first few
		 * characters cut off the left of the window. The rows are this column.
		 */
		@Override
		public int getRowWidth() {
			return getWidth() - PAD * 2;
		}
	}

	/** One mod: its name, and under it the loader it came from — which is the half no vanilla row has. */
	private final class Row extends ObjectSelectionList.Entry<Row> {
		private final ModCatalog.Entry entry;

		Row(ModCatalog.Entry entry) {
			this.entry = entry;
		}

		@Override
		public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered,
				float partialTick) {
			int x = getContentX();
			int y = getContentY();
			if (KernelModListScreen.this.list.getSelected() == this) {
				g.fill(x - 2, y - 2, x + getContentWidth() + 2, y + getContentHeight() + 1, SELECTED);
			}
			g.text(KernelModListScreen.this.font, trim(this.entry.name(), getContentWidth()), x, y, BRIGHT);
			g.text(KernelModListScreen.this.font, label(this.entry.ecosystem()), x, y + 11,
					tag(this.entry.ecosystem()));
		}

		/** Names are arbitrary user data and the column is narrow; an ellipsis beats drawing over the next pane. */
		private String trim(String text, int width) {
			if (KernelModListScreen.this.font.width(text) <= width) return text;
			String cut = KernelModListScreen.this.font.plainSubstrByWidth(text, width - 6);
			return cut + "…";
		}

		@Override
		public Component getNarration() {
			return Component.literal(this.entry.name() + ", " + label(this.entry.ecosystem()));
		}

		@Override
		public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
			KernelModListScreen.this.list.setSelected(this);
			return true;
		}
	}
}
