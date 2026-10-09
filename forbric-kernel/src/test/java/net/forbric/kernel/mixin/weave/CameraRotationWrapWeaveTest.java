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
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MixinCameraRollAdapter;

/**
 * MixinCameraRollAdapter through the real weave for a wrapper Do a Barrel Roll did not write: another mod's single
 * {@code @WrapWithCondition} on {@code setRotation(FF)} without an ordinal, which on vanilla wraps the rotation of every
 * view. On a camera whose ordinary and mirrored views now rotate through the carrier's {@code setRotation(FFF)}, the
 * wrapper keeps the minecart and sleeping calls and a widened copy of it takes the other two.
 *
 * <p>Adapted, every view's rotation passes the wrapper once, with its own yaw, and keeps the event's roll. Switched off,
 * the wrapper binds only the two two-float calls left: the ordinary and mirrored views rotate unseen.
 */
class CameraRotationWrapWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/camerawrap");
	private static final String CONFIG = "camerawrap.mixins.json";
	private static final String MOD = "steadycam";
	private static final String CAMERA = "net/minecraft/client/Camera";
	private static final String EVERY_VIEW = WeaveHarnessMain.DONE
			+ " minecart=[30.0]/0.0 ordinary=[30.0]/5.0 mirrored=[210.0]/-5.0 sleeping=[90.0]/0.0";
	private static final String TWO_VIEWS = WeaveHarnessMain.DONE
			+ " minecart=[30.0]/0.0 ordinary=[]/5.0 mirrored=[]/-5.0 sleeping=[90.0]/0.0";

	@TempDir static Path work;
	private static WeaveHarness.Result adapted, off;

	@BeforeAll static void weaveBothWays() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
		assertEquals(6, sources.size(), "the fixture's sources changed; update this test with it: " + sources);
		WeaveHarness.fixture(work, "camerawrap", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		// The class the mod was compiled against, as the merged-base builder ships it: the camera's own nativeAlignment
		// under the name alignWithEntity, its four two-float calls.
		ClassNode nativeCamera = new ClassNode();
		new ClassReader(Files.readAllBytes(work.resolve("camerawrap-classes/" + CAMERA + ".class"))).accept(nativeCamera, 0);
		nativeCamera.methods.removeIf(method -> method.name.equals("alignWithEntity"));
		nativeCamera.methods.stream().filter(method -> method.name.equals("nativeAlignment")).findFirst().orElseThrow().name = "alignWithEntity";
		ClassWriter writer = new ClassWriter(0);
		nativeCamera.accept(writer);
		byte[] nativeBytes = writer.toByteArray();
		Path binary = work.resolve("native-camera.bin"), index = work.resolve("native-camera-index.tsv");
		Files.write(binary, nativeBytes);
		Files.writeString(index, "# forbric-native-reference-v1\n" + CAMERA + "\t"
				+ java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(nativeBytes)) + "\n");
		Path fixture = WeaveHarness.fixture(work, "camerawrap", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG),
				"META-INF/forbric/native-reference/FABRIC/index.tsv", index,
				"META-INF/forbric/native-reference/FABRIC/" + CAMERA + ".class.bin", binary));
		adapted = run(fixture, "adapted", Map.of());
		off = run(fixture, "off", Map.of(MixinCameraRollAdapter.PROPERTY, "off"));
	}

	@Test void everyViewPassesTheWrapperOnce() throws Exception {
		assertTrue(adapted.printed(EVERY_VIEW), adapted.describe());
		assertEquals(List.of(), losses(adapted), adapted.describe());
	}

	@Test void switchedOffTheWrapperSeesOnlyTheTwoFloatCallsLeft() throws Exception {
		assertTrue(off.printed(TWO_VIEWS), off.describe());
	}

	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && f.id().startsWith("mixin-injector:")
				&& f.required() && !"RESOLVED".equals(f.confidence())).toList();
	}

	private static WeaveHarness.Result run(Path fixture, String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.CLIENT,
				"fixture.camerawrap.Probe", "probe", properties);
	}
}
