package net.forbric.kernel.soak;

/**
 * What a telemetry row has to say of the soak's player, decided in one place for the controller that writes the row and
 * checked again by {@code run/compat/soak-run.py} ({@code require_soak_player}) when the run is over.
 *
 * <p>The soak's player is a survival player on the server <em>and</em> on the client: the server's game type decides
 * what the world does to the player, the client's decides what the client draws and runs for it, and the soak is
 * defined by the second as much as the first. Both are read by name ({@code GameType.getName()}), so this class links
 * no game class and is tested off-game.
 *
 * <p>The controller used to put a drifted game type right at the next move and say nothing, while the verifier refused
 * the whole run for the one row that showed it, hours later. A row that is not a soak player's now fails the soak when
 * it is written (C6 m34fix review, soak finding 1): the two agree, and a certain refusal no longer costs the rest of
 * the two hours.
 */
public final class SoakPlayer {
	private SoakPlayer() { }

	/** {@code GameType.SURVIVAL.getName()}; {@code soak-run.py}'s {@code SOAK_GAME_TYPE} is the same word. */
	public static final String GAME_TYPE = "survival";

	/**
	 * Why a row is not a soak player's, or null when it is (or when there is no player in it to speak of).
	 *
	 * @param occupied whether the server had the one live player in this sample
	 * @param serverGameType the game type the server reported for that player, by name; null when it reported none
	 * @param clientInWorld whether the client holds a player now. A sample taken just before a disconnect can be written
	 *        just after it, when the client has left the world and has no game type; that is not a drift
	 * @param clientGameType the game type the client holds, by name; null when it holds none
	 */
	public static String problem(boolean occupied, String serverGameType, boolean clientInWorld, String clientGameType) {
		if (!occupied) return null;
		if (!GAME_TYPE.equals(serverGameType))
			return "the server says the soak player is " + named(serverGameType) + ", not a " + GAME_TYPE + " player";
		if (clientInWorld && !GAME_TYPE.equals(clientGameType))
			return "the client holds the soak player as " + named(clientGameType) + ", not as a " + GAME_TYPE + " player";
		return null;
	}

	/**
	 * Whether a joining session's row is the session's join: the server has the live player, the client holds a live
	 * one, and both call it a soak player. Until then the time is preparation. The client learns its game type from
	 * the server a moment after the server has it, so a session whose saved player was something else is joined only
	 * once the client has caught up, and every row from the join on can be held to {@link #problem}.
	 */
	public static boolean joined(boolean occupied, String serverGameType, boolean clientAlive, String clientGameType) {
		return occupied && clientAlive && problem(true, serverGameType, true, clientGameType) == null;
	}

	private static String named(String gameType) {
		return gameType == null ? "of no reported game type" : "a " + gameType + " player";
	}
}
