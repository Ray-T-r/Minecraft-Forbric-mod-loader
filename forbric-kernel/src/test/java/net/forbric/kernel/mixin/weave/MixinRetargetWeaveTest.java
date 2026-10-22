package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import net.forbric.kernel.mixin.MixinRetarget;

/**
 * MixinRetarget's R3 through the real weave: a guest injector whose method lost its body to a carrier's rename follows
 * the body, and one whose method merely shares a shape with another does not.
 *
 * <p>The fixture's {@code PersistentEntitySectionManager} has the shape of the carrier-renames.txt row
 * {@code addEntity -> addEntityWithoutEvent | FABRIC}: addEntity posts an event and calls the renamed body. Its
 * {@code Style} has the shape that misled R3 for text_styles: withColor(I) and withShadowColor(I) are the same shape,
 * only withShadowColor makes the call the mixin anchors on, and nothing renamed one to the other. Each mixin also has a
 * HEAD handler that binds where it is, so it reads PARTIAL, the only verdict the adapter plans a retarget for. The probe
 * adds an entity, sets a colour, then a shadow colour, and returns both traces.
 * <ul>
 *   <li>fabric — the entity hook runs in the renamed body; the colour hook stays where it was written, a reported loss;</li>
 *   <li>census-off — {@code -Dforbric.mixinRetarget.renameCensus=off}, R3 on the bytes alone as before the census: the
 *       entity hook still moves, and the colour hook runs on the shadow colour;</li>
 *   <li>retarget-off — {@code -Dforbric.mixinRetarget=off}: nothing moves, both anchors are reported losses;</li>
 *   <li>neoforge — the row is Fabric's only: NeoForge's own addEntity is the dispatcher its mods were compiled against.</li>
 *   <li>native — the same, with the Fabric game's own addEntity (the body) as the native reference, so every adapter that
 *       compares the merged method with it is awake. CarrierHelperResults sees the renamed body as a boolean helper of the
 *       class that makes the call and answers whether it did; it must leave the injector to R3, which puts it on the
 *       native instruction — before the ticking call — and not run it on the helper's answer, after it.</li>
 * </ul>
 */
class MixinRetargetWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/renamedbody");
	private static final String CONFIG = "renamedbody.mixins.json";
	private static final String MOD = "renamedbody";
	private static final String MANAGER = "net/minecraft/world/level/entity/PersistentEntitySectionManager";
	private static final String STYLE = "net/minecraft/network/chat/Style";

	/** The entity hook in the renamed body; the colour hook nowhere. */
	private static final String RENAMED = "manager[head;event;body;added;ticking;] style[colorHead;color;shadow;]";
	/** Both on the bytes alone: the colour hook runs on the shadow colour, which no game ever did. */
	private static final String BYTES_ALONE = "manager[head;event;body;added;ticking;] style[colorHead;color;shadow;colorChange;]";
	/** Nothing moved. */
	private static final String STAYED = "manager[head;event;body;ticking;] style[colorHead;color;shadow;]";
	private static final String ENTITY_MOVED = "EntityManagerMixin — addEntity → addEntityWithoutEvent";
	private static final String STYLE_MOVED = "StyleMixin — withColor(I)Lnet/minecraft/network/chat/Style; → withShadowColor";

	@TempDir static Path work;
	private static Path fixture, withNative;
	private static final Map<String, WeaveHarness.Result> RUNS = new LinkedHashMap<>();

	@BeforeAll static void weave() throws Exception {
		fixture = WeaveHarness.fixture(work, "renamedbody", List.of(
				SOURCES.resolve("net/minecraft/world/level/entity/EntityAccess.java"),
				SOURCES.resolve("net/minecraft/world/level/entity/Visibility.java"),
				SOURCES.resolve("net/minecraft/world/level/entity/PersistentEntitySectionManager.java"),
				SOURCES.resolve("net/minecraft/network/chat/Style.java"),
				SOURCES.resolve("fixture/renamedbody/Probe.java"),
				SOURCES.resolve("fixture/renamedbody/mixin/EntityManagerMixin.java"),
				SOURCES.resolve("fixture/renamedbody/mixin/StyleMixin.java")),
				Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		RUNS.put("fabric", run("fabric", Ecosystem.FABRIC, Map.of()));
		RUNS.put("census-off", run("census-off", Ecosystem.FABRIC, Map.of("forbric.mixinRetarget.renameCensus", "off")));
		RUNS.put("retarget-off", run("retarget-off", Ecosystem.FABRIC, Map.of(MixinRetarget.PROPERTY, "off")));
		RUNS.put("neoforge", run("neoforge", Ecosystem.NEOFORGE, Map.of()));
		Path nativeFixture = WeaveHarness.fixture(work, "renamedbody-native", List.of(
				SOURCES.resolve("net/minecraft/world/level/entity/EntityAccess.java"),
				SOURCES.resolve("net/minecraft/world/level/entity/Visibility.java"),
				SOURCES.resolve("native/net/minecraft/world/level/entity/PersistentEntitySectionManager.java"),
				SOURCES.resolve("net/minecraft/network/chat/Style.java"),
				SOURCES.resolve("fixture/renamedbody/Probe.java"),
				SOURCES.resolve("fixture/renamedbody/mixin/EntityManagerMixin.java"),
				SOURCES.resolve("fixture/renamedbody/mixin/StyleMixin.java")),
				Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		Map<String, byte[]> natives = new LinkedHashMap<>();
		natives.put(MANAGER, NativeWeaveReferences.classes(nativeFixture).get(MANAGER));
		withNative = NativeWeaveReferences.with(work, fixture, natives);
		RUNS.put("native", WeaveHarness.run(work, "native", withNative, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER,
				"fixture.renamedbody.Probe", "run", Map.of()));
	}

	/**
	 * With the native body known, the renamed body is still followed: the hook runs on the native instruction, before the
	 * ticking call, and not on the helper's answer after the whole body (where CarrierHelperResults would have put it).
	 */
	@Test void withTheNativeBodyKnownTheHookStillLandsOnTheNativeInstruction() throws Exception {
		WeaveHarness.Result run = RUNS.get("native");
		assertTrue(renamedOnly(run), run.describe() + "\nfindings: " + run.findings());
		assertFalse(run.printed("EntityManagerMixin.renamedbody$added: net.minecraft.world.level.entity.Visibility.isTicking is no longer called"),
				run.describe());
		WeaveHarness.assertWovenAndVerified(run, MANAGER, withNative);
	}

	@Test void theRenamedBodyIsFollowedAndTheLookAlikeIsNot() throws Exception {
		WeaveHarness.Result fabric = RUNS.get("fabric");
		assertTrue(renamedOnly(fabric), fabric.describe() + "\nfindings: " + fabric.findings());
		WeaveHarness.assertWovenAndVerified(fabric, MANAGER, fixture);
		WeaveHarness.assertWovenAndVerified(fabric, STYLE, fixture);
	}

	/** The census is what keeps the colour hook off the shadow colour: without it, R3 moves it there and it runs. */
	@Test void onTheBytesAloneTheLookAlikeMoves() throws Exception {
		WeaveHarness.Result off = RUNS.get("census-off");
		assertTrue(bytesAlone(off), off.describe() + "\nfindings: " + off.findings());
		WeaveHarness.assertWovenAndVerified(off, STYLE, fixture);
	}

	@Test void withTheRetargetOffNothingMoves() throws Exception {
		WeaveHarness.Result off = RUNS.get("retarget-off");
		assertTrue(stayed(off), off.describe() + "\nfindings: " + off.findings());
		WeaveHarness.assertWovenAndVerified(off, MANAGER, fixture);
	}

	/** The row's ecosystems, read through the real pipeline: a NeoForge mod was compiled against the dispatcher. */
	@Test void aNeoForgeModStaysOnTheDispatcher() throws Exception {
		WeaveHarness.Result neoforge = RUNS.get("neoforge");
		assertTrue(stayed(neoforge), neoforge.describe() + "\nfindings: " + neoforge.findings());
	}

	/** The runs and their controls must be told apart by the very predicates the tests use on them. */
	@Test void theControlsFlipEveryAssertion() {
		WeaveHarness.Result fabric = RUNS.get("fabric"), census = RUNS.get("census-off"), off = RUNS.get("retarget-off");
		assertTrue(renamedOnly(fabric) && !renamedOnly(census) && !renamedOnly(off), "renamed-body predicate does not separate the runs");
		assertTrue(bytesAlone(census) && !bytesAlone(fabric) && !bytesAlone(off), "bytes-alone predicate does not separate the runs");
		assertTrue(stayed(off) && !stayed(fabric) && !stayed(census), "control predicate does not separate the runs");
	}

	private static boolean renamedOnly(WeaveHarness.Result run) {
		return run.printed(WeaveHarnessMain.DONE + " " + RENAMED) && run.printed(ENTITY_MOVED) && !run.printed(STYLE_MOVED)
				&& losses(run, "renamedbody$added") == 0 && losses(run, "renamedbody$colorChange") == 1
				&& handlerCalls(run, MANAGER, "addEntityWithoutEvent") == 1 && handlerCalls(run, STYLE, "withShadowColor") == 0;
	}

	private static boolean bytesAlone(WeaveHarness.Result run) {
		return run.printed(WeaveHarnessMain.DONE + " " + BYTES_ALONE) && run.printed(ENTITY_MOVED) && run.printed(STYLE_MOVED)
				&& losses(run, "renamedbody$added") == 0 && losses(run, "renamedbody$colorChange") == 0
				&& handlerCalls(run, MANAGER, "addEntityWithoutEvent") == 1 && handlerCalls(run, STYLE, "withShadowColor") == 1;
	}

	private static boolean stayed(WeaveHarness.Result run) {
		return run.printed(WeaveHarnessMain.DONE + " " + STAYED) && !run.printed(ENTITY_MOVED) && !run.printed(STYLE_MOVED)
				&& losses(run, "renamedbody$added") == 1 && losses(run, "renamedbody$colorChange") == 1
				&& handlerCalls(run, MANAGER, "addEntityWithoutEvent") == 0 && handlerCalls(run, STYLE, "withShadowColor") == 0;
	}

	private static WeaveHarness.Result run(String label, Ecosystem ecosystem, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, ecosystem, EnvType.SERVER, "fixture.renamedbody.Probe", "run",
				properties);
	}

	/** Confirmed, required injector losses the audit recorded for {@code handler}. */
	private static long losses(WeaveHarness.Result run, String handler) {
		return run.findings().stream().filter(f -> f.id().startsWith("mixin-injector:") && f.id().contains("#" + handler)
				&& f.modId().equals(MOD) && f.confirmedRequired()).count();
	}

	/** Calls from the woven {@code method} of {@code owner} to a handler Mixin merged in. */
	private static int handlerCalls(WeaveHarness.Result run, String owner, String method) {
		ClassNode woven = new ClassNode();
		try {
			new ClassReader(run.defined(owner)).accept(woven, 0);
		} catch (java.io.IOException unreadable) {
			throw new java.io.UncheckedIOException(unreadable);
		}
		List<String> merged = woven.methods.stream().filter(m -> m.visibleAnnotations != null && m.visibleAnnotations.stream()
				.anyMatch(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;")))
				.map(m -> m.name + m.desc).toList();
		int calls = 0;
		for (MethodNode m : woven.methods) {
			if (!m.name.equals(method)) continue;
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && merged.contains(call.name + call.desc)) calls++;
			}
		}
		return calls;
	}
}
