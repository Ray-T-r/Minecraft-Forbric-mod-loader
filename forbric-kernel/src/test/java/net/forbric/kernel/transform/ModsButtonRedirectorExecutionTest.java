/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * A menu the merge left with one mods button per family shows it once.
 *
 * <p>Seen on a real client: on a base that keeps both families' edits of {@code PauseScreen.createPauseMenu} the pause
 * menu drew "Mods (Forbric)" twice, NeoForge's icon in the icon row and MinecraftForge's full-width button under
 * Options, because each family's body adds its own and the redirector points both at the one unified list. The stand-in
 * menu adds both shapes the real one does — a widget factory of another class whose own lambda opens NeoForge's list,
 * and an inline button whose lambda constructs MinecraftForge's — and is run before and after the pass.
 */
class ModsButtonRedirectorExecutionTest {
	private static final String MENU = "fixture.Menu";
	private static final Path MERGED_BASE = TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final Path NEO_RUNTIME = TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar");
	private static final String NEO = ForeignType.MOD_LIST_SCREEN.internal(Ecosystem.NEOFORGE);
	private static final String FORGE = ForeignType.MOD_LIST_SCREEN.internal(Ecosystem.FORGE);

	/** The statements of {@code Menu.build}, in order; each adds one widget to the layout. */
	private static Map<String, String> standIns(String... build) {
		Map<String, String> sources = new HashMap<>();
		sources.put("net.minecraft.client.gui.screens.Screen", """
				package net.minecraft.client.gui.screens;
				public class Screen { }
				""");
		sources.put("net.minecraftforge.client.gui.ModListScreen", """
				package net.minecraftforge.client.gui;
				public class ModListScreen extends net.minecraft.client.gui.screens.Screen {
					public ModListScreen(net.minecraft.client.gui.screens.Screen parent) { fixture.Trace.opened.add("forge list"); }
				}
				""");
		sources.put("net.neoforged.neoforge.client.gui.modlist.ModListScreen", """
				package net.neoforged.neoforge.client.gui.modlist;
				public class ModListScreen extends net.minecraft.client.gui.screens.Screen {
					public static ModListScreen create(net.minecraft.client.gui.screens.Screen parent) {
						fixture.Trace.opened.add("neoforge list");
						return new ModListScreen();
					}
				}
				""");
		sources.put("net.forbric.kernel.runtime.KernelModListScreen", """
				package net.forbric.kernel.runtime;
				public class KernelModListScreen extends net.minecraft.client.gui.screens.Screen {
					public KernelModListScreen(net.minecraft.client.gui.screens.Screen parent) { fixture.Trace.opened.add("unified list"); }
					public static KernelModListScreen create(net.minecraft.client.gui.screens.Screen parent) {
						return new KernelModListScreen(parent);
					}
				}
				""");
		sources.put("fixture.Trace", """
				package fixture;
				public final class Trace {
					public static final java.util.List<String> opened = new java.util.ArrayList<>();
				}
				""");
		sources.put("fixture.Button", """
				package fixture;
				public class Button {
					public final String label;
					public final Runnable press;
					public Button(String label, Runnable press) { this.label = label; this.press = press; }
				}
				""");
		sources.put("fixture.Layout", """
				package fixture;
				public class Layout {
					public final java.util.List<Button> children = new java.util.ArrayList<>();
					public Button add(Button child) { children.add(child); return child; }
				}
				""");
		// NeoForge's shape: a widget class of its own, built by a static factory, whose press opens the list.
		sources.put("fixture.widget.ModsWidget", """
				package fixture.widget;
				import net.minecraft.client.gui.screens.Screen;
				import net.neoforged.neoforge.client.gui.modlist.ModListScreen;
				public class ModsWidget extends fixture.Button {
					private ModsWidget(Screen parent) { super("icon mods", () -> ModListScreen.create(parent)); }
					public static ModsWidget create(Screen parent) { return new ModsWidget(parent); }
				}
				""");
		// A widget factory of the same shape whose class opens nothing: never a mods button.
		sources.put("fixture.widget.FriendsWidget", """
				package fixture.widget;
				import net.minecraft.client.gui.screens.Screen;
				public class FriendsWidget extends fixture.Button {
					private FriendsWidget(Screen parent) { super("friends", () -> { }); }
					public static FriendsWidget create(Screen parent) { return new FriendsWidget(parent); }
				}
				""");
		sources.put(MENU, """
				package fixture;
				import fixture.widget.FriendsWidget;
				import fixture.widget.ModsWidget;
				public class Menu extends net.minecraft.client.gui.screens.Screen {
					public void build(Layout layout) {
						%s
					}
					void open(net.minecraft.client.gui.screens.Screen screen) { }
				}
				""".formatted(String.join("\n\t\t", build)));
		return sources;
	}

	private static final String BACK = "layout.add(new Button(\"back\", () -> { }));";
	private static final String NEO_ICON = "layout.add(ModsWidget.create(this));";
	private static final String FRIENDS = "layout.add(FriendsWidget.create(this));";
	private static final String OPTIONS = "layout.add(new Button(\"options\", () -> { }));";
	private static final String FORGE_ROW =
			"layout.add(new Button(\"row mods\", () -> open(new net.minecraftforge.client.gui.ModListScreen(this))));";
	private static final String QUIT = "layout.add(new Button(\"quit\", () -> { }));";

	/** Classes as the transform chain would hand them over: the factory's own class, read by name. */
	private static Function<String, ClassNode> lookup(Map<String, byte[]> classes) {
		return owner -> {
			byte[] bytes = classes.get(owner);
			if (bytes == null) return null;
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node, 0);
			return node;
		};
	}

	private record Run(List<String> labels, List<String> opened) { }

	/** Builds the menu, presses every button, and reports what it showed and what each press opened. */
	private static Run run(ClassLoader loader) throws Throwable {
		Object layout = InjectorExecution.construct(loader.loadClass("fixture.Layout"));
		InjectorExecution.invoke(InjectorExecution.construct(loader.loadClass(MENU)), "build", layout);
		List<String> labels = new ArrayList<>();
		for (Object child : (List<?>) layout.getClass().getField("children").get(layout)) {
			labels.add((String) child.getClass().getField("label").get(child));
			((Runnable) child.getClass().getField("press").get(child)).run();
		}
		@SuppressWarnings("unchecked")
		List<String> opened = (List<String>) InjectorExecution.getStatic(loader.loadClass("fixture.Trace"), "opened");
		return new Run(labels, List.copyOf(opened));
	}

	private static Map<String, byte[]> transformed(Map<String, byte[]> original, Function<String, ClassNode> classes) {
		Map<String, byte[]> out = new HashMap<>(original);
		ModsButtonRedirector redirector = new ModsButtonRedirector(classes);
		for (Map.Entry<String, byte[]> e : original.entrySet()) {
			if (e.getKey().startsWith("net/forbric/")) continue;
			out.put(e.getKey(), InjectorExecution.transform(redirector, e.getKey().replace('/', '.'), e.getValue(), EnvType.CLIENT));
		}
		return out;
	}

	@Test void aMenuThatAddsOneModsButtonPerFamilyShowsTheFirstOnce(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = InjectorExecution.compile(work, standIns(BACK, NEO_ICON, FRIENDS, OPTIONS, FORGE_ROW, QUIT));
		assertEquals(new Run(List.of("back", "icon mods", "friends", "options", "row mods", "quit"),
						List.of("neoforge list", "forge list")), run(InjectorExecution.load(original)),
				"premise: as merged, the menu shows a mods button per family, each opening its own family's list");

		Map<String, byte[]> classes = transformed(original, lookup(original));
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(classes.get("fixture/Menu"), loader));
		assertEquals(new Run(List.of("back", "icon mods", "friends", "options", "quit"), List.of("unified list")), run(loader),
				"the first mods button stays and opens the unified list; the later one is gone with nothing around it");
		byte[] menu = classes.get("fixture/Menu");
		assertSame(menu, new ModsButtonRedirector(lookup(original)).transform(MENU, menu, null),
				"a second pass finds one button and nothing left to re-point");
	}

	/**
	 * What the pass says it re-pointed is what a player can press. The dropped button's lambda is left in the class with
	 * nothing referring to it, so a construction in it is no re-pointed button: the one report of a re-pointed
	 * construction is the kept button's, in its widget class, and the menu reports only the drop.
	 */
	@Test void onlyAConstructionAPlayerCanStillReachIsReportedAsRePointed(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = InjectorExecution.compile(work, standIns(BACK, NEO_ICON, FRIENDS, OPTIONS, FORGE_ROW, QUIT));
		String log = capture(() -> transformed(original, lookup(original)));
		List<String> rePointed = new ArrayList<>();
		for (String line : log.split("\\R")) if (line.contains("now opens the unified list")) rePointed.add(line);
		assertEquals(1, rePointed.size(), log);
		assertTrue(rePointed.get(0).contains("fixture/widget/ModsWidget's mods button"), rePointed.get(0));
		assertTrue(log.contains("fixture/Menu.build added 2 mods buttons"), log);
	}

	@Test void whicheverFamilysButtonComesFirstIsTheOneKept(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = InjectorExecution.compile(work, standIns(BACK, FORGE_ROW, OPTIONS, NEO_ICON, QUIT));
		Map<String, byte[]> classes = transformed(original, lookup(original));
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(classes.get("fixture/Menu"), loader));
		assertEquals(new Run(List.of("back", "row mods", "options", "quit"), List.of("unified list")), run(loader));
	}

	@Test void aMenuWithOneModsButtonKeepsEveryWidget(@TempDir Path work) throws Throwable {
		for (String only : List.of(NEO_ICON, FORGE_ROW)) {
			Map<String, byte[]> original = InjectorExecution.compile(work.resolve(only.length() + ""),
					standIns(BACK, only, FRIENDS, OPTIONS, QUIT));
			List<String> before = run(InjectorExecution.load(original)).labels();
			Run after = run(InjectorExecution.load(transformed(original, lookup(original))));
			assertEquals(before, after.labels(), "one mods button is the menu as it should be: " + only);
			assertEquals(List.of("unified list"), after.opened(), "and it is still re-pointed: " + only);
		}
	}

	/** Without the factory's class there is no evidence the widget is a mods button, and nothing is dropped. */
	@Test void aFactoryWhoseClassCannotBeReadIsNotCountedAsAModsButton(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = InjectorExecution.compile(work, standIns(BACK, NEO_ICON, OPTIONS, FORGE_ROW, QUIT));
		Run after = run(InjectorExecution.load(transformed(original, owner -> null)));
		assertEquals(List.of("back", "icon mods", "options", "row mods", "quit"), after.labels());
	}

	/**
	 * A later button whose statement also stores a local is left in place: dropping that span would take a value a
	 * later instruction may read. The method keeps running, and the report says the menu still shows two.
	 */
	@Test void aDuplicateThatCannotBeDroppedSafelyIsLeftAndReported(@TempDir Path work) throws Throwable {
		String stored = "Button mods = new Button(\"row mods\", () -> open(new net.minecraftforge.client.gui.ModListScreen(this)));"
				+ " layout.add(mods);";
		Map<String, byte[]> original = InjectorExecution.compile(work, standIns(BACK, NEO_ICON, OPTIONS, stored, QUIT));
		Map<String, byte[]>[] holder = new Map[1];
		String log = capture(() -> holder[0] = transformed(original, lookup(original)));
		ClassLoader loader = InjectorExecution.load(holder[0]);
		assertEquals("", InjectorExecution.verify(holder[0].get("fixture/Menu"), loader));
		assertEquals(List.of("back", "icon mods", "options", "row mods", "quit"), run(loader).labels());
		assertTrue(log.contains("could not be dropped safely"), log);
	}

	/**
	 * The staged base, whichever it is: transformed, its pause menu builds exactly one mods button — on a base that
	 * kept NeoForge's alone and on one that kept both families' edits — and the method still verifies.
	 */
	@Test void theStagedPauseMenuBuildsOneModsButton() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE) && Files.isRegularFile(NEO_RUNTIME),
				"staged jars absent — skipping real-bytecode check");
		String pause = "net/minecraft/client/gui/screens/PauseScreen";
		byte[] in = read(MERGED_BASE, pause + ".class");
		Function<String, ClassNode> classes = owner -> {
			try {
				byte[] bytes = read(NEO_RUNTIME, owner + ".class");
				ClassNode node = new ClassNode();
				new ClassReader(bytes).accept(node, 0);
				return node;
			} catch (Exception | AssertionError absent) {
				return null;
			}
		};
		ClassNode before = parse(in);
		byte[][] out = new byte[1][];
		String log = capture(() -> out[0] = new ModsButtonRedirector(classes).transform(pause.replace('/', '.'), in, null));
		ClassNode after = parse(out[0]);
		int was = modsButtons(before, "createPauseMenu", classes);
		assertTrue(was >= 1, "the staged pause menu builds a mods button at all");
		assertEquals(1, modsButtons(after, "createPauseMenu", classes),
				"transformed, the pause menu builds exactly one (the base built " + was + ")");
		// The kept button is NeoForge's factory, whose screen is built in its own class; a dropped one's lambda is left
		// with nothing referring to it. So the pause menu itself re-points no construction a player can reach, on a base
		// that kept one button and on one that kept both, and its report says only what it dropped.
		assertFalse(log.contains(pause + "'s mods button now opens the unified list"), log);
		assertEquals(was > 1, log.contains(pause + ".createPauseMenu added " + was + " mods buttons"), log);
		for (MethodNode m : after.methods) new Analyzer<>(new BasicVerifier()).analyze(after.name, m);
	}

	// --- helpers -------------------------------------------------------------------------------------------

	/** Mods buttons a method builds: lambdas of the class that open a list, and factories whose class does. */
	private static int modsButtons(ClassNode node, String methodName, Function<String, ClassNode> classes) {
		int count = 0;
		for (MethodNode m : node.methods) {
			if (!m.name.equals(methodName)) continue;
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof InvokeDynamicInsnNode indy && indy.bsmArgs.length >= 2
						&& indy.bsmArgs[1] instanceof Handle impl && impl.getOwner().equals(node.name)) {
					for (MethodNode body : node.methods) {
						if (body.name.equals(impl.getName()) && body.desc.equals(impl.getDesc()) && opens(body)) count++;
					}
				} else if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
						&& !call.owner.equals(node.name) && call.desc.equals("(Lnet/minecraft/client/gui/screens/Screen;)L" + call.owner + ";")) {
					ClassNode owner = classes.apply(call.owner);
					if (owner != null && owner.methods.stream().anyMatch(ModsButtonRedirectorExecutionTest::opens)) count++;
				}
			}
		}
		return count;
	}

	private static boolean opens(MethodNode m) {
		for (AbstractInsnNode insn : m.instructions) {
			if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
					&& (NEO.equals(type.desc) || FORGE.equals(type.desc) || ModsButtonRedirector.KERNEL_SCREEN.equals(type.desc))) return true;
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC && "create".equals(call.name)
					&& (NEO.equals(call.owner) || FORGE.equals(call.owner) || ModsButtonRedirector.KERNEL_SCREEN.equals(call.owner))) return true;
		}
		return false;
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] read(Path jar, String entry) throws Exception {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry e = zip.getEntry(entry);
			assertNotNull(e, entry + " must be in " + jar.getFileName());
			try (InputStream in = zip.getInputStream(e)) {
				return in.readAllBytes();
			}
		}
	}

	private static String capture(Runnable body) {
		java.io.PrintStream out = System.out, err = System.err;
		java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
		java.io.PrintStream sink = new java.io.PrintStream(buffer, true, java.nio.charset.StandardCharsets.UTF_8);
		System.setOut(sink);
		System.setErr(sink);
		try {
			body.run();
		} finally {
			System.setOut(out);
			System.setErr(err);
		}
		return buffer.toString(java.nio.charset.StandardCharsets.UTF_8);
	}
}
