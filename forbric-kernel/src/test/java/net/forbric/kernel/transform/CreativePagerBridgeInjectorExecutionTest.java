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
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.fabricmc.api.EnvType;

/**
 * {@link CreativePagerBridgeInjector}'s output, run: every {@code FabricCreativeModeInventoryScreen} call on the creative
 * screen answers from NeoForge's pager, where as merged each one threw {@code AssertionError("Implemented by mixin")} —
 * owo-lib asks {@code getCurrentPage()} while the screen opens, and the client died. NeoForge's page buttons still turn
 * the page as before, and a hook on Fabric's {@code updateSelection} hears the turn.
 *
 * <p>The hook is the kernel's real {@code KernelCreativePager} and {@code KernelCreativePagerScreen}, compiled from
 * {@code src/runtime/java} against stand-ins: tabs, NeoForge's pages and default-tab registry, the Fabric interface in
 * the shape the bridge reads (implementer methods whose defaults only throw, page-stepping defaults that delegate), and a
 * screen with NeoForge's fields, setter, private {@code selectTab} and two page-button lambdas. owo-lib's head
 * injection on {@code updateSelection} is modelled by adding a call at the head of the method the bridge adds.
 */
@ExecutesInjector(CreativePagerBridgeInjector.class)
@ResourceLock("system-properties")
class CreativePagerBridgeInjectorExecutionTest {
	private static final Path RUNTIME = Path.of("src/runtime/java/net/forbric/kernel/runtime");
	private static final String SCREEN = CreativePagerBridgeInjector.SCREEN_BINARY;
	private static final String API = CreativePagerBridgeInjector.API.replace('/', '.');

	private static final Map<String, String> STAND_INS = Map.of(
			"net.minecraft.world.item.CreativeModeTab", """
					package net.minecraft.world.item;

					public class CreativeModeTab {
						private final String name;
						private final boolean alignedRight;

						public CreativeModeTab(String name, boolean alignedRight) {
							this.name = name;
							this.alignedRight = alignedRight;
						}

						public boolean isAlignedRight() {
							return alignedRight;
						}

						@Override
						public String toString() {
							return name;
						}
					}
					""",
			"net.neoforged.neoforge.client.gui.CreativeTabsScreenPage", """
					package net.neoforged.neoforge.client.gui;

					import java.util.List;
					import net.minecraft.world.item.CreativeModeTab;

					public class CreativeTabsScreenPage {
						private final List<CreativeModeTab> tabs;

						public CreativeTabsScreenPage(List<CreativeModeTab> tabs) {
							this.tabs = tabs;
						}

						public List<CreativeModeTab> getVisibleTabs() {
							return tabs;
						}
					}
					""",
			"net.neoforged.neoforge.common.CreativeModeTabRegistry", """
					package net.neoforged.neoforge.common;

					import java.util.ArrayList;
					import java.util.List;
					import net.minecraft.world.item.CreativeModeTab;

					public class CreativeModeTabRegistry {
						public static final List<CreativeModeTab> DEFAULTS = new ArrayList<>();

						public static List<CreativeModeTab> getDefaultTabs() {
							return DEFAULTS;
						}
					}
					""",
			"net.minecraft.client.gui.components.Button", """
					package net.minecraft.client.gui.components;

					public class Button {
						public interface OnPress {
							void onPress(Button button);
						}

						private final OnPress onPress;

						public Button(OnPress onPress) {
							this.onPress = onPress;
						}

						public void press() {
							onPress.onPress(this);
						}
					}
					""",
			API, """
					package net.fabricmc.fabric.api.client.creativetab.v1;

					import java.util.List;
					import net.minecraft.world.item.CreativeModeTab;

					public interface FabricCreativeModeInventoryScreen {
						default boolean switchToPage(int page) {
							throw new AssertionError("Implemented by mixin");
						}

						default int getCurrentPage() {
							throw new AssertionError("Implemented by mixin");
						}

						default int getPageCount() {
							throw new AssertionError("Implemented by mixin");
						}

						default List<CreativeModeTab> getTabsOnPage(int page) {
							throw new AssertionError("Implemented by mixin");
						}

						default int getPage(CreativeModeTab tab) {
							throw new AssertionError("Implemented by mixin");
						}

						default boolean hasAdditionalPages() {
							throw new AssertionError("Implemented by mixin");
						}

						default CreativeModeTab getSelectedTab() {
							throw new AssertionError("Implemented by mixin");
						}

						default boolean setSelectedTab(CreativeModeTab tab) {
							throw new AssertionError("Implemented by mixin");
						}

						default boolean switchToNextPage() {
							return switchToPage(getCurrentPage() + 1);
						}

						default boolean switchToPreviousPage() {
							return switchToPage(getCurrentPage() - 1);
						}
					}
					""",
			SCREEN, """
					package net.minecraft.client.gui.screens.inventory;

					import java.util.ArrayList;
					import java.util.List;
					import net.fabricmc.fabric.api.client.creativetab.v1.FabricCreativeModeInventoryScreen;
					import net.minecraft.client.gui.components.Button;
					import net.minecraft.world.item.CreativeModeTab;
					import net.neoforged.neoforge.client.gui.CreativeTabsScreenPage;

					/** The merged screen: fabric-api's class tweaker gave it the interface; NeoForge's pager is all it has. */
					public class CreativeModeInventoryScreen implements FabricCreativeModeInventoryScreen {
						private static CreativeModeTab selectedTab;
						private final List<CreativeTabsScreenPage> pages = new ArrayList<>();
						private CreativeTabsScreenPage currentPage;
						public Button previous;
						public Button next;

						public CreativeModeInventoryScreen(List<CreativeTabsScreenPage> pages) {
							this.pages.addAll(pages);
							this.currentPage = pages.get(0);
						}

						/** NeoForge's init: the two page buttons, then the first tab. */
						public void init() {
							previous = new Button(button -> setCurrentPage(pages.get(Math.max(pages.indexOf(currentPage) - 1, 0))));
							next = new Button(button -> setCurrentPage(pages.get(Math.min(pages.indexOf(currentPage) + 1, pages.size() - 1))));
							selectTab(currentPage.getVisibleTabs().get(0));
						}

						public void setCurrentPage(CreativeTabsScreenPage page) {
							currentPage = page;
						}

						public int shownPage() {
							return pages.indexOf(currentPage);
						}

						public static CreativeModeTab shownTab() {
							return selectedTab;
						}

						private void selectTab(CreativeModeTab tab) {
							selectedTab = tab;
						}
					}
					""",
			"fixture.Owo", """
					package fixture;

					import java.util.ArrayList;
					import java.util.List;

					/** owo-lib's per-page tab memory, at the head of Fabric's updateSelection. */
					public class Owo {
						public static final List<Object> heard = new ArrayList<>();

						public static void updateSelectionHead(Object screen) {
							heard.add(screen);
						}
					}
					""");

	@AfterEach void reset() {
		System.clearProperty(CreativePagerBridgeInjector.PROPERTY);
	}

	private static Map<String, byte[]> compile(Path work) throws Exception {
		Map<String, String> sources = new HashMap<>(STAND_INS);
		for (String hook : List.of("KernelCreativePager", "KernelCreativePagerScreen")) {
			Path source = RUNTIME.resolve(hook + ".java");
			assertTrue(Files.isRegularFile(source), "the game-side hook's source is part of the checkout: " + source.toAbsolutePath());
			sources.put("net/forbric/kernel/runtime/" + hook + ".java", Files.readString(source));
		}
		return InjectorExecution.compile(work, sources);
	}

	private static CreativePagerBridgeInjector injector(Map<String, byte[]> classes, boolean pinned) {
		// The hook a pager's switchToPage calls, as config discovery would hand it (fabric-api's pager names updateSelection).
		return injector(classes, pinned, "updateSelection");
	}

	private static CreativePagerBridgeInjector injector(Map<String, byte[]> classes, boolean pinned, String hook) {
		return new CreativePagerBridgeInjector(classes::get, () -> pinned, () -> java.util.Set.of(hook + "()V"));
	}

	/** owo-lib's head injection on the updateSelection the bridge adds: what Mixin would weave there. */
	private static byte[] withOwoHook(byte[] screen) {
		return withOwoHook(screen, "updateSelection");
	}

	/** The same head injection on the hook the pager named {@code hook}. */
	private static byte[] withOwoHook(byte[] screen, String hook) {
		ClassNode node = new ClassNode();
		new ClassReader(screen).accept(node, 0);
		MethodNode update = node.methods.stream().filter(m -> m.name.equals(hook) && m.desc.equals("()V")).findFirst().orElseThrow();
		InsnList head = new InsnList();
		head.add(new VarInsnNode(Opcodes.ALOAD, 0));
		head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "fixture/Owo", "updateSelectionHead", "(Ljava/lang/Object;)V", false));
		update.instructions.insert(head);
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** Calls the Fabric interface's method {@code name} on {@code screen}, as a Fabric mod does; its throw is rethrown. */
	private static Object fabric(Object screen, String name, Object... args) throws Throwable {
		Class<?> api = Class.forName(API, false, screen.getClass().getClassLoader());
		for (Method method : api.getMethods()) {
			if (!method.getName().equals(name)) continue;
			try {
				return method.invoke(screen, args);
			} catch (InvocationTargetException thrown) {
				throw thrown.getCause();
			}
		}
		throw new NoSuchMethodException(name);
	}

	private static Object tab(ClassLoader loader, String name, boolean right) throws Throwable {
		return InjectorExecution.construct(loader.loadClass("net.minecraft.world.item.CreativeModeTab"), name, right);
	}

	/** A creative screen with two NeoForge pages, opened: building blocks and colored blocks, then a mod's two tabs. */
	private record Opened(Object screen, Object building, Object colored, Object gadgets, Object gizmos, Object search) {
	}

	@SuppressWarnings("unchecked")
	private static Opened open(ClassLoader loader) throws Throwable {
		Object building = tab(loader, "building", false), colored = tab(loader, "colored", false);
		Object gadgets = tab(loader, "mod:gadgets", false), gizmos = tab(loader, "mod:gizmos", false);
		Object search = tab(loader, "search", true), inventory = tab(loader, "inventory", true);
		((List<Object>) InjectorExecution.getStatic(loader.loadClass("net.neoforged.neoforge.common.CreativeModeTabRegistry"), "DEFAULTS"))
				.addAll(List.of(search, inventory));
		Class<?> page = loader.loadClass("net.neoforged.neoforge.client.gui.CreativeTabsScreenPage");
		Object first = InjectorExecution.construct(page, List.of(building, colored, search, inventory));
		Object second = InjectorExecution.construct(page, List.of(gadgets, gizmos, search, inventory));
		Object screen = InjectorExecution.construct(loader.loadClass(SCREEN), List.of(first, second));
		InjectorExecution.invoke(screen, "init");
		return new Opened(screen, building, colored, gadgets, gizmos, search);
	}

	@Test void theFabricInterfaceAnswersFromNeoForgesPager(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = compile(work);
		String internal = CreativePagerBridgeInjector.SCREEN;
		byte[] bridged = InjectorExecution.transform(injector(original, true), SCREEN, original.get(internal), EnvType.CLIENT);
		assertNotSame(original.get(internal), bridged, "the merged screen was bridged");
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(internal, withOwoHook(bridged));
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(bridged, loader));

		Opened opened = open(loader);
		Object screen = opened.screen();
		Class<?> type = screen.getClass();
		assertEquals(0, fabric(screen, "getCurrentPage"), "owo-lib's question while the screen opens is answered");
		assertEquals(2, fabric(screen, "getPageCount"));
		assertEquals(true, fabric(screen, "hasAdditionalPages"));
		assertEquals(List.of(opened.gadgets(), opened.gizmos()), fabric(screen, "getTabsOnPage", 1), "another page lists its own tabs");
		assertEquals(1, fabric(screen, "getPage", opened.gizmos()));
		assertEquals(0, fabric(screen, "getPage", opened.search()), "a default tab is on whichever page is shown");
		assertSame(opened.building(), fabric(screen, "getSelectedTab"));

		assertEquals(true, fabric(screen, "switchToNextPage"), "the interface's own page stepping goes through the bridge");
		assertEquals(1, InjectorExecution.invoke(screen, "shownPage"), "NeoForge's pager shows the page");
		assertSame(opened.gadgets(), fabric(screen, "getSelectedTab"), "and the selection moved onto it");
		assertEquals(true, fabric(screen, "setSelectedTab", opened.colored()));
		assertEquals(0, InjectorExecution.invoke(screen, "shownPage"), "selecting a tab on another page turns to it");
		assertSame(opened.colored(), InjectorExecution.invokeStatic(type, "shownTab"));

		List<?> heard = (List<?>) InjectorExecution.getStatic(loader.loadClass("fixture.Owo"), "heard");
		heard.clear();
		InjectorExecution.invoke(type.getField("next").get(screen), "press");
		assertEquals(1, InjectorExecution.invoke(screen, "shownPage"), "NeoForge's own button turns the page");
		assertSame(opened.colored(), InjectorExecution.invokeStatic(type, "shownTab"), "and leaves the selection as it always did");
		assertEquals(List.of(screen), heard, "while a hook on updateSelection hears the turn");

		Object stock = open(InjectorExecution.load(original)).screen();
		AssertionError thrown = assertThrows(AssertionError.class, () -> fabric(stock, "getCurrentPage"),
				"premise: as merged, the screen opening kills the client");
		assertEquals("Implemented by mixin", thrown.getMessage());
		assertThrows(NoSuchMethodException.class, () -> stock.getClass().getDeclaredMethod("updateSelection"),
				"premise: as merged, owo-lib's updateSelection hook has no method to attach to");
		assertSame(bridged, InjectorExecution.transform(injector(classes, true), SCREEN, bridged, EnvType.CLIENT),
				"a bridged screen is left alone");
	}

	/** The hook is the pager's by name too: a pager calling it qzxRefreshPage gets that method, and a hook there hears both kinds of turn. */
	@Test void aHookUnderThePagersOwnNameHearsEveryTurn(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = compile(work);
		String internal = CreativePagerBridgeInjector.SCREEN;
		byte[] bridged = InjectorExecution.transform(injector(original, true, "qzxRefreshPage"), SCREEN, original.get(internal), EnvType.CLIENT);
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(internal, withOwoHook(bridged, "qzxRefreshPage"));
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(bridged, loader));
		Opened opened = open(loader);
		Object screen = opened.screen();
		assertThrows(NoSuchMethodException.class, () -> screen.getClass().getDeclaredMethod("updateSelection"), "no name of the bridge's own");
		List<?> heard = (List<?>) InjectorExecution.getStatic(loader.loadClass("fixture.Owo"), "heard");
		heard.clear();
		assertEquals(true, fabric(screen, "switchToNextPage"));
		assertEquals(List.of(screen), heard, "a Fabric API turn runs the hook");
		InjectorExecution.invoke(screen.getClass().getField("previous").get(screen), "press");
		assertEquals(0, InjectorExecution.invoke(screen, "shownPage"));
		assertEquals(List.of(screen, screen), heard, "and so does NeoForge's own button");
	}

	@Test void theScreenIsLeftAsMergedWhenSwitchedOffUnpinnedOrWithoutTheApi(@TempDir Path work) throws Exception {
		Map<String, byte[]> original = compile(work);
		byte[] bytes = original.get(CreativePagerBridgeInjector.SCREEN);
		assertSame(bytes, InjectorExecution.transform(injector(original, false), SCREEN, bytes, EnvType.CLIENT),
				"Fabric's own mixin is not pinned: it implements the interface itself");
		Map<String, byte[]> withoutApi = new HashMap<>(original);
		withoutApi.remove(CreativePagerBridgeInjector.API);
		assertSame(bytes, InjectorExecution.transform(injector(withoutApi, true), SCREEN, bytes, EnvType.CLIENT),
				"no fabric-creative-tab-api, no interface to back");
		System.setProperty(CreativePagerBridgeInjector.PROPERTY, "off");
		assertSame(bytes, InjectorExecution.transform(injector(original, true), SCREEN, bytes, EnvType.CLIENT));
	}
}
