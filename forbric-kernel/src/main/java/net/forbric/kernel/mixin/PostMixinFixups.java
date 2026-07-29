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

package net.forbric.kernel.mixin;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Repairs the few classes a guest mixin wove <em>incorrectly</em> because the Forge/NeoForge byte-merge restructured
 * the target — the cases the pre-mixin adapter ({@link KernelGuestMixinAdapter}) cannot pre-empt because the damage
 * is done by Mixin's own weaving. Runs on the post-Mixin bytes, so it can see mixin-added members.
 *
 * <p>Currently one entry: {@code fabric-resource-loader-v1}'s {@code PackMixin} adds a {@code parentsPredicate} field
 * initialized to {@code DEFAULT_PARENT_PREDICATE}. Mixin injects a field initializer after the constructor's
 * super/this call — but NeoForge's merged {@code Pack} 5-arg constructor recursively calls {@code new Pack(...)}
 * inside its child-building loop, and Mixin placed the initializer after THAT recursive call, i.e. inside the loop.
 * The loop is skipped for any pack with an empty children list — every top-level pack from {@code readMetaAndCreate}
 * — so their {@code parentsPredicate} stays {@code null}. {@code fabric$isHidden()} is {@code parentsPredicate !=
 * DEFAULT}, so a null makes EVERY top-level pack (including the vanilla data pack) read as a hidden mod pack;
 * {@code refreshAutoEnabledPacks} then strips them from the selected set, the vanilla datapack never loads, and 13
 * dynamic registries come up empty. The repair re-adds the initializer where Mixin should have put it: immediately
 * after {@code super()}, so {@code parentsPredicate} is DEFAULT for every pack regardless of children (the stray
 * in-loop assignment becomes a harmless repeat).
 */
public final class PostMixinFixups {
	private static final String PACK = "net/minecraft/server/packs/repository/Pack";
	private static final String FABRIC_PACK = "net/fabricmc/fabric/impl/resource/pack/FabricPack";
	private static final String PARENTS_PREDICATE = "parentsPredicate";
	private static final String DEFAULT_PARENT_PREDICATE = "DEFAULT_PARENT_PREDICATE";
	private static final String PREDICATE_DESC = "Ljava/util/function/Predicate;";

	/** {@code -Dforbric.postMixinFixups=off} disables these repairs. */
	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty("forbric.postMixinFixups", "on"));
	}

	private PostMixinFixups() {
	}

	/** Applies any post-mixin repair keyed on {@code name}. {@code bytes} may be null (a mixin class-gen request). */
	public static byte[] apply(String name, byte[] bytes) {
		if (bytes == null || !enabled()) return bytes;
		if (PACK.equals(name.replace('.', '/'))) return repairPackParentsPredicate(bytes);
		return bytes;
	}

	private static byte[] repairPackParentsPredicate(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);

		// Only if PackMixin actually applied (the field + the FabricPack interface are present).
		boolean hasField = false;
		if (node.fields != null) {
			for (FieldNode f : node.fields) {
				if (PARENTS_PREDICATE.equals(f.name) && PREDICATE_DESC.equals(f.desc)) {
					hasField = true;
					break;
				}
			}
		}
		boolean isFabricPack = node.interfaces != null && node.interfaces.contains(FABRIC_PACK);
		if (!hasField || !isFabricPack) return bytes;

		boolean changed = false;
		for (MethodNode m : node.methods) {
			if (!m.name.equals("<init>")) continue;
			AbstractInsnNode superCall = superInitCall(m);
			if (superCall == null) continue; // a this()-delegating ctor; the super()-calling one gets the init

			InsnList init = new InsnList();
			init.add(new VarInsnNode(Opcodes.ALOAD, 0));
			init.add(new FieldInsnNode(Opcodes.GETSTATIC, PACK, DEFAULT_PARENT_PREDICATE, PREDICATE_DESC));
			init.add(new FieldInsnNode(Opcodes.PUTFIELD, PACK, PARENTS_PREDICATE, PREDICATE_DESC));
			m.instructions.insert(superCall, init);
			m.maxStack = Math.max(m.maxStack, 2);
			changed = true;
		}
		if (!changed) return bytes;

		ForbricLog.info("[Forbric/Mixin] post-mixin repair: seeded Pack.parentsPredicate=DEFAULT after super() — "
				+ "PackMixin's initializer was mis-woven into NeoForge's child-building loop, leaving top-level packs "
				+ "(incl. the vanilla datapack) hidden");
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** The {@code invokespecial Object.<init>()} that opens a super()-calling ctor, or null for a this()-delegating one. */
	private static AbstractInsnNode superInitCall(MethodNode ctor) {
		for (AbstractInsnNode insn = ctor.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() != Opcodes.INVOKESPECIAL) continue;
			MethodInsnNode call = (MethodInsnNode) insn;
			if (!call.name.equals("<init>")) continue;
			// The first <init> invokespecial in a ctor is its own chain call: super() (owner Object) or this() (owner
			// Pack). Only the super()-calling ctor should carry the field init; the this()-delegating one inherits it.
			return call.owner.equals("java/lang/Object") ? call : null;
		}
		return null;
	}
}
