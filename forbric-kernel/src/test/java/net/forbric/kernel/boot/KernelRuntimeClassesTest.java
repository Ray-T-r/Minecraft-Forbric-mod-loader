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

package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import net.forbric.kernel.classloading.DelegationPolicy;
import net.forbric.kernel.classloading.ForbricClassLoader;

/**
 * The registry of game-side kernel classes, and the boot-time check that they are actually there.
 *
 * <p>These names are the kernel's only way to refer to its own game-side half, and on the boot side they are
 * STRINGS — an argument to {@code Class.forName}, an ASM internal name. Nothing in the compiler relates a string
 * to the file that satisfies it, so the scanning tests below are the relation: they hold {@code src/main/java}
 * and {@code src/runtime/java} to the registry in both directions. This is the same reason {@code ForeignType}
 * has a scanning test and {@code Side} does not — a vocabulary made of strings needs one, a vocabulary made of
 * types does not.
 */
class KernelRuntimeClassesTest {
	private static final Path MAIN = Path.of("src/main/java");
	private static final Path RUNTIME_SRC = Path.of("src/runtime/java/net/forbric/kernel/runtime");

	/** {@code net.forbric.kernel.runtime.Foo} or {@code net/forbric/kernel/runtime/Foo}, however it is spelled. */
	private static final Pattern NAMED = Pattern.compile(
			"net[./]forbric[./]kernel[./]runtime[./]([A-Z][A-Za-z0-9_$]*)");

	private static List<Path> javaFiles(Path root) throws Exception {
		try (Stream<Path> walk = Files.walk(root)) {
			return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
	}

	@Test
	void everyGameSideClassTheBootSideNamesIsInTheRegistry() throws Exception {
		TreeSet<String> spelled = new TreeSet<>();
		List<String> where = new ArrayList<>();

		for (Path file : javaFiles(MAIN)) {
			// The registry itself and its own documentation name all of them by definition.
			if (file.endsWith("KernelRuntimeClasses.java")) continue;
			String text = Files.readString(file, StandardCharsets.UTF_8);
			Matcher m = NAMED.matcher(text);
			while (m.find()) {
				String binary = "net.forbric.kernel.runtime." + m.group(1);
				if (spelled.add(binary)) where.add(binary + " (" + MAIN.relativize(file) + ")");
			}
		}

		Map<String, KernelRuntimeClasses.Origin> registry = KernelRuntimeClasses.all();
		List<String> unregistered = where.stream()
				.filter(w -> !registry.containsKey(w.substring(0, w.indexOf(' '))))
				.toList();

		assertTrue(unregistered.isEmpty(),
				"boot-side code names game-side classes that KernelRuntimeClasses does not list, so nothing "
						+ "checks they are delivered: " + unregistered);
	}

	@Test
	void theRegistryNamesNothingTheBootSideHasStoppedUsing() throws Exception {
		TreeSet<String> spelled = new TreeSet<>();
		for (Path file : javaFiles(MAIN)) {
			if (file.endsWith("KernelRuntimeClasses.java")) continue;
			Matcher m = NAMED.matcher(Files.readString(file, StandardCharsets.UTF_8));
			while (m.find()) spelled.add("net.forbric.kernel.runtime." + m.group(1));
		}

		List<String> stale = KernelRuntimeClasses.all().keySet().stream().filter(n -> !spelled.contains(n)).toList();

		assertTrue(stale.isEmpty(), "the registry still lists game-side classes no boot-side code names — a "
				+ "registry nobody is forced to update goes stale silently, which is the defect it exists to "
				+ "prevent, one level up: " + stale);
	}

	@Test
	void compiledMeansThereIsSourceAndGeneratedMeansThereIsNot() {
		List<String> wrong = new ArrayList<>();

		KernelRuntimeClasses.all().forEach((binary, origin) -> {
			String simple = binary.substring(binary.lastIndexOf('.') + 1);
			boolean hasSource = Files.isRegularFile(RUNTIME_SRC.resolve(simple + ".java"));

			if (origin == KernelRuntimeClasses.Origin.COMPILED && !hasSource) {
				wrong.add(binary + " is COMPILED but has no file in " + RUNTIME_SRC);
			} else if (origin == KernelRuntimeClasses.Origin.GENERATED && hasSource) {
				wrong.add(binary + " is GENERATED but a source file exists — it is compiled now, so say so, or "
						+ "the boot-time check will skip a class that is genuinely delivered");
			}
		});

		assertTrue(wrong.isEmpty(), String.join("; ", wrong));
	}

	@Test
	void everyRegisteredClassIsPinnedToTheGameSide() {
		for (String binary : KernelRuntimeClasses.all().keySet()) {
			assertTrue(DelegationPolicy.alwaysGame(binary),
					binary + " is not pinned ALWAYS_GAME. The whole point of the game side is that it may name "
							+ "game types, which is only true of classes the transforming loader defines; "
							+ "parent-loaded it would link against nothing and silently be a second copy");
			assertFalse(DelegationPolicy.alwaysParent(binary), binary + " is claimed by BOTH delegation lists");
		}
	}

	// --- the boot-time check ------------------------------------------------------------------------------

	private static byte[] emptyClass(String internalName) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, internalName, null, "java/lang/Object", null);
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static URL jarWith(Path jar, List<String> binaryNames) throws Exception {
		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
			for (String binary : binaryNames) {
				String internal = binary.replace('.', '/');
				zip.putNextEntry(new ZipEntry(internal + ".class"));
				zip.write(emptyClass(internal));
				zip.closeEntry();
			}
		}
		return jar.toUri().toURL();
	}

	@Test
	void verifyPassesWhenTheGameSideJarIsOwned(@TempDir Path dir) throws Exception {
		URL runtimeJar = jarWith(dir.resolve("forbric-kernel-runtime.jar"), KernelRuntimeClasses.compiled());

		try (ForbricClassLoader loader =
				new ForbricClassLoader(new URL[] {runtimeJar}, getClass().getClassLoader())) {
			assertTrue(KernelRuntimeClasses.verify(loader));
		}
	}

	@Test
	void verifyFailsWhenTheBootJarWasBuiltWithoutStagedArtifacts(@TempDir Path dir) throws Exception {
		// The jar is simply not there — exactly what a boot jar built with no staged artifacts produces.
		URL somethingElse = jarWith(dir.resolve("unrelated.jar"), List.of("forbrictest.Unrelated"));

		try (ForbricClassLoader loader =
				new ForbricClassLoader(new URL[] {somethingElse}, getClass().getClassLoader())) {
			assertFalse(KernelRuntimeClasses.verify(loader),
					"a kernel whose own game-side classes are absent must say so; every one of them fails to "
							+ "link later, at the point of use, naming a class instead of the build");
		}
	}

	@Test
	void verifyFailsWhenAGameSideClassIsPresentButWillNotDefine(@TempDir Path dir) throws Exception {
		String victim = KernelRuntimeClasses.compiled().get(0);
		Path jar = dir.resolve("forbric-kernel-runtime.jar");

		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry(victim.replace('.', '/') + ".class"));
			zip.write("not a class file".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}

		try (ForbricClassLoader loader =
				new ForbricClassLoader(new URL[] {jar.toUri().toURL()}, getClass().getClassLoader())) {
			assertFalse(KernelRuntimeClasses.verify(loader),
					"present-but-broken is a different failure from absent, and must not read as success");
		}
	}

	@Test
	void theRegistryIsNotEmpty() {
		assertEquals(KernelRuntimeClasses.all().size(),
				KernelRuntimeClasses.all().keySet().stream().distinct().count());
		assertTrue(KernelRuntimeClasses.compiled().size() >= 1,
				"with nothing COMPILED the boot-time check verifies nothing and would pass on a kernel whose "
						+ "game-side jar was never built");
	}
}
