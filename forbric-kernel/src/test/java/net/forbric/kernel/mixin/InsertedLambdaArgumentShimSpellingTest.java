/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import net.forbric.kernel.transform.DuplicateLambdaPruneInjector;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The shim follows a selector naming a lambda the pruner dropped however Mixin reads it — an owner prefix, a dotted owner,
 * whitespace, several spellings of the one lambda, or a bare name the class the mod was compiled against decides — and an
 * anchor however it is written. The fixture is invented: a painter whose lambda gained an {@code Object} in the merge,
 * a handler taking two arguments, an anchor on another class.
 */
@org.junit.jupiter.api.parallel.ResourceLock("system-properties")
@org.junit.jupiter.api.extension.ExtendWith(net.forbric.kernel.DroppedLambdaRecordRestore.class)
class InsertedLambdaArgumentShimSpellingTest {
	private static final String TARGET = "spry/Canvas", LAMBDA = "lambda$paint$4", BRUSH = "spry/Brush";
	private static final String OLD = "(ILjava/lang/String;)V", NEW = "(ILjava/lang/Object;Ljava/lang/String;)V";

	public static class Painter {
		public static Object[] seen;
		private static void onStroke(int layer, String name, CallbackInfo callback) { seen = new Object[] {layer, name, callback}; }
	}

	public static class CapturingPainter {
		public static Object[] seen;
		private static void onStroke(int layer, String name, CallbackInfo callback, String colour) { seen = new Object[] {layer, name, callback, colour}; }
	}

	@Test
	void aDroppedLambdaSpelledAnyWayMixinReadsItMovesToTheLiveOne() throws Exception {
		List<List<String>> spellings = List.of(
				List.of("L" + TARGET + ";" + LAMBDA + OLD),
				List.of(" spry.Canvas." + LAMBDA + " " + OLD),
				List.of(LAMBDA + OLD, "L" + TARGET + ";" + LAMBDA + OLD));
		List<String> anchors = List.of("L" + BRUSH + ";stroke()V", "spry.Brush.stroke()V", "L" + BRUSH + ";stroke");
		for (int i = 0; i < spellings.size(); i++) {
			ClassNode mixin = mixin(Painter.class, spellings.get(i), anchors.get(i), false), target = target(false);
			assertEquals(1, InsertedLambdaArgumentShim.adapt(mixin, n -> target), spellings.get(i) + " at " + anchors.get(i));
			assertEquals(List.of(LAMBDA + NEW), MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(method(mixin, "forbric$expanded$onStroke")), "method")),
					"the shim's injector names the live lambda");
		}
	}

	/** A bare name is read in the class the mod was compiled against, where the lambda had its old shape. */
	@Test
	void aBareNameIsDecidedByTheClassTheModWasCompiledAgainst() throws Exception {
		ClassNode mixin = mixin(Painter.class, List.of(LAMBDA), "stroke", false), target = target(false);
		assertEquals(1, InsertedLambdaArgumentShim.adapt(mixin, n -> target, n -> n.equals(TARGET) ? nativeCanvas(OLD) : null));
		// The JVM runs the shim: the inserted argument is skipped, the rest reach the handler in order.
		MethodNode shim = method(mixin, "forbric$expanded$onStroke");
		byte[] bytes = StagedFabricMixinFixture.bytes(mixin);
		Class<?> defined = new ClassLoader(getClass().getClassLoader()) {
			Class<?> define() { return defineClass(mixin.name.replace('/', '.'), bytes, 0, bytes.length); }
		}.define();
		var invoke = defined.getDeclaredMethod(shim.name, int.class, Object.class, String.class, CallbackInfo.class);
		invoke.setAccessible(true);
		CallbackInfo callback = new CallbackInfo("paint", false);
		invoke.invoke(null, 3, new Object(), "wash", callback);
		assertArrayEquals(new Object[] {3, "wash", callback}, (Object[]) defined.getField("seen").get(null));
	}

	/** The captured locals are proved at the call the anchor selects, however the anchor is written. */
	@Test
	void capturedLocalsAreProvedAtTheCallAnAnchorWrittenAnotherWaySelects() throws Exception {
		for (String anchor : List.of("spry.Brush.stroke ()V", "stroke", "L" + BRUSH + ";stroke")) {
			ClassNode mixin = mixin(CapturingPainter.class, List.of("L" + TARGET + ";" + LAMBDA + OLD), anchor, true), target = target(true);
			assertEquals(1, InsertedLambdaArgumentShim.adapt(mixin, n -> target), anchor);
			assertEquals("(ILjava/lang/Object;Ljava/lang/String;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;Ljava/lang/String;)V",
					method(mixin, "forbric$expanded$onStroke").desc);
		}
	}

	/** The verdict judges the selector where the shim will move it, read the same way. */
	@Test
	void theVerdictReadsTheSpellingAsTheShimDoes() throws Exception {
		byte[] canvas = StagedFabricMixinFixture.bytes(target(false));
		Function<String, byte[]> resolver = path -> path.equals(TARGET + ".class") ? canvas : null;
		MixinFit.Result moved = MixinFit.evaluate(StagedFabricMixinFixture.bytes(mixin(Painter.class, List.of("spry.Canvas." + LAMBDA + OLD), "stroke", false)), resolver);
		assertEquals(MixinFit.Verdict.FIT, moved.verdict(), moved.unresolved().toString());
		MixinFit.Result elsewhere = MixinFit.evaluate(StagedFabricMixinFixture.bytes(mixin(Painter.class, List.of("Lspry/Easel;" + LAMBDA + OLD), "stroke", false)), resolver);
		assertNotEquals(MixinFit.Verdict.FIT, elsewhere.verdict(), "another owner's lambda is not this one");
	}

	@Test
	void anotherOwnerAnotherShapeOrAnUndecidedNameStaysAsWritten() throws Exception {
		List<List<String>> refused = List.of(
				List.of("Lspry/Easel;" + LAMBDA + OLD),
				List.of(LAMBDA + "(I)V"),
				List.of(LAMBDA + OLD, LAMBDA + "(I)V"),
				List.of("/" + LAMBDA.replace("$", "\\$") + "/"));
		for (List<String> selectors : refused) {
			ClassNode mixin = mixin(Painter.class, selectors, "L" + BRUSH + ";stroke()V", false), target = target(false);
			assertEquals(0, InsertedLambdaArgumentShim.adapt(mixin, n -> target), selectors.toString());
		}
		ClassNode bare = mixin(Painter.class, List.of(LAMBDA), "L" + BRUSH + ";stroke()V", false), target = target(false);
		assertEquals(0, InsertedLambdaArgumentShim.adapt(bare, n -> target, n -> null), "no class to decide a bare name in");
		assertEquals(0, InsertedLambdaArgumentShim.adapt(bare, n -> target, n -> nativeCanvas("(I)V")), "the name bound a shape the pruner kept");
	}

	@Test
	void anAnchorNamingAnotherCallIsNoAnchor() throws Exception {
		for (String anchor : List.of("Lspry/Pen;stroke()V", "spry.Pen.stroke", "dab", "L" + BRUSH + ";stroke(I)V")) {
			ClassNode mixin = mixin(Painter.class, List.of("L" + TARGET + ";" + LAMBDA + OLD), anchor, false), target = target(false);
			assertEquals(0, InsertedLambdaArgumentShim.adapt(mixin, n -> target), anchor);
		}
		for (String anchor : List.of("Lspry/Pen;stroke", "dab")) {
			ClassNode mixin = mixin(CapturingPainter.class, List.of("L" + TARGET + ";" + LAMBDA + OLD), anchor, true), target = target(true);
			assertEquals(0, InsertedLambdaArgumentShim.adapt(mixin, n -> target), "captures at " + anchor);
		}
	}

	// ---------------------------------------------------------------------------------------------------------------

	private ClassNode mixin(Class<?> source, List<String> selectors, String anchor, boolean capturing) throws Exception {
		ClassNode node;
		String simple = source.getName().substring(source.getName().lastIndexOf('.') + 1);
		try (var in = source.getResourceAsStream(simple + ".class")) { node = MixinFit.parse(in.readAllBytes()); }
		node.nestHostClass = null;
		node.innerClasses.clear();
		AnnotationNode mixin = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		mixin.values = new ArrayList<>(List.of("value", List.of(Type.getObjectType(TARGET))));
		node.invisibleAnnotations = new ArrayList<>(List.of(mixin));
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "INVOKE", "target", anchor));
		AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
		inject.values = new ArrayList<>(List.of("method", new ArrayList<>(selectors), "at", List.of(at)));
		if (capturing) inject.values.addAll(List.of("locals", new String[] {"Lorg/spongepowered/asm/mixin/injection/callback/LocalCapture;", "CAPTURE_FAILSOFT"}));
		method(node, "onStroke").visibleAnnotations = new ArrayList<>(List.of(inject));
		return node;
	}

	/** The merged painter: the live lambda took an {@code Object} after the layer, and keeps a colour local when {@code locals}. */
	private static ClassNode target(boolean locals) {
		ClassNode target = new ClassNode();
		target.name = TARGET;
		target.superName = "java/lang/Object";
		MethodNode lambda = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, LAMBDA, NEW, null, null);
		LabelNode start = new LabelNode(), end = new LabelNode();
		if (locals) {
			lambda.instructions.add(new LdcInsnNode("ochre"));
			lambda.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
		}
		lambda.instructions.add(start);
		lambda.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRUSH, "stroke", "()V", false));
		lambda.instructions.add(end);
		lambda.instructions.add(new InsnNode(Opcodes.RETURN));
		if (locals) lambda.localVariables = new ArrayList<>(List.of(new LocalVariableNode("colour", "Ljava/lang/String;", null, start, end, 3)));
		target.methods.add(lambda);
		MethodNode caller = new MethodNode(Opcodes.ACC_PUBLIC, "paint", "()V", null, null);
		caller.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TARGET, LAMBDA, NEW, false));
		caller.instructions.add(new InsnNode(Opcodes.RETURN));
		target.methods.add(caller);
		DuplicateLambdaPruneInjector.recordDroppedForTest(TARGET, LAMBDA, OLD);
		return target;
	}

	/** The painter the mod was compiled against: the lambda of that name with {@code desc}. */
	private static ClassNode nativeCanvas(String desc) {
		ClassNode node = new ClassNode();
		node.name = TARGET;
		node.superName = "java/lang/Object";
		MethodNode lambda = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, LAMBDA, desc, null, null);
		lambda.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRUSH, "stroke", "()V", false));
		lambda.instructions.add(new InsnNode(Opcodes.RETURN));
		node.methods.add(lambda);
		return node;
	}

	private static MethodNode method(ClassNode node, String name) {
		return StagedFabricMixinFixture.method(node, name);
	}
}
