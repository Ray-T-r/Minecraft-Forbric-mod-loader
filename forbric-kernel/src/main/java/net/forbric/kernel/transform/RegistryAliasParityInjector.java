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

import java.util.List;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Puts registry-alias resolution back on the traditional-Forge registry wrappers.
 *
 * <p>fabric-api implements aliases as a {@code @ModifyVariable} that rewrites the incoming id on eight
 * {@code MappedRegistry} lookups. {@code net.minecraftforge.registries.NamespacedWrapper} is a
 * {@code MappedRegistry} subclass that OVERRIDES all eight to delegate to a {@code ForgeRegistry}, and on the
 * merged base it is what {@code BuiltInRegistries.BLOCK} (and every other Forge-owned registry) actually is. The
 * mixin's rewrite therefore sits in a method nobody calls, while {@code addAlias} — mixin-ADDED, so inherited
 * rather than overridden — happily records aliases that will never be read. See {@link
 * net.forbric.kernel.boot.KernelRegistryAliases} for what that costs and how it was found.
 *
 * <p>The repair is the same rewrite, on the overriding methods: {@code arg1 = resolve(this, arg1)} at the head.
 * Same semantics as fabric-api's (it reads fabric-api's own map, so an alias registered through either ecosystem
 * resolves in both), so a caller cannot tell which class answered.
 *
 * <p>Two methods beyond fabric-api's list are covered — {@code getOptional(Identifier)} and
 * {@code getHolder(Identifier)}. On a stock instance those are {@code Registry} defaults that route through
 * {@code getValue}, so fabric-api gets them for free; the wrapper overrides them into direct delegates, which
 * breaks exactly that. Covering them is parity of EFFECT, which is the thing that matters.
 *
 * <p>Frame-safe by construction: straight-line code at the head, no branch, and the rewritten value goes back into
 * the slot it came from with the same type, so every existing stack-map frame still describes the same state.
 * {@code COMPUTE_MAXS} because {@code max_stack} may need to reach 2.
 */
public final class RegistryAliasParityInjector implements ClassTransformer {
	private static final Set<String> TARGETS = Set.of(
			"net.minecraftforge.registries.NamespacedWrapper",
			"net.minecraftforge.registries.NamespacedDefaultedWrapper");

	private static final String HOOK_OWNER = "net/forbric/kernel/boot/KernelRegistryAliases";
	private static final String HOOK_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
	private static final String IDENTIFIER = "net/minecraft/resources/Identifier";
	private static final String RESOURCE_KEY = "net/minecraft/resources/ResourceKey";

	/** fabric-api's Identifier-keyed list, plus the two the wrapper overrides out of {@code Registry}'s defaults. */
	private static final List<String> BY_IDENTIFIER = List.of(
			"get", "getValue", "containsKey", "getOptional", "getHolder");

	/** fabric-api's ResourceKey-keyed list, every one of which the wrapper also overrides. */
	private static final List<String> BY_RESOURCE_KEY = List.of(
			"get", "getValue", "containsKey", "registrationInfo", "getOrCreateHolderOrThrow");

	@Override
	public String name() {
		return "forbric-registry-alias-parity";
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		if (!TARGETS.contains(className)) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		int patched = 0;
		for (MethodNode m : node.methods) {
			if ((m.access & Opcodes.ACC_STATIC) != 0) continue;
			Type[] args = Type.getArgumentTypes(m.desc);
			if (args.length != 1) continue;

			String param = args[0].getInternalName();
			String hook;
			if (IDENTIFIER.equals(param) && BY_IDENTIFIER.contains(m.name)) {
				hook = "resolveId";
			} else if (RESOURCE_KEY.equals(param) && BY_RESOURCE_KEY.contains(m.name)) {
				hook = "resolveKey";
			} else {
				continue;
			}

			InsnList head = new InsnList();
			head.add(new VarInsnNode(Opcodes.ALOAD, 0)); // the registry
			head.add(new VarInsnNode(Opcodes.ALOAD, 1)); // the id / key
			head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, hook, HOOK_DESC, false));
			head.add(new TypeInsnNode(Opcodes.CHECKCAST, param));
			head.add(new VarInsnNode(Opcodes.ASTORE, 1));
			m.instructions.insert(head);
			patched++;
		}

		if (patched == 0) {
			// The wrapper stopped overriding the lookups, or was renamed. Say so: the failure it prevents is a mod's
			// own datapack silently failing to parse, which reads as a bug in that mod.
			ForbricLog.warn("[Forbric/Aliases] %s has no id-keyed lookup to repair — a registry alias may go "
					+ "unresolved; re-derive RegistryAliasParityInjector's method table", className);
			return classBytes;
		}

		ForbricLog.info("[Forbric/Aliases] gave %s back the %d alias-resolving lookup(s) its overrides hid from "
				+ "fabric-api's registry-sync mixin", className, patched);
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}
}
