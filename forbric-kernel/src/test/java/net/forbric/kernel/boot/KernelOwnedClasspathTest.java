/*
 * Copyright 2026 The Forbric Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.classloading.LoaderProbePolicy;

class KernelOwnedClasspathTest {
	private static final String BOOTSTRAP = "com.llamalad7.mixinextras.MixinExtrasBootstrap";
	private static final String RUNTIME = "net.forbric.kernel.runtime.SuppliedProbe";
	private static final String GUEST = "example.guest.OnlyHere";
	private static final String CONFIG = "mixinextras.init.mixins.json";
	@TempDir Path temporary;

	@BeforeEach @AfterEach void reset() {
		System.clearProperty(KernelOwnedClasspath.SWITCH);
	}

	@Test void preservesBaseCarriersLibrariesAndGuestOrder() throws Exception {
		List<URL> foundation = List.of(url("base.jar"), url("forge.jar"), url("neo.jar"));
		List<Path> libraries = List.of(path("mc-one.jar"), path("mc-two.jar"));
		List<Path> forge = List.of(path("guest.jar"), path("nested.jar"));
		List<Path> fabric = List.of(path("guest.jar"), path("fabric.jar"));
		List<Path> bundled = List.of(path("mixinextras.jar"), path("runtime.jar"));
		assertEquals(List.of(url("base.jar"), url("forge.jar"), url("neo.jar"), url("mc-one.jar"),
				url("mc-two.jar"), url("mixinextras.jar"), url("runtime.jar"), url("guest.jar"),
				url("nested.jar"), url("fabric.jar")),
				KernelOwnedClasspath.compose(foundation, libraries, forge, fabric, bundled));
		System.setProperty(KernelOwnedClasspath.SWITCH, "off");
		assertEquals(List.of(url("base.jar"), url("forge.jar"), url("neo.jar"), url("mc-one.jar"),
				url("mc-two.jar"), url("guest.jar"), url("nested.jar"), url("fabric.jar"),
				url("mixinextras.jar"), url("runtime.jar")),
				KernelOwnedClasspath.compose(foundation, libraries, forge, fabric, bundled));
		assertEquals(List.of(url("base.jar"), url("forge.jar"), url("neo.jar")), foundation,
				"composing the owned path must not mutate the launcher's carrier list");
	}

	@ParameterizedTest @ValueSource(booleans = {true, false})
	void classesPreMixinBytesAndConfigAgreeWithoutChangingGuestOwnership(boolean suppliedFirst) throws Exception {
		if (!suppliedFirst) System.setProperty(KernelOwnedClasspath.SWITCH, "off");
		byte[] supplied = type(BOOTSTRAP, "supplied");
		byte[] guestCopy = type(BOOTSTRAP, "guest");
		Path bundle = jar("bundle.jar", Map.of(entry(BOOTSTRAP), supplied, entry(RUNTIME), type(RUNTIME, "supplied"),
				CONFIG, "supplied-config".getBytes(StandardCharsets.UTF_8)));
		Path guest = jar("guest.jar", Map.of(entry(BOOTSTRAP), guestCopy, entry(RUNTIME), type(RUNTIME, "guest"),
				entry(GUEST), type(GUEST, "unique-guest"), CONFIG, "guest-config".getBytes(StandardCharsets.UTF_8)));
		List<URL> owned = KernelOwnedClasspath.compose(List.of(), List.of(), List.of(guest), List.of(), List.of(bundle));
		try (ForbricClassLoader loader = new ForbricClassLoader(owned.toArray(URL[]::new), getClass().getClassLoader())) {
			loader.setJarFamilies(Map.of(guest, LoaderProbePolicy.Family.FORGE));
			assertArrayEquals(suppliedFirst ? supplied : guestCopy, loader.getPreMixinClassBytes(BOOTSTRAP));
			Class<?> bootstrap = loader.loadClass(BOOTSTRAP);
			assertSame(loader, bootstrap.getClassLoader(), "MixinExtras must remain game-side");
			assertEquals(suppliedFirst ? "supplied" : "guest", bootstrap.getMethod("marker").invoke(null));
			assertEquals((suppliedFirst ? bundle : guest).toUri().toURL(), source(bootstrap));
			assertEquals(suppliedFirst ? "supplied" : "guest", loader.loadClass(RUNTIME).getMethod("marker").invoke(null));
			try (InputStream in = loader.getGameResourceAsStream(CONFIG)) {
				assertNotNull(in);
				assertEquals(suppliedFirst ? "supplied-config" : "guest-config", new String(in.readAllBytes(), StandardCharsets.UTF_8));
			}
			Class<?> unique = loader.loadClass(GUEST);
			assertSame(loader, unique.getClassLoader());
			assertEquals(guest.toUri().toURL(), source(unique));
			assertEquals(LoaderProbePolicy.Family.FORGE, loader.familyOfClass(GUEST));
			assertEquals("unique-guest", unique.getMethod("marker").invoke(null));
		}
	}

	@Test void aLaterGuestCannotReplaceTheSuppliedLibrary() throws Exception {
		Path bundle = jar("bundle.jar", Map.of(entry(BOOTSTRAP), type(BOOTSTRAP, "supplied")));
		Path later = jar("later.jar", Map.of(entry(BOOTSTRAP), type(BOOTSTRAP, "late-guest"),
				entry(GUEST), type(GUEST, "late-unique")));
		var owned = KernelOwnedClasspath.compose(List.of(), List.of(), List.of(), List.of(), List.of(bundle));
		try (ForbricClassLoader loader = new ForbricClassLoader(owned.toArray(URL[]::new), getClass().getClassLoader())) {
			loader.addURL(later.toUri().toURL());
			assertEquals("supplied", loader.loadClass(BOOTSTRAP).getMethod("marker").invoke(null));
			assertEquals("late-unique", loader.loadClass(GUEST).getMethod("marker").invoke(null));
		}
	}

	@Test void bootUsesTheTestedOrderBeforeConstructingTheLoader() throws Exception {
		ClassNode node = new ClassNode();
		try (InputStream in = KernelBoot.class.getResourceAsStream("KernelBoot.class")) {
			assertNotNull(in);
			new ClassReader(in.readAllBytes()).accept(node, 0);
		}
		var launch = node.methods.stream().filter(m -> m.name.equals("launch")).findFirst().orElseThrow();
		List<Integer> composers = new ArrayList<>();
		int loaderNew = -1;
		var instructions = launch.instructions.toArray();
		for (int i = 0; i < instructions.length; i++) {
			if (instructions[i] instanceof MethodInsnNode call && call.owner.equals("net/forbric/kernel/boot/KernelOwnedClasspath")
					&& call.name.equals("compose")) composers.add(i);
			if (instructions[i] instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
					&& type.desc.equals("net/forbric/kernel/classloading/ForbricClassLoader")) loaderNew = i;
		}
		assertEquals(1, composers.size(), "a passing isolated order helper must actually govern launch");
		assertTrue(loaderNew > composers.getFirst(), "source precedence must exist before any owned class loads");
	}

	private Path path(String name) { return temporary.resolve(name); }
	private URL url(String name) throws Exception { return path(name).toUri().toURL(); }
	private static String entry(String name) { return name.replace('.', '/') + ".class"; }
	private static URL source(Class<?> type) { return type.getProtectionDomain().getCodeSource().getLocation(); }

	private Path jar(String name, Map<String, byte[]> entries) throws Exception {
		Path target = path(name);
		try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(target))) {
			for (var entry : entries.entrySet()) {
				jar.putNextEntry(new JarEntry(entry.getKey())); jar.write(entry.getValue()); jar.closeEntry();
			}
		}
		return target;
	}

	private static byte[] type(String name, String marker) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null, "java/lang/Object", null);
		var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "marker", "()Ljava/lang/String;", null, null);
		method.visitCode(); method.visitLdcInsn(marker); method.visitInsn(Opcodes.ARETURN); method.visitMaxs(1, 0); method.visitEnd();
		writer.visitEnd(); return writer.toByteArray();
	}
}
