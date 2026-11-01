/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * No creative-search reader on NeoForge's base joins a future it looked up with {@code getOrDefault}.
 *
 * <p>The merged base carried MinecraftForge's {@code SessionSearchTrees.getSearchTree(Key)},
 * {@code creativeSearch.getOrDefault(key, EMPTY).join()}, over a map nothing filled and a default future nothing
 * completed: a MinecraftForge reader of a creative search tree hung the thread it asked on. {@code
 * CreativeSearchTreesInjector} repaired that until Stage 4 deleted it. NeoForge's {@code SessionSearchTrees} has no
 * {@code getSearchTree}; its readers are its own keyed {@code creativeNameSearch}/{@code creativeTagSearch}, which the
 * creative screen calls. This holds the repair's own defect criterion over the whole base and the NeoForge carrier, not
 * only its old anchor: a method that joins, as a {@code CompletableFuture}, a value it took from
 * {@code Map.getOrDefault} within the five instructions before. The merged base had one (getSearchTree); NeoForge's
 * base and carrier have none. gate-m55 holds the creative screen's behaviour.
 */
class NeoForgeBaseCreativeSearchStagedTest {
	private static final Path STAGED = TestFixtures.stagedRoot();
	private static final Path BASE = STAGED.resolve(TestFixtures.BASE_JAR);
	private static final Path NEO_CARRIER = STAGED.resolve(TestFixtures.NEO_CARRIER_JAR);
	private static final String TREES = "net/minecraft/client/multiplayer/SessionSearchTrees";
	private static final String KEY = "L" + TREES + "$Key;";
	/** How far after the getOrDefault the join may sit: the merged reader had a checkcast between them. */
	private static final int WINDOW = 5;

	@Test void noClassInTheBaseOrTheCarrierJoinsAFutureItLookedUpWithGetOrDefault() throws Exception {
		List<String> joins = new ArrayList<>();
		int classes = 0;
		for (Path jar : List.of(BASE, NEO_CARRIER)) {
			TestFixtures.requireFile(Fixture.STAGED, jar, jar + " absent");
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements();) {
					ZipEntry entry = e.nextElement();
					if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/")) continue;
					try (InputStream in = zip.getInputStream(entry)) {
						joins.addAll(joinsAfterGetOrDefault(node(in.readAllBytes())));
						classes++;
					}
				}
			}
		}
		assertTrue(classes > 10_000, "read the whole base and carrier, not " + classes + " classes");
		assertEquals(List.of(), joins, "a reader joins a future it looked up with getOrDefault");
	}

	/** The old anchor is gone and NeoForge's own keyed readers are there. */
	@Test void sessionSearchTreesHasNoGetSearchTreeAndKeepsNeoForgesKeyedReaders() {
		ClassNode trees = node(NativeCoremodParityTest.read(BASE, TREES));
		assertFalse(trees.methods.stream().anyMatch(m -> m.name.equals("getSearchTree")), "MinecraftForge's getSearchTree");
		for (String reader : List.of("creativeNameSearch", "creativeTagSearch")) {
			assertTrue(trees.methods.stream().anyMatch(m -> m.name.equals(reader) && m.desc.startsWith("(" + KEY + ")")),
					"NeoForge's " + reader + "(Key)");
		}
	}

	/** Control: the criterion finds the merged reader's shape, so an empty answer above is not a blind scanner. */
	@Test void controlTheCriterionFindsTheMergedReadersShape() {
		assertEquals(List.of("fixture/MergedTrees.getSearchTree(Ljava/lang/Object;)Ljava/lang/Object;"),
				joinsAfterGetOrDefault(node(mergedShapedReader("fixture/MergedTrees"))));
	}

	static List<String> joinsAfterGetOrDefault(ClassNode node) {
		List<String> found = new ArrayList<>();
		for (MethodNode method : node.methods) {
			AbstractInsnNode[] code = method.instructions.toArray();
			for (int k = 0; k < code.length; k++) {
				if (!(code[k] instanceof MethodInsnNode lookup) || !lookup.name.equals("getOrDefault")) continue;
				for (int j = k + 1; j < Math.min(code.length, k + 1 + WINDOW); j++) {
					if (code[j] instanceof MethodInsnNode join && join.owner.equals("java/util/concurrent/CompletableFuture")
							&& join.name.equals("join")) {
						found.add(node.name + "." + method.name + method.desc);
					}
				}
			}
		}
		return found;
	}

	/** {@code ((CompletableFuture) trees.getOrDefault(key, null)).join()}: the merged getSearchTree, javap'd. */
	private static byte[] mergedShapedReader(String name) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PRIVATE, "trees", "Ljava/util/Map;", null, null).visitEnd();
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "getSearchTree", "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitFieldInsn(Opcodes.GETFIELD, name, "trees", "Ljava/util/Map;");
		mv.visitVarInsn(Opcodes.ALOAD, 1);
		mv.visitInsn(Opcodes.ACONST_NULL);
		mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "getOrDefault",
				"(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
		mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/concurrent/CompletableFuture");
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/CompletableFuture", "join", "()Ljava/lang/Object;", false);
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return node;
	}
}
