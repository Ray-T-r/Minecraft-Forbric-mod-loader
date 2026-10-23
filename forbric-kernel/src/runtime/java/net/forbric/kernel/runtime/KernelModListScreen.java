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

import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;
import net.forbric.kernel.ui.ModListRows;
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
 *
 * <p>The rows are the installed jars plus whatever else has settings a player could otherwise not reach: a mod a jar
 * carries inside itself that has a config screen, the game itself, a loader's own mod with one (see
 * {@link ModListRows}). The title still counts the installed jars.
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
	/**
	 * Rows for mods something went wrong with.
	 *
	 * <p>The marker is a character rather than colour alone: colour alone is not readable to everyone, and this
	 * row is the one carrying the information a player most needs.
	 */
	private static final int BROKEN = 0xFFE06C6C;
	private static final String BROKEN_MARK = "! ";
	/** How many unconfirmed notes the detail pane names for one mod before pointing at the machine report. */
	private static final int SUSPECTED_NOTES = 3;
	private static final int PAD = 6;
	/** How far a mod listed under the parent it names is drawn in from its parent. */
	private static final int INDENT = 10;
	private static final int SEARCH_Y = 32;
	private static final int LIST_TOP = 54;

	private final Screen parent;
	/** Every row, worked out once per {@link #init}: deciding which carried mods have a config asks every one of them. */
	private List<ModListRows.Row> planned = List.of();
	private ModList list;
	private EditBox search;
	private Button config;

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

	/**
	 * The static-factory shape a family's own mod list may be opened through.
	 *
	 * <p>NeoForge 26.2.0.88's mods button calls {@code ModListScreen.create(screen)} rather than constructing it,
	 * so {@code ModsButtonRedirector} re-points that call here. The kernel's stand-in for Mod Menu's API answers
	 * {@code ModMenuApi.createModsScreen} with it too. Nothing in the kernel calls this itself.
	 */
	public static KernelModListScreen create(Screen parent) {
		return new KernelModListScreen(parent);
	}

	@Override
	protected void init() {
		int listWidth = Math.max(150, Math.min(280, this.width / 3));
		this.search = new EditBox(this.font, PAD, SEARCH_Y, listWidth - PAD, 18, Component.literal("Search"));
		this.search.setHint(Component.literal("Search"));
		this.search.setResponder(this::rebuild);
		addRenderableWidget(this.search);

		this.list = new ModList(this.minecraft, listWidth, this.height - LIST_TOP - 34, LIST_TOP, 26, this::selectionChanged);
		this.list.setX(0);
		addRenderableWidget(this.list);

		this.config = addRenderableWidget(Button.builder(Component.literal("Config"), b -> openConfig())
				.bounds(this.width - 110, this.height - 26, 100, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds(this.width / 2 - 100, this.height - 26, 200, 20).build());

		this.planned = KernelModConfigScreens.rows();
		rebuild(this.search.getValue());
	}

	private void rebuild(String query) {
		if (this.list == null) return;
		String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
		List<ModListRows.Row> kept = needle.isEmpty() ? this.planned : ModListRows.filter(this.planned, r -> matches(r, needle));
		List<Row> rows = new ArrayList<>();
		for (ModListRows.Row r : kept) rows.add(new Row(r));
		this.list.replaceEntries(rows);
		rowsBuilt = rows.size();
		if (!rows.isEmpty() && this.list.getSelected() == null) this.list.setSelected(rows.get(0));
		selectionChanged();
	}

	private static boolean matches(ModListRows.Row r, String needle) {
		ModCatalog.Entry e = r.entry();
		if (e != null && e.status() != ModCatalog.Status.OK
				&& ("broken".contains(needle) || "failed".contains(needle) || needle.startsWith("!"))) {
			// So that a player who can SEE something is wrong can also filter down to it.
			return true;
		}
		return r.name().toLowerCase(Locale.ROOT).contains(needle)
				|| r.id().toLowerCase(Locale.ROOT).contains(needle)
				|| tagLabel(r).toLowerCase(Locale.ROOT).contains(needle);
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
		ModListRows.Row selected = this.list == null || this.list.getSelected() == null
				? null : this.list.getSelected().row;
		if (selected != null && selected.entry() == null) group(g, selected);
		else if (selected != null) detail(g, selected);
	}

	/** The pane for a heading a carried mod named as its parent: what it is called, what it says, what is under it. */
	private void group(GuiGraphicsExtractor g, ModListRows.Row r) {
		int x = this.list.getWidth() + 12;
		int wrap = Math.max(80, this.width - x - 10);
		int y = LIST_TOP - 8;
		g.text(this.font, Component.literal(r.name()), x, y, BRIGHT);
		y += 14;
		FormattedText what = FormattedText.of("Mods other mods carry inside themselves list themselves under this name; "
				+ "it is not a mod of its own.");
		g.textWithWordWrap(this.font, what, x, y, wrap, DIM);
		y += 12 * this.font.split(what, wrap).size() + 6;
		if (!r.description().isEmpty()) {
			g.textWithWordWrap(this.font, FormattedText.of(r.description()), x, y, wrap, BRIGHT);
		}
	}

	/** The right-hand pane. Word-wrapped so a long description does not run off the edge of the screen. */
	private void detail(GuiGraphicsExtractor g, ModListRows.Row r) {
		ModCatalog.Entry e = r.entry();
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
		g.text(this.font, Component.literal(tagLabel(r)), x, y, tagColour(r));
		y += 14;
		// Where a row that is no jar in mods/ comes from, said once here rather than left for the player to wonder why
		// a mod they never installed is in the list.
		String origin = origin(r);
		if (!origin.isEmpty()) {
			g.textWithWordWrap(this.font, FormattedText.of(origin), x, y, wrap, DIM);
			y += 12 * this.font.split(FormattedText.of(origin), wrap).size() + 2;
		}
		if (e.status() != ModCatalog.Status.OK) {
			// The wording is the careful part. A withdrawn mod's classes are loaded and its mixins applied, so
			// "not running" would be untrue and would send someone to reinstall what is already there.
			String what = e.status() == ModCatalog.Status.FAILED
					? "This mod did not finish loading"
					: "Part of this mod did not run";
			String detail = e.statusDetail().isEmpty() ? "" : " \u2014 " + e.statusDetail();
			g.textWithWordWrap(this.font, FormattedText.of(what + detail), x, y, wrap, BROKEN);
			y += 12 * (1 + this.font.split(FormattedText.of(what + detail), wrap).size());
			g.text(this.font, Component.literal("see .forbric-kernel/load-report.txt"), x, y, DIM);
			y += 14;
		}
		// Suspicions about this mod, dimmed and said to be unconfirmed: they change no status, and a player
		// troubleshooting the mod should still be able to see what was noticed. The rest are pointed at the machine
		// report, which always lists them all -- load-report.txt exists only when something failed, and a boot
		// whose only findings are suspicions writes none.
		List<CompatibilityFinding> notes = CompatibilityFindings.suspected().stream()
				.filter(f -> f.modId().equals(e.modId())).toList();
		for (int i = 0; i < Math.min(notes.size(), SUSPECTED_NOTES); i++) {
			CompatibilityFinding f = notes.get(i);
			String note = "possibly (not confirmed): " + f.feature() + " \u2014 " + f.detail();
			g.textWithWordWrap(this.font, FormattedText.of(note), x, y, wrap, DIM);
			y += 12 * this.font.split(FormattedText.of(note), wrap).size() + 2;
		}
		if (notes.size() > SUSPECTED_NOTES) {
			g.text(this.font, Component.literal("and " + (notes.size() - SUSPECTED_NOTES)
					+ " more in .forbric-kernel/compatibility-report.json"), x, y, DIM);
			y += 12;
		}
		if (!notes.isEmpty()) y += 4;
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
			y += 12 * (1 + this.font.split(FormattedText.of(e.description()), wrap).size());
		}
		// What this jar carries inside itself. Not rows of their own -- they are not what anyone installed -- unless
		// they have settings of their own, which a row is the only way to; but a player looking for "why is Kotlin
		// here" should find the answer on the mod that brought it, not have it missing from the screen entirely.
		int bundled = ModCatalog.bundledBy(e.modId()).size();
		if (bundled > 0) {
			g.text(this.font, Component.literal("bundles " + bundled + " jar(s)"), x, y + 4, DIM);
		}
		// Said on the screen rather than left to be discovered: a double click that does something is only a
		// shortcut if someone knows it is there.
		if (this.config != null && this.config.visible) {
			g.text(this.font, Component.literal("double-click to configure"), x, this.height - 44, DIM);
		}
	}

	/** Why a row that is not a jar in {@code mods/} is listed, or empty for one that is. */
	private static String origin(ModListRows.Row r) {
		if (r.game()) return "The game itself.";
		if (r.kind() == ModListRows.Kind.PLATFORM) return "Part of " + label(r.family()) + " itself, not a jar in mods/.";
		ModCatalog.Entry e = r.entry();
		if (e == null || e.installed()) return "";
		// "?" is the catalogue's "more than one mod carries it", which is not a mod id a player could look for.
		String by = e.bundledBy();
		ModCatalog.Entry carrier = null;
		for (ModCatalog.Entry c : ModCatalog.all()) if (c.modId().equals(by)) carrier = c;
		return carrier != null ? "Carried inside " + carrier.name() + "; listed because it has settings of its own."
				: "Carried inside other mods; listed because it has settings of its own.";
	}

	/**
	 * Shows the Config button only when the selected mod's own ecosystem has a factory registered for it.
	 *
	 * <p>Asked on selection, not per frame: the answer needs a registry lookup in one of three places, and a
	 * button that dims and brightens as the highlight moves is worth one lookup per move, not sixty per second.
	 */
	private void selectionChanged() {
		if (this.config == null) return;
		Row row = this.list == null ? null : this.list.getSelected();
		this.config.visible = row != null && KernelModConfigScreens.has(row.row);
	}

	private void openConfig() {
		Row row = this.list == null ? null : this.list.getSelected();
		if (row == null) return;
		Screen screen = KernelModConfigScreens.open(row.row, this);
		// Null here means the factory changed its mind between the probe and the press, or threw. Staying put is
		// the only sane answer: setScreen(null) would drop the player into the world with the menu gone.
		if (screen != null) this.minecraft.gui.setScreen(screen);
	}

	private static String summary() {
		String counts = ModCatalog.count(Ecosystem.FABRIC) + " Fabric   "
				+ ModCatalog.count(Ecosystem.NEOFORGE) + " NeoForge   "
				+ ModCatalog.count(Ecosystem.FORGE) + " MinecraftForge";
		int broken = ModCatalog.failures().size();
		// "did not finish loading", never "not running": a withdrawn mod's classes ARE loaded and its mixins ARE
		// applied. Telling a player it is absent sends them to reinstall something that is already there.
		String summary = broken == 0 ? counts : counts + "   \u00a7c" + broken + " did not finish loading";
		// Confirmed losses no row can carry -- the kernel's own, or a config no single mod claims. The prompt that
		// sent the player here counted them, so the screen has to say they exist and where they are written.
		int unowned = CompatibilityFindings.unattributed().size();
		return unowned == 0 ? summary
				: summary + "   \u00a7c" + unowned + " not tied to one mod (see .forbric-kernel/load-report.txt)";
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

	/** A row's second line: the loader it came from; "Game" for the game, "Group" for a heading. */
	private static String tagLabel(ModListRows.Row r) {
		if (r.game()) return "Game";
		if (r.family() == null) return "Group";
		return label(r.family());
	}

	private static int tagColour(ModListRows.Row r) {
		return r.family() == null ? BRIGHT : tag(r.family());
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}

	/** The scrolling list. Vanilla's widget, so scrolling, selection and keyboard navigation are not reinvented. */
	private static final class ModList extends ObjectSelectionList<Row> {
		private final Runnable selected;

		ModList(Minecraft minecraft, int width, int height, int y, int entryHeight, Runnable selected) {
			super(minecraft, width, height, y, entryHeight);
			this.selected = selected;
		}

		/**
		 * Every way the selection moves comes through here -- a click, and the arrow keys and Tab, which vanilla's
		 * focus handling turns into {@code setFocused(entry)} and that into this. The Config button follows the
		 * selection only if it is told here: told from the click alone, it showed the previous row's answer after
		 * every key press, hidden on a mod with settings and pressable on one without.
		 */
		@Override
		public void setSelected(Row row) {
			super.setSelected(row);
			this.selected.run();
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

	/**
	 * One row: a mod's name, and under it the loader it came from — which is the half no vanilla row has. A mod listed
	 * under the parent it names is drawn indented beneath it.
	 */
	private final class Row extends ObjectSelectionList.Entry<Row> {
		private final ModListRows.Row row;

		Row(ModListRows.Row row) {
			this.row = row;
		}

		@Override
		public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered,
				float partialTick) {
			int indent = this.row.depth() * INDENT;
			int x = getContentX() + indent;
			int y = getContentY();
			int width = getContentWidth() - indent;
			if (KernelModListScreen.this.list.getSelected() == this) {
				g.fill(x - 2, y - 2, x + width + 2, y + getContentHeight() + 1, SELECTED);
			}
			ModCatalog.Entry entry = this.row.entry();
			boolean broken = entry != null && entry.status() != ModCatalog.Status.OK;
			String name = broken ? BROKEN_MARK + this.row.name() : this.row.name();
			g.text(KernelModListScreen.this.font, trim(name, width), x, y, broken ? BROKEN : BRIGHT);
			String second = tagLabel(this.row);
			if (entry != null && this.row.kind() == ModListRows.Kind.MOD && !entry.installed()) second += "  (carried)";
			g.text(KernelModListScreen.this.font, trim(second, width), x, y + 11, tagColour(this.row));
		}

		/** Names are arbitrary user data and the column is narrow; an ellipsis beats drawing over the next pane. */
		private String trim(String text, int width) {
			if (KernelModListScreen.this.font.width(text) <= width) return text;
			String cut = KernelModListScreen.this.font.plainSubstrByWidth(text, width - 6);
			return cut + "…";
		}

		@Override
		public Component getNarration() {
			return Component.literal(this.row.name() + ", " + tagLabel(this.row));
		}

		/**
		 * Select on a click, open the config on a double click.
		 *
		 * <p>The Config button is still there and is still what says whether a mod HAS one; this is the shortcut
		 * for the mod already under the cursor. A double click on a mod with no config does nothing rather than
		 * flashing an empty screen — {@code openConfig} already returns without a screen in that case, and a mod
		 * with no settings to change is the ordinary case, not an error worth reporting.
		 */
		@Override
		public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
			// setSelected tells the screen, which shows or hides the Config button.
			KernelModListScreen.this.list.setSelected(this);
			if (doubled) KernelModListScreen.this.openConfig();
			return true;
		}
	}
}
