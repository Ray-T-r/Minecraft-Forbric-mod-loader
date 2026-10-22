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

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.NativeDeadValueConsumers;

/**
 * NativeDeadValueConsumers through the real weave, against the same guest woven into the class it was written for.
 *
 * <p>The guest (unrelated names) wraps the modded-suffix lookup of a title screen's version string and makes it a count.
 * Natively the string is drawn bottom-left. The merged screen still builds the string, stores it and never reads it, and
 * draws the carrier's branding lines instead — the first of which is the carrier's own {@code "Minecraft " + version} —
 * through lambdas that call the same {@code text}.
 * <ul>
 *   <li>native — "Minecraft 26.2 (5 Things)" is drawn;</li>
 *   <li>merged — the carrier's lines are drawn, with its version line as the guest made it, every frame; the line
 *       above the copyright, which equals the first value too, is not drawn twice: one native draw, one carrier draw;</li>
 *   <li>merged with {@code -Dforbric.nativeDeadValueConsumers=off} (the control) — the guest's string is lost;</li>
 *   <li>merged with a guest that hooks the same method but not the string — nothing about the string changes.</li>
 * </ul>
 */
class NativeDeadValueConsumersWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/deadvalue");
	private static final Path NATIVE = Path.of("src/test/resources/weave/deadvalue-native");
	private static final String CONFIG = "tallyline.mixins.json", HEAD_CONFIG = "tallyline-head.mixins.json";
	private static final String MOD = "tallyline";
	private static final String SCREEN = "net/minecraft/client/gui/screens/TitleScreen";
	private static final String NATIVE_FRAME = "Minecraft 26.2 (5 Things)@2,90";
	private static final String DELIVERED_FRAME = "Carrier 1.0 (3 mods)@2,90; Minecraft 26.2 (5 Things)@2,80; Minecraft 26.2@198,90";
	private static final String CARRIER_FRAME = "Carrier 1.0 (3 mods)@2,90; Minecraft 26.2@2,80; Minecraft 26.2@198,90";
	private static final String DELIVERED_LOG = "net.minecraft.client.gui.screens.TitleScreen.extractRenderState: the value fixture.tallyline.mixin.TitleMixin's "
			+ "handler changed is stored and never read";

	@TempDir static Path work;
	private static WeaveHarness.Result nativeRun, adapted, off, headOnly;

	@BeforeAll static void weave() throws Exception {
		List<Path> shared = List.of(
				SOURCES.resolve("net/minecraft/SharedConstants.java"),
				SOURCES.resolve("net/minecraft/client/gui/Font.java"),
				SOURCES.resolve("net/minecraft/client/gui/GuiGraphicsExtractor.java"),
				SOURCES.resolve("net/minecraft/client/resources/language/I18n.java"),
				SOURCES.resolve("net/neoforged/neoforge/internal/BrandingControl.java"),
				SOURCES.resolve("fixture/tallyline/Probe.java"),
				SOURCES.resolve("fixture/tallyline/mixin/TitleMixin.java"),
				SOURCES.resolve("fixture/tallyline/mixin/HeadMixin.java"));
		Map<String, Path> configs = Map.of(CONFIG, SOURCES.resolve(CONFIG), HEAD_CONFIG, SOURCES.resolve(HEAD_CONFIG));
		Path nativeFixture = WeaveHarness.fixture(work, "deadvalue-native", with(shared, NATIVE.resolve("net/minecraft/client/gui/screens/TitleScreen.java")), configs);
		List<Path> mergedSources = with(shared, SOURCES.resolve("net/minecraft/client/gui/screens/TitleScreen.java"));
		WeaveHarness.fixture(work, "deadvalue", mergedSources, configs);
		ClassNode reference = new ClassNode();
		new ClassReader(Files.readAllBytes(work.resolve("deadvalue-classes/" + SCREEN + ".class"))).accept(reference, 0);
		reference.methods.removeIf(method -> method.name.equals("extractRenderState") || method.name.startsWith("lambda$"));
		reference.methods.stream().filter(method -> method.name.equals("nativeRender")).findFirst().orElseThrow().name = "extractRenderState";
		ClassWriter writer = new ClassWriter(0);
		reference.accept(writer);
		byte[] bytes = writer.toByteArray();
		Path binary = work.resolve("native-screen.bin"), index = work.resolve("native-screen-index.tsv");
		Files.write(binary, bytes);
		Files.writeString(index, "# forbric-native-reference-v1\n" + SCREEN + "\t"
				+ java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)) + "\n");
		java.util.HashMap<String, Path> resources = new java.util.HashMap<>(configs);
		resources.put("META-INF/forbric/native-reference/FABRIC/index.tsv", index);
		resources.put("META-INF/forbric/native-reference/FABRIC/" + SCREEN + ".class.bin", binary);
		Path merged = WeaveHarness.fixture(work, "deadvalue", mergedSources, resources);
		nativeRun = run("native", nativeFixture, CONFIG, Map.of());
		adapted = run("adapted", merged, CONFIG, Map.of());
		off = run("dead-values-off", merged, CONFIG, Map.of(NativeDeadValueConsumers.PROPERTY, "off"));
		headOnly = run("head-only-guest", merged, HEAD_CONFIG, Map.of());
	}

	private static List<Path> with(List<Path> shared, Path extra) {
		List<Path> all = new ArrayList<>(shared);
		all.add(extra);
		return all;
	}

	@Test void natively_theGuestsStringIsDrawn() {
		assertTrue(nativeRun.printed(WeaveHarnessMain.DONE + " " + NATIVE_FRAME + " | " + NATIVE_FRAME), nativeRun.describe());
	}

	@Test void merged_theCarriersVersionLineIsDrawnAsTheGuestMadeItEveryFrame() {
		assertTrue(adapted.printed(WeaveHarnessMain.DONE + " " + DELIVERED_FRAME + " | " + DELIVERED_FRAME), adapted.describe());
		assertTrue(adapted.printed(DELIVERED_LOG), adapted.describe());
	}

	@Test void switchedOff_theGuestsStringIsLost() {
		assertTrue(off.printed(WeaveHarnessMain.DONE + " " + CARRIER_FRAME + " | " + CARRIER_FRAME), off.describe());
		assertFalse(off.printed(DELIVERED_LOG), off.describe());
	}

	@Test void aGuestThatDoesNotTouchTheStringChangesNothing() {
		assertTrue(headOnly.printed(WeaveHarnessMain.DONE + " " + CARRIER_FRAME + " | " + CARRIER_FRAME), headOnly.describe());
		assertFalse(headOnly.printed("is stored and never read"), headOnly.describe());
	}

	private static WeaveHarness.Result run(String label, Path fixture, String config, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, config, MOD, Ecosystem.FABRIC, EnvType.CLIENT,
				"fixture.tallyline.Probe", "probe", properties);
	}
}
