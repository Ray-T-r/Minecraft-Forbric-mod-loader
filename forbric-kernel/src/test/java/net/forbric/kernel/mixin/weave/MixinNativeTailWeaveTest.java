package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.transform.VanillaEarlyReturns;

/**
 * MixinNativeTail through the real weave: a NeoForge or MinecraftForge mod's {@code @At("TAIL")} on a method whose
 * early return VanillaEarlyReturns restored still runs on every path its own loader's folded body sent there.
 *
 * <p>The target is a stand-in {@code com.mojang.blaze3d.IndexType} whose {@code least(int)} has the folded shape the
 * shipped {@code vanilla-early-returns.txt} row keys, so the production table splits it: the INT path gets its own
 * return again, before the tail. Runs, all through the real Mixin, differing only in owner and switch:
 * <ul>
 *   <li>neoforge — MixinNativeTail rewrites TAIL to every return: the handler sees INT and SHORT, at two
 *       call sites in a method with two returns;</li>
 *   <li>neoforge-off — {@code -Dforbric.vanillaEarlyReturns=off}, the switch MixinNativeTail stands down with: nothing
 *       is split, so the same handler is woven once, before the one return, and still sees both;</li>
 *   <li>fabric — the same split, but a Fabric mod's TAIL keeps vanilla's meaning, so its handler sees only SHORT.
 *       This is what a NeoForge mod's handler would see without the rewrite, and what the RED mutation shows.</li>
 * </ul>
 * VanillaEarlyReturns is the production stage on the run's pre-Mixin chain, registered as KernelBoot registers it:
 * last in the coremod phase, and only while its switch is on.
 */
class MixinNativeTailWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/nativetail");
	private static final String CONFIG = "nativetail.mixins.json";
	private static final String TARGET = "com/mojang/blaze3d/IndexType";
	private static final String LEAST = "least";
	private static final String HANDLER = "nativetail$countEveryChoice";
	private static final String SAW_BOTH = WeaveHarnessMain.DONE + " least(1048576)=INT least(16)=SHORT tail saw [INT, SHORT]";
	private static final String SAW_TAIL_ONLY = WeaveHarnessMain.DONE + " least(1048576)=INT least(16)=SHORT tail saw [SHORT]";
	private static final String INSTALLED = "[WeaveHarness] pre-Mixin chain installed: COREMOD VanillaEarlyReturns sort "
			+ Integer.MAX_VALUE + " (behind its enabled())";
	private static final String NOT_REGISTERED = "[WeaveHarness] pre-Mixin chain: VanillaEarlyReturns.enabled() is false, "
			+ "so KernelBoot would not register it";
	private static final String REWRITTEN = "fixture.nativetail.mixin.IndexTypeMixin#" + HANDLER
			+ ": its TAIL still runs at all 2 return(s)";

	@TempDir static Path work;
	private static Path fixture;
	private static final Map<String, WeaveHarness.Result> RUNS = new LinkedHashMap<>();

	@BeforeAll static void weaveEveryOwner() throws Exception {
		fixture = WeaveHarness.fixture(work, "nativetail", List.of(
				SOURCES.resolve("com/mojang/blaze3d/IndexType.java"),
				SOURCES.resolve("fixture/nativetail/Probe.java"),
				SOURCES.resolve("fixture/nativetail/Seen.java"),
				SOURCES.resolve("fixture/nativetail/mixin/IndexTypeMixin.java")),
				Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		RUNS.put("neoforge", run("neoforge", Ecosystem.NEOFORGE, "on"));

		RUNS.put("neoforge-off", run("neoforge-off", Ecosystem.NEOFORGE, "off"));
		RUNS.put("fabric", run("fabric", Ecosystem.FABRIC, "on"));
	}

	@Test void aForgeFamilyTailStillSeesThePathVanillaReturnsEarlyFrom() throws Exception {
		for (String label : List.of("neoforge")) {
			WeaveHarness.Result run = RUNS.get(label);
			assertTrue(run.printed(INSTALLED), run.describe());
			assertTrue(run.printed(SAW_BOTH), label + ": the TAIL handler missed the early path — " + run.describe());
			assertEquals(2, returns(run), label + ": least was not split — " + run.describe());
			assertEquals(2, handlerCalls(run), label + ": the handler is not woven before both returns — " + run.describe());
			assertTrue(run.printed(REWRITTEN), run.describe());
			assertEquals(List.of(), losses(run), run.describe());
			WeaveHarness.assertWovenAndVerified(run, TARGET, fixture);
		}
	}

	@Test void switchedOffNothingIsSplitAndTheFoldedTailStillSeesBoth() throws Exception {
		WeaveHarness.Result off = RUNS.get("neoforge-off");
		assertTrue(off.printed(NOT_REGISTERED), off.describe());
		assertFalse(off.printed(INSTALLED), off.describe());
		assertTrue(off.printed(SAW_BOTH), off.describe());
		assertEquals(1, returns(off), off.describe());
		assertEquals(1, handlerCalls(off), off.describe());
		assertFalse(off.printed("its TAIL still runs at all"), off.describe());
		assertEquals(List.of(), losses(off), off.describe());
		WeaveHarness.assertWovenAndVerified(off, TARGET, fixture);
	}

	@Test void aFabricTailOnTheSameSplitKeepsVanillasMeaning() throws Exception {
		WeaveHarness.Result fabric = RUNS.get("fabric");
		assertTrue(fabric.printed(SAW_TAIL_ONLY), fabric.describe());
		assertEquals(2, returns(fabric), "the split must be the same as the NeoForge run's — " + fabric.describe());
		assertEquals(1, handlerCalls(fabric), fabric.describe());
		assertFalse(fabric.printed("its TAIL still runs at all"), fabric.describe());
		assertEquals(List.of(), losses(fabric), fabric.describe());
		WeaveHarness.assertWovenAndVerified(fabric, TARGET, fixture);
	}

	/** Each run's predicate must fail on the others, or the runs are not told apart by what this test asserts. */
	@Test void everyRunIsToldApartByItsOwnPredicate() throws Exception {
		WeaveHarness.Result rewritten = RUNS.get("neoforge");
		WeaveHarness.Result off = RUNS.get("neoforge-off");
		WeaveHarness.Result fabric = RUNS.get("fabric");
		assertTrue(rewrittenHolds(rewritten) && !rewrittenHolds(off) && !rewrittenHolds(fabric), "rewrite predicate");
		assertTrue(offHolds(off) && !offHolds(rewritten) && !offHolds(fabric), "control predicate");
		assertTrue(fabricHolds(fabric) && !fabricHolds(rewritten) && !fabricHolds(off), "fabric predicate");
	}

	private static boolean rewrittenHolds(WeaveHarness.Result run) throws Exception {
		return run.printed(SAW_BOTH) && returns(run) == 2 && handlerCalls(run) == 2 && run.printed(REWRITTEN);
	}

	private static boolean offHolds(WeaveHarness.Result run) throws Exception {
		return run.printed(SAW_BOTH) && returns(run) == 1 && handlerCalls(run) == 1 && !run.printed(REWRITTEN);
	}

	private static boolean fabricHolds(WeaveHarness.Result run) throws Exception {
		return run.printed(SAW_TAIL_ONLY) && returns(run) == 2 && handlerCalls(run) == 1 && !run.printed(REWRITTEN);
	}

	private static WeaveHarness.Result run(String label, Ecosystem owner, String earlyReturns) throws Exception {
		return WeaveHarness.run(work, label, fixture, List.of(new WeaveHarness.Config(CONFIG, "nativetail", owner)),
				List.of(VanillaEarlyReturns.class), EnvType.CLIENT, "fixture.nativetail.Probe", "probe",
				Map.of("forbric.vanillaEarlyReturns", earlyReturns));
	}

	/** Return instructions in the woven least: 2 once the INT path has its own return again. */
	private static long returns(WeaveHarness.Result run) throws Exception {
		return count(least(run), insn -> insn.getOpcode() == Opcodes.ARETURN);
	}

	/** Calls from the woven least into the merged TAIL handler: one per return the injector selected. */
	private static long handlerCalls(WeaveHarness.Result run) throws Exception {
		return count(least(run), insn -> insn instanceof MethodInsnNode call && call.owner.equals(TARGET)
				&& call.name.endsWith(HANDLER));
	}

	private static MethodNode least(WeaveHarness.Result run) throws Exception {
		ClassNode woven = new ClassNode();
		new ClassReader(run.defined(TARGET)).accept(woven, 0);
		return woven.methods.stream().filter(m -> m.name.equals(LEAST)).findFirst().orElseThrow();
	}

	private static long count(MethodNode method, java.util.function.Predicate<AbstractInsnNode> which) {
		long n = 0;
		for (AbstractInsnNode insn : method.instructions) if (which.test(insn)) n++;
		return n;
	}

	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.id().startsWith("mixin-injector:") && f.confirmedRequired()).toList();
	}
}
