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

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

import net.forbric.kernel.util.ForbricLog;

/**
 * Extracts the jars bundled inside the kernel's own jar that must live on the GAME side.
 *
 * <p>Both of them are here for the same structural reason and neither can be parent-loaded: MixinExtras
 * generates classes (the {@code LocalRef} machinery) that must share a loader with the game classes they touch,
 * and the kernel's own game-side half is pinned {@code ALWAYS_GAME} by {@code DelegationPolicy} precisely so
 * that it can name game types. Carrying them inside the boot jar keeps the kernel ONE file — no launcher
 * argument, no installer step, nothing for a profile to get wrong.
 *
 * <p>The two differ in what their absence MEANS, and that difference is carried as data rather than averaged
 * into one warning. A missing MixinExtras is a degraded run: most of fabric-api stops applying its mixins, and
 * saying so is the most the kernel can do. A missing game-side jar is a BROKEN KERNEL — it means the boot jar
 * was built on a machine with no staged artifacts, so every class the kernel itself will ask for game-side is
 * simply not there. That one names the build command, because the reader can fix it in one line.
 */
public final class KernelBundledJars {
	/**
	 * A jar carried at {@code META-INF/jars/} in the boot jar.
	 *
	 * @param fileName  the entry name inside the boot jar, and the name it is extracted under
	 * @param onMissing what to tell the reader when it is not in the boot jar — the consequence first, then the
	 *                  fix if there is one. Never a bare "not found": by the time anyone reads this line they
	 *                  already know something is missing; what they do not know is what it costs them.
	 * @param required  whether a boot without this jar on the classpath is worth attempting at all. A degraded
	 *                  run is allowed to start and say what it lost; a run missing the kernel's OWN game-side
	 *                  classes is not — it cannot get far enough to be diagnosed from where it dies.
	 */
	private record Bundled(String fileName, String onMissing, boolean required) {
	}

	private static final Bundled[] BUNDLED = {
		new Bundled("mixinextras-fabric.jar",
				"mods using MixinExtras (most of fabric-api) will fail to apply their mixins", false),
		new Bundled("forbric-kernel-runtime.jar",
				"this boot jar was built with no staged game artifacts, so the kernel's own game-side classes are "
						+ "absent and anything that needs one will fail to link — rebuild with the staged jars in "
						+ "place (../forbric-loader/run/) via: ./gradlew jar", true),
	};

	private KernelBundledJars() {
	}

	/**
	 * Extracts every bundled game-side jar into {@code <gameDir>/.forbric-kernel/lib/} and returns their paths.
	 */
	public static List<Path> extract(Path gameDir) {
		Path libDir = gameDir.resolve(".forbric-kernel").resolve("lib");
		List<Path> extracted = new ArrayList<>();

		for (Bundled bundled : BUNDLED) {
			String name = bundled.fileName();
			try (InputStream in = KernelBundledJars.class.getResourceAsStream("/META-INF/jars/" + name)) {
				if (in == null) {
					if (bundled.required()) throw new java.io.IOException("required bundled jar is absent: " + name);
					ForbricLog.warn("[Forbric/Boot] bundled jar %s is missing — %s", name, bundled.onMissing());
					continue;
				}
				Path target = materialize(libDir, name, in.readAllBytes());
				extracted.add(target);
				ForbricLog.debug("[Forbric/Boot] verified bundled game-side jar %s at %s", name, target);
			} catch (Exception e) {
				if (bundled.required()) throw new IllegalStateException("bundled jar " + name
						+ " could not be materialized exactly — " + bundled.onMissing(), e);
				ForbricLog.warn("[Forbric/Boot] could not extract bundled jar %s: %s — %s",
						name, String.valueOf(e), bundled.onMissing());
			}
		}

		return extracted;
	}

	/**
	 * Content-addressed copies let an old Windows process retain its old jar without blocking a new version.
	 * Reusing any merely-readable archive would run untested old code; only exact bytes are reusable.
	 */
	static Path materialize(Path libDir, String name, byte[] expected) throws java.io.IOException {
		String hash = sha256(expected);
		Path directory = libDir.resolve(hash);
		Path target = directory.resolve(name);
		if (matches(target, hash)) return target;
		Files.createDirectories(directory);
		Path temporary = Files.createTempFile(directory, ".extract-", ".jar");
		try {
			Files.write(temporary, expected);
			try (ZipFile zip = new ZipFile(temporary.toFile())) {
				if (!zip.entries().hasMoreElements()) throw new java.io.IOException("empty bundled archive: " + name);
			}
			try {
				try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
				catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
					Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
				}
			} catch (java.io.IOException concurrentOrLocked) {
				if (!matches(target, hash)) throw concurrentOrLocked;
			}
			if (!matches(target, hash)) throw new java.io.IOException("bundled jar changed while extracting: " + target);
			return target;
		} finally { Files.deleteIfExists(temporary); }
	}

	private static boolean matches(Path target, String hash) throws java.io.IOException {
		if (!Files.isRegularFile(target)) return false;
		return hash.equals(sha256(Files.readAllBytes(target)));
	}

	private static String sha256(byte[] bytes) {
		try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
		catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
	}
}
