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

package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.mixin.MergedBaseAnonymousDrift;

/**
 * Derives the three anonymous-drift buckets from stock 26.2 and the merged base and asserts they EQUAL the
 * pinned constants. The identity of an anonymous class is its non-{@code <init>} method set (name + descriptor)
 * plus its superclass; the constructor descriptor and the {@code val$} captures are javac's plumbing.
 */
class MergedBaseAnonymousDriftTest {
	private static final Path MERGED = Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run",
			"merged-base", "patched-mc-merged-26.2.jar").normalize();
	private static final Pattern ANONYMOUS = Pattern.compile("^(net/minecraft/|com/mojang/).*\\$\\d+\\.class$");

	record Shape(String superName, Set<String> methods, String ctorDesc, Set<String> captures) {
		String identity() {
			return superName + "|" + methods;
		}

		/** The same class, possibly with methods a patch ADDED: every vanilla method is still there, same superclass. */
		boolean stillHolds(Shape vanilla) {
			return superName.equals(vanilla.superName) && methods.containsAll(vanilla.methods);
		}
	}

	@Test
	void theThreeBucketsAreExactlyThePinnedConstants() throws Exception {
		Path vanilla = vanillaJar();
		assumeTrue(Files.isRegularFile(MERGED) && Files.isRegularFile(vanilla), "staged merged base or stock 26.2 absent");
		Map<String, Shape> vanillaShapes = shapes(vanilla), mergedShapes = shapes(MERGED);
		assertTrue(vanillaShapes.size() > 500, "this does not look like a full vanilla jar (" + vanillaShapes.size() + " anonymous classes)");

		Map<String, List<String>> relocated = new TreeMap<>();
		Set<String> reshaped = new TreeSet<>(), captureOnly = new TreeSet<>();
		int missing = 0;
		for (Map.Entry<String, Shape> e : vanillaShapes.entrySet()) {
			String name = e.getKey();
			Shape v = e.getValue();
			Shape m = mergedShapes.get(name);
			if (m == null) { missing++; continue; }
			// NeoForge ADDS methods to anonymous classes it patches (MappedRegistry$2 gains getData/getDataMap,
			// CompoundTag$1 gains readNamedTagType): still the class vanilla compiled there. Only a vanilla method
			// that is GONE from $N says the name now holds a different class.
			if (m.stillHolds(v)) {
				if (!v.ctorDesc().equals(m.ctorDesc()) || !v.captures().equals(m.captures())) captureOnly.add(name);
				continue;
			}
			String outer = name.substring(0, name.lastIndexOf('$'));
			List<String> candidates = new ArrayList<>();
			for (Map.Entry<String, Shape> other : mergedShapes.entrySet()) {
				if (other.getKey().equals(name) || !other.getKey().startsWith(outer + "$")) continue;
				if (other.getValue().identity().equals(v.identity())) candidates.add(other.getKey());
			}
			java.util.Collections.sort(candidates);
			if (candidates.isEmpty()) reshaped.add(name); else relocated.put(name, candidates);
		}
		assertEquals(0, missing, "vanilla anonymous classes absent from the merged base");
		assertEquals(new TreeMap<>(MergedBaseAnonymousDrift.RELOCATED), relocated, "RELOCATED: vanilla $N whose body lives at another $M");
		assertEquals(new TreeSet<>(MergedBaseAnonymousDrift.RESHAPED), reshaped, "RESHAPED: vanilla $N whose body exists nowhere in the outer class");
		assertEquals(new TreeSet<>(MergedBaseAnonymousDrift.CAPTURE_ONLY), captureOnly, "CAPTURE_ONLY: same methods, different constructor or captures — never flagged");
	}

	private static Map<String, Shape> shapes(Path jar) throws IOException {
		Map<String, Shape> out = new HashMap<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (ZipEntry entry : zip.stream().toList()) {
				if (!ANONYMOUS.matcher(entry.getName()).matches()) continue;
				ClassNode node;
				try (InputStream in = zip.getInputStream(entry)) {
					node = new ClassNode();
					new ClassReader(in.readAllBytes()).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
				}
				Set<String> methods = new TreeSet<>();
				String ctor = "";
				for (MethodNode m : node.methods) {
					if ("<init>".equals(m.name)) ctor = m.desc; else if (!"<clinit>".equals(m.name)) methods.add(m.name + m.desc);
				}
				Set<String> captures = new TreeSet<>();
				for (FieldNode f : node.fields) if (f.name.startsWith("val$") || f.name.startsWith("this$")) captures.add(f.name + ":" + f.desc);
				out.put(entry.getName().substring(0, entry.getName().length() - ".class".length()),
						new Shape(node.superName, methods, ctor, captures));
			}
		}
		return out;
	}

	private static Path vanillaJar() {
		String env = System.getenv("MC_DIR");
		return Path.of(env != null && !env.isBlank() ? env : System.getProperty("user.home") + "/Library/Application Support/minecraft")
				.resolve("versions/26.2/26.2.jar");
	}
}
