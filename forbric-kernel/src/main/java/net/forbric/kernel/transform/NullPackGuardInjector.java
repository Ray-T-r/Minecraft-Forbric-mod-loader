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
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.boot.KernelPackRepair;
import net.forbric.kernel.util.ForbricLog;

/**
 * Skips a {@code null} pack at the repository boundary instead of taking the world down with it.
 *
 * <p>{@code PackRepository.discoverAvailable} hands each {@code RepositorySource} a consumer and calls
 * {@code streamSelfAndChildren()} on whatever arrives. A source that emits {@code null} therefore kills world
 * loading with an NPE naming neither the source nor the pack. Vanilla's own sources null-check before calling the
 * consumer, and NeoForge's {@code ResourcePackLoader} null-checks its own {@code readMetaAndCreate} result and
 * files a loading issue instead — so the discipline is already the contract everywhere except in third-party
 * sources, where it is simply missing. Tectonic's is one such: it stores the result of
 * {@code Pack.readMetaAndCreate} and passes it straight through.
 *
 * <p>This is a BACKSTOP, not the fix. The reason a merged base produces a null pack in the first place is
 * repaired by {@link PackOverlayMutabilityInjector}, and it has to be, because skipping alone would trade a crash
 * for a silently missing pack and quietly wrong terrain generation. The pairing, and why both are needed, is
 * written down in {@link KernelPackRepair}.
 *
 * <p>Matched STRUCTURALLY, never by the lambda's synthesised name: a {@code private static} method taking
 * {@code (Map, Pack)} whose first instruction loads the pack and calls {@code streamSelfAndChildren} on it. The
 * prologue is four instructions and one {@code F_SAME} frame — reachable with the locals the method was entered
 * with and an empty stack, so it is self-contained and no existing frame changes meaning.
 */
public final class NullPackGuardInjector implements ClassTransformer {
	private static final String TARGET = "net.minecraft.server.packs.repository.PackRepository";
	private static final String PACK = "net/minecraft/server/packs/repository/Pack";
	private static final String STREAM = "streamSelfAndChildren";
	private static final String STREAM_DESC = "()Ljava/util/stream/Stream;";
	private static final String CONSUMER_DESC = "(Ljava/util/Map;L" + PACK + ";)V";

	private static final String HOOK_OWNER = "net/forbric/kernel/boot/KernelPackRepair";
	private static final String HOOK_NAME = "nullPackSkipped";
	private static final String HOOK_DESC = "()V";

	@Override
	public String name() {
		return "forbric-null-pack-guard";
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		if (!TARGET.equals(className) || !KernelPackRepair.enabled()) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		int guarded = 0;
		for (MethodNode method : node.methods) {
			if (!consumesAPack(method)) continue;
			guard(method);
			guarded++;
		}
		if (guarded == 0) {
			ForbricLog.debug("[Forbric/PackRepair] no null-pack consumer found in %s — nothing to guard", TARGET);
			return classBytes;
		}

		ForbricLog.debug("[Forbric/PackRepair] guarded %d repository consumer(s) against a null pack", guarded);
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** The {@code (Map, Pack)} consumer whose body starts by streaming the pack — the one that NPEs on null. */
	private static boolean consumesAPack(MethodNode method) {
		if ((method.access & Opcodes.ACC_STATIC) == 0) return false;
		if (!CONSUMER_DESC.equals(method.desc)) return false;

		AbstractInsnNode load = null;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn.getOpcode() < 0) continue;
			if (load == null) {
				load = insn;
				continue;
			}
			return load instanceof VarInsnNode var && var.getOpcode() == Opcodes.ALOAD && var.var == packSlot()
					&& insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
					&& PACK.equals(call.owner) && STREAM.equals(call.name) && STREAM_DESC.equals(call.desc);
		}
		return false;
	}

	/** The pack is the second parameter of a static {@code (Map, Pack)} method, and both are references. */
	private static int packSlot() {
		return Type.getArgumentTypes(CONSUMER_DESC).length - 1;
	}

	private static void guard(MethodNode method) {
		LabelNode ok = new LabelNode();
		InsnList prologue = new InsnList();
		prologue.add(new VarInsnNode(Opcodes.ALOAD, packSlot()));
		prologue.add(new JumpInsnNode(Opcodes.IFNONNULL, ok));
		prologue.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_NAME, HOOK_DESC, false));
		prologue.add(new InsnNode(Opcodes.RETURN));
		prologue.add(ok);
		// Same locals the method was entered with, empty stack — nothing before this point can have changed either.
		prologue.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));

		method.instructions.insert(prologue);
		method.maxStack = Math.max(method.maxStack, 1);
	}
}
