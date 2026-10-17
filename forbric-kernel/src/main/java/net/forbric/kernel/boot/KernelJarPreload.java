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
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.kernel.classloading.DelegationPolicy;
import net.forbric.kernel.classloading.FabricLoaderInternals;
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;

/**
 * Defines every class the kernel's own jar carries while that jar is known to be readable.
 *
 * <h2>Why</h2>
 *
 * <p>The boot side is loaded from the kernel jar by the JVM's class-path loader, and that loader reads a class out of
 * the jar the first time something needs it. Much of the boot side is first needed long after boot, from bytecode the
 * kernel splices into the game: MinecraftForge's fluid rules reach {@code ForgeRuntimeInterop} at the first lava or
 * water placement that asks them, and NeoForge's {@code RegistryManager.revertToFrozen} reaches
 * {@code KernelRegistryRevert} when a player leaves a world. If the jar has been replaced on disk in between (a
 * developer redeploying mid-session), or sits on a volume that went away, that first use reads a file that no longer
 * holds the class: gate M22 measured both — a server crash report ("Exception ticking world",
 * {@code NoClassDefFoundError: net/forbric/kernel/interop/ForgeRuntimeInterop}) when the first lava flow came after
 * the jar was truncated, and a {@code NoClassDefFoundError} for {@code KernelRegistryRevert} on every disconnect.
 *
 * <p>The exit hook used to be the only class resolved early for this reason (see {@code KernelBoot}). Every spliced
 * hook has the same exposure, and so does every class those hooks reach in turn, so naming them one at a time would
 * be a list that goes stale the next time a repair calls into the boot side. Defining the whole jar is the complete
 * answer and it is cheap: 703 classes in about 65 ms on a cold JVM, most of which boot loads anyway.
 *
 * <p>Defining, not initializing: defining is the step that reads the jar, and a static initializer run at boot instead
 * of at first use would be a behaviour change for every class here. Initialization, linking and lambda spinning need
 * nothing from the file once the class is defined.
 *
 * <p>{@code -Dforbric.bootJarPreload=off} leaves the jar to be read lazily, as before.
 */
public final class KernelJarPreload {
	public static final String SWITCH = "forbric.bootJarPreload";

	private KernelJarPreload() {
	}

	/** What a preload did: the classes it asked for, and the ones that could not be defined, with why. */
	public record Outcome(int requested, List<String> failed) {
		public int defined() {
			return requested - failed.size();
		}
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(SWITCH, "on"));
	}

	/**
	 * Defines every class in the jar {@code anchor} was loaded from, through {@code anchor}'s loader, and says so in one
	 * line. Does nothing when switched off or when the kernel runs from class directories (a test or IDE run, where
	 * there is no single file to lose).
	 */
	public static void run(Class<?> anchor) {
		if (!enabled()) {
			ForbricLog.info("[Forbric/Boot] kernel jar classes are read on first use (-D%s=off): a jar replaced or "
					+ "unreadable mid-session breaks the first use of a class after that", SWITCH);
			return;
		}
		Path jar = jarOf(anchor);
		if (jar == null) return;
		long start = System.nanoTime();
		Outcome outcome;
		try {
			outcome = define(classNames(jar), anchor.getClassLoader());
		} catch (IOException | RuntimeException unreadable) {
			Caught.warn(unreadable, "[Forbric/Boot] could not list the kernel jar's classes to define them up front — "
					+ "each is read on first use instead, so a jar replaced mid-session can break that use");
			return;
		}
		long millis = (System.nanoTime() - start) / 1_000_000L;
		ForbricLog.info("[Forbric/Boot] defined %d of the kernel jar's %d classes up front (%d ms), so a class first "
				+ "used mid-session no longer needs the jar to still be readable then (-D%s=off)",
				outcome.defined(), outcome.requested(), millis, SWITCH);
		if (!outcome.failed().isEmpty()) {
			ForbricLog.warn("[Forbric/Boot] %d kernel class(es) could not be defined up front and will be read on first "
					+ "use: %s", outcome.failed().size(), outcome.failed().subList(0, Math.min(5, outcome.failed().size())));
		}
	}

	/** The jar {@code anchor} was loaded from, or null when it came from a directory or from nowhere readable. */
	static Path jarOf(Class<?> anchor) {
		try {
			CodeSource source = anchor.getProtectionDomain().getCodeSource();
			if (source == null || source.getLocation() == null) return null;
			Path path = Path.of(source.getLocation().toURI());
			return Files.isRegularFile(path) ? path : null;
		} catch (URISyntaxException | RuntimeException unknown) {
			Caught.expected(unknown);
			return null;
		}
	}

	/**
	 * The binary name of every class the jar carries at its root. {@code META-INF/} is skipped — nested jars are
	 * extracted and loaded from their own files, and a multi-release copy is not the class a loader defines — and so are
	 * names a boot-side loader must never define: anything pinned game-side, and the Fabric Loader internals when they
	 * are withheld.
	 */
	static List<String> classNames(Path jar) throws IOException {
		List<String> names = new ArrayList<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements();) {
				String entry = entries.nextElement().getName();
				if (!entry.endsWith(".class") || entry.startsWith("META-INF/")) continue;
				String binary = entry.substring(0, entry.length() - ".class".length()).replace('/', '.');
				if (binary.equals("module-info") || binary.endsWith(".package-info")) continue;
				if (DelegationPolicy.alwaysGame(binary) || FabricLoaderInternals.withheld(binary)) continue;
				names.add(binary);
			}
		}
		return names;
	}

	/** Defines each of {@code names} in {@code loader} without initializing it; a class that cannot be defined is noted. */
	static Outcome define(List<String> names, ClassLoader loader) {
		List<String> failed = new ArrayList<>();
		for (String name : names) {
			try {
				Class.forName(name, false, loader);
			} catch (Throwable t) {
				Caught.expected(t);
				failed.add(name + " (" + t + ")");
			}
		}
		return new Outcome(names.size(), List.copyOf(failed));
	}
}
