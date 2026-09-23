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
	void clearCompatibilityEvidence() { net.forbric.api.CompatibilityFindings.reset(); }

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
	 * A mixin the kernel has taken over is not a loss, so its mod is not marked — when the takeover is really
	 * there: the transforming loader serves ConditionalOps with its one factory exit wrapped and every other
	 * factory funnelling into it.
	 *
	 * <p>Both halves: no row, and the action still unchanged — suppressing the MARK must never suppress Mixin's
	 * own decision about the failure, which is what keeps a required config erroring.
	 */
	@Test
	void aMixinTheKernelSupersedesDoesNotMarkItsMod(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
		String superseded = SupersededMixins.all().keySet().iterator().next();
		MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned("s.mixins.json", "xmod", Ecosystem.FABRIC)));

		IMixinErrorHandler.ErrorAction out = withConditionalOps(dir, conditionalOps(true), () -> new KernelMixinErrorHandler()
				.onApplyError("net.minecraft.Foo", new RuntimeException("boom"), info("s.mixins.json", superseded),
						IMixinErrorHandler.ErrorAction.WARN));

		assertSame(IMixinErrorHandler.ErrorAction.WARN, out, "attribution never changes Mixin's own decision");
		assertTrue(ModCatalog.failures().isEmpty(),
				"the kernel does this mixin's job itself, so marking its mod reports a loss that did not happen");
		assertEquals(net.forbric.api.CompatibilityFinding.Confidence.RESOLVED, finding(superseded).confidence());
	}

	/**
	 * The table's name is a claim. With nothing proving the replacement — no witness class, the wrap missing, or
	 * the evaluator switched to a pass-through — the failure is the loss it looks like.
	 */
	@Test
	void aSupersededMixinWithoutItsStructuralWitnessIsALoss(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
		String superseded = SupersededMixins.all().keySet().iterator().next();
		MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned("s.mixins.json", "xmod", Ecosystem.FABRIC)));

		new KernelMixinErrorHandler().onApplyError("net.minecraft.Foo", new RuntimeException("boom"),
				info("s.mixins.json", superseded), IMixinErrorHandler.ErrorAction.WARN);
		assertEquals(1, ModCatalog.failures().size(), "no witness class at all");
		assertTrue(finding(superseded).confirmedRequired());

		net.forbric.api.CompatibilityFindings.reset();
		MixinCompatibility.reset();
		withConditionalOps(dir.resolve("unwrapped"), conditionalOps(false), () -> new KernelMixinErrorHandler().onApplyError(
				"net.minecraft.Foo", new RuntimeException("boom"), info("s.mixins.json", superseded),
				IMixinErrorHandler.ErrorAction.WARN));
		assertTrue(finding(superseded).confirmedRequired(), "the repair stood down, so the name proves nothing");

		net.forbric.api.CompatibilityFindings.reset();
		MixinCompatibility.reset();
		System.setProperty("forbric.fabricConditions", "off");
		try {
			withConditionalOps(dir.resolve("switched-off"), conditionalOps(true), () -> new KernelMixinErrorHandler()
					.onApplyError("net.minecraft.Foo", new RuntimeException("boom"), info("s.mixins.json", superseded),
							IMixinErrorHandler.ErrorAction.WARN));
		} finally {
			System.clearProperty("forbric.fabricConditions");
		}
		assertTrue(finding(superseded).confirmedRequired(), "the wrap is there but passes everything through");
	}

	/** The class that actually runs has the last word, in both directions. */
	@Test
	void theWitnessesFinalDefinitionSettlesTheFailureAgain(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
		String superseded = SupersededMixins.all().keySet().iterator().next();
		String ops = MixinEquivalentImplementations.CONDITIONAL_OPS.replace('/', '.');
		MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned("s.mixins.json", "xmod", Ecosystem.FABRIC)));
		try {
			new KernelMixinErrorHandler().onApplyError("net.minecraft.Foo", new RuntimeException("boom"),
					info("s.mixins.json", superseded), IMixinErrorHandler.ErrorAction.WARN);
			assertTrue(finding(superseded).confirmedRequired());
			FinalMixinApplications.onClassDefined(ops, conditionalOps(true));
			assertEquals(net.forbric.api.CompatibilityFinding.Confidence.RESOLVED, finding(superseded).confidence(),
					"the defined ConditionalOps carries the replacement");

			net.forbric.api.CompatibilityFindings.reset();
			MixinCompatibility.reset();
			withConditionalOps(dir, conditionalOps(true), () -> new KernelMixinErrorHandler().onApplyError(
					"net.minecraft.Foo", new RuntimeException("boom"), info("s.mixins.json", superseded),
					IMixinErrorHandler.ErrorAction.WARN));
			assertEquals(net.forbric.api.CompatibilityFinding.Confidence.RESOLVED, finding(superseded).confidence());
			FinalMixinApplications.onClassDefined(ops, conditionalOps(false));
			assertTrue(finding(superseded).confirmedRequired(),
					"a proof read from the transformed bytes may not outlive the class that actually runs");
		} finally {
			MixinCompatibility.reset();
		}
	}

	private static net.forbric.api.CompatibilityFinding finding(String mixin) {
		return net.forbric.api.CompatibilityFindings.all().stream()
				.filter(f -> f.id().equals(MixinCompatibility.id("s.mixins.json", mixin))).findFirst().orElseThrow();
	}

	/**
	 * NeoForge's ConditionalOps reduced to its factory shape: the funnel, and two public factories that reach it.
	 * {@code wrapped} is what {@code letFabricResourceConditionsDecide} leaves behind.
	 */
	private static byte[] conditionalOps(boolean wrapped) {
		String ops = MixinEquivalentImplementations.CONDITIONAL_OPS;
		String codec = "Lcom/mojang/serialization/Codec;";
		org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
		cw.visit(org.objectweb.asm.Opcodes.V21, org.objectweb.asm.Opcodes.ACC_PUBLIC, ops, null, "java/lang/Object", null);
		int access = org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC;
		var funnel = cw.visitMethod(access, "createConditionalCodecWithConditions", "(" + codec + "Ljava/lang/String;)" + codec, null, null);
		funnel.visitCode();
		funnel.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
		if (wrapped) funnel.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC, "net/forbric/kernel/runtime/KernelFabricConditions",
				"alsoAskFabric", "(" + codec + ")" + codec, false);
		funnel.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
		funnel.visitMaxs(1, 2);
		funnel.visitEnd();
		for (String[] entry : new String[][] {{"createConditionalCodecWithConditions", "(" + codec + ")" + codec},
				{"createConditionalCodec", "(" + codec + ")" + codec}}) {
			var m = cw.visitMethod(access, entry[0], entry[1], null, null);
			m.visitCode();
			m.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
			m.visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL);
			m.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC, ops, "createConditionalCodecWithConditions",
					"(" + codec + "Ljava/lang/String;)" + codec, false);
			m.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
			m.visitMaxs(2, 1);
			m.visitEnd();
		}
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** Runs {@code action} with the transforming loader serving {@code bytes} as ConditionalOps, as in a game. */
	private static <T> T withConditionalOps(Path dir, byte[] bytes, java.util.concurrent.Callable<T> action) throws Exception {
		Files.createDirectories(dir);
		Path jar = dir.resolve("neoforge.jar");
		try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
			out.putNextEntry(new java.util.jar.JarEntry(MixinEquivalentImplementations.CONDITIONAL_OPS + ".class"));
			out.write(bytes);
			out.closeEntry();
		}
		try (var loader = new net.forbric.kernel.classloading.ForbricClassLoader(new java.net.URL[] {jar.toUri().toURL()},
				KernelMixinErrorHandlerTest.class.getClassLoader())) {
			ForbricMixinService.bind(loader, net.fabricmc.api.EnvType.CLIENT);
			return action.call();
		} finally {
			ForbricMixinService.bind(null, net.fabricmc.api.EnvType.SERVER);
		}
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
