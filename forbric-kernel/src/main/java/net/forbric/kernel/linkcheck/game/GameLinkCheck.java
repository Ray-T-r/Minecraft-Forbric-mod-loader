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

package net.forbric.kernel.linkcheck.game;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import net.forbric.kernel.linkcheck.LinkCheck;
import net.forbric.kernel.mixin.NativeGameReferences;
import net.forbric.kernel.mixin.NativeReferenceSources;
import net.forbric.kernel.transform.ForbricMergedBaseCompatTransformer;
import net.forbric.kernel.transform.VanillaMemberTwinInjector;

/**
 * The link check of the game a launch assembles, not only of the jars the installer stages: NeoForge's patched game and
 * its carrier as the kernel's first two COREMOD transformers hand them on, the kernel's game side, and the helper
 * classes the kernel generates at boot.
 *
 * <p><b>The view.</b> Every class of the base and the carrier passes through {@link VanillaMemberTwinInjector} and then
 * {@link ForbricMergedBaseCompatTransformer}, constructed as KernelBoot constructs them and in its order: the members those
 * two add at run time (the vanilla twins NeoForge's base lacks, such as the private {@code Hud.extractHotbarAndDecorations};
 * the compat repairs' bridges and resolvers) exist for this check as they do in the game, with the access they get
 * there. A reference into one of them is judged against that, not against the jar. The rest of the transformer chain and
 * Mixin are not applied: what they weave depends on the mods installed.
 *
 * <p><b>The game side.</b> The kernel's game-side jar ({@code forbric-kernel-runtime.jar}), which the game's loader
 * defines beside the base, including the Mod Menu API stand-in it offers as resources.
 *
 * <p><b>Generated helpers.</b> Classes a launch defined that no input jar holds: read from a definitions capture
 * ({@code -Dforbric.definedClassEvidence}, gate-m0 step 1b), as the bytes the loader defined. Every name the capture
 * holds that a scanned input, a library or a {@code --known} jar (the mods installed for the capture, the kernel's boot
 * jar) provides is not a helper.
 *
 * <p>What is judged is {@link LinkCheck}'s rule: references whose owner the base, the carrier, the game side or a helper
 * defines (or whose missing owner is in a package they define). Libraries resolve inheritance only.
 *
 * <p>Usage: {@code GameLinkCheck --base <jar> --carrier <jar> --sidecar <vanilla-reference jar> --game-side <jar|dir>...
 * [--lib <jar|dir>]... [--definitions <capture dir> [--known <jar|dir>]...]}. Exit codes as {@link LinkCheck}'s, and
 * 2 when the view is not the launch's (a twin did not land, a class could not be viewed).
 */
public final class GameLinkCheck {

	/**
	 * The inputs of one check.
	 *
	 * @param base        NeoForge's patched game
	 * @param carrier     the NeoForge runtime
	 * @param gameSide    the kernel's game-side jar(s) or class directories
	 * @param libraries   jars or directories read for inheritance only (the game's libraries)
	 * @param definitions a definitions capture directory (holding {@code definitions.tsv}), or null for no helpers
	 * @param known       jars or directories whose classes a capture may hold and which are not helpers
	 */
	public record Inputs(Path base, Path carrier, List<Path> gameSide, List<Path> libraries, Path definitions,
			List<Path> known) {
	}

	/**
	 * What one check found.
	 *
	 * @param result       the link check's result
	 * @param viewed       classes of the base and carrier that the two transformers changed
	 * @param twinsAdded   twins the view added
	 * @param twinRows     twins the injector has rows for
	 * @param helpers      generated helpers scanned, by name
	 * @param viewFailures classes the view could not produce, with the reason
	 */
	public record Outcome(LinkCheck.Result result, int viewed, int twinsAdded, int twinRows, List<String> helpers,
			List<String> viewFailures) {
		/** True when the view is the launch's: every twin landed and every class was viewed. */
		public boolean viewComplete() {
			return twinsAdded == twinRows && viewFailures.isEmpty();
		}

		public String viewLine() {
			return "[link-check] view: " + viewed + " class(es) of the base and carrier changed by the vanilla twins and the"
					+ " compat repairs; twins landed " + twinsAdded + " of " + twinRows + "; generated helpers scanned "
					+ helpers.size() + (viewFailures.isEmpty() ? "" : "; NOT VIEWED " + viewFailures.size());
		}
	}

	private GameLinkCheck() {
	}

	/**
	 * Runs the check.
	 *
	 * @param vanilla vanilla's class by internal name, read with expanded frames, for the twins' copied bodies (what the
	 *                launch's sidecar answers for FABRIC)
	 */
	public static Outcome check(Inputs in, Function<String, ClassNode> vanilla, LinkCheck.Options options) throws IOException {
		VanillaMemberTwinInjector twins = new VanillaMemberTwinInjector(vanilla);
		ForbricMergedBaseCompatTransformer compat = new ForbricMergedBaseCompatTransformer();
		LinkCheck check = new LinkCheck(options);
		Set<String> provided = new HashSet<>();
		List<String> failures = new ArrayList<>();
		int viewed = 0;
		for (Path jar : List.of(in.base(), in.carrier())) {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				for (ZipEntry entry : Collections.list(zip.entries())) {
					String name = entry.getName();
					if (!isClass(name)) continue;
					byte[] raw;
					try (InputStream stream = zip.getInputStream(entry)) {
						raw = stream.readAllBytes();
					}
					String internal = name.substring(0, name.length() - ".class".length());
					provided.add(internal);
					byte[] view;
					try {
						String dotted = internal.replace('/', '.');
						view = compat.transform(dotted, twins.transform(dotted, raw, null), null);
					} catch (RuntimeException | LinkageError unviewable) {
						failures.add(internal + ": " + unviewable);
						view = raw;
					}
					if (view != raw) viewed++;
					check.scanClass(view, true);
				}
			}
		}
		for (Path side : in.gameSide()) {
			check.scan(side);
			names(side, provided);
			for (byte[] standIn : modMenuStandIn(side)) check.scanClass(standIn, true);
		}
		for (Path library : in.libraries()) {
			check.library(library);
			names(library, provided);
		}
		List<String> helpers = new ArrayList<>();
		if (in.definitions() != null) {
			for (Path known : in.known()) names(known, provided);
			for (String[] row : definitions(in.definitions())) {
				if (provided.contains(row[0])) continue;
				Path blob = in.definitions().resolve("blobs").resolve(row[1] + ".class");
				if (!Files.isRegularFile(blob)) {
					failures.add(row[0] + ": the capture has no bytes for it (" + blob + ")");
					continue;
				}
				check.scanClass(Files.readAllBytes(blob), true);
				helpers.add(row[0]);
			}
		}
		return new Outcome(check.run(), viewed, twins.twinsAdded(), VanillaMemberTwinInjector.rowCount(),
				List.copyOf(helpers), List.copyOf(failures));
	}

	private static boolean isClass(String entry) {
		return entry.endsWith(".class") && !entry.startsWith("META-INF/") && !entry.endsWith("module-info.class");
	}

	/** The Mod Menu API stand-in the game-side jar offers as {@code META-INF/forbric/modmenu-api/**.class.bin}. */
	private static List<byte[]> modMenuStandIn(Path side) throws IOException {
		List<byte[]> out = new ArrayList<>();
		if (!Files.isRegularFile(side)) return out;
		try (ZipFile zip = new ZipFile(side.toFile())) {
			for (ZipEntry entry : Collections.list(zip.entries())) {
				if (!entry.getName().startsWith("META-INF/forbric/modmenu-api/") || !entry.getName().endsWith(".class.bin")) continue;
				try (InputStream stream = zip.getInputStream(entry)) {
					out.add(stream.readAllBytes());
				}
			}
		}
		return out;
	}

	/** Every class name a jar (nested jars and multi-release overlays included) or a directory provides. */
	static void names(Path input, Set<String> into) throws IOException {
		if (Files.isDirectory(input)) {
			List<Path> files;
			try (Stream<Path> walk = Files.walk(input)) {
				files = walk.filter(Files::isRegularFile).toList();
			}
			for (Path file : files) {
				String name = file.getFileName().toString();
				if (name.endsWith(".jar")) names(file, into);
				else if (name.endsWith(".class")) into.add(className(Files.readAllBytes(file)));
			}
			return;
		}
		try (InputStream stream = Files.newInputStream(input)) {
			names(new ZipInputStream(stream), into);
		}
	}

	private static void names(ZipInputStream zip, Set<String> into) throws IOException {
		for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
			String name = entry.getName();
			if (name.endsWith(".jar")) {
				names(new ZipInputStream(new java.io.ByteArrayInputStream(zip.readAllBytes())), into);
			} else if (name.endsWith(".class") && !name.endsWith("module-info.class")) {
				String path = name;
				if (path.startsWith("META-INF/versions/")) {
					int slash = path.indexOf('/', "META-INF/versions/".length());
					if (slash < 0) continue;
					path = path.substring(slash + 1);
				} else if (path.startsWith("META-INF/")) {
					continue;
				}
				into.add(path.substring(0, path.length() - ".class".length()));
			}
		}
	}

	private static String className(byte[] bytes) {
		return new ClassReader(bytes).getClassName();
	}

	/** The rows of a capture's {@code definitions.tsv}: internal name, blob hash. Refuses a capture that is not intact. */
	static List<String[]> definitions(Path capture) throws IOException {
		if (!Files.isRegularFile(capture.resolve("intact"))) {
			throw new IOException(capture + " is not an intact definitions capture (no 'intact' marker)");
		}
		List<String[]> rows = new ArrayList<>();
		for (String line : Files.readAllLines(capture.resolve("definitions.tsv"), StandardCharsets.UTF_8)) {
			if (line.isBlank() || line.startsWith("#")) continue;
			String[] row = line.split("\t");
			if (row.length != 2) throw new IOException("malformed definitions row in " + capture + ": " + line);
			rows.add(row);
		}
		return rows;
	}

	// --- command line ----------------------------------------------------------------------------------------------

	public static void main(String[] args) {
		System.exit(run(args));
	}

	/** The command line, returning the exit code. */
	public static int run(String[] args) {
		Path base = null, carrier = null, sidecar = null, definitions = null;
		List<Path> gameSide = new ArrayList<>(), libraries = new ArrayList<>(), known = new ArrayList<>();
		for (int i = 0; i < args.length; i++) {
			String option = args[i];
			if (++i >= args.length) return usage(option + " needs a value");
			Path value = Path.of(args[i]);
			switch (option) {
				case "--base" -> base = value;
				case "--carrier" -> carrier = value;
				case "--sidecar" -> sidecar = value;
				case "--game-side" -> gameSide.add(value);
				case "--lib" -> libraries.add(value);
				case "--definitions" -> definitions = value;
				case "--known" -> known.add(value);
				default -> {
					return usage("unknown option " + option);
				}
			}
		}
		if (base == null || carrier == null || sidecar == null || gameSide.isEmpty()) {
			return usage("--base, --carrier, --sidecar and --game-side are required");
		}
		for (Path p : Stream.of(List.of(base, carrier, sidecar), gameSide, libraries, known,
				definitions == null ? List.<Path>of() : List.of(definitions)).flatMap(List::stream).toList()) {
			if (!Files.exists(p)) return usage("no such input: " + p);
		}
		// The twins' vanilla bodies come from where a launch takes them: the sidecar, through the launch's own reader.
		NativeGameReferences.configure(NativeReferenceSources.open(base, sidecar));
		Outcome outcome;
		try (ZipFile baseJar = new ZipFile(base.toFile())) {
			Function<String, ClassNode> vanilla = VanillaMemberTwinInjector.vanillaReferences(path -> {
				ZipEntry entry = baseJar.getEntry(path);
				if (entry == null) return null;
				try (InputStream stream = baseJar.getInputStream(entry)) {
					return stream.readAllBytes();
				} catch (IOException unreadable) {
					return null;
				}
			});
			outcome = check(new Inputs(base, carrier, gameSide, libraries, definitions, known), vanilla, LinkCheck.Options.all());
		} catch (IOException unreadable) {
			System.err.println("[link-check] could not read the inputs: " + unreadable);
			return 2;
		}
		LinkCheck.Result result = outcome.result();
		for (LinkCheck.Violation v : result.violations()) System.out.println(v.line());
		for (String helper : outcome.helpers()) System.out.println("[link-check] generated helper: " + helper);
		for (String failure : outcome.viewFailures()) System.out.println("[link-check] NOT VIEWED " + failure);
		System.out.println(outcome.viewLine());
		System.out.println(result.breakdown());
		System.out.println(result.summary());
		if (!outcome.viewComplete()) {
			System.out.println("[link-check] the view is not the launch's: every twin must land and every class be viewed");
			return 2;
		}
		return result.violations().isEmpty() ? 0 : 1;
	}

	private static int usage(String why) {
		System.err.println("usage: GameLinkCheck --base <jar> --carrier <jar> --sidecar <jar> --game-side <jar|dir>..."
				+ " [--lib <jar|dir>]... [--definitions <capture> [--known <jar|dir>]...]");
		System.err.println("       (" + why + ")");
		return 2;
	}
}
