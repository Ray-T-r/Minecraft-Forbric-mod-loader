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
 * {@code FabricLoader.getGameInstance()} answers the Minecraft instance on a client (Fabric's {@code Hooks.startClient}
 * hands it to {@code prepareModInit} before any entrypoint runs) and the dedicated server on a server (set from its
 * construction). The kernel never set it, so every mod asking got null on both sides. In game the probe now sees the
 * Minecraft instance and the dedicated server, as on native Fabric; here, the wiring the boot cannot run in a unit test.
 */
class FabricGameInstanceTest {
	private static ClassNode compiled(String sourceSet, String name) throws Exception {
		Path file = Path.of(System.getProperty("user.dir"), "build", "classes", "java", sourceSet, name + ".class");
		// The runtime source set (the game side) is compiled only when the staged game jars are present.
		if ("runtime".equals(sourceSet)) {
			net.forbric.kernel.TestFixtures.require(net.forbric.kernel.TestFixtures.Fixture.GAME_SIDE, Files.isRegularFile(file),
					"compiled game side absent: " + file);
		}
		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(file)).accept(node, 0);
		return node;
	}

	private static List<String> calls(MethodNode method) {
		List<String> out = new ArrayList<>();
		for (AbstractInsnNode i : method.instructions) if (i instanceof MethodInsnNode call) out.add(call.owner + "." + call.name);
		return out;
	}

	@Test void startClientHandsTheMinecraftInstanceOverBeforeTheEntrypointsRun() throws Exception {
		ClassNode hooks = compiled("main", "net/fabricmc/loader/impl/game/minecraft/Hooks");
		MethodNode start = hooks.methods.stream().filter(m -> m.name.equals("startClient")).findFirst().orElseThrow();
		List<String> calls = calls(start);
		int set = calls.indexOf("net/forbric/kernel/boot/KernelFabricEcosystem.setGameInstance"),
				entrypoints = calls.indexOf("net/forbric/kernel/boot/KernelLifecycle.onClientEntrypoints");
		assertTrue(set >= 0 && entrypoints > set, "the instance first, then the entrypoints: " + calls);
	}

	/** The call the server entry makes after the server's constructor (LifecycleHookInjector) publishes it. */
	@Test void setGameInstancePublishesWhatTheServerEntryHandsIt() throws Exception {
		ClassNode hooks = compiled("main", "net/fabricmc/loader/impl/game/minecraft/Hooks");
		MethodNode set = hooks.methods.stream().filter(m -> m.name.equals("setGameInstance") && m.desc.equals("(Ljava/lang/Object;)V"))
				.findFirst().orElseThrow();
		assertTrue(calls(set).contains("net/forbric/kernel/boot/KernelFabricEcosystem.setGameInstance"), calls(set).toString());
	}

	@Test void aDedicatedServerBecomesTheGameInstanceAsItStarts() throws Exception {
		ClassNode duties = compiled("runtime", "net/forbric/kernel/runtime/KernelServerDuties");
		boolean sets = false;
		for (MethodNode m : duties.methods) {
			if (!m.name.startsWith("lambda$installGameInstance$")) continue;
			List<String> calls = calls(m);
			sets |= calls.contains("net/minecraft/server/MinecraftServer.isDedicatedServer")
					&& calls.contains("net/forbric/kernel/boot/KernelFabricEcosystem.setGameInstance");
		}
		assertTrue(sets, "the about-to-start listener sets a dedicated server as the game instance");
		// Installed by the kernel's own duties, which MinecraftForge's absence and -Dforbric.unifiedEvents=off do not reach —
		// not by the bridge pass, whose ForgeEventFactory probe took it away wherever there was no MinecraftForge.
		String installer = Files.readString(Path.of(System.getProperty("user.dir"), "src", "main", "java", "net", "forbric", "kernel", "boot",
				"KernelServerDutiesInstaller.java"));
		assertTrue(installer.contains("getMethod(\"installGameInstance\", Object.class)"), "and the kernel's duties install it");
		String multiplexer = Files.readString(Path.of(System.getProperty("user.dir"), "src", "main", "java", "net", "forbric", "kernel", "boot",
				"GameEventMultiplexer.java"));
		assertFalse(multiplexer.contains("installGameInstance"), "and the bridge pass does not");
	}
}
