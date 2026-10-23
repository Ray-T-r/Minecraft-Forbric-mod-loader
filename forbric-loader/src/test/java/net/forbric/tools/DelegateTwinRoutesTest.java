/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The delegate-twin pass, on three small games whose names mean nothing to it.
 *
 * <p>The shape is the one the merged {@code Block.playerDestroy} had, under unrelated names: vanilla's {@code reap}
 * calls {@code scatter(a, 3)}, whose body ends in a {@code release}. The first side turns {@code scatter(a, n)} into a
 * delegate to its own {@code scatter(a, n, true)} and has {@code reap} pass {@code false}, because its own
 * {@code harvest} — the caller of {@code reap} — does the released part itself. The second side replaces the release in
 * {@code scatter} with its own hook and leaves {@code reap} alone. Each case runs the merged classes and records which
 * calls happen, so the claim is about what the merged game does, not only about its bytes.
 */
public class DelegateTwinRoutesTest {
	@TempDir Path directory;

	/** What the hooks of the games below record when they run. Read by the generated classes through {@link #record}. */
	static final List<String> CALLS = Collections.synchronizedList(new ArrayList<>());

	public static void record(String call) {
		CALLS.add(call);
	}

	private static final String RECORD = "net/forbric/tools/DelegateTwinRoutesTest";

	/** Two spellings of the same game: the pass must decide the same for both. */
	private record Names(String grain, String field, String scatter, String reap, String harvest, String forgeHook, String neoHook) {
		static Names of(String set) {
			return set.equals("aster")
					? new Names("net/minecraft/aster/Grain", "net/minecraft/aster/Field", "scatter", "reap", "harvest",
							"net/minecraftforge/aster/ForgeHook", "net/neoforged/aster/NeoHook")
					: new Names("net/minecraft/quill/Ledger", "net/minecraft/quill/Desk", "inscribe", "copy", "file",
							"net/minecraftforge/quill/Hook", "net/neoforged/quill/Hook");
		}
		String entryDesc() { return "(Ljava/lang/Object;I)V"; }
		String twinDesc() { return "(Ljava/lang/Object;IZ)V"; }
	}

	/** How each side's {@code harvest} looks and whether the first side's {@code reap} passes its own constant. */
	private enum Variant { PLAIN, NEO_LEAVES_HARVEST, SAME_CONSTANT, NEO_LEAVES_SCATTER }

	@ParameterizedTest
	@ValueSource(strings = { "aster", "quill" })
	void aCallIntoTheTwinFollowsTheMergedEntryWhenTheCompensationWasLost(String set) throws Exception {
		Names names = Names.of(set);
		Built built = build(names, Variant.PLAIN);
		MethodNode reap = built.method(names.grain(), names.reap(), "(Ljava/lang/Object;)V");
		assertEquals(List.of(names.grain() + "." + names.scatter() + names.entryDesc()), calls(reap),
				"the merged reap calls the merged entry, not the first side's twin");
		assertTrue(built.report().contains(MergedBaseBuilder.DELEGATE_TWIN_SECTION), built.report());
		assertTrue(built.report().contains(names.grain() + "#" + names.reap() + "(Ljava/lang/Object;)V -> " + names.grain() + "."
				+ names.scatter() + names.twinDesc() + " RETARGETED to " + names.grain() + "." + names.scatter() + names.entryDesc()
				+ " (forge edit given up here)"), built.report());
		assertFalse(built.report().contains(names.grain() + "#" + names.reap() + "(Ljava/lang/Object;)V (forge hook lost)"),
				"not a both-modified method: not a row of the hook-lost section");

		// Run it: the second side's hook in scatter now runs on reap's path, exactly once, and nothing is released twice.
		assertEquals(List.of("out", "neo-release"), built.run(names, "harvest-path"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "aster", "quill" })
	void theCallStaysWhereTheFirstSidesCompensationSurvived(String set) throws Exception {
		Names names = Names.of(set);
		Built built = build(names, Variant.NEO_LEAVES_HARVEST);
		MethodNode reap = built.method(names.grain(), names.reap(), "(Ljava/lang/Object;)V");
		assertEquals(List.of(names.grain() + "." + names.scatter() + names.twinDesc()), calls(reap));
		assertTrue(built.report().contains(" LEFT: " + names.field() + "#" + names.harvest()), built.report());
		// The first side's mechanism is whole on this path: its harvest releases, its twin does not.
		assertEquals(List.of("out", "forge-compensate"), built.run(names, "harvest-path"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "aster", "quill" })
	void aCallPassingTheDelegatesOwnConstantsIsLeftAlone(String set) throws Exception {
		Names names = Names.of(set);
		Built built = build(names, Variant.SAME_CONSTANT);
		assertEquals(List.of(names.grain() + "." + names.scatter() + names.twinDesc()),
				calls(built.method(names.grain(), names.reap(), "(Ljava/lang/Object;)V")));
		assertTrue(built.report().contains("LEFT: the call passes the delegate's own constants"), built.report());
	}

	@ParameterizedTest
	@ValueSource(strings = { "aster", "quill" })
	void nothingToFollowWhenTheMergeKeptTheFirstSidesDelegate(String set) throws Exception {
		Names names = Names.of(set);
		Built built = build(names, Variant.NEO_LEAVES_SCATTER);
		assertEquals(List.of(names.grain() + "." + names.scatter() + names.twinDesc()),
				calls(built.method(names.grain(), names.reap(), "(Ljava/lang/Object;)V")));
		assertFalse(built.report().contains("RETARGETED"), built.report());
		// The first side's delegate is the merged entry, so nothing was decided against it: the pass has nothing to
		// follow. (That this path also lost its compensation, in harvest, is the harvest merge's own conflict row.)
		assertEquals(List.of("out"), built.run(names, "harvest-path"));
	}

	// ---- the three games ---------------------------------------------------------------------------------------------

	private record Built(Map<String, byte[]> classes, String report) {
		MethodNode method(String owner, String name, String desc) {
			ClassNode node = new ClassNode();
			new ClassReader(classes.get(owner)).accept(node, 0);
			return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElseThrow();
		}

		/** Runs {@code new Field().harvest(new Grain(), "x")} on the merged classes and returns what was recorded. */
		List<String> run(Names names, String tag) throws Exception {
			Map<String, byte[]> all = new HashMap<>(classes);
			all.put(names.forgeHook(), hook(names.forgeHook()));
			all.put(names.neoHook(), hook(names.neoHook()));
			ClassLoader loader = new ClassLoader(DelegateTwinRoutesTest.class.getClassLoader()) {
				@Override protected Class<?> findClass(String name) throws ClassNotFoundException {
					byte[] bytes = all.get(name.replace('.', '/'));
					if (bytes == null) throw new ClassNotFoundException(name);
					return defineClass(name, bytes, 0, bytes.length);
				}
			};
			CALLS.clear();
			Class<?> field = loader.loadClass(names.field().replace('/', '.'));
			Class<?> grain = loader.loadClass(names.grain().replace('/', '.'));
			field.getMethod(names.harvest(), grain, Object.class).invoke(field.getConstructor().newInstance(), grain.getConstructor().newInstance(), tag);
			return List.copyOf(CALLS);
		}
	}

	private Built build(Names names, Variant variant) throws Exception {
		Path vanilla = jar("vanilla.jar", Map.of(names.grain(), grain(names, "vanilla", variant), names.field(), field(names, "vanilla", variant)));
		Path forge = jar("forge.jar", Map.of(names.grain(), grain(names, "forge", variant), names.field(), field(names, "forge", variant)));
		Path neo = jar("neo.jar", Map.of(names.grain(), grain(names, "neo", variant), names.field(), field(names, "neo", variant)));
		Path merged = directory.resolve("merged-" + variant + ".jar"), report = directory.resolve("report-" + variant + ".txt");
		new MergedBaseBuilder().run(vanilla, forge, neo, merged, report, null, null);
		Map<String, byte[]> classes = new HashMap<>();
		try (ZipFile zip = new ZipFile(merged.toFile())) {
			for (String owner : List.of(names.grain(), names.field())) classes.put(owner, zip.getInputStream(zip.getEntry(owner + ".class")).readAllBytes());
		}
		return new Built(classes, Files.readString(report));
	}

	/** {@code Grain}: {@code scatter(a, n)}, the first side's {@code scatter(a, n, release)}, and {@code reap(a)}. */
	private static byte[] grain(Names names, String side, Variant variant) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, names.grain(), null, "java/lang/Object", null);
		constructor(cw);
		boolean neoScatter = side.equals("neo") && variant != Variant.NEO_LEAVES_SCATTER;
		MethodVisitor scatter = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, names.scatter(), names.entryDesc(), null, null);
		scatter.visitCode();
		if (side.equals("forge")) {
			scatter.visitVarInsn(Opcodes.ALOAD, 0);
			scatter.visitVarInsn(Opcodes.ILOAD, 1);
			scatter.visitInsn(Opcodes.ICONST_1);
			scatter.visitMethodInsn(Opcodes.INVOKESTATIC, names.grain(), names.scatter(), names.twinDesc(), false);
		} else {
			recordCall(scatter, "out");
			if (neoScatter) scatter.visitMethodInsn(Opcodes.INVOKESTATIC, names.neoHook(), "release", "()V", false);
			else recordCall(scatter, "release");
		}
		scatter.visitInsn(Opcodes.RETURN);
		scatter.visitMaxs(0, 0);
		scatter.visitEnd();
		if (side.equals("forge")) {
			MethodVisitor twin = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, names.scatter(), names.twinDesc(), null, null);
			twin.visitCode();
			recordCall(twin, "out");
			Label skip = new Label();
			twin.visitVarInsn(Opcodes.ILOAD, 2);
			twin.visitJumpInsn(Opcodes.IFEQ, skip);
			recordCall(twin, "release");
			twin.visitLabel(skip);
			twin.visitInsn(Opcodes.RETURN);
			twin.visitMaxs(0, 0);
			twin.visitEnd();
		}
		MethodVisitor reap = cw.visitMethod(Opcodes.ACC_PUBLIC, names.reap(), "(Ljava/lang/Object;)V", null, null);
		reap.visitCode();
		reap.visitVarInsn(Opcodes.ALOAD, 1);
		reap.visitInsn(Opcodes.ICONST_3);
		if (side.equals("forge")) {
			reap.visitInsn(variant == Variant.SAME_CONSTANT ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
			reap.visitMethodInsn(Opcodes.INVOKESTATIC, names.grain(), names.scatter(), names.twinDesc(), false);
		} else {
			reap.visitMethodInsn(Opcodes.INVOKESTATIC, names.grain(), names.scatter(), names.entryDesc(), false);
		}
		reap.visitInsn(Opcodes.RETURN);
		reap.visitMaxs(0, 0);
		reap.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** {@code Field.harvest(grain, a)}: the first side releases after reaping; the second side observes before it. */
	private static byte[] field(Names names, String side, Variant variant) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, names.field(), null, "java/lang/Object", null);
		constructor(cw);
		MethodVisitor harvest = cw.visitMethod(Opcodes.ACC_PUBLIC, names.harvest(), "(L" + names.grain() + ";Ljava/lang/Object;)V", null, null);
		harvest.visitCode();
		if (side.equals("neo") && variant != Variant.NEO_LEAVES_HARVEST) harvest.visitMethodInsn(Opcodes.INVOKESTATIC, names.neoHook(), "observe", "()V", false);
		harvest.visitVarInsn(Opcodes.ALOAD, 1);
		harvest.visitVarInsn(Opcodes.ALOAD, 2);
		harvest.visitMethodInsn(Opcodes.INVOKEVIRTUAL, names.grain(), names.reap(), "(Ljava/lang/Object;)V", false);
		if (side.equals("forge")) harvest.visitMethodInsn(Opcodes.INVOKESTATIC, names.forgeHook(), "compensate", "()V", false);
		harvest.visitInsn(Opcodes.RETURN);
		harvest.visitMaxs(0, 0);
		harvest.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A platform hook class: {@code compensate}, {@code release} and {@code observe} record their family's name. */
	private static byte[] hook(String owner) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
		String family = owner.startsWith("net/minecraftforge/") ? "forge" : "neo";
		for (String name : List.of("compensate", "release", "observe")) {
			MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "()V", null, null);
			m.visitCode();
			if (!name.equals("observe")) recordCall(m, family + "-" + name);
			m.visitInsn(Opcodes.RETURN);
			m.visitMaxs(0, 0);
			m.visitEnd();
		}
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static void recordCall(MethodVisitor m, String what) {
		m.visitLdcInsn(what);
		m.visitMethodInsn(Opcodes.INVOKESTATIC, RECORD, "record", "(Ljava/lang/String;)V", false);
	}

	private static void constructor(ClassWriter cw) {
		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();
	}

	private static List<String> calls(MethodNode method) {
		List<String> out = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode call) out.add(call.owner + "." + call.name + call.desc);
		return out;
	}

	private Path jar(String name, Map<String, byte[]> classes) throws Exception {
		Path path = directory.resolve(name);
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
			for (var entry : classes.entrySet()) {
				out.putNextEntry(new ZipEntry(entry.getKey() + ".class"));
				out.write(entry.getValue());
				out.closeEntry();
			}
		}
		return path;
	}
}
