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
import net.forbric.kernel.util.Caught;
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
 * <p>A row says: {@code owner#method} is declared in the merged base; every instruction or method handle in the merged jar,
 * {@code forge-runtime-interop.jar}, {@code neoforge-runtime.jar} or the kernel's own classes that names its name and
 * descriptor (whatever the owner, so a virtual call through any type counts) is in a method that is itself uncalled, or
 * in code the kernel silences (the leaves MinecraftForge's builder puts in its vanilla HUD tree, each replaced before the
 * tree bakes) — a fixpoint, listed as the row's last column; no supertype declares it (a call through that type, from
 * anywhere, would reach it), and no string constant in that code spells its name (a call the kernel synthesizes, or a
 * reflective lookup). A PRIVATE method is reached only through its own class, so only a call or handle naming that class
 * counts, and only a string a call can be made from (beside its descriptor, inside one owner-qualified target, or in a
 * class that looks methods up reflectively and names the class): {@code Level.removeBlock(BlockPos, boolean)}, called
 * everywhere, had hidden that nothing calls {@code ServerPlayerGameMode}'s private method of the same name and
 * descriptor, and Physics Mod's collapse hook bound there read as running. An override's super call of itself runs only
 * when the override runs, so a method named by nothing else is uncalled unless a supertype outside the merged game
 * declares it. And the listed ecosystems' own jars — vanilla for Fabric, the patched game plus its runtime for
 * MinecraftForge and NeoForge — call it from the listed methods, each of which runs there (the same fixpoint, by the same
 * rules, over the reference), or is one a mod may call and the merged game no longer makes the call from. A method no
 * reference runs either is not here: a mod targeting it is promised nothing the merged game breaks. NeoForge drew the four
 * pieces of {@code Hud.extractPlayerHealth} as layers of their own: vanilla still calls it only from the uncalled
 * {@code extractHotbarAndDecorations}, MinecraftForge only from a silenced leaf, and Balm's health events there were read as
 * running.
 *
 * <p>What the census cannot see is the installed mods, and they do call some of these: fabric-resource-loader, Iris and
 * Collective all call {@code Language.loadFromJson(InputStream, BiConsumer)}, the stub the merged game no longer does, so
 * owo's mixin there runs. {@link #scanGuests} therefore reads every installed jar's constant pool at boot, beside the
 * other guest audits and before Mixin prepares a config, and a row whose name and descriptor any of them references is
 * live — for a private row, only a call naming its class, or an {@code @Invoker} that may name it — as is one an installed
 * mod reaches through an uncalled place naming it. The row is also re-checked against the live bytes: a call from code
 * that can run (not a row, not an uncalled place a row names), in the method's own class, a listed caller's or an
 * uncalled place's, makes it live again — for a private method, a call naming its class. {@code -Dforbric.mixinFit.liveness=off}
 * reads every row as live.
 */
public final class MergedBaseUncalledMethods {
	/** The shipped table; UncalledMethodCensusTest pins it to the staged artifacts. */
	static final String TABLE = "/net/forbric/kernel/mixin/uncalled-methods.txt";
	/** Marks a dead place that is code the kernel silences, in a row's last column. */
	static final String SILENCED = "!";
	/** {@code -Dforbric.uncalledMethods.extra=<file>}: more rows, in the table's format, for a base the shipped census did not read. */
	public static final String EXTRA_PROPERTY = "forbric.uncalledMethods.extra";

	/**
	 * @param owner      the declaring class (internal name)
	 * @param method     {@code name + descriptor}
	 * @param ecosystems whose own game calls it — the mods that expect it to run
	 * @param callers    where those games call it from, {@code class#name+descriptor}
	 * @param deadSites  every place the merged game names it, {@code class#name+descriptor}, each itself uncalled or code the
	 *                   kernel silences (MinecraftForge's vanilla HUD leaves, written with a leading {@value #SILENCED}): the
	 *                   re-check against live bytes leaves these out, so a call from anywhere else still makes the method live
	 */
	record Row(String owner, String method, Set<Ecosystem> ecosystems, List<String> callers, Set<String> deadSites) {
		Row(String owner, String method, Set<Ecosystem> ecosystems, List<String> callers) {
			this(owner, method, ecosystems, callers, Set.of());
		}

		/** The dead places as {@code class#name+descriptor}, without the silenced mark. */
		Set<String> deadPlaces() {
			Set<String> out = new HashSet<>();
			for (String site : deadSites) out.add(site.startsWith(SILENCED) ? site.substring(SILENCED.length()) : site);
			return out;
		}

		/** The dead places the kernel silences: never run, however live code refers to them (it builds them, and replaces them). */
		Set<String> silencedPlaces() {
			Set<String> out = new HashSet<>();
			for (String site : deadSites) if (site.startsWith(SILENCED)) out.add(site.substring(SILENCED.length()));
			return out;
		}

		/**
		 * {@code <class>#<method> | <ecosystems> | <caller> <caller> … [| <dead site> …]}, as the census writes it; null when
		 * not that.
		 */
		static Row parse(String line) {
			String[] columns = line.split(" \\| ");
			if (columns.length != 3 && columns.length != 4) return null;
			int hash = columns[0].indexOf('#');
			if (hash <= 0 || columns[0].indexOf('(', hash) < 0) return null;
			try {
				Set<Ecosystem> ecosystems = EnumSet.noneOf(Ecosystem.class);
				for (String e : columns[1].trim().split(",")) ecosystems.add(Ecosystem.valueOf(e.trim()));
				List<String> callers = List.of(columns[2].trim().split(" "));
				for (String caller : callers) if (caller.indexOf('#') <= 0) return null;
				Set<String> dead = columns.length == 4 ? Set.of(columns[3].trim().split(" ")) : Set.of();
				for (String site : dead) if (site.indexOf('#') <= (site.startsWith(SILENCED) ? SILENCED.length() : 0)) return null;
				return new Row(columns[0].substring(0, hash).trim(), columns[0].substring(hash + 1).trim(),
						Set.copyOf(ecosystems), callers, dead);
			} catch (IllegalArgumentException unknown) {
				Caught.warn(unknown, "[Forbric/Mixin] unreadable row in %s, skipped: %s", TABLE, line);
				return null;
			}
		}
	}

	private static volatile List<Row> rows;
	private static volatile Set<String> keys;
	private static volatile Set<String> dead;
	private static volatile Set<String> silenced;
	private static volatile Set<String> owners;
	/** Table rows ({@code name + descriptor}) some installed mod's code references; see {@link #scanGuests}. */
	private static final Set<String> GUEST_CALLED = ConcurrentHashMap.newKeySet();
	/** The same, as {@code owner#name + descriptor}: the owner a call or handle names, which is all a private row heeds. */
	private static final Set<String> GUEST_CALLED_OWNED = ConcurrentHashMap.newKeySet();
	/** Row names an installed mod's {@code @Invoker} accessor may name: a private method it can call through its own class. */
	private static final Set<String> GUEST_INVOKED = ConcurrentHashMap.newKeySet();
	private static final String INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";

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
			Caught.warn(unreadable, "[Forbric/Mixin] could not read %s; every merged-base method reads as called", TABLE);
		}
		// Rows a census of another base (a weave test's own stand-in game) adds, in the same format.
		String extra = System.getProperty(EXTRA_PROPERTY);
		if (extra != null && !extra.isBlank()) {
			try {
				for (String line : java.nio.file.Files.readAllLines(Path.of(extra), StandardCharsets.UTF_8)) {
					if (line.isBlank() || line.startsWith("#")) continue;
					Row row = Row.parse(line.trim());
					if (row != null) read.add(row);
				}
			} catch (IOException | RuntimeException unreadable) {
				Caught.warn(unreadable, "[Forbric/Mixin] could not read the extra uncalled-method rows in %s", extra);
			}
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

	/**
	 * What the census proved never runs in the merged game, {@code class#name+descriptor}: every row's method and every
	 * uncalled or silenced place a row names. The live-bytes re-check starts only from code outside this set.
	 */
	static Set<String> dead() {
		Set<String> loaded = dead;
		if (loaded != null) return loaded;
		Set<String> read = new HashSet<>();
		for (Row row : rows()) {
			read.add(row.owner() + "#" + row.method());
			read.addAll(row.deadPlaces());
		}
		dead = Set.copyOf(read);
		return dead;
	}

	/** The silenced places every row names: they never run, whatever names them. */
	static Set<String> silenced() {
		Set<String> loaded = silenced;
		if (loaded != null) return loaded;
		Set<String> read = new HashSet<>();
		for (Row row : rows()) read.addAll(row.silencedPlaces());
		silenced = Set.copyOf(read);
		return silenced;
	}

	/** Every row's {@code name + descriptor}, and every dead place's: a mod calling one of those makes it run. */
	private static Set<String> keys() {
		Set<String> loaded = keys;
		if (loaded != null) return loaded;
		Set<String> read = new HashSet<>();
		for (Row row : rows()) {
			read.add(row.method());
			for (String site : row.deadPlaces()) read.add(site.substring(site.indexOf('#') + 1));
		}
		keys = Set.copyOf(read);
		return keys;
	}

	/** {@link #dead()} without the places an installed mod calls (by name and descriptor): those run after all. */
	private static Set<String> deadNow() {
		if (GUEST_CALLED.isEmpty()) return dead();
		Set<String> out = new HashSet<>();
		for (String site : dead()) {
			if (!GUEST_CALLED.contains(site.substring(site.indexOf('#') + 1)) || silenced().contains(site)) out.add(site);
		}
		return out;
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
				Caught.warn(unreadable, "[Forbric/Mixin] could not read %s for calls into uncalled merged-base methods; "
						+ "what it calls reads as uncalled", jar.getFileName());
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
			Caught.warn(notAClass, "[Forbric/Mixin] a .class entry of %d bytes is not a class ASM can read; its calls into "
					+ "uncalled merged-base methods are not seen", classBytes == null ? -1 : classBytes.length);
			return;
		}
		Set<String> wanted = keys();
		char[] buf = new char[reader.getMaxStringLength()];
		Set<String> utf8 = new java.util.HashSet<>();
		for (int i = 1; i < reader.getItemCount(); i++) {
			int offset = reader.getItem(i);
			if (offset == 0) continue;
			int tag = reader.readByte(offset - 1);
			if (tag == 1) {                                                       // CONSTANT_Utf8: u2 length, then the bytes
				int length = reader.readUnsignedShort(offset);
				@SuppressWarnings("deprecation")
				byte[] bytes = reader.b;
				utf8.add(new String(bytes, offset + 2, length, java.nio.charset.StandardCharsets.UTF_8));
			} else if (tag == 12) {                                               // CONSTANT_NameAndType
				String key = reader.readUTF8(offset, buf) + reader.readUTF8(offset + 2, buf);
				if (wanted.contains(key)) GUEST_CALLED.add(key);
			} else if (tag == 10 || tag == 11) {                                  // CONSTANT_(Interface)Methodref
				String owner = reader.readClass(offset, buf);
				int nameAndType = reader.getItem(reader.readUnsignedShort(offset + 2));
				String key = reader.readUTF8(nameAndType, buf) + reader.readUTF8(nameAndType + 2, buf);
				if (wanted.contains(key)) GUEST_CALLED_OWNED.add(owner + "#" + key);
			}
		}
		// An @Invoker names its target by its value, or by its own name less "call"/"invoke": a private row of that name may be
		// called through the accessor, whatever this class's constant pool says of its owner.
		if (!utf8.contains(INVOKER)) return;
		for (String key : wanted) {
			String name = key.substring(0, key.indexOf('('));
			String capitalised = name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
			if (utf8.contains(name) || utf8.contains("call" + capitalised) || utf8.contains("invoke" + capitalised)) GUEST_INVOKED.add(name);
		}
	}

	/** Whether an installed mod references {@code nameAndDesc}; test and census seam. */
	static boolean calledByGuest(String nameAndDesc) {
		return GUEST_CALLED.contains(nameAndDesc);
	}

	/** Whether an installed mod calls {@code owner#nameAndDesc} naming that owner, or may through an {@code @Invoker}; test seam. */
	static boolean calledByGuest(String owner, String nameAndDesc) {
		return GUEST_CALLED_OWNED.contains(owner + "#" + nameAndDesc) || GUEST_INVOKED.contains(nameAndDesc.substring(0, nameAndDesc.indexOf('(')));
	}

	/** Test and census seam: forgets what the installed mods reference. */
	static void forgetGuests() {
		GUEST_CALLED.clear();
		GUEST_CALLED_OWNED.clear();
		GUEST_INVOKED.clear();
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
			Set<String> notRunning = deadNow();
			Set<String> checked = new HashSet<>();
			checked.add(owner.name);    // already checked, in the bytes the caller handed us
			List<String> classes = new ArrayList<>();
			for (String caller : row.callers()) classes.add(caller.substring(0, caller.indexOf('#')));
			for (String site : row.deadPlaces()) classes.add(site.substring(0, site.indexOf('#')));
			for (String callerOwner : classes) {
				if (!checked.add(callerOwner)) continue;
				byte[] bytes = resolver.apply(callerOwner + ".class");
				if (bytes == null) continue;
				ClassNode node = new ClassNode();
				new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
				if (calledIn(node, method, notRunning)) return null;
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
		if (row == null || calledIn(owner, method, deadNow())) return null;
		// A private method is reached only through its own class: a mod calling a method of another class with the same name
		// and descriptor (Level.removeBlock(BlockPos, boolean)) does not reach ServerPlayerGameMode's private one.
		boolean guest = (method.access & org.objectweb.asm.Opcodes.ACC_PRIVATE) != 0 ? calledByGuest(owner.name, row.method())
				: GUEST_CALLED.contains(row.method());
		return guest ? null : row;
	}

	/**
	 * Whether any method of {@code node} but {@code method} itself calls or takes a handle to {@code method}'s name and descriptor
	 * — for a private {@code method} of {@code node}, naming {@code node} as the owner, the only way to reach it.
	 */
	static boolean calledIn(ClassNode node, MethodNode method) {
		return calledIn(node, method, Set.of());
	}

	/**
	 * {@link #calledIn(ClassNode, MethodNode)}, from code of {@code node} that can run: a method that is one of {@code dead}
	 * (the census's uncalled or silenced places naming it, {@code class#name+descriptor}) calls nothing, unless a method of
	 * {@code node} that can run reaches it in turn — a transform that calls one of them again makes what it calls live too.
	 */
	static boolean calledIn(ClassNode node, MethodNode method, Set<String> dead) {
		if (node.methods == null) return false;
		java.util.Map<String, MethodNode> declared = new java.util.HashMap<>();
		for (MethodNode m : node.methods) declared.put(m.name + m.desc, m);
		boolean ownerOnly = (method.access & org.objectweb.asm.Opcodes.ACC_PRIVATE) != 0 && declared.containsKey(method.name + method.desc);
		java.util.Deque<MethodNode> pending = new java.util.ArrayDeque<>();
		Set<MethodNode> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		for (MethodNode m : node.methods) {
			if (m.name.equals(method.name) && m.desc.equals(method.desc)) continue;
			if (!dead.contains(node.name + "#" + m.name + m.desc)) pending.add(m);
		}
		while (!pending.isEmpty()) {
			MethodNode m = pending.removeFirst();
			if (!seen.add(m) || m.instructions == null) continue;
			for (AbstractInsnNode insn : m.instructions) {
				// what it names, owner first: a private method of this class is reached only by naming this class
				List<String[]> named = new ArrayList<>(1);
				if (insn instanceof MethodInsnNode call) named.add(new String[] {call.owner, call.name + call.desc});
				else if (insn instanceof InvokeDynamicInsnNode indy) {
					for (Object argument : indy.bsmArgs) if (argument instanceof Handle h) named.add(new String[] {h.getOwner(), h.getName() + h.getDesc()});
				} else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Handle h) named.add(new String[] {h.getOwner(), h.getName() + h.getDesc()});
				for (String[] target : named) {
					String key = target[1];
					if (key.equals(method.name + method.desc)) {
						if (!ownerOnly || target[0].equals(node.name)) return true;
						continue;
					}
					// a dead site this live code reaches runs after all: what it names counts from here on — unless the kernel
					// silences it, which it does exactly where live code builds it
					MethodNode reached = declared.get(key);
					if (reached == null || (reached.access & org.objectweb.asm.Opcodes.ACC_PRIVATE) != 0 && !target[0].equals(node.name)) continue;
					if (!seen.contains(reached) && dead.contains(node.name + "#" + key) && !silenced().contains(node.name + "#" + key)) pending.add(reached);
				}
			}
		}
		return false;
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
