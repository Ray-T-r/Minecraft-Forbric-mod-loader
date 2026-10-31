package net.forbric.installer.kernel;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code tools/dev.py prepare} on its own stage: the merged base's and MinecraftForge's retired directories go, and
 * nothing that leads out of the stage is deleted through.
 */
public final class DevPrepareRetiredTest {
	public static void main(String[] args) throws Exception {
		// By its real path: a temporary directory under a linked one (macOS's /var, /tmp) would make every stage here
		// one reached through a link.
		Path work = Files.createDirectories(Path.of(args[0])).toRealPath();
		int checks = 0;

		// An owned stage holding all three, as an older prepare left it, beside what this one stages.
		Path stage = Files.createDirectories(work.resolve("owned/staged/run"));
		for (String retired : DevPrepare.RETIRED) {
			Files.createDirectories(stage.resolve(retired).resolve("nested"));
			Files.writeString(stage.resolve(retired).resolve("nested/file.jar"), "old");
		}
		Files.createDirectories(stage.resolve("neoforge-patched"));
		Files.writeString(stage.resolve("neoforge-patched/patched-mc-neoforge-26.2.jar"), "base");
		List<String> log = new ArrayList<>();
		DevPrepare.removeRetired(stage, log::add);
		for (String retired : DevPrepare.RETIRED) {
			require(!Files.exists(stage.resolve(retired), LinkOption.NOFOLLOW_LINKS), retired + " was left in an owned stage: " + log);
		}
		require(Files.isRegularFile(stage.resolve("neoforge-patched/patched-mc-neoforge-26.2.jar")), "the staged base was removed");
		checks += 2;

		// Someone else's data linked into an owned stage: a retired directory that IS a link, and one that HOLDS one.
		Path shared = Files.createDirectories(work.resolve("shared-fixtures"));
		Files.writeString(shared.resolve("forge-runtime.jar"), "someone else's");
		Path linked = Files.createDirectories(work.resolve("linked/staged/run"));
		Files.createSymbolicLink(linked.resolve("forge-runtime"), shared);
		Files.createDirectories(linked.resolve("merged-base"));
		Files.createSymbolicLink(linked.resolve("merged-base/patched-mc-merged-26.2.jar"), shared.resolve("forge-runtime.jar"));
		Files.createDirectories(linked.resolve("forge-patched"));
		log.clear();
		DevPrepare.removeRetired(linked, log::add);
		require(Files.isSymbolicLink(linked.resolve("forge-runtime")) && Files.isDirectory(linked.resolve("merged-base")),
				"a retired directory that is or holds a link was removed: " + log);
		require(Files.isRegularFile(shared.resolve("forge-runtime.jar")), "a file was deleted through a link");
		require(!Files.exists(linked.resolve("forge-patched")), "the plain retired directory beside them was left");
		require(log.stream().filter(l -> l.contains("symbolic link")).count() == 2, "the links left in place were not named: " + log);
		checks += 4;

		// A stage reached through a linked staged/ directory is someone else's: nothing in it is touched.
		Path elsewhere = Files.createDirectories(work.resolve("elsewhere/run"));
		Files.createDirectories(elsewhere.resolve("merged-base"));
		Files.createDirectories(work.resolve("via"));
		Files.createSymbolicLink(work.resolve("via/staged"), work.resolve("elsewhere"));
		log.clear();
		DevPrepare.removeRetired(work.resolve("via/staged/run"), log::add);
		require(Files.isDirectory(elsewhere.resolve("merged-base")), "a stage under a linked staged/ was cleaned: " + log);
		checks++;

		// What tools/dev.py hands over from a git worktree: its forbric-kernel/.dev is a link to the main checkout's, so
		// the stage below it is the main checkout's shared one, though neither the stage nor staged/ is a link.
		Path mainDev = Files.createDirectories(work.resolve("main/forbric-kernel/.dev"));
		Path mainStage = Files.createDirectories(mainDev.resolve("staged/run"));
		for (String retired : DevPrepare.RETIRED) Files.createDirectories(mainStage.resolve(retired));
		Path worktreeKernel = Files.createDirectories(work.resolve("worktree/forbric-kernel"));
		Files.createSymbolicLink(worktreeKernel.resolve(".dev"), mainDev);
		log.clear();
		DevPrepare.removeRetired(worktreeKernel.resolve(".dev/staged/run"), log::add);
		for (String retired : DevPrepare.RETIRED) {
			require(Files.isDirectory(mainStage.resolve(retired)), "a stage under a linked .dev was cleaned: " + log);
		}
		require(log.stream().anyMatch(l -> l.contains("symbolic link")), "the stage left alone was not named: " + log);
		checks += 2;

		System.out.println("PASS dev stage cleanup: " + checks + " checks (retired directories removed from an owned stage;"
				+ " links, and stages reached through one anywhere on their path, left alone)");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
