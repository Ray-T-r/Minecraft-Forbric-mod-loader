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
 * Lets a FABRIC mod's Sodium options page exist, on an instance whose Sodium is the NeoForge build.
 *
 * <p>Sodium's video-settings screen is built from its config users, and its NeoForge build discovers them two
 * ways, both NeoForge-only: {@code ConfigLoaderForge.collectConfigEntryPoints} walks {@code ModList.getMods()}
 * reading {@code sodium:config_api_user} out of each {@code IModInfo.getModProperties()} (bci 68-118), then
 * walks {@code ModList.getAllScanData()} for {@code @ConfigEntryPointForge} (bci 130-306). A Fabric mod
 * declares the same thing as a Fabric ENTRYPOINT, has no {@code IModInfo}, and is not in {@code ModList} at
 * all — so neither walk can reach it, and there is no warning anywhere, because nothing looked.
 *
 * <p>Neither loader can produce that pairing: it exists because this one arbitrated two builds of sodium and
 * kept the NeoForge one, while the mods that integrate with it are Fabric. On the reporting instance that was
 * voxy — an entire page missing from Video Settings on a boot that reported every mod loaded.
 *
 * <p>The edit is one instruction: {@code INVOKESTATIC KernelLifecycle.onSodiumConfigUsers()V} immediately
 * before the method's {@code RETURN} (offset 309 on sodium 0.9.2). It takes nothing and returns nothing, so it
 * consumes and produces no stack, introduces no branch target, and shifts no existing frame — which is why
 * this transformer AUTHORS no {@code FrameNode}, unlike every splice in {@link CommonNetworkInteropInjector}.
 * It must still PRESERVE the frames already there, and getting the reader flags wrong for that cost a boot
 * crash; see {@link #transform}.
 *
 * <p>Placed at the END on purpose. Sodium's own two walks run first and register everything it can see by
 * itself, so the kernel's pass can skip a mod that is already in {@code ModList} rather than racing it — and
 * the deadline that matters is {@code registerConfigsLate}, which is later still.
 */
public final class SodiumConfigUserBridgeInjector implements ClassTransformer {
	static final String CONFIG_LOADER = "net.caffeinemc.mods.sodium.neoforge.config.ConfigLoaderForge";
	static final String COLLECT = "collectConfigEntryPoints";
	static final String COLLECT_DESC = "()V";
	private static final String HOOK_OWNER = "net/forbric/kernel/boot/KernelLifecycle";
	private static final String HOOK_NAME = "onSodiumConfigUsers";
	private static final String HOOK_DESC = "()V";

	@Override
	public String name() {
		return "forbric-sodium-config-user-bridge";
	}

	/**
	 * REQUIRED, not HEDGE, although the class is absent on most instances.
	 *
	 * <p>A HEDGE miss is unreportable by both outlets here: {@code AnchorLedger} returns before its error line
	 * for HEDGE, and the census landmark is the title screen, which this instance reaches roughly ten seconds
	 * BEFORE {@code Minecraft.<init>} loads {@code ConfigLoaderForge}. REQUIRED costs nothing on an instance
	 * without sodium's NeoForge build, because a class that never loads never reaches the ledger at all.
	 */
	@Override
	public AnchorSet anchors() {
		return AnchorSet.of(new AnchorSet.Anchor(CONFIG_LOADER, AnchorSet.Severity.REQUIRED,
				"a Fabric mod that declares a Sodium config entry point has no options page in Video Settings — "
						+ "Sodium's NeoForge build looks for its config users only in ModList, which a Fabric mod "
						+ "is not in, so the page is absent with no warning from anyone"));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		if (!CONFIG_LOADER.equals(className)) return classBytes;

		ClassNode node = new ClassNode();
		// EXPAND_FRAMES, and emphatically NOT SKIP_FRAMES. SKIP_FRAMES DISCARDS the StackMapTable rather than
		// passing it through, and ClassWriter(0) does not recompute one — so the class ships with no frames at
		// all and the verifier refuses it at the first branch target:
		//   VerifyError: Expecting a stackmap frame at branch target 124
		//   Location: ConfigLoaderForge.collectConfigEntryPoints()V @26: ifeq
		// That is a throw inside Minecraft.<init>, i.e. the game does not start. Measured on a live boot.
		// EXPAND_FRAMES keeps every original frame as an absolute F_NEW node and ClassWriter(0) re-serialises
		// them unchanged — which is correct here precisely BECAUSE this edit adds no branch and shifts no frame.
		new ClassReader(classBytes).accept(node, ClassReader.EXPAND_FRAMES);

		int hooked = 0;
		for (MethodNode m : node.methods) {
			if (!COLLECT.equals(m.name) || !COLLECT_DESC.equals(m.desc)) continue;
			if (alreadyHooked(m)) continue;
			for (AbstractInsnNode insn : m.instructions.toArray()) {
				if (insn.getOpcode() != Opcodes.RETURN) continue;
				m.instructions.insertBefore(insn,
						new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_NAME, HOOK_DESC, false));
				hooked++;
			}
		}
		if (hooked == 0) return classBytes;

		ForbricLog.info("[Forbric/Sodium] %s.%s now also asks the kernel for the FABRIC mods that declared a Sodium "
				+ "config entry point — it finds config users through ModList alone, and a Fabric mod is not in it, "
				+ "so their Video Settings pages did not exist (%d return site(s))", className, COLLECT, hooked);
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** So a class offered twice — the pre-mixin read and the define — is not hooked twice. */
	private static boolean alreadyHooked(MethodNode m) {
		for (AbstractInsnNode insn : m.instructions) {
			if (insn instanceof MethodInsnNode call && HOOK_OWNER.equals(call.owner) && HOOK_NAME.equals(call.name)) {
				return true;
			}
		}
		return false;
	}
}
