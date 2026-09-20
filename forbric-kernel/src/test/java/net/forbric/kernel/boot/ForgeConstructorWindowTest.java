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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.api.Side;
import net.forbric.kernel.discovery.ModAnnotationScanner;

/**
 * Pins which window constructs a traditional-MinecraftForge {@code @Mod} on a client.
 *
 * <p>The carriers disagree and the merged base can only carry one call site. NeoForge's
 * {@code ClientModLoader.begin()} runs in {@code Main.main}, before {@code new Minecraft}; traditional
 * MinecraftForge's is {@code begin(Minecraft, PackRepository, ReloadableResourceManager)}, which exists only
 * inside {@code Minecraft.<init>}. The kernel ran both in NeoForge's window, so a MinecraftForge mod that reads
 * {@code Minecraft.getInstance()} in its constructor got null where its own loader gives it the game — Simple
 * Voice Chat cached it and died on the next line, and the whole mod was withdrawn from ModList.
 *
 * <p>The decision is made BEFORE the constructor runs, and that is the part worth pinning: a constructor is not a
 * pure function. Simple Voice Chat registers its key binds before it touches {@code Minecraft}, and its own guard
 * answers "Registered key binds twice" on a second attempt — so "let it fail, then retry it later" is not a
 * repair that exists here, however tempting the smaller diff looks.
 */
class ForgeConstructorWindowTest {
	@Test
	void aClientsTraditionalForgeModWaitsForTheGameConstructor() {
		assertTrue(KernelModLoader.constructedInTheGameConstructor(forge("voicechat"), Side.CLIENT));
	}

	/** NeoForge's own loader gives its constructors a null instance too, so moving them would be a change, not a fix. */
	@Test
	void aNeoForgeModIsLeftWhereItsOwnLoaderConstructsIt() {
		assertFalse(KernelModLoader.constructedInTheGameConstructor(
				new ModAnnotationScanner.ModClassInfo("com.example.Neo", "neomod", Ecosystem.NEOFORGE), Side.CLIENT));
	}

	/** A dedicated server has no {@code Minecraft}, and MinecraftForge's server path uses this same window. */
	@Test
	void aDedicatedServerKeepsTheWindowItAlreadyHad() {
		assertFalse(KernelModLoader.constructedInTheGameConstructor(forge("voicechat"), Side.DEDICATED_SERVER));
	}

	@Test
	void theSwitchPutsThemBackInTheEarlyWindow() {
		String previous = System.getProperty(KernelModLoader.DEFERRAL_SWITCH);
		System.setProperty(KernelModLoader.DEFERRAL_SWITCH, "off");
		try {
			assertFalse(KernelModLoader.constructedInTheGameConstructor(forge("voicechat"), Side.CLIENT));
		} finally {
			if (previous == null) System.clearProperty(KernelModLoader.DEFERRAL_SWITCH);
			else System.setProperty(KernelModLoader.DEFERRAL_SWITCH, previous);
		}
	}

	/**
	 * Order inside the constructor hook. MinecraftForge mods are constructed before Fabric's entrypoints in the
	 * early window too, and a Fabric mod that asks MinecraftForge's ModList about one of them must not be told it
	 * is absent because the kernel happened to run the two phases the other way round here.
	 */
	@Test
	void theDeferredForgeModsAreConstructedBeforeTheFabricEntrypoints() throws Exception {
		List<String> calls = orderedCallsIn("onClientEntrypoints");
		assumeTrue(calls.contains("constructDeferredForgeMods"), "KernelLifecycle not compiled yet");

		assertTrue(calls.indexOf("constructDeferredForgeMods") < calls.indexOf("runMainEntrypoints"), calls.toString());
	}

	private static ModAnnotationScanner.ModClassInfo forge(String modId) {
		return new ModAnnotationScanner.ModClassInfo("com.example.Mod", modId, Ecosystem.FORGE);
	}

	private static List<String> orderedCallsIn(String methodName) throws Exception {
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main",
				"net", "forbric", "kernel", "boot", "KernelLifecycle.class");
		if (!Files.isRegularFile(compiled)) return List.of();

		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(compiled)).accept(node, 0);
		List<String> calls = new ArrayList<>();
		for (MethodNode method : node.methods) {
			if (!methodName.equals(method.name)) continue;
			for (AbstractInsnNode insn : method.instructions.toArray()) {
				if (insn instanceof MethodInsnNode call) calls.add(call.name);
			}
		}
		return calls;
	}
}
