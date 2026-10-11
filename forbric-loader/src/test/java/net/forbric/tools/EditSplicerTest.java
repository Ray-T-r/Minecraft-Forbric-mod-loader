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

			Class<?> define() {
				return defineClass(OWNER.replace('/', '.'), bytes, 0, bytes.length);
			}
		}
		CALLS.clear();
		Object returned = new Loader().define().getMethod("compute", int.class).invoke(null, x);
		List<Integer> out = new ArrayList<>(CALLS);
		out.add((Integer) returned);
		return out;
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
