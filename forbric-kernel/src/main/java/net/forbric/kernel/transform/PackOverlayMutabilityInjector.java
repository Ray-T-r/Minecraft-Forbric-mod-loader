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

import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.boot.KernelPackRepair;
import net.forbric.kernel.util.ForbricLog;

/**
 * Stops NeoForge's overlay-merge patch from mutating a list fabric-api's mixin just made immutable.
 *
 * <p>The collision, and why it costs the whole world load, is in {@link KernelPackRepair}. This is the repair:
 *
 * <pre>
 *   overlaySet.addAll(neoOverlays…);      →   overlaySet = KernelPackRepair.concat(overlaySet, neoOverlays…);
 *   overlaySet = List.copyOf(overlaySet);     overlaySet = List.copyOf(overlaySet);   (unchanged)
 * </pre>
 *
 * <p><b>The edit deliberately deletes instructions rather than adding a store, and that is the whole design.</b>
 * The kernel's transform chain runs BEFORE Mixin, and {@code ForbricClassLoader.getPreMixinClassBytes} hands
 * Mixin the chain's output — so Mixin resolves its injection points against whatever this leaves behind.
 * fabric-api's {@code PackMixin} selects {@code @At("STORE")} on {@code overlaySet} with no {@code ordinal}, i.e.
 * EVERY matching store injects. Rewriting the {@code POP} into an {@code ASTORE} of that same local — the obvious
 * shape — would have handed that mixin a fourth injection site and appended the conditional overlays twice. Wrong
 * instead of crashed is the worse outcome, so: the {@code addAll} call becomes a static call that returns the
 * merged list, and the {@code POP}/{@code ALOAD} pair that used to bridge to {@code List.copyOf} is deleted. The
 * set of {@code ASTORE overlaySet} sites comes out byte-identical, and this transform is invisible to the mixin.
 *
 * <p><b>Invariant for whoever edits this next:</b> no pre-Mixin transform may ADD a store to {@code overlaySet}
 * inside its local-variable scope. That is not a stylistic preference; it silently duplicates overlays.
 *
 * <p>Frame- and stack-neutral, so {@code ClassWriter(0)} writes the existing frames back untouched: the merged
 * base has no stack-map frame anywhere between the store the patch makes and the one {@code List.copyOf} makes,
 * and the peak depth over the edited span is 2 before and after.
 *
 * <p>Matched STRUCTURALLY. The anchor is the five-instruction tail
 * {@code addAll / POP / ALOAD n / List.copyOf / ASTORE n} sharing one local slot, corroborated by the
 * {@code new ArrayList<>(overlaySet)} store to that same slot earlier in the method — the signature of NeoForge's
 * patch. The receiver slot cannot be recovered by scanning backwards from {@code addAll}, because the argument
 * expression begins with its own {@code ALOAD}; it is read forwards off the {@code List.copyOf} pair instead.
 */
public final class PackOverlayMutabilityInjector implements ClassTransformer {
	private static final String TARGET = "net.minecraft.server.packs.repository.Pack";
	private static final String METHOD = "readPackMetadata";

	private static final String LIST = "java/util/List";
	private static final String ADD_ALL = "addAll";
	private static final String ADD_ALL_DESC = "(Ljava/util/Collection;)Z";
	private static final String COPY_OF = "copyOf";
	private static final String COPY_OF_DESC = "(Ljava/util/Collection;)Ljava/util/List;";
	private static final String ARRAY_LIST = "java/util/ArrayList";
	private static final String INIT = "<init>";
	private static final String COPY_CTOR_DESC = "(Ljava/util/Collection;)V";

	private static final String HOOK_OWNER = "net/forbric/kernel/boot/KernelPackRepair";
	private static final String HOOK_NAME = "concat";
	private static final String HOOK_DESC = "(Ljava/util/List;Ljava/util/Collection;)Ljava/util/List;";

	@Override
	public String name() {
		return "forbric-pack-overlay-mutability";
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		if (!TARGET.equals(className) || !KernelPackRepair.enabled()) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		int repaired = 0;
		for (MethodNode method : node.methods) {
			if (!METHOD.equals(method.name)) continue;
			repaired += repair(method);
		}
		if (repaired == 0) {
			// The anchor is gone: NeoForge changed its patch, or the merge no longer splices it. Say so rather
			// than passing silently — the crash this prevents looks like a mod bug and costs a day to re-find.
			// KernelPackRepair.nullPackSkipped still keeps the world loadable, minus that pack.
			ForbricLog.warn("[Forbric/PackRepair] %s.%s no longer matches the overlay-merge shape — the fabric-api "
					+ "vs NeoForge overlay collision is UNREPAIRED; re-derive PackOverlayMutabilityInjector's "
					+ "structural match", TARGET, METHOD);
			return classBytes;
		}
		if (repaired > 1) {
			ForbricLog.warn("[Forbric/PackRepair] repaired %d overlay merges in %s.%s — expected exactly one; "
					+ "re-check PackOverlayMutabilityInjector's structural match", repaired, TARGET, METHOD);
		} else {
			ForbricLog.info("[Forbric/PackRepair] %s.%s now merges overlays without mutating in place — a pack "
					+ "carrying both fabric:overlays and neoforge:overlays survives the merged base", TARGET, METHOD);
		}

		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** Rewrites every in-place overlay merge in {@code method}. Returns how many were found. */
	private static int repair(MethodNode method) {
		List<AbstractInsnNode> real = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn.getOpcode() >= 0) real.add(insn);
		}

		List<AbstractInsnNode[]> matches = new ArrayList<>();
		for (int i = 0; i + 4 < real.size(); i++) {
			AbstractInsnNode addAll = real.get(i);
			if (!(addAll instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKEINTERFACE
					|| !LIST.equals(call.owner) || !ADD_ALL.equals(call.name) || !ADD_ALL_DESC.equals(call.desc)) {
				continue;
			}
			AbstractInsnNode pop = real.get(i + 1);
			AbstractInsnNode load = real.get(i + 2);
			AbstractInsnNode copyOf = real.get(i + 3);
			AbstractInsnNode store = real.get(i + 4);
			if (pop.getOpcode() != Opcodes.POP) continue;
			if (!(load instanceof VarInsnNode loadVar) || loadVar.getOpcode() != Opcodes.ALOAD) continue;
			if (!(copyOf instanceof MethodInsnNode freeze) || freeze.getOpcode() != Opcodes.INVOKESTATIC
					|| !LIST.equals(freeze.owner) || !COPY_OF.equals(freeze.name)
					|| !COPY_OF_DESC.equals(freeze.desc)) {
				continue;
			}
			if (!(store instanceof VarInsnNode storeVar) || storeVar.getOpcode() != Opcodes.ASTORE
					|| storeVar.var != loadVar.var) {
				continue;
			}
			// Corroboration: the local was loaded from a defensive ArrayList copy earlier — NeoForge's patch.
			if (!copiedIntoArrayList(real, i, loadVar.var)) continue;

			matches.add(new AbstractInsnNode[] {call, pop, load});
		}

		for (AbstractInsnNode[] match : matches) {
			MethodInsnNode call = (MethodInsnNode) match[0];
			method.instructions.set(call,
					new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_NAME, HOOK_DESC, false));
			method.instructions.remove(match[1]);
			method.instructions.remove(match[2]);
		}
		return matches.size();
	}

	/** True when some earlier {@code ASTORE slot} is fed by {@code new ArrayList<>(…)} — the patch's signature. */
	private static boolean copiedIntoArrayList(List<AbstractInsnNode> real, int before, int slot) {
		for (int i = 1; i < before; i++) {
			AbstractInsnNode ctor = real.get(i - 1);
			AbstractInsnNode store = real.get(i);
			if (ctor instanceof MethodInsnNode init && init.getOpcode() == Opcodes.INVOKESPECIAL
					&& ARRAY_LIST.equals(init.owner) && INIT.equals(init.name) && COPY_CTOR_DESC.equals(init.desc)
					&& store instanceof VarInsnNode var && var.getOpcode() == Opcodes.ASTORE && var.var == slot) {
				return true;
			}
		}
		return false;
	}
}
