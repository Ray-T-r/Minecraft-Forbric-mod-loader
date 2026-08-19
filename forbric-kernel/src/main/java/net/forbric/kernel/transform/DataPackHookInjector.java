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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Gives the kernel the server datapack {@code PackRepository}, by prepending one call to the head of
 * {@code ResourcePackLoader.populatePackRepository(PackRepository, PackType, boolean)}.
 *
 * <p>That method is the genuine loader's own choke point for mod packs and it SURVIVED the byte merge — the merged
 * base's {@code ServerPacksSource.createPackRepository} still calls it, which is how a mod's
 * {@code AddPackFindersEvent} handler runs today. What does not survive is its payload: the mod packs come from
 * {@code ModList.get().getModFiles()}, which the kernel leaves empty, so the call adds nothing. Hooking here rather
 * than at {@code ServerPacksSource} keeps the kernel's packs in exactly the position NeoForge puts its own —
 * before {@code AddPackFindersEvent}, so a mod's explicitly-registered builtin pack still lands on top of its own
 * root data.
 *
 * <p>PREPENDS, never replaces: the rest of the method is the {@code AddPackFindersEvent} post, and Tectonic's
 * builtin datapack is registered from it. Replacing the body the way {@link ClientPackHookInjector} does would take
 * that away.
 *
 * <p>Frame-safe by construction: two {@code ALOAD}s and a static call at the head, no new locals and no branch, so
 * every existing stack-map frame still describes the same state. {@code COMPUTE_MAXS} because {@code max_stack}
 * may need to reach 2.
 */
public final class DataPackHookInjector implements ClassTransformer {
	private static final String TARGET = "net.neoforged.neoforge.resource.ResourcePackLoader";
	private static final String METHOD = "populatePackRepository";
	private static final String DESC =
			"(Lnet/minecraft/server/packs/repository/PackRepository;Lnet/minecraft/server/packs/PackType;Z)V";

	private static final String HOOK_OWNER = "net/forbric/kernel/boot/KernelLifecycle";
	private static final String HOOK_NAME = "onServerDataPacks";
	// Boot-side hooks cannot name net.minecraft types at compile time, so both parameters are Object. Passing a
	// PackRepository and a PackType into Object parameters is a widening reference conversion — the verifier is fine.
	private static final String HOOK_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)V";

	@Override
	public String name() {
		return "forbric-data-pack-hook";
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		if (!TARGET.equals(className)) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		boolean changed = false;
		for (MethodNode m : node.methods) {
			if (!METHOD.equals(m.name) || !DESC.equals(m.desc)) continue;
			InsnList head = new InsnList();
			head.add(new VarInsnNode(Opcodes.ALOAD, 0)); // PackRepository (the method is static)
			head.add(new VarInsnNode(Opcodes.ALOAD, 1)); // PackType
			head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_NAME, HOOK_DESC, false));
			m.instructions.insert(head);
			changed = true;
		}
		if (!changed) {
			// The anchor is gone: NeoForge changed the signature, or the merge stopped splicing it. Say so — the
			// failure it guards is silent, and looks like a bug in whichever mod first misses its own data.
			ForbricLog.warn("[Forbric/DataPacks] %s.%s%s no longer exists — Forge-family mods' own data/ is UNSERVED; "
					+ "re-derive DataPackHookInjector's target", TARGET, METHOD, DESC);
			return classBytes;
		}

		ForbricLog.info("[Forbric/DataPacks] hooked %s.%s — the kernel now serves Forge-family mods' own data/ to "
				+ "the server datapack repository", TARGET, METHOD);
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}
}
