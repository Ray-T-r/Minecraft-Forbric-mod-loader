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

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;

/**
 * Redirects {@code ClientModLoader.setupModResourcePacks(PackRepository)} into the kernel, so the kernel can serve
 * the ecosystem jars' assets to the REAL client {@code PackRepository}.
 *
 * <p>{@code Minecraft.<init>} calls this genuine hook with the live repository, before the client's first resource
 * reload — exactly the point mod resources must be added. The kernel used to NEUTER it (letting the genuine client
 * loader's resource integration run would drag in the rest of the FancyModLoader lifecycle the kernel replaces), but
 * neutering also threw away the only well-timed handle on the repository, leaving every ecosystem asset unreachable.
 *
 * <p>The whole body is replaced with {@code KernelLifecycle.onClientResourcePacks(arg0); return;} — rewriting the
 * METHOD rather than the call site in {@code Minecraft.<init>} so any caller is covered and the
 * {@link LifecycleHookInjector}'s single-entry "required excision" gating stays untouched.
 */
public final class ClientPackHookInjector implements ClassTransformer {
	private static final String HOOK_OWNER = "net/forbric/kernel/boot/KernelLifecycle";
	private static final String HOOK_NAME = "onClientResourcePacks";
	private static final String METHOD = "setupModResourcePacks";
	private static final String DESC = "(Lnet/minecraft/server/packs/repository/PackRepository;)V";
	// The hook is BOOT-side and cannot name net.minecraft types at compile time, so it takes Object. Passing the
	// PackRepository into an Object parameter is a widening reference conversion — the verifier accepts it.
	private static final String HOOK_DESC = "(Ljava/lang/Object;)V";

	// NeoForge is the live one. MinecraftForge's ClientModLoader has NO setupModResourcePacks on the staged carrier
	// — it takes the repository in begin(Minecraft, PackRepository, ReloadableResourceManager) instead — so that
	// entry currently matches nothing. It is kept as a hedge for a base that flips which family wins this seam, the
	// same way LifecycleHookInjector keeps Forge's no-arg ServerModLoader.load. ClientPackHookInjectorTest asserts
	// BOTH halves of that, so if a carrier ever adds the method the hedge stops being inert and says so.
	private static final String[] OWNERS = {
		ForeignType.CLIENT_MOD_LOADER.binary(Ecosystem.NEOFORGE),
		ForeignType.CLIENT_MOD_LOADER.binary(Ecosystem.FORGE),
	};

	@Override
	public String name() {
		return "forbric-client-pack-hook";
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		boolean target = false;
		for (String owner : OWNERS) {
			if (owner.equals(className)) {
				target = true;
				break;
			}
		}
		if (!target) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		boolean changed = false;
		for (MethodNode m : node.methods) {
			if (!m.name.equals(METHOD) || !m.desc.equals(DESC)) continue;
			// PREPENDS, never replaces — the same shape DataPackHookInjector uses on the server side, and for the
			// same reason, learned the hard way here.
			//
			// This used to assign a whole new body: call the kernel hook, return. That threw away the one thing in
			// the original that the kernel does not replace. The carrier's body is
			//
			//     ResourcePackLoader.populatePackRepository(repo, CLIENT_RESOURCES, false)
			//     DataPackConfig.DEFAULT.addModPacks(getPackNames(SERVER_DATA))
			//
			// and populatePackRepository ends by constructing an AddPackFindersEvent and posting it through
			// ModLoader — which is how EVERY mod of both Forge families registers a built-in client resource pack.
			// With the body gone the event was never posted: an optional pack simply did not appear in the resource
			// pack screen, and an alwaysActive one left the mod rendering missing textures, with no crash, no log
			// and nothing naming the loader. The rest of the original is inert under the kernel (findResourcePacks
			// walks ModList.getModFiles(), which the kernel deliberately leaves empty), so keeping it costs a
			// no-op walk and buys back the event.
			InsnList prologue = new InsnList();
			prologue.add(new VarInsnNode(Opcodes.ALOAD, 0)); // the PackRepository (the method is static)
			prologue.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_NAME, HOOK_DESC, false));
			m.instructions.insert(prologue);
			m.maxStack = Math.max(m.maxStack, 1);
			changed = true;
			ForbricLog.info("[Forbric/ClientPacks] prepended KernelLifecycle.%s to %s.%s — the kernel serves the "
					+ "ecosystem jars' assets and the carrier's own body still posts AddPackFindersEvent, which is "
					+ "how mods register built-in client packs", HOOK_NAME, className, METHOD);
		}
		if (!changed) return classBytes;

		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}
}
