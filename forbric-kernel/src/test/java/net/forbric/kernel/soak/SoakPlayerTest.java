package net.forbric.kernel.soak;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The rule a telemetry row is held to as the controller writes it: the soak's player is a survival player on the
 * server and on the client.
 *
 * <p>The controller used to put a drifted game type right before the next move and carry on, while
 * {@code run/compat/soak-run.py} refused the whole run for the one row that showed it, when the two hours were over
 * (C6 m34fix review, soak finding 1). Both now apply this rule, the controller at once.
 */
class SoakPlayerTest {
	@Test
	void aSurvivalPlayerOnBothSidesIsTheSoaksPlayer() {
		assertNull(SoakPlayer.problem(true, "survival", true, "survival"));
	}

	@Test
	void anyOtherGameTypeOnTheServerIsNotAndTheRowSaysWhich() {
		for (String other : new String[] { "creative", "adventure", "spectator" }) {
			String problem = SoakPlayer.problem(true, other, true, "survival");
			assertNotNull(problem, other);
			assertTrue(problem.contains("the server says the soak player is a " + other + " player"), problem);
		}
		String unreported = SoakPlayer.problem(true, null, true, "survival");
		assertNotNull(unreported);
		assertTrue(unreported.contains("of no reported game type"), unreported);
	}

	/** The client's game type decides what the client draws and runs for the player, which is what the soak is for. */
	@Test
	void anyOtherGameTypeOnTheClientIsNotEither() {
		for (String other : new String[] { "creative", "adventure", "spectator", null }) {
			String problem = SoakPlayer.problem(true, "survival", true, other);
			assertNotNull(problem, String.valueOf(other));
			assertTrue(problem.contains("the client holds the soak player as"), problem);
		}
	}

	/**
	 * A sample taken just before a disconnect can be written just after it: the server had the player, the client has
	 * left the world and holds no game type. That is not a drift, and must not fail a healthy soak at every disconnect.
	 */
	@Test
	void aClientThatHasLeftTheWorldHasNoGameTypeToHold() {
		assertNull(SoakPlayer.problem(true, "survival", false, null));
		assertNotNull(SoakPlayer.problem(true, "creative", false, null), "the server's side is still held");
	}

	@Test
	void aRowWithoutThePlayerIsHeldToNothing() {
		assertNull(SoakPlayer.problem(false, null, false, null));
		assertNull(SoakPlayer.problem(false, "creative", true, "creative"));
	}

	/**
	 * A session is joined once the server has the live player, the client holds a live one, and both call it a
	 * survival player. A client still holding the game type the saved player had is preparation, not a join: from the
	 * join on every row is held to the rule, so the join must not come before the client has caught up.
	 */
	@Test
	void aSessionIsJoinedOnlyOnceBothSidesHoldALiveSurvivalPlayer() {
		assertTrue(SoakPlayer.joined(true, "survival", true, "survival"));
		assertFalse(SoakPlayer.joined(false, "survival", true, "survival"), "no live player on the server");
		assertFalse(SoakPlayer.joined(true, "survival", false, "survival"), "no live player on the client");
		assertFalse(SoakPlayer.joined(true, "survival", true, "creative"), "the client has not been told yet");
		assertFalse(SoakPlayer.joined(true, "survival", true, null), "the client holds no game type yet");
		assertFalse(SoakPlayer.joined(true, "creative", true, "creative"), "the server has not made it one yet");
	}

	/** The word the controller holds a row to is the word {@code soak-run.py} refuses a run by. */
	@Test
	void theVerifierRefusesByTheSameWord() throws Exception {
		Matcher verifier = Pattern.compile("(?m)^SOAK_GAME_TYPE = '([a-z]+)'$")
				.matcher(Files.readString(Path.of("run/compat/soak-run.py")));
		assertTrue(verifier.find(), "soak-run.py declares SOAK_GAME_TYPE");
		assertEquals(SoakPlayer.GAME_TYPE, verifier.group(1));
	}
}
