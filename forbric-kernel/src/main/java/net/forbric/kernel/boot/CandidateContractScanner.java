/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import net.fabricmc.api.EnvType;
import net.forbric.api.*;
import net.forbric.kernel.discovery.ForbricModDiscoverer;
import net.forbric.kernel.discovery.ModAnnotationScanner;
import net.forbric.kernel.fabric.FabricModMetadataParser;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/** Reads only evidence strong enough to constrain candidate selection; arbitrary class references are not requirements. */
final class CandidateContractScanner {
	private enum Match { YES, NO, UNKNOWN }
	/** {@code unsupported} names an entrypoint form the closure cannot follow; it is then unproved, never skipped. */
	private record Entry(String owner, String method, String unsupported) { }
	private record MemberUse(boolean field, int opcode, boolean interfaceOwner, String caller) {
		boolean staticUse() { return opcode == Opcodes.INVOKESTATIC || opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC; }
		boolean writesField() { return opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC; }
	}
	private record Metadata(List<UnifiedDependency> dependencies, Map<String, String> provides,
			List<String> mixins, List<Entry> entries, List<Exclusion> exclusions) { }
	/** A declared "cannot run with": {@code constraint} null means the range could not be read. */
	private record Exclusion(String modId, String constraint, boolean hard) { }
	private static final Set<String> PLATFORM = Set.of("java", "minecraft", "forge", "neoforge", "fabricloader", "fabric", "fml", "mixinextras");
	static final int HELPER_DEPTH_LIMIT = 32;
	static final int HELPER_NODE_LIMIT = 256;
	static final int HELPER_INSTRUCTION_LIMIT = 32768;

	private CandidateContractScanner() { }

	static List<JointCandidateSelector.Rule> scan(List<DuplicateModArbiter.Claim> claims, EnvType side) {
		Map<Path, Set<String>> owners = new LinkedHashMap<>();
		for (var claim : claims) owners.put(JointCandidateSelector.path(claim), Set.copyOf(claim.modIds()));
		return scan(claims, side, false, owners);
	}

	/** The complete graph supplies each physical jar separately; a parent must not claim a losing child's classes. */
	static List<JointCandidateSelector.Rule> scanPhysical(List<DuplicateModArbiter.Claim> claims, EnvType side,
			Map<Path, Set<String>> symbolOwners) {
		return scan(claims, side, true, symbolOwners);
	}

	private static List<JointCandidateSelector.Rule> scan(List<DuplicateModArbiter.Claim> claims, EnvType side,
			boolean physicalOnly, Map<Path, Set<String>> symbolOwners) {
		Map<Path, Inventory> inventories = new LinkedHashMap<>();
		Map<Path, Metadata> metadata = new LinkedHashMap<>();
		List<JointCandidateSelector.Rule> rules = new ArrayList<>();
		for (var claim : claims) {
			Path path = JointCandidateSelector.path(claim);
			try {
				Inventory inventory = new Inventory(path, physicalOnly);
				inventories.put(path, inventory);
				metadata.put(path, readMetadata(claim, inventory, side));
			} catch (Exception unreadable) {
				Map<String, String> knownProvides = new LinkedHashMap<>();
				for (String id : claim.modIds()) knownProvides.put(JointCandidateSelector.key(id), claim.versionOf(id));
				metadata.put(path, new Metadata(List.of(), knownProvides, List.of(), List.of(), List.of()));
				rules.add(new JointCandidateSelector.Rule("metadata", path, Set.of(), Set.of(path), true,
						"candidate contracts could not be fully read: " + unreadable.getClass().getSimpleName()));
			}
		}
		// Candidate bytecode is pre-Mixin. Even an optional/plugin-controlled declaration can add the very
		// member an entrypoint will call; its declaration proves uncertainty, not that the member stays absent.
		Set<String> transformedTargets = new HashSet<>();
		for (var entry : metadata.entrySet()) transformedTargets.addAll(declaredMixinTargets(entry.getValue(), inventories.get(entry.getKey()), side));
		for (var claim : claims) {
			Path source = JointCandidateSelector.path(claim);
			Metadata mod = metadata.get(source); Inventory inventory = inventories.get(source);
			if (mod == null || inventory == null) continue;
			List<UnifiedDependency> mandatory = mod.dependencies().stream().filter(UnifiedDependency::isMandatory)
					.filter(d -> d.appliesOn(side == EnvType.SERVER ? Side.DEDICATED_SERVER : Side.CLIENT)).toList();
			List<UnifiedDependency> symbolDependencies = new ArrayList<>();
			for (UnifiedDependency dependency : mandatory) {
				if (PLATFORM.contains(dependency.getModId())) continue;
				String key = providedKey(dependency.getModId(), metadata);
				// DependencyAudit owns a missing installation and a version nobody installed: it warns, offers its
				// dialog and loads the mod anyway. Arbitration only decides between installed candidates, so a
				// requirement that no candidate can meet is not a choice here and must not become one (gate-m20).
				if (key == null) continue;
				symbolDependencies.add(new UnifiedDependency(key, dependency.getVersionConstraint(), true));
				Set<Path> providers = new LinkedHashSet<>(), unknown = new LinkedHashSet<>();
				for (var candidate : metadata.entrySet()) {
					String version = candidate.getValue().provides().get(key);
					if (version == null) continue;
					if (VersionPredicate.matchesStrictly(dependency.getVersionConstraint(), version)) providers.add(candidate.getKey());
					else if (VersionPredicate.matches(dependency.getVersionConstraint(), version)) unknown.add(candidate.getKey());
				}
				if (providers.isEmpty() && unknown.isEmpty()) continue;
				rules.add(new JointCandidateSelector.Rule("dependency:" + dependency.getModId(), source, providers, unknown, true,
						"requires " + dependency.getModId() + " " + dependency.getVersionConstraint()));
			}
			// Negative constraints are dependency constraints too (PLAN.md:62): prefer the build the mod can run with.
			for (Exclusion exclusion : mod.exclusions()) {
				if (PLATFORM.contains(exclusion.modId())) continue;
				String key = providedKey(exclusion.modId(), metadata); if (key == null) continue;
				Set<Path> excluded = new LinkedHashSet<>(), maybe = new LinkedHashSet<>();
				for (var candidate : metadata.entrySet()) {
					String version = candidate.getValue().provides().get(key);
					if (version == null || candidate.getKey().equals(source)) continue;
					if (exclusion.constraint() != null && VersionPredicate.matchesStrictly(exclusion.constraint(), version)) excluded.add(candidate.getKey());
					else if (exclusion.constraint() == null || VersionPredicate.matches(exclusion.constraint(), version)) maybe.add(candidate.getKey());
				}
				if (excluded.isEmpty() && maybe.isEmpty()) continue;
				String range = exclusion.constraint() == null ? "(unreadable range)" : exclusion.constraint();
				rules.add(new JointCandidateSelector.Rule((exclusion.hard() ? "breaks:" : "conflicts:") + exclusion.modId(), source, excluded, maybe,
						exclusion.hard(), (exclusion.hard() ? "declares it cannot run with " : "declares it conflicts with ") + exclusion.modId() + " " + range, true));
			}
			if (physicalOnly) for (String own : symbolOwners.getOrDefault(source, Set.of())) symbolDependencies.add(new UnifiedDependency(own, "*", true));
			for (String config : mod.mixins()) scanMixins(source, config, inventory, symbolDependencies, symbolOwners, inventories, side, rules);
			var calls = new EntrypointCalls(source, inventory, symbolDependencies, symbolOwners, inventories, transformedTargets, rules);
			for (Entry entry : mod.entries()) calls.scan(entry);
		}
		return List.copyOf(rules);
	}

	/**
	 * The provides key an installed candidate answers {@code id} under, or null when none does. Same order as
	 * DependencyAudit: the exact id (and every provides alias) first, then {@link ModIds#collapsed} only when
	 * exactly one installed mod collapses to it. Several jars of that ONE mod (its builds for each ecosystem) are
	 * still one mod; two different mods collapsing to the same key decline, exactly as the audit does.
	 */
	private static String providedKey(String id, Map<Path, Metadata> metadata) {
		String exact = JointCandidateSelector.key(id);
		Set<String> keys = new TreeSet<>();
		for (Metadata candidate : metadata.values()) keys.addAll(candidate.provides().keySet());
		if (keys.contains(exact)) return exact;
		if (!ModIds.enabled()) return null;
		String wanted = ModIds.collapsed(id);
		if (wanted == null || wanted.isEmpty()) return null;
		// Keyed by who provides it: one mod reached under its id and a provides alias is still one candidate set.
		Map<Set<Path>, String> spelled = new LinkedHashMap<>();
		for (String key : keys) {
			if (!wanted.equals(ModIds.collapsed(key))) continue;
			Set<Path> providers = new HashSet<>();
			for (var candidate : metadata.entrySet()) if (candidate.getValue().provides().containsKey(key)) providers.add(candidate.getKey());
			spelled.putIfAbsent(providers, key);
		}
		return spelled.size() == 1 ? spelled.values().iterator().next() : null;
	}

	private static Metadata readMetadata(DuplicateModArbiter.Claim claim, Inventory jar, EnvType side) throws Exception {
		List<UnifiedDependency> dependencies = new ArrayList<>(); Map<String, String> provides = new LinkedHashMap<>();
		for (String id : claim.modIds()) provides.put(JointCandidateSelector.key(id), claim.versionOf(id));
		List<String> mixins = new ArrayList<>(); List<Entry> entries = new ArrayList<>(); List<Exclusion> exclusions = new ArrayList<>();
		if (claim.ecosystem() == null) return new Metadata(List.of(), Map.of(), List.of(), List.of(), List.of());
		if (claim.ecosystem() == Ecosystem.FABRIC) {
			byte[] manifest = jar.read("fabric.mod.json");
			if (manifest == null) throw new IOException("no Fabric metadata");
			var mod = FabricModMetadataParser.read(new ByteArrayInputStream(manifest));
			dependencies.addAll(KernelFabricEcosystem.unifiedDependencies(mod));
			for (var dependency : mod.getDependencies()) {
				var kind = dependency.getKind();
				if (kind == net.fabricmc.loader.api.metadata.ModDependency.Kind.BREAKS || kind == net.fabricmc.loader.api.metadata.ModDependency.Kind.CONFLICTS)
					exclusions.add(new Exclusion(dependency.getModId(), KernelFabricEcosystem.constraintOf(dependency), !kind.isSoft()));
			}
			for (String alias : mod.getProvides()) provides.put(JointCandidateSelector.key(alias), mod.getVersion().getFriendlyString());
			for (var config : mod.getMixinConfigs()) if (side == null || config.environment().matches(side)) mixins.add(config.config());
			Map<String, String> phases = new LinkedHashMap<>(Map.of("preLaunch", "onPreLaunch", "main", "onInitialize"));
			phases.put(side == EnvType.SERVER ? "server" : "client", side == EnvType.SERVER ? "onInitializeServer" : "onInitializeClient");
			for (var phase : phases.entrySet()) for (var entry : mod.getEntrypoints().getOrDefault(phase.getKey(), List.of())) {
				// These all run (KernelFabricLoader resolves "Cls::member" and hands other adapters to their
				// language adapter), so none may be dropped silently. The default and Kotlin adapters both call the
				// named method, or the phase method on the class/object; another adapter is explicitly unproved.
				String value = entry.value(); int member = value.indexOf("::");
				String owner = (member < 0 ? value : value.substring(0, member)).replace('.', '/');
				if (!entry.isDefaultAdapter() && !"kotlin".equals(entry.adapter())) {
					entries.add(new Entry(owner, null, "language adapter '" + entry.adapter() + "'")); continue;
				}
				entries.add(new Entry(owner, member < 0 ? phase.getValue() : value.substring(member + 2), null));
			}
		} else {
			for (DiscoveredMod mod : new ForbricModDiscoverer().discoverJar(claim.jar())) {
				if (mod.getEcosystem() != claim.ecosystem() || !claim.modIds().contains(mod.getId())) continue;
				dependencies.addAll(mod.getDependencies()); mixins.addAll(mod.getMixinConfigs());
			}
			exclusions.addAll(forgeExclusions(claim, jar, side));
			for (var entry : ModAnnotationScanner.scan(claim.jar())) {
				if (entry.family != claim.ecosystem() || !claim.modIds().contains(entry.modId)) continue;
				if (!entry.dists.isEmpty() && !entry.dists.contains(side == EnvType.SERVER ? "DEDICATED_SERVER" : "CLIENT")) continue;
				entries.add(new Entry(entry.className.replace('.', '/'), "<init>", null));
			}
		}
		return new Metadata(List.copyOf(dependencies), Map.copyOf(provides), List.copyOf(mixins), List.copyOf(entries), List.copyOf(exclusions));
	}

	/**
	 * NeoForge {@code type="incompatible"} (hard) and {@code "discouraged"} (soft) entries, read from the toml here
	 * because the shared parser models only positive dependencies and reads both as optional ones.
	 */
	private static List<Exclusion> forgeExclusions(DuplicateModArbiter.Claim claim, Inventory jar, EnvType side) throws IOException {
		byte[] toml = jar.read(claim.ecosystem() == Ecosystem.NEOFORGE ? "META-INF/neoforge.mods.toml" : "META-INF/mods.toml");
		if (toml == null) return List.of();
		com.electronwill.nightconfig.core.UnmodifiableConfig config;
		try { config = new com.electronwill.nightconfig.toml.TomlParser().parse(new StringReader(new String(toml, java.nio.charset.StandardCharsets.UTF_8))); }
		catch (RuntimeException malformed) { return List.of(); }
		List<Exclusion> exclusions = new ArrayList<>();
		for (String id : claim.modIds()) {
			Object declared = config.get(List.of("dependencies", id));
			if (!(declared instanceof List<?> entries)) continue;
			for (Object entry : entries) {
				if (!(entry instanceof com.electronwill.nightconfig.core.UnmodifiableConfig dependency)) continue;
				String type = dependency.getOrElse("type", ""), modId = dependency.getOrElse("modId", "");
				boolean hard = "incompatible".equalsIgnoreCase(type.trim());
				if (!hard && !"discouraged".equalsIgnoreCase(type.trim()) || modId.isBlank()) continue;
				if (!UnifiedDependency.SideScope.parse(dependency.getOrElse("side", (String) null)).includes(side == EnvType.SERVER ? Side.DEDICATED_SERVER : Side.CLIENT)) continue;
				String constraint;
				try { constraint = net.forbric.kernel.metadata.forge.ForgeVersionRangeTranslator.toFabricPredicate(dependency.getOrElse("versionRange", (String) null)); }
				catch (IllegalArgumentException malformed) { constraint = null; }
				exclusions.add(new Exclusion(modId, constraint, hard));
			}
		}
		return exclusions;
	}

	private static void scanMixins(Path source, String name, Inventory jar, List<UnifiedDependency> dependencies,
			Map<Path, Set<String>> symbolOwners, Map<Path, Inventory> inventories, EnvType side, List<JointCandidateSelector.Rule> rules) {
		try {
			byte[] bytes = jar.read(name); if (bytes == null) { rules.add(unknown(source, "config:" + name, "mixin config not readable")); return; }
			var config = com.electronwill.nightconfig.json.JsonFormat.fancyInstance().createParser().parse(new StringReader(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)));
			String pkg = config.get("package"); if (pkg == null) return;
			boolean required = Boolean.TRUE.equals(config.get("required"));
			boolean unconditional = config.get("plugin") == null;
			List<String> mixins = new ArrayList<>();
			for (String list : List.of("mixins", side == EnvType.SERVER ? "server" : "client")) {
				Object value = config.get(list); if (value instanceof List<?> rows) for (Object row : rows) if (row instanceof String text) mixins.add(text);
			}
			for (String mixin : mixins) {
				ClassNode node = jar.node((pkg + "." + mixin).replace('.', '/'));
				if (node == null) { rules.add(unknown(source, "mixin:" + name + ":" + mixin, "mixin class not readable")); continue; }
				List<String> targets = new ArrayList<>(); boolean conditionalAnnotation = false, disabledOnSide = false;
				List<AnnotationNode> annotations = new ArrayList<>();
				if (node.visibleAnnotations != null) annotations.addAll(node.visibleAnnotations);
				if (node.invisibleAnnotations != null) annotations.addAll(node.invisibleAnnotations);
				for (AnnotationNode annotation : annotations) {
					if (annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) {
						if (annotation.values == null) continue;
						for (int i = 0; i < annotation.values.size(); i += 2) {
							String key = String.valueOf(annotation.values.get(i));
							if (!(key.equals("value") || key.equals("targets"))) continue;
							Object value = annotation.values.get(i + 1);
							if (value instanceof List<?> list) for (Object target : list) targets.add(target instanceof Type type ? type.getInternalName() : String.valueOf(target).replace('.', '/'));
						}
					} else if (annotation.desc.equals("Lnet/fabricmc/api/Environment;")) {
						boolean known = false;
						if (side != null && annotation.values != null) for (int i = 0; i < annotation.values.size(); i += 2) {
							if (!"value".equals(annotation.values.get(i)) || !(annotation.values.get(i + 1) instanceof String[] value) || value.length != 2) continue;
							known = value[1].equals("CLIENT") || value[1].equals("SERVER");
							if (known && !value[1].equals(side.name())) disabledOnSide = true;
						}
						if (!known) conditionalAnnotation = true;
					} else conditionalAnnotation = true;
				}
				if (disabledOnSide) continue;
				for (String target : targets) {
					if (!belongsToDependency(target, dependencies, symbolOwners, inventories)) continue;
					addSymbolRule(source, "mixin:" + name + ":" + mixin + ":" + target, target, null, null, null,
							required && unconditional && !conditionalAnnotation, "mixin " + mixin + " needs target " + target,
							inventories, Set.of(), rules);
				}
			}
		} catch (Exception malformed) { rules.add(unknown(source, "config:" + name, "could not verify mixin activation: " + malformed.getClass().getSimpleName())); }
	}

	/** Bounded same-jar closure, not a reflection/lambda or general virtual-dispatch analysis. */
	private static final class EntrypointCalls {
		private record MethodRef(String owner, String name, String desc) {
			String symbol() { return owner + "#" + name + desc; }
		}
		private record Visit(MethodRef method, boolean hard) { }
		private final Path source;
		private final Inventory jar;
		private final List<UnifiedDependency> dependencies;
		private final Map<Path, Set<String>> symbolOwners;
		private final Map<Path, Inventory> inventories;
		private final Set<String> transformedTargets;
		private final List<JointCandidateSelector.Rule> rules;
		private final Set<MethodRef> active = new HashSet<>();
		private final Map<Visit, Boolean> visited = new HashMap<>();
		private final Set<String> issues = new HashSet<>();
		private int nodes, instructions;

		EntrypointCalls(Path source, Inventory jar, List<UnifiedDependency> dependencies,
				Map<Path, Set<String>> symbolOwners, Map<Path, Inventory> inventories, Set<String> transformedTargets,
				List<JointCandidateSelector.Rule> rules) {
			this.source = source; this.jar = jar; this.dependencies = dependencies; this.symbolOwners = symbolOwners;
			this.inventories = inventories; this.transformedTargets = transformedTargets; this.rules = rules;
		}

		void scan(Entry entry) {
			if (entry.unsupported() != null) {
				unproved(entry.owner(), "entrypoint uses " + entry.unsupported() + ", which this scan does not follow", true);
				return;
			}
			ClassNode node = jar.node(entry.owner());
			List<MethodNode> methods = node == null ? List.of() : node.methods.stream()
					.filter(m -> m.name.equals(entry.method()) && (m.access & Opcodes.ACC_PUBLIC) != 0).toList();
			if (methods.size() != 1) {
				unproved(entry.owner() + "#" + entry.method(), "entrypoint dispatch/body is not uniquely known", true);
				return;
			}
			walk(node, methods.getFirst(), true, 0);
		}

		/** False means later instructions cannot inherit a proved unconditional call/return path. */
		private boolean walk(ClassNode owner, MethodNode method, boolean hard, int depth) {
			MethodRef ref = new MethodRef(owner.name, method.name, method.desc);
			if (active.contains(ref)) { unproved(ref.symbol(), "recursive helper call", hard); return false; }
			Visit visit = new Visit(ref, hard);
			Boolean previous = visited.get(visit); if (previous != null) return previous;
			if (depth > HELPER_DEPTH_LIMIT || nodes >= HELPER_NODE_LIMIT || instructions >= HELPER_INSTRUCTION_LIMIT) {
				unproved(ref.symbol(), "helper closure limit (depth=" + depth + ", nodes=" + nodes + ", instructions=" + instructions + ")", hard);
				return false;
			}
			nodes++;
			if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0 || method.instructions.size() == 0) {
				unproved(ref.symbol(), "helper has no inspectable executable body", hard); visited.put(visit, false); return false;
			}
			boolean straight = method.tryCatchBlocks.isEmpty();
			for (AbstractInsnNode instruction : method.instructions) {
				if (instruction instanceof JumpInsnNode || instruction instanceof TableSwitchInsnNode || instruction instanceof LookupSwitchInsnNode) straight = false;
			}
			// Branches/handlers weaken member requirements, but a fully scanned guard is not an
			// uncovered member contract. Only unresolved calls/bodies or bounds produce closure findings.
			if (transformedTargets.contains(owner.name)) {
				unproved(ref.symbol(), "local body may be changed by a declared Mixin", hard); straight = false;
			}
			boolean path = hard && straight, complete = straight;
			active.add(ref);
			try {
				for (AbstractInsnNode instruction : method.instructions) {
					if (++instructions > HELPER_INSTRUCTION_LIMIT) {
						unproved(ref.symbol(), "helper instruction limit", path); complete = false; break;
					}
					String target = null, name = null, descriptor = null; MemberUse use = null;
					if (instruction instanceof MethodInsnNode call) {
						target = call.owner; name = call.name; descriptor = call.desc;
						use = new MemberUse(false, call.getOpcode(), call.itf, owner.name);
						if (jar.has(target)) {
							ClassNode local = jar.node(target);
							List<MethodNode> matches = local == null ? List.of() : local.methods.stream()
									.filter(m -> m.name.equals(call.name) && m.desc.equals(call.desc)).toList();
							if (matches.size() != 1) {
								unproved(target + "#" + name + descriptor, "local helper resolution is not unique", path);
								path = false; complete = false; continue;
							}
							MethodNode helper = matches.getFirst();
							boolean staticCall = call.getOpcode() == Opcodes.INVOKESTATIC;
							boolean unique = staticCall == ((helper.access & Opcodes.ACC_STATIC) != 0)
									&& call.itf == ((local.access & Opcodes.ACC_INTERFACE) != 0)
									&& Inventory.accessible(helper.access, local.name, owner.name)
									&& (staticCall || (helper.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)) != 0
											|| (local.access & Opcodes.ACC_FINAL) != 0);
							if (!unique) unproved(target + "#" + name + descriptor, "local helper dispatch may select another body", path);
							boolean followed = walk(local, helper, path && unique, depth + 1);
							if (!unique || !followed) { path = false; complete = false; }
							continue;
						}
					}
					if (instruction instanceof FieldInsnNode call) {
						target = call.owner; name = call.name; descriptor = call.desc;
						use = new MemberUse(true, call.getOpcode(), false, owner.name);
					}
					if (target != null && !jar.has(target) && belongsToDependency(target, dependencies, symbolOwners, inventories)) {
						addSymbolRule(source, "entry:" + ref.symbol() + ":" + target + "#" + name + descriptor + ":" + instruction.getOpcode(),
								target, name, descriptor, use, path, "entrypoint-reachable " + ref.symbol() + " uses "
										+ org.objectweb.asm.util.Printer.OPCODES[instruction.getOpcode()] + " " + target + "#" + name + descriptor,
								inventories, transformedTargets, rules);
					}
					// Do not promote unreachable instructions after an unconditional return/throw into contracts.
					int opcode = instruction.getOpcode();
					if (opcode == Opcodes.ATHROW || opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
						if (straight) { if (opcode == Opcodes.ATHROW) complete = false; break; }
					}
				}
			} finally { active.remove(ref); }
			visited.put(visit, complete);
			return complete;
		}

		private void unproved(String symbol, String reason, boolean requiredPath) {
			String id = "entry-closure:" + symbol + ":" + reason + ":" + requiredPath;
			if (issues.add(id)) rules.add(new JointCandidateSelector.Rule(id, source, Set.of(), Set.of(source), requiredPath,
					"Entrypoint member closure remains unproved: " + symbol + ": " + reason));
		}
	}

	private static boolean belongsToDependency(String owner, List<UnifiedDependency> dependencies,
			Map<Path, Set<String>> symbolOwners, Map<Path, Inventory> inventories) {
		for (var claim : symbolOwners.entrySet()) {
			Inventory inventory = inventories.get(claim.getKey());
			if (inventory == null || !inventory.has(owner)) continue;
			for (String id : claim.getValue()) if (dependencies.stream().anyMatch(d -> JointCandidateSelector.key(d.getModId()).equals(JointCandidateSelector.key(id)))) return true;
		}
		return false;
	}

	private static void addSymbolRule(Path source, String id, String owner, String name, String descriptor, MemberUse use,
			boolean hard, String detail, Map<Path, Inventory> inventories, Set<String> transformedTargets, List<JointCandidateSelector.Rule> rules) {
		Set<Path> providers = new LinkedHashSet<>(), uncertain = new LinkedHashSet<>();
		for (var candidate : inventories.entrySet()) {
			if (!candidate.getValue().has(owner)) continue;
			Match match = name == null ? Match.YES : candidate.getValue().member(owner, name, descriptor, use, transformedTargets);
			if (match == Match.YES) providers.add(candidate.getKey());
			else if (match == Match.UNKNOWN) uncertain.add(candidate.getKey());
		}
		// A class served by the system libraries is available independently of which duplicate jar won.
		try (InputStream external = ClassLoader.getSystemResourceAsStream(owner + ".class")) {
			if (external != null) { uncertain.add(source); hard = false; }
		} catch (IOException ignored) { }
		rules.add(new JointCandidateSelector.Rule(id, source, providers, uncertain, hard,
				detail + (hard ? " (unconditional required contract)" : " (activation or member resolution not proved)")));
	}

	private static JointCandidateSelector.Rule unknown(Path source, String id, String detail) {
		return new JointCandidateSelector.Rule(id, source, Set.of(), Set.of(), false, detail);
	}

	private static Set<String> declaredMixinTargets(Metadata metadata, Inventory jar, EnvType side) {
		Set<String> targets = new HashSet<>();
		for (String configName : metadata.mixins()) {
			try {
				byte[] bytes = jar.read(configName); if (bytes == null) continue;
				var config = com.electronwill.nightconfig.json.JsonFormat.fancyInstance().createParser().parse(
						new StringReader(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)));
				String pkg = config.get("package"); if (pkg == null) continue;
				for (String section : List.of("mixins", side == EnvType.SERVER ? "server" : "client")) {
					Object raw = config.get(section); if (!(raw instanceof List<?> mixins)) continue;
					for (Object entry : mixins) {
						if (!(entry instanceof String name)) continue;
						ClassNode node = jar.node((pkg + "." + name).replace('.', '/')); if (node == null) continue;
						List<AnnotationNode> annotations = new ArrayList<>();
						if (node.visibleAnnotations != null) annotations.addAll(node.visibleAnnotations);
						if (node.invisibleAnnotations != null) annotations.addAll(node.invisibleAnnotations);
						boolean disabled = false;
						Set<String> declared = new HashSet<>();
						for (AnnotationNode annotation : annotations) {
							if (annotation.values == null) continue;
							for (int i = 0; i < annotation.values.size(); i += 2) {
								Object value = annotation.values.get(i + 1);
								if (annotation.desc.equals("Lnet/fabricmc/api/Environment;") && side != null
										&& value instanceof String[] environment && environment.length == 2
										&& (environment[1].equals("CLIENT") || environment[1].equals("SERVER"))
										&& !environment[1].equals(side.name())) disabled = true;
								if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) continue;
								Object key = annotation.values.get(i);
								if (!("value".equals(key) || "targets".equals(key)) || !(value instanceof List<?> list)) continue;
								for (Object target : list) declared.add(target instanceof Type type ? type.getInternalName() : String.valueOf(target).replace('.', '/'));
							}
						}
						if (!disabled) targets.addAll(declared);
					}
				}
			} catch (IOException | RuntimeException ignored) {
				// scanMixins keeps the unreadable declaration as its own uncertainty finding.
			}
		}
		return targets;
	}

	/** Resource inventory including bundled jars. Reads requested classes lazily and caps archive nesting. */
	private static final class Inventory {
		private final Path jar;
		private final Map<String, List<String>> resources = new LinkedHashMap<>();
		private final Map<String, ClassNode> nodes = new HashMap<>();
		Inventory(Path jar, boolean physicalOnly) throws IOException {
			this.jar = jar;
			try (JarFile zip = new JarFile(jar.toFile())) {
				for (ZipEntry entry : zip.stream().toList()) {
					resources.put(entry.getName(), List.of(entry.getName()));
					if (!physicalOnly && entry.getName().endsWith(".jar")) try (InputStream in = zip.getInputStream(entry)) { indexNested(in, List.of(entry.getName()), 1); }
				}
			}
		}
		private void indexNested(InputStream bytes, List<String> parents, int depth) throws IOException {
			if (depth > 4) throw new IOException("nested archive limit");
			try (ZipInputStream zip = new ZipInputStream(bytes)) {
				for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
					List<String> path = new ArrayList<>(parents); path.add(entry.getName());
					resources.putIfAbsent(entry.getName(), List.copyOf(path));
					if (entry.getName().endsWith(".jar")) indexNested(new ByteArrayInputStream(bounded(zip)), path, depth + 1);
				}
			}
		}
		boolean has(String owner) { return resources.containsKey(owner + ".class"); }
		byte[] read(String resource) throws IOException {
			List<String> path = resources.get(resource); if (path == null) return null;
			try (JarFile zip = new JarFile(jar.toFile()); InputStream in = zip.getInputStream(zip.getEntry(path.getFirst()))) {
				byte[] bytes = bounded(in);
				for (String name : path.subList(1, path.size())) {
					boolean found = false;
					try (ZipInputStream nested = new ZipInputStream(new ByteArrayInputStream(bytes))) {
						for (ZipEntry entry; (entry = nested.getNextEntry()) != null;) if (entry.getName().equals(name)) { bytes = bounded(nested); found = true; break; }
					}
					if (!found) return null;
				}
				return bytes;
			}
		}
		ClassNode node(String owner) {
			if (nodes.containsKey(owner)) return nodes.get(owner);
			ClassNode node = null;
			try { byte[] bytes = read(owner + ".class"); if (bytes != null) { node = new ClassNode(); new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES); } }
			catch (IOException | RuntimeException malformed) { }
			nodes.put(owner, node); return node;
		}
		Match member(String owner, String name, String descriptor, MemberUse use, Set<String> transformedTargets) {
			ClassNode symbolicOwner = node(owner); if (symbolicOwner == null) return Match.UNKNOWN;
			if (!use.field() && ((symbolicOwner.access & Opcodes.ACC_INTERFACE) != 0) != use.interfaceOwner()) {
				return transformedTargets.contains(owner) ? Match.UNKNOWN : Match.NO;
			}
			Match resolved = memberInHierarchy(owner, name, descriptor, use, transformedTargets, new HashSet<>());
			if (resolved == Match.YES && !accessible(symbolicOwner.access, owner, use.caller())) return Match.UNKNOWN;
			return resolved;
		}
		private Match memberInHierarchy(String owner, String name, String descriptor, MemberUse use,
				Set<String> transformedTargets, Set<String> visited) {
			if (!visited.add(owner)) return Match.UNKNOWN;
			ClassNode node = node(owner); if (node == null) return Match.UNKNOWN;
			Integer access = use.field() ? node.fields.stream().filter(f -> f.name.equals(name) && f.desc.equals(descriptor)).map(f -> f.access).findFirst().orElse(null)
					: node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).map(m -> m.access).findFirst().orElse(null);
			if (access != null) {
				if (((access & Opcodes.ACC_STATIC) != 0) != use.staticUse()) return transformedTargets.contains(owner) ? Match.UNKNOWN : Match.NO;
				// Access wideners/transformers can legitimately change these flags before linkage. An inaccessible
				// pre-transform member is unproved, never a reason to reject a candidate as necessarily broken.
				if (!accessible(access, owner, use.caller()) || (use.writesField() && (access & Opcodes.ACC_FINAL) != 0)) return Match.UNKNOWN;
				return Match.YES;
			}
			if (name.equals("<init>")) return transformedTargets.contains(owner) ? Match.UNKNOWN : Match.NO;
			boolean unknown = transformedTargets.contains(owner);
			// Static interface methods are not inherited. Fields and virtual/default methods use the
			// superinterface search, but an INVOKESTATIC cannot borrow an interface's static helper.
			List<String> ancestors = new ArrayList<>(!use.field() && use.staticUse() ? List.of() : node.interfaces);
			if (node.superName != null && !node.superName.equals("java/lang/Object")) ancestors.add(node.superName);
			for (String parent : ancestors) { Match inherited = memberInHierarchy(parent, name, descriptor, use, transformedTargets, visited); if (inherited == Match.YES) return Match.YES; unknown |= inherited == Match.UNKNOWN; }
			return unknown ? Match.UNKNOWN : Match.NO;
		}
		private static boolean accessible(int access, String owner, String caller) {
			if ((access & Opcodes.ACC_PUBLIC) != 0 || owner.equals(caller)) return true;
			if ((access & Opcodes.ACC_PRIVATE) != 0) return false;
			return owner.substring(0, Math.max(0, owner.lastIndexOf('/'))).equals(caller.substring(0, Math.max(0, caller.lastIndexOf('/'))));
		}
		private static byte[] bounded(InputStream stream) throws IOException {
			byte[] bytes = stream.readNBytes(64 * 1024 * 1024 + 1);
			if (bytes.length > 64 * 1024 * 1024) throw new IOException("archive resource exceeds scan limit");
			return bytes;
		}
	}
}
