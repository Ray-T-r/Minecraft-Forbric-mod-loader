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
import net.forbric.kernel.discovery.LoaderHalfJudge;
import net.forbric.kernel.discovery.MetadataFailures;
import net.forbric.kernel.discovery.ModAnnotationScanner;
import net.forbric.kernel.fabric.FabricModMetadataParser;
import net.forbric.kernel.fabric.KernelModMetadata;
import net.forbric.kernel.metadata.forge.ModsTomlParser;
import net.forbric.kernel.util.Caught;
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
 * {@code -Dforbric.multiLoaderPreference=neoforge,fabric}.
 *
 * <p>The default order prefers NeoForge over Fabric because the NeoForge baseline is ALWAYS present on the base,
 * whereas a jar's Fabric side typically declares a dependency on fabric-api that the user may not have installed.
 *
 * <h2>MinecraftForge is refused, not arbitrated</h2>
 *
 * <p>Forbric no longer loads MinecraftForge mods. A jar's MinecraftForge half is never a candidate: a jar that also has
 * a usable Fabric or NeoForge half (as {@link LoaderHalfJudge} judges it) is chosen among those alone — a Forge+Fabric
 * Architectury-style jar now loads as Fabric and says so — and a jar with nothing else to load through is REFUSED.
 * A refused jar's owner is {@link Ecosystem#FORGE}, held as a refused state: {@link #suppressedFor} is true for every
 * family, FORGE included, so no loader constructs it, registers its mixins or reads its access transformer. It must
 * not be {@code null}: null is this class's word for "a plain library, nobody claims it", and every pass treats an
 * unowned jar as one to keep — it would put the refused jar's Forge classes, mixins and ATs on the game.
 * {@code minecraftforge} in {@code -Dforbric.multiLoaderPreference} is warned about and ignored.
 */
public final class MultiLoaderArbiter {
	private static final List<Ecosystem> DEFAULT_PREFERENCE = List.of(Ecosystem.NEOFORGE, Ecosystem.FABRIC);
	static final String ENTRYPOINT_SWITCH = "forbric.multiLoaderEntrypoints";

	/** jar path -> the ecosystem that owns it. Computed once per jar; discovery order is stable. */
	private static final Map<String, Ecosystem> OWNERS = new LinkedHashMap<>();
	/** The jars refused as MinecraftForge-only builds; their entry in {@link #OWNERS} is FORGE. */
	private static final Set<String> REFUSED = new HashSet<>();

	private MultiLoaderArbiter() {
	}

	/** Forgets every decision — for tests, and so a re-launch in one process re-arbitrates. */
	public static synchronized void reset() {
		OWNERS.clear();
		REFUSED.clear();
	}

	/**
	 * Whether {@code jar} is refused: a MinecraftForge build with no usable Fabric or NeoForge half. Nothing of it
	 * loads; discovery has recorded why ({@link MetadataFailures#unsupported(Path)}).
	 */
	public static synchronized boolean refused(Path jar) {
		ownerOf(jar);
		return REFUSED.contains(jar.toAbsolutePath().toString());
	}

	/**
	 * The ecosystem that owns {@code jar}. A jar declaring exactly one family is owned by it (the common case, no
	 * log); a jar declaring several is arbitrated by preference and logged ONCE, naming what was suppressed.
	 * Returns {@code null} when the jar declares no loader manifest at all (a plain library — nobody claims it, and
	 * every path should treat it as unowned rather than as "not mine"). Never returns FORGE except for a
	 * {@linkplain #refused refused} jar.
	 */
	public static synchronized Ecosystem ownerOf(Path jar) {
		String key = jar.toAbsolutePath().toString();
		if (OWNERS.containsKey(key)) return OWNERS.get(key);

		List<Ecosystem> declared = declaredBy(jar);
		// Every manifest the jar carries, for the log line that says what was suppressed: `declared` itself narrows to
		// the halves that can load it.
		List<Ecosystem> manifests = declared;
		boolean forgeSetAside = false;
		if (declared.contains(Ecosystem.FORGE)) {
			LoaderHalfJudge.Verdict verdict = MetadataFailures.verdict(jar);
			if (verdict == null || verdict.refused()) {
				// Discovery recorded the refusal (or could not open the jar at all); here it only has to hold.
				REFUSED.add(key);
				OWNERS.put(key, Ecosystem.FORGE);
				ForbricLog.warn("[Forbric/MultiLoader] %s is refused — a MinecraftForge build with no usable Fabric or "
						+ "NeoForge half; no loader loads any of it (%s)", jar.getFileName(),
						verdict == null ? "the jar could not be judged" : verdict.describe());
				return Ecosystem.FORGE;
			}
			List<Ecosystem> usable = verdict.usableFamilies();
			declared = declared.stream().filter(family -> family != Ecosystem.FORGE && usable.contains(family)).toList();
			forgeSetAside = true;
			ForbricLog.info("[Forbric/MultiLoader] %s: its MinecraftForge half is set aside — Forbric no longer loads "
					+ "MinecraftForge mods — choosing among its usable %s half(s)", jar.getFileName(), declared);
		}
		if (declared.size() > 1) {
			// A family whose manifest cannot be read cannot load the jar either. Choosing it by preference left the
			// jar to nobody: EntityCount ships a valid fabric.mod.json and a neoforge.mods.toml with the range
			// "[26.2,26.23", NeoForge won the arbitration, NeoForge could not read it, and Fabric had been
			// suppressed — on native Fabric the same file loads. Choose among the families that can be read.
			java.util.Set<Ecosystem> unreadable = MetadataFailures.failedFamilies(jar);
			List<Ecosystem> readable = declared.stream().filter(family -> !unreadable.contains(family)).toList();
			if (!readable.isEmpty() && readable.size() < declared.size()) {
				ForbricLog.info("[Forbric/MultiLoader] %s: the %s manifest cannot be read — choosing among %s",
						jar.getFileName(), unreadable, readable);
				declared = readable;
			}
		}
		Ecosystem owner = null;
		if (declared.size() == 1) {
			owner = declared.get(0);
		} else if (declared.size() > 1) {
			List<Ecosystem> candidates = declared;
			boolean byInitialization = false;
			if (!"off".equalsIgnoreCase(System.getProperty(ENTRYPOINT_SWITCH, "on"))) {
				List<Ecosystem> initialized = initializationFamilies(jar, declared);
				// No initializer anywhere is a legitimate data-only/library jar, not a reason to reject it.
				if (!initialized.isEmpty()) {
					candidates = initialized;
					byInitialization = true;
				} else {
					// But a mixin-only mod runs through its mixins, and only the halves that ship a mixin config can
					// apply them. qubes-mouse-fix declares its client mixins in fabric.mod.json and nowhere a NeoForge
					// half reads (its MinecraftForge half had them in the manifest's MixinConfigs); chosen by preference
					// it loaded as NeoForge and silently did nothing, where it used to work as MinecraftForge.
					List<Ecosystem> mixins = mixinFamilies(jar, declared);
					if (!mixins.isEmpty()) candidates = mixins;
				}
			}
			for (Ecosystem candidate : preference()) {
				if (candidates.contains(candidate)) {
					owner = candidate;
					break;
				}
			}
			if (owner == null) owner = candidates.get(0); // preference listed none of them — stay deterministic
			if (byInitialization && !candidates.equals(declared)) {
				List<Ecosystem> metadataOnly = new ArrayList<>(declared);
				metadataOnly.removeAll(candidates);
				ForbricLog.info("[Forbric/MultiLoader] %s has initialization code for %s; ignoring manifest-only "
						+ "claims %s so its real entrypoint is not suppressed", jar.getFileName(), candidates, metadataOnly);
			}
		}
		// The universal-jar line, over every manifest the jar carries: a set-aside MinecraftForge half is named among
		// what was suppressed, so a Forge+Fabric jar that now loads as Fabric says so in the same words.
		if (owner != null && (declared.size() > 1 || forgeSetAside && manifests.size() > 1)) {
			List<Ecosystem> named = forgeSetAside ? manifests : declared;
			List<Ecosystem> suppressed = new ArrayList<>(named);
			suppressed.remove(owner);
			ForbricLog.info("[Forbric/MultiLoader] %s declares %d loaders — loading it as %s only, suppressing %s "
					+ "(a universal jar would otherwise initialise once per live ecosystem)",
					jar.getFileName(), named.size(), owner, suppressed);
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
			Caught.warn(error, "[Forbric/MultiLoader] could not inspect entrypoints in %s; no family of it is suppressed",
					jar.getFileName());
			return List.of();
		}
		return declared.stream().filter(found::contains).toList();
	}

	/**
	 * The declared families whose manifest names a mixin config the jar ships: NeoForge's {@code [[mixins]]} and
	 * Fabric's {@code mixins}. Asked only when no half has initialization code, so a mixin-only mod is given to a half
	 * that applies its mixins. Unreadable evidence suppresses nothing.
	 */
	private static List<Ecosystem> mixinFamilies(Path jar, List<Ecosystem> declared) {
		Set<Ecosystem> found = new HashSet<>();
		try (JarFile zip = new JarFile(jar.toFile())) {
			if (declared.contains(Ecosystem.NEOFORGE)) {
				try (var in = zip.getInputStream(zip.getJarEntry(ForbricModDiscoverer.NEOFORGE_MANIFEST))) {
					if (ModsTomlParser.parse(in).getMixinConfigs().stream().anyMatch(config -> zip.getJarEntry(config) != null)) {
						found.add(Ecosystem.NEOFORGE);
					}
				}
			}
			if (declared.contains(Ecosystem.FABRIC)) {
				try (var in = zip.getInputStream(zip.getJarEntry(ForbricModDiscoverer.FABRIC_MANIFEST))) {
					if (FabricModMetadataParser.read(in).getMixinConfigs().stream()
							.anyMatch(config -> zip.getJarEntry(config.config()) != null)) {
						found.add(Ecosystem.FABRIC);
					}
				}
			}
		} catch (Exception error) {
			Caught.warn(error, "[Forbric/MultiLoader] could not inspect mixin configs in %s; no family of it is suppressed",
					jar.getFileName());
			return List.of();
		}
		List<Ecosystem> families = declared.stream().filter(found::contains).toList();
		if (!families.isEmpty() && families.size() < declared.size()) {
			ForbricLog.info("[Forbric/MultiLoader] %s has no initialization code in any half; only %s ships its mixins, "
					+ "so that half loads it", jar.getFileName(), families);
		}
		return families;
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
		// A refused jar is suppressed for every family, MinecraftForge's own included: its owner FORGE is a refusal,
		// not a claim.
		return owner != null && (owner != mine || refused(jar));
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
			Caught.warn(t, "[Forbric/MultiLoader] could not read %s; it is taken to declare %s", jar.getFileName(),
					declared);
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
			if (parsed == Ecosystem.FORGE) {
				// Read, so an old launch profile that names it is told why it no longer means anything, and not
				// obeyed: preferring MinecraftForge would choose a half that is never loaded.
				ForbricLog.warn("[Forbric/MultiLoader] ignoring '%s' in -Dforbric.multiLoaderPreference — Forbric no "
						+ "longer loads MinecraftForge mods; the order is read as if it were not there", raw.trim());
			} else if (parsed != null) {
				order.add(parsed);
			} else {
				ForbricLog.warn("[Forbric/MultiLoader] ignoring unknown ecosystem '%s' in "
						+ "-Dforbric.multiLoaderPreference", raw.trim());
			}
		}
		return order.isEmpty() ? DEFAULT_PREFERENCE : order;
	}
}
