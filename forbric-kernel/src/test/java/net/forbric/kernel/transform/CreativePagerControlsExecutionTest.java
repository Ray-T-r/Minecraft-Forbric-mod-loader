/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * Whose page controls the creative screen draws: when the guest pager's are drawn, the carrier's two page buttons and its
 * page label stand aside — found by what they do, never by a name of NeoForge's — and the page still turns, through the
 * Fabric interface, on the carrier's page; when they are not, the screen is exactly as before.
 *
 * <p>The stand-in screen is the merged one in the shapes {@code javac} gives NeoForge's code: under
 * {@code if (pages.size() > 1)} {@code init} adds a "&lt;" and a "&gt;" button whose press handlers set the page, and under
 * {@code if (pages.size() != 1)} {@code extractRenderState} draws "N / M". The real merged screen is checked to have
 * exactly those two blocks.
 */
@ExecutesInjector(CreativePagerBridgeInjector.class)
@ResourceLock("system-properties")
class CreativePagerControlsExecutionTest {
	private static final Path RUNTIME = Path.of("src/runtime/java/net/forbric/kernel/runtime");
	private static final String SCREEN = CreativePagerBridgeInjector.SCREEN_BINARY;
	private static final String API = CreativePagerBridgeInjector.API.replace('/', '.');

	private static final Map<String, String> STAND_INS = new HashMap<>(Map.of(
			"net.minecraft.world.item.CreativeModeTab", """
					package net.minecraft.world.item;
					public class CreativeModeTab {
						private final String name;
						public CreativeModeTab(String name) { this.name = name; }
						public boolean isAlignedRight() { return false; }
						@Override public String toString() { return name; }
					}
					""",
			"net.neoforged.neoforge.client.gui.CreativeTabsScreenPage", """
					package net.neoforged.neoforge.client.gui;
					import java.util.List;
					import net.minecraft.world.item.CreativeModeTab;
					public class CreativeTabsScreenPage {
						private final List<CreativeModeTab> tabs;
						public CreativeTabsScreenPage(List<CreativeModeTab> tabs) { this.tabs = tabs; }
						public List<CreativeModeTab> getVisibleTabs() { return tabs; }
					}
					""",
			"net.neoforged.neoforge.common.CreativeModeTabRegistry", """
					package net.neoforged.neoforge.common;
					import java.util.List;
					import net.minecraft.world.item.CreativeModeTab;
					public class CreativeModeTabRegistry {
						public static List<CreativeModeTab> getDefaultTabs() { return List.of(); }
					}
					""",
			"net.minecraft.network.chat.Component", """
					package net.minecraft.network.chat;
					public interface Component {
						static Component literal(String text) { return () -> text; }
						String text();
						default String getVisualOrderText() { return text(); }
					}
					""",
			"net.minecraft.client.gui.Font", """
					package net.minecraft.client.gui;
					public class Font {
						public int width(net.minecraft.network.chat.Component text) { return text.text().length() * 6; }
					}
					""",
			"net.minecraft.client.gui.GuiGraphicsExtractor", """
					package net.minecraft.client.gui;
					public class GuiGraphicsExtractor {
						public final java.util.List<String> drawn = new java.util.ArrayList<>();
						public void text(Font font, String text, int x, int y, int color) { drawn.add(text); }
					}
					""",
			"net.minecraft.client.gui.components.Button", """
					package net.minecraft.client.gui.components;
					public class Button {
						public interface OnPress { void onPress(Button button); }
						public final String label;
						private final OnPress onPress;
						Button(String label, OnPress onPress) { this.label = label; this.onPress = onPress; }
						public void press() { onPress.onPress(this); }
						public static Builder builder(net.minecraft.network.chat.Component label, OnPress onPress) { return new Builder(label.text(), onPress); }
						public static class Builder {
							private final String label;
							private final OnPress onPress;
							Builder(String label, OnPress onPress) { this.label = label; this.onPress = onPress; }
							public Builder pos(int x, int y) { return this; }
							public Builder size(int w, int h) { return this; }
							public Button build() { return new Button(label, onPress); }
						}
					}
					"""));

	static {
		STAND_INS.put(API, """
				package net.fabricmc.fabric.api.client.creativetab.v1;
				import java.util.List;
				import net.minecraft.world.item.CreativeModeTab;
				public interface FabricCreativeModeInventoryScreen {
					default boolean switchToPage(int page) { throw new AssertionError("Implemented by mixin"); }
					default int getCurrentPage() { throw new AssertionError("Implemented by mixin"); }
					default int getPageCount() { throw new AssertionError("Implemented by mixin"); }
					default List<CreativeModeTab> getTabsOnPage(int page) { throw new AssertionError("Implemented by mixin"); }
					default int getPage(CreativeModeTab tab) { throw new AssertionError("Implemented by mixin"); }
					default boolean hasAdditionalPages() { throw new AssertionError("Implemented by mixin"); }
					default CreativeModeTab getSelectedTab() { throw new AssertionError("Implemented by mixin"); }
					default boolean setSelectedTab(CreativeModeTab tab) { throw new AssertionError("Implemented by mixin"); }
					default boolean switchToNextPage() { return switchToPage(getCurrentPage() + 1); }
					default boolean switchToPreviousPage() { return switchToPage(getCurrentPage() - 1); }
				}
				""");
		STAND_INS.put(SCREEN, """
				package net.minecraft.client.gui.screens.inventory;
				import java.util.ArrayList;
				import java.util.List;
				import net.fabricmc.fabric.api.client.creativetab.v1.FabricCreativeModeInventoryScreen;
				import net.minecraft.client.gui.Font;
				import net.minecraft.client.gui.GuiGraphicsExtractor;
				import net.minecraft.client.gui.components.Button;
				import net.minecraft.network.chat.Component;
				import net.minecraft.world.item.CreativeModeTab;
				import net.neoforged.neoforge.client.gui.CreativeTabsScreenPage;
				public class CreativeModeInventoryScreen implements FabricCreativeModeInventoryScreen {
					private static CreativeModeTab selectedTab;
					private final List<CreativeTabsScreenPage> pages = new ArrayList<>();
					private CreativeTabsScreenPage currentPage;
					public final List<Object> widgets = new ArrayList<>();
					private final Font font = new Font();
					private int leftPos = 100, topPos = 50, imageWidth = 195;
					public CreativeModeInventoryScreen(List<CreativeTabsScreenPage> pages) { this.pages.addAll(pages); }
					protected void init() {
						widgets.clear();
						currentPage = pages.get(0);
						if (pages.size() > 1) {
							addRenderableWidget(Button.builder(Component.literal("<"), button -> setCurrentPage(pages.get(Math.max(pages.indexOf(currentPage) - 1, 0))))
									.pos(leftPos, topPos - 50).size(20, 20).build());
							addRenderableWidget(Button.builder(Component.literal(">"), button -> setCurrentPage(pages.get(Math.min(pages.indexOf(currentPage) + 1, pages.size() - 1))))
									.pos(leftPos + imageWidth - 20, topPos - 50).size(20, 20).build());
						}
						selectTab(currentPage.getVisibleTabs().get(0));
					}
					public void open() { init(); }
					public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
						if (pages.size() != 1) {
							Component page = Component.literal(String.format("%d / %d", pages.indexOf(currentPage) + 1, pages.size()));
							graphics.text(font, page.getVisualOrderText(), leftPos + imageWidth / 2 - font.width(page) / 2, topPos - 44, -1);
						}
					}
					protected Object addRenderableWidget(Object widget) { widgets.add(widget); return widget; }
					public void setCurrentPage(CreativeTabsScreenPage page) { currentPage = page; }
					public int shownPage() { return pages.indexOf(currentPage); }
					private void selectTab(CreativeModeTab tab) { selectedTab = tab; }
				}
				""");
	}

	@AfterEach void reset() {
		System.clearProperty(CreativePagerBridgeInjector.PROPERTY);
	}

	private static Map<String, byte[]> compile(Path work) throws Exception {
		Map<String, String> sources = new HashMap<>(STAND_INS);
		for (String hook : List.of("KernelCreativePager", "KernelCreativePagerScreen")) {
			sources.put("net/forbric/kernel/runtime/" + hook + ".java", Files.readString(RUNTIME.resolve(hook + ".java")));
		}
		return InjectorExecution.compile(work, sources);
	}

	private static CreativePagerBridgeInjector injector(Map<String, byte[]> classes, boolean guestControls) {
		return new CreativePagerBridgeInjector(classes::get, () -> true, () -> java.util.Set.of("updateSelection()V"), () -> guestControls);
	}

	/** The screen, bridged, on three pages, opened, and one frame extracted: its widgets and what it drew. */
	private record Opened(Object screen, List<?> widgets, List<?> drawn) {
	}

	private static Opened open(Map<String, byte[]> classes) throws Throwable {
		ClassLoader loader = InjectorExecution.load(classes);
		Class<?> page = loader.loadClass("net.neoforged.neoforge.client.gui.CreativeTabsScreenPage");
		Class<?> tab = loader.loadClass("net.minecraft.world.item.CreativeModeTab");
		Object screen = InjectorExecution.construct(loader.loadClass(SCREEN), List.of(
				InjectorExecution.construct(page, List.of(InjectorExecution.construct(tab, "a"))),
				InjectorExecution.construct(page, List.of(InjectorExecution.construct(tab, "b"))),
				InjectorExecution.construct(page, List.of(InjectorExecution.construct(tab, "c")))));
		InjectorExecution.invoke(screen, "open");
		Object graphics = InjectorExecution.construct(loader.loadClass("net.minecraft.client.gui.GuiGraphicsExtractor"));
		screen.getClass().getMethod("extractRenderState", graphics.getClass(), int.class, int.class, float.class)
				.invoke(screen, graphics, 0, 0, 0f);
		return new Opened(screen, (List<?>) screen.getClass().getField("widgets").get(screen),
				(List<?>) graphics.getClass().getField("drawn").get(graphics));
	}

	private static Object fabric(Object screen, String name, Object... args) throws Throwable {
		for (Method method : Class.forName(API, false, screen.getClass().getClassLoader()).getMethods()) {
			if (!method.getName().equals(name)) continue;
			try {
				return method.invoke(screen, args);
			} catch (InvocationTargetException thrown) {
				throw thrown.getCause();
			}
		}
		throw new NoSuchMethodException(name);
	}

	@Test void theCarriersControlsStandAsideOnlyWhenTheGuestsAreDrawn(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = compile(work);
		String internal = CreativePagerBridgeInjector.SCREEN;
		ClassNode node = new ClassNode();
		new ClassReader(original.get(internal)).accept(node, 0);
		assertEquals(2, CreativePagerBridgeInjector.carrierControls(node).size(), "the button block and the label block");

		Map<String, byte[]> kept = new HashMap<>(original);
		kept.put(internal, InjectorExecution.transform(injector(original, false), SCREEN, original.get(internal), EnvType.CLIENT));
		Opened carrier = open(kept);
		assertEquals(2, carrier.widgets().size(), "with the carrier's controls drawn, its two page buttons are added");
		assertEquals(List.of("1 / 3"), carrier.drawn(), "and its page label is drawn");

		Map<String, byte[]> aside = new HashMap<>(original);
		byte[] guest = InjectorExecution.transform(injector(original, true), SCREEN, original.get(internal), EnvType.CLIENT);
		aside.put(internal, guest);
		assertEquals("", InjectorExecution.verify(guest, InjectorExecution.load(aside)), "the stood-aside screen verifies");
		Opened opened = open(aside);
		assertEquals(List.of(), opened.widgets(), "no page button of the carrier's");
		assertEquals(List.of(), opened.drawn(), "no page label of the carrier's");
		assertEquals(true, fabric(opened.screen(), "hasAdditionalPages"), "the pages are all still there");
		assertEquals(true, fabric(opened.screen(), "switchToNextPage"), "and a guest control turns them through the interface");
		assertEquals(1, InjectorExecution.invoke(opened.screen(), "shownPage"), "onto the carrier's page");
	}

	/** A block that does anything besides the carrier's own controls is not stood aside, guest controls or not. */
	@Test void aControlBlockThatDoesMoreIsLeftAlone(@TempDir Path work) throws Throwable {
		Map<String, String> sources = new HashMap<>(STAND_INS);
		sources.put(SCREEN, STAND_INS.get(SCREEN).replace("graphics.text(font, page.getVisualOrderText()",
				"System.setProperty(\"forbric.test.sideEffect\", \"1\"); graphics.text(font, page.getVisualOrderText()"));
		for (String hook : List.of("KernelCreativePager", "KernelCreativePagerScreen")) {
			sources.put("net/forbric/kernel/runtime/" + hook + ".java", Files.readString(RUNTIME.resolve(hook + ".java")));
		}
		Map<String, byte[]> original = InjectorExecution.compile(work, sources);
		ClassNode node = new ClassNode();
		new ClassReader(original.get(CreativePagerBridgeInjector.SCREEN)).accept(node, 0);
		assertNull(CreativePagerBridgeInjector.carrierControls(node));
		System.clearProperty("forbric.test.sideEffect");
	}

	/** The real merged screen has exactly the two blocks, in the shapes above. */
	@Test void theMergedScreenHasExactlyTheTwoControlBlocks() throws Exception {
		Path merged = TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");
		byte[] screen = TestFixtures.requireEntry(Fixture.STAGED, merged, CreativePagerBridgeInjector.SCREEN + ".class");
		ClassNode node = new ClassNode();
		new ClassReader(screen).accept(node, 0);
		var guards = CreativePagerBridgeInjector.carrierControls(node);
		assertNotNull(guards, "NeoForge's page buttons and page label are found on the merged screen");
		assertEquals(2, guards.size());
	}
}
