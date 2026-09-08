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

package net.forbric.installer.kernel;

import java.nio.file.Path;

/**
 * Double-clicked, this opens the window; run with arguments, it installs and exits, which is what the gate does.
 *
 * <pre>
 *   java -jar forbric-kernel-installer.jar                       # window
 *   java -jar forbric-kernel-installer.jar --dir DIR [options]   # install, no window
 *       --mc 26.2            the base version (default 26.2)
 *       --artifacts DIR      where the locally built game jars are
 * </pre>
 */
public final class Main {
	private Main() {
	}

	public static void main(String[] args) {
		if (args.length == 0) {
			InstallerGui.open();
			return;
		}
		Path dir = null;
		Path artifacts = null;
		String mcVersion = InstallerGui.DEFAULT_VERSION;
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--dir" -> dir = Util.path(require(args, ++i, "--dir"));
				case "--artifacts" -> artifacts = Util.path(require(args, ++i, "--artifacts"));
				case "--mc" -> mcVersion = require(args, ++i, "--mc");
				case "--help", "-h" -> {
					usage(System.out);
					return;
				}
				default -> {
					System.err.println("unknown option: " + args[i]);
					usage(System.err);
					System.exit(2);
				}
			}
		}
		if (dir == null) {
			System.err.println("--dir is required when running without a window");
			usage(System.err);
			System.exit(2);
		}
		try {
			new Installer(System.out::println).install(dir, mcVersion, artifacts);
		} catch (Exception e) {
			System.err.println("install failed: " + e.getMessage());
			System.exit(1);
		}
	}

	private static String require(String[] args, int i, String option) {
		if (i >= args.length) {
			System.err.println(option + " needs a value");
			System.exit(2);
		}
		return args[i];
	}

	private static void usage(java.io.PrintStream out) {
		out.println("usage: java -jar forbric-kernel-installer.jar [--dir DIR [--mc 26.2] [--artifacts DIR]]");
		out.println("       with no arguments, opens the installer window");
	}
}
