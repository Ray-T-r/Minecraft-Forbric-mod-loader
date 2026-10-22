/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * Re-derives {@code vanilla-early-returns.txt} from the vanilla jar and the staged merged base, asserts the shipped
 * table equals it, and checks that every split it causes is one the JVM will accept.
 *
 * <p>The merged side is read after {@link ForbricMergedBaseCompatTransformer}, the transformer that reshapes the most
 * bodies before {@link VanillaEarlyReturns}. Kernel hooks that also run earlier (ForgeDamageSeamsInjector's attack seam
 * in {@code Player.hurtServer}) are not replayed: an early return one adds has a lead vanilla lacks and pairs with
 * nothing, so the rest of its key still pairs. A row is written for any method an edge of which pairs with an edge vanilla
 * sent to an early return while the merged body sends it to the tail — not only one whose last return more edges reach
 * than vanilla's: NeoForge rewrote {@code Hud.extractRenderState} to draw through its layer manager, its tail has fewer
 * edges than vanilla's, and its LevelLoadingScreen guard still went to the tail — and only with the keys an edge was
 * actually moved by, each key with the label vanilla gave every occurrence of it, which is what the run-time pairing
 * needs. The last piece of a body a carrier split into pieces gets a row keyed against vanilla's method
 * ({@link VanillaEarlyReturns#pieceRows}).
 *
 * <p>Lambdas and other synthetic methods are left out: javac numbers them by position, so the same name in the two
 * jars is not the same method (the lesson {@code MergedBasePipelineDriftTest} paid for).
 *
 * <p>Regenerate with {@code FORBRIC_WRITE_EARLY_RETURNS=1} after a base rebuild.
 */
class VanillaEarlyReturnsCensusTest {
	private static final Path MERGED_BASE = TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final Path VANILLA = TestFixtures.vanillaJar();
	private static final String CAMERA_UPDATE = "net/minecraft/client/Camera#update(Lnet/minecraft/client/DeltaTracker;)V";

	@Test
	void theShippedTableIsExactlyWhatTheArtifactsSay() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "merged base not staged: " + MERGED_BASE);
		TestFixtures.require(Fixture.MC_LIBRARIES, Files.isRegularFile(VANILLA), "no vanilla 26.2 jar at " + VANILLA);

		TreeSet<String> rows = new TreeSet<>();
		int folded = 0;
		try (ZipFile vanillaJar = new ZipFile(VANILLA.toFile()); ZipFile mergedJar = new ZipFile(MERGED_BASE.toFile())) {
			ForbricMergedBaseCompatTransformer compat = new ForbricMergedBaseCompatTransformer(name -> bytes(mergedJar, name));
			TransformContext context = new TransformContext(EnvType.CLIENT, false, "intermediary");
			for (ZipEntry entry : Collections.list(vanillaJar.entries())) {
				if (!entry.getName().endsWith(".class")) continue;
				ZipEntry mirror = mergedJar.getEntry(entry.getName());
				if (mirror == null) continue;
				String binary = entry.getName().substring(0, entry.getName().length() - ".class".length()).replace('/', '.');
				ClassNode vanilla = read(vanillaJar.getInputStream(entry).readAllBytes());
				ClassNode merged = read(compat.transform(binary, mergedJar.getInputStream(mirror).readAllBytes(), context));
				Map<String, MethodNode> mergedMethods = new HashMap<>();
				for (MethodNode m : merged.methods) mergedMethods.put(m.name + m.desc, m);
				for (MethodNode original : vanilla.methods) {
					if ((original.access & Opcodes.ACC_SYNTHETIC) != 0 || original.name.startsWith("lambda$")) continue;
					MethodNode live = mergedMethods.get(original.name + original.desc);
					if (live == null || original.instructions.size() == 0 || live.instructions.size() == 0) continue;
					AbstractInsnNode vanillaTail = VanillaEarlyReturns.lastReturn(original);
					AbstractInsnNode mergedTail = VanillaEarlyReturns.lastReturn(live);
					if (vanillaTail == null || mergedTail == null) continue;
					List<VanillaEarlyReturns.Edge> vanillaEdges = VanillaEarlyReturns.edges(original);
					List<VanillaEarlyReturns.Edge> mergedEdges = VanillaEarlyReturns.edges(live);
					long before = vanillaEdges.stream().filter(e -> e.target() == vanillaTail).count();
					long after = mergedEdges.stream().filter(e -> e.target() == mergedTail).count();
					if (after > before) folded++;
					// Every method, not only one whose tail more edges reach: the pairing below moves an edge only where vanilla
					// sent the same block's exit to an early return, and a carrier that rewrote the rest of the body (NeoForge's
					// Hud.extractRenderState draws through its layer manager) can fold a guard while its tail has fewer edges.
					Map<String, List<String>> labels = VanillaEarlyReturns.labels(original);
					Map<VanillaEarlyReturns.Edge, Integer> decisions = VanillaEarlyReturns.decide(live, mergedEdges, mergedTail, labels);
					if (decisions.isEmpty()) continue;
					Map<String, List<String>> used = new LinkedHashMap<>();
					for (VanillaEarlyReturns.Edge moved : decisions.keySet()) used.put(moved.key(), labels.get(moved.key()));
					rows.add(new VanillaEarlyReturns.Row(merged.name, original.name + original.desc, new java.util.TreeMap<>(used)).format());
				}
				// A body a carrier split into pieces (CarrierHelpers SPLIT): its last piece ends where vanilla's method ended, and
				// is keyed against vanilla's method (MixinSplitBodyEdges moves a TAIL there).
				rows.addAll(VanillaEarlyReturns.pieceRows(merged, vanilla));
			}
		}

		assertTrue(folded > 1000, "the census found only " + folded + " folded methods — is this really the recompiled base?");
		String camera = rows.stream().filter(r -> r.startsWith(CAMERA_UPDATE + " ")).findFirst().orElse(null);
		assertNotNull(camera, "issue #31's method must be in the table");
		assertEquals(2, VanillaEarlyReturns.Row.parse(camera).keys().size(),
				"Camera.update: the null-player and null-level guards each move to vanilla's early return: " + camera);

		if (System.getenv("FORBRIC_WRITE_EARLY_RETURNS") != null) {
			Files.writeString(Path.of("src/main/resources" + VanillaEarlyReturns.TABLE),
					"# Generated by VanillaEarlyReturnsCensusTest (FORBRIC_WRITE_EARLY_RETURNS=1). Methods whose last return the merged\n"
							+ "# base reaches by an edge vanilla sent to an early return (and the last piece of a split body, keyed against\n"
							+ "# vanilla's method), with the keys of the edges vanilla sent to an early return.\n"
							+ "# <owner>#<name><desc> <key>=<label of each occurrence of that key, in vanilla's order> ...\n"
							+ "# A label is the vanilla return index the occurrence went to, or T for the last return. Where one key's\n"
							+ "# occurrences went to different returns, each label also carries @<length>:<lead> — a hash of what leads into\n"
							+ "# that occurrence's block — and the merged body's occurrences pair with vanilla's by lead, not by order.\n"
							+ String.join("\n", rows) + "\n");
		}
		assertEquals(rows, new TreeSet<>(shipped()), "vanilla-early-returns.txt must equal what the staged merged base and "
				+ "vanilla say; regenerate with FORBRIC_WRITE_EARLY_RETURNS=1 after a base rebuild");
	}

	/**
	 * Every split the table causes, checked without loading a game class: the frames it writes are exactly the
	 * frames ASM's own expansion reads back, every frame it did not mean to touch decodes to what it did before,
	 * the method still passes {@link BasicVerifier}, the tail is still the last return, and each moved edge now
	 * reaches its own block.
	 */
	@Test
	void everySplitIsOneTheVerifierAccepts() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "merged base not staged: " + MERGED_BASE);
		Map<String, Map<String, Map<String, List<String>>>> table = VanillaEarlyReturns.table();
		assertFalse(table.isEmpty(), "the shipped table is empty");

		int methods = 0, blocks = 0;
		List<String> failures = new ArrayList<>();
		try (ZipFile mergedJar = new ZipFile(MERGED_BASE.toFile())) {
			ForbricMergedBaseCompatTransformer compat = new ForbricMergedBaseCompatTransformer(name -> bytes(mergedJar, name));
			TransformContext context = new TransformContext(EnvType.CLIENT, false, "intermediary");
			for (Map.Entry<String, Map<String, Map<String, List<String>>>> owner : table.entrySet()) {
				byte[] raw = bytes(mergedJar, owner.getKey().replace('/', '.'));
				if (raw == null) {
					failures.add(owner.getKey() + ": not in the merged base");
					continue;
				}
				byte[] before = compat.transform(owner.getKey().replace('/', '.'), raw, context);
				ClassNode expandedBefore = new ClassNode();
				new ClassReader(before).accept(expandedBefore, ClassReader.EXPAND_FRAMES);
				ClassNode node = read(before);

				// The decoder the split relies on agrees with ASM's on every frame of every method it may touch.
				for (MethodNode method : node.methods) {
					if (!owner.getValue().containsKey(method.name + method.desc)) continue;
					MethodNode expanded = find(expandedBefore, method);
					List<FrameNode> compressed = frames(method), full = frames(expanded);
					for (int i = 0; i < compressed.size(); i++) {
						List<List<Object>> decoded = VanillaEarlyReturns.stateAt(node.name, method, compressed.get(i));
						if (decoded == null || !same(decoded.get(0), full.get(i).local) || !same(decoded.get(1), full.get(i).stack)) {
							failures.add(owner.getKey() + "." + method.name + method.desc + ": frame " + i + " decodes to " + decoded
									+ ", ASM reads " + full.get(i).local + " / " + full.get(i).stack);
						}
					}
				}

				Map<String, Integer> tailsBefore = new HashMap<>();
				for (MethodNode method : node.methods) {
					if (owner.getValue().containsKey(method.name + method.desc)) {
						tailsBefore.put(method.name + method.desc, tailEdges(method));
					}
				}
				int changed = VanillaEarlyReturns.restore(node, owner.getValue());
				if (changed == 0) {
					failures.add(owner.getKey() + ": no method split");
					continue;
				}
				org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
				node.accept(writer);
				ClassNode after = new ClassNode();
				new ClassReader(writer.toByteArray()).accept(after, ClassReader.EXPAND_FRAMES);
				for (MethodNode method : after.methods) {
					String id = method.name + method.desc;
					VanillaEarlyReturns.Split split = VanillaEarlyReturns.splitOf(owner.getKey(), method.name, method.desc);
					if (split == null) {
						if (owner.getValue().containsKey(id)) failures.add(owner.getKey() + "." + id + ": in the table but not split");
						continue;
					}
					methods++;
					blocks += split.blocks();
					try {
						new Analyzer<>(new BasicVerifier()).analyze(owner.getKey(), method);
					} catch (Exception rejected) {
						failures.add(owner.getKey() + "." + id + ": " + rejected.getMessage());
						continue;
					}
					List<AbstractInsnNode> returns = VanillaEarlyReturns.returns(method);
					if (returns.size() != split.inlineReturns() + split.blocks() + 1) {
						failures.add(owner.getKey() + "." + id + ": " + returns.size() + " returns after the split, expected "
								+ (split.inlineReturns() + split.blocks() + 1));
					}
					// Every block's frame is the tail's, and the tail's is what it was.
					MethodNode original = find(expandedBefore, method);
					FrameNode tailBefore = frameBefore(VanillaEarlyReturns.lastReturn(original));
					for (int b = 0; b <= split.blocks(); b++) {
						FrameNode frame = frameBefore(returns.get(split.inlineReturns() + b));
						if (frame == null || !same(frame.local, tailBefore.local) || !same(frame.stack, tailBefore.stack)) {
							failures.add(owner.getKey() + "." + id + ": return " + (split.inlineReturns() + b) + " carries "
									+ (frame == null ? "no frame" : frame.local + " / " + frame.stack) + ", the tail had "
									+ tailBefore.local + " / " + tailBefore.stack);
						}
					}
					// Every frame it did not write still decodes to what it did: the compressed chain after the tail is intact.
					List<FrameNode> kept = frames(method);
					for (int b = 0; b < split.blocks(); b++) kept.remove(frameBefore(returns.get(split.inlineReturns() + b)));
					List<FrameNode> was = frames(original);
					if (kept.size() != was.size()) {
						failures.add(owner.getKey() + "." + id + ": " + kept.size() + " original frames after the split, " + was.size() + " before");
					} else {
						for (int i = 0; i < was.size(); i++) {
							if (!same(kept.get(i).local, was.get(i).local) || !same(kept.get(i).stack, was.get(i).stack)) {
								failures.add(owner.getKey() + "." + id + ": frame " + i + " changed from " + was.get(i).local + " to " + kept.get(i).local);
								break;
							}
						}
					}
					// Every debug and exception range still runs forwards.
					for (org.objectweb.asm.tree.LocalVariableNode local : method.localVariables == null
							? List.<org.objectweb.asm.tree.LocalVariableNode>of() : method.localVariables) {
						if (method.instructions.indexOf(local.start) > method.instructions.indexOf(local.end)) {
							failures.add(owner.getKey() + "." + id + ": local " + local.name + " now ends before it starts");
						}
					}
					for (org.objectweb.asm.tree.TryCatchBlockNode range : method.tryCatchBlocks) {
						if (method.instructions.indexOf(range.start) >= method.instructions.indexOf(range.end)) {
							failures.add(owner.getKey() + "." + id + ": a try range is now empty or inverted");
						}
					}
					int moved = tailsBefore.get(id) - tailEdges(method);
					if (moved < split.blocks()) {
						failures.add(owner.getKey() + "." + id + ": only " + moved + " edge(s) left the tail for " + split.blocks() + " block(s)");
					}
				}
			}
		}
		assertTrue(failures.isEmpty(), failures.size() + " problem(s):\n" + String.join("\n", failures.subList(0, Math.min(40, failures.size()))));
		assertTrue(methods > 1000, "only " + methods + " methods split");
		System.out.println("[EarlyReturns] " + methods + " methods split into " + blocks + " early-return blocks, all verified");
	}

	private static int tailEdges(MethodNode method) {
		AbstractInsnNode tail = VanillaEarlyReturns.lastReturn(method);
		int n = 0;
		for (VanillaEarlyReturns.Edge edge : VanillaEarlyReturns.edges(method)) if (edge.target() == tail) n++;
		return n;
	}

	private static FrameNode frameBefore(AbstractInsnNode insn) {
		for (AbstractInsnNode at = insn.getPrevious(); at != null && at.getOpcode() < 0; at = at.getPrevious()) {
			if (at instanceof FrameNode frame) return frame;
		}
		return null;
	}

	private static List<FrameNode> frames(MethodNode method) {
		List<FrameNode> out = new ArrayList<>();
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof FrameNode frame) out.add(frame);
		}
		return out;
	}

	/** Two frame type lists, comparing an uninitialised value by the NEW it came from rather than by label object. */
	private static boolean same(List<Object> a, List<Object> b) {
		if (a == null || b == null) return a == b || (a == null ? b.isEmpty() : a.isEmpty());
		if (a.size() != b.size()) return false;
		for (int i = 0; i < a.size(); i++) {
			Object x = a.get(i), y = b.get(i);
			if (x instanceof org.objectweb.asm.tree.LabelNode lx && y instanceof org.objectweb.asm.tree.LabelNode ly) {
				if (newAfter(lx) != newAfter(ly) && !String.valueOf(newAfter(lx)).equals(String.valueOf(newAfter(ly)))) return false;
			} else if (!x.equals(y)) {
				return false;
			}
		}
		return true;
	}

	private static String newAfter(org.objectweb.asm.tree.LabelNode label) {
		for (AbstractInsnNode at = label; at != null; at = at.getNext()) {
			if (at instanceof org.objectweb.asm.tree.TypeInsnNode type && at.getOpcode() == Opcodes.NEW) return type.desc;
			if (at.getOpcode() >= 0) return null;
		}
		return null;
	}

	private static MethodNode find(ClassNode node, MethodNode like) {
		for (MethodNode m : node.methods) if (m.name.equals(like.name) && m.desc.equals(like.desc)) return m;
		throw new AssertionError("no " + like.name + like.desc + " in " + node.name);
	}

	private static List<String> shipped() throws IOException {
		List<String> out = new ArrayList<>();
		try (InputStream in = VanillaEarlyReturns.class.getResourceAsStream(VanillaEarlyReturns.TABLE)) {
			assertNotNull(in, VanillaEarlyReturns.TABLE + " is missing");
			for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
				if (!line.isBlank() && !line.startsWith("#")) out.add(line.strip());
			}
		}
		return out;
	}

	private static ClassNode read(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] bytes(ZipFile jar, String binaryName) {
		ZipEntry entry = jar.getEntry(binaryName.replace('.', '/') + ".class");
		if (entry == null) return null;
		try (InputStream in = jar.getInputStream(entry)) {
			return in.readAllBytes();
		} catch (IOException unreadable) {
			return null;
		}
	}
}
