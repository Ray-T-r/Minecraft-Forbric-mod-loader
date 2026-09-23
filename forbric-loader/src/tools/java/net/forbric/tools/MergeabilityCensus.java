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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * How many of the merge's dropped hooks could have been kept mechanically.
 *
 * <h2>The question</h2>
 *
 * <p>When both ecosystems patched the same method, {@link MergedBaseBuilder} keeps one body and drops the
 * other's hook — a thousand times, and the report says only that it happened. "Change the arbitration" is the
 * obvious response and the expensive one: it would rewrite the calibration every repair in the kernel depends
 * on. The cheaper question first is how many of those thousand are even a CHOICE.
 *
 * <p>A method where each side only INSERTED into vanilla's body could, in principle, keep both insertions. A
 * method where either side rewrote what vanilla did cannot: there is no body that is both. So for each dropped
 * hook this asks whether vanilla's instruction sequence still appears, in order, inside each side's — a
 * subsequence test, which is exactly "nothing was removed or replaced, only added".
 *
 * <h2>Why it reports its own trustworthiness first</h2>
 *
 * <p>The three jars come from three different decompile-and-recompile pipelines, so even the parts neither side
 * touched can differ textually. A subsequence test between them is therefore only as meaningful as the
 * normalisation, and a number produced without saying that would be worse than no number.
 *
 * <p>So it calibrates: over methods present in all three and NOT in the conflict list, how often does vanilla's
 * sequence survive as a subsequence? That rate is the ceiling on what the answer below can mean, and it is
 * printed above it.
 *
 * <p>Usage: {@code MergeabilityCensus <vanilla.jar> <forge.jar> <neo.jar> <merge-conflicts.txt>}
 */
public final class MergeabilityCensus {

	private MergeabilityCensus() {
	}

	public static void main(String[] args) throws IOException {
		if (args.length < 4) {
			System.err.println("usage: MergeabilityCensus <vanilla.jar> <forge.jar> <neo.jar> <merge-conflicts.txt>");
			System.exit(2);
		}
		Map<String, ClassNode> vanilla = load(args[0]);
		Map<String, ClassNode> forge = load(args[1]);
		Map<String, ClassNode> neo = load(args[2]);
		List<String[]> conflicts = conflicts(Path.of(args[3]));
		System.out.println("[mergeability] " + conflicts.size() + " dropped hook(s) to judge ("
				+ conflicts.stream().filter(c -> c[2].equals("forge")).count() + " Forge, "
				+ conflicts.stream().filter(c -> c[2].equals("neo")).count() + " NeoForge)");

		// ---- calibration ----
		int calTotal = 0;
		int calForge = 0;
		int calNeo = 0;
		java.util.Set<String> conflicted = new java.util.HashSet<>();
		for (String[] c : conflicts) conflicted.add(c[0] + "#" + c[1]);
		for (Map.Entry<String, ClassNode> e : vanilla.entrySet()) {
			ClassNode f = forge.get(e.getKey());
			ClassNode n = neo.get(e.getKey());
			if (f == null || n == null) continue;
			Map<String, MethodNode> vm = byKey(e.getValue());
			Map<String, MethodNode> fm = byKey(f);
			Map<String, MethodNode> nm = byKey(n);
			for (Map.Entry<String, MethodNode> m : vm.entrySet()) {
				if (conflicted.contains(e.getKey() + "#" + m.getKey())) continue;
				MethodNode fn = fm.get(m.getKey());
				MethodNode nn = nm.get(m.getKey());
				if (fn == null || nn == null) continue;
				List<String> v = normalise(m.getValue());
				if (v.isEmpty()) continue;
				calTotal++;
				if (isSubsequence(v, normalise(fn))) calForge++;
				if (isSubsequence(v, normalise(nn))) calNeo++;
				if (calTotal >= 20000) break;
			}
			if (calTotal >= 20000) break;
		}
		System.out.printf("[mergeability] calibration on %d untouched method(s): vanilla survives as a subsequence "
						+ "in %.1f%% of Forge bodies and %.1f%% of NeoForge bodies%n",
				calTotal, pct(calForge, calTotal), pct(calNeo, calTotal));
		System.out.println("[mergeability] that rate is the ceiling on what the answer below can mean: a low one "
				+ "means the pipelines differ too much for this test, not that the merge is unavoidable.");

		// ---- the answer ----
		int additive = 0;
		int overlapping = 0;
		int unjudged = 0;
		List<String> additiveExamples = new ArrayList<>();
		for (String[] c : conflicts) {
			ClassNode v = vanilla.get(c[0]);
			ClassNode f = forge.get(c[0]);
			ClassNode n = neo.get(c[0]);
			if (v == null || f == null || n == null) {
				unjudged++;
				continue;
			}
			MethodNode vmn = byKey(v).get(c[1]);
			MethodNode fmn = byKey(f).get(c[1]);
			MethodNode nmn = byKey(n).get(c[1]);
			if (vmn == null || fmn == null || nmn == null) {
				unjudged++;
				continue;
			}
			List<String> vs = normalise(vmn);
			if (vs.isEmpty()) {
				unjudged++;
				continue;
			}
			if (isSubsequence(vs, normalise(fmn)) && isSubsequence(vs, normalise(nmn))) {
				additive++;
				if (additiveExamples.size() < 15) additiveExamples.add(c[0] + "#" + c[1]);
			} else {
				overlapping++;
			}
		}
		int judged = additive + overlapping;
		System.out.printf("[mergeability] judged %d, unjudged %d; ADDITIVE (both sides only inserted) %d (%.1f%%), "
						+ "OVERLAPPING (one side rewrote vanilla) %d%n",
				judged, unjudged, additive, pct(additive, judged), overlapping);
		for (String e : additiveExamples) System.out.println("    ADDITIVE  " + e);
	}

	private static double pct(int part, int whole) {
		return whole == 0 ? 0d : 100d * part / whole;
	}

	/**
	 * Every dropped-hook row, as {owner, name+desc, lost family}. Both families and both row forms, through the one
	 * parser {@link LostHookAttribution} uses: this used to match {@code (forge hook lost)} alone, which dropped the
	 * NeoForge losses and the field-init-kept rows, and the question "was it a choice" applies to all of them.
	 */
	static List<String[]> conflicts(Path report) throws IOException {
		List<String[]> out = new ArrayList<>();
		for (LostHookAttribution.Conflict c : LostHookAttribution.conflicts(report)) {
			out.add(new String[] { c.owner(), c.method(), c.lostFamily() });
		}
		return out;
	}

	private static Map<String, MethodNode> byKey(ClassNode cn) {
		Map<String, MethodNode> out = new HashMap<>();
		for (MethodNode m : cn.methods) out.put(m.name + m.desc, m);
		return out;
	}

	/**
	 * Instruction text with everything a recompile is free to change removed: labels, frames, line numbers, and
	 * local variable slots. What is kept is what a hook actually is — the calls, the field accesses, the types
	 * and the constants.
	 */
	private static List<String> normalise(MethodNode m) {
		List<String> out = new ArrayList<>();
		if (m.instructions == null) return out;
		for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			int type = insn.getType();
			if (type == AbstractInsnNode.LABEL || type == AbstractInsnNode.FRAME || type == AbstractInsnNode.LINE) {
				continue;
			}
			int op = insn.getOpcode();
			if (op < 0) continue;
			// The other tools here build against --release 17, so this stays an if-chain rather than a
			// pattern switch.
			String text;
			if (insn instanceof MethodInsnNode mi) text = "M " + mi.owner + "." + mi.name + mi.desc;
			else if (insn instanceof FieldInsnNode fi) text = "F " + fi.owner + "." + fi.name + " " + fi.desc;
			else if (insn instanceof TypeInsnNode ti) text = "T " + op + " " + ti.desc;
			else if (insn instanceof LdcInsnNode li) text = "L " + String.valueOf(li.cst);
			else if (insn instanceof InvokeDynamicInsnNode di) text = "D " + di.name + di.desc;
			else if (insn instanceof IntInsnNode) text = "I " + op;
			else text = "O " + op;
			// Local variable slots are renumbered by any recompile, so a VarInsn keeps only its opcode.
			out.add(text);
		}
		return out;
	}

	/** Is {@code needle} a subsequence of {@code haystack} — i.e. was nothing removed, only inserted? */
	private static boolean isSubsequence(List<String> needle, List<String> haystack) {
		int i = 0;
		for (String h : haystack) {
			if (i < needle.size() && needle.get(i).equals(h)) i++;
			if (i == needle.size()) return true;
		}
		return i == needle.size();
	}

	private static Map<String, ClassNode> load(String jar) throws IOException {
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
		System.out.println("[mergeability] " + jar + " : " + out.size() + " classes");
		return out;
	}

	static {
		// Referenced so the switch above keeps Opcodes on the import list if it is ever trimmed.
		assert Opcodes.ASM9 > 0;
	}
}
