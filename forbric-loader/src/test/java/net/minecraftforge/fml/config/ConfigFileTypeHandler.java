package net.minecraftforge.fml.config;

import com.electronwill.nightconfig.core.file.FileWatcher;

/** Test stand-in for MinecraftForge's handler: three static handlers, each with a lazily built private watcher. */
public class ConfigFileTypeHandler {
	private static final ConfigFileTypeHandler CLIENT = new ConfigFileTypeHandler();
	private static final ConfigFileTypeHandler COMMON = new ConfigFileTypeHandler();
	private static final ConfigFileTypeHandler SERVER = new ConfigFileTypeHandler();
	private FileWatcher watcher;

	public static ConfigFileTypeHandler client() {
		return CLIENT;
	}

	public static ConfigFileTypeHandler server() {
		return SERVER;
	}

	public FileWatcher getWatcher() {
		if (watcher == null) watcher = new FileWatcher();
		return watcher;
	}

	public FileWatcher peekWatcher() {
		return watcher;
	}

	public static void reset() {
		CLIENT.watcher = null;
		COMMON.watcher = null;
		SERVER.watcher = null;
	}
}
