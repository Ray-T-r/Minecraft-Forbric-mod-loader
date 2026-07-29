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
 * <p>Usage: {@code MergedLinkChecker <merged.jar> [<classpath.jar> ...]}. Exit 0 = no dangling refs, 1 = found.
 */
public final class MergedLinkChecker {
	private MergedLinkChecker() {
	}

	/** internal-name -> ClassNode, across every input jar (merged jar loaded first, so it wins on collisions). */
	private final Map<String, ClassNode> classes = new LinkedHashMap<>();
	/** internal-names defined by the MERGED jar specifically — only refs INTO these are link-checked. */
	private final Set<String> mergedOwned = new LinkedHashSet<>();

	public static void main(String[] args) throws IOException {
		if (args.length < 1) {
			System.err.println("usage: MergedLinkChecker <merged.jar> [<classpath.jar> ...]");
			System.exit(2);
		}
		MergedLinkChecker c = new MergedLinkChecker();
		c.loadPath(args[0], true);
		for (int i = 1; i < args.length; i++) c.loadPath(args[i], false);
		int dangling = c.check();
		System.out.println("[link-check] loaded " + c.classes.size() + " classes ("
				+ c.mergedOwned.size() + " from the merged jar); dangling references: " + dangling);
		System.exit(dangling == 0 ? 0 : 1);
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

	private int check() {
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
		// De-dup and print grouped by target owner for readability.
		Set<String> unique = new LinkedHashSet<>(reports);
		unique.stream().sorted().forEach(r -> System.out.println("[DANGLING] " + r));
		return unique.size();
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
