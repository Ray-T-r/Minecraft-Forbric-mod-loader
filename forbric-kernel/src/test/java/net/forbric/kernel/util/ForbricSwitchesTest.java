/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.util;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.mixin.MixinCameraRollAdapter;
import net.forbric.kernel.mixin.MixinPlayerWorldCallbackAdapter;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

/**
 * A renamed {@code -Dforbric.*} switch still answers to its old name, with one warning naming the new one; a retired
 * one says it does nothing. The behaviour is checked on a table with made-up names; the census then checks the real
 * table against the compiled kernel, so a reader that goes back to {@link System#getProperty}, or a switch that
 * disappears without a row here, fails the build instead of silently dropping a player's flag.
 */
class ForbricSwitchesTest {
	private static final String OLD = "forbric.formerSampleSwitch", CURRENT = "forbric.presentSampleSwitch";
	private static final String HELPER = "net/forbric/kernel/util/ForbricSwitches";
	/** The ways a switch is read without the table. Properties.getProperty is how System.getProperties() is read. */
	private static final Set<String> RAW_READS = Set.of("java/lang/System.getProperty", "java/lang/Boolean.getBoolean",
			"java/lang/Integer.getInteger", "java/lang/Long.getLong", "java/util/Properties.getProperty");

	private record Run(ForbricSwitches switches, List<String> warnings) { }

	private static Run run(Map<String, String> renamed, Map<String, String> retired, Map<String, String> set) {
		List<String> warnings = new ArrayList<>();
		return new Run(new ForbricSwitches(renamed, retired, set::get, warnings::add), warnings);
	}

	private static Run run(Map<String, String> set) {
		return run(Map.of(OLD, CURRENT), Map.of(), set);
	}

	// ---- behaviour, on a table of made-up names ----

	@Test void theCurrentNameIsReadAsItIsAndWarnsNothing() {
		Run r = run(Map.of(CURRENT, "off"));
		assertEquals("off", r.switches().lookup(CURRENT, "on"));
		r.switches().announceSet();
		assertEquals(List.of(), r.warnings());
	}

	@Test void anOldNameAloneAppliesToTheCurrentSwitchAndWarnsOnceNamingIt() {
		Run r = run(Map.of(OLD, "off"));
		assertEquals("off", r.switches().lookup(CURRENT, "on"));
		assertEquals("off", r.switches().lookup(CURRENT, "on"));
		assertEquals("off", r.switches().lookup(CURRENT, null));
		assertEquals(1, r.warnings().size(), r.warnings()::toString);
		String warning = r.warnings().getFirst();
		assertTrue(warning.contains("-D" + OLD + " is a renamed switch") && warning.contains("-D" + CURRENT + "=off"), warning);
	}

	@Test void whenBothAreSetTheCurrentNameWinsAndTheOldOneIsReportedIgnored() {
		Run r = run(Map.of(OLD, "off", CURRENT, "keep"));
		assertEquals("keep", r.switches().lookup(CURRENT, "on"));
		assertEquals("keep", r.switches().lookup(CURRENT, "on"));
		assertEquals(1, r.warnings().size(), r.warnings()::toString);
		String warning = r.warnings().getFirst();
		assertTrue(warning.contains("-D" + OLD + "=off is a renamed switch and is ignored")
				&& warning.contains("-D" + CURRENT + "=keep is also set"), warning);
	}

	@Test void nothingSetFallsBackSilently() {
		Run r = run(Map.of());
		assertEquals("on", r.switches().lookup(CURRENT, "on"));
		assertNull(r.switches().lookup(CURRENT, null));
		r.switches().announceSet();
		assertEquals(List.of(), r.warnings());
	}

	/** Look-alikes stay apart: another switch, and the old name in another case, are not the declared rename. */
	@Test void onlyTheDeclaredRenameIsFollowed() {
		Run r = run(Map.of("forbric.FormerSampleSwitch", "off", "forbric.unrelatedSampleSwitch", "off"));
		assertEquals("on", r.switches().lookup(CURRENT, "on"));
		assertEquals("off", r.switches().lookup("forbric.unrelatedSampleSwitch", "on"));
		Run old = run(Map.of(OLD, "off"));
		assertEquals("on", old.switches().lookup("forbric.unrelatedSampleSwitch", "on"));
		r.switches().announceSet();
		assertEquals(List.of(), r.warnings());
		assertEquals(List.of(), old.warnings());
	}

	@Test void aRetiredSwitchWarnsOnceThatItHasNoEffect() {
		Map<String, String> retired = Map.of("forbric.retiredSampleSwitch", "its mechanism was removed");
		Run r = run(Map.of(), retired, Map.of("forbric.retiredSampleSwitch", "off"));
		r.switches().announceSet();
		r.switches().announceSet();
		assertEquals(List.of("[Forbric/Switches] -Dforbric.retiredSampleSwitch=off has no effect: its mechanism was "
				+ "removed. Remove it from the launch arguments."), r.warnings());
		Run quiet = run(Map.of(), retired, Map.of());
		quiet.switches().announceSet();
		assertEquals(List.of(), quiet.warnings());
	}

	/** The boot announcement and a later read report the same old name once, and agree on which value applies. */
	@Test void theBootAnnouncementAndLaterReadsShareOneWarning() {
		Run r = run(Map.of(OLD, "off"));
		r.switches().announceSet();
		assertEquals("off", r.switches().lookup(CURRENT, "on"));
		assertEquals(1, r.warnings().size(), r.warnings()::toString);
		assertTrue(r.warnings().getFirst().contains("is applied as -D" + CURRENT), r.warnings().getFirst());

		Run both = run(Map.of(OLD, "off", CURRENT, "keep"));
		both.switches().announceSet();
		assertEquals("keep", both.switches().lookup(CURRENT, "on"));
		assertEquals(1, both.warnings().size(), both.warnings()::toString);
		assertTrue(both.warnings().getFirst().contains("is ignored"), both.warnings().getFirst());
	}

	@Test void twoOldNamesOfOneSwitchResolveInAFixedOrder() {
		Map<String, String> renamed = Map.of("forbric.alphaSampleSwitch", CURRENT, "forbric.betaSampleSwitch", CURRENT);
		Run r = run(renamed, Map.of(), Map.of("forbric.alphaSampleSwitch", "first", "forbric.betaSampleSwitch", "second"));
		assertEquals("first", r.switches().lookup(CURRENT, null));
		assertEquals(2, r.warnings().size(), r.warnings()::toString);
		assertTrue(r.warnings().get(0).contains("-Dforbric.alphaSampleSwitch is a renamed switch"), r.warnings().get(0));
		assertTrue(r.warnings().get(1).contains("-Dforbric.betaSampleSwitch=second is a renamed switch and is ignored")
				&& r.warnings().get(1).contains("-Dforbric.alphaSampleSwitch=first, another old name"), r.warnings().get(1));
	}

	@Test void aTableThatDoesNotEndAtALiveSwitchIsRejected() {
		Consumer<String> none = w -> fail(w);
		assertThrows(IllegalArgumentException.class, () -> new ForbricSwitches(
				Map.of("forbric.a", "forbric.b", "forbric.b", "forbric.c"), Map.of(), k -> null, none));
		assertThrows(IllegalArgumentException.class, () -> new ForbricSwitches(
				Map.of("forbric.a", "forbric.a"), Map.of(), k -> null, none));
		assertThrows(IllegalArgumentException.class, () -> new ForbricSwitches(
				Map.of("forbric.a", "forbric.b"), Map.of("forbric.b", "gone"), k -> null, none));
		assertThrows(IllegalArgumentException.class, () -> new ForbricSwitches(
				Map.of("forbric.a", "forbric.b"), Map.of("forbric.a", "gone"), k -> null, none));
	}

	// ---- the real table ----

	@Test void theShippedTableIsWellFormed() {
		new ForbricSwitches(ForbricSwitches.RENAMED, ForbricSwitches.RETIRED, k -> null, w -> fail(w));
		for (Map.Entry<String, String> row : ForbricSwitches.RENAMED.entrySet()) {
			assertTrue(row.getKey().startsWith("forbric.") && row.getValue().startsWith("forbric."), row::toString);
		}
		for (String retired : ForbricSwitches.RETIRED.keySet()) assertTrue(retired.startsWith("forbric."), retired);
	}

	/** End to end through System properties: a real reader honours the name its switch used to have. */
	@Test void aRealReaderHonoursTheNameItsSwitchUsedToHave() {
		List<String> names = List.of("forbric.barrelRollCamera", MixinCameraRollAdapter.PROPERTY, "forbric.carpetMixins",
				MixinPlayerWorldCallbackAdapter.PROPERTY);
		Map<String, String> saved = new HashMap<>();
		for (String name : names) saved.put(name, System.getProperty(name));
		try {
			names.forEach(System::clearProperty);
			assertTrue(MixinCameraRollAdapter.enabled());
			System.setProperty("forbric.barrelRollCamera", "off");
			assertFalse(MixinCameraRollAdapter.enabled());
			System.setProperty(MixinCameraRollAdapter.PROPERTY, "on");
			assertTrue(MixinCameraRollAdapter.enabled(), "the current name wins over the old one");
			assertTrue(MixinPlayerWorldCallbackAdapter.enabled());
			System.setProperty("forbric.carpetMixins", "OFF");
			assertFalse(MixinPlayerWorldCallbackAdapter.enabled());
		} finally {
			for (String name : names) {
				if (saved.get(name) == null) System.clearProperty(name);
				else System.setProperty(name, saved.get(name));
			}
		}
	}

	// ---- census over the compiled kernel ----

	@Test void theKernelReadsEveryRenamedSwitchThroughTheTable() throws Exception {
		Set<String> through = new TreeSet<>();
		List<String> problems = new ArrayList<>();
		for (ClassNode type : classes(mainClasses())) {
			problems.addAll(census(type, ForbricSwitches.RENAMED, ForbricSwitches.RETIRED, through));
		}
		assertEquals(List.of(), problems);
		Set<String> unread = new TreeSet<>(ForbricSwitches.RENAMED.values());
		unread.removeAll(through);
		assertEquals(Set.of(), unread, "renamed switches no kernel class reads through ForbricSwitches.get, so no one "
				+ "would honour their old names");
	}

	@Test void theGameSideReadsRenamedSwitchesThroughTheTableToo() throws Exception {
		Path runtime = runtimeClasses();
		TestFixtures.requireDirectory(Fixture.GAME_SIDE, "compiled game side", runtime);
		Set<String> through = new TreeSet<>();
		List<String> problems = new ArrayList<>();
		for (ClassNode type : classes(runtime)) {
			problems.addAll(census(type, ForbricSwitches.RENAMED, ForbricSwitches.RETIRED, through));
		}
		assertEquals(List.of(), problems);
		assertTrue(through.contains("forbric.registryElementCallbacks"), through::toString);
	}

	/** The census must see a raw read and a leftover old name, and must leave a mere mention of the name alone. */
	@Test void theCensusSeesARawReadAndAnOldNameButNotAMention() throws Exception {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "sample/SwitchReader", null, "java/lang/Object", null);
		MethodVisitor raw = writer.visitMethod(Opcodes.ACC_STATIC, "raw", "()Ljava/lang/String;", null, null);
		raw.visitCode();
		raw.visitLdcInsn(CURRENT);
		raw.visitLdcInsn("on");
		raw.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperty", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
		raw.visitInsn(Opcodes.ARETURN);
		raw.visitMaxs(0, 0);
		MethodVisitor table = writer.visitMethod(Opcodes.ACC_STATIC, "table", "()Ljava/lang/String;", null, null);
		table.visitCode();
		table.visitLdcInsn(CURRENT);
		table.visitLdcInsn("on");
		table.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "get", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
		table.visitInsn(Opcodes.ARETURN);
		table.visitMaxs(0, 0);
		MethodVisitor old = writer.visitMethod(Opcodes.ACC_STATIC, "old", "()Z", null, null);
		old.visitCode();
		old.visitLdcInsn(OLD);
		old.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "getBoolean", "(Ljava/lang/String;)Z", false);
		old.visitInsn(Opcodes.IRETURN);
		old.visitMaxs(0, 0);
		MethodVisitor mention = writer.visitMethod(Opcodes.ACC_STATIC, "mention", "()Ljava/lang/String;", null, null);
		mention.visitCode();
		mention.visitLdcInsn("turned off with -D%s=off");
		mention.visitInsn(Opcodes.ICONST_1);
		mention.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
		mention.visitInsn(Opcodes.DUP);
		mention.visitInsn(Opcodes.ICONST_0);
		mention.visitLdcInsn(CURRENT);
		mention.visitInsn(Opcodes.AASTORE);
		mention.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "format", "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;", false);
		mention.visitInsn(Opcodes.ARETURN);
		mention.visitMaxs(0, 0);
		writer.visitEnd();
		ClassNode type = new ClassNode();
		new ClassReader(writer.toByteArray()).accept(type, 0);

		Set<String> through = new TreeSet<>();
		List<String> problems = census(type, Map.of(OLD, CURRENT), Map.of(), through);
		assertEquals(List.of("sample/SwitchReader.raw reads " + CURRENT + " with java/lang/System.getProperty, not through ForbricSwitches",
				"sample/SwitchReader.old still names " + OLD), problems);
		assertEquals(Set.of(CURRENT), through);
	}

	/** Boot warns before anything else runs, so a renamed or retired flag is named even if its reader never runs. */
	@Test void bootAnnouncesTheSwitchesBeforeAnythingElse() throws Exception {
		ClassNode boot = new ClassNode();
		try (InputStream in = Files.newInputStream(classFile(mainClasses(), "net/forbric/kernel/boot/KernelBoot"))) {
			new ClassReader(in).accept(boot, ClassReader.SKIP_DEBUG);
		}
		MethodNode launch = boot.methods.stream().filter(m -> m.name.equals("launch")).findFirst().orElseThrow();
		MethodInsnNode first = null;
		for (AbstractInsnNode insn : launch.instructions) {
			if (insn instanceof MethodInsnNode call) {
				first = call;
				break;
			}
		}
		assertNotNull(first);
		assertEquals(HELPER + ".announce", first.owner + "." + first.name);
	}

	// ---- the release line's switches ----

	@Test void everySwitchTheReleaseLineDeclaredIsStillLiveOrListedHere() throws Exception {
		Path runtime = runtimeClasses();
		TestFixtures.requireDirectory(Fixture.GAME_SIDE, "compiled game side", runtime);
		Set<String> live = new HashSet<>();
		for (ClassNode type : classes(mainClasses())) {
			if (!type.name.equals(HELPER)) live.addAll(constants(type));
		}
		for (ClassNode type : classes(runtime)) live.addAll(constants(type));
		assertEquals(List.of(), vanished(released(), live, ForbricSwitches.RENAMED, ForbricSwitches.RETIRED),
				"switches the release line declared that the kernel no longer reads: list each in ForbricSwitches as "
						+ "renamed (the new name) or retired (why it does nothing)");
	}

	@Test void theLedgerCheckSeesASwitchThatVanished() {
		assertEquals(List.of("forbric.c"), vanished(List.of("forbric.a", "forbric.b", "forbric.c", "forbric.d"),
				Set.of("forbric.a"), Map.of("forbric.b", "forbric.a"), Map.of("forbric.d", "gone")));
	}

	static List<String> vanished(Collection<String> released, Set<String> live, Map<String, String> renamed,
			Map<String, String> retired) {
		List<String> vanished = new ArrayList<>();
		for (String name : released) {
			if (!live.contains(name) && !renamed.containsKey(name) && !retired.containsKey(name)) vanished.add(name);
		}
		return vanished;
	}

	/**
	 * What one class does with the table's names: an old name anywhere outside ForbricSwitches, or a current name
	 * handed straight to a raw property read, is a problem. Current names handed to ForbricSwitches.get go into
	 * {@code through}.
	 */
	static List<String> census(ClassNode type, Map<String, String> renamed, Map<String, String> retired,
			Set<String> through) throws AnalyzerException {
		List<String> problems = new ArrayList<>();
		if (type.name.equals(HELPER)) return problems;
		Set<String> current = new HashSet<>(renamed.values());
		for (FieldNode field : type.fields) {
			if (field.value instanceof String s && (renamed.containsKey(s) || retired.containsKey(s))) {
				problems.add(type.name + "." + field.name + " still names " + s);
			}
		}
		for (MethodNode method : type.methods) {
			boolean mentionsCurrent = false;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s) {
					if (renamed.containsKey(s) || retired.containsKey(s)) problems.add(type.name + "." + method.name + " still names " + s);
					if (current.contains(s)) mentionsCurrent = true;
				}
			}
			if (!mentionsCurrent) continue;
			Frame<SourceValue>[] frames = new Analyzer<>(new SourceInterpreter()).analyze(type.name, method);
			for (int i = 0; i < method.instructions.size(); i++) {
				if (!(method.instructions.get(i) instanceof MethodInsnNode call) || frames[i] == null) continue;
				int arguments = Type.getArgumentTypes(call.desc).length;
				if (arguments == 0) continue;
				SourceValue key = frames[i].getStack(frames[i].getStackSize() - arguments);
				for (AbstractInsnNode source : key.insns) {
					if (!(source instanceof LdcInsnNode ldc) || !(ldc.cst instanceof String s) || !current.contains(s)) continue;
					String called = call.owner + "." + call.name;
					if (RAW_READS.contains(called)) {
						problems.add(type.name + "." + method.name + " reads " + s + " with " + called + ", not through ForbricSwitches");
					} else if (called.equals(HELPER + ".get")) {
						through.add(s);
					}
				}
			}
		}
		return problems;
	}

	/** Every string constant in a class: ldc operands, field constants, and the fixed parts of concatenations. */
	private static Set<String> constants(ClassNode type) {
		Set<String> strings = new HashSet<>();
		for (FieldNode field : type.fields) if (field.value instanceof String s) strings.add(s);
		for (MethodNode method : type.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s) strings.add(s);
				else if (insn instanceof InvokeDynamicInsnNode indy) {
					for (Object argument : indy.bsmArgs) {
						if (argument instanceof String recipe) strings.addAll(List.of(recipe.split("[\u0001\u0002]")));
					}
				}
			}
		}
		return strings;
	}

	private static List<String> released() throws IOException {
		try (InputStream in = ForbricSwitchesTest.class.getResourceAsStream("released-switches.txt")) {
			assertNotNull(in, "released-switches.txt is missing from the test resources");
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().map(String::strip)
					.filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
		}
	}

	private static Path mainClasses() throws URISyntaxException {
		return Path.of(ForbricSwitches.class.getProtectionDomain().getCodeSource().getLocation().toURI());
	}

	private static Path runtimeClasses() {
		return Path.of(System.getProperty("forbric.test.runtimeClasses", "build/classes/java/runtime"));
	}

	private static Path classFile(Path root, String internalName) throws IOException {
		if (Files.isDirectory(root)) return root.resolve(internalName + ".class");
		FileSystem jar = FileSystems.newFileSystem(root);
		return jar.getPath(internalName + ".class");
	}

	/** The classes under {@code root}, each parsed only as the loop reaches it, so the census never holds them all. */
	private static Iterable<ClassNode> classes(Path root) throws IOException {
		Path base = Files.isDirectory(root) ? root : FileSystems.newFileSystem(root).getPath("/");
		List<Path> files;
		try (Stream<Path> walk = Files.walk(base)) {
			files = walk.filter(p -> p.toString().endsWith(".class")).sorted().toList();
		}
		assertFalse(files.isEmpty(), "no classes under " + root);
		return () -> files.stream().map(file -> {
			try {
				ClassNode node = new ClassNode();
				new ClassReader(Files.readAllBytes(file)).accept(node, ClassReader.SKIP_DEBUG);
				return node;
			} catch (IOException unreadable) {
				throw new java.io.UncheckedIOException(unreadable);
			}
		}).iterator();
	}
}
