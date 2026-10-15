/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.fabric;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.mixin.MixinConfigOwners;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * A mod that unpacks jars at {@code preLaunch} and registers them as mods (Essential's loader) hands each jar's mixin
 * configs to Mixin before it builds the jar's mod container, and Mixin reads a config the moment it is added. The
 * configs a jar declares are its mod's, in the Fabric ecosystem, from the moment the jar joins the classpath, so the
 * config is judged with an owner: before, fabric-networking's config was judged ownerless and its two
 * {@code @WrapOperation}s of {@code CustomPacketPayload.codec} stayed unattached.
 */
@ResourceLock("system-properties")
class RuntimeJarConfigOwnersTest {
	@TempDir Path dir;

	@BeforeEach @AfterEach void clean() {
		MixinConfigOwners.publish(java.util.List.of());
		System.clearProperty(KernelFabricLauncher.RUNTIME_OWNERS_SWITCH);
	}

	@Test void theConfigsARuntimeJarDeclaresAreItsModsBeforeAnyoneAddsThem() throws Exception {
		Path jar = jar("unpacked.jar", "{\"schemaVersion\":1,\"id\":\"unpacked\",\"version\":\"1\","
				+ "\"mixins\":[\"unpacked.mixins.json\",{\"config\":\"unpacked.client.mixins.json\",\"environment\":\"client\"}]}");
		added(jar, EnvType.SERVER);
		assertEquals("unpacked", MixinConfigOwners.modIdOf("unpacked.mixins.json"));
		assertEquals(Ecosystem.FABRIC, MixinConfigOwners.ecosystemOf("unpacked.mixins.json"));
		assertNull(MixinConfigOwners.ecosystemOf("unpacked.client.mixins.json"), "a config of the other side is not its on this one");
	}

	@Test void aJarWithoutModMetadataOrAnOwnedConfigIsLeftAsItIs() throws Exception {
		MixinConfigOwners.publish(java.util.List.of(new MixinConfigOwners.Owned("shared.mixins.json", "first", Ecosystem.NEOFORGE)));
		added(jar("library.jar", null), EnvType.SERVER);
		added(jar("second.jar", "{\"schemaVersion\":1,\"id\":\"second\",\"version\":\"1\",\"mixins\":[\"shared.mixins.json\"]}"), EnvType.SERVER);
		assertEquals("first", MixinConfigOwners.modIdOf("shared.mixins.json"), "an owner at boot keeps its config");
		assertEquals(Ecosystem.NEOFORGE, MixinConfigOwners.ecosystemOf("shared.mixins.json"));
	}

	/** The control: switched off, the runtime jar's config stays ownerless until its container claims it. */
	@Test void switchedOffTheConfigStaysOwnerless() throws Exception {
		System.setProperty(KernelFabricLauncher.RUNTIME_OWNERS_SWITCH, "off");
		added(jar("unpacked.jar", "{\"schemaVersion\":1,\"id\":\"unpacked\",\"version\":\"1\",\"mixins\":[\"unpacked.mixins.json\"]}"), EnvType.SERVER);
		assertNull(MixinConfigOwners.modIdOf("unpacked.mixins.json"));
		assertNull(MixinConfigOwners.ecosystemOf("unpacked.mixins.json"));
	}

	private void added(Path jar, EnvType side) throws Exception {
		FabricLauncher before = FabricLauncherBase.getLauncher();
		try (ForbricClassLoader loader = new ForbricClassLoader(new URL[0], getClass().getClassLoader())) {
			KernelFabricLauncher.install(loader, side);
			FabricLauncherBase.getLauncher().addToClassPath(jar);
		} finally {
			FabricLauncherBase.setLauncher(before);
		}
	}

	private Path jar(String name, String fabricModJson) throws Exception {
		Path jar = dir.resolve(name);
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
			out.putNextEntry(new JarEntry(fabricModJson == null ? "lib/Thing.txt" : "fabric.mod.json"));
			out.write((fabricModJson == null ? "x" : fabricModJson).getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
		}
		return jar;
	}
}
