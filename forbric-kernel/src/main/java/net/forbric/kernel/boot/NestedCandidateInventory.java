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
import net.forbric.kernel.fabric.FabricUniqueIds;
import net.forbric.kernel.fabric.KernelModMetadata;
import net.forbric.kernel.fabric.KernelVersion;
import net.forbric.kernel.fabric.NestedFabricRequirements;
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;

/** Complete physical candidate inventory, before either ecosystem filters or registers a root jar. */
public final class NestedCandidateInventory {
	public record Node(Path path, String digest, DuplicateModArbiter.Claim claim, boolean root, boolean excluded) { }
	public record Coordinate(String id, String range, String version) { }
	public record Edge(Path parent, Path child, String entry, Coordinate coordinate, boolean payload) { }
	/** {@code bound}: the scan stopped here, so this parent's nested jars were NOT all examined. */
	public record Issue(Path source, String detail, boolean bound) {
		public Issue(Path source, String detail) { this(source, detail, false); }
	}
	/** Zip-bomb guards only; a real pack's nested archives total well under 1 GB. There is no archive-count cap. */
	static final int MAX_DEPTH = 8;
	static final long ENTRY_BYTES = 1L << 30, TOTAL_BYTES = 16L << 30;
	private static final String MIXINEXTRAS = "mixinextras";

	private final Map<Path, Node> nodes;
	private final List<Edge> edges;
	private final List<Issue> issues;
	private final List<DuplicateModArbiter.Alias> universalAliases;
	private final Map<Path, String> leftOut;
	private final Map<Path, NestedFabricRequirements.KeptBack> keptBack;
	/** Reached by something other than a Fabric mod's {@code jars} (see Builder): never Fabric Loader's to judge. */
	private final Set<Path> reachedOutsideFabric;
	private final Map<Path, KernelModMetadata> fabricManifests;
	private final EnvType side;

	private NestedCandidateInventory(Map<Path, Node> nodes, List<Edge> edges, List<Issue> issues,
			List<DuplicateModArbiter.Alias> aliases, Map<Path, String> leftOut,
			Map<Path, NestedFabricRequirements.KeptBack> keptBack, Set<Path> reachedOutsideFabric,
			Map<Path, KernelModMetadata> fabricManifests, EnvType side) {
		this.nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
		this.edges = List.copyOf(edges); this.issues = List.copyOf(issues); this.universalAliases = List.copyOf(aliases);
		this.leftOut = Collections.unmodifiableMap(new LinkedHashMap<>(leftOut));
		this.keptBack = Collections.unmodifiableMap(new LinkedHashMap<>(keptBack));
		this.reachedOutsideFabric = Set.copyOf(reachedOutsideFabric);
		this.fabricManifests = new HashMap<>(fabricManifests);
		this.side = side;
	}

	public Map<Path, Node> nodes() { return nodes; }
	public List<Edge> edges() { return edges; }
	public List<Issue> issues() { return issues; }
	/** Nested Fabric mods excluded because Fabric Loader could not load them here, with why (see NestedFabricRequirements). */
	public Map<Path, String> leftOut() { return leftOut; }
	/** Nested Fabric mods Fabric Loader would leave out but that stay, because a mod that loads needs them. */
	public Map<Path, NestedFabricRequirements.KeptBack> keptBack() { return keptBack; }
	List<DuplicateModArbiter.Alias> universalAliases() { return universalAliases; }

	/** This inventory with {@code out} excluded as well, as if the scan had left them out: arbitration can no longer pick them. */
	NestedCandidateInventory withExcluded(Set<Path> out) {
		Map<Path, Node> marked = new LinkedHashMap<>(nodes);
		for (Path path : out) {
			Node node = marked.get(path);
			if (node != null && !node.excluded()) marked.put(path, new Node(node.path(), node.digest(), node.claim(), node.root(), true));
		}
		return new NestedCandidateInventory(marked, edges, issues, universalAliases, leftOut, keptBack, reachedOutsideFabric,
				fabricManifests, side);
	}

	/**
	 * Fabric Loader's one mod per id, {@code provides} included, among {@code selected}, the jars cross-jar arbitration
	 * chose ({@link FabricUniqueIds}): which nested Fabric mods it leaves out, with what then cannot load either, which
	 * of them lost a rivalry themselves, and which would-be losers a mod keeps back. Logs nothing:
	 * {@link #logOneModPerId} does, once arbitration is done with every round.
	 */
	FabricUniqueIds.Outcome<Path> oneModPerId(Set<Path> selected) {
		return oneModPerId(selected, Set.of());
	}

	/** As above, with {@code outvoted}: the Fabric mods earlier rounds left out for losing a rivalry. */
	FabricUniqueIds.Outcome<Path> oneModPerId(Set<Path> selected, Set<Path> outvoted) {
		Map<Path, List<Path>> parentsOf = new HashMap<>();
		for (Edge edge : edges) {
			if (!selected.contains(edge.parent()) || !selected.contains(edge.child())) continue;
			List<Path> parents = parentsOf.computeIfAbsent(edge.child(), ignored -> new ArrayList<>());
			if (!parents.contains(edge.parent())) parents.add(edge.parent());
		}
		// Only Fabric mods can contest an id, and their candidates need no Forge-family manifest read.
		List<NestedFabricRequirements.Candidate<Path>> fabric = new ArrayList<>();
		for (Node node : nodes.values()) {
			if (!selected.contains(node.path()) || node.claim() == null || node.claim().ecosystem() != Ecosystem.FABRIC) continue;
			fabric.add(candidate(node, parentsOf.getOrDefault(node.path(), List.of()), judged(node), manifestOf(node.path()), null, null));
		}
		if (!FabricUniqueIds.contested(fabric) && outvoted.isEmpty()) return new FabricUniqueIds.Outcome<>(Map.of(), Map.of(), Map.of());
		net.forbric.api.Side physical = physical(side);
		ForbricModDiscoverer discoverer = new ForbricModDiscoverer();
		List<NestedFabricRequirements.Candidate<Path>> loading = new ArrayList<>();
		for (Node node : nodes.values()) {
			if (!selected.contains(node.path())) continue;
			boolean isFabric = node.claim() != null && node.claim().ecosystem() == Ecosystem.FABRIC;
			loading.add(candidate(node, parentsOf.getOrDefault(node.path(), List.of()), judged(node),
					isFabric ? manifestOf(node.path()) : null, discoverer, physical));
		}
		List<NestedFabricRequirements.Candidate<Path>> out = new ArrayList<>();
		for (Path path : outvoted) {
			Node node = nodes.get(path);
			if (node != null && node.claim() != null && node.claim().ecosystem() == Ecosystem.FABRIC) {
				out.add(candidate(node, List.of(), judged(node), manifestOf(path), null, null));
			}
		}
		return FabricUniqueIds.resolve(loading, List.of(), out);
	}

	/**
	 * The Fabric mods to exclude before arbitration runs again, with why: each that lost a rivalry itself (not what only
	 * went with one), and, where a jar in {@code mods/} won, every other build of the loser's id that Fabric Loader would
	 * reach and that answers to the same id, which loses the same way whichever build arbitration picks next. Builds
	 * {@code excluded} already are not repeated.
	 */
	Map<Path, String> outvoted(FabricUniqueIds.Outcome<Path> outcome, Set<Path> excluded) {
		Map<Path, String> out = new LinkedHashMap<>();
		for (var entry : outcome.lost().entrySet()) {
			Path loser = entry.getKey();
			out.put(loser, outcome.leftOut().get(loser));
			KernelModMetadata manifest = manifestOf(loser);
			if (!entry.getValue().toRoot() || manifest == null) continue;
			String lostId = entry.getValue().id();
			for (Node other : nodes.values()) {
				if (other.excluded() || other.path().equals(loser) || excluded.contains(other.path()) || !judged(other)
						|| other.claim() == null || other.claim().ecosystem() != Ecosystem.FABRIC) continue;
				KernelModMetadata build = manifestOf(other.path());
				if (build == null || !manifest.getId().equals(build.getId())) continue;
				if (build.getId().equals(lostId) || build.getProvides().contains(lostId)) out.putIfAbsent(other.path(), out.get(loser));
			}
		}
		return out;
	}

	/**
	 * Whether this round left out a jar (a loser, or what went with one) whose declared id another build could still
	 * take: one that is neither selected, nor left out, nor {@code excluded}. Only then can another arbitration round
	 * change what loads.
	 */
	boolean anotherBuildOf(Set<Path> leftOut, Set<Path> excluded, Set<Path> selected) {
		Set<String> ids = new HashSet<>();
		for (Path path : leftOut) {
			Node node = nodes.get(path);
			if (node != null && node.claim() != null) for (String id : node.claim().modIds()) ids.add(JointCandidateSelector.key(id));
		}
		for (Node node : nodes.values()) {
			if (node.excluded() || excluded.contains(node.path()) || leftOut.contains(node.path()) || selected.contains(node.path())
					|| node.claim() == null) continue;
			for (String id : node.claim().modIds()) if (ids.contains(JointCandidateSelector.key(id))) return true;
		}
		return false;
	}

	/** One line for each jar one mod per id leaves out and each it keeps back, as the platform rule gives its own. */
	void logOneModPerId(Map<Path, String> leftOut, Map<Path, NestedFabricRequirements.KeptBack> keptBack) {
		Map<Path, List<Path>> parentsOf = new HashMap<>();
		for (Edge edge : edges) parentsOf.computeIfAbsent(edge.child(), ignored -> new ArrayList<>()).add(edge.parent());
		for (var entry : leftOut.entrySet()) {
			KernelModMetadata manifest = manifestOf(entry.getKey());
			Path parent = firstParent(entry.getKey(), parentsOf);
			// A library or another ecosystem's jar that only a left-out mod bundles goes with it; it has no Fabric id.
			if (manifest != null) NestedFabricRequirements.log(manifest, parent, entry.getValue());
			else ForbricLog.info("[Forbric/JiJ] nested %s in %s left out: %s", entry.getKey().getFileName(), parent, entry.getValue());
		}
		for (var entry : keptBack.entrySet()) {
			NestedFabricRequirements.logKeptBack(manifestOf(entry.getKey()), firstParent(entry.getKey(), parentsOf), entry.getValue());
		}
	}

	/** Only what Fabric Loader alone would have reached is its to leave out. */
	private boolean judged(Node node) {
		return !node.root() && !reachedOutsideFabric.contains(node.path());
	}

	private static net.forbric.api.Side physical(EnvType side) {
		return side == null ? null : side == EnvType.SERVER ? net.forbric.api.Side.DEDICATED_SERVER : net.forbric.api.Side.CLIENT;
	}

	/**
	 * {@code node} as Fabric's rule weighs it: the ids it answers to (declared, and a Fabric mod's {@code provides} from
	 * {@code fabric}, its manifest), whether the rule may leave it out, and its hard requirements; a Forge-family
	 * node's only with a {@code discoverer} to read them.
	 */
	private static NestedFabricRequirements.Candidate<Path> candidate(Node node, List<Path> parents, boolean judged,
			KernelModMetadata fabric, ForbricModDiscoverer discoverer, net.forbric.api.Side physical) {
		Map<String, String> provides = new HashMap<>();
		KernelModMetadata manifest = null;
		List<NestedFabricRequirements.Requirement> requires = new ArrayList<>();
		String id = node.path().getFileName().toString();
		if (node.claim() != null) {
			id = node.claim().modIds().isEmpty() ? id : node.claim().modIds().getFirst();
			for (String claimed : node.claim().modIds()) provides.put(JointCandidateSelector.key(claimed), node.claim().versionOf(claimed));
			if (node.claim().ecosystem() == Ecosystem.FABRIC) {
				manifest = fabric;
				if (manifest != null && manifest.getVersion() != null) {
					for (String alias : manifest.getProvides()) provides.putIfAbsent(JointCandidateSelector.key(alias), manifest.getVersion().getFriendlyString());
				}
				requires.addAll(NestedFabricRequirements.requirementsOf(manifest));
			} else if (discoverer != null) {
				try {
					for (var mod : discoverer.discoverJar(node.path())) {
						if (mod.getEcosystem() == node.claim().ecosystem()) requires.addAll(NestedFabricRequirements.requirementsOf(mod, physical));
					}
				} catch (Exception unreadable) {
					// its claim was read; an unreadable manifest brings no requirement
					Caught.warn(unreadable, "[Forbric/JiJ] could not re-read the manifest of %s; it brings no requirement", node.path());
				}
			}
		}
		return new NestedFabricRequirements.Candidate<>(node.path(), id, node.root(), judged, provides, parents, manifest, requires);
	}

	private KernelModMetadata manifestOf(Path jar) {
		return fabricManifests.computeIfAbsent(jar, NestedCandidateInventory::readFabricManifest);
	}

	private static Path firstParent(Path child, Map<Path, List<Path>> parentsOf) {
		return parentsOf.getOrDefault(child, List.of()).stream().findFirst().orElse(child).getFileName();
	}

	private static KernelModMetadata readFabricManifest(Path jar) {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry manifest = zip.getEntry("fabric.mod.json");
			if (manifest == null) return null;
			try (InputStream in = zip.getInputStream(manifest)) {
				return FabricModMetadataParser.read(in);
			}
		} catch (Exception unreadable) {
			Caught.warn(unreadable, "[Forbric/JiJ] could not read the fabric.mod.json of %s; it is taken to have none", jar);
			return null;
		}
	}
	List<DuplicateModArbiter.Claim> claims() {
		return nodes.values().stream().filter(n -> !n.excluded()).map(n -> n.claim() != null ? n.claim()
				: new DuplicateModArbiter.Claim(n.path(), null, List.of())).toList();
	}

	public static NestedCandidateInventory scan(List<DuplicateModArbiter.Claim> roots, Path cache, EnvType side) {
		return scan(roots, cache, side, NestedFabricRequirements.Platform.running(null));
	}

	/** {@code platform}: what a nested Fabric mod's {@code minecraft}/{@code java} requirement is held against. */
	public static NestedCandidateInventory scan(List<DuplicateModArbiter.Claim> roots, Path cache, EnvType side,
			NestedFabricRequirements.Platform platform) {
		return new Builder(cache, side, platform).scan(roots);
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

	/**
	 * A nested Forge-family MixinExtras no newer than the kernel's own. MinecraftForge ships mixinextras-forge
	 * 0.5.3 and its JarJar selection puts that copy beside every nested one and keeps the highest, so
	 * badpackets-forge's 0.3.5 is closed unopened. Here it used to become a mod: its {@code MixinExtrasMod}
	 * constructor calls {@code ModList.get()}, gone from MinecraftForge 26.2, and the NoSuchMethodError stopped a
	 * STRICT server for anyone with WTHIT-Forge. The kernel's MixinExtras is the one every mod's mixins run on
	 * anyway (it sits ahead of guest jars), so a nested Forge-platform copy brings only that constructor.
	 */
	static boolean supersededByKernelMixinExtras(DuplicateModArbiter.Claim claim, String kernelVersion) {
		if (kernelVersion == null || claim == null || claim.ecosystem() == null || !claim.ecosystem().isForgeFamily()
				|| !claim.modIds().equals(List.of(MIXINEXTRAS))) return false;
		String nested = claim.versions().get(MIXINEXTRAS);
		if (nested == null) return false;
		try {
			return KernelVersion.parse(nested).compareTo(KernelVersion.parse(kernelVersion)) <= 0;
		} catch (Exception unparseable) {
			Caught.expected(unparseable);
			return false;
		}
	}

	static String digest(Path path) throws IOException {
		try {
			MessageDigest sha = MessageDigest.getInstance("SHA-256");
			try (InputStream in = Files.newInputStream(path)) { byte[] bytes = new byte[65536]; for (int n; (n = in.read(bytes)) >= 0;) sha.update(bytes, 0, n); }
			return HexFormat.of().formatHex(sha.digest());
		} catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
	}

	private static final class BoundReached extends IOException {
		BoundReached(String detail) { super(detail); }
	}

	private static final class Builder {
		private final Path cache;
		private final EnvType side;
		private final NestedFabricRequirements.Platform running;
		private final Map<Path, Node> nodes = new LinkedHashMap<>();
		private final Map<Path, KernelModMetadata> fabricManifests = new HashMap<>();
		private final Map<Path, String> leftOut = new LinkedHashMap<>();
		/** The parent whose {@code jars} declaration a directly left-out jar was judged under. */
		private final Map<Path, Path> leftOutParent = new HashMap<>();
		/**
		 * Reached by something other than a Fabric mod's {@code jars}: a child a JarJar {@code metadata.json} declares,
		 * which FML loads as its parent's library, or a jar that only sits in a Forge-family parent's
		 * {@code META-INF/jars/} or {@code META-INF/jarjar/}, which no native loader loads and the kernel's walk has
		 * always taken. Fabric Loader would never see either, so Fabric's rule never leaves it out.
		 */
		private final Set<Path> reachedOutsideFabric = new HashSet<>();
		private final Map<String, Path> content = new LinkedHashMap<>();
		private final List<Edge> edges = new ArrayList<>();
		private final List<Issue> issues = new ArrayList<>();
		private final List<DuplicateModArbiter.Alias> aliases = new ArrayList<>();
		private final Set<String> visited = new HashSet<>();
		private final ForbricModDiscoverer discoverer = new ForbricModDiscoverer();
		private long extractedBytes;
		private record Work(Path path, int depth, boolean forgeWalk) { }

		Builder(Path cache, EnvType side, NestedFabricRequirements.Platform platform) {
			this.cache = cache.toAbsolutePath().normalize(); this.side = side; this.running = platform;
		}
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
					Caught.warn(unreadable, "[Forbric/JiJ] could not fingerprint %s; it stays a separate choice of unknown content", path);
					nodes.put(path, new Node(path, "unreadable", root, true, false));
					issues.add(new Issue(path, "root could not be fingerprinted: " + unreadable));
				}
			}
			while (!work.isEmpty()) {
				Work parent = work.remove();
				if (!visited.add(parent.path() + ":" + parent.forgeWalk())) continue;
				// No cap on how many archives an instance has: a kitchen-sink pack passes a thousand, and every parent
				// left unopened would silently lose its libraries, because both discoveries read only this plan.
				if (parent.depth() >= MAX_DEPTH) { issues.add(new Issue(parent.path(), "nested candidate scan reached its depth bound", true)); continue; }
				try (ZipFile zip = new ZipFile(parent.path().toFile())) {
					Set<String> entries = new TreeSet<>();
					// What Fabric Loader itself would open in this jar: the jars of a mod loaded as a Fabric mod.
					Set<String> fabricJars = Set.of();
					ZipEntry fabric = zip.getEntry("fabric.mod.json");
					if (fabric != null) try (InputStream in = zip.getInputStream(fabric)) {
						var metadata = FabricModMetadataParser.read(in);
						fabricManifests.put(parent.path(), metadata);
						if (side == null || metadata.getEnvironment().matches(side)) {
							entries.addAll(metadata.getNestedJars());
							DuplicateModArbiter.Claim owner = nodes.get(parent.path()).claim();
							if (owner != null && owner.ecosystem() == Ecosystem.FABRIC) fabricJars = Set.copyOf(metadata.getNestedJars());
						}
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
							boolean fabricRoute = fabricJars.contains(entryName);
							String hash = hash(zip, entry);
							Path child = content.get(hash);
							if (child == null) {
								child = materialize(hash, entryName, zip, entry);
								var claim = DuplicateModArbiter.claimOf(discoverer, child, side, aliases);
								boolean excluded = claim == null && excludedBySide(child);
								String platform = KernelBundledJars.mixinExtrasVersion();
								if (!excluded && supersededByKernelMixinExtras(claim, platform)) {
									excluded = true;
									ForbricLog.info("[Forbric/JiJ] %s nests MixinExtras %s (%s); the kernel's own %s supersedes it, "
											+ "as MinecraftForge's JarJar selection drops a nested copy older than the one it ships",
											parent.path().getFileName(), claim.versions().get(MIXINEXTRAS), child.getFileName(), platform);
								}
								// Fabric Loader never loads a nested mod whose minecraft/java requirement excludes this
								// game, so its parent's other builds are the only ones that may run (ViaFabric's mc26-1).
								// Only a Fabric mod's jars declaration is Fabric Loader's to judge; see admitOutsideFabric.
								if (!excluded && fabricRoute && claim != null && claim.ecosystem() == Ecosystem.FABRIC) {
									String unmet = NestedFabricRequirements.unmet(fabricManifest(child), running);
									if (unmet != null) { excluded = true; leftOut.put(child, unmet); leftOutParent.put(child, parent.path()); }
								}
								if (claim == null && !excluded && MultiLoaderArbiter.ownerOf(child) != null) issues.add(new Issue(child, "nested mod identity was unreadable; retaining its physical library without claiming a valid mod"));
								nodes.put(child, new Node(child, hash, claim, false, excluded)); content.put(hash, child);
							}
							if (!fabricRoute) admitOutsideFabric(child);
							Node parentNode = nodes.get(parent.path()), childNode = nodes.get(child);
							boolean payload = sameIdentityPayload(parentNode.claim(), childNode.claim());
							Edge edge = new Edge(parent.path(), child, entryName, coordinates.get(entryName), payload);
							if (!edges.contains(edge)) edges.add(edge);
							// A left-out jar is opened too: a mod that loads may still need what it bundles (resolve).
							if (!childNode.excluded() || leftOut.containsKey(child)) work.add(new Work(child, parent.depth() + 1,
									parent.forgeWalk() || (childNode.claim() != null && childNode.claim().ecosystem().isForgeFamily())));
						} catch (BoundReached bound) {
							Caught.expected(bound);
							issues.add(new Issue(parent.path(), entryName + ": " + bound.getMessage(), true));
						} catch (Exception failure) {
							Caught.warn(failure, "[Forbric/JiJ] could not examine %s nested in %s", entryName, parent.path());
							issues.add(new Issue(parent.path(), entryName + ": " + failure.getMessage(), false));
						}
					}
				} catch (Exception failure) {
					Caught.warn(failure, "[Forbric/JiJ] could not read the nested metadata of %s", parent.path());
					issues.add(new Issue(parent.path(), "nested metadata could not be read: " + failure));
				}
			}
			Map<Path, NestedFabricRequirements.KeptBack> keptBack = resolveLeftOut();
			return new NestedCandidateInventory(nodes, edges, issues, aliases, leftOut, keptBack, reachedOutsideFabric,
					fabricManifests, side);
		}

		/**
		 * A jar reached other than through a Fabric mod's {@code jars} is never left out by Fabric's rule, whichever
		 * route the walk took first. If it was left out under a Fabric parent, it comes back in.
		 */
		private void admitOutsideFabric(Path child) {
			if (!reachedOutsideFabric.add(child) || leftOut.remove(child) == null) return;
			leftOutParent.remove(child);
			Node node = nodes.get(child);
			nodes.put(child, new Node(node.path(), node.digest(), node.claim(), node.root(), false));
		}

		/**
		 * The rest of Fabric Loader's rule, once the whole graph is known (NestedFabricRequirements.resolve): what needs
		 * only left-out mods, or that only they bundle, goes too; what a mod that loads needs comes back. Then each
		 * left-out mod gets its one line, here rather than during the walk, since a later route can still bring a jar
		 * back in. A left-out mod's children stay in the graph; with every parent excluded the selector cannot reach
		 * them.
		 */
		private Map<Path, NestedFabricRequirements.KeptBack> resolveLeftOut() {
			if (leftOut.isEmpty()) return Map.of();
			Map<Path, List<Path>> parentsOf = new HashMap<>();
			for (Edge edge : edges) {
				List<Path> parents = parentsOf.computeIfAbsent(edge.child(), ignored -> new ArrayList<>());
				if (!parents.contains(edge.parent())) parents.add(edge.parent());
			}
			net.forbric.api.Side physical = physical(side);
			List<NestedFabricRequirements.Candidate<Path>> candidates = new ArrayList<>();
			for (Node node : nodes.values()) {
				// Excluded for another reason (side, a superseded MixinExtras): never opened, never loaded.
				if (node.excluded() && !leftOut.containsKey(node.path())) continue;
				boolean isFabric = node.claim() != null && node.claim().ecosystem() == Ecosystem.FABRIC;
				// Only what Fabric Loader alone would have reached is its to leave out.
				boolean judged = !node.root() && !reachedOutsideFabric.contains(node.path());
				candidates.add(candidate(node, parentsOf.getOrDefault(node.path(), List.of()), judged,
						isFabric ? fabricManifest(node.path()) : null, discoverer, physical));
			}
			var resolution = NestedFabricRequirements.resolve(candidates, leftOut, List.of());
			// Only Fabric's rule is settled here: a jar it left out is excluded, one it kept back is not.
			for (Node node : List.copyOf(nodes.values())) {
				boolean out = resolution.leftOut().containsKey(node.path());
				if ((out || leftOut.containsKey(node.path())) && node.excluded() != out) {
					nodes.put(node.path(), new Node(node.path(), node.digest(), node.claim(), node.root(), out));
				}
			}
			leftOut.clear();
			leftOut.putAll(resolution.leftOut());
			for (var entry : resolution.leftOut().entrySet()) {
				NestedFabricRequirements.log(fabricManifest(entry.getKey()), parentName(entry.getKey(), parentsOf), entry.getValue());
			}
			for (var entry : resolution.keptBack().entrySet()) {
				NestedFabricRequirements.logKeptBack(fabricManifest(entry.getKey()), parentName(entry.getKey(), parentsOf), entry.getValue());
			}
			return resolution.keptBack();
		}

		private Path parentName(Path child, Map<Path, List<Path>> parentsOf) {
			Path parent = leftOutParent.get(child);
			if (parent == null) parent = parentsOf.getOrDefault(child, List.of()).stream().findFirst().orElse(child);
			return parent.getFileName();
		}

		private KernelModMetadata fabricManifest(Path jar) {
			KernelModMetadata known = fabricManifests.get(jar);
			if (known != null) return known;
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				ZipEntry manifest = zip.getEntry("fabric.mod.json"); if (manifest == null) return null;
				try (InputStream in = zip.getInputStream(manifest)) {
					KernelModMetadata metadata = FabricModMetadataParser.read(in); fabricManifests.put(jar, metadata); return metadata;
				}
			} catch (Exception unreadable) {
				Caught.warn(unreadable, "[Forbric/JiJ] could not read fabric.mod.json of %s; it is taken to have none", jar);
				return null;
			}
		}

		/** Streams one nested entry through SHA-256; a large native bundle is never held in memory whole. */
		private String hash(ZipFile zip, ZipEntry entry) throws IOException {
			MessageDigest sha;
			try { sha = MessageDigest.getInstance("SHA-256"); } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
			long size = 0;
			try (InputStream in = zip.getInputStream(entry)) {
				byte[] buffer = new byte[65536];
				for (int n; (n = in.read(buffer)) >= 0;) {
					if ((size += n) > ENTRY_BYTES) throw new BoundReached("nested archive exceeds the " + (ENTRY_BYTES >> 20) + " MB scan bound");
					sha.update(buffer, 0, n);
				}
			}
			if ((extractedBytes += size) > TOTAL_BYTES) throw new BoundReached("nested archives exceed the " + (TOTAL_BYTES >> 30) + " GB scan bound");
			return HexFormat.of().formatHex(sha.digest());
		}

		private Path materialize(String hash, String entry, ZipFile zip, ZipEntry source) throws IOException {
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
				try (InputStream in = zip.getInputStream(source)) { Files.copy(in, temporary, StandardCopyOption.REPLACE_EXISTING); }
				try (ZipFile ignored = new ZipFile(temporary.toFile())) { }
				try {
					try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
					catch (AtomicMoveNotSupportedException unsupported) {
						Caught.expected(unsupported);
						Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
					}
				} catch (IOException concurrentOrLocked) {
					if (!Files.isRegularFile(target) || !hash.equals(digest(target))) throw concurrentOrLocked;
					Caught.expected(concurrentOrLocked);
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
			} catch (Exception unreadable) {
				Caught.warn(unreadable, "[Forbric/JiJ] could not read the environment of %s; it is not left out by side", child);
				return false;
			}
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
