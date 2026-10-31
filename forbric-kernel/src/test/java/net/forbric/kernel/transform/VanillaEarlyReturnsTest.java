/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * {@link VanillaEarlyReturns} on bodies javac really compiles both ways: {@link Vanilla} written with guard clauses,
 * {@link Folded} the way the decompiler writes them back. A probe call is put before each method's last return —
 * what an {@code @Inject(at = @At("TAIL"))} handler compiles to — and another before every return with its ordinal,
 * what {@code @At(value = "RETURN", ordinal = n)} names. The classes are DEFINED and RUN, so the JVM's own verifier
 * passes on every frame the split writes, and each call is asserted to return the same value, through the same return,
 * with the TAIL probe firing exactly when it fires in vanilla. The unrepaired folded class is run too, and must differ:
 * that is the bug, measured.
 */
class VanillaEarlyReturnsTest {
	/** What a TAIL handler sees: one line per call that reached the method's last return. */
	public static final class Probe {
		public static final List<String> HITS = new ArrayList<>();

		public static void tail() {
			HITS.add("TAIL");
		}

		/** Which return the call left by, counted the way Mixin counts {@code RETURN} ordinals. */
		public static void left(int ordinal) {
			HITS.add("RETURN " + ordinal);
		}
	}

	/** A value a method returns from several returns, the way {@code getArmPose} returns {@code ArmPose}. */
	public enum Pose { EMPTY, ITEM, SPEAR }

	@SuppressWarnings("unused")
	public static final class Vanilla {
		public Vanilla() {
		}

		public Vanilla(Object a, List<String> log) {
			if (a == null) return;
			log.add("ctor");
		}

		public static void guard(Object a, Object b, List<String> log) {
			if (a == null || b == null) return;
			log.add("body");
		}

		public static int ternary(int x) {
			if (x < 0) return -1;
			return x * 2 + 1;
		}

		public static void earlyBlock(boolean a, List<String> log) {
			if (a) {
				log.add("early");
				return;
			}
			log.add("main");
		}

		public static void earlyFallsThrough(boolean a, List<String> log) {
			if (!a) {
				log.add("early");
				return;
			}
			log.add("main");
		}

		public static void wide(long t, double d, Object a, List<String> log) {
			double scaled = d * t;
			if (a == null || scaled < 0) return;
			long sum = t + (long) scaled;
			log.add("sum " + sum);
		}

		public static void inTry(Object a, List<String> log) {
			try {
				if (a == null) return;
				log.add("try " + a.hashCode() / (a.equals("zero") ? 0 : 1));
			} catch (ArithmeticException e) {
				log.add("catch");
			}
		}

		public static void twoEarly(int k, List<String> log) {
			if (k == 1) return;
			log.add("past one");
			if (k == 2) return;
			log.add("past two");
		}

		public static void mixed(int k, List<String> log) {
			if (k == 1) return;
			log.add("a");
			if (k == 2) return;
			log.add("b");
		}

		/**
		 * {@code HumanoidMobRenderer.getArmPose}'s shape (issue #54): two guards return the same constant, so both
		 * early edges carry ONE key, labelled {@code 0,1}.
		 */
		public static Pose armPose(boolean stab, boolean swinging, boolean spear) {
			if (stab && swinging) return Pose.SPEAR;
			if (spear) return Pose.SPEAR;
			return Pose.EMPTY;
		}

		/** {@code AvatarRenderer.getArmPose}'s: the same key on three returns, only the last of them folded. */
		public static Pose handPose(int use, boolean stab, boolean spear) {
			if (use == 0) return Pose.EMPTY;
			if (use == 9) return Pose.SPEAR;
			if (stab) return Pose.SPEAR;
			if (spear) return Pose.SPEAR;
			return Pose.ITEM;
		}

		/** One key on two returns, BOTH folded: each occurrence must still leave by its own return. */
		public static Pose eitherPose(boolean a, boolean b) {
			if (a) return Pose.SPEAR;
			if (b) return Pose.SPEAR;
			return Pose.EMPTY;
		}

		/**
		 * {@code Identifier.equals}' shape: two {@code iconst_0} blocks, one key — the {@code &&} chain's false goes
		 * to return 1, "not a String" to the tail.
		 */
		public static boolean same(Object o, String a) {
			if (o == a) return true;
			if (o instanceof String s) {
				return s.length() == a.length() && s.equals(a);
			}
			return false;
		}

		/** {@code CommandSuggestions.hasAllowedInput}'s: two folded guards, the second one the fall-through. */
		public static boolean allowed(boolean message, boolean messages, boolean command, boolean commands) {
			if (message && !messages) return false;
			if (command && !commands) return false;
			return true;
		}

		/**
		 * {@code EntityFlagsPredicate.matches}': two guards whose last three instructions are the same — what tells
		 * them apart is the call four back, so the census needs a longer lead.
		 */
		public static boolean fits(String s) {
			if (s.hashCode() + 1 > 3) return false;
			if (s.length() + 1 > 3) return false;
			return true;
		}
	}

	@SuppressWarnings("unused")
	public static final class Folded {
		public Folded() {
		}

		public Folded(Object a, List<String> log) {
			if (a != null) {
				log.add("ctor");
			}
		}

		public static void guard(Object a, Object b, List<String> log) {
			if (a != null && b != null) {
				log.add("body");
			}
		}

		public static int ternary(int x) {
			return x < 0 ? -1 : x * 2 + 1;
		}

		public static void earlyBlock(boolean a, List<String> log) {
			if (a) {
				log.add("early");
			} else {
				log.add("main");
			}
		}

		public static void earlyFallsThrough(boolean a, List<String> log) {
			if (a) {
				log.add("main");
			} else {
				log.add("early");
			}
		}

		public static void wide(long t, double d, Object a, List<String> log) {
			double scaled = d * t;
			if (a != null && !(scaled < 0)) {
				long sum = t + (long) scaled;
				log.add("sum " + sum);
			}
		}

		public static void inTry(Object a, List<String> log) {
			try {
				if (a != null) {
					log.add("try " + a.hashCode() / (a.equals("zero") ? 0 : 1));
				}
			} catch (ArithmeticException e) {
				log.add("catch");
			}
		}

		public static void twoEarly(int k, List<String> log) {
			if (k != 1) {
				log.add("past one");
				if (k != 2) {
					log.add("past two");
				}
			}
		}

		/** The first guard survived the round trip, the second was folded: one inline return before the block. */
		public static void mixed(int k, List<String> log) {
			if (k == 1) return;
			log.add("a");
			if (k != 2) {
				log.add("b");
			}
		}

		/** The merged base's {@code getArmPose}: the second guard became a ternary into the tail. */
		public static Pose armPose(boolean stab, boolean swinging, boolean spear) {
			if (stab && swinging) return Pose.SPEAR;
			return spear ? Pose.SPEAR : Pose.EMPTY;
		}

		public static Pose handPose(int use, boolean stab, boolean spear) {
			if (use == 0) return Pose.EMPTY;
			if (use == 9) return Pose.SPEAR;
			if (stab) return Pose.SPEAR;
			return spear ? Pose.SPEAR : Pose.ITEM;
		}

		public static Pose eitherPose(boolean a, boolean b) {
			return a ? Pose.SPEAR : (b ? Pose.SPEAR : Pose.EMPTY);
		}

		/** The recompiled {@code Identifier.equals}: "not a String"'s {@code iconst_0} now comes FIRST. */
		public static boolean same(Object o, String a) {
			if (o == a) return true;
			return !(o instanceof String s) ? false : s.length() == a.length() && s.equals(a);
		}

		/** The recompiled {@code hasAllowedInput}: the second guard's {@code false} falls into the tail. */
		public static boolean allowed(boolean message, boolean messages, boolean command, boolean commands) {
			return message && !messages ? false : !command || commands;
		}

		/** Recompiled the other way round: the second guard's {@code false} comes first. */
		public static boolean fits(String s) {
			return s.hashCode() + 1 <= 3 ? s.length() + 1 <= 3 : false;
		}
	}

	private static final String FOLDED = Type(Folded.class);

	@Test
	void theTailProbeFiresExactlyWhereVanillasDoes() throws Exception {
		Class<?> vanilla = define(probed(read(Vanilla.class)), Vanilla.class.getName());
		Class<?> folded = define(probed(read(Folded.class)), Folded.class.getName());
		ClassNode repairedNode = read(Folded.class);
		int split = VanillaEarlyReturns.restore(repairedNode, rows());
		assertEquals(15, split, "every fixture method is split");
		Class<?> repaired = define(probed(repairedNode), Folded.class.getName());

		List<String> differences = new ArrayList<>();
		for (Object[] call : calls()) {
			String expected = run(vanilla, call);
			assertEquals(expected, run(repaired, call), "repaired " + describe(call));
			String before = run(folded, call);
			if (!expected.equals(before)) differences.add(describe(call));
		}
		// The bug, measured on the same calls: the folded body reaches its tail on the early paths.
		assertTrue(differences.contains("guard(null, y)") && differences.contains("<init>(null)")
				&& differences.contains("ternary(-5)") && differences.contains("earlyBlock(true)")
				&& differences.contains("earlyFallsThrough(false)") && differences.contains("wide(null)")
				&& differences.contains("inTry(null)") && differences.contains("twoEarly(2)") && differences.contains("mixed(2)")
				&& !differences.contains("mixed(1)") && differences.contains("armPose(false, false, true)")
				&& differences.contains("handPose(5, false, true)") && differences.contains("eitherPose(true, false)")
				&& differences.contains("same(y, x)") && differences.contains("allowed(true, false, false, false)"),
				"folded differs on " + differences);
	}

	@Test
	void oneKeyOnSeveralReturnsPairsEachOccurrenceWithItsOwnReturn() throws Exception {
		// Issue #54 read "7e7dfdaf=0,1" as two branches collapsed onto one: it is one key whose two occurrences went to
		// vanilla's returns 0 and 1. Only the folded one moves, to its own block; what each call returns never changes.
		Map<String, Map<String, List<String>>> rows = rows();
		MethodNode armPose = method(read(Vanilla.class), "armPose");
		MethodNode handPose = method(read(Vanilla.class), "handPose");
		MethodNode eitherPose = method(read(Vanilla.class), "eitherPose");
		assertTrue(labelsOf(rows.get("armPose" + armPose.desc)).contains(List.of("0", "1")), "armPose: " + rows.get("armPose" + armPose.desc));
		assertTrue(labelsOf(rows.get("handPose" + handPose.desc)).contains(List.of("1", "2", "3")), "handPose: " + rows.get("handPose" + handPose.desc));
		assertTrue(labelsOf(rows.get("eitherPose" + eitherPose.desc)).contains(List.of("0", "1")), "eitherPose: " + rows.get("eitherPose" + eitherPose.desc));

		ClassNode node = read(Folded.class);
		VanillaEarlyReturns.restore(node, rows);
		assertEquals(new VanillaEarlyReturns.Split(1, 1), VanillaEarlyReturns.splitOf(FOLDED, "armPose", armPose.desc));
		assertEquals(new VanillaEarlyReturns.Split(3, 1), VanillaEarlyReturns.splitOf(FOLDED, "handPose", handPose.desc));
		assertEquals(new VanillaEarlyReturns.Split(0, 2), VanillaEarlyReturns.splitOf(FOLDED, "eitherPose", eitherPose.desc),
				"two occurrences of one key, two blocks — not one");

		Class<?> vanilla = define(probed(read(Vanilla.class)), Vanilla.class.getName());
		Class<?> repaired = define(probed(node), Folded.class.getName());
		assertEquals("SPEAR [] [RETURN 0]", run(vanilla, new Object[] {"armPose", true, true, false}));
		assertEquals("SPEAR [] [RETURN 1]", run(repaired, new Object[] {"armPose", false, false, true}));
		assertEquals("EMPTY [] [TAIL, RETURN 2]", run(repaired, new Object[] {"armPose", false, false, false}));
		assertEquals("SPEAR [] [RETURN 3]", run(repaired, new Object[] {"handPose", 5, false, true}));
		assertEquals("ITEM [] [TAIL, RETURN 4]", run(repaired, new Object[] {"handPose", 5, false, false}));
		assertEquals("SPEAR [] [RETURN 0]", run(repaired, new Object[] {"eitherPose", true, false}));
		assertEquals("SPEAR [] [RETURN 1]", run(repaired, new Object[] {"eitherPose", false, true}));
		assertEquals("EMPTY [] [TAIL, RETURN 2]", run(repaired, new Object[] {"eitherPose", false, false}));
	}

	@Test
	void occurrencesOfOneKeyPairByWhatLeadsIntoThemNotByOrder() throws Exception {
		// Identifier.equals: vanilla's "not an Identifier" false is its LAST iconst_0 and goes to the tail; the
		// recompiler emits it FIRST. Paired by order it took the early return and the && chain's false took the tail.
		Map<String, Map<String, List<String>>> rows = rows();
		MethodNode same = method(read(Vanilla.class), "same");
		assertTrue(labelsOf(rows.get("same" + same.desc)).contains(List.of("1", "T")), "same: " + rows.get("same" + same.desc));
		ClassNode node = read(Folded.class);
		VanillaEarlyReturns.restore(node, rows);
		Class<?> vanilla = define(probed(read(Vanilla.class)), Vanilla.class.getName());
		Class<?> repaired = define(probed(node), Folded.class.getName());
		for (Object o : new Object[] {7, "x", "y", "xy"}) {
			Object[] call = {"same", o, "x"};
			assertEquals(run(vanilla, call), run(repaired, call), describe(call));
		}
		assertEquals("false [] [TAIL, RETURN 2]", run(repaired, new Object[] {"same", 7, "x"}), "not a String: the tail, as vanilla");
		assertEquals("false [] [RETURN 1]", run(repaired, new Object[] {"same", "y", "x"}), "the && chain's false: return 1, as vanilla");
	}

	@Test
	void aLeadTakesAsManyInstructionsAsVanillaNeedsToTellItsOwnApart() throws Exception {
		MethodNode fits = method(read(Vanilla.class), "fits");
		List<String> labels = rows().get("fits" + fits.desc).values().stream()
				.filter(l -> l.size() == 2).findFirst().orElseThrow();
		assertTrue(labels.get(0).startsWith("0@4:") && labels.get(1).startsWith("1@4:"), "three instructions tie, four do not: " + labels);
		ClassNode node = read(Folded.class);
		VanillaEarlyReturns.restore(node, rows());
		Class<?> repaired = define(probed(node), Folded.class.getName());
		String high = "zzzz", low = "";   // "zzzz".hashCode() > 2 and length 4 > 2; "" has both 0
		assertTrue(high.hashCode() + 1 > 3);
		assertEquals("false [] [RETURN 0]", run(repaired, new Object[] {"fits", high}), "the hashCode guard: return 0, as vanilla");
		assertEquals("false [] [RETURN 1]", run(repaired, new Object[] {"fits", "\u0000\u0000\u0000\u0000"}), "the length guard: return 1, as vanilla");
		assertEquals("true [] [TAIL, RETURN 2]", run(repaired, new Object[] {"fits", low}));
	}

	@Test
	void anEarlyReturnAKernelHookAddedDoesNotUnpairTheRest() throws Exception {
		// A kernel hook that adds an early return at the head before this runs (as the MinecraftForge player-attack seam
		// did in Player.hurtServer: "if (!playerAttack(...)) return false;") makes a third iconst_0 where vanilla has two.
		// Paired lead by lead, the hook's is nobody's pair and the other two still go where vanilla sends them.
		ClassNode node = read(Folded.class);
		MethodNode same = method(node, "same");
		org.objectweb.asm.tree.LabelNode go = new org.objectweb.asm.tree.LabelNode();
		InsnList seam = new InsnList();
		seam.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
		seam.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IFNONNULL, go));
		seam.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ICONST_0));
		seam.add(new org.objectweb.asm.tree.InsnNode(Opcodes.IRETURN));
		seam.add(go);
		seam.add(new org.objectweb.asm.tree.FrameNode(Opcodes.F_SAME, 0, null, 0, null));
		same.instructions.insert(seam);
		VanillaEarlyReturns.restore(node, rows());
		assertEquals(new VanillaEarlyReturns.Split(2, 1), VanillaEarlyReturns.splitOf(FOLDED, "same", same.desc));
		Class<?> repaired = define(probed(node), Folded.class.getName());
		assertEquals("false [] [RETURN 0]", run(repaired, new Object[] {"same", null, "x"}), "the seam's own return");
		assertEquals("false [] [RETURN 2]", run(repaired, new Object[] {"same", "y", "x"}), "the && chain's false: its own block");
		assertEquals("false [] [TAIL, RETURN 3]", run(repaired, new Object[] {"same", 7, "x"}), "not a String: the tail, as vanilla");
	}

	@Test
	void theBlocksKeepVanillasOrderWhenTheFallThroughIsNotTheFirst() throws Exception {
		// hasAllowedInput: the second guard's false falls into the tail. It used to get the first block, so RETURN
		// ordinals 0 and 1 swapped; it now jumps to its own block, which stays second.
		ClassNode node = read(Folded.class);
		VanillaEarlyReturns.restore(node, rows());
		Class<?> repaired = define(probed(node), Folded.class.getName());
		assertEquals("false [] [RETURN 0]", run(repaired, new Object[] {"allowed", true, false, false, false}));
		assertEquals("false [] [RETURN 1]", run(repaired, new Object[] {"allowed", false, false, true, false}));
		assertEquals("true [] [TAIL, RETURN 2]", run(repaired, new Object[] {"allowed", false, false, false, false}));
	}

	@Test
	void aSplitThatWouldLeaveTheTailWithNoEdgeIsNotMade() {
		// Rows claiming the body's path goes to an early return too: every edge into the tail would move, and a TAIL
		// handler would sit on dead code. Something was mispaired; the method stays folded.
		ClassNode node = read(Folded.class);
		Map<String, Map<String, List<String>>> rows = rows();
		String guard = "guard" + method(node, "guard").desc;
		Map<String, List<String>> everyPathEarly = new HashMap<>();
		rows.get(guard).forEach((key, labels) -> everyPathEarly.put(key, labels.stream().map(l -> l.equals("T") ? "0" : l).toList()));
		rows.put(guard, everyPathEarly);
		VanillaEarlyReturns.restore(node, rows);
		assertEquals(1, VanillaEarlyReturns.returns(method(node, "guard")).size(), "the tail keeps its edges: no split");
	}

	@Test
	void eachVanillaEarlyReturnGetsItsOwnBlockInVanillasOrder() {
		ClassNode node = read(Folded.class);
		VanillaEarlyReturns.restore(node, rows());
		MethodNode twoEarly = method(node, "twoEarly");
		VanillaEarlyReturns.Split split = VanillaEarlyReturns.splitOf(FOLDED, "twoEarly", twoEarly.desc);
		assertEquals(new VanillaEarlyReturns.Split(0, 2), split);
		assertEquals(3, VanillaEarlyReturns.returns(twoEarly).size(), "vanilla's return count, so RETURN ordinals count alike");
		VanillaEarlyReturns.Split guard = VanillaEarlyReturns.splitOf(FOLDED, "guard", method(node, "guard").desc);
		assertEquals(new VanillaEarlyReturns.Split(0, 1), guard, "two guard conditions, ONE vanilla return");
		assertEquals(new VanillaEarlyReturns.Split(1, 1), VanillaEarlyReturns.splitOf(FOLDED, "mixed", method(node, "mixed").desc),
				"the guard that survived stays where it is, before the block");
	}

	@Test
	void anEdgeVanillaAlsoSendsToTheTailIsNeverMoved() {
		// The fall-through after "main" reaches the tail in both: only the edge after "early" moves.
		ClassNode node = read(Folded.class);
		MethodNode before = method(node, "earlyBlock");
		int edgesBefore = tailEdges(before);
		VanillaEarlyReturns.restore(node, rows());
		assertEquals(edgesBefore - 1, tailEdges(method(node, "earlyBlock")));
	}

	@Test
	void aKeyThatOccursADifferentNumberOfTimesIsLeftAlone() {
		ClassNode node = read(Folded.class);
		Map<String, Map<String, List<String>>> rows = rows();
		String guard = "guard" + method(node, "guard").desc;
		Map<String, List<String>> doubled = new HashMap<>();
		rows.get(guard).forEach((key, labels) -> {
			List<String> more = new ArrayList<>(labels);
			more.add(labels.get(0));
			doubled.put(key, more);
		});
		rows.put(guard, doubled);
		VanillaEarlyReturns.restore(node, rows);
		assertEquals(1, VanillaEarlyReturns.returns(method(node, "guard")).size(), "ambiguity loses: the method stays folded");
	}

	@Test
	void theTableRowRoundTrips() {
		VanillaEarlyReturns.Row row = VanillaEarlyReturns.Row.parse("a/B#m(I)V 0000abcd=0,T 1234ffff=2");
		assertEquals("a/B", row.owner());
		assertEquals("m(I)V", row.method());
		assertEquals(List.of("0", "T"), row.keys().get("0000abcd"));
		assertEquals("a/B#m(I)V 0000abcd=0,T 1234ffff=2", row.format());
		assertNull(VanillaEarlyReturns.Row.parse("# comment"));
	}

	@Test
	void switchedOffItLeavesEveryBodyAsMerged() {
		String old = System.getProperty(VanillaEarlyReturns.PROPERTY);
		System.setProperty(VanillaEarlyReturns.PROPERTY, "off");
		try {
			byte[] bytes = bytes(Folded.class);
			assertSame(bytes, new VanillaEarlyReturns().transform(Folded.class.getName(), bytes, null));
		} finally {
			if (old == null) System.clearProperty(VanillaEarlyReturns.PROPERTY); else System.setProperty(VanillaEarlyReturns.PROPERTY, old);
		}
	}

	// --- fixtures ---

	/** The rows the census would write for the fixtures: every vanilla method's labels, keyed by the folded method. */
	static Map<String, Map<String, List<String>>> rows() {
		Map<String, Map<String, List<String>>> rows = new HashMap<>();
		for (MethodNode method : read(Vanilla.class).methods) {
			if ("<init>".equals(method.name) && "()V".equals(method.desc)) continue;
			rows.put(method.name + method.desc, VanillaEarlyReturns.labels(method));
		}
		return rows;
	}

	/** A row's labels without the leads the census attaches where one key's occurrences went different ways. */
	private static List<List<String>> labelsOf(Map<String, List<String>> row) {
		List<List<String>> out = new ArrayList<>();
		for (List<String> labels : row.values()) {
			out.add(labels.stream().map(l -> l.indexOf(VanillaEarlyReturns.LEAD_MARK) < 0 ? l : l.substring(0, l.indexOf(VanillaEarlyReturns.LEAD_MARK))).toList());
		}
		return out;
	}

	private static List<Object[]> calls() {
		List<Object[]> calls = new ArrayList<>();
		for (Object a : new Object[] {null, "x"}) for (Object b : new Object[] {null, "y"}) calls.add(new Object[] {"guard", a, b});
		calls.add(new Object[] {"<init>", null});
		calls.add(new Object[] {"<init>", "x"});
		for (int x : new int[] {-5, 0, 7}) calls.add(new Object[] {"ternary", x});
		for (boolean a : new boolean[] {true, false}) {
			calls.add(new Object[] {"earlyBlock", a});
			calls.add(new Object[] {"earlyFallsThrough", a});
		}
		calls.add(new Object[] {"wide", null});
		calls.add(new Object[] {"wide", "x"});
		calls.add(new Object[] {"wideNegative", "x"});
		calls.add(new Object[] {"inTry", null});
		calls.add(new Object[] {"inTry", "x"});
		calls.add(new Object[] {"inTry", "zero"});
		for (int k = 0; k < 4; k++) {
			calls.add(new Object[] {"twoEarly", k});
			calls.add(new Object[] {"mixed", k});
		}
		for (boolean a : new boolean[] {true, false}) for (boolean b : new boolean[] {true, false}) for (boolean c : new boolean[] {true, false}) {
			calls.add(new Object[] {"armPose", a, b, c});
		}
		for (int use : new int[] {0, 9, 5}) for (boolean b : new boolean[] {true, false}) for (boolean c : new boolean[] {true, false}) {
			calls.add(new Object[] {"handPose", use, b, c});
		}
		for (boolean a : new boolean[] {true, false}) for (boolean b : new boolean[] {true, false}) calls.add(new Object[] {"eitherPose", a, b});
		for (Object o : new Object[] {7, "x", "y", "xy"}) calls.add(new Object[] {"same", o, "x"});
		for (String fit : new String[] {"zzzz", "\u0000\u0000\u0000\u0000", ""}) calls.add(new Object[] {"fits", fit});
		for (int bits = 0; bits < 16; bits++) {
			calls.add(new Object[] {"allowed", (bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0, (bits & 8) != 0});
		}
		return calls;
	}

	private static String describe(Object[] call) {
		return switch ((String) call[0]) {
			case "guard" -> "guard(" + call[1] + ", " + call[2] + ")";
			case "wideNegative" -> "wide(negative)";
			case "armPose", "handPose" -> call[0] + "(" + call[1] + ", " + call[2] + ", " + call[3] + ")";
			case "eitherPose", "same" -> call[0] + "(" + call[1] + ", " + call[2] + ")";
			case "allowed" -> "allowed(" + call[1] + ", " + call[2] + ", " + call[3] + ", " + call[4] + ")";
			case "fits" -> "fits(" + call[1].toString().length() + " chars, hash " + call[1].hashCode() + ")";
			default -> call[0] + "(" + call[1] + ")";
		};
	}

	/** One call's whole observable outcome: result or exception, what it logged, and whether the tail probe fired. */
	private static String run(Class<?> cls, Object[] call) throws Exception {
		Probe.HITS.clear();
		List<String> log = new ArrayList<>();
		Object result;
		try {
			result = switch ((String) call[0]) {
				case "guard" -> invoke(cls, "guard", call[1], call[2], log);
				case "<init>" -> {
					Constructor<?> ctor = cls.getConstructor(Object.class, List.class);
					ctor.newInstance(call[1], log);
					yield "constructed";
				}
				case "ternary" -> invoke(cls, "ternary", call[1]);
				case "earlyBlock", "earlyFallsThrough" -> invoke(cls, (String) call[0], call[1], log);
				case "wide" -> invoke(cls, "wide", 3L, 2.5, call[1], log);
				case "wideNegative" -> invoke(cls, "wide", 3L, -2.5, call[1], log);
				case "inTry" -> invoke(cls, "inTry", call[1], log);
				case "twoEarly", "mixed" -> invoke(cls, (String) call[0], call[1], log);
				case "armPose", "handPose" -> invoke(cls, (String) call[0], call[1], call[2], call[3]);
				case "eitherPose", "same" -> invoke(cls, (String) call[0], call[1], call[2]);
				case "allowed" -> invoke(cls, "allowed", call[1], call[2], call[3], call[4]);
				case "fits" -> invoke(cls, "fits", call[1]);
				default -> throw new AssertionError(call[0]);
			};
		} catch (InvocationTargetException thrown) {
			result = "threw " + thrown.getCause();
		}
		return result + " " + log + " " + Probe.HITS;
	}

	private static Object invoke(Class<?> cls, String name, Object... args) throws Exception {
		for (Method m : cls.getMethods()) {
			if (m.getName().equals(name) && m.getParameterCount() == args.length) return m.invoke(null, args);
		}
		throw new AssertionError("no " + name);
	}

	/** Every method's last return gets the call a TAIL injector compiles to, and every return one naming its ordinal. */
	private static ClassNode probed(ClassNode node) {
		for (MethodNode method : node.methods) {
			AbstractInsnNode tail = VanillaEarlyReturns.lastReturn(method);
			if (tail == null || ("<init>".equals(method.name) && "()V".equals(method.desc))) continue;
			method.instructions.insertBefore(tail, new MethodInsnNode(Opcodes.INVOKESTATIC, Type(Probe.class), "tail", "()V", false));
			List<AbstractInsnNode> returns = VanillaEarlyReturns.returns(method);
			for (int ordinal = 0; ordinal < returns.size(); ordinal++) {
				InsnList left = new InsnList();
				left.add(new LdcInsnNode(ordinal));
				left.add(new MethodInsnNode(Opcodes.INVOKESTATIC, Type(Probe.class), "left", "(I)V", false));
				method.instructions.insertBefore(returns.get(ordinal), left);
			}
			method.maxStack++;
		}
		return node;
	}

	private static Class<?> define(ClassNode node, String binaryName) {
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		byte[] bytes = writer.toByteArray();
		return new ClassLoader(VanillaEarlyReturnsTest.class.getClassLoader()) {
			Class<?> defined = defineClass(binaryName, bytes, 0, bytes.length);
		}.defined;
	}

	private static int tailEdges(MethodNode method) {
		AbstractInsnNode tail = VanillaEarlyReturns.lastReturn(method);
		int n = 0;
		for (VanillaEarlyReturns.Edge edge : VanillaEarlyReturns.edges(method)) if (edge.target() == tail) n++;
		return n;
	}

	static MethodNode method(ClassNode node, String name) {
		for (MethodNode m : node.methods) if (m.name.equals(name)) return m;
		throw new AssertionError("no " + name);
	}

	static ClassNode read(Class<?> cls) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes(cls)).accept(node, 0);
		return node;
	}

	static byte[] bytes(Class<?> cls) {
		String resource = "/" + cls.getName().replace('.', '/') + ".class";
		try (InputStream in = VanillaEarlyReturnsTest.class.getResourceAsStream(resource)) {
			return in.readAllBytes();
		} catch (Exception e) {
			throw new AssertionError(resource, e);
		}
	}

	private static String Type(Class<?> cls) {
		return cls.getName().replace('.', '/');
	}
}
