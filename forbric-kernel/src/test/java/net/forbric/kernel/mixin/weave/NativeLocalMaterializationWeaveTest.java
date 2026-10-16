package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.NativeLocalMaterialization;

/**
 * {@code NativeLocalMaterialization} through the real weave, on classes that share no name with any game or mod.
 *
 * <p>{@code org.quarry.yard.Crate.each} names its bundle before handing it to {@code Bundle.forEach} as the guest's own
 * loader has it; as merged, a carrier hands its lookup's result straight to the call. The guest, {@code com.lantern.keep},
 * injects at that call with a {@code @Local Bundle}. Kept in the native local, the guest reads the very bundle the call
 * is handed, each time; switched off, the local is not there and the guest's injector does not run.
 */
class NativeLocalMaterializationWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/nativelocal");
	private static final String CONFIG = "nativelocal.mixins.json";
	private static final String MOD = "keep";
	private static final String TARGET = "org/quarry/yard/Crate";
	private static final String KEPT = WeaveHarnessMain.DONE + " seen:hay/top,hay/top,tail,seen:oats/side,oats/side,tail";
	private static final String OFF = WeaveHarnessMain.DONE + " hay/top,tail,oats/side,tail";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result kept;
	private static WeaveHarness.Result off;

	@BeforeAll static void weave() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = walk.filter(p -> p.toString().endsWith(".java") && !SOURCES.relativize(p).startsWith("native")).sorted().toList();
		}
		assertEquals(6, sources.size(), "the fixture's sources changed; update this test with it: " + sources);
		Path merged = WeaveHarness.fixture(work, "nativelocal", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g"));
		List<Path> nativeSources = new ArrayList<>(sources);
		nativeSources.remove(SOURCES.resolve("org/quarry/yard/Crate.java"));
		nativeSources.removeIf(p -> SOURCES.relativize(p).startsWith("com"));
		nativeSources.add(SOURCES.resolve("native/org/quarry/yard/Crate.java"));
		Path original = WeaveHarness.fixture(work, "original", nativeSources, Map.of(), List.of("-g"));
		fixture = NativeWeaveReferences.with(work, merged, NativeWeaveReferences.classes(original));
		kept = run("kept", Map.of());
		off = run("off", Map.of(NativeLocalMaterialization.PROPERTY, "off"));
	}

	@Test void theGuestReadsTheBundleTheCallIsHanded() throws Exception {
		assertTrue(kept.printedLine(KEPT), kept.describe());
		assertTrue(kept.printed("[Forbric/Mixin] com.lantern.keep.mixin.CrateMixin.keep$seen: the merged org.quarry.yard.Crate.each"), kept.describe());
		assertEquals(List.of(), kept.findings().stream().filter(f -> f.modId().equals(MOD) && !f.confidence().equals("RESOLVED")).toList(),
				kept.describe());
		assertTrue(callsGuest(kept), kept.describe());
		WeaveHarness.assertWovenAndVerified(kept, TARGET, fixture);
	}

	@Test void switchedOffTheGuestsInjectorDoesNotRun() throws Exception {
		assertTrue(off.printedLine(OFF), off.describe());
		assertFalse(callsGuest(off), off.describe());
	}

	@Test void theControlFlipsEveryAssertion() {
		assertTrue(kept.printedLine(KEPT) && !off.printedLine(KEPT));
		assertTrue(off.printedLine(OFF) && !kept.printedLine(OFF));
	}

	private static boolean callsGuest(WeaveHarness.Result run) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(run.defined(TARGET)).accept(node, 0);
		for (MethodNode body : node.methods) {
			if (!body.name.equals("each")) continue;
			for (AbstractInsnNode insn : body.instructions)
				if (insn instanceof MethodInsnNode call && call.owner.equals(TARGET) && call.name.contains("keep$")) return true;
		}
		return false;
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER, "fixture.nativelocal.Probe", "probe",
				properties);
	}
}
