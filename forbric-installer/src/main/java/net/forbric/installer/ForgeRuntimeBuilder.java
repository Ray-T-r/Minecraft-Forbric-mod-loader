package net.forbric.installer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Reproduces {@code run/assemble-minecraftforge-runtime.sh} in pure Java: download the Forge {@code -universal} jar
 * plus the FML/ModLauncher/eventbus runtime libs (from {@code config.libraries}, minus what already lives on the
 * MC + Forbric classpath), and merge them into ONE jar carrying a synthetic library {@code fabric.mod.json}
 * (id {@code "forge"}) so Knot loads every {@code net.minecraftforge.*} class in its transforming classloader.
 *
 * <p>Mixin is deliberately excluded (Forge ships {@code org.spongepowered:mixin:0.8.7}, which would collide with the
 * Fabric sponge-mixin fork already on the Knot classpath). {@code module-info}/signatures/other-jars'-mods.toml are
 * dropped; {@code META-INF/services/*} are concatenated; per-package manifest version sections are harvested so
 * FML's {@code JarVersionLookupHandler} still sees the language-provider versions.
 */
final class ForgeRuntimeBuilder {

	/** JetBrains annotations Forge bytecode references; not in {@code config.libraries} (fetched from Central). */
	private static final String ANNOTATIONS_COORDINATE = "org.jetbrains:annotations:24.1.0";

	private final ForgeArtifacts fa;
	private final Http http;
	private final Path dlDir;
	private final Path outJar;
	private final Consumer<String> log;

	ForgeRuntimeBuilder(ForgeArtifacts fa, Http http, Path workDir, Path outJar, Consumer<String> log) {
		this.fa = fa;
		this.http = http;
		this.dlDir = workDir.resolve("dl");
		this.outJar = outJar;
		this.log = log;
	}

	/** Build (or reuse) the merged runtime jar; returns its coordinate/path/sha1/size. */
	ArtifactResult build(ForgeArtifacts.UserdevConfig cfg) throws IOException {
		String coordinate = fa.runtimeCoordinate();
		if (Files.isRegularFile(outJar) && Files.size(outJar) > 0) {
			log.accept("[forge-runtime] up-to-date: " + outJar.getFileName());
			return new ArtifactResult(coordinate, outJar, Util.sha1(outJar), Files.size(outJar));
		}

		// 1) Resolve the merge inputs: the universal jar + config.libraries minus the classpath-provided set,
		//    plus the JetBrains annotations Forge's bytecode references (the assemble script's glob-merge swept
		//    these in via the dl/ cache; they are CLASS-retention and inert at runtime, but including them keeps
		//    the merged jar byte-for-byte a superset of the regression-validated reference).
		List<String> inputs = new ArrayList<>();
		inputs.add(cfg.universalCoordinate);
		for (String lib : cfg.libraries) {
			if (!isProvidedElsewhere(lib)) inputs.add(lib);
		}
		inputs.add(ANNOTATIONS_COORDINATE);

		// 2) Download every input (Forge Maven, then Central).
		log.accept("[forge-runtime] fetching " + inputs.size() + " Forge runtime artifacts …");
		List<Path> jars = new ArrayList<>();
		for (String coord : inputs) {
			String rel = Util.coordinateToPath(ForgeArtifacts.stripExtension(coord));
			Path dest = dlDir.resolve(rel.substring(rel.lastIndexOf('/') + 1)); // flat cache, like the script's dl/
			http.ensureWithFallback(fa.forgeUrl(coord), fa.centralUrl(coord), dest);
			jars.add(dest);
		}

		// 3) Merge.
		log.accept("[forge-runtime] merging universal + libs → " + outJar.getFileName());
		mergeInto(jars);

		String sha1 = Util.sha1(outJar);
		long size = Files.size(outJar);
		log.accept("[forge-runtime] wrote " + outJar.getFileName() + " (" + (size / (1024 * 1024)) + " MB)");
		return new ArtifactResult(coordinate, outJar, sha1, size);
	}

	/** The exclusion set derived from diffing config.libraries against the assemble script's curated GAV list. */
	private static boolean isProvidedElsewhere(String coordinate) {
		String[] p = coordinate.split(":");
		String group = p[0];
		String artifact = p.length > 1 ? p[1] : "";
		if (group.equals("org.ow2.asm")) return true;                 // asm — on the substrate classpath
		if (group.equals("org.jline")) return true;                   // jline — MC-provided terminal libs
		if (group.equals("org.spongepowered") && artifact.equals("mixin")) return true; // collides with sponge-mixin fork
		if (group.equals("io.github.llamalad7") && artifact.equals("mixinextras-forge")) return true; // JiJ'd already
		if (group.equals("com.google.guava") && artifact.equals("guava")) return true; // guava on classpath (keep failureaccess)
		return false;
	}

	// ---- the merge (ports the assemble script's python heredoc) ----

	private void mergeInto(List<Path> jars) throws IOException {
		// Sort by filename — the universal jar sorts in naturally and the class set across jars is disjoint.
		TreeMap<String, Path> ordered = new TreeMap<>();
		for (Path j : jars) ordered.put(j.getFileName().toString(), j);

		LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
		LinkedHashMap<String, byte[]> services = new LinkedHashMap<>();
		LinkedHashMap<String, String> pkgSections = new LinkedHashMap<>();

		for (Path jar : ordered.values()) {
			boolean isUniversal = jar.getFileName().toString().contains("-universal");
			byte[] mf = Zips.readEntry(jar, "META-INF/MANIFEST.MF");
			if (mf != null) harvestManifest(mf, pkgSections);
			for (var e : Zips.readAll(jar).entrySet()) {
				String n = e.getKey();
				if (skip(n, isUniversal)) continue;
				if (n.startsWith("META-INF/services/")) {
					byte[] prev = services.get(n);
					byte[] cur = e.getValue();
					byte[] merged = new byte[(prev == null ? 0 : prev.length) + cur.length + 1];
					int off = 0;
					if (prev != null) { System.arraycopy(prev, 0, merged, 0, prev.length); off = prev.length; }
					System.arraycopy(cur, 0, merged, off, cur.length);
					merged[off + cur.length] = '\n';
					services.put(n, merged);
				} else {
					files.put(n, e.getValue()); // last wins on the (disjoint) class set
				}
			}
		}

		log.accept("[forge-runtime] manifest carries " + pkgSections.size() + " per-package version sections");

		// Assemble the output in a stable order: manifest, fabric.mod.json, files, services.
		LinkedHashMap<String, byte[]> out = new LinkedHashMap<>();
		out.put("META-INF/MANIFEST.MF", buildManifest(pkgSections));
		out.put("fabric.mod.json", buildFabricModJson());
		for (var e : files.entrySet()) {
			if (e.getKey().equals("fabric.mod.json") || e.getKey().equals("META-INF/MANIFEST.MF")) continue;
			out.put(e.getKey(), e.getValue());
		}
		out.putAll(services);
		Zips.writeJar(outJar, out);
	}

	private static void harvestManifest(byte[] data, LinkedHashMap<String, String> pkgSections) {
		String text = new String(data, StandardCharsets.UTF_8).replace("\r\n", "\n");
		for (String rawBlock : text.split("\n\n")) {
			String block = strip(rawBlock, '\n');
			if (!block.startsWith("Name: ")) continue;
			String nameLine = block.split("\n", 2)[0];
			String name = nameLine.substring("Name: ".length());
			if (name.endsWith("/") && !pkgSections.containsKey(name)) {
				pkgSections.put(name, block);
			}
		}
	}

	private static boolean skip(String n, boolean isUniversal) {
		if (n.endsWith("/")) return true;
		if (n.equals("module-info.class") || n.endsWith("/module-info.class")) return true;
		if (n.equals("META-INF/MANIFEST.MF")) return true;
		if (n.equals("META-INF/mods.toml")) return !isUniversal; // keep only the universal jar's system-mod toml
		if (n.equals("META-INF/neoforge.mods.toml")) return true;
		if (n.startsWith("META-INF/jarjar/")) return true;
		if (Zips.isSignatureFile(n)) return true;
		return false;
	}

	private byte[] buildManifest(LinkedHashMap<String, String> pkgSections) {
		StringBuilder b = new StringBuilder();
		b.append("Manifest-Version: 1.0\r\n")
		 // the OFFICIAL universal module name FML hardcodes: layer.findModule("net.minecraftforge.forge")
		 .append("Automatic-Module-Name: net.minecraftforge.forge\r\n")
		 .append("Implementation-Title: MinecraftForge\r\n")
		 // FML version domain (65.0.1, not 26.2-65.0.1): language-provider loaderVersion ranges compare against it.
		 .append("Implementation-Version: ").append(fa.fmlVersion).append("\r\n")
		 .append("Implementation-Vendor: Forbric-assembled (runtime-supplied, not redistributed)\r\n")
		 .append("\r\n");
		for (String section : pkgSections.values()) {
			b.append(section.replace("\n", "\r\n")).append("\r\n").append("\r\n");
		}
		return b.toString().getBytes(StandardCharsets.UTF_8);
	}

	private byte[] buildFabricModJson() {
		// id "forge" so Knot discovers + loads this jar; version in the mc.fmlMajor domain (script pinned 26.2.65).
		String fmlMajor = fa.fmlVersion.contains(".") ? fa.fmlVersion.substring(0, fa.fmlVersion.indexOf('.')) : fa.fmlVersion;
		String version = fa.mcVersion + "." + fmlMajor;
		String json = "{\n"
				+ "  \"schemaVersion\": 1,\n"
				+ "  \"id\": \"forge\",\n"
				+ "  \"version\": \"" + version + "\",\n"
				+ "  \"name\": \"MinecraftForge runtime (via Forbric)\",\n"
				+ "  \"environment\": \"*\"\n"
				+ "}\n";
		return json.getBytes(StandardCharsets.UTF_8);
	}

	/** Trim only the given char from both ends (Python's {@code str.strip(ch)}). */
	private static String strip(String s, char ch) {
		int a = 0, b = s.length();
		while (a < b && s.charAt(a) == ch) a++;
		while (b > a && s.charAt(b - 1) == ch) b--;
		return s.substring(a, b);
	}
}
