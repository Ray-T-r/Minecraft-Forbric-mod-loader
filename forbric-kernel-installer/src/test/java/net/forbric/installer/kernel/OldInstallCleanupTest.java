package net.forbric.installer.kernel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@link OldInstallCleanup} on a synthetic .minecraft laid out the way the installers before Stage 4 left one: v0.3.1-beta2's
 * coordinates (forbric-loader, forbricruntime, {@code neoforge-runtime:<mc>}, an older kernel), the last merged-base
 * build's (tri-eco-26.2-final: {@code patched-mc-merged:26.2-65.0.1-26.2.0.93}, {@code forge-runtime:26.2-65.0.1}, the old
 * vanilla reference), its .forbric-build products and MinecraftForge work files, and this install's own files beside
 * them. A second profile still names the old merged base and an older kernel; their files must survive.
 *
 * <p>Then the controls, each of which must turn the checks red (D34): a clean-up that ignores the profiles, and one with
 * its guard off and a rule broad enough to reach this install's own outputs. A delete that fails is named and the run
 * carries on; an unexpected failure stops the clean-up and says so. Every way a profile may spell a library keeps it.
 * The lines are checked in Chinese too, forced (D88b); the English ones are pinned to English (and the caller's language
 * restored after), so the class passes on a machine whose language is Chinese.
 *
 * <p>The download cache ({@code .forbric-build/dl}, owner ruling 6 of Stage 4 C6): an install that built its game files
 * keeps exactly what {@link ArtifactBuilder#downloadInputs} names -- read here from a synthetic NeoForge userdev jar, and
 * checked against the file names the build fetches by, written out below ({@link #DL_INPUTS}) -- and removes the rest:
 * MinecraftForge's downloads, an older NeoForge's, another Minecraft version's, a broken download's {@code .part}. An
 * install given its game files leaves the cache alone. The controls: inputs ignored with the guard off (red: a current
 * input removed), and a clean-up that does not judge the cache (red: an orphan kept).
 */
public final class OldInstallCleanupTest {
	private static final String KERNEL_NOW = "net/forbric/forbric-kernel/0.4.0-dev/forbric-kernel-0.4.0-dev.jar";
	private static final String BASE_NOW = "net/forbric/patched-mc-neoforge/26.2-26.2.0.93/patched-mc-neoforge-26.2-26.2.0.93.jar";
	private static final String RUNTIME_NOW = "net/forbric/neoforge-runtime/26.2.0.93/neoforge-runtime-26.2.0.93.jar";
	private static final String REFERENCE_NOW = "net/forbric/vanilla-reference/26.2-26.2.0.93/vanilla-reference-26.2-26.2.0.93.jar";

	/** Old libraries no profile names: removed. */
	private static final List<String> ORPHANS = List.of(
			"net/forbric/forbric-loader/0.3.1-beta2/forbric-loader-0.3.1-beta2.jar",
			"net/forbric/neoforge-runtime/26.2/neoforge-runtime-26.2.jar",
			"net/forbric/patched-mc-merged/26.2/patched-mc-merged-26.2.jar",
			"net/forbric/forge-runtime/26.2/forge-runtime-26.2.jar",
			"net/forbric/forge-runtime/26.2-65.0.1/forge-runtime-26.2-65.0.1.jar",
			"net/forbric/vanilla-reference/26.2-65.0.1-26.2.0.93/vanilla-reference-26.2-65.0.1-26.2.0.93.jar",
			"net/forbric/forbric-kernel/0.3.0/forbric-kernel-0.3.0.jar");
	/** Old libraries a second profile still names, by coordinate, by path in an argument, or through its parent. */
	private static final List<String> NAMED_OLD = List.of(
			"net/forbric/forbric-kernel/0.3.1-beta2/forbric-kernel-0.3.1-beta2.jar",
			"net/forbric/patched-mc-merged/26.2-65.0.1-26.2.0.93/patched-mc-merged-26.2-65.0.1-26.2.0.93.jar",
			"net/forbric/forbricruntime/0.3.1-beta2/forbricruntime-0.3.1-beta2.jar");
	/** Not Forbric's: never touched, named or not. */
	private static final List<String> OTHER_GROUPS = List.of(
			"org/ow2/asm/asm/9.9.1/asm-9.9.1.jar", "net/other/thing/1.0/thing-1.0.jar", "net/forbricx/fake/1/fake-1.jar");

	private static final List<String> RETIRED_BUILD = List.of(
			"out/patched-mc-merged-26.2.jar", "out/patched-mc-merged-26.2.jar.pins", "out/merge-conflicts.txt",
			"out/forge-runtime.jar", "out/forge-runtime.jar.pins", "out/forge-runtime-interop.jar",
			"out/forge-runtime-interop.jar.pins", "out/patched-mc-forge-26.2.jar", "out/patched-mc-forge-26.2.jar.pins",
			"server-main.jar", "patched-subset.jar", "patched-full.jar", "patched-at.jar", "joined.lzma",
			"ats/accesstransformer.cfg",
			// v0.1.0's work tree, .forbric-build/<mc>-<forge>/, with its own download cache and work files.
			"26.2-65.0.1/dl/forge-userdev.jar", "26.2-65.0.1/dl/server.jar", "26.2-65.0.1/patched-full.jar",
			"26.2-65.0.1/ats/accesstransformer.cfg");
	/** This install's own build outputs and their stamps, and NFRT's caches: never touched. */
	private static final List<String> CURRENT_BUILD = List.of(
			"out/patched-mc-neoforge-26.2.jar", "out/patched-mc-neoforge-26.2.jar.pins", "out/neoforge-runtime.jar",
			"out/neoforge-runtime.jar.pins", "out/vanilla-reference-26.2.jar", "nfrt/cache/entry.bin", "nfrt-work/state.json",
			"tools/neoform-runtime-2.0.18-all.jar");
	/**
	 * The libraries the synthetic userdev jar's config lists: three NeoForge runs on, and one ({@code org.ow2.asm}) the
	 * kernel supplies, so the runtime is not built from it and its cached copy is no input.
	 */
	private static final List<String> USERDEV_LIBRARIES = List.of("net.neoforged:bus:8.0.5",
			"net.neoforged.fancymodloader:loader:11.0.16", "cpw.mods:securejarhandler:3.0.8@jar", "org.ow2.asm:asm:9.9.1");
	/**
	 * What this install's build reads in {@code dl/}, by the names the build fetches by (ArtifactBuilder.build,
	 * NeoForgeRuntimeBuilder.build, NfrtRunner's server jar), written out here rather than asked of the code under test.
	 */
	private static final List<String> DL_INPUTS = List.of("neoforge-" + Pins.NEOFORGE + "-userdev.jar",
			"neoforge-" + Pins.NEOFORGE + "-universal.jar", "bus-8.0.5.jar", "loader-11.0.16.jar",
			"securejarhandler-3.0.8.jar", "server.jar");
	/**
	 * What an older installer, or an install for another Minecraft version, cached in {@code dl/} and this install never
	 * reads: v0.3.1-beta2's MinecraftForge downloads and fixed-name userdev jars, an older NeoForge's universal jar, a
	 * library the kernel supplies, another version's userdev, an interrupted download, a file in a sub-directory.
	 */
	private static final List<String> DL_OLD = List.of("forge-userdev.jar", "forge-26.2-65.0.1-universal.jar",
			"fmlcore-26.2-65.0.1.jar", "neoforge-userdev.jar", "neoforge-26.2.0.88-universal.jar", "asm-9.9.1.jar",
			"neoforge-26.1.0.5-userdev.jar", "server.jar.part", "sub/deeper/old-1.jar");
	private static final List<String> USER_DATA = List.of(
			"saves/World/level.dat", "mods/some-mod.jar", "config/some.toml", "options.txt");

	private static final String LANGUAGE = "forbric.dialogLanguage";
	/** The cases this machine could not run: printed with the verdict, never silently counted as passed. */
	private static final List<String> NOT_RUN = new ArrayList<>();

	public static void main(String[] args) throws Exception {
		Path work = Files.createDirectories(args.length > 0 ? Path.of(args[0])
				: Files.createTempDirectory("old-install-cleanup"));
		// The checks below read the English lines; whatever this machine's language, ask for English, and give the
		// caller's choice back afterwards (the installer's tests share one JVM).
		String language = System.getProperty(LANGUAGE);
		String cleanupSwitch = System.getProperty(OldInstallCleanup.SWITCH);
		System.setProperty(LANGUAGE, "en");
		System.clearProperty(OldInstallCleanup.SWITCH);
		try {
			theRulesHold(work.resolve("tip"));
			theDownloadCacheKeepsExactlyThisInstallsInputs(work.resolve("downloads"));
			aDownloadThatCannotBeRemovedIsNamed(work.resolve("downloads-locked"));
			everySpellingOfALibraryKeepsIt(work.resolve("spellings"));
			aLinkOrJunctionAtAnyStepIsRedirected(work.resolve("redirected"));
			aLinkedBuildOrDownloadCacheIsLeftWhole(work.resolve("dl-linked"));
			onlyAProfileThatCannotBeReadStopsTheLibraries(work.resolve("json"));
			anUnexpectedFailureStopsAndSaysSo(work.resolve("stopped"));
			theMergeToolsGoOnlyWhenThisInstallerCarriesNone(work.resolve("tools"));
			anUnreadableProfileKeepsEveryLibrary(work.resolve("unreadable"));
			aFailedDeleteIsNamedAndTheRunCarriesOn(work.resolve("locked"));
			theSwitchLeavesEverything(work.resolve("off"));
			ignoringTheProfilesTurnsItRed(work.resolve("control-profiles"));
			reachingThisInstallsOutputsTurnsItRedWithoutTheGuard(work.resolve("control-guard"));
			ignoringTheDownloadInputsTurnsItRedWithoutTheGuard(work.resolve("control-downloads"));
			notJudgingTheDownloadCacheKeepsItsOrphans(work.resolve("control-downloads-kept"));
			theLinesExistInChinese(work.resolve("zh"));
		} finally {
			restore(LANGUAGE, language);
			restore(OldInstallCleanup.SWITCH, cleanupSwitch);
		}
		System.out.println("OldInstallCleanupTest: OK" + (NOT_RUN.isEmpty() ? ""
				: " -- NOT RUN on this machine, so not checked here: " + String.join("; ", NOT_RUN)));
	}

	private static void restore(String property, String value) {
		if (value == null) System.clearProperty(property);
		else System.setProperty(property, value);
	}

	// --- the tip ---------------------------------------------------------------------------------------------

	private static void theRulesHold(Path mc) throws Exception {
		Layout layout = layout(mc);
		List<String> log = new ArrayList<>();
		OldInstallCleanup.Result result = new OldInstallCleanup(log::add, OldInstallCleanup.RETIRED_BUILD_FILES, true,
				true, true).run(mc, layout.built, layout.staged, layout.downloads);
		List<String> problems = verify(mc, layout, log, true);
		require(problems.isEmpty(), "the clean-up broke its rules:\n  " + String.join("\n  ", problems) + "\n" + log);
		require(result.failed() == 0, "nothing failed: " + result);
		require(result.removed() == ORPHANS.size() + RETIRED_BUILD.size() + DL_OLD.size(), "every removal counted: " + result);
		require(log.stream().filter(l -> l.startsWith("[cleanup] removed /") || l.startsWith("[cleanup] removed " + mc))
				.count() == result.removed(), "one line per removed file: " + log);
		require(log.get(log.size() - 1).matches("\\[cleanup\\] removed " + result.removed()
				+ " file\\(s\\) an older Forbric left behind or this install does not use \\([0-9.]+ MB\\)"),
				"the summary closes it: " + log);
		// The symbolic links: left, and what they point at untouched.
		require(Files.isSymbolicLink(mc.resolve(".forbric-build/clean.jar")), "a retired name that is a link is left");
		require(Files.isSymbolicLink(mc.resolve("libraries/net/forbric/linkdir")), "a linked directory is left");
		require(Files.isSymbolicLink(mc.resolve("libraries/net/forbric/linked/1.0/linked-1.0.jar")),
				"a linked file in an unnamed library is left");
		require(log.stream().anyMatch(l -> l.startsWith("[cleanup] left ") && l.contains("clean.jar")), "and named: " + log);
		require(Files.isRegularFile(layout.outside.resolve("victim.jar"))
				&& Files.isRegularFile(layout.outside.resolve("dir/old/1/old-1.jar")), "no link was followed");
		// A second run over the result finds nothing left to do.
		Map<String, String> after = manifest(mc);
		List<String> again = new ArrayList<>();
		OldInstallCleanup.Result second = new OldInstallCleanup(again::add, OldInstallCleanup.RETIRED_BUILD_FILES, true,
				true, true).run(mc, layout.built, layout.staged, layout.downloads);
		require(second.removed() == 0 && second.failed() == 0, "a second run removes nothing: " + again);
		require(after.equals(manifest(mc)), "and changes nothing, in the download cache least of all: " + again);
		require(again.stream().noneMatch(l -> l.startsWith("[cleanup] clearing the download cache")),
				"nor announces a clean-up of the download cache it has nothing to do in: " + again);
	}

	/**
	 * The download cache on its own (owner ruling 6): exactly the inputs stay, byte for byte; every other file goes,
	 * named, after one line that says another Minecraft version's downloads go too; a sub-directory it empties goes; a
	 * link is left. An install given its game files (no inputs) leaves the cache as it is.
	 */
	private static void theDownloadCacheKeepsExactlyThisInstallsInputs(Path mc) throws Exception {
		Layout layout = layout(mc);
		Path dl = mc.resolve(".forbric-build/dl");
		List<String> inputs = new ArrayList<>();
		for (Path input : layout.downloads.get()) inputs.add(dl.relativize(input).toString());
		require(inputs.equals(DL_INPUTS), "the inputs are the files the build fetches, by the same names: " + inputs);
		Map<String, String> before = manifest(dl);
		List<String> log = new ArrayList<>();
		new OldInstallCleanup(log::add).run(mc, layout.built, layout.staged, layout.downloads);
		List<String> problems = verify(mc, layout, log, true);
		require(problems.isEmpty(), "the download cache broke its rule:\n  " + String.join("\n  ", problems) + "\n" + log);
		Map<String, String> kept = new TreeMap<>(before);
		kept.keySet().removeIf(name -> !DL_INPUTS.contains(name) && !name.equals("linked.jar"));
		require(kept.equals(new TreeMap<>(manifest(dl))), "what stays is the inputs, unchanged, and the link: " + manifest(dl));
		require(log.stream().filter(l -> l.equals("[cleanup] clearing the download cache " + dl + " of every file this"
				+ " install does not use, cached downloads for another Minecraft version included; an install that needs"
				+ " one again downloads it again")).count() == 1, "one line says what goes, other versions' too: " + log);
		require(!Files.exists(dl.resolve("sub")), "a sub-directory the clean-up emptied goes: " + log);
		require(Files.isDirectory(dl), "the cache itself stays");
		require(log.stream().anyMatch(l -> l.startsWith("[cleanup] left " + dl.resolve("linked.jar") + " ")),
				"a link in the cache is left, named: " + log);

		// Given its game files, an install read nothing there: the cache is not judged.
		Layout given = layout(mc.resolveSibling(mc.getFileName() + "-given"));
		Path givenDl = given.mc.resolve(".forbric-build/dl");
		Map<String, String> givenBefore = manifest(givenDl);
		List<String> givenLog = new ArrayList<>();
		new OldInstallCleanup(givenLog::add).run(given.mc, given.built, given.staged, null);
		require(givenBefore.equals(manifest(givenDl)), "an install given its game files leaves the cache alone: " + givenLog);
		require(verify(given.mc, given, givenLog).isEmpty(), "and the rest of the rules hold: " + verify(given.mc, given, givenLog));
	}

	/** A cached download that cannot be deleted is named and left; the rest of the cache is still cleared. */
	private static void aDownloadThatCannotBeRemovedIsNamed(Path mc) throws Exception {
		Layout layout = layout(mc);
		Path lockedDir = mc.resolve(".forbric-build/dl/sub/deeper");
		Path locked = lockedDir.resolve("old-1.jar");
		List<String> log = new ArrayList<>();
		OldInstallCleanup.Result result;
		if (System.getProperty("os.name", "").startsWith("Windows")) {
			try (java.io.FileInputStream held = new java.io.FileInputStream(locked.toFile())) {
				result = new OldInstallCleanup(log::add).run(mc, layout.built, layout.staged, layout.downloads);
			}
		} else if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
			Files.setPosixFilePermissions(lockedDir, PosixFilePermissions.fromString("r-xr-xr-x"));
			try {
				Files.delete(Files.createFile(lockedDir.resolve("probe")));
				Files.setPosixFilePermissions(lockedDir, PosixFilePermissions.fromString("rwxr-xr-x"));
				NOT_RUN.add("the failed-download-delete case (this user writes into a read-only directory)");
				System.out.println("OldInstallCleanupTest: SKIP the failed-download-delete case: this user writes into a"
						+ " read-only directory");
				return;
			} catch (IOException refused) {
				// As intended: nothing may be written, or deleted, in it.
			}
			try {
				result = new OldInstallCleanup(log::add).run(mc, layout.built, layout.staged, layout.downloads);
			} finally {
				Files.setPosixFilePermissions(lockedDir, PosixFilePermissions.fromString("rwxr-xr-x"));
			}
		} else {
			NOT_RUN.add("the failed-download-delete case (neither Windows nor POSIX permissions)");
			return;
		}
		require(Files.isRegularFile(locked), "the download that could not be deleted is still there");
		require(result.failed() == 1, "one failure counted: " + result + " " + log);
		require(log.stream().anyMatch(l -> l.startsWith("[cleanup] could not remove " + locked)
				&& l.endsWith("it is left in place and the install carries on")), "named: " + log);
		require(!Files.exists(mc.resolve(".forbric-build/dl/forge-userdev.jar")), "the rest of the cache still went: " + log);
		require(log.get(log.size() - 1).endsWith("; 1 could not be removed and were left in place"), "summed up: " + log);
	}

	private static void theMergeToolsGoOnlyWhenThisInstallerCarriesNone(Path mc) throws Exception {
		Layout layout = layout(mc);
		Path tools = mc.resolve(".forbric-build/" + OldInstallCleanup.MERGE_TOOLS);
		new OldInstallCleanup(l -> { }, OldInstallCleanup.RETIRED_BUILD_FILES, true, true, true)
				.run(mc, layout.built, layout.staged);
		require(Files.isRegularFile(tools), "an installer that runs its link checker from the merge tools keeps them");
		List<String> log = new ArrayList<>();
		new OldInstallCleanup(log::add, OldInstallCleanup.RETIRED_BUILD_FILES, false, true, true)
				.run(mc, layout.built, layout.staged);
		require(!Files.exists(tools), "one that carries none removes the copy an older installer unpacked");
		require(log.stream().anyMatch(l -> l.contains("forbric-merge-tools.jar")), "and says so: " + log);
		require(Files.isRegularFile(mc.resolve(".forbric-build/tools/neoform-runtime-2.0.18-all.jar")), "NFRT's jar stays");
	}

	private static void anUnreadableProfileKeepsEveryLibrary(Path mc) throws Exception {
		Layout layout = layout(mc);
		write(mc.resolve("versions/broken/broken.json"), "{ this is not json");
		List<String> log = new ArrayList<>();
		new OldInstallCleanup(log::add).run(mc, layout.built, layout.staged);
		for (String orphan : ORPHANS) {
			require(Files.isRegularFile(mc.resolve("libraries").resolve(orphan)), "kept while a profile is unreadable: " + orphan);
		}
		require(log.stream().anyMatch(l -> l.startsWith("[cleanup] could not read ") && l.contains("broken.json")),
				"and the profile is named: " + log);
		for (String retired : RETIRED_BUILD) {
			require(!Files.exists(mc.resolve(".forbric-build").resolve(retired)), "the build directory is still cleaned: " + retired);
		}
	}

	private static void aFailedDeleteIsNamedAndTheRunCarriesOn(Path mc) throws Exception {
		Layout layout = layout(mc);
		Path lockedDir = mc.resolve("libraries/net/forbric/forge-runtime/26.2");
		Path locked = lockedDir.resolve("forge-runtime-26.2.jar");
		List<String> log = new ArrayList<>();
		OldInstallCleanup.Result result;
		if (System.getProperty("os.name", "").startsWith("Windows")) {
			// The case itself: a file a running game holds open. java.io opens without FILE_SHARE_DELETE, as the game's
			// own jar reads do, so Windows refuses to delete it while the stream is open.
			try (java.io.FileInputStream held = new java.io.FileInputStream(locked.toFile())) {
				result = new OldInstallCleanup(log::add).run(mc, layout.built, layout.staged);
			}
		} else if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
			// Elsewhere an open file can be deleted; a directory nobody may write to makes the delete fail the same way.
			Files.setPosixFilePermissions(lockedDir, PosixFilePermissions.fromString("r-xr-xr-x"));
			try {
				Files.delete(Files.createFile(lockedDir.resolve("probe")));
				Files.setPosixFilePermissions(lockedDir, PosixFilePermissions.fromString("rwxr-xr-x"));
				NOT_RUN.add("the failed-delete case (this user writes into a read-only directory)");
				System.out.println("OldInstallCleanupTest: SKIP the failed-delete case: this user writes into a read-only"
						+ " directory");
				return;
			} catch (IOException refused) {
				// As intended: nothing may be written, or deleted, in it.
			}
			try {
				result = new OldInstallCleanup(log::add).run(mc, layout.built, layout.staged);
			} finally {
				Files.setPosixFilePermissions(lockedDir, PosixFilePermissions.fromString("rwxr-xr-x"));
			}
		} else {
			NOT_RUN.add("the failed-delete case (neither Windows nor POSIX permissions)");
			System.out.println("OldInstallCleanupTest: SKIP the failed-delete case: neither Windows nor POSIX permissions");
			return;
		}
		require(Files.isRegularFile(locked), "the file that could not be deleted is still there");
		require(result.failed() == 1, "one failure counted: " + result + " " + log);
		require(log.stream().anyMatch(l -> l.startsWith("[cleanup] could not remove " + locked)
				&& l.endsWith("it is left in place and the install carries on")), "named: " + log);
		require(log.get(log.size() - 1).endsWith("; 1 could not be removed and were left in place"), "summed up: " + log);
		require(!Files.exists(mc.resolve("libraries/net/forbric/forbric-kernel/0.3.0")), "the rest still went: " + log);
	}

	private static void theSwitchLeavesEverything(Path mc) throws Exception {
		Layout layout = layout(mc);
		Map<String, String> before = manifest(mc);
		List<String> log = new ArrayList<>();
		String before_ = System.getProperty(OldInstallCleanup.SWITCH);
		System.setProperty(OldInstallCleanup.SWITCH, "off");
		try {
			new OldInstallCleanup(log::add).run(mc, layout.built, layout.staged);
		} finally {
			restore(OldInstallCleanup.SWITCH, before_);
		}
		require(before.equals(manifest(mc)), "-D" + OldInstallCleanup.SWITCH + "=off removes nothing");
		require(log.equals(List.of("[cleanup] off (-Dforbric.installerCleanup=off): an older Forbric's files are left in place")),
				"and says so: " + log);
		// And the checks see it: the reverse control gate-m17 phase U makes of the switch.
		require(!verify(mc, layout, log).isEmpty(), "with the clean-up off the removal checks are red");
	}

	/**
	 * Every way a profile may name a library keeps it (review A, F1): a coordinate with an {@code @ext}, or a
	 * classifier and an {@code @ext}; two paths in one string joined by a launcher's {@code ${classpath_separator}};
	 * a path after {@code =} in a JVM argument; a sub-group's coordinate inside a longer string. And (C6 wave 2, the
	 * review of those fixes): paths joined by a comma (NeoForge's own {@code -DmergeModules} style) or a bar, with no
	 * separator the path pattern stops at; a directory above a library (a module path); a path in another letter case
	 * than the directory on disk. And (C6 wave 2, the review of lane INSTFIX): {@code net/forbric} and
	 * {@code net.forbric} themselves in another letter case; coordinates joined by a colon, where the first one's
	 * optional classifier swallows the second's group; a sub-directory of a version directory whose file a profile
	 * names (a natives/ folder). Unnamed ones beside them still go -- one a character longer than the named directory,
	 * and a version under an artifact directory that holds a file of its own while a sibling version is named -- so the
	 * case cannot pass by keeping everything.
	 */
	private static void everySpellingOfALibraryKeepsIt(Path mc) throws Exception {
		Layout layout = layout(mc);
		Path libraries = mc.resolve("libraries");
		List<String> spelled = List.of("net/forbric/forbric-kernel/0.3.1/forbric-kernel-0.3.1.jar",
				"net/forbric/natives/1/natives-1-macos.jar", "net/forbric/a/1/a-1.jar", "net/forbric/b/1/b-1.jar",
				"net/forbric/c/1/c-1.jar", "net/forbric/extra/thing/2/thing-2.jar",
				"net/forbric/d/1/d-1.jar", "net/forbric/e/1/e-1.jar", "net/forbric/f/1/f-1.jar", "net/forbric/g/1/g-1.jar",
				"net/forbric/modules/m/1/m-1.jar", "net/forbric/cased/1/cased-1.jar",
				"net/forbric/UpDisk/1/UpDisk-1.jar", "net/forbric/upperprefix/1/upperprefix-1.jar",
				"net/forbric/uppercoord/1/uppercoord-1.jar", "net/forbric/h/1/h-1.jar", "net/forbric/i/2/i-2.jar",
				"net/forbric/withnatives/1/withnatives-1.jar",
				"net/forbric/withnatives/1/natives/withnatives-natives.dylib",
				"net/forbric/meta/1/meta-1.jar", "net/forbric/meta/maven-metadata.xml");
		List<String> unnamed = List.of("net/forbric/extra/other/2/other-2.jar", "net/forbric/modulesx/n/1/n-1.jar",
				"net/forbric/meta/2/meta-2.jar");
		for (String library : spelled) write(libraries.resolve(library), "old " + library);
		for (String library : unnamed) write(libraries.resolve(library), "old " + library);
		write(mc.resolve("versions/spelled/spelled.json"), "{\"id\":\"spelled\",\"inheritsFrom\":\"26.2\","
				+ "\"libraries\":[{\"name\":\"net.forbric:forbric-kernel:0.3.1@jar\"},"
				+ "{\"name\":\"net.forbric:natives:1:macos@jar\"},{\"name\":\"NET.FORBRIC:uppercoord:1\"}],"
				+ "\"arguments\":{\"jvm\":[\"-p\",\"${library_directory}/net/forbric/a/1/a-1.jar${classpath_separator}"
				+ "${library_directory}/net/forbric/b/1/b-1.jar\",\"-Dsome.path=net/forbric/c/1/c-1.jar\","
				+ "\"-DmergeModules=net/forbric/d/1/d-1.jar,net/forbric/e/1/e-1.jar\","
				+ "\"libraries\\\\net\\\\forbric\\\\f\\\\1\\\\f-1.jar|libraries\\\\net\\\\forbric\\\\g\\\\1\\\\g-1.jar\","
				+ "\"-p\",\"${library_directory}/net/forbric/modules\","
				+ "\"${library_directory}/net/forbric/Cased/1/Cased-1.jar\","
				+ "\"${library_directory}/net/forbric/updisk/1/updisk-1.jar\","
				+ "\"${library_directory}/Net/Forbric/upperprefix/1/upperprefix-1.jar\","
				+ "\"-Dmods=net.forbric:h:1:net.forbric:i:2\","
				+ "\"${library_directory}/net/forbric/withnatives/1/withnatives-1.jar\","
				+ "\"${library_directory}/net/forbric/meta/1/meta-1.jar\"],"
				+ "\"game\":[\"--uses net.forbric.extra:thing:2 for something\"]}}");
		List<String> log = new ArrayList<>();
		new OldInstallCleanup(log::add).run(mc, layout.built, layout.staged);
		List<String> problems = new ArrayList<>();
		for (String library : spelled) {
			if (!Files.isRegularFile(libraries.resolve(library))) problems.add("removed, though the profile names it: " + library);
		}
		require(problems.isEmpty(), "every spelling keeps its library: " + problems + "\n" + log);
		for (String library : unnamed) {
			require(!Files.exists(libraries.resolve(library)), "an unnamed sibling still goes: " + library + " " + log);
		}
		require(verify(mc, layout, log).isEmpty(), "and the rest of the rules hold: " + verify(mc, layout, log));
	}

	/**
	 * The test behind the guard for junctions (review A, F2), on what this machine can make: a symbolic link at a
	 * step is redirected, a plain directory is not, and neither is a plain directory whose ancestors are links (a
	 * Minecraft directory under a linked folder). Windows junctions resolve the same way through toRealPath; that
	 * leg is checked on Windows only (ACCEPT).
	 */
	private static void aLinkOrJunctionAtAnyStepIsRedirected(Path base) throws Exception {
		Path real = Files.createDirectories(base.resolve("real/mc/libraries/net/forbric"));
		Path elsewhere = Files.createDirectories(base.resolve("elsewhere/net"));
		Path linkedParent = Files.createSymbolicLink(base.resolve("linked-parent"), base.resolve("real"));
		Path mc = base.resolve("real/mc");
		require(!OldInstallCleanup.redirected(mc.resolve("libraries")), "a plain libraries is not redirected");
		require(!OldInstallCleanup.redirected(linkedParent.resolve("mc/libraries/net")),
				"a plain directory under a linked ancestor is not redirected");
		require(!OldInstallCleanup.redirected(mc.resolve("LIBRARIES")) || !Files.exists(mc.resolve("LIBRARIES")),
				"a different case of the same name is not redirected on a case-insensitive file system");
		Path second = Files.createDirectories(base.resolve("second"));
		Path linked = Files.createSymbolicLink(second.resolve("libraries"), elsewhere.getParent());
		require(OldInstallCleanup.redirected(linked), "a libraries that is a link is redirected");
		// (A step below it is judged against its own resolved parent, so the guard checks from libraries down.)
		// And the clean-up refuses such a tree, naming it, with what is in it untouched.
		write(elsewhere.resolve("forbric/old/1/old-1.jar"), "another directory's library");
		List<String> log = new ArrayList<>();
		new OldInstallCleanup(log::add).run(second, List.of(), List.of());
		require(Files.isRegularFile(elsewhere.resolve("forbric/old/1/old-1.jar")), "a linked libraries is left whole");
		require(log.stream().anyMatch(l -> l.startsWith("[cleanup] left " + linked + " ")), "and named: " + log);
		if (System.getProperty("os.name", "").startsWith("Windows")) aJunctionedLibrariesIsLeftWhole(base);
	}

	/**
	 * The download cache reached through a link (review of lane DLCLEAN, F1): a {@code .forbric-build} that is a link
	 * to another directory, a {@code dl} that is one, and on Windows a {@code dl} that is a directory junction. In each
	 * the cache is someone else's too: an orphan there and every input stay, nothing at all is removed, and the link is
	 * named once. What holds each: the {@code .forbric-build} step of the guard in {@code cleanDownloads} (the path
	 * through a symbolic link resolves, so nothing else stops the walk); for a symbolic {@code dl}, the guard's {@code dl}
	 * step and, behind it, the walk's own refusal to follow links; for a junctioned {@code dl}, the guard's {@code dl}
	 * step alone (the walk treats a junction as a directory and descends).
	 */
	private static void aLinkedBuildOrDownloadCacheIsLeftWhole(Path base) throws Exception {
		// A .forbric-build that is a symbolic link to another directory's build, whose dl/ holds the inputs and an orphan.
		Path mc = Files.createDirectories(base.resolve("build-linked/mc"));
		Path otherBuild = base.resolve("build-linked/other-build");
		writeCache(otherBuild.resolve("dl"));
		Path build = Files.createSymbolicLink(mc.resolve(".forbric-build"), otherBuild);
		requireCacheLeftWhole(mc, otherBuild.resolve("dl"), build);

		// A dl/ that is a symbolic link, inside a .forbric-build that is not.
		mc = Files.createDirectories(base.resolve("dl-linked/mc"));
		Path otherDl = base.resolve("dl-linked/other-dl");
		writeCache(otherDl);
		Files.createDirectories(mc.resolve(".forbric-build"));
		Path dl = Files.createSymbolicLink(mc.resolve(".forbric-build/dl"), otherDl);
		requireCacheLeftWhole(mc, otherDl, dl);

		if (System.getProperty("os.name", "").startsWith("Windows")) aJunctionedDownloadCacheIsLeftWhole(base);
	}

	/** On Windows, a {@code dl} made with {@code mklink /J}, as {@link #aJunctionedLibrariesIsLeftWhole} does for libraries. */
	private static void aJunctionedDownloadCacheIsLeftWhole(Path base) throws Exception {
		Path target = base.resolve("dl-junction/other-dl");
		writeCache(target);
		Path mc = Files.createDirectories(base.resolve("dl-junction/mc"));
		Path junction = Files.createDirectories(mc.resolve(".forbric-build")).resolve("dl");
		Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J", junction.toString(), target.toString())
				.redirectErrorStream(true).start();
		String output = new String(mklink.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
		if (mklink.waitFor() != 0 || !Files.isDirectory(junction)) {
			NOT_RUN.add("the junctioned download cache case (mklink /J failed: " + output + ")");
			System.out.println("OldInstallCleanupTest: SKIP the junctioned download cache case: mklink /J failed: " + output);
			return;
		}
		try {
			requireCacheLeftWhole(mc, target, junction);
		} finally {
			// rmdir on a junction removes the junction only, never what it points at.
			new ProcessBuilder("cmd", "/c", "rmdir", junction.toString()).inheritIO().start().waitFor();
		}
	}

	/** A download cache as an install leaves it: this install's inputs, and an orphan beside them. */
	private static void writeCache(Path dl) throws IOException {
		writeUserdev(dl.resolve(DL_INPUTS.get(0)), USERDEV_LIBRARIES);
		for (String input : DL_INPUTS.subList(1, DL_INPUTS.size())) write(dl.resolve(input), "input " + input);
		write(dl.resolve("orphan.jar"), "another directory's cached download");
	}

	private static void requireCacheLeftWhole(Path mc, Path cache, Path link) throws IOException {
		Map<String, String> before = manifest(cache);
		require(before.containsKey("orphan.jar") && before.keySet().containsAll(DL_INPUTS), "the premise: " + before);
		List<String> log = new ArrayList<>();
		OldInstallCleanup.Result result = new OldInstallCleanup(log::add)
				.run(mc, List.of(), List.of(), () -> ArtifactBuilder.downloadInputs(mc, "26.2"));
		require(before.equals(manifest(cache)), "a download cache reached through " + link + " is left whole: " + log);
		require(result.removed() == 0 && result.failed() == 0, "nothing removed: " + result + " " + log);
		require(log.stream().noneMatch(l -> l.startsWith("[cleanup] clearing the download cache")),
				"nor a clean-up of it announced: " + log);
		require(log.stream().filter(l -> l.startsWith("[cleanup] left " + link + " ")).count() == 1,
				"and the link named, once: " + log);
	}

	/**
	 * The same on Windows with a real directory junction (C6 wave 2, review of lane INSTFIX, R-4): {@code mklink /J}
	 * needs no privilege, so CI's Windows job runs this. The junction's target holds a library no profile of the
	 * junctioned install names; the clean-up must leave it, and name the junction.
	 */
	private static void aJunctionedLibrariesIsLeftWhole(Path base) throws Exception {
		Path target = Files.createDirectories(base.resolve("junction-target/libraries"));
		write(target.resolve("net/forbric/sentinel/1/sentinel-1.jar"), "another install's library");
		Path mc = Files.createDirectories(base.resolve("junctioned"));
		Path junction = mc.resolve("libraries");
		Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J", junction.toString(), target.toString())
				.redirectErrorStream(true).start();
		String output = new String(mklink.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
		if (mklink.waitFor() != 0 || !Files.isDirectory(junction)) {
			NOT_RUN.add("the junction case (mklink /J failed: " + output + ")");
			System.out.println("OldInstallCleanupTest: SKIP the junction case: mklink /J failed: " + output);
			return;
		}
		try {
			require(OldInstallCleanup.isLink(junction) || OldInstallCleanup.redirected(junction),
					"a junctioned libraries is a link or redirected");
			List<String> log = new ArrayList<>();
			new OldInstallCleanup(log::add).run(mc, List.of(), List.of());
			require(Files.isRegularFile(target.resolve("net/forbric/sentinel/1/sentinel-1.jar")),
					"a junctioned libraries is left whole: " + log);
			require(log.stream().anyMatch(l -> l.startsWith("[cleanup] left ") && l.contains(junction.toString())),
					"and named: " + log);
		} finally {
			// rmdir on a junction removes the junction only, never what it points at.
			new ProcessBuilder("cmd", "/c", "rmdir", junction.toString()).inheritIO().start().waitFor();
		}
	}

	/**
	 * Only a profile -- versions/<id>/<id>.json, or a parent one names -- that cannot be read stops the libraries
	 * (review B, 5). Any other JSON beside it that does not parse (the game's or a mod's under version isolation: an
	 * empty file, comments) is skipped and named; a profile written with a byte-order mark is read.
	 */
	private static void onlyAProfileThatCannotBeReadStopsTheLibraries(Path mc) throws Exception {
		Layout layout = layout(mc);
		write(mc.resolve("versions/26.2-forbric/usercache.json"), "");
		write(mc.resolve("versions/keep-old/some-mod.json"), "// a mod's settings\n{\"a\": 1}");
		String bomNamed = "net/forbric/bom/1/bom-1.jar";
		write(mc.resolve("libraries").resolve(bomNamed), "old " + bomNamed);
		write(mc.resolve("versions/bom/bom.json"), "\uFEFF{\"id\":\"bom\",\"libraries\":[{\"name\":\"net.forbric:bom:1\"}]}");
		List<String> log = new ArrayList<>();
		new OldInstallCleanup(log::add).run(mc, layout.built, layout.staged);
		require(verify(mc, layout, log).isEmpty(), "the libraries are still cleaned: " + verify(mc, layout, log) + "\n" + log);
		for (String skipped : List.of("usercache.json", "some-mod.json")) {
			require(log.stream().anyMatch(l -> l.startsWith("[cleanup] skipped ") && l.contains(skipped)),
					"a JSON that is not a profile is named as skipped: " + skipped + " " + log);
		}
		require(Files.isRegularFile(mc.resolve("libraries").resolve(bomNamed)), "a profile with a byte-order mark is read");
		require(OldInstallCleanup.isProfile(mc.resolve("versions/a/a.json")) && !OldInstallCleanup.isProfile(
				mc.resolve("versions/a/b.json")), "a profile is versions/<id>/<id>.json");
		require(OldInstallCleanup.isProfile(mc.resolve("versions/Upper/upper.json")),
				"a profile in another letter case is a profile (a launcher on Windows or macOS reads it)");
		// A parent named by inheritsFrom that cannot be read stops the libraries, like any profile.
		Path parentMc = mc.resolveSibling(mc.getFileName() + "-parent");
		Layout parent = layout(parentMc);
		write(parentMc.resolve("versions/old-parent/old-parent.json"), "");
		List<String> parentLog = new ArrayList<>();
		new OldInstallCleanup(parentLog::add).run(parentMc, parent.built, parent.staged);
		for (String orphan : ORPHANS) {
			require(Files.isRegularFile(parentMc.resolve("libraries").resolve(orphan)), "kept while a parent is unreadable: " + orphan);
		}
	}

	/**
	 * Something unexpected (here: a staged path no file system accepts) stops the clean-up with its own line -- not
	 * "could not remove" the Minecraft directory (review B, 3) -- and nothing is counted as a failed file.
	 */
	private static void anUnexpectedFailureStopsAndSaysSo(Path mc) throws Exception {
		Layout layout = layout(mc);
		List<Map<String, Object>> staged = new ArrayList<>(layout.staged);
		staged.add(Map.of("downloads", Map.of("artifact", Map.of("path", "net/forbric/bad\u0000name.jar"))));
		Map<String, String> before = manifest(mc);
		List<String> log = new ArrayList<>();
		OldInstallCleanup.Result result = new OldInstallCleanup(log::add).run(mc, layout.built, staged);
		require(log.stream().anyMatch(l -> l.startsWith("[cleanup] the clean-up stopped part-way (")
				&& l.endsWith("): every old file it had not reached yet is left in place, and the install carries on")),
				"the stop has its own line: " + log);
		require(log.stream().noneMatch(l -> l.startsWith("[cleanup] could not remove")), "and no 'could not remove': " + log);
		require(result.removed() == 0 && result.failed() == 0, "nothing removed, nothing counted as failed: " + result);
		require(before.equals(manifest(mc)), "every file is still there");
	}

	// --- the controls -------------------------------------------------------------------------------------------

	private static void ignoringTheProfilesTurnsItRed(Path mc) throws Exception {
		Layout layout = layout(mc);
		List<String> log = new ArrayList<>();
		new OldInstallCleanup(log::add, OldInstallCleanup.RETIRED_BUILD_FILES, true, false, true)
				.run(mc, layout.built, layout.staged);
		List<String> problems = verify(mc, layout, log);
		for (String named : NAMED_OLD) {
			require(problems.contains("a library a profile names was removed: " + named), "control (profiles ignored) red on "
					+ named + ": " + problems);
		}
	}

	private static void reachingThisInstallsOutputsTurnsItRedWithoutTheGuard(Path mc) throws Exception {
		List<String> broad = new ArrayList<>(OldInstallCleanup.RETIRED_BUILD_FILES);
		broad.add("out/*");
		Layout guarded = layout(mc.resolve("guarded"));
		List<String> log = new ArrayList<>();
		new OldInstallCleanup(log::add, broad, true, true, true).run(guarded.mc, guarded.built, guarded.staged);
		require(verify(guarded.mc, guarded, log).isEmpty(), "with the guard on, a rule that reaches this install's own"
				+ " outputs removes none of them: " + verify(guarded.mc, guarded, log));

		Layout open = layout(mc.resolve("unguarded"));
		log.clear();
		new OldInstallCleanup(log::add, broad, true, true, false).run(open.mc, open.built, open.staged);
		List<String> problems = verify(open.mc, open, log);
		for (String output : List.of("out/patched-mc-neoforge-26.2.jar", "out/patched-mc-neoforge-26.2.jar.pins",
				"out/neoforge-runtime.jar", "out/neoforge-runtime.jar.pins", "out/vanilla-reference-26.2.jar")) {
			require(problems.contains("this install's own build file was removed: " + output),
					"control (guard off, rule reaching out/*) red on " + output + ": " + problems);
		}
	}

	/**
	 * Control (a): a clean-up that takes none of the inputs as named, with its guard off, removes this install's own
	 * downloads -- the checks must see each one go. With the guard on the same rule removes none of them.
	 */
	private static void ignoringTheDownloadInputsTurnsItRedWithoutTheGuard(Path mc) throws Exception {
		Layout guarded = layout(mc.resolve("guarded"));
		List<String> log = new ArrayList<>();
		new OldInstallCleanup(log::add, OldInstallCleanup.RETIRED_BUILD_FILES, true, true, true, false)
				.run(guarded.mc, guarded.built, guarded.staged, guarded.downloads);
		require(verify(guarded.mc, guarded, log, true).isEmpty(), "with the guard on, inputs ignored by the rule are still"
				+ " kept: " + verify(guarded.mc, guarded, log, true));
		Layout open = layout(mc.resolve("unguarded"));
		log.clear();
		new OldInstallCleanup(log::add, OldInstallCleanup.RETIRED_BUILD_FILES, true, true, false, false)
				.run(open.mc, open.built, open.staged, open.downloads);
		List<String> problems = verify(open.mc, open, log, true);
		for (String input : DL_INPUTS) {
			require(problems.contains("a download this install reads was removed: " + input),
					"control (inputs ignored, guard off) red on " + input + ": " + problems);
		}
	}

	/** Control (b): a clean-up that does not judge the download cache keeps every orphan there -- the checks see each. */
	private static void notJudgingTheDownloadCacheKeepsItsOrphans(Path mc) throws Exception {
		Layout layout = layout(mc);
		List<String> log = new ArrayList<>();
		new OldInstallCleanup(log::add).run(mc, layout.built, layout.staged, null);
		List<String> problems = verify(mc, layout, log, true);
		for (String old : DL_OLD) {
			require(problems.contains("a cached download this install does not read was kept: " + old),
					"control (cache not judged) red on " + old + ": " + problems);
		}
	}

	// --- Chinese ------------------------------------------------------------------------------------------------

	private static void theLinesExistInChinese(Path mc) throws Exception {
		Layout layout = layout(mc);
		List<String> log = new ArrayList<>();
		System.setProperty(LANGUAGE, "zh");
		try {
			new OldInstallCleanup(log::add).run(mc, layout.built, layout.staged, layout.downloads);
			require(log.stream().anyMatch(l -> l.startsWith("[cleanup] 已删除 ") && l.endsWith("（旧版 Forbric 留下的文件，已没有任何版本使用）")),
					"removals in Chinese: " + log);
			Path dl = mc.resolve(".forbric-build/dl");
			require(log.contains("[cleanup] 清理下载缓存 " + dl + "：本次安装用不到的文件都会删除，其中也包括为其他 Minecraft 版本缓存的下载；"
					+ "以后哪次安装需要，会重新下载"), "the download cache's line in Chinese: " + log);
			require(log.contains("[cleanup] 已删除 " + dl.resolve("forge-userdev.jar") + "（缓存的下载文件，本次安装用不到）"),
					"a removed download in Chinese: " + log);
			require(log.stream().anyMatch(l -> l.startsWith("[cleanup] 保留 ")
					&& l.endsWith("：它是链接（符号链接或目录联接），或经由链接到达；清理从不跟随或删除链接")), "links in Chinese: " + log);
			require(log.get(log.size() - 1).matches("\\[cleanup\\] 已删除 [0-9]+ 个旧版 Forbric 留下或本次安装用不到的文件（[0-9.]+ MB）"),
					"the summary in Chinese: " + log);
			Path any = mc.resolve("x");
			require(InstallerText.cleanupFailed(any, "r").equals("[cleanup] 无法删除 " + any + "（r），已保留；安装照常完成"),
					InstallerText.cleanupFailed(any, "r"));
			require(InstallerText.cleanupProfileUnreadable(any, "r").startsWith("[cleanup] 无法读取 " + any + "（r）"),
					InstallerText.cleanupProfileUnreadable(any, "r"));
			require(InstallerText.cleanupOff().equals("[cleanup] 已关闭（-Dforbric.installerCleanup=off）：旧版 Forbric 的文件全部保留"),
					InstallerText.cleanupOff());
			require(InstallerText.cleanupSummary(2, 0, 1).endsWith("；1 个无法删除，已保留"), InstallerText.cleanupSummary(2, 0, 1));
			require(InstallerText.cleanupStopped("r").equals("[cleanup] 清理中途停止（r）：还没处理到的旧文件全部保留；安装照常完成"),
					InstallerText.cleanupStopped("r"));
			require(InstallerText.cleanupSkippedJson(any, "r").startsWith("[cleanup] 跳过 " + any + "（r）：它不是版本配置文件"),
					InstallerText.cleanupSkippedJson(any, "r"));
		} finally {
			System.setProperty(LANGUAGE, "en");
		}
		require(InstallerText.cleanupOff().startsWith("[cleanup] off"), "and English again once English is asked for");
	}

	// --- the layout ---------------------------------------------------------------------------------------------

	private static final class Layout {
		Path mc;
		Path outside;
		List<Path> built = new ArrayList<>();
		List<Map<String, Object>> staged = new ArrayList<>();
		Map<String, String> userData = new TreeMap<>();
		/** What the installer passes: the build's inputs, read from the userdev jar in the cache. */
		OldInstallCleanup.DownloadInputs downloads;
	}

	private static Layout layout(Path mc) throws IOException {
		Layout layout = new Layout();
		layout.mc = mc;
		Path libraries = mc.resolve("libraries");
		for (String now : List.of(KERNEL_NOW, BASE_NOW, RUNTIME_NOW, REFERENCE_NOW)) {
			write(libraries.resolve(now), "current " + now);
			layout.staged.add(Map.of("name", "staged", "downloads", Map.of("artifact", Map.of("path", now))));
		}
		for (String old : ORPHANS) write(libraries.resolve(old), "old " + old);
		for (String old : NAMED_OLD) write(libraries.resolve(old), "old " + old);
		for (String other : OTHER_GROUPS) write(libraries.resolve(other), "other " + other);

		// Links: one at a retired name in .forbric-build, one directory and one file under net/forbric.
		layout.outside = Files.createDirectories(mc.resolveSibling(mc.getFileName() + "-outside"));
		write(layout.outside.resolve("victim.jar"), "not the installer's");
		write(layout.outside.resolve("dir/old/1/old-1.jar"), "not the installer's either");
		Files.createDirectories(libraries.resolve("net/forbric/linked/1.0"));
		Files.createSymbolicLink(libraries.resolve("net/forbric/linked/1.0/linked-1.0.jar"), layout.outside.resolve("victim.jar"));
		Files.createSymbolicLink(libraries.resolve("net/forbric/linkdir"), layout.outside.resolve("dir"));

		Path build = mc.resolve(".forbric-build");
		for (String retired : RETIRED_BUILD) write(build.resolve(retired), "retired " + retired);
		for (String current : CURRENT_BUILD) write(build.resolve(current), "current " + current);
		write(build.resolve(OldInstallCleanup.MERGE_TOOLS), "the link checker");
		// The download cache: this install's inputs (a userdev jar whose config lists the runtime's libraries), and what
		// older installs and other versions left beside them, and a link.
		Path dl = build.resolve("dl");
		writeUserdev(dl.resolve(DL_INPUTS.get(0)), USERDEV_LIBRARIES);
		for (String input : DL_INPUTS.subList(1, DL_INPUTS.size())) write(dl.resolve(input), "input " + input);
		for (String old : DL_OLD) write(dl.resolve(old), "cached " + old);
		Files.createSymbolicLink(dl.resolve("linked.jar"), layout.outside.resolve("victim.jar"));
		layout.downloads = () -> ArtifactBuilder.downloadInputs(mc, "26.2");
		Files.createSymbolicLink(build.resolve("clean.jar"), layout.outside.resolve("victim.jar"));
		for (String output : List.of("out/patched-mc-neoforge-26.2.jar", "out/neoforge-runtime.jar",
				"out/vanilla-reference-26.2.jar")) {
			layout.built.add(build.resolve(output));
		}

		// This install's profile, the base version it inherits from, and a second profile still on old files: the old
		// merged base named in an argument, an older kernel by coordinate, and forbricruntime only through its parent.
		write(mc.resolve("versions/26.2-forbric/26.2-forbric.json"), "{\"id\":\"26.2-forbric\",\"inheritsFrom\":\"26.2\","
				+ "\"arguments\":{\"game\":[\"--gameJar\",\"${library_directory}/" + BASE_NOW + "\",\"--runtimeJar\","
				+ "\"${library_directory}/" + RUNTIME_NOW + "\",\"--nativeReference\",\"${library_directory}/" + REFERENCE_NOW
				+ "\"]},\"libraries\":[" + library("net.forbric:forbric-kernel:0.4.0-dev", KERNEL_NOW) + ","
				+ library("org.ow2.asm:asm:9.9.1", "org/ow2/asm/asm/9.9.1/asm-9.9.1.jar") + "]}");
		write(mc.resolve("versions/26.2/26.2.json"), "{\"id\":\"26.2\",\"libraries\":[{\"name\":\"com.mojang:brigadier:1.0\"}]}");
		write(mc.resolve("versions/keep-old/keep-old.json"), "{\"id\":\"keep-old\",\"inheritsFrom\":\"old-parent\","
				+ "\"arguments\":{\"game\":[\"--gameJar\",\"${library_directory}/" + NAMED_OLD.get(1) + "\"]},"
				+ "\"libraries\":[{\"name\":\"net.forbric:forbric-kernel:0.3.1-beta2\"}]}");
		write(mc.resolve("versions/old-parent/old-parent.json"), "{\"id\":\"old-parent\",\"libraries\":[{\"name\":"
				+ "\"x\",\"downloads\":{\"artifact\":{\"path\":\"" + NAMED_OLD.get(2) + "\"}}}]}");

		for (String data : USER_DATA) write(mc.resolve(data), "the player's " + data);
		layout.userData.putAll(manifest(mc, USER_DATA));
		return layout;
	}

	private static String library(String coordinate, String path) {
		return "{\"name\":\"" + coordinate + "\",\"downloads\":{\"artifact\":{\"path\":\"" + path + "\"}}}";
	}

	// --- the checks ----------------------------------------------------------------------------------------------

	/** Every rule the clean-up must hold, as a list of what it broke; empty when it held them all. */
	private static List<String> verify(Path mc, Layout layout, List<String> log) throws IOException {
		return verify(mc, layout, log, false);
	}

	/**
	 * @param downloadsJudged whether the run was told this install's download inputs: then exactly they stay in
	 *                        {@code dl/} and every old file there goes, named; otherwise every file there stays
	 */
	private static List<String> verify(Path mc, Layout layout, List<String> log, boolean downloadsJudged) throws IOException {
		List<String> problems = new ArrayList<>();
		Path dl = mc.resolve(".forbric-build/dl");
		for (String input : DL_INPUTS) {
			if (!Files.isRegularFile(dl.resolve(input))) problems.add("a download this install reads was removed: " + input);
		}
		for (String old : DL_OLD) {
			Path file = dl.resolve(old);
			if (!downloadsJudged) {
				if (!Files.isRegularFile(file)) problems.add("a cached download was removed by a run told no inputs: " + old);
			} else if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
				problems.add("a cached download this install does not read was kept: " + old);
			} else if (!log.contains("[cleanup] removed " + file + " (a cached download this install does not use)")) {
				problems.add("a removal the log does not name: dl/" + old);
			}
		}
		if (!Files.isSymbolicLink(dl.resolve("linked.jar"))) problems.add("the link in the download cache was removed");
		Path libraries = mc.resolve("libraries");
		Path build = mc.resolve(".forbric-build");
		for (String now : List.of(KERNEL_NOW, BASE_NOW, RUNTIME_NOW, REFERENCE_NOW)) {
			if (!Files.isRegularFile(libraries.resolve(now))) problems.add("this install's own library was removed: " + now);
		}
		for (String named : NAMED_OLD) {
			if (!Files.isRegularFile(libraries.resolve(named))) problems.add("a library a profile names was removed: " + named);
		}
		for (String other : OTHER_GROUPS) {
			if (!Files.isRegularFile(libraries.resolve(other))) problems.add("a library outside net/forbric was removed: " + other);
		}
		for (String orphan : ORPHANS) {
			Path file = libraries.resolve(orphan);
			if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) problems.add("an old library nothing names was kept: " + orphan);
			else if (log.stream().noneMatch(l -> l.equals("[cleanup] removed " + file
					+ " (an older Forbric's file that no installed version uses)"))) {
				problems.add("a removal the log does not name: " + orphan);
			}
			if (Files.exists(file.getParent(), LinkOption.NOFOLLOW_LINKS)) problems.add("its emptied directory was kept: " + orphan);
		}
		for (String retired : RETIRED_BUILD) {
			Path file = build.resolve(retired);
			if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) problems.add("an old build product was kept: " + retired);
			else if (log.stream().noneMatch(l -> l.startsWith("[cleanup] removed " + file + " "))) {
				problems.add("a removal the log does not name: " + retired);
			}
		}
		if (Files.exists(build.resolve("ats"), LinkOption.NOFOLLOW_LINKS)) problems.add("the emptied ats directory was kept");
		if (Files.exists(build.resolve("26.2-65.0.1"), LinkOption.NOFOLLOW_LINKS)) problems.add("v0.1.0's work tree was kept");
		for (String current : CURRENT_BUILD) {
			if (!Files.isRegularFile(build.resolve(current))) problems.add("this install's own build file was removed: " + current);
		}
		if (!layout.userData.equals(manifest(mc, USER_DATA))) problems.add("the player's data changed");
		return problems;
	}

	// --- plumbing ------------------------------------------------------------------------------------------------

	private static Map<String, String> manifest(Path mc, List<String> files) throws IOException {
		Map<String, String> sums = new TreeMap<>();
		for (String file : files) sums.put(file, sha1(mc.resolve(file)));
		return sums;
	}

	/** Every regular file and link under {@code mc}, by path, with its digest or its target. */
	private static Map<String, String> manifest(Path mc) throws IOException {
		Map<String, String> all = new HashMap<>();
		Files.walkFileTree(mc, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
				all.put(mc.relativize(file).toString(), Files.isSymbolicLink(file)
						? "-> " + Files.readSymbolicLink(file) : sha1(file));
				return FileVisitResult.CONTINUE;
			}
		});
		return new LinkedHashMap<>(new TreeMap<>(all));
	}

	private static String sha1(Path file) throws IOException {
		try {
			StringBuilder hex = new StringBuilder();
			for (byte b : MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(file))) {
				hex.append(String.format("%02x", b));
			}
			return hex.toString();
		} catch (java.security.NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	/** A NeoForge userdev jar as far as the installer reads one: a {@code config.json} listing the runtime's libraries. */
	private static void writeUserdev(Path jar, List<String> libraries) throws IOException {
		Files.createDirectories(jar.getParent());
		StringBuilder json = new StringBuilder("{\"libraries\":[");
		for (int i = 0; i < libraries.size(); i++) json.append(i == 0 ? "" : ",").append('"').append(libraries.get(i)).append('"');
		json.append("]}");
		try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
			zip.putNextEntry(new java.util.zip.ZipEntry("config.json"));
			zip.write(json.toString().getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
	}

	private static void write(Path file, String content) throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
