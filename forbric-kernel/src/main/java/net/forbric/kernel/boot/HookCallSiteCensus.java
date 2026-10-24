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

package net.forbric.kernel.boot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Which of a hook class's hooks the merged game base still CALLS, and which events therefore never get posted.
 *
 * <h2>Why this exists</h2>
 *
 * <p>An ecosystem's event surface is two halves that live in different jars: the hook class ({@code
 * ForgeEventFactory}, {@code ForgeEventFactoryClient}, NeoForge's {@code ClientHooks}) ships in the carrier and
 * declares a static method per event; the CALL SITES live in the game, and the byte-merge decides per method
 * which ecosystem's patched body survives. When NeoForge's body wins a method both patched, MinecraftForge's
 * call to its own hook goes with it — {@code merge-conflicts.txt} logs that as {@code (forge hook lost)} — and
 * the hook method is still there, still linkable, still callable by anyone, and never called by anything.
 *
 * <p>A mod that subscribes to that event gets no exception, no warning, and no event. The surface is intact:
 * only the calls are gone. That is a defect you can only find by asking whether a call site EXISTS, which is
 * why the numbers this project quotes — "46 hooks, eight still have a call site", "161 declared, 20 alive" —
 * were produced by reading {@code javap} output by hand and writing the answer into a javadoc. A number in a
 * comment is true on the day it is written and unfalsifiable afterwards; the merged base is rebuilt whenever a
 * carrier moves, and nothing re-derived any of them.
 *
 * <p>This derives them. The comparison is absolute in the direction that matters: a constant pool either names
 * the method or it does not, so {@code dead} is a fact about bytecode, not a reading of prose or a log line.
 *
 * <h2>From dead hooks to dead events</h2>
 *
 * <p>A hook method posts its event by constructing it, so the {@code NEW} instructions in a hook's body name
 * the events that hook can post. An event is dead when EVERY hook that constructs it is dead — one surviving
 * call site anywhere is enough to keep it alive, which is why this cannot be answered one hook at a time.
 *
 * <p>Only an EVENT counts: a type the hook constructs whose supertypes, read from the carrier, reach its family's
 * event base ({@link #EVENT_ROOTS}). A codec, a map or a result holder a hook allocates in its own namespace is not an
 * event nothing posts; a type the carriers do not carry is counted, as before, because it cannot be told apart.
 *
 * <p>Two honest limits make this UNDER-report deadness (call a dead event live):
 * <ul>
 *   <li>a hook handed an already-constructed event posts an event it never {@code NEW}s, so that event looks
 *       like it has no posters at all and is simply not judged;</li>
 *   <li>a call site the merge left in unreachable code still counts as a call site.</li>
 * </ul>
 *
 * <p>And one makes it OVER-report, since a carrier caller that is a hook counts only when it is reached ({@link #all}):
 * a call the scanned bytecode does not show — one a kernel transform injects at load time, or one made reflectively —
 * reaches a hook the census calls unreached. The kernel's own compiled calls are read ({@code kernelRoots}); the other
 * two are why {@link DeadEventAudit#REPAIRED} names such events by hand (NeoForge's multi-block place, reached through
 * the transform that routes {@code ItemStack.useOn} to its {@code onPlaceItemIntoWorld}; MinecraftForge's configuration
 * tasks, gathered reflectively), and the staged test requires every measured-dead event to have a row, so such an event
 * cannot be named "never posted" without someone reading where it comes from.
 */
public final class HookCallSiteCensus {

	/**
	 * One hook class's answer.
	 *
	 * @param hookClass   internal name of the class whose static hooks were counted
	 * @param declared    every {@code name+desc} it declares as a public static hook, sorted
	 * @param live        those with at least one call site in the scanned base
	 * @param dead        {@code declared - live} — the surface that is present and never reached
	 * @param postersOf   event internal name → the hooks that construct it
	 * @param deadEvents  events every one of whose posters is dead: neither the game nor a reached carrier method
	 *                    calls any of them
	 * @param viaCarrier  dead hooks a carrier method that is itself reached calls — see {@link #all}
	 * @param viaKernel   dead hooks reached through neither, but called by the kernel's own classes
	 * @param kernelDelivered the dead events the kernel posts itself: through a hook in {@code viaKernel}, or by
	 *                    constructing the event in its own classes
	 */
	public record Census(String hookClass, List<String> declared, Set<String> live, Set<String> dead,
			Map<String, Set<String>> postersOf, Set<String> deadEvents, Set<String> viaCarrier, Set<String> viaKernel,
			Set<String> kernelDelivered) {

		public Census {
			declared = List.copyOf(declared);
			live = Set.copyOf(live);
			dead = Set.copyOf(dead);
			postersOf = Map.copyOf(postersOf);
			deadEvents = Set.copyOf(deadEvents);
			viaCarrier = Set.copyOf(viaCarrier);
			viaKernel = Set.copyOf(viaKernel);
			kernelDelivered = Set.copyOf(kernelDelivered);
		}

		/** Live events: posted by at least one hook something still calls, the carriers included. */
		public Set<String> liveEvents() {
			Set<String> out = new TreeSet<>(postersOf.keySet());
			out.removeAll(deadEvents);
			return out;
		}

		/** Hooks with no call site in the game, but one in their own ecosystem's runtime. */
		public Set<String> reachedOnlyViaCarrier() {
			return viaCarrier;
		}

		/**
		 * The one line a gate greps. Shaped like the other censuses: the denominator first, so a run that
		 * scanned nothing cannot be mistaken for a run that found nothing.
		 */
		public String summary() {
			return "[Forbric/Hooks] " + hookClass + ": " + declared.size() + " declared, " + live.size()
					+ " called by the game, " + viaCarrier.size() + " only by their own runtime, "
					+ (dead.size() - viaCarrier.size()) + " called by nothing; events: " + postersOf.size()
					+ " posted, " + deadEvents.size() + " never posted";
		}
	}

	private HookCallSiteCensus() {
	}

	/**
	 * Counts {@code hookClass}'s hooks against the call sites in {@code baseJars}.
	 *
	 * @param carrierJar the jar DECLARING the hook class (a carrier; the hook class is not in the merged base)
	 * @param hookClass  its internal name, e.g. {@code net/minecraftforge/client/event/ForgeEventFactoryClient}
	 * @param baseJars   the jars whose call sites count — the merged game base
	 */
	public static Census of(Path carrierJar, String hookClass, List<Path> baseJars) throws IOException {
		return of(carrierJar, hookClass, baseJars, List.of());
	}

	/**
	 * The same, told which jars are the ecosystem's OWN runtime rather than the game.
	 *
	 * <h2>The third state</h2>
	 *
	 * <p>A hook with no call site in the merged base may still be reached, through its own ecosystem's runtime:
	 * {@code EventHooks.onMultiBlockPlace} has no caller in any patched game and one in
	 * {@code CommonHooks.onPlaceItemIntoWorld}, which this kernel's own repair routes {@code ItemStack.useOn}
	 * into. Counting that as dead put a delivered event on the work list.
	 *
	 * <p>It is a weaker statement than "the game calls it", so it is a third answer rather than being folded into
	 * {@code live}. The numbers this project has quoted about its own surface are the game-only ones, and {@link
	 * #live} still means that. A carrier caller that is itself a hook counts only when it is reached in turn: see
	 * {@link #all}, which this is for one hook class.
	 */
	public static Census of(Path carrierJar, String hookClass, List<Path> baseJars, List<Path> carrierJars)
			throws IOException {
		return all(Map.of(hookClass, carrierJar), baseJars, carrierJars, List.of()).get(hookClass);
	}

	/**
	 * Each type's family event base. An event of either family is a subtype of one of these; a type that is not is
	 * something a hook allocates on the way (a codec, a map, a result holder), and counting it as an event "nothing
	 * posts" put four non-events on the dead list.
	 */
	static final Set<String> EVENT_ROOTS = Set.of(net.forbric.api.ForeignType.EVENT.internal(net.forbric.api.Ecosystem.FORGE),
			net.forbric.api.ForeignType.EVENT.internal(net.forbric.api.Ecosystem.NEOFORGE));

	/**
	 * Every hook class at once, with reach judged across all of them.
	 *
	 * <h2>Why a carrier caller is not enough on its own</h2>
	 *
	 * <p>A hook with no call site in the game may be reached through its own ecosystem's runtime, and {@link #of}
	 * counted any caller in a carrier as that. But a caller that is itself a hook nothing reaches reaches nothing:
	 * MinecraftForge's {@code ForgeHooks.onLivingBreathe} calls {@code ForgeEventFactory.onLivingBreathe}, and the merged
	 * game calls neither, so a MinecraftForge mod's breathe listener never ran while the census called its event posted.
	 * So a caller that is a hook of one of {@code hookClasses} (a public static method, not synthetic) counts only when
	 * it is reached itself — called by the game, or by a caller that counts — followed to a fixed point. Any other caller
	 * (an instance method, a lambda body, a static initializer, a class that is not a hook class) still counts, because
	 * reading whether the runtime reaches it is beyond a call-site count; that keeps every error on the side of calling
	 * a dead event live. A hook class calling its own hook is judged the same way: from a dead hook it is nothing, from a
	 * lambda the game's call hands out it is the runtime reaching it.
	 *
	 * <h2>The kernel</h2>
	 *
	 * <p>{@code kernelRoots} (class directories or jars) are the kernel's own classes: a dead hook they call, or a dead
	 * event they construct, is one the kernel delivers. That does not make the event posted by the merged game — {@link
	 * Census#deadEvents} stays what the game and its carriers never post — but it is what tells a bridged or repaired
	 * event from one nothing delivers at all.
	 *
	 * @param hookClasses hook class internal name → the carrier jar declaring it
	 * @param baseJars    the jars whose call sites count as the game's — the merged game base
	 * @param carrierJars the ecosystems' own runtimes, for the third state
	 * @param kernelRoots the kernel's own classes, for the fourth
	 */
	public static Map<String, Census> all(Map<String, Path> hookClasses, List<Path> baseJars, List<Path> carrierJars,
			List<Path> kernelRoots) throws IOException {
		Map<String, ClassNode> hookNodes = new LinkedHashMap<>();
		Map<Path, Map<String, String[]>> hierarchies = new java.util.HashMap<>();
		for (Map.Entry<String, Path> e : hookClasses.entrySet()) {
			ClassNode hooks = read(e.getValue(), e.getKey() + ".class");
			if (hooks == null) throw new IOException(e.getKey() + " is not in " + e.getValue());
			hookNodes.put(e.getKey(), hooks);
			if (!hierarchies.containsKey(e.getValue())) hierarchies.put(e.getValue(), hierarchy(e.getValue()));
		}
		// Every hook of every censused class: the callers whose own reach is judged rather than assumed.
		Set<String> gates = new java.util.HashSet<>();
		Map<String, List<String>> declaredOf = new LinkedHashMap<>();
		for (Map.Entry<String, ClassNode> e : hookNodes.entrySet()) {
			List<String> declared = new java.util.ArrayList<>();
			for (MethodNode m : e.getValue().methods) {
				if (!isHook(m)) continue;
				declared.add(m.name + m.desc);
				gates.add(e.getKey() + "." + m.name + m.desc);
			}
			declared.sort(String::compareTo);
			declaredOf.put(e.getKey(), declared);
		}

		// The game's calls, and the kernel's. A copy of a hook class handed in as a base calling its own hooks is not
		// the game reaching them.
		Set<String> gameCalls = calls(baseJars, hookClasses.keySet());
		Set<String> kernelCalls = calls(kernelRoots, Set.of());
		Set<String> kernelNews = constructions(kernelRoots);

		// The carriers' calls into hook classes, by caller: a gate's edges are followed only from a reached gate; every
		// other caller's callees are reached outright.
		Map<String, Set<String>> gateEdges = new java.util.HashMap<>();
		Set<String> reachedOutright = new java.util.HashSet<>();
		for (Path jar : carrierJars) carrierEdges(jar, hookClasses.keySet(), gates, gateEdges, reachedOutright);

		Set<String> reachedByGame = reach(gates, gameCalls, gateEdges, reachedOutright);
		Set<String> withKernel = new java.util.HashSet<>(gameCalls);
		withKernel.addAll(kernelCalls);
		Set<String> reachedWithKernel = reach(gates, withKernel, gateEdges, reachedOutright);

		Map<String, Census> out = new LinkedHashMap<>();
		for (Map.Entry<String, ClassNode> e : hookNodes.entrySet()) {
			String hookClass = e.getKey();
			// Only the hook's OWN ecosystem namespace counts as an event it posts. Without this, every ArrayList,
			// Vec3 and StringBuilder a hook body allocates comes back as an "event nothing posts" — 171 of them on
			// the real base, which buries the fourteen that are real and makes the count worse than no count.
			String eventNamespace = namespaceOf(hookClass);
			Map<String, String[]> hierarchy = hierarchies.get(hookClasses.get(hookClass));
			Map<String, Set<String>> posters = new TreeMap<>();
			for (MethodNode m : e.getValue().methods) {
				if (!isHook(m) || m.instructions == null) continue;
				for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (insn.getOpcode() == Opcodes.NEW && insn instanceof TypeInsnNode t
							&& t.desc.startsWith(eventNamespace) && isEvent(t.desc, eventNamespace, hierarchy)) {
						posters.computeIfAbsent(t.desc, k -> new TreeSet<>()).add(m.name + m.desc);
					}
				}
			}
			List<String> declared = declaredOf.get(hookClass);
			Set<String> live = new TreeSet<>(), dead = new TreeSet<>(), viaCarrier = new TreeSet<>(), viaKernel = new TreeSet<>();
			for (String key : declared) {
				String gate = hookClass + "." + key;
				if (gameCalls.contains(gate)) {
					live.add(key);
					continue;
				}
				dead.add(key);
				if (reachedByGame.contains(gate)) viaCarrier.add(key);
				else if (reachedWithKernel.contains(gate)) viaKernel.add(key);
			}
			Set<String> deadEvents = new TreeSet<>(), kernelDelivered = new TreeSet<>();
			for (Map.Entry<String, Set<String>> event : posters.entrySet()) {
				boolean allUnreached = true, byKernel = kernelNews.contains(event.getKey());
				for (String poster : event.getValue()) {
					if (!dead.contains(poster) || viaCarrier.contains(poster)) {
						allUnreached = false;
						break;
					}
					if (viaKernel.contains(poster)) byKernel = true;
				}
				if (!allUnreached) continue;
				deadEvents.add(event.getKey());
				if (byKernel) kernelDelivered.add(event.getKey());
			}
			Map<String, Set<String>> frozen = new LinkedHashMap<>();
			posters.forEach((k, v) -> frozen.put(k, Set.copyOf(v)));
			out.put(hookClass, new Census(hookClass, declared, live, dead, frozen, deadEvents, viaCarrier, viaKernel, kernelDelivered));
		}
		return out;
	}

	/** A public static, non-synthetic, non-bridge, non-initializer method: what a hook class offers as a hook. */
	private static boolean isHook(MethodNode m) {
		if ((m.access & Opcodes.ACC_STATIC) == 0 || (m.access & Opcodes.ACC_PUBLIC) == 0) return false;
		if ((m.access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0) return false;
		return !m.name.startsWith("<");
	}

	/** The gates reached from {@code roots}: called there, or by a reached gate, or by any caller that is not a gate. */
	private static Set<String> reach(Set<String> gates, Set<String> roots, Map<String, Set<String>> gateEdges,
			Set<String> reachedOutright) {
		Set<String> reached = new java.util.HashSet<>();
		java.util.ArrayDeque<String> work = new java.util.ArrayDeque<>();
		for (String gate : gates) if (roots.contains(gate) || reachedOutright.contains(gate)) work.add(gate);
		while (!work.isEmpty()) {
			String gate = work.removeFirst();
			if (!reached.add(gate)) continue;
			for (String next : gateEdges.getOrDefault(gate, Set.of())) if (gates.contains(next)) work.add(next);
		}
		return reached;
	}

	/**
	 * Whether {@code type} is an event: its supertypes, as far as the carrier carries them, reach a family event base. A
	 * supertype in the family's own namespace that the carrier does not carry cannot be told apart and is counted — the
	 * census only ever errs towards calling a dead event live, and an unknown type was always counted before this check.
	 * One outside it (the game's, a library's, the JDK's) cannot lead to a family event base: those know neither family.
	 */
	static boolean isEvent(String type, String namespace, Map<String, String[]> hierarchy) {
		java.util.ArrayDeque<String> work = new java.util.ArrayDeque<>(List.of(type));
		Set<String> seen = new java.util.HashSet<>();
		boolean unknown = false;
		while (!work.isEmpty()) {
			String at = work.removeFirst();
			if (!seen.add(at)) continue;
			if (EVENT_ROOTS.contains(at)) return true;
			String[] supers = hierarchy.get(at);
			if (supers == null) {
				if (at.startsWith(namespace)) unknown = true;
				continue;
			}
			for (String s : supers) if (s != null) work.add(s);
		}
		return unknown;
	}

	/** internal name → {superclass, interfaces...} for every class in {@code jar}. */
	private static Map<String, String[]> hierarchy(Path jar) throws IOException {
		Map<String, String[]> out = new java.util.HashMap<>();
		try (ZipFile zf = new ZipFile(jar.toFile())) {
			var entries = zf.entries();
			while (entries.hasMoreElements()) {
				ZipEntry e = entries.nextElement();
				if (!e.getName().endsWith(".class")) continue;
				try (InputStream in = zf.getInputStream(e)) {
					ClassReader reader = new ClassReader(in.readAllBytes());
					String[] interfaces = reader.getInterfaces();
					String[] supers = new String[interfaces.length + 1];
					supers[0] = reader.getSuperName();
					System.arraycopy(interfaces, 0, supers, 1, interfaces.length);
					out.put(reader.getClassName(), supers);
				}
			}
		}
		return out;
	}

	/** {@code owner.name+desc} of every method called from {@code roots}, classes named in {@code skip} left out. */
	private static Set<String> calls(List<Path> roots, Set<String> skip) throws IOException {
		Set<String> out = new java.util.HashSet<>();
		for (Path root : roots) {
			forEachClass(root, cn -> {
				if (skip.contains(cn.name)) return;
				for (MethodNode m : cn.methods) {
					if (m.instructions == null) continue;
					for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
						if (insn instanceof MethodInsnNode mi) out.add(mi.owner + "." + mi.name + mi.desc);
					}
				}
			});
		}
		return out;
	}

	/** Every type {@code roots} construct. */
	private static Set<String> constructions(List<Path> roots) throws IOException {
		Set<String> out = new java.util.HashSet<>();
		for (Path root : roots) {
			forEachClass(root, cn -> {
				for (MethodNode m : cn.methods) {
					if (m.instructions == null) continue;
					for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
						if (insn.getOpcode() == Opcodes.NEW && insn instanceof TypeInsnNode t) out.add(t.desc);
					}
				}
			});
		}
		return out;
	}

	/**
	 * The carrier's calls into the hook classes: from a gate, recorded as that gate's edges; from anything else, the
	 * callee is reached outright.
	 */
	private static void carrierEdges(Path jar, Set<String> hookClasses, Set<String> gates, Map<String, Set<String>> gateEdges,
			Set<String> reachedOutright) throws IOException {
		forEachClass(jar, cn -> {
			for (MethodNode m : cn.methods) {
				if (m.instructions == null) continue;
				String caller = cn.name + "." + m.name + m.desc;
				boolean gate = gates.contains(caller);
				for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (!(insn instanceof MethodInsnNode mi) || !hookClasses.contains(mi.owner)) continue;
					String callee = mi.owner + "." + mi.name + mi.desc;
					if (callee.equals(caller)) continue;
					if (gate) gateEdges.computeIfAbsent(caller, k -> new java.util.HashSet<>()).add(callee);
					else reachedOutright.add(callee);
				}
			}
		});
	}

	/** Each class in a jar or under a class directory; a root that is neither is skipped. */
	private static void forEachClass(Path root, java.util.function.Consumer<ClassNode> action) throws IOException {
		if (Files.isDirectory(root)) {
			try (var walk = Files.walk(root)) {
				for (Path file : (Iterable<Path>) walk.filter(f -> f.toString().endsWith(".class"))::iterator) {
					ClassNode cn = new ClassNode();
					new ClassReader(Files.readAllBytes(file)).accept(cn, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
					action.accept(cn);
				}
			}
			return;
		}
		if (!Files.isRegularFile(root)) return;
		try (ZipFile zf = new ZipFile(root.toFile())) {
			var entries = zf.entries();
			while (entries.hasMoreElements()) {
				ZipEntry e = entries.nextElement();
				if (!e.getName().endsWith(".class")) continue;
				ClassNode cn = new ClassNode();
				try (InputStream in = zf.getInputStream(e)) {
					new ClassReader(in.readAllBytes()).accept(cn, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
				}
				action.accept(cn);
			}
		}
	}

	/**
	 * The hook class's ecosystem root: at most the first two segments of its package, with a trailing slash.
	 *
	 * <p>{@code net/minecraftforge/event/ForgeEventFactory} → {@code net/minecraftforge/};
	 * {@code net/neoforged/neoforge/event/EventHooks} → {@code net/neoforged/}. A package shallower than two
	 * segments yields itself, so a one-package fixture still matches its own types.
	 */
	static String namespaceOf(String internalName) {
		int lastSlash = internalName.lastIndexOf('/');
		if (lastSlash < 0) return "";
		String pkg = internalName.substring(0, lastSlash);
		int first = pkg.indexOf('/');
		int second = first < 0 ? -1 : pkg.indexOf('/', first + 1);
		return (second < 0 ? pkg : pkg.substring(0, second)) + "/";
	}

	/**
	 * One hook's call sites before and after the merge.
	 *
	 * @param hook   {@code name+desc}
	 * @param before how many call sites the ecosystem's own patched game had
	 * @param after  how many the merged base has
	 */
	public record Erosion(String hook, int before, int after) {

		/** Called from somewhere, and from fewer places than it used to be. */
		public boolean partial() {
			return after > 0 && after < before;
		}

		/** Called from nowhere at all. */
		public boolean lost() {
			return before > 0 && after == 0;
		}
	}

	/**
	 * How many call sites each of {@code hookClass}'s hooks kept across the merge.
	 *
	 * <h2>Why "dead" was not the whole answer</h2>
	 *
	 * <p>{@link #of} asks whether a hook has ANY call site, which answers "is this event ever posted". It cannot
	 * answer the state in between, and that state is real: three rows were deleted from {@link DeadEventAudit}
	 * for claiming an event is never posted when it is — posted from one class and not from another, because the
	 * merge took some of its call sites and left others.
	 *
	 * <p>That is worse for a mod than either extreme. A mod whose fall-damage listener never fires gets reported
	 * and investigated; one that fires for horses and llamas and not for anything else looks intermittent, which
	 * is the hardest kind of bug to report and the easiest to blame on the mod.
	 *
	 * @param before the ecosystem's OWN patched game jar, as it was before the merge
	 * @param after  the merged base
	 */
	public static List<Erosion> erosion(String hookClass, Path before, Path after) throws IOException {
		Map<String, Integer> was = callSites(before, hookClass);
		Map<String, Integer> is = callSites(after, hookClass);
		List<Erosion> out = new java.util.ArrayList<>();
		for (Map.Entry<String, Integer> e : was.entrySet()) {
			out.add(new Erosion(e.getKey(), e.getValue(), is.getOrDefault(e.getKey(), 0)));
		}
		out.sort(java.util.Comparator.comparing(Erosion::hook));
		return List.copyOf(out);
	}

	/** {@code name+desc} → how many instructions in {@code jar} call it on {@code hookClass}. */
	static Map<String, Integer> callSites(Path jar, String hookClass) throws IOException {
		Map<String, Integer> out = new TreeMap<>();
		if (!Files.isRegularFile(jar)) return out;
		try (ZipFile zf = new ZipFile(jar.toFile())) {
			var entries = zf.entries();
			while (entries.hasMoreElements()) {
				ZipEntry e = entries.nextElement();
				if (!e.getName().endsWith(".class")) continue;
				ClassNode cn = new ClassNode();
				try (InputStream in = zf.getInputStream(e)) {
					new ClassReader(in.readAllBytes()).accept(cn, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
				}
				// The hook class calling its own hooks is not the game reaching them, the same rule as of().
				if (cn.name.equals(hookClass)) continue;
				for (MethodNode m : cn.methods) {
					if (m.instructions == null) continue;
					for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
						if (insn instanceof MethodInsnNode mi && mi.owner.equals(hookClass)) {
							out.merge(mi.name + mi.desc, 1, Integer::sum);
						}
					}
				}
			}
		}
		return out;
	}

	/**
	 * Methods that call into BOTH ecosystems' event-hook classes, which is the only way one path can deliver a
	 * bridged event twice.
	 *
	 * <p>"The base also posts this event directly" is NOT that, and reading it as that produced a finding here
	 * that did not survive being checked: {@code BlockEvent$EntityPlaceEvent} has a bridge and one surviving
	 * call site, in {@code ReplaceDisk#apply}, which calls MinecraftForge's {@code onBlockPlace} and no NeoForge
	 * hook at all — so the bridge is silent on that path and a subscriber gets exactly one event. A bridge fires
	 * on the OTHER ecosystem's event; unless something fires both, the two never meet.
	 */
	public static List<String> methodsCallingBothFamilies(List<Path> jars) throws IOException {
		List<String> both = new java.util.ArrayList<>();
		for (Path jar : jars) {
			if (!Files.isRegularFile(jar)) continue;
			try (ZipFile zf = new ZipFile(jar.toFile())) {
				var entries = zf.entries();
				while (entries.hasMoreElements()) {
					ZipEntry e = entries.nextElement();
					if (!e.getName().endsWith(".class")) continue;
					ClassNode cn = new ClassNode();
					try (InputStream in = zf.getInputStream(e)) {
						new ClassReader(in.readAllBytes()).accept(cn, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
					}
					for (MethodNode m : cn.methods) {
						if (m.instructions == null) continue;
						boolean forge = false;
						boolean neo = false;
						for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
							if (!(insn instanceof MethodInsnNode mi)) continue;
							if (isHookClass(mi.owner, "net/minecraftforge/")) forge = true;
							if (isHookClass(mi.owner, "net/neoforged/")) neo = true;
						}
						if (forge && neo) both.add(cn.name + "#" + m.name + m.desc);
					}
				}
			}
		}
		return List.copyOf(both);
	}

	/**
	 * An event-hook ENTRY POINT of {@code family}, as opposed to any other class in its namespace.
	 *
	 * <p>Narrow on purpose. Both namespaces carry ordinary utilities a game method may legitimately touch —
	 * {@code BlockSnapshot.create} is one — and counting those would make this find methods that post nothing.
	 */
	static boolean isHookClass(String owner, String family) {
		if (!owner.startsWith(family)) return false;
		String simple = owner.substring(owner.lastIndexOf('/') + 1);
		return simple.equals("ForgeEventFactory") || simple.equals("ForgeEventFactoryClient")
				|| simple.equals("ForgeHooks") || simple.equals("ForgeHooksClient")
				|| simple.equals("EventHooks") || simple.equals("ClientHooks");
	}

	private static ClassNode read(Path jar, String entry) throws IOException {
		try (ZipFile zf = new ZipFile(jar.toFile())) {
			ZipEntry e = zf.getEntry(entry);
			if (e == null) return null;
			ClassNode cn = new ClassNode();
			try (InputStream in = zf.getInputStream(e)) {
				new ClassReader(in.readAllBytes()).accept(cn, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
			}
			return cn;
		}
	}
}
