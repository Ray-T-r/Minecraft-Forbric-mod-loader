/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.tools;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Static link-checker for the tri-in-one MERGED game base. The byte-merge that fuses vanilla + Forge-patched +
 * NeoForge-patched Minecraft into one jar (see {@link MergedBaseBuilder}) can, for a class BOTH ecosystems patch
 * with divergent shapes, keep one side's method while the other side's field/nested-class/constructor won — leaving
 * a surviving instruction that references a member the merged class no longer has. At runtime that surfaces only
 * when the exact code path executes ({@code NoSuchFieldError} / {@code NoSuchMethodError} deep inside client init),
 * one crash per boot. This tool finds them ALL in one pass, ahead of any launch.
 *
 * <p>It loads the merged jar plus the runtime/library jars given as extra args (so supertypes and library members
 * resolve), then, for every method body in the merged jar AND those runtime jars, checks each field/method
 * instruction whose OWNER is a class defined by the MERGED jar (i.e. a game class the merge could have broken): is
 * the referenced {@code name+desc} declared on the owner or anywhere up its supertype chain? Unresolved references
 * are reported as {@code referencingClass#method -> owner.member desc}. References whose owner isn't a merged-jar
 * class (pure library/JDK targets) are ignored — those the merge never touched.
 *
 * <p>Usage: {@code MergedLinkChecker [--baseline <file>] [--write-baseline] <merged.jar> [<classpath.jar> ...]}.
 *
 * <p><b>Why a baseline rather than a bare count.</b> There are dangling references today that nobody can fix in
 * one sitting — Forge's biome and structure modifiers, its datapack condition context, the capability methods the
 * merge dropped. Failing on the total means nobody can rebuild the base, so for a long time this tool's exit code
 * was thrown away by the caller and the number only ever appeared in scrollback. That makes it a measurement of
 * nothing: a merge change that adds a dangling reference reads exactly like one that does not.
 *
 * <p>With {@code --baseline}, the known set is DATA in a committed file and the exit code means one thing:
 * <b>a reference that is dangling now and was not dangling before</b>. Entries in the baseline that no longer
 * dangle are reported as {@code [FIXED]} so the file can shrink — the number is supposed to go down, and a
 * baseline nobody prunes is how it silently stops going down. A missing baseline file is itself a failure (with
 * the one command that seeds it) rather than a silent fallback to "report only", because the whole point is that
 * an unenforced check reads green.
 *
 * <p>Exit codes: 0 = no NEW dangling references, 1 = at least one, 2 = usage/seed error.
 */
public final class MergedLinkChecker {
	private MergedLinkChecker() {
	}

	/** internal-name -> ClassNode, across every input jar (merged jar loaded first, so it wins on collisions). */
	private final Map<String, ClassNode> classes = new LinkedHashMap<>();
	/** internal-names defined by the MERGED jar specifically — only refs INTO these are link-checked. */
	private final Set<String> mergedOwned = new LinkedHashSet<>();

	public static void main(String[] args) throws IOException {
		Path baseline = null;
		String baselineResource = null;
		boolean writeBaseline = false;
		List<String> jars = new ArrayList<>();
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--baseline" -> {
					if (++i >= args.length) usage("--baseline needs a file");
					baseline = Path.of(args[i]);
				}
				case "--write-baseline" -> writeBaseline = true;
				case "--baseline-resource" -> {
					if (++i >= args.length) usage("--baseline-resource needs a resource name");
					baselineResource = args[i];
				}
				default -> jars.add(args[i]);
			}
		}
		if (jars.isEmpty()) usage("no merged jar given");
		if (writeBaseline && baseline == null) usage("--write-baseline needs --baseline <file>");
		if (baseline != null && baselineResource != null) usage("choose a file or a packaged baseline, not both");

		MergedLinkChecker c = new MergedLinkChecker();
		c.loadPath(jars.get(0), true);
		for (int i = 1; i < jars.size(); i++) c.loadPath(jars.get(i), false);
		if (c.mergedOwned.isEmpty()) usage("the merged input contains no classes; no link check was performed");
		List<String> dangling = c.check();

		String scanned = "[link-check] loaded " + c.classes.size() + " classes ("
				+ c.mergedOwned.size() + " from the merged jar); ";

		if (baseline == null && baselineResource == null) {
			dangling.forEach(r -> System.out.println("[DANGLING] " + r));
			System.out.println(scanned + "dangling references: " + dangling.size());
			System.exit(dangling.isEmpty() ? 0 : 1);
			return;
		}

		if (writeBaseline) {
			writeBaseline(baseline, dangling);
			System.out.println(scanned + "wrote " + dangling.size() + " entries to " + baseline);
			System.out.println("[link-check] READ THE DIFF. Seeding this file accepts every line in it as known.");
			System.exit(0);
			return;
		}

		if (baselineResource == null && !Files.isRegularFile(baseline)) {
			dangling.forEach(r -> System.out.println("[DANGLING] " + r));
			System.err.println(scanned + "dangling references: " + dangling.size()
					+ ", but there is no baseline at " + baseline);
			System.err.println("[link-check] seed it once, then read the diff before committing:");
			System.err.println("[link-check]   ... MergedLinkChecker --baseline " + baseline
					+ " --write-baseline <merged.jar> <cp.jar>...");
			System.exit(2);
			return;
		}

		Set<String> known;
		if (baselineResource != null) {
			try (java.io.InputStream in = MergedLinkChecker.class.getResourceAsStream(baselineResource)) {
				if (in == null) throw new IOException("missing packaged link baseline: " + baselineResource);
				known = parseBaseline(new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList());
			}
		} else known = readBaseline(baseline);
		List<String> fresh = new ArrayList<>();
		Set<String> stillDangling = new LinkedHashSet<>(dangling);
		for (String r : dangling) {
			if (known.contains(r)) System.out.println("[KNOWN]    " + r);
			else {
				System.out.println("[NEW]      " + r);
				fresh.add(r);
			}
		}
		List<String> fixed = new ArrayList<>();
		for (String k : known) {
			if (!stillDangling.contains(k)) {
				System.out.println("[FIXED]    " + k);
				fixed.add(k);
			}
		}
		// One machine-readable line: a gate greps THIS, never the prose above it.
		System.out.println(scanned + "dangling references: " + dangling.size()
				+ " (known " + (dangling.size() - fresh.size()) + ", new " + fresh.size()
				+ "); baseline entries now fixed: " + fixed.size());
		if (!fixed.isEmpty()) {
			System.out.println("[link-check] " + fixed.size() + " baseline entr" + (fixed.size() == 1 ? "y" : "ies")
					+ " no longer dangle — prune " + baseline + " so the number keeps meaning something.");
		}
		if (!fresh.isEmpty()) {
			System.err.println("[link-check] " + fresh.size() + " NEW dangling reference"
					+ (fresh.size() == 1 ? "" : "s") + " — the merge broke something it did not break before.");
		}
		System.exit(fresh.isEmpty() ? 0 : 1);
	}

	private static void usage(String why) {
		System.err.println("usage: MergedLinkChecker [--baseline <file> | --baseline-resource <name>] [--write-baseline] <merged.jar> [<cp.jar> ...]");
		System.err.println("       (" + why + ")");
		System.exit(2);
	}

	/** Baseline format: one report line per entry; {@code #} comments and blank lines ignored. */
	private static Set<String> readBaseline(Path file) throws IOException {
		return parseBaseline(Files.readAllLines(file, StandardCharsets.UTF_8));
	}

	private static Set<String> parseBaseline(List<String> lines) {
		Set<String> out = new LinkedHashSet<>();
		for (String line : lines) {
			String t = line.strip();
			if (t.isEmpty() || t.startsWith("#")) continue;
			out.add(t);
		}
		return out;
	}

	private static void writeBaseline(Path file, List<String> entries) throws IOException {
		StringBuilder sb = new StringBuilder();
		sb.append("# Dangling references the merged base is KNOWN to carry, one per line.\n");
		sb.append("# Generated by MergedLinkChecker --write-baseline; a line here is an accepted defect, not a fact.\n");
		sb.append("# The count is supposed to go DOWN: when the tool reports [FIXED], delete that line.\n");
		for (String e : entries) sb.append(e).append('\n');
		if (file.getParent() != null) Files.createDirectories(file.getParent());
		Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
	}

	/** Load a jar, or (if a directory) every {@code .jar} under it recursively — lets one arg pull in a libs tree. */
	private void loadPath(String path, boolean merged) throws IOException {
		File f = new File(path);
		if (f.isDirectory()) {
			List<File> jars = new ArrayList<>();
			collectJars(f, jars);
			for (File j : jars) load(j.getPath(), merged);
		} else {
			load(path, merged);
		}
	}

	private static void collectJars(File dir, List<File> out) {
		File[] kids = dir.listFiles();
		if (kids == null) return;
		for (File k : kids) {
			if (k.isDirectory()) collectJars(k, out);
			else if (k.getName().endsWith(".jar")) out.add(k);
		}
	}

	private void load(String jarPath, boolean merged) throws IOException {
		try (ZipFile zf = new ZipFile(jarPath)) {
			var entries = zf.entries();
			int n = 0;
			while (entries.hasMoreElements()) {
				ZipEntry e = entries.nextElement();
				if (!e.getName().endsWith(".class")) continue;
				try (InputStream in = zf.getInputStream(e)) {
					ClassNode cn = new ClassNode();
					new ClassReader(in.readAllBytes()).accept(cn, ClassReader.SKIP_FRAMES);
					// Merged jar wins: don't let a later classpath jar shadow a merged-owned class.
					if (merged || !classes.containsKey(cn.name)) classes.put(cn.name, cn);
					if (merged) mergedOwned.add(cn.name);
					n++;
				}
			}
			System.out.println("[link-check] " + (merged ? "MERGED " : "cp     ") + jarPath + " : " + n + " classes");
		}
	}

	private List<String> check() {
		List<String> reports = new ArrayList<>();
		// Scan bodies in the merged jar AND every classpath jar (a runtime jar can reference a broken game member too).
		for (ClassNode cn : classes.values()) {
			for (MethodNode m : cn.methods) {
				if (m.instructions == null) continue;
				for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (insn instanceof FieldInsnNode fi) {
						if (!mergedOwned.contains(fi.owner)) continue;
						if (!resolveField(fi.owner, fi.name, fi.desc)) {
							reports.add(cn.name + "#" + m.name + m.desc + "  ->  FIELD " + fi.owner + "." + fi.name + " " + fi.desc);
						}
					} else if (insn instanceof MethodInsnNode mi) {
						if (!mergedOwned.contains(mi.owner)) continue;
						if (mi.itf) continue; // interface dispatch: default/abstract resolution is looser, skip
						if (!resolveMethod(mi.owner, mi.name, mi.desc)) {
							reports.add(cn.name + "#" + m.name + m.desc + "  ->  METHOD " + mi.owner + "." + mi.name + mi.desc);
						}
					}
				}
			}
		}
		// De-dup and sort; the caller decides how each line is labelled and whether it is fatal.
		return new LinkedHashSet<>(reports).stream().sorted().toList();
	}

	private boolean resolveField(String owner, String name, String desc) {
		for (String c = owner; c != null; ) {
			ClassNode cn = classes.get(c);
			if (cn == null) {
				// Supertype outside our closure — resolve by reflection (JDK/library on the tool classpath). If the
				// class loads and declares/inherits a field of this name, it's a legit inherited access; if the
				// class loads but has no such field, it's genuinely DANGLING; if it can't load at all, assume OK.
				return reflectivelyHasField(c, name);
			}
			for (FieldNode f : cn.fields) {
				if (f.name.equals(name) && f.desc.equals(desc)) return true;
			}
			for (String itf : cn.interfaces) { // constants can come from interfaces
				if (resolveField(itf, name, desc)) return true;
			}
			c = cn.superName;
		}
		return false;
	}

	private boolean resolveMethod(String owner, String name, String desc) {
		// Constructors and static initializers are NEVER inherited — they must be declared on the exact owner.
		if (name.equals("<init>") || name.equals("<clinit>")) {
			ClassNode cn = classes.get(owner);
			if (cn == null) return true; // owner itself outside closure — not a merged class we can judge
			for (MethodNode mn : cn.methods) {
				if (mn.name.equals(name) && mn.desc.equals(desc)) return true;
			}
			return false;
		}
		for (String c = owner; c != null; ) {
			ClassNode cn = classes.get(c);
			if (cn == null) {
				// Outside our closure — resolve by reflection (JDK/library supertypes: Thread.start, ArrayList.addAll,
				// Throwable.getMessage, etc. are all legit inherited). Match by NAME (lenient) to suppress false
				// positives; only a name that exists NOWHERE up the reflective chain is genuinely DANGLING.
				return reflectivelyHasMethod(c, name);
			}
			for (MethodNode mn : cn.methods) {
				if (mn.name.equals(name) && mn.desc.equals(desc)) return true;
			}
			for (String itf : cn.interfaces) {
				if (resolveMethod(itf, name, desc)) return true;
			}
			c = cn.superName;
		}
		return false;
	}

	private boolean reflectivelyHasField(String internalName, String name) {
		try {
			Class<?> c = Class.forName(internalName.replace('/', '.'), false, getClass().getClassLoader());
			for (Class<?> k = c; k != null; k = k.getSuperclass()) {
				for (var f : k.getDeclaredFields()) if (f.getName().equals(name)) return true;
				for (Class<?> itf : k.getInterfaces()) {
					for (var f : itf.getFields()) if (f.getName().equals(name)) return true;
				}
			}
			return false; // class exists but no such field anywhere up its chain -> dangling
		} catch (Throwable notLoadable) {
			return true; // library/runtime supertype we couldn't load -> assume OK (avoid false positives)
		}
	}

	private boolean reflectivelyHasMethod(String internalName, String name) {
		try {
			Class<?> c = Class.forName(internalName.replace('/', '.'), false, getClass().getClassLoader());
			for (Class<?> k = c; k != null; k = k.getSuperclass()) {
				for (var m : k.getDeclaredMethods()) if (m.getName().equals(name)) return true;
			}
			for (var m : c.getMethods()) if (m.getName().equals(name)) return true; // default/interface methods
			return false;
		} catch (Throwable notLoadable) {
			return true;
		}
	}
}
