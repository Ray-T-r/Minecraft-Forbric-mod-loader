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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.discovery.ForbricModDiscoverer;
import net.forbric.kernel.discovery.ModAnnotationScanner;
import net.forbric.kernel.fabric.FabricModMetadataParser;
import net.forbric.kernel.fabric.KernelModMetadata;
import net.forbric.kernel.metadata.forge.ModsTomlParser;
import net.forbric.kernel.util.ForbricLog;

/**
 * Decides which ONE ecosystem loads a multi-loader mod jar.
 *
 * <p>A "universal" mod jar ships a manifest per loader — {@code fabric.mod.json}, {@code META-INF/mods.toml} AND
 * {@code META-INF/neoforge.mods.toml} — plus one glue class per family (a {@code net.minecraftforge} {@code @Mod},
 * a {@code net.neoforged} {@code @Mod}, a Fabric entrypoint). On a normal instance exactly one loader is running,
 * so exactly one of those is claimed. On Forbric ALL THREE are running, so without arbitration the same mod is
 * initialised three times: its content registers three times, its listeners fire three times. Some jars instead
 * carry leftover manifests without those implementations; preference must not discard their only initializer.
 * Observed on real
 * mods — FallingTree and collective were each claimed by Fabric + MinecraftForge + NeoForge simultaneously.
 *
 * <p>Discovery deliberately reports every manifest truthfully ("which family actually loads is a boot-time
 * policy" — {@link ForbricModDiscoverer}); this class is that policy. It picks by a documented preference order,
 * logs the choice and what it suppressed, and is overridable with
 * {@code -Dforbric.multiLoaderPreference=neoforge,minecraftforge,fabric}.
 *
 * <p>The default order prefers a Forge-family claim over Fabric because the Forge/NeoForge baselines are ALWAYS
 * present on the merged base, whereas a jar's Fabric side typically declares a dependency on fabric-api that the
 * user may not have installed; and NeoForge before traditional Forge because NeoForge won most of the byte-merge,
 * so its glue's hooks are the most likely to be intact.
 */
public final class MultiLoaderArbiter {
	private static final List<Ecosystem> DEFAULT_PREFERENCE =
			List.of(Ecosystem.NEOFORGE, Ecosystem.FORGE, Ecosystem.FABRIC);
	static final String ENTRYPOINT_SWITCH = "forbric.multiLoaderEntrypoints";

	/** jar path -> the ecosystem that owns it. Computed once per jar; discovery order is stable. */
	private static final Map<String, Ecosystem> OWNERS = new LinkedHashMap<>();

	private MultiLoaderArbiter() {
	}

	/** Forgets every decision — for tests, and so a re-launch in one process re-arbitrates. */
	public static synchronized void reset() {
		OWNERS.clear();
	}

	/**
	 * The ecosystem that owns {@code jar}. A jar declaring exactly one family is owned by it (the common case, no
	 * log); a jar declaring several is arbitrated by preference and logged ONCE, naming what was suppressed.
	 * Returns {@code null} when the jar declares no loader manifest at all (a plain library — nobody claims it, and
	 * every path should treat it as unowned rather than as "not mine").
	 */
	public static synchronized Ecosystem ownerOf(Path jar) {
		String key = jar.toAbsolutePath().toString();
		if (OWNERS.containsKey(key)) return OWNERS.get(key);

		List<Ecosystem> declared = declaredBy(jar);
		Ecosystem owner = null;
		if (declared.size() == 1) {
			owner = declared.get(0);
		} else if (declared.size() > 1) {
			List<Ecosystem> candidates = declared;
			if (!"off".equalsIgnoreCase(System.getProperty(ENTRYPOINT_SWITCH, "on"))) {
				List<Ecosystem> initialized = initializationFamilies(jar, declared);
				// No initializer anywhere is a legitimate data-only/library jar, not a reason to reject it.
				if (!initialized.isEmpty()) candidates = initialized;
			}
			for (Ecosystem candidate : preference()) {
				if (candidates.contains(candidate)) {
					owner = candidate;
					break;
				}
			}
			if (owner == null) owner = candidates.get(0); // preference listed none of them — stay deterministic
			if (!candidates.equals(declared)) {
				List<Ecosystem> metadataOnly = new ArrayList<>(declared);
				metadataOnly.removeAll(candidates);
				ForbricLog.info("[Forbric/MultiLoader] %s has initialization code for %s; ignoring manifest-only "
						+ "claims %s so its real entrypoint is not suppressed", jar.getFileName(), candidates, metadataOnly);
			}
			List<Ecosystem> suppressed = new ArrayList<>(declared);
			suppressed.remove(owner);
			ForbricLog.info("[Forbric/MultiLoader] %s declares %d loaders — loading it as %s only, suppressing %s "
					+ "(a universal jar would otherwise initialise once per live ecosystem)",
					jar.getFileName(), declared.size(), owner, suppressed);
		}

		OWNERS.put(key, owner);
		return owner;
	}

	/** Read the same annotations and Fabric declarations the actual loaders consume; never initialize a class. */
	private static List<Ecosystem> initializationFamilies(Path jar, List<Ecosystem> declared) {
		Set<Ecosystem> found = new HashSet<>();
		try (JarFile zip = new JarFile(jar.toFile())) {
			List<ModAnnotationScanner.ModClassInfo> annotations = ModAnnotationScanner.scan(jar);
			for (Ecosystem family : List.of(Ecosystem.NEOFORGE, Ecosystem.FORGE)) {
				if (!declared.contains(family)) continue;
				String manifest = family == Ecosystem.NEOFORGE
						? ForbricModDiscoverer.NEOFORGE_MANIFEST : ForbricModDiscoverer.FORGE_MANIFEST;
				try (var in = zip.getInputStream(zip.getJarEntry(manifest))) {
					Set<String> ids = new HashSet<>();
					for (var mod : ModsTomlParser.parse(in).getMods()) ids.add(mod.getModId());
					if (annotations.stream().anyMatch(info -> info.family == family && ids.contains(info.modId))) {
						found.add(family);
					}
				}
			}
			if (declared.contains(Ecosystem.FABRIC)) {
				try (var in = zip.getInputStream(zip.getJarEntry(ForbricModDiscoverer.FABRIC_MANIFEST))) {
					KernelModMetadata metadata = FabricModMetadataParser.read(in);
					for (var group : metadata.getEntrypoints().entrySet()) {
						for (var entry : group.getValue()) {
							if (fabricEntryCanInitialize(zip, group.getKey(), entry)) found.add(Ecosystem.FABRIC);
						}
					}
				}
			}
		} catch (Exception error) {
			// Incomplete evidence must not suppress a possibly valid family. Discovery reports malformed metadata.
			ForbricLog.debug("[Forbric/MultiLoader] could not inspect entrypoints in %s: %s", jar.getFileName(), error);
			return List.of();
		}
		return declared.stream().filter(found::contains).toList();
	}

	private static boolean fabricEntryCanInitialize(JarFile zip, String key,
			KernelModMetadata.EntrypointDecl entry) throws java.io.IOException {
		// Custom language adapters and method/field references have their own contracts. Do not invent a
		// stricter one here; their declared entrypoint is positive evidence of a Fabric initialization path.
		if (!"default".equals(entry.adapter()) || entry.value().contains("::")) return true;
		String className = entry.value().replace('.', '/');
		var ownClass = zip.getJarEntry(className + ".class");
		if (ownClass != null) {
			ClassReader type;
			try (var in = zip.getInputStream(ownClass)) { type = new ClassReader(in); }
			if ((type.getAccess() & Opcodes.ACC_PUBLIC) == 0
					|| (type.getAccess() & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) != 0) return false;
			boolean[] noArgConstructor = {false};
			type.accept(new ClassVisitor(Opcodes.ASM9) {
				@Override public MethodVisitor visitMethod(int access, String name, String descriptor,
						String signature, String[] exceptions) {
					if (name.equals("<init>") && descriptor.equals("()V") && (access & Opcodes.ACC_PUBLIC) != 0) {
						noArgConstructor[0] = true;
					}
					return null;
				}
			}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			if (!noArgConstructor[0]) return false; // The default adapter instantiates through this constructor.
		}
		String contract = switch (key) {
			case "main" -> "net/fabricmc/api/ModInitializer";
			case "client" -> "net/fabricmc/api/ClientModInitializer";
			case "server" -> "net/fabricmc/api/DedicatedServerModInitializer";
			case "preLaunch" -> "net/fabricmc/loader/api/entrypoint/PreLaunchEntrypoint";
			default -> null; // An integration entrypoint's interface belongs to the mod consuming it.
		};
		return contract == null || mayImplement(zip, className, contract, new HashSet<>());
	}

	private static boolean mayImplement(JarFile zip, String name, String contract, Set<String> visited)
			throws java.io.IOException {
		if (name == null || name.startsWith("java/") || !visited.add(name)) return false;
		if (name.equals(contract)) return true;
		var entry = zip.getJarEntry(name + ".class");
		if (entry == null) return true; // A dependency may supply the initializer or its superclass/interface.
		ClassReader type;
		try (var in = zip.getInputStream(entry)) { type = new ClassReader(in); }
		for (String implemented : type.getInterfaces()) {
			if (mayImplement(zip, implemented, contract, visited)) return true;
		}
		return mayImplement(zip, type.getSuperName(), contract, visited);
	}

	/** True when {@code jar} is claimed by another family, so {@code mine} must skip it. Unowned jars are never skipped. */
	public static boolean suppressedFor(Path jar, Ecosystem mine) {
		Ecosystem owner = ownerOf(jar);
		return owner != null && owner != mine;
	}

	/**
	 * Which loader manifests the jar actually carries, in preference-independent (stable) order.
	 *
	 * <p>Public because a universal jar's LOSING ecosystems still have an identity that has to be handed back —
	 * {@code DuplicateModArbiter} reads this to work out which manifests to publish presence aliases from.
	 */
	public static List<Ecosystem> declaredBy(Path jar) {
		List<Ecosystem> declared = new ArrayList<>();
		try (JarFile zip = new JarFile(jar.toFile())) {
			if (zip.getEntry(ForbricModDiscoverer.NEOFORGE_MANIFEST) != null) declared.add(Ecosystem.NEOFORGE);
			if (zip.getEntry(ForbricModDiscoverer.FORGE_MANIFEST) != null) declared.add(Ecosystem.FORGE);
			if (zip.getEntry(ForbricModDiscoverer.FABRIC_MANIFEST) != null) declared.add(Ecosystem.FABRIC);
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/MultiLoader] could not read %s: %s", jar.getFileName(), String.valueOf(t));
		}
		return declared;
	}

	/**
	 * {@code -Dforbric.multiLoaderPreference} (csv of ecosystem names), else the documented default.
	 *
	 * <p>Package-visible so {@link DuplicateModArbiter} resolves cross-jar ties by the SAME order — one knob for
	 * both arbitrations, which is the only way "prefer Fabric on this instance" can mean one thing.
	 */
	static List<Ecosystem> preference() {
		String csv = System.getProperty("forbric.multiLoaderPreference");
		if (csv == null || csv.isBlank()) return DEFAULT_PREFERENCE;

		List<Ecosystem> order = new ArrayList<>();
		for (String raw : csv.split(",")) {
			// Ecosystem.parse, not valueOf: this knob has always taken "minecraftforge", and the constant is now
			// spelled FORGE because run/diff-oracle.sh pins that spelling independently of kernel code.
			Ecosystem parsed = Ecosystem.parse(raw);
			if (parsed != null) {
				order.add(parsed);
			} else {
				ForbricLog.warn("[Forbric/MultiLoader] ignoring unknown ecosystem '%s' in "
						+ "-Dforbric.multiLoaderPreference", raw.trim());
			}
		}
		return order.isEmpty() ? DEFAULT_PREFERENCE : order;
	}
}
