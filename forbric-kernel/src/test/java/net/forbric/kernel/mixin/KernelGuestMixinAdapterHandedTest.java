/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;

/**
 * {@link KernelGuestMixinAdapter#judgeAsHanded}: a PARTIAL verdict the config-time pass printed is read again on the mixin as it
 * is handed to Mixin. An injector the config-time read could not anchor, which an adapter that runs when Mixin loads the class
 * moved onto a method that exists and runs, is withdrawn: the mixin leaves the partial count and its preflight row is resolved.
 * One still missing as handed keeps both. The stand-in names belong to no game or mod.
 */
@ResourceLock("ModCatalog")
@ResourceLock("system-properties")
class KernelGuestMixinAdapterHandedTest {
	private static final String CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
	private static final String TARGET = "net/minecraft/world/HandedTarget";

	@AfterEach void reset() {
		ForbricMixinService.setGuestConfigs(List.of());
		MixinCompatibility.reset();
		CompatibilityFindings.reset();
	}

	private static ClassNode target() {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V21;
		node.access = Opcodes.ACC_PUBLIC;
		node.name = TARGET;
		node.superName = "java/lang/Object";
		for (String name : List.of("render", "carried")) {
			MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, "()V", null, null);
			method.instructions.add(new InsnNode(Opcodes.RETURN));
			node.methods.add(method);
		}
		return node;
	}

	private static MethodNode handler(String name, String selector) {
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "HEAD"));
		AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
		inject.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of(selector)), "at", new ArrayList<>(List.of(at)), "require", 0));
		MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE, name, "(" + CI + ")V", null, null);
		method.visibleAnnotations = new ArrayList<>(List.of(inject));
		method.instructions.add(new InsnNode(Opcodes.RETURN));
		return method;
	}

	/** onRender binds render; onGone names a method the merged class does not have, as the config-time read sees it. */
	private static ClassNode mixin(String pkg, String simpleName) {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V21;
		node.access = Opcodes.ACC_PUBLIC;
		node.name = pkg + "/" + simpleName;
		node.superName = "java/lang/Object";
		AnnotationNode type = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		type.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType(TARGET)))));
		node.invisibleAnnotations = new ArrayList<>(List.of(type));
		node.methods.add(handler("onRender", "render"));
		node.methods.add(handler("onGone", "gone"));
		return node;
	}

	private static byte[] bytes(ClassNode node) {
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static ClassNode judged(String config, String pkg, String simpleName) {
		ClassNode mixin = mixin(pkg, simpleName);
		Map<String, byte[]> classes = new HashMap<>();
		classes.put(TARGET + ".class", bytes(target()));
		classes.put(mixin.name + ".class", bytes(mixin));
		byte[] json = ("{\"package\":\"" + pkg.replace('/', '.') + "\",\"required\":true,\"mixins\":[\"" + simpleName + "\"]}")
				.getBytes(StandardCharsets.UTF_8);
		assertEquals(List.of(), KernelGuestMixinAdapter.unfitMixins(config, json, classes::get), "kept, partial");
		assertTrue(KernelGuestMixinAdapter.partiallyApplied().contains(config + ":" + simpleName), KernelGuestMixinAdapter.partiallyApplied().toString());
		return mixin;
	}

	private static CompatibilityFinding row(String config, String pkg, String simpleName) {
		String id = MixinCompatibility.id(config, pkg.replace('/', '.') + "." + simpleName);
		return CompatibilityFindings.all().stream().filter(f -> f.id().equals(id)).findFirst().orElse(null);
	}

	@Test void anInjectorMovedOntoAMethodThatRunsIsWithdrawnAsHanded() {
		String config = "handed-moved.mixins.json", pkg = "net/example/handed", name = "MovedMixin";
		ClassNode mixin = judged(config, pkg, name);
		assertEquals(CompatibilityFinding.Confidence.SUSPECTED, row(config, pkg, name).confidence());
		// What an adapter that runs when Mixin loads the class does: onGone now names the method that carries its body.
		AnnotationNode inject = MixinFit.injectorOf(mixin.methods.stream().filter(m -> m.name.equals("onGone")).findFirst().orElseThrow());
		inject.values.set(inject.values.indexOf("method") + 1, new ArrayList<>(List.of("carried")));
		KernelGuestMixinAdapter.judgeAsHanded(mixin);
		assertFalse(KernelGuestMixinAdapter.partiallyApplied().contains(config + ":" + name), "it fits as handed to Mixin");
		assertEquals(CompatibilityFinding.Confidence.RESOLVED, row(config, pkg, name).confidence(), CompatibilityFindings.all().toString());
		KernelGuestMixinAdapter.judgeAsHanded(mixin);
		assertEquals(CompatibilityFinding.Confidence.RESOLVED, row(config, pkg, name).confidence(), "read once");
	}

	/** RED control: handed over as the config-time read saw it, the verdict and the row stand. */
	@Test void anInjectorStillMissingAsHandedKeepsTheVerdict() {
		String config = "handed-still.mixins.json", pkg = "net/example/still", name = "StillMixin";
		ClassNode mixin = judged(config, pkg, name);
		KernelGuestMixinAdapter.judgeAsHanded(mixin);
		assertTrue(KernelGuestMixinAdapter.partiallyApplied().contains(config + ":" + name));
		assertEquals(CompatibilityFinding.Confidence.SUSPECTED, row(config, pkg, name).confidence());
	}
}
