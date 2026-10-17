package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MixinFieldInitialiserPlacement;

/**
 * {@code MixinFieldInitialiserPlacement} through the real weave, on classes that share no name with any game or mod.
 *
 * <p>{@code org.harbor.shelf.Crate}, as merged, builds a copy of each child crate with {@code new Crate(...)} after
 * {@code super()}; as the guest's loader has it, it builds none. The guest ({@code com.tallyman.mark}) adds a tag field
 * with an initialiser. Mixin puts the initialiser after the last construction of a {@code Crate} in the constructor:
 * natively that is {@code super()}, merged it is the copy inside the child loop, so a crate without children — and every
 * copy, which has none — keeps a null tag. Repaired, the initialiser runs right after {@code super()} for every crate, and
 * every tag is the native run's. Switched off, the lone crate and the copies have no tag.
 */
class MixinFieldInitialiserPlacementWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/fieldinitialiser");
	private static final String CONFIG = "fieldinitialiser.mixins.json";
	private static final String MOD = "tallyman";
	private static final String CRATE = "org/harbor/shelf/Crate";
	private static final String TAGGED = WeaveHarnessMain.DONE + " lone=untagged stack=untagged a=untagged b=untagged";
	private static final String MISPLACED = WeaveHarnessMain.DONE + " lone=null stack=untagged a=null b=null";
	private static final String LOGGED = "[Forbric/Mixin] post-mixin repair: org.harbor.shelf.Crate's merged constructor builds "
			+ "instances of itself after super(), so Mixin put the initialiser of tallyman$tag (com/tallyman/mark/mixin/CrateMixin)";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result repaired, off, natively;

	@BeforeAll static void weave() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = walk.filter(p -> p.toString().endsWith(".java") && !SOURCES.relativize(p).startsWith("native")).sorted().toList();
		}
		assertEquals(5, sources.size(), "the fixture's sources changed; update this test with it: " + sources);
		List<Path> nativeSources = new ArrayList<>(sources);
		nativeSources.remove(SOURCES.resolve("org/harbor/shelf/Crate.java"));
		nativeSources.add(SOURCES.resolve("native/org/harbor/shelf/Crate.java"));
		Path nativeFixture = WeaveHarness.fixture(work, "native", nativeSources, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g"));
		fixture = NativeWeaveReferences.with(work, WeaveHarness.fixture(work, "fieldinitialiser", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)),
				List.of("-g")), NativeWeaveReferences.classes(nativeFixture));
		repaired = run(fixture, "repaired", Map.of());
		off = run(fixture, "off", Map.of(MixinFieldInitialiserPlacement.PROPERTY, "off"));
		natively = run(nativeFixture, "native", Map.of());
	}

	@Test void everyCrateGetsItsTagAsNatively() throws Exception {
		assertTrue(repaired.printedLine(TAGGED), repaired.describe());
		assertTrue(natively.printedLine(TAGGED), natively.describe());
		assertTrue(repaired.printed(LOGGED), repaired.describe());
		assertTrue(initialisedRightAfterSuper(repaired), repaired.describe());
		WeaveHarness.assertWovenAndVerified(repaired, CRATE, fixture);
	}

	@Test void switchedOffACrateWithoutChildrenHasNoTag() throws Exception {
		assertTrue(off.printedLine(MISPLACED), off.describe());
		assertFalse(off.printed(LOGGED), off.describe());
		assertFalse(initialisedRightAfterSuper(off), off.describe());
	}

	/** On the native crate Mixin puts the initialiser after super() itself: nothing is repaired there. */
	@Test void theNativeClassIsLeftAsMixinWoveIt() {
		assertFalse(natively.printed("[Forbric/Mixin] post-mixin repair: org.harbor.shelf.Crate"), natively.describe());
	}

	@Test void theControlFlipsEveryAssertion() {
		assertTrue(repaired.printedLine(TAGGED) && !off.printedLine(TAGGED));
		assertTrue(off.printedLine(MISPLACED) && !repaired.printedLine(MISPLACED));
	}

	/** Whether the super()-calling constructor of the woven crate stores the guest's tag right after super(). */
	private static boolean initialisedRightAfterSuper(WeaveHarness.Result run) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(run.defined(CRATE)).accept(node, 0);
		for (MethodNode ctor : node.methods) {
			if (!ctor.name.equals("<init>")) continue;
			for (AbstractInsnNode insn : ctor.instructions) {
				if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESPECIAL || !call.owner.equals("java/lang/Object")) continue;
				for (AbstractInsnNode next = call.getNext(); next != null; next = next.getNext()) {
					if (next instanceof MethodInsnNode) break;
					if (next instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTFIELD) return put.name.equals("tallyman$tag");
				}
			}
		}
		return false;
	}

	private static WeaveHarness.Result run(Path fixture, String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER, "fixture.fieldinitialiser.Probe", "probe", properties);
	}
}
