/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.installer.kernel;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;

/** Reuses the installer artifact pipeline without installing a player profile or release payload. */
public final class DevPrepare {

	/**
	 * Where an older Forbric's development stage kept the merged base and the MinecraftForge side. Nothing writes
	 * them any more; left in a stage, a script or test that still spells one of these paths keeps reading the old
	 * merged base and Forge carrier instead of failing.
	 */
	static final List<String> RETIRED = List.of("merged-base", "forge-runtime", "forge-patched");

	static final String USAGE = "usage: DevPrepare <mc-dir> <staged-run-dir> <java> [--owned-stage]";

	public static void main(String[] args) throws Exception {
		boolean owned = args.length == 4 && args[3].equals("--owned-stage");
		if (args.length != 3 && !owned) throw new IllegalArgumentException(USAGE);
		Path mc = Path.of(args[0]).toAbsolutePath();
		Path stage = Path.of(args[1]).toAbsolutePath();
		JdkLocator.Jvm jvm = JdkLocator.locate(mc, Path.of(args[2]), System.out::println);
		if (jvm.feature() < 25) throw new IOException("Minecraft 26.2 development needs JDK 25 or newer");
		Path version = mc.resolve("versions").resolve(Pins.MINECRAFT);
		if (!Files.isRegularFile(version.resolve(Pins.MINECRAFT + ".jar"))
				|| !Files.isRegularFile(version.resolve(Pins.MINECRAFT + ".json"))) {
			new MojangDownloader(System.out::println).downloadClient(Pins.MINECRAFT, version);
		}
		Map<String, Path> artifacts = new ArtifactBuilder(System.out::println).build(mc, Pins.MINECRAFT, jvm);
		// The base with its build stamp: the pins it came from, which is how tools/dev.py tells a base built from another
		// NeoForge or NFRT pin from the current one -- its file name says neither.
		copyWithStamp(artifacts.get(ArtifactBuilder.BASE),
				stage.resolve("neoforge-patched/patched-mc-neoforge-" + Pins.MINECRAFT + ".jar"));
		// And the NeoForge carrier with its stamp, for the same reason.
		copyWithStamp(artifacts.get(ArtifactBuilder.NEOFORGE_RUNTIME), stage.resolve("neoforge-runtime/neoforge-runtime.jar"));
		// What every launch path hands the kernel as --nativeReference, measured against the staged base (its
		// provenance records the base's SHA-1).
		copy(artifacts.get(ArtifactBuilder.VANILLA_REFERENCE),
				stage.resolve("vanilla-reference/" + VanillaReference.fileName(Pins.MINECRAFT)));
		if (owned) {
			removeRetired(stage, System.out::println);
		} else {
			for (String retired : RETIRED) {
				if (Files.exists(stage.resolve(retired), LinkOption.NOFOLLOW_LINKS)) {
					System.out.println("Left " + stage.resolve(retired) + " in place: this stage was named with --staged or "
							+ "FORBRIC_OLD and may be shared, so nothing is removed from it.");
				}
			}
		}
		System.out.println("Development artifacts staged under " + stage);
	}

	/**
	 * Removes the {@link #RETIRED} directories from a stage this preparation owns: the stage {@code tools/dev.py}
	 * keeps for its own checkout ({@code forbric-kernel/.dev/staged/run}), which it says with {@code --owned-stage}.
	 * A stage named with {@code --staged} or {@code FORBRIC_OLD} is never cleaned: it may serve another checkout
	 * still on the merged base.
	 *
	 * <p>Even an owned stage is cleaned only where nothing can lead outside it: a stage directory (or the
	 * {@code staged} directory holding it) that is a symbolic link, or a retired directory that is one or that holds one anywhere inside, is left alone and named. A fixture
	 * tree linked into a stage is someone else's data, and deleting through a link deletes it.
	 */
	static void removeRetired(Path stage, java.util.function.Consumer<String> log) throws IOException {
		Path root = stage.getParent();
		if (Files.isSymbolicLink(stage) || !Files.isDirectory(stage, LinkOption.NOFOLLOW_LINKS)
				|| root == null || Files.isSymbolicLink(root)) {
			log.accept("Left the retired directories in " + stage + ": the stage, or the directory holding it, is not a "
					+ "plain directory.");
			return;
		}
		for (String name : RETIRED) {
			Path retired = stage.resolve(name);
			if (!Files.exists(retired, LinkOption.NOFOLLOW_LINKS)) continue;
			if (!Files.isDirectory(retired, LinkOption.NOFOLLOW_LINKS) || holdsALink(retired)) {
				log.accept("Left " + retired + " in place: it is, or holds, a symbolic link.");
				continue;
			}
			Files.walkFileTree(retired, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
					Files.delete(file);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path dir, IOException failed) throws IOException {
					if (failed != null) throw failed;
					Files.delete(dir);
					return FileVisitResult.CONTINUE;
				}
			});
			log.accept("Removed " + retired + ": the merged base and MinecraftForge's files are not staged any more.");
		}
	}

	private static boolean holdsALink(Path dir) throws IOException {
		try (var walk = Files.walk(dir)) {
			return walk.anyMatch(Files::isSymbolicLink);
		}
	}

	/** {@code source} and its {@code .pins} build stamp, side by side, as the build wrote them. */
	private static void copyWithStamp(Path source, Path target) throws IOException {
		copy(source, target);
		copy(source.resolveSibling(source.getFileName() + ".pins"), target.resolveSibling(target.getFileName() + ".pins"));
	}

	private static void copy(Path source, Path target) throws IOException {
		Files.createDirectories(target.getParent());
		if (!Files.isRegularFile(target) || Files.mismatch(source, target) != -1) {
			Path temporary = target.resolveSibling(target.getFileName() + ".part");
			Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
			Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
