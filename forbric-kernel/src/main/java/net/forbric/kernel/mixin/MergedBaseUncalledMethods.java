/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ForbricLog;

/**
 * Methods of the merged base that the game a mod was built against calls, and that nothing in the merged game calls —
 * the shipped table {@code uncalled-methods.txt}, which UncalledMethodCensusTest re-derives from the staged jars and pins.
 *
 * <p>Better Mount HUD redirects {@code MultiPlayerGameMode.hasExperience()} inside {@code Hud.extractHotbarAndDecorations}
 * to hide the XP number over the jump bar. The merged base still has that method, with that call in it, so the anchor
 * resolves and the redirect attaches — but vanilla calls the method from {@code Hud.extractRenderState}, NeoForge's HUD
 * layers replaced that call, and no class in the merged jar, the carriers or the kernel calls it any more. The redirect
 * never runs; resolution alone called it FIT.
 *
 * <p>A row says: {@code owner#method} is declared in the merged base; no instruction or method handle in the merged jar,
 * {@code forge-runtime-interop.jar}, {@code neoforge-runtime.jar} or the kernel's own classes names its name and
 * descriptor (whatever the owner, so a virtual call through any type counts), no supertype declares it (a call through
 * that type, from anywhere, would reach it), and no string constant in that code spells its name (a call the kernel
 * synthesizes, or a reflective lookup); and the listed ecosystems' own jars — vanilla for Fabric, the patched game plus
 * its runtime for MinecraftForge and NeoForge — call it from the listed methods. A method no reference calls either is
 * not here: a mod targeting it expects a caller the census cannot see.
 *
 * <p>What the census cannot see is the installed mods, and they do call some of these: fabric-resource-loader, Iris and
 * Collective all call {@code Language.loadFromJson(InputStream, BiConsumer)}, the stub the merged game no longer does, so
 * owo's mixin there runs. {@link #scanGuests} therefore reads every installed jar's constant pool at boot, beside the
 * other guest audits and before Mixin prepares a config, and a row whose name and descriptor any of them references is
 * live. The row is also re-checked against the live bytes: a transform that restores a call in the method's own class or
 * in a listed caller makes it live again. {@code -Dforbric.mixinFit.liveness=off} reads every row as live.
 */
public final class MergedBaseUncalledMethods {
	/** The shipped table; UncalledMethodCensusTest pins it to the staged artifacts. */
	static final String TABLE = "/net/forbric/kernel/mixin/uncalled-methods.txt";

	/**
	 * @param owner      the declaring class (internal name)
	 * @param method     {@code name + descriptor}
	 * @param ecosystems whose own game calls it — the mods that expect it to run
	 * @param callers    where those games call it from, {@code class#name+descriptor}
	 */
	record Row(String owner, String method, Set<Ecosystem> ecosystems, List<String> callers) {
		/** {@code <class>#<method> | <ecosystems> | <caller> <caller> …}, as the census writes it; null when not that. */
		static Row parse(String line) {
			String[] columns = line.split(" \\| ");
			if (columns.length != 3) return null;
			int hash = columns[0].indexOf('#');
			if (hash <= 0 || columns[0].indexOf('(', hash) < 0) return null;
			try {
				Set<Ecosystem> ecosystems = EnumSet.noneOf(Ecosystem.class);
				for (String e : columns[1].trim().split(",")) ecosystems.add(Ecosystem.valueOf(e.trim()));
				List<String> callers = List.of(columns[2].trim().split(" "));
				for (String caller : callers) if (caller.indexOf('#') <= 0) return null;
				return new Row(columns[0].substring(0, hash).trim(), columns[0].substring(hash + 1).trim(),
						Set.copyOf(ecosystems), callers);
			} catch (IllegalArgumentException unknown) {
				return null;
			}
		}
	}

	private static volatile List<Row> rows;
	private static volatile Set<String> keys;
	private static volatile Set<String> owners;
	/** Table rows ({@code name + descriptor}) some installed mod's code references; see {@link #scanGuests}. */
	private static final Set<String> GUEST_CALLED = ConcurrentHashMap.newKeySet();

	private MergedBaseUncalledMethods() {
	}

	static List<Row> rows() {
		List<Row> loaded = rows;
		if (loaded != null) return loaded;
		List<Row> read = new ArrayList<>();
		try (InputStream in = MergedBaseUncalledMethods.class.getResourceAsStream(TABLE)) {
			if (in != null) {
				for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
					if (line.isBlank() || line.startsWith("#")) continue;
					Row row = Row.parse(line.trim());
					if (row != null) read.add(row);
				}
			}
		} catch (IOException unreadable) {
			ForbricLog.warn("[Forbric/Mixin] could not read %s; every merged-base method reads as called", TABLE);
		}
		rows = List.copyOf(read);
		return rows;
	}

	/** Whether any row is declared by {@code owner} — the cheap question asked of every class before a closer look. */
	static boolean lists(String owner) {
		Set<String> loaded = owners;
		if (loaded == null) {
			Set<String> read = new HashSet<>();
			for (Row row : rows()) read.add(row.owner());
			owners = loaded = Set.copyOf(read);
		}
		return owner != null && loaded.contains(owner);
	}

	/** Whether any row has this {@code name + descriptor}, whatever its owner. */
	static boolean lists(String name, String desc) {
		return keys().contains(name + desc);
	}

	/** Every row's {@code name + descriptor}. */
	private static Set<String> keys() {
		Set<String> loaded = keys;
		if (loaded != null) return loaded;
		Set<String> read = new HashSet<>();
		for (Row row : rows()) read.add(row.method());
		keys = Set.copyOf(read);
		return keys;
	}

	/**
	 * Records which rows the code of {@code jars} (the installed mods) references by name and descriptor, whatever the
	 * owner: a mod calling one makes it run. Reads only each class's constant pool — a call, and a method handle, both name
	 * the method through a {@code CONSTANT_NameAndType} — so it costs one pass over the entries the other guest audits read.
	 */
	public static void scanGuests(List<Path> jars) {
		if (!MixinFit.asksLiveness() || jars == null) return;
		for (Path jar : jars) {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				for (ZipEntry entry : zip.stream().toList()) {
					if (!entry.getName().endsWith(".class")) continue;
					try (InputStream in = zip.getInputStream(entry)) {
						noteGuest(in.readAllBytes());
					}
				}
			} catch (IOException | RuntimeException unreadable) {
				ForbricLog.debug("[Forbric/Mixin] could not read %s for calls into uncalled merged-base methods: %s",
						jar.getFileName(), unreadable);
			}
		}
		if (!GUEST_CALLED.isEmpty()) {
			ForbricLog.info("[Forbric/Mixin] installed mods call %d of the %d merged-base method(s) nothing in the merged game "
					+ "calls; an injector there counts as running", GUEST_CALLED.size(), keys().size());
		}
	}

	/** {@link #scanGuests} for one class's bytes. */
	static void noteGuest(byte[] classBytes) {
		ClassReader reader;
		try {
			reader = new ClassReader(classBytes);
		} catch (RuntimeException notAClass) {
			return;
		}
		Set<String> wanted = keys();
		char[] buf = new char[reader.getMaxStringLength()];
		for (int i = 1; i < reader.getItemCount(); i++) {
			int offset = reader.getItem(i);
			if (offset == 0 || reader.readByte(offset - 1) != 12) continue;    // CONSTANT_NameAndType
			String key = reader.readUTF8(offset, buf) + reader.readUTF8(offset + 2, buf);
			if (wanted.contains(key)) GUEST_CALLED.add(key);
		}
	}

	/** Whether an installed mod references {@code nameAndDesc}; test and census seam. */
	static boolean calledByGuest(String nameAndDesc) {
		return GUEST_CALLED.contains(nameAndDesc);
	}

	/** Test and census seam: forgets what the installed mods reference. */
	static void forgetGuests() {
		GUEST_CALLED.clear();
	}

	/** The row for {@code owner#method} whose ecosystems include {@code ecosystem}; null when none, or the ecosystem is unknown. */
	static Row find(String owner, String method, Ecosystem ecosystem) {
		if (ecosystem == null || owner == null || method == null) return null;
		for (Row row : rows()) {
			if (row.owner().equals(owner) && row.method().equals(method) && row.ecosystems().contains(ecosystem)) return row;
		}
		return null;
	}

	/**
	 * Why {@code method}, declared by {@code owner}, never runs for a mod of {@code ecosystem}; null when it may. The row
	 * is re-checked against {@code owner} as given and against each listed caller as {@code resolver} serves it
	 * (internal name + {@code .class} → bytes, or null): a call there makes it live.
	 */
	static String neverRuns(ClassNode owner, MethodNode method, Ecosystem ecosystem, Function<String, byte[]> resolver) {
		Row row = liveRow(owner, method, ecosystem);
		if (row == null) return null;
		if (resolver != null) {
			for (String caller : row.callers()) {
				int hash = caller.indexOf('#');
				String callerOwner = caller.substring(0, hash);
				if (callerOwner.equals(owner.name)) continue;    // already checked, in the bytes the caller handed us
				byte[] bytes = resolver.apply(callerOwner + ".class");
				if (bytes == null) continue;
				ClassNode node = new ClassNode();
				new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
				if (calledIn(node, method)) return null;
			}
		}
		return describe(row);
	}

	/** {@link #neverRuns(ClassNode, MethodNode, Ecosystem, Function)} checked against {@code owner} alone — the final class. */
	static String neverRuns(ClassNode owner, MethodNode method, Ecosystem ecosystem) {
		return neverRuns(owner, method, ecosystem, null);
	}

	private static Row liveRow(ClassNode owner, MethodNode method, Ecosystem ecosystem) {
		if (!MixinFit.asksLiveness() || owner == null || method == null) return null;
		Row row = find(owner.name, method.name + method.desc, ecosystem);
		if (row == null || GUEST_CALLED.contains(row.method()) || calledIn(owner, method)) return null;
		return row;
	}

	/** Whether any method of {@code node} but {@code method} itself calls or takes a handle to {@code method}'s name and descriptor. */
	static boolean calledIn(ClassNode node, MethodNode method) {
		if (node.methods == null) return false;
		for (MethodNode m : node.methods) {
			if (m.instructions == null || (m.name.equals(method.name) && m.desc.equals(method.desc))) continue;
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof MethodInsnNode call) {
					if (call.name.equals(method.name) && call.desc.equals(method.desc)) return true;
				} else if (insn instanceof InvokeDynamicInsnNode indy) {
					for (Object argument : indy.bsmArgs) if (argument instanceof Handle h && names(h, method)) return true;
				} else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Handle h && names(h, method)) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean names(Handle handle, MethodNode method) {
		return handle.getName().equals(method.name) && handle.getDesc().equals(method.desc);
	}

	/** "nothing in the merged game calls it; vanilla and MinecraftForge call it from Hud.extractRenderState" */
	static String describe(Row row) {
		List<String> games = new ArrayList<>();
		for (Ecosystem e : Ecosystem.values()) {
			if (row.ecosystems().contains(e)) games.add(e == Ecosystem.FABRIC ? "vanilla" : e.displayName());
		}
		List<String> from = new ArrayList<>();
		for (String caller : row.callers()) {
			int hash = caller.indexOf('#');
			int paren = caller.indexOf('(', hash);
			String owner = caller.substring(caller.lastIndexOf('/', hash) + 1, hash);
			String name = caller.substring(hash + 1, paren < 0 ? caller.length() : paren);
			String simple = owner + "." + name;
			if (!from.contains(simple)) from.add(simple);
		}
		return "nothing in the merged game calls it; " + String.join(" and ", games) + (games.size() == 1 ? " calls" : " call")
				+ " it from " + String.join(", ", from);
	}
}
