import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shortest strong paths from GC roots to every live instance of a class, in an HPROF heap dump.
 *
 * <p>Usage: {@code java -Xmx6g HeapPaths.java <dump.hprof> <target.class.Name> [maxPaths] [excluded Owner.field ...]
 * [--mod-owned <modsDir> <gameJar> ...]}. Weak/soft/phantom referents are not strong edges. Each reported path ends
 * at a distinct root or static field, so a second holder is not hidden behind the shortest one; excluded fields are
 * treated as already cut.
 *
 * <p>{@code --mod-owned} additionally cuts every edge a mod owns: fields and statics of classes shipped in a mod jar
 * (nested jars included; a lambda counts as its host class), and fields a mod's Mixin added to a game class (the
 * name is declared by some mod class and NOT by that class in the given game jars). It then prints one
 * {@code VERDICT <id> REACHABLE|UNREACHABLE} line per target: UNREACHABLE means every strong path to it passes
 * through state a mod keeps, none only through the game, a carrier or Forbric.
 */
public final class HeapPaths {
	private static int idSize;
	private static final Map<Long, String> strings = new HashMap<>();
	private static final Map<Long, Long> classNameIds = new HashMap<>();
	private record ClassInfo(long superId, long[] refOffsets, String[] refNames, int instanceSize, long[] staticIds, String[] staticNames) { }
	private static final Map<Long, ClassInfo> classes = new HashMap<>();
	private static final Map<Long, String> rootKinds = new HashMap<>();
	// id -> node index (open addressing)
	private static long[] keys = new long[1 << 20]; private static int[] vals = new int[1 << 20]; private static int used;
	private static long[] nodeIds = new long[1 << 20], nodeOffsets = new long[1 << 20]; private static long[] nodeClass = new long[1 << 20];
	private static byte[] nodeKind = new byte[1 << 20]; // 0 instance, 1 object array, 2 class
	private static int nodes;

	public static void main(String[] args) throws IOException {
		Path dump = Path.of(args[0]); String target = args[1];
		int maxPaths = args.length > 2 ? Integer.parseInt(args[2]) : 20;
		List<String> rest = new ArrayList<>(Arrays.asList(args).subList(Math.min(3, args.length), args.length));
		int split = rest.indexOf("--mod-owned");
		if (split >= 0) { ModOwnership.load(rest.subList(split + 1, rest.size())); rest = rest.subList(0, split); }
		Set<String> excluded = new HashSet<>(rest);
		long started = System.nanoTime();
		scan(dump, false);
		System.out.printf("pass1: %d nodes, %d classes, %d roots (%.1fs)%n", nodes, classes.size(), rootKinds.size(), (System.nanoTime() - started) / 1e9);
		// reverse edges: for each node, which nodes reference it
		excludedFields = excluded;
		int[][] edges = edges(dump, excluded);
		int[] count = new int[nodes + 1];
		for (int i = 0; i < edgeCount; i++) count[edges[0][i] + 1]++;
		for (int i = 0; i < nodes; i++) count[i + 1] += count[i];
		int[] from = new int[edgeCount]; int[] fill = Arrays.copyOf(count, nodes);
		for (int i = 0; i < edgeCount; i++) from[fill[edges[0][i]]++] = edges[1][i];
		edges = null;
		System.out.printf("pass2: %d strong edges (%.1fs)%n", edgeCount, (System.nanoTime() - started) / 1e9);
		List<Integer> targets = new ArrayList<>();
		for (int i = 0; i < nodes; i++) if (nodeKind[i] == 0 && target.equals(className(nodeClass[i]))) targets.add(i);
		System.out.println("targets " + targets.size());
		try (RandomAccessFile file = new RandomAccessFile(dump.toFile(), "r")) {
			for (int t : targets) {
				System.out.println("== " + target + "@" + Long.toHexString(nodeIds[t]));
				int[] next = new int[nodes]; Arrays.fill(next, -2); next[t] = -1;
				ArrayDeque<Integer> queue = new ArrayDeque<>(); queue.add(t); int reported = 0;
				Set<String> terminals = new HashSet<>();
				while (!queue.isEmpty() && reported < maxPaths) {
					int child = queue.poll();
					String rootKind = rootKinds.get(nodeIds[child]);
					if (rootKind != null && terminals.add("root " + rootKind + "@" + child)) { print(file, child, next, "ROOT " + rootKind); reported++; continue; }
					for (int k = count[child]; k < count[child + 1]; k++) {
						int parent = from[k];
						if (next[parent] != -2) continue;
						next[parent] = child;
						if (nodeKind[parent] == 2) {
							String field = staticField(nodeIds[parent], nodeIds[child]);
							String label = "static " + className(nodeIds[parent]) + "." + field;
							if (terminals.add(label)) { print(file, parent, next, label); reported++; }
							continue;
						}
						queue.add(parent);
					}
				}
			}
		}
		if (ModOwnership.active()) {
			for (int t : targets) {
				boolean[] seen = new boolean[nodes]; ArrayDeque<Integer> queue = new ArrayDeque<>(); queue.add(t); seen[t] = true; boolean reachable = false;
				while (!queue.isEmpty() && !reachable) {
					int child = queue.poll();
					if (rootKinds.containsKey(nodeIds[child])) { reachable = true; break; }
					for (int k = count[child]; k < count[child + 1]; k++) {
						int parent = from[k];
						if (seen[parent]) continue;
						seen[parent] = true;
						if (nodeKind[parent] == 2) { reachable = true; break; }
						queue.add(parent);
					}
				}
				System.out.println("VERDICT " + Long.toHexString(nodeIds[t]) + " " + (reachable ? "REACHABLE" : "UNREACHABLE"));
			}
			System.out.println("CUT-FIELDS " + cutCache.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).sorted().toList().size());
		}
		System.out.printf("done (%.1fs)%n", (System.nanoTime() - started) / 1e9);
	}

	private static void print(RandomAccessFile file, int start, int[] next, String head) throws IOException {
		StringBuilder line = new StringBuilder(head);
		for (int node = start; next[node] >= 0; node = next[node]) {
			int child = next[node];
			line.append("\n    ").append(describe(node)).append(" --").append(label(file, node, nodeIds[child])).append("--> ");
		}
		System.out.println(line);
	}
	private static String describe(int node) {
		return switch (nodeKind[node]) { case 2 -> "class " + className(nodeIds[node]); case 1 -> className(nodeClass[node]); default -> className(nodeClass[node]); };
	}
	private static String label(RandomAccessFile file, int node, long child) throws IOException {
		if (nodeKind[node] == 2) return "static " + staticField(nodeIds[node], child);
		file.seek(nodeOffsets[node]);
		if (nodeKind[node] == 1) {
			int n = file.readInt(); file.skipBytes(idSize);
			for (int i = 0; i < n; i++) if (readId(file) == child) return "[" + i + "]";
			return "[?]";
		}
		long cls = nodeClass[node]; int length = file.readInt(); byte[] data = new byte[length]; file.readFully(data);
		long[] offsets = absOffsets.get(cls); String[] names = absNames.get(cls);
		for (int i = 0; offsets != null && i < offsets.length; i++) if (offsets[i] + idSize <= length && id(data, (int) offsets[i]) == child) return names[i];
		return "?";
	}
	private static String staticField(long cls, long child) {
		ClassInfo info = classes.get(cls);
		if (info != null) for (int i = 0; i < info.staticIds().length; i++) if (info.staticIds()[i] == child) return info.staticNames()[i];
		return "?";
	}
	private static String className(long cls) { Long name = classNameIds.get(cls); return name == null ? "class@" + Long.toHexString(cls) : strings.getOrDefault(name, "?").replace('/', '.'); }

	private static int edgeCount;
	private static Set<String> excludedFields = Set.of();
	private static final Map<String, Boolean> cutCache = new HashMap<>();
	private static boolean cut(String label) {
		if (excludedFields.contains(label)) return true;
		if (!ModOwnership.active()) return false;
		return cutCache.computeIfAbsent(label, ModOwnership::owned);
	}
	private static int[] edgeTo = new int[1 << 24], edgeFrom = new int[1 << 24];
	private static void edge(int child, int parent) {
		if (child < 0) return;
		if (edgeCount == edgeTo.length) { edgeTo = Arrays.copyOf(edgeTo, edgeCount * 2); edgeFrom = Arrays.copyOf(edgeFrom, edgeCount * 2); }
		edgeTo[edgeCount] = child; edgeFrom[edgeCount++] = parent;
	}
	private static int[][] edges(Path dump, Set<String> excluded) throws IOException {
		for (long cls : classes.keySet()) layout(cls);
		for (int node = 0; node < nodes; node++) {
			if (nodeKind[node] != 2) continue;
			ClassInfo info = classes.get(nodeIds[node]);
			if (info == null) continue;
			for (int i = 0; i < info.staticIds().length; i++)
				if (!excluded.contains(className(nodeIds[node]) + "." + info.staticNames()[i])
						&& !cut(className(nodeIds[node]) + "." + info.staticNames()[i])) edge(index(info.staticIds()[i]), node);
		}
		scan(dump, true);  // a second sequential pass is far cheaper than a seek per object
		return new int[][] { edgeTo, edgeFrom };
	}
	private static final Map<Long, long[]> absOffsets = new HashMap<>();
	private static final Map<Long, String[]> absNames = new HashMap<>();
	/** Instance data holds the class's own fields first, then each superclass's; Reference.referent is not strong. */
	private static void layout(long cls) {
		List<Long> offsets = new ArrayList<>(); List<String> names = new ArrayList<>(); long offset = 0;
		for (long c = cls; c != 0 && classes.containsKey(c); c = classes.get(c).superId()) {
			ClassInfo info = classes.get(c);
			for (int i = 0; i < info.refOffsets().length; i++) {
				String name = info.refNames()[i];
				if (!name.equals("java.lang.ref.Reference.referent")) { offsets.add(offset + info.refOffsets()[i]); names.add(name); }
			}
			offset += info.instanceSize();
		}
		absOffsets.put(cls, offsets.stream().mapToLong(Long::longValue).toArray());
		absNames.put(cls, names.toArray(String[]::new));
	}

	private static void scan(Path dump, boolean emit) throws IOException {
		try (InputStream raw = Files.newInputStream(dump); CountingStream counting = new CountingStream(new BufferedInputStream(raw, 1 << 24)); DataInputStream in = new DataInputStream(counting)) {
			while (in.readByte() != 0) { }
			idSize = in.readInt(); in.readLong();
			while (true) {
				int tag; try { tag = in.readUnsignedByte(); } catch (EOFException end) { break; }
				in.readInt(); long length = Integer.toUnsignedLong(in.readInt());
				long end = counting.position + length;
				if (emit && tag != 0x0C && tag != 0x1C) { in.skipNBytes(length); continue; }
				if (tag == 0x01) { long id = readId(in); byte[] text = in.readNBytes((int) (length - idSize)); strings.put(id, new String(text, java.nio.charset.StandardCharsets.UTF_8)); }
				else if (tag == 0x02) { in.readInt(); long cls = readId(in); in.readInt(); classNameIds.put(cls, readId(in)); }
				else if (tag == 0x0C || tag == 0x1C) {
					while (counting.position < end) {
						int sub = in.readUnsignedByte();
						switch (sub) {
							case 0xFF -> root(readId(in), "unknown");
							case 0x01 -> { root(readId(in), "jni-global"); readId(in); }
							case 0x02 -> { root(readId(in), "jni-local"); in.readInt(); in.readInt(); }
							case 0x03 -> { root(readId(in), "java-frame"); in.readInt(); in.readInt(); }
							case 0x04 -> { root(readId(in), "native-stack"); in.readInt(); }
							case 0x05 -> root(readId(in), "sticky-class");
							case 0x06 -> { root(readId(in), "thread-block"); in.readInt(); }
							case 0x07 -> root(readId(in), "monitor");
							case 0x08 -> { root(readId(in), "thread-object"); in.readInt(); in.readInt(); }
							case 0x20 -> classDump(in, emit);
							case 0x21 -> {
								long id = readId(in); in.readInt(); long cls = readId(in); long at = counting.position; int n = in.readInt();
								if (!emit) { in.skipNBytes(n); node(id, at, cls, (byte) 0); continue; }
								long[] offsets = absOffsets.get(cls);
								if (offsets == null || offsets.length == 0) { in.skipNBytes(n); continue; }
								byte[] data = in.readNBytes(n); int parent = index(id);
								String[] names = absNames.get(cls);
								for (int f = 0; f < offsets.length; f++)
									if (offsets[f] + idSize <= n && !cut(names[f])) edge(index(id(data, (int) offsets[f])), parent);
							}
							case 0x22 -> {
								long id = readId(in); in.readInt(); long at = counting.position; int n = in.readInt(); long cls = readId(in);
								if (!emit) { in.skipNBytes((long) n * idSize); node(id, at, cls, (byte) 1); continue; }
								byte[] data = in.readNBytes(n * idSize); int parent = index(id);
								for (int i = 0; i < n; i++) edge(index(id(data, i * idSize)), parent);
							}
							case 0x23 -> { readId(in); in.readInt(); int n = in.readInt(); int type = in.readUnsignedByte(); in.skipNBytes((long) n * size(type)); }
							default -> throw new IOException("unknown heap sub-record 0x" + Integer.toHexString(sub) + " at " + counting.position);
						}
					}
				} else in.skipNBytes(length);
			}
		}
	}
	private static void classDump(DataInputStream in, boolean emit) throws IOException {
		long cls = readId(in); in.readInt(); long superId = readId(in);
		for (int i = 0; i < 5; i++) readId(in);
		int instanceSize = in.readInt();
		int constants = in.readUnsignedShort();
		for (int i = 0; i < constants; i++) { in.readUnsignedShort(); int type = in.readUnsignedByte(); in.skipNBytes(size(type)); }
		int statics = in.readUnsignedShort(); List<Long> staticIds = new ArrayList<>(); List<String> staticNames = new ArrayList<>();
		for (int i = 0; i < statics; i++) {
			long name = readId(in); int type = in.readUnsignedByte();
			if (type == 2) { staticIds.add(readId(in)); staticNames.add(strings.getOrDefault(name, "?")); } else in.skipNBytes(size(type));
		}
		int fields = in.readUnsignedShort(); List<Long> offsets = new ArrayList<>(); List<String> names = new ArrayList<>(); long offset = 0;
		for (int i = 0; i < fields; i++) {
			long name = readId(in); int type = in.readUnsignedByte();
			if (type == 2) { offsets.add(offset); names.add(className(cls) + "." + strings.getOrDefault(name, "?")); }
			offset += size(type);
		}
		if (emit) return;
		classes.put(cls, new ClassInfo(superId, offsets.stream().mapToLong(Long::longValue).toArray(), names.toArray(String[]::new), (int) offset,
				staticIds.stream().mapToLong(Long::longValue).toArray(), staticNames.toArray(String[]::new)));
		node(cls, -1, 0, (byte) 2);
	}
	private static void root(long id, String kind) { rootKinds.putIfAbsent(id, kind); }
	private static void node(long id, long offset, long cls, byte kind) {
		if (nodes == nodeIds.length) { int n = nodes * 2; nodeIds = Arrays.copyOf(nodeIds, n); nodeOffsets = Arrays.copyOf(nodeOffsets, n); nodeClass = Arrays.copyOf(nodeClass, n); nodeKind = Arrays.copyOf(nodeKind, n); }
		nodeIds[nodes] = id; nodeOffsets[nodes] = offset; nodeClass[nodes] = cls; nodeKind[nodes] = kind;
		put(id, nodes++);
	}
	private static void put(long key, int value) {
		if (used * 2 >= keys.length) { long[] oldKeys = keys; int[] oldVals = vals; keys = new long[oldKeys.length * 2]; vals = new int[oldKeys.length * 2]; used = 0; for (int i = 0; i < oldKeys.length; i++) if (oldKeys[i] != 0) put(oldKeys[i], oldVals[i]); }
		int mask = keys.length - 1, slot = (int) (mix(key) & mask);
		while (keys[slot] != 0 && keys[slot] != key) slot = (slot + 1) & mask;
		if (keys[slot] == 0) used++;
		keys[slot] = key; vals[slot] = value;
	}
	private static int index(long key) {
		if (key == 0) return -1;
		int mask = keys.length - 1, slot = (int) (mix(key) & mask);
		while (keys[slot] != 0) { if (keys[slot] == key) return vals[slot]; slot = (slot + 1) & mask; }
		return -1;
	}
	private static long mix(long key) { key ^= key >>> 33; key *= 0xff51afd7ed558ccdL; key ^= key >>> 33; return key; }
	private static int size(int type) { return switch (type) { case 2 -> idSize; case 4, 8 -> 1; case 5, 9 -> 2; case 6, 10 -> 4; case 7, 11 -> 8; default -> throw new IllegalArgumentException("type " + type); }; }
	private static long readId(java.io.DataInput in) throws IOException { return idSize == 8 ? in.readLong() : Integer.toUnsignedLong(in.readInt()); }
	private static long id(byte[] data, int at) {
		if (idSize == 4) return ((data[at] & 0xFFL) << 24) | ((data[at + 1] & 0xFFL) << 16) | ((data[at + 2] & 0xFFL) << 8) | (data[at + 3] & 0xFFL);
		long value = 0; for (int i = 0; i < 8; i++) value = (value << 8) | (data[at + i] & 0xFFL); return value;
	}
	/** Which classes and Mixin-added field names come from mod jars, and which fields the game jars declare. */
	static final class ModOwnership {
		private static final Set<String> modClasses = new HashSet<>(), modFieldNames = new HashSet<>();
		private static final List<java.util.zip.ZipFile> gameJars = new ArrayList<>();
		private static final Map<String, Set<String>> gameFields = new HashMap<>();
		private static boolean active;
		private static int unreadableModClasses;
		static boolean active() { return active; }
		static void load(List<String> args) throws IOException {
			active = true;
			try (var stream = Files.list(Path.of(args.get(0)))) {
				for (Path jar : stream.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) scanMod(Files.readAllBytes(jar));
			}
			for (String jar : args.subList(1, args.size())) gameJars.add(new java.util.zip.ZipFile(jar));
			System.out.println("mod-owned: " + modClasses.size() + " mod classes, " + modFieldNames.size() + " mod field names, " + gameJars.size() + " game jars, " + unreadableModClasses + " unreadable mod classes");
		}
		private static void scanMod(byte[] jar) throws IOException {
			try (var in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(jar))) {
				for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
					String name = entry.getName();
					if (name.endsWith(".jar")) scanMod(in.readAllBytes());
					else if (name.endsWith(".class") && !name.startsWith("META-INF/")) {
						byte[] bytes = in.readAllBytes();
						modClasses.add(name.substring(0, name.length() - 6).replace('/', '.'));
						// An unreadable mod class contributes no field names: fewer cuts, so a verdict can only get stricter.
						try { modFieldNames.addAll(fields(bytes)); } catch (RuntimeException unreadable) { unreadableModClasses++; }
					}
				}
			}
		}
		/** label = DeclaringClass.field, as HeapPaths builds it. */
		static boolean owned(String label) {
			int dot = label.lastIndexOf('.');
			String cls = label.substring(0, dot), field = label.substring(dot + 1);
			int lambda = cls.indexOf("$$Lambda");
			String host = lambda >= 0 ? cls.substring(0, lambda) : cls;
			if (modClasses.contains(host)) return true;
			if (!modFieldNames.contains(field)) return false;
			Set<String> declared = gameFields.computeIfAbsent(host, ModOwnership::gameDeclared);
			return declared != null && !declared.contains(field);
		}
		private static Set<String> gameDeclared(String cls) {
			String entry = cls.replace('.', '/') + ".class";
			for (var jar : gameJars) {
				var found = jar.getEntry(entry);
				if (found != null) try (var in = jar.getInputStream(found)) { return new HashSet<>(fields(in.readAllBytes())); } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
			}
			return null; // not a game class (JDK, library, Forbric): never attributed to a mod by name
		}
		/** Field names declared by one class file (constant pool walk, no bytecode library). */
		static List<String> fields(byte[] b) {
			java.nio.ByteBuffer in = java.nio.ByteBuffer.wrap(b);
			if (in.getInt() != 0xCAFEBABE) return List.of();
			in.getShort(); in.getShort();
			int count = in.getShort() & 0xFFFF; String[] utf = new String[count];
			for (int i = 1; i < count; i++) {
				int tag = in.get() & 0xFF;
				switch (tag) {
					case 1 -> { int len = in.getShort() & 0xFFFF; byte[] s = new byte[len]; in.get(s); utf[i] = new String(s, java.nio.charset.StandardCharsets.UTF_8); }
					case 3, 4 -> in.getInt();
					case 5, 6 -> { in.getLong(); i++; }
					case 7, 8, 16, 19, 20 -> in.getShort();
					case 9, 10, 11, 12, 17, 18 -> in.getInt();
					case 15 -> { in.get(); in.getShort(); }
					default -> { return List.of(); }
				}
			}
			in.getShort(); in.getShort(); in.getShort();
			int interfaces = in.getShort() & 0xFFFF; for (int i = 0; i < interfaces; i++) in.getShort();
			int fields = in.getShort() & 0xFFFF; List<String> names = new ArrayList<>();
			for (int i = 0; i < fields; i++) {
				in.getShort(); names.add(utf[in.getShort() & 0xFFFF]); in.getShort();
				int attributes = in.getShort() & 0xFFFF;
				for (int a = 0; a < attributes; a++) { in.getShort(); int length = in.getInt(); in.position(in.position() + length); }
			}
			return names;
		}
	}
	private static final class CountingStream extends java.io.FilterInputStream {
		long position;
		CountingStream(InputStream in) { super(in); }
		@Override public int read() throws IOException { int b = super.read(); if (b >= 0) position++; return b; }
		@Override public int read(byte[] b, int off, int len) throws IOException { int n = super.read(b, off, len); if (n > 0) position += n; return n; }
		@Override public long skip(long n) throws IOException { long s = super.skip(n); position += s; return s; }
	}
}
