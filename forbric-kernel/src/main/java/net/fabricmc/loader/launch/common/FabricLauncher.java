/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.fabricmc.loader.launch.common;

import java.io.InputStream;
import java.net.URL;

import net.fabricmc.api.EnvType;

/**
 * Fabric Loader's pre-0.12 launcher interface, which Fabric Loader still ships (deprecated) beside its internal
 * {@link net.fabricmc.loader.impl.launch.FabricLauncher}. Mods written against it call
 * {@code FabricLauncherBase.getLauncher().propose(url)} to put a jar they unpacked at {@code preLaunch} on the
 * classpath; Essential's loader does exactly that, and without the class it fell back to reflecting {@code addURL} on
 * whatever class loader it could find, which skipped what the kernel does for such a jar.
 *
 * <p>As with the internal interface, only the methods mods are observed to call are declared, so anything else
 * fails by name at link time instead of silently doing nothing.
 */
@Deprecated
public interface FabricLauncher {
	/** Adds a jar to the running mod classpath, as {@code addToClassPath} of the internal launcher. */
	void propose(URL url);

	/** Client or dedicated server. */
	EnvType getEnvironmentType();

	/** A resource of the running classpath. */
	InputStream getResourceAsStream(String name);

	/** The loader this instance defines mod and game classes with. */
	ClassLoader getTargetClassLoader();

	/** Whether this is a development launch. */
	boolean isDevelopment();
}
