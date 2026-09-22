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

package net.forbric.tools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
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
 * Which hook each dropped conflict actually cost, and whether anything is waiting for it.
 *
 * <h2>Why the report alone cannot decide anything</h2>
 *
 * <p>{@code merge-conflicts.txt} says a method lost its Forge hook. It does not say WHICH hook, so the thousand
 * lines are a thousand unknowns, and the two obvious responses — flip the arbitration, or repair them by hand in
 * the order they appear — both act without knowing what any single line costs.
 *
 * <p>This names it: read the method's body in MinecraftForge's own patched game and in the merged base, and the
 * hook calls present in the first and absent from the second are what the merge took. Then the same for the
 * NeoForge side, because forcing a method to Forge's body would take those instead — a whitelist entry is a
 * TRADE, not a repair, and it is only worth making when the hook gained has someone waiting and the hook given
 * up does not.
 *
 * <p>Potential consumers are identified by an event class in a mod's constant pool. This is not proof of a
 * registered listener, and absence is not proof that reflection or an unmodelled helper never uses it.
 * Raw losses are reported separately from runtime restoration, which this tool does not assess.
 *
 * <p>Usage: {@code LostHookAttribution <forge-patched.jar> <neo-patched.jar> <merged.jar> <forge-runtime.jar>
 * <neoforge-runtime.jar> <merge-conflicts.txt> <mods-dir>}
 */
public final class LostHookAttribution {

	private static final String[] HOOK_CLASSES = {
			"net/minecraftforge/event/ForgeEventFactory", "net/minecraftforge/client/event/ForgeEventFactoryClient",
			"net/minecraftforge/common/ForgeHooks", "net/minecraftforge/client/ForgeHooksClient",
			"net/neoforged/neoforge/event/EventHooks", "net/neoforged/neoforge/client/ClientHooks",
	};

	private LostHookAttribution() {
	}

	public static void main(String[] args) throws IOException {
		if (args.length < 7) {
			System.err.println("usage: LostHookAttribution <forge-patched.jar> <neo-patched.jar> <merged.jar> "
					+ "<forge-runtime.jar> <neoforge-runtime.jar> <merge-conflicts.txt> <mods-dir>");
			System.exit(2);
		}
		Map<String, ClassNode> forge = load(args[0], "forge-patched");
		Map<String, ClassNode> neo = load(args[1], "neo-patched");
		Map<String, ClassNode> merged = load(args[2], "merged");

		// hook owner#name+desc -> the events it constructs, from both carriers.
		Map<String, Set<String>> eventsOfHook = new HashMap<>();
		for (String carrier : new String[] { args[3], args[4] }) {
			for (String hookClass : HOOK_CLASSES) collectEvents(carrier, hookClass, eventsOfHook);
		}
		Set<String> wanted = eventsNamedByMods(Path.of(args[6]));
		System.out.println("[attribution] mods name " + wanted.size() + " ecosystem event class(es)");

		int judged = 0, noHookFound = 0, unobserved = 0;
		List<String> candidates = new ArrayList<>(), trades = new ArrayList<>();
		List<Conflict> conflicts = conflicts(Path.of(args[5]));
		for (Conflict c : conflicts) {
			MethodNode f = method(forge.get(c.owner()), c.method());
			MethodNode n = method(neo.get(c.owner()), c.method());
			MethodNode m = method(merged.get(c.owner()), c.method());
			if (f == null || n == null || m == null) { unobserved++; continue; }
			judged++;
			MethodNode losing = c.lostFamily().equals("forge") ? f : n;
			MethodNode retained = c.lostFamily().equals("forge") ? n : f;
			Set<String> lost = new TreeSet<>(hookCalls(losing));
			lost.removeAll(hookCalls(m));
			Set<String> kept = new TreeSet<>(hookCalls(retained));
			kept.retainAll(hookCalls(m));
			if (lost.isEmpty()) { noHookFound++; continue; }
			boolean gainWanted = anyWanted(lost, eventsOfHook, wanted);
			boolean giveUpWanted = anyWanted(kept, eventsOfHook, wanted);
			String row = c.owner() + "#" + c.method() + " lost-family=" + c.lostFamily()
					+ " RAW-LOST " + lost + " RETAINED " + kept;
			System.out.println("[attribution] " + row);
			if (gainWanted && !giveUpWanted) candidates.add(row);
			else if (gainWanted) trades.add(row);
		}
		System.out.println("[attribution] conflicts=" + conflicts.size() + " judged=" + judged
				+ " no-modelled-direct-hook=" + noHookFound + " unobserved=" + unobserved);
		System.out.println("[attribution] scope: " + HOOK_CLASSES.length + " hook facades; direct calls and event"
				+ " construction only; event type references are potential consumers, not proof of subscription");
		System.out.println("[attribution] runtime restoration=NOT_ASSESSED; use the effective pipeline/bridge census"
				+ " before treating RAW-LOST as a remaining defect");
		System.out.println("[attribution] CANDIDATES (lost event referenced, no retained event reference observed): " + candidates.size());
		for (String row : candidates) System.out.println("    + " + row);
		System.out.println("[attribution] TRADES (both event types referenced): " + trades.size());
		for (String row : trades) System.out.println("    ~ " + row);
	}

	private static boolean anyWanted(Set<String> hooks, Map<String, Set<String>> eventsOfHook, Set<String> wanted) {
		for (String hook : hooks) {
			for (String event : eventsOfHook.getOrDefault(hook, Set.of())) {
				if (wanted.contains(event)) return true;
			}
		}
		return false;
	}

	/** {@code owner#name+desc} of every modelled hook call in this body. */
	static Set<String> hookCalls(MethodNode m) {
		Set<String> out = new LinkedHashSet<>();
		if (m.instructions == null) return out;
		for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (!(insn instanceof MethodInsnNode mi)) continue;
			for (String hookClass : HOOK_CLASSES) {
				if (mi.owner.equals(hookClass)) out.add(mi.owner + "#" + mi.name + mi.desc);
			}
		}
		return out;
	}

	/** hook {@code owner#name+desc} -> the ecosystem classes its body constructs. */
	private static void collectEvents(String carrier, String hookClass, Map<String, Set<String>> into)
			throws IOException {
		try (ZipFile zf = new ZipFile(carrier)) {
			ZipEntry e = zf.getEntry(hookClass + ".class");
			if (e == null) return;
			ClassNode cn = new ClassNode();
			try (InputStream in = zf.getInputStream(e)) {
				new ClassReader(in.readAllBytes()).accept(cn, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
			}
			for (MethodNode m : cn.methods) {
				if (m.instructions == null) continue;
				for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (insn.getOpcode() == Opcodes.NEW && insn instanceof TypeInsnNode t
							&& (t.desc.startsWith("net/minecraftforge/") || t.desc.startsWith("net/neoforged/"))) {
						into.computeIfAbsent(hookClass + "#" + m.name + m.desc, k -> new TreeSet<>()).add(t.desc);
					}
				}
			}
		}
	}

	/** Ecosystem event classes named in any mod jar's constant pool, nested jars included. */
	private static Set<String> eventsNamedByMods(Path modsDir) throws IOException {
		Set<String> named = new TreeSet<>();
		if (!Files.isDirectory(modsDir)) throw new IOException("mods directory not found: " + modsDir);
		try (var jars = Files.list(modsDir)) {
			for (Path jar : jars.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) {
				try (ZipFile zf = new ZipFile(jar.toFile())) {
					namesIn(zf, named);
				} catch (IOException unreadable) {
					throw new IOException("cannot inventory mod jar: " + jar, unreadable);
				}
			}
		}
		return named;
	}

	private static void namesIn(ZipFile zf, Set<String> into) throws IOException {
		var entries = zf.entries();
		List<byte[]> nested = new ArrayList<>();
		while (entries.hasMoreElements()) {
			ZipEntry e = entries.nextElement();
			if (e.getName().endsWith(".class")) {
				try (InputStream in = zf.getInputStream(e)) {
					ClassReader r = new ClassReader(in.readAllBytes());
					char[] buf = new char[r.getMaxStringLength()];
					for (int i = 1; i < r.getItemCount(); i++) {
						int off = r.getItem(i);
						if (off == 0 || r.readByte(off - 1) != 7) continue;
						String name = r.readUTF8(off, buf);
						if (name == null) continue;
						if (name.startsWith("net/minecraftforge/") || name.startsWith("net/neoforged/")) into.add(name);
					}
				} catch (RuntimeException unparsable) {
					throw new IOException("cannot inventory mod class: " + e.getName(), unparsable);
				}
			} else if (e.getName().endsWith(".jar") && (e.getName().startsWith("META-INF/jars/") || e.getName().startsWith("META-INF/jarjar/"))) {
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
					namesIn(inner, into);
				}
			} catch (IOException unreadable) {
				throw new IOException("cannot inventory nested mod jar", unreadable);
			} finally {
				Files.deleteIfExists(tmp);
			}
		}
	}

	private static MethodNode method(ClassNode cn, String key) {
		if (cn == null) return null;
		for (MethodNode m : cn.methods) {
			if ((m.name + m.desc).equals(key)) return m;
		}
		return null;
	}

	record Conflict(String owner, String method, String lostFamily) { }

	static List<Conflict> conflicts(Path report) throws IOException {
		List<Conflict> out = new ArrayList<>();
		for (String line : Files.readAllLines(report, StandardCharsets.UTF_8)) {
			String family = line.endsWith(" (forge hook lost)") ? "forge"
					: line.endsWith(" (neo hook lost)") ? "neo" : null;
			if (family == null) continue;
			String body = line.substring(0, line.lastIndexOf(" (" )).trim();
			int hash = body.indexOf('#');
			if (hash < 0) continue;
			out.add(new Conflict(body.substring(0, hash), body.substring(hash + 1), family));
		}
		return out;
	}

	private static Map<String, ClassNode> load(String jar, String label) throws IOException {
		Map<String, ClassNode> out = new LinkedHashMap<>();
		try (ZipFile zf = new ZipFile(jar)) {
			var entries = zf.entries();
			while (entries.hasMoreElements()) {
				ZipEntry e = entries.nextElement();
				if (!e.getName().endsWith(".class")) continue;
				ClassNode cn = new ClassNode();
				try (InputStream in = zf.getInputStream(e)) {
					new ClassReader(in.readAllBytes()).accept(cn, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
				}
				out.put(cn.name, cn);
			}
		}
		System.out.println("[attribution] " + label + " : " + out.size() + " classes");
		return out;
	}

	static {
		Map<String, Integer> unused = new TreeMap<>();
		assert unused.isEmpty();
	}
}
