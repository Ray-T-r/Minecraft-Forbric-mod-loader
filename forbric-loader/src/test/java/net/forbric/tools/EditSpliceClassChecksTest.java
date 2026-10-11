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
		Set<String> findings = MergedBaseBuilder.auditCapturedLambdas(merged, methods(), methods(), Set.of("use()V"), Set.of());
		assertEquals(Set.of("use()V -> lambda$use$0()V: a static handle names an instance method"), findings);
	}

	@Test
	void theAuditFindsBaseSideCodeCapturingTheOtherSidesLambda() {
		MethodNode base = lambda("lambda$use$0", true, rec(2)), other = lambda("lambda$use$0", true, mark(FORGE, 100));
		ClassNode merged = audited(lambda("lambda$use$0", true, mark(FORGE, 100)));
		Set<String> findings = MergedBaseBuilder.auditCapturedLambdas(merged, methods(base), methods(other), Set.of("use()V"), Set.of());
		assertEquals(Set.of("use()V -> lambda$use$0()V: base-side code captures the other side's lambda of that name"), findings);
	}

	/** Negative control: the base's own lambda, or a lambda both sides' code captures (already reported), is no finding. */
	@Test
	void theAuditAcceptsTheBasesLambdaAndLeavesAContestedOneToItsReport() {
		MethodNode base = lambda("lambda$use$0", true, rec(2)), other = lambda("lambda$use$0", true, mark(FORGE, 100));
		assertEquals(Set.of(), MergedBaseBuilder.auditCapturedLambdas(audited(lambda("lambda$use$0", true, rec(2))),
				methods(base), methods(other), Set.of("use()V"), Set.of()));
		assertEquals(Set.of(), MergedBaseBuilder.auditCapturedLambdas(audited(lambda("lambda$use$0", true, mark(FORGE, 100))),
				methods(base), methods(other), Set.of("use()V"), Set.of("lambda$use$0()V")));
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
}
