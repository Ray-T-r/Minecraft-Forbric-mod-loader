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

package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;
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

import net.forbric.api.Side;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * The kernel's early config pass opens the config types in native NeoForge's order.
 *
 * <p>Opening a config posts {@code ModConfigEvent.Loading} for it. Native NeoForge's "Config loading" step
 * ({@code CommonModLoader.begin}, {@code lambda$begin$1}) loads CLIENT (on the client) and then COMMON, so a COMMON
 * config's Loading listener that reads the mod's own CLIENT values finds them loaded. The kernel opened COMMON first,
 * where the same read throws "Cannot get config value before config is loaded" (Stage 4 C5 K3b review A-3).
 */
class KernelLifecycleEarlyConfigOrderTest {
	private static final String CONFIG_TRACKER = "net/neoforged/fml/config/ConfigTracker";
	private static final String CONFIG_TYPE = "net/neoforged/fml/config/ModConfig$Type";

	@Test
	void theClientOpensClientThenCommonAndAServerCommonOnly() {
		assertEquals(List.of("CLIENT", "COMMON"), KernelLifecycle.earlyConfigTypes(Side.CLIENT),
				"native NeoForge loads CLIENT, then COMMON, on the client");
		assertEquals(List.of("COMMON"), KernelLifecycle.earlyConfigTypes(Side.DEDICATED_SERVER),
				"a dedicated server has no CLIENT config to load");
	}

	/** The order read off the carrier's own bytes, so a carrier that changes it turns this red. */
	@Test
	void theClientsOrderIsTheCarriersOwn() throws Exception {
		List<String> nativeOrder = nativeConfigLoadOrder();
		assertEquals(List.of("CLIENT", "COMMON"), nativeOrder,
				"CommonModLoader's config-loading step loads these types, in this order");
		assertEquals(nativeOrder, KernelLifecycle.earlyConfigTypes(Side.CLIENT),
				"the kernel's early pass opens the types in the carrier's order");
		for (Side side : Side.values()) {
			assertFalse(KernelLifecycle.earlyConfigTypes(side).contains("STARTUP"),
					side + ": STARTUP opens at registration; the early pass would open it twice");
		}
	}

	/**
	 * The {@code ModConfig.Type} constants the one {@code CommonModLoader} method that calls
	 * {@code ConfigTracker.loadConfigs} reads, in bytecode order.
	 */
	private static List<String> nativeConfigLoadOrder() throws Exception {
		java.nio.file.Path carrier = TestFixtures.neoCarrier();
		TestFixtures.requireFiles(Fixture.STAGED, "staged NeoForge carrier absent", carrier);
		ClassNode node = new ClassNode();
		try (ZipFile zip = new ZipFile(carrier.toFile())) {
			ZipEntry entry = zip.getEntry("net/neoforged/neoforge/internal/CommonModLoader.class");
			assertFalse(entry == null, "the carrier has no CommonModLoader");
			new ClassReader(zip.getInputStream(entry).readAllBytes()).accept(node, 0);
		}
		List<MethodNode> loaders = new ArrayList<>();
		for (MethodNode m : node.methods) {
			for (AbstractInsnNode insn : m.instructions.toArray()) {
				if (insn instanceof MethodInsnNode call && call.owner.equals(CONFIG_TRACKER)
						&& call.name.equals("loadConfigs")) {
					loaders.add(m);
					break;
				}
			}
		}
		assertEquals(1, loaders.size(), "one CommonModLoader method loads the configs: " + loaders.size());
		List<String> types = new ArrayList<>();
		for (AbstractInsnNode insn : loaders.get(0).instructions.toArray()) {
			if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
					&& field.owner.equals(CONFIG_TYPE)) {
				types.add(field.name);
			}
		}
		return types;
	}
}
