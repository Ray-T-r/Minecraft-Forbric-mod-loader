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
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
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
 * <p>Three honest limits make this UNDER-report deadness (call a dead event live):
 * <ul>
 *   <li>a hook handed an already-constructed event posts an event it never {@code NEW}s, so that event looks
 *       like it has no posters at all and is simply not judged;</li>
 *   <li>a call site the merge left in unreachable code still counts as a call site;</li>
 *   <li>a carrier method is reached by rules that err towards reaching ({@link CarrierCallGraph}): a lambda or method
 *       reference counts as called where it is created, a virtual call reaches every override in a class something
 *       constructs, and a method overriding one the scan cannot read counts as called. A listener a carrier builds for a
 *       payload the kernel routes elsewhere (MinecraftForge's channel-registration handler) is reached by these rules
 *       and never runs.</li>
 * </ul>
 *
 * <p>And one makes it OVER-report, since a carrier method counts only when it is reached ({@link #all}): a call the scanned
 * bytecode does not show — one a kernel transform outside the merged-base repairs injects at load time — reaches a hook
 * the census calls unreached. The kernel's own compiled calls are read ({@code kernelRoots}), method references included,
 * and so are its calls by reflection (a family class's binary name and a method name held by a method that looks a method
 * up: {@code PayloadInterop} gathers MinecraftForge's configuration tasks that way); the staged test requires every
 * measured-dead event to have a row, so such an event cannot be named "never posted" without someone reading where it
 * comes from.
 *
 * <p>A call is an {@code invoke} instruction or a method handle naming the method (a method reference, a lambda's
 * implementation, a handle constant): {@code ForgeEventFactory::onPreClientTick} handed to a listener reaches the hook as
 * surely as calling it does.
 *
 * <h2>The game as the kernel loads it</h2>
 *
 * <p>The staged jar is not what runs: the kernel's merged-base repairs rewrite some of its classes as they load, and a
 * repair can take a hook call away as well as add one. {@code routePlaceItemHookToNeoForge} sends {@code ItemStack.useOn}'s
 * {@code ForgeHooks.onPlaceItemIntoWorld} to NeoForge's {@code CommonHooks} (the snapshot list it drains is NeoForge's), so
 * MinecraftForge's multi-block place event, whose one poster is reached only from that call, is never posted at runtime,
 * while the staged jar still shows the call. Read off the staged bytes, the census called it posted, and no table could
 * then say otherwise. So the game's classes can be handed through a load-time view ({@link #all(Map, List, List, List,
 * java.util.function.BiFunction)}) before their calls are counted — the staged test hands the merged-base repairs — and
 * what the census counts is what the game calls once those repairs ran. Other transformers are not replayed; the ones
 * that touch hook calls wrap or redirect them into the kernel, whose classes are read as the fourth source.
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
	 * @param deadEvents  events every one of whose posters is unreached: neither the game nor a carrier method the game
	 *                    or the runtime reaches calls any of them
	 * @param viaCarrier  dead hooks a carrier method that is itself reached calls — see {@link #all}
	 * @param viaKernel   dead hooks reached through neither, but through the kernel's own classes (compiled calls, and
	 *                    calls by reflection — see {@link CarrierCallGraph})
	 * @param kernelDelivered the dead events the kernel posts itself: through a hook in {@code viaKernel}, or by
	 *                    constructing the event in its own classes
	 * @param guestPosted the dead events the kernel does not post that a guest does, by calling a family's public API
	 *                    outside the hook classes (a MinecraftForge mod opening its menu through
	 *                    {@code IForgeServerPlayer.openMenu}): posted on the paths mods take, never on the game's
	 * @param witness     each hook in {@code viaCarrier}, {@code viaKernel}, or reached only by a guest → why it is
	 *                    reached: the chain of carrier methods back to its root, root last ({@link CarrierCallGraph})
	 * @param postsThrough each declared hook → the events it posts: those it constructs, and those a hook it calls (of
	 *                    any censused class, to a fixed point) constructs — what a call site of it delivers
	 * @param kernelConstructs every type the kernel's own code constructs, in its classes or in the carrier methods it
	 *                    alone reaches (the same set for every hook class of one census): what a repair or a bridge
	 *                    posts itself
	 */
	public record Census(String hookClass, List<String> declared, Set<String> live, Set<String> dead,
			Map<String, Set<String>> postersOf, Set<String> deadEvents, Set<String> viaCarrier, Set<String> viaKernel,
			Set<String> kernelDelivered, Set<String> guestPosted, Map<String, List<String>> witness,
			Map<String, Set<String>> postsThrough, Set<String> kernelConstructs) {

		public Census {
			declared = List.copyOf(declared);
			live = Set.copyOf(live);
			dead = Set.copyOf(dead);
			postersOf = Map.copyOf(postersOf);
			deadEvents = Set.copyOf(deadEvents);
			viaCarrier = Set.copyOf(viaCarrier);
			viaKernel = Set.copyOf(viaKernel);
			kernelDelivered = Set.copyOf(kernelDelivered);
			guestPosted = Set.copyOf(guestPosted);
			witness = Map.copyOf(witness);
			postsThrough = Map.copyOf(postsThrough);
			kernelConstructs = Set.copyOf(kernelConstructs);
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
	 * Both families' hook classes: the classes whose public static methods post the families' game events, which the
	 * merged game calls and which this census counts. The staged test censuses exactly these, and {@link
	 * GuestEventPosters} asks a guest's classes for calls into them.
	 */
	public static final Set<String> HOOK_CLASSES = Set.of(
			"net/minecraftforge/event/ForgeEventFactory",
			"net/minecraftforge/client/event/ForgeEventFactoryClient",
			"net/minecraftforge/common/ForgeHooks",
			"net/minecraftforge/client/ForgeHooksClient",
			"net/neoforged/neoforge/event/EventHooks",
			"net/neoforged/neoforge/common/CommonHooks",
			"net/neoforged/neoforge/client/ClientHooks");

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
	 * counted any caller in a carrier as that. But a caller nothing reaches reaches nothing: MinecraftForge's
	 * {@code ForgeHooks.onLivingBreathe} calls {@code ForgeEventFactory.onLivingBreathe}, and the merged game calls neither,
	 * so a MinecraftForge mod's breathe listener never ran while the census called its event posted. That was fixed for a
	 * caller that is itself a hook; any other caller (an instance method, a lambda body, a static initializer, a class that
	 * is not a hook class) still counted outright, and {@code IForgeServerPlayer.openMenu} kept MinecraftForge's
	 * container-open event "posted" although nothing the merged game runs calls it. So every carrier method is followed
	 * now: reached when something reached calls it or the runtime enters it, to a fixed point ({@link CarrierCallGraph}).
	 * Its rules err towards reaching more, so the census still errs towards calling a dead event live.
	 *
	 * <h2>The kernel</h2>
	 *
	 * <p>{@code kernelRoots} (class directories or jars) are the kernel's own classes: a dead hook they call, or a dead
	 * event they construct, is one the kernel delivers — a call made by reflection included, which is how its boot side
	 * reaches a family method ({@link CarrierCallGraph}). That does not make the event posted by the merged game — {@link
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
		return all(hookClasses, baseJars, carrierJars, kernelRoots, null);
	}

	/**
	 * The same, with the game's classes read as the kernel loads them: each class of {@code baseJars} is handed to
	 * {@code loadTime} (internal name, staged bytes → the bytes that are defined) before its calls are counted. See the
	 * class javadoc; null reads the staged bytes as they are.
	 */
	public static Map<String, Census> all(Map<String, Path> hookClasses, List<Path> baseJars, List<Path> carrierJars,
			List<Path> kernelRoots, java.util.function.BiFunction<String, byte[], byte[]> loadTime) throws IOException {
		return all(hookClasses, baseJars, carrierJars, kernelRoots, loadTime, List.of());
	}

	/**
	 * The same, told each family's own patched game ({@code nativeGames}), so that a public carrier method one of them
	 * calls is read as the game's entry rather than as API a guest calls ({@link Census#guestPosted}): MinecraftForge's
	 * patched {@code LiquidBlock} calls {@code FluidInteractionRegistry.canInteract}, so a merged game that does not call it
	 * has lost a path, and no guest is assumed to take it instead.
	 */
	public static Map<String, Census> all(Map<String, Path> hookClasses, List<Path> baseJars, List<Path> carrierJars,
			List<Path> kernelRoots, java.util.function.BiFunction<String, byte[], byte[]> loadTime, List<Path> nativeGames)
			throws IOException {
		Map<String, ClassNode> hookNodes = new LinkedHashMap<>();
		Map<Path, Map<String, String[]>> hierarchies = new java.util.HashMap<>();
		for (Map.Entry<String, Path> e : hookClasses.entrySet()) {
			ClassNode hooks = read(e.getValue(), e.getKey() + ".class");
			if (hooks == null) throw new IOException(e.getKey() + " is not in " + e.getValue());
			hookNodes.put(e.getKey(), hooks);
			if (!hierarchies.containsKey(e.getValue())) hierarchies.put(e.getValue(), hierarchy(e.getValue()));
		}
		Map<String, List<String>> declaredOf = new LinkedHashMap<>();
		for (Map.Entry<String, ClassNode> e : hookNodes.entrySet()) {
			List<String> declared = new java.util.ArrayList<>();
			for (MethodNode m : e.getValue().methods) if (isHook(m)) declared.add(m.name + m.desc);
			declared.sort(String::compareTo);
			declaredOf.put(e.getKey(), declared);
		}

		// The carriers: the runtimes handed in, and the jars declaring the hook classes.
		List<Path> graphJars = new java.util.ArrayList<>(new java.util.LinkedHashSet<>(carrierJars));
		for (Path jar : hookClasses.values()) if (!graphJars.contains(jar)) graphJars.add(jar);
		CarrierCallGraph graph = CarrierCallGraph.of(graphJars);
		// The game's calls (a copy of a hook class handed in as a base calling its own hooks is not the game reaching them),
		// then the kernel's: compiled, and by reflection.
		CarrierCallGraph.Body game = new CarrierCallGraph.Body();
		for (Path jar : baseJars) graph.readBodies(jar, hookClasses.keySet(), loadTime, game, true, false);
		graph.markScannedBodies();
		CarrierCallGraph.Body kernel = new CarrierCallGraph.Body();
		for (Path root : kernelRoots) graph.readBodies(root, Set.of(), null, kernel, false, true);
		graph.resolveReflection(kernel);
		Set<String> gameCalls = new java.util.HashSet<>();
		for (CarrierCallGraph.Ref ref : game.refs) gameCalls.add(ref.id());
		Set<String> kernelNews = kernel.constructs;

		Map<CarrierCallGraph.Body, String> roots = new LinkedHashMap<>();
		roots.put(game, CarrierCallGraph.GAME);
		CarrierCallGraph.Reach byGame = graph.reach(roots);
		roots.put(kernel, CarrierCallGraph.KERNEL);
		CarrierCallGraph.Reach withKernel = graph.reach(roots);
		roots.put(graph.publicApi(hookClasses.keySet(), CarrierCallGraph.entries(graphJars, nativeGames)), CarrierCallGraph.GUEST);
		CarrierCallGraph.Reach withGuests = graph.reach(roots);
		// What the kernel's own code posts: its constructions, and those of the carrier methods it alone reaches.
		Set<String> kernelConstructs = new java.util.HashSet<>(kernelNews);
		kernelConstructs.addAll(graph.reach(Map.of(kernel, CarrierCallGraph.KERNEL), false).constructed());

		// Each hook → the events it posts, through the hooks it calls too (GuestEventPosters' rule).
		Map<String, Set<String>> constructsOf = new java.util.HashMap<>(), hookCalls = new java.util.HashMap<>();
		for (Map.Entry<String, ClassNode> e : hookNodes.entrySet()) {
			String namespace = namespaceOf(e.getKey());
			Map<String, String[]> hierarchy = hierarchies.get(hookClasses.get(e.getKey()));
			for (MethodNode m : e.getValue().methods) {
				if (!isHook(m) || m.instructions == null) continue;
				String gate = e.getKey() + "." + m.name + m.desc;
				for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (insn.getOpcode() == Opcodes.NEW && insn instanceof TypeInsnNode t && t.desc.startsWith(namespace)
							&& isEvent(t.desc, namespace, hierarchy)) {
						constructsOf.computeIfAbsent(gate, k -> new TreeSet<>()).add(t.desc);
					}
					callees(insn, callee -> {
						if (!callee.equals(gate) && hookClasses.containsKey(callee.substring(0, callee.indexOf('.')))) {
							hookCalls.computeIfAbsent(gate, k -> new java.util.HashSet<>()).add(callee);
						}
					});
				}
			}
		}

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
			Map<String, List<String>> witness = new TreeMap<>();
			for (String key : declared) {
				String gate = hookClass + "." + key;
				if (gameCalls.contains(gate)) {
					live.add(key);
					continue;
				}
				dead.add(key);
				if (byGame.reached(gate)) {
					viaCarrier.add(key);
					witness.put(key, byGame.chain(gate));
				} else if (withKernel.reached(gate)) {
					viaKernel.add(key);
					witness.put(key, withKernel.chain(gate));
				} else if (withGuests.reached(gate)) {
					witness.put(key, withGuests.chain(gate));
				}
			}
			Map<String, Set<String>> postsThrough = new TreeMap<>();
			for (String key : declared) {
				Set<String> events = new TreeSet<>(), seen = new java.util.HashSet<>();
				java.util.ArrayDeque<String> work = new java.util.ArrayDeque<>(List.of(hookClass + "." + key));
				while (!work.isEmpty()) {
					String at = work.removeFirst();
					if (!seen.add(at)) continue;
					events.addAll(constructsOf.getOrDefault(at, Set.of()));
					work.addAll(hookCalls.getOrDefault(at, Set.of()));
				}
				if (!events.isEmpty()) postsThrough.put(key, Set.copyOf(events));
			}
			Set<String> deadEvents = new TreeSet<>(), kernelDelivered = new TreeSet<>(), guestPosted = new TreeSet<>();
			for (Map.Entry<String, Set<String>> event : posters.entrySet()) {
				boolean reached = false, byKernel = kernelNews.contains(event.getKey()), byGuest = false;
				for (String poster : event.getValue()) {
					String gate = hookClass + "." + poster;
					if (gameCalls.contains(gate) || byGame.reached(gate)) reached = true;
					if (withKernel.reached(gate)) byKernel = true;
					if (withGuests.reached(gate)) byGuest = true;
				}
				if (reached) continue;
				deadEvents.add(event.getKey());
				if (byKernel) kernelDelivered.add(event.getKey());
				else if (byGuest) guestPosted.add(event.getKey());
			}
			Map<String, Set<String>> frozen = new LinkedHashMap<>();
			posters.forEach((k, v) -> frozen.put(k, Set.copyOf(v)));
			out.put(hookClass, new Census(hookClass, declared, live, dead, frozen, deadEvents, viaCarrier, viaKernel,
					kernelDelivered, guestPosted, witness, postsThrough, kernelConstructs));
		}
		return out;
	}

	/** A public static, non-synthetic, non-bridge, non-initializer method: what a hook class offers as a hook. */
	static boolean isHook(MethodNode m) {
		if ((m.access & Opcodes.ACC_STATIC) == 0 || (m.access & Opcodes.ACC_PUBLIC) == 0) return false;
		if ((m.access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0) return false;
		return !m.name.startsWith("<");
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


	/**
	 * Every method {@code insn} names as one it calls, as {@code owner.name+desc}: the target of an {@code invoke}, and each
	 * method handle an {@code invokedynamic} or an {@code ldc} carries (a method reference or a lambda's implementation,
	 * which is how a listener is handed a hook without an {@code invoke} naming it).
	 */
	static void callees(AbstractInsnNode insn, java.util.function.Consumer<String> out) {
		if (insn instanceof MethodInsnNode mi) {
			out.accept(mi.owner + "." + mi.name + mi.desc);
		} else if (insn instanceof InvokeDynamicInsnNode indy) {
			handle(indy.bsm, out);
			if (indy.bsmArgs != null) for (Object arg : indy.bsmArgs) handle(arg, out);
		} else if (insn instanceof LdcInsnNode ldc) {
			handle(ldc.cst, out);
		}
	}

	private static void handle(Object value, java.util.function.Consumer<String> out) {
		if (value instanceof Handle h && h.getTag() >= Opcodes.H_INVOKEVIRTUAL) out.accept(h.getOwner() + "." + h.getName() + h.getDesc());
	}



	/** Each class in a jar or under a class directory; a root that is neither is skipped. */
	private static void forEachClass(Path root, java.util.function.Consumer<ClassNode> action) throws IOException {
		forEachClass(root, null, action);
	}

	/** The same, each class's bytes handed through {@code loadTime} first when there is one. */
	private static void forEachClass(Path root, java.util.function.BiFunction<String, byte[], byte[]> loadTime,
			java.util.function.Consumer<ClassNode> action) throws IOException {
		if (Files.isDirectory(root)) {
			try (var walk = Files.walk(root)) {
				for (Path file : (Iterable<Path>) walk.filter(f -> f.toString().endsWith(".class"))::iterator) {
					action.accept(node(Files.readAllBytes(file), loadTime));
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
				try (InputStream in = zf.getInputStream(e)) {
					action.accept(node(in.readAllBytes(), loadTime));
				}
			}
		}
	}

	private static ClassNode node(byte[] bytes, java.util.function.BiFunction<String, byte[], byte[]> loadTime) {
		if (loadTime != null) {
			byte[] loaded = loadTime.apply(new ClassReader(bytes).getClassName(), bytes);
			if (loaded != null) bytes = loaded;
		}
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
		return cn;
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
	 * One place an event is posted from: a method whose calls reach a hook constructing the event.
	 *
	 * @param caller     {@code owner#name+desc}
	 * @param clientOnly the method or its class carries its family's {@code @OnlyIn(Dist.CLIENT)} — the marker the
	 *                   families' merge of the client and server jars puts on what only the client jar has, so a
	 *                   dedicated server does not have it
	 * @param carrier    the caller is in a carrier and is not itself a hook (an instance method, a lambda): where that
	 *                   runs is beyond a call-site count
	 */
	public record PostingSite(String caller, boolean clientOnly, boolean carrier) {
	}

	/**
	 * Event → the methods of {@code baseJars} whose own calls reach a hook constructing it: a call to a hook that constructs
	 * it, or to a hook whose carrier body reaches one that does; and the carrier methods that are not hooks and call one.
	 *
	 * <p>This is what answers WHERE an event is posted, and so on which side: an event whose every posting site is
	 * client-only is posted on the client alone, and a listener for it on a dedicated server waits for nothing under its
	 * own loader either.
	 */
	public static Map<String, Set<PostingSite>> postingSites(Map<String, Path> hookClasses, List<Path> baseJars,
			List<Path> carrierJars) throws IOException {
		Map<String, ClassNode> hookNodes = new LinkedHashMap<>();
		Map<Path, Map<String, String[]>> hierarchies = new java.util.HashMap<>();
		Set<String> gates = new java.util.HashSet<>();
		for (Map.Entry<String, Path> e : hookClasses.entrySet()) {
			ClassNode hooks = read(e.getValue(), e.getKey() + ".class");
			if (hooks == null) throw new IOException(e.getKey() + " is not in " + e.getValue());
			hookNodes.put(e.getKey(), hooks);
			if (!hierarchies.containsKey(e.getValue())) hierarchies.put(e.getValue(), hierarchy(e.getValue()));
			for (MethodNode m : hooks.methods) if (isHook(m)) gates.add(e.getKey() + "." + m.name + m.desc);
		}
		// event -> the hooks constructing it
		Map<String, Set<String>> posters = new TreeMap<>();
		for (Map.Entry<String, ClassNode> e : hookNodes.entrySet()) {
			String namespace = namespaceOf(e.getKey());
			Map<String, String[]> hierarchy = hierarchies.get(hookClasses.get(e.getKey()));
			for (MethodNode m : e.getValue().methods) {
				if (!isHook(m) || m.instructions == null) continue;
				for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (insn.getOpcode() == Opcodes.NEW && insn instanceof TypeInsnNode t && t.desc.startsWith(namespace)
							&& isEvent(t.desc, namespace, hierarchy)) {
						posters.computeIfAbsent(t.desc, k -> new TreeSet<>()).add(e.getKey() + "." + m.name + m.desc);
					}
				}
			}
		}
		// gate -> the gates it calls; gate -> the sites calling it (non-gate carrier methods, and the game's methods)
		Map<String, Set<String>> gateEdges = new java.util.HashMap<>();
		Map<String, Set<PostingSite>> callers = new java.util.HashMap<>();
		for (Path jar : carrierJars) {
			forEachClass(jar, cn -> {
				for (MethodNode m : cn.methods) {
					if (m.instructions == null) continue;
					String caller = cn.name + "." + m.name + m.desc;
					boolean gate = gates.contains(caller);
					PostingSite site = new PostingSite(cn.name + "#" + m.name + m.desc, false, true);
					for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
						callees(insn, callee -> {
							if (callee.equals(caller) || !gates.contains(callee)) return;
							if (gate) gateEdges.computeIfAbsent(caller, k -> new java.util.HashSet<>()).add(callee);
							else callers.computeIfAbsent(callee, k -> new java.util.HashSet<>()).add(site);
						});
					}
				}
			});
		}
		for (Path jar : baseJars) {
			forEachClass(jar, cn -> {
				if (hookClasses.containsKey(cn.name)) return;
				boolean clientClass = clientOnly(cn.visibleAnnotations) || clientOnly(cn.invisibleAnnotations);
				for (MethodNode m : cn.methods) {
					if (m.instructions == null) continue;
					PostingSite site = new PostingSite(cn.name + "#" + m.name + m.desc,
							clientClass || clientOnly(m.visibleAnnotations) || clientOnly(m.invisibleAnnotations), false);
					for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
						callees(insn, callee -> {
							if (gates.contains(callee)) callers.computeIfAbsent(callee, k -> new java.util.HashSet<>()).add(site);
						});
					}
				}
			});
		}
		Map<String, Set<PostingSite>> out = new TreeMap<>();
		for (Map.Entry<String, Set<String>> event : posters.entrySet()) {
			// Every gate that reaches a poster, the posters included: followed backwards to a fixed point.
			Set<String> reaching = new java.util.HashSet<>(event.getValue());
			boolean grew = true;
			while (grew) {
				grew = false;
				for (Map.Entry<String, Set<String>> edge : gateEdges.entrySet()) {
					if (reaching.contains(edge.getKey())) continue;
					for (String callee : edge.getValue()) {
						if (reaching.contains(callee)) {
							reaching.add(edge.getKey());
							grew = true;
							break;
						}
					}
				}
			}
			Set<PostingSite> sites = new java.util.HashSet<>();
			for (String gate : reaching) sites.addAll(callers.getOrDefault(gate, Set.of()));
			out.put(event.getKey(), Set.copyOf(sites));
		}
		return out;
	}

	/** Whether {@code annotations} hold a family's {@code @OnlyIn(Dist.CLIENT)}. */
	private static boolean clientOnly(List<org.objectweb.asm.tree.AnnotationNode> annotations) {
		if (annotations == null) return false;
		for (org.objectweb.asm.tree.AnnotationNode a : annotations) {
			if (!a.desc.endsWith("/api/distmarker/OnlyIn;") || a.values == null) continue;
			for (int i = 0; i + 1 < a.values.size(); i += 2) {
				if ("value".equals(a.values.get(i)) && a.values.get(i + 1) instanceof String[] e && e.length == 2
						&& e[0].endsWith("/api/distmarker/Dist;") && "CLIENT".equals(e[1])) {
					return true;
				}
			}
		}
		return false;
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
		return erosion(Set.of(hookClass), before, after, null).getOrDefault(hookClass, List.of());
	}

	/**
	 * The same for several hook classes of one family in one pass over each jar, with the merged base read as the kernel
	 * loads it ({@code afterLoadTime}, as {@link #all}): a merged-base repair that sends a hook call elsewhere takes that call
	 * site away ({@code routePlaceItemHookToNeoForge} takes {@code ItemStack.useOn}'s {@code ForgeHooks.onPlaceItemIntoWorld}).
	 *
	 * @return hook class → its hooks' erosion, every hook the ecosystem's own game calls listed
	 */
	public static Map<String, List<Erosion>> erosion(Set<String> hookClasses, Path before, Path after,
			java.util.function.BiFunction<String, byte[], byte[]> afterLoadTime) throws IOException {
		Map<String, Map<String, Integer>> was = callSites(before, hookClasses, null);
		Map<String, Map<String, Integer>> is = callSites(after, hookClasses, afterLoadTime);
		Map<String, List<Erosion>> out = new TreeMap<>();
		for (String hookClass : hookClasses) {
			List<Erosion> eroded = new java.util.ArrayList<>();
			Map<String, Integer> kept = is.getOrDefault(hookClass, Map.of());
			for (Map.Entry<String, Integer> e : was.getOrDefault(hookClass, Map.of()).entrySet()) {
				eroded.add(new Erosion(e.getKey(), e.getValue(), kept.getOrDefault(e.getKey(), 0)));
			}
			eroded.sort(java.util.Comparator.comparing(Erosion::hook));
			out.put(hookClass, List.copyOf(eroded));
		}
		return out;
	}

	/** {@code name+desc} → how many instructions in {@code jar} call it on {@code hookClass} (or hand it out as a method handle). */
	static Map<String, Integer> callSites(Path jar, String hookClass) throws IOException {
		return callSites(jar, Set.of(hookClass), null).getOrDefault(hookClass, new TreeMap<>());
	}

	/** The same for each of {@code hookClasses}, each class of {@code jar} read through {@code loadTime} first when there is one. */
	static Map<String, Map<String, Integer>> callSites(Path jar, Set<String> hookClasses,
			java.util.function.BiFunction<String, byte[], byte[]> loadTime) throws IOException {
		Map<String, Map<String, Integer>> out = new TreeMap<>();
		if (!Files.isRegularFile(jar)) return out;
		forEachClass(jar, loadTime, cn -> {
			// The hook class calling its own hooks is not the game reaching them, the same rule as of().
			if (hookClasses.contains(cn.name)) return;
			for (MethodNode m : cn.methods) {
				if (m.instructions == null) continue;
				for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					callees(insn, callee -> {
						int dot = callee.indexOf('.');
						String owner = callee.substring(0, dot);
						if (hookClasses.contains(owner)) {
							out.computeIfAbsent(owner, k -> new TreeMap<>()).merge(callee.substring(dot + 1), 1, Integer::sum);
						}
					});
				}
			}
		});
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
