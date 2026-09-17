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
