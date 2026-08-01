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

import java.util.function.Function;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.classloading.LoaderProbePolicy;
import net.forbric.kernel.util.ForbricLog;

/**
 * Redirects {@code Class.forName} inside a single-loader guest class to {@link LoaderProbePolicy#forName}, so the
 * mod's "which platform am I on?" question is answered for the loader it was actually loaded as.
 *
 * <p>The rationale, and why this cannot live in the class loader, is in {@link LoaderProbePolicy}. In short: the
 * kernel seeds both {@code FMLLoader}s early, and {@code Class.forName} on an already-loaded class never reaches
 * {@code loadClass} — so the only place left that still sees the question is the call site.
 *
 * <p>The asking family is a constant known at transform time (the jar the class came from declares exactly one
 * loader), so it is baked in as an extra {@code LDC} argument rather than recovered from the stack at runtime.
 *
 * <p>Both overloads mods actually use are handled:
 *
 * <pre>{@code
 * Class.forName(name)                     -> LoaderProbePolicy.forName(name, "FABRIC")
 * Class.forName(name, init, loader)       -> LoaderProbePolicy.forName(name, init, loader, "FABRIC")
 * }</pre>
 *
 * <p>Classes with no {@code java/lang/Class.forName} reference in their constant pool are rejected by a substring
 * scan before ASM ever parses them, so the common case costs one {@code indexOf} over the raw bytes.
 */
public final class LoaderProbeRewriter implements ClassTransformer {

	private static final String POLICY = "net/forbric/kernel/classloading/LoaderProbePolicy";
	private static final byte[] MARKER = "forName".getBytes(java.nio.charset.StandardCharsets.UTF_8);

	private final Function<String, LoaderProbePolicy.Family> familyOf;
	private int rewritten;

	/**
	 * @param familyOf resolves a binary class name to the loader family of the jar it is being defined from, or
	 *                 {@code null} for the merged base, a universal jar, a library or the kernel itself
	 */
	public LoaderProbeRewriter(Function<String, LoaderProbePolicy.Family> familyOf) {
		this.familyOf = familyOf;
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		LoaderProbePolicy.Family family = familyOf.apply(className);
		if (family == null || !containsForName(classBytes)) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		int hits = 0;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions.toArray()) {
				if (insn.getOpcode() != Opcodes.INVOKESTATIC) continue;

				MethodInsnNode call = (MethodInsnNode) insn;
				if (!"java/lang/Class".equals(call.owner) || !"forName".equals(call.name)) continue;

				String redirected = switch (call.desc) {
					// Class.forName(String) — the family is pushed on top of the single String argument.
					case "(Ljava/lang/String;)Ljava/lang/Class;" ->
							"(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Class;";
					// Class.forName(String, boolean, ClassLoader) — likewise, on top of the three it already has.
					case "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;" ->
							"(Ljava/lang/String;ZLjava/lang/ClassLoader;Ljava/lang/String;)Ljava/lang/Class;";
					// Class.forName(Module, String) throws nothing and answers for a named module; not a probe.
					default -> null;
				};
				if (redirected == null) continue;

				method.instructions.insertBefore(insn, new LdcInsnNode(family.name()));
				method.instructions.set(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, POLICY, "forName",
						redirected, false));
				hits++;
			}
		}
		if (hits == 0) return classBytes;

		rewritten += hits;
		ForbricLog.debug("[Forbric/Probe] %s (%s) asks Class.forName %d time(s) — routed through the loader-probe "
				+ "policy so it sees only its own loader", className, family, hits);

		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** How many probe call sites have been rewritten so far, for the boot summary. */
	public int rewritten() {
		return rewritten;
	}

	/**
	 * Whether the raw bytes mention {@code forName} at all. A UTF-8 constant is stored verbatim in the pool, so a
	 * class that never names the method cannot contain the call, and is skipped without an ASM parse.
	 */
	private static boolean containsForName(byte[] bytes) {
		outer:
		for (int i = 0, max = bytes.length - MARKER.length; i <= max; i++) {
			for (int j = 0; j < MARKER.length; j++) {
				if (bytes[i + j] != MARKER[j]) continue outer;
			}
			return true;
		}
		return false;
	}

	@Override
	public String name() {
		return "loader-probe-rewriter";
	}
}
