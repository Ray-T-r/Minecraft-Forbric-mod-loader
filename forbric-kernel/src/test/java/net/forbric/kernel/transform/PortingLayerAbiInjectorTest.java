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
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Covers the ForgeConfigAPIPort ABI shim, against the REAL jar.
 *
 * <p>A hand-built fixture would be the wrong subject: the whole premise is what a third-party jar compiled
 * against, and the shim's refusal condition is "that jar changed". Skips when the jar is not staged.
 */
class PortingLayerAbiInjectorTest {
	private static final Path PORT = Path.of(System.getProperty("user.dir"), "run", "client-kernel", "mods",
			"ForgeConfigAPIPort-v26.2.1-mc26.2.x-Fabric.jar").normalize();
	private static final String REGISTRY = "fuzs/forgeconfigapiport/fabric/impl/core/ConfigRegistryImpl";
	private static final String ADAPTER = "fuzs/forgeconfigapiport/fabric/impl/core/ForgeConfigSpecAdapter";
	private static final String TRACKER = "net/neoforged/fml/config/ConfigTracker";
	private static final String BRIDGE = "net/forbric/kernel/runtime/KernelConfigPortBridge";

	@Test
	void everyModIdKeyedRegistrationGoesThroughTheBridgeWithTheTrackerAsArgumentZero() throws Exception {
		ClassNode node = shim(REGISTRY);

		int bridged = 0;
		for (MethodNode m : node.methods) {
			AbstractInsnNode[] body = m.instructions.toArray();
			for (int i = 0; i < body.length; i++) {
				if (!(body[i] instanceof MethodInsnNode call) || !BRIDGE.equals(call.owner)) continue;
				assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
				assertEquals("registerConfig", call.name);
				assertTrue(call.desc.startsWith("(L" + TRACKER + ";"),
						"the tracker must become argument zero — keeping the getstatic in place is what makes this "
								+ "a one-instruction swap with no stack surgery, no frames and no maxStack change");
				bridged++;
			}
			for (AbstractInsnNode insn : body) {
				if (insn instanceof MethodInsnNode call && TRACKER.equals(call.owner)
						&& "registerConfig".equals(call.name)) {
					throw new AssertionError("a mod-id-keyed registerConfig survived: " + call.desc
							+ " — real NeoForge does not have it, so this one is still a NoSuchMethodError");
				}
			}
		}
		assertEquals(4, bridged, "all four of the port's register overloads must be routed");
	}

	@Test
	void theStaticTrackerFieldReadIsKeptSoTheStackStillBalances() throws Exception {
		ClassNode node = shim(REGISTRY);
		for (MethodNode m : node.methods) {
			AbstractInsnNode[] body = m.instructions.toArray();
			for (int i = 0; i < body.length; i++) {
				if (!(body[i] instanceof MethodInsnNode call) || !BRIDGE.equals(call.owner)) continue;
				boolean sawTracker = false;
				for (int j = i - 1; j >= 0 && j > i - 12; j--) {
					if (body[j] instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC
							&& TRACKER.equals(f.owner) && "INSTANCE".equals(f.name)) {
						sawTracker = true;
						break;
					}
				}
				assertTrue(sawTracker, "the ConfigTracker.INSTANCE read must still precede the call");
			}
		}
	}

	@Test
	void theSpecAdapterGainsTheMethodTheCarrierCallsOnEveryRegistration() throws Exception {
		ClassNode node = shim(ADAPTER);
		assertTrue(node.methods.stream().anyMatch(m -> "validateSpec".equals(m.name)
						&& "(Lnet/neoforged/fml/config/ModConfig;)V".equals(m.desc)),
				"real IConfigSpec declares validateSpec(ModConfig) and registerConfig calls it unconditionally; "
						+ "the port's adapter implements an older shape of the interface without it, so every "
						+ "registration is an AbstractMethodError");
	}

	/**
	 * The swap changes an owner, an opcode and a descriptor, and nothing else. That is the property that lets it
	 * skip stack-frame work entirely, so it is asserted rather than assumed: same instruction count, same
	 * {@code maxStack}, same {@code maxLocals}, method for method.
	 */
	@Test
	void nothingAboutTheFramesOrTheStackMoved() throws Exception {
		ClassNode before = new ClassNode();
		new ClassReader(original(REGISTRY)).accept(before, 0);
		ClassNode after = shim(REGISTRY);

		assertEquals(before.methods.size(), after.methods.size());
		for (int i = 0; i < before.methods.size(); i++) {
			MethodNode was = before.methods.get(i);
			MethodNode now = after.methods.get(i);
			assertEquals(was.name + was.desc, now.name + now.desc, "method order must be untouched");
			assertEquals(was.instructions.size(), now.instructions.size(),
					was.name + ": the rewrite must not add or remove an instruction");
			assertEquals(was.maxStack, now.maxStack, was.name + ": stack depth must be unchanged");
			assertEquals(was.maxLocals, now.maxLocals, was.name + ": locals must be unchanged");
		}
	}

	/**
	 * Drift is a refusal of the WHOLE shim. Rewriting three of four sites would leave the fourth as the original
	 * {@code NoSuchMethodError} and the first three pointing at a bridge built for a contract that has moved.
	 */
	@Test
	void aPortWithADifferentNumberOfCallSitesIsRefusedWhole() throws Exception {
		byte[] original = original(REGISTRY);
		ClassNode node = new ClassNode();
		new ClassReader(original).accept(node, 0);
		// Drop one register overload entirely, which is what a version bump that consolidates them looks like.
		List<MethodNode> kept = new ArrayList<>();
		boolean dropped = false;
		for (MethodNode m : node.methods) {
			if (!dropped && "register".equals(m.name)) {
				dropped = true;
				continue;
			}
			kept.add(m);
		}
		assertTrue(dropped, "the fixture must have a register overload to drop");
		node.methods = kept;
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		byte[] drifted = writer.toByteArray();

		byte[] out = new PortingLayerAbiInjector().transform(
				"fuzs.forgeconfigapiport.fabric.impl.core.ConfigRegistryImpl", drifted, null);

		assertSame(drifted, out, "a port this shim no longer recognises must be left exactly as it is");
	}

	@Test
	void anUnrelatedClassIsUntouched() {
		byte[] bytes = {(byte) 0xCA, (byte) 0xFE};
		assertSame(bytes, new PortingLayerAbiInjector().transform("com.example.Whatever", bytes, null));
	}

	private static ClassNode shim(String entry) throws IOException {
		byte[] out = new PortingLayerAbiInjector().transform(entry.replace('/', '.'), original(entry), null);
		ClassNode node = new ClassNode();
		new ClassReader(out).accept(node, 0);
		return node;
	}

	private static byte[] original(String entry) throws IOException {
		assumeTrue(Files.isRegularFile(PORT), "ForgeConfigAPIPort not staged — skipping the real-bytecode check");
		try (ZipFile jar = new ZipFile(PORT.toFile())) {
			ZipEntry e = jar.getEntry(entry + ".class");
			assumeTrue(e != null, entry + " absent from this build of the port");
			try (InputStream in = jar.getInputStream(e)) {
				return in.readAllBytes();
			}
		}
	}

}
