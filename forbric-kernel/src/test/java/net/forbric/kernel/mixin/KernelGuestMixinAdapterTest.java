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

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * The adapter must drop guest mixins that inject into Forge/NeoForge-owned merged classes while keeping ones that
 * target vanilla-owned classes and ones that are pure accessors — the exact distinctions that decide whether a
 * fabric-api client boots. Mixin classes are built with ASM so the test does not depend on a real fabric-api jar.
 */
class KernelGuestMixinAdapterTest {
	private static final String PKG = "net/example/mixin";

	/** A @Mixin(<target>) class carrying one @Inject method (i.e. NOT a pure accessor). */
	private static byte[] injectingMixin(String simpleName, String targetInternalName) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, PKG + "/" + simpleName, null, "java/lang/Object", null);
		AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		AnnotationVisitor arr = mixin.visitArray("value");
		arr.visit(null, Type.getObjectType(targetInternalName));
		arr.visitEnd();
		mixin.visitEnd();

		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "onInit", "()V", null, null);
		mv.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", false).visitEnd();
		mv.visitCode();
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 1);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A @Mixin(<target>) class whose only member is an @Accessor method and which declares no fields. */
	private static byte[] accessorMixin(String simpleName, String targetInternalName) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
				PKG + "/" + simpleName, null, "java/lang/Object", null);
		AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		AnnotationVisitor arr = mixin.visitArray("value");
		arr.visit(null, Type.getObjectType(targetInternalName));
		arr.visitEnd();
		mixin.visitEnd();

		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "getThing", "()I", null, null);
		mv.visitAnnotation("Lorg/spongepowered/asm/mixin/gen/Accessor;", false).visitEnd();
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static byte[] config(String pkg, String... clientMixins) {
		StringBuilder sb = new StringBuilder("{\"package\":\"").append(pkg).append("\",\"client\":[");
		for (int i = 0; i < clientMixins.length; i++) {
			if (i > 0) sb.append(',');
			sb.append('"').append(clientMixins[i]).append('"');
		}
		sb.append("]}");
		return sb.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static Function<String, byte[]> resolver(Map<String, byte[]> classes) {
		return path -> classes.get(path);
	}

	@Test
	void suppressesOwnedTargetInjectorButKeepsVanillaTargetAndAccessor() {
		Map<String, byte[]> classes = new HashMap<>();
		// Owned: net/minecraft/client/gui/render/ prefix (the GuiRenderer family).
		classes.put(PKG + "/GuiRendererMixin.class",
				injectingMixin("GuiRendererMixin", "net/minecraft/client/gui/render/GuiRenderer"));
		// Owned: exact-name table entry.
		classes.put(PKG + "/GameRendererMixin.class",
				injectingMixin("GameRendererMixin", "net/minecraft/client/renderer/GameRenderer"));
		// Vanilla-owned target: must be KEPT.
		classes.put(PKG + "/OptionsMixin.class",
				injectingMixin("OptionsMixin", "net/minecraft/client/Options"));
		// Accessor on an owned target: must be KEPT (other code casts to its interface).
		classes.put(PKG + "/GuiRendererAccessor.class",
				accessorMixin("GuiRendererAccessor", "net/minecraft/client/gui/render/GuiRenderer"));

		byte[] cfg = config(PKG.replace('/', '.'),
				"GuiRendererMixin", "GameRendererMixin", "OptionsMixin", "GuiRendererAccessor");

		List<String> dropped = KernelGuestMixinAdapter.ownedNonAccessorMixins(
				"example.mixins.json", cfg, resolver(classes));

		assertEquals(List.of("GuiRendererMixin", "GameRendererMixin"), dropped,
				"only the owned-target injecting mixins should be suppressed");
	}

	@Test
	void returnsEmptyForNonMixinJsonOrMissingPackage() {
		assertTrue(KernelGuestMixinAdapter.ownedNonAccessorMixins(
				"x.json", "{\"not\":\"a mixin config\"}".getBytes(StandardCharsets.UTF_8), p -> null).isEmpty());
		assertTrue(KernelGuestMixinAdapter.ownedNonAccessorMixins(
				"x.json", "not even json".getBytes(StandardCharsets.UTF_8), p -> null).isEmpty());
	}

	@Test
	void toleratesAMixinClassThatCannotBeResolved() {
		byte[] cfg = config("net.example.mixin", "GhostMixin");
		// resolver returns null for every path — must not throw, just skip.
		assertTrue(KernelGuestMixinAdapter.ownedNonAccessorMixins("example.mixins.json", cfg, p -> null).isEmpty());
	}
}
