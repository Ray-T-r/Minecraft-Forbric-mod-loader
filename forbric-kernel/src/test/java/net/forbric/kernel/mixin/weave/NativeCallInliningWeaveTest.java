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
import net.forbric.kernel.mixin.NativeCallInlining;

/**
 * {@code NativeCallInlining} through the real weave, on classes that share no name with any game or mod.
 *
 * <p>The host {@code org.atelier.scene.Stage} is written twice. As its guest's own loader has it, it makes three calls
 * itself: it paints its front panel, discards the item it drops, and asks the sky once per walker. As merged, a carrier
 * makes each of those calls inside a helper of its own: a layered painting it inherits ({@code Loom.weave}, which paints
 * every backdrop and then the front, each through a private static {@code weaveOne} around its own events), a static
 * hook ({@code Relay.toss}, which discards through the actor it is handed), and an instance method of an event object a
 * static factory makes ({@code Notice.allows}). The guest, {@code com.hollow.tint}, anchors a {@code @WrapWithCondition}
 * and an {@code @Inject(AFTER)} on the front panel's paint, a cancellable {@code @Inject} with a {@code @Local} on the
 * discard, and an {@code @Inject} on the second sky question ({@code ordinal = 1}) with a {@code @Local} walker.
 *
 * <p>Inlined, each injector binds where it binds natively and runs the guest's own code there — around the front panel
 * only (the backdrops are painted through the helper, as natively there are none), once per discard (and a cancel stops
 * the discard), once per walker — while every carrier step (its events, its relay lines) still runs in its order. A
 * subclass that paints its own layers keeps its own dispatch. Switched off, none of the guest's injectors runs.
 */
class NativeCallInliningWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/nativecallinline");
	private static final String CONFIG = "nativecallinline.mixins.json";
	private static final String MOD = "tint";
	private static final String TARGET = "org/atelier/scene/Stage";

	private static final String RENDER = "render-begin,weave-begin,before:back,paint:back@2147483647,2147483647,after:back,before:front,"
			+ "gate:front@3,4,paint:front@3,4,tint-after,after:front,weave-end,render-end";
	private static final String DROPS = "relay-begin,toss:apple,stage-discard:apple,actor-discard:apple,relay-end,relay-begin,toss:pear";
	private static final String TICK = "sky-open,notice:ada,cache:ada,sky-open,spawn-for:ada,notice:bo,cache:bo,sky-open,spawn-for:bo";
	private static final String SUBCLASS = "render-begin,backstage-weave,render-end";
	private static final String INLINED = WeaveHarnessMain.DONE + " " + RENDER + ",|," + DROPS + ",|," + TICK + ",|," + SUBCLASS;
	private static final String OFF = WeaveHarnessMain.DONE + " render-begin,weave-begin,before:back,paint:back@2147483647,2147483647,"
			+ "after:back,before:front,paint:front@3,4,after:front,weave-end,render-end,|,relay-begin,stage-discard:apple,"
			+ "actor-discard:apple,relay-end,relay-begin,stage-discard:pear,actor-discard:pear,relay-end,|,sky-open,notice:ada,sky-open,"
			+ "spawn-for:ada,notice:bo,sky-open,spawn-for:bo,|," + SUBCLASS;

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result inlined;
	private static WeaveHarness.Result off;

	@BeforeAll static void weave() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = walk.filter(p -> p.toString().endsWith(".java") && !SOURCES.relativize(p).startsWith("native")).sorted().toList();
		}
		assertEquals(20, sources.size(), "the fixture's sources changed; update this test with it: " + sources);
		Path merged = WeaveHarness.fixture(work, "nativecallinline", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g"));
		List<Path> nativeSources = new ArrayList<>(sources);
		nativeSources.remove(SOURCES.resolve("org/atelier/scene/Stage.java"));
		nativeSources.removeIf(p -> SOURCES.relativize(p).startsWith("com"));
		nativeSources.add(SOURCES.resolve("native/org/atelier/scene/Stage.java"));
		Path original = WeaveHarness.fixture(work, "original", nativeSources, Map.of(), List.of("-g"));
		fixture = NativeWeaveReferences.with(work, merged, NativeWeaveReferences.classes(original));
		inlined = run("inlined", Map.of());
		off = run("off", Map.of(NativeCallInlining.PROPERTY, "off"));
	}

	@Test void everyInjectorRunsTheGuestsCodeWhereItsLoaderRunsIt() throws Exception {
		assertTrue(inlined.printedLine(INLINED), inlined.describe());
		assertTrue(inlined.printed("[Forbric/Mixin] com.hollow.tint.mixin.StageMixin.tint$gate: Lorg/atelier/scene/Panel;paint"), inlined.describe());
		// The preflight read the target before the inline existed; every injector then attached, so its suspicion resolves.
		assertEquals(List.of(), inlined.findings().stream().filter(f -> f.modId().equals(MOD) && !f.confidence().equals("RESOLVED")).toList(),
				inlined.describe());
		WeaveHarness.assertWovenAndVerified(inlined, TARGET, fixture);
	}

	@Test void theInlinedMethodsCallTheGuestAndNoLongerTheHelpers() throws Exception {
		ClassNode stage = defined(inlined);
		assertTrue(callsGuest(stage, "render") && callsGuest(stage, "drop") && callsGuest(stage, "tick"), inlined.describe());
		assertFalse(calls(stage, "drop", "org/atelier/loom/Relay", "toss"), "the static hook is inlined");
		assertFalse(calls(stage, "tick", "org/atelier/loom/Notice", "allows"), "the event's own answer is inlined");
		// The layered painting stays a call on the subclass path, guarded by the receiver's exact class.
		assertTrue(calls(stage, "render", "org/atelier/scene/Stage", "weave"), "the guarded fallback keeps the virtual call");
	}

	@Test void switchedOffNoneOfTheGuestsInjectorsRuns() throws Exception {
		assertTrue(off.printedLine(OFF), off.describe());
		assertFalse(off.printed("[Forbric/Mixin] com.hollow.tint.mixin.StageMixin.tint$gate: Lorg/atelier/scene/Panel;paint"), off.describe());
		assertFalse(callsGuest(defined(off), "drop"), off.describe());
	}

	/** Each run's predicate must fail on the other, or the control proves nothing. */
	@Test void theControlFlipsEveryAssertion() {
		assertTrue(inlined.printedLine(INLINED) && !off.printedLine(INLINED));
		assertTrue(off.printedLine(OFF) && !inlined.printedLine(OFF));
	}

	private static ClassNode defined(WeaveHarness.Result run) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(run.defined(TARGET)).accept(node, 0);
		return node;
	}

	private static boolean callsGuest(ClassNode stage, String method) {
		for (MethodNode body : stage.methods) {
			if (!body.name.equals(method)) continue;
			for (AbstractInsnNode insn : body.instructions)
				if (insn instanceof MethodInsnNode call && call.owner.equals(TARGET) && call.name.contains("tint$")) return true;
		}
		return false;
	}

	private static boolean calls(ClassNode stage, String method, String owner, String name) {
		for (MethodNode body : stage.methods) {
			if (!body.name.equals(method)) continue;
			for (AbstractInsnNode insn : body.instructions)
				if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) return true;
		}
		return false;
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER,
				"fixture.nativecallinline.Probe", "probe", properties);
	}
}
