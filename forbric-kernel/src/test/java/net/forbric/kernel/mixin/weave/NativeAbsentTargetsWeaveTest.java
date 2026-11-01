package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
 * An injector whose target a Fabric mod's own platform — vanilla — lacks too, through the real weave: dropped the way
 * native Mixin drops it — the injector only — while the rest of its mixin applies.
 *
 * <p>The fixture is Not Enough Crashes' {@code MixinTileEntity} in shape ({@code GadgetMixin}): a field of its own with
 * an initializer, an interface so the probe can see the rest arrive, and one {@code @Inject} into
 * {@code populateReport}, which the game's {@code Gadget} does not have and nothing in the shipped difference table
 * says it ever had — in a Fabric mod's {@code required: true} config with no {@code defaultRequire}. The fixture jar is
 * not the merged base the table was derived from, so every run but {@code unmatched} names the fixture's members digest
 * as the base to trust ({@code -Dforbric.mixinFit.nativeAbsent.base}). Five runs of one child JVM each:
 * <ul>
 *   <li>{@code kept}: the default. The adapter names the target as absent from vanilla and hands Mixin the whole mixin;
 *       Gadget comes out tagged, carrying the field and the merged handler with no call to it, and the mod has no
 *       finding.</li>
 *   <li>{@code off}: {@code -Dforbric.mixinFit.nativeAbsent=off}, the RED control. The old verdict: UNFIT, the mixin
 *       left out of its config, Gadget untagged, and a CONFIRMED required finding — what stopped Not Enough Crashes'
 *       server under the STRICT policy.</li>
 *   <li>{@code unmatched}: no base named, so the kernel reads the fixture jar's members digest itself, finds it is not
 *       the table's, says so once and answers nothing: the old verdict again. This is the check that keeps a table
 *       from speaking for a base it was not derived from, run end to end.</li>
 *   <li>{@code newer}: the mod's manifest requires {@code minecraft >=26.3}, so vanilla 26.2 is not the game it was
 *       built for, and nothing is answered: the old verdict, and one line naming the requirement.</li>
 *   <li>{@code mixin-alone}: {@code -Dforbric.guestMixinAdapter=off}, so Mixin decides by itself, as it does on native
 *       Fabric. {@code kept} must define exactly the Gadget this run defines: that is "dropped like native".</li>
 * </ul>
 */
class NativeAbsentTargetsWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/vanillaabsent");
	private static final String CONFIG = "vanillaabsent.mixins.json";
	private static final String MOD = "vanillaabsent";
	private static final String TARGET = "net/minecraft/fixture/vanillaabsent/Gadget";
	private static final String TAGGED = "fixture/vanillaabsent/Tagged";
	private static final String NAMED = "[Forbric/Mixin] guest mixin vanillaabsent (vanillaabsent.mixins.json):GadgetMixin names "
			+ "@Inject target Gadget.populateReport, which vanilla 26.2 lacks too";
	private static final String AUTO_SUPPRESSED = "[Forbric/Mixin] auto-suppressing guest mixin vanillaabsent "
			+ "(vanillaabsent.mixins.json):GadgetMixin — UNFIT on this base (no anchor resolves (@Inject target "
			+ "Gadget.populateReport))";
	private static final String REMOVED = "[Forbric/Mixin] suppressed mixin GadgetMixin from vanillaabsent (vanillaabsent.mixins.json)";
	private static final String LEFT_OUT = "mixin:vanillaabsent.mixins.json:fixture.vanillaabsent.mixin.GadgetMixin";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result kept;
	private static WeaveHarness.Result off;
	private static WeaveHarness.Result unmatched;
	private static WeaveHarness.Result newer;
	private static WeaveHarness.Result alone;
	private static String fixtureMembers;

	@BeforeAll static void weave() throws Exception {
		fixture = WeaveHarness.fixture(work, "vanillaabsent", List.of(
				SOURCES.resolve("net/minecraft/fixture/vanillaabsent/Gadget.java"),
				SOURCES.resolve("fixture/vanillaabsent/Tagged.java"),
				SOURCES.resolve("fixture/vanillaabsent/Probe.java"),
				SOURCES.resolve("fixture/vanillaabsent/mixin/GadgetMixin.java")),
				Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		fixtureMembers = NativeAbsentTargets.membersDigest(fixture);
		Map<String, String> trusted = Map.of("forbric.mixinFit.nativeAbsent.base", fixtureMembers);
		kept = run("kept", trusted);
		off = run("off", Map.of("forbric.mixinFit.nativeAbsent.base", fixtureMembers, "forbric.mixinFit.nativeAbsent", "off"));
		unmatched = run("unmatched", Map.of());
		newer = run("newer", Map.of("forbric.mixinFit.nativeAbsent.base", fixtureMembers, WeaveHarnessMain.REQUIRES, "minecraft=>=26.3"));
		alone = run("mixin-alone", Map.of("forbric.mixinFit.nativeAbsent.base", fixtureMembers, "forbric.guestMixinAdapter", "off"));
	}

	@Test void theInjectorIsDroppedAndTheRestOfTheMixinApplies() throws Exception {
		assertTrue(keptHolds(kept), kept.describe() + "\nfindings " + kept.findings());
		ClassNode gadget = node(kept);
		assertEquals(List.of(TAGGED), gadget.interfaces, "the mixin's interface reached Gadget");
		assertTrue(gadget.fields.stream().anyMatch(f -> f.name.contains("tag")), "the mixin's field reached Gadget");
		MethodNode handler = gadget.methods.stream().filter(m -> m.name.contains("onPopulateReport")).findFirst()
				.orElseThrow(() -> new AssertionError("the handler was merged as native merges it — " + kept.describe()));
		assertEquals(0, calls(gadget, handler), "…and nothing calls it: the injector was dropped, as natively");
		WeaveHarness.assertWovenAndVerified(kept, TARGET, fixture);
	}

	/** RED control: the switch brings back the verdict that stopped the server. */
	@Test void switchedOffTheMixinIsLeftOutAsARequiredLoss() throws Exception {
		assertTrue(offHolds(off), off.describe() + "\nfindings " + off.findings());
		assertEquals(List.of(), node(off).interfaces, off.describe());
		WeaveHarness.assertWovenAndVerified(off, TARGET, fixture);
	}

	/**
	 * RED control: on a base whose members digest is not the table's, nothing is answered and the old verdict stands;
	 * the warning names the digest the kernel read from the fixture jar, which is the one this test computed.
	 */
	@Test void onABaseTheTableWasNotDerivedFromTheMissIsTheMergesAsBefore() throws Exception {
		assertTrue(offHolds(unmatched), unmatched.describe() + "\nfindings " + unmatched.findings());
		assertTrue(unmatched.printed(TARGET + " is served by a jar with members " + fixtureMembers + ", which is neither the "
				+ "base "), unmatched.describe());
		assertFalse(kept.printed(" is served by "), kept.describe());
		assertEquals(List.of(), node(unmatched).interfaces, unmatched.describe());
	}

	/**
	 * RED control: a mod built for a newer Minecraft is not native to the game the table describes, so the target it
	 * lacks there is not answered for; the old verdict stands, and one line says which requirement decided it.
	 */
	@Test void aModBuiltForANewerGameKeepsTheOldVerdict() throws Exception {
		assertTrue(offHolds(newer), newer.describe() + "\nfindings " + newer.findings());
		assertTrue(newer.printed("[Forbric/Mixin] vanillaabsent requires minecraft >=26.3, which vanilla 26.2 (minecraft 26.2) "
				+ "does not satisfy"), newer.describe());
		assertFalse(kept.printed(" does not satisfy"), kept.describe());
		assertEquals(List.of(), node(newer).interfaces, newer.describe());
	}

	/** Mixin deciding alone is native's decision; the kept run defines the same Gadget, member for member. */
	@Test void theKeptRunDefinesWhatMixinAloneDefines() throws Exception {
		assertTrue(alone.printed(WeaveHarnessMain.DONE + " gadget tagged kept"), alone.describe());
		assertEquals(shape(node(alone)), shape(node(kept)), "kept differs from Mixin's own decision");
		assertTrue(ours(alone).stream().noneMatch(WeaveHarness.Finding::confirmedRequired), "findings " + alone.findings());
	}

	/** Each run's predicate must fail on the other, or the control proves nothing about the change. */
	@Test void theControlFlipsEveryAssertion() {
		assertTrue(keptHolds(kept) && !keptHolds(off) && !keptHolds(unmatched) && !keptHolds(newer),
				"the kept predicate does not separate the runs");
		assertTrue(offHolds(off) && offHolds(unmatched) && offHolds(newer) && !offHolds(kept),
				"the control predicate does not separate the runs");
	}

	private static boolean keptHolds(WeaveHarness.Result run) {
		return run.printed(WeaveHarnessMain.DONE + " gadget tagged kept") && run.printed(NAMED)
				&& !run.printed(AUTO_SUPPRESSED) && !run.printed(REMOVED)
				&& ours(run).stream().noneMatch(f -> !"RESOLVED".equals(f.confidence()));
	}

	private static boolean offHolds(WeaveHarness.Result run) {
		return run.printed(WeaveHarnessMain.DONE + " gadget untagged") && !run.printed(NAMED)
				&& run.printed(AUTO_SUPPRESSED) && run.printed(REMOVED)
				&& ours(run).stream().anyMatch(f -> f.id().equals(LEFT_OUT) && f.confirmedRequired());
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
				"fixture.vanillaabsent.Probe", "probe", properties);
	}
}
