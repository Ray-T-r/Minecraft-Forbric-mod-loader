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
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Fires the Fabric {@code client} entrypoints from inside {@code Minecraft.<init>}, at the same window Fabric itself
 * uses — after the {@code Minecraft} singleton is set but before {@code Options} is created.
 *
 * <p>The kernel used to run client entrypoints in its pre-{@code Minecraft} registration window (with the registries
 * unfrozen). But {@code Minecraft.getInstance()} is still null there, so a client entrypoint that touches the
 * instance NPEs — e.g. Fabric keymapping registration reads {@code Minecraft.getInstance().options}, and Jade's
 * {@code JadeClient.init} crashed on it, leaving its keybinds null and taking down the client on world-load. Fabric
 * fires client entrypoints from {@code Minecraft.<init>} precisely because {@code getInstance()} must be live
 * (instance already assigned) while {@code options} must still be null (keymapping registration rejects a built
 * {@code Options} with "GameOptions has already been initialised").
 *
 * <p>The injection point is the first {@code new net/minecraft/client/Options} in the constructor: the singleton was
 * assigned earlier in the same {@code <init>}, and {@code Options} has not been constructed yet.
 *
 * <p><b>The call is Fabric's own.</b> What is inserted is {@code Hooks.startClient(this.gameDirectory, this)} — the
 * call Fabric Loader patches into this constructor — and the kernel's client-entrypoint window runs from inside it.
 * Mods anchor on that call: owo's {@code MinecraftMixin} injects after it to freeze its channels once every client
 * entrypoint has run, in a {@code @Group(min = 1)} that failed outright when the kernel inserted its own
 * {@code KernelLifecycle.onClientEntrypoints()} there instead. {@code -Dforbric.fabricHooks=off} inserts that bare
 * call again. Either form adds no branch and leaves the stack as it found it, so the frames stay valid; the Fabric
 * form needs two more operand slots while it builds its arguments.
 */
public final class ClientEntrypointHookInjector implements ClassTransformer {
	private static final String MINECRAFT = "net.minecraft.client.Minecraft";
	private static final String OPTIONS = "net/minecraft/client/Options";
	private static final String HOOK_OWNER = "net/forbric/kernel/boot/KernelLifecycle";
	private static final String HOOK_NAME = "onClientEntrypoints";
	/** The field Fabric passes as {@code startClient}'s run directory; assigned well before the first Options. */
	private static final String GAME_DIRECTORY = "gameDirectory";
	private static final String FILE_DESC = "Ljava/io/File;";

	@Override
	public String name() {
		return "forbric-client-entrypoint-hook";
	}

	@Override
	public AnchorSet anchors() {
		return AnchorSet.of(new AnchorSet.Anchor(MINECRAFT, AnchorSet.Severity.REQUIRED,
				"Fabric mods' client entrypoints would never run: no keybinds, no client-side registration, no "
						+ "renderers -- and, because this transformer returns the class untouched when the anchor "
						+ "is gone, no error and no log line either"));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0 || !MINECRAFT.equals(className)) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		boolean changed = false;
		for (MethodNode m : node.methods) {
			if (!m.name.equals("<init>")) continue;
			AbstractInsnNode newOptions = firstNewOptions(m);
			if (newOptions == null) continue;
			if (LifecycleHookInjector.fabricHooksEnabled()) {
				m.instructions.insertBefore(newOptions, fabricStartClient(node));
				// aload_0 + the File, on top of whatever is already there: +2 at the peak, balanced after the call.
				m.maxStack += 2;
			} else {
				m.instructions.insertBefore(newOptions,
						new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_NAME, "()V", false));
			}
			changed = true;
			ForbricLog.info("[Forbric/Fabric] wired client-entrypoint hook into Minecraft.<init> (before Options) — "
					+ "Fabric client entrypoints now fire with a live Minecraft.getInstance()");
			break;
		}
		if (!changed) return classBytes;

		// ClassWriter(0): the inserted code adds no branch target and leaves the stack as it found it, so the original
		// frames stay valid (their offsets shift, which ASM handles on write); max stack is raised by hand above.
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/**
	 * {@code Hooks.startClient(this.gameDirectory, this)} — Fabric's arguments. A base without the field passes
	 * {@code null}, which {@code Hooks} accepts as Fabric does (it means "the working directory"); reading a field
	 * that is not there would fail {@code Minecraft.<init>} itself.
	 */
	private static InsnList fabricStartClient(ClassNode minecraft) {
		InsnList call = new InsnList();
		if (hasGameDirectory(minecraft)) {
			call.add(new VarInsnNode(Opcodes.ALOAD, 0));
			call.add(new FieldInsnNode(Opcodes.GETFIELD, minecraft.name, GAME_DIRECTORY, FILE_DESC));
		} else {
			call.add(new InsnNode(Opcodes.ACONST_NULL));
		}
		call.add(new VarInsnNode(Opcodes.ALOAD, 0));
		call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LifecycleHookInjector.FABRIC_HOOKS, "startClient",
				LifecycleHookInjector.FABRIC_HOOK_DESC, false));
		return call;
	}

	private static boolean hasGameDirectory(ClassNode minecraft) {
		for (FieldNode f : minecraft.fields) {
			if (GAME_DIRECTORY.equals(f.name) && FILE_DESC.equals(f.desc) && (f.access & Opcodes.ACC_STATIC) == 0) return true;
		}
		return false;
	}

	private static AbstractInsnNode firstNewOptions(MethodNode ctor) {
		for (AbstractInsnNode insn = ctor.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() == Opcodes.NEW && ((TypeInsnNode) insn).desc.equals(OPTIONS)) return insn;
		}
		return null;
	}
}
