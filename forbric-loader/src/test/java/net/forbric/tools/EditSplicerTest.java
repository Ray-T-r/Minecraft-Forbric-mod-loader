/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * The edit splice on synthetic three-way inputs: a vanilla method of three statements, a base side and an other
 * side that each patched it, and the two patches that say which of their lines are edits. Every accepted result
 * is executed and the order of the probe calls is compared with what both patches together mean.
 */
public class EditSplicerTest {

	private static final String OWNER = "net/forbric/tools/EditSplicerProbe";
	private static final String SOURCE = "net/forbric/tools/EditSplicerProbe.java";
	private static final List<Integer> CALLS = new ArrayList<>();

	public static void record(int value) {
		CALLS.add(value);
	}

	@BeforeEach
	void clear() {
		CALLS.clear();
	}

	// ---- building blocks -------------------------------------------------------------------------------------

	/** One source statement: its patched line and its instructions (locals by slot). */
	private record Statement(int line, List<AbstractInsnNode> code, String declares) {
		static Statement call(int line, int value) {
			return new Statement(line, List.of(new IntInsnNode(Opcodes.SIPUSH, value),
					new MethodInsnNode(Opcodes.INVOKESTATIC, "net/forbric/tools/EditSplicerTest", "record", "(I)V", false)), null);
		}

		static Statement returnArg(int line) {
			return new Statement(line, List.of(new VarInsnNode(Opcodes.ILOAD, 0), new InsnNode(Opcodes.IRETURN)), null);
		}

		static Statement returnConstant(int line, int value) {
			return new Statement(line, List.of(new IntInsnNode(Opcodes.SIPUSH, value), new InsnNode(Opcodes.IRETURN)), null);
		}

		/** {@code int doubled = x * 2;} into slot 1. */
		static Statement declareDoubled(int line) {
			return new Statement(line, List.of(new VarInsnNode(Opcodes.ILOAD, 0), new InsnNode(Opcodes.ICONST_2),
					new InsnNode(Opcodes.IMUL), new VarInsnNode(Opcodes.ISTORE, 1)), "doubled");
		}

		/** {@code record(doubled);} */
		static Statement recordDoubled(int line) {
			return new Statement(line, List.of(new VarInsnNode(Opcodes.ILOAD, 1),
					new MethodInsnNode(Opcodes.INVOKESTATIC, "net/forbric/tools/EditSplicerTest", "record", "(I)V", false)), null);
		}
	}

	/** {@code static int compute(int x)} with a line number per statement and a local table for x (and doubled). */
	private static MethodNode method(Statement... statements) {
		MethodNode m = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "compute", "(I)I", null, null);
		LabelNode start = new LabelNode(), end = new LabelNode();
		m.instructions.add(start);
		m.localVariables = new ArrayList<>();
		m.localVariables.add(new LocalVariableNode("x", "I", null, start, end, 0));
		for (Statement s : statements) {
			LabelNode label = new LabelNode();
			m.instructions.add(label);
			m.instructions.add(new LineNumberNode(s.line(), label));
			for (AbstractInsnNode n : s.code()) m.instructions.add(n.clone(Map.of()));
			if (s.declares() != null) {
				LabelNode after = new LabelNode();
				m.instructions.add(after);
				m.localVariables.add(new LocalVariableNode(s.declares(), "I", null, after, end, 1));
			}
		}
		m.instructions.add(end);
		m.maxStack = 4;
		m.maxLocals = 4;
		return m;
	}

	private static ClassNode owner(MethodNode method) {
		ClassNode cn = new ClassNode();
		cn.version = Opcodes.V17;
		cn.access = Opcodes.ACC_PUBLIC;
		cn.name = OWNER;
		cn.superName = "java/lang/Object";
		cn.sourceFile = "EditSplicerProbe.java";
		cn.methods.add(method);
		return cn;
	}

	private static EditSplicer.Result splice(MethodNode base, String basePatch, MethodNode other, String otherPatch,
			boolean otherIsForge) {
		ClassNode owner = owner(base);
		ContractGraph graph = new ContractGraph(List.of(Map.of()));
		Map<String, MethodNode> ownerMethods = new LinkedHashMap<>(), otherMethods = new LinkedHashMap<>();
		ownerMethods.put(base.name + base.desc, base);
		otherMethods.put(other.name + other.desc, other);
		return new EditSplicer(graph).splice(new EditSplicer.Input(owner, base, other, SOURCE,
				PatchLineProvenance.of(Map.of(SOURCE, basePatch)), PatchLineProvenance.of(Map.of(SOURCE, otherPatch)),
				otherIsForge, ownerMethods, otherMethods));
	}

	private static List<Integer> run(MethodNode method, int x) throws Exception {
		ClassNode cn = owner(method);
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
		cn.accept(writer);
		byte[] bytes = writer.toByteArray();
		class Loader extends ClassLoader {
			Loader() {
				super(EditSplicerTest.class.getClassLoader());
			}

			Class<?> define(String name, byte[] code) {
				return defineClass(name.replace('/', '.'), code, 0, code.length);
			}
		}
		CALLS.clear();
		Loader loader = new Loader();
		loader.define(FORGE_HOOKS, hookClass(FORGE_HOOKS, 100, 5));
		loader.define(NEO_HOOKS, hookClass(NEO_HOOKS, 200, 6));
		Object returned = loader.define(OWNER, bytes).getMethod("compute", int.class).invoke(null, x);
		List<Integer> out = new ArrayList<>(CALLS);
		out.add((Integer) returned);
		return out;
	}

	private static final String FORGE_HOOKS = "net/minecraftforge/EditSplicerProbeHooks";
	private static final String NEO_HOOKS = "net/neoforged/EditSplicerProbeHooks";

	/** {@code static boolean allow(int x)}: records {@code mark} and answers {@code x != veto}. */
	private static byte[] hookClass(String name, int mark, int veto) {
		ClassNode cn = new ClassNode();
		cn.version = Opcodes.V17;
		cn.access = Opcodes.ACC_PUBLIC;
		cn.name = name;
		cn.superName = "java/lang/Object";
		MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "allow", "(I)Z", null, null);
		LabelNode no = new LabelNode();
		m.instructions.add(new IntInsnNode(Opcodes.SIPUSH, mark));
		m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/forbric/tools/EditSplicerTest", "record", "(I)V", false));
		m.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
		m.instructions.add(new IntInsnNode(Opcodes.SIPUSH, veto));
		m.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, no));
		m.instructions.add(new InsnNode(Opcodes.ICONST_1));
		m.instructions.add(new InsnNode(Opcodes.IRETURN));
		m.instructions.add(no);
		m.instructions.add(new InsnNode(Opcodes.ICONST_0));
		m.instructions.add(new InsnNode(Opcodes.IRETURN));
		cn.methods.add(m);
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
		cn.accept(writer);
		return writer.toByteArray();
	}

	/**
	 * {@code record(1); if (!HOOK.allow(x)) return 0; record(2); return x;} with the four statements starting at
	 * {@code first}; {@code hook} null means the vanilla condition {@code x != 0}.
	 */
	private static MethodNode guarded(int first, String hook) {
		MethodNode m = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "compute", "(I)I", null, null);
		LabelNode start = new LabelNode(), end = new LabelNode(), pass = new LabelNode();
		m.instructions.add(start);
		line(m, first, new IntInsnNode(Opcodes.SIPUSH, 1),
				new MethodInsnNode(Opcodes.INVOKESTATIC, "net/forbric/tools/EditSplicerTest", "record", "(I)V", false));
		line(m, first + 1, new VarInsnNode(Opcodes.ILOAD, 0), hook == null ? new InsnNode(Opcodes.NOP)
				: new MethodInsnNode(Opcodes.INVOKESTATIC, hook, "allow", "(I)Z", false), new JumpInsnNode(Opcodes.IFNE, pass));
		line(m, first + 2, new InsnNode(Opcodes.ICONST_0), new InsnNode(Opcodes.IRETURN));
		m.instructions.add(pass);
		line(m, first + 3, new IntInsnNode(Opcodes.SIPUSH, 2),
				new MethodInsnNode(Opcodes.INVOKESTATIC, "net/forbric/tools/EditSplicerTest", "record", "(I)V", false));
		line(m, first + 4, new VarInsnNode(Opcodes.ILOAD, 0), new InsnNode(Opcodes.IRETURN));
		m.instructions.add(end);
		m.localVariables = new ArrayList<>(List.of(new LocalVariableNode("x", "I", null, start, end, 0)));
		m.maxStack = 4;
		m.maxLocals = 2;
		return m;
	}

	private static void line(MethodNode m, int line, AbstractInsnNode... code) {
		LabelNode label = new LabelNode();
		m.instructions.add(label);
		m.instructions.add(new LineNumberNode(line, label));
		for (AbstractInsnNode n : code) if (n.getOpcode() != Opcodes.NOP) m.instructions.add(n);
	}

	private static String replaceCondition(String dialect, int first) {
		return "--- a/X.java\n+++ b/X.java\n@@ -" + first + ",5 +" + (dialect.equals("_") ? "_" : String.valueOf(first)) + ",5 @@\n"
				+ " A\n-if (x != 0)\n+if (HOOK.allow(x))\n     return 0;\n B\n R\n";
	}

	// Vanilla: 10 record(1)  11 record(2)  12 return x
	private static final String NO_PATCH = "";

	private static String insertBefore11(String dialect, String added) {
		return "--- a/X.java\n+++ b/X.java\n@@ -10,3 +" + dialect + ",4 @@\n A\n+" + added + "\n B\n R\n";
	}

	// ---- behaviour ---------------------------------------------------------------------------------------------

	@Test
	void bothInsertionsAtOnePointAreKeptWithMinecraftForgeFirst() throws Exception {
		MethodNode neo = method(Statement.call(10, 1), Statement.call(11, 200), Statement.call(12, 2), Statement.returnArg(13));
		MethodNode forge = method(Statement.call(10, 1), Statement.call(11, 100), Statement.call(12, 2), Statement.returnArg(13));
		EditSplicer.Result r = splice(neo, insertBefore11("_", "NEO"), forge, insertBefore11("10", "FORGE"), true);
		assertTrue(r.accepted(), r.reason());
		assertEquals(List.of(1, 100, 200, 2, 7), run(r.method(), 7));
	}

	@Test
	void minecraftForgeStillGoesFirstWhenItIsTheBase() throws Exception {
		MethodNode neo = method(Statement.call(10, 1), Statement.call(11, 200), Statement.call(12, 2), Statement.returnArg(13));
		MethodNode forge = method(Statement.call(10, 1), Statement.call(11, 100), Statement.call(12, 2), Statement.returnArg(13));
		EditSplicer.Result r = splice(forge, insertBefore11("10", "FORGE"), neo, insertBefore11("_", "NEO"), false);
		assertTrue(r.accepted(), r.reason());
		assertEquals(List.of(1, 100, 200, 2, 7), run(r.method(), 7));
	}

	@Test
	void insertionsAtDifferentPointsAreBothKept() throws Exception {
		MethodNode neo = method(Statement.call(10, 1), Statement.call(11, 200), Statement.call(12, 2), Statement.returnArg(13));
		MethodNode forge = method(Statement.call(10, 1), Statement.call(11, 2), Statement.call(12, 100), Statement.returnArg(13));
		String forgePatch = "--- a/X.java\n+++ b/X.java\n@@ -10,3 +10,4 @@\n A\n B\n+FORGE\n R\n";
		EditSplicer.Result r = splice(neo, insertBefore11("_", "NEO"), forge, forgePatch, true);
		assertTrue(r.accepted(), r.reason());
		assertEquals(List.of(1, 200, 2, 100, 7), run(r.method(), 7));
	}

	@Test
	void aLineOnlyTheOtherSideReplacedIsReplaced() throws Exception {
		MethodNode neo = method(Statement.call(10, 1), Statement.call(11, 200), Statement.call(12, 2), Statement.returnArg(13));
		MethodNode forge = method(Statement.call(10, 1), Statement.call(11, 102), Statement.returnArg(12));
		String forgePatch = "--- a/X.java\n+++ b/X.java\n@@ -10,3 +10,3 @@\n A\n-B\n+B2\n R\n";
		EditSplicer.Result r = splice(neo, insertBefore11("_", "NEO"), forge, forgePatch, true);
		assertTrue(r.accepted(), r.reason());
		assertEquals(List.of(1, 200, 102, 7), run(r.method(), 7));
	}

	@Test
	void aLineBothSidesReplacedDifferentlyIsAConflictAndTheBaseStands() {
		MethodNode neo = method(Statement.call(10, 1), Statement.call(11, 202), Statement.returnArg(12));
		MethodNode forge = method(Statement.call(10, 1), Statement.call(11, 102), Statement.returnArg(12));
		String patch = "--- a/X.java\n+++ b/X.java\n@@ -10,3 +10,3 @@\n A\n-B\n+B2\n R\n";
		EditSplicer.Result r = splice(neo, patch.replace("+10,3", "+_,3"), forge, patch, true);
		assertFalse(r.accepted());
		assertTrue(r.conflicts().stream().anyMatch(c -> c.kind().equals("both-changed")), r.conflicts().toString());
	}

	@Test
	void anIdenticalInsertionIsNotDuplicated() {
		MethodNode neo = method(Statement.call(10, 1), Statement.call(11, 300), Statement.call(12, 2), Statement.returnArg(13));
		MethodNode forge = method(Statement.call(10, 1), Statement.call(11, 300), Statement.call(12, 2), Statement.returnArg(13));
		EditSplicer.Result r = splice(neo, insertBefore11("_", "SAME"), forge, insertBefore11("10", "SAME"), true);
		assertFalse(r.accepted());
		assertTrue(r.conflicts().stream().anyMatch(c -> c.kind().equals("same-edit")), r.conflicts().toString());
	}

	@Test
	void aLocalTheRunDeclaresGetsAFreshSlot() throws Exception {
		MethodNode neo = method(Statement.call(10, 1), Statement.call(11, 2), Statement.returnArg(12));
		MethodNode forge = method(Statement.call(10, 1), Statement.declareDoubled(11), Statement.recordDoubled(12),
				Statement.call(13, 2), Statement.returnArg(14));
		String forgePatch = "--- a/X.java\n+++ b/X.java\n@@ -10,3 +10,5 @@\n A\n+int doubled = x * 2;\n+record(doubled);\n B\n R\n";
		EditSplicer.Result r = splice(neo, NO_PATCH, forge, forgePatch, true);
		assertTrue(r.accepted(), r.reason());
		assertEquals(List.of(1, 14, 2, 7), run(r.method(), 7));
	}

	@Test
	void aRunThatWouldCutOffTheBaseIsRefused() {
		MethodNode neo = method(Statement.call(10, 1), Statement.call(11, 200), Statement.call(12, 2), Statement.returnArg(13));
		MethodNode forge = method(Statement.call(10, 1), Statement.returnConstant(11, -1), Statement.call(12, 2), Statement.returnArg(13));
		EditSplicer.Result r = splice(neo, insertBefore11("_", "NEO"), forge, insertBefore11("10", "return -1;"), true);
		assertFalse(r.accepted(), "an unconditional early return would make the base's own code unreachable");
	}

	/** Negative control: a patch that names the wrong vanilla line as replaced must not remove the base's copy of another line. */
	@Test
	void aReplacementWhosePatchNamesOtherLinesIsRefused() {
		MethodNode neo = method(Statement.call(10, 1), Statement.call(11, 2), Statement.returnArg(12));
		MethodNode forge = method(Statement.call(10, 1), Statement.call(11, 102), Statement.returnArg(12));
		// Claims vanilla line 10 was replaced, while the edit in the body sits where line 11 was.
		String lying = "--- a/X.java\n+++ b/X.java\n@@ -9,4 +9,4 @@\n Z\n-A\n+A2\n B\n R\n";
		EditSplicer.Result r = splice(neo, NO_PATCH, forge, lying, true);
		assertFalse(r.accepted() && r.spliced().stream().anyMatch(s -> s.kind().equals("replace")),
				"a replacement keyed to the wrong vanilla line was carried over: " + r);
	}

	@Test
	void nothingIsSplicedWhenTheOtherSideAddedNothing() {
		MethodNode neo = method(Statement.call(10, 1), Statement.call(11, 200), Statement.call(12, 2), Statement.returnArg(13));
		MethodNode forge = method(Statement.call(10, 1), Statement.call(11, 2), Statement.returnArg(12));
		EditSplicer.Result r = splice(neo, insertBefore11("_", "NEO"), forge, NO_PATCH, true);
		assertFalse(r.accepted());
	}

	/**
	 * Two rewrites of one vanilla condition that differ only in their hook are NOT chained: each hook computes the
	 * vanilla default itself and may override it either way, so AND and OR each drop one family's override.
	 */
	@Test
	void conditionRewritesThatDifferOnlyInTheirHookStayAConflict() {
		// The MinecraftForge decompile numbers this file three lines further down than NeoForm's: the conflict must
		// still be recognised as the same vanilla line.
		MethodNode neo = guarded(10, NEO_HOOKS), forge = guarded(13, FORGE_HOOKS);
		EditSplicer.Result r = splice(neo, replaceCondition("_", 10), forge, replaceCondition("13", 13), true);
		assertFalse(r.accepted());
		assertTrue(r.conflicts().stream().anyMatch(c -> c.kind().equals("both-changed") && c.detail().contains("EditSplicerProbeHooks.allow")),
				r.conflicts().toString());
	}

	@Test
	void aRunNextToAConditionTheOtherNumberingShiftsIsStillPlaced() throws Exception {
		// NeoForge inserts nothing here; MinecraftForge (numbered three lines down) inserts a probe before record(2).
		MethodNode neo = guarded(10, null);
		MethodNode forge = guarded(13, null);
		// MinecraftForge's extra statement sits on its own added line 16, before record(2) (vanilla 16 -> patched 17).
		LabelNode label = new LabelNode();
		AbstractInsnNode record2 = null;
		for (AbstractInsnNode n : forge.instructions) if (n instanceof LineNumberNode ln && ln.line == 16) record2 = ln;
		InsnList probe = new InsnList();
		probe.add(label);
		probe.add(new LineNumberNode(16, label));
		probe.add(new IntInsnNode(Opcodes.SIPUSH, 100));
		probe.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/forbric/tools/EditSplicerTest", "record", "(I)V", false));
		forge.instructions.insertBefore(record2.getPrevious(), probe);
		for (AbstractInsnNode n : forge.instructions) if (n instanceof LineNumberNode ln && ln.line >= 16 && ln.start != label) ln.line++;
		String forgePatch = "--- a/X.java\n+++ b/X.java\n@@ -14,4 +14,5 @@\n if (x != 0)\n     return 0;\n+probe\n B\n R\n";
		EditSplicer.Result r = splice(neo, NO_PATCH, forge, forgePatch, true);
		assertTrue(r.accepted(), r.reason() + " " + r.refused());
		assertEquals(List.of(1, 100, 2, 7), run(r.method(), 7));
		assertEquals(List.of(1, 0), run(r.method(), 0));
	}

	@Test
	void theInputsAreNotMutated() throws Exception {
		MethodNode neo = method(Statement.call(10, 1), Statement.call(11, 200), Statement.call(12, 2), Statement.returnArg(13));
		MethodNode forge = method(Statement.call(10, 1), Statement.call(11, 100), Statement.call(12, 2), Statement.returnArg(13));
		List<Integer> before = run(EditSplicer.copy(neo), 3);
		int forgeSize = forge.instructions.size();
		EditSplicer.Result r = splice(neo, insertBefore11("_", "NEO"), forge, insertBefore11("10", "FORGE"), true);
		assertTrue(r.accepted(), r.reason());
		assertEquals(before, run(EditSplicer.copy(neo), 3));
		assertEquals(forgeSize, forge.instructions.size());
	}
}
