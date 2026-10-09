/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.List;
import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.boot.CrossEcosystemDeclarations;
import net.forbric.kernel.util.ByteScan;
import net.forbric.kernel.util.ForbricLog;

/**
 * Lets a class that reads mods' declared properties out of a Forge-family {@code ModList} meet the Fabric mods that
 * declare the same things.
 *
 * <p>A library built for a Forge family finds the mods that integrate with it by enumerating its loader's
 * {@code ModList} and reading each {@code IModInfo.getModProperties()} — Sodium's config users, Jade's metadata,
 * ResourcefulLib's bundled packs, LibJF's flags and entry points, fzzy_config's scopes. A Fabric mod is in no
 * Forge-family {@code ModList}, so on this loader every such walk stops at its own family, and the Fabric mod's
 * declaration (see {@code CrossEcosystemDeclarations}) is read by nobody.
 *
 * <h2>The trigger</h2>
 *
 * <p>A class, whatever it is called, that BOTH reads {@code getModProperties()} off that family's {@code IModInfo}
 * (a call, or a method reference) AND asks that family's {@code ModList} which mods there are or which one an id is.
 * In such a class each of those {@code ModList} calls — {@code getMods}, {@code getSortedMods}/{@code getLoadedMods},
 * {@code getModContainerById}, {@code getModFileById}, {@code forEachModContainer}, {@code forEachModInOrder},
 * {@code applyForEachModContainer}, again as a call or a method reference — goes through the game side's
 * {@code KernelDeclarationReaders} (NeoForge) or {@code KernelForgeDeclarationReaders} (MinecraftForge), which
 * answers what {@code ModList} answers and then the declaring Fabric mods. The by-id lookups are included because a
 * reader that found a mod in the list resolves it again by id (Sodium reads the name and version for the options page
 * that way), and the list must not contradict itself.
 *
 * <p>The edit swaps one call for another with the same stack effect — the receiver becomes the first argument — so it
 * does not care how the surrounding code is written: a loop or a stream, a lambda or a helper method in the same class,
 * a value kept in a local or passed straight on.
 *
 * <h2>How each key is read</h2>
 *
 * <p>Every reader of {@code getModProperties()} — also one that only reads {@code LoadingModList} — is also asked how it
 * reads each namespaced key ({@link #keyReads}): whether the value goes into a {@code String} test or cast (a NAME), or
 * into a test or cast to anything else. That is recorded with {@code CrossEcosystemDeclarations.noteRead} while the class
 * is being defined, so before any of its code runs, and it decides whether the class names a Fabric mod declares under an
 * entrypoint key are offered in that key's place.
 *
 * <h2>What is deliberately left alone</h2>
 *
 * <p>A class that enumerates {@code ModList} without reading properties — a mod list screen, a crash report section,
 * the handshake, a version checker — keeps the native answer: it is asking what the family LOADED, and a Fabric mod
 * is not that. Measured on the merged base and both carriers: none of their {@code ModList} readers reads properties,
 * so no platform class is touched. A reader whose walk and whose property read sit in two different classes is not
 * reached either; on the packs measured, every reader has both in one class.
 */
public final class DeclarationReaderModListInjector implements ClassTransformer {
	/** One family's names, and how it calls its {@code ModList}: NeoForge's is an object, MinecraftForge's static. */
	record Family(Ecosystem ecosystem, String modList, List<String> infoTypes, String hook, boolean staticList,
			Map<String, String> members) {
	}

	private static final String PROPERTIES = "getModProperties";
	private static final String PROPERTIES_DESC = "()Ljava/util/Map;";

	/** Member name → its argument list. The return type is the caller's own; the hook declares the same one. */
	private static final Map<String, String> SHARED_MEMBERS = Map.of(
			"getMods", "()",
			"getModContainerById", "(Ljava/lang/String;)",
			"getModFileById", "(Ljava/lang/String;)",
			"forEachModContainer", "(Ljava/util/function/BiConsumer;)",
			"forEachModInOrder", "(Ljava/util/function/Consumer;)",
			"applyForEachModContainer", "(Ljava/util/function/Function;)");

	static final List<Family> FAMILIES = List.of(
			family(Ecosystem.NEOFORGE, "net/forbric/kernel/runtime/KernelDeclarationReaders", false, "getSortedMods"),
			family(Ecosystem.FORGE, "net/forbric/kernel/runtime/KernelForgeDeclarationReaders", true, "getLoadedMods"));

	private static Family family(Ecosystem ecosystem, String hook, boolean staticList, String containerList) {
		Map<String, String> members = new java.util.HashMap<>(SHARED_MEMBERS);
		members.put(containerList, "()");
		return new Family(ecosystem, ForeignType.MOD_LIST.internal(ecosystem),
				List.of(ForeignType.MOD_INFO_SPI.internal(ecosystem), ForeignType.MOD_INFO.internal(ecosystem)), hook,
				staticList, Map.copyOf(members));
	}

	private static final byte[][] NEEDLES = {
			ByteScan.poolEntry(PROPERTIES),
			ByteScan.poolEntry(FAMILIES.get(0).modList()),
			ByteScan.poolEntry(FAMILIES.get(1).modList())};

	@Override
	public String name() {
		return "forbric-declaration-reader-modlist";
	}

	@Override
	public AnchorSet anchors() {
		return AnchorSet.scanned("any class, whatever its name, that reads per-mod declared properties out of a "
				+ "Forge-family ModList");
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		if (className != null && className.startsWith("net.forbric.")) return classBytes;
		boolean[] names = ByteScan.constantPoolNames(classBytes, NEEDLES);
		if (!names[0]) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		int rewritten = 0;
		boolean reader = false;
		for (Family family : FAMILIES) {
			if (!readsProperties(node, family)) continue;
			reader = true;
			if (names[1] || names[2]) rewritten += redirect(node, family);
		}
		// Recorded for every reader, also one that reads LoadingModList and never ModList, and before it can run.
		if (reader) {
			String readerName = className == null ? node.name : className;
			keyReads(node).forEach((key, reads) -> reads.forEach(read ->
					CrossEcosystemDeclarations.noteRead(key, read, readerName)));
		}
		if (rewritten == 0) return classBytes;

		ForbricLog.info("[Forbric/Declarations] %s reads mods' declared properties out of ModList — its %d ModList "
				+ "call(s) now also meet the Fabric mods that declare them in fabric.mod.json", className, rewritten);
		// The stack effect of every edit is unchanged, so the frames and max stack read in are still right.
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** Whether any method of {@code node} reads {@code getModProperties()} off this family's mod info. */
	static boolean readsProperties(ClassNode node, Family family) {
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call) {
					if (isProperties(call.owner, call.name, call.desc, family)) return true;
				} else if (insn instanceof InvokeDynamicInsnNode indy && indy.bsmArgs != null) {
					for (Object arg : indy.bsmArgs) {
						if (arg instanceof Handle handle && isProperties(handle.getOwner(), handle.getName(), handle.getDesc(), family)) {
							return true;
						}
					}
				}
			}
		}
		return false;
	}

	private static boolean isProperties(String owner, String name, String desc, Family family) {
		return PROPERTIES.equals(name) && PROPERTIES_DESC.equals(desc) && family.infoTypes().contains(owner);
	}

	/** Sends this family's mod-list questions in {@code node} through its hook; returns how many were rewritten. */
	private static int redirect(ClassNode node, Family family) {
		int count = 0;
		int listCall = family.staticList() ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL;
		int listHandle = family.staticList() ? Opcodes.H_INVOKESTATIC : Opcodes.H_INVOKEVIRTUAL;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call) {
					if (call.getOpcode() != listCall || !isMember(call.owner, call.name, call.desc, family)) continue;
					call.setOpcode(Opcodes.INVOKESTATIC);
					call.owner = family.hook();
					call.desc = hookDesc(call.desc, family);
					call.itf = false;
					count++;
				} else if (insn instanceof InvokeDynamicInsnNode indy && indy.bsmArgs != null) {
					for (int i = 0; i < indy.bsmArgs.length; i++) {
						if (!(indy.bsmArgs[i] instanceof Handle handle) || handle.getTag() != listHandle
								|| !isMember(handle.getOwner(), handle.getName(), handle.getDesc(), family)) {
							continue;
						}
						indy.bsmArgs[i] = new Handle(Opcodes.H_INVOKESTATIC, family.hook(), handle.getName(),
								hookDesc(handle.getDesc(), family), false);
						count++;
					}
				}
			}
		}
		return count;
	}

	private static boolean isMember(String owner, String name, String desc, Family family) {
		if (!family.modList().equals(owner)) return false;
		String arguments = family.members().get(name);
		return arguments != null && desc.startsWith(arguments);
	}

	/** The hook's descriptor: the {@code ModList} receiver, when there is one, becomes the first argument. */
	static String hookDesc(String desc, Family family) {
		return family.staticList() ? desc : "(L" + family.modList() + ";" + desc.substring(1);
	}

	// ---- how a reader reads each key --------------------------------------------------------------------------------

	/**
	 * For every {@code Map.get}/{@code Map.getOrDefault} in {@code node} whose key is a namespaced string constant, how
	 * the value it returns is used: {@code instanceof}/{@code checkcast String} is a read of a NAME, a type test or cast
	 * to any other type (but {@code Object}) is a read of something else. The value is followed through locals,
	 * {@code DUP} and {@code checkcast Object}; anything else it flows into (a call, a field, a lambda's captures) says
	 * nothing about its type and is not counted. Any map is counted, not just a {@code getModProperties()} result: a
	 * reader commonly hands that map to a helper, and a key over-counted as "something else" only means a Fabric
	 * entrypoint name is not offered under it — the safe direction.
	 */
	static Map<String, java.util.EnumSet<CrossEcosystemDeclarations.Read>> keyReads(ClassNode node) {
		Map<String, java.util.EnumSet<CrossEcosystemDeclarations.Read>> reads = new java.util.TreeMap<>();
		for (MethodNode method : node.methods) {
			if (!hasLookup(method)) continue;
			org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.SourceValue>[] frames;
			try {
				frames = new org.objectweb.asm.tree.analysis.Analyzer<>(
						new org.objectweb.asm.tree.analysis.SourceInterpreter()).analyze(node.name, method);
			} catch (org.objectweb.asm.tree.analysis.AnalyzerException | RuntimeException unreadable) {
				continue;
			}
			AbstractInsnNode[] insns = method.instructions.toArray();
			Map<AbstractInsnNode, List<AbstractInsnNode>> users = new java.util.HashMap<>();
			Map<AbstractInsnNode, List<AbstractInsnNode>> loads = new java.util.HashMap<>();
			for (int i = 0; i < insns.length; i++) {
				var frame = frames[i];
				if (frame == null) continue;
				int op = insns[i].getOpcode();
				if (op == Opcodes.CHECKCAST || op == Opcodes.INSTANCEOF || op == Opcodes.ASTORE || op == Opcodes.DUP) {
					for (AbstractInsnNode source : frame.getStack(frame.getStackSize() - 1).insns) {
						users.computeIfAbsent(source, k -> new java.util.ArrayList<>()).add(insns[i]);
					}
				} else if (op == Opcodes.ALOAD) {
					for (AbstractInsnNode store : frame.getLocal(((org.objectweb.asm.tree.VarInsnNode) insns[i]).var).insns) {
						loads.computeIfAbsent(store, k -> new java.util.ArrayList<>()).add(insns[i]);
					}
				}
			}
			for (int i = 0; i < insns.length; i++) {
				if (!(insns[i] instanceof MethodInsnNode call) || !isLookup(call) || frames[i] == null) continue;
				var frame = frames[i];
				int keyDepth = call.name.equals("get") ? 1 : 2;
				var key = frame.getStack(frame.getStackSize() - keyDepth);
				if (key.insns.size() != 1 || !(key.insns.iterator().next() instanceof org.objectweb.asm.tree.LdcInsnNode ldc)
						|| !(ldc.cst instanceof String name) || !CrossEcosystemDeclarations.namespaced(name)) {
					continue;
				}
				java.util.EnumSet<CrossEcosystemDeclarations.Read> found = usesOf(call, users, loads);
				if (!found.isEmpty()) {
					reads.computeIfAbsent(name, k -> java.util.EnumSet.noneOf(CrossEcosystemDeclarations.Read.class)).addAll(found);
				}
			}
		}
		return reads;
	}

	private static boolean hasLookup(MethodNode method) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && isLookup(call)) return true;
		}
		return false;
	}

	private static boolean isLookup(MethodInsnNode call) {
		return "java/util/Map".equals(call.owner)
				&& ("get".equals(call.name) && "(Ljava/lang/Object;)Ljava/lang/Object;".equals(call.desc)
						|| "getOrDefault".equals(call.name)
								&& "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;".equals(call.desc));
	}

	private static java.util.EnumSet<CrossEcosystemDeclarations.Read> usesOf(AbstractInsnNode value,
			Map<AbstractInsnNode, List<AbstractInsnNode>> users, Map<AbstractInsnNode, List<AbstractInsnNode>> loads) {
		java.util.EnumSet<CrossEcosystemDeclarations.Read> found = java.util.EnumSet.noneOf(CrossEcosystemDeclarations.Read.class);
		java.util.ArrayDeque<AbstractInsnNode> pending = new java.util.ArrayDeque<>(List.of(value));
		java.util.Set<AbstractInsnNode> seen = new java.util.HashSet<>();
		while (!pending.isEmpty()) {
			AbstractInsnNode current = pending.poll();
			if (!seen.add(current)) continue;
			for (AbstractInsnNode user : users.getOrDefault(current, List.of())) {
				switch (user.getOpcode()) {
					case Opcodes.CHECKCAST, Opcodes.INSTANCEOF -> {
						String type = ((org.objectweb.asm.tree.TypeInsnNode) user).desc;
						if ("java/lang/String".equals(type)) found.add(CrossEcosystemDeclarations.Read.NAME);
						else if ("java/lang/Object".equals(type)) pending.add(user);
						else found.add(CrossEcosystemDeclarations.Read.OTHER);
					}
					case Opcodes.DUP -> pending.add(user);
					case Opcodes.ASTORE -> pending.addAll(loads.getOrDefault(user, List.of()));
					default -> { }
				}
			}
		}
		return found;
	}
}
