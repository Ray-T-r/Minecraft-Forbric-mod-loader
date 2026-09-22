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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.api.GameEventBridge;

/**
 * Joins {@link HookCallSiteCensus}'s dead events to the jars that are actually waiting on them.
 *
 * <h2>Why the join, and not the count</h2>
 *
 * <p>The census says 140 of {@code ForgeEventFactory}'s 160 hooks have no call site. That number is true and
 * almost useless on its own: most of those events nobody in a given pack subscribes to, and the handful that
 * someone does subscribe to are the ones a player experiences as "this mod does nothing". Repairing them in
 * census order would be repairing them in an order chosen by the merge.
 *
 * <p>So this asks the second question — for each dead event, which jars name it? — and sorts by the answer. The
 * evidence rule is the one {@code fapi-usage.py} settled on for the same shape of question: a reference in the
 * CONSTANT_Class pool is use; a resource string or a dependency declaration is not. Nested {@code META-INF/jars}
 * count with their parent, because a bundled library waiting on a dead event fails exactly as loudly.
 *
 * <p>This is deliberately a REPORT, not an assertion. What it lists depends on which mods are installed, so
 * pinning it would go red on every pack change while saying nothing about the kernel. The assertions live in
 * {@code HookCallSiteCensusStagedTest}, on the relationships that do not depend on a pack.
 */
public final class DeadHookWorklist {

	/** One dead event and who is waiting for it. */
	public record Waiting(String event, List<String> jars, Set<String> posters) {
		public Waiting {
			jars = List.copyOf(jars);
			posters = Set.copyOf(posters);
		}
	}

	private DeadHookWorklist() {
	}

	/**
	 * @param deadEvents internal names of events nothing posts
	 * @param modJars    the jars to search, each scanned including its nested {@code META-INF/jars}
	 * @return one entry per dead event that at least one jar names, most-waited-on first
	 */
	public static List<Waiting> of(Map<String, Set<String>> postersOf, Set<String> deadEvents, List<Path> modJars)
			throws IOException {
		Map<String, List<String>> waiting = new TreeMap<>();
		for (Path jar : modJars) {
			if (!Files.isRegularFile(jar)) continue;
			Set<String> named = namesIn(jar);
			for (String event : deadEvents) {
				if (named.contains(event)) waiting.computeIfAbsent(event, k -> new ArrayList<>()).add(jar.getFileName().toString());
			}
		}
		List<Waiting> out = new ArrayList<>();
		waiting.forEach((event, jars) ->
				out.add(new Waiting(event, jars, postersOf.getOrDefault(event, Set.of()))));
		out.sort(Comparator.comparingInt((Waiting w) -> -w.jars().size()).thenComparing(Waiting::event));
		return out;
	}

	/**
	 * Whether a bridge already delivers this event, and from which of the two records that say so.
	 *
	 * <p>There are two, and they answer different questions. {@link DeadEventAudit}'s {@code BRIDGED} map is an
	 * audit-suppression table: it exists to stop the audit reporting an event whose bridge is in. {@link
	 * GameEventBridge} is the bridge INVENTORY. They are not the same set — the level-lifecycle and tick bridges
	 * are in the inventory and not in the table — so consulting only the table understates coverage and sends
	 * someone to repair a call site the kernel already replaced.
	 *
	 * <p>The inventory match is by display name, because that is all a bridge records about its event: the
	 * internal name's simple-name chain ({@code LevelEvent$Load} → {@code LevelEvent.Load}) against
	 * {@link GameEventBridge#event()}. That is a heuristic and is labelled as one; the table's answer is exact.
	 */
	private static String coverage(String event) {
		if (DeadEventAudit.BRIDGED.containsKey(event)) return "  [BRIDGED — already delivered]";
		// A redirect into the kernel delivers it just as a bridge would, and a work list that keeps naming
		// finished work is worse than none.
		if (DeadEventAudit.REPAIRED.contains(event)) return "  [REPAIRED — the call site is redirected]";
		String chain = simpleChain(event);
		for (GameEventBridge bridge : GameEventBridge.values()) {
			if (namesTheSameEvent(bridge.event(), chain)) {
				return "  [bridge " + bridge.name() + " looks like it carries this — by name, not by symbol]";
			}
		}
		return "";
	}

	/**
	 * Whether a bridge's display name and a class's simple-name chain are the same event.
	 *
	 * <p>Either may be the shorter one, and the first version of this only allowed one direction. The bridges
	 * name the tick events {@code ServerTickEvent.Post}, while the class chain is
	 * {@code TickEvent.ServerTickEvent.Post} — so three events that ARE bridged came out of the worklist as
	 * "no bridge at all", which is how a work list grows items that are already done.
	 */
	static boolean namesTheSameEvent(String bridgeEvent, String chain) {
		if (bridgeEvent == null || chain == null) return false;
		return bridgeEvent.equals(chain)
				|| bridgeEvent.endsWith("." + chain)
				|| chain.endsWith("." + bridgeEvent);
	}

	/** {@code net/minecraftforge/event/level/LevelEvent$Load} -> {@code LevelEvent.Load}. */
	static String simpleChain(String internalName) {
		int slash = internalName.lastIndexOf('/');
		return (slash < 0 ? internalName : internalName.substring(slash + 1)).replace('$', '.');
	}

	/** Every class named by any class in the jar, recursing into nested mod jars. */
	private static Set<String> namesIn(Path jar) throws IOException {
		Set<String> out = new TreeSet<>();
		try (ZipFile zf = new ZipFile(jar.toFile())) {
			collect(zf, out);
		}
		return out;
	}

	private static void collect(ZipFile zf, Set<String> out) throws IOException {
		var entries = zf.entries();
		List<byte[]> nested = new ArrayList<>();
		while (entries.hasMoreElements()) {
			ZipEntry e = entries.nextElement();
			if (e.getName().endsWith(".class")) {
				try (InputStream in = zf.getInputStream(e)) {
					out.addAll(AbiLinkAudit.namedClasses(in.readAllBytes()));
				} catch (RuntimeException unreadable) {
					// A jar with one unparsable class still answers for its other classes; refusing the whole
					// jar would silently drop a real consumer.
				}
			} else if (e.getName().endsWith(".jar") && e.getName().startsWith("META-INF/jars/")) {
				try (InputStream in = zf.getInputStream(e)) {
					nested.add(in.readAllBytes());
				}
			}
		}
		for (byte[] bytes : nested) {
			Path tmp = Files.createTempFile("forbric-nested", ".jar");
			try {
				Files.write(tmp, bytes);
				try (ZipFile inner = new ZipFile(tmp.toFile())) {
					collect(inner, out);
				}
			} finally {
				Files.deleteIfExists(tmp);
			}
		}
	}

	/**
	 * {@code DeadHookWorklist <merged-base.jar> <mods-dir> <carrier.jar>=<hookClass> ...}
	 *
	 * <p>Prints each hook class's census line, then the dead events someone is waiting for, most-waited-on first.
	 */
	public static void main(String[] args) throws IOException {
		if (args.length < 3) {
			System.err.println("usage: DeadHookWorklist <merged-base.jar> <mods-dir> <carrier.jar>=<hookClass> ...");
			System.exit(2);
		}
		Path base = Path.of(args[0]);
		Path modsDir = Path.of(args[1]);
		// Every carrier named on the command line is also a place a hook can be called from — see the third
		// state in HookCallSiteCensus. Without this the worklist lists events that are delivered.
		List<Path> carriers = new ArrayList<>();
		for (int i = 2; i < args.length; i++) {
			int eq = args[i].lastIndexOf('=');
			if (eq > 0) carriers.add(Path.of(args[i].substring(0, eq)));
		}
		Map<String, Set<String>> posters = new LinkedHashMap<>();
		Set<String> dead = new TreeSet<>();
		Set<String> live = new TreeSet<>();
		for (int i = 2; i < args.length; i++) {
			int eq = args[i].lastIndexOf('=');
			if (eq < 0) {
				System.err.println("expected <carrier.jar>=<hookClass>, got " + args[i]);
				System.exit(2);
			}
			var census = HookCallSiteCensus.of(Path.of(args[i].substring(0, eq)), args[i].substring(eq + 1),
					List.of(base), carriers);
			System.out.println(census.summary());
			census.postersOf().forEach((e, p) -> posters.merge(e, p, (a, b) -> {
				Set<String> both = new TreeSet<>(a);
				both.addAll(b);
				return both;
			}));
			dead.addAll(census.deadEvents());
			live.addAll(census.liveEvents());
		}
		dead.removeAll(live); // posted from another hook class is still posted

		List<Path> jars = new ArrayList<>();
		if (Files.isDirectory(modsDir)) {
			try (var s = Files.list(modsDir)) {
				s.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().forEach(jars::add);
			}
		} else if (Files.isRegularFile(modsDir)) {
			jars.add(modsDir);
		}

		List<Waiting> worklist = of(posters, dead, jars);
		int waitingJars = worklist.stream().mapToInt(w -> w.jars().size()).sum();
		System.out.println("[Forbric/Hooks] worklist: " + dead.size() + " dead event(s) across " + jars.size()
				+ " jar(s); " + worklist.size() + " have someone waiting (" + waitingJars + " jar-references)");
		for (Waiting w : worklist) {
			String covered = coverage(w.event());
			System.out.println("    " + w.jars().size() + "x " + w.event() + covered
					+ "  <- " + String.join(", ", w.jars())
					+ (w.posters().isEmpty() ? "" : "   [posted by " + String.join(", ", w.posters()) + "]"));
		}
		long uncovered = worklist.stream().filter(w -> coverage(w.event()).isEmpty()).count();
		System.out.println("[Forbric/Hooks] of those, " + uncovered + " have no bridge at all — that is the work.");
	}
}
