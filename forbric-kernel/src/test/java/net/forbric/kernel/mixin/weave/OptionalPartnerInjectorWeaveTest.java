package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.NativeAbsentTargets;

/**
 * A compatibility mixin for a mod that is not installed, through the real weave: mod {@code alpha}'s
 * {@code PlaqueCompatMixin} — no field, no interface, one {@code @Inject} into {@code beta$cacheable}, the method mod
 * beta's own mixin would add to the game's {@code Plaque} — in a config with {@code "injectors": {"defaultRequire": -1}}
 * and no {@code required}. Mod beta is absent and alpha declares no dependency on it. Three runs of one child JVM each:
 * <ul>
 *   <li>{@code mixin-alone}: {@code -Dforbric.guestMixinAdapter=off}, so sponge-mixin decides by itself, as it does on
 *       native Fabric. It is the evidence for the rule: the -1 requires nothing, the injector finds no target and is
 *       dropped without an error, and the handler is merged into Plaque with nothing calling it.</li>
 *   <li>{@code kept}: the default. The adapter names the target as absent from vanilla too and hands Mixin the whole
 *       mixin; Plaque comes out as Mixin alone defines it, and alpha has no finding — no DEGRADED mark.</li>
 *   <li>{@code off}: {@code -Dforbric.mixinFit.nativeAbsent=off}, the RED control, which is also the verdict the kernel
 *       gave while it read a written -1 as "unknown": UNFIT, the mixin left out of its config, and a CONFIRMED finding
 *       that marks alpha DEGRADED for a loss native never has.</li>
 * </ul>
 * The fixture jar is not the merged base the shipped table was derived from, so every run names the fixture's members
 * digest as the base to trust ({@code -Dforbric.mixinFit.nativeAbsent.base}), as NativeAbsentTargetsWeaveTest does.
 */
class OptionalPartnerInjectorWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/optionalpartner");
	private static final String CONFIG = "alpha.mixins.json";
	private static final String MOD = "alpha";
	private static final String TARGET = "net/minecraft/fixture/optionalpartner/Plaque";
	private static final String NAMED = "[Forbric/Mixin] guest mixin alpha (alpha.mixins.json):PlaqueCompatMixin names "
			+ "@Inject target Plaque.beta$cacheable, which vanilla 26.2 lacks too";
	private static final String AUTO_SUPPRESSED = "[Forbric/Mixin] auto-suppressing guest mixin alpha (alpha.mixins.json):"
			+ "PlaqueCompatMixin — UNFIT on the merged base";
	private static final String REMOVED = "[Forbric/Mixin] suppressed mixin PlaqueCompatMixin from alpha (alpha.mixins.json)";
	private static final String LEFT_OUT = "mixin:alpha.mixins.json:fixture.optionalpartner.mixin.PlaqueCompatMixin";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result kept;
	private static WeaveHarness.Result off;
	private static WeaveHarness.Result alone;

	@BeforeAll static void weave() throws Exception {
		fixture = WeaveHarness.fixture(work, "optionalpartner", List.of(
				SOURCES.resolve("net/minecraft/fixture/optionalpartner/Plaque.java"),
				SOURCES.resolve("fixture/optionalpartner/Probe.java"),
				SOURCES.resolve("fixture/optionalpartner/mixin/PlaqueCompatMixin.java")),
				Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		String members = NativeAbsentTargets.membersDigest(fixture);
		kept = run("kept", Map.of("forbric.mixinFit.nativeAbsent.base", members));
		off = run("off", Map.of("forbric.mixinFit.nativeAbsent.base", members, "forbric.mixinFit.nativeAbsent", "off"));
		alone = run("mixin-alone", Map.of("forbric.mixinFit.nativeAbsent.base", members, "forbric.guestMixinAdapter", "off"));
	}

	/** Native's decision: -1 requires nothing, so the empty injector is dropped silently and the rest applies. */
	@Test void mixinAloneDropsTheInjectorWithoutAnErrorAndMergesTheRest() throws Exception {
		assertTrue(alone.printed(WeaveHarnessMain.DONE + " plaque"), alone.describe());
		assertFalse(alone.printed("Critical injection failure") || alone.printed("InvalidInjectionException")
				|| alone.printed("InjectionError"), alone.describe());
		ClassNode plaque = node(alone);
		MethodNode handler = handler(plaque).orElseThrow(() -> new AssertionError("Mixin merged no handler — " + alone.describe()));
		assertEquals(0, calls(plaque, handler), "nothing calls the merged handler: the injector found no target");
	}

	/** The fix: kept whole, defined as Mixin alone defines it, and no finding marks the mod. */
	@Test void theAdapterKeepsTheMixinAndRecordsNothing() throws Exception {
		assertTrue(keptHolds(kept), kept.describe() + "\nfindings " + kept.findings());
		assertEquals(shape(node(alone)), shape(node(kept)), "kept differs from Mixin's own decision");
		WeaveHarness.assertWovenAndVerified(kept, TARGET, fixture);
	}

	/** RED control: the old verdict — left out, and a CONFIRMED (not required) finding on the mod's row. */
	@Test void switchedOffTheMixinIsLeftOutAndTheModMarked() throws Exception {
		assertTrue(offHolds(off), off.describe() + "\nfindings " + off.findings());
		assertTrue(handler(node(off)).isEmpty(), "the left-out mixin merged nothing — " + off.describe());
	}

	/** Each run's predicate must fail on the other, or the control proves nothing about the change. */
	@Test void theControlFlipsEveryAssertion() {
		assertTrue(keptHolds(kept) && !keptHolds(off), "the kept predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(kept), "the control predicate does not separate the runs");
	}

	private static boolean keptHolds(WeaveHarness.Result run) {
		return run.printed(WeaveHarnessMain.DONE + " plaque") && run.printed(NAMED) && !run.printed(AUTO_SUPPRESSED)
				&& !run.printed(REMOVED) && ours(run).stream().noneMatch(f -> !"RESOLVED".equals(f.confidence()));
	}

	private static boolean offHolds(WeaveHarness.Result run) {
		return run.printed(WeaveHarnessMain.DONE + " plaque") && !run.printed(NAMED) && run.printed(AUTO_SUPPRESSED)
				&& run.printed(REMOVED) && ours(run).stream().anyMatch(f -> f.id().equals(LEFT_OUT)
						&& "CONFIRMED".equals(f.confidence()) && !f.required());
	}

	private static Optional<MethodNode> handler(ClassNode plaque) {
		return plaque.methods.stream().filter(m -> m.name.contains("afterCacheable")).findFirst();
	}

	/** Interfaces, fields and methods by name and descriptor, Mixin's unique-name prefixes stripped. */
	private static TreeSet<String> shape(ClassNode node) {
		TreeSet<String> members = new TreeSet<>(node.interfaces);
		for (FieldNode f : node.fields) members.add("field " + f.name + " " + f.desc);
		for (MethodNode m : node.methods) members.add("method " + m.name.replaceAll("^handler\\$[a-z0-9]+\\$", "handler\\$")
				.replaceAll("^md[0-9a-f]+\\$", "md\\$") + m.desc);
		return members;
	}

	private static int calls(ClassNode owner, MethodNode handler) {
		int calls = 0;
		for (MethodNode m : owner.methods) {
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof MethodInsnNode call && call.owner.equals(owner.name) && call.name.equals(handler.name)
						&& call.desc.equals(handler.desc)) calls++;
			}
		}
		return calls;
	}

	private static List<WeaveHarness.Finding> ours(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD)).toList();
	}

	private static ClassNode node(WeaveHarness.Result run) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(run.defined(TARGET)).accept(node, 0);
		return node;
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER,
				"fixture.optionalpartner.Probe", "probe", properties);
	}
}
