/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.fabric;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.launch.common.FabricLauncher;
import net.fabricmc.loader.launch.common.FabricLauncherBase;
import net.forbric.kernel.classloading.DelegationPolicy;
import net.forbric.kernel.classloading.ForbricClassLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fabric Loader still ships its pre-0.12 {@code net.fabricmc.loader.launch.common.FabricLauncherBase}, and a mod that
 * unpacks a jar at {@code preLaunch} proposes it there: Essential's loader logged a {@code NoClassDefFoundError} for
 * it on every boot and fell back to reflecting {@code addURL}. Through the legacy view the jar now lands on the
 * kernel's launcher like one added through the internal API.
 */
class LegacyFabricLauncherTest {
	@Test void aProposedJarJoinsTheRunningClasspathThroughTheKernelsLauncher(@TempDir Path dir) throws Exception {
		Path jar = dir.resolve("unpacked-at-prelaunch.jar");
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
			out.putNextEntry(new JarEntry("legacy/hello.txt"));
			out.write("proposed".getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
		}
		net.fabricmc.loader.impl.launch.FabricLauncher before = net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher();
		try (ForbricClassLoader loader = new ForbricClassLoader(new URL[0], getClass().getClassLoader())) {
			KernelFabricLauncher.install(loader, EnvType.SERVER);
			FabricLauncher legacy = FabricLauncherBase.getLauncher();
			assertNull(loader.findResource("legacy/hello.txt"), "not there before");
			legacy.propose(jar.toUri().toURL());
			assertNotNull(loader.findResource("legacy/hello.txt"), "the proposed jar is an owned URL of the kernel's loader");
			try (InputStream in = legacy.getResourceAsStream("legacy/hello.txt")) {
				assertEquals("proposed", new String(in.readAllBytes(), StandardCharsets.UTF_8));
			}
			assertSame(loader, legacy.getTargetClassLoader());
			assertEquals(EnvType.SERVER, legacy.getEnvironmentType());
			assertFalse(legacy.isDevelopment());
		} finally {
			net.fabricmc.loader.impl.launch.FabricLauncherBase.setLauncher(before);
		}
		assertTrue(DelegationPolicy.alwaysParent(FabricLauncherBase.class.getName()), "one copy, the kernel's");
	}
}
