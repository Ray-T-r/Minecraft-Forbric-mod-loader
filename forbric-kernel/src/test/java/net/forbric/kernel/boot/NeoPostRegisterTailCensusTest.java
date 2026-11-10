package net.forbric.kernel.boot;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.transform.RegisterEventsSeam;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
/**
 * The kernel used to replace NeoForge's {@code GameData.postRegisterEvents} with its own dispatch and a hand-made copy
 * of the steps after it. That copy left out {@code ItemTooltipHandler.init}, and every item tooltip lost its component
 * lines without a word; and a mod hooking the method never ran (K3: supermartijn642corelib). The method runs as NeoForge
 * ships it now, with {@code RegisterEventsSeam} routing each tail step through the kernel's step hook: so each step NeoForge
 * takes after its RegisterEvent loop must still be one the seam routes, and the two the kernel treats specially
 * ({@code modifyAttributes}, its own catch site; {@code ItemTooltipHandler.init}, behind its switch) must still be named
 * by {@code KernelLifecycle.onRegisterEventsTailStep}.
 */
class NeoPostRegisterTailCensusTest {
	private static final Path STAGED = TestFixtures.stagedRoot();
	private static final Path NEO_RT = STAGED.resolve(TestFixtures.NEO_CARRIER_JAR);

	/** NeoForge's step → the strings KernelLifecycle names to treat it specially (empty: the generic step). */
	private static final Map<String, List<String>> TAKEN = new LinkedHashMap<>();
	static {
		TAKEN.put("net/neoforged/neoforge/common/CommonHooks.modifyAttributes", List.of("net.neoforged.neoforge.common.CommonHooks", "modifyAttributes"));
		TAKEN.put("net/minecraft/world/entity/SpawnPlacements.fireSpawnPlacementEvent", List.of());
		TAKEN.put("new net/neoforged/neoforge/event/BlockEntityTypeAddBlocksEvent", List.of());
		TAKEN.put("net/neoforged/neoforge/common/CreativeModeTabRegistry.sortTabs", List.of());
		TAKEN.put("net/minecraft/world/level/gamerules/GameRuleCategory.registerModdedCategories", List.of());
		TAKEN.put("net/neoforged/neoforge/common/tooltip/ItemTooltipHandler.init", List.of("net.neoforged.neoforge.common.tooltip.ItemTooltipHandler", "init"));
	}

	@Test void everyStepAfterNeoForgesRegisterLoopIsOneTheKernelTakes() throws Exception {
		TestFixtures.requireFile(Fixture.STAGED, NEO_RT, NEO_RT + " absent");
		ClassNode gameData = new ClassNode();
		try (ZipFile zip = new ZipFile(NEO_RT.toFile())) {
			new ClassReader(zip.getInputStream(zip.getEntry("net/neoforged/neoforge/registries/GameData.class")).readAllBytes())
					.accept(gameData, 0);
		}
		assertEquals(TAKEN.keySet(), tail(post(gameData), false),
				"NeoForge's tail changed: check RegisterEventsSeam still routes the new step, and list it here");

		assertNotNull(RegisterEventsSeam.apply(gameData), "RegisterEventsSeam no longer recognises NeoForge's method");
		Set<String> routed = tail(post(gameData), true);
		assertEquals(TAKEN.keySet(), routed, "a tail step the seam did not route to the kernel's step hook");

		Path compiled = Path.of(System.getProperty("user.dir"), "build/classes/java/main/net/forbric/kernel/boot/KernelLifecycle.class");
		assertTrue(Files.isRegularFile(compiled),
				"KernelLifecycle not found in the compiled src/main classes, which exist before any test runs");
		ClassNode lifecycle = new ClassNode();
		new ClassReader(Files.readAllBytes(compiled)).accept(lifecycle, 0);
		MethodNode step = lifecycle.methods.stream().filter(m -> m.name.equals("onRegisterEventsTailStep")).findFirst()
				.orElseThrow(() -> new AssertionError("KernelLifecycle.onRegisterEventsTailStep is gone"));
		Set<Object> constants = new LinkedHashSet<>();
		for (AbstractInsnNode insn : step.instructions) if (insn instanceof LdcInsnNode ldc) constants.add(ldc.cst);
		TAKEN.forEach((name, names) -> names.forEach(n ->
				assertTrue(constants.contains(n), name + ": onRegisterEventsTailStep never names " + n)));
	}

	private static MethodNode post(ClassNode gameData) {
		return gameData.methods.stream().filter(m -> m.name.equals("postRegisterEvents")).findFirst()
				.orElseThrow(() -> new AssertionError("NeoForge's postRegisterEvents is gone"));
	}

	/**
	 * The steps after the roll-back test: as NeoForge names them, or ({@code routed}) only those that now reach the kernel —
	 * a no-argument step as the class/name pair the seam hands {@code onRegisterEventsTailStep}, the event as its NEW once
	 * its {@code ModLoader.postEvent} goes to {@code onRegisterEventsModBusEvent}.
	 */
	private static Set<String> tail(MethodNode post, boolean routed) {
		Set<String> tail = new LinkedHashSet<>();
		boolean afterLoop = false;
		String pending = null;
		for (AbstractInsnNode insn : post.instructions) {
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC) {
				if (call.name.equals("revertToVanilla")) { afterLoop = true; continue; }
				if (!afterLoop) continue;
				if (!routed) {
					if (!call.owner.equals("net/neoforged/fml/ModLoader") && !call.owner.startsWith("net/forbric/")) tail.add(call.owner + "." + call.name);
				} else if (call.name.equals("onRegisterEventsTailStep") && insn.getPrevious() instanceof LdcInsnNode name
						&& name.getPrevious() instanceof LdcInsnNode owner && owner.cst instanceof Type type) {
					tail.add(type.getInternalName() + "." + name.cst);
				} else if (call.name.equals("onRegisterEventsModBusEvent") && pending != null) {
					tail.add(pending);
				}
			} else if (afterLoop && insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW) {
				if (routed) pending = "new " + type.desc;
				else tail.add("new " + type.desc);
			}
		}
		return tail;
	}
}
