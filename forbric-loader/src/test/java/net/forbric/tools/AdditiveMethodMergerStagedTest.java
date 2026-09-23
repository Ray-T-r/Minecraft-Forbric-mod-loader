/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Feeds the actual vanilla, MinecraftForge-patched and NeoForge-patched 26.2 methods to the merger. Synthetic
 * fixtures prove the grammar; these pin what it says about the real counterexamples, so widening it cannot
 * quietly start accepting one of them.
 */
class AdditiveMethodMergerStagedTest {
	private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
	private static final String PLAYER = "net/minecraft/world/entity/player/Player";
	private static final String FALL = "causeFallDamage(DFLnet/minecraft/world/damagesource/DamageSource;)Z";

	@Test
	void livingFallHooksFireAtDifferentStagesAndAreRefusedInEitherBaseOrder() throws Exception {
		MethodNode vanilla = method("vanilla", LIVING, FALL), forge = method("forge", LIVING, FALL), neo = method("neo", LIVING, FALL);
		// MinecraftForge posts LivingFallEvent at entry with the raw distance; NeoForge posts it after vanilla has
		// clamped the distance to the current impulse. Neither order of the two is either family's contract.
		for (boolean neoBase : new boolean[] { true, false }) {
			AdditiveMethodMerger.Result result = neoBase
					? AdditiveMethodMerger.merge(vanilla, neo, forge, "net/neoforged/", "net/minecraftforge/", AdditiveMethodMergerTest.PROBES)
					: AdditiveMethodMerger.merge(vanilla, forge, neo, "net/minecraftforge/", "net/neoforged/", AdditiveMethodMergerTest.PROBES);
			assertFalse(result.accepted(), result.reason());
			assertTrue(result.reason().startsWith("hook stages differ"), result.reason());
			assertTrue(result.reason().contains("CommonHooks.onLivingFall"), result.reason());
			assertTrue(result.reason().contains("ForgeEventFactory.onLivingFall"), result.reason());
		}
	}

	@Test
	void playerFallHooksAreRefusedToo() throws Exception {
		// Both families post the flyable fall at the same vanilla point, so this is not a stage refusal: NeoForge
		// also rewrote vanilla's abilities.mayfly read into mayFly(), and its hook takes different inputs.
		MethodNode vanilla = method("vanilla", PLAYER, FALL), forge = method("forge", PLAYER, FALL), neo = method("neo", PLAYER, FALL);
		AdditiveMethodMerger.Result result = AdditiveMethodMerger.merge(vanilla, neo, forge, "net/neoforged/",
				"net/minecraftforge/", AdditiveMethodMergerTest.PROBES);
		assertFalse(result.accepted(), result.reason());
		assertEquals("base changes vanilla operands, control flow or handlers", result.reason());
	}

	@Test
	void spawnerFinalizationIsRefusedBecauseNeoForgesHookReplacesTheVanillaCallForgeKeeps() throws Exception {
		String key = "serverTick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;)V";
		String owner = "net/minecraft/world/level/BaseSpawner";
		AdditiveMethodMerger.Result result = AdditiveMethodMerger.merge(method("vanilla", owner, key), method("neo", owner, key),
				method("forge", owner, key), "net/neoforged/", "net/minecraftforge/", AdditiveMethodMergerTest.PROBES);
		assertFalse(result.accepted(), result.reason());
		assertTrue(result.reason().startsWith("one side replaces a vanilla call: base drops net/minecraft/world/entity/Mob.finalizeSpawn("),
				result.reason());
		assertTrue(result.reason().endsWith("that other keeps"), result.reason());
	}

	@Test
	void portalPilotComposesInTheReviewedGuardedShapeAgainstTheRealRuntimes() throws Exception {
		String owner = "net/minecraft/world/level/block/BaseFireBlock";
		String key = "onPlace(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;"
				+ "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V";
		String forgeHooks = "net/minecraftforge/event/ForgeEventFactory", neoHooks = "net/neoforged/neoforge/event/EventHooks";
		AdditiveMethodMerger.Context production = AdditiveMethodMerger.context(List.of(
				Map.of(forgeHooks, classBytes("forge-runtime", forgeHooks)), Map.of(neoHooks, classBytes("neo-runtime", neoHooks))),
				AdditiveMethodMerger.REVIEWED_RESTORATIONS);
		MethodNode vanilla = method("vanilla", owner, key), forge = method("forge", owner, key), neo = method("neo", owner, key);
		AdditiveMethodMerger.Result result = AdditiveMethodMerger.merge(vanilla, neo, forge, "net/neoforged/", "net/minecraftforge/", production);
		assertTrue(result.accepted(), result.reason());

		// Exactly NeoForge's body, with its refusal guard repeated and MinecraftForge's call inserted after the
		// store: the shape the kernel's PortalSpawnInjector proves before it scopes the legacy forward.
		List<String> expected = shapes(neo);
		int store = expected.indexOf("INVOKESTATIC " + neoHooks + ".onTrySpawnPortal") + 1;
		List<String> guard = expected.subList(store + 1, store + 4);
		assertEquals(List.of("ALOAD 6", "INVOKEVIRTUAL java/util/Optional.isPresent", "IFEQ"), guard);
		List<String> inserted = new ArrayList<>(guard);
		inserted.addAll(List.of("ALOAD 2", "ALOAD 3", "ALOAD 6", "INVOKESTATIC " + forgeHooks + ".onTrySpawnPortal", "ASTORE 6"));
		expected.addAll(store + 1, inserted);
		assertEquals(expected, shapes(result.method()));
		List<AbstractInsnNode> code = new ArrayList<>();
		for (AbstractInsnNode instruction : result.method().instructions) if (instruction.getOpcode() >= 0) code.add(instruction);
		assertSame(((JumpInsnNode) code.get(store + 3)).label, ((JumpInsnNode) code.get(store + 11)).label,
				"the repeated guard refuses to the same place NeoForge's own guard does");

		// The reviewed restoration is ordered: MinecraftForge first would not be the shape the kernel proves.
		AdditiveMethodMerger.Result reversed = AdditiveMethodMerger.merge(vanilla, forge, neo, "net/minecraftforge/", "net/neoforged/", production);
		assertFalse(reversed.accepted());
		assertTrue(reversed.reason().startsWith("paired hooks align, but no runtime stand-down is reviewed"), reversed.reason());
	}

	private static List<String> shapes(MethodNode method) {
		List<String> out = new ArrayList<>();
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction.getOpcode() < 0) continue;
			String op = org.objectweb.asm.util.Printer.OPCODES[instruction.getOpcode()];
			if (instruction instanceof VarInsnNode variable) out.add(op + " " + variable.var);
			else if (instruction instanceof MethodInsnNode call) out.add(op + " " + call.owner + "." + call.name);
			else if (instruction instanceof JumpInsnNode) out.add(op);
			else out.add(op);
		}
		return out;
	}

	static MethodNode method(String side, String owner, String key) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(classBytes(side, owner)).accept(node, 0);
		return node.methods.stream().filter(m -> (m.name + m.desc).equals(key)).findFirst().orElseThrow();
	}

	static byte[] classBytes(String side, String owner) throws Exception {
		Path jar = jar(side);
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			return zip.getInputStream(zip.getEntry(owner + ".class")).readAllBytes();
		}
	}

	/** The same staged inputs the kernel's structural tests read: FORBRIC_OLD's run tree and the launcher's vanilla jar. */
	static Path jar(String side) {
		String old = System.getenv("FORBRIC_OLD");
		Path run = old == null ? Path.of("run") : Path.of(old, "run");
		String mc = System.getenv("MC_DIR");
		Path path = switch (side) {
			case "vanilla" -> Path.of(mc != null && !mc.isBlank() ? mc : System.getProperty("user.home")
					+ "/Library/Application Support/minecraft").resolve("versions/26.2/26.2.jar");
			case "forge" -> run.resolve("forge-patched/patched-mc-forge-26.2.jar");
			case "neo" -> run.resolve("neoforge-patched/patched-mc-neoforge-26.2.jar");
			case "forge-runtime" -> run.resolve("forge-runtime/forge-runtime.jar");
			case "neo-runtime" -> run.resolve("neoforge-runtime/neoforge-runtime.jar");
			default -> throw new IllegalArgumentException(side);
		};
		boolean present = Files.isRegularFile(path);
		if ("1".equals(System.getenv("FORBRIC_COMPAT_FIXTURES_REQUIRED"))) assertTrue(present, "staged input absent: " + path);
		assumeTrue(present, "staged input absent: " + path);
		return path;
	}
}
