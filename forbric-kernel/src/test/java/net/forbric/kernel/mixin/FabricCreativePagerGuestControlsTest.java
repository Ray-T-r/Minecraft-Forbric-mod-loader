/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.transform.CreativePagerFixtures;

/**
 * Whose page controls are drawn, decided once from what is installed, and what that does to fabric-creative-tab-api's
 * real pager mixin: with only Fabric mods installed its button-adding injector is kept with its page-state write
 * dropped; with any Forge-family mod installed it is taken out with the rest of the second pager, as before.
 */
@ResourceLock("system-properties")
class FabricCreativePagerGuestControlsTest {
	private static final Path MERGED = TestFixtures.stagedBase();

	@BeforeEach @AfterEach void reset() {
		FabricCreativePagerMixinAdapter.forgetSelectionHooks();
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static ClassNode copy(ClassNode node) {
		ClassNode copy = new ClassNode();
		node.accept(copy);
		return copy;
	}

	private static ClassNode mergedScreen() {
		return node(TestFixtures.requireEntry(Fixture.STAGED, MERGED, FabricCreativePagerMixinAdapter.TARGET + ".class"));
	}

	private static Function<String, ClassNode> classes(ClassNode api, ClassNode screen) {
		return name -> name.equals(CreativePagerFixtures.INTERFACE) ? api : name.equals(FabricCreativePagerMixinAdapter.TARGET) ? screen : null;
	}

	private static MethodNode method(ClassNode node, String name) {
		return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElse(null);
	}

	@Test void withTheGuestsControlsDrawnItsButtonInjectorStaysWithoutItsPageWrite() throws Exception {
		ClassNode pager = node(CreativePagerFixtures.creativeModule(CreativePagerFixtures.PINNED_MIXIN + ".class"));
		ClassNode api = node(CreativePagerFixtures.creativeModule(CreativePagerFixtures.INTERFACE + ".class"));
		ClassNode screen = mergedScreen();

		ClassNode drawn = copy(pager);
		var result = FabricCreativePagerMixinAdapter.adapt(drawn, classes(api, screen), true);
		assertEquals(1, result.changed());
		assertEquals(List.of("init"), result.keptWriters(), "the one injector that writes the page and reads none of it");
		MethodNode init = method(drawn, "init");
		assertNotNull(init, "kept");
		boolean writes = false, adds = false;
		for (AbstractInsnNode insn : init.instructions) {
			if (insn instanceof FieldInsnNode f && f.owner.equals(drawn.name) && (f.getOpcode() == Opcodes.PUTSTATIC || f.getOpcode() == Opcodes.PUTFIELD)) writes = true;
			if (insn instanceof MethodInsnNode m && m.name.equals("addRenderableWidget")) adds = true;
		}
		assertFalse(writes, "its write of the second page state is dropped");
		assertTrue(adds, "and it still adds its page buttons");
		assertTrue(drawn.fields.stream().noneMatch(f -> f.desc.equals("I") && (f.access & Opcodes.ACC_STATIC) != 0
				&& !f.name.equals("PAGE_UP") && !f.name.equals("PAGE_DOWN")), "no second page state left");
		assertNull(method(drawn, "isTabVisible"), "what reads the second page still goes");

		ClassNode kept = copy(pager);
		var carrier = FabricCreativePagerMixinAdapter.adapt(kept, classes(api, screen), false);
		assertEquals(1, carrier.changed());
		assertEquals(List.of(), carrier.keptWriters());
		assertNull(method(kept, "init"), "with the carrier's controls drawn, the guest's button injector goes with its pager");
	}

	@Test void theDecisionFollowsWhatIsInstalledAndTheCarriersShape() throws Exception {
		ClassNode pager = node(CreativePagerFixtures.creativeModule(CreativePagerFixtures.PINNED_MIXIN + ".class"));
		ClassNode screen = mergedScreen();

		assertFalse(FabricCreativePagerMixinAdapter.noteGuestControls(copy(pager), screen, 1),
				"a Forge-family mod is installed: its platform draws the carrier's pager");
		assertFalse(FabricCreativePagerMixinAdapter.guestControlsDrawn());
		assertFalse(FabricCreativePagerMixinAdapter.noteGuestControls(copy(pager), screen, 0), "decided once for the boot");

		FabricCreativePagerMixinAdapter.forgetSelectionHooks();
		assertFalse(FabricCreativePagerMixinAdapter.noteGuestControls(copy(pager), null, 0), "no screen to stand aside on");
		FabricCreativePagerMixinAdapter.forgetSelectionHooks();
		ClassNode reshaped = copy(screen);
		reshaped.methods.removeIf(m -> m.name.equals("extractRenderState"));
		assertFalse(FabricCreativePagerMixinAdapter.noteGuestControls(copy(pager), reshaped, 0),
				"a carrier whose controls are shaped otherwise keeps them");
		assertFalse(FabricCreativePagerMixinAdapter.guestControlsDrawn());

		FabricCreativePagerMixinAdapter.forgetSelectionHooks();
		assertTrue(FabricCreativePagerMixinAdapter.noteGuestControls(copy(pager), screen, 0), "only Fabric mods installed");
		assertTrue(FabricCreativePagerMixinAdapter.guestControlsDrawn(), "decided for the boot");
		assertEquals(1, FabricCreativePagerMixinAdapter.adapt(copy(pager), classes(null, screen)), "the public adapt follows it");
	}
}
