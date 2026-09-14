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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * A lambda in the merged base must come from the same compile as the method that captures it.
 *
 * <p>A lambda is not called, it is NAMED — by the method handle in its capturing method's {@code invokedynamic}.
 * The merge splices method bodies one at a time, so a {@code lambda$M$N} can be taken from one side while
 * {@code M} is kept from the other; {@code M}'s call site then resolves, with no error of any kind, to a body
 * from the other compile, and the button does whatever the other side's button did.
 *
 * <p>Found the hard way. On NeoForge 26.2.0.88 the pause menu's "Save and Quit to Title" opened the mods list:
 * NeoForge's patch shifted the lambda numbering by one, MinecraftForge's mods-button
 * {@code lambda$createPauseMenu$11(Button)} collided with vanilla's disconnect lambda of the same name and
 * descriptor and won the splice, and NeoForge's {@code createPauseMenu} — the kept body — pointed its disconnect
 * button at it. Seven of these existed across 10,956 two-sided classes, including {@code TitleScreen.init}.
 *
 * <p>This is a property of the ARTIFACT, so it is asserted against the artifact rather than against the tool: the
 * builder that produces it is in another module, is run by shell scripts and by the installer, and the thing that
 * must be true is true of the jar either way. Skips when the three jars are not staged.
 */
class MergedLambdaCaptureTest {
	private static final Path MERGED = staged("..", "forbric-loader", "run", "merged-base",
			"patched-mc-merged-26.2.jar");
	private static final Path NEO = staged("run", "installed", ".forbric-build", "out",
			"patched-mc-neoforge-26.2.jar");
	private static final Path FORGE = staged("run", "installed", ".forbric-build", "out",
			"patched-mc-forge-26.2.jar");

	private static Path staged(String... parts) {
		Path p = Path.of(System.getProperty("user.dir"));
		for (String part : parts) p = p.resolve(part);
		return p.normalize();
	}

	@Test
	void noCapturedLambdaComesFromTheOtherSideOfTheMerge() throws IOException {
		assumeTrue(Files.isRegularFile(MERGED) && Files.isRegularFile(NEO) && Files.isRegularFile(FORGE),
				"the merged base and both patched sides must be staged");

		Map<String, byte[]> merged = classesIn(MERGED);
		Map<String, byte[]> forge = classesIn(FORGE);
		Map<String, byte[]> neo = classesIn(NEO);

		List<String> wrong = new ArrayList<>();
		int twoSided = 0;
		for (Map.Entry<String, byte[]> e : merged.entrySet()) {
			byte[] fb = forge.get(e.getKey());
			byte[] nb = neo.get(e.getKey());
			if (fb == null || nb == null) continue;
			twoSided++;
			ClassNode mc = parse(e.getValue());
			Map<String, MethodNode> mm = index(mc);
			Map<String, MethodNode> fm = index(parse(fb));
			Map<String, MethodNode> nm = index(parse(nb));

			for (MethodNode m : mc.methods) {
				if (m.instructions == null || m.name.startsWith("lambda$")) continue;
				MethodNode f = fm.get(m.name + m.desc);
				MethodNode n = nm.get(m.name + m.desc);
				if (f == null || n == null) continue;

				String kept = body(m);
				boolean fromForge = kept.equals(body(f));
				boolean fromNeo = kept.equals(body(n));
				// Only a body that IS one of the two says which compile it is from. Anything else — spliced,
				// repaired, rewritten — has no side, and nothing here is claimed about it.
				if (fromForge == fromNeo) continue;
				Map<String, MethodNode> own = fromForge ? fm : nm;
				Map<String, MethodNode> other = fromForge ? nm : fm;

				for (AbstractInsnNode insn : m.instructions) {
					if (!(insn instanceof InvokeDynamicInsnNode indy)) continue;
					for (Object arg : indy.bsmArgs) {
						if (!(arg instanceof Handle h)) continue;
						if (!h.getOwner().equals(mc.name) || !h.getName().startsWith("lambda$")) continue;
						String key = h.getName() + h.getDesc();
						MethodNode have = mm.get(key);
						MethodNode want = own.get(key);
						MethodNode theirs = other.get(key);
						if (have == null || want == null || theirs == null) continue;
						if (body(have).equals(body(want))) continue;
						if (body(have).equals(body(theirs))) {
							wrong.add(mc.name + "." + m.name + m.desc + " kept "
									+ (fromForge ? "forge" : "neo") + " but captures " + key
									+ " from the other side");
						}
					}
				}
			}
		}
		assumeTrue(twoSided > 1000, "the staged jars do not look like a full merge (" + twoSided + " two-sided)");
		assertTrue(wrong.isEmpty(), "a captured lambda came from the other compile, so its call site silently runs "
				+ "the wrong body: " + wrong);
	}

	private static Map<String, byte[]> classesIn(Path jar) throws IOException {
		Map<String, byte[]> out = new HashMap<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (ZipEntry entry : zip.stream().toList()) {
				if (!entry.getName().endsWith(".class")) continue;
				try (InputStream in = zip.getInputStream(entry)) {
					out.put(entry.getName(), in.readAllBytes());
				}
			}
		}
		return out;
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return node;
	}

	private static Map<String, MethodNode> index(ClassNode node) {
		Map<String, MethodNode> out = new HashMap<>();
		for (MethodNode m : node.methods) out.put(m.name + m.desc, m);
		return out;
	}

	/** Two compiles of one source differ in debug info and frames, so equality is over opcodes and operands. */
	private static String body(MethodNode m) {
		if (m.instructions == null) return "";
		StringBuilder sb = new StringBuilder();
		for (AbstractInsnNode i : m.instructions) {
			if (i.getOpcode() < 0) continue;
			sb.append(i.getOpcode()).append(':');
			if (i instanceof MethodInsnNode c) sb.append(c.owner).append('.').append(c.name).append(c.desc);
			else if (i instanceof FieldInsnNode f) sb.append(f.owner).append('.').append(f.name).append(f.desc);
			else if (i instanceof TypeInsnNode t) sb.append(t.desc);
			else if (i instanceof LdcInsnNode l) sb.append(l.cst);
			else if (i instanceof InvokeDynamicInsnNode d) {
				sb.append(d.name).append(d.desc);
				for (Object a : d.bsmArgs) {
					if (a instanceof Handle h) sb.append('|').append(h.getOwner()).append('.').append(h.getName());
				}
			} else if (i instanceof IntInsnNode n) sb.append(n.operand);
			else if (i instanceof VarInsnNode v) sb.append(v.var);
			sb.append(';');
		}
		return sb.toString();
	}
}
