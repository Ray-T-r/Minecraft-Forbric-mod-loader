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

package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** A one-element `at` array on a single-point injector is unwrapped; multi-point injectors and real arrays are not. */
@org.junit.jupiter.api.parallel.ResourceLock("system-properties")
class MixinAtShapeTest {
	private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
	private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";

	@AfterEach
	void reset() {
		System.clearProperty(MixinAtShape.PROPERTY);
	}

	@Test
	void aOneElementArrayOnARedirectBecomesTheSingleAt() {
		ClassNode node = parse(mixin(REDIRECT, 1));
		assertEquals(1, MixinAtShape.normalise(node));
		Object at = atValue(node);
		assertTrue(at instanceof AnnotationNode single && MixinAtShape.AT_DESC.equals(single.desc), String.valueOf(at));
		assertEquals(0, MixinAtShape.normalise(node), "idempotent");
	}

	@Test
	void anInjectKeepsItsArrayAndATwoElementRedirectArrayIsLeftAlone() {
		ClassNode inject = parse(mixin(INJECT, 1));
		assertEquals(0, MixinAtShape.normalise(inject));
		assertTrue(atValue(inject) instanceof List<?>, "@Inject declares At[] and keeps its list");
		ClassNode two = parse(mixin(REDIRECT, 2));
		assertEquals(0, MixinAtShape.normalise(two));
		assertTrue(atValue(two) instanceof List<?> list && list.size() == 2, "two points cannot be one");
	}

	@Test
	void switchedOffTheShapeStaysAsCompiled() {
		System.setProperty(MixinAtShape.PROPERTY, "off");
		ClassNode node = parse(mixin(REDIRECT, 1));
		assertEquals(0, MixinAtShape.normalise(node));
		assertTrue(atValue(node) instanceof List<?>);
	}

	/** The real mixin that took the popular-set server down, when its jar is staged. */
	@Test
	void architecturysNaturalSpawnerRedirectIsUnwrapped() throws Exception {
		Path mods = Path.of(System.getProperty("user.dir"), "build", "compat-inputs", "popular", "mods").normalize();
		assumeTrue(Files.isDirectory(mods), "popular inputs absent");
		Path jar = null;
		try (Stream<Path> list = Files.list(mods)) {
			jar = list.filter(p -> p.getFileName().toString().startsWith("architectury-fabric-")).findFirst().orElse(null);
		}
		assumeTrue(jar != null, "architectury-fabric not staged");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry("dev/architectury/mixin/fabric/MixinNaturalSpawner.class");
			assumeTrue(entry != null, "MixinNaturalSpawner is gone from architectury");
			ClassNode node = new ClassNode();
			try (InputStream in = zip.getInputStream(entry)) {
				new ClassReader(in).accept(node, 0);
			}
			assertTrue(MixinAtShape.normalise(node) >= 1, "architectury's @Redirect carries at=[@At] on this build");
		}
	}

	private static Object atValue(ClassNode node) {
		for (MethodNode m : node.methods) {
			if (m.visibleAnnotations == null) continue;
			for (AnnotationNode a : m.visibleAnnotations) {
				for (int i = 0; i + 1 < a.values.size(); i += 2) if ("at".equals(a.values.get(i))) return a.values.get(i + 1);
			}
		}
		return null;
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	/** A mixin-shaped class with one handler whose injector annotation carries `at` as an array of {@code points} @At. */
	private static byte[] mixin(String injectorDesc, int points) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "a/b/SomeMixin", null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "handler", "()V", null, null);
		AnnotationVisitor av = mv.visitAnnotation(injectorDesc, true);
		AnnotationVisitor method = av.visitArray("method");
		method.visit(null, "target()V");
		method.visitEnd();
		AnnotationVisitor at = av.visitArray("at");
		for (int i = 0; i < points; i++) {
			AnnotationVisitor point = at.visitAnnotation(null, MixinAtShape.AT_DESC);
			point.visit("value", "INVOKE");
			point.visit("target", "La/b/C;m" + i + "()V");
			point.visitEnd();
		}
		at.visitEnd();
		av.visitEnd();
		mv.visitCode();
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 1);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}
}
