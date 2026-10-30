/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.installer.kernel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * The vanilla-reference sidecar on synthetic jars: one row per vanilla class with its vanilla hash, vanilla bytes only for
 * the classes the base changed or lacks, no class files, the same bytes from the same inputs, and reuse only while both
 * inputs are the ones it records. The staged proof that it equals what the merged base embeds is the kernel's
 * (VanillaReferenceStagedTest); this one runs with no game inputs, in linkGateTest.
 */
public final class VanillaReferenceTest {
	public static void main(String[] args) throws Exception {
		Path work = Files.createDirectories(Path.of(args[0]));
		Path vanilla = jar(work.resolve("vanilla.jar"), Map.of(
				"game/Same.class", bytes("same"), "game/Changed.class", bytes("vanilla"), "game/Dropped.class", bytes("gone"),
				"version.json", bytes("{}")));
		Path base = jar(work.resolve("base.jar"), Map.of(
				"game/Same.class", bytes("same"), "game/Changed.class", bytes("patched"), "game/Added.class", bytes("new")));

		Path out = work.resolve("out/" + VanillaReference.fileName("26.2"));
		VanillaReference.Result built = VanillaReference.ensure(vanilla, base, out);
		require(!built.reused() && built.classes() == 3 && built.differing() == 2, "counts: " + built);
		Map<String, byte[]> entries = read(out);
		String index = new String(entries.get(VanillaReference.INDEX), StandardCharsets.UTF_8);
		List<String> expected = new ArrayList<>(List.of(VanillaReference.INDEX_HEADER));
		for (String name : List.of("game/Changed", "game/Dropped", "game/Same")) {
			expected.add(name + "\t" + VanillaReference.sha256(bytes(name.equals("game/Changed") ? "vanilla"
					: name.equals("game/Dropped") ? "gone" : "same")));
		}
		require(index.equals(String.join("\n", expected) + "\n"), "the index is every vanilla class in name order:\n" + index);
		require(new String(entries.get(VanillaReference.PREFIX + "game/Changed.class.bin"), StandardCharsets.UTF_8)
				.equals("vanilla"), "a class the base changed carries its vanilla bytes");
		require(entries.containsKey(VanillaReference.PREFIX + "game/Dropped.class.bin"),
				"a class the base lacks carries its vanilla bytes");
		require(!entries.containsKey(VanillaReference.PREFIX + "game/Same.class.bin"),
				"a class the base holds unchanged is a hash-only row");
		require(entries.keySet().stream().noneMatch(name -> name.endsWith(".class")),
				"no class files: on the classpath it must be inert " + entries.keySet());
		require(entries.keySet().stream().noneMatch(name -> name.contains("Added")), "a class only the base has is no row");

		Path again = work.resolve("again/" + VanillaReference.fileName("26.2"));
		VanillaReference.ensure(vanilla, base, again);
		require(Files.mismatch(out, again) == -1, "the same inputs give the same bytes, so the profile's sha1 is stable");

		require(VanillaReference.ensure(vanilla, base, out).reused(), "a sidecar from these inputs is reused");
		jar(base, Map.of("game/Same.class", bytes("same, rebuilt"), "game/Changed.class", bytes("patched")));
		VanillaReference.Result rebuilt = VanillaReference.ensure(vanilla, base, out);
		require(!rebuilt.reused() && rebuilt.differing() == 3, "a rebuilt base is measured again: " + rebuilt);

		try {
			VanillaReference.ensure(jar(work.resolve("empty.jar"), Map.of("version.json", bytes("{}"))), base,
					work.resolve("refused.jar"));
			throw new AssertionError("a vanilla jar without classes was accepted");
		} catch (IOException expectedRefusal) {
			require(expectedRefusal.getMessage().contains("holds no classes"), expectedRefusal.getMessage());
		}
		System.out.println("PASS vanilla reference: vanilla rows, copies only where the base differs, deterministic, "
				+ "reused only for its own inputs");
	}

	private static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	private static Path jar(Path file, Map<String, byte[]> entries) throws IOException {
		Files.createDirectories(file.getParent());
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
			for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
				zip.putNextEntry(new ZipEntry(entry.getKey()));
				zip.write(entry.getValue());
				zip.closeEntry();
			}
		}
		return file;
	}

	private static Map<String, byte[]> read(Path jar) throws IOException {
		Map<String, byte[]> out = new LinkedHashMap<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (var entries = zip.entries(); entries.hasMoreElements(); ) {
				ZipEntry entry = entries.nextElement();
				try (InputStream in = zip.getInputStream(entry)) {
					out.put(entry.getName(), in.readAllBytes());
				}
			}
		}
		return out;
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
