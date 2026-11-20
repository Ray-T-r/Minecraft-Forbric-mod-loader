package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.ExpectCaught;
import net.forbric.kernel.discovery.ModAnnotationScanner.ModClassInfo;

/**
 * The construction-order line states the kernel's verdict in every case, and the gates' check on it means the same
 * thing in every case: "the dependency order was applied to construction".
 *
 * <p>The line used to be written only when the sort moved something. A pack whose {@code @Mod} classes were found in
 * dependency order already (two mods with nothing between them are enough) logged nothing, which {@code gate-m9} and
 * {@code run/compat/assert.sh} read as "construction was not ordered" and failed a healthy run on (Stage 4 C6
 * acceptance on Windows, finding W-2). Silence could not be told from ordering that was switched off or that failed.
 *
 * <p>Each case below is decided by the two scripts' OWN patterns, read out of the scripts and run through
 * {@code grep -acE} as they run them, so the wording and the checks cannot drift apart.
 */
class ConstructionOrderVerdictTest {
	@TempDir Path temporary;

	@AfterEach
	void clear() {
		System.clearProperty(ModConstructionOrder.SWITCH);
	}

	private static ModClassInfo mod(String id) {
		return new ModClassInfo("mods." + id + ".Main", id, Ecosystem.NEOFORGE);
	}

	/** The library's jar sorts after its user's, so the found order is wrong and the sort has to move a class. */
	@Test
	void anOrderThatMovedAClassSaysSoAndSatisfiesTheGates() throws Exception {
		List<ModClassInfo> found = List.of(mod("architectury"), mod("zzzlib"));

		Run run = order(found, List.of("zzzlib", "architectury"));

		assertEquals(List.of("zzzlib", "architectury"), ids(run.sorted()));
		assertEquals(1, run.lines().size(), run.log());
		assertTrue(run.log().contains("not jar-file order"), run.log());
		assertTrue(run.log().contains("[zzzlib, architectury]"), run.log());
		assertTrue(matchesGates(run.log()), run.log());
	}

	/**
	 * W-2. The classes were found in dependency order already: nothing moves, and the order still is the dependency
	 * order. The kernel says that, in the gates' words, where it used to say nothing.
	 */
	@Test
	void anOrderWithNothingToMoveStillStatesTheVerdictTheGatesLookFor() throws Exception {
		List<ModClassInfo> found = List.of(mod("jade"), mod("sodium"));

		Run run = order(found, List.of("jade", "sodium"));

		assertEquals(found, run.sorted());
		assertEquals(1, run.lines().size(), "exactly one verdict, where there was none:\n" + run.log());
		assertTrue(run.log().contains("the 2 @Mod class(es) were found in it already, so none moved"), run.log());
		assertFalse(run.log().contains("not jar-file order"), "nothing moved, so it must not claim a move:\n" + run.log());
		assertTrue(matchesGates(run.log()), "a healthy pack with nothing to reorder must satisfy the gates:\n" + run.log());
	}

	/** One class is a pack too; the order handed over names it, so the verdict is the same sentence. */
	@Test
	void aSingleClassIsInDependencyOrder() throws Exception {
		Run run = order(List.of(mod("only")), List.of("only"));

		assertTrue(matchesGates(run.log()), run.log());
		assertTrue(run.log().contains("the 1 @Mod class(es)"), run.log());
	}

	/**
	 * The check must still be able to go red. With ordering off the sort hands the list back as it came, exactly like
	 * the nothing-to-move case; the verdict is the opposite, and the gates must not be satisfied by it.
	 */
	@Test
	void orderingSwitchedOffSaysSoAndDoesNotSatisfyTheGates() throws Exception {
		System.setProperty(ModConstructionOrder.SWITCH, "name");
		List<ModClassInfo> found = List.of(mod("architectury"), mod("zzzlib"));

		Run run = order(found, List.of("zzzlib", "architectury"));

		assertEquals(found, run.sorted(), "the switch keeps the found order");
		assertEquals(1, run.lines().size(), run.log());
		assertTrue(run.log().contains("dependency ordering is off (-Dforbric.modOrder=name)"), run.log());
		assertFalse(run.log().contains(KernelModLoader.ORDER_APPLIED), run.log());
		assertFalse(matchesGates(run.log()), "ordering that was switched off is not dependency order:\n" + run.log());
	}

	/** Discovery published nothing to order by (the caller already warned if that was a failure): no claim is made. */
	@Test
	void noOrderToApplyMakesNoClaim() throws Exception {
		Run run = order(List.of(mod("architectury"), mod("zzzlib")), List.of());

		assertEquals(List.of(), run.lines(), run.log());
		assertFalse(matchesGates(run.log()));
	}

	/** A pack with no {@code @Mod} class constructs nothing: there is no order to give a verdict on, on or off. */
	@Test
	void nothingToConstructMakesNoClaim() throws Exception {
		Run run = order(List.of(), List.of("zzzlib", "architectury"));
		assertEquals(List.of(), run.lines(), run.log());
		assertFalse(matchesGates(run.log()));

		System.setProperty(ModConstructionOrder.SWITCH, "name");
		assertEquals(List.of(), order(List.of(), List.of("zzzlib")).lines());
	}

	/** Ordering that fails keeps the found order, warns, and makes no claim either. */
	@ExpectCaught("could not order mods by dependency")
	@Test
	void anOrderThatFailsWarnsAndMakesNoClaim() throws Exception {
		List<ModClassInfo> found = new ArrayList<>();
		found.add(mod("architectury"));
		found.add(null);

		Run run = order(found, List.of("zzzlib", "architectury"));

		assertEquals(found, run.sorted());
		assertFalse(run.log().contains(KernelModLoader.ORDER_APPLIED), run.log());
		assertFalse(matchesGates(run.log()));
	}

	/**
	 * The order handed over names none of the classes being constructed: other mods, another spelling of the same
	 * ids, or classes whose annotation gave no id. Nothing can move, exactly as when the classes were found in
	 * dependency order already; the verdict is the opposite, because no dependency of these classes was consulted. The
	 * sentence the gates look for was written here (C6 m34fix review, harness finding 1): a boot that published the
	 * wrong mods to the order went green on the one check that exists to notice it.
	 */
	@Test
	void anOrderThatNamesNoneOfTheClassesIsNotDependencyOrderAndDoesNotSatisfyTheGates() throws Exception {
		List<ModClassInfo> found = List.of(mod("architectury"), mod("zzzlib"));
		List<ModClassInfo> unnamed = List.of(new ModClassInfo("mods.a.Main", null, Ecosystem.NEOFORGE),
				new ModClassInfo("mods.b.Main", null, Ecosystem.NEOFORGE));

		for (Run run : List.of(order(found, List.of("fabric-api", "sodium")),
				order(found, List.of("ZZZLib", "Architectury")), order(unnamed, List.of("zzzlib", "architectury")))) {
			assertEquals(1, run.lines().size(), run.log());
			assertFalse(run.log().contains(KernelModLoader.ORDER_APPLIED), run.log());
			assertTrue(run.log().contains("names none of the 2 @Mod class(es)"), run.log());
			assertFalse(matchesGates(run.log()), "an order that knows none of these classes was not applied to them:\n"
					+ run.log());
		}
		assertEquals(found, order(found, List.of("fabric-api", "sodium")).sorted(), "and they stay as they were found");
	}

	/**
	 * The order names some of the classes. Those are in dependency order; the others belong to no mod discovery
	 * published, so nothing is known of what they need and they are constructed after the ordered ones, as found. The
	 * verdict is given for the classes it is true of, with the count and the rest named, so the line cannot be read as
	 * "all of them".
	 */
	@Test
	void anOrderThatNamesSomeOfTheClassesSaysHowManyAndWhichItDoesNot() throws Exception {
		List<ModClassInfo> found = List.of(mod("architectury"), mod("stray"), mod("zzzlib"));

		Run moved = order(found, List.of("zzzlib", "architectury"));
		assertEquals(List.of("zzzlib", "architectury", "stray"), ids(moved.sorted()));
		assertEquals(1, moved.lines().size(), moved.log());
		assertTrue(moved.log().contains("for the 2 @Mod class(es) the order names, of 3: [zzzlib, architectury]; the other 1 "), moved.log());
		assertTrue(moved.log().contains("[stray]"), moved.log());
		assertTrue(matchesGates(moved.log()), moved.log());

		Run still = order(List.of(mod("architectury"), mod("zzzlib")), List.of("architectury"));
		assertEquals(List.of("architectury", "zzzlib"), ids(still.sorted()));
		assertEquals(1, still.lines().size(), still.log());
		assertTrue(still.log().contains(": the 1 @Mod class(es) the order names, of 2, were found in it already"), still.log());
		assertTrue(still.log().contains("[zzzlib]"), still.log());
		assertFalse(still.log().contains(": the 2 @Mod class(es) were found in it already"),
				"it must not say both were found in dependency order when the order knows one:\n" + still.log());
	}

	/** The two scripts ask the same question in the same words, and those words are the constant the kernel writes. */
	@Test
	void bothGatesLookForTheSentenceTheKernelWrites() throws Exception {
		String m9 = gatePattern("run/gate-m9-client.sh", "construction is in dependency order");
		String compat = gatePattern("run/compat/assert.sh", "construction is dependency order");

		assertEquals(m9, compat, "gate-m9 and compat/assert.sh disagree about the same line");
		assertTrue(grep(m9, KernelModLoader.ORDER_APPLIED + "\n"), m9);
	}

	// --- helpers -----------------------------------------------------------------------------------------------

	private record Run(List<ModClassInfo> sorted, String log) {
		/** The construction-order lines only: the kernel's log goes to the same stream as everything else. */
		List<String> lines() {
			return log.lines().filter(line -> line.contains("[Forbric/Order] construction order")).toList();
		}
	}

	private static Run order(List<ModClassInfo> found, List<String> order) {
		ByteArrayOutputStream log = new ByteArrayOutputStream();
		PrintStream out = System.out, err = System.err;
		List<ModClassInfo> sorted;
		try {
			// Both streams, as a boot log holds both: off-game the kernel's warnings go to the error stream.
			PrintStream both = new PrintStream(log, true, StandardCharsets.UTF_8);
			System.setOut(both);
			System.setErr(both);
			sorted = KernelModLoader.orderByDependency(found, order);
		} finally {
			System.setOut(out);
			System.setErr(err);
		}
		return new Run(sorted, log.toString(StandardCharsets.UTF_8));
	}

	private static List<String> ids(List<ModClassInfo> classes) {
		return classes.stream().map(info -> info.modId).toList();
	}

	/** Whether gate-m9's AND compat/assert.sh's construction-order check accepts {@code log}, by their own patterns. */
	private boolean matchesGates(String log) throws Exception {
		boolean m9 = grep(gatePattern("run/gate-m9-client.sh", "construction is in dependency order"), log);
		boolean compat = grep(gatePattern("run/compat/assert.sh", "construction is dependency order"), log);
		assertEquals(m9, compat, "gate-m9 and compat/assert.sh disagree about the same line");
		return m9;
	}

	private static String gatePattern(String gate, String name) throws IOException {
		Matcher m = Pattern.compile("(?:check|ck)\\s+\"" + Pattern.quote(name) + "\"\\s*\\\\?\\s*\"([^\"]+)\"")
				.matcher(Files.readString(Path.of(gate)));
		if (!m.find()) throw new AssertionError(gate + " has no \"" + name + "\" check");
		return m.group(1);
	}

	private boolean grep(String pattern, String text) throws Exception {
		Path log = Files.writeString(temporary.resolve("boot.log"), text);
		Process grep = new ProcessBuilder("grep", "-acE", pattern, log.toString()).redirectErrorStream(true).start();
		assertTrue(grep.waitFor(15, TimeUnit.SECONDS), "grep timed out");
		return !"0".equals(new String(grep.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip());
	}
}
