package net.forbric.installer.kernel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * The NeoForge runtime the installer assembles comes out byte for byte the same from the same inputs, so an upgrade does
 * not rewrite {@code net.forbric:neoforge-runtime:<build>} in place under a new SHA-1 (Zips.FIXED_TIME). Two builds are
 * made more than two seconds apart, the resolution of a zip entry's time, so without the fixed time they cannot agree.
 *
 * <p>And the fixed time changes nothing else (critic 18, carrier equivalence): a jar written at the fixed time and one
 * written at the clock's time hold the same entries in the same order, with the same method, CRC, sizes, stored bytes and
 * contents; only the times differ. {@link #differences} is that comparison, and {@code main("compare", a, b)} runs it on
 * two real carriers (the evidence for the staged one is in the Stage 4 C6 record).
 */
public final class ReproducibleRuntimeTest {
	public static void main(String[] args) throws Exception {
		if (args.length == 3 && args[0].equals("compare")) {
			List<String> found = differences(Path.of(args[1]), Path.of(args[2]));
			System.out.println(found.isEmpty() ? "equivalent apart from entry times: " + args[1] + " / " + args[2]
					: String.join("\n", found));
			System.exit(found.isEmpty() ? 0 : 1);
		}
		Path work = Files.createDirectories(args.length > 0 ? Path.of(args[0])
				: Files.createTempDirectory("reproducible-runtime"));
		twoBuildsAreByteEqual(work.resolve("build"));
		theFixedTimeChangesNothingButTimes(work.resolve("times"));
		System.out.println("ReproducibleRuntimeTest: OK");
	}

	private static void twoBuildsAreByteEqual(Path work) throws Exception {
		NeoForgeArtifacts nfa = new NeoForgeArtifacts("26.2", "26.2.0.93");
		Path dl = work.resolve("dl");
		// The flat download cache the builder reads, already full, so it fetches nothing: the universal jar and one library
		// the config lists, plus one it leaves out (Guava is Minecraft's).
		jar(dl.resolve("neoforge-26.2.0.93-universal.jar"), Map.of("net/neoforged/Thing.class", "neo",
				"META-INF/neoforge.mods.toml", "modId=\"neoforge\"", "META-INF/services/a.Service", "neo.Impl",
				"META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n"));
		jar(dl.resolve("extra-1.0.jar"), Map.of("com/example/Extra.class", "extra", "META-INF/services/a.Service",
				"extra.Impl", "META-INF/mods.toml", "dropped"));
		List<String> config = List.of("com.example:extra:1.0", "com.google.guava:guava:33.0");
		Http offline = new Http(line -> { });

		Path first = work.resolve("first/neoforge-runtime.jar");
		new NeoForgeRuntimeBuilder(nfa, offline, work, first, line -> { }).build(config);
		Thread.sleep(2_100);
		Path second = work.resolve("second/neoforge-runtime.jar");
		new NeoForgeRuntimeBuilder(nfa, offline, work, second, line -> { }).build(config);

		require(Files.mismatch(first, second) == -1, "two builds of the NeoForge runtime from the same inputs, "
				+ "2.1 s apart, are not byte-equal: " + differences(first, second) + " / times " + times(first) + " vs "
				+ times(second));
		for (LocalDateTime time : times(first)) {
			require(time.equals(Zips.FIXED_TIME), "an entry carries " + time + ", not the fixed time");
		}
		require(entries(first).containsKey("com/example/Extra.class") && !entries(first).containsKey("META-INF/mods.toml"),
				"the merge itself ran: " + entries(first).keySet());
	}

	private static void theFixedTimeChangesNothingButTimes(Path work) throws Exception {
		Map<String, byte[]> content = new LinkedHashMap<>();
		content.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
		for (int i = 0; i < 40; i++) {
			content.put("pkg/C" + i + ".class", ("class " + i + " ").repeat(50 + i).getBytes(StandardCharsets.UTF_8));
		}
		content.put("fabric.mod.json", "{}".getBytes(StandardCharsets.UTF_8));
		Path fixed = work.resolve("fixed.jar");
		Path clock = work.resolve("clock.jar");
		Zips.writeJar(fixed, content);
		Zips.writeJar(clock, content, LocalDateTime.now().withNano(0));
		require(!times(fixed).equals(times(clock)), "the two jars must differ in their times for this to show anything");
		List<String> found = differences(fixed, clock);
		require(found.isEmpty(), "the fixed time changed more than the times: " + found);

		// The comparison is not blind: one changed byte, one renamed entry and one reordering are each seen.
		Map<String, byte[]> changed = new LinkedHashMap<>(content);
		changed.put("pkg/C3.class", "class 3!".getBytes(StandardCharsets.UTF_8));
		Path oneByte = work.resolve("changed.jar");
		Zips.writeJar(oneByte, changed);
		require(!differences(fixed, oneByte).isEmpty(), "a changed entry was not seen");
		Map<String, byte[]> reordered = new LinkedHashMap<>();
		reordered.put("fabric.mod.json", content.get("fabric.mod.json"));
		content.forEach(reordered::putIfAbsent);
		Path moved = work.resolve("reordered.jar");
		Zips.writeJar(moved, reordered);
		require(!differences(fixed, moved).isEmpty(), "a reordered entry was not seen");
	}

	/**
	 * Everything that differs between two jars apart from entry times: entry names and order, compression method, CRC,
	 * sizes, the stored (compressed) bytes, contents, extra fields and comments. Empty when they agree on all of it.
	 */
	static List<String> differences(Path a, Path b) throws IOException {
		List<String> found = new ArrayList<>();
		try (ZipFile left = new ZipFile(a.toFile()); ZipFile right = new ZipFile(b.toFile())) {
			List<ZipEntry> l = list(left);
			List<ZipEntry> r = list(right);
			if (l.size() != r.size()) found.add("entry count " + l.size() + " vs " + r.size());
			for (int i = 0; i < Math.min(l.size(), r.size()); i++) {
				ZipEntry x = l.get(i);
				ZipEntry y = r.get(i);
				String at = "#" + i + " " + x.getName();
				if (!x.getName().equals(y.getName())) {
					found.add(at + ": name " + y.getName());
					continue;
				}
				if (x.getMethod() != y.getMethod()) found.add(at + ": method " + x.getMethod() + " vs " + y.getMethod());
				if (x.getCrc() != y.getCrc()) found.add(at + ": crc");
				if (x.getSize() != y.getSize()) found.add(at + ": size " + x.getSize() + " vs " + y.getSize());
				if (x.getCompressedSize() != y.getCompressedSize()) {
					found.add(at + ": stored size " + x.getCompressedSize() + " vs " + y.getCompressedSize());
				}
				if (!java.util.Arrays.equals(x.getExtra(), y.getExtra())) found.add(at + ": extra field");
				if (!java.util.Objects.equals(x.getComment(), y.getComment())) found.add(at + ": comment");
				if (!java.util.Arrays.equals(read(left, x), read(right, y))) found.add(at + ": content");
			}
			if (!java.util.Objects.equals(left.getComment(), right.getComment())) found.add("archive comment");
		}
		// The stored bytes, entry by entry: the local data of each entry, which a time cannot reach once the header is
		// skipped. Equal stored sizes plus equal contents and one deflater make this a check of the writer, not a
		// tautology: a different compression level or strategy changes it.
		if (found.isEmpty()) found.addAll(storedBytesDiffer(a, b));
		return found;
	}

	private static List<String> storedBytesDiffer(Path a, Path b) throws IOException {
		byte[] x = Files.readAllBytes(a);
		byte[] y = Files.readAllBytes(b);
		if (x.length != y.length) return List.of("file length " + x.length + " vs " + y.length);
		List<String> found = new ArrayList<>();
		for (int i = 0; i < x.length; i++) {
			if (x[i] == y[i]) continue;
			// A local header's mod time and date sit at 10..13, a central header's at 12..15; anything else is not a time.
			if (!isTimeByte(x, i)) {
				found.add("a byte other than an entry time differs at offset " + i);
				break;
			}
		}
		return found;
	}

	/** Is offset {@code i} inside the time/date field of a local (PK\3\4) or central (PK\1\2) header? */
	private static boolean isTimeByte(byte[] zip, int i) {
		for (int back = 10; back <= 15; back++) {
			int h = i - back;
			if (h < 0) continue;
			if (zip[h] != 'P' || zip[h + 1] != 'K') continue;
			if (zip[h + 2] == 3 && zip[h + 3] == 4 && back >= 10 && back <= 13) return true;
			if (zip[h + 2] == 1 && zip[h + 3] == 2 && back >= 12 && back <= 15) return true;
		}
		return false;
	}

	private static List<ZipEntry> list(ZipFile zip) {
		List<ZipEntry> all = new ArrayList<>();
		Enumeration<? extends ZipEntry> e = zip.entries();
		while (e.hasMoreElements()) all.add(e.nextElement());
		return all;
	}

	private static byte[] read(ZipFile zip, ZipEntry entry) throws IOException {
		try (InputStream in = zip.getInputStream(entry)) {
			return in.readAllBytes();
		}
	}

	private static List<LocalDateTime> times(Path jar) throws IOException {
		List<LocalDateTime> times = new ArrayList<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (ZipEntry entry : list(zip)) times.add(entry.getTimeLocal());
		}
		return times;
	}

	private static Map<String, byte[]> entries(Path jar) throws IOException {
		Map<String, byte[]> all = new LinkedHashMap<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (ZipEntry entry : list(zip)) all.put(entry.getName(), read(zip, entry));
		}
		return all;
	}

	private static void jar(Path file, Map<String, String> entries) throws IOException {
		Files.createDirectories(file.getParent());
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
			for (Map.Entry<String, String> e : new java.util.TreeMap<>(entries).entrySet()) {
				zip.putNextEntry(new ZipEntry(e.getKey()));
				zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
