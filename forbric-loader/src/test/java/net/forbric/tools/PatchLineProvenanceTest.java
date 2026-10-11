/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

public class PatchLineProvenanceTest {

	private static final String SOURCE = "net/minecraft/Example.java";

	private static PatchLineProvenance of(String patch) {
		return PatchLineProvenance.of(Map.of(SOURCE, patch));
	}

	@Test
	void unifiedHunkMapsContextAddedAndShiftedLines() {
		PatchLineProvenance p = of("--- a/X\n+++ b/X\n@@ -10,3 +10,4 @@\n a\n+added\n b\n c\n");
		assertEquals(5, p.origin(SOURCE, 5).vanilla());
		assertEquals(10, p.origin(SOURCE, 10).vanilla());
		assertTrue(p.origin(SOURCE, 11).added());
		assertEquals(11, p.origin(SOURCE, 12).vanilla());
		assertEquals(12, p.origin(SOURCE, 13).vanilla());
		assertEquals(99, p.origin(SOURCE, 100).vanilla(), "lines after the hunk shift back by what it added");
	}

	@Test
	void diffPatchUnderscoreStartsFollowTheRunningOffset() {
		String patch = "--- a/X\n+++ b/X\n"
				+ "@@ -10,2 +_,4 @@\n a\n+one\n+two\n b\n"
				+ "@@ -50,2 +_,3 @@\n c\n+three\n d\n";
		PatchLineProvenance p = of(patch);
		assertTrue(p.origin(SOURCE, 11).added());
		assertTrue(p.origin(SOURCE, 12).added());
		assertEquals(11, p.origin(SOURCE, 13).vanilla());
		assertEquals(49, p.origin(SOURCE, 51).vanilla());
		assertEquals(50, p.origin(SOURCE, 52).vanilla(), "the second hunk starts at old line + 2");
		assertTrue(p.origin(SOURCE, 53).added());
		assertEquals(51, p.origin(SOURCE, 54).vanilla());
		assertEquals(97, p.origin(SOURCE, 100).vanilla());
	}

	@Test
	void aReplacementRecordsTheVanillaLinesItRemoved() {
		PatchLineProvenance p = of("--- a/X\n+++ b/X\n@@ -10,4 +10,3 @@\n a\n-b\n-c\n+bc\n d\n");
		PatchLineProvenance.Origin added = p.origin(SOURCE, 11);
		assertTrue(added.added());
		assertEquals(List.of(11, 12), added.block().removedVanilla());
		assertEquals(10, added.block().vanillaBefore());
		assertEquals(13, added.block().vanillaAfter());
		assertEquals(13, p.origin(SOURCE, 12).vanilla());
	}

	@Test
	void anUnpatchedFileIsVanillaLineForLine() {
		PatchLineProvenance p = of("--- a/X\n+++ b/X\n@@ -1,1 +1,2 @@\n a\n+b\n");
		assertEquals(40, p.origin("net/minecraft/Other.java", 40).vanilla());
		assertFalse(p.origin("net/minecraft/Other.java", 40).added());
	}

	@Test
	void sourcePathJoinsPackageAndSourceFile() {
		assertEquals("net/minecraft/world/Foo.java", PatchLineProvenance.sourcePath("net/minecraft/world/Foo$Inner", "Foo.java"));
		assertNull(PatchLineProvenance.sourcePath("net/minecraft/world/Foo", null));
	}

	@Test
	void blocksCarryTheirLinesTextAndHunk() {
		String patch = "--- a/X\n+++ b/X\n"
				+ "@@ -10,4 +10,4 @@\n a\n-    moved(this);\n b\n+    hook();\n c\n"
				+ "@@ -40,2 +40,3 @@\n d\n+    moved(this);\n e\n";
		List<PatchLineProvenance.Block> blocks = of(patch).blocks(SOURCE);
		assertEquals(3, blocks.size());
		PatchLineProvenance.Block removal = blocks.get(0), hook = blocks.get(1), move = blocks.get(2);
		assertTrue(removal.deletionOnly());
		assertEquals(List.of("moved(this);"), removal.removedText(), "the text without its indentation");
		assertEquals(List.of(11), removal.removedVanilla());
		assertFalse(hook.deletionOnly());
		assertEquals("hook();", hook.addedText(12));
		assertNull(hook.addedText(13));
		assertEquals(removal.hunk(), hook.hunk(), "edits of one hunk sit side by side");
		assertNotEquals(hook.hunk(), move.hunk());
		assertEquals("moved(this);", move.addedText(41), "a line removed in one place and added in another is moved");
	}

	@Test
	void aFileTheSideNeverPatchedHasNoBlocks() {
		assertEquals(List.of(), of("--- a/X\n+++ b/X\n@@ -1,1 +1,2 @@\n a\n+b\n").blocks("net/minecraft/Other.java"));
	}
}
