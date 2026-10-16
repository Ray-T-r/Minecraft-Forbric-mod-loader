package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MixinShiftedPoints;

/**
 * MixinShiftedPoints through the real weave: a guest's {@code @ModifyVariable} at {@code TAIL} shifted {@code by = -2}
 * (Shoulder Surfing's FOV override on {@code Camera.calculateFov}) still modifies the local where its own loader's body
 * reads it, when the merged body carries another family's event call between that read and the return.
 *
 * <p>The native body (the guest's own loader, NEOFORGE reference) computes {@code fov}, hands it to {@code modify} and
 * returns that. The merged body hands {@code modify}'s result to a carrier event first and returns the event's value.
 * Natively {@code TAIL - 2} is the load of {@code fov} that feeds {@code modify}; in the merged body it is the event
 * call, after the last read of {@code fov}, so the handler's 30 is stored where nothing reads it and the probe prints
 * the vanilla 70. The fixture's names are its own: the rule reads the bodies, never a name.
 * <ul>
 *   <li>on — the point names the same load as natively: the override reaches the return (30);</li>
 *   <li>off — {@code -Dforbric.shiftedPoints=off}: as compiled, the override is lost (70), the RED this rule fixes;</li>
 *   <li>unchanged — the same guest on a merged body equal to the native one: nothing is rewritten, the override works.</li>
 * </ul>
 */
class MixinShiftedPointsWeaveTest {
	private static final String OWNER = "net/minecraft/fixture/shift/Lens";
	private static final String CONFIG = "shifted.mixins.json";
	private static final String OVERRIDDEN = "[Shifted] fov=30.0";
	private static final String LOST = "[Shifted] fov=70.0";
	private static final String MOVED = "the merged body keeps that instruction behind a carrier's code";

	@TempDir static Path work;
	private static WeaveHarness.Result on, off, unchanged;
	private static Path mergedFixture, nativeFixture;

	@BeforeAll static void weave() throws Exception {
		String header = "package net.minecraft.fixture.shift;public class Lens{float base=70f;float modify(float partial,float fov){return fov*partial;}";
		Path event = source("Event.java", "package net.minecraft.fixture.shift;public class Event{private final float value;Event(float v){value=v;}"
				+ "public static Event fire(Lens lens,float partial,float value){return new Event(value);}public float value(){return value;}}");
		Path nativeLens = source("native/Lens.java", header + "public float calculate(float partial){float fov=base*partial;return modify(partial,fov);}}");
		Path mergedLens = source("merged/Lens.java", header + "public float calculate(float partial){float fov=base*partial;float ret=modify(partial,fov);"
				+ "return Event.fire(this,partial,ret).value();}}");
		Path probe = source("Probe.java", "package fixture.shift;public class Probe{public static void probe(){"
				+ "System.out.println(\"[Shifted] fov=\"+new net.minecraft.fixture.shift.Lens().calculate(1.0f));}}");
		Path mixin = source("LensMixin.java", """
				package fixture.shift.mixin;
				import net.minecraft.fixture.shift.Lens;
				import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.*;
				@Mixin(Lens.class)public class LensMixin {
				 @ModifyVariable(method="calculate",at=@At(value="TAIL",shift=At.Shift.BY,by=-2),ordinal=1)
				 private float override(float fov){return 30f;}
				}
				""");
		Path json = source(CONFIG, "{\"required\":true,\"package\":\"fixture.shift.mixin\",\"mixins\":[\"LensMixin\"],\"injectors\":{\"defaultRequire\":1}}");
		Path originalJar = WeaveHarness.fixture(work, "native-lens", List.of(nativeLens, event), Map.of(), List.of("-g"));
		byte[] reference;
		try (JarFile jar = new JarFile(originalJar.toFile())) {
			reference = jar.getInputStream(jar.getJarEntry(OWNER + ".class")).readAllBytes();
		}
		Path bin = work.resolve("native.bin"), index = work.resolve("native-index.tsv");
		Files.write(bin, reference);
		Files.writeString(index, "# forbric-native-reference-v1\n" + OWNER + "\t"
				+ HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(reference)) + "\n");
		Map<String, Path> references = Map.of(CONFIG, json, "META-INF/forbric/native-reference/NEOFORGE/index.tsv", index,
				"META-INF/forbric/native-reference/NEOFORGE/" + OWNER + ".class.bin", bin);
		mergedFixture = WeaveHarness.fixture(work, "merged", List.of(mergedLens, event, probe, mixin), references, List.of("-g"));
		nativeFixture = WeaveHarness.fixture(work, "same", List.of(nativeLens, event, probe, mixin), references, List.of("-g"));
		on = run("on", mergedFixture, Map.of());
		off = run("off", mergedFixture, Map.of(MixinShiftedPoints.PROPERTY, "off"));
		unchanged = run("unchanged", nativeFixture, Map.of());
	}

	@Test void theShiftedPointStillModifiesTheLocalWhereTheNativeBodyReadsIt() throws Exception {
		assertTrue(on.printed(OVERRIDDEN), "the override did not reach the return — " + on.describe());
		assertTrue(on.printed(MOVED), on.describe());
		assertTrue(on.printed("INVOKE, target=Lnet/minecraft/fixture/shift/Lens;modify(FF)F, ordinal=0, by=-1"), on.describe());
		WeaveHarness.assertWovenAndVerified(on, OWNER, mergedFixture);
	}

	@Test void switchedOffTheCountLandsOnTheCarriersCallAndTheOverrideIsLost() throws Exception {
		assertTrue(off.printed(LOST), "the control must reproduce the loss — " + off.describe());
		assertFalse(off.printed(MOVED), off.describe());
		WeaveHarness.assertWovenAndVerified(off, OWNER, mergedFixture);
	}

	@Test void aBodyThatKeptTheNativeStretchIsLeftAlone() throws Exception {
		assertTrue(unchanged.printed(OVERRIDDEN), unchanged.describe());
		assertFalse(unchanged.printed(MOVED), unchanged.describe());
		assertFalse(unchanged.printed("is not proved, so the point stays as compiled"), unchanged.describe());
		WeaveHarness.assertWovenAndVerified(unchanged, OWNER, nativeFixture);
	}

	private static WeaveHarness.Result run(String label, Path fixture, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, "shiftedprobe", Ecosystem.NEOFORGE, EnvType.CLIENT,
				"fixture.shift.Probe", "probe", properties);
	}

	private static Path source(String name, String text) throws Exception {
		Path file = work.resolve("src").resolve(name);
		Files.createDirectories(file.getParent());
		Files.writeString(file, text);
		return file;
	}
}
