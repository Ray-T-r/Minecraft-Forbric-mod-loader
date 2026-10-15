/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

/**
 * Fabric runs every {@code preLaunch} entrypoint before any game class is defined, and a mod may register a mixin config
 * from there ({@code Mixins.addConfiguration}). The kernel's self-check of its own game-side classes ran right after
 * Mixin came up, before {@code preLaunch}: proving a class links resolves every type its public methods name, and that
 * defined about forty Minecraft classes ({@code MinecraftServer}, {@code BlockableEventLoop}, {@code Screen},
 * {@code Component}, {@code CompoundTag}...). Essential registers its configs from {@code preLaunch}; Mixin found
 * {@code BlockableEventLoop} already defined, threw {@code MixinTargetAlreadyLoadedException}, and the client died at
 * boot. Measured in game with {@code -Xlog:class+load}: before, 40 classes of the merged base were defined ahead of
 * Essential's {@code preLaunch}; after, none.
 */
class PreLaunchBeforeGameClassesTest {
	private static MethodNode launch() throws Exception {
		Path file = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main", "net", "forbric", "kernel", "boot",
				"KernelBoot.class");
		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(file)).accept(node, 0);
		return node.methods.stream().filter(m -> m.name.equals("launch")).findFirst().orElseThrow();
	}

	private static List<String> calls(MethodNode method) {
		List<String> out = new ArrayList<>();
		for (AbstractInsnNode i : method.instructions) if (i instanceof MethodInsnNode call) out.add(call.owner + "." + call.name);
		return out;
	}

	@Test void theGameSideSelfCheckRunsOnlyOncePreLaunchHasRun() throws Exception {
		List<String> calls = calls(launch());
		int mixin = calls.indexOf("net/forbric/kernel/mixin/KernelMixinBootstrap.init");
		int preLaunch = calls.indexOf("net/forbric/kernel/boot/KernelFabricEcosystem.runPreLaunch");
		int verify = calls.indexOf("net/forbric/kernel/boot/KernelRuntimeClasses.verify");
		assertTrue(mixin >= 0 && preLaunch > mixin, "preLaunch runs once Mixin is up: " + calls);
		assertEquals(calls.lastIndexOf("net/forbric/kernel/boot/KernelRuntimeClasses.verify"), verify, "verified once");
		assertTrue(verify > preLaunch, "verify defines the game types its signatures name, so not before preLaunch: " + calls);
		assertTrue(calls.subList(verify, calls.size()).contains("java/lang/Class.forName"),
				"and still before the game's main class loads");
	}
}
