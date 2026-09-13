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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * Covers the restored hidden-pack filter.
 *
 * <p>{@code Pack.isHidden} gates listing and not application, which is how a mod's own assets are meant to work.
 * The merge kept NeoForge's {@code Pack} (so the flag exists) and vanilla's {@code TransferableSelectionList} and
 * {@code PackSelectionModel} (so nothing reads it), and every ecosystem's asset pack became a row in the player's
 * resource-pack screen that they did not add and cannot remove.
 */
class PackScreenHiddenFilterInjectorTest {
	private static final Path MERGED_BASE =
			Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run", "merged-base",
					"patched-mc-merged-26.2.jar").normalize();

	@Test
	void theStagedScreenStillForEachesWithoutFiltering() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		MethodNode update = update(parse(readClass()));
		assertNotNull(update, "updateList must still exist with that descriptor");
		assertEquals(0, countCall(update, "filter"),
				"the staged base must still need the repair — if upstream filtered, re-derive this test");
		assertEquals(1, countCall(update, "forEach"), "and must still feed its rows through one forEach");
	}

	@Test
	void theFilterIsRestoredAheadOfTheForEach() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		byte[] in = readClass();
		byte[] out = transform(in);
		assertTrue(out != in, "the staged base must still need the repair");

		ClassNode node = parse(out);
		MethodNode update = update(node);
		assertEquals(1, countCall(update, "filter"), "exactly one filter, not one per pass");
		assertTrue(indexOfCall(update, "filter") < indexOfCall(update, "forEach"),
				"filtering after the rows are built would be filtering nothing");
		new Analyzer<>(new BasicVerifier()).analyze(node.name, update);
	}

	/** The predicate must be the interface's own {@code notHidden}, which is the method the merge orphaned. */
	@Test
	void thePredicateIsTheOrphanedNotHiddenMethod() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		MethodNode update = update(parse(transform(readClass())));
		InvokeDynamicInsnNode indy = null;
		for (AbstractInsnNode insn : update.instructions) {
			if (insn instanceof InvokeDynamicInsnNode i && "test".equals(i.name)) indy = i;
		}
		assertNotNull(indy, "the filter needs a Predicate call site");
		org.objectweb.asm.Handle target = (org.objectweb.asm.Handle) indy.bsmArgs[1];
		assertEquals(PackScreenHiddenFilterInjector.ENTRY, target.getOwner());
		assertEquals("notHidden", target.getName());
		assertEquals("()Z", target.getDesc());
		assertTrue(target.isInterface(), "Entry is an interface and the default is what unhidden rows rely on");
	}

	@Test
	void aSecondPassLeavesTheFilteredMethodAlone() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		byte[] once = transform(readClass());
		assertSame(once, transform(once), "a body that already filters is coherent and must not be touched");
	}

	@Test
	void anyOtherClassIsHandedBackUntouched() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		byte[] other = readClass("net/minecraft/client/gui/screens/ChatScreen.class");
		assertSame(other, new PackScreenHiddenFilterInjector()
				.transform("net.minecraft.client.gui.screens.ChatScreen", other, null));
	}

	// --- helpers ---------------------------------------------------------------------------------------------

	private static byte[] transform(byte[] bytes) {
		return new PackScreenHiddenFilterInjector()
				.transform(PackScreenHiddenFilterInjector.LIST.replace('/', '.'), bytes, null);
	}

	private static MethodNode update(ClassNode node) {
		for (MethodNode m : node.methods) {
			if (PackScreenHiddenFilterInjector.UPDATE.equals(m.name)
					&& PackScreenHiddenFilterInjector.UPDATE_DESC.equals(m.desc)) {
				return m;
			}
		}
		return null;
	}

	private static int countCall(MethodNode method, String name) {
		int n = 0;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEINTERFACE
					&& "java/util/stream/Stream".equals(call.owner) && name.equals(call.name)) {
				n++;
			}
		}
		return n;
	}

	private static int indexOfCall(MethodNode method, String name) {
		int i = 0;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && "java/util/stream/Stream".equals(call.owner)
					&& name.equals(call.name)) {
				return i;
			}
			i++;
		}
		return -1;
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] readClass() throws Exception {
		return readClass(PackScreenHiddenFilterInjector.LIST + ".class");
	}

	private static byte[] readClass(String entry) throws Exception {
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			ZipEntry e = zip.getEntry(entry);
			assertNotNull(e, entry + " must be in the staged merged base");
			try (InputStream in = zip.getInputStream(e)) {
				return in.readAllBytes();
			}
		}
	}
}
