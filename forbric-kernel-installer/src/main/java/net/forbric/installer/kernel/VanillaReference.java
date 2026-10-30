/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.installer.kernel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Builds the vanilla-reference sidecar: a resources-only jar that tells the kernel how vanilla Minecraft compiled each
 * of its classes, measured against the base the instance actually runs on.
 *
 * <p><b>What it is for.</b> Fabric mods are written against vanilla bytecode, and the kernel's mixin adapters decide
 * what to do with a Fabric mixin by comparing the vanilla body it was written against with the body the base has
 * (the kernel's {@code NativeGameReferences}, ecosystem {@code FABRIC}). That evidence used to ride inside the merged
 * base: {@code MergedBaseBuilder.writeNativeReferences} embedded one {@code META-INF/forbric/native-reference/<ECO>/}
 * tree per ecosystem. A base that is not that merge — the genuine NeoForge-patched jar the project is moving to —
 * embeds nothing, and every adapter that compares against vanilla then stands down without a word: measured on the
 * genuine base, fabric-api alone left 13 vanilla datapack registries empty, and a resources-only jar with the FABRIC
 * half brought it back to Done. This class is that jar, built from the two files it is a function of, so it can sit
 * beside any base rather than inside one.
 *
 * <p><b>The format is the embedded one, byte for byte</b> — the FABRIC half of {@code writeNativeReferences}, so the
 * kernel reads both through the same code and a test can hold the two side by side: one
 * {@code META-INF/forbric/native-reference/FABRIC/index.tsv} listing every class of the vanilla jar with the SHA-256 of
 * its vanilla bytes, and a {@code <class>.class.bin} copy of the vanilla bytes for exactly those classes whose bytes
 * differ from the base's. A class the base holds unchanged is a hash-only row, answered from the base itself and
 * accepted only if it hashes to the row. A sidecar from another base therefore costs evidence (a changed class with no
 * copy is no answer), never gives wrong evidence.
 *
 * <p>It also records {@value #PROVENANCE}: the SHA-1 of both inputs, which is how a later run knows it is current. The
 * pins stamp the other artifacts use is not enough here — this jar is a function of the base's bytes, and the base can
 * be rebuilt (or supplied with {@code --artifacts}) without a pin moving.
 *
 * <p><b>Why it is self-contained.</b> It needs nothing but the JDK — a zip, a byte comparison and SHA-256 — and is kept
 * that way on purpose: the installer runs it in-process (no 4 GB subprocess, no ASM), the development launch scripts run
 * this one file as a single-file source program ({@code java VanillaReference.java <vanilla> <base> <out>}) when their
 * staged tree has no sidecar, and the kernel's staged tests compile it to prove that what it writes equals what the
 * merged base embeds.
 */
public final class VanillaReference {

	/** The coordinate the installer stages it under, without its version. */
	public static final String COORDINATE = "net.forbric:vanilla-reference";

	/** The one ecosystem whose references this jar carries: what Fabric mods are compiled against. */
	static final String PREFIX = "META-INF/forbric/native-reference/FABRIC/";
	static final String INDEX = PREFIX + "index.tsv";
	static final String INDEX_HEADER = "# forbric-native-reference-v1";
	/** What the jar was built from; not read by the kernel, which trusts only the per-class hashes. */
	static final String PROVENANCE = "META-INF/forbric/vanilla-reference.properties";

	/**
	 * Every entry carries this one time, so the same two inputs give the same bytes, and so the same sha1 in the profile,
	 * on every machine and in every time zone ({@link ZipEntry#setTimeLocal} writes it as given).
	 */
	private static final LocalDateTime FIXED_TIME = LocalDateTime.of(2000, 1, 1, 0, 0);

	private VanillaReference() {
	}

	/** The file name the builders and the development scripts give it. */
	public static String fileName(String mcVersion) {
		return "vanilla-reference-" + mcVersion + ".jar";
	}

	/**
	 * What one call produced.
	 *
	 * @param classes   vanilla classes indexed, or -1 when the existing file was current and not reopened
	 * @param differing how many of them differ from the base and so carry their vanilla bytes, or -1 as above
	 * @param reused    true when {@code file} was already built from these two inputs
	 */
	public record Result(Path file, int classes, int differing, boolean reused) {
	}

	/**
	 * Returns {@code out} built from {@code vanilla} against {@code base}, reusing it when its recorded inputs are these.
	 * Written to a sibling and moved into place, so an interrupted run never leaves a half jar that looks current, and two
	 * runs building it at once each move a whole one.
	 */
	public static Result ensure(Path vanilla, Path base, Path out) throws IOException {
		String vanillaSha1 = sha1(vanilla);
		String baseSha1 = sha1(base);
		if (current(out, vanillaSha1, baseSha1)) return new Result(out, -1, -1, true);
		Files.createDirectories(out.toAbsolutePath().getParent());
		// This process's own part file: two launches of one checkout (a gate's server and client) may build at once.
		Path part = out.resolveSibling(out.getFileName() + "." + ProcessHandle.current().pid() + ".part");
		int[] counts;
		try (ZipFile vanillaJar = new ZipFile(vanilla.toFile()); ZipFile baseJar = new ZipFile(base.toFile());
				OutputStream file = Files.newOutputStream(part)) {
			Map<String, ZipEntry> vanillaClasses = classes(vanillaJar);
			if (vanillaClasses.isEmpty()) throw new IOException(vanilla + " holds no classes; it is not Minecraft's jar");
			if (classes(baseJar).isEmpty()) throw new IOException(base + " holds no classes; it is not a game base");
			counts = write(file, name -> read(vanillaJar, vanillaClasses.get(name)), vanillaClasses.keySet(),
					name -> read(baseJar, baseJar.getEntry(name + ".class")), vanillaSha1, baseSha1);
		}
		Files.move(part, out, StandardCopyOption.REPLACE_EXISTING);
		return new Result(out, counts[0], counts[1], false);
	}

	/** One jar's class bytes by internal name, or null when it has no such class. */
	@FunctionalInterface
	interface ClassBytes {
		byte[] get(String name) throws IOException;
	}

	/**
	 * Writes the sidecar for the vanilla classes {@code names} measured against {@code base} and returns
	 * {@code {indexed, differing}}. The order and contents are {@code writeNativeReferences}' FABRIC half: classes in
	 * name order, each one's vanilla bytes where they differ from the base's (absent counts as different), then the
	 * index. Neither jar is held in memory: the merged base alone is a few hundred megabytes of class files.
	 */
	static int[] write(OutputStream target, ClassBytes vanilla, java.util.Collection<String> names, ClassBytes base,
			String vanillaSha1, String baseSha1) throws IOException {
		int differing = 0;
		StringBuilder index = new StringBuilder(INDEX_HEADER).append('\n');
		try (ZipOutputStream zip = new ZipOutputStream(target)) {
			for (String name : new TreeSet<>(names)) {
				byte[] bytes = vanilla.get(name);
				index.append(name).append('\t').append(sha256(bytes)).append('\n');
				if (Arrays.equals(bytes, base.get(name))) continue;
				put(zip, PREFIX + name + ".class.bin", bytes);
				differing++;
			}
			put(zip, INDEX, index.toString().getBytes(StandardCharsets.UTF_8));
			put(zip, PROVENANCE, ("format=1\nvanilla-sha1=" + vanillaSha1 + "\nbase-sha1=" + baseSha1 + "\n")
					.getBytes(StandardCharsets.UTF_8));
		}
		return new int[] {names.size(), differing};
	}

	/** True when {@code out} exists and records exactly these two inputs. */
	static boolean current(Path out, String vanillaSha1, String baseSha1) {
		if (!Files.isRegularFile(out)) return false;
		try (ZipFile zip = new ZipFile(out.toFile())) {
			ZipEntry provenance = zip.getEntry(PROVENANCE);
			if (provenance == null || zip.getEntry(INDEX) == null) return false;
			Map<String, String> recorded = new HashMap<>();
			try (InputStream in = zip.getInputStream(provenance)) {
				for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
					int eq = line.indexOf('=');
					if (eq > 0) recorded.put(line.substring(0, eq), line.substring(eq + 1).strip());
				}
			}
			return "1".equals(recorded.get("format")) && vanillaSha1.equals(recorded.get("vanilla-sha1"))
					&& baseSha1.equals(recorded.get("base-sha1"));
		} catch (IOException unreadable) {
			// A file that does not open as this jar is not current; it is rebuilt over.
			return false;
		}
	}

	/**
	 * Every class entry of {@code jar}, by internal name: the same selection {@code MergedBaseBuilder.readClasses} makes,
	 * so a class the merge indexed is a class this indexes.
	 */
	static Map<String, ZipEntry> classes(ZipFile jar) {
		Map<String, ZipEntry> out = new HashMap<>();
		for (Enumeration<? extends ZipEntry> entries = jar.entries(); entries.hasMoreElements(); ) {
			ZipEntry entry = entries.nextElement();
			if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
			out.putIfAbsent(entry.getName().substring(0, entry.getName().length() - ".class".length()), entry);
		}
		return out;
	}

	private static byte[] read(ZipFile jar, ZipEntry entry) throws IOException {
		if (entry == null) return null;
		try (InputStream in = jar.getInputStream(entry)) {
			return in.readAllBytes();
		}
	}

	private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
		ZipEntry entry = new ZipEntry(name);
		entry.setTimeLocal(FIXED_TIME);
		zip.putNextEntry(entry);
		zip.write(bytes);
		zip.closeEntry();
	}

	static String sha256(byte[] bytes) {
		return HexFormat.of().formatHex(digest("SHA-256").digest(bytes));
	}

	static String sha1(Path file) throws IOException {
		MessageDigest digest = digest("SHA-1");
		try (InputStream in = Files.newInputStream(file)) {
			byte[] buffer = new byte[1 << 16];
			for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private static MessageDigest digest(String algorithm) {
		try {
			return MessageDigest.getInstance(algorithm);
		} catch (NoSuchAlgorithmException impossible) {
			throw new AssertionError(impossible);
		}
	}

	/**
	 * {@code VanillaReference <vanilla.jar> <base.jar> <out.jar>}: ensures the sidecar and prints its path on stdout,
	 * nothing else, so a script can take it with {@code $(...)}. What it did goes to stderr.
	 */
	public static void main(String[] args) throws IOException {
		if (args.length != 3) {
			System.err.println("usage: VanillaReference <vanilla.jar> <base.jar> <out.jar>");
			System.exit(2);
		}
		Result result = ensure(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
		System.err.println(result.reused()
				? "[vanilla-reference] up-to-date: " + result.file()
				: "[vanilla-reference] wrote " + result.file() + ": " + result.classes() + " vanilla classes, "
						+ result.differing() + " differ from the base");
		System.out.println(result.file());
	}
}
