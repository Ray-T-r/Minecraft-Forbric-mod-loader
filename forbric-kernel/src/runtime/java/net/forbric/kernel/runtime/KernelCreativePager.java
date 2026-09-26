/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.neoforge.client.gui.CreativeTabsScreenPage;
import net.neoforged.neoforge.common.CreativeModeTabRegistry;

/**
 * fabric-creative-tab-api-v1's {@code FabricCreativeModeInventoryScreen}, answered from NeoForge's creative pager.
 *
 * <p>On the merged game the creative screen implements that interface — fabric-api's class tweaker injects it — but the
 * mixin that implements it is pinned off ({@code MergedBaseMixinCompat}: two pagers on one screen hid mod tabs), so
 * every method was the interface's own default, {@code throw new AssertionError("Implemented by mixin")}. owo calls
 * {@code getCurrentPage()} from the tail of {@code selectTab}, which {@code init} calls: the first time a player with
 * owo and fabric-api opened the creative inventory, the client died. CreativePagerBridgeInjector gives the screen real
 * bodies that land here, with NeoForge's pager as the only one there is:
 *
 * <ul>
 *   <li>a page is an index into NeoForge's {@code pages}, the one its "&lt; N/M &gt;" buttons move through;</li>
 *   <li>the default tabs (hotbar, search, operator, inventory) are on every page, as NeoForge draws them and as Fabric's
 *       {@code COMMON_TABS} are — the same four — so they belong to whichever page is current;</li>
 *   <li>a tab NeoForge puts on no page (it shows nothing) is on page {@code -1}, which no switch reaches.</li>
 * </ul>
 *
 * <p>{@code updateSelection} is Fabric's own private method name, kept so owo's per-page tab memory (an {@code @Inject}
 * at its head) binds. Fabric runs it after every page switch; here {@link #switchToPage} does, and so do NeoForge's two
 * page buttons ({@link #pageTurned}) — but for those the body itself stands aside, so the page turns exactly as
 * NeoForge turns it (the selected tab stays) unless a mod hooked on the method acts. A pack without such a hook sees
 * NeoForge's buttons unchanged.
 */
public final class KernelCreativePager {
	/** Set while NeoForge's page buttons announce a turn: {@link #updateSelection} leaves the selection to the hooks. */
	private static boolean turningPage;

	private KernelCreativePager() {
	}

	/** {@code getCurrentPage()I}: NeoForge's current page, as an index. 0 before {@code init} has built any page. */
	public static int currentPage(KernelCreativePagerScreen screen) {
		return Math.max(0, screen.forbric$pages().indexOf(screen.forbric$currentPage()));
	}

	/** {@code getPageCount()}: at least one, as Fabric counts. */
	public static int pageCount(KernelCreativePagerScreen screen) {
		return Math.max(1, screen.forbric$pages().size());
	}

	/** {@code hasAdditionalPages()}: whether NeoForge draws its page buttons at all. */
	public static boolean hasAdditionalPages(KernelCreativePagerScreen screen) {
		return screen.forbric$pages().size() > 1;
	}

	/**
	 * {@code getTabsOnPage(int)}: what NeoForge draws on that page. The default tabs count on the current page only,
	 * as Fabric's {@code getPage} places them; a page that does not exist has none.
	 */
	public static List<CreativeModeTab> tabsOnPage(KernelCreativePagerScreen screen, int page) {
		CreativeTabsScreenPage found = pageAt(screen, page);
		if (found == null) return List.of();
		List<CreativeModeTab> visible = found.getVisibleTabs();
		if (page == currentPage(screen)) return visible;
		List<CreativeModeTab> defaults = CreativeModeTabRegistry.getDefaultTabs();
		List<CreativeModeTab> own = new ArrayList<>(visible.size());
		for (CreativeModeTab tab : visible) if (!defaults.contains(tab)) own.add(tab);
		return List.copyOf(own);
	}

	/** {@code getPage(CreativeModeTab)}: the current page for a default tab, the first page showing it, else -1. */
	public static int pageOf(KernelCreativePagerScreen screen, CreativeModeTab tab) {
		if (CreativeModeTabRegistry.getDefaultTabs().contains(tab)) return currentPage(screen);
		List<CreativeTabsScreenPage> pages = screen.forbric$pages();
		for (int i = 0; i < pages.size(); i++) {
			if (pages.get(i).getVisibleTabs().contains(tab)) return i;
		}
		return -1;
	}

	/**
	 * {@code switchToPage(int)}, as Fabric's: no such page or already on it is {@code false}; otherwise NeoForge's
	 * page moves and the selection is updated (owo's hook first, then Fabric's rule).
	 */
	public static boolean switchToPage(KernelCreativePagerScreen screen, int page) {
		List<CreativeTabsScreenPage> pages = screen.forbric$pages();
		if (page < 0 || page >= pages.size()) return false;
		CreativeTabsScreenPage target = pages.get(page);
		if (target == screen.forbric$currentPage()) return false;
		screen.forbric$setCurrentPage(target);
		screen.forbric$updateSelection();
		return true;
	}

	/**
	 * {@code setSelectedTab(CreativeModeTab)}, as Fabric's: the tab already selected, or one on no page, is
	 * {@code false}; otherwise its page is shown first and the tab selected.
	 */
	public static boolean setSelectedTab(KernelCreativePagerScreen screen, CreativeModeTab tab) {
		Objects.requireNonNull(tab, "creativeModeTab");
		if (screen.forbric$selectedTab() == tab) return false;
		int page = pageOf(screen, tab);
		if (page < 0) return false;
		if (page != currentPage(screen) && !switchToPage(screen, page)) return false;
		screen.forbric$selectTab(tab);
		return true;
	}

	/**
	 * The body of the screen's {@code updateSelection()}, Fabric's rule: a selected tab not drawn on the current page
	 * gives way to the page's first tab, a left-aligned one before a right-aligned one. Stands aside while NeoForge's
	 * own buttons turn the page, which keep the selection.
	 */
	public static void updateSelection(KernelCreativePagerScreen screen) {
		if (turningPage) return;
		List<CreativeModeTab> visible = screen.forbric$currentPage().getVisibleTabs();
		CreativeModeTab selected = screen.forbric$selectedTab();
		if (selected != null && visible.contains(selected)) return;
		CreativeModeTab replacement = null;
		for (CreativeModeTab tab : visible) {
			if (!tab.isAlignedRight()) {
				replacement = tab;
				break;
			}
			if (replacement == null) replacement = tab;
		}
		if (replacement != null) screen.forbric$selectTab(replacement);
	}

	/**
	 * After NeoForge's "&lt;" or "&gt;" button moved the page: the screen's {@code updateSelection} runs so a mod
	 * hooked on it (owo remembers each page's tab) sees the turn, while its own body keeps NeoForge's behaviour.
	 */
	public static void pageTurned(KernelCreativePagerScreen screen) {
		boolean outer = turningPage;
		turningPage = true;
		try {
			screen.forbric$updateSelection();
		} finally {
			turningPage = outer;
		}
	}

	/** Page {@code page} of NeoForge's list; before {@code init} built any, page 0 is the placeholder it draws. */
	private static CreativeTabsScreenPage pageAt(KernelCreativePagerScreen screen, int page) {
		List<CreativeTabsScreenPage> pages = screen.forbric$pages();
		if (pages.isEmpty()) return page == 0 ? screen.forbric$currentPage() : null;
		return page < 0 || page >= pages.size() ? null : pages.get(page);
	}
}
