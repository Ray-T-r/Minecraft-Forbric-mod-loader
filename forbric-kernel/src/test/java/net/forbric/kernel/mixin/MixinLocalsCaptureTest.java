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

/** FAIL_HARD locals captures become FAIL_SOFT; other captures, other injectors and the switch are respected. */
@org.junit.jupiter.api.parallel.ResourceLock("system-properties")
class MixinLocalsCaptureTest {
	@AfterEach
	void reset() {
		System.clearProperty(MixinLocalsCapture.PROPERTY);
	}

	@Test
	void failHardBecomesFailSoft() {
		ClassNode node = parse(mixin("CAPTURE_FAILHARD"));
		assertEquals(1, MixinLocalsCapture.soften(node));
		assertEquals("CAPTURE_FAILSOFT", locals(node));
		assertEquals(0, MixinLocalsCapture.soften(node), "idempotent");
	}

	@Test
	void otherCapturesAndTheSwitchAreLeftAlone() {
		ClassNode print = parse(mixin("PRINT"));
		assertEquals(0, MixinLocalsCapture.soften(print));
		assertEquals("PRINT", locals(print));
		System.setProperty(MixinLocalsCapture.PROPERTY, "off");
		ClassNode hard = parse(mixin("CAPTURE_FAILHARD"));
		assertEquals(0, MixinLocalsCapture.soften(hard));
		assertEquals("CAPTURE_FAILHARD", locals(hard));
	}

	/** The real mixin that killed the popular-set server, when its jar is staged. */
	@Test
	void architecturysServerPlayerGameModeCaptureIsSoftened() throws Exception {
		Path mods = Path.of(System.getProperty("user.dir"), "build", "compat-inputs", "popular", "mods").normalize();
		assumeTrue(Files.isDirectory(mods), "popular inputs absent");
		Path jar = null;
		try (Stream<Path> list = Files.list(mods)) {
			jar = list.filter(p -> p.getFileName().toString().startsWith("architectury-fabric-")).findFirst().orElse(null);
		}
		assumeTrue(jar != null, "architectury-fabric not staged");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry("dev/architectury/mixin/fabric/MixinServerPlayerGameMode.class");
			assumeTrue(entry != null, "MixinServerPlayerGameMode is gone from architectury");
			ClassNode node = new ClassNode();
			try (InputStream in = zip.getInputStream(entry)) {
				new ClassReader(in).accept(node, 0);
			}
			assertTrue(MixinLocalsCapture.soften(node) >= 1, "onBreak captures locals FAIL_HARD on this build");
			assertEquals("CAPTURE_FAILSOFT", locals(node));
		}
	}

	private static String locals(ClassNode node) {
		for (MethodNode m : node.methods) {
			if (m.visibleAnnotations == null) continue;
			for (AnnotationNode a : m.visibleAnnotations) {
				if (!MixinLocalsCapture.INJECT_DESC.equals(a.desc)) continue;
				for (int i = 0; i + 1 < a.values.size(); i += 2) {
					if ("locals".equals(a.values.get(i)) && a.values.get(i + 1) instanceof String[] e) return e[1];
				}
			}
		}
		return null;
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] mixin(String capture) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "a/b/SomeMixin", null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "handler", "()V", null, null);
		AnnotationVisitor av = mv.visitAnnotation(MixinLocalsCapture.INJECT_DESC, true);
		AnnotationVisitor method = av.visitArray("method");
		method.visit(null, "target()V");
		method.visitEnd();
		av.visitEnum("locals", MixinLocalsCapture.LOCAL_CAPTURE_DESC, capture);
		av.visitEnd();
		mv.visitCode();
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 1);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}
}
