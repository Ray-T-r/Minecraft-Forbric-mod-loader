/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.kernel.fabric.FabricModMetadataParser;
import net.forbric.kernel.fabric.KernelModMetadata;
import net.forbric.kernel.fabric.KernelModMetadata.EntrypointDecl;
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * The entrypoints a library's losing Fabric build offers to OTHER mods, kept where the build that loaded can satisfy them.
 *
 * <p>When arbitration loads a library's NeoForge build and supersedes its Fabric build, the Fabric build's whole
 * {@code fabric.mod.json} goes with it — including the entrypoints it declares under keys another mod dispatches:
 * {@code modmenu} is the usual one. Mod Menu asks Fabric Loader for every {@code modmenu} entrypoint and the library's
 * plugin answers with a config screen for each of its consumers; with the Fabric build superseded nobody declares it, so
 * a Fabric mod whose config the library manages had no config screen in Mod Menu where natively it has one. The build
 * that loaded cannot declare it: a NeoForge build offers config screens through NeoForge's own extension point, which
 * Mod Menu does not read.
 *
 * <p>The library's presence alias on the Fabric side (the identity that keeps {@code isModLoaded} answering) now carries
 * those declarations, decided entirely from the two builds:
 * <ul>
 * <li><b>only keys Fabric Loader does not drive itself</b> — not {@code main}, {@code client}, {@code server},
 *     {@code preLaunch}. Those are the library's own initialisation, and the build that loaded initialised the library
 *     its own way: running the losing build's too would set the library up twice (its payload registered twice, its
 *     server configs loaded twice, every player sent its configs twice). A custom key such as {@code modmenu} is an
 *     integration offered to whichever mod reads it, which the other platform's build has no way to offer;</li>
 * <li><b>not a key the loaded build already declares</b> to Fabric mods ({@link CrossEcosystemDeclarations} forwards a
 *     Forge-family mod's namespaced {@code [modproperties]} entries): then it offers the integration itself;</li>
 * <li><b>not the library's own protocol</b>: a key either build asks Fabric Loader for itself (a platform provider, a
 *     plugin list of its own). The losing build's dispatch of it is {@link ArbitratedAwayDispatchers}' business, and a
 *     winner asking for it has its own implementation, which the losing build's would replace with the other
 *     platform's;</li>
 * <li><b>not a framework's key</b>: one a mod the losing build requires asks for (a component registry, a world-gen
 *     region API, an init hook of a shared library). The library builds its Fabric side on that framework and the loaded
 *     build replaces it with its own platform's; such a framework may also check the declaring mod's own metadata, which
 *     the library's identity here does not carry. A mod the library merely integrates with when present — Mod Menu,
 *     a recipe viewer — is not required by it, and its key is the integration this keeps;</li>
 * <li><b>only an entrypoint the loaded build can satisfy</b>: its class is the loaded build's own, or exists only in the
 *     losing build (the class loader serves it from there as a last resort) and so do the classes it reaches, and every
 *     member that code uses on a class the loaded build ships exists there with that descriptor — and no field of a
 *     losing-build-only class that code reads is written only by code that never runs. What runs is read method by method
 *     ({@link #reachable}): the entrypoint's own methods and what they call, initialise or instantiate — never the losing
 *     build's lifecycle entry methods (its {@code onInitialize} and the like), which only Fabric Loader calls, even where
 *     the entrypoint reads a field of that very class or calls another of its methods. One that cannot is named, with what
 *     it is missing, and not declared — a consumer then gets nothing instead of a {@code NoSuchMethodError} or a
 *     {@code NullPointerException} inside another mod.</li>
 * </ul>
 * The entrypoint is offered under the library's own id, as natively, so a reader keying on the provider's id (Mod Menu
 * does) sees the library. {@code -Dforbric.arbitratedAwayEntrypoints=off} declares none of them, as before.
 */
public final class ArbitratedAwayEntrypoints {
	public static final String PROPERTY = "forbric.arbitratedAwayEntrypoints";
	/** The keys Fabric Loader's own lifecycle dispatches: the library's initialisation, which the loaded build did. */
	static final Set<String> LIFECYCLE_KEYS = Set.of("main", "client", "server", "preLaunch");

	/** One declaration of the losing build, kept or not. */
	public record Decision(String library, Path losingBuild, String key, EntrypointDecl entrypoint, String refusal) {
		public boolean kept() {
			return refusal == null;
		}
	}

	private ArbitratedAwayEntrypoints() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/**
	 * The entrypoints {@code library}'s presence alias should declare besides {@code alreadyDeclared}, taken from its losing
	 * Fabric builds among {@code dupes}' rescue jars, each logged.
	 */
	public static Map<String, List<EntrypointDecl>> adopted(String library, DuplicateModArbiter.Decision dupes,
			Map<String, List<EntrypointDecl>> alreadyDeclared) {
		return adopted(library, dupes, alreadyDeclared, id -> null);
	}

	/** @param loadedJarOf the jar of a loaded Fabric mod by id (or {@code provides} alias), null when none is loaded */
	public static Map<String, List<EntrypointDecl>> adopted(String library, DuplicateModArbiter.Decision dupes,
			Map<String, List<EntrypointDecl>> alreadyDeclared, java.util.function.Function<String, Path> loadedJarOf) {
		if (!enabled() || library == null || dupes == null) return Map.of();
		Path winner = dupes.ownerByModId().get(library);
		if (winner == null) return Map.of();
		List<Decision> decisions = decide(library, losingBuildsOf(library, dupes.rescueJars(), winner), winner,
				alreadyDeclared == null ? Set.of() : alreadyDeclared.keySet(), loadedJarOf);
		Map<String, List<EntrypointDecl>> kept = new LinkedHashMap<>();
		for (Decision decision : decisions) {
			if (decision.kept()) {
				kept.computeIfAbsent(decision.key(), k -> new ArrayList<>()).add(decision.entrypoint());
				ForbricLog.info("[Forbric/DupeId] %s: its losing build %s declares the '%s' entrypoint %s, which the build "
						+ "that loaded (%s) can satisfy — offered under %s, as it is natively", library,
						decision.losingBuild().getFileName(), decision.key(), decision.entrypoint().value(), winner.getFileName(),
						library);
			} else {
				ForbricLog.warn("[Forbric/DupeId] %s: its losing build %s declares the '%s' entrypoint %s, but the build that "
						+ "loaded cannot satisfy it (%s) — not offered; a mod reading '%s' goes without it, and loading %s's "
						+ "Fabric build instead (forbric-mods.txt) restores it", library, decision.losingBuild().getFileName(),
						decision.key(), decision.entrypoint().value(), decision.refusal(), decision.key(), library);
				CompatibilityFindings.record(new CompatibilityFinding("arbitration:declared-entrypoint:" + decision.key(),
						library, "Mod integration", "ArbitratedAwayEntrypoints", CompatibilityFinding.Confidence.SUSPECTED, false,
						"the '" + decision.key() + "' entrypoint only this library's build that was not loaded declares cannot "
								+ "be satisfied by the build that loaded",
						List.of("losing build=" + decision.losingBuild().getFileName(), "entrypoint=" + decision.entrypoint().value(),
								decision.refusal())));
			}
		}
		return kept;
	}

	/** The superseded jars whose {@code fabric.mod.json} names {@code library}, as its id or a {@code provides}. */
	static List<Path> losingBuildsOf(String library, Collection<Path> rescueJars, Path winner) {
		List<Path> out = new ArrayList<>();
		for (Path jar : new TreeSet<>(rescueJars)) {
			if (jar.toAbsolutePath().equals(winner.toAbsolutePath())) continue;
			KernelModMetadata metadata = fabricMetadata(jar);
			if (metadata == null) continue;
			if (library.equals(metadata.getId()) || metadata.getProvides().contains(library)) out.add(jar);
		}
		return out;
	}

	/** The pure half, with no other loaded mod known. */
	static List<Decision> decide(String library, List<Path> losingBuilds, Path winner, Set<String> alreadyDeclared) {
		return decide(library, losingBuilds, winner, alreadyDeclared, id -> null);
	}

	/** The pure half: what each losing build's declarations come to against {@code winner}. */
	static List<Decision> decide(String library, List<Path> losingBuilds, Path winner, Set<String> alreadyDeclared,
			java.util.function.Function<String, Path> loadedJarOf) {
		List<Decision> out = new ArrayList<>();
		for (Path jar : losingBuilds) {
			KernelModMetadata metadata = fabricMetadata(jar);
			if (metadata == null) continue;
			try (Classes losing = new Classes(jar); Classes loaded = new Classes(winner, true)) {
				Set<String> lifecycle = lifecycleMethods(metadata, losing);
				Set<String> custom = new LinkedHashSet<>(metadata.getEntrypoints().keySet());
				custom.removeAll(LIFECYCLE_KEYS);
				custom.removeAll(alreadyDeclared);
				Set<String> ownProtocol = dispatchedBy(List.of(jar, winner), custom);
				for (String key : ownProtocol) {
					ForbricLog.info("[Forbric/DupeId] %s: '%s' is a protocol of its own (a build of it asks Fabric Loader for it), "
							+ "which the build that loaded serves its own way — its losing build's declarations under it are not "
							+ "offered", library, key);
				}
				Set<String> frameworks = new LinkedHashSet<>(custom);
				frameworks.removeAll(ownProtocol);
				frameworks.retainAll(dispatchedBy(requiredJars(metadata, loadedJarOf, jar, winner), frameworks));
				for (String key : frameworks) {
					ForbricLog.info("[Forbric/DupeId] %s: '%s' is asked for by a mod its losing build requires — a framework that "
							+ "build initialises itself through, which the build that loaded replaces with its own platform's — its "
							+ "losing build's declarations under it are not offered", library, key);
				}
				for (Map.Entry<String, List<EntrypointDecl>> entry : metadata.getEntrypoints().entrySet()) {
					String key = entry.getKey();
					if (!custom.contains(key) || ownProtocol.contains(key) || frameworks.contains(key)) continue;
					for (EntrypointDecl decl : entry.getValue()) {
						out.add(new Decision(library, jar, key, decl, refusal(decl, losing, loaded, lifecycle)));
					}
				}
			} catch (IOException | RuntimeException unreadable) {
				Caught.warn(unreadable, "[Forbric/DupeId] could not read %s's losing build %s or the build that loaded %s — "
						+ "the entrypoints it declares are not offered", library, jar.getFileName(), winner.getFileName());
			}
		}
		return out;
	}

	/**
	 * The keys either build of the library itself asks Fabric Loader for: the library's own protocol. The losing build's
	 * own dispatch of one is {@link ArbitratedAwayDispatchers}' business, and a winner that asks for it has its own
	 * implementation of it (handing it the losing build's would give it the other platform's).
	 */
	static Set<String> dispatchedBy(List<Path> builds, Set<String> keys) throws IOException {
		Set<String> out = new LinkedHashSet<>();
		if (keys.isEmpty()) return out;
		for (Path build : builds) {
			for (var dispatch : net.forbric.kernel.fabric.EntrypointDispatchScan.scan(build, keys)) out.add(dispatch.key());
		}
		return out;
	}

	/** The loaded jars of the mods {@code metadata} requires (a hard, positive dependency), neither build itself. */
	static List<Path> requiredJars(KernelModMetadata metadata, java.util.function.Function<String, Path> loadedJarOf,
			Path losing, Path winner) {
		Set<Path> out = new LinkedHashSet<>();
		for (net.fabricmc.loader.api.metadata.ModDependency dependency : metadata.getDependencies()) {
			if (!dependency.getKind().isPositive() || dependency.getKind().isSoft()) continue;
			Path jar = loadedJarOf.apply(dependency.getModId());
			if (jar != null && !jar.equals(losing) && !jar.equals(winner) && Files.isRegularFile(jar)) out.add(jar);
		}
		return List.copyOf(out);
	}

	/** Why {@code decl} cannot be satisfied by {@code loaded}, with no lifecycle entrypoint of the losing build known; null when it can. */
	static String refusal(EntrypointDecl decl, Classes losing, Classes loaded) throws IOException {
		return refusal(decl, losing, loaded, Set.of());
	}

	/**
	 * Why {@code decl} cannot be satisfied by {@code loaded}, or null when it can. {@code lifecycle} is the losing build's
	 * lifecycle entry methods ({@code owner#name+desc}, or {@code owner#name} for a member reference), which never run here.
	 */
	static String refusal(EntrypointDecl decl, Classes losing, Classes loaded, Set<String> lifecycle) throws IOException {
		if (!decl.isDefaultAdapter()) return "it goes through the '" + decl.getAdapter() + "' language adapter, whose resolution is not derived";
		String value = decl.value();
		String type = value.contains("::") ? value.substring(0, value.indexOf("::")) : value;
		String start = type.replace('.', '/');
		if (loaded.has(start)) return null;
		if (!losing.has(start)) return "its class " + type + " is in neither build";

		// The losing build's own classes the entrypoint reaches, which only the losing build has.
		Set<String> own = new LinkedHashSet<>();
		Deque<String> queue = new ArrayDeque<>(List.of(start));
		Map<String, ClassNode> nodes = new HashMap<>();
		while (!queue.isEmpty()) {
			String name = queue.removeFirst();
			if (!own.add(name)) continue;
			ClassNode node = losing.node(name);
			nodes.put(name, node);
			for (String referenced : referencedClasses(node)) {
				if (!own.contains(referenced) && losing.has(referenced) && !loaded.has(referenced)) queue.addLast(referenced);
			}
		}
		// What of that code can run here: the entrypoint's own methods (a reader may call any of them), and what they reach —
		// never the losing build's lifecycle entrypoints, which Fabric Loader would call and which do not run here.
		Set<MethodNode> runs = reachable(nodes, start, value.contains("::") ? value.substring(value.indexOf("::") + 2) : null, lifecycle);
		Set<String> written = new LinkedHashSet<>();
		for (ClassNode node : nodes.values()) {
			for (FieldNode field : node.fields) if (field.value != null) written.add(node.name + "." + field.name);
			for (MethodNode method : node.methods) {
				if (!runs.contains(method)) continue;
				for (AbstractInsnNode insn : method.instructions) {
					if (insn instanceof FieldInsnNode f && (f.getOpcode() == Opcodes.PUTFIELD || f.getOpcode() == Opcodes.PUTSTATIC)) {
						written.add(f.owner + "." + f.name);
					}
				}
			}
		}
		for (ClassNode node : nodes.values()) {
			for (MethodNode method : node.methods) {
				for (AbstractInsnNode insn : method.instructions) {
					String missing = null;
					if (insn instanceof FieldInsnNode f) {
						if (loaded.has(f.owner)) {
							if (!loaded.hasField(f.owner, f.name, f.desc)) missing = "field " + f.owner.replace('/', '.') + "." + f.name + " " + f.desc;
						} else if (own.contains(f.owner) && (f.getOpcode() == Opcodes.GETFIELD || f.getOpcode() == Opcodes.GETSTATIC)
								&& runs.contains(method) && !written.contains(f.owner + "." + f.name)) {
							return node.name.replace('/', '.') + "." + method.name + " reads " + f.owner.replace('/', '.') + "." + f.name
									+ ", which only code that never runs here writes (the losing build's own initialisation)";
						}
					} else if (insn instanceof MethodInsnNode m) {
						missing = missingMethod(loaded, m.owner, m.name, m.desc);
					} else if (insn instanceof InvokeDynamicInsnNode indy) {
						for (Object argument : indy.bsmArgs) {
							if (argument instanceof Handle handle && missing == null) {
								missing = handle.getTag() <= Opcodes.H_PUTSTATIC
										? (loaded.has(handle.getOwner()) && !loaded.hasField(handle.getOwner(), handle.getName(), handle.getDesc())
												? "field " + handle.getOwner().replace('/', '.') + "." + handle.getName() : null)
										: missingMethod(loaded, handle.getOwner(), handle.getName(), handle.getDesc());
							}
						}
					}
					if (missing != null) {
						return node.name.replace('/', '.') + "." + method.name + " uses " + missing + ", which the build that loaded "
								+ "does not have";
					}
				}
			}
		}
		return null;
	}

	/**
	 * Fabric Loader's lifecycle entrypoint contracts: the interface a key's entrypoint implements and the method the loader
	 * calls on it. A class declared under such a key runs that method only when Fabric Loader runs the build.
	 */
	private static final Map<String, String[]> LIFECYCLE_CONTRACTS = Map.of(
			"main", new String[] {"net/fabricmc/api/ModInitializer", "onInitialize()V"},
			"client", new String[] {"net/fabricmc/api/ClientModInitializer", "onInitializeClient()V"},
			"server", new String[] {"net/fabricmc/api/DedicatedServerModInitializer", "onInitializeServer()V"},
			"preLaunch", new String[] {"net/fabricmc/loader/api/entrypoint/PreLaunchEntrypoint", "onPreLaunch()V"});

	/**
	 * The losing build's lifecycle entry methods, {@code owner#name+desc}: what Fabric Loader would call on each class it
	 * declares under {@code main}, {@code client}, {@code server} or {@code preLaunch} — the contract's method, wherever the
	 * class or a super class of the build declares it — and, for a member reference ({@code Type::member}), that member
	 * ({@code owner#name}, any descriptor).
	 */
	static Set<String> lifecycleMethods(KernelModMetadata metadata, Classes losing) throws IOException {
		Set<String> out = new LinkedHashSet<>();
		for (Map.Entry<String, List<EntrypointDecl>> entry : metadata.getEntrypoints().entrySet()) {
			String[] contract = LIFECYCLE_CONTRACTS.get(entry.getKey());
			if (contract == null) continue;
			for (EntrypointDecl decl : entry.getValue()) {
				String value = decl.value();
				if (value.contains("::")) {
					out.add(value.substring(0, value.indexOf("::")).replace('.', '/') + "#" + value.substring(value.indexOf("::") + 2));
					continue;
				}
				for (String type = value.replace('.', '/'); type != null; ) {
					ClassNode node = losing.has(type) ? losing.node(type) : null;
					if (node == null) break;
					for (MethodNode method : node.methods) {
						if ((method.name + method.desc).equals(contract[1]) && (method.access & Opcodes.ACC_ABSTRACT) == 0) out.add(node.name + "#" + contract[1]);
					}
					type = node.superName;
				}
			}
		}
		return out;
	}

	/**
	 * The methods of {@code nodes} (the losing-build-only classes the entrypoint reaches) that can run when a reader asks
	 * for the entrypoint: every method of its class {@code start} (Fabric Loader instantiates it, and the reader may call
	 * any of its methods) — or, for a member reference, that member — and what they reach in turn: a call or method handle
	 * (a virtual one: any of these classes' methods of that name and descriptor), the static initialiser of a class whose
	 * static member they use or that they instantiate, and every instance method of a class they instantiate (the object
	 * may be handed to code that calls them). Never a lifecycle entry method ({@code lifecycle}) but through a call.
	 */
	static Set<MethodNode> reachable(Map<String, ClassNode> nodes, String start, String member, Set<String> lifecycle) {
		Set<MethodNode> runs = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		Deque<MethodNode> pending = new ArrayDeque<>();
		Set<String> initialised = new LinkedHashSet<>(), instantiated = new LinkedHashSet<>();
		ClassNode entry = nodes.get(start);
		if (entry == null) return runs;
		initialise(nodes, start, initialised, pending);
		for (MethodNode method : entry.methods) {
			boolean lifecycleOnly = lifecycle.contains(start + "#" + method.name + method.desc) || lifecycle.contains(start + "#" + method.name);
			if (member != null ? method.name.equals(member) : !lifecycleOnly) pending.add(method);
		}
		if (member == null) instantiate(nodes, start, instantiated, initialised, pending, lifecycle);
		while (!pending.isEmpty()) {
			MethodNode method = pending.removeFirst();
			if (!runs.add(method) || method.instructions == null) continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call) {
					if (nodes.containsKey(call.owner) && (call.getOpcode() == Opcodes.INVOKESTATIC)) initialise(nodes, call.owner, initialised, pending);
					called(nodes, call.owner, call.name, call.desc, call.getOpcode() == Opcodes.INVOKEVIRTUAL || call.getOpcode() == Opcodes.INVOKEINTERFACE, pending);
				} else if (insn instanceof FieldInsnNode f && (f.getOpcode() == Opcodes.GETSTATIC || f.getOpcode() == Opcodes.PUTSTATIC)) {
					initialise(nodes, f.owner, initialised, pending);
				} else if (insn instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW) {
					instantiate(nodes, t.desc, instantiated, initialised, pending, lifecycle);
				} else if (insn instanceof InvokeDynamicInsnNode indy) {
					for (Object argument : indy.bsmArgs) {
						if (argument instanceof Handle h && h.getTag() > Opcodes.H_PUTSTATIC) {
							called(nodes, h.getOwner(), h.getName(), h.getDesc(), h.getTag() == Opcodes.H_INVOKEVIRTUAL || h.getTag() == Opcodes.H_INVOKEINTERFACE, pending);
							if (h.getTag() == Opcodes.H_NEWINVOKESPECIAL) instantiate(nodes, h.getOwner(), instantiated, initialised, pending, lifecycle);
						}
					}
				} else if (insn instanceof LdcInsnNode l && l.cst instanceof Handle h && h.getTag() > Opcodes.H_PUTSTATIC) {
					called(nodes, h.getOwner(), h.getName(), h.getDesc(), h.getTag() == Opcodes.H_INVOKEVIRTUAL || h.getTag() == Opcodes.H_INVOKEINTERFACE, pending);
				}
			}
		}
		return runs;
	}

	/** A call of {@code owner.name+desc}: the method it resolves to among {@code nodes}, or for a virtual call each of theirs with that name and descriptor. */
	private static void called(Map<String, ClassNode> nodes, String owner, String name, String desc, boolean virtual, Deque<MethodNode> pending) {
		if (virtual) {
			for (ClassNode node : nodes.values()) {
				for (MethodNode method : node.methods) if (method.name.equals(name) && method.desc.equals(desc)) pending.add(method);
			}
			return;
		}
		for (String type = owner; type != null && nodes.containsKey(type); type = nodes.get(type).superName) {
			for (MethodNode method : nodes.get(type).methods) {
				if (method.name.equals(name) && method.desc.equals(desc)) {
					pending.add(method);
					return;
				}
			}
		}
	}

	/** Class initialisation of {@code type} among {@code nodes}: its static initialiser and its super classes'. */
	private static void initialise(Map<String, ClassNode> nodes, String type, Set<String> initialised, Deque<MethodNode> pending) {
		for (String at = type; at != null && nodes.containsKey(at) && initialised.add(at); at = nodes.get(at).superName) {
			for (MethodNode method : nodes.get(at).methods) if (method.name.equals("<clinit>")) pending.add(method);
		}
	}

	/** An instance of {@code type} exists: it and its super classes are initialised, and their instance methods may be called. */
	private static void instantiate(Map<String, ClassNode> nodes, String type, Set<String> instantiated, Set<String> initialised,
			Deque<MethodNode> pending, Set<String> lifecycle) {
		initialise(nodes, type, initialised, pending);
		for (String at = type; at != null && nodes.containsKey(at) && instantiated.add(at); at = nodes.get(at).superName) {
			for (MethodNode method : nodes.get(at).methods) {
				if ((method.access & Opcodes.ACC_STATIC) != 0 || method.name.equals("<init>")) continue;
				if (lifecycle.contains(at + "#" + method.name + method.desc) || lifecycle.contains(at + "#" + method.name)) continue;
				pending.add(method);
			}
		}
	}

	private static String missingMethod(Classes loaded, String owner, String name, String desc) throws IOException {
		if (owner.startsWith("[") || !loaded.has(owner)) return null;
		return loaded.hasMethod(owner, name, desc) ? null : "method " + owner.replace('/', '.') + "." + name + desc;
	}

	/** Every class a class needs resolved to run: its supertypes and what its code names. */
	static Set<String> referencedClasses(ClassNode node) {
		Set<String> out = new LinkedHashSet<>();
		if (node.superName != null) out.add(node.superName);
		out.addAll(node.interfaces);
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof FieldInsnNode f) out.add(f.owner);
				else if (insn instanceof MethodInsnNode m) addType(out, m.owner);
				else if (insn instanceof TypeInsnNode t) addType(out, t.desc);
				else if (insn instanceof MultiANewArrayInsnNode a) addType(out, Type.getType(a.desc).getElementType().getInternalName());
				else if (insn instanceof LdcInsnNode l && l.cst instanceof Type t && t.getSort() == Type.OBJECT) out.add(t.getInternalName());
				else if (insn instanceof InvokeDynamicInsnNode indy) {
					for (Object argument : indy.bsmArgs) if (argument instanceof Handle h) out.add(h.getOwner());
				}
			}
		}
		return out;
	}

	private static void addType(Set<String> out, String internal) {
		if (internal.startsWith("[")) {
			Type element = Type.getType(internal).getElementType();
			if (element.getSort() == Type.OBJECT) out.add(element.getInternalName());
		} else {
			out.add(internal);
		}
	}

	private static KernelModMetadata fabricMetadata(Path jar) {
		if (jar == null || !Files.isRegularFile(jar)) return null;
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry("fabric.mod.json");
			if (entry == null) return null;
			try (InputStream in = zip.getInputStream(entry)) {
				return FabricModMetadataParser.read(in);
			}
		} catch (IOException | RuntimeException unreadable) {
			Caught.warn(unreadable, "[Forbric/DupeId] could not read the fabric.mod.json of %s; the entrypoints it declares "
					+ "are not offered", jar.getFileName());
			return null;
		}
	}

	/**
	 * One build's classes by internal name: the jar's own, and for the build that loaded also those of the jars it nests
	 * (a build may keep its shared half in a bundled jar, which is on the class path with it).
	 */
	static final class Classes implements AutoCloseable {
		private final ZipFile zip;
		private final Map<String, byte[]> nested = new HashMap<>();
		private final Map<String, ClassNode> nodes = new HashMap<>();

		Classes(Path jar) throws IOException {
			this(jar, false);
		}

		Classes(Path jar, boolean withNested) throws IOException {
			this.zip = new ZipFile(jar.toFile());
			if (!withNested) return;
			for (var entries = zip.entries(); entries.hasMoreElements();) {
				ZipEntry entry = entries.nextElement();
				String name = entry.getName();
				if (!name.endsWith(".jar") || !(name.startsWith("META-INF/jars/") || name.startsWith("META-INF/jarjar/"))) continue;
				try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(zip.getInputStream(entry))) {
					for (ZipEntry inner; (inner = in.getNextEntry()) != null;) {
						String innerName = inner.getName();
						if (innerName.endsWith(".class") && !innerName.startsWith("META-INF/")) {
							nested.putIfAbsent(innerName.substring(0, innerName.length() - ".class".length()), in.readAllBytes());
						}
					}
				}
			}
		}

		boolean has(String internalName) {
			return zip.getEntry(internalName + ".class") != null || nested.containsKey(internalName);
		}

		ClassNode node(String internalName) throws IOException {
			ClassNode cached = nodes.get(internalName);
			if (cached != null) return cached;
			ZipEntry entry = zip.getEntry(internalName + ".class");
			byte[] bytes;
			if (entry != null) {
				try (InputStream in = zip.getInputStream(entry)) {
					bytes = in.readAllBytes();
				}
			} else {
				bytes = nested.get(internalName);
				if (bytes == null) return null;
			}
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
			nodes.put(internalName, node);
			return node;
		}

		/**
		 * Declared on the class or a supertype: one this jar has is read, one of the JDK's is asked, and any other supertype
		 * (the game's, a library's) is not judged and counts as having it.
		 */
		boolean hasMethod(String owner, String name, String desc) throws IOException {
			Set<String> seen = new LinkedHashSet<>();
			Deque<String> queue = new ArrayDeque<>(List.of(owner));
			while (!queue.isEmpty()) {
				String type = queue.removeFirst();
				if (!seen.add(type)) continue;
				ClassNode node = node(type);
				if (node == null) {
					if (platformDeclares(type, name, desc, true)) return true;
					if (type.startsWith("java/")) continue;
					return true;
				}
				for (MethodNode method : node.methods) if (method.name.equals(name) && method.desc.equals(desc)) return true;
				if (name.equals("<init>") || name.equals("<clinit>")) return false;
				if (node.superName != null) queue.addLast(node.superName);
				queue.addAll(node.interfaces);
			}
			return false;
		}

		boolean hasField(String owner, String name, String desc) throws IOException {
			Set<String> seen = new LinkedHashSet<>();
			Deque<String> queue = new ArrayDeque<>(List.of(owner));
			while (!queue.isEmpty()) {
				String type = queue.removeFirst();
				if (!seen.add(type)) continue;
				ClassNode node = node(type);
				if (node == null) {
					if (platformDeclares(type, name, desc, false)) return true;
					if (type.startsWith("java/")) continue;
					return true;
				}
				for (FieldNode field : node.fields) if (field.name.equals(name) && field.desc.equals(desc)) return true;
				if (node.superName != null) queue.addLast(node.superName);
				queue.addAll(node.interfaces);
			}
			return false;
		}

		/** Whether a JDK type, or a supertype of it, declares the member. */
		private static boolean platformDeclares(String type, String name, String desc, boolean method) {
			if (!type.startsWith("java/")) return false;
			try {
				Deque<Class<?>> queue = new ArrayDeque<>(List.of(Class.forName(type.replace('/', '.'), false,
						ClassLoader.getPlatformClassLoader())));
				Set<Class<?>> seen = new LinkedHashSet<>();
				while (!queue.isEmpty()) {
					Class<?> owner = queue.removeFirst();
					if (!seen.add(owner)) continue;
					if (method && name.equals("<init>")) {
						for (var constructor : owner.getDeclaredConstructors()) {
							if (Type.getConstructorDescriptor(constructor).equals(desc)) return true;
						}
						return false;
					}
					if (method) {
						for (var declared : owner.getDeclaredMethods()) {
							if (declared.getName().equals(name) && Type.getMethodDescriptor(declared).equals(desc)) return true;
						}
					} else {
						for (var declared : owner.getDeclaredFields()) {
							if (declared.getName().equals(name) && Type.getDescriptor(declared.getType()).equals(desc)) return true;
						}
					}
					if (owner.getSuperclass() != null) queue.addLast(owner.getSuperclass());
					queue.addAll(List.of(owner.getInterfaces()));
				}
				return false;
			} catch (ClassNotFoundException | LinkageError absent) {
				Caught.expected(absent);
				return false;
			}
		}

		@Override
		public void close() throws IOException {
			zip.close();
		}
	}
}
