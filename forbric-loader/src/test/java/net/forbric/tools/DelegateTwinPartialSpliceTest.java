/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

/**
 * The delegate-twin pass when an edit splice carried PART of the first side's compensating caller into the merged one.
 *
 * <p>Same game as {@link DelegateTwinRoutesTest}, under two more spellings: the first side's {@code fire(a, n)} delegates to
 * its {@code fire(a, n, true)}, whose mode switches a release on; its {@code glaze} passes {@code false}, and its {@code bake}
 * (glaze's caller) adds two calls — {@code compensate}, whose hook does the release, and an unrelated {@code extra}. The
 * second side's {@code fire} releases through its own hook. The merged {@code bake} is the second side's body with some of
 * the first side's calls carried in. The pass has to tell a carried compensation (pointing glaze back would release twice)
 * from a carried unrelated call (pointing it back is what releases at all); each case runs the merged game.
 */
public class DelegateTwinPartialSpliceTest {
	static final List<String> CALLS = Collections.synchronizedList(new ArrayList<>());

	public static void record(String call) {
		CALLS.add(call);
	}

	private static final String RECORD = "net/forbric/tools/DelegateTwinPartialSpliceTest";
	private static final String ENTRY = "(Ljava/lang/Object;I)V", TWIN = "(Ljava/lang/Object;IZ)V";

	private record Names(String tray, String shelf, String fire, String glaze, String bake, String forgeHook, String neoHook) {
		static Names of(String set) {
			return set.equals("kiln")
					? new Names("net/minecraft/kiln/Tray", "net/minecraft/kiln/Shelf", "fire", "glaze", "bake",
							"net/minecraftforge/kiln/Hook", "net/neoforged/kiln/Hook")
					: new Names("net/minecraft/loom/Spool", "net/minecraft/loom/Bench", "wind", "twist", "weave",
							"net/minecraftforge/loom/Rack", "net/neoforged/loom/Rack");
		}
	}

	/** Which of the first side's calls the merged caller carries, and whether the twin's mode is a readable test. */
	private enum Carried { COMPENSATION, UNRELATED, UNRELATED_UNREADABLE_MODE }

	@ParameterizedTest
	@ValueSource(strings = { "kiln", "loom" })
	void aCarriedCompensationKeepsTheCallOnTheTwin(String set) throws Exception {
		Names names = Names.of(set);
		Result result = route(names, Carried.COMPENSATION);
		assertFalse(result.retargeted(), result.lines());
		assertTrue(result.lines().contains(" LEFT: " + names.shelf() + "#" + names.bake()), result.lines());
		assertTrue(result.lines().contains("keeps " + names.forgeHook() + ".compensate()V of that side's edit, which does what the part the "
				+ "constants switch off does"), result.lines());
		// Released once — by the carried compensation; the twin's mode is still off on this path.
		assertEquals(List.of("out", "forge-compensate"), result.run());
	}

	@ParameterizedTest
	@ValueSource(strings = { "kiln", "loom" })
	void aCarriedUnrelatedCallDoesNotStandForTheCompensation(String set) throws Exception {
		Names names = Names.of(set);
		Result result = route(names, Carried.UNRELATED);
		assertTrue(result.retargeted(), result.lines());
		assertTrue(result.lines().contains("keeps " + names.forgeHook() + ".extra()V of that side's edit, none of which does what the "
				+ "switched-off part does"), result.lines());
		// Released once — by the second side's hook in the merged entry, which the call now reaches.
		assertEquals(List.of("out", "neo-release"), result.run());
	}

	@ParameterizedTest
	@ValueSource(strings = { "kiln", "loom" })
	void aCarriedCallIsLeftForJudgmentWhenTheSwitchedOffPartCannotBeRead(String set) throws Exception {
		Names names = Names.of(set);
		Result result = route(names, Carried.UNRELATED_UNREADABLE_MODE);
		assertFalse(result.retargeted(), result.lines());
		assertTrue(result.lines().contains("keeps part of that side's edit of it (" + names.forgeHook() + ".extra()V), and what the constants "
				+ "switch off cannot be read from the twin: left for judgment"), result.lines());
	}

	// ---- the games --------------------------------------------------------------------------------------------------

	private record Result(List<DelegateTwinRoutes.Decision> decisions, Map<String, byte[]> merged, Names names) {
		boolean retargeted() {
			return decisions.stream().anyMatch(DelegateTwinRoutes.Decision::retargeted);
		}

		String lines() {
			StringBuilder out = new StringBuilder();
			for (DelegateTwinRoutes.Decision decision : decisions) out.append(decision.line()).append('\n');
			return out.toString();
		}

		List<String> run() throws Exception {
			Map<String, byte[]> all = new HashMap<>(merged);
			all.put(names.forgeHook(), hook(names.forgeHook()));
			all.put(names.neoHook(), hook(names.neoHook()));
			ClassLoader loader = new ClassLoader(DelegateTwinPartialSpliceTest.class.getClassLoader()) {
				@Override protected Class<?> findClass(String name) throws ClassNotFoundException {
					byte[] bytes = all.get(name.replace('.', '/'));
					if (bytes == null) throw new ClassNotFoundException(name);
					return defineClass(name, bytes, 0, bytes.length);
				}
			};
			CALLS.clear();
			Class<?> shelf = loader.loadClass(names.shelf().replace('/', '.')), tray = loader.loadClass(names.tray().replace('/', '.'));
			shelf.getMethod(names.bake(), tray, Object.class).invoke(shelf.getConstructor().newInstance(), tray.getConstructor().newInstance(), "x");
			return List.copyOf(CALLS);
		}
	}

	private static Result route(Names names, Carried carried) {
		boolean readable = carried != Carried.UNRELATED_UNREADABLE_MODE;
		Map<String, byte[]> vanilla = Map.of(names.tray(), tray(names, "vanilla", readable), names.shelf(), shelf(names, "vanilla", carried));
		Map<String, byte[]> forge = Map.of(names.tray(), tray(names, "forge", readable), names.shelf(), shelf(names, "forge", carried));
		Map<String, byte[]> neo = Map.of(names.tray(), tray(names, "neo", readable), names.shelf(), shelf(names, "neo", carried));
		// The hooks are the sides' runtime classes: the pass follows a carried call into what its hook does.
		Map<String, byte[]> forgeRuntime = Map.of(names.forgeHook(), hook(names.forgeHook())), neoRuntime = Map.of(names.neoHook(), hook(names.neoHook()));
		Map<String, ClassNode> merged = new HashMap<>(Map.of(names.tray(), node(tray(names, "merged", readable)), names.shelf(),
				node(shelf(names, "merged", carried))));
		Set<String> changed = new HashSet<>();
		List<DelegateTwinRoutes.Decision> decisions = DelegateTwinRoutes.route(merged, vanilla, forge, neo, forgeRuntime, neoRuntime, changed);
		Map<String, byte[]> out = new HashMap<>();
		for (var entry : merged.entrySet()) {
			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
			entry.getValue().accept(writer);
			out.put(entry.getKey(), writer.toByteArray());
		}
		return new Result(decisions, out, names);
	}

	/**
	 * {@code Tray}: {@code fire(a, n)}; the first side's {@code fire(a, n, mode)} records "out" and releases when the mode is on
	 * (or, unreadable, hands the mode to a field nobody tests); {@code glaze(a)} calls it.
	 */
	private static byte[] tray(Names names, String side, boolean readable) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, names.tray(), null, "java/lang/Object", null);
		constructor(cw);
		if (!readable) cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "mode", "Z", null, null).visitEnd();
		MethodVisitor entry = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, names.fire(), ENTRY, null, null);
		entry.visitCode();
		if (side.equals("forge")) {
			entry.visitVarInsn(Opcodes.ALOAD, 0);
			entry.visitVarInsn(Opcodes.ILOAD, 1);
			entry.visitInsn(Opcodes.ICONST_1);
			entry.visitMethodInsn(Opcodes.INVOKESTATIC, names.tray(), names.fire(), TWIN, false);
		} else {
			recordCall(entry, "out");
			if (side.equals("neo") || side.equals("merged")) entry.visitMethodInsn(Opcodes.INVOKESTATIC, names.neoHook(), "release", "()V", false);
			else recordCall(entry, "release");
		}
		entry.visitInsn(Opcodes.RETURN);
		entry.visitMaxs(0, 0);
		entry.visitEnd();
		if (side.equals("forge") || side.equals("merged")) {
			MethodVisitor twin = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, names.fire(), TWIN, null, null);
			twin.visitCode();
			recordCall(twin, "out");
			twin.visitVarInsn(Opcodes.ILOAD, 2);
			if (readable) {
				Label skip = new Label();
				twin.visitJumpInsn(Opcodes.IFEQ, skip);
				recordCall(twin, "release");
				twin.visitLabel(skip);
			} else {
				twin.visitFieldInsn(Opcodes.PUTSTATIC, names.tray(), "mode", "Z");
			}
			twin.visitInsn(Opcodes.RETURN);
			twin.visitMaxs(0, 0);
			twin.visitEnd();
		}
		MethodVisitor glaze = cw.visitMethod(Opcodes.ACC_PUBLIC, names.glaze(), "(Ljava/lang/Object;)V", null, null);
		glaze.visitCode();
		glaze.visitVarInsn(Opcodes.ALOAD, 1);
		glaze.visitInsn(Opcodes.ICONST_3);
		if (side.equals("forge") || side.equals("merged")) {
			glaze.visitInsn(Opcodes.ICONST_0);
			glaze.visitMethodInsn(Opcodes.INVOKESTATIC, names.tray(), names.fire(), TWIN, false);
		} else {
			glaze.visitMethodInsn(Opcodes.INVOKESTATIC, names.tray(), names.fire(), ENTRY, false);
		}
		glaze.visitInsn(Opcodes.RETURN);
		glaze.visitMaxs(0, 0);
		glaze.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** {@code Shelf.bake(tray, a)}: the first side adds compensate and extra after glazing; the merged body carries one of them. */
	private static byte[] shelf(Names names, String side, Carried carried) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, names.shelf(), null, "java/lang/Object", null);
		constructor(cw);
		MethodVisitor bake = cw.visitMethod(Opcodes.ACC_PUBLIC, names.bake(), "(L" + names.tray() + ";Ljava/lang/Object;)V", null, null);
		bake.visitCode();
		if (side.equals("neo") || side.equals("merged")) bake.visitMethodInsn(Opcodes.INVOKESTATIC, names.neoHook(), "observe", "()V", false);
		bake.visitVarInsn(Opcodes.ALOAD, 1);
		bake.visitVarInsn(Opcodes.ALOAD, 2);
		bake.visitMethodInsn(Opcodes.INVOKEVIRTUAL, names.tray(), names.glaze(), "(Ljava/lang/Object;)V", false);
		boolean compensate = side.equals("forge") || (side.equals("merged") && carried == Carried.COMPENSATION);
		boolean extra = side.equals("forge") || (side.equals("merged") && carried != Carried.COMPENSATION);
		if (compensate) bake.visitMethodInsn(Opcodes.INVOKESTATIC, names.forgeHook(), "compensate", "()V", false);
		if (extra) bake.visitMethodInsn(Opcodes.INVOKESTATIC, names.forgeHook(), "extra", "()V", false);
		bake.visitInsn(Opcodes.RETURN);
		bake.visitMaxs(0, 0);
		bake.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A platform hook class: {@code compensate} and {@code release} record their family's name; {@code observe} and {@code extra} do nothing. */
	private static byte[] hook(String owner) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
		String family = owner.startsWith("net/minecraftforge/") ? "forge" : "neo";
		for (String name : List.of("compensate", "release", "observe", "extra")) {
			MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "()V", null, null);
			m.visitCode();
			if (name.equals("compensate") || name.equals("release")) recordCall(m, family + "-" + name);
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

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}
}
