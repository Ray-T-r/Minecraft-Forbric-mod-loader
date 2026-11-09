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
 * Rewrites, before vanilla reads it, the pack selection a world saved: the NeoForge carrier's pack under an id an
 * installed Forbric 0.4 or earlier wrote ({@code forbric/carrier/2-neoforge-runtime-<version>}) becomes the id it has
 * now, in place. See {@code KernelCarrierPackIds} for why, and {@code KernelDataPacks.migrateCarrierIds} for the rule.
 *
 * <p>The anchor is the head of {@code MinecraftServer.configurePackRepository(PackRepository, WorldDataConfiguration,
 * boolean, boolean)}: its only caller is {@code WorldLoader.PackConfig.createResourceManager}, which a dedicated server
 * and a singleplayer world both go through, and its second argument is the selection read from {@code level.dat}.
 * Replacing that argument is all it takes: the method resolves the list against the repository and returns the
 * configuration the world then saves.
 */
public final class CarrierPackIdMigrationInjector implements ClassTransformer {
	private static final String TARGET = "net/minecraft/server/MinecraftServer";
	private static final String METHOD = "configurePackRepository";
	private static final String CONFIG = "Lnet/minecraft/world/level/WorldDataConfiguration;";
	private static final String DESC = "(Lnet/minecraft/server/packs/repository/PackRepository;" + CONFIG + "ZZ)" + CONFIG;
	private static final String HOOK_OWNER = "net/forbric/kernel/runtime/KernelCarrierPackIds";
	private static final String HOOK_NAME = "migrate";
	private static final String HOOK_DESC = "(" + CONFIG + ")" + CONFIG;

	@Override
	public String name() {
		return "forbric-carrier-pack-id-migration";
	}

	@Override
	public AnchorSet anchors() {
		return AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.REQUIRED,
				"a world an installed Forbric 0.4 or earlier wrote would lose its NeoForge carrier pack's place: vanilla "
						+ "re-adds it at the end of the list, above every mod pack"));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		if (!TARGET.equals(className) && !TARGET.replace('/', '.').equals(className)) return classBytes;
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		boolean changed = false;
		for (MethodNode m : node.methods) {
			if (!METHOD.equals(m.name) || !DESC.equals(m.desc) || (m.access & Opcodes.ACC_STATIC) == 0) continue;
			InsnList head = new InsnList();
			head.add(new VarInsnNode(Opcodes.ALOAD, 1)); // the WorldDataConfiguration the world saved
			head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_NAME, HOOK_DESC, false));
			head.add(new VarInsnNode(Opcodes.ASTORE, 1));
			m.instructions.insert(head);
			changed = true;
		}
		if (!changed) {
			ForbricLog.warn("[Forbric/DataPacks] %s.%s%s no longer exists — a world an installed Forbric 0.4 or earlier "
					+ "wrote keeps its old NeoForge carrier pack id; re-derive CarrierPackIdMigrationInjector's target",
					TARGET, METHOD, DESC);
			return classBytes;
		}
		ForbricLog.debug("[Forbric/DataPacks] hooked %s.%s — old NeoForge carrier pack ids in a world are renamed in place",
				TARGET, METHOD);
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}
}
