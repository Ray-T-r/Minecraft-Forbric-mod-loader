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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The repair for fabric-api's half-applied condition mixin, and the premise that makes it necessary.
 *
 * <p>The producer half of {@code SimpleJsonResourceReloadListenerMixin} applies on the merged base and the
 * consumer half does not, so a bare {@code Object} sentinel reaches a reader that casts to {@code Optional}.
 * Unrepaired, one condition-gated data file whose condition is false stops the server starting.
 */
class MergedBaseSkipMarkerTest {
	private static final Path MERGED_BASE =
			Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run", "merged-base",
					"patched-mc-merged-26.2.jar").normalize();
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
	void theMergedClassStillHasTheTwoLambdasThatMakeThisNecessary() throws Exception {
		// The premise, asserted so the repair retires itself rather than quietly becoming dead weight. fabric's
		// @Inject targets the (…,Object) overload; the live invokedynamic binds the (…,Optional) one. If a future
		// merged base or fabric-api ever makes those agree, the mixin applies whole and this test says so.
		ClassNode node = original();

		boolean optionalForm = false;
		boolean objectForm = false;
		for (MethodNode method : node.methods) {
			if (!"lambda$scanDirectory$0".equals(method.name)) continue;
			if (method.desc.endsWith("Ljava/util/Optional;)V")) optionalForm = true;
			if (method.desc.endsWith("Ljava/lang/Object;)V")) objectForm = true;
		}

		assertTrue(optionalForm && objectForm,
				"the merged base no longer carries BOTH lambda$scanDirectory$0 overloads — re-derive whether "
						+ "fabric-api's condition mixin now applies whole, and delete this repair if it does");
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
		ClassNode node = new ClassNode();
		new ClassReader(bytes()).accept(node, 0);
		return node;
	}

	private static byte[] bytes() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			ZipEntry entry = zip.getEntry(ENTRY);
			assumeTrue(entry != null, "SimpleJsonResourceReloadListener absent from this base");
			try (InputStream in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}
}
