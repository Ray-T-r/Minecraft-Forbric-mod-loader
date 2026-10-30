/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.opentest4j.AssertionFailedError;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * Whether what a repair spliced into a class still links on the base it ran against: every member and type reference
 * an edit ADDED must resolve among the classes the game would define.
 *
 * <p>The anchor ledger cannot see this. A repair that lands is a hit whatever it wrote, and the two half-applies the
 * genuine NeoForge base was first measured with were both hits: {@code UntrackedFluidEyeQueryInjector} called
 * {@code EntityFluidInteraction.getTracker(TagKey)}, which only the merged base's MinecraftForge half declares, and
 * {@code ItemUseOnInjector} pointed {@code CommonHooks.onPlaceItemIntoWorld} at an {@code ItemStack} bridge that only
 * its stood-down {@code ItemStack} half adds. Each is a {@code NoSuchMethodError} the first time the path runs, and
 * nothing before then. This is the check Stage 2.0's whole-jar census ran (its "dangling references" column), cut down
 * to what a staged test needs: the classes before and after its chain, and the jars that make up the base.
 *
 * <p>Only what the edit introduced is judged — a reference the class already made is the base's own business — and it
 * is resolved the way the JVM would: the edited classes as the chain left them first, then the base's jars, then the
 * kernel's game side ({@code build/classes/java/runtime}, which the game loader defines beside the base), then this
 * JVM (the JDK and the boot side). A member is looked up through superclasses and interfaces. A reference into the
 * game's or the kernel's namespaces that nothing declares is dangling; one into a library none of those carries is not
 * judged, because this check does not have the launcher's library list.
 */
public final class ChainReferences {
	private static final List<String> JUDGED = List.of("net/minecraft/", "com/mojang/", "net/neoforged/", "net/minecraftforge/",
			"net/forbric/");
	private static final Path GAME_SIDE = Path.of("build/classes/java/runtime");

	private ChainReferences() {
	}

	/** NeoForge's own patched game for 26.2 — the base Stage 4 switches to — as the staged root carries it. */
	public static Path genuineBase() {
		return TestFixtures.stagedRoot().resolve("neoforge-patched/patched-mc-neoforge-26.2.jar").normalize();
	}

	/** The genuine base and the one carrier it runs with: the jar set a staged genuine-base test links against. */
	public static List<Path> genuineJars() {
		Path base = genuineBase();
		Path neo = TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar").normalize();
		TestFixtures.requireFiles(Fixture.STAGED, "the genuine NeoForge base and its carrier", base, neo);
		return List.of(base, neo);
	}

	/** {@code internalName}'s class file from the first of {@code jars} that has it; a missing class fails. */
	public static byte[] read(List<Path> jars, String internalName) {
		byte[] bytes = resource(jars, internalName + ".class");
		if (bytes == null) throw new AssertionFailedError("content drift: no jar of " + jars + " carries " + internalName);
		return bytes;
	}

	/**
	 * The references the edits in {@code after} introduced (relative to {@code before}, keyed by internal name) that
	 * resolve nowhere: one line per reference, {@code <class> <WHY> <M|F|T> <owner> <name> <descriptor>}. Empty when
	 * every one links.
	 */
	public static List<String> dangling(Map<String, byte[]> before, Map<String, byte[]> after, List<Path> jars) {
		Linker linker = new Linker(after, jars);
		List<String> out = new ArrayList<>();
		for (Map.Entry<String, byte[]> edited : after.entrySet()) {
			byte[] old = before.get(edited.getKey());
			if (old == edited.getValue()) continue;
			Set<String> added = references(edited.getValue());
			if (old != null) added.removeAll(references(old));
			for (String reference : added) {
				String why = linker.check(reference);
				if (why != null) out.add(edited.getKey() + " " + why + " " + reference);
			}
		}
		return out;
	}

	/** Fails, naming each one, when an edit introduced a reference that resolves nowhere. */
	public static void assertNoneDangling(Map<String, byte[]> before, Map<String, byte[]> after, List<Path> jars) {
		List<String> dangling = dangling(before, after, jars);
		if (!dangling.isEmpty()) {
			throw new AssertionFailedError("the chain spliced in " + dangling.size() + " reference(s) the base does not declare "
					+ "(each a NoSuchMethodError/NoSuchFieldError/NoClassDefFoundError the first time it runs):\n  "
					+ String.join("\n  ", dangling));
		}
	}

	/** Every member and type a class file's code refers to: {@code M owner name desc}, {@code F ...}, {@code T owner}. */
	static Set<String> references(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
		Set<String> out = new TreeSet<>();
		for (MethodNode method : node.methods) {
			if (method.instructions == null) continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call) {
					if (!call.owner.startsWith("[")) out.add("M " + call.owner + " " + call.name + " " + call.desc);
				} else if (insn instanceof FieldInsnNode field) {
					out.add("F " + field.owner + " " + field.name + " " + field.desc);
				} else if (insn instanceof TypeInsnNode type) {
					Type t = Type.getObjectType(type.desc);
					while (t.getSort() == Type.ARRAY) t = t.getElementType();
					if (t.getSort() == Type.OBJECT) out.add("T " + t.getInternalName());
				} else if (insn instanceof InvokeDynamicInsnNode indy) {
					for (Object argument : indy.bsmArgs) {
						if (argument instanceof Handle handle) {
							out.add((handle.getTag() <= org.objectweb.asm.Opcodes.H_PUTSTATIC ? "F " : "M ") + handle.getOwner() + " "
									+ handle.getName() + " " + handle.getDesc());
						}
					}
				}
			}
		}
		return out;
	}

	private static byte[] resource(List<Path> jars, String entry) {
		for (Path jar : jars) {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				var found = zip.getEntry(entry);
				if (found == null) continue;
				try (InputStream in = zip.getInputStream(found)) {
					return in.readAllBytes();
				}
			} catch (IOException unreadable) {
				throw new UncheckedIOException("reading " + entry + " from " + jar, unreadable);
			}
		}
		return null;
	}

	/** Resolves against the chain's output first, as the game would define it. */
	private static final class Linker {
		private final Map<String, byte[]> edited;
		private final List<Path> jars;
		private final Map<String, ClassNode> cache = new HashMap<>();
		private final Set<String> absent = new HashSet<>();

		Linker(Map<String, byte[]> edited, List<Path> jars) {
			this.edited = edited;
			this.jars = jars;
		}

		/** Null when {@code reference} links or is outside what this check judges; otherwise why it does not. */
		String check(String reference) {
			String[] part = reference.split(" ");
			String owner = part[1];
			if (part[0].equals("T")) return node(owner) == null && platform(owner) == null && judged(owner) ? "TYPE_ABSENT" : null;
			if (node(owner) == null && platform(owner) == null) return judged(owner) ? "OWNER_ABSENT" : null;
			int found = resolve(owner, part[0].equals("M"), part[2], part[3], new HashSet<>());
			return found == NOT_FOUND ? "MEMBER_ABSENT" : null;
		}

		private static final int FOUND = 1, NOT_FOUND = 0, UNKNOWN = 2;

		private int resolve(String owner, boolean method, String name, String desc, Set<String> visited) {
			if (owner == null || !visited.add(owner)) return NOT_FOUND;
			ClassNode node = node(owner);
			if (node == null) {
				Class<?> type = platform(owner);
				if (type == null) return judged(owner) ? NOT_FOUND : UNKNOWN;
				return reflects(type, method, name, desc) ? FOUND : NOT_FOUND;
			}
			if (method) {
				for (MethodNode m : node.methods) if (m.name.equals(name) && m.desc.equals(desc)) return FOUND;
				if (name.equals("<init>")) return NOT_FOUND;
			} else {
				for (FieldNode f : node.fields) if (f.name.equals(name) && f.desc.equals(desc)) return FOUND;
			}
			boolean unknown = false;
			int fromSuper = resolve(node.superName, method, name, desc, visited);
			if (fromSuper == FOUND) return FOUND;
			unknown |= fromSuper == UNKNOWN;
			for (String itf : node.interfaces) {
				int fromInterface = resolve(itf, method, name, desc, visited);
				if (fromInterface == FOUND) return FOUND;
				unknown |= fromInterface == UNKNOWN;
			}
			return unknown ? UNKNOWN : NOT_FOUND;
		}

		private static boolean reflects(Class<?> type, boolean method, String name, String desc) {
			for (Class<?> k = type; k != null; k = k.getSuperclass()) {
				if (method) {
					for (Method m : k.getDeclaredMethods()) if (m.getName().equals(name) && Type.getMethodDescriptor(m).equals(desc)) return true;
					if (name.equals("<init>")) {
						for (var c : k.getDeclaredConstructors()) if (Type.getConstructorDescriptor(c).equals(desc)) return true;
						return false;
					}
				} else {
					for (Field f : k.getDeclaredFields()) if (f.getName().equals(name) && Type.getDescriptor(f.getType()).equals(desc)) return true;
				}
			}
			if (method) {
				for (Method m : type.getMethods()) if (m.getName().equals(name) && Type.getMethodDescriptor(m).equals(desc)) return true;
			}
			return false;
		}

		private ClassNode node(String internal) {
			if (cache.containsKey(internal)) return cache.get(internal);
			if (absent.contains(internal)) return null;
			byte[] bytes = edited.get(internal);
			if (bytes == null) bytes = resource(jars, internal + ".class");
			if (bytes == null) bytes = gameSide(internal);
			if (bytes == null) {
				absent.add(internal);
				return null;
			}
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
			cache.put(internal, node);
			return node;
		}

		/** The kernel's game side as compiled beside these tests; the game loader defines it next to the base. */
		private static byte[] gameSide(String internal) {
			Path file = GAME_SIDE.resolve(internal + ".class");
			try {
				return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
			} catch (IOException unreadable) {
				throw new UncheckedIOException(unreadable);
			}
		}

		/** A class this JVM has without the base — the JDK or the boot side — never a game class served from elsewhere. */
		private static Class<?> platform(String internal) {
			if (judged(internal) && !internal.startsWith("net/forbric/")) return null;
			try {
				return Class.forName(internal.replace('/', '.'), false, ChainReferences.class.getClassLoader());
			} catch (ClassNotFoundException | LinkageError notHere) {
				return null;
			}
		}

		private static boolean judged(String internal) {
			for (String prefix : JUDGED) if (internal.startsWith(prefix)) return true;
			return false;
		}
	}
}
