package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/**
 * {@link FabricClientMixinAnchors} resolves a target with its code only for a mixin that has a handler it could act on:
 * an empty {@code @Redirect} of a void call, or a {@code Map.remove} redirect on {@code LevelChunk}. Every other mixin —
 * almost all of them — costs no resolution. The positive control still resolves its target and moves its redirect.
 */
class FabricClientMixinAnchorsResolutionTest {
	private static final String CHUNK = "net/minecraft/world/level/chunk/LevelChunk";

	/** A mixin full of code (an inject with a body, a redirect with a body, a plain helper) resolves nothing. */
	@Test void aMixinWithoutAnEmptyRedirectResolvesNoTarget() {
		ClassNode mixin = mixin("org/example/paint/mixin/TintMixin", List.of("org/example/paint/Canvas", "org/example/paint/Brush"));
		mixin.methods.add(injector("onDraw", "Lorg/spongepowered/asm/mixin/injection/Inject;", "draw", "HEAD", null,
				"(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", true));
		mixin.methods.add(injector("swapStroke", "Lorg/spongepowered/asm/mixin/injection/Redirect;", "draw", "INVOKE",
				"Lorg/example/paint/Stroke;apply(Ljava/lang/String;)V", "(Lorg/example/paint/Stroke;Ljava/lang/String;)V", true));
		MethodNode helper = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "shade", "()V", null, null);
		helper.instructions.add(new InsnNode(Opcodes.RETURN));
		mixin.methods.add(helper);
		Counting targets = new Counting(name -> host(name, "draw", "apply", "(Ljava/lang/Object;Ljava/lang/String;)V"));
		assertEquals(0, FabricClientMixinAnchors.adapt(mixin, targets));
		assertEquals(List.of(), targets.asked, "no target resolved for a mixin with nothing to move");
	}

	/** A mixin on LevelChunk without a {@code Map.remove} redirect resolves nothing either. */
	@Test void aChunkMixinWithoutTheRemovalRedirectResolvesNoTarget() {
		ClassNode mixin = mixin("org/example/blocks/mixin/ChunkWatchMixin", List.of(CHUNK));
		mixin.methods.add(injector("watch", "Lorg/spongepowered/asm/mixin/injection/Inject;", "getBlockEntity", "HEAD", null,
				"(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", true));
		Counting targets = new Counting(name -> null);
		assertEquals(0, FabricClientMixinAnchors.adapt(mixin, targets));
		assertEquals(List.of(), targets.asked);
	}

	/** Control: an empty redirect of a call the host widened resolves its target and is moved onto the widened call. */
	@Test void anEmptyRedirectResolvesItsTargetAndMoves() {
		ClassNode mixin = mixin("org/example/paint/mixin/NoStrokeMixin", List.of("org/example/paint/Canvas"));
		mixin.methods.add(injector("skipStroke", "Lorg/spongepowered/asm/mixin/injection/Redirect;", "draw", "INVOKE",
				"Lorg/example/paint/Stroke;apply(Ljava/lang/String;)V", "(Lorg/example/paint/Stroke;Ljava/lang/String;)V", false));
		Counting targets = new Counting(name -> host(name, "draw", "apply", "(Ljava/lang/Object;Ljava/lang/String;)V"));
		assertEquals(1, FabricClientMixinAnchors.adapt(mixin, targets));
		assertEquals(List.of("org/example/paint/Canvas"), targets.asked);
		assertEquals("Lorg/example/paint/Stroke;apply(Ljava/lang/Object;Ljava/lang/String;)V",
				MixinFit.value(StagedFabricMixinFixture.at(mixin, "skipStroke"), "target"));
	}

	private static final class Counting implements Function<String, ClassNode> {
		final List<String> asked = new ArrayList<>();
		final Function<String, ClassNode> delegate;

		Counting(Function<String, ClassNode> delegate) {
			this.delegate = delegate;
		}

		@Override public ClassNode apply(String name) {
			asked.add(name);
			return delegate.apply(name);
		}
	}

	private static ClassNode mixin(String name, List<String> targets) {
		ClassNode mixin = new ClassNode();
		mixin.version = Opcodes.V21;
		mixin.access = Opcodes.ACC_ABSTRACT;
		mixin.name = name;
		mixin.superName = "java/lang/Object";
		AnnotationNode type = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		type.values = new ArrayList<>(List.of("value", new ArrayList<>(targets.stream().map(Type::getObjectType).toList())));
		mixin.invisibleAnnotations = new ArrayList<>(List.of(type));
		return mixin;
	}

	/** An injector handler; with {@code body}, it does something before returning. */
	private static MethodNode injector(String name, String kind, String selector, String point, String target, String desc, boolean body) {
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", point));
		if (target != null) {
			at.values.add("target");
			at.values.add(target);
		}
		AnnotationNode injector = new AnnotationNode(kind);
		injector.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of(selector)), "at", at));
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, name, desc, null, null);
		handler.visibleAnnotations = new ArrayList<>(List.of(injector));
		if (body) {
			handler.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
			handler.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "()V", false));
		}
		handler.instructions.add(new InsnNode(Opcodes.RETURN));
		handler.maxStack = 1;
		handler.maxLocals = Type.getArgumentsAndReturnSizes(desc) >> 2;
		return handler;
	}

	/** A host whose {@code method()} makes one call of {@code Stroke.callee(desc)}, a widened form of the redirected call. */
	private static ClassNode host(String name, String method, String callee, String desc) {
		ClassNode host = new ClassNode();
		host.version = Opcodes.V21;
		host.name = name;
		host.superName = "java/lang/Object";
		MethodNode draw = new MethodNode(Opcodes.ACC_PUBLIC, method, "()V", null, null);
		draw.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
		draw.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
		draw.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
		draw.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "org/example/paint/Stroke", callee, desc, false));
		draw.instructions.add(new InsnNode(Opcodes.RETURN));
		host.methods.add(draw);
		return host;
	}
}
