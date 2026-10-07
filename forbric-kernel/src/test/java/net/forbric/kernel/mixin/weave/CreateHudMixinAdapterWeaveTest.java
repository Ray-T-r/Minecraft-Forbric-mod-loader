package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MixinHudContextAdapter;
import net.forbric.kernel.transform.HudContextQueryInjector;

/**
 * {@code MixinHudContextAdapter} through the real weave, on a CLIENT run: Create Fly's train overlay wraps the
 * nextContextualInfoState() call in vanilla's extractHotbarAndDecorations. The merged Hud still declares that method, but
 * NeoForge's extractRenderState picks the contextual bar through updateContextualBarRenderer and never calls it.
 *
 * <p>The fixture's Hud is the merged one after KernelBoot's client-only {@code HudContextQueryInjector}
 * ({@link PreMixinFixture}), so updateContextualBarRenderer asks the kernel's {@code KernelHudContextQuery}, compiled in
 * from {@code src/runtime/java}, which consults {@code HudContextCallbackScope}. The probe draws one frame while riding a train and
 * one with the HUD hidden. Adapted, the wrap surrounds NeoForge's updateContextualBarRenderer call: the overlay is drawn
 * with the frame's graphics and the bar is hidden, while a hidden HUD keeps NeoForge's own answer. With
 * {@code -Dforbric.hudContextCallbacks=off} the wrap binds to the dead vanilla method: no overlay, and the final audit says
 * the injector never runs (SUSPECTED, not required).
 */
class CreateHudMixinAdapterWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/createhud");
	private static final String CONFIG = "createhud.mixins.json";
	private static final String MOD = "create";
	private static final String TARGET = "net/minecraft/client/gui/Hud";

	private static final String SCOPE = "net/forbric/kernel/interop/HudContextCallbackScope";
	/** NeoForge's live frame method, and vanilla's, which the merged game declares but never calls. */
	private static final List<String> HOSTS = List.of("extractRenderState", "extractHotbarAndDecorations");

	private static final String ADAPTED = WeaveHarnessMain.DONE + " shown: train overlay, bar EMPTY | hidden: bar EXPERIENCE";
	private static final String UNADAPTED = WeaveHarnessMain.DONE + " shown: bar EXPERIENCE | hidden: bar EXPERIENCE";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result adapted;
	private static WeaveHarness.Result off;

	@BeforeAll static void weave() throws Exception {
		fixture = WeaveHarness.fixture(work, "createhud", List.of(
				SOURCES.resolve("net/minecraft/client/Minecraft.java"),
				SOURCES.resolve("net/minecraft/client/DeltaTracker.java"),
				SOURCES.resolve("net/minecraft/client/gui/GuiGraphicsExtractor.java"),
				SOURCES.resolve("net/minecraft/client/gui/Hud.java"),
				SOURCES.resolve("fixture/createhud/TrainHud.java"),
				SOURCES.resolve("fixture/createhud/Probe.java"),
				SOURCES.resolve("com/zurrtum/create/client/mixin/HudMixin.java"),
				// The kernel's game-side hooks the adapted code reaches, which ForbricClassLoader must define from a jar it
				// owns; a fresh clone has no compiled runtime source set, so the production sources are compiled in here.
				Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelWrapOperations.java"),
				Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelHudContextQuery.java")),
				Map.of(CONFIG, SOURCES.resolve(CONFIG)),
				// KernelHudContextQuery names the boot-side HudContextCallbackScope: resolved from source, not packed, since the
				// loader always takes that package from the kernel.
				List.of("-sourcepath", "src/main/java", "-implicit:none"));
		// What the merged base's Hud is once KernelBoot's HudContextQueryInjector (client only) has run.
		PreMixinFixture.transform(fixture, HudContextQueryInjector.TARGET, new HudContextQueryInjector(), EnvType.CLIENT);
		adapted = run("adapted", Map.of());
		off = run("adapter-off", Map.of(MixinHudContextAdapter.PROPERTY, "off"));
	}

	@Test void theOverlayIsDrawnAtNeoForgesContextualBarAndF1StillHidesIt() throws Exception {
		assertTrue(adaptedHolds(adapted), adapted.describe() + "\nfindings " + adapted.findings());
		assertTrue(mixinRow(adapted).stream().allMatch(f -> f.confidence().equals("RESOLVED")), adapted.findings().toString());
		WeaveHarness.assertWovenAndVerified(adapted, TARGET, fixture);
	}

	@Test void switchedOffTheWrapSitsInTheDeadVanillaMethodAndNeverRuns() throws Exception {
		assertTrue(offHolds(off), off.describe() + "\nfindings " + off.findings());
		WeaveHarness.assertWovenAndVerified(off, TARGET, fixture);
	}

	/** Each run's predicate must fail on the other, or the control proves nothing about the adapter. */
	@Test void theControlFlipsEveryAdapterAssertion() throws Exception {
		assertTrue(adaptedHolds(adapted) && !adaptedHolds(off), "adapter predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(adapted), "control predicate does not separate the runs");
	}

	private static boolean adaptedHolds(WeaveHarness.Result run) throws Exception {
		return returned(run, ADAPTED) && losses(run).isEmpty() && hostsCallingTheMod(run).equals(List.of("extractRenderState"))
				&& entersScope(run);
	}

	private static boolean offHolds(WeaveHarness.Result run) throws Exception {
		List<WeaveHarness.Finding> losses = losses(run);
		return returned(run, UNADAPTED) && losses.size() == 1 && losses.get(0).id().contains("#renderMainHud(")
				&& losses.get(0).detail().contains("so it never runs")
				&& hostsCallingTheMod(run).equals(List.of("extractHotbarAndDecorations")) && !entersScope(run);
	}

	/** The probe's whole return line: a value that merely starts with the expected one is a different outcome. */
	private static boolean returned(WeaveHarness.Result run, String line) {
		return run.output().lines().anyMatch(line::equals);
	}

	/** The final audit's unresolved rows for the mod's injectors: not attached, or attached where nothing runs them. */
	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && f.id().startsWith("mixin-injector:")
				&& !f.confidence().equals("RESOLVED")).toList();
	}

	private static List<WeaveHarness.Finding> mixinRow(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.id().equals("mixin:" + CONFIG + ":com.zurrtum.create.client.mixin.HudMixin")).toList();
	}

	/** Whether any method of the defined Hud opens the kernel's HudContextCallbackScope. */
	private static boolean entersScope(WeaveHarness.Result run) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(run.defined(TARGET)).accept(node, 0);
		for (MethodNode method : node.methods) {
			for (var insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && call.owner.equals(SCOPE) && call.name.equals("enter")) return true;
			}
		}
		return false;
	}

	/** Which of {@link #HOSTS} in the defined Hud call a handler the mod's mixin merged into it, in class order. */
	private static List<String> hostsCallingTheMod(WeaveHarness.Result run) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(run.defined(TARGET)).accept(node, 0);
		List<String> hosts = new java.util.ArrayList<>();
		for (MethodNode method : node.methods) {
			if (!HOSTS.contains(method.name)) continue;
			for (var insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && call.owner.equals(TARGET) && call.name.contains("$" + MOD + "$")) {
					hosts.add(method.name);
					break;
				}
			}
		}
		return hosts;
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.CLIENT,
				"fixture.createhud.Probe", "probe", properties);
	}
}
