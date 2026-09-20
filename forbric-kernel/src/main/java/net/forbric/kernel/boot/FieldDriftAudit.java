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

package net.forbric.kernel.boot;

import java.io.IOException;
import java.io.InputStream;
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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.ModCatalog;
import net.forbric.kernel.transform.MergedBaseFieldDrift;
import net.forbric.kernel.util.ByteScan;
import net.forbric.kernel.util.ForbricLog;

/**
 * Names every installed jar, and its mod, that reads a vanilla field with a descriptor the merged base no longer
 * has ({@link MergedBaseFieldDrift#KNOWN}) — at boot, statically, so the finding is complete before
 * {@code load-report.txt} is written rather than a {@code NoSuchFieldError} whenever the access runs.
 *
 * <p>Same shape as {@link CapabilityUseAudit}: a {@link ByteScan} needle over each class for the owner names
 * (cheap, no inflate beyond the bytes), then an ASM confirm on the hit — the field reference must carry the
 * VANILLA descriptor. A class that names {@code KeyMapping} only as a type, or reads {@code MAP} with the merged
 * descriptor (a NeoForge build), is not a finding. {@code -Dforbric.fieldDriftAudit=off} skips the scan.
 */
public final class FieldDriftAudit {
	static final String SWITCH = "forbric.fieldDriftAudit";

	private static final byte[][] NEEDLES = needles();
	/** jar file name → the drifted accesses it carries. */
	private static final Map<String, Set<String>> HITS = new LinkedHashMap<>();
	private static int scannedJars;

	private FieldDriftAudit() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(SWITCH, "on"));
	}

	private static byte[][] needles() {
		Set<String> owners = new LinkedHashSet<>();
		for (MergedBaseFieldDrift.Drift drift : MergedBaseFieldDrift.KNOWN) owners.add(drift.owner());
		byte[][] out = new byte[owners.size()][];
		int i = 0;
		for (String owner : owners) out[i++] = ByteScan.needle(owner);
		return out;
	}

	/** Every class of every jar; a jar that cannot be read is skipped, never refused. */
	public static void scan(List<Path> jars) {
		if (!enabled()) return;
		for (Path jar : jars) {
			String name = jar.getFileName().toString();
			synchronized (HITS) {
				scannedJars++;
			}
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				for (ZipEntry entry : zip.stream().toList()) {
					if (!entry.getName().endsWith(".class")) continue;
					try (InputStream in = zip.getInputStream(entry)) {
						note(name, in.readAllBytes());
					}
				}
			} catch (IOException unreadable) {
				ForbricLog.debug("[Forbric/FieldDrift] could not read %s: %s", name, unreadable);
			}
		}
	}

	/** Records {@code jarName} for every KNOWN access {@code classBytes} makes. */
	public static void note(String jarName, byte[] classBytes) {
		if (jarName == null || classBytes == null || !ByteScan.containsAny(classBytes, NEEDLES)) return;
		ClassNode node = new ClassNode();
		try {
			new ClassReader(classBytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		} catch (RuntimeException unreadable) {
			return;
		}
		if (node.methods == null) return;
		for (MethodNode m : node.methods) {
			if (m.instructions == null) continue;
			for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof FieldInsnNode f)) continue;
				MergedBaseFieldDrift.Drift drift = MergedBaseFieldDrift.find(f.owner, f.name, f.desc);
				if (drift == null) continue;
				synchronized (HITS) {
					HITS.computeIfAbsent(jarName, j -> new LinkedHashSet<>()).add(drift.key());
				}
			}
		}
	}

	/** One count line always; one line per hit jar; DEGRADED on every catalog entry whose jar is a hit. */
	public static void report() {
		if (!enabled()) return;
		Map<String, Set<String>> hits;
		int scanned;
		synchronized (HITS) {
			hits = new LinkedHashMap<>();
			for (var e : HITS.entrySet()) hits.put(e.getKey(), Set.copyOf(e.getValue()));
			scanned = scannedJars;
		}
		ForbricLog.info("[Forbric/FieldDrift] scanned %d jar(s): %d reference a vanilla field the merge re-typed", scanned,
				hits.size());
		if (hits.isEmpty()) return;
		List<ModCatalog.Entry> catalog = ModCatalog.everything();
		for (var e : hits.entrySet()) {
			List<String> named = new ArrayList<>();
			for (ModCatalog.Entry entry : catalog) {
				if (entry.jar() == null || !entry.jar().equals(e.getKey())) continue;
				ModCatalog.mark(entry.modId(), ModCatalog.Status.DEGRADED, "reads " + String.join(", ", simple(e.getValue()))
						+ " with vanilla's descriptor, which the merged base no longer declares — NoSuchFieldError at that access");
				named.add(entry.modId());
			}
			for (String key : e.getValue()) {
				MergedBaseFieldDrift.Drift drift = byKey(key);
				ForbricLog.warn("[Forbric/FieldDrift] %s reads %s (cost: %s)%s", e.getKey(), key,
						drift == null ? "unknown" : drift.cost(), named.isEmpty() ? "" : " — marked DEGRADED: " + named);
			}
		}
	}

	private static MergedBaseFieldDrift.Drift byKey(String key) {
		for (MergedBaseFieldDrift.Drift drift : MergedBaseFieldDrift.KNOWN) if (drift.key().equals(key)) return drift;
		return null;
	}

	private static List<String> simple(Set<String> keys) {
		List<String> out = new ArrayList<>();
		for (String key : keys) {
			int slash = key.lastIndexOf('/', key.indexOf('#'));
			out.add(slash >= 0 ? key.substring(slash + 1) : key);
		}
		return out;
	}

	/** Package-private, for the test. */
	static Map<String, Set<String>> hits() {
		synchronized (HITS) {
			Map<String, Set<String>> out = new LinkedHashMap<>();
			for (var e : HITS.entrySet()) out.put(e.getKey(), Set.copyOf(e.getValue()));
			return out;
		}
	}

	/** Test seam. */
	static void reset() {
		synchronized (HITS) {
			HITS.clear();
			scannedJars = 0;
		}
	}
}
