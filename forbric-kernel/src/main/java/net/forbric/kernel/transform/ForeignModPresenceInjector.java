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

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.api.ModPresence;
import net.forbric.kernel.util.ForbricLog;

/**
 * Makes NeoForge's {@code ModList.isLoaded} answer for mods the OTHER ecosystem, Fabric, loaded.
 *
 * <p>{@code isLoaded} is the presence check NeoForge mods gate their compatibility branches on, and it reads
 * NeoForge's own {@code indexedMods}. In this instance that map can only ever hold NeoForge's mods, so a
 * NeoForge mod asking whether Sodium is installed is told no while a Fabric Sodium is running — and it then takes
 * the "no Sodium" branch, which is not a smaller feature but a wrong one. See {@code ModPresence} for why
 * that is invisible rather than loud.
 *
 * <p>The rewrite ORs NeoForge's own answer with the kernel's cross-ecosystem one. Both operands are evaluated
 * (an {@code IOR}, not a short circuit), which is deliberate: it keeps the method BRANCH-FREE, so no stack map
 * frame has to be written for a merged control flow, and both calls are pure map lookups anyway.
 *
 * <p>Presence only: {@code getModContainerById} is left alone. There genuinely is no NeoForge container for a
 * Fabric mod, and inventing one would put a container with no event bus and no config where a caller expects a
 * real one. A mod that needs the container rather than the fact still gets an honest empty.
 */
public final class ForeignModPresenceInjector implements ClassTransformer {
	private static final String NEOFORGE_MOD_LIST = ForeignType.MOD_LIST.binary(Ecosystem.NEOFORGE);
	private static final String IS_LOADED = "isLoaded";
	private static final String IS_LOADED_DESC = "(Ljava/lang/String;)Z";
	private static final String PRESENCE = "net/forbric/api/ModPresence";

	@Override
	public String name() {
		return "forbric-foreign-mod-presence";
	}

	@Override
	public AnchorSet anchors() {
		String cost = "a mod asking its own loader whether another ecosystem's mod is installed would be told no "
				+ "while that mod is running. Physics Mod took that branch next to a live Fabric Sodium: loaded, "
				+ "mixins applied, no error anywhere, and nothing on screen";
		return AnchorSet.of(
				new AnchorSet.Anchor(NEOFORGE_MOD_LIST, AnchorSet.Severity.REQUIRED, cost));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		if (!NEOFORGE_MOD_LIST.equals(className)) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		MethodNode isLoaded = null;
		for (MethodNode m : node.methods) {
			if (IS_LOADED.equals(m.name) && IS_LOADED_DESC.equals(m.desc)) {
				isLoaded = m;
				break;
			}
		}
		if (isLoaded == null || isLoaded.instructions.size() == 0) return classBytes;

		// The id argument is slot 1 on NeoForge's instance method (slot 0 on a static overload, as MinecraftForge's
		// was). Read it from the access flags rather than assumed, so a signature change fails here rather than silently
		// reading `this`.
		int idSlot = (isLoaded.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;

		int returns = 0;
		for (var insn = isLoaded.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() != Opcodes.IRETURN) continue;
			InsnList orForeign = new InsnList();
			orForeign.add(new VarInsnNode(Opcodes.ALOAD, idSlot));
			orForeign.add(new MethodInsnNode(Opcodes.INVOKESTATIC, PRESENCE, IS_LOADED, IS_LOADED_DESC, false));
			orForeign.add(new InsnNode(Opcodes.IOR));
			isLoaded.instructions.insertBefore(insn, orForeign);
			returns++;
		}
		if (returns == 0) return classBytes;

		// One Forge-family ModList is left since MinecraftForge went (Stage 4), so the line names the one ecosystem it
		// answers for; gate-m18 asserts it exactly once.
		ForbricLog.info("[Forbric/Presence] %s.isLoaded now answers for Fabric's mods too — it reads only "
				+ "NeoForge's own indexedMods, so a NeoForge mod gating a compatibility branch on \"is Sodium here\" "
				+ "was told no next to a live Fabric Sodium and took the wrong branch in silence", className);

		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}
}
