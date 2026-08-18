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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Removes the lambda bodies the byte merge orphaned, so a guest mixin cannot bind to one.
 *
 * <p>When two ecosystems patch the same method, the merge keeps ONE body but both sets of lambdas. The winner's
 * body captures only its own, and the loser's chain is left behind — still in the class, still referenced from
 * inside its own dead chain, reachable from nothing. Harmless, until a mixin looks a lambda up BY NAME.
 *
 * <p>Mixin's {@code method = "lambda$load$2"} carries no descriptor, because mod authors write it against a single
 * compilation where the name is unique — javac numbers lambdas per class, so a normal class can never have two.
 * On a merged base it can, and there is nothing in the selector to tell them apart. That is not hypothetical:
 * {@code ResourceManagerRegistryLoadTask.load} came out of the merge as MinecraftForge's version, which wraps the
 * element codec in {@code ConditionCodec} and therefore drives the five-argument lambda chain — while vanilla's
 * four-argument chain sat beside it, dead. Lithostitched's {@code @ModifyExpressionValue} on
 * {@code lambda$load$2} landed in the dead one. Its handler drops the elements whose load predicate is false; not
 * running, those elements reached the registry loader as errors, and three conditional Tectonic modifiers failed
 * every world load with nothing but {@code StubException} to explain it.
 *
 * <p>Scoped to exactly the ambiguity: a lambda is removed only when another method in the class shares its NAME
 * and it is unreachable from any non-lambda method. Since javac cannot produce two lambdas with one name, the
 * duplicate-name test alone means "this class was merged", and the reachability test means "and this is the half
 * that lost". A class with no duplicates is returned untouched, byte for byte.
 *
 * <p>Reachability follows both edges a lambda can arrive on: a direct call, and the method handle inside an
 * {@code invokedynamic}'s bootstrap arguments — which is how a lambda is normally reached, and how a dead chain
 * keeps itself alive if you only look one level deep.
 */
public final class DuplicateLambdaPruneInjector implements ClassTransformer {
	private static final String LAMBDA = "lambda$";

	/** {@code off} keeps the orphaned bodies, i.e. restores the ambiguity. */
	static final String PROPERTY = "forbric.pruneDuplicateLambdas";

	private int prunedClasses;

	@Override
	public String name() {
		return "forbric-duplicate-lambda-prune";
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		if ("off".equalsIgnoreCase(String.valueOf(System.getProperty(PROPERTY, "on")).trim())) return classBytes;

		// Two passes on purpose. Almost every class the game loads has no duplicate, and building a full ClassNode
		// with instructions for all of them to discover that would be a real boot cost; a names-only visit is cheap.
		Set<String> duplicatedNames = duplicatedLambdaNames(classBytes);
		if (duplicatedNames.isEmpty()) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		Set<String> reachable = reachableFromRealMethods(node);
		List<MethodNode> orphaned = new ArrayList<>();
		for (MethodNode m : node.methods) {
			if (!duplicatedNames.contains(m.name)) continue;
			if (reachable.contains(m.name + m.desc)) continue;
			orphaned.add(m);
		}
		if (orphaned.isEmpty()) return classBytes;

		node.methods.removeAll(orphaned);
		prunedClasses++;
		ForbricLog.info("[Forbric/Merge] %s carried %d duplicated lambda name(s) from the byte merge — dropped %d "
				+ "orphaned bod(ies) so a mixin selecting by name cannot bind to the half that lost: %s",
				className, duplicatedNames.size(), orphaned.size(), describe(orphaned));

		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** Lambda-shaped method names declared more than once. javac cannot produce one; only a merge can. */
	private static Set<String> duplicatedLambdaNames(byte[] classBytes) {
		Set<String> seen = new HashSet<>();
		Set<String> duplicated = new HashSet<>();
		new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
					String[] exceptions) {
				if (name.startsWith(LAMBDA) && !seen.add(name)) duplicated.add(name);
				return null;
			}
		}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return duplicated;
	}

	/**
	 * Every {@code name+desc} in this class reachable from a method that is not itself a lambda body.
	 *
	 * <p>Transitive, because a dead chain's first link is reached only from its own later links — stopping at depth
	 * one would call the whole chain live.
	 */
	private static Set<String> reachableFromRealMethods(ClassNode node) {
		Map<String, MethodNode> byKey = new HashMap<>();
		for (MethodNode m : node.methods) byKey.put(m.name + m.desc, m);

		Set<String> reachable = new HashSet<>();
		Deque<MethodNode> queue = new ArrayDeque<>();
		for (MethodNode m : node.methods) {
			if (m.name.startsWith(LAMBDA)) continue;
			if (reachable.add(m.name + m.desc)) queue.add(m);
		}

		while (!queue.isEmpty()) {
			MethodNode m = queue.poll();
			if (m.instructions == null) continue;
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof MethodInsnNode call && node.name.equals(call.owner)) {
					visit(byKey, reachable, queue, call.name + call.desc);
				} else if (insn instanceof InvokeDynamicInsnNode indy) {
					// The lambda body lives in a method handle among the bootstrap arguments, not in the call itself.
					for (Object arg : indy.bsmArgs) {
						if (arg instanceof Handle h && node.name.equals(h.getOwner())) {
							visit(byKey, reachable, queue, h.getName() + h.getDesc());
						}
					}
					if (indy.bsm != null && node.name.equals(indy.bsm.getOwner())) {
						visit(byKey, reachable, queue, indy.bsm.getName() + indy.bsm.getDesc());
					}
				}
			}
		}
		return reachable;
	}

	private static void visit(Map<String, MethodNode> byKey, Set<String> reachable, Deque<MethodNode> queue,
			String key) {
		if (!reachable.add(key)) return;
		MethodNode target = byKey.get(key);
		if (target != null) queue.add(target);
	}

	/** How many classes were pruned, for diagnostics. */
	public int prunedClasses() {
		return prunedClasses;
	}

	private static String describe(List<MethodNode> orphaned) {
		StringBuilder sb = new StringBuilder();
		for (MethodNode m : orphaned) {
			if (sb.length() > 0) sb.append(", ");
			sb.append(m.name).append(m.desc);
		}
		return sb.toString();
	}
}
