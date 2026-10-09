package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * An empty {@code @Redirect} of a call the carrier widened skips the widened call, wherever the guest's selector points
 * — not only in the one host fabric-renderer-api names. The positive fixtures are other mods' redirects of
 * {@code collectParts} in the two other merged bodies NeoForge widened it in: a bare-name selector whose handler also
 * captures a host argument, and a full selector.
 */
class EmptyRedirectWidenedCallTest {
	private static final String MODEL = "net/minecraft/client/renderer/block/dispatch/BlockStateModel";
	private static final String SHORT = "(Lnet/minecraft/util/RandomSource;Ljava/util/List;)V";
	private static final String WIDE = "(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
			+ "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/util/RandomSource;Ljava/util/List;)V";
	private static final String TESSELATOR = "net/minecraft/client/renderer/block/ModelBlockRenderer";
	private static final String WRAPPER = "net/minecraft/client/renderer/block/model/BlockStateModelWrapper";

	@Test void aBareNameRedirectCapturingAHostArgumentSkipsTheWidenedCall() throws Exception {
		ClassNode target = StagedFabricMixinFixture.game(TESSELATOR, false);
		ClassNode mixin = mixin("org/example/xray/mixin/HideBlocksMixin", TESSELATOR, "tesselateBlock", "hideParts",
				"(L" + MODEL + ";Lnet/minecraft/util/RandomSource;Ljava/util/List;Lnet/minecraft/client/renderer/block/BlockQuadOutput;)V", "L" + MODEL + ";collectParts" + SHORT);
		assertEquals(1, FabricClientMixinAnchors.adapt(mixin, name -> name.equals(TESSELATOR) ? target : null));
		MethodNode handler = StagedFabricMixinFixture.method(mixin, "hideParts");
		assertEquals("(L" + MODEL + ";" + WIDE.substring(1, WIDE.indexOf(')')) + "Lnet/minecraft/client/renderer/block/BlockQuadOutput;)V", handler.desc,
				"the widened call's arguments, then the host argument it captured");
		assertEquals("L" + MODEL + ";collectParts" + WIDE, MixinFit.value(StagedFabricMixinFixture.at(mixin, "hideParts"), "target"));
		assertEquals(Type.getArgumentsAndReturnSizes(handler.desc) >> 2, handler.maxLocals);
		new Analyzer<>(new BasicVerifier()).analyze(mixin.name, handler);
	}

	@Test void aFullSelectorInAnotherClassSkipsTheWidenedCall() throws Exception {
		ClassNode target = StagedFabricMixinFixture.game(WRAPPER, false);
		String update = target.methods.stream().filter(m -> m.name.equals("update")).map(m -> m.name + m.desc).findFirst().orElseThrow();
		ClassNode mixin = mixin("org/example/display/mixin/NoHeldPartsMixin", WRAPPER, update, "skipHeldParts",
				"(L" + MODEL + ";Lnet/minecraft/util/RandomSource;Ljava/util/List;)V", "L" + MODEL + ";collectParts" + SHORT);
		assertEquals(1, FabricClientMixinAnchors.adapt(mixin, name -> name.equals(WRAPPER) ? target : null));
		assertEquals("L" + MODEL + ";collectParts" + WIDE, MixinFit.value(StagedFabricMixinFixture.at(mixin, "skipHeldParts"), "target"));
	}

	/** RED: vanilla's body still makes the named call, so the redirect binds as written. */
	@Test void aHostThatStillMakesTheNamedCallIsLeftAlone() throws Exception {
		ClassNode vanilla = StagedFabricMixinFixture.game(TESSELATOR, true);
		ClassNode mixin = mixin("org/example/xray/mixin/HideBlocksMixin", TESSELATOR, "tesselateBlock", "hideParts",
				"(L" + MODEL + ";Lnet/minecraft/util/RandomSource;Ljava/util/List;)V", "L" + MODEL + ";collectParts" + SHORT);
		byte[] before = StagedFabricMixinFixture.bytes(mixin);
		assertEquals(0, FabricClientMixinAnchors.adapt(mixin, name -> name.equals(TESSELATOR) ? vanilla : null));
		assertArrayEquals(before, StagedFabricMixinFixture.bytes(mixin));
	}

	/** RED: a handler that does something with the arguments describes the call; it is not an empty redirect. */
	@Test void aRedirectWithABodyIsLeftAlone() throws Exception {
		ClassNode target = StagedFabricMixinFixture.game(TESSELATOR, false);
		ClassNode mixin = mixin("org/example/xray/mixin/HideBlocksMixin", TESSELATOR, "tesselateBlock", "hideParts",
				"(L" + MODEL + ";Lnet/minecraft/util/RandomSource;Ljava/util/List;)V", "L" + MODEL + ";collectParts" + SHORT);
		MethodNode handler = StagedFabricMixinFixture.method(mixin, "hideParts");
		handler.instructions.insert(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "clear", "()V", true));
		handler.instructions.insert(new VarInsnNode(Opcodes.ALOAD, 3));
		byte[] before = StagedFabricMixinFixture.bytes(mixin);
		assertEquals(0, FabricClientMixinAnchors.adapt(mixin, name -> name.equals(TESSELATOR) ? target : null));
		assertArrayEquals(before, StagedFabricMixinFixture.bytes(mixin));
	}

	/** RED: two widened calls in one body, or a call with a value, are not the one skipped call the redirect meant. */
	@Test void twoWidenedCallsOrAValuedCallAreLeftAlone() throws Exception {
		ClassNode twice = host("org/example/Host", "draw", "collect", "(Ljava/lang/Object;Ljava/lang/String;)V", 2);
		ClassNode mixin = mixin("org/example/mixin/SkipMixin", twice.name, "draw", "skip", "(Lorg/example/Model;Ljava/lang/String;)V",
				"Lorg/example/Model;collect(Ljava/lang/String;)V");
		byte[] before = StagedFabricMixinFixture.bytes(mixin);
		assertEquals(0, FabricClientMixinAnchors.adapt(mixin, name -> twice));
		assertArrayEquals(before, StagedFabricMixinFixture.bytes(mixin));
		ClassNode once = host("org/example/Host", "draw", "collect", "(Ljava/lang/Object;Ljava/lang/String;)V", 1);
		assertEquals(1, FabricClientMixinAnchors.adapt(mixin, name -> once), "control: one widened call moves");
		ClassNode valued = host("org/example/Host", "draw", "collect", "(Ljava/lang/Object;Ljava/lang/String;)I", 1);
		ClassNode returning = mixin("org/example/mixin/SkipMixin", valued.name, "draw", "skip", "(Lorg/example/Model;Ljava/lang/String;)I",
				"Lorg/example/Model;collect(Ljava/lang/String;)I");
		StagedFabricMixinFixture.method(returning, "skip").instructions.insert(new InsnNode(Opcodes.ICONST_0));
		assertEquals(0, FabricClientMixinAnchors.adapt(returning, name -> valued));
	}

	/** A host whose {@code draw()} makes {@code calls} invocations of {@code Model.name(desc)}. */
	private static ClassNode host(String name, String method, String callee, String desc, int calls) {
		ClassNode host = new ClassNode();
		host.version = Opcodes.V21;
		host.name = name;
		host.superName = "java/lang/Object";
		MethodNode draw = new MethodNode(Opcodes.ACC_PUBLIC, method, "()V", null, null);
		for (int i = 0; i < calls; i++) {
			draw.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
			draw.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
			draw.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
			draw.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "org/example/Model", callee, desc, false));
			if (Type.getReturnType(desc).getSort() != Type.VOID) draw.instructions.add(new InsnNode(Opcodes.POP));
		}
		draw.instructions.add(new InsnNode(Opcodes.RETURN));
		host.methods.add(draw);
		return host;
	}

	private static ClassNode mixin(String name, String target, String selector, String handlerName, String handlerDesc, String point) {
		ClassNode mixin = new ClassNode();
		mixin.version = Opcodes.V21;
		mixin.access = Opcodes.ACC_ABSTRACT;
		mixin.name = name;
		mixin.superName = "java/lang/Object";
		AnnotationNode type = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		type.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType(target)))));
		mixin.invisibleAnnotations = new ArrayList<>(List.of(type));
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "INVOKE", "target", point));
		AnnotationNode redirect = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Redirect;");
		redirect.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of(selector)), "at", at));
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, handlerName, handlerDesc, null, null);
		handler.visibleAnnotations = new ArrayList<>(List.of(redirect));
		handler.instructions.add(new InsnNode(Type.getReturnType(handlerDesc).getOpcode(Opcodes.IRETURN)));
		handler.maxStack = 1;
		handler.maxLocals = Type.getArgumentsAndReturnSizes(handlerDesc) >> 2;
		mixin.methods.add(handler);
		return mixin;
	}
}
