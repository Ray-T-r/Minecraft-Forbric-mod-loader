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
import org.objectweb.asm.tree.TypeInsnNode;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.FabricCommonPayloadMixinAdapter;

/**
 * FabricCommonPayloadMixinAdapter through the real weave: fabric-networking-api's configuration-only payload handler, a
 * cancellable HEAD {@code @Inject} of {@code ServerCommonPacketListenerImpl.handleCustomPayload} that throws
 * {@code IllegalStateException("Unknown addon")} for any listener whose addon is not a configuration addon, on NeoForge's
 * listeners: the play listener hands every payload to that super (where NeoForge's dispatcher lives; vanilla's override
 * was empty), and the configuration listener answers NeoForge's network query itself and hands every other payload to it.
 *
 * <p>The probe sends the configuration listener a payload a Fabric mod registered, one NeoForge's common dispatcher
 * handles and NeoForge's network query, and the play listener a payload fabric-api's play receiver declined. Adapted, the
 * handler returns at its head for a play listener: Fabric's payload is fabric-api's, NeoForge's goes on to NeoForge's
 * dispatcher, the query never reaches the common handler, and the play payload reaches NeoForge's dispatcher too. With
 * {@code -Dforbric.playPayloadFallThrough=off} the handler is as written and the play payload disconnects the player with
 * "Unknown addon", as measured on the 97-mod pack before the adapter; the configuration listener's three answers do not
 * change.
 */
class FabricCommonPayloadMixinAdapterWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/commonpayload");
	private static final String CONFIG = "commonpayload.mixins.json";
	private static final String MOD = "fabric-networking-api-v1";
	private static final String COMMON = "net/minecraft/server/network/ServerCommonPacketListenerImpl";
	private static final String GAME = "net/minecraft/server/network/ServerGamePacketListenerImpl";
	private static final String CONFIGURATION = " configuration.fabric=fabric configuration.neoforge=neoforge configuration.query=neoforge-query";
	private static final String ADAPTED = WeaveHarnessMain.DONE + CONFIGURATION + " play.neoforge=neoforge";
	private static final String AS_WRITTEN = WeaveHarnessMain.DONE + CONFIGURATION + " play.neoforge=disconnect(Unknown addon)";
	private static final String ADAPT_LOG = "net.fabricmc.fabric.mixin.networking.ServerCommonPacketListenerImplMixin's "
			+ "configuration-phase payload handler on net.minecraft.server.network.ServerCommonPacketListenerImpl lets a play listener's payload go on";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result adapted, off;

	@BeforeAll static void weaveBothWays() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = new ArrayList<>(walk.filter(p -> p.toString().endsWith(".java")).sorted().toList());
		}
		assertEquals(13, sources.size(), "the fixture's sources changed; update this test with it: " + sources);
		fixture = WeaveHarness.fixture(work, "commonpayload", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		adapted = run("adapted", Map.of());
		off = run("fall-through-off", Map.of(FabricCommonPayloadMixinAdapter.PROPERTY, "off"));
		for (WeaveHarness.Result run : List.of(adapted, off)) System.out.println(run.label() + ": " + run.output().lines()
				.filter(line -> line.startsWith(WeaveHarnessMain.DONE)).findFirst().orElse("(the probe printed no result)"));
	}

	@Test void aPlayPayloadFabricApiDeclinedGoesOnToNeoForgesDispatcher() throws Exception {
		assertTrue(adapted.printedLine(ADAPTED), adapted.describe());
		assertTrue(adapted.printed(ADAPT_LOG), adapted.describe());
		assertTrue(asksForThePlayListener(adapted), adapted.describe());
		assertEquals(List.of(), confirmedRequired(adapted), adapted.findings().toString());
		WeaveHarness.assertWovenAndVerified(adapted, COMMON, fixture);
	}

	@Test void switchedOffThePlayPayloadDisconnectsWithUnknownAddon() throws Exception {
		assertTrue(off.printedLine(AS_WRITTEN), off.describe());
		assertFalse(off.printed(ADAPT_LOG), off.describe());
		assertFalse(asksForThePlayListener(off), off.describe());
		assertEquals(List.of(), confirmedRequired(off), off.findings().toString());
		WeaveHarness.assertWovenAndVerified(off, COMMON, fixture);
	}

	/** The switch is the runs' only difference, so each run's predicate must reject the other. */
	@Test void theControlFlipsEveryAssertion() throws Exception {
		assertTrue(adaptedHolds(adapted) && !adaptedHolds(off), "the adapted predicate does not separate the runs");
		assertTrue(asWrittenHolds(off) && !asWrittenHolds(adapted), "the control predicate does not separate the runs");
	}

	private static boolean adaptedHolds(WeaveHarness.Result run) throws Exception {
		return run.printedLine(ADAPTED) && run.printed(ADAPT_LOG) && asksForThePlayListener(run);
	}

	private static boolean asWrittenHolds(WeaveHarness.Result run) throws Exception {
		return run.printedLine(AS_WRITTEN) && !run.printed(ADAPT_LOG) && !asksForThePlayListener(run);
	}

	/** Whether the woven common listener tests {@code this instanceof ServerGamePacketListenerImpl}: the adapter's guard. */
	private static boolean asksForThePlayListener(WeaveHarness.Result run) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(run.defined(COMMON)).accept(node, 0);
		return node.methods.stream().anyMatch(m -> java.util.Arrays.stream(m.instructions.toArray()).anyMatch(insn ->
				insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.INSTANCEOF && type.desc.equals(GAME)));
	}

	private static List<WeaveHarness.Finding> confirmedRequired(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && f.confirmedRequired()).toList();
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER,
				"fixture.commonpayload.Probe", "probe", properties);
	}
}
