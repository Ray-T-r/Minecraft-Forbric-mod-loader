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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.Set;
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
 * side's patch and the vanilla capture site, and names every lambda it proved in its report. This test accepts such a
 * capture only when both hold: the builder named it, and vanilla's own class shows it independently
 * ({@link #onlyTheOtherSideEdited}). A capture the test would let through that the builder did not prove fails, so
 * the check cannot quietly grow wider than the rule it is checking.
 *
 * <p>This is a property of the ARTIFACT, so it is asserted against the artifact rather than against the tool: the
 * builder that produces it is in another module, is run by shell scripts and by the installer, and the thing that
 * must be true is true of the jar either way. Skips when the three jars are not staged.
 */
class MergedLambdaCaptureTest {
	private static final Path STAGED = TestFixtures.stagedRoot().toAbsolutePath().normalize();
	/** The merged ARTIFACT as such, with its own report beside it: this test has no meaning on any other base. */
	private static final Path MERGED = STAGED.resolve(TestFixtures.MERGED_BASE_JAR);
	private static final Path NEO = patched(TestFixtures.GENUINE_BASE_JAR);
	private static final Path FORGE = patched(TestFixtures.FORGE_PATCHED_JAR);
	private static final Path REPORT = MERGED.resolveSibling("merge-conflicts.txt");
	/** The heading MergedBaseBuilder.KEPT_AS_ONE_SECTION writes above the lambdas it proved one lambda on both sides. */
	private static final String KEPT_AS_ONE = "=== lambdas both sides compile as one vanilla lambda";

	/** The installer's own build output when this checkout ran it, else the side staged at {@code stagedPath}. */
	private static Path patched(String stagedPath) {
		Path staged = STAGED.resolve(stagedPath);
		Path installed = staged("run", "installed", ".forbric-build", "out", staged.getFileName().toString());
		return Files.isRegularFile(installed) ? installed : staged;
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
		Set<String> proved = keptAsOne(REPORT);
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
							if (onlyTheOtherSideEdited(mc.name, key, vm, vm.get(m.name + m.desc), m, want, theirs,
									other.get(m.name + m.desc))) {
								editedByTheOtherSide.add(mc.name + "." + key);
								if (!proved.contains(mc.name + "#" + key)) wrong.add(mc.name + "." + m.name + m.desc
										+ " captures " + key + " from the other side, and the merge report does not name it"
										+ " as one vanilla lambda on both sides");
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
		MethodNode capturer = capturer(owner, "lambda$init$0"), vanillaLambda = lambda("lambda$init$0", false, call("record", 2));
		Map<String, MethodNode> vanilla = Map.of(key, vanillaLambda);
		MethodNode own = lambda("lambda$init$0", false, call("record", 2));
		MethodNode edited = lambda("lambda$init$0", false, call("guard", 100), call("record", 2));
		assertTrue(onlyTheOtherSideEdited(owner, key, vanilla, capturer, capturer, own, edited, capturer),
				"vanilla's lambda, left alone by the capturer's compile, with the other compile's code added");
		assertFalse(onlyTheOtherSideEdited(owner, key, vanilla, capturer, capturer, own,
				lambda("lambda$init$0", false, call("open", 100)), capturer),
				"PauseScreen's collision: the other compile's lambda of that name is code of its own");
		assertFalse(onlyTheOtherSideEdited(owner, key, vanilla, capturer, capturer,
				lambda("lambda$init$0", false, call("veto", 200), call("record", 2)), edited, capturer),
				"the capturer's compile edited it too, so the other body drops that edit");
		assertFalse(onlyTheOtherSideEdited(owner, key, vanilla, capturer, capturer, own, edited,
				lambda("init", false, call("record", 2))), "the other compile's method of that name does not capture it");
		assertFalse(onlyTheOtherSideEdited(owner, key, vanilla, capturer, capturer, own,
				lambda("lambda$init$0", true, call("guard", 100), call("record", 2)), capturer),
				"a static body under the name the capturer's handle reaches as an instance method");
		assertFalse(onlyTheOtherSideEdited(owner, key, vanilla, capturer, capturer, own,
				lambda("lambda$init$0", false, call("record", 2)), capturer), "nothing added is no edit: the bodies would have been equal");
	}

	/**
	 * The builder's own negative controls, which a check by name let through. Vanilla's {@code build} captures two
	 * lambdas; NeoForge's is vanilla's; MinecraftForge captures one of its own first, so its numbering shifts.
	 */
	@Test
	void aRenumberedCollisionAnEmptyVanillaLambdaAndAReplacedCallAreRefused() {
		String owner = "net/minecraft/demo/Mine", key = "lambda$build$1()V";
		MethodNode vanillaBuild = capturer(owner, "lambda$build$0", "lambda$build$1");
		MethodNode forgeBuild = capturer(owner, "lambda$build$0", "lambda$build$1", "lambda$build$2");
		// Vanilla's two lambdas do the same thing. MinecraftForge's lambda$build$1 is vanilla's FIRST lambda with its
		// guard, captured at vanilla's first capture; NeoForge's lambda$build$1 is vanilla's second.
		MethodNode first = lambda("lambda$build$0", false, call("record", 2)), second = lambda("lambda$build$1", false, call("record", 2));
		Map<String, MethodNode> vanilla = Map.of("lambda$build$0()V", first, key, second);
		assertFalse(onlyTheOtherSideEdited(owner, key, vanilla, vanillaBuild, vanillaBuild,
				lambda("lambda$build$1", false, call("record", 2)),
				lambda("lambda$build$1", false, call("guard", 101), call("record", 2)), forgeBuild),
				"a renumbered collision whose lambdas share vanilla's code: the captures are at different places");
		// The same collision where MinecraftForge's captures line up one for one, so only the code can tell.
		assertFalse(onlyTheOtherSideEdited(owner, key, Map.of("lambda$build$0()V", first, key, lambda("lambda$build$1", false)),
				vanillaBuild, vanillaBuild, lambda("lambda$build$1", false), lambda("lambda$build$1", false, call("openModsScreen", 7)),
				vanillaBuild), "an empty vanilla lambda: any other body ending in a return has all of its code");
		MethodNode recordOne = recordCall(false), recordTwo = recordCall(true);
		assertFalse(onlyTheOtherSideEdited(owner, key, Map.of("lambda$build$0()V", first, key, recordOne), vanillaBuild, vanillaBuild,
				recordCall(false), recordTwo, vanillaBuild),
				"vanilla's call replaced by another overload: the other compile changed vanilla's code, it did not only add");
		// The control: the same shapes with captures that line up and code only added.
		assertTrue(onlyTheOtherSideEdited(owner, key, vanilla, vanillaBuild, vanillaBuild,
				lambda("lambda$build$1", false, call("record", 2)),
				lambda("lambda$build$1", false, call("guard", 101), call("record", 2)), vanillaBuild),
				"vanilla's second lambda, guarded by the other compile at the same capture");
	}

	@Test
	void theReportsListOfLambdasKeptAsOneIsReadFromItsOwnSectionOnly(@org.junit.jupiter.api.io.TempDir Path directory)
			throws IOException {
		Path report = directory.resolve("merge-conflicts.txt");
		Files.writeString(report, String.join("\n", "[merge] summary", "",
				"=== both-modified methods where one side's hook is LOST (see suffix for which) ===",
				"game/A#lambda$a$0()V (forge hook lost)", "",
				KEPT_AS_ONE + " (left on the side that edited it) ===", "game/B#lambda$b$1()V", "", 
				"=== edit splice: both ecosystems' edits kept in one body ===", "game/C#c()V SPLICED", ""));
		assertEquals(Set.of("game/B#lambda$b$1()V"), keptAsOne(report));
		assertEquals(Set.of(), keptAsOne(directory.resolve("absent.txt")), "no report proves nothing");
	}

	private static MethodNode lambda(String name, boolean isStatic, org.objectweb.asm.tree.InsnList... statements) {
		MethodNode m = new MethodNode(org.objectweb.asm.Opcodes.ACC_PRIVATE | org.objectweb.asm.Opcodes.ACC_SYNTHETIC
				| (isStatic ? org.objectweb.asm.Opcodes.ACC_STATIC : 0), name, "()V", null, null);
		for (org.objectweb.asm.tree.InsnList statement : statements) m.instructions.add(statement);
		m.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.RETURN));
		return m;
	}

	/** {@code Hooks.record(this)}, or with {@code twice} the two-argument overload {@code Hooks.record(this, this)}. */
	private static MethodNode recordCall(boolean twice) {
		MethodNode m = new MethodNode(org.objectweb.asm.Opcodes.ACC_PRIVATE | org.objectweb.asm.Opcodes.ACC_SYNTHETIC,
				"lambda$build$1", "()V", null, null);
		m.instructions.add(new VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 0));
		if (twice) m.instructions.add(new VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 0));
		m.instructions.add(new MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC, "net/minecraft/demo/Hooks", "record",
				twice ? "(Ljava/lang/Object;Ljava/lang/Object;)V" : "(Ljava/lang/Object;)V", false));
		m.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.RETURN));
		return m;
	}

	private static org.objectweb.asm.tree.InsnList call(String name, int value) {
		org.objectweb.asm.tree.InsnList list = new org.objectweb.asm.tree.InsnList();
		list.add(new IntInsnNode(org.objectweb.asm.Opcodes.SIPUSH, value));
		list.add(new MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC, "net/minecraft/demo/Hooks", name, "(I)V", false));
		return list;
	}

	/** An instance method capturing each named instance lambda of {@code owner} through one functional interface. */
	private static MethodNode capturer(String owner, String... lambdas) {
		MethodNode m = new MethodNode(org.objectweb.asm.Opcodes.ACC_PUBLIC, "init", "()V", null, null);
		for (String lambda : lambdas) {
			m.instructions.add(new VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 0));
			m.instructions.add(new InvokeDynamicInsnNode("run", "(L" + owner + ";)Ljava/lang/Runnable;",
					new Handle(org.objectweb.asm.Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
							"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
									+ "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
									+ "Ljava/lang/invoke/CallSite;", false),
					org.objectweb.asm.Type.getType("()V"), new Handle(org.objectweb.asm.Opcodes.H_INVOKESPECIAL, owner, lambda, "()V", false),
					org.objectweb.asm.Type.getType("()V")));
			m.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.POP));
		}
		m.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.RETURN));
		return m;
	}

	/**
	 * Whether {@code key} is one vanilla lambda that only the other compile edited, judged as the builder judges it but
	 * against vanilla's own class instead of the patches.
	 *
	 * <p>Which vanilla lambda it is comes from where it is captured, never from its name (the two compiles number their
	 * lambdas independently): vanilla's capturer, the capturer's compile and the other compile each capture lambdas that
	 * look like this capture (the same interface, the same lambda descriptor, the same handle kind, the number aside)
	 * the same number of times, and both compiles capture {@code key} at the same one of those places, the n-th; the
	 * vanilla lambda is the one vanilla captures n-th. Then the capturer's compile has that vanilla lambda exactly; the
	 * other compile has every one of its instructions, in order, with code of its own added; all three share the
	 * static flag; and the vanilla lambda has code beyond its return, since an empty lambda's code is in every body.
	 */
	static boolean onlyTheOtherSideEdited(String owner, String key, Map<String, MethodNode> vanilla, MethodNode vanillaCapturer,
			MethodNode ownCapturer, MethodNode own, MethodNode theirs, MethodNode theirCapturer) {
		if (vanillaCapturer == null || ownCapturer == null || theirCapturer == null || own == null || theirs == null) return false;
		InvokeDynamicInsnNode ownCapture = onlyCaptureOf(ownCapturer, owner, key), theirCapture = onlyCaptureOf(theirCapturer, owner, key);
		if (ownCapture == null || theirCapture == null) return false;
		String shape = shape(ownCapture, owner);
		if (!shape.equals(shape(theirCapture, owner))) return false;
		List<InvokeDynamicInsnNode> inVanilla = alike(vanillaCapturer, owner, shape), inOwn = alike(ownCapturer, owner, shape),
				inTheirs = alike(theirCapturer, owner, shape);
		if (inVanilla.size() != inOwn.size() || inOwn.size() != inTheirs.size()) return false;
		int place = inOwn.indexOf(ownCapture);
		if (place < 0 || place != inTheirs.indexOf(theirCapture)) return false;
		Handle named = ownHandle(inVanilla.get(place), owner);
		MethodNode vanillaLambda = named == null ? null : vanilla.get(named.getName() + named.getDesc());
		if (vanillaLambda == null) return false;
		int isStatic = vanillaLambda.access & org.objectweb.asm.Opcodes.ACC_STATIC;
		if ((own.access & org.objectweb.asm.Opcodes.ACC_STATIC) != isStatic || (theirs.access & org.objectweb.asm.Opcodes.ACC_STATIC) != isStatic) return false;
		List<String> vanillaCode = instructions(vanillaLambda), ownCode = instructions(own), theirCode = instructions(theirs);
		if (!ownCode.equals(vanillaCode) || theirCode.size() <= vanillaCode.size()) return false;
		if (vanillaCode.stream().allMatch(MergedLambdaCaptureTest::isReturn)) return false;
		int matched = 0;
		for (String instruction : theirCode) if (matched < vanillaCode.size() && instruction.equals(vanillaCode.get(matched))) matched++;
		return matched == vanillaCode.size();
	}

	/** The one call site of {@code method} that captures {@code key}, or null when there is none or more than one. */
	private static InvokeDynamicInsnNode onlyCaptureOf(MethodNode method, String owner, String key) {
		InvokeDynamicInsnNode found = null;
		if (method.instructions == null) return null;
		for (AbstractInsnNode insn : method.instructions) {
			if (!(insn instanceof InvokeDynamicInsnNode indy)) continue;
			Handle h = ownHandle(indy, owner);
			if (h == null || !(h.getName() + h.getDesc()).equals(key)) continue;
			if (found != null) return null;
			found = indy;
		}
		return found;
	}

	/** The call sites of {@code method} that look like one another: {@link #shape} without the lambda's number. */
	private static List<InvokeDynamicInsnNode> alike(MethodNode method, String owner, String shape) {
		List<InvokeDynamicInsnNode> out = new ArrayList<>();
		if (method.instructions == null) return out;
		for (AbstractInsnNode insn : method.instructions) if (insn instanceof InvokeDynamicInsnNode indy && shape(indy, owner).equals(shape)) out.add(indy);
		return out;
	}

	private static String shape(InvokeDynamicInsnNode indy, String owner) {
		Handle h = ownHandle(indy, owner);
		return indy.name + indy.desc + (h == null ? "" : "|" + h.getTag() + " " + h.getName().replaceAll("\\$\\d+$", "") + h.getDesc());
	}

	private static Handle ownHandle(InvokeDynamicInsnNode indy, String owner) {
		for (Object arg : indy.bsmArgs) if (arg instanceof Handle h && h.getOwner().equals(owner)) return h;
		return null;
	}

	private static boolean isReturn(String instruction) {
		int opcode = Integer.parseInt(instruction.substring(0, instruction.indexOf(':')));
		return opcode >= org.objectweb.asm.Opcodes.IRETURN && opcode <= org.objectweb.asm.Opcodes.RETURN;
	}

	/** The lambdas the merge report names as one vanilla lambda on both sides ({@code owner#name(desc)}). */
	static Set<String> keptAsOne(Path report) throws IOException {
		Set<String> out = new java.util.LinkedHashSet<>();
		if (!Files.isRegularFile(report)) return out;
		boolean in = false;
		for (String line : Files.readAllLines(report)) {
			if (line.startsWith("===")) { in = line.startsWith(KEPT_AS_ONE); continue; }
			if (in && !line.isBlank()) out.add(line.trim());
		}
		return out;
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
		return String.join(";", instructions(m));
	}

	/** Each instruction's opcode and operands, one entry per instruction (a descriptor's own ';' stays inside it). */
	private static List<String> instructions(MethodNode m) {
		List<String> out = new ArrayList<>();
		if (m.instructions == null) return out;
		for (AbstractInsnNode i : m.instructions) {
			if (i.getOpcode() < 0) continue;
			StringBuilder sb = new StringBuilder();
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
			out.add(sb.toString());
		}
		return out;
	}
}
