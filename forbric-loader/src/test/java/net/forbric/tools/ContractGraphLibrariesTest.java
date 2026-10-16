/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

/**
 * The merge's proofs read exactly the libraries they are handed -- the base version's own, each jar named, in the
 * order its version JSON lists them -- and nothing else. They used to be handed a directory and to take the first jar
 * in sorted path order that held a class: in a player's libraries folder that was {@code guava-25.1-jre}, sitting
 * beside 26.2's own {@code guava-33.6.0-jre}, so a merge's verdict depended on what else a launcher had installed.
 */
class ContractGraphLibrariesTest {
	@TempDir Path directory;

	private static final String PROBE = "lib/Probe";

	@Test
	void theNamedJarAnswersNotOneThatSortsBeforeItInTheSameFolder() throws Exception {
		Path stale = jar("libraries/com/example/lib/1.0/lib-1.0.jar", "stale");
		Path named = jar("libraries/com/example/lib/9.0/lib-9.0.jar", "named");
		assertTrue(stale.compareTo(named) < 0, "the fixture needs the stale jar to sort first");
		assertEquals("named", marker(new ContractGraph(List.of(Map.of()), ContractGraph.libraries(named.toString()))));
	}

	@Test
	void theListsOrderDecidesNotThePathsOrder() throws Exception {
		Path first = jar("libraries/z/lib-z.jar", "listed first"), second = jar("libraries/a/lib-a.jar", "listed second");
		String argument = first + File.pathSeparator + second;
		assertEquals(List.of(first, second), ContractGraph.libraries(argument));
		assertEquals("listed first", marker(new ContractGraph(List.of(Map.of()), ContractGraph.libraries(argument))));
	}

	/** Negative control: a directory is not read by whatever sorts first in it; it is refused. */
	@Test
	void aDirectoryIsRefusedRatherThanWalked() throws Exception {
		jar("libraries/com/example/lib/1.0/lib-1.0.jar", "stale");
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
				() -> ContractGraph.libraries(directory.resolve("libraries").toString()));
		assertTrue(refused.getMessage().contains("not a library list"), refused.getMessage());
	}

	@Test
	void aNamedJarThatIsNotThereIsAnErrorNotAClassTheProofQuietlyLacks() {
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
				() -> ContractGraph.libraries(directory.resolve("gone.jar").toString()));
		assertTrue(refused.getMessage().contains("library not found"), refused.getMessage());
	}

	@Test
	void aNamedJarThatCannotBeReadIsAnErrorNotAMiss() throws Exception {
		Path broken = Files.writeString(directory.resolve("broken.jar"), "not a zip");
		ContractGraph graph = new ContractGraph(List.of(Map.of()), ContractGraph.libraries(broken.toString()));
		assertThrows(UncheckedIOException.class, () -> graph.node(PROBE));
	}

	@Test
	void noLibrariesMeansOnlyTheSuppliedSetsAndTheJdk() {
		assertEquals(List.of(), ContractGraph.libraries(""));
		ContractGraph graph = new ContractGraph(List.of(Map.of()), ContractGraph.libraries(""));
		assertNull(graph.node(PROBE));
		assertEquals(java.util.Set.of(PROBE), graph.takeMisses());
		assertNotNull(graph.node("java/lang/Object"));
	}

	private static String marker(ContractGraph graph) {
		ClassNode node = graph.node(PROBE);
		assertNotNull(node, "the probe class was not read from the libraries");
		return node.fields.stream().map(f -> (String) f.value).findFirst().orElseThrow();
	}

	/** A jar holding one class, {@link #PROBE}, whose one constant field says which jar it came from. */
	private Path jar(String relative, String marker) throws Exception {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V17;
		node.access = Opcodes.ACC_PUBLIC;
		node.name = PROBE;
		node.superName = "java/lang/Object";
		node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "WHICH",
				"Ljava/lang/String;", null, marker));
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		Path jar = directory.resolve(relative);
		Files.createDirectories(jar.getParent());
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
			out.putNextEntry(new ZipEntry(PROBE + ".class"));
			out.write(writer.toByteArray());
			out.closeEntry();
		}
		return jar;
	}
}
