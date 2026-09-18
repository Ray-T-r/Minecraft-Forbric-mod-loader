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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The lookup that used to kill the client over a Fabric mod adding a reload listener the way Fabric mods do.
 *
 * <p>Asserted on the call site, because that is what decides whether the kernel is on the path. What it must NOT
 * do is remove the throw and leave the listener unnamed: an unnamed listener is an unregistered one, and this
 * class's point is that the listener still runs.
 */
class MergedBaseClientReloadNamesTest {
	private static final Path NEO_CARRIER =
			Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run", "neoforge-runtime",
					"neoforge-runtime.jar").normalize();
	private static final String ENTRY = "net/neoforged/neoforge/client/event/AddClientReloadListenersEvent.class";
	private static final String BINARY = "net.neoforged.neoforge.client.event.AddClientReloadListenersEvent";
	private static final String KERNEL = "net/forbric/kernel/runtime/KernelClientReloadNames";

	@Test
	void theNameLookupGoesThroughTheKernel() throws Exception {
		ClassNode node = repaired();
		boolean redirected = false;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (!(insn instanceof MethodInsnNode call)) continue;
				assertTrue(!("net/neoforged/neoforge/client/resources/VanillaClientListeners".equals(call.owner)
								&& "getNameForClass".equals(call.name)),
						"the event still asks VanillaClientListeners directly in " + method.name
								+ " — a listener it does not know then throws inside Minecraft.<init>");
				if (KERNEL.equals(call.owner) && "nameFor".equals(call.name)) {
					assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
					assertEquals("(Ljava/lang/Class;)Lnet/minecraft/resources/Identifier;", call.desc,
							"same descriptor as the lookup it replaced, or the stack moves");
					redirected = true;
				}
			}
		}
		assertTrue(redirected, "AddClientReloadListenersEvent must name unknown listeners through the kernel");
	}

	/**
	 * The throw is left in place on purpose: it is unreachable once the lookup always answers, and deleting it
	 * would hide a genuine future case where the kernel's own naming returned null.
	 */
	@Test
	void theRefusalIsBypassedRatherThanDeleted() throws Exception {
		ClassNode node = repaired();
		boolean stillHasTheGuard = false;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn.getOpcode() == Opcodes.ATHROW) stillHasTheGuard = true;
			}
		}
		assertTrue(stillHasTheGuard,
				"NeoForge's own refusal should remain as the last resort — the kernel answers before it, it does "
						+ "not replace it");
	}

	@Test
	void aSecondPassChangesNothingFurther() throws Exception {
		byte[] once = new ForbricMergedBaseCompatTransformer().transform(BINARY, original(), null);
		byte[] twice = new ForbricMergedBaseCompatTransformer().transform(BINARY, once, null);
		assertSame(once, twice, "no VanillaClientListeners lookup is left, so the second pass must find nothing");
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
			assumeTrue(entry != null, "AddClientReloadListenersEvent absent from this carrier");
			try (InputStream in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}
}
