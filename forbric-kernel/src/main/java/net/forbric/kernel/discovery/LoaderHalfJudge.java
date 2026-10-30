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

package net.forbric.kernel.discovery;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.json.JsonFormat;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.metadata.forge.ForgeModEntry;
import net.forbric.kernel.metadata.forge.ForgeModsToml;
import net.forbric.kernel.metadata.forge.ModsTomlParser;
import net.forbric.kernel.util.Caught;

/**
 * Whether a mod jar can still load now that Forbric no longer runs MinecraftForge mods — judged one declared loader
 * HALF at a time, from the jar's bytes alone.
 *
 * <h2>Why per half, and not "does it have a mods.toml"</h2>
 *
 * <p>A jar declares a half per manifest it carries: {@code fabric.mod.json} (Fabric), {@code META-INF/neoforge.mods.toml}
 * (NeoForge) and {@code META-INF/mods.toml} (MinecraftForge). Refusing every jar that has a {@code mods.toml} would
 * refuse the universal jars too — FallingTree and collective ship all three manifests and one glue class per loader,
 * and Architectury-style mods ship a MinecraftForge half beside a real Fabric one. Those load perfectly well through
 * the halves that remain. What has to be refused is the jar whose OTHER halves are fake, and the measured corpus has
 * exactly that shape three times:
 * <ul>
 *   <li>{@code animalgarden-owl-…-forge} carries all three manifests, but its only {@code @Mod} is MinecraftForge's
 *       ({@code ModSetup(FMLJavaModLoadingContext)}) — and that same Forge class is what its {@code fabric.mod.json}
 *       names as the Fabric {@code main} entrypoint, which no Fabric loader can construct.</li>
 *   <li>{@code aquariuslibs-…-forge} carries a {@code neoforge.mods.toml} whose mod id's only {@code @Mod} is again
 *       MinecraftForge's; not one class in the jar names {@code net.neoforged}.</li>
 *   <li>{@code journeymap-forge} carries a {@code fabric.mod.json} with the id {@code journeymap-wrongloader} whose one
 *       entrypoint, {@code FabricError.onPreLaunch}, does nothing but throw "User Attempting to load JourneyMap for Forge
 *       in Fabric Loader". It is a notice, not a mod.</li>
 * </ul>
 *
 * <h2>The rule</h2>
 *
 * <p>A half is USABLE when what it declares really exists in the jar (its JarJar / jar-in-jar children included) and
 * does not stand only on MinecraftForge:
 * <ul>
 *   <li><b>Fabric</b> — the manifest reads, and either it declares no entrypoint at all (a library: it contributes
 *       classes and resources, which is all Fabric asks of it), or at least one entrypoint can initialise: for the four
 *       built-in contracts the class is present, public, concrete, has the public no-arg constructor the default adapter
 *       calls, may implement the contract, and its contract method does not consist of nothing but a {@code throw}. A
 *       custom language adapter or a {@code ::} member reference is positive evidence on its own; an integration key
 *       (any other key) counts when its class is present.</li>
 *   <li><b>NeoForge</b> — the manifest reads, and: a {@code net.neoforged} {@code @Mod} for one of its ids exists; or,
 *       without one (NeoForge loads a data, mixin or library mod with no entry class), the jar's own classes do not
 *       stand on MinecraftForge: none uses {@code net.minecraftforge} beyond a MinecraftForge {@code @Mod} annotation,
 *       or some class names NeoForge. A MinecraftForge {@code @Mod} on its own decides nothing — NeoForge ignores it,
 *       and the Modrinth datapack wrappers and SimpleRPC put one on a no-argument dummy class and use nothing else of
 *       Forge — while aquariuslibs' helpers and animalgarden-owl's {@code FMLJavaModLoadingContext} constructor are
 *       MinecraftForge code a NeoForge half cannot run.</li>
 *   <li><b>MinecraftForge</b> — never usable: Forbric no longer loads MinecraftForge mods.</li>
 * </ul>
 * <p>A jar is REFUSED only when it has a MinecraftForge half and NO usable Fabric or NeoForge half. Every other jar is
 * left to {@code MultiLoaderArbiter}, which chooses among the usable halves.
 *
 * <p>A declared mixin config the jar does not carry is named in the half's reason and decides nothing. Multiloader jars
 * routinely over-declare a sibling loader's config (a {@code [[mixins]]} block listing a {@code *.neoforge.mixins.json}
 * that only ships in the NeoForge build), and discovery already drops such a config with a warning; refusing the jar
 * over it would refuse mods that load today. Whether a half is real is answered by its initialiser, which is what the
 * fake halves in the corpus lack.
 *
 * <h2>Pure</h2>
 *
 * <p>Bytes in, verdict out: no class is loaded, nothing is logged, no kernel state is read or written, and a manifest
 * that cannot be parsed is a half with a reason rather than an exception. That is so the sweep tooling can ask the same
 * question of a thousand jars without booting anything, and get exactly the answer the boot gets.
 */
public final class LoaderHalfJudge {
	public static final String FABRIC_MANIFEST = ForbricModDiscoverer.FABRIC_MANIFEST;
	public static final String NEOFORGE_MANIFEST = ForbricModDiscoverer.NEOFORGE_MANIFEST;
	public static final String FORGE_MANIFEST = ForbricModDiscoverer.FORGE_MANIFEST;

	/** How deep jar-in-jar is followed. Real mods nest two levels (Tectonic → apollib → json5); four is generous. */
	private static final int MAX_NESTING = 4;

	private static final String MOD_FORGE = ModAnnotationScanner.MOD_DESC_MINECRAFTFORGE;
	private static final String MOD_NEO = ModAnnotationScanner.MOD_DESC_NEOFORGE;
	private static final byte[] FORGE_PACKAGE = "net/minecraftforge/".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] NEO_PACKAGE = "net/neoforged/".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] FORGE_MOD_DESCRIPTOR = MOD_FORGE.getBytes(StandardCharsets.US_ASCII);

	/** The Fabric entrypoint keys whose contract is the loader's own, and the method that contract calls. */
	private static final Map<String, String[]> CONTRACTS = Map.of(
			"main", new String[] {"net/fabricmc/api/ModInitializer", "onInitialize"},
			"client", new String[] {"net/fabricmc/api/ClientModInitializer", "onInitializeClient"},
			"server", new String[] {"net/fabricmc/api/DedicatedServerModInitializer", "onInitializeServer"},
			"preLaunch", new String[] {"net/fabricmc/loader/api/entrypoint/PreLaunchEntrypoint", "onPreLaunch"});

	private LoaderHalfJudge() {
	}

	/**
	 * One declared half and what was found.
	 *
	 * @param family   FABRIC, NEOFORGE, or FORGE (which here, as everywhere, means traditional MinecraftForge)
	 * @param manifest the manifest path that declares it
	 * @param modIds   the ids the manifest declares (empty when it could not be read)
	 * @param name     the display name the manifest gives its first mod, else its first id, else empty
	 * @param usable   whether this half can load the jar on Forbric
	 * @param reason   why, in one English clause — for logs, the sweep tool and the finding's evidence
	 */
	public record Half(Ecosystem family, String manifest, List<String> modIds, String name, boolean usable,
			String reason) {
		public Half {
			modIds = List.copyOf(modIds);
			name = name == null ? "" : name;
		}

		@Override
		public String toString() {
			return manifest + ": " + (family == Ecosystem.FORGE ? "MinecraftForge" : usable ? "usable" : "not usable")
					+ " (" + reason + ")";
		}
	}

	/** Every half a jar declares, in manifest order: Fabric, NeoForge, MinecraftForge. */
	public record Verdict(String jar, List<Half> halves) {
		public Verdict {
			halves = List.copyOf(halves);
		}

		public Optional<Half> half(Ecosystem family) {
			return halves.stream().filter(h -> h.family() == family).findFirst();
		}

		/** Whether the jar declares a MinecraftForge half at all. */
		public boolean hasMinecraftForge() {
			return half(Ecosystem.FORGE).isPresent();
		}

		/** The Fabric / NeoForge halves that can load the jar, in preference-independent order. */
		public List<Ecosystem> usableFamilies() {
			return halves.stream().filter(h -> h.family() != Ecosystem.FORGE && h.usable()).map(Half::family).toList();
		}

		/** THE rule: a MinecraftForge half, and no usable Fabric or NeoForge half to load the jar through instead. */
		public boolean refused() {
			return hasMinecraftForge() && usableFamilies().isEmpty();
		}

		/** The id that names this mod to a player: the MinecraftForge half's first, else any half's, else the file. */
		public String modId() {
			for (Ecosystem family : List.of(Ecosystem.FORGE, Ecosystem.NEOFORGE, Ecosystem.FABRIC)) {
				Optional<Half> half = half(family);
				if (half.isPresent() && !half.get().modIds().isEmpty()) return half.get().modIds().get(0);
			}
			return "jar:" + jar;
		}

		/** The display name that names this mod to a player, by the same order as {@link #modId()}. */
		public String displayName() {
			for (Ecosystem family : List.of(Ecosystem.FORGE, Ecosystem.NEOFORGE, Ecosystem.FABRIC)) {
				Optional<Half> half = half(family);
				if (half.isPresent() && !half.get().name().isBlank()) return half.get().name();
			}
			String id = modId();
			return id.startsWith("jar:") ? jar : id;
		}

		/** Every half and its reason, on one line. */
		public String describe() {
			List<String> parts = new ArrayList<>();
			for (Half half : halves) parts.add(half.toString());
			return parts.isEmpty() ? "declares no loader manifest" : String.join("; ", parts);
		}
	}

	/** Judges the jar at {@code jar}. Throws only when the file itself cannot be read as a zip. */
	public static Verdict judge(Path jar) throws IOException {
		return judge(String.valueOf(jar.getFileName()), read(jar, 0));
	}

	/**
	 * Judges a jar given as bytes; {@code name} is only what the verdict calls it. Throws only when the bytes are not
	 * a zip at all — a jar whose manifests or classes are broken is still judged, half by half.
	 */
	public static Verdict judge(String name, byte[] jar) throws IOException {
		return judge(name, read(jar, 0));
	}

	private static Verdict judge(String name, Contents contents) {
		List<Half> halves = new ArrayList<>();
		if (contents.files.containsKey(FABRIC_MANIFEST)) halves.add(fabricHalf(contents));
		if (contents.files.containsKey(NEOFORGE_MANIFEST)) halves.add(neoForgeHalf(contents));
		if (contents.files.containsKey(FORGE_MANIFEST)) halves.add(forgeHalf(contents));
		return new Verdict(name, halves);
	}

	// -------------------------------------------------------------------------------------------------------------
	// The halves
	// -------------------------------------------------------------------------------------------------------------

	private static Half fabricHalf(Contents contents) {
		UnmodifiableConfig json;
		try {
			json = JsonFormat.minimalInstance().createParser().parse(
					new String(contents.files.get(FABRIC_MANIFEST), StandardCharsets.UTF_8));
		} catch (RuntimeException unreadable) {
			// The manifest cannot be read, which is this half's answer: Fabric cannot load the jar through it.
			Caught.expected(unreadable);
			return new Half(Ecosystem.FABRIC, FABRIC_MANIFEST, List.of(), "", false,
					"fabric.mod.json cannot be read: " + message(unreadable));
		}
		String id = string(json.get("id"));
		List<String> ids = id == null ? List.of() : List.of(id);
		String name = string(json.get("name"));
		if (name == null) name = id;

		String absent = absentConfigs(contents, fabricMixins(json.get("mixins")));

		Map<String, List<String[]>> entrypoints = fabricEntrypoints(json.get("entrypoints"));
		if (entrypoints.values().stream().allMatch(List::isEmpty)) {
			return new Half(Ecosystem.FABRIC, FABRIC_MANIFEST, ids, name, true,
					"a library: no entrypoints, it contributes classes and resources" + absent);
		}
		List<String> failures = new ArrayList<>();
		for (Map.Entry<String, List<String[]>> group : entrypoints.entrySet()) {
			for (String[] entry : group.getValue()) {
				String adapter = entry[0], value = entry[1];
				String verdict = fabricEntrypoint(contents, group.getKey(), adapter, value);
				if (verdict == null) {
					return new Half(Ecosystem.FABRIC, FABRIC_MANIFEST, ids, name, true,
							"entrypoint " + group.getKey() + " " + value + " can initialise" + absent);
				}
				failures.add(verdict);
			}
		}
		return new Half(Ecosystem.FABRIC, FABRIC_MANIFEST, ids, name, false,
				"no entrypoint can initialise: " + String.join("; ", failures) + absent);
	}

	/** {@code ""}, or a clause naming the declared mixin configs the jar does not carry; see the class note. */
	private static String absentConfigs(Contents contents, List<String> configs) {
		List<String> absent = configs.stream().filter(config -> !contents.hasFile(config)).toList();
		return absent.isEmpty() ? "" : " (declared mixin config(s) " + String.join(", ", absent)
				+ " not in the jar; discovery drops them)";
	}

	/** Null when the entrypoint can initialise; otherwise why not. */
	private static String fabricEntrypoint(Contents contents, String key, String adapter, String value) {
		// A custom adapter (Kotlin, Scala) or a method/field reference has its own contract. Its declaration is positive
		// evidence of a Fabric initialisation path, and inventing a stricter contract here would refuse a real mod.
		if (!"default".equals(adapter) || value.contains("::")) return null;
		String className = value.replace('.', '/');
		ClassFacts type = contents.classes.get(className);
		if (type == null) return key + " " + value + " is not in the jar";
		String[] contract = CONTRACTS.get(key);
		if (contract == null) return null; // an integration key: the mod consuming it owns the interface
		if ((type.access & Opcodes.ACC_PUBLIC) == 0 || (type.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) != 0) {
			return key + " " + value + " is not a public concrete class";
		}
		if (!type.publicNoArgConstructor) {
			return key + " " + value + " has no public no-argument constructor (" + type.constructorSummary() + ")";
		}
		if (!mayImplement(contents, className, contract[0], new HashSet<>())) {
			return key + " " + value + " does not implement " + contract[0].replace('/', '.');
		}
		Boolean throwsOnly = type.throwsOnly.get(contract[1]);
		if (Boolean.TRUE.equals(throwsOnly)) {
			return key + " " + value + "." + contract[1] + " does nothing but throw (a wrong-loader notice, not a mod)";
		}
		return null;
	}

	/** True unless the in-jar hierarchy proves the class cannot implement {@code contract}. */
	private static boolean mayImplement(Contents contents, String name, String contract, Set<String> visited) {
		if (name == null || name.startsWith("java/") || !visited.add(name)) return false;
		if (name.equals(contract)) return true;
		ClassFacts type = contents.classes.get(name);
		if (type == null) return true; // a dependency may supply the superclass or interface
		for (String implemented : type.interfaces) {
			if (mayImplement(contents, implemented, contract, visited)) return true;
		}
		return mayImplement(contents, type.superName, contract, visited);
	}

	private static Half neoForgeHalf(Contents contents) {
		ForgeModsToml toml;
		try {
			toml = ModsTomlParser.parse(new ByteArrayInputStream(contents.files.get(NEOFORGE_MANIFEST)));
		} catch (RuntimeException unreadable) {
			Caught.expected(unreadable);
			return new Half(Ecosystem.NEOFORGE, NEOFORGE_MANIFEST, List.of(), "", false,
					"neoforge.mods.toml cannot be read: " + message(unreadable));
		}
		List<String> ids = ids(toml);
		String name = name(toml);
		String absent = absentConfigs(contents, toml.getMixinConfigs());
		for (String id : ids) {
			String neo = contents.modClass(MOD_NEO, id);
			if (neo != null) {
				return new Half(Ecosystem.NEOFORGE, NEOFORGE_MANIFEST, ids, name, true,
						"net.neoforged @Mod(\"" + id + "\") is " + neo.replace('/', '.') + absent);
			}
		}
		// No NeoForge @Mod: NeoForge loads such a mod without an entry class (data, mixins, services). A MinecraftForge
		// @Mod for the id is ignored by NeoForge, so on its own it decides nothing — the Modrinth datapack wrappers and
		// SimpleRPC carry one on a no-argument dummy class and nothing else of Forge. What decides is whether the code
		// stands on MinecraftForge: classes that use net.minecraftforge beyond that annotation, and none naming NeoForge.
		String forgeMod = null;
		for (String id : ids) {
			String forge = contents.modClass(MOD_FORGE, id);
			if (forge != null) {
				forgeMod = " (its @Mod(\"" + id + "\") is MinecraftForge's " + forge.replace('/', '.') + ")";
				break;
			}
		}
		if (contents.forgeBound > 0 && contents.neoReferences == 0) {
			return new Half(Ecosystem.NEOFORGE, NEOFORGE_MANIFEST, ids, name, false,
					"no NeoForge @Mod, and its code links only against MinecraftForge (" + contents.forgeBound
							+ " class(es) use net.minecraftforge beyond a @Mod annotation, none names net.neoforged)"
							+ (forgeMod == null ? "" : forgeMod) + absent);
		}
		return new Half(Ecosystem.NEOFORGE, NEOFORGE_MANIFEST, ids, name, true,
				"no NeoForge @Mod: a data, mixin or library mod, and its code does not stand on MinecraftForge"
						+ (forgeMod == null ? "" : forgeMod + ", which NeoForge ignores") + absent);
	}

	private static Half forgeHalf(Contents contents) {
		List<String> ids = List.of();
		String name = "";
		String detail = "a MinecraftForge mods.toml";
		try {
			ForgeModsToml toml = ModsTomlParser.parse(new ByteArrayInputStream(contents.files.get(FORGE_MANIFEST)));
			ids = ids(toml);
			name = name(toml);
			if (!ids.isEmpty()) detail += " declaring " + String.join(", ", ids);
			// Native NeoForge 26.2 reads neoforge.mods.toml only, so a NeoForge build that kept the legacy file name is
			// not loaded there either; saying so tells the reader of the sweep which of the two it is looking at.
			for (String id : ids) {
				String neo = contents.modClass(MOD_NEO, id);
				if (neo != null && contents.modClass(MOD_FORGE, id) == null) {
					detail += " (its @Mod " + neo.replace('/', '.') + " is NeoForge's, under the pre-20.5 manifest name "
							+ "that NeoForge itself no longer reads)";
					break;
				}
			}
		} catch (RuntimeException unreadable) {
			Caught.expected(unreadable);
			detail = "a MinecraftForge mods.toml that cannot be read (" + message(unreadable) + ")";
		}
		return new Half(Ecosystem.FORGE, FORGE_MANIFEST, ids, name, false,
				detail + " — Forbric no longer loads MinecraftForge mods");
	}

	private static List<String> ids(ForgeModsToml toml) {
		List<String> ids = new ArrayList<>();
		for (ForgeModEntry mod : toml.getMods()) {
			if (mod.getModId() != null && !mod.getModId().isBlank() && !ids.contains(mod.getModId())) ids.add(mod.getModId());
		}
		return ids;
	}

	private static String name(ForgeModsToml toml) {
		for (ForgeModEntry mod : toml.getMods()) {
			if (mod.getDisplayName() != null && !mod.getDisplayName().isBlank()) return mod.getDisplayName();
			if (mod.getModId() != null && !mod.getModId().isBlank()) return mod.getModId();
		}
		return "";
	}

	// -------------------------------------------------------------------------------------------------------------
	// fabric.mod.json, read leniently: real files carry comments and trailing commas
	// -------------------------------------------------------------------------------------------------------------

	private static List<String> fabricMixins(Object value) {
		if (!(value instanceof List<?> list)) return List.of();
		List<String> configs = new ArrayList<>();
		for (Object element : list) {
			if (element instanceof String config) {
				configs.add(config);
			} else if (element instanceof UnmodifiableConfig object && string(object.get("config")) != null) {
				configs.add(string(object.get("config")));
			}
		}
		return configs;
	}

	/** key -> [adapter, value] per declared entrypoint. */
	private static Map<String, List<String[]>> fabricEntrypoints(Object value) {
		Map<String, List<String[]>> out = new java.util.LinkedHashMap<>();
		if (!(value instanceof UnmodifiableConfig groups)) return out;
		for (UnmodifiableConfig.Entry group : groups.entrySet()) {
			List<String[]> entries = new ArrayList<>();
			Object raw = group.getValue();
			List<?> list = raw instanceof List<?> l ? l : List.of(raw);
			for (Object element : list) {
				if (element instanceof String entry && !entry.isBlank()) {
					entries.add(new String[] {"default", entry.strip()});
				} else if (element instanceof UnmodifiableConfig object && string(object.get("value")) != null) {
					String adapter = string(object.get("adapter"));
					entries.add(new String[] {adapter == null ? "default" : adapter, string(object.get("value")).strip()});
				}
			}
			out.put(group.getKey(), entries);
		}
		return out;
	}

	private static String string(Object value) {
		return value instanceof String s && !s.isBlank() ? s : null;
	}

	private static String message(Throwable error) {
		String message = error.getMessage();
		return message == null || message.isBlank() ? error.getClass().getSimpleName() : message.strip();
	}

	// -------------------------------------------------------------------------------------------------------------
	// Reading a jar, and the jars it nests
	// -------------------------------------------------------------------------------------------------------------

	/** What one jar (with its nested jars folded in for existence questions) holds. */
	private static final class Contents {
		/** The few files whose bytes a judgement needs: the three manifests, at the outer jar's root only. */
		final Map<String, byte[]> files = new HashMap<>();
		/** Every entry name, the nested jars' included — a declared mixin config may live in either. */
		final Set<String> names = new HashSet<>();
		/** Every class, the nested jars' included; the outer jar's copy wins a name both carry. */
		final Map<String, ClassFacts> classes = new HashMap<>();
		/**
		 * How many of the OUTER jar's classes use net.minecraftforge for anything but a MinecraftForge {@code @Mod}
		 * annotation — a dummy entry class that is only annotated stands on nothing — and how many name NeoForge.
		 */
		int forgeBound;
		int neoReferences;

		boolean hasFile(String name) {
			return names.contains(name);
		}

		/** The class carrying {@code @Mod(id)} of the given annotation, or null. */
		String modClass(String descriptor, String id) {
			List<String> found = new ArrayList<>();
			for (ClassFacts type : classes.values()) {
				if (id.equals(type.mods.get(descriptor))) found.add(type.name);
			}
			Collections.sort(found);
			return found.isEmpty() ? null : found.get(0);
		}
	}

	/** Reads a jar given as a file: through {@link ZipFile}, which reads every entry layout a loader accepts. */
	private static Contents read(Path jar, int depth) throws IOException {
		Contents contents = new Contents();
		List<byte[]> nested = new ArrayList<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (var entries = zip.entries(); entries.hasMoreElements(); ) {
				ZipEntry entry = entries.nextElement();
				try (InputStream in = zip.getInputStream(entry)) {
					accept(contents, entry, in, nested, depth);
				}
			}
		}
		return withNested(contents, nested, depth);
	}

	/**
	 * Reads a jar given as bytes. Streamed first; a jar whose STORED entries carry data descriptors cannot be streamed
	 * ({@link ZipInputStream} refuses them) and is read from a temporary copy instead, as a loader would read the file.
	 */
	private static Contents read(byte[] jar, int depth) throws IOException {
		Contents contents = new Contents();
		List<byte[]> nested = new ArrayList<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(jar))) {
			boolean any = false;
			for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
				any = true;
				accept(contents, entry, in, nested, depth);
			}
			if (!any) throw new IOException("not a jar: no zip entries");
		} catch (java.util.zip.ZipException unstreamable) {
			// The answer is "read it as a file"; a file that is not a zip either throws from there.
			Caught.expected(unstreamable);
			Path copy = Files.createTempFile("forbric-judge-", ".jar");
			try {
				Files.write(copy, jar);
				return read(copy, depth);
			} finally {
				Files.deleteIfExists(copy);
			}
		}
		return withNested(contents, nested, depth);
	}

	private static void accept(Contents contents, ZipEntry entry, InputStream in, List<byte[]> nested, int depth)
			throws IOException {
		if (entry.isDirectory()) return;
		String name = entry.getName();
		contents.names.add(name);
		if (name.equals(FABRIC_MANIFEST) || name.equals(NEOFORGE_MANIFEST) || name.equals(FORGE_MANIFEST)) {
			contents.files.put(name, in.readAllBytes());
		} else if (name.endsWith(".class") && !name.startsWith("META-INF/versions/")) {
			byte[] bytes = in.readAllBytes();
			ClassFacts facts = ClassFacts.of(bytes);
			if (facts == null) return;
			contents.classes.putIfAbsent(facts.name, facts);
			// A constant-pool string appears once per class, so a class whose only net.minecraftforge string is the
			// @Mod descriptor has exactly as many of the one as of the other.
			if (count(bytes, FORGE_PACKAGE) > count(bytes, FORGE_MOD_DESCRIPTOR)) contents.forgeBound++;
			if (count(bytes, NEO_PACKAGE) > 0) contents.neoReferences++;
		} else if (depth < MAX_NESTING && name.endsWith(".jar")
				&& (name.startsWith("META-INF/jarjar/") || name.startsWith("META-INF/jars/"))) {
			nested.add(in.readAllBytes());
		}
	}

	private static Contents withNested(Contents contents, List<byte[]> nested, int depth) {
		for (byte[] child : nested) {
			Contents inner;
			try {
				inner = read(child, depth + 1);
			} catch (IOException unreadable) {
				// A nested file that is not a jar holds no classes; the outer jar is judged on what it does hold.
				Caught.expected(unreadable);
				continue;
			}
			contents.names.addAll(inner.names);
			inner.classes.forEach(contents.classes::putIfAbsent);
		}
		return contents;
	}

	/** How many times {@code needle} occurs in {@code haystack}; the strings searched for are plain ASCII. */
	private static int count(byte[] haystack, byte[] needle) {
		int found = 0;
		outer:
		for (int i = 0, last = haystack.length - needle.length; i <= last; i++) {
			if (haystack[i] != needle[0]) continue;
			for (int j = 1; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) continue outer;
			}
			found++;
		}
		return found;
	}

	/** What a judgement needs to know about one class: its shape, its {@code @Mod}s, and which entry methods only throw. */
	private static final class ClassFacts extends ClassVisitor {
		String name;
		int access;
		String superName;
		String[] interfaces = new String[0];
		boolean publicNoArgConstructor;
		final List<String> constructors = new ArrayList<>();
		/** {@code @Mod} descriptor -> its value. */
		final Map<String, String> mods = new HashMap<>();
		/** Entry-method name -> whether its body ends every path in {@code athrow}. */
		final Map<String, Boolean> throwsOnly = new HashMap<>();

		private ClassFacts() {
			super(Opcodes.ASM9);
		}

		static ClassFacts of(byte[] bytes) {
			ClassFacts facts = new ClassFacts();
			try {
				new ClassReader(bytes).accept(facts, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			} catch (RuntimeException malformed) {
				// A class ASM cannot parse cannot be an entrypoint or carry an @Mod a loader would find either.
				Caught.warn(malformed, "[Forbric/Discovery] a class in a mod jar could not be parsed; it is left out of the "
						+ "loader-half judgement");
				return null;
			}
			return facts.name == null ? null : facts;
		}

		String constructorSummary() {
			return constructors.isEmpty() ? "none" : String.join(", ", new LinkedHashSet<>(constructors));
		}

		@Override
		public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
			this.name = name;
			this.access = access;
			this.superName = superName;
			this.interfaces = interfaces == null ? new String[0] : interfaces;
		}

		@Override
		public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
			if (!MOD_FORGE.equals(descriptor) && !MOD_NEO.equals(descriptor)) return null;
			return new AnnotationVisitor(Opcodes.ASM9) {
				@Override
				public void visit(String key, Object value) {
					if ("value".equals(key) && value instanceof String id) mods.put(descriptor, id);
				}
			};
		}

		@Override
		public MethodVisitor visitMethod(int access, String method, String descriptor, String signature,
				String[] exceptions) {
			if (method.equals("<init>")) {
				constructors.add(descriptor);
				if (descriptor.equals("()V") && (access & Opcodes.ACC_PUBLIC) != 0) publicNoArgConstructor = true;
				return null;
			}
			if (!descriptor.equals("()V") || (access & Opcodes.ACC_ABSTRACT) != 0) return null;
			boolean entry = false;
			for (String[] contract : CONTRACTS.values()) entry |= contract[1].equals(method);
			if (!entry) return null;
			return new MethodVisitor(Opcodes.ASM9) {
				boolean returns;
				boolean throwsSomething;

				@Override
				public void visitInsn(int opcode) {
					if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) returns = true;
					if (opcode == Opcodes.ATHROW) throwsSomething = true;
				}

				@Override
				public void visitEnd() {
					throwsOnly.put(method, throwsSomething && !returns);
				}
			};
		}
	}

	/** For a caller holding a stream rather than bytes. */
	public static Verdict judge(String name, InputStream jar) throws IOException {
		return judge(name, jar.readAllBytes());
	}
}
