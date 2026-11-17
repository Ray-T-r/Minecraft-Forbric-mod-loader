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

import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Runs the installer's tests: every class under the given class directories whose name ends in {@code Test}, by its
 * {@code public static void main(String[])}, in name order, each with a scratch directory of its own as its one argument.
 *
 * <p>Found by name, never listed: the gate this replaced ran a hand-kept list of eight, so a new test that nobody added to
 * it compiled and never ran. For the same reason a {@code *Test} class without a runnable {@code main} (a JUnit-style
 * test, a {@code main} that is not public, static and void) is not passed over: it is reported as {@code NO MAIN} and
 * fails the run, since this runner would never run it. A test failing does not stop the others; the run fails when any
 * did, when one had no runnable main, or when none ran.
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
		List<Path> roots = new ArrayList<>();
		for (int i = 1; i < args.length; i++) roots.add(Path.of(args[i]));
		System.exit(run(Path.of(args[0]), roots, InstallerTests.class.getClassLoader(), System.out));
	}

	/** Runs every {@code *Test} under {@code roots}, loaded through {@code loader}; returns the exit code. */
	static int run(Path scratchRoot, List<Path> roots, ClassLoader loader, PrintStream out) throws Exception {
		Path scratch = Files.createDirectories(scratchRoot);
		List<String> names = new ArrayList<>();
		for (Path root : roots) names.addAll(testClasses(root));
		names.sort(null);
		List<String> failed = new ArrayList<>(), noMain = new ArrayList<>();
		int ran = 0;
		for (String name : names) {
			String simple = name.substring(name.lastIndexOf('.') + 1);
			Method main = mainOf(name, loader);
			if (main == null) {
				noMain.add(simple);
				out.println("[installer-test] NO MAIN " + simple + ": a *Test class without public static void main(String[])"
						+ " never runs here");
				continue;
			}
			ran++;
			out.println("[installer-test] " + simple);
			try {
				main.invoke(null, (Object) new String[] { scratch.resolve(simple).toString() });
				out.println("[installer-test] PASS " + simple);
			} catch (InvocationTargetException thrown) {
				failed.add(simple);
				out.println("[installer-test] FAIL " + simple + ": " + thrown.getCause());
				thrown.getCause().printStackTrace(out);
			}
		}
		out.println("[installer-test] " + ran + " test classes ran, " + failed.size() + " failed" + (failed.isEmpty() ? ""
				: ": " + failed) + (noMain.isEmpty() ? "" : "; " + noMain.size() + " without a runnable main: " + noMain));
		return ran == 0 || !failed.isEmpty() || !noMain.isEmpty() ? 1 : 0;
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
	private static Method mainOf(String name, ClassLoader loader) {
		try {
			Method main = Class.forName(name, false, loader).getMethod("main", String[].class);
			return Modifier.isStatic(main.getModifiers()) && main.getReturnType() == void.class ? main : null;
		} catch (ReflectiveOperationException none) {
			return null;
		}
	}
}
