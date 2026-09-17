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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The wrap of {@code ICondition.CODEC} that keeps one ecosystem's condition dialect from failing the other's data.
 *
 * <p>What makes the insertion point load-bearing rather than stylistic: {@code LIST_CODEC} is built FROM
 * {@code CODEC} two instructions later in the same initializer, so wrapping before the {@code PUTSTATIC} covers
 * the list form — which is the one {@code neoforge:conditions} actually uses — and wrapping after it would not.
 */
class MergedBaseForeignConditionsTest {
	private static final Path NEO_CARRIER =
			Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run", "neoforge-runtime",
					"neoforge-runtime.jar").normalize();
	private static final String ENTRY = "net/neoforged/neoforge/common/conditions/ICondition.class";
	private static final String BINARY = "net.neoforged.neoforge.common.conditions.ICondition";
	private static final String KERNEL = "net/forbric/kernel/runtime/KernelNeoConditions";

	@Test
	void theCodecIsWrappedBeforeItIsStoredAndBeforeListCodecIsDerived() throws Exception {
		MethodNode clinit = clinit(repaired());
		AbstractInsnNode[] body = clinit.instructions.toArray();

		int wrap = -1;
		int store = -1;
		int list = -1;
		for (int i = 0; i < body.length; i++) {
			if (body[i] instanceof MethodInsnNode call && KERNEL.equals(call.owner) && "lenient".equals(call.name)) {
				assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
				assertEquals("(Lcom/mojang/serialization/Codec;)Lcom/mojang/serialization/Codec;", call.desc,
						"a Codec in and a Codec out, or the PUTSTATIC after it is storing something else");
				wrap = i;
			} else if (body[i] instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC
					&& "CODEC".equals(field.name)) {
				store = i;
			} else if (body[i] instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC
					&& "LIST_CODEC".equals(field.name)) {
				list = i;
			}
		}

		assertTrue(wrap >= 0, "ICondition.CODEC is not wrapped — a Fabric mod's own condition id then fails the "
				+ "whole registry load, because the merged base runs NeoForge's evaluator over every pack");
		assertTrue(store > wrap, "the wrap must happen before CODEC is stored");
		assertTrue(list < 0 || list > store,
				"LIST_CODEC is derived from CODEC, so it has to be built after the wrapped value is in place — "
						+ "neoforge:conditions is a LIST, and an unwrapped list codec is the whole failure back");
	}

	@Test
	void aSecondPassDoesNotWrapItTwice() throws Exception {
		byte[] once = new ForbricMergedBaseCompatTransformer().transform(BINARY, original(), null);
		byte[] twice = new ForbricMergedBaseCompatTransformer().transform(BINARY, once, null);
		assertSame(once, twice, "wrapping the wrapper would work and would also make the log lie about how many "
				+ "conditions were ignored");
	}

	private static ClassNode repaired() throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(new ForbricMergedBaseCompatTransformer().transform(BINARY, original(), null)).accept(node, 0);
		return node;
	}

	private static byte[] original() throws IOException {
		assumeTrue(Files.isRegularFile(NEO_CARRIER), "staged NeoForge carrier absent");
		try (ZipFile zip = new ZipFile(NEO_CARRIER.toFile())) {
			ZipEntry entry = zip.getEntry(ENTRY);
			assumeTrue(entry != null, "ICondition absent from this carrier");
			try (InputStream in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}

	private static MethodNode clinit(ClassNode node) {
		for (MethodNode method : node.methods) {
			if ("<clinit>".equals(method.name)) return method;
		}
		throw new AssertionError("ICondition has no static initializer");
	}
}
