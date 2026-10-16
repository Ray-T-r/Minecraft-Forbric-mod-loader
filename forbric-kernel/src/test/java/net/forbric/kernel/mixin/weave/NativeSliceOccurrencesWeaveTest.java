package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
import net.forbric.kernel.mixin.NativeSliceOccurrences;

/**
 * {@code NativeSliceOccurrences} through the real weave, on classes that share no name with any game or mod.
 *
 * <p>{@code org.vault.keep.Ledger.fetch} creates a missing entry and only then, for a removed one, takes it out of
 * {@code entries}, as the guest's loader has it; as merged, the removed entry is taken out first. The guest,
 * {@code com.marten.audit}, redirects that removal in a slice that starts at the creation call, so as merged its slice
 * holds no removal. {@code prune} is the control: as merged it takes an entry out under another condition (stale, not
 * removed), so its removal is not the native one.
 *
 * <p>Moved, the guest audits the removal in {@code fetch} exactly where and when the native run does; its {@code prune}
 * redirect stays unbound. Switched off, neither binds.
 */
class NativeSliceOccurrencesWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/nativeslice");
	private static final String CONFIG = "nativeslice.mixins.json";
	private static final String MOD = "audit";
	private static final String TARGET = "org/vault/keep/Ledger";
	private static final String FETCHES = " | fetch live: -> live | fetch late: promote late, -> late:note | fetch fresh: create fresh, -> fresh";
	private static final String PRUNES = " | prune gone: -> kept | prune stale: -> dropped | prune fresh: create fresh, -> dropped";
	private static final String MOVED = WeaveHarnessMain.DONE + " fetch gone: unload gone, -> null" + FETCHES + PRUNES;
	private static final String OFF = WeaveHarnessMain.DONE + " fetch gone: -> null" + FETCHES + PRUNES;
	private static final String LOGGED = "[Forbric/Mixin] com.marten.audit.mixin.LedgerMixin.%s: the call its slice picked in org.vault.keep.Ledger.%s is no "
			+ "longer inside that slice";

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
		nativeSources.remove(SOURCES.resolve("org/vault/keep/Ledger.java"));
		nativeSources.add(SOURCES.resolve("native/org/vault/keep/Ledger.java"));
		Path nativeFixture = WeaveHarness.fixture(work, "native", nativeSources, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g"));
		fixture = NativeWeaveReferences.with(work, WeaveHarness.fixture(work, "nativeslice", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)),
				List.of("-g")), NativeWeaveReferences.classes(nativeFixture));
		moved = run(fixture, "moved", Map.of());
		off = run(fixture, "off", Map.of(NativeSliceOccurrences.PROPERTY, "off"));
		natively = run(nativeFixture, "native", Map.of());
	}

	@Test void theGuestAuditsTheRemovalTheCarrierMovedOutOfItsSlice() throws Exception {
		assertTrue(moved.printedLine(MOVED), moved.describe());
		assertTrue(moved.printed(String.format(LOGGED, "audit$unload", "fetch")), moved.describe());
		assertTrue(redirects(moved, "fetch"), moved.describe());
		WeaveHarness.assertWovenAndVerified(moved, TARGET, fixture);
	}

	/** On {@code fetch}, the guest's lines and every result are the native run's. */
	@Test void everyFetchIsTheNativeOne() {
		assertEquals(updates(natively, "fetch"), updates(moved, "fetch"), natively.describe());
	}

	/** Where the merged method takes an entry out under another condition, nothing says it is the native removal. */
	@Test void aRemovalUnderAnotherConditionIsNotTheNativeOne() throws Exception {
		assertFalse(moved.printed(String.format(LOGGED, "audit$prune", "prune")), moved.describe());
		assertFalse(redirects(moved, "prune"), moved.describe());
		assertTrue(natively.output().contains("prune gone: prune gone, -> dropped"), "the native oracle audits that removal: " + natively.describe());
	}

	@Test void switchedOffNeitherRedirectBinds() throws Exception {
		assertTrue(off.printedLine(OFF), off.describe());
		assertFalse(redirects(off, "fetch"), off.describe());
	}

	@Test void theControlFlipsEveryAssertion() {
		assertTrue(moved.printedLine(MOVED) && !off.printedLine(MOVED));
		assertTrue(off.printedLine(OFF) && !moved.printedLine(OFF));
	}

	private static List<String> updates(WeaveHarness.Result run, String kind) {
		String line = run.output().lines().filter(l -> l.startsWith(WeaveHarnessMain.DONE + " ")).findFirst().orElse("");
		return Arrays.stream(line.substring(Math.min(line.length(), WeaveHarnessMain.DONE.length() + 1)).split(" \\| "))
				.filter(entry -> entry.startsWith(kind + " ")).toList();
	}

	/** Whether {@code method} of the woven ledger hands a map removal to a handler of the guest instead of making it. */
	private static boolean redirects(WeaveHarness.Result run, String method) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(run.defined(TARGET)).accept(node, 0);
		for (MethodNode body : node.methods) {
			if (!body.name.equals(method)) continue;
			for (AbstractInsnNode insn : body.instructions)
				if (insn instanceof MethodInsnNode call && call.owner.equals(TARGET) && call.name.contains("audit$")) return true;
		}
		return false;
	}

	private static WeaveHarness.Result run(Path fixture, String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER, "fixture.nativeslice.Probe", "probe", properties);
	}
}
