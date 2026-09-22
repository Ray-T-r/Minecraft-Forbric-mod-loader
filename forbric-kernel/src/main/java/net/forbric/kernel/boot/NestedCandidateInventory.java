/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.discovery.ForbricModDiscoverer;
import net.forbric.kernel.fabric.FabricModMetadataParser;

/** Complete physical candidate inventory, before either ecosystem filters or registers a root jar. */
public final class NestedCandidateInventory {
	public record Node(Path path, String digest, DuplicateModArbiter.Claim claim, boolean root, boolean excluded) { }
	public record Coordinate(String id, String range, String version) { }
	public record Edge(Path parent, Path child, String entry, Coordinate coordinate, boolean payload) { }
	public record Issue(Path source, String detail) { }

	private final Map<Path, Node> nodes;
	private final List<Edge> edges;
	private final List<Issue> issues;
	private final List<DuplicateModArbiter.Alias> universalAliases;

	private NestedCandidateInventory(Map<Path, Node> nodes, List<Edge> edges, List<Issue> issues,
			List<DuplicateModArbiter.Alias> aliases) {
		this.nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
		this.edges = List.copyOf(edges); this.issues = List.copyOf(issues); this.universalAliases = List.copyOf(aliases);
	}

	public Map<Path, Node> nodes() { return nodes; }
	public List<Edge> edges() { return edges; }
	public List<Issue> issues() { return issues; }
	List<DuplicateModArbiter.Alias> universalAliases() { return universalAliases; }
	List<DuplicateModArbiter.Claim> claims() {
		return nodes.values().stream().filter(n -> !n.excluded()).map(n -> n.claim() != null ? n.claim()
				: new DuplicateModArbiter.Claim(n.path(), null, List.of())).toList();
	}

	public static NestedCandidateInventory scan(List<DuplicateModArbiter.Claim> roots, Path cache, EnvType side) {
		return new Builder(cache, side).scan(roots);
	}

	/** The physical classes in an anonymous library belong to the reachable mod(s) that bundle it. */
	Map<Path, Set<String>> symbolOwners() {
		Map<Path, Set<String>> owners = new LinkedHashMap<>();
		for (Node node : nodes.values()) owners.put(node.path(), new LinkedHashSet<>(node.claim() == null ? List.of() : node.claim().modIds()));
		for (int round = 0; round < nodes.size(); round++) {
			boolean changed = false;
			for (Edge edge : edges) if (nodes.get(edge.child()).claim() == null) changed |= owners.get(edge.child()).addAll(owners.get(edge.parent()));
			if (!changed) break;
		}
		return owners;
	}

	boolean payloadRelated(Path first, Path second) {
		return payloadDescendant(first, second, new HashSet<>()) || payloadDescendant(second, first, new HashSet<>());
	}
	private boolean payloadDescendant(Path parent, Path child, Set<Path> seen) {
		if (!seen.add(parent)) return false;
		for (Edge edge : edges) if (edge.payload() && edge.parent().equals(parent)) {
			if (edge.child().equals(child) || payloadDescendant(edge.child(), child, seen)) return true;
		}
		return false;
	}

	static String digest(Path path) throws IOException {
		try {
			MessageDigest sha = MessageDigest.getInstance("SHA-256");
			try (InputStream in = Files.newInputStream(path)) { byte[] bytes = new byte[65536]; for (int n; (n = in.read(bytes)) >= 0;) sha.update(bytes, 0, n); }
			return HexFormat.of().formatHex(sha.digest());
		} catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
	}

	private static final class Builder {
		private final Path cache;
		private final EnvType side;
		private final Map<Path, Node> nodes = new LinkedHashMap<>();
		private final Map<String, Path> content = new LinkedHashMap<>();
		private final List<Edge> edges = new ArrayList<>();
		private final List<Issue> issues = new ArrayList<>();
		private final List<DuplicateModArbiter.Alias> aliases = new ArrayList<>();
		private final Set<String> visited = new HashSet<>();
		private final ForbricModDiscoverer discoverer = new ForbricModDiscoverer();
		private long extractedBytes;
		private record Work(Path path, int depth, boolean forgeWalk) { }

		Builder(Path cache, EnvType side) { this.cache = cache.toAbsolutePath().normalize(); this.side = side; }
		NestedCandidateInventory scan(List<DuplicateModArbiter.Claim> roots) {
			Deque<Work> work = new ArrayDeque<>();
			for (var root : roots) {
				Path path = JointCandidateSelector.path(root);
				try {
					String digest = digest(path);
					nodes.put(path, new Node(path, digest, root, true, false));
					// Root copies remain separate choices. A nested occurrence of identical bytes can reuse a root.
					content.putIfAbsent(digest, path);
					work.add(new Work(path, 0, root.ecosystem().isForgeFamily()));
				} catch (IOException unreadable) {
					nodes.put(path, new Node(path, "unreadable", root, true, false));
					issues.add(new Issue(path, "root could not be fingerprinted: " + unreadable));
				}
			}
			while (!work.isEmpty()) {
				Work parent = work.remove();
				if (!visited.add(parent.path() + ":" + parent.forgeWalk())) continue;
				if (parent.depth() >= 8 || nodes.size() > 1024) { issues.add(new Issue(parent.path(), "nested candidate scan reached its depth/size bound")); continue; }
				try (ZipFile zip = new ZipFile(parent.path().toFile())) {
					Set<String> entries = new TreeSet<>();
					ZipEntry fabric = zip.getEntry("fabric.mod.json");
					if (fabric != null) try (InputStream in = zip.getInputStream(fabric)) {
						var metadata = FabricModMetadataParser.read(in);
						if (side == null || metadata.getEnvironment().matches(side)) entries.addAll(metadata.getNestedJars());
					}
					if (parent.forgeWalk()) for (ZipEntry entry : zip.stream().toList()) {
						String name = entry.getName();
						if (name.endsWith(".jar") && (name.startsWith("META-INF/jars/") || name.startsWith("META-INF/jarjar/"))) entries.add(name);
					}
					Map<String, Coordinate> coordinates = coordinates(zip);
					// JarJar metadata is itself a declaration, including for a Fabric-owned universal parent and
					// for a path outside the conventional META-INF directories.
					entries.addAll(coordinates.keySet());
					for (String entryName : entries) {
						try {
							ZipEntry entry = zip.getEntry(entryName);
							if (entry == null || entry.isDirectory()) throw new IOException("declared nested jar is absent");
							byte[] bytes; try (InputStream in = zip.getInputStream(entry)) { bytes = in.readNBytes(64 * 1024 * 1024 + 1); }
							if (bytes.length > 64 * 1024 * 1024 || (extractedBytes += bytes.length) > 2L * 1024 * 1024 * 1024) throw new IOException("nested archive scan byte bound");
							String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
							Path child = content.get(hash);
							if (child == null) {
								child = materialize(hash, entryName, bytes);
								var claim = DuplicateModArbiter.claimOf(discoverer, child, side, aliases);
								boolean excluded = claim == null && excludedBySide(child);
								if (claim == null && !excluded && MultiLoaderArbiter.ownerOf(child) != null) issues.add(new Issue(child, "nested mod identity was unreadable; retaining its physical library without claiming a valid mod"));
								nodes.put(child, new Node(child, hash, claim, false, excluded)); content.put(hash, child);
							}
							Node parentNode = nodes.get(parent.path()), childNode = nodes.get(child);
							boolean payload = sameIdentityPayload(parentNode.claim(), childNode.claim());
							Edge edge = new Edge(parent.path(), child, entryName, coordinates.get(entryName), payload);
							if (!edges.contains(edge)) edges.add(edge);
							if (!childNode.excluded()) work.add(new Work(child, parent.depth() + 1,
									parent.forgeWalk() || (childNode.claim() != null && childNode.claim().ecosystem().isForgeFamily())));
						} catch (Exception failure) { issues.add(new Issue(parent.path(), entryName + ": " + failure.getMessage())); }
					}
				} catch (Exception failure) { issues.add(new Issue(parent.path(), "nested metadata could not be read: " + failure)); }
			}
			return new NestedCandidateInventory(nodes, edges, issues, aliases);
		}

		private Path materialize(String hash, String entry, byte[] bytes) throws IOException {
			String name = entry.substring(entry.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._+() -]", "_");
			name = name.replaceAll("[. ]+$", "_");
			if (name.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?")) name = "_" + name;
			if (name.isBlank() || name.equals(".") || name.equals("..")) name = "nested.jar";
			Path directory = cache.resolve(hash); Files.createDirectories(directory);
			Path target = directory.resolve(name).toAbsolutePath().normalize();
			if (Files.isRegularFile(target) && hash.equals(digest(target))) {
				try (ZipFile ignored = new ZipFile(target.toFile())) { }
				return target;
			}
			Path temporary = Files.createTempFile(directory, ".candidate-", ".jar");
			try {
				Files.write(temporary, bytes);
				try (ZipFile ignored = new ZipFile(temporary.toFile())) { }
				try {
					try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
					catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
				} catch (IOException concurrentOrLocked) {
					if (!Files.isRegularFile(target) || !hash.equals(digest(target))) throw concurrentOrLocked;
				}
				if (!hash.equals(digest(target))) throw new IOException("nested candidate changed while extracting: " + target);
			} finally { Files.deleteIfExists(temporary); }
			return target;
		}

		private boolean excludedBySide(Path child) {
			if (side == null || MultiLoaderArbiter.ownerOf(child) != Ecosystem.FABRIC) return false;
			try (ZipFile zip = new ZipFile(child.toFile())) {
				ZipEntry manifest = zip.getEntry("fabric.mod.json"); if (manifest == null) return false;
				try (InputStream in = zip.getInputStream(manifest)) { return !FabricModMetadataParser.read(in).getEnvironment().matches(side); }
			} catch (Exception unreadable) { return false; }
		}

		private static boolean sameIdentityPayload(DuplicateModArbiter.Claim parent, DuplicateModArbiter.Claim child) {
			if (parent == null || child == null || parent.ecosystem() != child.ecosystem() || child.modIds().isEmpty()) return false;
			Set<String> parentIds = new HashSet<>(); for (String id : parent.modIds()) parentIds.add(JointCandidateSelector.key(id));
			return child.modIds().stream().map(JointCandidateSelector::key).allMatch(parentIds::contains);
		}

		private static Map<String, Coordinate> coordinates(ZipFile zip) throws IOException {
			ZipEntry entry = zip.getEntry("META-INF/jarjar/metadata.json"); if (entry == null) return Map.of();
			Map<String, Coordinate> result = new LinkedHashMap<>();
			try (Reader in = new InputStreamReader(zip.getInputStream(entry), java.nio.charset.StandardCharsets.UTF_8)) {
				var metadata = com.electronwill.nightconfig.json.JsonFormat.fancyInstance().createParser().parse(in);
				List<? extends com.electronwill.nightconfig.core.UnmodifiableConfig> children = metadata.getOrElse("jars", List.of());
				for (var child : children) {
					com.electronwill.nightconfig.core.UnmodifiableConfig id = child.get("identifier"), version = child.get("version");
					String path = child.get("path"); if (path == null || id == null || version == null) continue;
					String group = id.getOrElse("group", ""), artifact = id.getOrElse("artifact", "");
					if (artifact.isBlank()) continue;
					result.put(path, new Coordinate(group + ":" + artifact, version.getOrElse("range", "*"), version.getOrElse("artifactVersion", "")));
				}
			}
			return result;
		}
	}
}
