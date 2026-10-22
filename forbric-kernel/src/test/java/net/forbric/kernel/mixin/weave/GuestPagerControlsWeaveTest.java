/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;

/**
 * A guest creative pager through the real weave, with names of its own ({@code org.aster.pager}): when only Fabric mods
 * are installed and the carrier's controls are the two page buttons and the label the bridge can stand aside, the
 * guest's injector that adds its own page buttons is kept, without its page-state write, and its buttons and keys turn
 * the carrier's page. The control is the same guest on a carrier whose label block does one thing more: there the
 * carrier's controls stay, so the guest's button injector goes with the rest of its pager.
 *
 * <p>The decision is the boot's own: the child JVM runs config discovery ({@code MergedBaseMixinCompat}) with no
 * Forge-family mod published, exactly as a Fabric-only instance does. (The bridge that then keeps the carrier's two
 * buttons and label from running is constructed by KernelBoot with arguments, which the weave chain cannot reproduce;
 * {@code CreativePagerControlsExecutionTest} runs it.)
 */
class GuestPagerControlsWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/guestpagercontrols");
	private static final String CONFIG = "guestpagercontrols.mixins.json";
	private static final String MOD = "asterpager";
	private static final String SCREEN = "net/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen";

	@TempDir static Path work;
	private static WeaveHarness.Result drawn;
	private static WeaveHarness.Result kept;
	private static Path drawnFixture;

	@BeforeAll static void weaveBoth() throws Exception {
		drawnFixture = WeaveHarness.fixture(work, MOD, sources(null), Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		drawn = run("drawn", drawnFixture);
		// The carrier's label block also reads a property: not only its controls, so it is not stood aside.
		Path screen = SOURCES.resolve(SCREEN + ".java");
		Path reshaped = Files.createDirectories(work.resolve("reshaped/" + SCREEN).getParent()).resolve("CreativeModeInventoryScreen.java");
		Files.writeString(reshaped, Files.readString(screen).replace("graphics.text(font, page.getVisualOrderText()",
				"System.getProperty(\"asterpager.label\"); graphics.text(font, page.getVisualOrderText()"));
		kept = run("kept", WeaveHarness.fixture(work, MOD + "-reshaped", sources(reshaped), Map.of(CONFIG, SOURCES.resolve(CONFIG))));
	}

	@Test void theGuestsButtonsAreAddedAndTurnTheCarriersPage() throws Exception {
		assertTrue(drawnHolds(drawn), drawn.describe() + "\nfindings: " + drawn.findings());
		assertTrue(drawn.printed("draws org.aster.pager.mixin.ScreenPagerMixin's own page controls"), drawn.describe());
		WeaveHarness.assertWovenAndVerified(drawn, SCREEN, drawnFixture);
	}

	@Test void onACarrierShapedOtherwiseTheGuestsButtonsStayOut() throws Exception {
		assertTrue(keptHolds(kept), kept.describe() + "\nfindings: " + kept.findings());
		assertFalse(kept.printed("draws org.aster.pager.mixin.ScreenPagerMixin's own page controls"), kept.describe());
		assertTrue(kept.printed("are not the two buttons and the label the bridge can stand aside"), kept.describe());
	}

	@Test void theControlFlipsEveryAssertion() throws Exception {
		assertTrue(drawnHolds(drawn) && !drawnHolds(kept), "drawn predicate does not separate the runs");
		assertTrue(keptHolds(kept) && !keptHolds(drawn), "kept predicate does not separate the runs");
	}

	private static boolean drawnHolds(WeaveHarness.Result run) throws Exception {
		return run.printed(WeaveHarnessMain.DONE + " widgets=<,>,aster-previous,aster-next drawn=0 key=true drawn=1")
				&& losses(run).isEmpty() && secondPagerFields(run) == 0;
	}

	private static boolean keptHolds(WeaveHarness.Result run) throws Exception {
		return run.printed(WeaveHarnessMain.DONE + " widgets=<,> drawn=0 key=true drawn=1") && losses(run).isEmpty()
				&& secondPagerFields(run) == 0;
	}

	/** The guest's own page, merged in as a static int: the second pager state that must never be there. */
	private static long secondPagerFields(WeaveHarness.Result run) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(run.defined(SCREEN)).accept(node, ClassReader.SKIP_CODE);
		return node.fields.stream().filter(f -> f.desc.equals("I") && (f.access & Opcodes.ACC_STATIC) != 0).count();
	}

	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && f.confirmedRequired()).toList();
	}

	private static WeaveHarness.Result run(String label, Path fixture) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.CLIENT,
				"fixture.guestpagercontrols.Probe", "run", Map.of());
	}

	/** The fixture's sources, with {@code screen} in place of the stand-in screen when given. */
	private static List<Path> sources(Path screen) throws Exception {
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			List<Path> out = new ArrayList<>();
			for (Path p : walk.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
				out.add(screen != null && p.endsWith(SCREEN + ".java") ? screen : p);
			}
			return out;
		}
	}
}
