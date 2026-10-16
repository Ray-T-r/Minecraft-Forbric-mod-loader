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
import net.forbric.kernel.mixin.NativeEntryRelay;

/**
 * {@code NativeEntryRelay} through the real weave, on classes that share no name with any game or mod.
 *
 * <p>{@code org.mill.works.Sluice} asks its own private {@code shouldPour} from {@code open} and {@code nudge} and fills
 * the trough when it answers true, as the guest's loader has it; as merged, each caller asks a different carrier's
 * decision instead ({@code !Locks.settle}, {@code !Weirs.settle}) and nothing calls {@code shouldPour}. The guest,
 * {@code com.otter.dam}, holds ice back with a cancellable {@code HEAD} {@code @Inject} on {@code shouldPour}.
 *
 * <p>Relayed, the guest is asked first at both callers, its veto stops the fill and the carrier's decision, and
 * everywhere else each carrier decides as merged — the guest's lines and every fill are those of the same guest on the
 * native sluice. The relayed method's own body does not run (the carrier's decision stands for it). Switched off, the
 * guest is never asked and ice is poured.
 */
class NativeEntryRelayWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/nativeentry");
	private static final String CONFIG = "nativeentry.mixins.json";
	private static final String MOD = "dam";
	private static final String TARGET = "org/mill/works/Sluice";
	private static final String RELAYED = WeaveHarnessMain.DONE + " open river: dam:river, locks:river, fill 3, tail | nudge river: dam:river, weirs:river, fill 3"
			+ " | open ice: dam:ice, tail | nudge ice: dam:ice | open mud: dam:mud, locks:mud, tail | nudge mud: dam:mud, weirs:mud";
	private static final String OFF = WeaveHarnessMain.DONE + " open river: locks:river, fill 3, tail | nudge river: weirs:river, fill 3"
			+ " | open ice: locks:ice, fill 3, tail | nudge ice: weirs:ice, fill 3 | open mud: locks:mud, tail | nudge mud: weirs:mud";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result relayed, off, natively;

	@BeforeAll static void weave() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = walk.filter(p -> p.toString().endsWith(".java") && !SOURCES.relativize(p).startsWith("native")).sorted().toList();
		}
		assertEquals(9, sources.size(), "the fixture's sources changed; update this test with it: " + sources);
		List<Path> nativeSources = new ArrayList<>(sources);
		nativeSources.remove(SOURCES.resolve("org/mill/works/Sluice.java"));
		nativeSources.add(SOURCES.resolve("native/org/mill/works/Sluice.java"));
		Path nativeFixture = WeaveHarness.fixture(work, "native", nativeSources, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g"));
		fixture = NativeWeaveReferences.with(work, WeaveHarness.fixture(work, "nativeentry", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)),
				List.of("-g")), NativeWeaveReferences.classes(nativeFixture));
		relayed = run(fixture, "relayed", Map.of());
		off = run(fixture, "off", Map.of(NativeEntryRelay.PROPERTY, "off"));
		natively = run(nativeFixture, "native", Map.of());
	}

	@Test void theGuestIsAskedFirstAndItsVetoStopsTheFillAndTheCarrier() throws Exception {
		assertTrue(relayed.printedLine(RELAYED), relayed.describe());
		assertTrue(relayed.printed("[Forbric/Mixin] com.otter.dam.mixin.SluiceMixin.dam$hold: org.mill.works.Sluice.shouldPour"), relayed.describe());
		assertEquals(List.of(), relayed.findings().stream().filter(f -> f.modId().equals(MOD) && !f.confidence().equals("RESOLVED")).toList(),
				relayed.describe());
		WeaveHarness.assertWovenAndVerified(relayed, TARGET, fixture);
	}

	/** The guest's lines and the fills, update by update, are the native run's. */
	@Test void theGuestAndEveryFillAreTheNativeOnes() {
		assertEquals(guestAndFills(natively), guestAndFills(relayed), natively.describe());
	}

	@Test void bothCallersAskTheMethodAgain() throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(relayed.defined(TARGET)).accept(node, 0);
		assertTrue(calls(node, "open", "shouldPour") && calls(node, "nudge", "shouldPour"), relayed.describe());
	}

	@Test void switchedOffTheGuestIsNeverAskedAndIceIsPoured() {
		assertTrue(off.printedLine(OFF), off.describe());
	}

	@Test void theControlFlipsEveryAssertion() {
		assertTrue(relayed.printedLine(RELAYED) && !off.printedLine(RELAYED));
		assertTrue(off.printedLine(OFF) && !relayed.printedLine(OFF));
	}

	private static List<String> guestAndFills(WeaveHarness.Result run) {
		String line = run.output().lines().filter(l -> l.startsWith(WeaveHarnessMain.DONE + " ")).findFirst().orElse("");
		return Arrays.stream(line.substring(Math.min(line.length(), WeaveHarnessMain.DONE.length() + 1)).split(" \\| "))
				.map(update -> update.substring(0, update.indexOf(": ") + 2) + Arrays.stream(update.substring(update.indexOf(": ") + 2).split(", "))
						.filter(step -> step.startsWith("dam:") || step.startsWith("fill")).collect(Collectors.joining(", ")))
				.toList();
	}

	private static boolean calls(ClassNode node, String method, String callee) {
		for (MethodNode body : node.methods) {
			if (!body.name.equals(method)) continue;
			for (AbstractInsnNode insn : body.instructions)
				if (insn instanceof MethodInsnNode call && call.owner.equals(TARGET) && call.name.equals(callee)) return true;
		}
		return false;
	}

	private static WeaveHarness.Result run(Path fixture, String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER, "fixture.nativeentry.Probe", "probe", properties);
	}
}
