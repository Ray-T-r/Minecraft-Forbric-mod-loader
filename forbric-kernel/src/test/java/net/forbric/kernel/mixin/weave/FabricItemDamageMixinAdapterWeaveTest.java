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
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.FabricItemDamageMixinAdapter;

/**
 * FabricItemDamageMixinAdapter through the real weave: fabric-item-api's {@code hookDamage}, a {@code @WrapOperation} of
 * vanilla's {@code hurtAndBreak(int, ServerLevel, ServerPlayer, Consumer)} call with the host's entity and slot as
 * {@code @Local} sugar, on NeoForge's {@code ItemStack}, whose {@code hurtAndBreak(int, LivingEntity, EquipmentSlot)} calls
 * NeoForge's {@code hurtAndBreak(int, ServerLevel, LivingEntity, Consumer)} with the entity itself instead.
 *
 * <p>The probe damages a tool whose custom damage handler absorbs all damage, held by a mob and by a player, and a plain
 * tool held by a mob. Moved, the wrap runs on NeoForge's call with the entity as a server player or null
 * ({@code KernelItemDamage.playerOf}) and an {@code Operation} that makes NeoForge's call with the entity again
 * ({@code KernelItemDamage.relay}, the production source compiled in): the handler is asked once per use and the tool takes
 * no damage, for the mob as for the player, and NeoForge's call is still told who held the tool. With
 * {@code -Dforbric.itemDamageHandler=off} the wrap names a call the body never makes: the handler is never asked, the tool
 * wears by vanilla's rule, and the final audit names the unbound handler.
 */
class FabricItemDamageMixinAdapterWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/itemdamage");
	private static final String CONFIG = "itemdamage.mixins.json";
	private static final String MOD = "fabric-item-api-v1";
	private static final String STACK = "net/minecraft/world/item/ItemStack";
	private static final String RUNTIME = "net/forbric/kernel/runtime/KernelItemDamage";
	private static final String MOVED = WeaveHarnessMain.DONE + " mob=handler:1,damage:0,base:zombie player=handler:1,damage:0,base:steve"
			+ " plain=handler:0,damage:1,base:zombie";
	private static final String UNMOVED = WeaveHarnessMain.DONE + " mob=handler:0,damage:1,base:zombie player=handler:0,damage:1,base:steve"
			+ " plain=handler:0,damage:1,base:zombie";
	private static final String MOVE_LOG = "net.fabricmc.fabric.mixin.item.ItemStackMixin: hookDamage now wraps "
			+ "ItemStack.hurtAndBreak(int, ServerLevel, LivingEntity, Consumer)";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result moved, off;

	@BeforeAll static void weaveBothWays() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = new ArrayList<>(walk.filter(p -> p.toString().endsWith(".java")).sorted().toList());
		}
		assertEquals(10, sources.size(), "the fixture's sources changed; update this test with it: " + sources);
		// The moved handler calls the kernel's game-side KernelItemDamage, which ForbricClassLoader must define from a jar it
		// owns; a fresh clone has no compiled runtime source set, so the production source is compiled in here.
		sources.add(Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelItemDamage.java"));
		fixture = WeaveHarness.fixture(work, "itemdamage", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		moved = run("moved", Map.of());
		off = run("item-damage-off", Map.of(FabricItemDamageMixinAdapter.PROPERTY, "off"));
		for (WeaveHarness.Result run : List.of(moved, off)) System.out.println(run.label() + ": " + run.output().lines()
				.filter(line -> line.startsWith(WeaveHarnessMain.DONE)).findFirst().orElse("(the probe printed no result)"));
	}

	@Test void aCustomDamageHandlerIsAskedOnNeoForgesCallForAMobAndAPlayer() throws Exception {
		assertTrue(moved.printedLine(MOVED), moved.describe());
		assertTrue(moved.printed(MOVE_LOG), moved.describe());
		assertEquals(List.of(), unbound(moved), moved.findings() + "\n" + moved.describe());
		assertTrue(calls(moved, RUNTIME, "relay") && calls(moved, RUNTIME, "playerOf"), moved.describe());
		WeaveHarness.assertWovenAndVerified(moved, STACK, fixture);
	}

	@Test void switchedOffTheWrapIsUnboundAndTheHandlerIsNeverAsked() throws Exception {
		assertTrue(off.printedLine(UNMOVED), off.describe());
		assertFalse(off.printed(MOVE_LOG), off.describe());
		assertEquals(1, unbound(off).size(), off.findings() + "\n" + off.describe());
		assertFalse(calls(off, RUNTIME, "relay"), off.describe());
		WeaveHarness.assertWovenAndVerified(off, STACK, fixture);
	}

	/** The switch is the runs' only difference, so each run's predicate must reject the other. */
	@Test void theControlFlipsEveryMoveAssertion() throws Exception {
		assertTrue(movedHolds(moved) && !movedHolds(off), "the move predicate does not separate the runs");
		assertTrue(unmovedHolds(off) && !unmovedHolds(moved), "the control predicate does not separate the runs");
	}

	private static boolean movedHolds(WeaveHarness.Result run) throws Exception {
		return run.printedLine(MOVED) && run.printed(MOVE_LOG) && unbound(run).isEmpty() && calls(run, RUNTIME, "relay");
	}

	private static boolean unmovedHolds(WeaveHarness.Result run) throws Exception {
		return run.printedLine(UNMOVED) && !run.printed(MOVE_LOG) && unbound(run).size() == 1 && !calls(run, RUNTIME, "relay");
	}

	/** Whether any method of the woven ItemStack calls {@code owner.name}: the moved handler is merged into it. */
	private static boolean calls(WeaveHarness.Result run, String owner, String name) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(run.defined(STACK)).accept(node, 0);
		for (MethodNode method : node.methods) {
			for (var insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) return true;
			}
		}
		return false;
	}

	/** The final audit's rows for the mod's required injectors that attached nowhere and are not settled either way. */
	private static List<WeaveHarness.Finding> unbound(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && f.id().startsWith("mixin-injector:")
				&& f.id().contains("#hookDamage(") && f.required() && !"RESOLVED".equals(f.confidence())).toList();
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER,
				"fixture.itemdamage.Probe", "probe", properties);
	}
}
