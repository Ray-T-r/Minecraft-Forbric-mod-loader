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

package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * The repair for fabric-api's half-applied condition mixin, and the premise that makes it necessary.
 *
 * <p>The producer half of {@code SimpleJsonResourceReloadListenerMixin} applies on NeoForge's base and the
 * consumer half does not, so a bare {@code Object} sentinel reaches a reader that casts to {@code Optional}.
 * Unrepaired, one condition-gated data file whose condition is false stops the server starting.
 */
class MergedBaseSkipMarkerTest {
	private static final Path MERGED_BASE = TestFixtures.stagedBase();
	private static final String ENTRY = "net/minecraft/server/packs/resources/SimpleJsonResourceReloadListener.class";
	private static final String BINARY = "net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener";
	private static final String KERNEL = "net/forbric/kernel/runtime/KernelFabricConditions";

	@Test
	void bothReadersGoThroughTheKernelInsteadOfStraightAtIfSuccess() throws Exception {
		ClassNode node = repaired();

		List<String> routed = new ArrayList<>();
		for (MethodNode method : node.methods) {
			if (method.instructions == null) continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && KERNEL.equals(call.owner)
						&& "ifSuccessWithoutAForeignSkipMarker".equals(call.name)) {
					assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
					assertEquals("(Lcom/mojang/serialization/DataResult;Ljava/util/function/Consumer;)"
							+ "Lcom/mojang/serialization/DataResult;", call.desc,
							"same stack shape as the ifSuccess it replaces, or the captured values below it move");
					routed.add(method.name);
				}
			}
		}

		// scanDirectoryWithModifier is repaired too, not just the one observed failing: same shape, same consumer
		// contract, and it is the reader recipes go through. This file already carries the lesson about patching
		// one call site and silently missing every recipe.
		assertEquals(List.of("scanDirectory", "scanDirectoryWithModifier"), routed.stream().sorted().toList());
	}

	@Test
	void noRawIfSuccessSurvivesInEitherReader() throws Exception {
		ClassNode node = repaired();
		for (MethodNode method : node.methods) {
			if (!"scanDirectory".equals(method.name) && !"scanDirectoryWithModifier".equals(method.name)) continue;
			for (AbstractInsnNode insn : method.instructions) {
				assertTrue(!(insn instanceof MethodInsnNode call && "ifSuccess".equals(call.name)),
						method.name + " still reaches DataResult.ifSuccess directly, so the marker still gets "
								+ "cast to Optional there");
			}
		}
	}

	@Test
	void theBasesOnlyScanDirectoryLambdaIsNotTheShapeFabricsHandlerWasWrittenFor() throws Exception {
		// The premise, asserted so the repair retires itself rather than quietly becoming dead weight. fabric-api's
		// condition mixin @Injects into the bare "lambda$scanDirectory$0" with a handler whose leading parameters are
		// vanilla's (Map, Identifier, Object) lambda; NeoForge's base has exactly one lambda of that name, of another
		// shape, so the consumer half does not apply while the producer half does. The handler is read from the pinned
		// fabric-api itself and the lambda from the base, so a change on either side that makes them agree -- the
		// mixin then applies whole -- fails here.
		String handler = fabricsScanDirectoryLambdaHandler();
		List<String> base = lambdas(original());
		Path vanilla = TestFixtures.vanillaJar();
		TestFixtures.requireFiles(Fixture.MC_LIBRARIES, "stock 26.2", vanilla);
		List<String> stock = lambdas(node(TestFixtures.requireEntry(Fixture.MC_LIBRARIES, vanilla, ENTRY)));
		assertEquals(List.of(handler), stock,
				"fabric-api's handler (its parameters before CallbackInfo) is vanilla's lambda, the shape it was written for");
		assertEquals(1, base.size(), "the base carries one lambda$scanDirectory$0: " + base);
		assertTrue(!handler.equals(base.getFirst()),
				"the base's only lambda$scanDirectory$0 has the shape fabric-api's handler takes — re-derive whether its "
						+ "condition mixin now applies whole, and delete this repair if it does");
	}

	private static final String FABRIC_CONDITIONS_MODULE = "META-INF/jars/fabric-resource-conditions-api-v1-";
	private static final String FABRIC_CONDITIONS_MIXIN =
			"net/fabricmc/fabric/mixin/resource/conditions/SimpleJsonResourceReloadListenerMixin.class";

	/**
	 * The shape of the lambda fabric-api's condition mixin was written for: the descriptor of its one handler whose
	 * {@code @Inject} names {@code lambda$scanDirectory$0}, with the trailing {@code CallbackInfo} dropped.
	 */
	private static String fabricsScanDirectoryLambdaHandler() throws Exception {
		Path api = TestFixtures.fabricApi();
		TestFixtures.requireFiles(Fixture.STAGED, "the pinned fabric-api", api);
		ClassNode mixin = null;
		try (ZipFile outer = new ZipFile(api.toFile())) {
			ZipEntry module = outer.stream().filter(e -> e.getName().startsWith(FABRIC_CONDITIONS_MODULE)).findFirst()
					.orElseThrow(() -> new AssertionError("content drift: " + api + " nests no " + FABRIC_CONDITIONS_MODULE + "*"));
			try (ZipInputStream inner = new ZipInputStream(outer.getInputStream(module))) {
				for (ZipEntry e = inner.getNextEntry(); e != null; e = inner.getNextEntry()) {
					if (e.getName().equals(FABRIC_CONDITIONS_MIXIN)) mixin = node(inner.readAllBytes());
				}
			}
		}
		assertNotNull(mixin, "content drift: " + module(api) + " has no " + FABRIC_CONDITIONS_MIXIN);
		List<String> handlers = new ArrayList<>();
		for (MethodNode method : mixin.methods) {
			if (method.visibleAnnotations == null) continue;
			for (AnnotationNode annotation : method.visibleAnnotations) {
				if (!"Lorg/spongepowered/asm/mixin/injection/Inject;".equals(annotation.desc)) continue;
				if (!injectTargets(annotation).contains("lambda$scanDirectory$0")) continue;
				Type[] parameters = Type.getArgumentTypes(method.desc);
				assertEquals("Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;",
						parameters[parameters.length - 1].getDescriptor(), method.name + " ends in CallbackInfo");
				handlers.add(Type.getMethodDescriptor(Type.VOID_TYPE, Arrays.copyOf(parameters, parameters.length - 1)));
			}
		}
		assertEquals(1, handlers.size(), "fabric-api has one handler on lambda$scanDirectory$0: " + handlers);
		return handlers.getFirst();
	}

	private static String module(Path api) {
		return api + "!" + FABRIC_CONDITIONS_MODULE + "*";
	}

	@SuppressWarnings("unchecked")
	private static List<String> injectTargets(AnnotationNode inject) {
		for (int i = 0; i + 1 < inject.values.size(); i += 2) {
			if ("method".equals(inject.values.get(i))) return (List<String>) inject.values.get(i + 1);
		}
		return List.of();
	}

	private static List<String> lambdas(ClassNode node) {
		return node.methods.stream().filter(m -> "lambda$scanDirectory$0".equals(m.name)).map(m -> m.desc).toList();
	}

	@Test
	void aSecondPassChangesNothing() throws Exception {
		byte[] once = new ForbricMergedBaseCompatTransformer().transform(BINARY, bytes(), null);
		byte[] twice = new ForbricMergedBaseCompatTransformer().transform(BINARY, once, null);
		assertSame(once, twice, "there is no ifSuccess left to replace on a second pass");
	}

	private static ClassNode repaired() throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(new ForbricMergedBaseCompatTransformer().transform(BINARY, bytes(), null)).accept(node, 0);
		return node;
	}

	private static ClassNode original() throws Exception {
		return node(bytes());
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] bytes() throws Exception {
		return TestFixtures.requireEntry(Fixture.STAGED, MERGED_BASE, ENTRY);
	}
}
