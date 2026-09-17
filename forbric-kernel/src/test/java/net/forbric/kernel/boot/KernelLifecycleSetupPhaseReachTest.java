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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The MinecraftForge half of the setup lifecycle must be reachable on a pack that has no NeoForge mod.
 *
 * <p>This is a shape test on the compiled method rather than a behaviour test, because the behaviour needs a
 * live carrier: every call inside {@code fireModSetupLifecycle} resolves game classes reflectively, so the only
 * thing assertable off-game is the CONTROL FLOW — and the control flow is exactly what was wrong. The method
 * opened with {@code if (mods.isEmpty()) return;} over {@code publishedNeoMods()}, which on a classic
 * MinecraftForge pack is empty while {@code publishedForgeMods()} is full, so all four MinecraftForge phases
 * and NeoForge's own {@code RegistrationEvents.init} were skipped with no log line anywhere.
 *
 * <p>The invariant it pins: the only {@code RETURN} that may precede the first {@code fireForgeSetupPhase} call
 * is the SIDE guard. The client's phases all live in {@code fireClientSetupLifecycle} now, common setup
 * included, so a return on {@code side.isClient()} is correct here — a return on "no NeoForge mods" is the
 * defect, and the two are told apart by what the branch tests.
 */
class KernelLifecycleSetupPhaseReachTest {
	@Test
	void theMinecraftForgePhasesAreReachableWithNoNeoForgeModLoaded() throws Exception {
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main",
				"net", "forbric", "kernel", "boot", "KernelLifecycle.class");
		assumeTrue(Files.isRegularFile(compiled), "KernelLifecycle not compiled yet");

		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(compiled)).accept(node, 0);
		MethodNode setup = node.methods.stream().filter(m -> "fireModSetupLifecycle".equals(m.name)).findFirst()
				.orElseThrow(() -> new AssertionError("fireModSetupLifecycle is gone"));

		int firstReturn = -1;
		int firstForgePhase = -1;
		int firstIsClient = -1;
		int firstIsEmpty = -1;
		AbstractInsnNode[] insns = setup.instructions.toArray();
		for (int i = 0; i < insns.length; i++) {
			if (firstReturn < 0 && insns[i].getOpcode() == Opcodes.RETURN) firstReturn = i;
			if (!(insns[i] instanceof MethodInsnNode call)) continue;
			if (firstForgePhase < 0 && "fireForgeSetupPhase".equals(call.name)) firstForgePhase = i;
			if (firstIsClient < 0 && "isClient".equals(call.name)) firstIsClient = i;
			if (firstIsEmpty < 0 && "isEmpty".equals(call.name)) firstIsEmpty = i;
		}

		assertTrue(firstForgePhase >= 0,
				"fireModSetupLifecycle no longer posts any traditional-MinecraftForge setup phase");
		assertTrue(firstIsEmpty < 0 || firstIsEmpty > firstForgePhase,
				"fireModSetupLifecycle tests a collection for emptiness before it reaches the first "
						+ "fireForgeSetupPhase — a pack whose Forge-family mods are all traditional MinecraftForge "
						+ "then gets no FMLCommonSetupEvent at all, which is the 'the world came out looking "
						+ "vanilla' failure");
		assertTrue(firstReturn < 0 || firstReturn > firstForgePhase
						|| (firstIsClient >= 0 && firstIsClient < firstReturn),
				"the only early return allowed here is the side guard — the client's phases, common setup "
						+ "included, are posted from fireClientSetupLifecycle instead");
	}

	/**
	 * On the client, common setup must be posted from inside {@code Minecraft}'s constructor, before the sided
	 * phase.
	 *
	 * <p>{@code fireModSetupLifecycle} runs BEFORE {@code new Minecraft(...)}, so the singleton is still null
	 * there — and common setup is where a mod does its dist-guarded client initialisation, caching that singleton
	 * into a static field or handing work to its executor. Genuine NeoForge posts common setup from
	 * {@code ClientModLoader.finish()}, inside that constructor. A shape test again, and for the same reason: the
	 * calls resolve game classes reflectively, so the order of the phase constants is what is assertable.
	 */
	@Test
	void theClientPostsCommonSetupBeforeTheSidedPhase() throws Exception {
		MethodNode client = method("fireClientSetupLifecycle");

		int common = -1;
		int sided = -1;
		AbstractInsnNode[] insns = client.instructions.toArray();
		for (int i = 0; i < insns.length; i++) {
			if (!(insns[i] instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETSTATIC) continue;
			if (common < 0 && "FML_COMMON_SETUP_EVENT".equals(field.name)) common = i;
			if (sided < 0 && "FML_CLIENT_SETUP_EVENT".equals(field.name)) sided = i;
		}

		assertTrue(common >= 0,
				"fireClientSetupLifecycle no longer posts common setup — on the client nothing else does, and a "
						+ "mod caching Minecraft.getInstance() from it gets null");
		assertTrue(sided >= 0, "fireClientSetupLifecycle no longer posts client setup");
		assertTrue(common < sided, "common setup comes before the sided phase, as in CommonModLoader.load");
	}

	/**
	 * And the pre-Minecraft window must NOT post it any more, or it is posted twice — once into a null singleton.
	 */
	@Test
	void theWindowBeforeMinecraftExistsDoesNotPostClientCommonSetup() throws Exception {
		MethodNode setup = method("fireModSetupLifecycle");

		int isClient = -1;
		int common = -1;
		AbstractInsnNode[] insns = setup.instructions.toArray();
		for (int i = 0; i < insns.length; i++) {
			if (insns[i] instanceof MethodInsnNode call && isClient < 0 && "isClient".equals(call.name)) {
				isClient = i;
			}
			if (insns[i] instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
					&& common < 0 && "FML_COMMON_SETUP_EVENT".equals(field.name)) {
				common = i;
			}
		}

		assertTrue(isClient >= 0, "fireModSetupLifecycle no longer looks at the side at all");
		assertTrue(common < 0 || isClient < common,
				"the side is checked AFTER common setup is posted, so the client posts it here too — with "
						+ "Minecraft.getInstance() still null");
	}

	private static MethodNode method(String name) throws Exception {
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main",
				"net", "forbric", "kernel", "boot", "KernelLifecycle.class");
		assumeTrue(Files.isRegularFile(compiled), "KernelLifecycle not compiled yet");

		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(compiled)).accept(node, 0);
		return node.methods.stream().filter(m -> name.equals(m.name)).findFirst()
				.orElseThrow(() -> new AssertionError(name + " is gone"));
	}

	/**
	 * The guard that was removed from the caller has to exist in the callee instead, or a MinecraftForge-only
	 * pack pays eight DeferredWorkQueue builds and eight "posted FML … to 0 NeoForge mod(s)" lines.
	 */
	@Test
	void theNeoForgePhaseStillNoOpsWhenNoNeoForgeModIsLoaded() throws Exception {
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main",
				"net", "forbric", "kernel", "boot", "KernelLifecycle.class");
		assumeTrue(Files.isRegularFile(compiled), "KernelLifecycle not compiled yet");

		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(compiled)).accept(node, 0);
		MethodNode phase = node.methods.stream().filter(m -> "fireSetupPhase".equals(m.name)).findFirst()
				.orElseThrow(() -> new AssertionError("fireSetupPhase is gone"));

		int firstIsEmpty = -1;
		int firstForName = -1;
		AbstractInsnNode[] insns = phase.instructions.toArray();
		for (int i = 0; i < insns.length; i++) {
			if (!(insns[i] instanceof MethodInsnNode call)) continue;
			if (firstIsEmpty < 0 && "isEmpty".equals(call.name)) firstIsEmpty = i;
			if (firstForName < 0 && "forName".equals(call.name)) firstForName = i;
		}

		assertTrue(firstIsEmpty >= 0 && (firstForName < 0 || firstIsEmpty < firstForName),
				"fireSetupPhase must return on an empty NeoForge mod set BEFORE it resolves game classes and "
						+ "builds a DeferredWorkQueue — its caller no longer guards that case");
	}
}
