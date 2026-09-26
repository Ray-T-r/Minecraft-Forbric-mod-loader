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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * A {@code @Shadow} of a member another mixin adds is not a miss when that mixin is applied first.
 *
 * <p>What paid for it: moreculling's {@code BlockModelRenderState_fabricCullMixin} (priority 1200) shadows
 * {@code mesh}, a {@code @Unique} field fabric-renderer-api-v1's {@code BlockModelRenderStateMixin} (priority 1000)
 * adds. Every client boot listed it under possible problems as "missing @Shadow field BlockModelRenderState.mesh".
 */
class MixinAddedMembersTest {
	private static final String TARGET = "net/example/RenderState";
	private static final String MESH = "Lnet/example/Mesh;";

	@BeforeEach
	void clear() {
		MixinAddedMembers.reset();
	}

	@AfterEach
	void restore() {
		System.clearProperty(MixinAddedMembers.PROPERTY);
		ForbricMixinService.setGuestConfigs(List.of());
		MixinAddedMembers.reset();
	}

	@Test
	void aShadowOfAFieldAMixinAppliedFirstAddsResolves() {
		Stage stage = new Stage();
		stage.config("renderer.mixins.json", "renderer.mixin", mixin("renderer/mixin/Adder", null).uniqueField("mesh"));
		stage.config("culling.mixins.json", "culling.mixin", mixin("culling/mixin/Culler", 1200).shadowField("parts")
				.shadowField("mesh"));
		ForbricMixinService.setGuestConfigs(stage.names());

		MixinFit.Result withAdded = judge(stage, "culling.mixins.json", "Culler");
		assertEquals(MixinFit.Verdict.FIT, withAdded.verdict(), withAdded.reason());

		MixinFit.Result alone = MixinFit.evaluate(stage.apply("culling/mixin/Culler.class"), stage);
		assertEquals(MixinFit.Verdict.PARTIAL, alone.verdict(), "the merged class alone has no mesh");
		assertEquals(List.of("@Shadow field RenderState.mesh"), alone.unresolved());
	}

	@Test
	void aMixinAppliedAfterItDoesNotCount() {
		// Priority 900 applies before the adder's default 1000: the field is not there yet, and Mixin fails the shadow.
		Stage stage = new Stage();
		stage.config("renderer.mixins.json", "renderer.mixin", mixin("renderer/mixin/Adder", null).uniqueField("mesh"));
		stage.config("culling.mixins.json", "culling.mixin", mixin("culling/mixin/Culler", 900).shadowField("parts")
				.shadowField("mesh"));
		ForbricMixinService.setGuestConfigs(stage.names());

		assertEquals(MixinFit.Verdict.PARTIAL, judge(stage, "culling.mixins.json", "Culler").verdict());
	}

	@Test
	void atEqualPriorityTheConfigsOrderDecides() {
		Stage stage = new Stage();
		stage.config("culling.mixins.json", "culling.mixin", mixin("culling/mixin/Culler", null).shadowField("parts")
				.shadowField("mesh"));
		stage.config("renderer.mixins.json", "renderer.mixin", mixin("renderer/mixin/Adder", null).uniqueField("mesh"));
		ForbricMixinService.setGuestConfigs(stage.names());
		assertEquals(MixinFit.Verdict.PARTIAL, judge(stage, "culling.mixins.json", "Culler").verdict(),
				"registered after the shadowing config, so created and applied after it");

		// The config's own priority moves it ahead, as MixinConfig.compareTo does.
		stage.raw("renderer.mixins.json", "{\"package\": \"renderer.mixin\", \"priority\": 500, \"mixins\": [\"Adder\"]}");
		MixinAddedMembers.reset();
		assertEquals(MixinFit.Verdict.FIT, judge(stage, "culling.mixins.json", "Culler").verdict());
	}

	@Test
	void theConfigsMixinPriorityIsTheDefaultForItsMixins() {
		Stage stage = new Stage();
		stage.config("culling.mixins.json", "culling.mixin", mixin("culling/mixin/Culler", null).shadowField("parts")
				.shadowField("mesh"));
		stage.raw("culling.mixins.json", "{\"package\": \"culling.mixin\", \"mixinPriority\": 1100, \"mixins\": [\"Culler\"]}");
		stage.config("renderer.mixins.json", "renderer.mixin", mixin("renderer/mixin/Adder", null).uniqueField("mesh"));
		ForbricMixinService.setGuestConfigs(stage.names());

		assertEquals(MixinFit.Verdict.FIT, judge(stage, "culling.mixins.json", "Culler").verdict());
	}

	@Test
	void withinOneConfigTheEntryOrderDecides() {
		Stage stage = new Stage();
		stage.put("pack/mixin/Adder.class", mixin("pack/mixin/Adder", null).uniqueField("mesh").bytes());
		stage.put("pack/mixin/Culler.class", mixin("pack/mixin/Culler", null).shadowField("parts").shadowField("mesh").bytes());
		stage.raw("pack.mixins.json", "{\"package\": \"pack.mixin\", \"mixins\": [\"Adder\", \"Culler\"]}");
		ForbricMixinService.setGuestConfigs(stage.names());
		assertEquals(MixinFit.Verdict.FIT, judge(stage, "pack.mixins.json", "Culler").verdict());

		stage.raw("pack.mixins.json", "{\"package\": \"pack.mixin\", \"mixins\": [\"Culler\", \"Adder\"]}");
		MixinAddedMembers.reset();
		assertEquals(MixinFit.Verdict.PARTIAL, judge(stage, "pack.mixins.json", "Culler").verdict());
	}

	@Test
	void aShadowedMethodAnotherMixinAddsResolvesTooButAHandlerNameDoesNot() {
		Stage stage = new Stage();
		stage.config("renderer.mixins.json", "renderer.mixin", mixin("renderer/mixin/Adder", null)
				.method("renderer$cull", "()Z").injector("onRender"));
		stage.config("culling.mixins.json", "culling.mixin", mixin("culling/mixin/Culler", 1200).shadowField("parts")
				.shadowMethod("renderer$cull", "()Z").shadowMethod("onRender", "()V"));
		ForbricMixinService.setGuestConfigs(stage.names());

		MixinFit.Result r = judge(stage, "culling.mixins.json", "Culler");
		assertEquals(List.of("@Shadow method RenderState.onRender()V"), r.unresolved(),
				"a plain method is added under its name; an injector handler is renamed by Mixin");
	}

	@Test
	void theMixinsOwnMembersDoNotCount() {
		Stage stage = new Stage();
		stage.config("culling.mixins.json", "culling.mixin", mixin("culling/mixin/Culler", null).shadowField("parts")
				.shadowField("mesh").uniqueField("mesh"));
		ForbricMixinService.setGuestConfigs(stage.names());
		assertEquals(MixinFit.Verdict.PARTIAL, judge(stage, "culling.mixins.json", "Culler").verdict());
	}

	@Test
	void anUnregisteredConfigSeesNothingAdded() {
		Stage stage = new Stage();
		stage.config("renderer.mixins.json", "renderer.mixin", mixin("renderer/mixin/Adder", null).uniqueField("mesh"));
		stage.config("culling.mixins.json", "culling.mixin", mixin("culling/mixin/Culler", 1200).shadowField("parts")
				.shadowField("mesh"));
		ForbricMixinService.setGuestConfigs(List.of("renderer.mixins.json"));
		assertEquals(MixinFit.Verdict.PARTIAL, judge(stage, "culling.mixins.json", "Culler").verdict(),
				"no creation order is known for it, so nothing can be said to come first");
	}

	@Test
	void offSwitchJudgesTheMergedClassAlone() {
		Stage stage = new Stage();
		stage.config("renderer.mixins.json", "renderer.mixin", mixin("renderer/mixin/Adder", null).uniqueField("mesh"));
		stage.config("culling.mixins.json", "culling.mixin", mixin("culling/mixin/Culler", 1200).shadowField("parts")
				.shadowField("mesh"));
		ForbricMixinService.setGuestConfigs(stage.names());
		System.setProperty(MixinAddedMembers.PROPERTY, "off");
		assertEquals(MixinFit.Verdict.PARTIAL, judge(stage, "culling.mixins.json", "Culler").verdict());
	}

	@Test
	void theAdapterNoLongerReportsTheShadowAsAPartialMixin() {
		Stage stage = new Stage();
		stage.config("renderer.mixins.json", "renderer.mixin", mixin("renderer/mixin/Adder", null).uniqueField("mesh"));
		stage.config("adapter-culling.mixins.json", "adapter.culling.mixin", mixin("adapter/culling/mixin/Culler", 1200)
				.shadowField("parts").shadowField("mesh"));
		ForbricMixinService.setGuestConfigs(stage.names());

		assertTrue(KernelGuestMixinAdapter.unfitMixins("adapter-culling.mixins.json",
				stage.apply("adapter-culling.mixins.json"), stage).isEmpty());
		assertFalse(KernelGuestMixinAdapter.partiallyApplied().contains("adapter-culling.mixins.json:Culler"),
				KernelGuestMixinAdapter.partiallyApplied().toString());
	}

	@Test
	void registeredConfigsKeepTheirRegistrationOrder() {
		List<String> names = List.of("z.mixins.json", "a.mixins.json", "m.mixins.json", "b.mixins.json",
				"y.mixins.json", "c.mixins.json");
		ForbricMixinService.setGuestConfigs(names);
		assertEquals(names, List.copyOf(ForbricMixinService.registeredConfigNames()),
				"equal-priority mixins apply in the order their configs were created");
	}

	/** The two real mixins against the raw merged BlockModelRenderState. */
	@Test
	void morecullingsShadowOfFabricRenderersMeshResolves() throws Exception {
		Path mods = Path.of(System.getProperty("user.dir"), "build", "compat-inputs", "sweep90", "mods").normalize();
		Path moreculling = mods.resolve("moreculling-fabric-26.2-1.8.1.jar");
		Path fabricApi = mods.resolve("fabric-api-0.161.0+26.2.jar");
		assumeTrue(Files.isRegularFile(moreculling) && Files.isRegularFile(fabricApi), "sweep90 pack absent");
		Function<String, byte[]> merged = MixinFitStagedTest.rawResolver();

		String cullConfig = "moreculling.fabric.mixins.json";
		String cullMixin = "ca/fxco/moreculling/mixin/renderers/BlockModelRenderState_fabricCullMixin.class";
		String rendererConfig = "fabric-renderer-api-v1.mixins.json";
		String rendererMixin = "net/fabricmc/fabric/mixin/client/renderer/block/render/BlockModelRenderStateMixin.class";
		Map<String, byte[]> real = new HashMap<>();
		real.put(cullConfig, MixinFitStagedTest.readFromJar(moreculling, cullConfig));
		real.put(cullMixin, MixinFitStagedTest.readFromJar(moreculling, cullMixin));
		real.put(rendererConfig, MixinFitStagedTest.readFromNestedJar(fabricApi, "fabric-renderer-api-v1", rendererConfig));
		real.put(rendererMixin, MixinFitStagedTest.readFromNestedJar(fabricApi, "fabric-renderer-api-v1", rendererMixin));
		assumeTrue(real.values().stream().allMatch(b -> b != null), "an entry is missing from the real jars");
		Function<String, byte[]> resource = name -> real.containsKey(name) ? real.get(name) : merged.apply(name);
		ForbricMixinService.setGuestConfigs(List.of(rendererConfig, cullConfig));
		// Both mixins are in their configs' client arrays, which Mixin prepares on a client only.
		net.fabricmc.api.EnvType side = ForbricMixinService.side();
		ForbricMixinService.bind(null, net.fabricmc.api.EnvType.CLIENT);
		try {
			judgeTheRealPair(resource, real, cullConfig, cullMixin, rendererConfig);
		} finally {
			ForbricMixinService.bind(null, side);
		}
	}

	private static void judgeTheRealPair(Function<String, byte[]> resource, Map<String, byte[]> real, String cullConfig,
			String cullMixin, String rendererConfig) {

		MixinFit.Result alone = MixinFit.evaluate(real.get(cullMixin), resource);
		assertTrue(alone.unresolved().contains("@Shadow field BlockModelRenderState.mesh"), alone.reason());

		MixinFit.Result r = MixinFit.evaluate(real.get(cullMixin), resource,
				net.forbric.kernel.classloading.DelegationPolicy::alwaysGame,
				MixinAddedMembers.before(cullConfig, "renderers.BlockModelRenderState_fabricCullMixin", resource));
		assertEquals(MixinFit.Verdict.FIT, r.verdict(), r.reason());

		// And not because of registration order: moreculling's 1200 against fabric-renderer's 1000 decides it.
		ForbricMixinService.setGuestConfigs(List.of(cullConfig, rendererConfig));
		MixinAddedMembers.reset();
		assertEquals(MixinFit.Verdict.FIT, MixinFit.evaluate(real.get(cullMixin), resource,
				net.forbric.kernel.classloading.DelegationPolicy::alwaysGame,
				MixinAddedMembers.before(cullConfig, "renderers.BlockModelRenderState_fabricCullMixin", resource)).verdict());
	}

	// --- staging ----------------------------------------------------------------------------------------------

	private static MixinFit.Result judge(Stage stage, String config, String entry) {
		String pkg = stage.packages.get(config);
		return MixinFit.evaluate(stage.apply(pkg.replace('.', '/') + "/" + entry + ".class"), stage,
				name -> true, MixinAddedMembers.before(config, entry, stage));
	}

	/** Config names, config JSON, mixin classes and the one merged target. */
	private static final class Stage implements Function<String, byte[]> {
		private final Map<String, byte[]> resources = new HashMap<>();
		private final LinkedHashSet<String> names = new LinkedHashSet<>();
		final Map<String, String> packages = new HashMap<>();

		Stage() {
			resources.put(TARGET + ".class", target());
		}

		void put(String path, byte[] bytes) {
			resources.put(path, bytes);
		}

		void raw(String configName, String json) {
			names.add(configName);
			resources.put(configName, json.getBytes(StandardCharsets.UTF_8));
			int start = json.indexOf("\"package\": \"") + "\"package\": \"".length();
			packages.put(configName, json.substring(start, json.indexOf('"', start)));
		}

		void config(String configName, String pkg, MixinBuilder mixin) {
			String simple = mixin.name.substring(mixin.name.lastIndexOf('/') + 1);
			raw(configName, "{\"package\": \"" + pkg + "\", \"mixins\": [\"" + simple + "\"]}");
			resources.put(mixin.name + ".class", mixin.bytes());
		}

		List<String> names() {
			return List.copyOf(names);
		}

		@Override
		public byte[] apply(String path) {
			return resources.get(path);
		}
	}

	/** The merged class: a public field {@code parts} (public, so the orphaned-field rule has nothing to say). */
	private static byte[] target() {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, TARGET, null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PUBLIC, "parts", "Ljava/util/List;", null, null).visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static MixinBuilder mixin(String name, Integer priority) {
		return new MixinBuilder(name, priority);
	}

	private static final class MixinBuilder {
		final String name;
		final ClassWriter cw = new ClassWriter(0);

		MixinBuilder(String name, Integer priority) {
			this.name = name;
			cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, name, null, "java/lang/Object", null);
			AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
			AnnotationVisitor targets = mixin.visitArray("value");
			targets.visit(null, Type.getObjectType(TARGET));
			targets.visitEnd();
			if (priority != null) mixin.visit("priority", priority);
			mixin.visitEnd();
		}

		MixinBuilder shadowField(String field) {
			FieldVisitor fv = cw.visitField(Opcodes.ACC_PRIVATE, field, field.equals("parts") ? "Ljava/util/List;" : MESH,
					null, null);
			fv.visitAnnotation("Lorg/spongepowered/asm/mixin/Shadow;", true).visitEnd();
			fv.visitEnd();
			return this;
		}

		MixinBuilder uniqueField(String field) {
			FieldVisitor fv = cw.visitField(Opcodes.ACC_PRIVATE, field, MESH, null, null);
			fv.visitAnnotation("Lorg/spongepowered/asm/mixin/Unique;", false).visitEnd();
			fv.visitEnd();
			return this;
		}

		MixinBuilder method(String method, String desc) {
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, method, desc, null, null);
			mv.visitCode();
			mv.visitInsn(Opcodes.ICONST_0);
			mv.visitInsn(Opcodes.IRETURN);
			mv.visitMaxs(1, 1);
			mv.visitEnd();
			return this;
		}

		MixinBuilder shadowMethod(String method, String desc) {
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, method, desc, null, null);
			mv.visitAnnotation("Lorg/spongepowered/asm/mixin/Shadow;", true).visitEnd();
			mv.visitEnd();
			return this;
		}

		/** An {@code @Inject} handler named {@code method}, into the target's (absent) {@code render}. */
		MixinBuilder injector(String method) {
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, method, "()V", null, null);
			AnnotationVisitor inject = mv.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true);
			AnnotationVisitor selectors = inject.visitArray("method");
			selectors.visit(null, "parts");
			selectors.visitEnd();
			inject.visitEnd();
			mv.visitCode();
			mv.visitInsn(Opcodes.RETURN);
			mv.visitMaxs(0, 1);
			mv.visitEnd();
			return this;
		}

		byte[] bytes() {
			cw.visitEnd();
			return cw.toByteArray();
		}
	}
}
