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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * The runner's own red control: a {@code *Test} class this runner cannot run (no {@code main}, or one that is not public,
 * static and void) is reported and fails the run, never passed over; with only runnable tests the run passes. Compiles
 * three throwaway classes into its scratch directory and runs {@link InstallerTests} over them alone.
 */
public final class InstallerTestsRunnerTest {
	private InstallerTestsRunnerTest() {
	}

	public static void main(String[] args) throws Exception {
		Path work = Files.createDirectories(Path.of(args[0])).toRealPath();
		Path src = work.resolve("src"), classes = Files.createDirectories(work.resolve("classes"));
		write(src, "GoodTest", "public static void main(String[] args) { }");
		write(src, "JunitStyleTest", "void works() { }");
		write(src, "PackageMainTest", "static void main(String[] args) { }");
		JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
		require(javac != null, "no system Java compiler: this test needs a JDK");
		try (var files = Files.list(src.resolve("probe"))) {
			List<String> command = new java.util.ArrayList<>(List.of("--release", "17", "-d", classes.toString()));
			files.forEach(f -> command.add(f.toString()));
			require(javac.run(null, null, null, command.toArray(String[]::new)) == 0, "the probe classes did not compile");
		}

		String[] out = new String[1];
		int code = run(work.resolve("all"), classes, out);
		require(code == 1, "a run with *Test classes that have no runnable main passed (exit " + code + "):\n" + out[0]);
		require(out[0].contains("[installer-test] NO MAIN JunitStyleTest:"), "the JUnit-style test was passed over:\n" + out[0]);
		require(out[0].contains("[installer-test] NO MAIN PackageMainTest:"), "a non-public main was passed over:\n" + out[0]);
		require(out[0].contains("[installer-test] PASS GoodTest"), "the runnable test did not run beside them:\n" + out[0]);
		require(out[0].contains("1 test classes ran, 0 failed; 2 without a runnable main"), "the summary hides them:\n" + out[0]);

		Files.delete(classes.resolve("probe/JunitStyleTest.class"));
		Files.delete(classes.resolve("probe/PackageMainTest.class"));
		code = run(work.resolve("good"), classes, out);
		require(code == 0, "a run of only runnable tests failed (exit " + code + "):\n" + out[0]);
		require(out[0].contains("1 test classes ran, 0 failed") && !out[0].contains("NO MAIN"), out[0]);
		System.out.println("PASS installer test runner: a *Test without a runnable main fails the run; runnable ones pass");
	}

	private static void write(Path src, String name, String body) throws Exception {
		Path file = src.resolve("probe/" + name + ".java");
		Files.createDirectories(file.getParent());
		Files.writeString(file, "package probe; public class " + name + " { " + body + " }");
	}

	private static int run(Path scratch, Path classes, String[] out) throws Exception {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (URLClassLoader loader = new URLClassLoader(new URL[] { classes.toUri().toURL() }, ClassLoader.getPlatformClassLoader());
				PrintStream print = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
			int code = InstallerTests.run(scratch, List.of(classes), loader, print);
			out[0] = buffer.toString(StandardCharsets.UTF_8);
			return code;
		}
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
