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

import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;

/**
 * Points the mods button at the list that knows the whole instance.
 *
 * <p>The pause menu's mods button opens {@code net.neoforged.neoforge.client.gui.ModListScreen}, which lists
 * {@code ModList.get()} — every mod NeoForge loaded. On a NeoForge instance that is the complete answer. Here it
 * was three of sixteen, and no other family's screen does better: each reads its own loader's registry and is
 * right about it. So the button is re-pointed at {@code KernelModListScreen}, which reads {@code ModCatalog}.
 *
 * <p>BOTH families' screens are redirected, not just the one the merged button happens to call. The merged base
 * carries a lambda for each — {@code lambda$createPauseMenu$3} builds NeoForge's, {@code lambda$createPauseMenu$11}
 * builds MinecraftForge's — and which one the button is bound to is a byte-merge outcome, not a decision. Leaving
 * the loser in place would make this repair silently conditional on a merge detail that has changed before.
 *
 * <p>The rewrite is two instructions: the {@code NEW} and the {@code INVOKESPECIAL} of its {@code (Screen)}
 * constructor. Same stack shape, same descriptor, no branch and no frame — the button, its sprite, its tooltip and
 * its place in the layout are untouched, because none of them are what was wrong.
 */
public final class ModsButtonRedirector implements ClassTransformer {
	static final String KERNEL_SCREEN = "net/forbric/kernel/runtime/KernelModListScreen";
	private static final String SCREEN_CTOR = "(Lnet/minecraft/client/gui/screens/Screen;)V";

	/**
	 * The screens that carry a mods button. Both are client-only, so a dedicated server never reaches this pass.
	 */
	private static final Set<String> CARRIERS = Set.of(
			"net/minecraft/client/gui/screens/PauseScreen",
			"net/minecraft/client/gui/screens/TitleScreen");

	/** Named through {@link ForeignType} so neither family's spelling can be the one that quietly stops matching. */
	private static final Set<String> REPLACED = Set.of(
			ForeignType.MOD_LIST_SCREEN.internal(Ecosystem.NEOFORGE),
			ForeignType.MOD_LIST_SCREEN.internal(Ecosystem.FORGE));

	@Override
	public String name() {
		return "forbric-mods-button";
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		String internal = className.replace('.', '/');
		if (!CARRIERS.contains(internal)) return classBytes;
		try {
			ClassNode node = new ClassNode();
			new ClassReader(classBytes).accept(node, 0);
			int redirected = 0;
			for (MethodNode method : node.methods) {
				if (method.instructions == null) continue;
				for (AbstractInsnNode insn : method.instructions) {
					if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
							&& REPLACED.contains(type.desc)) {
						type.desc = KERNEL_SCREEN;
						redirected++;
					} else if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
							&& "<init>".equals(call.name) && SCREEN_CTOR.equals(call.desc)
							&& REPLACED.contains(call.owner)) {
						call.owner = KERNEL_SCREEN;
					}
				}
			}
			if (redirected == 0) return classBytes;
			ClassWriter writer = new ClassWriter(0);
			node.accept(writer);
			ForbricLog.info("[Forbric/ModsButton] %s's mods button now opens the unified list (%d construction "
					+ "site(s) re-pointed) — each family's own screen lists only its own family, which on this "
					+ "instance is never the whole answer", internal, redirected);
			return writer.toByteArray();
		} catch (RuntimeException e) {
			ForbricLog.warn("[Forbric/ModsButton] could not re-point " + internal + "'s mods button — it will open "
					+ "one family's list", e);
			return classBytes;
		}
	}
}
