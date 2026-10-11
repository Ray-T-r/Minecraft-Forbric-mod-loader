/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.tools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Which source line of a patched game class each line number came from: an unchanged vanilla line, or a line one
 * ecosystem's own source patch added.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Both patched games are recompiled from patched sources, and both keep their LineNumberTable, so every
 * instruction already carries the PATCHED source line it was compiled from. The ecosystem's userdev artifact ships
 * the very patches that produced those sources. Walking a patch turns a patched line number back into the vanilla
 * line it was (an unchanged line), or into the edit block that introduced it (an added line). That is the fact the
 * merge needs and could not see before: which instructions of a method are that ecosystem's edit, and which are
 * vanilla it left alone — exactly, from the patch author's own record, rather than inferred from a body diff across
 * two different compiles.
 *
 * <p>Two hunk dialects are read: MinecraftForge's plain unified diff ({@code @@ -a,b +c,d @@}) and NeoForge's
 * DiffPatch form, which writes the new start as {@code _} ({@code @@ -a,b +_,d @@}); there the new position is
 * recomputed from the running offset, which is also how DiffPatch applies it.
 */
final class PatchLineProvenance {

	/**
	 * One contiguous run of removed and/or added lines inside a hunk, with the source text of each line as the patch
	 * writes it (stripped of its indentation): a line removed in one place and added back in another is the same
	 * statement moved, by the patch author's own record.
	 */
	record Block(int id, List<Integer> removedVanilla, List<Integer> addedPatched, int vanillaBefore, int vanillaAfter,
			List<String> removedText, List<String> addedText, int hunk) {
		Block(int id, List<Integer> removedVanilla, List<Integer> addedPatched, int vanillaBefore, int vanillaAfter) {
			this(id, removedVanilla, addedPatched, vanillaBefore, vanillaAfter, List.of(), List.of(), -1);
		}

		boolean insertionOnly() {
			return removedVanilla.isEmpty();
		}

		/** Removes vanilla lines and adds nothing in their place. */
		boolean deletionOnly() {
			return !removedVanilla.isEmpty() && addedPatched.isEmpty();
		}

		/** The text of added patched line {@code line}, or null when this block did not add it. */
		String addedText(int line) {
			int i = addedPatched.indexOf(line);
			return i < 0 || i >= addedText.size() ? null : addedText.get(i);
		}
	}

	/** A patched line's origin: {@code vanilla > 0} is the vanilla line, otherwise {@code block} is set. */
	record Origin(int vanilla, Block block) {
		boolean added() {
			return block != null;
		}
	}

	/** Every file this side patched, keyed by source path ({@code net/minecraft/.../Foo.java}). */
	private final Map<String, FileMap> files;

	private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+|_)(?:,(\\d+|_))? @@");

	private PatchLineProvenance(Map<String, FileMap> files) {
		this.files = files;
	}

	static PatchLineProvenance empty() {
		return new PatchLineProvenance(Map.of());
	}

	/** Reads every {@code patches/**}{@code .java.patch} entry of a userdev artifact. */
	static PatchLineProvenance fromUserdev(Path userdev) throws IOException {
		Map<String, FileMap> files = new HashMap<>();
		try (ZipFile zip = new ZipFile(userdev.toFile())) {
			for (ZipEntry entry : Collections.list(zip.entries())) {
				String name = entry.getName();
				if (!name.startsWith("patches/") || !name.endsWith(".java.patch")) continue;
				String source = name.substring("patches/".length(), name.length() - ".patch".length());
				try (InputStream in = zip.getInputStream(entry)) {
					files.put(source, FileMap.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
				}
			}
		}
		return new PatchLineProvenance(files);
	}

	/** For tests: one patch text per source path. */
	static PatchLineProvenance of(Map<String, String> patches) {
		Map<String, FileMap> files = new HashMap<>();
		patches.forEach((source, text) -> files.put(source, FileMap.parse(text)));
		return new PatchLineProvenance(files);
	}

	int patchedFiles() {
		return files.size();
	}

	boolean patches(String source) {
		return files.containsKey(source);
	}

	/** Every block this side's patch of {@code source} has, in patch order; empty for a file it never patched. */
	List<Block> blocks(String source) {
		FileMap map = files.get(source);
		return map == null ? List.of() : List.copyOf(map.blocks);
	}

	/**
	 * The origin of {@code patchedLine} in {@code source}. A file the side never patched is vanilla line for line.
	 * Line numbers below 1 have no origin.
	 */
	Origin origin(String source, int patchedLine) {
		if (patchedLine < 1) return null;
		FileMap map = files.get(source);
		if (map == null) return new Origin(patchedLine, null);
		return map.origin(patchedLine);
	}

	/** The source path a class was compiled from, or null when the class does not name its source file. */
	static String sourcePath(String internalName, String sourceFile) {
		if (sourceFile == null) return null;
		int slash = internalName.lastIndexOf('/');
		return (slash < 0 ? "" : internalName.substring(0, slash + 1)) + sourceFile;
	}

	static final class FileMap {
		/** Lines inside hunks: patched line -> vanilla line (unchanged context). */
		private final Map<Integer, Integer> context = new HashMap<>();
		/** Lines inside hunks: patched line -> the block that added it. */
		private final Map<Integer, Block> added = new HashMap<>();
		/** Outside hunks: the first patched line of a region -> (patched - vanilla) for that region. */
		private final TreeMap<Integer, Integer> offsets = new TreeMap<>();
		private final List<Block> blocks = new ArrayList<>();
		/** The hunk being read: blocks of one hunk are edits the patch author made side by side. */
		private int hunk = -1;

		static FileMap parse(String text) {
			FileMap map = new FileMap();
			map.offsets.put(1, 0);
			int delta = 0;
			int oldLine = -1, newLine = -1;
			boolean inHunk = false;
			List<Integer> removed = null, addedLines = null;
			List<String> removedText = new ArrayList<>(), addedText = new ArrayList<>();
			int before = 0;
			for (String line : text.split("\n", -1)) {
				Matcher hunk = HUNK.matcher(line);
				if (hunk.find()) {
					if (inHunk) {
						map.closeBlock(removed, addedLines, before, oldLine, removedText, addedText);
						delta = newLine - oldLine;
						map.offsets.put(newLine, delta);
					}
					removed = addedLines = null;
					removedText = new ArrayList<>();
					addedText = new ArrayList<>();
					map.hunk++;
					oldLine = Integer.parseInt(hunk.group(1));
					newLine = hunk.group(3).equals("_") ? oldLine + delta : Integer.parseInt(hunk.group(3));
					before = oldLine - 1;
					// The region between the previous hunk and this one keeps the previous delta; this hunk's own
					// lines are mapped one by one below.
					inHunk = true;
					continue;
				}
				if (!inHunk || line.startsWith("--- ") || line.startsWith("+++ ") || line.startsWith("\\")) continue;
				char kind = line.isEmpty() ? ' ' : line.charAt(0);
				if (kind == ' ') {
					if (removed != null || addedLines != null) {
						map.closeBlock(removed, addedLines, before, oldLine, removedText, addedText);
						removed = addedLines = null;
						removedText = new ArrayList<>();
						addedText = new ArrayList<>();
					}
					map.context.put(newLine, oldLine);
					before = oldLine;
					oldLine++;
					newLine++;
				} else if (kind == '-') {
					if (removed == null) removed = new ArrayList<>();
					removed.add(oldLine++);
					removedText.add(line.substring(1).strip());
				} else if (kind == '+') {
					if (addedLines == null) addedLines = new ArrayList<>();
					addedLines.add(newLine++);
					addedText.add(line.substring(1).strip());
				} else {
					// Anything else ends the hunk (trailing text, a new file header in a combined patch).
					map.closeBlock(removed, addedLines, before, oldLine, removedText, addedText);
					removed = addedLines = null;
					removedText = new ArrayList<>();
					addedText = new ArrayList<>();
					delta = newLine - oldLine;
					map.offsets.put(newLine, delta);
					inHunk = false;
				}
			}
			if (inHunk) {
				map.closeBlock(removed, addedLines, before, oldLine, removedText, addedText);
				map.offsets.put(newLine, newLine - oldLine);
			}
			return map;
		}

		private void closeBlock(List<Integer> removed, List<Integer> addedLines, int before, int after,
				List<String> removedText, List<String> addedText) {
			if (removed == null && addedLines == null) return;
			Block block = new Block(blocks.size(), removed == null ? List.of() : List.copyOf(removed),
					addedLines == null ? List.of() : List.copyOf(addedLines), before, after, List.copyOf(removedText),
					List.copyOf(addedText), hunk);
			blocks.add(block);
			for (int line : block.addedPatched()) added.put(line, block);
		}

		Origin origin(int patchedLine) {
			Block block = added.get(patchedLine);
			if (block != null) return new Origin(0, block);
			Integer vanilla = context.get(patchedLine);
			if (vanilla != null) return new Origin(vanilla, null);
			var region = offsets.floorEntry(patchedLine);
			return new Origin(patchedLine - (region == null ? 0 : region.getValue()), null);
		}
	}
}
