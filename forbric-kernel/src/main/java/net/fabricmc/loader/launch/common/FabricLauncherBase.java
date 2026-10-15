/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.fabricmc.loader.launch.common;

import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;

import net.fabricmc.api.EnvType;

/**
 * The legacy hand-off to {@link FabricLauncher}, as Fabric Loader keeps it: a view over the internal launcher
 * ({@link net.fabricmc.loader.impl.launch.FabricLauncherBase#getLauncher()}), built fresh on each
 * {@link #getLauncher()} call. Under Fabric the internal launcher is Knot; here it is the kernel's
 * ({@code KernelFabricLauncher}), so a proposed jar becomes an owned URL that goes through the whole pipeline and is
 * environment-stripped as Fabric's.
 */
@Deprecated
public class FabricLauncherBase implements FabricLauncher {
	private final net.fabricmc.loader.impl.launch.FabricLauncher parent;

	public FabricLauncherBase() {
		parent = net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher();
		if (parent == null) throw new IllegalStateException("the launcher is not installed yet");
	}

	/** Loads (and initialises) {@code name} with the target class loader. */
	public static Class<?> getClass(String name) throws ClassNotFoundException {
		return Class.forName(name, true, getLauncher().getTargetClassLoader());
	}

	public static FabricLauncher getLauncher() {
		return new FabricLauncherBase();
	}

	@Override
	public void propose(URL url) {
		Path path;
		try {
			path = Path.of(url.toURI());
		} catch (URISyntaxException | IllegalArgumentException e) {
			throw new RuntimeException("cannot add " + url + " to the classpath: not a file", e);
		}
		parent.addToClassPath(path);
	}

	@Override
	public EnvType getEnvironmentType() {
		return parent.getEnvironmentType();
	}

	@Override
	public InputStream getResourceAsStream(String name) {
		return parent.getTargetClassLoader().getResourceAsStream(name);
	}

	@Override
	public ClassLoader getTargetClassLoader() {
		return parent.getTargetClassLoader();
	}

	@Override
	public boolean isDevelopment() {
		return parent.isDevelopment();
	}
}
