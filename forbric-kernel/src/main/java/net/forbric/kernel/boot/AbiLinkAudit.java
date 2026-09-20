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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;

import net.forbric.api.ModCatalog;
import net.forbric.kernel.util.ByteScan;
import net.forbric.kernel.util.ForbricLog;

/**
 * Names the mods compiled against a NeoForge or MinecraftForge this instance does not carry.
 *
 * <p>A mod built for another loader version links fine at load and dies at the first call into a class that is
 * not there — a {@code NoClassDefFoundError} inside a deferred task, a listener, a render pass — and the report
 * names the class, not the mod. This reads every class's constant pool for the Forge-family classes it names and
 * resolves each against the carriers, the merged base and every installed jar (a Fabric port of a Forge library
 * legitimately ships {@code net.minecraftforge.*} classes for its dependants); what resolves nowhere is dangling.
 *
 * <p>Loader-bootstrap packages are out of scope: the kernel REPLACES FML's loading layer, so
 * {@code fml/loading}, {@code fml/relauncher} and the {@code locating} SPIs are absent here by design and a mod
 * naming them (CustomSkinLoader's six references) is not compiled against the wrong Forge.
 *
 * <p>Never throws, never refuses a jar; {@code -Dforbric.abiAudit=off}. Scanned at boot while the jar names are
 * in hand, reported after the catalog is published so the rows reach load-report.txt.
 */
public final class AbiLinkAudit {
	static final String SWITCH = "forbric.abiAudit";

	/** What is judged: everything under either family's root… */
	static final String[] FAMILIES = { "net/neoforged/", "net/minecraftforge/" };
	/** …except the loading layer the kernel replaces. */
	static final String[] OUT_OF_SCOPE = { "net/neoforged/fml/loading/", "net/minecraftforge/fml/loading/",
			"net/minecraftforge/fml/relauncher/", "net/neoforged/neoforgespi/locating/", "net/minecraftforge/forgespi/locating/" };
	private static final byte[][] NEEDLES = { ByteScan.needle("net/neoforged/"), ByteScan.needle("net/minecraftforge/") };

	/** One jar with dangling references: which family, and the classes (internal names) that resolve nowhere. */
	public record Finding(String jar, String family, List<String> missing) {
	}

	/** jar file name → its finding. */
	private static final Map<String, Finding> FINDINGS = new LinkedHashMap<>();
	private static int scannedJars;
	private static long scanNanos;

	private AbiLinkAudit() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(SWITCH, "on"));
	}

	/** Scans {@code jars}, resolving against {@code jars} themselves plus {@code alsoAgainst} (carriers, merged base). */
	public static void scan(List<Path> jars, List<Path> alsoAgainst) {
		if (!enabled()) return;
		long start = System.nanoTime();
		List<Path> universe = new ArrayList<>(alsoAgainst);
		for (Path jar : jars) if (!universe.contains(jar)) universe.add(jar);
		Set<String> present = classesOf(universe);
		List<Finding> findings = audit(jars, present);
		synchronized (FINDINGS) {
			for (Finding f : findings) FINDINGS.put(f.jar(), f);
			scannedJars += jars.size();
			scanNanos += System.nanoTime() - start;
		}
	}

	/** Every {@code .class} entry name (without the extension) across {@code jars}. */
	static Set<String> classesOf(List<Path> jars) {
		Set<String> present = new HashSet<>();
		for (Path jar : jars) {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				for (ZipEntry entry : zip.stream().toList()) {
					String name = entry.getName();
					if (name.endsWith(".class")) present.add(name.substring(0, name.length() - 6));
				}
			} catch (IOException unreadable) {
				ForbricLog.debug("[Forbric/AbiAudit] could not list %s: %s", jar.getFileName(), unreadable);
			}
		}
		return present;
	}

	/** The findings over {@code jars}, given the set of classes that exist. Pure; the test's entry point. */
	static List<Finding> audit(List<Path> jars, Set<String> present) {
		List<Finding> out = new ArrayList<>();
		for (Path jar : jars) {
			Set<String> missing = new LinkedHashSet<>();
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				for (ZipEntry entry : zip.stream().toList()) {
					if (!entry.getName().endsWith(".class")) continue;
					byte[] bytes;
					try (InputStream in = zip.getInputStream(entry)) {
						bytes = in.readAllBytes();
					}
					if (!ByteScan.containsAny(bytes, NEEDLES)) continue;
					for (String named : namedClasses(bytes)) {
						if (inScope(named) && !present.contains(named)) missing.add(named);
					}
				}
			} catch (IOException | RuntimeException unreadable) {
				ForbricLog.debug("[Forbric/AbiAudit] could not read %s: %s", jar.getFileName(), unreadable);
				continue;
			}
			if (missing.isEmpty()) continue;
			String first = missing.iterator().next();
			out.add(new Finding(jar.getFileName().toString(), first.startsWith("net/neoforged/") ? "NeoForge" : "MinecraftForge",
					List.copyOf(missing)));
		}
		return out;
	}

	/** Whether {@code internal} is a Forge-family class this audit judges. */
	static boolean inScope(String internal) {
		boolean family = false;
		for (String root : FAMILIES) family |= internal.startsWith(root);
		if (!family) return false;
		for (String skip : OUT_OF_SCOPE) if (internal.startsWith(skip)) return false;
		return true;
	}

	/** Every CONSTANT_Class in the constant pool, array descriptors reduced to their element class. */
	static Set<String> namedClasses(byte[] classBytes) {
		Set<String> out = new LinkedHashSet<>();
		ClassReader reader = new ClassReader(classBytes);
		char[] buf = new char[reader.getMaxStringLength()];
		for (int i = 1; i < reader.getItemCount(); i++) {
			int offset = reader.getItem(i);
			if (offset == 0 || reader.readByte(offset - 1) != 7) continue; // CONSTANT_Class
			String name = reader.readUTF8(offset, buf);
			if (name == null) continue;
			if (name.startsWith("[")) {
				int l = name.indexOf('L');
				if (l < 0 || !name.endsWith(";")) continue;
				name = name.substring(l + 1, name.length() - 1);
			}
			out.add(name);
		}
		return out;
	}

	/** One summary line always; one WARN per jar with dangling references; DEGRADED on every row from that jar. */
	public static void report() {
		if (!enabled()) return;
		List<Finding> findings;
		int scanned;
		long nanos;
		synchronized (FINDINGS) {
			findings = List.copyOf(FINDINGS.values());
			scanned = scannedJars;
			nanos = scanNanos;
		}
		for (Finding f : findings) {
			List<String> shown = f.missing().subList(0, Math.min(5, f.missing().size()));
			ForbricLog.warn("[Forbric/AbiAudit] %s was compiled against a different %s than this instance carries — %d "
					+ "class(es) it names do not exist here: %s%s", f.jar(), f.family(), f.missing().size(),
					String.join(", ", shown).replace('/', '.'), f.missing().size() > shown.size() ? ", …" : "");
			ModCatalog.markByJar(f.jar(), ModCatalog.Status.DEGRADED, "compiled against a different " + f.family() + " — "
					+ f.missing().get(0).replace('/', '.') + " is not in this instance");
		}
		ForbricLog.info("[Forbric/AbiAudit] scanned %d jar(s) in %d ms: %d with dangling Forge-family references", scanned,
				nanos / 1_000_000, findings.size());
	}

	/** Package-private, for the test. */
	static void reset() {
		synchronized (FINDINGS) {
			FINDINGS.clear();
			scannedJars = 0;
			scanNanos = 0;
		}
	}
}
