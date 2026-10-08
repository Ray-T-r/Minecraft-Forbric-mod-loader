package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MixinCameraRollAdapter;

/**
 * MixinCameraRollAdapter through the real weave: Do a Barrel Roll's camera mixin, written for vanilla's four
 * {@code setRotation(FF)} calls, on a camera whose ordinary and mirrored views go through the carrier's
 * {@code setRotation(FFF)} with the angles event's roll.
 *
 * <p>The probe aligns one camera per view with an event roll of 5 and a tick delta of 0.5 (40 degrees per tick while
 * flying), and reports the roll that reached the rotation and which of the mod's hooks ran. Adapted, each view's
 * hook runs on its own call, the shared tick delta still reaches the ordinary hook after its {@code @Share} slot moved,
 * and the roll is added on top of the event's. With {@code -Dforbric.cameraRollCallbacks=off} the same mixin binds by its
 * vanilla ordinals: the ordinary hook runs only for the bed, the mirrored and bed hooks bind nothing, so the camera
 * keeps the event's roll while flying and rolls while sleeping. (The roll modifier's name-only selector is still moved
 * off the delegating {@code setRotation(FF)} there, by MixinStubRebind; adapted, the adapter pins it first.)
 */
class BarrelRollCameraAdapterWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/barrelroll");
	private static final String CONFIG = "barrelroll.mixins.json";
	private static final String MOD = "do_a_barrel_roll";
	private static final String CAMERA = "net/minecraft/client/Camera";
	private static final String ROLLED = WeaveHarnessMain.DONE
			+ " ordinary=25.0[ordinary] mirrored=-25.0[mirrored] sleeping=0.0[bed] minecart=0.0[]";
	private static final String UNROLLED = WeaveHarnessMain.DONE
			+ " ordinary=5.0[] mirrored=-5.0[] sleeping=20.0[ordinary] minecart=0.0[]";
	private static final String ADAPTED_LOG = "[Forbric/Mixin] The camera roll callback now wraps the merged "
			+ "alignWithEntity's setRotation(FFF) calls";
	private static final String STUB_REBIND_LOG = "CameraMixin: doABarrelRoll$setRoll now targets net.minecraft.client.Camera"
			+ ".setRotation(FFF)V — Mixin bound its selector to the merge-added stub (FF)V";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result adapted, off;

	@BeforeAll static void weaveBothWays() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
		assertEquals(6, sources.size(), "the fixture's sources changed; update this test with it: " + sources);
		fixture = WeaveHarness.fixture(work, "barrelroll", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)));
        ClassNode nativeCamera=new ClassNode();new ClassReader(Files.readAllBytes(work.resolve("barrelroll-classes/"+CAMERA+".class"))).accept(nativeCamera,0);
        nativeCamera.methods.removeIf(method->method.name.equals("alignWithEntity"));
        nativeCamera.methods.stream().filter(method->method.name.equals("nativeAlignment")).findFirst().orElseThrow().name="alignWithEntity";
        org.objectweb.asm.ClassWriter writer=new org.objectweb.asm.ClassWriter(0);nativeCamera.accept(writer);byte[] nativeBytes=writer.toByteArray();
        Path binary=work.resolve("native-camera.bin"),index=work.resolve("native-camera-index.tsv");Files.write(binary,nativeBytes);
        Files.writeString(index,"# forbric-native-reference-v1\n"+CAMERA+"\t"+java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(nativeBytes))+"\n");
        fixture=WeaveHarness.fixture(work,"barrelroll",sources,Map.of(CONFIG,SOURCES.resolve(CONFIG),
                "META-INF/forbric/native-reference/FABRIC/index.tsv",index,
                "META-INF/forbric/native-reference/FABRIC/"+CAMERA+".class.bin",binary));
		adapted = run("adapted", Map.of());
		off = run("adapter-off", Map.of(MixinCameraRollAdapter.PROPERTY, "off"));
	}

	@Test void everyViewRollsOnTopOfTheEventsRoll() throws Exception {
		assertTrue(adapted.printed(ROLLED), adapted.describe());
		assertTrue(adapted.printed(ADAPTED_LOG), adapted.describe());
		assertFalse(adapted.printed(STUB_REBIND_LOG), "the adapter pins the modifier before any rebind — " + adapted.describe());
		assertEquals(List.of(), losses(adapted), adapted.describe());
		// The roll modifier sits in the three-argument overload, where rotationYXZ is called, not in the delegating one.
		assertEquals(List.of("setRotation(FFF)V"), callers(adapted, "doABarrelRoll$setRoll"), adapted.describe());
		WeaveHarness.assertWovenAndVerified(adapted, CAMERA, fixture);
	}

	@Test void switchedOffTheOrdinaryHookRollsTheSleeperAndFlightDoesNotRoll() throws Exception {
		assertTrue(off.printed(UNROLLED), off.describe());
		assertFalse(off.printed(ADAPTED_LOG), off.describe());
		assertTrue(off.printed(STUB_REBIND_LOG), off.describe());
		// The mirrored and bed wraps name ordinals 2 and 3 of a call the merged body makes twice.
		assertEquals(List.of("doABarrelRoll$addRoll2", "doABarrelRoll$addRoll3"),
				losses(off).stream().map(BarrelRollCameraAdapterWeaveTest::handler).sorted().toList(),
				"findings: " + off.findings() + "\n" + off.describe());
		assertEquals(List.of("setRotation(FFF)V"), callers(off, "doABarrelRoll$setRoll"), off.describe());
		WeaveHarness.assertWovenAndVerified(off, CAMERA, fixture);
	}

	/** The switch is the runs' only difference, so each run's predicate must reject the other. */
	@Test void theControlFlipsEveryRollAssertion() throws Exception {
		assertTrue(rolledHolds(adapted) && !rolledHolds(off), "the adapted predicate does not separate the runs");
		assertTrue(unrolledHolds(off) && !unrolledHolds(adapted), "the control predicate does not separate the runs");
	}

	private static boolean rolledHolds(WeaveHarness.Result run) throws Exception {
		return run.printed(ROLLED) && run.printed(ADAPTED_LOG) && !run.printed(STUB_REBIND_LOG) && losses(run).isEmpty();
	}

	private static boolean unrolledHolds(WeaveHarness.Result run) throws Exception {
		return run.printed(UNROLLED) && !run.printed(ADAPTED_LOG) && run.printed(STUB_REBIND_LOG) && losses(run).size() == 2;
	}

	/** The woven camera's methods (name + descriptor) that call the merged handler ending in {@code handler}. */
	private static List<String> callers(WeaveHarness.Result run, String handler) throws Exception {
		ClassNode camera = new ClassNode();
		new ClassReader(run.defined(CAMERA)).accept(camera, 0);
		return camera.methods.stream().filter(m -> calls(m, handler)).map(m -> m.name + m.desc).toList();
	}

	private static boolean calls(MethodNode method, String handler) {
		for (var insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(CAMERA) && call.name.endsWith(handler)) return true;
		}
		return false;
	}

	/**
	 * The final audit's rows for the mod's required injectors that attached nowhere and are not settled either way: a
	 * MixinExtras miss stays SUSPECTED, so a confirmed-only filter would see none.
	 */
	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && f.id().startsWith("mixin-injector:")
				&& f.required() && !"RESOLVED".equals(f.confidence())).toList();
	}

	private static String handler(WeaveHarness.Finding finding) {
		String id = finding.id();
		String member = id.substring(id.indexOf('#') + 1);
		return member.contains("(") ? member.substring(0, member.indexOf('(')) : member;
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.CLIENT,
				"fixture.barrelroll.Probe", "probe", properties);
	}
}
