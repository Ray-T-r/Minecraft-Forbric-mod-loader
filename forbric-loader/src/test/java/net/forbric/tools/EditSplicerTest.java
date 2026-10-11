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
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
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

	/**
	 * The probe class as it runs: the method under test, a helper {@code static void bump(int)} that records its
	 * argument, and a constant {@code static final int LIMIT = 42}. The member-origin tests treat the helper and the
	 * constant as members both patches added.
	 */
	private static ClassNode runnableOwner(MethodNode method) {
		ClassNode cn = owner(method);
		MethodNode bump = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "bump", "(I)V", null, null);
		bump.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
		bump.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/forbric/tools/EditSplicerTest", "record", "(I)V", false));
		bump.instructions.add(new InsnNode(Opcodes.RETURN));
		cn.methods.add(bump);
		cn.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "LIMIT", "I", null, 42));
		cn.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "count", "I", null, null));
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

	/** {@link #splice} with a member oracle for which exactly the given members of the probe are added by both patches. */
	private static EditSplicer.Result splice(MethodNode base, String basePatch, MethodNode other, String otherPatch,
			boolean otherIsForge, String... addedByBoth) {
		ClassNode owner = owner(base);
		ContractGraph graph = new ContractGraph(List.of(Map.of()));
		Map<String, MethodNode> ownerMethods = new LinkedHashMap<>(), otherMethods = new LinkedHashMap<>();
		ownerMethods.put(base.name + base.desc, base);
		otherMethods.put(other.name + other.desc, other);
		List<String> both = List.of(addedByBoth);
		EditSplicer.MemberOrigin members = (o, name, method) -> o.equals(OWNER) && both.contains(name);
		return new EditSplicer(graph).splice(new EditSplicer.Input(owner, base, other, SOURCE,
				PatchLineProvenance.of(Map.of(SOURCE, basePatch)), PatchLineProvenance.of(Map.of(SOURCE, otherPatch)),
				otherIsForge, ownerMethods, otherMethods, ownerMethods, members));
	}

	private static List<Integer> run(MethodNode method, int x) throws Exception {
		return run(method, List.of(), x);
	}

	/** {@link #run} with further methods of the probe (lambdas) beside the method under test. */
	private static List<Integer> run(MethodNode method, List<MethodNode> extra, int x) throws Exception {
		ClassNode cn = runnableOwner(method);
		for (MethodNode m : extra) cn.methods.add(EditSplicer.copy(m));
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
		loader.define(TRACKER, trackerClass());
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
		// static void check(int x): records mark + 1 and throws when x == veto.
		MethodNode check = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "check", "(I)V", null, null);
		LabelNode fine = new LabelNode();
		check.instructions.add(new IntInsnNode(Opcodes.SIPUSH, mark + 1));
		check.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/forbric/tools/EditSplicerTest", "record", "(I)V", false));
		check.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
		check.instructions.add(new IntInsnNode(Opcodes.SIPUSH, veto));
		check.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPNE, fine));
		check.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));
		check.instructions.add(new InsnNode(Opcodes.DUP));
		check.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false));
		check.instructions.add(new InsnNode(Opcodes.ATHROW));
		check.instructions.add(fine);
		check.instructions.add(new InsnNode(Opcodes.RETURN));
		cn.methods.add(check);
		// static int twice(int x): records mark + 2 and answers 2 * x.
		MethodNode twice = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "twice", "(I)I", null, null);
		twice.instructions.add(new IntInsnNode(Opcodes.SIPUSH, mark + 2));
		twice.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/forbric/tools/EditSplicerTest", "record", "(I)V", false));
		twice.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
		twice.instructions.add(new InsnNode(Opcodes.ICONST_2));
		twice.instructions.add(new InsnNode(Opcodes.IMUL));
		twice.instructions.add(new InsnNode(Opcodes.IRETURN));
		cn.methods.add(twice);
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
		cn.accept(writer);
		return writer.toByteArray();
	}

	private static final String TRACKER = "net/minecraftforge/EditSplicerProbeTracker";

	/** An object a static field holds, with a begin/end pair: {@code INSTANCE.begin(int)} records 110, {@code end(int)} 120. */
	private static byte[] trackerClass() {
		ClassNode cn = new ClassNode();
		cn.version = Opcodes.V17;
		cn.access = Opcodes.ACC_PUBLIC;
		cn.name = TRACKER;
		cn.superName = "java/lang/Object";
		cn.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "INSTANCE", "L" + TRACKER + ";", null, null));
		MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
		init.instructions.add(new InsnNode(Opcodes.RETURN));
		cn.methods.add(init);
		MethodNode clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
		clinit.instructions.add(new TypeInsnNode(Opcodes.NEW, TRACKER));
		clinit.instructions.add(new InsnNode(Opcodes.DUP));
		clinit.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, TRACKER, "<init>", "()V", false));
		clinit.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, TRACKER, "INSTANCE", "L" + TRACKER + ";"));
		clinit.instructions.add(new InsnNode(Opcodes.RETURN));
		cn.methods.add(clinit);
		for (String name : List.of("begin", "end")) {
			MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, name, "(I)V", null, null);
			m.instructions.add(new IntInsnNode(Opcodes.SIPUSH, name.equals("begin") ? 110 : 120));
			m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/forbric/tools/EditSplicerTest", "record", "(I)V", false));
			m.instructions.add(new InsnNode(Opcodes.RETURN));
			cn.methods.add(m);
		}
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

	// ---- a body written line by line, for shapes the statement helpers cannot build ----------------------------

	private static final String RECORD_OWNER = "net/forbric/tools/EditSplicerTest";

	private static final class Body {
		final MethodNode m = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "compute", "(I)I", null, null);
		final LabelNode start = new LabelNode(), end = new LabelNode();

		Body() {
			m.instructions.add(start);
			m.localVariables = new ArrayList<>(List.of(new LocalVariableNode("x", "I", null, start, end, 0)));
			m.maxStack = 4;
			m.maxLocals = 6;
		}

		Body line(int line, AbstractInsnNode... code) {
			LabelNode label = new LabelNode();
			m.instructions.add(label);
			m.instructions.add(new LineNumberNode(line, label));
			for (AbstractInsnNode n : code) m.instructions.add(n);
			return this;
		}

		Body label(LabelNode l) {
			m.instructions.add(l);
			return this;
		}

		/** A local named {@code name} in {@code slot} from here to the end of the method. */
		Body local(String name, int slot) {
			LabelNode from = new LabelNode();
			m.instructions.add(from);
			m.localVariables.add(new LocalVariableNode(name, "I", null, from, end, slot));
			return this;
		}

		Body handler(LabelNode s, LabelNode e, LabelNode h, String type) {
			m.tryCatchBlocks.add(new TryCatchBlockNode(s, e, h, type));
			return this;
		}

		MethodNode done() {
			m.instructions.add(end);
			return m;
		}
	}

	private static AbstractInsnNode[] rec(int value) {
		return new AbstractInsnNode[] { new IntInsnNode(Opcodes.SIPUSH, value),
				new MethodInsnNode(Opcodes.INVOKESTATIC, RECORD_OWNER, "record", "(I)V", false) };
	}

	private static AbstractInsnNode[] recLocal(int slot) {
		return new AbstractInsnNode[] { new VarInsnNode(Opcodes.ILOAD, slot),
				new MethodInsnNode(Opcodes.INVOKESTATIC, RECORD_OWNER, "record", "(I)V", false) };
	}

	/** {@code HOOKS.check(local)}. */
	private static AbstractInsnNode[] check(String hooks, int slot) {
		return new AbstractInsnNode[] { new VarInsnNode(Opcodes.ILOAD, slot),
				new MethodInsnNode(Opcodes.INVOKESTATIC, hooks, "check", "(I)V", false) };
	}

	/** {@code bump(value)}: the probe's helper that both patches added. */
	private static AbstractInsnNode[] bump(int value) {
		return new AbstractInsnNode[] { new IntInsnNode(Opcodes.SIPUSH, value),
				new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "bump", "(I)V", false) };
	}

	private static AbstractInsnNode[] returnX() {
		return new AbstractInsnNode[] { new VarInsnNode(Opcodes.ILOAD, 0), new InsnNode(Opcodes.IRETURN) };
	}

	private static String patch(String dialect, int from, int count, String... body) {
		int added = 0;
		for (String b : body) if (b.startsWith("+")) added++;
		int removed = 0;
		for (String b : body) if (b.startsWith("-")) removed++;
		return "--- a/X.java\n+++ b/X.java\n@@ -" + from + "," + count + " +" + (dialect.equals("_") ? "_" : String.valueOf(from)) + ","
				+ (count + added - removed) + " @@\n" + String.join("\n", body) + "\n";
	}

	private static boolean hasOutcome(List<EditSplicer.Outcome> outcomes, String kind, String detail) {
		return outcomes.stream().anyMatch(o -> o.kind().equals(kind) && (detail == null || o.detail().contains(detail)));
	}

	// ---- rule: exception coverage at the real insertion position ----------------------------------------------

	/**
	 * {@code try { record(1); [check] } catch (Throwable t) { record(9); } return x;} with MinecraftForge's
	 * {@code check(x)} after the region ({@code inside} false) or inside it; NeoForge left the method vanilla.
	 */
	private static MethodNode guardedCheck(String hooks, boolean withCheck, boolean inside) {
		Body b = new Body();
		LabelNode tryStart = new LabelNode(), tryEnd = new LabelNode(), handler = new LabelNode(), done = new LabelNode();
		b.label(tryStart).line(10, rec(1));
		int line = 11;
		if (withCheck && inside) b.line(line++, check(hooks, 0));
		b.label(tryEnd);
		if (withCheck && !inside) b.line(line++, check(hooks, 0));
		b.line(withCheck ? 10 : 10, new JumpInsnNode(Opcodes.GOTO, done));
		b.label(handler).line(line++, new InsnNode(Opcodes.POP)).line(line - 1, rec(9));
		b.label(done).line(line, returnX());
		b.handler(tryStart, tryEnd, handler, "java/lang/Throwable");
		return b.done();
	}

	@Test
	void aRunOutsideARegionWhoseEndLabelPrecedesTheAnchorIsInsertedAfterThatLabel() throws Exception {
		// The base's try region ends at a label right in front of the vanilla goto the run is anchored at. Inserting
		// at the start of that label block would put MinecraftForge's check inside the region and let the handler
		// swallow what it throws; in its own body nothing catches it.
		MethodNode neo = guardedCheck(NEO_HOOKS, false, false);
		MethodNode forge = guardedCheck(FORGE_HOOKS, true, false);
		String forgePatch = patch("10", 10, 3, " A", "+C", " B", " R");
		EditSplicer.Result r = splice(neo, NO_PATCH, forge, forgePatch, true);
		assertTrue(r.accepted(), r.reason() + " " + r.refused());
		assertEquals(List.of(1, 101, 7), run(r.method(), 7));
		Exception thrown = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> run(r.method(), 5));
		assertInstanceOf(IllegalStateException.class, thrown.getCause(), "the handler must not catch the carried check");
		assertEquals(List.of(1, 101), CALLS);
	}

	/** Negative control: the same check inside the region in its own body is inserted inside it in the base. */
	@Test
	void aRunInsideTheRegionInItsOwnBodyStaysInsideIt() throws Exception {
		MethodNode neo = guardedCheck(NEO_HOOKS, false, false);
		MethodNode forge = guardedCheck(FORGE_HOOKS, true, true);
		String forgePatch = patch("10", 10, 3, " A", "+C", " B", " R");
		EditSplicer.Result r = splice(neo, NO_PATCH, forge, forgePatch, true);
		assertTrue(r.accepted(), r.reason() + " " + r.refused());
		assertEquals(List.of(1, 101, 7), run(r.method(), 7));
		assertEquals(List.of(1, 101, 9, 5), run(r.method(), 5));
	}

	// ---- rule: both sides reimplementing one feature ----------------------------------------------------------

	/** record(1); [added line 11]; record(2); return x — the added statement given per side. */
	private static MethodNode withAdded(AbstractInsnNode... added) {
		return new Body().line(10, rec(1)).line(11, added).line(12, rec(2)).line(13, returnX()).done();
	}

	private static final String ADDED_AT_11_NEO = patch("_", 10, 3, " A", "+N", " B", " R");
	private static final String ADDED_AT_11_FORGE = patch("10", 10, 3, " A", "+F", " B", " R");

	@Test
	void bothEditsInvokingAMemberBothPatchesAddedAreAConflict() {
		// Both patches added bump() to the class and both edits call it: two implementations of one step, which
		// running both would run twice (the block removed twice, the capture closed twice).
		MethodNode neo = withAdded(bump(200)), forge = withAdded(bump(100));
		EditSplicer.Result r = splice(neo, ADDED_AT_11_NEO, forge, ADDED_AT_11_FORGE, true, "bump");
		assertFalse(r.accepted(), "MinecraftForge's bump must not run beside NeoForge's");
		assertTrue(hasOutcome(r.conflicts(), "both-reimplemented", "bump"), r.conflicts().toString());
	}

	/** Negative control: each side calling only its own platform's hook with vanilla arguments still splices. */
	@Test
	void aHookOnlyPairStillSplices() throws Exception {
		MethodNode neo = withAdded(check(NEO_HOOKS, 0)), forge = withAdded(check(FORGE_HOOKS, 0));
		EditSplicer.Result r = splice(neo, ADDED_AT_11_NEO, forge, ADDED_AT_11_FORGE, true, "bump");
		assertTrue(r.accepted(), r.reason() + " " + r.conflicts() + " " + r.refused());
		assertEquals(List.of(1, 101, 201, 2, 7), run(r.method(), 7));
	}

	@Test
	void bothEditsWritingAFieldBothPatchesAddedAreAConflict() {
		MethodNode neo = withAdded(new IntInsnNode(Opcodes.SIPUSH, 200), new FieldInsnNode(Opcodes.PUTSTATIC, OWNER, "count", "I"));
		MethodNode forge = withAdded(new IntInsnNode(Opcodes.SIPUSH, 100), new FieldInsnNode(Opcodes.PUTSTATIC, OWNER, "count", "I"));
		EditSplicer.Result r = splice(neo, ADDED_AT_11_NEO, forge, ADDED_AT_11_FORGE, true, "count");
		assertFalse(r.accepted());
		assertTrue(hasOutcome(r.conflicts(), "both-reimplemented", "count"), r.conflicts().toString());
	}

	/** Negative control: two edits that only read a constant both patches added are not a reimplementation. */
	@Test
	void bothEditsReadingAConstantBothPatchesAddedStillSplice() throws Exception {
		MethodNode neo = withAdded(new FieldInsnNode(Opcodes.GETSTATIC, OWNER, "LIMIT", "I"), new IntInsnNode(Opcodes.SIPUSH, 200),
				new InsnNode(Opcodes.IADD), new MethodInsnNode(Opcodes.INVOKESTATIC, RECORD_OWNER, "record", "(I)V", false));
		MethodNode forge = withAdded(new FieldInsnNode(Opcodes.GETSTATIC, OWNER, "LIMIT", "I"), new IntInsnNode(Opcodes.SIPUSH, 100),
				new InsnNode(Opcodes.IADD), new MethodInsnNode(Opcodes.INVOKESTATIC, RECORD_OWNER, "record", "(I)V", false));
		EditSplicer.Result r = splice(neo, ADDED_AT_11_NEO, forge, ADDED_AT_11_FORGE, true, "LIMIT");
		assertTrue(r.accepted(), r.reason() + " " + r.conflicts() + " " + r.refused());
		assertEquals(List.of(1, 142, 242, 2, 7), run(r.method(), 7));
	}

	// ---- rule: statement-level carry of insertion-only runs ---------------------------------------------------

	/** record(1); [line 11]; [line 12]; record(2); return x. */
	private static MethodNode withTwoAdded(AbstractInsnNode[] first, AbstractInsnNode[] second) {
		return new Body().line(10, rec(1)).line(11, first).line(12, second).line(13, rec(2)).line(14, returnX()).done();
	}

	private static final String TWO_ADDED_NEO = patch("_", 10, 3, " A", "+N", "+S", " B", " R");
	private static final String TWO_ADDED_FORGE = patch("10", 10, 3, " A", "+F", "+S", " B", " R");

	@Test
	void aStatementTheBaseAlreadyInsertedIsKeptOnceWhileTheRestOfTheRunIsCarried() throws Exception {
		// Both sides moved the same statement (record(300)) in front of record(2) and put their own hook before it:
		// the hook is carried, the shared statement is the same edit and runs once.
		MethodNode neo = withTwoAdded(check(NEO_HOOKS, 0), rec(300));
		MethodNode forge = withTwoAdded(check(FORGE_HOOKS, 0), rec(300));
		EditSplicer.Result r = splice(neo, TWO_ADDED_NEO, forge, TWO_ADDED_FORGE, true);
		assertTrue(r.accepted(), r.reason() + " " + r.refused());
		assertTrue(hasOutcome(r.conflicts(), "same-edit", null), r.conflicts().toString());
		assertEquals(List.of(1, 101, 201, 300, 2, 7), run(r.method(), 7));
	}

	/** Negative control: when the second statements differ, the whole run is carried, statement by statement. */
	@Test
	void aRunWhoseStatementsTheBaseDoesNotHaveIsCarriedWhole() throws Exception {
		MethodNode neo = withTwoAdded(check(NEO_HOOKS, 0), rec(300));
		MethodNode forge = withTwoAdded(check(FORGE_HOOKS, 0), rec(301));
		EditSplicer.Result r = splice(neo, TWO_ADDED_NEO, forge, TWO_ADDED_FORGE, true);
		assertTrue(r.accepted(), r.reason() + " " + r.refused());
		assertEquals(List.of(1, 101, 301, 201, 300, 2, 7), run(r.method(), 7));
	}

	@Test
	void aStatementAfterOneThatWasNotCarriedIsNotCarriedEither() {
		// The first statement reads a local with no name, so it has no place; the check runs after it in its own
		// body and is not carried without it.
		MethodNode neo = withTwoAdded(check(NEO_HOOKS, 0), rec(300));
		MethodNode forge = withTwoAdded(new AbstractInsnNode[] { new VarInsnNode(Opcodes.ILOAD, 4), new InsnNode(Opcodes.POP) },
				check(FORGE_HOOKS, 0));
		EditSplicer.Result r = splice(neo, TWO_ADDED_NEO, forge, TWO_ADDED_FORGE, true);
		assertFalse(r.accepted(), r.spliced().toString());
		assertTrue(hasOutcome(r.refused(), "insert", "local with no name"), r.refused().toString());
		assertTrue(hasOutcome(r.refused(), "insert", "statement before it"), r.refused().toString());
	}

	/** Negative control: with a first statement that is carried, the one after it is carried too. */
	@Test
	void aStatementAfterACarriedOneIsCarried() throws Exception {
		MethodNode neo = withTwoAdded(check(NEO_HOOKS, 0), rec(300));
		MethodNode forge = withTwoAdded(rec(100), check(FORGE_HOOKS, 0));
		EditSplicer.Result r = splice(neo, TWO_ADDED_NEO, forge, TWO_ADDED_FORGE, true);
		assertTrue(r.accepted(), r.reason() + " " + r.conflicts() + " " + r.refused());
		assertEquals(List.of(1, 100, 101, 201, 300, 2, 7), run(r.method(), 7));
	}

	// ---- rule: paired withholding -----------------------------------------------------------------------------

	/**
	 * record(1); doubled = x * 2; [A] record(2); [B] record(3); record(4); [C] return x. MinecraftForge adds
	 * {@code a} before record(2), {@code b} before record(3) and {@code c} before the return (null leaves a place
	 * empty).
	 */
	private static MethodNode threePlaces(AbstractInsnNode[] a, AbstractInsnNode[] b, AbstractInsnNode[] c) {
		Body body = new Body().line(10, rec(1)).line(11, new VarInsnNode(Opcodes.ILOAD, 0), new InsnNode(Opcodes.ICONST_2),
				new InsnNode(Opcodes.IMUL), new VarInsnNode(Opcodes.ISTORE, 1)).local("doubled", 1);
		int line = 12;
		if (a != null) body.line(line++, a);
		body.line(line++, rec(2));
		if (b != null) body.line(line++, b);
		body.line(line++, rec(3));
		body.line(line++, rec(4));
		if (c != null) body.line(line++, c);
		body.line(line, returnX());
		return body.done();
	}

	/** NeoForge rewrote record(2) and record(3) (vanilla lines 12 and 13) into record(22), record(33). */
	private static MethodNode rewrittenBase() {
		return new Body().line(10, rec(1)).line(11, new VarInsnNode(Opcodes.ILOAD, 0), new InsnNode(Opcodes.ICONST_2),
				new InsnNode(Opcodes.IMUL), new VarInsnNode(Opcodes.ISTORE, 1)).local("doubled", 1)
				.line(12, rec(22)).line(13, rec(33)).line(14, rec(4)).line(15, returnX()).done();
	}

	private static final String REWRITTEN = patch("_", 10, 6, " A", " D", "-B", "-C", "+B2", "+C2", " F", " R");

	private static MethodNode vanillaBase() {
		return threePlaces(null, null, null);
	}

	@Test
	void aCopyOfCodeWhoseTwinWasNotCarriedIsWithheld() {
		// javac copies a finally block into every exit; carrying only the copies that could be placed runs the hook
		// on some paths and not others. Here the copy before record(3) has no place (NeoForge rewrote both lines
		// around it), so the identical copy before record(2) is withheld too.
		MethodNode forge = threePlaces(check(FORGE_HOOKS, 1), check(FORGE_HOOKS, 1), null);
		String forgePatch = patch("10", 10, 6, " A", " D", "+K", " B", "+K", " C", " F", " R");
		EditSplicer.Result r = splice(rewrittenBase(), REWRITTEN, forge, forgePatch, true);
		assertFalse(r.accepted(), "one copy alone must not be carried: " + r.spliced());
		assertTrue(hasOutcome(r.refused(), "withheld", "copy of the same code"), r.refused().toString());
	}

	/** Negative control: where both copies have a place, both are carried. */
	@Test
	void bothCopiesAreCarriedWhenBothHaveAPlace() throws Exception {
		MethodNode forge = threePlaces(check(FORGE_HOOKS, 1), check(FORGE_HOOKS, 1), null);
		String forgePatch = patch("10", 10, 6, " A", " D", "+K", " B", "+K", " C", " F", " R");
		EditSplicer.Result r = splice(vanillaBase(), NO_PATCH, forge, forgePatch, true);
		assertTrue(r.accepted(), r.reason() + " " + r.refused());
		assertEquals(List.of(1, 101, 2, 101, 3, 4, 7), run(r.method(), 7));
	}

	private static AbstractInsnNode[] tracker(String method, int slot) {
		return new AbstractInsnNode[] { new FieldInsnNode(Opcodes.GETSTATIC, TRACKER, "INSTANCE", "L" + TRACKER + ";"),
				new VarInsnNode(Opcodes.ILOAD, slot), new MethodInsnNode(Opcodes.INVOKEVIRTUAL, TRACKER, method, "(I)V", false) };
	}

	@Test
	void theEndOfABeginEndPairOnOneStaticObjectIsWithheldWhenItsBeginWasNotCarried() {
		// begin() has no place in the base; end() alone would end what was never begun.
		MethodNode forge = threePlaces(null, tracker("begin", 1), tracker("end", 0));
		String forgePatch = patch("10", 10, 6, " A", " D", " B", "+K", " C", " F", "+E", " R");
		EditSplicer.Result r = splice(rewrittenBase(), REWRITTEN, forge, forgePatch, true);
		assertFalse(r.accepted(), "end() must not be carried without begin(): " + r.spliced());
		assertTrue(hasOutcome(r.refused(), "withheld", "EditSplicerProbeTracker"), r.refused().toString());
	}

	/** Negative control: when begin() has its place, the pair is carried. */
	@Test
	void aBeginEndPairIsCarriedWhenBothHalvesHaveAPlace() throws Exception {
		MethodNode forge = threePlaces(null, tracker("begin", 1), tracker("end", 0));
		String forgePatch = patch("10", 10, 6, " A", " D", " B", "+K", " C", " F", "+E", " R");
		EditSplicer.Result r = splice(vanillaBase(), NO_PATCH, forge, forgePatch, true);
		assertTrue(r.accepted(), r.reason() + " " + r.refused());
		assertEquals(List.of(1, 2, 110, 3, 4, 120, 7), run(r.method(), 7));
	}

	/** record(1); r = FORGE.twice(x); [second]; record(2); return x — r in slot 2. */
	private static MethodNode resultAndUse(AbstractInsnNode... second) {
		return new Body().line(10, rec(1))
				.line(11, new VarInsnNode(Opcodes.ILOAD, 0), new MethodInsnNode(Opcodes.INVOKESTATIC, FORGE_HOOKS, "twice", "(I)I", false),
						new VarInsnNode(Opcodes.ISTORE, 2)).local("r", 2)
				.line(12, second).line(13, rec(2)).line(14, returnX()).done();
	}

	@Test
	void aStatementWhoseResultALaterStatementOfItsRunReadsIsWithheldWithIt() {
		// The second statement reads a local with no name, so it has no place; the first would fire the hook and
		// drop its answer.
		MethodNode forge = resultAndUse(new VarInsnNode(Opcodes.ILOAD, 2), new VarInsnNode(Opcodes.ILOAD, 4), new InsnNode(Opcodes.IADD),
				new MethodInsnNode(Opcodes.INVOKESTATIC, RECORD_OWNER, "record", "(I)V", false));
		EditSplicer.Result r = splice(method(Statement.call(10, 1), Statement.call(11, 2), Statement.returnArg(12)), NO_PATCH,
				forge, patch("10", 10, 3, " A", "+T", "+U", " B", " R"), true);
		assertFalse(r.accepted(), r.spliced().toString());
		assertTrue(hasOutcome(r.refused(), "withheld", "reads what it computes"), r.refused().toString());
	}

	/** Negative control: when the reader has its place, both statements are carried. */
	@Test
	void aStatementAndTheOneReadingItsResultAreCarriedTogether() throws Exception {
		MethodNode forge = resultAndUse(recLocal(2));
		EditSplicer.Result r = splice(method(Statement.call(10, 1), Statement.call(11, 2), Statement.returnArg(12)), NO_PATCH,
				forge, patch("10", 10, 3, " A", "+T", "+U", " B", " R"), true);
		assertTrue(r.accepted(), r.reason() + " " + r.refused());
		assertEquals(List.of(1, 102, 14, 2, 7), run(r.method(), 7));
	}

	// ---- rule: dead output -------------------------------------------------------------------------------------

	/** record(1); [added: base's line 11]; record(x); return 0. */
	private static MethodNode recordsX(AbstractInsnNode... added) {
		return new Body().line(10, rec(1)).line(11, added).line(12, recLocal(0))
				.line(13, new InsnNode(Opcodes.ICONST_0), new InsnNode(Opcodes.IRETURN)).done();
	}

	private static AbstractInsnNode[] doubleX() {
		return new AbstractInsnNode[] { new VarInsnNode(Opcodes.ILOAD, 0),
				new MethodInsnNode(Opcodes.INVOKESTATIC, FORGE_HOOKS, "twice", "(I)I", false), new VarInsnNode(Opcodes.ISTORE, 0) };
	}

	@Test
	void aHookWhoseResultTheBaseOverwritesBeforeReadingIsRefused() {
		// MinecraftForge's hook adjusts x, which its own body then records; NeoForge assigns x itself at the same
		// point, so the hook's answer would reach nothing.
		MethodNode neo = recordsX(new IntInsnNode(Opcodes.BIPUSH, 50), new VarInsnNode(Opcodes.ISTORE, 0));
		MethodNode forge = recordsX(doubleX());
		EditSplicer.Result r = splice(neo, ADDED_AT_11_NEO, forge, ADDED_AT_11_FORGE, true);
		assertFalse(r.accepted(), r.spliced().toString());
		assertTrue(hasOutcome(r.refused(), "insert", "reaches nothing in the base"), r.refused().toString());
	}

	/** Negative control: when the base reads x after the hook, the hook is carried and its answer used. */
	@Test
	void aHookWhoseResultTheBaseReadsIsCarried() throws Exception {
		MethodNode neo = recordsX(check(NEO_HOOKS, 0));
		MethodNode forge = recordsX(doubleX());
		EditSplicer.Result r = splice(neo, ADDED_AT_11_NEO, forge, ADDED_AT_11_FORGE, true);
		assertTrue(r.accepted(), r.reason() + " " + r.refused());
		assertEquals(List.of(1, 102, 201, 14, 0), run(r.method(), 7));
	}

	// ---- rule: reach-back --------------------------------------------------------------------------------------

	/**
	 * {@code if (x == 0) record(1); else { record(2); [inElse] } [atJoin] return x;} where the then-branch jumps to
	 * the join, past whatever the else-branch added.
	 */
	private static MethodNode branches(AbstractInsnNode[] inElse, AbstractInsnNode[] atJoin) {
		LabelNode otherwise = new LabelNode(), join = new LabelNode();
		Body b = new Body().line(10, new VarInsnNode(Opcodes.ILOAD, 0), new JumpInsnNode(Opcodes.IFNE, otherwise))
				.line(11, rec(1)).line(11, new JumpInsnNode(Opcodes.GOTO, join))
				.label(otherwise).line(12, rec(2));
		int line = 13;
		if (inElse != null) b.line(line++, inElse);
		b.label(join);
		if (atJoin != null) b.line(line++, atJoin);
		b.line(line, returnX());
		return b.done();
	}

	@Test
	void aRunTheBaseWouldReachFromABranchThatSkippedItIsRefused() {
		// NeoForge's record(200) sits in the else-branch; the then-branch jumps past it. The kept MinecraftForge body
		// has its own code at the join, which both branches reach, and the run would go after it.
		MethodNode forge = branches(null, rec(100));
		MethodNode neo = branches(rec(200), null);
		String patch = "@@ -10,4 +%s,5 @@\n C\n T\n E\n+X\n R\n";
		EditSplicer.Result r = splice(forge, "--- a/X.java\n+++ b/X.java\n" + patch.formatted("10"), neo,
				"--- a/X.java\n+++ b/X.java\n" + patch.formatted("_"), false);
		assertFalse(r.accepted(), r.spliced().toString());
		assertTrue(hasOutcome(r.refused(), "insert", "could not reach it"), r.refused().toString());
	}

	/** Negative control: with nothing of the base's at the join, the run is placed where only the else-branch runs it. */
	@Test
	void aRunInABranchIsCarriedWhereOnlyThatBranchReachesIt() throws Exception {
		MethodNode forge = branches(null, null);
		MethodNode neo = branches(rec(200), null);
		EditSplicer.Result r = splice(forge, NO_PATCH, neo, "--- a/X.java\n+++ b/X.java\n@@ -10,4 +_,5 @@\n C\n T\n E\n+X\n R\n", false);
		assertTrue(r.accepted(), r.reason() + " " + r.refused());
		assertEquals(List.of(1, 0), run(r.method(), 0));
		assertEquals(List.of(2, 200, 7), run(r.method(), 7));
	}

	// ---- rule: a carried run's lambdas against the base side's own ---------------------------------------------

	private static final Handle METAFACTORY = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
			"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
					+ "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);

	/** {@code ((Runnable) OWNER::lambda).run()} for a static lambda of the probe. */
	private static AbstractInsnNode[] runLambda(String lambda) {
		return new AbstractInsnNode[] { new InvokeDynamicInsnNode("run", "()Ljava/lang/Runnable;", METAFACTORY, Type.getType("()V"),
				new Handle(Opcodes.H_INVOKESTATIC, OWNER, lambda, "()V", false), Type.getType("()V")),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true) };
	}

	/** {@code private static synthetic void name()} with the given body. */
	private static MethodNode lambda(String name, AbstractInsnNode... body) {
		MethodNode m = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, name, "()V", null, null);
		for (AbstractInsnNode n : body) m.instructions.add(n);
		m.instructions.add(new InsnNode(Opcodes.RETURN));
		m.maxStack = 2;
		return m;
	}

	private static Map<String, MethodNode> methods(MethodNode... ms) {
		Map<String, MethodNode> out = new LinkedHashMap<>();
		for (MethodNode m : ms) out.put(m.name + m.desc, m);
		return out;
	}

	/** A splice where the merged class, the other side's class and the base side's class are given apart. */
	private static EditSplicer.Result splice(MethodNode base, String basePatch, MethodNode other, String otherPatch,
			boolean otherIsForge, Map<String, MethodNode> merged, Map<String, MethodNode> otherSide, Map<String, MethodNode> baseSide) {
		return new EditSplicer(new ContractGraph(List.of(Map.of()))).splice(new EditSplicer.Input(owner(base), base, other, SOURCE,
				PatchLineProvenance.of(Map.of(SOURCE, basePatch)), PatchLineProvenance.of(Map.of(SOURCE, otherPatch)),
				otherIsForge, merged, otherSide, baseSide, EditSplicer.MemberOrigin.NONE));
	}

	private static String captured(MethodNode m, int nth) {
		int i = 0;
		for (AbstractInsnNode n : m.instructions) {
			if (n instanceof InvokeDynamicInsnNode d && i++ == nth) return ((Handle) d.bsmArgs[1]).getName();
		}
		return null;
	}

	@Test
	void aCarriedRunsLambdaIsComparedWithTheBaseSidesLambdaNotWithTheMergedClass() throws Exception {
		// MinecraftForge's added button captures lambda$compute$0, its own new lambda; vanilla's lambda is its
		// lambda$compute$1. NeoForge's lambda$compute$0 is vanilla's. The per-method merge already put
		// MinecraftForge's lambda$compute$0 in the merged class (it hooked it, NeoForge did not); comparing with that
		// found it equal and shared the name, so after the merge puts NeoForge's lambda back, MinecraftForge's button
		// ran vanilla's body (PauseScreen's disconnect, Entity.load's NeoForgeData).
		MethodNode neo = new Body().line(10, rec(1)).line(11, check(NEO_HOOKS, 0)).line(12, runLambda("lambda$compute$0")).line(13, returnX()).done();
		MethodNode forge = new Body().line(10, rec(1)).line(11, runLambda("lambda$compute$0")).line(12, runLambda("lambda$compute$1"))
				.line(13, returnX()).done();
		MethodNode neoLambda = lambda("lambda$compute$0", rec(2));
		MethodNode forgeAdded = lambda("lambda$compute$0", rec(100)), forgeVanilla = lambda("lambda$compute$1", rec(2));
		Map<String, MethodNode> merged = methods(neo, forgeAdded, forgeVanilla);
		EditSplicer.Result r = splice(neo, ADDED_AT_11_NEO, forge, ADDED_AT_11_FORGE, true, merged,
				methods(forge, forgeAdded, forgeVanilla), methods(neo, neoLambda));
		assertTrue(r.accepted(), r.reason() + " " + r.refused());
		assertEquals("lambda$compute$0$forbricforge", captured(r.method(), 0));
		assertEquals("lambda$compute$0", captured(r.method(), 1), "NeoForge's own capture keeps its name");
		List<MethodNode> classAfterMerge = new ArrayList<>(List.of(neoLambda));
		classAfterMerge.addAll(r.addedMethods());
		assertEquals(List.of(1, 100, 201, 2, 7), run(r.method(), classAfterMerge, 7));
	}

	/** Negative control: a lambda the base side has under the same name, doing the same thing, keeps that name. */
	@Test
	void aCarriedRunsLambdaTheBaseSideHasTheSameKeepsItsName() throws Exception {
		AbstractInsnNode[] forgeStatement = concat(runLambda("lambda$compute$1"), rec(5));
		MethodNode neo = new Body().line(10, rec(1)).line(11, runLambda("lambda$compute$0")).line(12, check(NEO_HOOKS, 0))
				.line(13, runLambda("lambda$compute$1")).line(14, returnX()).done();
		MethodNode forge = new Body().line(10, rec(1)).line(11, runLambda("lambda$compute$0")).line(12, forgeStatement)
				.line(13, returnX()).done();
		MethodNode vanillaLambda = lambda("lambda$compute$0", rec(2));
		MethodNode neoAdded = lambda("lambda$compute$1", rec(100)), forgeAdded = lambda("lambda$compute$1", rec(100));
		EditSplicer.Result r = splice(neo, patch("_", 10, 3, " A", " B", "+N", "+M", " R"), forge, patch("10", 10, 3, " A", " B", "+F", " R"),
				true, methods(neo, vanillaLambda, neoAdded), methods(forge, vanillaLambda, forgeAdded), methods(neo, vanillaLambda, neoAdded));
		assertTrue(r.accepted(), r.reason() + " " + r.refused());
		assertTrue(r.addedMethods().isEmpty(), "nothing to carry: " + r.addedMethods());
		assertEquals(List.of(1, 2, 100, 5, 201, 100, 7), run(r.method(), List.of(vanillaLambda, neoAdded), 7));
	}

	@Test
	void aLambdaWhoseOwnCapturesDifferOnTheBaseSideIsCarriedWithThem() throws Exception {
		// Both sides' lambda$compute$0 read the same (each runs its lambda$compute$1), but MinecraftForge's
		// lambda$compute$1 is its own hook and NeoForge's is vanilla's: the names inside the two bodies say nothing.
		MethodNode neo = new Body().line(10, rec(1)).line(11, check(NEO_HOOKS, 0)).line(12, runLambda("lambda$compute$2")).line(13, returnX()).done();
		MethodNode forge = new Body().line(10, rec(1)).line(11, runLambda("lambda$compute$0")).line(12, runLambda("lambda$compute$2"))
				.line(13, returnX()).done();
		MethodNode neoOuter = lambda("lambda$compute$0", runLambda("lambda$compute$1")), neoInner = lambda("lambda$compute$1", rec(2));
		MethodNode forgeOuter = lambda("lambda$compute$0", runLambda("lambda$compute$1")), forgeInner = lambda("lambda$compute$1", rec(100));
		MethodNode vanilla = lambda("lambda$compute$2", rec(3));
		Map<String, MethodNode> neoSide = methods(neo, neoOuter, neoInner, vanilla);
		EditSplicer.Result r = splice(neo, ADDED_AT_11_NEO, forge, ADDED_AT_11_FORGE, true, neoSide,
				methods(forge, forgeOuter, forgeInner, vanilla), neoSide);
		assertTrue(r.accepted(), r.reason() + " " + r.refused());
		assertEquals("lambda$compute$0$forbricforge", captured(r.method(), 0));
		List<MethodNode> classAfterMerge = new ArrayList<>(List.of(neoOuter, neoInner, vanilla));
		classAfterMerge.addAll(r.addedMethods());
		assertEquals(List.of(1, 100, 201, 3, 7), run(r.method(), classAfterMerge, 7));
	}

	/** Negative control: when the lambdas the two bodies capture do the same thing too, the name is shared. */
	@Test
	void aLambdaWhoseOwnCapturesMatchTheBaseSideKeepsItsName() throws Exception {
		MethodNode neo = new Body().line(10, rec(1)).line(11, check(NEO_HOOKS, 0)).line(12, runLambda("lambda$compute$2")).line(13, returnX()).done();
		MethodNode forge = new Body().line(10, rec(1)).line(11, runLambda("lambda$compute$0")).line(12, runLambda("lambda$compute$2"))
				.line(13, returnX()).done();
		MethodNode outer = lambda("lambda$compute$0", runLambda("lambda$compute$1")), inner = lambda("lambda$compute$1", rec(100));
		MethodNode vanilla = lambda("lambda$compute$2", rec(3));
		Map<String, MethodNode> side = methods(neo, outer, inner, vanilla);
		EditSplicer.Result r = splice(neo, ADDED_AT_11_NEO, forge, ADDED_AT_11_FORGE, true, side, methods(forge, outer, inner, vanilla), side);
		assertTrue(r.accepted(), r.reason() + " " + r.refused());
		assertEquals("lambda$compute$0", captured(r.method(), 0));
		assertTrue(r.addedMethods().isEmpty(), r.addedMethods().toString());
		assertEquals(List.of(1, 100, 201, 3, 7), run(r.method(), List.of(outer, inner, vanilla), 7));
	}

	@Test
	void aLambdaOnlyTheOtherSideHasKeepsItsNameWhenTheMergedClassHoldsItsBody() throws Exception {
		// NeoForge has no lambda$compute$1; the merged class holds MinecraftForge's (added whole), so its mods still
		// find it under that name.
		MethodNode neo = new Body().line(10, rec(1)).line(11, check(NEO_HOOKS, 0)).line(12, runLambda("lambda$compute$0")).line(13, returnX()).done();
		MethodNode forge = new Body().line(10, rec(1)).line(11, runLambda("lambda$compute$1")).line(12, runLambda("lambda$compute$0"))
				.line(13, returnX()).done();
		MethodNode vanillaLambda = lambda("lambda$compute$0", rec(2)), forgeOnly = lambda("lambda$compute$1", rec(100));
		EditSplicer.Result r = splice(neo, ADDED_AT_11_NEO, forge, ADDED_AT_11_FORGE, true, methods(neo, vanillaLambda, forgeOnly),
				methods(forge, vanillaLambda, forgeOnly), methods(neo, vanillaLambda));
		assertTrue(r.accepted(), r.reason() + " " + r.refused());
		assertEquals("lambda$compute$1", captured(r.method(), 0));
		assertTrue(r.addedMethods().isEmpty(), r.addedMethods().toString());
		assertEquals(List.of(1, 100, 201, 2, 7), run(r.method(), List.of(vanillaLambda, forgeOnly), 7));
	}

	/** Negative control: a lambda only the other side has, capturing one the base side numbers differently, is carried. */
	@Test
	void aLambdaOnlyTheOtherSideHasIsCarriedWhenWhatItCapturesDiffersOnTheBaseSide() throws Exception {
		MethodNode neo = new Body().line(10, rec(1)).line(11, check(NEO_HOOKS, 0)).line(12, runLambda("lambda$compute$0")).line(13, returnX()).done();
		MethodNode forge = new Body().line(10, rec(1)).line(11, runLambda("lambda$compute$1")).line(12, runLambda("lambda$compute$2"))
				.line(13, returnX()).done();
		MethodNode neoLambda = lambda("lambda$compute$0", rec(2));
		MethodNode forgeInner = lambda("lambda$compute$0", rec(100)), forgeOuter = lambda("lambda$compute$1", runLambda("lambda$compute$0"));
		MethodNode forgeVanilla = lambda("lambda$compute$2", rec(2));
		EditSplicer.Result r = splice(neo, ADDED_AT_11_NEO, forge, ADDED_AT_11_FORGE, true, methods(neo, neoLambda, forgeOuter, forgeVanilla),
				methods(forge, forgeInner, forgeOuter, forgeVanilla), methods(neo, neoLambda));
		assertTrue(r.accepted(), r.reason() + " " + r.refused());
		assertEquals("lambda$compute$1$forbricforge", captured(r.method(), 0));
		List<MethodNode> classAfterMerge = new ArrayList<>(List.of(neoLambda, forgeOuter, forgeVanilla));
		classAfterMerge.addAll(r.addedMethods());
		assertEquals(List.of(1, 100, 201, 2, 7), run(r.method(), classAfterMerge, 7));
	}

	private static AbstractInsnNode[] concat(AbstractInsnNode[] a, AbstractInsnNode[] b) {
		AbstractInsnNode[] out = java.util.Arrays.copyOf(a, a.length + b.length);
		System.arraycopy(b, 0, out, a.length, b.length);
		return out;
	}

	// ---- the caller's whole-class view: carried reads, withheld units ------------------------------------------

	@Test
	void aUnitTheCallerWithholdsIsLeftOutAndReported() throws Exception {
		// MinecraftForge adds two statements; the first reads the probe's field count. The caller (which sees the
		// whole merged class) withholds that one by its identity, and the method is spliced again without it.
		AbstractInsnNode[] readsCount = { new FieldInsnNode(Opcodes.GETSTATIC, OWNER, "count", "I"),
				new MethodInsnNode(Opcodes.INVOKESTATIC, RECORD_OWNER, "record", "(I)V", false) };
		MethodNode neo = withTwoAdded(check(NEO_HOOKS, 0), rec(300));
		MethodNode forge = withTwoAdded(rec(100), readsCount);
		EditSplicer.Result first = splice(neo, TWO_ADDED_NEO, forge, TWO_ADDED_FORGE, true);
		assertTrue(first.accepted(), first.reason() + " " + first.refused());
		assertEquals(2, first.carried().size(), first.carried().toString());
		EditSplicer.Carried reader = first.carried().get(1);
		assertEquals(java.util.Set.of("count I"), reader.fieldReads());
		assertEquals(java.util.Set.of(), first.carried().get(0).fieldReads());

		Map<String, MethodNode> ms = methods(neo), os = methods(forge);
		EditSplicer.Result again = new EditSplicer(new ContractGraph(List.of(Map.of()))).splice(new EditSplicer.Input(owner(neo), neo, forge,
				SOURCE, PatchLineProvenance.of(Map.of(SOURCE, TWO_ADDED_NEO)), PatchLineProvenance.of(Map.of(SOURCE, TWO_ADDED_FORGE)),
				true, ms, os, ms, EditSplicer.MemberOrigin.NONE, Map.of(reader.id(), "it reads count, which nothing writes")));
		assertTrue(again.accepted(), again.reason() + " " + again.refused());
		assertTrue(hasOutcome(again.refused(), "withheld", "it reads count"), again.refused().toString());
		assertEquals(List.of(1, 100, 201, 300, 2, 7), run(again.method(), 7));
	}
}
