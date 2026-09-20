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
	private List<ModCatalog.Entry> previous;

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
	}

	@Test
	void aPrepareFailureMarksTheOwningModToo() {
		IMixinInfo info = info("x.mixins.json", "a.b.BarMixin");
		new KernelMixinErrorHandler().onPrepareError(info.getConfig(), new IllegalStateException(), info, IMixinErrorHandler.ErrorAction.ERROR);
		assertEquals(1, ModCatalog.failures().size());
		assertTrue(ModCatalog.failures().get(0).statusDetail().contains("BarMixin"));
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
