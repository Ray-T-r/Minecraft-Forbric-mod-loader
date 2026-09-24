package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.api.Ecosystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * An injector of a mixin the kernel supersedes is resolved with the mixin, whichever is defined first: the target
 * (where the injector's miss is seen) or the class carrying the replacement. The popular pack's dedicated server
 * stopped on the first order: the mixin-level row said the job was done, its skipData injector still said lost.
 */
class SupersededInjectorTest {
	private static final String CONFIG = "fabric-resource-conditions-api-v1.mixins.json", TARGET = "game.Target",
			CONDITIONAL_OPS = "net.neoforged.neoforge.common.conditions.ConditionalOps";
	private static final String MIXIN = SupersededMixins.all().keySet().iterator().next();

	@BeforeEach @AfterEach void reset() {
		MixinCompatibility.reset(); CompatibilityFindings.reset(); SupersededMixins.reset();
		MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned(CONFIG, "fabric-resource-conditions-api-v1", Ecosystem.FABRIC)));
		String json = "{\"required\":true,\"package\":\"" + MIXIN.substring(0, MIXIN.lastIndexOf('.')) + "\",\"mixins\":[\""
				+ MIXIN.substring(MIXIN.lastIndexOf('.') + 1) + "\"],\"injectors\":{\"defaultRequire\":1}}";
		MixinCompatibility.rememberOriginalConfig(CONFIG, json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		FinalMixinApplications.remember(mixin());
	}

	@Test void theReplacementSeenAfterTheMissResolvesTheInjector() {
		observeMissingInjector();
		assertEquals(1, CompatibilityFindings.confirmedRequired().size(), "not yet proved: recorded as a loss");
		SupersededMixins.observeDefinition(CONDITIONAL_OPS, conditionalOps(true));
		assertTrue(CompatibilityFindings.confirmedRequired().isEmpty(), CompatibilityFindings.all().toString());
	}

	@Test void theReplacementSeenBeforeTheMissResolvesTheInjectorOnArrival() {
		SupersededMixins.observeDefinition(CONDITIONAL_OPS, conditionalOps(true));
		observeMissingInjector();
		assertTrue(CompatibilityFindings.confirmedRequired().isEmpty(), CompatibilityFindings.all().toString());
		assertTrue(CompatibilityFindings.all().stream().anyMatch(f -> f.id().startsWith("mixin-injector:")
				&& f.confidence() == CompatibilityFinding.Confidence.RESOLVED));
	}

	@Test void aReplacementThatIsNotReallyThereLeavesTheLoss() {
		observeMissingInjector();
		SupersededMixins.observeDefinition(CONDITIONAL_OPS, conditionalOps(false));
		assertEquals(1, CompatibilityFindings.confirmedRequired().size());
	}

	private void observeMissingInjector() {
		ClassNode target = new ClassNode(); target.version = Opcodes.V21; target.name = TARGET.replace('.', '/');
		target.superName = "java/lang/Object"; target.access = Opcodes.ACC_PUBLIC;
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "handler$000$skipData", "()V", null, null);
		handler.instructions.add(new InsnNode(Opcodes.RETURN));
		handler.visibleAnnotations = List.of(annotation("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;", "mixin", MIXIN));
		target.methods.add(handler);
		ClassWriter writer = new ClassWriter(0); target.accept(writer);
		FinalMixinApplications.observe(TARGET, writer.toByteArray(), (mixin, name, desc) -> List.of(new FinalMixinApplications.Renamed("handler$000$skipData", desc)));
	}

	private static ClassNode mixin() {
		ClassNode node = new ClassNode(); node.name = MIXIN.replace('.', '/');
		node.visibleAnnotations = List.of(annotation("Lorg/spongepowered/asm/mixin/Mixin;", "targets", List.of(TARGET)));
		MethodNode skipData = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "skipData", "()V", null, null);
		skipData.visibleAnnotations = new ArrayList<>(List.of(annotation("Lorg/spongepowered/asm/mixin/injection/Inject;", "require", -1)));
		node.methods.add(skipData);
		return node;
	}

	private static AnnotationNode annotation(String desc, String key, Object value) {
		AnnotationNode a = new AnnotationNode(desc); a.values = new ArrayList<>(List.of(key, value)); return a;
	}

	/** ConditionalOps' codec factory, with or without the kernel's wrap before its one exit. */
	private static byte[] conditionalOps(boolean wrapped) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, CONDITIONAL_OPS.replace('.', '/'), null, "java/lang/Object", null);
		var factory = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "createConditionalCodecWithConditions",
				"(Lcom/mojang/serialization/Codec;Ljava/lang/String;)Lcom/mojang/serialization/Codec;", null, null);
		factory.visitCode(); factory.visitVarInsn(Opcodes.ALOAD, 0);
		if (wrapped) factory.visitMethodInsn(Opcodes.INVOKESTATIC, "net/forbric/kernel/runtime/KernelFabricConditions", "alsoAskFabric",
				"(Lcom/mojang/serialization/Codec;)Lcom/mojang/serialization/Codec;", false);
		factory.visitInsn(Opcodes.ARETURN); factory.visitMaxs(1, 2); factory.visitEnd(); writer.visitEnd();
		return writer.toByteArray();
	}
}
