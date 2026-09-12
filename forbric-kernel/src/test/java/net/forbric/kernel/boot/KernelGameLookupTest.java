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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;

import net.forbric.kernel.classloading.DelegationPolicy;
import net.forbric.kernel.classloading.ForbricClassLoader;

/**
 * Pins the one thing {@link KernelGameLookup} exists for: the helper must yield a FULL-POWER lookup.
 *
 * <p>Forge's EventBus spins its listener lambdas with {@code LambdaMetafactory}, which rejects any caller whose
 * lookup lacks the MODULE bit — {@code LambdaConversionException: Invalid caller}. A boot-side
 * {@code MethodHandles.lookup()} teleported across the boot/game boundary loses that bit, so the kernel asks a
 * helper class that lives on the GAME side for a lookup instead.
 *
 * <p>The failure mode if that regressed is not a compile error and not an obvious crash: it is every Forge-family
 * mod's event listeners failing to bind, which surfaces as mods that load and then do nothing. So this test does
 * not inspect bytecode — it drives a real {@code LambdaMetafactory} call through the lookup, which is the exact
 * operation that was failing.
 *
 * <h2>Why the helper is compiled here instead of being read off the classpath</h2>
 *
 * <p>{@code KernelGameLookupHelper} lives in the {@code runtime} source set, which links against the staged game
 * jars and is therefore not built at all on a machine that has none. Reading it out of
 * {@code forbric-kernel-runtime.jar} would make this test — and {@code gradlew test} with it — red on a fresh
 * clone, which is a property the repository deliberately keeps.
 *
 * <p>So the test compiles {@code src/runtime/java/.../KernelGameLookupHelper.java} itself, with the system
 * compiler, into a temporary jar. That file happens to name no game type (it is the ONLY game-side class that
 * does not), so it compiles against the JDK alone. The subject is therefore the real source, delivered the real
 * way — inside a jar the loader owns — and driven through the real production entry point
 * {@link KernelGameLookup#get}. Nothing here is a stand-in except the jar's filename.
 */
class KernelGameLookupTest {
	private static final String HELPER = "net.forbric.kernel.runtime.KernelGameLookupHelper";
	private static final Path SOURCE =
			Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelGameLookupHelper.java");

	/**
	 * One loader for the whole class. {@link KernelGameLookup} memoises its lookup process-wide — correct in
	 * production, where there is exactly one game loader for the life of the JVM — so a second loader here would
	 * be silently ignored and the test would be asserting about the first one anyway. Better to have one.
	 */
	private static ForbricClassLoader shared;

	private static synchronized ForbricClassLoader gameLoader() throws Exception {
		if (shared == null) shared = new ForbricClassLoader(new URL[] {helperJar()}, KernelGameLookupTest.class
				.getClassLoader());
		return shared;
	}

	@Test
	void theRealSourceIsWhatGetsCompiled() {
		assertTrue(Files.isRegularFile(SOURCE),
				SOURCE + " is gone. The kernel loads this class by name at runtime, so its absence is not a "
						+ "compile error anywhere — it is a ClassNotFoundException inside mod construction");
	}

	@Test
	void theHelperYieldsALookupWithTheModuleBit() throws Exception {
		MethodHandles.Lookup lookup = KernelGameLookup.get(gameLoader());

		assertNotNull(lookup, "the helper returned no lookup");
		assertNotEquals(0, lookup.lookupModes() & MethodHandles.Lookup.MODULE,
				"the lookup has no MODULE bit — LambdaMetafactory will reject every caller using it, and Forge's "
						+ "EventBus silently binds no listeners at all");
	}

	/** The lookup must belong to the GAME loader, or it is minting one for the wrong module. */
	@Test
	void theLookupClassWasDefinedByTheGameLoader() throws Exception {
		MethodHandles.Lookup lookup = KernelGameLookup.get(gameLoader());

		assertSame(gameLoader(), lookup.lookupClass().getClassLoader(),
				"the helper was defined somewhere other than the game loader, so its lookup describes a module "
						+ "the game classes are not in");
	}

	/** The operation that was actually failing: metafactory over that lookup must produce a working call site. */
	@Test
	void lambdaMetafactoryAcceptsThatLookupAsACaller() throws Throwable {
		MethodHandles.Lookup lookup = KernelGameLookup.get(gameLoader());

		MethodHandle target = lookup.findStatic(lookup.lookupClass(), "lookup",
				MethodType.methodType(MethodHandles.Lookup.class));
		@SuppressWarnings("unchecked")
		Supplier<Object> made = (Supplier<Object>) LambdaMetafactory.metafactory(
				lookup, "get",
				MethodType.methodType(Supplier.class),
				MethodType.methodType(Object.class),
				target,
				MethodType.methodType(MethodHandles.Lookup.class)).getTarget().invoke();

		assertNotNull(made.get(), "the metafactory-built lambda produced nothing");
	}

	/** The helper must land on the GAME side, or it is minting a lookup for the wrong module. */
	@Test
	void theHelperNameIsPinnedGameSide() {
		assertTrue(DelegationPolicy.alwaysGame(HELPER),
				HELPER + " is not pinned game-side; a helper defined by the boot loader would hand back a lookup "
						+ "for the boot module, which is the thing this class exists to avoid");
	}

	/** It ships inside the game-side jar, not on the boot classpath — nothing should find it the ordinary way. */
	@Test
	void theHelperIsNotOnTheOrdinaryClasspath() {
		assertEquals(false, canLoad(HELPER),
				"the helper is delivered in forbric-kernel-runtime.jar and defined by the transforming loader; "
						+ "reachable from the boot classpath it would be a second, boot-module copy");
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	/** Compiles the real game-side source into a jar, the way the build does, minus the staged-jar classpath. */
	private static URL helperJar() throws Exception {
		JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
		assertNotNull(javac, "no system Java compiler — this test needs a JDK, not a JRE");

		Path out = Files.createTempDirectory("forbric-game-side");
		int rc = javac.run(null, null, null, "-d", out.toString(), "--release", "21", SOURCE.toString());
		assertEquals(0, rc, "the kernel's game-side source did not compile: " + SOURCE);

		Path jar = out.resolve("forbric-kernel-runtime.jar");
		try (OutputStream os = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(os);
				Stream<Path> walk = Files.walk(out)) {
			List<Path> classes = walk.filter(p -> p.toString().endsWith(".class")).sorted().toList();
			assertTrue(!classes.isEmpty(), "compiling " + SOURCE + " produced no class files");
			for (Path c : classes) {
				zip.putNextEntry(new ZipEntry(out.relativize(c).toString().replace(java.io.File.separatorChar, '/')));
				zip.write(Files.readAllBytes(c));
				zip.closeEntry();
			}
		}
		return jar.toUri().toURL();
	}

	private static boolean canLoad(String name) {
		try {
			Class.forName(name, false, KernelGameLookupTest.class.getClassLoader());
			return true;
		} catch (ClassNotFoundException expected) {
			return false;
		}
	}
}
