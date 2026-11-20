package net.forbric.kernel.soak;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * The soak's player, read out of the compiled controller: a survival player, prepared in the one order that works, and
 * a telemetry row that carries what {@code run/compat/soak-run.py} reads.
 *
 * <p>The controller links the game, so it cannot run off-game; what it does to the player is a short straight-line
 * method, and its three invariants are visible in its bytecode:
 * <ol>
 * <li>the game type it asks for is SURVIVAL, and CREATIVE is named nowhere in the class (it was creative until the C6
 *     acceptance soak, whose client skipped every path that belongs to a player who can be hurt);</li>
 * <li>the game type is set BEFORE the abilities and the client is told AFTER them: {@code ServerPlayer.setGameMode}
 *     overwrites {@code mayfly} and {@code flying} with the game type's values, so abilities written first would be
 *     lost, and abilities written after {@code onUpdateAbilities} would never reach the client;</li>
 * <li>invulnerability and no-gravity are on the entity, not left to the game type, since survival gives neither.</li>
 * <li>the game type is set only while a session joins. Before a move a type that differs is the world's doing, and
 *     the move fails the soak instead of putting it right in silence (the run would be refused when it was over);</li>
 * <li>every row is held to {@link SoakPlayer} as it is written, the row first, so the evidence holds the row;</li>
 * <li>asking whether it rains on the player never loads a chunk.</li>
 * </ol>
 * The real client run that shows the outcome (the server reporting survival, flight kept, no damage) is the control
 * soak; this test is what keeps the order from being rearranged between such runs.
 */
class SoakPlayerPreparationTest {
	private static final Path CONTROLLER =
			Path.of("build/classes/java/runtime/net/forbric/kernel/runtime/soak/ClientSoakController.class");
	private static final String GAME_TYPE = "net/minecraft/world/level/GameType";
	private static final String ABILITIES = "net/minecraft/world/entity/player/Abilities";

	private static ClassNode controller() throws Exception {
		TestFixtures.require(Fixture.GAME_SIDE, Files.isRegularFile(CONTROLLER), "the game-side classes are not compiled");
		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(CONTROLLER)).accept(node, 0);
		return node;
	}

	private static MethodNode preparePlayer(ClassNode node) {
		return node.methods.stream().filter(method -> method.name.equals("preparePlayer")).findFirst().orElseThrow();
	}

	@Test
	void theSoakPlayerIsASurvivalPlayerAndCreativeIsNamedNowhere() throws Exception {
		ClassNode node = controller();
		Set<String> inPrepare = new LinkedHashSet<>(), anywhere = new LinkedHashSet<>();
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode instruction : method.instructions) {
				if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
						&& field.owner.equals(GAME_TYPE)) {
					anywhere.add(field.name);
					if (method.name.equals("preparePlayer")) inPrepare.add(field.name);
				}
			}
		}
		assertEquals(Set.of("SURVIVAL"), inPrepare, "preparePlayer asks for exactly one game type");
		assertEquals(Set.of("SURVIVAL"), anywhere, "no other method of the controller names a game type");
	}

	@Test
	void theGameTypeIsSetBeforeTheAbilitiesAndTheClientIsToldAfterThem() throws Exception {
		MethodNode method = preparePlayer(controller());
		int setGameMode = -1, mayfly = -1, flying = -1, told = -1;
		for (AbstractInsnNode instruction : method.instructions) {
			int at = method.instructions.indexOf(instruction);
			if (instruction instanceof MethodInsnNode call && call.name.equals("setGameMode")) {
				assertEquals(-1, setGameMode, "one setGameMode call");
				setGameMode = at;
			}
			if (instruction instanceof MethodInsnNode call && call.name.equals("onUpdateAbilities")) told = at;
			if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD && field.owner.equals(ABILITIES)) {
				assertTrue(wroteTrue(instruction), "the soak only ever switches an ability on: " + field.name);
				if (field.name.equals("mayfly")) mayfly = at;
				if (field.name.equals("flying")) flying = at;
			}
		}
		assertTrue(setGameMode >= 0 && mayfly >= 0 && flying >= 0 && told >= 0,
				"setGameMode, mayfly, flying and onUpdateAbilities are all there: " + List.of(setGameMode, mayfly, flying, told));
		assertTrue(setGameMode < mayfly && setGameMode < flying,
				"setGameMode resets the abilities to the game type's, so it comes first: " + List.of(setGameMode, mayfly, flying));
		assertTrue(told > mayfly && told > flying,
				"onUpdateAbilities is what tells the client, so it comes after both: " + List.of(mayfly, flying, told));
	}

	@Test
	void theEntityItselfIsInvulnerableAndWithoutGravity() throws Exception {
		MethodNode method = preparePlayer(controller());
		List<String> switchedOn = new ArrayList<>();
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof MethodInsnNode call && (call.name.equals("setInvulnerable") || call.name.equals("setNoGravity"))) {
				assertTrue(wroteTrue(instruction), call.name + " is called with true");
				switchedOn.add(call.name);
			}
		}
		assertEquals(List.of("setInvulnerable", "setNoGravity"), switchedOn);
	}

	/**
	 * What soak-run.py reads from an occupied row is what the controller writes: every key of its CONDITION_KEYS is a
	 * string constant of the controller. A key renamed on one side only would turn "the player was not a survival
	 * player" into the verdict of every run, or empty the weather summary without a word.
	 */
	@Test
	void theTelemetryCarriesEveryKeyTheVerifierReads() throws Exception {
		ClassNode node = controller();
		Set<String> constants = new LinkedHashSet<>();
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode instruction : method.instructions) {
				if (instruction instanceof LdcInsnNode constant && constant.cst instanceof String text) constants.add(text);
			}
		}
		String verifier = Files.readString(Path.of("run/compat/soak-run.py"));
		Matcher keys = Pattern.compile("CONDITION_KEYS = \\(([^)]*)\\)").matcher(verifier);
		assertTrue(keys.find(), "soak-run.py declares CONDITION_KEYS");
		List<String> read = new ArrayList<>();
		Matcher key = Pattern.compile("'([A-Za-z]+)'").matcher(keys.group(1));
		while (key.find()) read.add(key.group(1));
		assertTrue(read.containsAll(List.of("gameType", "raining", "thundering", "rainingAtPlayer")), read.toString());
		List<String> unwritten = read.stream().filter(name -> !constants.contains(name)).toList();
		assertEquals(List.of(), unwritten, "keys soak-run.py reads and the controller never writes");
		assertFalse(constants.contains("creative"), "the controller writes the game type the server reports, not a literal");
	}

	/**
	 * {@code preparePlayer(player, joining)}: a session that is joining may set the game type; a move may not. The
	 * method throws before it reaches {@code setGameMode} when it is not joining, and the two callers say which they
	 * are with a constant, so neither can be mistaken for the other.
	 */
	@Test
	void onlyAJoiningSessionSetsTheGameTypeAndAMoveFailsOnADriftedOne() throws Exception {
		ClassNode node = controller();
		MethodNode method = preparePlayer(node);
		assertEquals("(Lnet/minecraft/server/level/ServerPlayer;Z)V", method.desc);
		int readsJoining = -1, refuses = -1, setGameMode = -1;
		for (AbstractInsnNode instruction : method.instructions) {
			int at = method.instructions.indexOf(instruction);
			if (instruction instanceof VarInsnNode load && load.getOpcode() == Opcodes.ILOAD && load.var == 1 && readsJoining < 0) readsJoining = at;
			if (instruction.getOpcode() == Opcodes.ATHROW && refuses < 0) refuses = at;
			if (instruction instanceof MethodInsnNode call && call.name.equals("setGameMode")) setGameMode = at;
		}
		assertTrue(readsJoining >= 0 && readsJoining < refuses && refuses < setGameMode,
				"joining is read, then the not-joining case throws, and only then is the game type set: "
						+ List.of(readsJoining, refuses, setGameMode));

		List<String> callers = new ArrayList<>();
		for (MethodNode caller : node.methods) {
			for (AbstractInsnNode instruction : caller.instructions) {
				if (!(instruction instanceof MethodInsnNode call) || !call.name.equals("preparePlayer")) continue;
				AbstractInsnNode before = instruction.getPrevious();
				while (before != null && before.getOpcode() < 0) before = before.getPrevious();
				String joining = before == null ? "?" : before.getOpcode() == Opcodes.ICONST_1 ? "joining"
						: before.getOpcode() == Opcodes.ICONST_0 ? "moving" : "?";
				callers.add((caller.name.startsWith("lambda$") ? "the move" : caller.name) + ":" + joining);
			}
		}
		assertEquals(List.of("observe:joining", "the move:moving"), callers.stream().sorted().toList());
	}

	/**
	 * The client tick holds every row to {@link SoakPlayer}: a join is decided by {@code joined}, and a sample is
	 * written and then checked by {@code problem}, whose answer is thrown. Written first, so the row that shows the
	 * drift is in the telemetry the verdict is explained from.
	 */
	@Test
	void everyRowIsHeldToTheSoakPlayerRuleAsItIsWritten() throws Exception {
		MethodNode tick = controller().methods.stream().filter(method -> method.name.equals("tick")).findFirst().orElseThrow();
		int joined = -1, wroteSample = -1, problem = -1, thrown = -1, moved = -1;
		for (AbstractInsnNode instruction : tick.instructions) {
			int at = tick.instructions.indexOf(instruction);
			if (instruction instanceof MethodInsnNode call && call.owner.equals("net/forbric/kernel/soak/SoakPlayer")) {
				if (call.name.equals("joined")) joined = at;
				if (call.name.equals("problem")) problem = at;
			}
			if (instruction instanceof LdcInsnNode constant && "sample".equals(constant.cst)) {
				AbstractInsnNode next = instruction.getNext();
				while (next != null && !(next instanceof MethodInsnNode)) next = next.getNext();
				if (next instanceof MethodInsnNode call && call.name.equals("write")) wroteSample = tick.instructions.indexOf(next);
			}
			if (problem >= 0 && thrown < 0 && instruction.getOpcode() == Opcodes.ATHROW) thrown = at;
			if (problem >= 0 && moved < 0 && instruction instanceof MethodInsnNode call && call.name.equals("apply")) moved = at;
		}
		assertTrue(joined >= 0, "a join is decided by SoakPlayer.joined");
		assertTrue(wroteSample >= 0 && problem > wroteSample, "the sample row is written before it is judged: " + List.of(wroteSample, problem));
		assertTrue(thrown > problem && moved > thrown, "a row that is not a soak player's fails the soak before the next action is applied: "
				+ List.of(problem, thrown, moved));
	}

	/**
	 * {@code Level.isRainingAt} takes the column's chunk with a required {@code getChunk}, which on the server waits for
	 * a chunk that is ticketed but not loaded yet (the seconds after a teleport). The telemetry asks it only after
	 * {@code getChunkNow}, which never loads, has shown the chunk, and loads none itself.
	 */
	@Test
	void askingWhetherItRainsOnThePlayerLoadsNoChunk() throws Exception {
		MethodNode view = controller().methods.stream().filter(method -> method.name.equals("worldView")).findFirst().orElseThrow();
		int present = -1, rains = -1;
		for (AbstractInsnNode instruction : view.instructions) {
			int at = view.instructions.indexOf(instruction);
			if (instruction instanceof MethodInsnNode call) {
				if (call.name.equals("getChunkNow")) present = at;
				if (call.name.equals("isRainingAt")) rains = at;
				assertFalse(call.name.equals("getChunk") || call.name.equals("getChunkAt") || call.name.equals("getHeight")
						|| call.name.equals("getHeightmapPos"), "the telemetry itself loads no chunk: " + call.name);
			}
		}
		assertTrue(present >= 0 && rains > present, "getChunkNow, and only then isRainingAt: " + List.of(present, rains));
		// Asked only when the chunk is there: between the two a conditional jump leads past isRainingAt. A getChunkNow
		// whose answer is computed and then not used has jumps too, but none of them skips the question.
		List<Integer> skips = new ArrayList<>();
		for (AbstractInsnNode instruction : view.instructions) {
			int at = view.instructions.indexOf(instruction);
			if (at > present && at < rains && instruction instanceof JumpInsnNode jump && jump.getOpcode() != Opcodes.GOTO
					&& view.instructions.indexOf(jump.label) > rains) skips.add(at);
		}
		assertEquals(1, skips.size(), "one conditional jump after getChunkNow that skips isRainingAt: " + skips);
	}

	/** Whether the value on top of the stack at {@code instruction} is the constant {@code true}. */
	private static boolean wroteTrue(AbstractInsnNode instruction) {
		AbstractInsnNode before = instruction.getPrevious();
		while (before != null && before.getOpcode() < 0) before = before.getPrevious();
		return before != null && before.getOpcode() == Opcodes.ICONST_1;
	}
}
