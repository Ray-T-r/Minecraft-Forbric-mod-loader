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

import net.forbric.kernel.util.ForbricLog;

/**
 * Redirects each ecosystem's {@code ClientModLoader.setupModResourcePacks(PackRepository)} into the kernel, so the
 * kernel can serve the ecosystem jars' assets to the REAL client {@code PackRepository}.
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

	private static final String[] OWNERS = {
		"net.neoforged.neoforge.client.loading.ClientModLoader",
		"net.minecraftforge.client.loading.ClientModLoader",
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
			InsnList body = new InsnList();
			body.add(new VarInsnNode(Opcodes.ALOAD, 0)); // the PackRepository (the method is static)
			body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_NAME, HOOK_DESC, false));
			body.add(new InsnNode(Opcodes.RETURN));
			m.instructions = body;
			m.tryCatchBlocks.clear();
			if (m.localVariables != null) m.localVariables.clear();
			m.maxStack = 1;
			m.maxLocals = 1;
			changed = true;
			ForbricLog.info("[Forbric/ClientPacks] redirected %s.%s → KernelLifecycle.%s — the kernel now owns client "
					+ "mod resource packs", className, METHOD, HOOK_NAME);
		}
		if (!changed) return classBytes;

		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}
}
