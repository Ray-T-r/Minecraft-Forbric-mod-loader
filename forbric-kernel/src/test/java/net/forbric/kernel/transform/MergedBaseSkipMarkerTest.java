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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
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
		// The premise, asserted so the repair retires itself rather than quietly becoming dead weight. fabric's skipData
		// @Inject names the bare "lambda$scanDirectory$0" with a handler written for vanilla's (Map, Identifier, Object)
		// lambda; NeoForge's base has exactly one lambda of that name, of another shape, so the consumer half does not apply
		// while the producer half does. If a future base or fabric-api ever makes those agree, the mixin applies whole and
		// this test says so.
		List<String> base = lambdas(original());
		Path vanilla = TestFixtures.vanillaJar();
		TestFixtures.requireFiles(Fixture.MC_LIBRARIES, "stock 26.2", vanilla);
		List<String> stock = lambdas(node(TestFixtures.requireEntry(Fixture.MC_LIBRARIES, vanilla, ENTRY)));
		assertEquals(List.of("(Ljava/util/Map;Lnet/minecraft/resources/Identifier;Ljava/lang/Object;)V"), stock,
				"vanilla's lambda, the shape fabric's handler (Map, Identifier, Object, CallbackInfo) was written for");
		assertEquals(1, base.size(), "the base carries one lambda$scanDirectory$0: " + base);
		assertTrue(!stock.contains(base.getFirst()),
				"the base's only lambda$scanDirectory$0 is vanilla's shape again — re-derive whether fabric-api's condition "
						+ "mixin now applies whole, and delete this repair if it does");
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
