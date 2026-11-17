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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Runs the installer's tests: every class under the given class directories whose name ends in {@code Test} and that has
 * a {@code public static void main(String[])}, in name order, each with a scratch directory of its own as its one
 * argument.
 *
 * <p>Found by name, never listed: the gate this replaced ran a hand-kept list of eight, so a new test that nobody added to
 * it compiled and never ran. A test failing does not stop the others; the run fails when any did, or when none ran.
 *
 * <p>Usage: {@code InstallerTests <scratch dir> <test classes dir>...}
 */
public final class InstallerTests {
	private InstallerTests() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length < 2) {
			System.err.println("usage: InstallerTests <scratch dir> <test classes dir>...");
			System.exit(2);
		}
		Path scratch = Files.createDirectories(Path.of(args[0]));
		List<String> names = new ArrayList<>();
		for (int i = 1; i < args.length; i++) names.addAll(testClasses(Path.of(args[i])));
		names.sort(null);
		List<String> failed = new ArrayList<>();
		for (String name : names) {
			Method main = mainOf(name);
			if (main == null) continue;
			String simple = name.substring(name.lastIndexOf('.') + 1);
			System.out.println("[installer-test] " + simple);
			try {
				main.invoke(null, (Object) new String[] { scratch.resolve(simple).toString() });
				System.out.println("[installer-test] PASS " + simple);
			} catch (InvocationTargetException thrown) {
				failed.add(simple);
				System.out.println("[installer-test] FAIL " + simple + ": " + thrown.getCause());
				thrown.getCause().printStackTrace(System.out);
			}
		}
		int ran = (int) names.stream().filter(n -> mainOf(n) != null).count();
		System.out.println("[installer-test] " + ran + " test classes ran, " + failed.size() + " failed" + (failed.isEmpty() ? ""
				: ": " + failed));
		System.exit(ran == 0 || !failed.isEmpty() ? 1 : 0);
	}

	/** Binary names of the {@code *Test} classes under {@code root}, nested classes left out. */
	static List<String> testClasses(Path root) throws Exception {
		if (!Files.isDirectory(root)) return List.of();
		try (Stream<Path> walk = Files.walk(root)) {
			return walk.filter(f -> f.getFileName().toString().endsWith("Test.class"))
					.map(f -> root.relativize(f).toString().replace('\\', '/'))
					.map(f -> f.substring(0, f.length() - ".class".length()).replace('/', '.'))
					.filter(n -> !n.contains("$"))
					.toList();
		}
	}

	/** The class's {@code public static void main(String[])}, or null when it has none. */
	private static Method mainOf(String name) {
		try {
			Method main = Class.forName(name).getMethod("main", String[].class);
			return Modifier.isStatic(main.getModifiers()) && main.getReturnType() == void.class ? main : null;
		} catch (ReflectiveOperationException none) {
			return null;
		}
	}
}
