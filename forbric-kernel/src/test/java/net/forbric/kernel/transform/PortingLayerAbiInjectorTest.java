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

	/**
	 * The consumer's half, and the one that is not in the port's jar at all: ShoulderSurfing hands
	 * {@code ConfigurationScreen::new} to the port's screen-factory registry. The mismatch is a method handle in
	 * an invokedynamic, so it fails when the lambda's call site LINKS — which is why the NoSuchMethodError came
	 * from a line of code that constructs nothing.
	 */
	@Test
	void aMethodReferenceToThePortsScreenConstructorIsReAimedAtTheCarriers() throws Exception {
		Path ss = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"), "run", "mods",
				"ShoulderSurfing-Fabric-26.2-5.0.11.jar").normalize();
		assumeTrue(Files.isRegularFile(ss), "ShoulderSurfing not staged — skipping");
		byte[] in;
		try (ZipFile jar = new ZipFile(ss.toFile())) {
			ZipEntry e = jar.getEntry("com/github/exopandora/shouldersurfing/fabric/ShoulderSurfingFabric.class");
			assumeTrue(e != null, "the client entrypoint moved in this build");
			try (InputStream stream = jar.getInputStream(e)) {
				in = stream.readAllBytes();
			}
		}

		byte[] out = new PortingLayerAbiInjector().transform(
				"com.github.exopandora.shouldersurfing.fabric.ShoulderSurfingFabric", in, null);
		ClassNode node = new ClassNode();
		new ClassReader(out).accept(node, 0);

		boolean reaimed = false;
		for (MethodNode m : node.methods) {
			for (AbstractInsnNode insn : m.instructions) {
				if (!(insn instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode indy)) continue;
				for (Object arg : indy.bsmArgs) {
					if (!(arg instanceof org.objectweb.asm.Handle h)) continue;
					assertTrue(!"net/neoforged/neoforge/client/gui/ConfigurationScreen".equals(h.getOwner()),
							"a handle to the port's screen constructor survived; it does not exist on the carrier");
					if ("net/forbric/kernel/runtime/KernelConfigPortBridge".equals(h.getOwner())
							&& "configurationScreen".equals(h.getName())) {
						assertEquals(Opcodes.H_INVOKESTATIC, h.getTag());
						assertEquals("(Ljava/lang/String;Lnet/minecraft/client/gui/screens/Screen;)"
								+ "Lnet/minecraft/client/gui/screens/Screen;", h.getDesc(),
								"the factory must match the lambda's instantiated type exactly");
						reaimed = true;
					}
				}
			}
		}
		assertTrue(reaimed, "the ConfigurationScreen::new reference must be re-aimed at the kernel's factory");
	}

	@Test
	void theCarriersWholeTypeLoadPassesOverAConfigThatIsAlreadyOpen() throws Exception {
		// The port loads SERVER configs in Fabric's SERVER_STARTING and NeoForge loads them again from initServer:
		// every config was opened twice — a second Loading event and a second file watcher on each.
		String cfg = "net/neoforged/fml/config/ModConfig";
		String loaded = "net/neoforged/fml/config/IConfigSpec$ILoadedConfig";
		java.util.Map<String, byte[]> classes = new java.util.HashMap<>();
		ClassWriter iface = new ClassWriter(0);
		iface.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, loaded, null, "java/lang/Object", null);
		classes.put(loaded, iface.toByteArray());
		ClassWriter mc = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		mc.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, cfg, null, "java/lang/Object", null);
		mc.visitField(Opcodes.ACC_PUBLIC, "value", "L" + loaded + ";", null, null);
		var ctor = mc.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		ctor.visitInsn(Opcodes.RETURN);
		ctor.visitMaxs(0, 0);
		var get = mc.visitMethod(Opcodes.ACC_PUBLIC, "getLoadedConfig", "()L" + loaded + ";", null, null);
		get.visitVarInsn(Opcodes.ALOAD, 0);
		get.visitFieldInsn(Opcodes.GETFIELD, cfg, "value", "L" + loaded + ";");
		get.visitInsn(Opcodes.ARETURN);
		get.visitMaxs(0, 0);
		classes.put(cfg, mc.toByteArray());
		ClassWriter tw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		tw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, TRACKER, null, "java/lang/Object", null);
		tw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "opened", "I", null, null);
		var each = tw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "lambda$loadConfigs$0",
				"(Ljava/nio/file/Path;Ljava/nio/file/Path;L" + cfg + ";)V", null, null);
		each.visitFieldInsn(Opcodes.GETSTATIC, TRACKER, "opened", "I");
		each.visitInsn(Opcodes.ICONST_1);
		each.visitInsn(Opcodes.IADD);
		each.visitFieldInsn(Opcodes.PUTSTATIC, TRACKER, "opened", "I");
		each.visitInsn(Opcodes.RETURN);
		each.visitMaxs(0, 0);
		byte[] out = new PortingLayerAbiInjector().transform(TRACKER.replace('/', '.'), tw.toByteArray(), null);
		classes.put(TRACKER, out);
		assertSame(out, new PortingLayerAbiInjector().transform(TRACKER.replace('/', '.'), out, null), "guarded once");

		ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException {
				byte[] bytes = classes.get(name.replace('.', '/'));
				if (bytes == null) throw new ClassNotFoundException(name);
				return defineClass(name, bytes, 0, bytes.length);
			}
		};
		Class<?> tracker = loader.loadClass(TRACKER.replace('/', '.'));
		Class<?> modConfig = loader.loadClass(cfg.replace('/', '.'));
		var lambda = tracker.getDeclaredMethod("lambda$loadConfigs$0", Path.class, Path.class, modConfig);
		lambda.setAccessible(true);
		Object fresh = modConfig.getConstructor().newInstance();
		lambda.invoke(null, null, null, fresh);
		assertEquals(1, tracker.getField("opened").getInt(null), "an unloaded config is opened");
		Object open = modConfig.getConstructor().newInstance();
		modConfig.getField("value").set(open, java.lang.reflect.Proxy.newProxyInstance(loader,
				new Class<?>[] {loader.loadClass(loaded.replace('/', '.'))}, (proxy, method, args) -> null));
		lambda.invoke(null, null, null, open);
		assertEquals(1, tracker.getField("opened").getInt(null), "an open one is passed over");
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
