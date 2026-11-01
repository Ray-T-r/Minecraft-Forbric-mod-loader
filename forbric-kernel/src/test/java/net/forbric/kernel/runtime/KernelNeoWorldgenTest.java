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

package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.SourceInterpreter;

/**
 * The data-map watch's registrations over a recording bus: three LOWEST-priority listeners through the four-argument
 * overload. (The kernel's own data-map fallback, whose shape this also pinned, went in Stage 4.)
 */
class KernelNeoWorldgenTest {
	private static final Path RUNTIME = Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"));
	private static final Path COMPILED = RUNTIME.resolve("net/forbric/kernel/runtime/KernelNeoWorldgen.class");

	@Test
	void theWatchRegistersThreeLowestPriorityListenersThroughTheFourArgumentOverload() throws Exception {
		try (URLClassLoader cl = gameSideLoader()) {
			Class<?> bus = Class.forName("net.neoforged.bus.api.IEventBus", false, cl);
			Class<?> priority = Class.forName("net.neoforged.bus.api.EventPriority", true, cl);
			List<String> calls = new ArrayList<>();
			Object recorder = Proxy.newProxyInstance(cl, new Class<?>[] { bus }, (proxy, method, args) -> {
				if ("addListener".equals(method.getName())) {
					StringBuilder sb = new StringBuilder(method.getParameterCount() + ":");
					for (Object a : args) sb.append(a instanceof Class<?> c ? c.getName() : a instanceof Enum<?> e ? e.name() : a == null ? "null" : a.getClass().isArray() ? "[]" : a instanceof Boolean ? a.toString() : "consumer").append('|');
					calls.add(sb.toString());
					return null;
				}
				if ("toString".equals(method.getName())) return "recorder";
				throw new UnsupportedOperationException(method.getName());
			});
			Class<?> helper = Class.forName("net.forbric.kernel.runtime.KernelNeoDataMapWatch", false, cl);
			helper.getMethod("installDataMapWatch", Object.class).invoke(null, recorder);
			assertEquals(3, calls.size(), calls.toString());
			for (String call : calls) {
				assertTrue(call.startsWith("4:LOWEST|false|"), "the four-argument overload at LOWEST, after NeoForgeEventHandler's NORMAL: " + call);
			}
			assertTrue(calls.get(0).contains("net.neoforged.neoforge.event.AddServerReloadListenersEvent"), calls.get(0));
			assertTrue(calls.get(1).contains("net.neoforged.neoforge.registries.datamaps.DataMapsUpdatedEvent"), calls.get(1));
			assertTrue(calls.get(2).contains("net.neoforged.neoforge.event.TagsUpdatedEvent$ServerDataLoad"), calls.get(2));
			assertNotNull(priority);
		}
	}

	private static boolean insideAHandler(MethodNode method, AbstractInsnNode target) {
		if (method.tryCatchBlocks == null) return false;
		int index = method.instructions.indexOf(target);
		for (TryCatchBlockNode block : method.tryCatchBlocks) {
			LabelNode handler = block.handler;
			// The handler range: from the handler label to the next label that begins another block's range or
			// handler — enough to place a GETSTATIC that sits right after the ASTORE of the caught throwable.
			int start = method.instructions.indexOf(handler);
			if (index <= start) continue;
			AbstractInsnNode insn = handler;
			int i = start;
			while (insn != null && i < index) {
				insn = insn.getNext();
				i++;
				if (insn instanceof LabelNode label && label != handler && isBlockBoundary(method, label)) return false;
			}
			if (insn != null) return true;
		}
		return false;
	}

	private static boolean isBlockBoundary(MethodNode method, LabelNode label) {
		for (TryCatchBlockNode block : method.tryCatchBlocks) if (block.start == label || block.handler == label) return true;
		return false;
	}

	private static MethodNode find(ClassNode node, String name) {
		for (MethodNode m : node.methods) if (m.name.equals(name)) return m;
		throw new AssertionError(name + " is gone from KernelNeoWorldgen");
	}

	private static URLClassLoader gameSideLoader() throws Exception {
		Path compiled = RUNTIME;
		Path run = TestFixtures.stagedRoot();
		Path neoRt = run.resolve(TestFixtures.NEO_CARRIER_JAR);
		Path merged = run.resolve(TestFixtures.BASE_JAR);
		TestFixtures.requireFiles(Fixture.STAGED,
				"the staged artifacts are absent", neoRt, merged);
		TestFixtures.require(Fixture.GAME_SIDE, Files.isDirectory(compiled), "the game-side set is not compiled");
		List<URL> urls = new ArrayList<>(List.of(compiled.toUri().toURL(), neoRt.toUri().toURL(), merged.toUri().toURL()));
		for (String pattern : List.of("com/mojang/datafixerupper", "com/google/code/gson", "com/mojang/brigadier", "com/google/guava/guava",
				"it/unimi/dsi/fastutil", "org/slf4j/slf4j-api", "org/apache/logging/log4j/log4j-api",
				"io/netty/netty-common", "io/netty/netty-buffer", TestFixtures.nettyCodecLibrary(), "io/netty/netty-transport", "io/netty/netty-handler",
				"org/joml/joml")) {
			Path library = newestUnder(pattern);
			TestFixtures.require(Fixture.MC_LIBRARIES, library != null, "no staged " + pattern + " jar in the local Minecraft libraries");
			urls.add(library.toUri().toURL());
		}
		return new URLClassLoader(urls.toArray(new URL[0]), KernelNeoWorldgenTest.class.getClassLoader());
	}

	private static Path newestUnder(String pattern) throws java.io.IOException {
		Path root = TestFixtures.minecraftDir().resolve("libraries");
		Path under = root.resolve(pattern);
		if (!Files.isDirectory(under)) return null;
		try (var stream = Files.walk(under)) {
			return stream.filter(f -> f.toString().endsWith(".jar") && !f.toString().contains("sources"))
					.sorted(java.util.Comparator.comparing(f -> f.getFileName().toString())).reduce((a, b) -> b).orElse(null);
		}
	}
}
