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

package net.forbric.kernel.classloading;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/**
 * What Mixin is shown when it asks for a class's bytes.
 *
 * <p>Every ask re-read the jar and re-ran the WHOLE transform chain, and Mixin asks repeatedly for the same
 * classes: each anchor it resolves walks its target's superclass chain, and the game's own types sit under
 * nearly everything.
 *
 * <p>The correctness that matters more than the saving: whatever is remembered must be the TRANSFORMED bytes.
 * Handing Mixin the untransformed class would have it weave against something the loader never defines.
 */
class ForbricClassLoaderPreMixinCacheTest {

	private static byte[] classBytes(String internalName) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static Path jarWith(Path dir, String internalName) throws Exception {
		Path jar = dir.resolve("owned.jar");
		try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(out)) {
			jos.putNextEntry(new ZipEntry(internalName + ".class"));
			jos.write(classBytes(internalName));
			jos.closeEntry();
		}
		return jar;
	}

	@Test
	void theChainRunsOncePerClass(@TempDir Path dir) throws Exception {
		Path jar = jarWith(dir, "com/example/Target");
		AtomicInteger ran = new AtomicInteger();

		try (ForbricClassLoader loader = new ForbricClassLoader(
				new URL[] {jar.toUri().toURL()}, getClass().getClassLoader())) {
			loader.setTransformer((name, bytes) -> {
				ran.incrementAndGet();
				return bytes;
			});

			assertNotNull(loader.getPreMixinClassBytes("com.example.Target"));
			loader.getPreMixinClassBytes("com.example.Target");
			loader.getPreMixinClassBytes("com.example.Target");

			assertEquals(1, ran.get(), "the transform chain re-ran for a class it had already produced");
		}
	}

	@Test
	void whatIsRememberedIsTheTransformedClass(@TempDir Path dir) throws Exception {
		// The hazard worth more than the saving: Mixin weaving against bytes the loader never defines.
		Path jar = jarWith(dir, "com/example/Target");
		byte[] rewritten = classBytes("com/example/Rewritten");

		try (ForbricClassLoader loader = new ForbricClassLoader(
				new URL[] {jar.toUri().toURL()}, getClass().getClassLoader())) {
			// Name-sensitive, as every real transformer is: it compares BINARY names and declines anything else.
			loader.setTransformer((name, bytes) -> "com.example.Target".equals(name) ? rewritten : bytes);

			assertArrayEquals(rewritten, loader.getPreMixinClassBytes("com.example.Target"));
			// The INTERNAL name too: fabric-item-api's tooltip-order scrape asks the bytecode provider with
			// Type.getInternalName(ItemStack.class), and got untransformed bytes (every dotted-name transformer
			// declined the slashed name) — "Found no component types" on a base that had 34 restored.
			assertArrayEquals(rewritten, loader.getPreMixinClassBytes("com/example/Target"),
					"a slashed name must reach the same transformed bytes as the dotted one");
			assertArrayEquals(rewritten, loader.getPreMixinClassBytes("com.example.Target"),
					"the remembered answer has to be the transformed one, not the raw class");
		}
	}

	@Test
	void installingTheChainForgetsWhatWasAnsweredWithoutIt(@TempDir Path dir) throws Exception {
		// Anything answered before the chain existed was answered UNTRANSFORMED. Keeping it would hand that out
		// for the rest of the run.
		Path jar = jarWith(dir, "com/example/Target");
		byte[] rewritten = classBytes("com/example/Rewritten");

		try (ForbricClassLoader loader = new ForbricClassLoader(
				new URL[] {jar.toUri().toURL()}, getClass().getClassLoader())) {
			byte[] beforeChain = loader.getPreMixinClassBytes("com.example.Target");
			assertNotNull(beforeChain);

			loader.setTransformer((name, bytes) -> rewritten);

			assertArrayEquals(rewritten, loader.getPreMixinClassBytes("com.example.Target"));
		}
	}

	@Test
	void installingASecondChainForgetsWhatTheFirstProduced(@TempDir Path dir) throws Exception {
		// The case the clear in setTransformer exists for. With one install it is redundant — nothing is
		// remembered before a chain exists — so this is the only place it is load-bearing, and a second install
		// handing out the FIRST chain's output for the rest of the run is the failure it prevents.
		Path jar = jarWith(dir, "com/example/Target");
		byte[] first = classBytes("com/example/First");
		byte[] second = classBytes("com/example/Second");

		try (ForbricClassLoader loader = new ForbricClassLoader(
				new URL[] {jar.toUri().toURL()}, getClass().getClassLoader())) {
			loader.setTransformer((name, bytes) -> first);
			assertArrayEquals(first, loader.getPreMixinClassBytes("com.example.Target"));

			loader.setTransformer((name, bytes) -> second);

			assertArrayEquals(second, loader.getPreMixinClassBytes("com.example.Target"));
		}
	}

	@Test
	void generatingAClassForgetsWhatWasAnsweredForThatName(@TempDir Path dir) throws Exception {
		Path jar = jarWith(dir, "com/example/Target");

		try (ForbricClassLoader loader = new ForbricClassLoader(
				new URL[] {jar.toUri().toURL()}, getClass().getClassLoader())) {
			AtomicInteger ran = new AtomicInteger();
			loader.setTransformer((name, bytes) -> {
				ran.incrementAndGet();
				return bytes;
			});

			loader.getPreMixinClassBytes("com.example.Target");
			loader.putGeneratedClass("com/example/Target", classBytes("com/example/Target"));
			loader.getPreMixinClassBytes("com.example.Target");

			assertEquals(2, ran.get(),
					"a name whose class was regenerated must not keep answering with the old bytes");
		}
	}
}
