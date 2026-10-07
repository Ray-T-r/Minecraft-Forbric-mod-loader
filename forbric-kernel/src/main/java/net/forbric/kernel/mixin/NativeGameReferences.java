/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Function;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.classloading.ForbricClassLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/** Reads hash-pinned, non-executable original platform bytes emitted by the merged-base builder. */
public final class NativeGameReferences {
	private static volatile NativeGameReferences active;
	private final Function<String, byte[]> resources;
	private final Map<Ecosystem, Map<String, String>> indexes = new EnumMap<>(Ecosystem.class);

	public NativeGameReferences(Function<String, byte[]> resources) {
		this.resources = resources;
	}

	static void bind(ForbricClassLoader loader) {
		if (loader == null) {
			active = null;
			return;
		}
		active = new NativeGameReferences(path -> {
			try (var stream = loader.getGameResourceAsStream(path)) {
				return stream == null ? null : stream.readAllBytes();
			} catch (IOException unavailable) {
				return null;
			}
		});
	}

	static ClassNode reference(Ecosystem ecosystem, String owner) {
		NativeGameReferences reader = active;
		return reader == null ? null : reader.get(ecosystem, owner);
	}

	public synchronized ClassNode get(Ecosystem ecosystem, String owner) {
		if (ecosystem == null || owner == null) return null;
		String expected = indexes.computeIfAbsent(ecosystem, this::index).get(owner);
		if (expected == null) return null;
		byte[] bytes = resources.apply(prefix(ecosystem) + owner + ".class.bin");
		if (bytes == null) bytes = resources.apply(owner + ".class");
		if (bytes == null || !expected.equals(hash(bytes))) return null;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
		return owner.equals(node.name) ? node : null;
	}

	private Map<String, String> index(Ecosystem ecosystem) {
		byte[] bytes = resources.apply(prefix(ecosystem) + "index.tsv");
		if (bytes == null) return Map.of();
		String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\\R");
		if (lines.length == 0 || !lines[0].equals("# forbric-native-reference-v1")) return Map.of();
		Map<String, String> result = new HashMap<>();
		for (int i = 1; i < lines.length; i++) {
			if (lines[i].isBlank()) continue;
			String[] parts = lines[i].split("\\t", -1);
			if (parts.length != 2 || !parts[1].matches("[a-f0-9]{64}")
					|| result.putIfAbsent(parts[0], parts[1]) != null) return Map.of();
		}
		return Map.copyOf(result);
	}

	private static String prefix(Ecosystem ecosystem) {
		return "META-INF/forbric/native-reference/" + ecosystem.name() + "/";
	}

	private static String hash(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException impossible) {
			throw new AssertionError(impossible);
		}
	}
}
