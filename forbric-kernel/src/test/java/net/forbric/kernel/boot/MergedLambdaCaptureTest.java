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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
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
 * <p>One kind of cross-compile capture is right, and it is not a collision: both compiles' lambdas of that name are
 * vanilla's one lambda, the capturer's compile left it as vanilla wrote it, and the other compile only added code to
 * it. The per-method merge then gives the name the body of the side that edited it, as for any method both sides
 * have, and the capturer runs that lambda with the edit in it (MinecraftForge's guard in
 * {@code KeyBindsList$KeyEntry.lambda$new$0}, under NeoForge's constructor). The builder proves it from each
 * side's patch and the vanilla capture site; this test checks it against vanilla's own class
 * ({@link #onlyTheOtherSideEdited}), and PauseScreen's lambda, which shares no code with vanilla's, still fails.
 *
 * <p>This is a property of the ARTIFACT, so it is asserted against the artifact rather than against the tool: the
 * builder that produces it is in another module, is run by shell scripts and by the installer, and the thing that
 * must be true is true of the jar either way. Skips when the three jars are not staged.
 */
class MergedLambdaCaptureTest {
	private static final Path STAGED = TestFixtures.stagedRoot().toAbsolutePath().normalize();
	private static final Path MERGED = STAGED.resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final Path NEO = patched("patched-mc-neoforge-26.2.jar", "neoforge-patched");
	private static final Path FORGE = patched("patched-mc-forge-26.2.jar", "forge-patched");

	/** The installer's own build output when this checkout ran it, else the side staged beside the merged base. */
	private static Path patched(String jar, String stagedDirectory) {
		Path installed = staged("run", "installed", ".forbric-build", "out", jar);
		return Files.isRegularFile(installed) ? installed : STAGED.resolve(stagedDirectory).resolve(jar);
	}

	private static Path staged(String... parts) {
		Path p = Path.of(System.getProperty("user.dir"));
		for (String part : parts) p = p.resolve(part);
		return p.normalize();
	}

	@Test
	void noCapturedLambdaComesFromTheOtherSideOfTheMerge() throws IOException {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED) && Files.isRegularFile(NEO) && Files.isRegularFile(FORGE),
				"the merged base and both patched sides must be staged");

		Map<String, byte[]> merged = classesIn(MERGED);
		Map<String, byte[]> forge = classesIn(FORGE);
		Map<String, byte[]> neo = classesIn(NEO);

		List<String> wrong = new ArrayList<>();
		List<String> editedByTheOtherSide = new ArrayList<>();
		Map<String, byte[]> vanilla = null;
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
							if (vanilla == null && Files.isRegularFile(TestFixtures.vanillaJar())) vanilla = classesIn(TestFixtures.vanillaJar());
							byte[] vb = vanilla == null ? null : vanilla.get(e.getKey());
							Map<String, MethodNode> vm = vb == null ? Map.of() : index(parse(vb));
							if (onlyTheOtherSideEdited(mc.name, key, vm.get(m.name + m.desc), vm.get(key), want, theirs,
									other.get(m.name + m.desc))) {
								editedByTheOtherSide.add(mc.name + "." + key);
								continue;
							}
							wrong.add(mc.name + "." + m.name + m.desc + " kept "
									+ (fromForge ? "forge" : "neo") + " but captures " + key
									+ " from the other side");
						}
					}
				}
			}
		}
		System.out.println("[Forbric/Lambdas] captured from the other compile because only that side edited vanilla's "
				+ "lambda: " + editedByTheOtherSide);
		// The three jars are staged, so a small count is a merge that changed under the test, not a missing fixture.
		assertTrue(twoSided > 1000, "the staged jars do not look like a full merge (" + twoSided + " two-sided)");
		assertTrue(wrong.isEmpty(), "a captured lambda came from the other compile, so its call site silently runs "
				+ "the wrong body: " + wrong);
	}

	/** The acceptance on its own: the one shape it takes, and the collisions and near misses it refuses. */
	@Test
	void onlyAVanillaLambdaTheOtherSideOnlyAddedCodeToIsAccepted() {
		String owner = "net/minecraft/demo/Screen", key = "lambda$init$0()V";
		MethodNode capturer = capturer(owner, "lambda$init$0"), vanillaLambda = lambda(false, call("record", 2));
		MethodNode own = lambda(false, call("record", 2)), edited = lambda(false, call("guard", 100), call("record", 2));
		assertTrue(onlyTheOtherSideEdited(owner, key, capturer, vanillaLambda, own, edited, capturer),
				"vanilla's lambda, left alone by the capturer's compile, with the other compile's code added");
		assertFalse(onlyTheOtherSideEdited(owner, key, capturer, vanillaLambda, own, lambda(false, call("open", 100)), capturer),
				"PauseScreen's collision: the other compile's lambda of that name is code of its own");
		assertFalse(onlyTheOtherSideEdited(owner, key, capturer, vanillaLambda, lambda(false, call("veto", 200), call("record", 2)),
				edited, capturer), "the capturer's compile edited it too, so the other body drops that edit");
		assertFalse(onlyTheOtherSideEdited(owner, key, capturer, vanillaLambda, own, edited, lambda(false, call("record", 2))),
				"the other compile's method of that name does not capture it");
		assertFalse(onlyTheOtherSideEdited(owner, key, capturer, vanillaLambda, own, lambda(true, call("guard", 100), call("record", 2)),
				capturer), "a static body under the name the capturer's handle reaches as an instance method");
		assertFalse(onlyTheOtherSideEdited(owner, key, capturer, vanillaLambda, own, lambda(false, call("record", 2)), capturer),
				"nothing added is no edit: the bodies would have been equal");
	}

	private static MethodNode lambda(boolean isStatic, org.objectweb.asm.tree.InsnList... statements) {
		MethodNode m = new MethodNode(org.objectweb.asm.Opcodes.ACC_PRIVATE | org.objectweb.asm.Opcodes.ACC_SYNTHETIC
				| (isStatic ? org.objectweb.asm.Opcodes.ACC_STATIC : 0), "lambda$init$0", "()V", null, null);
		for (org.objectweb.asm.tree.InsnList statement : statements) m.instructions.add(statement);
		m.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.RETURN));
		return m;
	}

	private static org.objectweb.asm.tree.InsnList call(String name, int value) {
		org.objectweb.asm.tree.InsnList list = new org.objectweb.asm.tree.InsnList();
		list.add(new IntInsnNode(org.objectweb.asm.Opcodes.SIPUSH, value));
		list.add(new MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC, "net/minecraft/demo/Hooks", name, "(I)V", false));
		return list;
	}

	private static MethodNode capturer(String owner, String lambda) {
		MethodNode m = new MethodNode(org.objectweb.asm.Opcodes.ACC_PUBLIC, "init", "()V", null, null);
		m.instructions.add(new VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 0));
		m.instructions.add(new InvokeDynamicInsnNode("run", "(L" + owner + ";)Ljava/lang/Runnable;",
				new Handle(org.objectweb.asm.Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
						"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
								+ "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
								+ "Ljava/lang/invoke/CallSite;", false),
				org.objectweb.asm.Type.getType("()V"), new Handle(org.objectweb.asm.Opcodes.H_INVOKESPECIAL, owner, lambda, "()V", false),
				org.objectweb.asm.Type.getType("()V")));
		m.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.POP));
		m.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.RETURN));
		return m;
	}

	/**
	 * Whether {@code key} is one vanilla lambda that only the other compile edited: vanilla's own method of the
	 * capturer's name captures a lambda of that name, and so does the other compile's; the capturer's compile has
	 * vanilla's lambda exactly; and the other compile's has every instruction of it in order, with code of its own
	 * added and the same static flag. A lambda the other compile wrote itself under a colliding name shares no such
	 * sequence with vanilla's.
	 */
	static boolean onlyTheOtherSideEdited(String owner, String key, MethodNode vanillaCapturer, MethodNode vanillaLambda,
			MethodNode own, MethodNode theirs, MethodNode theirCapturer) {
		if (vanillaCapturer == null || vanillaLambda == null || theirCapturer == null) return false;
		if (!captures(vanillaCapturer, owner, key) || !captures(theirCapturer, owner, key)) return false;
		if ((own.access & org.objectweb.asm.Opcodes.ACC_STATIC) != (theirs.access & org.objectweb.asm.Opcodes.ACC_STATIC)) return false;
		if (!body(own).equals(body(vanillaLambda))) return false;
		String[] vanillaCode = body(vanillaLambda).split(";"), theirCode = body(theirs).split(";");
		if (theirCode.length <= vanillaCode.length) return false;
		int matched = 0;
		for (String instruction : theirCode) if (matched < vanillaCode.length && instruction.equals(vanillaCode[matched])) matched++;
		return matched == vanillaCode.length;
	}

	private static boolean captures(MethodNode method, String owner, String key) {
		if (method.instructions == null) return false;
		for (AbstractInsnNode insn : method.instructions) {
			if (!(insn instanceof InvokeDynamicInsnNode indy)) continue;
			for (Object arg : indy.bsmArgs) {
				if (arg instanceof Handle h && h.getOwner().equals(owner) && (h.getName() + h.getDesc()).equals(key)) return true;
			}
		}
		return false;
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
