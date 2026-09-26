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
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import net.forbric.kernel.mixin.MixinWeaverSlot;
import net.forbric.kernel.util.ForbricLog;

/**
 * Lets NeoForge's {@code ModuleClassLoader} be initialised on a JVM that was not launched with NeoForge's
 * {@code --add-opens java.base/java.lang.invoke=ALL-UNNAMED}.
 *
 * <h2>Why the kernel touches this class at all</h2>
 *
 * <p>The kernel never loads classes through FML's module class loaders, so on every boot this class is never even
 * initialised. It is initialised for exactly one reason: {@code KernelFmlTransformerView} hands a guest a
 * {@code TransformingClassLoader} — a subclass — so that LibJF's ASM layer can reach the Mixin transformer the way
 * it does on NeoForge, and allocating any instance of it runs this class's static initialiser.
 *
 * <p>That initialiser (javap on the carrier) does {@code MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP")
 * .setAccessible(true)} to build {@code LAYER_BIND_TO_LOADER}, and catches only {@code NoSuchFieldException},
 * {@code IllegalAccessException} and {@code NoSuchMethodException}. NeoForge launches with java.lang.invoke opened,
 * so there it succeeds. The kernel's launch profile does not open it, and changing every launcher profile for this
 * would be a launch-contract change for one library; so {@code setAccessible} throws
 * {@code InaccessibleObjectException}, which escapes, and the class — and every subclass — is erroneous for the
 * rest of the run.
 *
 * <p>So the same guarded block also catches {@code InaccessibleObjectException}, and on it the initialiser simply
 * finishes, with {@code LAYER_BIND_TO_LOADER} left null. That handle is used only by {@code bindToLayer}, from the
 * constructor; the view is allocated without a constructor and never loads or defines anything, so it never reaches
 * it. Nothing else changes: with java.lang.invoke open the new handler never runs.
 *
 * <p>Switched with the view: {@code -Dforbric.fmlTransformerView=off} leaves this class as shipped.
 */
public final class ModuleClassLoaderInitInjector implements ClassTransformer {
	static final String TARGET = "net.neoforged.fml.classloading.ModuleClassLoader";
	private static final String INACCESSIBLE = "java/lang/reflect/InaccessibleObjectException";
	/** The first of the three exceptions the carrier already catches around the IMPL_LOOKUP lookup. */
	private static final String GUARDED = "java/lang/NoSuchFieldException";

	@Override
	public String name() {
		return "forbric-module-class-loader-init";
	}

	@Override
	public AnchorSet anchors() {
		if (!MixinWeaverSlot.enabled()) return AnchorSet.scanned("switched off by -D" + MixinWeaverSlot.SWITCH);
		return AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.REQUIRED,
				"a guest handed FML's TransformingClassLoader view (LibJF's ASM layer) finds the class erroneous "
						+ "and starts without its patches"));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0 || !TARGET.equals(className)) return classBytes;
		if (!MixinWeaverSlot.enabled()) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		MethodNode clinit = null;
		for (MethodNode method : node.methods) {
			if ("<clinit>".equals(method.name)) clinit = method;
		}
		if (clinit == null || !tolerate(clinit)) return classBytes;

		// Debug, not info: the class is loaded on every boot (verifying the view's signature loads it), whether or
		// not any mod is ever handed the view, so this says nothing about the pack.
		ForbricLog.debug("[Forbric/FmlView] patched ModuleClassLoader's initialiser to finish without "
				+ "java.lang.invoke opened — it matters only if a NeoForge mod is handed FML's TransformingClassLoader "
				+ "view; LAYER_BIND_TO_LOADER then stays null, which only a constructor the kernel never calls reads");
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/**
	 * Adds an {@code InaccessibleObjectException} handler over the block the carrier already guards, whose body
	 * just returns. Returns false when that block is not there or already has one.
	 */
	static boolean tolerate(MethodNode clinit) {
		TryCatchBlockNode guard = null;
		for (TryCatchBlockNode block : clinit.tryCatchBlocks) {
			if (INACCESSIBLE.equals(block.type)) return false;
			if (guard == null && GUARDED.equals(block.type)) guard = block;
		}
		if (guard == null) return false;

		LabelNode handler = new LabelNode();
		// Appended after the method's last instruction, a return, so a full frame is what the verifier needs here:
		// no locals yet (the try block's own are not live at its start) and the exception on the stack.
		clinit.instructions.add(handler);
		clinit.instructions.add(new FrameNode(Opcodes.F_FULL, 0, new Object[0], 1, new Object[] {INACCESSIBLE}));
		clinit.instructions.add(new InsnNode(Opcodes.POP));
		clinit.instructions.add(new InsnNode(Opcodes.RETURN));
		clinit.tryCatchBlocks.add(new TryCatchBlockNode(guard.start, guard.end, handler, INACCESSIBLE));
		return true;
	}
}
