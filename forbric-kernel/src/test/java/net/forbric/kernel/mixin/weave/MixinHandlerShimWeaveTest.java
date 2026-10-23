package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
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

/**
 * {@code MixinHandlerShim} through the real weave: a handler written for vanilla's lambda, on a merged base that keeps
 * the same captures in another order.
 *
 * <p>The fixture's {@code NbtOps.lambda$mergeToMap$3} has the merged side of the SHIPPED census row
 * ({@code lambda-permutations.txt}: vanilla {@code (List, CompoundTag, Pair)}, merged {@code (CompoundTag, List, Pair)}),
 * and {@code NbtOpsMixin} is a HEAD {@code @Inject} written against vanilla's order. NbtOps because it is the smallest
 * row and no kernel transformer is keyed on that class, so nothing but the shim decides the outcome.
 * <ul>
 *   <li>shimmed — the handler is wrapped, Mixin binds the wrapper, and the woven lambda hands the handler its List and
 *       its CompoundTag in the order it declared them: the probe's trail starts with the handler's line;</li>
 *   <li>shim-off ({@code -Dforbric.mixinHandlerShim=off}) — the name binds the merged lambda, which the handler was not
 *       written for: the verdict reads its only injector as a miss, the mixin is UNFIT and left out before Mixin reads
 *       it (no "Invalid descriptor"), nothing calls the handler, and the loss is a confirmed, required finding for the
 *       mod;</li>
 *   <li>table-off ({@code -Dforbric.lambdaPermutations=off}) — the same as shim-off, which pins the evidence that fired
 *       to the census row and not to anything else;</li>
 *   <li>shim-off with {@code -Dforbric.mixinFit.handlerFit=off} — the mixin reaches Mixin, which rejects the descriptor,
 *       as every control did before the verdict asked.</li>
 * </ul>
 * The shim's other evidence, {@code DuplicateLambdaPruneInjector}'s record, is not reached: the pruner is a COREMOD
 * transformer that KernelBoot registers, and the harness bootstraps Mixin without that chain.
 */
class MixinHandlerShimWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/handlershim");
	private static final String CONFIG = "handlershim.mixins.json";
	private static final String TARGET = "net/minecraft/nbt/NbtOps";
	private static final String LAMBDA = "lambda$mergeToMap$3";
	private static final String MOD = "handlershim";
	private static final String HANDLER = "handlershim$seeEntry";

	/** What the probe returns when the handler ran first, having been handed the right List and the right compound. */
	private static final String WOVEN = WeaveHarnessMain.DONE + " handler saw key in root | merged key=value into root";
	/** What it returns when nothing reached the handler: the lambda's own line and nothing else. */
	private static final String UNWOVEN = WeaveHarnessMain.DONE + " merged key=value into root";
	private static final String SHIM_WARNING = "NbtOpsMixin.handlershim$seeEntry was written for a differently shaped "
			+ "lambda$mergeToMap$3 — vanilla's (Ljava/util/List;Lnet/minecraft/nbt/CompoundTag;Lcom/mojang/datafixers/util/Pair;)V";
	private static final String MIXIN_REJECTS = "Invalid descriptor on handlershim.mixins.json:NbtOpsMixin";
	private static final String CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
	/** The handler as the mod compiled it. */
	private static final String VANILLA_HANDLER = "(Ljava/util/List;Lnet/minecraft/nbt/CompoundTag;Lcom/mojang/datafixers/util/Pair;" + CI + ")V";
	/** The wrapper the shim puts in its place, shaped like the merged lambda. */
	private static final String MERGED_HANDLER = "(Lnet/minecraft/nbt/CompoundTag;Ljava/util/List;Lcom/mojang/datafixers/util/Pair;" + CI + ")V";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result shimmed;
	private static WeaveHarness.Result off;
	private static WeaveHarness.Result tableOff;
	private static WeaveHarness.Result rejected;
	private static final String LEFT_OUT = "auto-suppressing guest mixin handlershim (handlershim.mixins.json):NbtOpsMixin — UNFIT";

	@BeforeAll static void weave() throws Exception {
		fixture = WeaveHarness.fixture(work, "handlershim", List.of(
				SOURCES.resolve("net/minecraft/nbt/NbtOps.java"),
				SOURCES.resolve("net/minecraft/nbt/CompoundTag.java"),
				SOURCES.resolve("com/mojang/datafixers/util/Pair.java"),
				SOURCES.resolve("fixture/handlershim/mixin/NbtOpsMixin.java")),
				Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		shimmed = run("shimmed", Map.of());
		off = run("shim-off", Map.of("forbric.mixinHandlerShim", "off"));
		tableOff = run("table-off", Map.of("forbric.lambdaPermutations", "off"));
		rejected = run("shim-off-verdict-off", Map.of("forbric.mixinHandlerShim", "off", "forbric.mixinFit.handlerFit", "off"));
	}

	@Test void theWrappedHandlerReceivesItsVanillaArgumentsFromTheMergedLambda() throws Exception {
		assertTrue(shimmed.printed(SHIM_WARNING), shimmed.describe());
		// The handler's line comes first (HEAD) and names the compound it was handed: a List and a CompoundTag put back
		// into the order the mod declared, not merely a mixin that applied.
		assertTrue(shimmed.printed(WOVEN), shimmed.describe());
		assertFalse(shimmed.printed(MIXIN_REJECTS), shimmed.describe());
		assertEquals(List.of(), losses(shimmed), shimmed.describe());

		// The chain in the defined class: merged lambda -> merged-shaped wrapper -> the mod's own body, still vanilla-shaped.
		byte[] woven = shimmed.defined(TARGET);
		List<MethodInsnNode> fromLambda = handlerCalls(woven, LAMBDA, null);
		assertEquals(1, fromLambda.size(), methods(woven));
		assertEquals(MERGED_HANDLER, fromLambda.get(0).desc, methods(woven));
		List<MethodInsnNode> fromWrapper = handlerCalls(woven, fromLambda.get(0).name, MERGED_HANDLER);
		assertEquals(1, fromWrapper.size(), methods(woven));
		assertTrue(fromWrapper.get(0).name.startsWith(HANDLER + "$forbricshim$"), "the body aside, under a name of its own mixin: " + methods(woven));
		assertEquals(VANILLA_HANDLER, fromWrapper.get(0).desc, methods(woven));
		WeaveHarness.assertWovenAndVerified(shimmed, TARGET, fixture);
	}

	@Test void withTheShimOffTheMixinIsLeftOutAndTheModIsReported() throws Exception {
		for (WeaveHarness.Result control : List.of(off, tableOff)) {
			assertFalse(control.printed(SHIM_WARNING), control.describe());
			assertTrue(control.printed(LEFT_OUT), control.describe());
			assertFalse(control.printed(MIXIN_REJECTS), control.describe());
			assertTrue(control.printedLine(UNWOVEN), control.describe());
			assertFalse(control.printed("handler saw"), control.describe());

			List<WeaveHarness.Finding> losses = losses(control);
			assertEquals(1, losses.size(), control.label() + " " + losses + "\n" + control.describe());
			assertTrue(losses.get(0).id().startsWith("mixin:") && losses.get(0).detail().contains("was left out"), losses.toString());

			byte[] defined = control.defined(TARGET);
			assertEquals(List.of(), handlerCalls(defined, LAMBDA, null), control.label() + "\n" + methods(defined));
			WeaveHarness.assertWovenAndVerified(control, TARGET, fixture);
		}
	}

	/** With the verdict's handler rule off too, the mixin reaches Mixin, which rejects the descriptor. */
	@Test void withTheVerdictRuleOffMixinRejectsTheHandlerAndTheModIsReported() throws Exception {
		assertTrue(rejectedHolds(rejected), rejected.describe());
		List<WeaveHarness.Finding> losses = losses(rejected);
		assertTrue(losses.stream().anyMatch(f -> f.id().startsWith("mixin-injector:")
				&& f.id().contains("NbtOpsMixin#" + HANDLER + VANILLA_HANDLER)), losses.toString());
		assertTrue(losses.stream().anyMatch(f -> f.id().startsWith("mixin:")
				&& f.detail().contains("InvalidInjectionException")), losses.toString());
		// Mixin merged the handler before it rejected the injection, so the body is in the class — and dead.
		WeaveHarness.assertWovenAndVerified(rejected, TARGET, fixture);
	}

	/** Each run must be told apart from its controls by the very predicates the tests above use on it. */
	@Test void theControlFlipsEveryShimAssertion() throws Exception {
		assertTrue(shimHolds(shimmed) && !shimHolds(off) && !shimHolds(tableOff) && !shimHolds(rejected),
				"shim predicate does not separate the runs");
		assertTrue(offHolds(off) && offHolds(tableOff) && !offHolds(shimmed) && !offHolds(rejected),
				"control predicate does not separate the runs");
		assertTrue(rejectedHolds(rejected) && !rejectedHolds(off) && !rejectedHolds(shimmed),
				"verdict-off predicate does not separate the runs");
	}

	private static boolean shimHolds(WeaveHarness.Result run) throws Exception {
		return run.printed(SHIM_WARNING) && run.printed(WOVEN) && losses(run).isEmpty()
				&& handlerCalls(run.defined(TARGET), LAMBDA, null).stream().anyMatch(c -> c.desc.equals(MERGED_HANDLER));
	}

	private static boolean offHolds(WeaveHarness.Result run) throws Exception {
		return run.printed(LEFT_OUT) && !run.printed(MIXIN_REJECTS) && run.printedLine(UNWOVEN) && losses(run).size() == 1
				&& handlerCalls(run.defined(TARGET), LAMBDA, null).isEmpty();
	}

	private static boolean rejectedHolds(WeaveHarness.Result run) throws Exception {
		return !run.printed(LEFT_OUT) && run.printed(MIXIN_REJECTS) && run.printedLine(UNWOVEN) && losses(run).size() == 2
				&& handlerCalls(run.defined(TARGET), LAMBDA, null).isEmpty();
	}

	/** Calls from {@code caller} (any descriptor when null) to a method of the target carrying the mod's handler name. */
	private static List<MethodInsnNode> handlerCalls(byte[] defined, String caller, String callerDesc) {
		List<MethodInsnNode> calls = new ArrayList<>();
		for (MethodNode method : node(defined).methods) {
			if (!method.name.equals(caller) || callerDesc != null && !method.desc.equals(callerDesc)) continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn.getOpcode() == Opcodes.INVOKESTATIC && insn instanceof MethodInsnNode call
						&& call.owner.equals(TARGET) && call.name.contains(HANDLER)) {
					calls.add(call);
				}
			}
		}
		return calls;
	}

	private static String methods(byte[] defined) {
		StringBuilder out = new StringBuilder("defined " + TARGET + ":\n");
		for (MethodNode method : node(defined).methods) out.append("  ").append(method.name).append(method.desc).append('\n');
		return out.toString();
	}

	private static ClassNode node(byte[] defined) {
		ClassNode node = new ClassNode();
		new ClassReader(defined).accept(node, 0);
		return node;
	}

	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && f.confirmedRequired()).toList();
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER,
				"net.minecraft.nbt.NbtOps", "probe", properties);
	}
}
