/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/**
 * What only the whole merged class shows about its edit splices, through the builder on synthetic three-way classes:
 * captured lambdas put back on their capturer's side when the capturer was spliced, a carried read of a field
 * nothing writes withheld (again and again until nothing changes), and the lambda audit. Every merged class is
 * defined and run, and the order of the probe calls is compared with what both patches together mean.
 */
public class EditSpliceClassChecksTest {
	@TempDir Path directory;

	private static final String RECORD = "net/forbric/tools/EditSpliceClassChecksTest";
	private static final String FORGE = "net/minecraftforge/demo/Hooks";
	private static final String NEO = "net/neoforged/demo/Hooks";
	private static final List<Integer> CALLS = new ArrayList<>();

	public static void record(int value) {
		CALLS.add(value);
	}

	@BeforeEach
	void clear() {
		CALLS.clear();
	}

	// ---- building blocks -------------------------------------------------------------------------------------

	private static final Handle METAFACTORY = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
			"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
					+ "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);

	/** A method written line by line; its parameters get local-table entries ("this", then "x"). */
	private static final class M {
		final MethodNode m;
		final LabelNode start = new LabelNode(), end = new LabelNode();
		final String owner;

		M(String owner, int access, String name, String desc) {
			this.owner = owner;
			m = new MethodNode(Opcodes.ASM9, access, name, desc, null, null);
			m.instructions.add(start);
		}

		M line(int line, AbstractInsnNode... code) {
			LabelNode label = new LabelNode();
			m.instructions.add(label);
			m.instructions.add(new LineNumberNode(line, label));
			for (AbstractInsnNode n : code) m.instructions.add(n);
			return this;
		}

		MethodNode done() {
			m.instructions.add(end);
			m.localVariables = new ArrayList<>();
			int slot = 0;
			if ((m.access & Opcodes.ACC_STATIC) == 0) m.localVariables.add(new LocalVariableNode("this", "L" + owner + ";", null, start, end, slot++));
			for (Type t : Type.getArgumentTypes(m.desc)) {
				m.localVariables.add(new LocalVariableNode("x", t.getDescriptor(), null, start, end, slot));
				slot += t.getSize();
			}
			return m;
		}
	}

	private static AbstractInsnNode[] rec(int value) {
		return new AbstractInsnNode[] { new IntInsnNode(Opcodes.SIPUSH, value),
				new MethodInsnNode(Opcodes.INVOKESTATIC, RECORD, "record", "(I)V", false) };
	}

	private static AbstractInsnNode[] mark(String hooks, int value) {
		return new AbstractInsnNode[] { new IntInsnNode(Opcodes.SIPUSH, value),
				new MethodInsnNode(Opcodes.INVOKESTATIC, hooks, "mark", "(I)V", false) };
	}

	private static AbstractInsnNode[] returnX(boolean instance) {
		return new AbstractInsnNode[] { new VarInsnNode(Opcodes.ILOAD, instance ? 1 : 0), new InsnNode(Opcodes.IRETURN) };
	}

	private static AbstractInsnNode[] ret() {
		return new AbstractInsnNode[] { new InsnNode(Opcodes.RETURN) };
	}

	/** {@code ((Runnable) Owner::lambda).run()} for a static lambda. */
	private static AbstractInsnNode[] runStatic(String owner, String lambda) {
		return new AbstractInsnNode[] { new InvokeDynamicInsnNode("run", "()Ljava/lang/Runnable;", METAFACTORY, Type.getType("()V"),
				new Handle(Opcodes.H_INVOKESTATIC, owner, lambda, "()V", false), Type.getType("()V")),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true) };
	}

	/** {@code ((Runnable) this::lambda).run()} for an instance lambda. */
	private static AbstractInsnNode[] runInstance(String owner, String lambda) {
		return new AbstractInsnNode[] { new VarInsnNode(Opcodes.ALOAD, 0),
				new InvokeDynamicInsnNode("run", "(L" + owner + ";)Ljava/lang/Runnable;", METAFACTORY, Type.getType("()V"),
						new Handle(Opcodes.H_INVOKESPECIAL, owner, lambda, "()V", false), Type.getType("()V")),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true) };
	}

	private static MethodNode lambda(String name, boolean isStatic, AbstractInsnNode... body) {
		MethodNode m = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC | (isStatic ? Opcodes.ACC_STATIC : 0), name, "()V", null, null);
		for (AbstractInsnNode n : body) m.instructions.add(n);
		m.instructions.add(new InsnNode(Opcodes.RETURN));
		return m;
	}

	private static MethodNode constructor() {
		MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		m.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
		m.instructions.add(new InsnNode(Opcodes.RETURN));
		return m;
	}

	private static ClassNode type(String name, MethodNode... methods) {
		ClassNode cn = new ClassNode();
		cn.version = Opcodes.V17;
		cn.access = Opcodes.ACC_PUBLIC;
		cn.name = name;
		cn.superName = "java/lang/Object";
		cn.sourceFile = name.substring(name.lastIndexOf('/') + 1) + ".java";
		cn.methods.addAll(List.of(methods));
		return cn;
	}

	private static ClassNode withField(ClassNode cn, String field) {
		cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, field, "Ljava/lang/Integer;", null, null));
		return cn;
	}

	/** {@code mark(int)} records its argument, {@code make(int)} boxes it, {@code show(Integer)} records its value. */
	private static byte[] hooks(String name) {
		ClassNode cn = type(name);
		MethodNode mark = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "mark", "(I)V", null, null);
		mark.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
		mark.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RECORD, "record", "(I)V", false));
		mark.instructions.add(new InsnNode(Opcodes.RETURN));
		MethodNode make = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make", "(I)Ljava/lang/Integer;", null, null);
		make.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
		make.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
		make.instructions.add(new InsnNode(Opcodes.ARETURN));
		MethodNode show = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "show", "(Ljava/lang/Integer;)V", null, null);
		show.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		show.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false));
		show.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RECORD, "record", "(I)V", false));
		show.instructions.add(new InsnNode(Opcodes.RETURN));
		cn.methods.addAll(List.of(mark, make, show));
		return bytes(cn);
	}

	private static byte[] bytes(ClassNode cn) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
		cn.accept(writer);
		return writer.toByteArray();
	}

	private Path jar(String name, Map<String, byte[]> classes) throws Exception {
		Path path = directory.resolve(name);
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
			for (var e : classes.entrySet()) {
				zip.putNextEntry(new ZipEntry(e.getKey() + ".class"));
				zip.write(e.getValue());
				zip.closeEntry();
			}
		}
		return path;
	}

	private record Build(byte[] bytes, String report) {
		/** The merged class, defined beside both hook classes. */
		Class<?> load(String name) {
			class Loader extends ClassLoader {
				Loader() {
					super(EditSpliceClassChecksTest.class.getClassLoader());
				}

				Class<?> define(String internal, byte[] code) {
					return defineClass(internal.replace('/', '.'), code, 0, code.length);
				}
			}
			Loader loader = new Loader();
			loader.define(FORGE, hooks(FORGE));
			loader.define(NEO, hooks(NEO));
			return loader.define(name, bytes);
		}

		int occurrences(String text) {
			int n = 0;
			for (int at = report.indexOf(text); at >= 0; at = report.indexOf(text, at + 1)) n++;
			return n;
		}
	}

	/** Merges one class with edit splicing on: each side's patch is the given text for the class's source file. */
	private Build build(ClassNode vanilla, ClassNode forge, ClassNode neo, String forgePatch, String neoPatch) throws Exception {
		String owner = vanilla.name;
		Path v = jar("vanilla.jar", Map.of(owner, bytes(vanilla)));
		Path f = jar("forge.jar", Map.of(owner, bytes(forge)));
		Path n = jar("neo.jar", Map.of(owner, bytes(neo)));
		Path forgeRuntime = jar("forge-runtime.jar", Map.of(FORGE, hooks(FORGE)));
		Path neoRuntime = jar("neo-runtime.jar", Map.of(NEO, hooks(NEO)));
		MergedBaseBuilder builder = new MergedBaseBuilder();
		String source = PatchLineProvenance.sourcePath(owner, vanilla.sourceFile);
		builder.forgeProvenance = PatchLineProvenance.of(Map.of(source, forgePatch));
		builder.neoProvenance = PatchLineProvenance.of(Map.of(source, neoPatch));
		Path merged = directory.resolve("merged.jar"), report = directory.resolve("report.txt");
		builder.run(v, f, n, merged, report, forgeRuntime, neoRuntime);
		try (ZipFile zip = new ZipFile(merged.toFile())) {
			return new Build(zip.getInputStream(zip.getEntry(owner + ".class")).readAllBytes(), Files.readString(report));
		}
	}

	/** Calls {@code method(int...)} (every argument an int). */
	private static Object call(Class<?> c, Object target, String method, Object... args) throws Exception {
		Class<?>[] ints = new Class<?>[args.length];
		java.util.Arrays.fill(ints, int.class);
		var m = c.getDeclaredMethod(method, ints);
		m.setAccessible(true);
		return m.invoke(target, args);
	}

	private static String hunk(int from, int to, String added, String... vanilla) {
		StringBuilder sb = new StringBuilder("@@ -" + from + "," + vanilla.length + " +" + to + "," + (vanilla.length + 1) + " @@\n");
		sb.append(' ').append(vanilla[0]).append('\n').append('+').append(added).append('\n');
		for (int i = 1; i < vanilla.length; i++) sb.append(' ').append(vanilla[i]).append('\n');
		return sb.toString();
	}

	private static String patch(String... hunks) {
		return "--- a/X.java\n+++ b/X.java\n" + String.join("", hunks);
	}

	// ---- captured lambdas of a spliced method ------------------------------------------------------------------

	private static final String MENU = "net/minecraft/demo/Menu";

	@Test
	void aSplicedMethodsOwnCapturesStayOnTheBaseSideAndTheCarriedRunGetsItsOwnLambda() throws Exception {
		// PauseScreen's shape. MinecraftForge adds a button whose lambda is its lambda$build$0, and vanilla's button
		// lambda becomes its lambda$build$1; NeoForge's lambda$build$0 is vanilla's. The per-method merge puts
		// MinecraftForge's lambda$build$0 under that name (only MinecraftForge hooked it). The spliced build must
		// still run vanilla's lambda for NeoForge's (vanilla) button, and MinecraftForge's for its own.
		MethodNode vanillaBuild = new M(MENU, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "build", "(I)I")
				.line(10, rec(1)).line(11, runStatic(MENU, "lambda$build$0")).line(12, returnX(false)).done();
		MethodNode neoBuild = new M(MENU, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "build", "(I)I")
				.line(10, rec(1)).line(11, mark(NEO, 200)).line(12, runStatic(MENU, "lambda$build$0")).line(13, returnX(false)).done();
		MethodNode forgeBuild = new M(MENU, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "build", "(I)I")
				.line(10, rec(1)).line(11, runStatic(MENU, "lambda$build$0")).line(12, runStatic(MENU, "lambda$build$1"))
				.line(13, returnX(false)).done();
		ClassNode vanilla = type(MENU, vanillaBuild, lambda("lambda$build$0", true, rec(2)));
		ClassNode neo = type(MENU, neoBuild, lambda("lambda$build$0", true, rec(2)));
		ClassNode forge = type(MENU, forgeBuild, lambda("lambda$build$0", true, mark(FORGE, 100)), lambda("lambda$build$1", true, rec(2)));
		Build b = build(vanilla, forge, neo, patch(hunk(10, 10, "F", "A", "B", "R")), patch(hunk(10, 10, "N", "A", "B", "R")));
		assertTrue(b.report.contains(MENU + "#build(I)I SPLICED"), b.report);
		assertEquals(7, call(b.load(MENU), null, "build", 7));
		assertEquals(List.of(1, 100, 200, 2), CALLS, "MinecraftForge's button runs its lambda, NeoForge's runs vanilla's");
	}

	/**
	 * When both sides hooked the lambda itself, its name keeps NeoForge's body and MinecraftForge's lives on under the
	 * fresh name its spliced capturer carried: the report says so instead of calling MinecraftForge's hook lost.
	 */
	@Test
	void aLambdaBothSidesHookedWhoseOtherBodyACapturerCarriedIsNotReportedLost() throws Exception {
		MethodNode vanillaBuild = new M(MENU, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "build", "(I)I")
				.line(10, rec(1)).line(11, runStatic(MENU, "lambda$build$0")).line(12, returnX(false)).done();
		MethodNode neoBuild = new M(MENU, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "build", "(I)I")
				.line(10, rec(1)).line(11, mark(NEO, 200)).line(12, runStatic(MENU, "lambda$build$0")).line(13, returnX(false)).done();
		MethodNode forgeBuild = new M(MENU, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "build", "(I)I")
				.line(10, rec(1)).line(11, runStatic(MENU, "lambda$build$0")).line(12, runStatic(MENU, "lambda$build$1"))
				.line(13, returnX(false)).done();
		ClassNode vanilla = type(MENU, vanillaBuild, lambda("lambda$build$0", true, rec(2)));
		ClassNode neo = type(MENU, neoBuild, lambda("lambda$build$0", true, mark(NEO, 202)));
		ClassNode forge = type(MENU, forgeBuild, lambda("lambda$build$0", true, mark(FORGE, 100)), lambda("lambda$build$1", true, mark(FORGE, 102)));
		Build b = build(vanilla, forge, neo, patch(hunk(10, 10, "F", "A", "B", "R")), patch(hunk(10, 10, "N", "A", "B", "R")));
		assertTrue(b.report.contains(MENU + "#build(I)I SPLICED"), b.report);
		assertTrue(b.report.contains(MENU + "#lambda$build$0()V (forge body carried as lambda$build$0$forbricforge for its spliced capturer)"), b.report);
		assertFalse(b.report.contains(MENU + "#lambda$build$0()V (forge hook lost)"), b.report);
		assertFalse(b.report.contains(MENU + "#lambda$build$0()V (" + MergedBaseBuilder.DISPLACED_LAMBDA + "forge hook lost)"), b.report);
		assertEquals(7, call(b.load(MENU), null, "build", 7));
		assertEquals(List.of(1, 100, 200, 202), CALLS, "MinecraftForge's button runs its own lambda, NeoForge's its own");
	}

	private static final String SPAWN = "net/minecraft/demo/Spawn";

	@Test
	void aLambdaNameTheTwoSidesGiveAStaticAndAnInstanceBodyLinksOnBothSides() throws Exception {
		// PrepareSpawnTask$Ready's shape. MinecraftForge adds an instance lambda between vanilla's two, so its
		// lambda$spawn$1 is an instance method while NeoForge's (vanilla's second) is static. NeoForge's call site
		// keeps its static handle; it must find a static body, and MinecraftForge's its own instance body.
		MethodNode vanillaSpawn = new M(SPAWN, Opcodes.ACC_PUBLIC, "spawn", "(I)I").line(10, rec(1))
				.line(11, runStatic(SPAWN, "lambda$spawn$0")).line(12, runStatic(SPAWN, "lambda$spawn$1")).line(13, returnX(true)).done();
		MethodNode neoSpawn = new M(SPAWN, Opcodes.ACC_PUBLIC, "spawn", "(I)I").line(10, rec(1)).line(11, mark(NEO, 200))
				.line(12, runStatic(SPAWN, "lambda$spawn$0")).line(13, runStatic(SPAWN, "lambda$spawn$1")).line(14, returnX(true)).done();
		MethodNode forgeSpawn = new M(SPAWN, Opcodes.ACC_PUBLIC, "spawn", "(I)I").line(10, rec(1))
				.line(11, runStatic(SPAWN, "lambda$spawn$0")).line(12, runInstance(SPAWN, "lambda$spawn$1"))
				.line(13, runStatic(SPAWN, "lambda$spawn$2")).line(14, returnX(true)).done();
		AbstractInsnNode[] forgeLambda = { new VarInsnNode(Opcodes.ALOAD, 0), new InsnNode(Opcodes.POP), mark(FORGE, 100)[0], mark(FORGE, 100)[1] };
		ClassNode vanilla = type(SPAWN, constructor(), vanillaSpawn, lambda("lambda$spawn$0", true, rec(2)), lambda("lambda$spawn$1", true, rec(3)));
		ClassNode neo = type(SPAWN, constructor(), neoSpawn, lambda("lambda$spawn$0", true, rec(2)), lambda("lambda$spawn$1", true, rec(3)));
		ClassNode forge = type(SPAWN, constructor(), forgeSpawn, lambda("lambda$spawn$0", true, rec(2)),
				lambda("lambda$spawn$1", false, forgeLambda), lambda("lambda$spawn$2", true, rec(3)));
		Build b = build(vanilla, forge, neo, patch(hunk(10, 10, "F", "A", "B", "C", "R").replace("+F\n B\n", " B\n+F\n")),
				patch(hunk(10, 10, "N", "A", "B", "C", "R")));
		assertTrue(b.report.contains(SPAWN + "#spawn(I)I SPLICED"), b.report);
		Class<?> c = b.load(SPAWN);
		assertEquals(7, call(c, c.getConstructor().newInstance(), "spawn", 7));
		assertEquals(List.of(1, 200, 2, 100, 3), CALLS);
	}

	// ---- a lambda that is one lambda on both sides ---------------------------------------------------------------

	private static final String MINE = "net/minecraft/demo/Mine";
	/** The row form of a lambda body the capturer realignment displaced. */
	private static final String DISPLACED = MergedBaseBuilder.DISPLACED_LAMBDA;

	/** A static lambda of MINE written line by line, as javac writes one: every statement on its own line. */
	private static MethodNode lineLambda(String name, Object... linesAndCode) {
		M m = new M(MINE, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, name, "()V");
		for (int i = 0; i < linesAndCode.length; i += 2) m.line((Integer) linesAndCode[i], (AbstractInsnNode[]) linesAndCode[i + 1]);
		return m.done();
	}

	/**
	 * MultiPlayerGameMode.continueDestroyBlock's shape. Both sides hook {@code mine}, so it is spliced. MinecraftForge also
	 * puts its own guard in front of vanilla's statement inside the lambda {@code mine} captures; its lambda keeps that
	 * name, because it is vanilla's lambda captured where vanilla captures it. NeoForge's lambda is vanilla's. The
	 * per-method merge gives the lambda MinecraftForge's body (only MinecraftForge edited it), and the spliced capturer
	 * must keep running it: forcing NeoForge's back on its capturer took MinecraftForge's guard out of the merged game.
	 */
	@Test
	void aLambdaOnlyTheOtherSideEditedKeepsTheEditUnderItsSplicedCapturer() throws Exception {
		int st = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
		ClassNode vanilla = type(MINE, new M(MINE, st, "mine", "(I)I").line(10, rec(1)).line(11, runStatic(MINE, "lambda$mine$0"))
				.line(12, returnX(false)).done(), lineLambda("lambda$mine$0", 20, rec(2), 21, ret()));
		ClassNode neo = type(MINE, new M(MINE, st, "mine", "(I)I").line(10, rec(1)).line(11, mark(NEO, 200))
				.line(12, runStatic(MINE, "lambda$mine$0")).line(13, returnX(false)).done(), lineLambda("lambda$mine$0", 21, rec(2), 22, ret()));
		ClassNode forge = type(MINE, new M(MINE, st, "mine", "(I)I").line(10, rec(1)).line(11, mark(FORGE, 100))
				.line(12, runStatic(MINE, "lambda$mine$0")).line(13, returnX(false)).done(),
				lineLambda("lambda$mine$0", 21, mark(FORGE, 101), 22, rec(2), 23, ret()));
		Build b = build(vanilla, forge, neo, patch(hunk(10, 10, "F", "A", "B", "R"), hunk(19, 20, "G", "H", "L", "E")),
				patch(hunk(10, 10, "N", "A", "B", "R")));
		assertTrue(b.report.contains(MINE + "#mine(I)I SPLICED"), b.report);
		assertFalse(b.report.contains("lambda audit refused"), b.report);
		assertTrue(b.report.contains("lambdas-realigned-to-their-capturer=0 lambdas-kept-as-one-on-both-sides=1"), b.report);
		assertEquals(List.of(MINE + "#lambda$mine$0()V"), keptAsOne(b), b.report);
		assertEquals(7, call(b.load(MINE), null, "mine", 7));
		assertEquals(List.of(1, 100, 200, 101, 2), CALLS, "MinecraftForge's guard inside the lambda still runs");
	}

	/**
	 * The same lambda under a capturer the splice did not take. MinecraftForge edited only the lambda, so its {@code mine}
	 * adds nothing of its own and the splice refuses it; the merge keeps NeoForge's {@code mine}, which captures the
	 * lambda by the name both sides give vanilla's lambda. MinecraftForge's guard inside it stays.
	 */
	@Test
	void aLambdaOnlyTheOtherSideEditedKeepsTheEditUnderTheBasesUnsplicedCapturer() throws Exception {
		int st = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
		ClassNode vanilla = type(MINE, new M(MINE, st, "mine", "(I)I").line(10, rec(1)).line(11, runStatic(MINE, "lambda$mine$0"))
				.line(12, returnX(false)).done(), lineLambda("lambda$mine$0", 20, rec(2), 21, ret()));
		ClassNode neo = type(MINE, new M(MINE, st, "mine", "(I)I").line(10, rec(1)).line(11, mark(NEO, 200))
				.line(12, runStatic(MINE, "lambda$mine$0")).line(13, returnX(false)).done(), lineLambda("lambda$mine$0", 21, rec(2), 22, ret()));
		ClassNode forge = type(MINE, new M(MINE, st, "mine", "(I)I").line(10, rec(1)).line(11, runStatic(MINE, "lambda$mine$0"))
				.line(12, returnX(false)).done(), lineLambda("lambda$mine$0", 20, mark(FORGE, 101), 21, rec(2), 22, ret()));
		Build b = build(vanilla, forge, neo, patch(hunk(19, 19, "G", "H", "L", "E")), patch(hunk(10, 10, "N", "A", "B", "R")));
		assertTrue(b.report.contains(MINE + "#mine(I)I NOT SPLICED"), b.report);
		assertTrue(b.report.contains("lambdas-realigned-to-their-capturer=0 lambdas-kept-as-one-on-both-sides=1"), b.report);
		assertEquals(7, call(b.load(MINE), null, "mine", 7));
		assertEquals(List.of(1, 200, 101, 2), CALLS, "MinecraftForge's guard inside the lambda still runs under NeoForge's capturer");
	}

	/**
	 * Negative control: a name collision whose two lambdas carry the same vanilla code. Vanilla's {@code build} captures
	 * two lambdas that do the same thing. MinecraftForge adds a lambda of its own in front of them, so its numbering
	 * shifts: its {@code lambda$build$1} is vanilla's FIRST lambda with a MinecraftForge guard added, captured at
	 * vanilla's first capture, while NeoForge's {@code lambda$build$1} is vanilla's second. Each lambda's vanilla code is
	 * vanilla's second lambda's code, so only the capture site tells them apart: NeoForge's spliced {@code build} must
	 * run vanilla's second lambda at its second capture, not MinecraftForge's guard.
	 */
	@Test
	void aNameCollisionWhoseLambdasCarryTheSameVanillaCodeIsStillForcedBack() throws Exception {
		int st = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
		ClassNode vanilla = type(MINE, new M(MINE, st, "build", "(I)I").line(10, rec(1)).line(11, runStatic(MINE, "lambda$build$0"))
				.line(12, runStatic(MINE, "lambda$build$1")).line(13, returnX(false)).done(),
				lineLambda("lambda$build$0", 20, rec(2), 21, ret()), lineLambda("lambda$build$1", 30, rec(2), 31, ret()));
		ClassNode neo = type(MINE, new M(MINE, st, "build", "(I)I").line(10, rec(1)).line(11, mark(NEO, 200))
				.line(12, runStatic(MINE, "lambda$build$0")).line(13, runStatic(MINE, "lambda$build$1")).line(14, returnX(false)).done(),
				lineLambda("lambda$build$0", 21, rec(2), 22, ret()), lineLambda("lambda$build$1", 31, rec(2), 32, ret()));
		ClassNode forge = type(MINE, new M(MINE, st, "build", "(I)I").line(10, rec(1)).line(11, runStatic(MINE, "lambda$build$0"))
				.line(12, runStatic(MINE, "lambda$build$1")).line(13, runStatic(MINE, "lambda$build$2")).line(14, returnX(false)).done(),
				lineLambda("lambda$build$0", 43, mark(FORGE, 100), 44, ret()),
				lineLambda("lambda$build$1", 21, mark(FORGE, 101), 22, rec(2), 23, ret()), lineLambda("lambda$build$2", 32, rec(2), 33, ret()));
		// MinecraftForge's patch: the capture of its own lambda after line 10, a guard inside vanilla's first lambda, and
		// its own lambda's two lines past vanilla's end.
		String forgePatch = patch(hunk(10, 10, "F", "A", "B", "C", "R"), hunk(19, 20, "G", "H", "L", "E"),
				"@@ -40,1 +42,3 @@\n Z\n+P\n+Q\n");
		Build b = build(vanilla, forge, neo, forgePatch, patch(hunk(10, 10, "N", "A", "B", "C", "R")));
		assertTrue(b.report.contains(MINE + "#build(I)I SPLICED"), b.report);
		assertTrue(b.report.contains("lambdas-kept-as-one-on-both-sides=0"), b.report);
		assertFalse(keptAsOne(b).contains(MINE + "#lambda$build$1()V"), b.report);
		assertEquals(7, call(b.load(MINE), null, "build", 7));
		assertEquals(List.of(1, 100, 200), CALLS.subList(0, 3), "MinecraftForge's own button, then NeoForge's mark");
		assertEquals(List.of(2), CALLS.subList(CALLS.size() - 1, CALLS.size()), "NeoForge's second capture runs vanilla's second lambda");
		assertNotEquals(101, CALLS.get(CALLS.size() - 2), "MinecraftForge's guard belongs to vanilla's first lambda, not its second");
		// MinecraftForge's guard in vanilla's first lambda either runs at NeoForge's first capture, or its loss is named.
		boolean atItsPlace = CALLS.equals(List.of(1, 100, 200, 101, 2, 2));
		assertTrue(atItsPlace || (CALLS.equals(List.of(1, 100, 200, 2, 2))
				&& b.report.contains(MINE + "#lambda$build$1()V (" + DISPLACED + "forge hook lost)")), CALLS + "\n" + b.report);
		assertFalse(b.report.contains(MINE + "#lambda$build$0()V (" + DISPLACED + "forge hook lost)"),
				"MinecraftForge's own lambda runs under the fresh name its spliced capturer carried: " + b.report);
		assertFalse(b.report.contains(MINE + "#lambda$build$2()V (" + DISPLACED + "forge hook lost)"),
				"MinecraftForge's lambda$build$2 is vanilla's second lambda as vanilla wrote it, so nothing of its is lost: " + b.report);
	}

	/**
	 * Negative control: a collision whose capture the alignment pairs with the wrong vanilla capture. MinecraftForge
	 * deleted vanilla's first capture, so its only lambda, {@code lambda$build$0}, is vanilla's SECOND lambda with a
	 * MinecraftForge guard added. Its capture instruction looks exactly like vanilla's first (both capture a lambda of
	 * {@code build} through the same interface), so matching MinecraftForge's body with vanilla's pairs it with
	 * vanilla's first capture, the same one NeoForge's {@code lambda$build$0} is captured at. That pairing is the
	 * alignment's choice, not a fact, since MinecraftForge's patch took one of the two look-alike captures away; the
	 * lambdas' own code shows it too. NeoForge's first capture must run vanilla's first lambda, and the guard the
	 * displaced body carried is named as lost.
	 */
	@Test
	void aCollisionWhoseCaptureTheAlignmentPairsWithTheWrongVanillaCaptureIsForcedBack() throws Exception {
		int st = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
		ClassNode vanilla = type(MINE, new M(MINE, st, "build", "(I)I").line(10, rec(1)).line(11, runStatic(MINE, "lambda$build$0"))
				.line(12, runStatic(MINE, "lambda$build$1")).line(13, returnX(false)).done(),
				lineLambda("lambda$build$0", 20, rec(2), 21, ret()), lineLambda("lambda$build$1", 30, rec(3), 31, ret()));
		ClassNode neo = type(MINE, new M(MINE, st, "build", "(I)I").line(10, rec(1)).line(11, mark(NEO, 200))
				.line(12, runStatic(MINE, "lambda$build$0")).line(13, runStatic(MINE, "lambda$build$1")).line(14, returnX(false)).done(),
				lineLambda("lambda$build$0", 21, rec(2), 22, ret()), lineLambda("lambda$build$1", 31, rec(3), 32, ret()));
		ClassNode forge = type(MINE, new M(MINE, st, "build", "(I)I").line(10, rec(1)).line(11, runStatic(MINE, "lambda$build$0"))
				.line(12, returnX(false)).done(), lineLambda("lambda$build$0", 29, mark(FORGE, 101), 30, rec(3), 31, ret()));
		// MinecraftForge's patch: vanilla line 11 (the first capture) deleted, a guard added in front of line 30.
		String forgePatch = patch("@@ -10,3 +10,2 @@\n A\n-B\n C\n", "@@ -29,3 +28,4 @@\n H\n+G\n L\n E\n");
		Build b = build(vanilla, forge, neo, forgePatch, patch(hunk(10, 10, "N", "A", "B", "C", "R")));
		assertTrue(b.report.contains("lambdas-kept-as-one-on-both-sides=0"), b.report);
		assertEquals(7, call(b.load(MINE), null, "build", 7));
		assertEquals(List.of(1, 200, 2), CALLS.subList(0, 3), "NeoForge's first capture runs vanilla's first lambda");
		assertTrue(CALLS.equals(List.of(1, 200, 2, 101, 3))
				|| (CALLS.equals(List.of(1, 200, 2, 3)) && b.report.contains(MINE + "#lambda$build$0()V (" + DISPLACED + "forge hook lost)")),
				"MinecraftForge's guard runs in front of vanilla's second lambda, or its loss is named: " + CALLS + "\n" + b.report);
	}

	/**
	 * Negative control for the capture site alone: the same collision with lambdas whose code is the same. Vanilla's
	 * two lambdas do one thing, so MinecraftForge's {@code lambda$build$0} (vanilla's SECOND lambda with its guard,
	 * the first capture removed by its patch) has all of vanilla's first lambda's code too, and only the capture shows
	 * that it is not that lambda: the alignment pairs it with vanilla's first capture by choice, because one of the two
	 * look-alike captures is gone on MinecraftForge's side. Keeping it as one lambda put MinecraftForge's guard in front
	 * of NeoForge's FIRST capture, a button the guard was never on.
	 */
	@Test
	void aCollisionOfLambdasWithTheSameCodeWhoseLookAlikeCaptureWasRemovedIsForcedBack() throws Exception {
		int st = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
		ClassNode vanilla = type(MINE, new M(MINE, st, "build", "(I)I").line(10, rec(1)).line(11, runStatic(MINE, "lambda$build$0"))
				.line(12, runStatic(MINE, "lambda$build$1")).line(13, returnX(false)).done(),
				lineLambda("lambda$build$0", 20, rec(2), 21, ret()), lineLambda("lambda$build$1", 30, rec(2), 31, ret()));
		ClassNode neo = type(MINE, new M(MINE, st, "build", "(I)I").line(10, rec(1)).line(11, mark(NEO, 200))
				.line(12, runStatic(MINE, "lambda$build$0")).line(13, runStatic(MINE, "lambda$build$1")).line(14, returnX(false)).done(),
				lineLambda("lambda$build$0", 21, rec(2), 22, ret()), lineLambda("lambda$build$1", 31, rec(2), 32, ret()));
		ClassNode forge = type(MINE, new M(MINE, st, "build", "(I)I").line(10, rec(1)).line(11, runStatic(MINE, "lambda$build$0"))
				.line(12, returnX(false)).done(), lineLambda("lambda$build$0", 29, mark(FORGE, 101), 30, rec(2), 31, ret()));
		String forgePatch = patch("@@ -10,3 +10,2 @@\n A\n-B\n C\n", "@@ -29,3 +28,4 @@\n H\n+G\n L\n E\n");
		Build b = build(vanilla, forge, neo, forgePatch, patch(hunk(10, 10, "N", "A", "B", "C", "R")));
		assertTrue(b.report.contains("lambdas-kept-as-one-on-both-sides=0"), b.report);
		assertFalse(keptAsOne(b).contains(MINE + "#lambda$build$0()V"), b.report);
		assertEquals(7, call(b.load(MINE), null, "build", 7));
		assertEquals(List.of(1, 200, 2), CALLS.subList(0, 3), "NeoForge's first capture runs vanilla's first lambda, unguarded");
		assertTrue(CALLS.equals(List.of(1, 200, 2, 101, 2))
				|| (CALLS.equals(List.of(1, 200, 2, 2)) && b.report.contains(MINE + "#lambda$build$0()V (" + DISPLACED + "forge hook lost)")),
				"MinecraftForge's guard runs in front of vanilla's second lambda, or its loss is named: " + CALLS + "\n" + b.report);
	}

	/**
	 * The control for that rule: look-alike captures that are all still there on both sides, in order, pair as a fact.
	 * Vanilla captures two lambdas that do one thing; MinecraftForge guards the second and leaves both captures alone,
	 * NeoForge marks {@code build}. MinecraftForge's {@code lambda$build$1} is vanilla's second lambda on both sides, and
	 * its guard runs at NeoForge's second capture.
	 */
	@Test
	void aLambdaOnlyTheOtherSideEditedAmongLookAlikeCapturesKeepsTheEdit() throws Exception {
		int st = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
		ClassNode vanilla = type(MINE, new M(MINE, st, "build", "(I)I").line(10, rec(1)).line(11, runStatic(MINE, "lambda$build$0"))
				.line(12, runStatic(MINE, "lambda$build$1")).line(13, returnX(false)).done(),
				lineLambda("lambda$build$0", 20, rec(2), 21, ret()), lineLambda("lambda$build$1", 30, rec(2), 31, ret()));
		ClassNode neo = type(MINE, new M(MINE, st, "build", "(I)I").line(10, rec(1)).line(11, mark(NEO, 200))
				.line(12, runStatic(MINE, "lambda$build$0")).line(13, runStatic(MINE, "lambda$build$1")).line(14, returnX(false)).done(),
				lineLambda("lambda$build$0", 21, rec(2), 22, ret()), lineLambda("lambda$build$1", 31, rec(2), 32, ret()));
		ClassNode forge = type(MINE, new M(MINE, st, "build", "(I)I").line(10, rec(1)).line(11, runStatic(MINE, "lambda$build$0"))
				.line(12, runStatic(MINE, "lambda$build$1")).line(13, returnX(false)).done(),
				lineLambda("lambda$build$0", 20, rec(2), 21, ret()), lineLambda("lambda$build$1", 30, mark(FORGE, 101), 31, rec(2), 32, ret()));
		Build b = build(vanilla, forge, neo, patch(hunk(29, 29, "G", "H", "L", "E")), patch(hunk(10, 10, "N", "A", "B", "C", "R")));
		assertTrue(b.report.contains("lambdas-realigned-to-their-capturer=0 lambdas-kept-as-one-on-both-sides=1"), b.report);
		assertEquals(List.of(MINE + "#lambda$build$1()V"), keptAsOne(b), b.report);
		assertFalse(b.report.contains(MINE + "#lambda$build$1()V (" + DISPLACED + "forge hook lost)"), b.report);
		assertEquals(7, call(b.load(MINE), null, "build", 7));
		assertEquals(List.of(1, 200, 2, 101, 2), CALLS, "MinecraftForge's guard runs at NeoForge's second capture");
	}

	/**
	 * Negative control for the lambda's own code: one capture, paired as a fact, but MinecraftForge rewrote vanilla's
	 * statement in the lambda instead of adding to it, so its body is not vanilla's code with code added. It is forced
	 * back onto NeoForge's capturer, and the edit it carried is named as lost, not only counted.
	 */
	@Test
	void aLambdaTheOtherSideRewroteIsForcedBackAndItsLossIsNamed() throws Exception {
		int st = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
		ClassNode vanilla = type(MINE, new M(MINE, st, "mine", "(I)I").line(10, rec(1)).line(11, runStatic(MINE, "lambda$mine$0"))
				.line(12, returnX(false)).done(), lineLambda("lambda$mine$0", 20, rec(2), 21, ret()));
		ClassNode neo = type(MINE, new M(MINE, st, "mine", "(I)I").line(10, rec(1)).line(11, mark(NEO, 200))
				.line(12, runStatic(MINE, "lambda$mine$0")).line(13, returnX(false)).done(), lineLambda("lambda$mine$0", 21, rec(2), 22, ret()));
		ClassNode forge = type(MINE, new M(MINE, st, "mine", "(I)I").line(10, rec(1)).line(11, runStatic(MINE, "lambda$mine$0"))
				.line(12, returnX(false)).done(), lineLambda("lambda$mine$0", 20, mark(FORGE, 5), 21, ret()));
		Build b = build(vanilla, forge, neo, patch("@@ -19,3 +19,3 @@\n H\n-L\n+X\n E\n"), patch(hunk(10, 10, "N", "A", "B", "R")));
		assertTrue(b.report.contains("lambdas-realigned-to-their-capturer=1 lambdas-kept-as-one-on-both-sides=0"), b.report);
		assertTrue(b.report.contains(MINE + "#lambda$mine$0()V (" + DISPLACED + "forge hook lost)"), b.report);
		assertEquals(7, call(b.load(MINE), null, "mine", 7));
		assertEquals(List.of(1, 200, 2), CALLS, "NeoForge's capturer runs NeoForge's (vanilla's) lambda");
	}

	/**
	 * A displaced body whose edit still runs is no lost hook. MinecraftForge adds a button whose lambda (code its patch
	 * added) takes {@code lambda$build$0}, so vanilla's lambda becomes its {@code lambda$build$1}; NeoForge marks
	 * {@code build}. MinecraftForge's {@code lambda$build$0} is displaced from NeoForge's capturer, but the spliced
	 * capturer carried it under a fresh name, so its button still runs it and the report must not call it lost.
	 */
	@Test
	void aDisplacedBodyASplicedCapturerCarriedIsNoLostHook() throws Exception {
		int st = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
		ClassNode vanilla = type(MINE, new M(MINE, st, "build", "(I)I").line(10, rec(1)).line(11, runStatic(MINE, "lambda$build$0"))
				.line(12, returnX(false)).done(), lineLambda("lambda$build$0", 20, rec(2), 21, ret()));
		ClassNode neo = type(MINE, new M(MINE, st, "build", "(I)I").line(10, rec(1)).line(11, mark(NEO, 200))
				.line(12, runStatic(MINE, "lambda$build$0")).line(13, returnX(false)).done(), lineLambda("lambda$build$0", 21, rec(2), 22, ret()));
		ClassNode forge = type(MINE, new M(MINE, st, "build", "(I)I").line(10, rec(1)).line(11, runStatic(MINE, "lambda$build$0"))
				.line(12, runStatic(MINE, "lambda$build$1")).line(13, returnX(false)).done(),
				lineLambda("lambda$build$0", 42, mark(FORGE, 100), 43, ret()), lineLambda("lambda$build$1", 21, rec(2), 22, ret()));
		Build b = build(vanilla, forge, neo, patch(hunk(10, 10, "F", "A", "B", "R"), "@@ -40,1 +41,3 @@\n Z\n+P\n+Q\n"),
				patch(hunk(10, 10, "N", "A", "B", "R")));
		assertTrue(b.report.contains(MINE + "#build(I)I SPLICED"), b.report);
		assertTrue(b.report.contains("lambdas-realigned-to-their-capturer=1 lambdas-kept-as-one-on-both-sides=0"), b.report);
		assertFalse(b.report.contains("hook lost"), b.report);
		assertEquals(7, call(b.load(MINE), null, "build", 7));
		assertEquals(List.of(1, 100, 200, 2), CALLS, "MinecraftForge's button runs its lambda, NeoForge's runs vanilla's");
	}

	/** The lambdas the report lists as one vanilla lambda on both sides. */
	private static List<String> keptAsOne(Build b) {
		List<String> out = new ArrayList<>();
		boolean in = false;
		for (String line : b.report.lines().toList()) {
			if (line.startsWith("===")) { in = line.startsWith(MergedBaseBuilder.KEPT_AS_ONE_SECTION); continue; }
			if (in && !line.isBlank()) out.add(line);
		}
		return out;
	}

	// ---- a carried read of a field nothing writes ----------------------------------------------------------------

	private static final String TITLE = "net/minecraft/demo/Title";

	/** {@code notice = FORGE.make(value)}, or with {@code fromUnnamedLocal} the value from a local without a name. */
	private static AbstractInsnNode[] writeNotice(String field, boolean fromUnnamedLocal) {
		return new AbstractInsnNode[] { fromUnnamedLocal ? new VarInsnNode(Opcodes.ILOAD, 4) : new IntInsnNode(Opcodes.SIPUSH, 42),
				new MethodInsnNode(Opcodes.INVOKESTATIC, FORGE, "make", "(I)Ljava/lang/Integer;", false),
				new FieldInsnNode(Opcodes.PUTSTATIC, TITLE, field, "Ljava/lang/Integer;") };
	}

	private static AbstractInsnNode[] showNotice(String owner, String field) {
		return new AbstractInsnNode[] { new FieldInsnNode(Opcodes.GETSTATIC, owner, field, "Ljava/lang/Integer;"),
				new MethodInsnNode(Opcodes.INVOKESTATIC, FORGE, "show", "(Ljava/lang/Integer;)V", false) };
	}

	/**
	 * TitleScreen's shape: MinecraftForge's setup writes its private field notice and its render shows it; NeoForge
	 * adds a mark to each. {@code writerCarried} false makes MinecraftForge's writer read a local without a name, so
	 * its run has no place and setup keeps NeoForge's body.
	 */
	private Build title(boolean writerCarried) throws Exception {
		int st = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
		ClassNode vanilla = type(TITLE,
				new M(TITLE, st, "setup", "()V").line(20, rec(10)).line(21, ret()).done(),
				new M(TITLE, st, "render", "(I)I").line(30, rec(1)).line(31, rec(2)).line(32, returnX(false)).done());
		ClassNode forge = withField(type(TITLE,
				new M(TITLE, st, "setup", "()V").line(20, rec(10)).line(21, writeNotice("notice", !writerCarried)).line(22, ret()).done(),
				new M(TITLE, st, "render", "(I)I").line(31, rec(1)).line(32, showNotice(TITLE, "notice")).line(33, rec(2))
						.line(34, returnX(false)).done()), "notice");
		ClassNode neo = type(TITLE,
				new M(TITLE, st, "setup", "()V").line(20, rec(10)).line(21, mark(NEO, 200)).line(22, ret()).done(),
				new M(TITLE, st, "render", "(I)I").line(31, rec(1)).line(32, mark(NEO, 201)).line(33, rec(2)).line(34, returnX(false)).done());
		String patch = patch(hunk(20, 20, "W", "A", "R"), hunk(30, 31, "S", "A", "B", "R"));
		return build(vanilla, forge, neo, patch, patch);
	}

	@Test
	void aCarriedReadOfAFieldWhoseWriterWasNotCarriedIsWithheld() throws Exception {
		Build b = title(false);
		assertTrue(b.report.contains(TITLE + "#setup()V NOT SPLICED"), b.report);
		assertTrue(b.report.contains("x withheld L32-32 it reads notice, which nothing in the merged class writes"), b.report);
		assertTrue(b.report.contains(TITLE + "#render(I)I (forge hook lost)"), b.report);
		Class<?> c = b.load(TITLE);
		call(c, null, "setup");
		assertEquals(7, call(c, null, "render", 7), "no NullPointerException from showing a notice nothing set");
		assertEquals(List.of(10, 200, 1, 201, 2), CALLS);
	}

	/** Negative control: when MinecraftForge's writer is carried, so is its reader. */
	@Test
	void aCarriedReadOfAFieldACarriedRunWritesIsKept() throws Exception {
		Build b = title(true);
		assertTrue(b.report.contains(TITLE + "#setup()V SPLICED"), b.report);
		assertTrue(b.report.contains(TITLE + "#render(I)I SPLICED"), b.report);
		assertFalse(b.report.contains("which nothing in the merged class writes"), b.report);
		Class<?> c = b.load(TITLE);
		call(c, null, "setup");
		assertEquals(7, call(c, null, "render", 7));
		assertEquals(List.of(10, 200, 1, 42, 201, 2), CALLS);
	}

	@Test
	void withholdingIsRepeatedUntilNoCarriedReadIsLeftWithoutAWriter() throws Exception {
		// MinecraftForge's p computes a from b, and q shows a. b's writer has no place, so p's run is withheld; that
		// takes away a's only writer, so q's run is withheld in the next round. Each method is arbitrated and
		// reported once, by the round that stands.
		int st = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
		AbstractInsnNode[] aFromB = { new FieldInsnNode(Opcodes.GETSTATIC, TITLE, "b", "Ljava/lang/Integer;"),
				new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false),
				new MethodInsnNode(Opcodes.INVOKESTATIC, FORGE, "make", "(I)Ljava/lang/Integer;", false),
				new FieldInsnNode(Opcodes.PUTSTATIC, TITLE, "a", "Ljava/lang/Integer;") };
		ClassNode vanilla = type(TITLE,
				new M(TITLE, st, "setup", "()V").line(20, rec(10)).line(21, ret()).done(),
				new M(TITLE, st, "p", "(I)I").line(30, rec(1)).line(31, rec(2)).line(32, returnX(false)).done(),
				new M(TITLE, st, "q", "(I)I").line(40, rec(3)).line(41, rec(4)).line(42, returnX(false)).done());
		ClassNode forge = withField(withField(type(TITLE,
				new M(TITLE, st, "setup", "()V").line(20, rec(10)).line(21, writeNotice("b", true)).line(22, ret()).done(),
				new M(TITLE, st, "p", "(I)I").line(31, rec(1)).line(32, aFromB).line(33, rec(2)).line(34, returnX(false)).done(),
				new M(TITLE, st, "q", "(I)I").line(42, rec(3)).line(43, showNotice(TITLE, "a")).line(44, rec(4)).line(45, returnX(false)).done()),
				"a"), "b");
		ClassNode neo = type(TITLE,
				new M(TITLE, st, "setup", "()V").line(20, rec(10)).line(21, mark(NEO, 200)).line(22, ret()).done(),
				new M(TITLE, st, "p", "(I)I").line(31, rec(1)).line(32, mark(NEO, 201)).line(33, rec(2)).line(34, returnX(false)).done(),
				new M(TITLE, st, "q", "(I)I").line(42, rec(3)).line(43, mark(NEO, 202)).line(44, rec(4)).line(45, returnX(false)).done());
		String patch = patch(hunk(20, 20, "W", "A", "R"), hunk(30, 31, "S", "A", "B", "R"), hunk(40, 42, "S", "A", "B", "R"));
		Build b = build(vanilla, forge, neo, patch, patch);
		assertTrue(b.report.contains("x withheld L32-32 it reads b, which nothing in the merged class writes"), b.report);
		assertTrue(b.report.contains("x withheld L43-43 it reads a, which nothing in the merged class writes"), b.report);
		assertEquals(1, b.occurrences(TITLE + "#p(I)I (forge hook lost)"), b.report);
		assertEquals(1, b.occurrences(TITLE + "#q(I)I (forge hook lost)"), b.report);
		assertEquals(1, b.occurrences(TITLE + "#p(I)I DECLINED"), b.report);
		Class<?> c = b.load(TITLE);
		call(c, null, "setup");
		assertEquals(7, call(c, null, "p", 7));
		assertEquals(7, call(c, null, "q", 7));
		assertEquals(List.of(10, 200, 1, 201, 2, 3, 202, 4), CALLS);
	}

	// ---- the lambda audit ------------------------------------------------------------------------------------------

	private static final String AUDIT = "net/minecraft/demo/Audit";

	/** A class whose static {@code use()} runs {@code lambda$use$0}, given as {@code target}. */
	private static ClassNode audited(MethodNode target) {
		MethodNode use = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "use", "()V", null, null);
		for (AbstractInsnNode n : runStatic(AUDIT, "lambda$use$0")) use.instructions.add(n);
		use.instructions.add(new InsnNode(Opcodes.RETURN));
		return type(AUDIT, use, target);
	}

	private static Map<String, MethodNode> methods(MethodNode... ms) {
		Map<String, MethodNode> out = new LinkedHashMap<>();
		for (MethodNode m : ms) out.put(m.name + m.desc, m);
		return out;
	}

	@Test
	void theAuditFindsAStaticHandleToAnInstanceLambda() {
		ClassNode merged = audited(lambda("lambda$use$0", false, mark(FORGE, 100)));
		Set<String> findings = MergedBaseBuilder.auditCapturedLambdas(merged, methods(), methods(), Set.of("use()V"), Set.of(), Set.of());
		assertEquals(Set.of("use()V -> lambda$use$0()V: a static handle names an instance method"), findings);
	}

	@Test
	void theAuditFindsBaseSideCodeCapturingTheOtherSidesLambda() {
		MethodNode base = lambda("lambda$use$0", true, rec(2)), other = lambda("lambda$use$0", true, mark(FORGE, 100));
		ClassNode merged = audited(lambda("lambda$use$0", true, mark(FORGE, 100)));
		Set<String> findings = MergedBaseBuilder.auditCapturedLambdas(merged, methods(base), methods(other), Set.of("use()V"), Set.of(), Set.of());
		assertEquals(Set.of("use()V -> lambda$use$0()V: base-side code captures the other side's lambda of that name"), findings);
	}

	/** Negative control: the base's own lambda, or a lambda both sides' code captures (already reported), is no finding. */
	@Test
	void theAuditAcceptsTheBasesLambdaAndLeavesAContestedOneToItsReport() {
		MethodNode base = lambda("lambda$use$0", true, rec(2)), other = lambda("lambda$use$0", true, mark(FORGE, 100));
		assertEquals(Set.of(), MergedBaseBuilder.auditCapturedLambdas(audited(lambda("lambda$use$0", true, rec(2))),
				methods(base), methods(other), Set.of("use()V"), Set.of(), Set.of()));
		assertEquals(Set.of(), MergedBaseBuilder.auditCapturedLambdas(audited(lambda("lambda$use$0", true, mark(FORGE, 100))),
				methods(base), methods(other), Set.of("use()V"), Set.of("lambda$use$0()V"), Set.of()));
	}

	private static final String BOTH = "net/minecraft/demo/Both";

	/**
	 * Negative control for the audit's fallback: a finding the class has without its splices too is not theirs. Here a
	 * method only MinecraftForge has (an overload) captures lambda$build$0 as its instance lambda, while NeoForge's
	 * build captures it as vanilla's static one; the class is broken for that overload whether build is spliced or
	 * not, so build keeps its splice and no hook is reported lost.
	 */
	@Test
	void aFindingTheClassHasWithoutItsSplicesDoesNotCostThem() throws Exception {
		MethodNode vanillaBuild = new M(BOTH, Opcodes.ACC_PUBLIC, "build", "(I)I").line(10, rec(1))
				.line(11, runStatic(BOTH, "lambda$build$0")).line(12, returnX(true)).done();
		MethodNode neoBuild = new M(BOTH, Opcodes.ACC_PUBLIC, "build", "(I)I").line(10, rec(1)).line(11, mark(NEO, 200))
				.line(12, runStatic(BOTH, "lambda$build$0")).line(13, returnX(true)).done();
		MethodNode forgeBuild = new M(BOTH, Opcodes.ACC_PUBLIC, "build", "(I)I").line(10, rec(1)).line(11, mark(FORGE, 100))
				.line(12, runStatic(BOTH, "lambda$build$1")).line(13, returnX(true)).done();
		MethodNode forgeOverload = new M(BOTH, Opcodes.ACC_PUBLIC, "build", "(J)I").line(50, runInstance(BOTH, "lambda$build$0"))
				.line(51, new InsnNode(Opcodes.ICONST_0), new InsnNode(Opcodes.IRETURN)).done();
		AbstractInsnNode[] forgeLambda = { new VarInsnNode(Opcodes.ALOAD, 0), new InsnNode(Opcodes.POP), mark(FORGE, 300)[0], mark(FORGE, 300)[1] };
		ClassNode vanilla = type(BOTH, constructor(), vanillaBuild, lambda("lambda$build$0", true, rec(2)));
		ClassNode neo = type(BOTH, constructor(), neoBuild, lambda("lambda$build$0", true, rec(2)));
		ClassNode forge = type(BOTH, constructor(), forgeBuild, forgeOverload, lambda("lambda$build$0", false, forgeLambda),
				lambda("lambda$build$1", true, rec(2)));
		Build b = build(vanilla, forge, neo, patch(hunk(10, 10, "F", "A", "B", "R")), patch(hunk(10, 10, "N", "A", "B", "R")));
		assertTrue(b.report.contains(BOTH + "#build(I)I SPLICED"), b.report);
		assertTrue(b.report.contains(BOTH + "#lambda$build$0()V (captured from BOTH sides' surviving methods"), b.report);
		assertFalse(b.report.contains("lambda audit refused"), b.report);
		assertFalse(b.report.contains(BOTH + "#build(I)I (forge hook lost)"), b.report);
		Class<?> c = b.load(BOTH);
		assertEquals(7, call(c, c.getConstructor().newInstance(), "build", 7));
		assertEquals(List.of(1, 100, 200, 2), CALLS);
	}

	// ---- a carried call into a method that reads a field no running code writes -----------------------------------

	private static final String BREW = "net/minecraft/demo/Brew", RECIPES = "net/minecraft/demo/Recipes";

	/** Merges several classes with edit splicing on, each side's patch given per class. */
	private Map<String, byte[]> buildAll(List<ClassNode> vanilla, List<ClassNode> forge, List<ClassNode> neo, Map<String, String> forgePatches,
			Map<String, String> neoPatches, StringBuilder report) throws Exception {
		Map<String, byte[]> v = new LinkedHashMap<>(), f = new LinkedHashMap<>(), n = new LinkedHashMap<>();
		for (ClassNode c : vanilla) v.put(c.name, bytes(c));
		for (ClassNode c : forge) f.put(c.name, bytes(c));
		for (ClassNode c : neo) n.put(c.name, bytes(c));
		MergedBaseBuilder builder = new MergedBaseBuilder();
		Map<String, String> fp = new LinkedHashMap<>(), np = new LinkedHashMap<>();
		forgePatches.forEach((cls, text) -> fp.put(cls + ".java", text));
		neoPatches.forEach((cls, text) -> np.put(cls + ".java", text));
		builder.forgeProvenance = PatchLineProvenance.of(fp);
		builder.neoProvenance = PatchLineProvenance.of(np);
		Path merged = directory.resolve("merged-all.jar"), out = directory.resolve("report-all.txt");
		builder.run(jar("v.jar", v), jar("f.jar", f), jar("n.jar", n), merged, out, jar("fr.jar", Map.of(FORGE, hooks(FORGE))),
				jar("nr.jar", Map.of(NEO, hooks(NEO))));
		report.append(Files.readString(out));
		Map<String, byte[]> classes = new LinkedHashMap<>();
		try (ZipFile zip = new ZipFile(merged.toFile())) {
			for (ClassNode c : vanilla) classes.put(c.name, zip.getInputStream(zip.getEntry(c.name + ".class")).readAllBytes());
		}
		return classes;
	}

	/**
	 * BrewingStand's shape: MinecraftForge's run in {@code Brew.place} calls {@code Recipes.valid()}, which reads
	 * MinecraftForge's private {@code Recipes.list}. Its only writer is the private {@code Recipes.load()};
	 * {@code initialized} says whether MinecraftForge's class initializer calls it (in its own game a method the merge
	 * kept from NeoForge called it, so in the merged game nothing does).
	 */
	private Map<String, byte[]> brew(boolean initialized, StringBuilder report) throws Exception {
		int st = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
		AbstractInsnNode[] callValid = { new MethodInsnNode(Opcodes.INVOKESTATIC, RECIPES, "valid", "()I", false),
				new MethodInsnNode(Opcodes.INVOKESTATIC, RECORD, "record", "(I)V", false) };
		ClassNode vanillaBrew = type(BREW, new M(BREW, st, "place", "(I)I").line(10, rec(1)).line(11, rec(2)).line(12, returnX(false)).done());
		ClassNode neoBrew = type(BREW, new M(BREW, st, "place", "(I)I").line(10, rec(1)).line(11, mark(NEO, 200)).line(12, rec(2)).line(13, returnX(false)).done());
		ClassNode forgeBrew = type(BREW, new M(BREW, st, "place", "(I)I").line(10, rec(1)).line(11, callValid).line(12, rec(2)).line(13, returnX(false)).done());
		ClassNode vanillaRecipes = type(RECIPES, constructor());
		ClassNode forgeRecipes = type(RECIPES, constructor());
		forgeRecipes.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "list", "Ljava/lang/Integer;", null, null));
		MethodNode load = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "load", "()V", null, null);
		load.instructions.add(new IntInsnNode(Opcodes.SIPUSH, 42));
		load.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FORGE, "make", "(I)Ljava/lang/Integer;", false));
		load.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, RECIPES, "list", "Ljava/lang/Integer;"));
		load.instructions.add(new InsnNode(Opcodes.RETURN));
		MethodNode valid = new MethodNode(st, "valid", "()I", null, null);
		valid.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, RECIPES, "list", "Ljava/lang/Integer;"));
		valid.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false));
		valid.instructions.add(new InsnNode(Opcodes.IRETURN));
		forgeRecipes.methods.addAll(List.of(load, valid));
		if (initialized) {
			MethodNode clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
			clinit.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RECIPES, "load", "()V", false));
			clinit.instructions.add(new InsnNode(Opcodes.RETURN));
			forgeRecipes.methods.add(clinit);
		}
		String brewPatch = patch(hunk(10, 10, "B", "A", "B", "R"));
		return buildAll(List.of(vanillaBrew, vanillaRecipes), List.of(forgeBrew, forgeRecipes), List.of(neoBrew, type(RECIPES, constructor())),
				Map.of(BREW, brewPatch, RECIPES, patch(hunk(1, 1, "L", "A"))), Map.of(BREW, brewPatch), report);
	}

	private static Class<?> load(Map<String, byte[]> classes, String name) throws Exception {
		class Loader extends ClassLoader {
			Loader() {
				super(EditSpliceClassChecksTest.class.getClassLoader());
			}

			Class<?> define(String internal, byte[] code) {
				return defineClass(internal.replace('/', '.'), code, 0, code.length);
			}
		}
		Loader loader = new Loader();
		loader.define(FORGE, hooks(FORGE));
		loader.define(NEO, hooks(NEO));
		Class<?> wanted = null;
		for (var e : classes.entrySet()) {
			Class<?> c = loader.define(e.getKey(), e.getValue());
			if (e.getKey().equals(name)) wanted = c;
		}
		return wanted;
	}

	@Test
	void aCarriedCallIntoAMethodReadingAFieldNoRunningCodeWritesIsWithheld() throws Exception {
		StringBuilder report = new StringBuilder();
		Map<String, byte[]> classes = brew(false, report);
		assertTrue(report.toString().contains("x withheld L11-11 it calls Recipes.valid, which reads Recipes.list, which no code that runs"),
				report.toString());
		assertEquals(7, call(load(classes, BREW), null, "place", 7), "no NullPointerException from a field only an uncalled method writes");
		assertEquals(List.of(1, 200, 2), CALLS);
	}

	/** Negative control: when something that runs writes the field (the class initializer), the call is carried. */
	@Test
	void aCarriedCallIntoAMethodReadingAFieldRunningCodeWritesIsKept() throws Exception {
		StringBuilder report = new StringBuilder();
		Map<String, byte[]> classes = brew(true, report);
		assertFalse(report.toString().contains("no code that runs"), report.toString());
		assertTrue(report.toString().contains(BREW + "#place(I)I SPLICED"), report.toString());
		assertEquals(7, call(load(classes, BREW), null, "place", 7));
		assertEquals(List.of(1, 42, 200, 2), CALLS);
	}
}
