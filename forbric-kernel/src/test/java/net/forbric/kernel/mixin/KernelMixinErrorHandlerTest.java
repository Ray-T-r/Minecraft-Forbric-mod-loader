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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinErrorHandler;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;

/** The handler over a proxied IMixinInfo: the owner is marked, the action is never changed, and the bootstrap registers it. */
@org.junit.jupiter.api.parallel.ResourceLock("ModCatalog")
class KernelMixinErrorHandlerTest {
	@org.junit.jupiter.api.BeforeEach
	@org.junit.jupiter.api.AfterEach
	void clearCompatibilityEvidence() { net.forbric.api.CompatibilityFindings.reset(); SupersededMixins.reset(); }

	private List<ModCatalog.Entry> previous;

	@Test
	void missingMixinMetadataKeepsEvidenceWithoutInventingAModOrBreakingTheErrorHandler() {
		var action = new KernelMixinErrorHandler().onApplyError("example.Target", new IllegalStateException(), null,
				IMixinErrorHandler.ErrorAction.WARN);
		assertSame(IMixinErrorHandler.ErrorAction.WARN, action);
		assertTrue(ModCatalog.failures().isEmpty());
		assertEquals(1, net.forbric.api.CompatibilityFindings.all().size());
	}

	@Test
	void theOriginalRequiredDeclarationSurvivesTheConfigsRelaxation() {
		MixinCompatibility.rememberOriginalConfig("required.mixins.json", "{\"required\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		assertTrue(MixinCompatibility.required("required.mixins.json", false),
				"the runtime config was relaxed, but the player's required-feature policy still needs the original declaration");
		MixinCompatibility.rememberOriginalConfig("optional.mixins.json", "{\"required\":false}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		assertTrue(!MixinCompatibility.required("optional.mixins.json", false));
		MixinCompatibility.reset();
		assertTrue(!MixinCompatibility.required("required.mixins.json", false), "a new launch must not inherit the old declaration");
	}

	@BeforeEach
	void publish() {
		previous = ModCatalog.everything();
		MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned("x.mixins.json", "xmod", Ecosystem.FABRIC)));
		ModCatalog.publish(List.of(new ModCatalog.Entry(Ecosystem.FABRIC, "xmod", "X", "1", "", List.of(), "x.jar", "", "")));
	}

	@AfterEach
	void forget() {
		MixinConfigOwners.reset();
		ModCatalog.publish(previous);
	}

	@Test
	void anApplyFailureMarksTheOwningMod() {
		IMixinErrorHandler handler = new KernelMixinErrorHandler();
		IMixinErrorHandler.ErrorAction out = handler.onApplyError("net.minecraft.Foo", new RuntimeException("boom"),
				info("x.mixins.json", "a.b.FooMixin"), IMixinErrorHandler.ErrorAction.WARN);
		assertSame(IMixinErrorHandler.ErrorAction.WARN, out);
		assertEquals(1, ModCatalog.failures().size());
		ModCatalog.Entry xmod = ModCatalog.failures().get(0);
		assertEquals("xmod", xmod.modId());
		assertEquals(ModCatalog.Status.DEGRADED, xmod.status());
		assertTrue(xmod.statusDetail().contains("FooMixin") && xmod.statusDetail().contains("net.minecraft.Foo"), xmod.statusDetail());
		var finding = net.forbric.api.CompatibilityFindings.confirmedRequired().getFirst();
		assertEquals("mixin:x.mixins.json", finding.source());
		assertTrue(finding.evidence().stream().anyMatch(e -> e.contains("net.minecraft.Foo")));
	}

	@Test
	void aPrepareFailureMarksTheOwningModToo() {
		IMixinInfo info = info("x.mixins.json", "a.b.BarMixin");
		new KernelMixinErrorHandler().onPrepareError(info.getConfig(), new IllegalStateException(), info, IMixinErrorHandler.ErrorAction.ERROR);
		assertEquals(1, ModCatalog.failures().size());
		assertTrue(ModCatalog.failures().get(0).statusDetail().contains("BarMixin"));
	}

	/**
	 * A mixin the kernel has taken over is not a loss once the takeover is SEEN, so its mod is then not marked.
	 *
	 * <p>Seen, not named: the failure is recorded like any other and resolved only when the class carrying the
	 * replacement is defined with the replacement in its bytes. And the action stays unchanged throughout —
	 * suppressing the MARK must never suppress Mixin's own decision, which is what keeps a required config erroring.
	 */
	@Test
	void aMixinTheKernelSupersedesIsUnmarkedOnlyOnceItsReplacementIsDefined() {
		String superseded = SupersededMixins.all().keySet().iterator().next();
		MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned("s.mixins.json", "xmod", Ecosystem.FABRIC)));

		IMixinErrorHandler.ErrorAction out = new KernelMixinErrorHandler().onApplyError("net.minecraft.Foo",
				new RuntimeException("boom"), info("s.mixins.json", superseded),
				IMixinErrorHandler.ErrorAction.WARN);

		assertSame(IMixinErrorHandler.ErrorAction.WARN, out, "attribution never changes Mixin's own decision");
		assertEquals(1, ModCatalog.failures().size(), "a table entry is a claim; nothing has shown the repair yet");
		SupersededMixins.observeDefinition(CONDITIONAL_OPS, conditionalOps(false));
		assertEquals(1, ModCatalog.failures().size(), "ConditionalOps defined without the wrap proves nothing");
		SupersededMixins.observeDefinition(CONDITIONAL_OPS, conditionalOps(true));
		assertTrue(ModCatalog.failures().isEmpty(),
				"the kernel does this mixin's job itself, so marking its mod reports a loss that did not happen");
		assertTrue(net.forbric.api.CompatibilityFindings.confirmedRequired().isEmpty());
	}

	/** A replacement proved before the mixin fails resolves the failure as it is recorded. */
	@Test
	void aReplacementDefinedBeforeTheFailureResolvesItOnArrival() {
		String superseded = SupersededMixins.all().keySet().iterator().next();
		MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned("s.mixins.json", "xmod", Ecosystem.FABRIC)));
		SupersededMixins.observeDefinition(CONDITIONAL_OPS, conditionalOps(true));
		new KernelMixinErrorHandler().onApplyError("net.minecraft.Foo", new RuntimeException("boom"),
				info("s.mixins.json", superseded), IMixinErrorHandler.ErrorAction.WARN);
		assertTrue(ModCatalog.failures().isEmpty());
	}

	/** -Dforbric.fabricConditions=off leaves the wrap in place but makes it do nothing: the loss is real again. */
	@Test
	void aSwitchedOffReplacementDoesNotResolveTheLossEvenWhenItsBytesAreThere() {
		String previous = System.getProperty("forbric.fabricConditions");
		try {
			System.setProperty("forbric.fabricConditions", "off");
			String superseded = SupersededMixins.all().keySet().iterator().next();
			MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned("s.mixins.json", "xmod", Ecosystem.FABRIC)));
			new KernelMixinErrorHandler().onApplyError("net.minecraft.Foo", new RuntimeException("boom"),
					info("s.mixins.json", superseded), IMixinErrorHandler.ErrorAction.WARN);
			SupersededMixins.observeDefinition(CONDITIONAL_OPS, conditionalOps(true));
			assertEquals(1, ModCatalog.failures().size());
			assertEquals(1, net.forbric.api.CompatibilityFindings.confirmedRequired().size());
		} finally {
			if (previous == null) System.clearProperty("forbric.fabricConditions");
			else System.setProperty("forbric.fabricConditions", previous);
		}
	}

	private static final String CONDITIONAL_OPS = "net.neoforged.neoforge.common.conditions.ConditionalOps";

	/** ConditionalOps' codec factory, with or without the kernel's wrap before its one exit. */
	private static byte[] conditionalOps(boolean wrapped) {
		org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
		writer.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC,
				CONDITIONAL_OPS.replace('.', '/'), null, "java/lang/Object", null);
		var factory = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
				"createConditionalCodecWithConditions",
				"(Lcom/mojang/serialization/Codec;Ljava/lang/String;)Lcom/mojang/serialization/Codec;", null, null);
		factory.visitCode();
		factory.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
		if (wrapped) factory.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC,
				"net/forbric/kernel/runtime/KernelFabricConditions", "alsoAskFabric",
				"(Lcom/mojang/serialization/Codec;)Lcom/mojang/serialization/Codec;", false);
		factory.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
		factory.visitMaxs(1, 2);
		factory.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	/** With the switch off it is an ordinary failure again — which is how the claim in each entry gets checked. */
	@Test
	void theSupersededSwitchTurnsThemBackIntoOrdinaryFailures() {
		String previousValue = System.getProperty(SupersededMixins.PROPERTY);
		try {
			System.setProperty(SupersededMixins.PROPERTY, "off");
			String superseded = SupersededMixins.all().keySet().iterator().next();
			MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned("s.mixins.json", "xmod", Ecosystem.FABRIC)));

			new KernelMixinErrorHandler().onApplyError("net.minecraft.Foo", new RuntimeException("boom"),
					info("s.mixins.json", superseded), IMixinErrorHandler.ErrorAction.WARN);

			assertEquals(1, ModCatalog.failures().size());
			assertTrue(ModCatalog.failures().get(0).statusDetail().contains(superseded));
		} finally {
			if (previousValue == null) System.clearProperty(SupersededMixins.PROPERTY);
			else System.setProperty(SupersededMixins.PROPERTY, previousValue);
		}
	}

	/**
	 * A reason the kernel worked out while READING the mixin reaches the row the player sees.
	 *
	 * <p>Two different moments and two different classes: the diagnosis is made when the mixin is read, the mark
	 * when it fails to apply. Losing it in between leaves the load report saying "InvalidInjectionException",
	 * which is true and tells nobody anything.
	 */
	@Test
	void aReasonTheKernelWorkedOutReachesTheRow() {
		net.forbric.kernel.transform.DuplicateLambdaPruneInjector.recordDroppedForTest(
				"net/example/Target", "lambda$doThing$0", "(I)V");
		org.objectweb.asm.tree.ClassNode mixin = new org.objectweb.asm.tree.ClassNode();
		mixin.name = "a/b/ThingMixin";
		org.objectweb.asm.tree.AnnotationNode at =
				new org.objectweb.asm.tree.AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		at.values = new java.util.ArrayList<>(List.of("value", new java.util.ArrayList<>(
				List.of(org.objectweb.asm.Type.getObjectType("net/example/Target")))));
		mixin.visibleAnnotations = new java.util.ArrayList<>(List.of(at));
		org.objectweb.asm.tree.MethodNode handler = new org.objectweb.asm.tree.MethodNode(
				org.objectweb.asm.Opcodes.ASM9, org.objectweb.asm.Opcodes.ACC_PRIVATE, "onThing",
				"(ILorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
		org.objectweb.asm.tree.AnnotationNode inject =
				new org.objectweb.asm.tree.AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
		inject.values = new java.util.ArrayList<>(List.of("method",
				new java.util.ArrayList<>(List.of("lambda$doThing$0"))));
		handler.visibleAnnotations = new java.util.ArrayList<>(List.of(inject));
		mixin.methods = new java.util.ArrayList<>(List.of(handler));

		org.objectweb.asm.tree.ClassNode target = new org.objectweb.asm.tree.ClassNode();
		target.name = "net/example/Target";
		target.methods = new java.util.ArrayList<>(List.of(new org.objectweb.asm.tree.MethodNode(
				org.objectweb.asm.Opcodes.ASM9, org.objectweb.asm.Opcodes.ACC_PRIVATE, "lambda$doThing$0",
				"(Ljava/lang/String;)V", null, null)));
		assertEquals(1, MixinOverloadPin.pin(mixin, name -> target.name.equals(name) ? target : null));

		MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned("t.mixins.json", "xmod", Ecosystem.FABRIC)));
		new KernelMixinErrorHandler().onApplyError("net.example.Target", new RuntimeException(),
				info("t.mixins.json", "a.b.ThingMixin"), IMixinErrorHandler.ErrorAction.WARN);

		assertEquals(1, ModCatalog.failures().size());
		assertTrue(ModCatalog.failures().get(0).statusDetail().contains("the byte merge did not keep"),
				ModCatalog.failures().get(0).statusDetail());
	}

	@Test
	void theActionIsNeverChanged() {
		IMixinErrorHandler handler = new KernelMixinErrorHandler();
		for (IMixinErrorHandler.ErrorAction in : IMixinErrorHandler.ErrorAction.values()) {
			assertSame(in, handler.onApplyError("t", new RuntimeException(), info("x.mixins.json", "M"), in));
			assertSame(in, handler.onPrepareError(info("x.mixins.json", "M").getConfig(), new RuntimeException(), info("x.mixins.json", "M"), in));
		}
	}

	@Test
	void anUnownedConfigMarksNobody() {
		new KernelMixinErrorHandler().onApplyError("t", new RuntimeException(), info("nobody.mixins.json", "M"), IMixinErrorHandler.ErrorAction.WARN);
		assertTrue(ModCatalog.failures().isEmpty());
	}

	@Test
	void theBootstrapRegistersTheHandlerByItsRealName() throws Exception {
		assertEquals(KernelMixinErrorHandler.class.getName(), KernelMixinErrorHandler.NAME);
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main", "net", "forbric", "kernel", "mixin",
				"KernelMixinBootstrap.class").normalize();
		assertTrue(Files.isRegularFile(compiled), "the bootstrap is compiled");
		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(compiled)).accept(node, 0);
		boolean registered = false;
		for (MethodNode m : node.methods) {
			if (!m.name.equals("init")) continue;
			for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof LdcInsnNode ldc && KernelMixinErrorHandler.NAME.equals(ldc.cst)) {
					AbstractInsnNode next = insn.getNext();
					while (next != null && next.getOpcode() < 0) next = next.getNext();
					registered |= next instanceof MethodInsnNode call && "org/spongepowered/asm/mixin/Mixins".equals(call.owner)
							&& "registerErrorHandlerClass".equals(call.name);
				}
			}
		}
		assertTrue(registered, "init() hands the handler's name to Mixins.registerErrorHandlerClass");
	}

	private static IMixinInfo info(String configName, String className) {
		IMixinConfig config = (IMixinConfig) Proxy.newProxyInstance(KernelMixinErrorHandlerTest.class.getClassLoader(),
				new Class<?>[] { IMixinConfig.class }, (proxy, method, args) -> switch (method.getName()) {
					case "getName" -> configName;
					case "isRequired" -> true;
					case "toString" -> configName;
					default -> throw new UnsupportedOperationException(method.getName());
				});
		return (IMixinInfo) Proxy.newProxyInstance(KernelMixinErrorHandlerTest.class.getClassLoader(),
				new Class<?>[] { IMixinInfo.class }, (proxy, method, args) -> switch (method.getName()) {
					case "getConfig" -> config;
					case "getClassName" -> className;
					case "getName" -> className.substring(className.lastIndexOf('.') + 1);
					case "toString" -> className;
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}
}
