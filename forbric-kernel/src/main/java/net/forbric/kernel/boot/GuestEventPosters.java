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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import net.forbric.kernel.util.Caught;

/**
 * Which of a set of game events a loaded guest posts itself.
 *
 * <h2>Why the dead-event audit has to ask</h2>
 *
 * <p>{@link HookCallSiteCensus} counts who calls a family's hooks in the game and in the families' runtimes; a mod is in
 * neither. But a hook is public API, and some mods call one: Tombstone replaces the vanilla phantom spawner with its own
 * in {@code LevelEvent.Load}, and its spawner calls {@code ForgeEventFactory.onPlayerSpawnPhantom}, so in a pack with
 * Tombstone MinecraftForge's {@code PlayerSpawnPhantomsEvent} IS posted, although the merged game never posts it. Naming
 * every other mod that listens for it DEGRADED, "never posts", was then a false accusation the census could not see.
 *
 * <p>So before {@link DeadEventAudit} names a mod for a {@link DeadEventAudit#DEAD} event, the loaded jars are asked
 * whether any of their classes posts it: calls (or holds a method reference to) a hook that constructs the event —
 * directly or through another hook of the same classes — or constructs the event itself. Which hooks those are is read
 * from the hook classes on the game's class path, not written down. A guest that posts the event posts it on its own
 * paths and not necessarily on the game's, so this can only make the audit say less; that is the side the audit errs on.
 */
public final class GuestEventPosters {

	private GuestEventPosters() {
	}

	/**
	 * For each of {@code events}, the jars whose classes post it ({@code outer.jar!/META-INF/jarjar/inner.jar} for a nested
	 * one). An event nobody posts has no key.
	 *
	 * @param hookClassBytes a hook class's bytes by internal name, null when the class path has none
	 * @param jars           the guests' jars, each read with its nested {@code META-INF/jars} and {@code META-INF/jarjar}
	 */
	public static Map<String, Set<String>> find(Set<String> events, Function<String, byte[]> hookClassBytes, List<Path> jars) {
		Map<String, Set<String>> out = new TreeMap<>();
		if (events.isEmpty()) return out;
		Map<String, Set<String>> eventsOfPoster = new HashMap<>();
		posters(events, hookClassBytes).forEach((event, posters) -> {
			for (String poster : posters) eventsOfPoster.computeIfAbsent(poster, k -> new TreeSet<>()).add(event);
		});
		// The classes a class must name to post one: the events themselves, or the owner of a hook that posts one.
		Set<String> named = new HashSet<>(events);
		for (String poster : eventsOfPoster.keySet()) named.add(poster.substring(0, poster.indexOf('.')));
		Scan scan = new Scan(events, eventsOfPoster, named, out);
		for (Path jar : jars) {
			if (!Files.isRegularFile(jar)) continue;
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				var entries = zip.entries();
				while (entries.hasMoreElements()) {
					ZipEntry entry = entries.nextElement();
					try (InputStream in = zip.getInputStream(entry)) {
						scan.entry(jar.getFileName().toString(), entry.getName(), in);
					}
				}
			} catch (IOException | RuntimeException unreadable) {
				// A jar that cannot be read posts nothing as far as this can tell; the audit then accuses as before.
				Caught.warn(unreadable, "[Forbric/DeadEvents] could not read %s for the events it posts", jar);
			}
		}
		return out;
	}

	/**
	 * Event → the hooks that post it: those whose body constructs it, and those that call (or hand out a method reference
	 * to) one that does, followed to a fixed point. Read from {@link HookCallSiteCensus#HOOK_CLASSES}.
	 */
	static Map<String, Set<String>> posters(Set<String> events, Function<String, byte[]> hookClassBytes) {
		Map<String, Set<String>> constructs = new HashMap<>(), calls = new HashMap<>();
		for (String hookClass : HookCallSiteCensus.HOOK_CLASSES) {
			byte[] bytes = hookClassBytes.apply(hookClass);
			if (bytes == null) continue;
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			for (MethodNode m : node.methods) {
				if (!HookCallSiteCensus.isHook(m) || m.instructions == null) continue;
				String hook = node.name + "." + m.name + m.desc;
				for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (insn.getOpcode() == Opcodes.NEW && insn instanceof TypeInsnNode t && events.contains(t.desc)) {
						constructs.computeIfAbsent(hook, k -> new HashSet<>()).add(t.desc);
					}
					HookCallSiteCensus.callees(insn, callee -> {
						if (!callee.equals(hook) && HookCallSiteCensus.HOOK_CLASSES.contains(callee.substring(0, callee.indexOf('.')))) {
							calls.computeIfAbsent(hook, k -> new HashSet<>()).add(callee);
						}
					});
				}
			}
		}
		Map<String, Set<String>> out = new TreeMap<>();
		for (String event : events) {
			Set<String> posters = new TreeSet<>();
			constructs.forEach((hook, built) -> {
				if (built.contains(event)) posters.add(hook);
			});
			boolean grew = !posters.isEmpty();
			while (grew) {
				grew = false;
				for (Map.Entry<String, Set<String>> edge : calls.entrySet()) {
					if (posters.contains(edge.getKey())) continue;
					for (String callee : edge.getValue()) {
						if (posters.contains(callee)) {
							posters.add(edge.getKey());
							grew = true;
							break;
						}
					}
				}
			}
			if (!posters.isEmpty()) out.put(event, Set.copyOf(posters));
		}
		return out;
	}

	/** One pass over the guests' entries. */
	private record Scan(Set<String> events, Map<String, Set<String>> eventsOfPoster, Set<String> named,
			Map<String, Set<String>> out) {

		void entry(String jar, String name, InputStream in) throws IOException {
			if (name.endsWith(".class")) {
				byte[] bytes = in.readAllBytes();
				try {
					ClassReader reader = new ClassReader(bytes);
					if (namesAny(reader)) classFile(jar, reader);
				} catch (RuntimeException unparsable) {
					Caught.warn(unparsable, "[Forbric/DeadEvents] could not parse %s in %s for the events it posts", name, jar);
				}
			} else if (name.endsWith(".jar") && (name.startsWith("META-INF/jars/") || name.startsWith("META-INF/jarjar/"))) {
				String nested = jar + "!/" + name;
				try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(in.readAllBytes()))) {
					for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
						entry(nested, entry.getName(), zip);
					}
				}
			}
		}

		private void classFile(String jar, ClassReader reader) {
			ClassNode node = new ClassNode();
			reader.accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			for (MethodNode m : node.methods) {
				if (m.instructions == null) continue;
				for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (insn.getOpcode() == Opcodes.NEW && insn instanceof TypeInsnNode t && events.contains(t.desc)) {
						out.computeIfAbsent(t.desc, k -> new TreeSet<>()).add(jar);
					}
					HookCallSiteCensus.callees(insn, callee -> {
						for (String event : eventsOfPoster.getOrDefault(callee, Set.of())) {
							out.computeIfAbsent(event, k -> new TreeSet<>()).add(jar);
						}
					});
				}
			}
		}

		/**
		 * Whether the class's constant pool names a class it would have to name to post one of the events: every
		 * {@code NEW}, {@code invoke} and method handle names its owner through a CONSTANT_Class entry. Only the pool is
		 * read, so a class that names none of them costs no parse.
		 */
		private boolean namesAny(ClassReader reader) {
			char[] buffer = new char[reader.getMaxStringLength()];
			for (int item = 1; item < reader.getItemCount(); item++) {
				int offset = reader.getItem(item);
				if (offset == 0 || reader.readByte(offset - 1) != CONSTANT_CLASS) continue;
				if (named.contains(reader.readUTF8(offset, buffer))) return true;
			}
			return false;
		}

		/** The constant pool tag of a CONSTANT_Class entry (JVMS 4.4.1). */
		private static final int CONSTANT_CLASS = 7;
	}
}
