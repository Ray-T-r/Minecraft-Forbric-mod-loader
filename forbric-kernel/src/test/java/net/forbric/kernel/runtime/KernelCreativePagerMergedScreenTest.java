/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.kernel.transform.CreativePagerBridgeInjector;
import net.forbric.kernel.transform.CreativePagerFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

/**
 * The creative pager on the REAL merged screen, in a real JVM: the merged {@code CreativeModeInventoryScreen}, put
 * through fabric-api's own class tweaker and the bridge, linked — and so verified — against the merged game, NeoForge's
 * carrier and fabric-api's interface, then called the way owo-lib and a Fabric mod call it, and paged with NeoForge's
 * own button lambdas.
 *
 * <p>{@link KernelCreativePagerTest} proves the pager's rules on a stand-in screen; this proves the bytes the client
 * gets. A LinkageError here (a descriptor, an interface call or a trampoline the JVM refuses) is what a live client would
 * die of the moment the creative inventory opens. Two things are left out, nothing else: the screen's class initializer
 * (it asks the item registries for the default tab, which needs a bootstrapped game) and its constructor (it needs a
 * player). The screen is allocated bare and handed NeoForge's pages, as its {@code init} builds them.
 */
@ResourceLock("system-properties")
class KernelCreativePagerMergedScreenTest {
	private static final Path RUN = Path.of(System.getProperty("forbric.stagedRoot", "../forbric-loader/run"));
	private static final String SCREEN = "net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen";
	private static final String API = CreativePagerFixtures.INTERFACE.replace('/', '.');
	private static final String PROBE = "fixture.OwoCall";

	@Test void theBridgedMergedScreenLinksAndAnswersThroughTheFabricInterface() throws Exception {
		try (URLClassLoader loader = gameLoader()) {
			Class<?> screenClass = Class.forName(SCREEN, false, loader);
			assertTrue(screenClass.getDeclaredMethods().length > 0, "linked: the JVM verified every method of the screen");
			Class<?> api = Class.forName(API, false, loader);
			Class<?> tabClass = Class.forName("net.minecraft.world.item.CreativeModeTab", false, loader);
			Class<?> pageClass = Class.forName("net.neoforged.neoforge.client.gui.CreativeTabsScreenPage", false, loader);

			Map<String, Object> tabs = new HashMap<>();
			for (String[] tab : new String[][] {{"A", "CATEGORY", "left"}, {"B", "CATEGORY", "left"}, {"C", "CATEGORY", "right"},
					{"D", "CATEGORY", "left"}, {"HIDDEN", "CATEGORY", "empty"}, {"SEARCH", "SEARCH", "right"},
					{"INVENTORY", "INVENTORY", "right"}}) {
				tabs.put(tab[0], tab(loader, tabClass, tab[1], tab[2]));
			}
			@SuppressWarnings("unchecked") List<Object> defaults = (List<Object>) field(
					Class.forName("net.neoforged.neoforge.common.CreativeModeTabRegistry", true, loader), "DEFAULT_TABS").get(null);
			defaults.add(tabs.get("SEARCH"));
			defaults.add(tabs.get("INVENTORY"));
			Object first = pageClass.getConstructor(List.class).newInstance(List.of(tabs.get("A"), tabs.get("B")));
			Object second = pageClass.getConstructor(List.class).newInstance(List.of(tabs.get("C"), tabs.get("D"), tabs.get("HIDDEN")));

			Object screen = unsafe().allocateInstance(screenClass);
			field(screenClass, "pages").set(screen, new ArrayList<>(List.of(first, second)));
			field(screenClass, "currentPage").set(screen, first);
			// A default tab is drawn on every page, so Fabric's rule keeps it and the private selectTab — which needs the
			// menu a real init builds — is never reached.
			field(screenClass, "selectedTab").set(null, tabs.get("SEARCH"));
			Field current = field(screenClass, "currentPage");

			assertTrue(api.isInstance(screen), "fabric-api's class tweaker injected the interface");
			Method owo = Class.forName(PROBE, true, loader).getMethod("page", Object.class);
			assertEquals(0, owo.invoke(null, screen), "owo's invokevirtual getCurrentPage()I at the tail of selectTab");
			assertEquals(0, call(api, screen, "getCurrentPage"));
			assertEquals(2, call(api, screen, "getPageCount"));
			assertEquals(true, call(api, screen, "hasAdditionalPages"));
			assertEquals(List.of(tabs.get("A"), tabs.get("B"), tabs.get("SEARCH"), tabs.get("INVENTORY")),
					call(api, screen, "getTabsOnPage", 0));
			assertEquals(List.of(tabs.get("C"), tabs.get("D")), call(api, screen, "getTabsOnPage", 1));
			assertEquals(1, call(api, screen, "getPage", tabs.get("D")));
			assertEquals(-1, call(api, screen, "getPage", tabs.get("HIDDEN")));
			assertSame(tabs.get("SEARCH"), call(api, screen, "getSelectedTab"));

			assertEquals(true, call(api, screen, "switchToPage", 1));
			assertSame(second, current.get(screen), "NeoForge's own page moved");
			assertEquals(1, owo.invoke(null, screen));
			assertEquals(false, call(api, screen, "switchToPage", 1), "already there");
			assertEquals(false, call(api, screen, "switchToPage", 5));
			assertEquals(true, call(api, screen, "switchToPreviousPage"), "the interface's own default, through the bodies");
			assertSame(first, current.get(screen));
			assertEquals(false, call(api, screen, "setSelectedTab", tabs.get("SEARCH")), "already selected");

			// NeoForge's "<" and ">" buttons, with the turn announcement the bridge put after their setCurrentPage.
			Class<?> button = Class.forName("net.minecraft.client.gui.components.Button", false, loader);
			Method next = screenClass.getDeclaredMethod("lambda$init$1", button);
			Method previous = screenClass.getDeclaredMethod("lambda$init$0", button);
			next.setAccessible(true);
			previous.setAccessible(true);
			next.invoke(screen, (Object) null);
			assertSame(second, current.get(screen));
			assertEquals(1, call(api, screen, "getCurrentPage"));
			previous.invoke(screen, (Object) null);
			assertSame(first, current.get(screen));
			assertSame(tabs.get("SEARCH"), field(screenClass, "selectedTab").get(null), "NeoForge's buttons keep the selection");
		}
	}

	/** A real tab without a registry: its type, alignment, and whether it has items (a category shows only then). */
	private static Object tab(ClassLoader loader, Class<?> tabClass, String type, String look) throws Exception {
		Object tab = unsafe().allocateInstance(tabClass);
		@SuppressWarnings({"unchecked", "rawtypes"}) Object kind = Enum.valueOf((Class) Class.forName(
				"net.minecraft.world.item.CreativeModeTab$Type", true, loader), type);
		field(tabClass, "type").set(tab, kind);
		field(tabClass, "alignedRight").set(tab, look.equals("right"));
		field(tabClass, "displayItems").set(tab, look.equals("empty") ? List.of() : List.of("an item"));
		return tab;
	}

	/** Through the Fabric interface, as a mod compiled against fabric-api calls it. */
	private static Object call(Class<?> api, Object screen, String name, Object... args) throws Exception {
		for (Method m : api.getMethods()) {
			if (m.getName().equals(name) && m.getParameterCount() == args.length) return m.invoke(screen, args);
		}
		throw new AssertionError("no " + name + " on " + api);
	}

	private static Field field(Class<?> owner, String name) throws Exception {
		Field f = owner.getDeclaredField(name);
		f.setAccessible(true);
		return f;
	}

	private static sun.misc.Unsafe unsafe() throws Exception {
		Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
		f.setAccessible(true);
		return (sun.misc.Unsafe) f.get(null);
	}

	/** The merged game, both carriers, the libraries they link against, the compiled pager, and the bridged screen. */
	private static URLClassLoader gameLoader() throws Exception {
		Path compiled = Path.of(System.getProperty("forbric.test.runtimeClasses", "build/classes/java/runtime"));
		Path merged = RUN.resolve("merged-base/patched-mc-merged-26.2.jar");
		Path neo = RUN.resolve("neoforge-runtime/neoforge-runtime.jar");
		Path forge = RUN.resolve("merged-base/forge-runtime-interop.jar");
		assumeTrue(Files.isRegularFile(compiled.resolve("net/forbric/kernel/runtime/KernelCreativePager.class"))
				&& Files.isRegularFile(merged) && Files.isRegularFile(neo) && Files.isRegularFile(forge),
				"the staged game or the compiled runtime set is absent");
		Map<String, byte[]> defined = new HashMap<>();
		byte[] api = CreativePagerFixtures.creativeModule(CreativePagerFixtures.INTERFACE + ".class");
		defined.put(API, api);
		defined.put(SCREEN, bridgedScreen(merged, api));
		defined.put(PROBE, owoCall());

		List<URL> urls = new ArrayList<>(List.of(compiled.toUri().toURL(), forge.toUri().toURL(), neo.toUri().toURL(),
				merged.toUri().toURL()));
		for (String pattern : List.of("com/mojang/datafixerupper", "com/google/code/gson", "com/mojang/brigadier",
				"com/google/guava/guava", "it/unimi/dsi/fastutil", "org/slf4j/slf4j-api", "org/apache/logging/log4j/log4j-api",
				"io/netty/netty-common", "io/netty/netty-buffer", "io/netty/netty-codec", "io/netty/netty-transport",
				"io/netty/netty-handler", "org/joml/joml", "com/mojang/authlib", "org/apache/commons/commons-lang3",
				"com/mojang/logging")) {
			Path library = newestUnder(pattern);
			assumeTrue(library != null, "no " + pattern + " jar in the local Minecraft libraries");
			urls.add(library.toUri().toURL());
		}
		for (String pattern : List.of("org/apache/logging/log4j/log4j-core", "org/apache/logging/log4j/log4j-slf4j2-impl",
				"org/lwjgl/lwjgl/", "org/lwjgl/lwjgl-glfw", "org/lwjgl/lwjgl-opengl")) {
			Path library = newestUnder(pattern);
			if (library != null) urls.add(library.toUri().toURL());
		}
		return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader()) {
			@Override protected Class<?> findClass(String name) throws ClassNotFoundException {
				byte[] bytes = defined.get(name);
				if (bytes != null) return defineClass(name, bytes, 0, bytes.length);
				return super.findClass(name);
			}
		};
	}

	/** The screen as Mixin receives it on a client with fabric-api, less its class initializer (see the class comment). */
	private static byte[] bridgedScreen(Path merged, byte[] api) throws Exception {
		byte[] bytes;
		try (ZipFile zip = new ZipFile(merged.toFile())) {
			ZipEntry entry = zip.getEntry(SCREEN.replace('.', '/') + ".class");
			assertNotNull(entry, "the merged base has no creative screen");
			bytes = zip.getInputStream(entry).readAllBytes();
		}
		byte[] tweaked = CreativePagerFixtures.classTweaked(SCREEN, bytes);
		byte[] bridged = new CreativePagerBridgeInjector(name -> name.equals(CreativePagerFixtures.INTERFACE) ? api : null, () -> true)
				.transform(SCREEN, tweaked, null);
		assertNotSame(tweaked, bridged, "the bridge took the merged screen");
		ClassNode node = new ClassNode();
		new ClassReader(bridged).accept(node, 0);
		assertTrue(node.methods.removeIf(m -> m.name.equals("<clinit>")));
		ClassWriter writer = new ClassWriter(0);
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

	private static Path newestUnder(String pattern) throws java.io.IOException {
		String env = System.getenv("MC_DIR");
		Path root = Path.of(env != null ? env + "/libraries" : System.getProperty("user.home") + "/Library/Application Support/minecraft/libraries");
		Path under = root.resolve(pattern);
		if (!Files.isDirectory(under)) return null;
		try (var stream = Files.walk(under)) {
			return stream.filter(f -> f.toString().endsWith(".jar") && !f.toString().contains("sources") && !f.toString().contains("natives"))
					.sorted(java.util.Comparator.comparing(f -> f.getFileName().toString())).reduce((a, b) -> b).orElse(null);
		}
	}
}
