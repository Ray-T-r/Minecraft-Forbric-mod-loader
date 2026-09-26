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

import java.nio.charset.StandardCharsets;
import java.util.function.Function;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import net.forbric.kernel.classloading.LoaderProbePolicy;
import net.forbric.kernel.mixin.MixinWeaverSlot;
import net.forbric.kernel.util.ForbricLog;

/**
 * Answers a NeoForge mod that casts the thread's context class loader to FML's {@code TransformingClassLoader}
 * with a view of FML's transformer, rather than with the kernel's own loader and a {@code ClassCastException}.
 *
 * <h2>The call site</h2>
 *
 * <p>LibJF's mixin plugin opens with {@code (TransformingClassLoader) Thread.currentThread().getContextClassLoader()}
 * and walks from there to the live Mixin transformer ({@code classTransformer.processors.processors[MIXIN]
 * .transformer}), which it wraps in its own {@code AsmTransformer} and writes back — that is how LibJF's ASM
 * patches reach every class on NeoForge. Under the kernel the context loader is {@code ForbricClassLoader}, so the
 * cast failed at its first instruction, LibJF logged "Could not initialize LibJF ASM", and no {@code libjf:asm}
 * patch was ever applied.
 *
 * <p>The thread's context loader is not changed — every other reader keeps getting the real one. Only this exact
 * instruction pair, {@code invokevirtual Thread.getContextClassLoader} immediately followed by {@code checkcast
 * TransformingClassLoader}, gets {@code KernelFmlTransformerView.contextLoader} between the two; the cast stays, so
 * if no view can be built the real loader comes back and the cast fails exactly as it always did.
 *
 * <p>Only classes from jars arbitrated to NeoForge: nothing else can mean FML's loader by that name. A byte scan for
 * the class name rejects everything else before ASM parses it.
 *
 * <p>{@code -Dforbric.fmlTransformerView=off} rewrites nothing.
 */
public final class FmlContextLoaderRewriter implements ClassTransformer {
	static final String TRANSFORMING_LOADER = "net/neoforged/fml/classloading/transformation/TransformingClassLoader";
	static final String VIEW = "net/forbric/kernel/runtime/KernelFmlTransformerView";
	private static final byte[] MARKER = TRANSFORMING_LOADER.getBytes(StandardCharsets.UTF_8);

	private final Function<String, LoaderProbePolicy.Family> familyOf;

	/**
	 * @param familyOf the loader family of the jar a class is being defined from, or null when it is not one
	 *                 family's alone
	 */
	public FmlContextLoaderRewriter(Function<String, LoaderProbePolicy.Family> familyOf) {
		this.familyOf = familyOf;
	}

	@Override
	public String name() {
		return "forbric-fml-context-loader";
	}

	@Override
	public AnchorSet anchors() {
		return AnchorSet.scanned("rewrites a cast inside guest NeoForge mod classes, which depend on which mods are "
				+ "installed");
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		// The family first: it is a map lookup and rejects almost every class, where the byte scan reads it whole.
		if (classBytes == null || familyOf.apply(className) != LoaderProbePolicy.Family.NEOFORGE) return classBytes;
		if (!MixinWeaverSlot.enabled() || !contains(classBytes, MARKER)) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		int hits = 0;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions.toArray()) {
				if (!isContextLoaderCall(insn) || !isCastToTransformingLoader(next(insn))) continue;
				method.instructions.insert(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, VIEW, "contextLoader",
						"(Ljava/lang/ClassLoader;)Ljava/lang/ClassLoader;", false));
				hits++;
			}
		}
		if (hits == 0) return classBytes;

		ForbricLog.info("[Forbric/FmlView] %s casts the context class loader to FML's TransformingClassLoader %d "
				+ "time(s) — it is handed a view of FML's transformer there, as on NeoForge", className, hits);
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static boolean isContextLoaderCall(AbstractInsnNode insn) {
		return insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
				&& "java/lang/Thread".equals(call.owner) && "getContextClassLoader".equals(call.name)
				&& "()Ljava/lang/ClassLoader;".equals(call.desc);
	}

	private static boolean isCastToTransformingLoader(AbstractInsnNode insn) {
		return insn instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST
				&& TRANSFORMING_LOADER.equals(cast.desc);
	}

	/**
	 * The next real instruction. Line numbers and labels are skipped — the cast of a call on the next source line
	 * is still the same expression — but a frame is not: one there means something jumps between the two, and
	 * then they are not one expression any more.
	 */
	private static AbstractInsnNode next(AbstractInsnNode insn) {
		AbstractInsnNode next = insn.getNext();
		while (next != null && (next.getType() == AbstractInsnNode.LINE || next.getType() == AbstractInsnNode.LABEL)) {
			next = next.getNext();
		}
		return next;
	}

	private static boolean contains(byte[] haystack, byte[] needle) {
		outer:
		for (int i = 0; i + needle.length <= haystack.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) continue outer;
			}
			return true;
		}
		return false;
	}
}
