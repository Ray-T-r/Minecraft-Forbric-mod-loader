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
 * <p>Two honest limits, both of which make this UNDER-report deadness (it can call a dead event live, never the
 * reverse, so a finding here is always real):
 * <ul>
 *   <li>a hook handed an already-constructed event posts an event it never {@code NEW}s, so that event looks
 *       like it has no posters at all and is simply not judged;</li>
 *   <li>a call site the merge left in unreachable code still counts as a call site.</li>
 * </ul>
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
	 * @param deadEvents  events every one of whose posters is dead
	 */
	public record Census(String hookClass, List<String> declared, Set<String> live, Set<String> dead,
			Map<String, Set<String>> postersOf, Set<String> deadEvents) {

		public Census {
			declared = List.copyOf(declared);
			live = Set.copyOf(live);
			dead = Set.copyOf(dead);
			postersOf = Map.copyOf(postersOf);
			deadEvents = Set.copyOf(deadEvents);
		}

		/** Live events: posted by at least one hook the base still calls. */
		public Set<String> liveEvents() {
			Set<String> out = new TreeSet<>(postersOf.keySet());
			out.removeAll(deadEvents);
			return out;
		}

		/**
		 * The one line a gate greps. Shaped like the other censuses: the denominator first, so a run that
		 * scanned nothing cannot be mistaken for a run that found nothing.
		 */
		public String summary() {
			return "[Forbric/Hooks] " + hookClass + ": " + declared.size() + " declared, " + live.size()
					+ " with a call site, " + dead.size() + " dead; events: " + postersOf.size()
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
		ClassNode hooks = read(carrierJar, hookClass + ".class");
		if (hooks == null) throw new IOException(hookClass + " is not in " + carrierJar);
		// Only the hook's OWN ecosystem namespace counts as an event it posts. Without this, every ArrayList,
		// Vec3 and StringBuilder a hook body allocates comes back as an "event nothing posts" — 171 of them on
		// the real base, which buries the fourteen that are real and makes the count worse than no count.
		String eventNamespace = namespaceOf(hookClass);

		List<String> declared = new java.util.ArrayList<>();
		Map<String, Set<String>> posters = new TreeMap<>();
		for (MethodNode m : hooks.methods) {
			if ((m.access & Opcodes.ACC_STATIC) == 0) continue;
			if ((m.access & Opcodes.ACC_PUBLIC) == 0) continue;
			if ((m.access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0) continue;
			if (m.name.startsWith("<")) continue;
			String key = m.name + m.desc;
			declared.add(key);
			if (m.instructions == null) continue;
			for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn.getOpcode() == Opcodes.NEW && insn instanceof TypeInsnNode t
						&& t.desc.startsWith(eventNamespace)) {
					posters.computeIfAbsent(t.desc, k -> new TreeSet<>()).add(key);
				}
			}
		}
		declared.sort(String::compareTo);

		Set<String> referenced = new LinkedHashSet<>();
		for (Path jar : baseJars) {
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
					// The hook class calling its own hooks is not the game reaching them.
					if (cn.name.equals(hookClass)) continue;
					for (MethodNode m : cn.methods) {
						if (m.instructions == null) continue;
						for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
							if (insn instanceof MethodInsnNode mi && mi.owner.equals(hookClass)) {
								referenced.add(mi.name + mi.desc);
							}
						}
					}
				}
			}
		}

		Set<String> live = new TreeSet<>();
		Set<String> dead = new TreeSet<>();
		for (String key : declared) (referenced.contains(key) ? live : dead).add(key);

		Set<String> deadEvents = new TreeSet<>();
		for (Map.Entry<String, Set<String>> e : posters.entrySet()) {
			if (dead.containsAll(e.getValue())) deadEvents.add(e.getKey());
		}
		Map<String, Set<String>> frozen = new LinkedHashMap<>();
		posters.forEach((k, v) -> frozen.put(k, Set.copyOf(v)));
		return new Census(hookClass, declared, live, dead, frozen, deadEvents);
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
