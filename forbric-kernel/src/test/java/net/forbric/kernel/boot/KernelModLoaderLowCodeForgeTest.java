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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;

/**
 * The MinecraftForge half of the declared-only mods: a {@code lowcodefml} mod gets the {@code LowCodeModContainer}
 * MinecraftForge gives it, and nothing else does.
 *
 * <p>Dungeons and Taverns is the shape — a {@code mods.toml} and data, no class — and native MinecraftForge lists it
 * in {@code ModList} (its VersionChecker line says so). A {@code javafml} mod with no class is MinecraftForge's own
 * load error, {@code fml.modloading.missingclasses}, and must not be handed a container that hides it.
 */
class KernelModLoaderLowCodeForgeTest {
	private static final Path STAGED = Path.of(System.getenv().getOrDefault("FORBRIC_OLD",
			System.getProperty("user.dir") + "/../forbric-loader"), "run").normalize();

	@AfterEach
	void reset() {
		System.clearProperty(KernelModLoader.CLASSLESS_SWITCH);
	}

	@Test
	void onlyALowCodeMinecraftForgeModGetsOne() {
		Map<String, KernelModLoader.Declared> declared = new LinkedHashMap<>();
		declared.put("mr_dungeons_andtaverns", declared("mr_dungeons_andtaverns", "dat.jar"));
		declared.put("missing_class", declared("missing_class", "java.jar"));
		declared.put("has_class", declared("has_class", "lowcode2.jar"));
		Map<String, String> languages = Map.of("dat.jar", "lowcodefml", "java.jar", "javafml",
				"lowcode2.jar", "lowcodefml");

		List<KernelModLoader.Declared> lowCode = KernelModLoader.declaredWithoutClass(declared, Set.of("has_class"),
				Ecosystem.FORGE, entry -> languages.get(entry.jar().getFileName().toString()));

		assertEquals(List.of("mr_dungeons_andtaverns"), ids(lowCode),
				"lowcodefml gets one; a javafml mod with no class is MinecraftForge's own load error");
	}

	@Test
	void switchedOffALowCodeModGetsNoneAgain() {
		System.setProperty(KernelModLoader.CLASSLESS_SWITCH, "off");
		Map<String, KernelModLoader.Declared> declared = Map.of("mr_dungeons_andtaverns",
				declared("mr_dungeons_andtaverns", "dat.jar"));
		assertTrue(KernelModLoader.declaredWithoutClass(declared, Set.of(), Ecosystem.FORGE, entry -> "lowcodefml")
				.isEmpty());
	}

	@Test
	void theLowCodeContainersRideAlongOnEveryWrite() {
		Object forgeContainer = "forge-container";
		Object lowCode = "lowcode-container";
		List<KernelForgeModContext.Handle> handles = List.of(
				new KernelForgeModContext.Handle("libraryferret", new Object(), forgeContainer, new Object()),
				new KernelForgeModContext.Handle("odd", new Object(), 42, new Object()));

		List<Object> written = KernelModLoader.forgeListContents(handles, List.of(lowCode),
				container -> container instanceof String);
		assertEquals(List.of(forgeContainer, lowCode), written,
				"setLoadedMods REPLACES, so a low-code container left out of a later write is gone from ModList");

		assertEquals(List.of(lowCode), KernelModLoader.forgeListContents(List.of(), List.of(lowCode), c -> true),
				"with every constructed mod withdrawn, the low-code ones still stand");
	}

	/**
	 * The container is MinecraftForge's own class, built through its own public constructor — read off the compiled
	 * game side and the staged carrier, because constructing one needs the whole Minecraft logging stack.
	 */
	@Test
	void theContainerIsMinecraftForgesOwnLowCodeModContainer() throws Exception {
		Path compiled = Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"),
				"net/forbric/kernel/runtime/KernelForgeContainers.class");
		Path forge = STAGED.resolve("forge-runtime/forge-runtime.jar");
		assumeTrue(Files.isRegularFile(compiled) && Files.isRegularFile(forge), "compiled game side/carrier absent");

		String ctor = "(Lnet/minecraftforge/forgespi/language/IModInfo;"
				+ "Lnet/minecraftforge/forgespi/language/ModFileScanData;Ljava/lang/ModuleLayer;)V";
		ClassNode factory = new ClassNode();
		new ClassReader(Files.readAllBytes(compiled)).accept(factory, 0);
		MethodNode lowCode = factory.methods.stream().filter(m -> m.name.equals("lowCode")).findFirst().orElseThrow();
		boolean constructs = false;
		for (AbstractInsnNode insn : lowCode.instructions) {
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
					&& call.owner.equals("net/minecraftforge/fml/lowcodemod/LowCodeModContainer")
					&& call.name.equals("<init>") && call.desc.equals(ctor)) {
				constructs = true;
			}
		}
		assertTrue(constructs, "lowCode must build MinecraftForge's own LowCodeModContainer through its constructor");

		try (ZipFile zip = new ZipFile(forge.toFile())) {
			ClassNode carrier = new ClassNode();
			new ClassReader(zip.getInputStream(zip.getEntry("net/minecraftforge/fml/lowcodemod/LowCodeModContainer.class"))
					.readAllBytes()).accept(carrier, 0);
			MethodNode init = carrier.methods.stream().filter(m -> m.name.equals("<init>") && m.desc.equals(ctor))
					.findFirst().orElse(null);
			assertNotNull(init, "the carrier's LowCodeModContainer no longer has the constructor the factory calls");
			assertTrue((init.access & Opcodes.ACC_PUBLIC) != 0, "and it must stay public");
			assertEquals("net/minecraftforge/fml/ModContainer", carrier.superName);
		}
	}

	// --- helpers --------------------------------------------------------------------------------------------

	private static KernelModLoader.Declared declared(String id, String jar) {
		return new KernelModLoader.Declared(new DiscoveredMod(Ecosystem.FORGE, id, "1", id, List.of(), List.of(), null,
				List.of(), jar), Path.of(jar));
	}

	private static List<String> ids(List<KernelModLoader.Declared> entries) {
		List<String> out = new ArrayList<>();
		for (KernelModLoader.Declared entry : entries) out.add(entry.mod().getId());
		return out;
	}
}
