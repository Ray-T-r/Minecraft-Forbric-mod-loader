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
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Lets the owner of a registry decide the directory {@code Registries.registryDirPath} gives it, so WorldWeaver's
 * world presets and biome data are read from where WorldWeaver ships them.
 *
 * <p>The merged {@code registryDirPath} is NeoForge's: the namespace is put in front inside the body. Native Fabric
 * keeps vanilla's body and adds the namespace with a return-value mixin, and WorldWeaver's own mixin returns the
 * body's answer before that mixin can — see {@link net.forbric.kernel.boot.KernelRegistryDirectories} for the whole
 * chain and what it cost. The edit hands every answer the body gives, with the registry's namespace and path, to
 * that hook just before it is returned:
 * <pre>
 *   ... merged                      // what the body computed
 *   aload_0; invokevirtual ResourceKey.identifier; dup; invokevirtual Identifier.getNamespace
 *   swap; invokevirtual Identifier.getPath
 *   invokestatic KernelRegistryDirectories.registryDirPath(String, String, String)String
 *   areturn
 * </pre>
 * Before every {@code areturn} rather than on the one {@code CommonHooks.prefixNamespace} call, so the edit does not
 * care whether the body prefixes through NeoForge's hook or inline as MinecraftForge's does. Straight-line code that
 * leaves one {@code String} where one was, so every existing stack-map frame still holds; {@code COMPUTE_MAXS}
 * because the stack now reaches three. The hook names only {@code String} and is parent-loaded, so the new call
 * cannot fail to link on a resource-reload path.
 *
 * <p>{@code -Dforbric.registryDirectoryOwner=off} leaves {@code Registries} exactly as merged; {@code =force} keeps
 * the edit but stops the hook checking that fabric-registry-sync's modifier is in {@code Registries} first.
 */
public final class RegistryDirectoryOwnerInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.registryDirectoryOwner";

	static final String TARGET = "net.minecraft.core.registries.Registries";
	static final String METHOD = "registryDirPath";
	static final String METHOD_DESC = "(Lnet/minecraft/resources/ResourceKey;)Ljava/lang/String;";
	static final String HOOK_OWNER = "net/forbric/kernel/boot/KernelRegistryDirectories";
	static final String HOOK_DESC = "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";

	private static final String RESOURCE_KEY = "net/minecraft/resources/ResourceKey";
	private static final String IDENTIFIER = "net/minecraft/resources/Identifier";

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override
	public String name() {
		return "forbric-registry-directory-owner";
	}

	@Override
	public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("switched off by -D" + PROPERTY);
		return AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.REQUIRED,
				"a Fabric mod whose own mixin keeps its registry directory unprefixed reads a directory nothing "
						+ "ships, so the registry loads empty — WorldWeaver's world presets and biome data"));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (!enabled() || classBytes == null || classBytes.length == 0 || !TARGET.equals(className)) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		MethodNode body = null;
		for (MethodNode m : node.methods) {
			if (METHOD.equals(m.name) && METHOD_DESC.equals(m.desc) && (m.access & Opcodes.ACC_STATIC) != 0) {
				body = m;
				break;
			}
		}
		if (body == null) {
			ForbricLog.warn("[Forbric/Registries] %s has no static %s%s — a Fabric mod that keeps its registry "
					+ "directory unprefixed (WorldWeaver) will read nothing; re-derive RegistryDirectoryOwnerInjector",
					className, METHOD, METHOD_DESC);
			return classBytes;
		}

		List<AbstractInsnNode> returns = new ArrayList<>();
		for (AbstractInsnNode insn = body.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode call && HOOK_OWNER.equals(call.owner)) return classBytes; // done
			if (insn.getOpcode() == Opcodes.ARETURN) returns.add(insn);
		}
		if (returns.isEmpty()) return classBytes;

		for (AbstractInsnNode ret : returns) {
			InsnList owned = new InsnList();
			owned.add(new VarInsnNode(Opcodes.ALOAD, 0));
			owned.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, RESOURCE_KEY, "identifier",
					"()L" + IDENTIFIER + ";", false));
			owned.add(new InsnNode(Opcodes.DUP));
			owned.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, IDENTIFIER, "getNamespace", "()Ljava/lang/String;",
					false));
			owned.add(new InsnNode(Opcodes.SWAP));
			owned.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, IDENTIFIER, "getPath", "()Ljava/lang/String;",
					false));
			owned.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, "registryDirPath", HOOK_DESC, false));
			body.instructions.insertBefore(ret, owned);
		}

		ForbricLog.info("[Forbric/Registries] %s.%s now answers as each registry's owner's loader does — the merged "
				+ "body is NeoForge's, and a Fabric mod's registry gets vanilla's directory for fabric-registry-sync "
				+ "to prefix, or for WorldWeaver's own mixin to keep as it is", className, METHOD);

		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}
}
