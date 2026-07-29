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
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * The keystone of the kernel's native registry model: it neuters Forge's registry-wrapping seam on the merged
 * base so every builtin registry stays a plain vanilla {@code net.minecraft.core.MappedRegistry}.
 *
 * <p><b>The merged-base fact.</b> The merged (vanilla + Forge + NeoForge) game base has
 * {@code BuiltInRegistries.internalRegister} patched Forge's way: after creating a plain
 * {@code net.minecraft.core.MappedRegistry} it routes it through
 * {@code net.minecraftforge.registries.GameData.getWrapper(ResourceKey, WritableRegistry)}, which looks up
 * {@code RegistryManager.ACTIVE} and returns a {@code net.minecraftforge.registries.NamespacedWrapper} —
 * a registry born <em>frozen</em>, unfrozen only inside Forge's {@code GameData} unfreeze/RegisterEvent/freeze
 * window. Under the old weld, two ecosystems fought over that one wrapper's freeze state, and resetting its
 * {@code frozenTags} to rebuild the snapshot is exactly the {@code "Tags not bound"} world-join wall (see
 * {@code forbric-loader/.../forge/runtime/ForbricRegistryBridge}).
 *
 * <p><b>The kernel's redirect.</b> This transformer rewrites {@code GameData.getWrapper}'s body to
 * {@code return arg1} — the plain {@code WritableRegistry} it was handed. So {@code internalRegister} gets the
 * plain {@code MappedRegistry} back, {@code NamespacedWrapper}/{@code RegistryManager} are never instantiated
 * for builtin registries, and the ONLY freeze is vanilla's own {@code BuiltInRegistries.bootStrap -> freeze()}.
 * Tags then bind exactly once, by vanilla's {@code TagLoader}, strictly after all registration — the old
 * refreeze/unbind cycle has no code path. The merged {@code MappedRegistry} already implements NeoForge's
 * {@code IRegistryExtension}, so NeoForge-facing structural expectations still hold with zero wrappers.
 *
 * <p><b>Fail-loud (risk R7).</b> If {@code GameData} is loaded but {@code getWrapper} with the expected
 * descriptor is absent — i.e. the merged base or the forge-runtime jar drifted — this throws rather than
 * silently letting a wrapped, frozen registry through. Redirecting the single {@code getWrapper} seam (rather
 * than each caller) is deliberate: it catches every present and future call site in one place.
 *
 * <p>Scope note: for M1 (vanilla boot, zero mods) identity is fully correct — vanilla never consults
 * {@code RegistryManager}. The Forge {@code RegisterEvent}/{@code ForgeRegistry} path that some Forge mods
 * expect is a separate, native concern handled at M3 (kernel-driven dispatch), not by resurrecting the wrapper.
 */
public final class RegistryHookRedirector implements ClassTransformer {
	public static final String GAMEDATA = "net.minecraftforge.registries.GameData";
	private static final String GET_WRAPPER = "getWrapper";
	// (ResourceKey, WritableRegistry) -> WritableRegistry   (erased)
	private static final String GET_WRAPPER_DESC =
			"(Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/core/WritableRegistry;)Lnet/minecraft/core/WritableRegistry;";

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (!GAMEDATA.equals(className)) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		MethodNode target = null;
		for (MethodNode m : node.methods) {
			if (m.name.equals(GET_WRAPPER) && m.desc.equals(GET_WRAPPER_DESC)) {
				target = m;
				break;
			}
		}
		if (target == null) {
			// R7: the redirect seam vanished. Do NOT let a frozen NamespacedWrapper through silently.
			throw new IllegalStateException("RegistryHookRedirector: " + GAMEDATA + "." + GET_WRAPPER
					+ GET_WRAPPER_DESC + " not found on the merged base — the registry-wrapping seam moved. "
					+ "The kernel's single-freeze registry model cannot be guaranteed; refusing to boot. "
					+ "Re-run the merged-base scan and update the redirect.");
		}

		// Replace the whole body with: ALOAD 1 (the WritableRegistry arg); ARETURN.
		// getWrapper is static, so parameter 0 = ResourceKey (slot 0), parameter 1 = WritableRegistry (slot 1).
		InsnList identity = new InsnList();
		identity.add(new VarInsnNode(Opcodes.ALOAD, 1));
		identity.add(new InsnNode(Opcodes.ARETURN));
		target.instructions = identity;
		target.tryCatchBlocks = null;
		target.localVariables = null;
		target.maxStack = 1;
		target.maxLocals = Math.max(target.maxLocals, 2);

		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		if (ForbricLog.debugEnabled()) {
			ForbricLog.debug("[Forbric/RegistryHook] redirected %s.%s to identity — builtin registries stay plain "
					+ "MappedRegistry (single vanilla freeze, no NamespacedWrapper)", GAMEDATA, GET_WRAPPER);
		}
		return writer.toByteArray();
	}

	@Override
	public String name() {
		return "forbric:registry-hook-redirector";
	}
}
