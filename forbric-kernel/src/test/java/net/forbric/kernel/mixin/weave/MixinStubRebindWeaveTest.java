package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MixinStubRebind;

/**
 * MixinStubRebind through the real weave: a Fabric mod's injectors, which Mixin binds to a carrier's forwarding stub,
 * land on the body the merged game actually calls.
 *
 * <p>The fixture's {@code LivingEntity} has the shape of the carrier-stubs.txt row
 * {@code randomTeleport(DDDZ)Z -> (DDDZLItemStack;)Z neo=stub}: vanilla's signature, declared first, only
 * forwards to the widened overload, which carries the body. The mixin names {@code randomTeleport} alone, as it would
 * against vanilla, with a HEAD that captures vanilla's four arguments and an INVOKE anchor only the body has. The probe
 * calls the body (the merged game's caller) and then the stub (an old caller), and returns both traces.
 * <ul>
 *   <li>fabric — both handlers move: HEAD runs on either call, still handed vanilla's arguments, and the anchor fires;</li>
 *   <li>fabric-off — {@code -Dforbric.mixinStubRebind=off}, the control: HEAD runs only when the stub is called, the
 *       anchor never fires and is a confirmed loss, and the audit flags HEAD as attached only inside the stub;</li>
 *   <li>neoforge — the row's {@code neo=stub}: a NeoForge mod was compiled against that same stub, so nothing moves.
 *       (A MinecraftForge run, on the row's former {@code forge=body}, went with {@code Ecosystem.FORGE}.)</li>
 * </ul>
 */
class MixinStubRebindWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/stubrebind");
	private static final String CONFIG = "stubrebind.mixins.json";
	private static final String MOD = "stubrebind";
	private static final String TARGET = "net/minecraft/world/entity/LivingEntity";
	private static final String STUB = "(DDDZ)Z";
	private static final String BODY = "(DDDZLnet/minecraft/world/item/ItemStack;)Z";

	/** Both handlers on the body: the stub call reaches them through its forwarding call, with the stub's own arguments. */
	private static final String MOVED = "body[head(1.0,2.0,3.0,true);body;anchor;land;] stub[head(4.0,5.0,6.0,false);body;anchor;land;]";
	/** HEAD on the stub, the anchor nowhere: the game's own call to the body runs neither handler. */
	private static final String STAYED = "body[body;land;] stub[head(4.0,5.0,6.0,false);body;land;]";
	/** MixinStubRebind's own line per moved handler; the capturing one is moved through a shim, hence its tail. */
	private static final String ANCHOR_MOVED = "stubrebind$beforeLand now targets net.minecraft.world.entity.LivingEntity.randomTeleport" + BODY;
	private static final String HEAD_MOVED = "randomTeleport" + BODY + " — Mixin bound its selector to the merge-added stub " + STUB
			+ ", which only forwards to it; the handler still receives the stub's arguments";

	@TempDir static Path work;
	private static Path fixture;
	private static final Map<String, WeaveHarness.Result> RUNS = new LinkedHashMap<>();

	@BeforeAll static void weave() throws Exception {
		fixture = WeaveHarness.fixture(work, "stubrebind", List.of(
				SOURCES.resolve("net/minecraft/world/item/ItemStack.java"),
				SOURCES.resolve("net/minecraft/world/entity/LivingEntity.java"),
				SOURCES.resolve("fixture/stubrebind/Probe.java"),
				SOURCES.resolve("fixture/stubrebind/mixin/RandomTeleportMixin.java")),
				Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		RUNS.put("fabric", run("fabric", Ecosystem.FABRIC, Map.of()));
		RUNS.put("fabric-off", run("fabric-off", Ecosystem.FABRIC, Map.of(MixinStubRebind.PROPERTY, "off")));
		RUNS.put("neoforge", run("neoforge", Ecosystem.NEOFORGE, Map.of()));

	}

	@Test void aFabricModsInjectorsLeaveTheStubForTheBody() throws Exception {
		WeaveHarness.Result fabric = RUNS.get("fabric");
		assertTrue(fabric.printed(WeaveHarnessMain.DONE + " " + MOVED), fabric.describe());
		assertTrue(fabric.printed(ANCHOR_MOVED) && fabric.printed(HEAD_MOVED), fabric.describe());
		assertEquals(List.of(), losses(fabric), fabric.describe());
		assertEquals(List.of(), stubHosted(fabric), fabric.describe());
		// MixinFit asks the rebind where the injector lands, so the config-time verdict agrees with the weave.
		assertEquals(List.of(), fitMissesAnchor(fabric), fabric.describe());
		assertEquals(0, handlerCalls(fabric, STUB), "a handler is still woven into the stub — " + fabric.describe());
		assertEquals(2, handlerCalls(fabric, BODY), "both handlers belong in the body — " + fabric.describe());
		WeaveHarness.assertWovenAndVerified(fabric, TARGET, fixture);
	}

	@Test void theControlLeavesThemOnTheStub() throws Exception {
		WeaveHarness.Result off = RUNS.get("fabric-off");
		assertTrue(off.printed(WeaveHarnessMain.DONE + " " + STAYED), off.describe());
		assertFalse(off.printed(ANCHOR_MOVED) || off.printed(HEAD_MOVED), off.describe());
		List<WeaveHarness.Finding> losses = losses(off);
		assertEquals(1, losses.size(), losses + "\n" + off.describe());
		assertTrue(losses.get(0).id().contains("stubrebind$beforeLand"), losses.toString());
		List<WeaveHarness.Finding> stubbed = stubHosted(off);
		assertEquals(1, stubbed.size(), off.findings() + "\n" + off.describe());
		assertTrue(stubbed.get(0).id().contains("stubrebind$head"), stubbed.toString());
		assertEquals(1, fitMissesAnchor(off).size(), off.findings() + "\n" + off.describe());
		assertEquals(1, handlerCalls(off, STUB), off.describe());
		assertEquals(0, handlerCalls(off, BODY), off.describe());
		WeaveHarness.assertWovenAndVerified(off, TARGET, fixture);
	}

	/** The row's carrier columns, read through the real pipeline: each Forge family gets what its own platform did. */
	@Test void aForgeFamilyModMovesOnlyWhereItsCarrierRanTheBody() throws Exception {
		WeaveHarness.Result neoforge = RUNS.get("neoforge");
		assertTrue(neoforge.printed(WeaveHarnessMain.DONE + " " + STAYED), neoforge.describe());
		assertFalse(neoforge.printed(ANCHOR_MOVED) || neoforge.printed(HEAD_MOVED), neoforge.describe());
		assertEquals(1, handlerCalls(neoforge, STUB), neoforge.describe());
		// NeoForge's own class has the same stub, so the audit does not call HEAD's place a merge artefact.
		assertEquals(List.of(), stubHosted(neoforge), neoforge.describe());
		WeaveHarness.assertWovenAndVerified(neoforge, TARGET, fixture);
	}

	/** The run and its control must be told apart by the very predicates the test uses on them. */
	@Test void theControlFlipsEveryRebindAssertion() {
		WeaveHarness.Result fabric = RUNS.get("fabric");
		WeaveHarness.Result off = RUNS.get("fabric-off");
		assertTrue(moved(fabric) && !moved(off), "rebind predicate does not separate the runs");
		assertTrue(stayed(off) && !stayed(fabric), "control predicate does not separate the runs");
	}

	private static boolean moved(WeaveHarness.Result run) {
		return run.printed(WeaveHarnessMain.DONE + " " + MOVED) && run.printed(ANCHOR_MOVED) && run.printed(HEAD_MOVED)
				&& losses(run).isEmpty() && stubHosted(run).isEmpty() && fitMissesAnchor(run).isEmpty()
				&& handlerCalls(run, STUB) == 0 && handlerCalls(run, BODY) == 2;
	}

	private static boolean stayed(WeaveHarness.Result run) {
		return run.printed(WeaveHarnessMain.DONE + " " + STAYED) && !run.printed(ANCHOR_MOVED) && !run.printed(HEAD_MOVED)
				&& losses(run).size() == 1 && stubHosted(run).size() == 1 && fitMissesAnchor(run).size() == 1
				&& handlerCalls(run, STUB) == 1 && handlerCalls(run, BODY) == 0;
	}

	private static WeaveHarness.Result run(String label, Ecosystem ecosystem, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, ecosystem, EnvType.SERVER,
				"fixture.stubrebind.Probe", "run", properties);
	}

	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return findings(run, f -> f.id().startsWith("mixin-injector:") && f.confirmedRequired());
	}

	/** FinalMixinApplications' "attached only inside a forwarding stub" report. */
	private static List<WeaveHarness.Finding> stubHosted(WeaveHarness.Result run) {
		return findings(run, f -> f.id().startsWith("mixin-injector:") && "SUSPECTED".equals(f.confidence())
				&& f.detail().contains("forwarding stub"));
	}

	/** MixinFit's config-time verdict on the whole mixin: the body-only anchor judged against the stub. */
	private static List<WeaveHarness.Finding> fitMissesAnchor(WeaveHarness.Result run) {
		return findings(run, f -> f.id().startsWith("mixin:") && f.detail().contains("missing: @At(INVOKE) LivingEntity.land"));
	}

	private static List<WeaveHarness.Finding> findings(WeaveHarness.Result run, Predicate<WeaveHarness.Finding> which) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && which.test(f)).toList();
	}

	/** Calls from the woven {@code randomTeleport} of that descriptor to a handler Mixin merged in. */
	private static int handlerCalls(WeaveHarness.Result run, String desc) {
		ClassNode woven = new ClassNode();
		try {
			new ClassReader(run.defined(TARGET)).accept(woven, 0);
		} catch (java.io.IOException unreadable) {
			throw new java.io.UncheckedIOException(unreadable);
		}
		List<String> merged = woven.methods.stream().filter(m -> m.visibleAnnotations != null && m.visibleAnnotations.stream()
				.anyMatch(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;")))
				.map(m -> m.name + m.desc).toList();
		int calls = 0;
		for (MethodNode method : woven.methods) {
			if (!method.name.equals("randomTeleport") || !method.desc.equals(desc)) continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && call.owner.equals(TARGET) && merged.contains(call.name + call.desc)) calls++;
			}
		}
		return calls;
	}
}
