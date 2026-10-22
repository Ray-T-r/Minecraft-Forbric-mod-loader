package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MixinCameraRollAdapter;

/**
 * A {@code @ModifyArgs} on a call the carrier now makes through a widened overload, through the real weave, against the
 * same guest woven into the class it was written for.
 *
 * <p>The guest (unrelated names) hooks the first {@code setRotation(FF)} after the minecart branch's
 * {@code setPosition(Vec3)} — the ordinary view's, natively — and turns its yaw through lambdas over the argument list.
 * The merged camera makes the ordinary view's call as {@code setRotation(FFF)} after an angles event, so that slice's
 * first two-float call is the sleeper's.
 * <ul>
 *   <li>native — the hook runs for every non-minecart view and turns the ordinary yaw from 30 to 75;</li>
 *   <li>merged — the point moves to the widened call: the same views run it, the same yaw, and the event's roll (5)
 *       reaches the rotation untouched;</li>
 *   <li>merged with {@code -Dforbric.cameraRollCallbacks.argumentLists=off} (the control) — the hook runs only on the
 *       sleeper's call, turning the bed's yaw to 135, and the ordinary view never turns.</li>
 * </ul>
 */
class WidenedCallArgumentListWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/widenedargs");
	private static final Path NATIVE = Path.of("src/test/resources/weave/widenedargs-native");
	private static final String CONFIG = "widenedargs.mixins.json";
	private static final String MOD = "lanternview";
	private static final String CAMERA = "net/minecraft/client/Camera";
	private static final String NATIVE_ANSWER = WeaveHarnessMain.DONE
			+ " ordinary=yaw:75.0,roll:0.0,hook:1 sleeping=yaw:90.0,roll:0.0,hook:1 minecart=yaw:30.0,roll:0.0,hook:0";
	private static final String MOVED = WeaveHarnessMain.DONE
			+ " ordinary=yaw:75.0,roll:5.0,hook:1 sleeping=yaw:90.0,roll:0.0,hook:1 minecart=yaw:30.0,roll:0.0,hook:0";
	private static final String STAYED = WeaveHarnessMain.DONE
			+ " ordinary=yaw:30.0,roll:5.0,hook:0 sleeping=yaw:135.0,roll:0.0,hook:1 minecart=yaw:30.0,roll:0.0,hook:0";
	private static final String MOVED_LOG = "ViewMixin.lantern$turn: the setRotation(FF)V call its point picks in net.minecraft.client.Camera"
			+ ".alignWithEntity is made on the merged body as setRotation(FFF)V (ordinal 0)";

	@TempDir static Path work;
	private static Path merged;
	private static WeaveHarness.Result nativeRun, adapted, off;

	@BeforeAll static void weave() throws Exception {
		List<Path> shared = List.of(
				SOURCES.resolve("org/joml/Quaternionf.java"),
				SOURCES.resolve("net/minecraft/world/phys/Vec3.java"),
				SOURCES.resolve("net/neoforged/neoforge/client/event/ViewportEvent.java"),
				SOURCES.resolve("fixture/lanternview/Angles.java"),
				SOURCES.resolve("fixture/lanternview/Probe.java"),
				SOURCES.resolve("fixture/lanternview/mixin/ViewMixin.java"));
		Path nativeFixture = WeaveHarness.fixture(work, "widenedargs-native", with(shared, NATIVE.resolve("net/minecraft/client/Camera.java")),
				Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		List<Path> mergedSources = with(shared, SOURCES.resolve("net/minecraft/client/Camera.java"));
		merged = WeaveHarness.fixture(work, "widenedargs", mergedSources, Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		// The native reference: the merged camera with nativeAlignment as its alignWithEntity, as the barrel-roll test embeds it.
		ClassNode reference = new ClassNode();
		new ClassReader(Files.readAllBytes(work.resolve("widenedargs-classes/" + CAMERA + ".class"))).accept(reference, 0);
		reference.methods.removeIf(method -> method.name.equals("alignWithEntity"));
		reference.methods.stream().filter(method -> method.name.equals("nativeAlignment")).findFirst().orElseThrow().name = "alignWithEntity";
		ClassWriter writer = new ClassWriter(0);
		reference.accept(writer);
		byte[] bytes = writer.toByteArray();
		Path binary = work.resolve("native-camera.bin"), index = work.resolve("native-camera-index.tsv");
		Files.write(binary, bytes);
		Files.writeString(index, "# forbric-native-reference-v1\n" + CAMERA + "\t"
				+ java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)) + "\n");
		merged = WeaveHarness.fixture(work, "widenedargs", mergedSources, Map.of(CONFIG, SOURCES.resolve(CONFIG),
				"META-INF/forbric/native-reference/FABRIC/index.tsv", index,
				"META-INF/forbric/native-reference/FABRIC/" + CAMERA + ".class.bin", binary));
		nativeRun = run("native", nativeFixture, Map.of());
		adapted = run("adapted", merged, Map.of());
		off = run("argument-lists-off", merged, Map.of(MixinCameraRollAdapter.ARGUMENT_LISTS_PROPERTY, "off"));
	}

	private static List<Path> with(List<Path> shared, Path extra) {
		List<Path> all = new ArrayList<>(shared);
		all.add(extra);
		return all;
	}

	@Test void natively_theOrdinaryViewTurns() {
		assertTrue(nativeRun.printed(NATIVE_ANSWER), nativeRun.describe());
	}

	@Test void merged_theHookMovesToTheWidenedCallAndTurnsTheSameViews() throws Exception {
		assertTrue(adapted.printed(MOVED), adapted.describe());
		assertTrue(adapted.printed(MOVED_LOG), adapted.describe());
		assertEquals(List.of("(FFF)V#1"), hooked(adapted), adapted.describe());
		// The JVM itself verified the woven camera when the probe loaded it; the harness's offline verifier cannot see the
		// argument-list class Mixin generates at run time (org.spongepowered.asm.synthetic.args.Args$1).
		assertTrue(WeaveHarness.hasMergedMethod(adapted.defined(CAMERA)), adapted.describe());
	}

	@Test void switchedOff_theHookTurnsTheSleeperInstead() throws Exception {
		assertTrue(off.printed(STAYED), off.describe());
		assertFalse(off.printed(MOVED_LOG), off.describe());
		assertTrue(WeaveHarness.hasMergedMethod(off.defined(CAMERA)), off.describe());
	}

	@Test void theControlFlipsTheAnswer() {
		assertTrue(adapted.printed(MOVED) && !off.printed(MOVED) && off.printed(STAYED) && !adapted.printed(STAYED));
	}

	/**
	 * In the woven alignWithEntity, the setRotation calls that come right after a call of the guest's argument-list handler,
	 * as {@code descriptor#count}: Mixin calls the handler and then the original method with the list's values.
	 */
	private static List<String> hooked(WeaveHarness.Result run) throws Exception {
		ClassNode camera = new ClassNode();
		new ClassReader(run.defined(CAMERA)).accept(camera, 0);
		MethodNode align = camera.methods.stream().filter(m -> m.name.equals("alignWithEntity")).findFirst().orElseThrow();
		Map<String, Integer> found = new java.util.TreeMap<>();
		boolean armed = false;
		for (var insn : align.instructions) {
			if (!(insn instanceof MethodInsnNode call)) continue;
			if (call.name.contains("lantern$turn")) armed = true;
			else if (armed && call.owner.equals(CAMERA) && call.name.equals("setRotation")) { found.merge(call.desc, 1, Integer::sum); armed = false; }
		}
		return found.entrySet().stream().map(e -> e.getKey() + "#" + e.getValue()).toList();
	}

	private static WeaveHarness.Result run(String label, Path fixture, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.CLIENT,
				"fixture.lanternview.Probe", "probe", properties);
	}
}
