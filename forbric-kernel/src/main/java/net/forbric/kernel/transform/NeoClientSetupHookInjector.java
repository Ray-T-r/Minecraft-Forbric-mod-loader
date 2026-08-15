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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Fires the NeoForge client setup lifecycle from inside {@code Minecraft.<init>}, immediately AFTER
 * {@code this.options} is assigned.
 *
 * <p>The two ecosystems want windows that cannot be the same one, which is why this exists alongside
 * {@link ClientEntrypointHookInjector} rather than sharing its anchor:
 * <ul>
 *   <li>Fabric's client entrypoints must run while {@code options} is still NULL — keymapping registration rejects
 *       a built {@code Options} with "GameOptions has already been initialised". That hook anchors on the
 *       {@code new Options} itself.</li>
 *   <li>NeoForge's {@code FMLClientSetupEvent} must run once {@code options} EXISTS. Genuine NeoForge runs its
 *       client mod loading later in the same constructor, and mods rely on it: JourneyMap's setup loads its config,
 *       which validates against {@code Minecraft.getInstance().options.renderDistance()} and NPE'd on the null,
 *       leaving every JourneyMap property null and killing the render loop the moment the world drew.</li>
 * </ul>
 *
 * <p>So the order inside one constructor is: singleton assigned → Fabric client entrypoints → {@code Options}
 * built → NeoForge client setup. Both ecosystems get the state they were written against.
 *
 * <p><b>The anchor is NeoForge's own call site</b>: the {@code ClientModLoader.finish()} invocation the merged base
 * already carries in {@code Minecraft.<init>}. That is precisely where genuine NeoForge completes client mod
 * loading, so anchoring there inherits its timing instead of guessing at it — and the kernel already neuters
 * {@code finish()}'s body (it would re-drive the discovery the kernel owns), so the call site is otherwise inert.
 *
 * <p>Guessing was tried and does not converge: anchoring just after {@code PUTFIELD Minecraft.options} cleared
 * JourneyMap's {@code options.renderDistance()} NPE only to hit {@code font.isBidirectional()} on the next field,
 * assigned a few hundred instructions later. Every such field is another round; {@code finish()} is after all of
 * them by construction.
 *
 * <p>A no-arg void {@code INVOKESTATIC} inserted before it is stack-neutral (no operands, no branch), so it needs
 * no frame or max-stack change.
 */
public final class NeoClientSetupHookInjector implements ClassTransformer {
	private static final String MINECRAFT = "net.minecraft.client.Minecraft";
	private static final String CLIENT_MOD_LOADER = "net/neoforged/neoforge/client/loading/ClientModLoader";
	private static final String FINISH = "finish";
	private static final String HOOK_OWNER = "net/forbric/kernel/boot/KernelLifecycle";
	private static final String HOOK_NAME = "onNeoClientSetup";

	@Override
	public String name() {
		return "forbric-neo-client-setup-hook";
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0 || !MINECRAFT.equals(className)) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		boolean changed = false;
		for (MethodNode method : node.methods) {
			if (!"<init>".equals(method.name) || method.instructions == null) continue;

			for (AbstractInsnNode insn : method.instructions.toArray()) {
				if (!(insn instanceof MethodInsnNode call)) continue;
				if (call.getOpcode() != Opcodes.INVOKESTATIC) continue;
				if (!CLIENT_MOD_LOADER.equals(call.owner) || !FINISH.equals(call.name)) continue;

				method.instructions.insertBefore(call,
						new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_NAME, "()V", false));
				changed = true;
				ForbricLog.debug("[Forbric/Transform] NeoForge client-setup hook installed at ClientModLoader.finish");
				break; // one call site; a second hook would only re-enter the once-only guard
			}
		}
		if (!changed) {
			// Not fatal on its own — KernelLifecycle's guard means the phases simply never fire — but it is the
			// difference between mods being set up and silently not, so it must not pass unnoticed.
			ForbricLog.warn("[Forbric/Transform] no ClientModLoader.finish() call in Minecraft.<init> — the NeoForge "
					+ "client setup lifecycle will NOT fire; mods that set up from FMLClientSetupEvent will do nothing");
			return classBytes;
		}

		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}
}
