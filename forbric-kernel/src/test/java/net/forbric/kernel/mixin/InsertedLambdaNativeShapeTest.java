/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import net.forbric.kernel.transform.DuplicateLambdaPruneInjector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The second evidence InsertedLambdaArgumentShim acts on: the class the mod was compiled against declares the lambda in
 * the shape the handler was written for, and the merged class has that lambda only with an argument inserted. The pruner
 * never saw the native shape — both carriers' bodies have the inserted argument (Screenshot.grab's
 * {@code lambda$grab$3} takes a {@code ScreenshotEvent} on MinecraftForge and NeoForge, not on Fabric), and it dropped
 * the other carrier's — so its record alone named nothing, Mixin rejected the handler's descriptor and Essential's whole
 * screenshot-capture mixin failed. The fixture is invented: a camera whose lambda gained an {@code Object}.
 */
@ResourceLock("system-properties")
class InsertedLambdaNativeShapeTest {
	private static final String LAMBDA = "lambda$snap$3", LENS = "spry/Lens";
	private static final String OLD = "(ILjava/lang/String;)V", NEW = "(ILjava/lang/Object;Ljava/lang/String;)V";

	public static class Photographer {
		public static Object[] seen;
		private static void onSnap(int frame, String name, CallbackInfo callback) { seen = new Object[] {frame, name, callback}; }
	}

	@AfterEach void clear() {
		System.clearProperty(InsertedLambdaArgumentShim.NATIVE_SHAPES);
	}

	/** The other carrier's body was pruned, the native shape never was: a bare name and a spelled one both move. */
	@Test
	void aLambdaBothCarriersWidenedMovesWhereItsNativeShapeIsTheHandlers() throws Exception {
		String owner = "spry/CameraPruned";
		DuplicateLambdaPruneInjector.recordDroppedForTest(owner, LAMBDA, "(ILjava/lang/Integer;Ljava/lang/String;)V");
		for (List<String> selectors : List.of(List.of(LAMBDA), List.of(LAMBDA + OLD))) {
			ClassNode mixin = mixin(owner, selectors, "L" + LENS + ";open()V"), target = target(owner, true);
			assertEquals(1, InsertedLambdaArgumentShim.adapt(mixin, n -> target, natives(owner, OLD)), selectors.toString());
			assertEquals(List.of(LAMBDA + NEW), MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(method(mixin, "forbric$expanded$onSnap")), "method")));
		}
	}

	/** A lambda the merge carried once, with the argument inserted: no pruner record at all. */
	@Test
	void aLambdaCarriedOnceWithAnInsertedArgumentMovesToo() throws Exception {
		String owner = "spry/CameraOnce";
		ClassNode mixin = mixin(owner, List.of(LAMBDA), "L" + LENS + ";open()V"), target = target(owner, true);
		assertEquals(1, InsertedLambdaArgumentShim.adapt(mixin, n -> target, natives(owner, OLD)));
		// The JVM runs the shim: the inserted argument is skipped, the rest reach the handler in order.
		byte[] bytes = StagedFabricMixinFixture.bytes(mixin);
		Class<?> defined = new ClassLoader(getClass().getClassLoader()) {
			Class<?> define() { return defineClass(mixin.name.replace('/', '.'), bytes, 0, bytes.length); }
		}.define();
		var invoke = defined.getDeclaredMethod("forbric$expanded$onSnap", int.class, Object.class, String.class, CallbackInfo.class);
		invoke.setAccessible(true);
		CallbackInfo callback = new CallbackInfo("snap", false);
		invoke.invoke(null, 7, new Object(), "flash", callback);
		assertArrayEquals(new Object[] {7, "flash", callback}, (Object[]) defined.getField("seen").get(null));
	}

	@Test
	void withoutTheNativeShapeOrWithItStillInTheMergedClassNothingMoves() throws Exception {
		String owner = "spry/CameraRefused";
		ClassNode target = target(owner, true);
		assertEquals(0, InsertedLambdaArgumentShim.adapt(mixin(owner, List.of(LAMBDA), "L" + LENS + ";open()V"), n -> target, n -> null),
				"no class the mod was compiled against");
		assertEquals(0, InsertedLambdaArgumentShim.adapt(mixin(owner, List.of(LAMBDA), "L" + LENS + ";open()V"), n -> target, natives(owner, NEW)),
				"natively the lambda already has the merged shape");
		assertEquals(0, InsertedLambdaArgumentShim.adapt(mixin(owner, List.of(LAMBDA + OLD), "L" + LENS + ";open()V"), n -> target, n -> null),
				"a spelled shape no class declares");
		ClassNode both = target(owner, true);
		MethodNode old = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, LAMBDA, OLD, null, null);
		old.instructions.add(new InsnNode(Opcodes.RETURN));
		both.methods.add(old);
		assertEquals(0, InsertedLambdaArgumentShim.adapt(mixin(owner, List.of(LAMBDA + OLD), "L" + LENS + ";open()V"), n -> both, natives(owner, OLD)),
				"the native shape is still in the merged class: it binds as written");
	}

	/** A lambda of the same name that does not make the anchored call is another lambda, not this one widened. */
	@Test
	void aLiveLambdaWithoutTheAnchorIsNotTheNativeOne() throws Exception {
		String owner = "spry/CameraDrifted";
		ClassNode target = target(owner, false);
		assertEquals(0, InsertedLambdaArgumentShim.adapt(mixin(owner, List.of(LAMBDA), "L" + LENS + ";open()V"), n -> target, natives(owner, OLD)));
	}

	/** The control: with the second evidence switched off the handler stays as written, as before. */
	@Test
	void switchedOffTheNativeShapeIsNoEvidence() throws Exception {
		System.setProperty(InsertedLambdaArgumentShim.NATIVE_SHAPES, "off");
		String owner = "spry/CameraOff";
		ClassNode target = target(owner, true);
		assertEquals(0, InsertedLambdaArgumentShim.adapt(mixin(owner, List.of(LAMBDA), "L" + LENS + ";open()V"), n -> target, natives(owner, OLD)));
		assertEquals(0, InsertedLambdaArgumentShim.adapt(mixin(owner, List.of(LAMBDA + OLD), "L" + LENS + ";open()V"), n -> target, natives(owner, OLD)));
	}

	// ---------------------------------------------------------------------------------------------------------------

	private ClassNode mixin(String owner, List<String> selectors, String anchor) throws Exception {
		ClassNode node;
		try (var in = Photographer.class.getResourceAsStream("InsertedLambdaNativeShapeTest$Photographer.class")) {
			node = MixinFit.parse(in.readAllBytes());
		}
		node.nestHostClass = null;
		node.innerClasses.clear();
		AnnotationNode mixin = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		mixin.values = new ArrayList<>(List.of("value", List.of(Type.getObjectType(owner))));
		node.invisibleAnnotations = new ArrayList<>(List.of(mixin));
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "INVOKE", "target", anchor));
		AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
		inject.values = new ArrayList<>(List.of("method", new ArrayList<>(selectors), "at", List.of(at)));
		method(node, "onSnap").visibleAnnotations = new ArrayList<>(List.of(inject));
		return node;
	}

	/** The merged camera: its one lambda of the name took an {@code Object} after the frame; it opens the lens when {@code anchored}. */
	private static ClassNode target(String owner, boolean anchored) {
		ClassNode target = new ClassNode();
		target.name = owner;
		target.superName = "java/lang/Object";
		MethodNode lambda = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, LAMBDA, NEW, null, null);
		lambda.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LENS, anchored ? "open" : "close", "()V", false));
		lambda.instructions.add(new InsnNode(Opcodes.RETURN));
		target.methods.add(lambda);
		MethodNode caller = new MethodNode(Opcodes.ACC_PUBLIC, "snap", "()V", null, null);
		caller.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, LAMBDA, NEW, false));
		caller.instructions.add(new InsnNode(Opcodes.RETURN));
		target.methods.add(caller);
		return target;
	}

	/** The camera the mod was compiled against: the lambda of that name with {@code desc}. */
	private static Function<String, ClassNode> natives(String owner, String desc) {
		return name -> {
			if (!name.equals(owner)) return null;
			ClassNode node = new ClassNode();
			node.name = owner;
			node.superName = "java/lang/Object";
			MethodNode lambda = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, LAMBDA, desc, null, null);
			lambda.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LENS, "open", "()V", false));
			lambda.instructions.add(new InsnNode(Opcodes.RETURN));
			node.methods.add(lambda);
			return node;
		};
	}

	private static MethodNode method(ClassNode node, String name) {
		return StagedFabricMixinFixture.method(node, name);
	}
}
