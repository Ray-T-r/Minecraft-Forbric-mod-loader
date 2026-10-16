import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.RecordComponentNode;

/**
 * The bytecode half of rename-mod.py: relocates a mod's classes by an explicit plan, and reads Mixin exports back.
 *
 * <p>{@code remap <plan.tsv> <jobs.tsv> <log.tsv>} rewrites every listed class file and JSON file. The plan names
 * each relocated class ({@code C old new}), package ({@code P}), moved resource ({@code R}) and mod id ({@code I}),
 * all in internal (slash) form, plus the mod-id string mode ({@code M modidStrings meta|lookups|all}). Every
 * reference to a planned class is relocated: descriptors, signatures, annotations, constants, handles; and so is
 * every string that names one, so Mixin's {@code @Mixin(targets)}, {@code @At(target)}, MixinExtras definitions,
 * {@code Class.forName} names and refmap entries follow the classes. A class that keeps its name and has no
 * reference to change is copied byte for byte.
 *
 * <p>{@code scan <plan.tsv> <jobs.tsv>} lists every constant of the written classes that still names an old
 * package or mod id, for auditing what the rename deliberately left (resource namespaces, translation keys).
 *
 * <p>{@code census <export dir> [mod.jar ...]} prints one TSV row per member Mixin merged into an exported class
 * ({@code -Dmixin.debug.export=true}): target, mixin, the handler's name in the mixin class (read from the given
 * jars, nested jars included), the merged name, descriptor, call sites in the target and their callers.
 */
public final class ModRename {
	private static final String MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";
	/** Loader methods whose String argument is a mod id; {@code lookups} mode follows the rename into these calls. */
	private static final Set<String> LOOKUPS = Set.of(
			"net/fabricmc/loader/api/FabricLoader.getModContainer", "net/fabricmc/loader/api/FabricLoader.isModLoaded",
			"net/neoforged/fml/ModList.isLoaded", "net/neoforged/fml/ModList.getModContainerById",
			"net/neoforged/fml/ModList.getModFileById", "net/neoforged/fml/loading/LoadingModList.getModFileById",
			"net/minecraftforge/fml/ModList.isLoaded", "net/minecraftforge/fml/ModList.getModContainerById",
			"net/minecraftforge/fml/ModList.getModFileById", "net/minecraftforge/fml/ModList.getModObjectById",
			"net/minecraftforge/fml/loading/LoadingModList.getModFileById");
	private static final Pattern DESC_TOKEN = Pattern.compile("L([A-Za-z_$][A-Za-z0-9_$]*(?:/[A-Za-z0-9_$]+)*);");

	private ModRename() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length >= 4 && args[0].equals("remap")) {
			remap(Plan.read(Path.of(args[1])), Path.of(args[2]), Path.of(args[3]));
		} else if (args.length >= 3 && args[0].equals("refs")) {
			refs(Files.readAllLines(Path.of(args[1]), StandardCharsets.UTF_8), Path.of(args[2]));
		} else if (args.length >= 3 && args[0].equals("scan")) {
			scan(Plan.read(Path.of(args[1])), Path.of(args[2]));
		} else if (args.length >= 2 && args[0].equals("census")) {
			census(Path.of(args[1]), Arrays.asList(args).subList(2, args.length));
		} else {
			System.err.println("usage: ModRename remap <plan.tsv> <jobs.tsv> <log.tsv> | refs <roots.txt> <jobs.tsv>"
					+ " | scan <plan.tsv> <jobs.tsv>"
					+ " | census <export dir> [mod.jar ...]");
			System.exit(2);
		}
	}

	/** The rename plan written by rename-mod.py. */
	static final class Plan {
		final Map<String, String> classes = new HashMap<>();
		final Map<String, String> packages = new HashMap<>();
		final Map<String, String> resources = new HashMap<>();
		final Map<String, String> ids = new HashMap<>();
		String modidStrings = "lookups";

		static Plan read(Path file) throws IOException {
			Plan plan = new Plan();
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				if (line.isEmpty() || line.startsWith("#")) continue;
				String[] f = line.split("\t", -1);
				switch (f[0]) {
					case "C" -> plan.classes.put(f[1], f[2]);
					case "P" -> plan.packages.put(f[1], f[2]);
					case "R" -> plan.resources.put(f[1], f[2]);
					case "I" -> plan.ids.put(f[1], f[2]);
					case "M" -> { if (f[1].equals("modidStrings")) plan.modidStrings = f[2]; }
					default -> throw new IOException("unknown plan line: " + line);
				}
			}
			return plan;
		}

		/** A string that names a planned class, package or moved resource, relocated; {@code s} itself otherwise. */
		String remapString(String s) {
			if (s.isEmpty() || s.length() > 4096) return s;
			String exact = exact(s);
			if (exact != null) return exact;
			String out = s;
			if (out.indexOf(';') > 0) {
				Matcher m = DESC_TOKEN.matcher(out);
				StringBuilder sb = null;
				int last = 0;
				while (m.find()) {
					String mapped = classes.get(m.group(1));
					if (mapped == null) continue;
					if (sb == null) sb = new StringBuilder();
					sb.append(out, last, m.start(1)).append(mapped);
					last = m.end(1);
				}
				if (sb != null) out = sb.append(out.substring(last)).toString();
			}
			return ownerPrefix(out);
		}

		private String exact(String s) {
			String m = classes.get(s);
			if (m != null) return m;
			boolean slashed = s.indexOf('/') >= 0;
			if (!slashed && s.indexOf('.') > 0) {
				m = classes.get(s.replace('.', '/'));
				if (m != null) return m.replace('/', '.');
			}
			if (slashed && s.indexOf('.') > 0 && s.indexOf('(') < 0 && s.indexOf(';') < 0) {
				// Kotlin metadata writes a nested class as "a/b/Outer.Inner".
				m = classes.get(s.replace('.', '$'));
				if (m != null) return nestedDots(m);
			}
			if (s.length() > 2 && s.charAt(0) == 'L' && s.endsWith(";") && s.indexOf(';') == s.length() - 1) {
				m = classes.get(s.substring(1, s.length() - 1));
				if (m != null) return "L" + m + ";";
			}
			String tail = "";
			String pkg = s;
			char end = s.charAt(s.length() - 1);
			if (end == '/' || end == '.') {
				tail = String.valueOf(end);
				pkg = s.substring(0, s.length() - 1);
			}
			boolean dotted = pkg.indexOf('/') < 0;
			String internal = dotted ? pkg.replace('.', '/') : pkg;
			// A one-segment package ("carpet") is indistinguishable from a word, an id or a translation key.
			if (internal.indexOf('/') > 0 && (!dotted || tail.isEmpty() || end == '.')) {
				m = packages.get(internal);
				if (m != null) return (dotted ? m.replace('/', '.') : m) + tail;
			}
			String lead = s.startsWith("/") ? "/" : "";
			m = resources.get(s.substring(lead.length()));
			if (m != null) return lead + m;
			return null;
		}

		private static String nestedDots(String mapped) {
			int slash = mapped.lastIndexOf('/');
			return mapped.substring(0, slash + 1) + mapped.substring(slash + 1).replace('$', '.');
		}

		/** "a/b/C.member", "a.b.C.member(...)", "a/b/C;member", "a.b.C::member": the class part relocated. */
		private String ownerPrefix(String s) {
			if (s.length() > 1024 || (s.indexOf('/') < 0 && s.indexOf('.') < 0)) return s;
			for (int i = s.length() - 1; i > 0; i--) {
				char c = s.charAt(i);
				if (c != '.' && c != ';' && c != ':' && c != '(') continue;
				String head = s.substring(0, i);
				String m = classes.get(head);
				if (m != null) return m + s.substring(i);
				if (head.indexOf('/') < 0 && head.indexOf('.') > 0) {
					m = classes.get(head.replace('.', '/'));
					if (m != null) return m.replace('/', '.') + s.substring(i);
				}
			}
			return s;
		}
	}

	/** Collects what one class's rewrite changed; the tool copies a class it did not change byte for byte. */
	static final class Names extends Remapper {
		final Plan plan;
		final PrintWriter log;
		String label = "";
		String where = "";
		boolean changed;

		Names(Plan plan, PrintWriter log) {
			super(Opcodes.ASM9);
			this.plan = plan;
			this.log = log;
		}

		@Override
		public String map(String internalName) {
			String m = plan.classes.get(internalName);
			if (m == null) return internalName;
			changed = true;
			return m;
		}

		@Override
		public String mapPackageName(String name) {
			String m = plan.packages.get(name);
			if (m == null) return name;
			changed = true;
			return m;
		}

		@Override
		public Object mapValue(Object value) {
			if (value instanceof String s) {
				String r = plan.remapString(s);
				if (!r.equals(s)) {
					changed = true;
					log.println("STRING\t" + label + "\t" + where + "\t" + esc(s) + "\t" + esc(r));
				}
				return r;
			}
			return super.mapValue(value);
		}
	}

	/** Also renames the SourceFile of a relocated class after its new outer name. */
	static final class Relocator extends ClassRemapper {
		private final Names names;
		private String oldName;
		private String newName;

		Relocator(ClassVisitor next, Names names) {
			super(Opcodes.ASM9, next, names);
			this.names = names;
		}

		@Override
		public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
			oldName = name;
			newName = names.plan.classes.getOrDefault(name, name);
			super.visit(version, access, name, signature, superName, interfaces);
		}

		@Override
		public void visitSource(String source, String debug) {
			if (source != null && !newName.equals(oldName)) {
				String simple = newName.substring(newName.lastIndexOf('/') + 1);
				int dollar = simple.indexOf('$');
				if (dollar > 0) simple = simple.substring(0, dollar);
				int dot = source.lastIndexOf('.');
				source = simple + (dot >= 0 ? source.substring(dot) : "");
			}
			super.visitSource(source, debug);
		}
	}

	static void remap(Plan plan, Path jobs, Path logFile) throws IOException {
		int classes = 0, changedClasses = 0, json = 0, changedJson = 0;
		try (PrintWriter log = new PrintWriter(Files.newBufferedWriter(logFile, StandardCharsets.UTF_8))) {
			Names names = new Names(plan, log);
			for (String line : Files.readAllLines(jobs, StandardCharsets.UTF_8)) {
				if (line.isEmpty()) continue;
				String[] f = line.split("\t", -1);
				Path in = Path.of(f[2]);
				Path out = Path.of(f[3]);
				byte[] bytes = Files.readAllBytes(in);
				byte[] result;
				boolean changed;
				if (f[0].equals("class")) {
					classes++;
					names.label = f[4];
					names.changed = false;
					result = remapClass(bytes, names, f[1].equals("own"));
					changed = names.changed;
					if (changed) changedClasses++;
				} else if (f[0].equals("json")) {
					json++;
					String text = new String(bytes, StandardCharsets.UTF_8);
					String mapped = remapJsonStrings(text, plan, log, f[4]);
					changed = !mapped.equals(text);
					result = changed ? mapped.getBytes(StandardCharsets.UTF_8) : bytes;
					if (changed) changedJson++;
				} else {
					throw new IOException("unknown job: " + line);
				}
				Files.createDirectories(out.getParent());
				Files.write(out, changed ? result : bytes);
				log.println((changed ? "CHANGED\t" : "SAME\t") + f[4]);
			}
		}
		System.out.println("ModRename: " + changedClasses + "/" + classes + " classes and " + changedJson + "/" + json
				+ " JSON files changed");
	}

	static byte[] remapClass(byte[] bytes, Names names, boolean own) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);
		names.where = cn.name;
		if (!names.plan.ids.isEmpty()) idStrings(cn, names, own);
		ClassWriter cw = new ClassWriter(0);
		// ClassRemapper calls mapValue for constants and annotation values; "where" names the class only.
		cn.accept(new Relocator(cw, names));
		return cw.toByteArray();
	}

	/** Mod-id strings: annotation values (own classes), lookup arguments, or every constant ({@code all}). */
	private static void idStrings(ClassNode cn, Names names, boolean own) {
		Plan plan = names.plan;
		boolean all = plan.modidStrings.equals("all");
		boolean lookups = all || plan.modidStrings.equals("lookups");
		if (own) {
			annotations(cn.visibleAnnotations, names, cn.name);
			annotations(cn.invisibleAnnotations, names, cn.name);
			if (cn.recordComponents != null) for (RecordComponentNode rc : cn.recordComponents) {
				annotations(rc.visibleAnnotations, names, cn.name + "." + rc.name);
				annotations(rc.invisibleAnnotations, names, cn.name + "." + rc.name);
			}
		}
		for (FieldNode f : cn.fields) {
			if (own) {
				annotations(f.visibleAnnotations, names, cn.name + "." + f.name);
				annotations(f.invisibleAnnotations, names, cn.name + "." + f.name);
			}
			if (all && f.value instanceof String s && plan.ids.containsKey(s)) {
				f.value = plan.ids.get(s);
				idLog(names, "ALL-ID", cn.name + "." + f.name, s);
			}
		}
		for (MethodNode m : cn.methods) {
			String where = cn.name + "." + m.name + m.desc;
			if (own) {
				annotations(m.visibleAnnotations, names, where);
				annotations(m.invisibleAnnotations, names, where);
				if (m.visibleParameterAnnotations != null) for (List<AnnotationNode> l : m.visibleParameterAnnotations) annotations(l, names, where);
				if (m.invisibleParameterAnnotations != null) for (List<AnnotationNode> l : m.invisibleParameterAnnotations) annotations(l, names, where);
				if (m.annotationDefault != null) m.annotationDefault = annotationValue(m.annotationDefault, names, where);
			}
			if (!lookups || m.instructions == null) continue;
			for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof LdcInsnNode ldc) || !(ldc.cst instanceof String s) || !plan.ids.containsKey(s)) continue;
				AbstractInsnNode next = insn.getNext();
				while (next != null && next.getOpcode() < 0) next = next.getNext();
				boolean lookup = next instanceof MethodInsnNode mi && LOOKUPS.contains(mi.owner + "." + mi.name);
				if (!lookup && !all) continue;
				ldc.cst = plan.ids.get(s);
				idLog(names, lookup ? "LOOKUP-ID" : "ALL-ID", where, s);
			}
		}
	}

	private static void annotations(List<AnnotationNode> list, Names names, String where) {
		if (list == null) return;
		for (AnnotationNode a : list) annotation(a, names, where);
	}

	private static void annotation(AnnotationNode a, Names names, String where) {
		if (a.values == null) return;
		for (int i = 1; i < a.values.size(); i += 2) a.values.set(i, annotationValue(a.values.get(i), names, where + " @" + a.desc));
	}

	private static Object annotationValue(Object v, Names names, String where) {
		if (v instanceof String s && names.plan.ids.containsKey(s)) {
			idLog(names, "ANNOTATION-ID", where, s);
			return names.plan.ids.get(s);
		}
		if (v instanceof AnnotationNode a) {
			annotation(a, names, where);
		} else if (v instanceof List<?> l) {
			@SuppressWarnings("unchecked")
			List<Object> list = (List<Object>) l;
			for (int i = 0; i < list.size(); i++) list.set(i, annotationValue(list.get(i), names, where));
		}
		return v;
	}

	private static void idLog(Names names, String kind, String where, String old) {
		names.changed = true;
		names.log.println(kind + "\t" + names.label + "\t" + where + "\t" + esc(old) + "\t" + esc(names.plan.ids.get(old)));
	}

	/** Rewrites each JSON string literal (keys too) through {@link Plan#remapString}, keeping all other text. */
	static String remapJsonStrings(String text, Plan plan, PrintWriter log, String label) {
		StringBuilder out = new StringBuilder(text.length());
		int i = 0;
		int n = text.length();
		while (i < n) {
			char c = text.charAt(i);
			if (c != '"') {
				out.append(c);
				i++;
				continue;
			}
			int j = i + 1;
			StringBuilder raw = new StringBuilder();
			while (j < n) {
				char d = text.charAt(j);
				if (d == '\\') {
					char e = text.charAt(j + 1);
					switch (e) {
						case 'n' -> raw.append('\n');
						case 't' -> raw.append('\t');
						case 'r' -> raw.append('\r');
						case 'b' -> raw.append('\b');
						case 'f' -> raw.append('\f');
						case 'u' -> {
							raw.append((char) Integer.parseInt(text.substring(j + 2, j + 6), 16));
							j += 4;
						}
						default -> raw.append(e);
					}
					j += 2;
					continue;
				}
				if (d == '"') break;
				raw.append(d);
				j++;
			}
			if (j >= n) {
				out.append(text, i, n);
				break;
			}
			String value = raw.toString();
			String mapped = plan.remapString(value);
			if (mapped.equals(value)) {
				out.append(text, i, j + 1);
			} else {
				log.println("STRING\t" + label + "\tjson\t" + esc(value) + "\t" + esc(mapped));
				out.append('"').append(jsonEscape(mapped)).append('"');
			}
			i = j + 1;
		}
		return out.toString();
	}

	private static String jsonEscape(String s) {
		StringBuilder sb = new StringBuilder();
		for (char c : s.toCharArray()) {
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\t' -> sb.append("\\t");
				case '\r' -> sb.append("\\r");
				default -> {
					if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
					else sb.append(c);
				}
			}
		}
		return sb.toString();
	}

	private static String esc(String s) {
		return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
	}

	private static final Pattern CLASS_LIKE = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:/[A-Za-z_$][A-Za-z0-9_$]*)+");

	/**
	 * Every class name under the roots that the jobs' classes refer to, shipped or not: through descriptors, types,
	 * handles and signatures, and through strings that name a class ("a.b.C", "a/b/C", last segment capitalised).
	 * rename-mod.py plans these too, so a reference to a class the jar does not ship stays as dead as it was.
	 */
	static void refs(List<String> roots, Path jobs) throws IOException {
		Set<String> found = new java.util.TreeSet<>();
		Remapper recorder = new Remapper(Opcodes.ASM9) {
			@Override
			public String map(String internalName) {
				note(internalName);
				return internalName;
			}

			@Override
			public Object mapValue(Object value) {
				if (value instanceof String s) {
					String internal = s.indexOf('/') < 0 ? s.replace('.', '/') : s;
					int last = internal.lastIndexOf('/');
					if (CLASS_LIKE.matcher(internal).matches() && (s.indexOf('/') < 0 || s.indexOf('.') < 0)
							&& last >= 0 && last + 1 < internal.length() && Character.isUpperCase(internal.charAt(last + 1))) note(internal);
					Matcher m = DESC_TOKEN.matcher(s);
					while (m.find()) note(m.group(1));
				}
				return super.mapValue(value);
			}

			private void note(String name) {
				for (String root : roots) if (!root.isEmpty() && name.startsWith(root + "/")) found.add(name);
			}
		};
		Set<String> tests = new java.util.TreeSet<>();
		for (String line : Files.readAllLines(jobs, StandardCharsets.UTF_8)) {
			String[] f = line.split("\t", -1);
			if (!f[0].equals("class")) continue;
			ClassNode cn = new ClassNode();
			new ClassReader(Files.readAllBytes(Path.of(f[2]))).accept(cn, 0);
			cn.accept(new ClassRemapper(new ClassVisitor(Opcodes.ASM9) { }, recorder));
			for (MethodNode m : cn.methods) if (m.instructions != null) nameTests(m, tests);
		}
		for (String name : found) System.out.println("REF\t" + name);
		for (String test : tests) {
			int tab = test.indexOf('\t');
			System.out.println("TEST\t" + test.substring(0, tab) + "\t" + esc(test.substring(tab + 1)));
		}
	}

	private static final Set<String> STRING_TESTS = Set.of("contains", "startsWith", "endsWith", "indexOf", "lastIndexOf",
			"equals", "equalsIgnoreCase", "matches", "contentEquals");

	/**
	 * Constants the code compares a string against: {@code s.contains("X")}, {@code "X".equals(s)}. A mod that tests
	 * its own class names this way ("VKOnly" in a mixin plugin) keeps behaving only if those names keep the text.
	 */
	private static void nameTests(MethodNode m, Set<String> tests) {
		for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (!(insn instanceof LdcInsnNode ldc) || !(ldc.cst instanceof String s) || s.isEmpty()) continue;
			AbstractInsnNode next = real(insn.getNext());
			if (next != null && next.getOpcode() == Opcodes.ALOAD) next = real(next.getNext());
			if (isStringTest(next)) tests.add((EQUALITY.contains(((MethodInsnNode) next).name) ? "equal" : "substring") + "\t" + s);
		}
	}

	private static final Set<String> EQUALITY = Set.of("equals", "equalsIgnoreCase", "contentEquals");

	private static AbstractInsnNode real(AbstractInsnNode insn) {
		while (insn != null && insn.getOpcode() < 0) insn = insn.getNext();
		return insn;
	}

	private static boolean isStringTest(AbstractInsnNode insn) {
		return insn instanceof MethodInsnNode mi && mi.owner.equals("java/lang/String") && STRING_TESTS.contains(mi.name);
	}

	/** Every constant-pool string of the jobs' outputs that still mentions an old package (≥2 segments) or old id. */
	static void scan(Plan plan, Path jobs) throws IOException {
		Set<String> roots = new HashSet<>();
		for (String p : plan.packages.keySet()) {
			boolean top = true;
			for (String q : plan.packages.keySet()) if (!q.equals(p) && p.startsWith(q + "/")) top = false;
			if (top) roots.add(p);
		}
		List<String> needles = new ArrayList<>();
		for (String r : roots) {
			needles.add(r + "/");
			needles.add(r.replace('/', '.') + ".");
		}
		for (String line : Files.readAllLines(jobs, StandardCharsets.UTF_8)) {
			String[] f = line.split("\t", -1);
			if (!f[0].equals("class")) continue;
			byte[] b = Files.readAllBytes(Path.of(f[3]));
			ClassReader cr = new ClassReader(b);
			for (int i = 1; i < cr.getItemCount(); i++) {
				int off = cr.getItem(i);
				if (off == 0 || b[off - 1] != 1) continue;
				int len = cr.readUnsignedShort(off);
				String s = new DataInputStream(new ByteArrayInputStream(b, off, len + 2)).readUTF();
				String hit = null;
				if (plan.ids.containsKey(s)) hit = "ID";
				else for (String needle : needles) if (s.contains(needle)) hit = "PACKAGE";
				if (hit != null) System.out.println("LEFT\t" + hit + "\t" + f[4] + "\t" + esc(s));
			}
		}
	}

	/** One row per Mixin-merged member of every exported class; see the class comment. */
	static void census(Path export, List<String> jars) throws IOException {
		Map<String, Set<String>> mixinMethods = new HashMap<>();
		for (String jar : jars) try (InputStream in = Files.newInputStream(Path.of(jar))) {
			scanJar(in, mixinMethods);
		}
		Path classes = Files.isDirectory(export.resolve("class")) ? export.resolve("class") : export;
		List<Path> files = new ArrayList<>();
		try (Stream<Path> s = Files.walk(classes)) {
			s.filter(p -> p.toString().endsWith(".class")).forEach(files::add);
		}
		files.sort(null);
		System.out.println("target\tmixin\toriginal\tmerged\tdesc\tcalls\tcallers\tkind");
		for (Path file : files) {
			ClassNode cn = new ClassNode();
			new ClassReader(Files.readAllBytes(file)).accept(cn, 0);
			Map<String, String> mixinOf = new TreeMap<>();
			Map<String, String> kindOf = new HashMap<>();
			for (MethodNode m : cn.methods) {
				String mixin = mergedFrom(m.invisibleAnnotations, m.visibleAnnotations);
				if (mixin != null) {
					mixinOf.put(m.name + m.desc, mixin);
					kindOf.put(m.name + m.desc, "method");
				}
			}
			for (FieldNode f : cn.fields) {
				String mixin = mergedFrom(f.invisibleAnnotations, f.visibleAnnotations);
				if (mixin != null) {
					mixinOf.put(f.name + ":" + f.desc, mixin);
					kindOf.put(f.name + ":" + f.desc, "field");
				}
			}
			if (mixinOf.isEmpty()) continue;
			Map<String, Map<String, Integer>> callers = new HashMap<>();
			for (MethodNode caller : cn.methods) {
				if (caller.instructions == null) continue;
				String from = caller.name + caller.desc;
				for (AbstractInsnNode insn : caller.instructions) {
					if (insn instanceof MethodInsnNode mi && mi.owner.equals(cn.name)) {
						count(callers, mixinOf, mi.name + mi.desc, from);
					} else if (insn instanceof InvokeDynamicInsnNode indy) {
						for (Object arg : indy.bsmArgs) if (arg instanceof Handle h && h.getOwner().equals(cn.name)) {
							count(callers, mixinOf, h.getName() + h.getDesc(), from);
						}
					} else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Handle h && h.getOwner().equals(cn.name)) {
						count(callers, mixinOf, h.getName() + h.getDesc(), from);
					}
				}
			}
			for (Map.Entry<String, String> e : mixinOf.entrySet()) {
				String key = e.getKey();
				boolean field = kindOf.get(key).equals("field");
				int split = field ? key.indexOf(':') : key.indexOf('(');
				String name = key.substring(0, split);
				String desc = key.substring(field ? split + 1 : split);
				Map<String, Integer> c = callers.getOrDefault(key, Map.of());
				int total = c.values().stream().mapToInt(Integer::intValue).sum();
				StringBuilder sb = new StringBuilder();
				new TreeMap<>(c).forEach((k, v) -> sb.append(sb.length() == 0 ? "" : "|").append(k).append(':').append(v));
				System.out.println(cn.name + "\t" + e.getValue() + "\t" + original(mixinMethods, e.getValue(), name) + "\t" + name
						+ "\t" + desc + "\t" + total + "\t" + sb + "\t" + kindOf.get(key));
			}
		}
	}

	private static void count(Map<String, Map<String, Integer>> callers, Map<String, String> merged, String key, String from) {
		if (merged.containsKey(key)) callers.computeIfAbsent(key, k -> new TreeMap<>()).merge(from, 1, Integer::sum);
	}

	@SafeVarargs
	private static String mergedFrom(List<AnnotationNode>... lists) {
		for (List<AnnotationNode> anns : lists) {
			if (anns == null) continue;
			for (AnnotationNode a : anns) {
				if (!MERGED.equals(a.desc) || a.values == null) continue;
				for (int i = 0; i + 1 < a.values.size(); i += 2) {
					if ("mixin".equals(a.values.get(i))) return ((String) a.values.get(i + 1)).replace('/', '.');
				}
			}
		}
		return null;
	}

	private static String original(Map<String, Set<String>> mixinMethods, String mixin, String merged) {
		Set<String> names = mixinMethods.get(mixin.replace('.', '/'));
		if (names == null) return merged;
		String best = null;
		for (String n : names) {
			if (merged.equals(n)) return n;
			if ((merged.endsWith("$" + n) || merged.startsWith(n + "$")) && (best == null || n.length() > best.length())) best = n;
		}
		return best == null ? merged : best;
	}

	private static void scanJar(InputStream in, Map<String, Set<String>> mixinMethods) throws IOException {
		ZipInputStream zip = new ZipInputStream(in);
		ZipEntry e;
		while ((e = zip.getNextEntry()) != null) {
			String n = e.getName();
			if (n.endsWith(".jar")) {
				scanJar(new ByteArrayInputStream(readAll(zip)), mixinMethods);
			} else if (n.endsWith(".class")) {
				byte[] b = readAll(zip);
				try {
					ClassNode cn = new ClassNode();
					new ClassReader(b).accept(cn, ClassReader.SKIP_CODE);
					boolean mixin = false;
					for (List<AnnotationNode> l : Arrays.asList(cn.invisibleAnnotations, cn.visibleAnnotations)) {
						if (l != null) for (AnnotationNode a : l) if (a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) mixin = true;
					}
					if (!mixin) continue;
					Set<String> names = new HashSet<>();
					for (MethodNode m : cn.methods) names.add(m.name);
					for (FieldNode f : cn.fields) names.add(f.name);
					mixinMethods.put(cn.name, names);
				} catch (RuntimeException ignored) {
					// not a class ASM can read; it cannot be a mixin either
				}
			}
		}
	}

	private static byte[] readAll(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		in.transferTo(out);
		return out.toByteArray();
	}
}
