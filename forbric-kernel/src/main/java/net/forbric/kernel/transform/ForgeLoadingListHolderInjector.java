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
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Makes MinecraftForge's {@code LoadingModList} immune to WHEN it is first touched, by having its lazy holder
 * read the kernel's published list instead of a field the genuine loader would have filled.
 *
 * <p>The holder's initializer is:
 *
 * <pre>{@code
 *    0: new           LoadingModListImpl
 *    3: dup
 *    4: getstatic     LoadingModListImpl.temp : ModSorter$State;
 *    7: invokevirtual ModSorter$State.files:()Ljava/util/List;      // NPE when temp is null
 *   10: getstatic     LoadingModListImpl.temp : ModSorter$State;
 *   13: invokevirtual ModSorter$State.mods:()Ljava/util/List;
 *   16: invokespecial LoadingModListImpl."<init>":(List;List;)V
 *   19: putstatic     INSTANCE
 *   22: return
 * }</pre>
 *
 * <p>and it is rewritten to:
 *
 * <pre>{@code
 *    0: new           LoadingModListImpl
 *    3: dup
 *    4: invokestatic  net/forbric/api/ForgeLoadingList.modFiles:()Ljava/util/List;
 *    7: invokestatic  net/forbric/api/ForgeLoadingList.modInfos:()Ljava/util/List;
 *   10: invokespecial LoadingModListImpl."<init>":(List;List;)V
 *   13: putstatic     INSTANCE
 *   16: return
 * }</pre>
 *
 * <p>Why this and not a null guard on {@code temp}: a class initializer runs ONCE and has an empty exception
 * table, and {@code INSTANCE} is {@code static final} with this as its only writer. So the very first read
 * decides the list for the whole run. Injecting {@code if (temp == null) temp = new State(List.of(), List.of())}
 * would replace the crash with an empty list frozen in place — and the seeder's own
 * {@code if (temp.get(null) == null)} guard would then short-circuit, so the REAL list would never be written
 * while the log still said it had been. An empty MinecraftForge mod list is not a smaller failure than a crash:
 * it is what makes the multiplayer handshake tell every peer this instance runs no mods.
 *
 * <p>The rewrite names only {@code java.util.List} and {@code net.forbric.api.ForgeLoadingList}, both of which
 * the parent loader has already defined (see {@code DelegationPolicy}). So the initializer can run in the middle
 * of Mixin's {@code prepareConfigs} — where a guest mixin plugin asking "is mod X present" is exactly the kind of
 * caller that arrives too early — without defining a game-side class or re-entering {@code select()}. The two
 * {@code getstatic temp} reads disappear entirely: whether {@code temp} is null stops mattering to this class.
 *
 * <p>Branch-free, so no stack map frame has to be written for a merged control flow, and {@code maxLocals} stays
 * 0 — the same reason {@code ForeignModPresenceInjector} uses an {@code IOR} rather than a short circuit.
 *
 * <p>This is MinecraftForge-only and has no {@link net.forbric.api.ForeignType} row: NeoForge's
 * {@code LoadingModList} is a plain class holding a static field, with no lazy holder and no one-shot to poison,
 * and it is seeded directly.
 */
public final class ForgeLoadingListHolderInjector implements ClassTransformer {
	private static final String HOLDER = "net.minecraftforge.fml.loading.LoadingModListImpl$1LazyInit";
	private static final String IMPL_INTERNAL = "net/minecraftforge/fml/loading/LoadingModListImpl";
	private static final String HOLDER_INTERNAL = IMPL_INTERNAL + "$1LazyInit";
	private static final String TEMP = "temp";
	private static final String INSTANCE = "INSTANCE";
	private static final String LIST_DESC = "()Ljava/util/List;";
	private static final String IMPL_CTOR_DESC = "(Ljava/util/List;Ljava/util/List;)V";
	private static final String FORGE_LOADING_LIST = "net/forbric/api/ForgeLoadingList";

	/** What the seam looks like on the carrier this was written against. Any drift is a refusal, not a warning. */
	private static final int EXPECTED_TEMP_READS = 2;

	@Override
	public String name() {
		return "forbric-forge-loading-list-holder";
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		if (!HOLDER.equals(className)) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		MethodNode clinit = null;
		for (MethodNode m : node.methods) {
			if ("<clinit>".equals(m.name) && "()V".equals(m.desc)) {
				clinit = m;
				break;
			}
		}
		if (clinit == null || clinit.instructions.size() == 0) {
			throw refuse("its <clinit> is missing or empty");
		}

		// Count what the replacement is equivalent TO. Each of these is load-bearing: drop the class-initializer
		// shape and "replace the whole body" stops being a rewrite of the same computation and becomes a guess.
		int tempReads = 0;
		int instanceWrites = 0;
		int news = 0;
		int ctorCalls = 0;
		for (var insn = clinit.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof FieldInsnNode field) {
				if (insn.getOpcode() == Opcodes.GETSTATIC && IMPL_INTERNAL.equals(field.owner)
						&& TEMP.equals(field.name)) {
					tempReads++;
				} else if (insn.getOpcode() == Opcodes.PUTSTATIC && HOLDER_INTERNAL.equals(field.owner)
						&& INSTANCE.equals(field.name)) {
					instanceWrites++;
				}
			} else if (insn instanceof TypeInsnNode type && insn.getOpcode() == Opcodes.NEW
					&& IMPL_INTERNAL.equals(type.desc)) {
				news++;
			} else if (insn instanceof MethodInsnNode call && insn.getOpcode() == Opcodes.INVOKESPECIAL
					&& IMPL_INTERNAL.equals(call.owner) && "<init>".equals(call.name)
					&& IMPL_CTOR_DESC.equals(call.desc)) {
				ctorCalls++;
			}
		}
		if (tempReads != EXPECTED_TEMP_READS || instanceWrites != 1 || news != 1 || ctorCalls != 1) {
			throw refuse(String.format("expected %d getstatic %s.%s + 1 new/1 <init>%s/1 putstatic %s, found %d/%d/%d/%d",
					EXPECTED_TEMP_READS, IMPL_INTERNAL, TEMP, IMPL_CTOR_DESC, INSTANCE,
					tempReads, news, ctorCalls, instanceWrites));
		}

		// INSTANCE being final is what makes the FIRST value permanent, which is the entire reason this transform
		// exists rather than a late repair. If it ever stops being final there is a second writer, and replacing
		// the whole body is no longer equivalent.
		FieldNode instance = null;
		for (FieldNode f : node.fields) {
			if (INSTANCE.equals(f.name)) {
				instance = f;
				break;
			}
		}
		if (instance == null || (instance.access & Opcodes.ACC_STATIC) == 0
				|| (instance.access & Opcodes.ACC_FINAL) == 0) {
			throw refuse(instance == null ? "it has no " + INSTANCE + " field"
					: INSTANCE + " is no longer static final (access 0x"
							+ Integer.toHexString(instance.access) + "), so something else writes it too");
		}

		clinit.instructions.clear();
		clinit.tryCatchBlocks.clear();
		clinit.localVariables = null;
		clinit.visitTypeInsn(Opcodes.NEW, IMPL_INTERNAL);
		clinit.visitInsn(Opcodes.DUP);
		clinit.visitMethodInsn(Opcodes.INVOKESTATIC, FORGE_LOADING_LIST, "modFiles", LIST_DESC, false);
		clinit.visitMethodInsn(Opcodes.INVOKESTATIC, FORGE_LOADING_LIST, "modInfos", LIST_DESC, false);
		clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, IMPL_INTERNAL, "<init>", IMPL_CTOR_DESC, false);
		clinit.visitFieldInsn(Opcodes.PUTSTATIC, HOLDER_INTERNAL, INSTANCE,
				"L" + IMPL_INTERNAL + ";");
		clinit.visitInsn(Opcodes.RETURN);

		ForbricLog.info("[Forbric/ForgeList] %s now builds MinecraftForge's LoadingModList from the kernel's "
				+ "published list instead of LoadingModListImpl.temp — that field is filled in the mod-loading "
				+ "window, and this initializer is a one-shot with no exception table, so any caller arriving "
				+ "before it (a mixin plugin during prepareConfigs, ServerStatusPing) used to leave the class "
				+ "permanently erroneous and MinecraftForge's mod list empty for the whole run", className);

		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static IllegalStateException refuse(String what) {
		return new IllegalStateException("ForgeLoadingListHolderInjector: " + HOLDER + " does not have the "
				+ "lazy-holder shape this rewrite is equivalent to — " + what + ". Letting it through means the "
				+ "holder keeps reading LoadingModListImpl.temp, so the first caller to reach LoadingModList "
				+ "before the kernel seeds that field poisons the class for the run while the seeder still logs "
				+ "success; refusing to boot. Re-read LoadingModListImpl$1LazyInit on the new carrier and update "
				+ "the rewrite.");
	}
}
