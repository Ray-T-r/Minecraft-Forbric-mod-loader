/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.kernel.mixin.MixinFit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * FabricCreativeModeInventoryScreen on the real merged creative screen, after the real class tweaker that injects it:
 * with nothing behind it before the bridge, every method the interface leaves to its implementer present after.
 */
@ResourceLock("system-properties")
class CreativePagerBridgeInjectorTest {
	private static final Path STAGED = Path.of(System.getProperty("forbric.stagedRoot", "../forbric-loader/run"));
	private static final Path MERGED = STAGED.resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final String SCREEN = CreativePagerBridgeInjector.SCREEN;

	@AfterEach void reset() { System.clearProperty(CreativePagerBridgeInjector.PROPERTY); }

	private static CreativePagerBridgeInjector bridge(boolean installed) {
		return bridge(installed, true);
	}

	/** The bridge over fabric-api's real interface, installed or not, with Fabric's own mixin pinned or not. */
	private static CreativePagerBridgeInjector bridge(boolean installed, boolean pinned) {
		return new CreativePagerBridgeInjector(name -> {
			if (!installed || !name.equals(CreativePagerBridgeInjector.API)) return null;
			try {
				return CreativePagerFixtures.creativeModule(CreativePagerBridgeInjector.API + ".class");
			} catch (Exception unreadable) {
				throw new AssertionError(unreadable);
			}
		}, () -> pinned);
	}

	/** The screen as the COREMOD phase receives it on a client with fabric-api: merged, then class-tweaked. */
	private static byte[] tweakedScreen() throws Exception {
		return CreativePagerFixtures.classTweaked(CreativePagerBridgeInjector.SCREEN_BINARY,
				NativeCoremodParityTest.read(MERGED, SCREEN));
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static ClassNode contract() throws Exception {
		return MixinFit.parse(CreativePagerFixtures.creativeModule(CreativePagerBridgeInjector.API + ".class"));
	}

	@Test void theInterfaceIsAlreadyOnTheScreenWithNothingBehindItAndTheBridgeSuppliesEveryMethod() throws Exception {
		byte[] tweaked = tweakedScreen();
		ClassNode before = node(tweaked);
		assertTrue(before.interfaces.contains(CreativePagerBridgeInjector.API),
				"fabric-api's class tweaker injects the interface whatever the kernel pins — so the cast works");
		List<String> missing = MixinFit.unsupplied(contract(), before);
		assertEquals(8, missing.size(), "every method Fabric's pinned mixin implements is the throwing default: " + missing);
		assertTrue(missing.contains("getCurrentPage()I"), "owo's call: " + missing);

		byte[] out = bridge(true).transform(CreativePagerBridgeInjector.SCREEN_BINARY, tweaked, null);
		assertNotSame(tweaked, out);
		ClassNode after = node(out);
		assertEquals(List.of(), MixinFit.unsupplied(contract(), after));
		assertTrue(after.interfaces.contains(CreativePagerBridgeInjector.PAGER_SCREEN));
		assertEquals(1, after.interfaces.stream().filter(CreativePagerBridgeInjector.API::equals).count(), "added once");

		MethodNode update = method(after, "updateSelection", "()V");
		assertNotNull(update, "owo's @Inject(method = \"updateSelection\") needs Fabric's private hook point");
		assertEquals(Opcodes.ACC_PRIVATE, update.access & Opcodes.ACC_PRIVATE);

		// Mixin binds a descriptor-less selector to the FIRST method of a name: NeoForge's must stay first.
		int neo = indexOf(after, "getCurrentPage", "()Lnet/neoforged/neoforge/client/gui/CreativeTabsScreenPage;");
		int fabric = indexOf(after, "getCurrentPage", "()I");
		assertTrue(neo >= 0 && neo < fabric, "NeoForge's getCurrentPage at " + neo + ", Fabric's at " + fabric);

		for (MethodNode method : after.methods) new Analyzer<>(new BasicVerifier()).analyze(SCREEN, method);
	}

	@Test void neoForgesTwoPageButtonsAnnounceTheTurnRightAfterSettingThePage() throws Exception {
		ClassNode after = node(bridge(true).transform(CreativePagerBridgeInjector.SCREEN_BINARY, tweakedScreen(), null));
		List<String> buttons = new ArrayList<>();
		for (MethodNode method : after.methods) {
			if (!method.desc.equals(CreativePagerBridgeInjector.BUTTON_HANDLER)) continue;
			List<String> calls = new ArrayList<>();
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && (call.name.equals("setCurrentPage") || call.name.equals("pageTurned"))) {
					calls.add(call.name);
				}
			}
			if (!calls.isEmpty()) buttons.add(method.name + calls);
		}
		assertEquals(List.of("lambda$init$1[setCurrentPage, pageTurned]", "lambda$init$0[setCurrentPage, pageTurned]"), buttons);
	}

	@Test void nothingChangesTwiceWithoutTheModuleOrWithTheSwitchOff() throws Exception {
		byte[] tweaked = tweakedScreen();
		byte[] once = bridge(true).transform(CreativePagerBridgeInjector.SCREEN_BINARY, tweaked, null);
		assertSame(once, bridge(true).transform(CreativePagerBridgeInjector.SCREEN_BINARY, once, null), "a second pass");

		byte[] merged = NativeCoremodParityTest.read(MERGED, SCREEN);
		assertSame(merged, bridge(false).transform(CreativePagerBridgeInjector.SCREEN_BINARY, merged, null),
				"no creative-tab module installed: no interface to back");
		assertTrue(scanned(bridge(false).anchors()));

		System.setProperty(CreativePagerBridgeInjector.PROPERTY, "off");
		assertSame(tweaked, bridge(true).transform(CreativePagerBridgeInjector.SCREEN_BINARY, tweaked, null));
		assertTrue(scanned(bridge(true).anchors()));
	}

	/**
	 * With the pin lifted, Fabric's own mixin implements the interface. Over a bridged screen it would replace the eight
	 * bodies (the kernel relaxes guest configs; strict, {@code "overwrites": {"requireAnnotations": true}} refuses to
	 * apply it at all) and its private updateSelection would be renamed around the kernel's, leaving owo hooked on a
	 * method Fabric's switch never calls. So the bridge stands aside and the kill switch gives the old screen back.
	 */
	@Test void withFabricsOwnMixinUnpinnedTheBridgeStandsAside() throws Exception {
		byte[] tweaked = tweakedScreen();
		assertSame(tweaked, bridge(true, false).transform(CreativePagerBridgeInjector.SCREEN_BINARY, tweaked, null));
		AnchorSet anchors = bridge(true, false).anchors();
		assertTrue(scanned(anchors) && anchors.scanNote().contains("not pinned"), String.valueOf(anchors.scanNote()));
		assertFalse(scanned(bridge(true, true).anchors()), "pinned, the screen is a REQUIRED anchor");
	}

	/** A carrier whose page buttons are not NeoForge's two keeps the crash fix; only the turn announcement goes. */
	@Test void aReshapedPagerKeepsTheInterfaceBodiesAndOnlyLosesTheTurnAnnouncement() throws Exception {
		ClassNode reshaped = node(tweakedScreen());
		// A third page button that sets the page once, as a carrier with a "first page" button would have.
		MethodNode first = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC, "lambda$init$2",
				CreativePagerBridgeInjector.BUTTON_HANDLER, null, null);
		first.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
		first.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
		first.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETFIELD, SCREEN, "currentPage",
				CreativePagerBridgeInjector.PAGE));
		first.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SCREEN, "setCurrentPage",
				"(" + CreativePagerBridgeInjector.PAGE + ")V", false));
		first.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
		reshaped.methods.add(first);
		assertEquals(3, CreativePagerBridgeInjector.pageButtonTurns(reshaped).size());

		CreativePagerBridgeInjector.Repair done = CreativePagerBridgeInjector.repair(reshaped,
				MixinFit.implementerSupplies(contract()));
		assertNotNull(done, "the interface bodies do not depend on the buttons");
		assertFalse(done.turnsAnnounced());
		assertEquals(List.of(), MixinFit.unsupplied(contract(), reshaped), "no call through the interface throws");
		assertNotNull(method(reshaped, "updateSelection", "()V"), "owo's hook still binds, and Fabric switches run it");
		for (MethodNode method : reshaped.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				assertFalse(insn instanceof MethodInsnNode call && call.name.equals("pageTurned"), method.name + " announces a turn");
			}
		}
	}

	/**
	 * What the bridge backs is read off the installed interface, not assumed: a method a newer fabric-api leaves to its
	 * implementer is named rather than reported as backed, and one it implements itself is left to it.
	 */
	@Test void theBackedMethodsFollowTheInstalledInterface() throws Exception {
		Set<String> needed = new LinkedHashSet<>(MixinFit.implementerSupplies(contract()));
		assertEquals(8, needed.size(), needed.toString());
		needed.add("scrollToTab(Lnet/minecraft/world/item/CreativeModeTab;)Z"); // a newer fabric-api's
		needed.remove("getSelectedTab()Lnet/minecraft/world/item/CreativeModeTab;"); // a working default there

		ClassNode screen = node(tweakedScreen());
		CreativePagerBridgeInjector.Repair done = CreativePagerBridgeInjector.repair(screen, needed);
		assertNotNull(done);
		assertEquals(List.of("scrollToTab(Lnet/minecraft/world/item/CreativeModeTab;)Z"), done.uncovered());
		assertNull(method(screen, "getSelectedTab", "()Lnet/minecraft/world/item/CreativeModeTab;"),
				"the interface's own default answers it");
		assertNotNull(method(screen, "getCurrentPage", "()I"));
		assertTrue(done.turnsAnnounced());
	}

	@Test void owoLibsMixinFitsOnlyOnceTheBridgeRan() throws Exception {
		byte[] owo = CreativePagerFixtures.owoClass(CreativePagerFixtures.OWO_MIXIN);
		// The crashing call is exactly this instruction, retargeted by Mixin to the screen.
		ClassNode mixin = node(owo);
		MethodInsnNode call = null;
		for (AbstractInsnNode insn : method(mixin, "captureSetTab",
				"(Lnet/minecraft/world/item/CreativeModeTab;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V").instructions) {
			if (insn instanceof MethodInsnNode m && m.name.equals("getCurrentPage")) call = m;
		}
		assertNotNull(call);
		assertEquals(Opcodes.INVOKEVIRTUAL, call.getOpcode());
		assertEquals("()I", call.desc);

		byte[] tweaked = tweakedScreen();
		MixinFit.Result unbacked = MixinFit.evaluate(owo, resolver(tweaked));
		assertEquals(MixinFit.Verdict.PARTIAL, unbacked.verdict(), unbacked.unresolved().toString());
		assertTrue(unbacked.unresolved().stream().anyMatch(r -> r.contains("updateSelection")), unbacked.unresolved().toString());

		byte[] backed = bridge(true).transform(CreativePagerBridgeInjector.SCREEN_BINARY, tweaked, null);
		MixinFit.Result fit = MixinFit.evaluate(owo, resolver(backed));
		assertEquals(MixinFit.Verdict.FIT, fit.verdict(), fit.unresolved().toString());
		assertEquals(3, fit.total(), "@Shadow selectTab, @Inject selectTab, @Inject updateSelection");
	}

	private static Function<String, byte[]> resolver(byte[] screen) {
		return path -> {
			if (path.equals(SCREEN + ".class")) return screen;
			try (ZipFile zip = new ZipFile(MERGED.toFile())) {
				ZipEntry entry = zip.getEntry(path);
				return entry == null ? null : zip.getInputStream(entry).readAllBytes();
			} catch (Exception unreadable) {
				return null;
			}
		};
	}

	private static boolean scanned(AnchorSet anchors) {
		return anchors.anchors().isEmpty() && anchors.scanNote() != null;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
		return null;
	}

	private static int indexOf(ClassNode node, String name, String desc) {
		for (int i = 0; i < node.methods.size(); i++) {
			if (node.methods.get(i).name.equals(name) && node.methods.get(i).desc.equals(desc)) return i;
		}
		return -1;
	}
}
