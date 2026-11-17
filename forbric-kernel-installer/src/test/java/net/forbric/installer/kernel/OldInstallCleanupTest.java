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
	/** This install's own build outputs and their stamps, the download cache and NFRT's: never touched. */
	private static final List<String> CURRENT_BUILD = List.of(
			"out/patched-mc-neoforge-26.2.jar", "out/patched-mc-neoforge-26.2.jar.pins", "out/neoforge-runtime.jar",
			"out/neoforge-runtime.jar.pins", "out/vanilla-reference-26.2.jar", "dl/forge-userdev.jar",
			"dl/neoforge-26.2.0.93-userdev.jar", "dl/server.jar", "nfrt/cache/entry.bin", "nfrt-work/state.json",
			"tools/neoform-runtime-2.0.18-all.jar");
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
			everySpellingOfALibraryKeepsIt(work.resolve("spellings"));
			aLinkOrJunctionAtAnyStepIsRedirected(work.resolve("redirected"));
			onlyAProfileThatCannotBeReadStopsTheLibraries(work.resolve("json"));
			anUnexpectedFailureStopsAndSaysSo(work.resolve("stopped"));
			theMergeToolsGoOnlyWhenThisInstallerCarriesNone(work.resolve("tools"));
			anUnreadableProfileKeepsEveryLibrary(work.resolve("unreadable"));
			aFailedDeleteIsNamedAndTheRunCarriesOn(work.resolve("locked"));
			theSwitchLeavesEverything(work.resolve("off"));
			ignoringTheProfilesTurnsItRed(work.resolve("control-profiles"));
			reachingThisInstallsOutputsTurnsItRedWithoutTheGuard(work.resolve("control-guard"));
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
				true, true).run(mc, layout.built, layout.staged);
		List<String> problems = verify(mc, layout, log);
		require(problems.isEmpty(), "the clean-up broke its rules:\n  " + String.join("\n  ", problems) + "\n" + log);
		require(result.failed() == 0, "nothing failed: " + result);
		require(result.removed() == ORPHANS.size() + RETIRED_BUILD.size(), "every removal counted: " + result);
		require(log.stream().filter(l -> l.startsWith("[cleanup] removed /") || l.startsWith("[cleanup] removed " + mc))
				.count() == result.removed(), "one line per removed file: " + log);
		require(log.get(log.size() - 1).matches("\\[cleanup\\] removed " + result.removed()
				+ " file\\(s\\) an older Forbric left behind \\([0-9.]+ MB\\)"), "the summary closes it: " + log);
		// The symbolic links: left, and what they point at untouched.
		require(Files.isSymbolicLink(mc.resolve(".forbric-build/clean.jar")), "a retired name that is a link is left");
		require(Files.isSymbolicLink(mc.resolve("libraries/net/forbric/linkdir")), "a linked directory is left");
		require(Files.isSymbolicLink(mc.resolve("libraries/net/forbric/linked/1.0/linked-1.0.jar")),
				"a linked file in an unnamed library is left");
		require(log.stream().anyMatch(l -> l.startsWith("[cleanup] left ") && l.contains("clean.jar")), "and named: " + log);
		require(Files.isRegularFile(layout.outside.resolve("victim.jar"))
				&& Files.isRegularFile(layout.outside.resolve("dir/old/1/old-1.jar")), "no link was followed");
		// A second run over the result finds nothing left to do.
		List<String> again = new ArrayList<>();
		OldInstallCleanup.Result second = new OldInstallCleanup(again::add, OldInstallCleanup.RETIRED_BUILD_FILES, true,
				true, true).run(mc, layout.built, layout.staged);
		require(second.removed() == 0 && second.failed() == 0, "a second run removes nothing: " + again);
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
	 * a path after {@code =} in a JVM argument; a sub-group's coordinate inside a longer string. An unnamed one beside
	 * them still goes, so the case cannot pass by keeping everything.
	 */
	private static void everySpellingOfALibraryKeepsIt(Path mc) throws Exception {
		Layout layout = layout(mc);
		Path libraries = mc.resolve("libraries");
		List<String> spelled = List.of("net/forbric/forbric-kernel/0.3.1/forbric-kernel-0.3.1.jar",
				"net/forbric/natives/1/natives-1-macos.jar", "net/forbric/a/1/a-1.jar", "net/forbric/b/1/b-1.jar",
				"net/forbric/c/1/c-1.jar", "net/forbric/extra/thing/2/thing-2.jar");
		String unnamed = "net/forbric/extra/other/2/other-2.jar";
		for (String library : spelled) write(libraries.resolve(library), "old " + library);
		write(libraries.resolve(unnamed), "old " + unnamed);
		write(mc.resolve("versions/spelled/spelled.json"), "{\"id\":\"spelled\",\"inheritsFrom\":\"26.2\","
				+ "\"libraries\":[{\"name\":\"net.forbric:forbric-kernel:0.3.1@jar\"},"
				+ "{\"name\":\"net.forbric:natives:1:macos@jar\"}],"
				+ "\"arguments\":{\"jvm\":[\"-p\",\"${library_directory}/net/forbric/a/1/a-1.jar${classpath_separator}"
				+ "${library_directory}/net/forbric/b/1/b-1.jar\",\"-Dsome.path=net/forbric/c/1/c-1.jar\"],"
				+ "\"game\":[\"--uses net.forbric.extra:thing:2 for something\"]}}");
		List<String> log = new ArrayList<>();
		new OldInstallCleanup(log::add).run(mc, layout.built, layout.staged);
		List<String> problems = new ArrayList<>();
		for (String library : spelled) {
			if (!Files.isRegularFile(libraries.resolve(library))) problems.add("removed, though the profile names it: " + library);
		}
		require(problems.isEmpty(), "every spelling keeps its library: " + problems + "\n" + log);
		require(!Files.exists(libraries.resolve(unnamed)), "the sub-group's unnamed sibling still goes: " + log);
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

	// --- Chinese ------------------------------------------------------------------------------------------------

	private static void theLinesExistInChinese(Path mc) throws Exception {
		Layout layout = layout(mc);
		List<String> log = new ArrayList<>();
		System.setProperty(LANGUAGE, "zh");
		try {
			new OldInstallCleanup(log::add).run(mc, layout.built, layout.staged);
			require(log.stream().anyMatch(l -> l.startsWith("[cleanup] 已删除 ") && l.endsWith("（旧版 Forbric 留下的文件，已没有任何版本使用）")),
					"removals in Chinese: " + log);
			require(log.stream().anyMatch(l -> l.startsWith("[cleanup] 保留 ")
					&& l.endsWith("：它是链接（符号链接或目录联接），或经由链接到达；清理从不跟随或删除链接")), "links in Chinese: " + log);
			require(log.get(log.size() - 1).matches("\\[cleanup\\] 已删除旧版 Forbric 留下的 [0-9]+ 个文件（[0-9.]+ MB）"),
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
		List<String> problems = new ArrayList<>();
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

	private static void write(Path file, String content) throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
