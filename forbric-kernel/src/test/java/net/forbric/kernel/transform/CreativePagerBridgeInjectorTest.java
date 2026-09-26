/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
		return new CreativePagerBridgeInjector(name -> installed && name.equals(CreativePagerBridgeInjector.API));
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
