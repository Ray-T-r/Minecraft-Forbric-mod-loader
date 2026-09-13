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
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Restores the filter that keeps hidden packs out of the resource-pack screen.
 *
 * <h2>The casualty</h2>
 *
 * <p>A {@code Pack} carries {@code isHidden}, and it gates LISTING only: {@code PackRepository.getAvailableIds}
 * and {@code getSelectedIds} filter on it, while {@code openAllSelected} and {@code getSelectedPacks} do not, and
 * {@code rebuildSelected} re-inserts every {@code isRequired()} pack whether hidden or not. That is how a mod's
 * own assets are meant to work — always applied, never a row in the player's list.
 *
 * <p>The screen side of it did not survive the byte merge. The two Forge families implement it differently:
 * MinecraftForge filters inside {@code TransferableSelectionList.updateList}, NeoForge patches three lambdas into
 * {@code PackSelectionModel}. The merge took NeoForge's {@code Pack} (so {@code isHidden} exists) and VANILLA's
 * {@code TransferableSelectionList} and {@code PackSelectionModel} (so nothing reads it) — a per-class
 * merge-granularity casualty, the same shape as the orphaned {@code ResourcePackLoader.findResourcePacks} already
 * recorded in {@code KernelClientPacks}. {@code PackSelectionModel$Entry.notHidden()} is in the merged jar with
 * zero callers anywhere.
 *
 * <p>The symptom: every ecosystem's asset pack — ten of them on a real install — listed in the player's
 * resource-pack screen as rows they did not add and cannot turn off.
 *
 * <h2>The repair</h2>
 *
 * <p>MinecraftForge's call site, byte for byte, because it is the smaller of the two: one {@code Stream.filter}
 * on a method that both columns and the datapack screen already funnel through, against a {@code notHidden()}
 * that already exists on the interface (default {@code true}) and on {@code EntryBase} ({@code !pack.isHidden()}).
 * NeoForge's route needs three edits, two of them inside a constructor.
 */
public final class PackScreenHiddenFilterInjector implements ClassTransformer {
	static final String LIST = "net/minecraft/client/gui/screens/packs/TransferableSelectionList";
	static final String ENTRY = "net/minecraft/client/gui/screens/packs/PackSelectionModel$Entry";
	static final String UPDATE = "updateList";
	static final String UPDATE_DESC = "(Ljava/util/stream/Stream;Lnet/minecraft/client/gui/screens/packs/"
			+ "PackSelectionModel$EntryBase;)V";
	private static final String STREAM = "java/util/stream/Stream";

	private static final Handle METAFACTORY = new Handle(Opcodes.H_INVOKESTATIC,
			"java/lang/invoke/LambdaMetafactory", "metafactory",
			"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
					+ "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
					+ "Ljava/lang/invoke/CallSite;", false);

	@Override
	public String name() {
		return "forbric-pack-screen-hidden-filter";
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		if (!LIST.equals(className.replace('.', '/'))) return classBytes;
		try {
			ClassNode node = new ClassNode();
			new ClassReader(classBytes).accept(node, 0);
			MethodNode update = null;
			for (MethodNode method : node.methods) {
				if (UPDATE.equals(method.name) && UPDATE_DESC.equals(method.desc)) update = method;
			}
			if (update == null || update.instructions == null) return classBytes;

			// The shape this pass understands, and the only one it will touch: the stream is pushed, a Consumer
			// is built, forEach consumes both. Anything else means the method was rewritten and a blind insert
			// would be a guess.
			MethodInsnNode forEach = null;
			for (AbstractInsnNode insn : update.instructions) {
				if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEINTERFACE
						&& STREAM.equals(call.owner) && "forEach".equals(call.name)) {
					forEach = call;
					break;
				}
				if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEINTERFACE
						&& STREAM.equals(call.owner) && "filter".equals(call.name)) {
					// Already filtered — a MinecraftForge-patched carrier, or a second pass.
					return classBytes;
				}
			}
			if (forEach == null) return classBytes;

			AbstractInsnNode push = streamPush(update, forEach);
			if (push == null) {
				ForbricLog.warn("[Forbric/PackScreen] %s.%s no longer pushes its stream where this pass can see it "
						+ "— hidden packs will be listed in the resource-pack screen", LIST, UPDATE);
				return classBytes;
			}

			InsnList filter = new InsnList();
			filter.add(new InvokeDynamicInsnNode("test", "()Ljava/util/function/Predicate;", METAFACTORY,
					Type.getMethodType("(Ljava/lang/Object;)Z"),
					new Handle(Opcodes.H_INVOKEINTERFACE, ENTRY, "notHidden", "()Z", true),
					Type.getMethodType("(L" + ENTRY + ";)Z")));
			filter.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, STREAM, "filter",
					"(Ljava/util/function/Predicate;)Ljava/util/stream/Stream;", true));
			update.instructions.insert(push, filter);
			update.maxStack = Math.max(update.maxStack, 4);

			ClassWriter writer = new ClassWriter(0);
			node.accept(writer);
			ForbricLog.info("[Forbric/PackScreen] restored the hidden-pack filter to %s.%s — the merge kept "
					+ "NeoForge's Pack (so isHidden exists) and vanilla's screen (so nothing read it), which put "
					+ "every ecosystem's asset pack in the player's resource-pack list as a row they cannot "
					+ "remove", LIST, UPDATE);
			return writer.toByteArray();
		} catch (RuntimeException e) {
			ForbricLog.warn("[Forbric/PackScreen] could not restore the hidden-pack filter", e);
			return classBytes;
		}
	}

	/** The {@code ALOAD} of the stream parameter feeding this {@code forEach}, or null if the shape moved. */
	private static AbstractInsnNode streamPush(MethodNode update, MethodInsnNode forEach) {
		for (AbstractInsnNode insn = forEach.getPrevious(); insn != null; insn = insn.getPrevious()) {
			if (insn instanceof VarInsnNode var && var.getOpcode() == Opcodes.ALOAD && var.var == 1) return insn;
		}
		return null;
	}
}
