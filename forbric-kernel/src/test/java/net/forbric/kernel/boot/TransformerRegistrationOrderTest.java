package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import net.forbric.kernel.transform.ClassTransformer;

/**
 * Two ordering constraints inside the COREMOD phase that were structural, documented in comments, and pinned by
 * nothing.
 *
 * <p>{@code TransformPhase}'s declaration order governs the phases and a test already covers it. WITHIN a phase
 * the order is registration order, every registration in {@code KernelBoot} takes the default sort index, and
 * nothing used {@code predepends} — so the two constraints the comments state were held in place only by where
 * the lines happen to sit. Moving one breaks it silently: the transformer still runs, still reports applied, and
 * edits a class the other one has already rewritten (or has not yet).
 *
 * <p>The multipart-entity repairs are pinned here too, though not their order: for them what matters is that each
 * one is registered at all.
 *
 * <p>Read from the compiled bytecode rather than the source. {@code KernelBoot.java} contains NUL bytes that make
 * {@code grep} treat it as binary and silently skip lines — this project has lost an afternoon to that twice —
 * and a constant pool cannot be misread that way.
 */
class TransformerRegistrationOrderTest {

	private static List<String> transformerConstructionOrder() throws Exception {
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main",
				"net", "forbric", "kernel", "boot", "KernelBoot.class");
		assertTrue(Files.isRegularFile(compiled),
				"KernelBoot not found in the compiled src/main classes, which exist before any test runs");
		ClassNode cn = new ClassNode();
		new ClassReader(Files.readAllBytes(compiled)).accept(cn, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
		List<String> order = new ArrayList<>();
		for (MethodNode m : cn.methods) {
			if (!m.name.equals("launch") || m.instructions == null) continue;
			for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn.getOpcode() != Opcodes.NEW || !(insn instanceof TypeInsnNode t)) continue;
				if (!t.desc.startsWith("net/forbric/kernel/transform/")) continue;
				// Only real transformers: TransformChain and TransformContext are constructed here too, and a
				// name-based exclusion list is the thing that goes stale while looking maintained.
				if (isTransformer(t.desc)) order.add(t.desc);
			}
		}
		return order;
	}

	private static boolean isTransformer(String internalName) {
		try {
			return ClassTransformer.class.isAssignableFrom(Class.forName(internalName.replace('/', '.'), false,
					TransformerRegistrationOrderTest.class.getClassLoader()));
		} catch (ClassNotFoundException | LinkageError notOne) {
			return false;
		}
	}

	@Test
	void theLoaderProbeRewriterIsStillTheFirstTransformerConstructed() throws Exception {
		List<String> order = transformerConstructionOrder();
		assertTrue(!order.isEmpty(), "no transformers found — launch() did not compile the way this expects");
		// "Registered first in the phase: it rewrites only Class.forName call sites, so nothing later in the
		// chain can be looking at what it edits." Anything registered ahead of it can.
		assertEquals("net/forbric/kernel/transform/LoaderProbeRewriter", order.get(0),
				"the loader probe is no longer first; a transformer ahead of it may edit a Class.forName site "
						+ "before the probe rewrites it. Full order: " + order);
	}

	@Test
	void capabilityCompositionStillComesBeforeTheCompatTransformer() throws Exception {
		List<String> order = transformerConstructionOrder();
		int composition = order.indexOf("net/forbric/kernel/transform/ForgeCapabilityCompositionTransformer");
		int compat = order.indexOf("net/forbric/kernel/transform/ForbricMergedBaseCompatTransformer");
		// Both are src/main and constructed by every launch() today; retiring one retires this test with it.
		assertTrue(composition >= 0 && compat >= 0,
				"one of the two is no longer constructed in launch(). Full order: " + order);
		// The compat transformer's addTheMissingCapabilityLifecycleStubs stands down when composition has
		// already run. Reversed, it adds bare-return stubs the composition then has to work around, and its own
		// claim ledger records a repair that did nothing useful.
		assertTrue(composition < compat,
				"capability composition must be registered before the compat transformer, so the latter's "
						+ "lifecycle stubs stand down on their own. Full order: " + order);
	}

	/**
	 * The merged base lists Entity/BlockEntity/Level in required-ancestor-compositions.tsv and the loader refuses to
	 * define them without a registered proof; the capability composition is that proof. It used to be constructed
	 * and registered only when -Dforbric.forgeCapabilities was on, so off stopped the game at the first Entity.
	 * Pinned on the compiled launch(): the construction and the registration are one straight line that no branch
	 * enters, leaves or skips — whatever decides dispatch must happen inside the transformer.
	 */
	@Test
	void theCapabilityCompositionIsRegisteredAsTheAncestorProofOnEveryLaunch() throws Exception {
		MethodNode launch = launch();
		List<AbstractInsnNode> code = List.of(launch.instructions.toArray());
		int created = -1, registered = -1;
		for (int i = 0; i < code.size(); i++) {
			AbstractInsnNode insn = code.get(i);
			if (insn.getOpcode() == Opcodes.NEW && insn instanceof TypeInsnNode t
					&& t.desc.equals("net/forbric/kernel/transform/ForgeCapabilityCompositionTransformer")) {
				assertEquals(-1, created, "constructed once");
				created = i;
			}
			if (insn instanceof MethodInsnNode call && call.name.equals("registerAncestorComposition")
					&& call.owner.equals("net/forbric/kernel/classloading/ForbricClassLoader")) {
				assertEquals(-1, registered, "registered once");
				registered = i;
			}
		}
		assertTrue(created >= 0 && registered > created, "the composition must be constructed, then registered as the proof");
		for (int i = 0; i < code.size(); i++) {
			for (LabelNode target : targets(code.get(i))) {
				int to = code.indexOf(target);
				assertFalse(i > created && i < registered, "a branch between constructing and registering the proof");
				assertFalse(to > created && to <= registered, "a branch into the middle of the registration");
				assertFalse(i < created && to > registered, "a branch that can skip registering the ancestor-composition proof");
			}
		}
	}

	private static List<LabelNode> targets(AbstractInsnNode insn) {
		if (insn instanceof JumpInsnNode jump) return List.of(jump.label);
		List<LabelNode> out = new ArrayList<>();
		if (insn instanceof TableSwitchInsnNode table) { out.add(table.dflt); out.addAll(table.labels); }
		if (insn instanceof LookupSwitchInsnNode lookup) { out.add(lookup.dflt); out.addAll(lookup.labels); }
		return out;
	}

	private static MethodNode launch() throws Exception {
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main",
				"net", "forbric", "kernel", "boot", "KernelBoot.class");
		assertTrue(Files.isRegularFile(compiled), "KernelBoot not found in the compiled src/main classes");
		ClassNode cn = new ClassNode();
		new ClassReader(Files.readAllBytes(compiled)).accept(cn, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
		return cn.methods.stream().filter(m -> m.name.equals("launch") && m.instructions != null && m.instructions.size() > 0)
				.findFirst().orElseThrow(() -> new AssertionError("KernelBoot.launch() not found"));
	}

	@Test
	void eachMultipartRepairIsConstructedExactlyOnce() throws Exception {
		List<String> order = transformerConstructionOrder();
		assertTrue(!order.isEmpty(), "no transformers found — launch() did not compile the way this expects");
		// These registrations are the whole fix: without the client part tracking a NeoForge mod's multipart entity
		// disconnects the client on sight, and without the Forge part tracking a MinecraftForge one throws in the
		// server's tracking callbacks and the server cannot stop. Each repair's own test constructs it directly, so a
		// merge of these lines that dropped one left every test green; one that kept both sides' copies is caught too.
		// The order is free: all six give the same classes on the merged base.
		assertEquals(0, Collections.frequency(order, "net/forbric/kernel/transform/DragonPartsInjector"),
                "the old retype/empty-array rewrite is retired; both native APIs survive the merge");
        for (String repair : List.of("net/forbric/kernel/transform/ClientPartTrackingInjector",
				"net/forbric/kernel/transform/ForgePartTrackingInjector")) {
			assertEquals(1, Collections.frequency(order, repair),
					repair + " must be constructed exactly once in launch(). Full order: " + order);
		}
	}
}
