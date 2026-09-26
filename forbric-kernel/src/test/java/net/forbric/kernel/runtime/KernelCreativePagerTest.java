/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.tools.ToolProvider;

import net.forbric.kernel.transform.CreativePagerBridgeInjector;
import net.forbric.kernel.transform.CreativePagerFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The creative pager end to end, off-game: a screen with the merged screen's pager shape, put through fabric-api's own
 * class tweaker and then the bridge, called the way owo-lib and the Fabric API call it — with fabric-api's real
 * interface and the compiled game-side pager, over stand-ins for the three game types.
 */
@ResourceLock("system-properties")
class KernelCreativePagerTest {
	private static final String SCREEN = "net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen";
	private static final String TAB = "net.minecraft.world.item.CreativeModeTab";
	private static final String API = CreativePagerFixtures.INTERFACE.replace('/', '.');
	private static final String PROBE = "fixture.OwoCall";
	@TempDir Path temporary;

	@AfterEach void reset() { System.clearProperty(CreativePagerBridgeInjector.PROPERTY); }

	/** Without the bridge: the cast works, and owo's retargeted call is the interface default. */
	@Test void withoutTheBridgeOwosCallOnTheCreativeScreenThrowsAssertionError() throws Exception {
		try (Fixture f = fixture(false, false)) {
			Object screen = f.screen();
			assertTrue(f.api.isInstance(screen), "the class tweaker injects the interface: a cast would succeed");
			InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () -> f.owoPage(screen));
			assertInstanceOf(AssertionError.class, thrown.getCause());
			assertEquals("Implemented by mixin", thrown.getCause().getMessage());
		}
		System.setProperty(CreativePagerBridgeInjector.PROPERTY, "off");
		try (Fixture f = fixture(true, false)) {
			Object screen = f.screen();
			InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () -> f.owoPage(screen),
					"switched off, the bridge leaves the screen as it was");
			assertInstanceOf(AssertionError.class, thrown.getCause());
		}
	}

	@Test void theBridgeAnswersEveryCallFromNeoForgesPager() throws Exception {
		try (Fixture f = fixture(true, false)) {
			Object screen = f.screen();
			assertEquals(0, f.owoPage(screen), "owo's call now returns NeoForge's page");
			assertEquals(2, f.api("getPageCount", screen));
			assertEquals(true, f.api("hasAdditionalPages", screen));
			assertEquals(List.of("A", "B", "SEARCH", "INVENTORY"), names(f.api("getTabsOnPage", screen, 0)),
					"the default tabs are on the current page, as NeoForge draws them");
			assertEquals(List.of("C", "D"), names(f.api("getTabsOnPage", screen, 1)));
			assertEquals(List.of(), names(f.api("getTabsOnPage", screen, 2)));
			assertEquals(0, f.api("getPage", screen, f.tab("A")));
			assertEquals(1, f.api("getPage", screen, f.tab("D")));
			assertEquals(0, f.api("getPage", screen, f.tab("SEARCH")));
			assertEquals(-1, f.api("getPage", screen, f.tab("HIDDEN")), "a tab NeoForge shows on no page");
			assertSame(f.tab("A"), f.api("getSelectedTab", screen));

			assertEquals(true, f.api("switchToPage", screen, 1));
			assertSame(f.page(1), f.neoForgePage(screen), "NeoForge's own page moved");
			assertSame(f.tab("D"), f.selected(), "Fabric's rule: the page's first left-aligned tab (C is right-aligned)");
			assertEquals(1, f.owoPage(screen));
			assertEquals(1, f.api("getPage", screen, f.tab("SEARCH")), "default tabs follow the current page");
			assertEquals(false, f.api("switchToPage", screen, 1), "already there");
			assertEquals(false, f.api("switchToPage", screen, 2));
			assertEquals(false, f.api("switchToPage", screen, -1));

			f.calls().clear();
			assertEquals(true, f.api("setSelectedTab", screen, f.tab("B")));
			assertSame(f.page(0), f.neoForgePage(screen));
			assertEquals(List.of("selectTab A", "selectTab B"), f.calls(), "as Fabric: switch (reselect), then select");
			assertEquals(false, f.api("setSelectedTab", screen, f.tab("B")), "already selected");
			assertEquals(false, f.api("setSelectedTab", screen, f.tab("HIDDEN")), "on no page");

			assertEquals(true, f.api("switchToNextPage", screen), "the interface's own defaults work through the bodies");
			assertEquals(true, f.api("switchToPreviousPage", screen));
			assertEquals(0, f.owoPage(screen));
		}
	}

	/** NeoForge's own "<" and ">" keep the selected tab; only a mod hooked on updateSelection (owo) acts on a turn. */
	@Test void neoForgesPageButtonsKeepTheSelectionUnlessAHookActs() throws Exception {
		try (Fixture f = fixture(true, false)) {
			Object screen = f.screen();
			f.press(screen, "pressNext");
			assertSame(f.page(1), f.neoForgePage(screen));
			assertSame(f.tab("A"), f.selected(), "unhooked, NeoForge's buttons behave exactly as before");
			assertEquals(List.of(), f.calls());
		}
		try (Fixture f = fixture(true, true)) {
			Object screen = f.screen();
			f.press(screen, "pressNext");
			assertEquals(List.of("hook"), f.calls(), "a hook at the head of updateSelection sees the turn");
			assertSame(f.tab("A"), f.selected(), "and the body still keeps NeoForge's selection");
			f.press(screen, "pressPrevious");
			assertEquals(List.of("hook", "hook"), f.calls());

			f.calls().clear();
			assertEquals(true, f.api("switchToPage", screen, 1));
			assertEquals(List.of("hook", "selectTab D"), f.calls(), "a Fabric switch runs the hook, then Fabric's rule");
		}
	}

	private static List<String> names(Object tabs) {
		List<String> out = new ArrayList<>();
		for (Object tab : (List<?>) tabs) out.add(tab.toString());
		return out;
	}

	private Fixture fixture(boolean bridged, boolean hooked) throws Exception {
		Path runtime = Path.of(System.getProperty("forbric.test.runtimeClasses", "build/classes/java/runtime"));
		Path pager = runtime.resolve("net/forbric/kernel/runtime/KernelCreativePager.class");
		assumeTrue(Files.isRegularFile(pager), "compile the staged runtime source set before this test: " + pager);
		Map<String, byte[]> defined = new HashMap<>();
		defined.put("net.forbric.kernel.runtime.KernelCreativePager", Files.readAllBytes(pager));
		defined.put("net.forbric.kernel.runtime.KernelCreativePagerScreen",
				Files.readAllBytes(runtime.resolve("net/forbric/kernel/runtime/KernelCreativePagerScreen.class")));
		defined.put(API, CreativePagerFixtures.creativeModule(CreativePagerFixtures.INTERFACE + ".class"));

		Path classes = compileStandIns();
		byte[] screen = CreativePagerFixtures.classTweaked(SCREEN,
				Files.readAllBytes(classes.resolve(SCREEN.replace('.', '/') + ".class")));
		if (bridged) {
			screen = new CreativePagerBridgeInjector(name -> name.equals(CreativePagerFixtures.INTERFACE))
					.transform(SCREEN, screen, null);
		}
		if (hooked) screen = hookUpdateSelection(screen);
		Files.delete(classes.resolve(SCREEN.replace('.', '/') + ".class"));
		defined.put(SCREEN, screen);
		defined.put(PROBE, owoCall());

		URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader()) {
			@Override protected Class<?> findClass(String name) throws ClassNotFoundException {
				byte[] bytes = defined.get(name);
				if (bytes != null) return defineClass(name, bytes, 0, bytes.length);
				return super.findClass(name);
			}
		};
		return new Fixture(loader);
	}

	/** What Mixin makes of a HEAD {@code @Inject} into updateSelection, without the callback: a call first. */
	private static byte[] hookUpdateSelection(byte[] screen) {
		ClassNode node = new ClassNode();
		new ClassReader(screen).accept(node, 0);
		MethodNode update = node.methods.stream().filter(m -> m.name.equals("updateSelection") && m.desc.equals("()V"))
				.findFirst().orElseThrow();
		update.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, "fixture/Trace", "hook", "()V", false));
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** owo's {@code captureSetTab} call, as Mixin retargets it: {@code invokevirtual CreativeModeInventoryScreen.getCurrentPage()I}. */
	private static byte[] owoCall() {
		String screen = SCREEN.replace('.', '/');
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, PROBE.replace('.', '/'), null, "java/lang/Object", null);
		var mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "page", "(Ljava/lang/Object;)I", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitTypeInsn(Opcodes.CHECKCAST, screen);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, screen, "getCurrentPage", "()I", false);
		mv.visitInsn(Opcodes.IRETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private Path compileStandIns() throws Exception {
		Path classes = temporary.resolve("fixture-classes");
		Files.createDirectories(classes);
		List<String> arguments = new ArrayList<>(List.of("--release", "21", "-d", classes.toString()));
		Map<String, String> sources = Map.of(
				"net/minecraft/world/item/CreativeModeTab.java", """
						package net.minecraft.world.item;
						public class CreativeModeTab {
						  private final String name; private final boolean right; private final boolean display;
						  public CreativeModeTab(String name, boolean right, boolean display) { this.name = name; this.right = right; this.display = display; }
						  public boolean isAlignedRight() { return right; }
						  public boolean shouldDisplay() { return display; }
						  @Override public String toString() { return name; }
						}
						""",
				"net/neoforged/neoforge/common/CreativeModeTabRegistry.java", """
						package net.neoforged.neoforge.common;
						import java.util.*;
						import net.minecraft.world.item.CreativeModeTab;
						public final class CreativeModeTabRegistry {
						  public static final List<CreativeModeTab> DEFAULTS = new ArrayList<>();
						  public static List<CreativeModeTab> getDefaultTabs() { return Collections.unmodifiableList(DEFAULTS); }
						}
						""",
				"net/neoforged/neoforge/client/gui/CreativeTabsScreenPage.java", """
						package net.neoforged.neoforge.client.gui;
						import java.util.*;
						import net.minecraft.world.item.CreativeModeTab;
						import net.neoforged.neoforge.common.CreativeModeTabRegistry;
						public final class CreativeTabsScreenPage {
						  private final List<CreativeModeTab> tabs;
						  public CreativeTabsScreenPage(List<CreativeModeTab> tabs) { this.tabs = tabs; }
						  public List<CreativeModeTab> getVisibleTabs() {
						    List<CreativeModeTab> all = new ArrayList<>(tabs); all.addAll(CreativeModeTabRegistry.getDefaultTabs());
						    return all.stream().filter(CreativeModeTab::shouldDisplay).toList();
						  }
						}
						""",
				"net/minecraft/client/gui/components/Button.java", """
						package net.minecraft.client.gui.components;
						public class Button { }
						""",
				"fixture/Trace.java", """
						package fixture;
						public class Trace {
						  public static final java.util.List<String> calls = new java.util.ArrayList<>();
						  public static void hook() { calls.add("hook"); }
						}
						""",
				// The merged screen's pager, as NeoForge patches it in: fields, setter, and the two button lambdas.
				"net/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen.java", """
						package net.minecraft.client.gui.screens.inventory;
						import java.util.*;
						import net.minecraft.client.gui.components.Button;
						import net.minecraft.world.item.CreativeModeTab;
						import net.neoforged.neoforge.client.gui.CreativeTabsScreenPage;
						public class CreativeModeInventoryScreen {
						  private static CreativeModeTab selectedTab;
						  private final List<CreativeTabsScreenPage> pages = new ArrayList<>();
						  private CreativeTabsScreenPage currentPage = new CreativeTabsScreenPage(new ArrayList<>());
						  public CreativeModeInventoryScreen(List<CreativeTabsScreenPage> built, CreativeModeTab selected) {
						    pages.addAll(built); if (!pages.isEmpty()) currentPage = pages.get(0); selectedTab = selected;
						  }
						  public static CreativeModeTab selected() { return selectedTab; }
						  private void selectTab(CreativeModeTab tab) { selectedTab = tab; fixture.Trace.calls.add("selectTab " + tab); }
						  public CreativeTabsScreenPage getCurrentPage() { return currentPage; }
						  public void setCurrentPage(CreativeTabsScreenPage page) { currentPage = page; }
						  private void lambda$init$0(Button button) { setCurrentPage(pages.get(Math.max(pages.indexOf(currentPage) - 1, 0))); }
						  private void lambda$init$1(Button button) { setCurrentPage(pages.get(Math.min(pages.indexOf(currentPage) + 1, pages.size() - 1))); }
						  public void pressPrevious() { lambda$init$0(null); }
						  public void pressNext() { lambda$init$1(null); }
						}
						""");
		for (var source : sources.entrySet()) {
			Path path = temporary.resolve("fixture-src").resolve(source.getKey());
			Files.createDirectories(path.getParent());
			Files.writeString(path, source.getValue());
			arguments.add(path.toString());
		}
		var compiler = ToolProvider.getSystemJavaCompiler();
		assertNotNull(compiler, "the tests require the JDK used to build the kernel");
		ByteArrayOutputStream errors = new ByteArrayOutputStream();
		assertEquals(0, compiler.run(null, null, errors, arguments.toArray(String[]::new)), errors.toString(StandardCharsets.UTF_8));
		return classes;
	}

	/** Two NeoForge pages of two tabs, the four default tabs' stand-ins, and a tab NeoForge shows nowhere. */
	private static final class Fixture implements AutoCloseable {
		final URLClassLoader loader;
		final Class<?> screenClass, tabClass, pageClass, api, trace;
		final Map<String, Object> tabs = new HashMap<>();
		final List<Object> pages = new ArrayList<>();

		Fixture(URLClassLoader loader) throws Exception {
			this.loader = loader;
			screenClass = loader.loadClass(SCREEN);
			tabClass = loader.loadClass(TAB);
			pageClass = loader.loadClass("net.neoforged.neoforge.client.gui.CreativeTabsScreenPage");
			api = loader.loadClass(API);
			trace = loader.loadClass("fixture.Trace");
			for (String[] tab : new String[][] {{"A", "left"}, {"B", "left"}, {"C", "right"}, {"D", "left"},
					{"HIDDEN", "hidden"}, {"SEARCH", "right"}, {"INVENTORY", "right"}}) {
				tabs.put(tab[0], tabClass.getConstructor(String.class, boolean.class, boolean.class)
						.newInstance(tab[0], tab[1].equals("right"), !tab[1].equals("hidden")));
			}
			@SuppressWarnings("unchecked") List<Object> defaults = (List<Object>) loader
					.loadClass("net.neoforged.neoforge.common.CreativeModeTabRegistry").getField("DEFAULTS").get(null);
			defaults.add(tab("SEARCH"));
			defaults.add(tab("INVENTORY"));
			pages.add(pageClass.getConstructor(List.class).newInstance(List.of(tab("A"), tab("B"))));
			pages.add(pageClass.getConstructor(List.class).newInstance(List.of(tab("C"), tab("D"), tab("HIDDEN"))));
		}

		Object tab(String name) { return tabs.get(name); }
		Object page(int index) { return pages.get(index); }

		Object screen() throws Exception {
			return screenClass.getConstructor(List.class, tabClass).newInstance(pages, tab("A"));
		}

		int owoPage(Object screen) throws Exception {
			return (int) loader.loadClass(PROBE).getMethod("page", Object.class).invoke(null, screen);
		}

		/** Through the Fabric interface, as a Fabric mod compiled against fabric-api calls it. */
		Object api(String name, Object screen, Object... args) throws Exception {
			for (Method m : api.getMethods()) {
				if (m.getName().equals(name) && m.getParameterCount() == args.length) return m.invoke(screen, args);
			}
			throw new AssertionError("no " + name + " on " + api);
		}

		/** NeoForge's page-returning {@code getCurrentPage}; by return type, since the screen now also has Fabric's {@code ()I}. */
		Object neoForgePage(Object screen) throws Exception {
			for (Method m : screenClass.getDeclaredMethods()) {
				if (m.getName().equals("getCurrentPage") && m.getReturnType() == pageClass) return m.invoke(screen);
			}
			throw new AssertionError("no NeoForge getCurrentPage on " + screenClass);
		}
		Object selected() throws Exception { return screenClass.getMethod("selected").invoke(null); }
		void press(Object screen, String button) throws Exception { screenClass.getMethod(button).invoke(screen); }
		@SuppressWarnings("unchecked") List<String> calls() throws Exception { return (List<String>) trace.getField("calls").get(null); }
		@Override public void close() throws Exception { loader.close(); }
	}
}
