package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
import net.forbric.kernel.mixin.ReplacedDecisionValues;

/**
 * {@code ReplacedDecisionValues} through the real weave, on classes that share no name with any game or mod.
 *
 * <p>{@code org.harbor.deck.Diver} asks its own {@code isUnder} on {@code tick} and on {@code surface}, and holds its
 * breath when the answer is no, as the guest's loader has it; as merged, both ask a carrier's {@code Currents.covers}
 * there instead. On {@code tick} the carrier's answer decides the very block the native one did; on {@code surface} it
 * skips another block (the diver gasps). The guest, {@code com.kestrel.gill}, makes a gilled diver count as under water
 * with a {@code @ModifyExpressionValue} on {@code isUnder} in each method.
 *
 * <p>Moved, the guest changes the carrier's answer on {@code tick}, and the guest's lines and every breath held there are
 * those of the same guest on the native diver. On {@code surface} the block differs, so nothing is moved: the guest
 * binds nothing there. Switched off, the guest is not asked anywhere and the gilled diver holds its breath on land.
 */
class ReplacedDecisionValuesWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/replaceddecision");
	private static final String CONFIG = "replaceddecision.mixins.json";
	private static final String MOD = "gill";
	private static final String TARGET = "org/harbor/deck/Diver";
	private static final String SURFACE = " | surface plain shallow: currents:shallow, gasp plain | surface plain deep: currents:deep"
			+ " | surface gilled shallow: currents:shallow, gasp gilled | surface gilled deep: currents:deep";
	private static final String MOVED = WeaveHarnessMain.DONE + " tick plain shallow: tick, currents:shallow, gill:false, hold plain, tail"
			+ " | tick plain deep: tick, currents:deep, gill:true, tail | tick gilled shallow: tick, currents:shallow, gill:false, tail"
			+ " | tick gilled deep: tick, currents:deep, gill:true, tail" + SURFACE;
	private static final String OFF = WeaveHarnessMain.DONE + " tick plain shallow: tick, currents:shallow, hold plain, tail"
			+ " | tick plain deep: tick, currents:deep, tail | tick gilled shallow: tick, currents:shallow, hold gilled, tail"
			+ " | tick gilled deep: tick, currents:deep, tail" + SURFACE;
	private static final String LOGGED = "[Forbric/Mixin] com.kestrel.gill.mixin.DiverMixin.%s: org.harbor.deck.Diver.isUnder is no longer called in org.harbor.deck.Diver.%s;"
			+ " the call the merged method makes in its place, deciding the same branch, is org.harbor.carrier.Currents.covers";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result moved, off, natively;

	@BeforeAll static void weave() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = walk.filter(p -> p.toString().endsWith(".java") && !SOURCES.relativize(p).startsWith("native")).sorted().toList();
		}
		assertEquals(7, sources.size(), "the fixture's sources changed; update this test with it: " + sources);
		List<Path> nativeSources = new ArrayList<>(sources);
		nativeSources.remove(SOURCES.resolve("org/harbor/deck/Diver.java"));
		nativeSources.add(SOURCES.resolve("native/org/harbor/deck/Diver.java"));
		Path nativeFixture = WeaveHarness.fixture(work, "native", nativeSources, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g"));
		fixture = NativeWeaveReferences.with(work, WeaveHarness.fixture(work, "replaceddecision", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)),
				List.of("-g")), NativeWeaveReferences.classes(nativeFixture));
		moved = run(fixture, "moved", Map.of());
		off = run(fixture, "off", Map.of(ReplacedDecisionValues.PROPERTY, "off"));
		natively = run(nativeFixture, "native", Map.of());
	}

	@Test void theGuestChangesTheCarriersAnswerWhereItDecidesTheSameBlock() throws Exception {
		assertTrue(moved.printedLine(MOVED), moved.describe());
		assertTrue(moved.printed(String.format(LOGGED, "gill$wet", "tick")), moved.describe());
		assertTrue(callsGuestAfterCarrier(moved, "tick"), moved.describe());
		WeaveHarness.assertWovenAndVerified(moved, TARGET, fixture);
	}

	/** On {@code tick}, the guest's lines and the breaths held, update by update, are the native run's. */
	@Test void theGuestAndEveryBreathOnTickAreTheNativeOnes() {
		assertEquals(guestAndBreaths(natively, "tick"), guestAndBreaths(moved, "tick"), natively.describe());
	}

	/** Where the carrier's answer skips another block, nothing says it is the native answer: the point stays unbound. */
	@Test void whereTheCarrierSkipsAnotherBlockNothingIsMoved() throws Exception {
		assertFalse(moved.printed(String.format(LOGGED, "gill$surface", "surface")), moved.describe());
		assertFalse(callsGuestAfterCarrier(moved, "surface"), moved.describe());
		assertNotEquals(guestAndBreaths(natively, "surface"), guestAndBreaths(moved, "surface"), "the native oracle must tell the two apart");
	}

	@Test void switchedOffTheGuestIsNotAskedAndTheGilledDiverHoldsItsBreath() throws Exception {
		assertTrue(off.printedLine(OFF), off.describe());
		assertFalse(callsGuestAfterCarrier(off, "tick"), off.describe());
	}

	@Test void theControlFlipsEveryAssertion() {
		assertTrue(moved.printedLine(MOVED) && !off.printedLine(MOVED));
		assertTrue(off.printedLine(OFF) && !moved.printedLine(OFF));
	}

	private static List<String> guestAndBreaths(WeaveHarness.Result run, String update) {
		String line = run.output().lines().filter(l -> l.startsWith(WeaveHarnessMain.DONE + " ")).findFirst().orElse("");
		return Arrays.stream(line.substring(Math.min(line.length(), WeaveHarnessMain.DONE.length() + 1)).split(" \\| "))
				.filter(entry -> entry.startsWith(update + " "))
				.map(entry -> entry.substring(0, entry.indexOf(": ") + 2) + Arrays.stream(entry.substring(entry.indexOf(": ") + 2).split(", "))
						.filter(step -> step.startsWith("gill") || step.startsWith("hold") || step.startsWith("gasp")).collect(Collectors.joining(", ")))
				.toList();
	}

	/** Whether {@code method} of the woven diver hands the carrier's answer straight to a handler of the guest. */
	private static boolean callsGuestAfterCarrier(WeaveHarness.Result run, String method) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(run.defined(TARGET)).accept(node, 0);
		for (MethodNode body : node.methods) {
			if (!body.name.equals(method)) continue;
			for (AbstractInsnNode insn : body.instructions) {
				if (!(insn instanceof MethodInsnNode call) || !call.name.equals("covers")) continue;
				AbstractInsnNode next = call.getNext();
				while (next != null && next.getOpcode() < 0) next = next.getNext();
				for (int i = 0; i < 3 && next != null; i++, next = next.getNext())
					if (next instanceof MethodInsnNode handler && handler.owner.equals(TARGET) && handler.name.contains("gill$")) return true;
			}
		}
		return false;
	}

	private static WeaveHarness.Result run(Path fixture, String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER, "fixture.replaceddecision.Probe", "probe", properties);
	}
}
