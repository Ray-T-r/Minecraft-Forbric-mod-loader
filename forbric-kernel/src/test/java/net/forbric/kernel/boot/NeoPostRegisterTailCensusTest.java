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

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * The kernel replaces NeoForge's {@code GameData.postRegisterEvents} with its own dispatch and a hand-made copy of
 * the steps after it. That copy left out {@code ItemTooltipHandler.init}, and every item tooltip lost its component
 * lines without a word. Each step NeoForge takes after its RegisterEvent loop must be one the kernel names.
 */
class NeoPostRegisterTailCensusTest {
	private static final Path STAGED = Path.of(System.getProperty("forbric.stagedRoot", "../forbric-loader/run"));
	private static final Path NEO_RT = STAGED.resolve("neoforge-runtime/neoforge-runtime.jar");

	/** NeoForge's step → the strings the kernel's copy uses to take it. */
	private static final Map<String, List<String>> REPLICATED = new LinkedHashMap<>();
	static {
		REPLICATED.put("net/neoforged/neoforge/common/CommonHooks.modifyAttributes", List.of("net.neoforged.neoforge.common.CommonHooks", "modifyAttributes"));
		REPLICATED.put("net/minecraft/world/entity/SpawnPlacements.fireSpawnPlacementEvent", List.of("net.minecraft.world.entity.SpawnPlacements", "fireSpawnPlacementEvent"));
		REPLICATED.put("new net/neoforged/neoforge/event/BlockEntityTypeAddBlocksEvent", List.of("net.neoforged.neoforge.event.BlockEntityTypeAddBlocksEvent"));
		REPLICATED.put("net/neoforged/neoforge/common/CreativeModeTabRegistry.sortTabs", List.of("sortCreativeTabs"));
		REPLICATED.put("net/minecraft/world/level/gamerules/GameRuleCategory.registerModdedCategories", List.of("net.minecraft.world.level.gamerules.GameRuleCategory", "registerModdedCategories"));
		REPLICATED.put("net/neoforged/neoforge/common/tooltip/ItemTooltipHandler.init", List.of("net.forbric.kernel.runtime.KernelNeoTooltips", "init"));
	}

	@Test void everyStepAfterNeoForgesRegisterLoopIsOneTheKernelTakes() throws Exception {
		Assumptions.assumeTrue(Files.isRegularFile(NEO_RT), NEO_RT + " absent");
		ClassNode gameData = new ClassNode();
		try (ZipFile zip = new ZipFile(NEO_RT.toFile())) {
			new ClassReader(zip.getInputStream(zip.getEntry("net/neoforged/neoforge/registries/GameData.class")).readAllBytes())
					.accept(gameData, 0);
		}
		MethodNode post = gameData.methods.stream().filter(m -> m.name.equals("postRegisterEvents")).findFirst()
				.orElseThrow(() -> new AssertionError("NeoForge's postRegisterEvents is gone"));
		Set<String> tail = new LinkedHashSet<>();
		boolean afterLoop = false;
		for (AbstractInsnNode insn : post.instructions) {
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC) {
				if (call.name.equals("revertToVanilla")) { afterLoop = true; continue; }
				if (afterLoop && !call.owner.equals("net/neoforged/fml/ModLoader")) tail.add(call.owner + "." + call.name);
			} else if (afterLoop && insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW) {
				tail.add("new " + type.desc);
			}
		}
		assertEquals(REPLICATED.keySet(), tail, "NeoForge's tail changed: take the new step in KernelLifecycle and list it here");

		Path compiled = Path.of(System.getProperty("user.dir"), "build/classes/java/main/net/forbric/kernel/boot/KernelLifecycle.class");
		Assumptions.assumeTrue(Files.isRegularFile(compiled), "KernelLifecycle not compiled yet");
		ClassNode lifecycle = new ClassNode();
		new ClassReader(Files.readAllBytes(compiled)).accept(lifecycle, 0);
		Set<Object> constants = new LinkedHashSet<>();
		for (MethodNode method : lifecycle.methods) {
			for (AbstractInsnNode insn : method.instructions) if (insn instanceof LdcInsnNode ldc) constants.add(ldc.cst);
		}
		REPLICATED.forEach((step, names) -> names.forEach(name ->
				assertTrue(constants.contains(name), step + ": KernelLifecycle never names " + name)));
	}
}
