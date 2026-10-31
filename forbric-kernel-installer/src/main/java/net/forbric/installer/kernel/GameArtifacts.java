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

package net.forbric.installer.kernel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * The two heavy jars a Forbric instance runs on, when someone supplies them instead of letting the installer build
 * them: NeoForge's patched game (the base) and the NeoForge runtime.
 *
 * <p>They are not in this installer and never will be. The base is Minecraft with NeoForge's patches applied, and the
 * runtime is assembled from NeoForge's own distribution — so both embed code this project has no right to hand out.
 * A player never needs this class: with no directory given, {@link ArtifactBuilder} builds both during the install.
 * It serves developers who already built them ({@code python3 tools/dev.py prepare} stages them, and an earlier
 * install keeps them in {@code .forbric-build/out}) and want to skip that.
 *
 * <p>A missing artifact is reported by name and expected path rather than guessed at, because every later step —
 * the profile's game arguments above all — is a lie without it. A FOUND artifact is then opened and checked for
 * what only the real one carries ({@link #verifyContents}), because a file name is not an identity.
 */
final class GameArtifacts {
	/** Coordinate → the file name the build scripts produce. */
	private static final Map<String, String> WANTED = new LinkedHashMap<>();

	static {
		WANTED.put(ArtifactBuilder.BASE, "patched-mc-neoforge-%s.jar");
		WANTED.put(ArtifactBuilder.NEOFORGE_RUNTIME, "neoforge-runtime.jar");
	}

	/**
	 * What to do about any Built-artifacts error, said the same way everywhere. For everyone who is not developing
	 * Forbric the answer is to supply nothing: the installer then builds both itself.
	 */
	static final String LEAVE_EMPTY = "Leave \"Built artifacts\" empty (on the command line: leave out --artifacts) "
			+ "and the installer downloads and builds these files itself.";

	// What only the real artifacts carry; see problem() for how these were chosen.
	private static final String MINECRAFT_CLIENT = "net/minecraft/client/Minecraft.class";
	private static final String NEO_CORE = "net/neoforged/neoforge/common/NeoForge.class";
	private static final List<String> NEO_LOADER = List.of(
			"net/neoforged/fml/loading/FMLLoader.class",
			"net/neoforged/neoforgespi/language/IModInfo.class");
	private static final byte[] NEO_REFERENCE = "net/neoforged/".getBytes(StandardCharsets.US_ASCII);
	/**
	 * MinecraftForge, by name only: this installer builds and accepts nothing of it. A base that still refers to it is
	 * the merged game base Forbric 0.4 and earlier built, and a "runtime" holding its core class is a MinecraftForge
	 * runtime picked up instead of NeoForge's.
	 */
	private static final String FORGE_CORE = "net/minecraftforge/common/MinecraftForge.class";
	private static final byte[] FORGE_REFERENCE = "net/minecraftforge/".getBytes(StandardCharsets.US_ASCII);
	/** The merged base's embedded native-reference index for MinecraftForge; NeoForge's patched game has none. */
	private static final String FORGE_NATIVE_INDEX = "META-INF/forbric/native-reference/FORGE/index.tsv";
	/** The file name Forbric 0.4 and earlier gave its merged base, recognised only to say so. */
	private static final String OLD_MERGED_BASE = "patched-mc-merged-%s.jar";

	private final Path dir;
	private final Map<String, Path> found = new LinkedHashMap<>();
	private final List<String> missing = new ArrayList<>();

	private GameArtifacts(Path dir) {
		this.dir = dir;
	}

	/**
	 * Locates both for {@code mcVersion} in {@code dir}, or in the subdirectories a development stage keeps them in
	 * ({@code neoforge-patched/}, {@code neoforge-runtime/}) — so the stage {@code tools/dev.py prepare} writes can be
	 * named as it is, as can an earlier install's {@code .forbric-build/out}.
	 *
	 * <p>Only the named directory. This used to fall back to the {@code run/} directory of whatever checkout the
	 * installer jar sat in, from when that fallback was how the installer found anything at all. Since the
	 * installer builds the artifacts itself when no directory is named, the fallback only ever completed a
	 * directory someone DID name with files from somewhere else: a set mixed from two builds, reported under a
	 * directory that did not hold it, and tests whose "this file is missing" case quietly passed or failed
	 * depending on whether the checkout running them had built its own artifacts.
	 */
	static GameArtifacts locate(String mcVersion, Path dir) throws IOException {
		GameArtifacts artifacts = find(mcVersion, dir);
		if (!artifacts.missing.isEmpty()) throw artifacts.refusal(Map.of());
		return artifacts;
	}

	/**
	 * What {@link #locate} looks for, file by file, without refusing an incomplete set: {@link #all} holds what is
	 * there and {@link #missing} names what is not. {@code --doctor} reports a half-filled directory this way; it
	 * used to call every file in one missing, the ones that were there included, because locate refuses the set
	 * as a whole.
	 */
	static GameArtifacts find(String mcVersion, Path dir) {
		GameArtifacts artifacts = new GameArtifacts(dir);
		for (Map.Entry<String, String> wanted : WANTED.entrySet()) {
			String fileName = String.format(wanted.getValue(), mcVersion);
			Path hit = null;
			for (Path candidate : new Path[] {
					dir.resolve(fileName),
					dir.resolve("neoforge-patched").resolve(fileName),
					dir.resolve("neoforge-runtime").resolve(fileName)}) {
				if (Files.isRegularFile(candidate)) {
					hit = candidate;
					break;
				}
			}
			if (hit == null) artifacts.missing.add(fileName);
			else artifacts.found.put(wanted.getKey(), hit);
		}
		return artifacts;
	}

	/** The file names {@link #find} looked for and did not find. */
	List<String> missing() {
		return missing;
	}

	/**
	 * Opens every located artifact and refuses the set unless each one is what its name says it is — before
	 * anything is staged or a profile is written.
	 *
	 * <p>Issue #13: a player filled "Built artifacts" with three unrelated jars that happened to carry the right
	 * names. Nothing looked inside them. The link check passed, because a jar that refers to nothing outside
	 * itself leaves nothing dangling; the install reported success; and the game died at the first NeoForge class
	 * with five lines in latest.log and no crash report. Every bad file is listed at once, so fixing one does not
	 * just reveal the next on the following attempt.
	 */
	void verifyContents(String mcVersion) throws IOException {
		Map<String, String> problems = contentProblems(mcVersion);
		if (!problems.isEmpty()) throw refusal(problems);
	}

	/** coordinate → why its file is not what its name says; empty when every located file is. */
	Map<String, String> contentProblems(String mcVersion) {
		Map<String, String> problems = new LinkedHashMap<>();
		for (Map.Entry<String, Path> e : found.entrySet()) {
			String problem = problem(e.getKey(), e.getValue(), mcVersion);
			if (problem != null) problems.put(e.getKey(), problem);
		}
		return problems;
	}

	/**
	 * The refusal for this set: the files that are not there, then each file in {@code problems} by its full path
	 * and what is wrong with it, then the way out. An install never gets both halves at once — it does not open
	 * an incomplete set — but {@code --doctor} does, and says both.
	 */
	IOException refusal(Map<String, String> problems) {
		List<String> lines = new ArrayList<>();
		if (!missing.isEmpty()) {
			lines.add("Built artifacts: cannot find " + String.join(", ", missing) + " in " + dir + ".");
		}
		if (!problems.isEmpty()) {
			lines.add("Built artifacts: " + (problems.size() == 1 ? "this file is" : "these files are")
					+ " not the game files Forbric needs.");
			for (Map.Entry<String, String> e : problems.entrySet()) {
				lines.add("  " + found.get(e.getKey()) + "\n    " + e.getValue());
			}
		}
		lines.add(LEAVE_EMPTY);
		if (!missing.isEmpty()) {
			String oldBase = oldMergedBase();
			if (oldBase != null) {
				lines.add("This directory holds " + oldBase + ", the merged game base Forbric 0.4 or earlier built; it "
						+ "still carries MinecraftForge's patches, and this installer runs on NeoForge's patched Minecraft.");
			}
			lines.add("Developers: the directory must hold what python3 tools/dev.py prepare stages "
					+ "(neoforge-patched/patched-mc-neoforge-<mc>.jar and neoforge-runtime/neoforge-runtime.jar), or be an "
					+ "earlier install's .forbric-build/out.");
		}
		return new IOException(String.join("\n", lines));
	}

	/** The older Forbric's merged base, when it sits where this set's base was looked for; null otherwise. */
	private String oldMergedBase() {
		String base = String.format(WANTED.get(ArtifactBuilder.BASE), Pins.MINECRAFT);
		if (!missing.contains(base)) return null;
		String old = String.format(OLD_MERGED_BASE, Pins.MINECRAFT);
		for (Path candidate : new Path[] {dir.resolve(old), dir.resolve("merged-base").resolve(old)}) {
			if (Files.isRegularFile(candidate)) return candidate.toString();
		}
		return null;
	}

	/**
	 * Why {@code jar} cannot stand for {@code coordinate}, in words a player can act on, or null when it can.
	 *
	 * <p>The markers were chosen against the real artifacts — the ones a development stage holds and the ones
	 * {@link ArtifactBuilder} builds — and against what a player is likely to pick up instead: the MinecraftForge,
	 * NeoForge and Fabric installers, the vanilla client jar, NeoForge's {@code -universal} jar, a MinecraftForge
	 * runtime, and the merged game base an older Forbric built. Every real artifact has all of its markers; none of the
	 * others has all of them.
	 *
	 * <ul>
	 *   <li>The base is Minecraft {@code mcVersion} (the id in its {@code version.json}, and the client's
	 *       {@code Minecraft} class) whose own classes refer to {@code net/neoforged/}: that is what carrying
	 *       NeoForge's patches looks like in bytecode, and in the real base hundreds of classes do, so this does not
	 *       hinge on any one class keeping one hook. Vanilla refers to it nowhere. A base that refers to
	 *       {@code net/minecraftforge/} too, or embeds the MinecraftForge native-reference index, is the merged base
	 *       Forbric 0.4 and earlier built: refused by name, because it would install and then fail far away.</li>
	 *   <li>The runtime holds NeoForge's core class AND its mod loader. The {@code -universal} jar has the first
	 *       without the second; the kernel needs both, and the loader half is exactly what was absent in #13
	 *       ({@code net/neoforged/neoforgespi/language/IModInfo}).</li>
	 *   <li>The runtime was built for the NeoForge this installer pins: the {@code Implementation-Version} in its
	 *       manifest's main section, which {@link NeoForgeRuntimeBuilder} (and {@code assemble-neoforge-runtime.sh})
	 *       write from {@link Pins#NEOFORGE}. A runtime from an older install passes every check above: 0.2.0's,
	 *       {@code 26.2.0.38-beta}, installed without a word.</li>
	 * </ul>
	 */
	static String problem(String coordinate, Path jar, String mcVersion) {
		ZipFile opened;
		try {
			opened = new ZipFile(jar.toFile());
		} catch (ZipException notAZip) {
			return "It is not a jar file: it cannot be opened as one.";
		} catch (IOException unreadable) {
			return "It cannot be read: " + unreadable.getMessage();
		}
		try (ZipFile zip = opened) {
			if (zip.size() == 0) return "It is an empty archive, with no files in it.";
			String installer = installerName(zip);
			if (installer != null) return "It is " + installer + ", not " + what(coordinate, mcVersion) + ".";
			return switch (coordinate) {
				case ArtifactBuilder.BASE -> baseProblem(zip, mcVersion);
				case ArtifactBuilder.NEOFORGE_RUNTIME -> runtimeProblem(zip, "NeoForge", NEO_CORE, NEO_LOADER,
						Pins.NEOFORGE);
				default -> throw new IllegalArgumentException("not a game artifact: " + coordinate);
			};
		} catch (IOException damaged) {
			// It opened, so it is a jar; an entry inside it did not read back.
			return "It is damaged: part of it cannot be read (" + damaged.getMessage() + ").";
		}
	}

	private static String what(String coordinate, String mcVersion) {
		return switch (coordinate) {
			case ArtifactBuilder.BASE -> "NeoForge's patched game (Minecraft " + mcVersion + " with NeoForge's patches)";
			case ArtifactBuilder.NEOFORGE_RUNTIME -> "the NeoForge runtime";
			default -> coordinate;
		};
	}

	/** What a supplied merged base from Forbric 0.4 or earlier is told. */
	static final String OLD_INSTALL = "It is the merged game base Forbric 0.4 or earlier built; it still carries "
			+ "MinecraftForge's patches. This installer runs on NeoForge's patched Minecraft, which it builds itself.";

	private static String baseProblem(ZipFile zip, String mcVersion) throws IOException {
		String id = versionId(zip);
		if (id == null || zip.getEntry(MINECRAFT_CLIENT) == null) {
			return "It does not contain Minecraft" + looksLike(zip, null) + ".";
		}
		if (!id.equals(mcVersion)) {
			return "It is Minecraft " + id + ", but this install is for Minecraft " + mcVersion + ".";
		}
		if (zip.getEntry(FORGE_NATIVE_INDEX) != null) return OLD_INSTALL;
		boolean forge = false;
		boolean neo = false;
		// Every class is read, not just until NeoForge turns up: a merged base names NeoForge in some classes long
		// before it names MinecraftForge.
		for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
			ZipEntry entry = entries.nextElement();
			String name = entry.getName();
			if (!name.startsWith("net/minecraft/") || !name.endsWith(".class")) continue;
			byte[] bytes;
			try (InputStream in = zip.getInputStream(entry)) {
				bytes = in.readAllBytes();
			}
			forge |= contains(bytes, FORGE_REFERENCE);
			neo |= contains(bytes, NEO_REFERENCE);
		}
		if (forge && neo) return OLD_INSTALL;
		if (forge) {
			return "It is Minecraft " + mcVersion + " patched by MinecraftForge. This installer runs on NeoForge's patched "
					+ "Minecraft, which it builds itself.";
		}
		return neo ? null : "It is plain Minecraft " + mcVersion + ", not NeoForge's patched game Forbric builds from it.";
	}

	private static String runtimeProblem(ZipFile zip, String family, String core, List<String> loader,
			String pinned) {
		if (zip.getEntry(core) == null) return "It does not contain " + family + looksLike(zip, family) + ".";
		for (String marker : loader) {
			if (zip.getEntry(marker) == null) {
				return "It contains " + family + " but not the mod loader that belongs with it, so it is not the "
						+ family + " runtime Forbric puts together (" + family + "'s -universal jar looks like this).";
			}
		}
		String built = implementationVersion(zip);
		if (built == null) {
			return "It does not say which " + family + " it was built for (its manifest has no "
					+ "Implementation-Version); this installer needs " + pinned + ".";
		}
		if (!built.equals(pinned)) {
			return "It was built for " + family + " " + built + "; this installer needs " + pinned + ".";
		}
		return null;
	}

	/**
	 * The {@code Implementation-Version} of the manifest's main section, or null. Only the main section: a runtime's
	 * manifest may also carry one per bundled library ({@code AccessTransformers 8.2.2}, ...), and none of those says
	 * what the runtime was built for.
	 */
	private static String implementationVersion(ZipFile zip) {
		ZipEntry entry = zip.getEntry("META-INF/MANIFEST.MF");
		if (entry == null) return null;
		try (InputStream in = zip.getInputStream(entry)) {
			String version = new Manifest(in).getMainAttributes().getValue("Implementation-Version");
			return version == null || version.isBlank() ? null : version.strip();
		} catch (IOException unreadable) {
			return null;
		}
	}

	/** A hint at what a wrong file actually is, when that is recognisable — the usual mistake is a swap. */
	private static String looksLike(ZipFile zip, String notThis) {
		if (!"NeoForge".equals(notThis) && zip.getEntry(NEO_CORE) != null) return " (it looks like NeoForge instead)";
		if (!"MinecraftForge".equals(notThis) && zip.getEntry(FORGE_CORE) != null) {
			return " (it looks like MinecraftForge instead)";
		}
		if (zip.getEntry(MINECRAFT_CLIENT) != null) return " (it looks like Minecraft instead)";
		return "";
	}

	/**
	 * The installer a player most plausibly downloaded instead, or null. MinecraftForge's and NeoForge's installers
	 * share one layout (NeoForge's is a fork) and are told apart by the version id they would install.
	 */
	private static String installerName(ZipFile zip) {
		if (zip.getEntry("install_profile.json") != null) {
			String id = versionId(zip);
			String lower = id == null ? "" : id.toLowerCase(Locale.ROOT);
			if (lower.contains("neoforge")) return "the NeoForge installer";
			if (lower.contains("forge")) return "the MinecraftForge installer";
			return "a mod loader's installer";
		}
		if (zip.stream().anyMatch(e -> e.getName().startsWith("net/fabricmc/installer/"))) {
			return "the Fabric installer";
		}
		return null;
	}

	/** The {@code id} in a jar's {@code version.json}, or null when it has none that reads as one. */
	private static String versionId(ZipFile zip) {
		ZipEntry entry = zip.getEntry("version.json");
		if (entry == null) return null;
		try (InputStream in = zip.getInputStream(entry)) {
			Object parsed = Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
			return parsed instanceof Map<?, ?> map && map.get("id") instanceof String id ? id : null;
		} catch (IOException | RuntimeException unreadable) {
			return null;
		}
	}

	private static boolean contains(byte[] haystack, byte[] needle) {
		outer:
		for (int i = 0, last = haystack.length - needle.length; i <= last; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) continue outer;
			}
			return true;
		}
		return false;
	}

	/** coordinate (without version) → located file. */
	Map<String, Path> all() {
		return found;
	}

	Path get(String coordinate) {
		return found.get(coordinate);
	}
}
