/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.fabric;

import java.nio.file.Path;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;

import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.classloading.LoaderProbePolicy;
import net.forbric.kernel.util.ForbricLog;

/**
 * The kernel's answer to {@code FabricLauncherBase.getLauncher()} — what a mod reaches for when it needs to put a
 * jar it just unpacked onto the running classpath.
 *
 * <p>Under Fabric that call lands on Knot. There is no Knot here, so it lands on the sovereign loader instead:
 * the jar becomes an owned URL and its classes go through the full pipeline (access tweakers, the compat chain,
 * then Mixin), which is what the mod expects and what Fabric's own {@code addToClassPath} provides.
 *
 * <p>{@code allowedPrefixes} is accepted and ignored: it exists so Knot can keep a jar's packages from shadowing
 * another's, and the kernel has one flat loader with no per-jar package filter to express that against.
 *
 * <p>The jar is recorded as Fabric's for environment stripping: Knot strips what it loads from it like any other
 * Fabric class, and a client-only member left in it on a server fails the class the way CreativeCore's did.
 * {@code -Dforbric.envStrip.runtimeJars=off} leaves such a jar unstripped, as before.
 */
public final class KernelFabricLauncher implements FabricLauncher {
	/** {@code -Dforbric.envStrip.runtimeJars=off}: a jar added here is not recorded as Fabric's for the strip. */
	public static final String RUNTIME_JARS_SWITCH = "forbric.envStrip.runtimeJars";
	/** {@code -Dforbric.runtimeJarOwners=off}: the configs a jar added here declares are not claimed for its mod. */
	public static final String RUNTIME_OWNERS_SWITCH = "forbric.runtimeJarOwners";

	private final ForbricClassLoader loader;
	private final EnvType envType;

	private KernelFabricLauncher(ForbricClassLoader loader, EnvType envType) {
		this.loader = loader;
		this.envType = envType;
	}

	/** Builds the launcher view and publishes it. Call once, before any mod class loads. */
	public static void install(ForbricClassLoader loader, EnvType envType) {
		FabricLauncherBase.setLauncher(new KernelFabricLauncher(loader, envType));
	}

	@Override
	public void addToClassPath(Path path, String... allowedPrefixes) {
		try {
			loader.addURL(path.toUri().toURL());
			if (!"off".equalsIgnoreCase(System.getProperty(RUNTIME_JARS_SWITCH, "on").trim())) {
				loader.addRuntimeJarFamily(path, LoaderProbePolicy.Family.FABRIC);
			}
			ForbricLog.info("[Forbric/Fabric] a mod added %s to the classpath at runtime", path.getFileName());
		} catch (Throwable t) {
			throw new RuntimeException("could not add " + path + " to the classpath", t);
		}
		claimDeclaredConfigs(path, envType);
	}

	/**
	 * The mixin configs a jar added here declares in its {@code fabric.mod.json} belong to that mod, in the Fabric
	 * ecosystem, from the moment the jar is on the classpath. A mod that registers such a jar as a mod itself hands its
	 * configs to Mixin before it builds the mod's container (Essential's loader does, for the 21 jars it unpacks), and
	 * Mixin reads a config the moment it is added: judged then with no owner, a guest mixin had no ecosystem, so nothing
	 * that reads the native game of its own platform could answer for it — fabric-networking's two {@code @WrapOperation}s
	 * of {@code CustomPacketPayload.codec}, which the full fabric-api has moved onto NeoForge's widened call, stayed
	 * unattached. A jar without a {@code fabric.mod.json}, or a config already claimed, is left as it is.
	 * {@code -Dforbric.runtimeJarOwners=off} claims nothing here.
	 */
	static void claimDeclaredConfigs(Path path, EnvType envType) {
		if ("off".equalsIgnoreCase(System.getProperty(RUNTIME_OWNERS_SWITCH, "on").trim())) return;
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(path.toFile())) {
			java.util.zip.ZipEntry entry = zip.getEntry("fabric.mod.json");
			if (entry == null) return;
			KernelModMetadata metadata;
			try (java.io.InputStream in = zip.getInputStream(entry)) {
				metadata = FabricModMetadataParser.read(in);
			}
			if (metadata == null || metadata.getId() == null) return;
			for (String config : metadata.getMixinConfigs(envType)) {
				net.forbric.kernel.mixin.MixinConfigOwners.claimLate(config, metadata.getId(), net.forbric.api.Ecosystem.FABRIC);
			}
		} catch (Throwable unreadable) {
			// Not a jar, or no readable fabric.mod.json: it brings no configs to claim.
		}
	}

	@Override
	public ClassLoader getTargetClassLoader() {
		return loader;
	}

	@Override
	public EnvType getEnvironmentType() {
		return envType;
	}

	@Override
	public boolean isDevelopment() {
		return false;
	}
}
