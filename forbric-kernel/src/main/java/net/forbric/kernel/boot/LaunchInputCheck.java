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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;

/**
 * Whether the game jars this launch was handed are the ones a Forbric game is made of, judged by what is IN them and
 * before anything is loaded out of them.
 *
 * <h2>Why it exists</h2>
 *
 * <p>Issue #13. A player's installer was given a directory of unrelated jars, matched the three game files in it by
 * name alone, and staged two "runtime" jars that opened fine and held no Forge or NeoForge at all. The kernel took
 * them as given, and every step that read them had a legitimate empty answer: {@code EcosystemVersions} found no
 * manifest and said so at debug, the access transformers found none, Mixin found no config. The first class either
 * family owns was asked for inside {@link KernelRuntimeClasses#verify}, and its {@code NoClassDefFoundError} went to
 * stderr. The player's {@code latest.log} -- the only file a launcher collects -- was five INFO lines.
 *
 * <p>So the inputs are checked once, here, while nothing has been read out of them, and a launch that cannot work
 * stops with the reason and the fix in {@code latest.log} instead of a stack trace somewhere else.
 *
 * <h2>What "the right jars" means</h2>
 *
 * <ul>
 *   <li>The game jar is the MERGED base: its {@code Block} implements an extension interface from each Forge family.
 *       Vanilla's implements neither, a NeoForge- or MinecraftForge-patched game one, and a jar that is not
 *       Minecraft has no {@code Block}.</li>
 *   <li>Every {@code --runtimeJar} carries at least one family completely: the loader SPI, {@code ModContainer},
 *       {@code FMLLoader}, {@code FMLEnvironment} and the family's own manifest. A jar with some of that is a broken
 *       carrier; a jar with only the manifest is one of that family's mods, which carry the same manifest; a jar
 *       with none of it is something else wearing a carrier's name.</li>
 *   <li>Both families are carried by something this launch owns. The merged base and the kernel's own game side
 *       name both, so a launch missing either cannot start -- that includes no {@code --runtimeJar} at all, and a
 *       launcher that kept only the last of two repeated flags.</li>
 * </ul>
 *
 * <p>By content, never by file name: an installed profile passes {@code forge-runtime-26.2.jar}, a developer launch
 * {@code merged-base/forge-runtime-interop.jar}, and the names in the broken install were exactly right. Nor is it a
 * version or link check -- the installer's link gate is that. It asks only whether each jar is the kind of thing its
 * flag says it is, which is cheap enough to ask on every launch: one central directory per jar and one class read.
 *
 * <h2>What it does not catch</h2>
 *
 * <p>Five names per family are evidence of a carrier, not proof of a whole one. A jar that has them and lacks some
 * other class passes, and so does an entry whose bytes are damaged, a carrier from a different build than the
 * merged base, a base whose {@code Block} is intact and whose other classes are not, and anything wrong with the
 * libraries or the mods folder. Each of those fails where the missing piece is first used:
 *
 * <ul>
 *   <li>In the kernel's own boot -- {@link KernelRuntimeClasses#verify} reports a class it cannot resolve, and
 *       {@link CompatibilityLaunchBoundary} logs anything else that leaves the boot, both into {@code latest.log}.
 *       The game crashes; it is not reported as a broken install.</li>
 *   <li>In the first three steps of the client's own {@code Main.main} (detecting the version, building and running
 *       the argument parser). Vanilla catches what they throw, prints it to stderr and exits with status 249, 252 or
 *       251, so nothing that ends the game there leaves {@code main}.
 *       {@code FMLEnvironment} is a marker for that reason: the merged base's {@code SharedConstants.<clinit>} --
 *       the first game code that runs -- calls NeoForge's, and a carrier without it ended the launch there with a
 *       {@code NoClassDefFoundError} on stderr and nothing in {@code latest.log}. MinecraftForge's is the one
 *       {@code PassiveSeeder} decides, which takes its absence for "no MinecraftForge" and says so only at debug.
 *       Any other failure on that path is put in the log by {@code LifecycleHookInjector}'s hook in
 *       {@code Main.logEarlyException} ({@link KernelLifecycle#onEarlyStartupFailure}), again as a crash.</li>
 *   <li>Anywhere after that: what leaves {@code main} is logged by the boundary, what the game catches by the game's
 *       own crash handling.</li>
 * </ul>
 *
 * <p>{@code -Dforbric.launchInputCheck=off} reports the same problems as warnings and launches anyway.
 */
final class LaunchInputCheck {
	static final String SWITCH = "forbric.launchInputCheck";

	/** The two families the merged base is built from, in the order a problem list names them. */
	static final List<Ecosystem> FAMILIES = List.of(Ecosystem.NEOFORGE, Ecosystem.FORGE);

	/**
	 * The class both families patch an extension interface onto ({@code IBlockExtension}, {@code IForgeBlock}), so its
	 * interface list says which families a game jar was built with.
	 */
	static final String BLOCK = "net/minecraft/world/level/block/Block.class";

	private LaunchInputCheck() {
	}

	/** Whether a problem stops the launch; {@code -Dforbric.launchInputCheck=off} only reports it. */
	static boolean enforced() {
		return !"off".equalsIgnoreCase(System.getProperty(SWITCH, "on"));
	}

	/**
	 * What a complete carrier of {@code family} holds: its loader SPI, its ModContainer, its FMLLoader, its
	 * FMLEnvironment (the class the game's own first statement needs; see above), and its manifest.
	 */
	static List<String> markers(Ecosystem family) {
		return List.of(ForeignType.MOD_INFO_SPI.internal(family) + ".class",
				ForeignType.MOD_CONTAINER.internal(family) + ".class",
				ForeignType.FML_LOADER.internal(family) + ".class",
				ForeignType.FML_ENVIRONMENT.internal(family) + ".class",
				manifest(family));
	}

	/** The family's manifest. Its runtime carries one, and so does every one of its mods. */
	static String manifest(Ecosystem family) {
		return family == Ecosystem.NEOFORGE ? "META-INF/neoforge.mods.toml" : "META-INF/mods.toml";
	}

	/** The package root a family's extension interfaces on {@code Block} live under. */
	private static String root(Ecosystem family) {
		return family == Ecosystem.NEOFORGE ? "net/neoforged/" : "net/minecraftforge/";
	}

	/**
	 * Checks the inputs and, if they cannot run, logs why and what to do, then stops the launch.
	 *
	 * @param gameJars    every {@code --gameJar}, the base first
	 * @param runtimeJars every {@code --runtimeJar}
	 * @throws Rejected when a problem was found and the check is enforced
	 */
	static void require(List<Path> gameJars, List<Path> runtimeJars) {
		List<String> problems = problems(gameJars, runtimeJars);
		if (problems.isEmpty()) return;

		boolean enforced = enforced();
		// Plain-string overloads throughout: these lines carry paths, and a '%' in a path is not a format.
		log(enforced, "[Forbric/Install] the Forbric install is broken: the game files this launch was given are not "
				+ "the ones Forbric runs on (" + problems.size() + " problem(s) below), so "
				+ (enforced ? "the game stops here instead of crashing later on a class that is not there"
						: "the game is likely to crash, because -D" + SWITCH + "=off launches it anyway"));
		for (String problem : problems) log(enforced, "[Forbric/Install]   - " + problem);
		log(enforced, "[Forbric/Install] to fix it: close the launcher, run the Forbric installer again with the same "
				+ "Game directory and leave \"Built artifacts\" EMPTY — the installer then builds the merged game and both "
				+ "runtime jars itself instead of copying them from a folder. Developers: run `python3 tools/dev.py "
				+ "prepare`, or point FORBRIC_OLD / -Pforbric.stagedRoot at a complete staged tree");
		if (enforced) throw new Rejected(problems);
	}

	private static void log(boolean enforced, String line) {
		if (enforced) ForbricLog.error(line);
		else ForbricLog.warn(line);
	}

	/**
	 * Everything wrong with these inputs, one line each and worded for whoever reads {@code latest.log}; empty when
	 * the launch can proceed.
	 */
	static List<String> problems(List<Path> gameJars, List<Path> runtimeJars) {
		List<String> problems = new ArrayList<>();
		Set<Ecosystem> carried = EnumSet.noneOf(Ecosystem.class);

		if (gameJars.isEmpty()) {
			problems.add("no game jar was given (--gameJar), so there is no Minecraft to start");
		} else {
			String base = baseProblem(gameJars.get(0));
			if (base != null) problems.add(base);
			// A carrier passed as one more --gameJar is owned all the same. Nothing launches that way today, but it
			// would not be wrong, so it counts toward "carried" rather than being reported.
			for (Path extra : gameJars.subList(1, gameJars.size())) carried.addAll(read(extra).complete());
		}

		for (Path jar : runtimeJars) {
			Contents contents = read(jar);
			if (contents.unreadable() != null) {
				problems.add("runtime jar " + jar.getFileName() + " " + contents.unreadable() + " (" + jar + ")");
				continue;
			}
			carried.addAll(contents.complete());
			if (!contents.complete().isEmpty()) continue;

			List<String> partial = new ArrayList<>();
			List<Ecosystem> modLike = new ArrayList<>();
			for (Ecosystem family : FAMILIES) {
				if (contents.manifestOnly(family)) {
					modLike.add(family);
				} else if (contents.present(family)) {
					partial.add("is an incomplete " + family.displayName() + " runtime: it is missing "
							+ String.join(", ", contents.missing().get(family)));
				}
			}
			if (!modLike.isEmpty()) {
				// A mod under a runtime's name. The manifest is the one marker a mod shares with its runtime, so
				// "an incomplete runtime" would send the player looking for classes no mod ever had.
				List<String> names = new ArrayList<>(), manifests = new ArrayList<>(), classes = new ArrayList<>();
				for (Ecosystem family : modLike) {
					names.add(family.displayName());
					manifests.add(manifest(family));
					for (String marker : markers(family)) if (!marker.equals(manifest(family))) classes.add(marker);
				}
				partial.add("looks like a " + String.join(" and ", names) + " mod, not a runtime: it has "
						+ String.join(" and ", manifests) + ", which mods carry too, and none of the runtime's classes "
						+ classes);
			}
			if (!partial.isEmpty()) {
				problems.add("runtime jar " + jar.getFileName() + " " + String.join("; and ", partial) + " ("
						+ described(jar) + ")");
			} else {
				problems.add("runtime jar " + jar.getFileName() + " contains neither NeoForge nor MinecraftForge: none of "
						+ markers(Ecosystem.NEOFORGE) + " and none of " + markers(Ecosystem.FORGE) + " ("
						+ described(jar) + ")");
			}
		}

		for (Ecosystem family : FAMILIES) {
			if (carried.contains(family)) continue;
			List<String> given = runtimeJars.stream().map(jar -> String.valueOf(jar.getFileName())).toList();
			problems.add("nothing this launch was given contains a complete " + family.displayName() + " runtime, and "
					+ "the game cannot start without it — "
					+ (runtimeJars.isEmpty() ? "no --runtimeJar was passed at all"
							: "the runtime jars passed were " + given));
		}
		return problems;
	}

	/** Why {@code jar} is not the merged base, or {@code null} if it is. */
	static String baseProblem(Path jar) {
		String name = "game jar " + jar.getFileName();
		if (!Files.isRegularFile(jar)) return name + " does not exist (" + jar + ")";

		List<String> interfaces;
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry block = zip.getEntry(BLOCK);
			if (block == null) {
				return name + " is not a Minecraft game jar: it has no " + BLOCK + " (" + described(jar) + ")";
			}
			try (InputStream in = zip.getInputStream(block)) {
				interfaces = List.of(new ClassReader(in).getInterfaces());
			}
		} catch (IOException | RuntimeException unreadable) {
			// ClassReader throws IllegalArgumentException / ArrayIndexOutOfBounds on bytes that are not a class.
			Caught.expected(unreadable);
			return name + " cannot be read as a game jar: " + unreadable + " (" + described(jar) + ")";
		}

		List<Ecosystem> patched = new ArrayList<>();
		for (Ecosystem family : FAMILIES) {
			if (interfaces.stream().anyMatch(i -> i.startsWith(root(family)))) patched.add(family);
		}
		if (patched.size() == FAMILIES.size()) return null;
		if (patched.isEmpty()) {
			return name + " is plain Minecraft, not Forbric's merged game: its Block carries neither NeoForge's nor "
					+ "MinecraftForge's changes (" + described(jar) + ")";
		}
		Ecosystem missing = FAMILIES.stream().filter(f -> !patched.contains(f)).findFirst().orElseThrow();
		return name + " carries only " + patched.get(0).displayName() + "'s changes and not " + missing.displayName()
				+ "'s, so it is not Forbric's merged game (" + described(jar) + ")";
	}

	/**
	 * The path and the size. The size alone is often the diagnosis: a real carrier is megabytes, and the ones in
	 * issue #13 would have read as a few hundred bytes.
	 */
	private static String described(Path jar) {
		try {
			return jar + ", " + Files.size(jar) + " bytes";
		} catch (IOException | RuntimeException unknown) {
			Caught.expected(unknown);
			return jar.toString();
		}
	}

	/**
	 * Which markers of each family a jar has.
	 *
	 * @param unreadable why the jar could not be opened at all, or {@code null}
	 * @param missing    per family, the markers it lacks
	 * @param found      per family, how many markers it has
	 */
	record Contents(String unreadable, Map<Ecosystem, List<String>> missing, Map<Ecosystem, Integer> found) {
		/** Families this jar carries completely. */
		Set<Ecosystem> complete() {
			Set<Ecosystem> out = EnumSet.noneOf(Ecosystem.class);
			if (unreadable != null) return out;
			for (Ecosystem family : FAMILIES) if (missing.get(family).isEmpty()) out.add(family);
			return out;
		}

		/** Whether anything of {@code family} is in this jar. */
		boolean present(Ecosystem family) {
			return unreadable == null && found.get(family) > 0;
		}

		/** Whether all this jar has of {@code family} is its manifest: what that family's mods carry. */
		boolean manifestOnly(Ecosystem family) {
			return present(family) && found.get(family) == 1 && !missing.get(family).contains(manifest(family));
		}
	}

	static Contents read(Path jar) {
		if (!Files.isRegularFile(jar)) return new Contents("does not exist", Map.of(), Map.of());

		Map<Ecosystem, List<String>> missing = new LinkedHashMap<>();
		Map<Ecosystem, Integer> found = new LinkedHashMap<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (Ecosystem family : FAMILIES) {
				List<String> absent = new ArrayList<>();
				int have = 0;
				for (String marker : markers(family)) {
					if (zip.getEntry(marker) == null) absent.add(marker);
					else have++;
				}
				missing.put(family, List.copyOf(absent));
				found.put(family, have);
			}
		} catch (IOException | RuntimeException unreadable) {
			Caught.expected(unreadable);
			return new Contents("cannot be opened as a jar: " + unreadable, Map.of(), Map.of());
		}
		return new Contents(null, missing, found);
	}

	/**
	 * The launch stopped because the jars it was handed cannot run. The reason and the fix are already in the log, so
	 * {@link CompatibilityLaunchBoundary} turns this into an exit code rather than a crash.
	 */
	static final class Rejected extends IllegalStateException {
		Rejected(List<String> problems) {
			super("Forbric install is broken; see logs/latest.log: " + String.join("; ", problems));
		}
	}
}
