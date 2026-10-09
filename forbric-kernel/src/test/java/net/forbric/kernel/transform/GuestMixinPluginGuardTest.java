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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.fabricmc.api.EnvType;

/**
 * Proves {@link GuestMixinPluginGuard} actually contains a throwing plugin — by DEFINING the transformed class and
 * calling it, not by inspecting the bytecode it produced.
 *
 * <p>An untested guard is the thing most likely not to guard, and this one is load-bearing: Mixin guards plugin
 * INSTANTIATION but not the calls, so a throw out of {@code shouldApplyMixin} or {@code acceptTargets} propagates
 * through {@code select()} and aborts config preparation for the WHOLE instance. Both plugins in this instance hit
 * it — mixinconstraints with an {@code IncompatibleClassChangeError} (which then killed the client in
 * {@code Options.<init>} with an unrelated NPE), CustomSkinLoader with an unresolvable {@code SkinManager$3}
 * (boot dead, no crash report).
 *
 * <p>Defining the class also checks something no inspection can: the wrapper's stack map frames are written BY
 * HAND (COMPUTE_FRAMES would need to resolve guest types through a loader this transformer does not have), so a
 * wrong frame is a VerifyError at definition time. Every test here loads the class, so every test pays that check.
 */
class GuestMixinPluginGuardTest {
	private static final String PLUGIN_INTERFACE = "org/spongepowered/asm/mixin/extensibility/IMixinConfigPlugin";
	private static final String NAME = "com.example.GuestPlugin";

	private final GuestMixinPluginGuard guard = new GuestMixinPluginGuard();

	/** Fail OPEN: the plugin threw, so behave as if it had no opinion and apply the mixin. */
	@Test
	void aThrowingShouldApplyMixinAnswersTrueInsteadOfPropagating() throws Exception {
		Object plugin = guarded(true);
		Method m = plugin.getClass().getMethod("shouldApplyMixin", String.class, String.class);

		assertTrue((boolean) m.invoke(plugin, "target", "mixin"),
				"a plugin that throws must be treated as absent — dropping the mixin instead would silently remove "
						+ "behaviour the plugin would have allowed");
	}

	/** And a plugin that works keeps its answer: the guard must not turn every 'no' into a 'yes'. */
	@Test
	void aWorkingPluginKeepsItsOwnAnswer() throws Exception {
		Object plugin = guarded(false);
		Method m = plugin.getClass().getMethod("shouldApplyMixin", String.class, String.class);

		assertFalse((boolean) m.invoke(plugin, "target", "mixin"),
				"the guard only catches; it must not change a successful answer");
	}

	/** The object-returning queries fail open as null — CustomSkinLoader's case was acceptTargets. */
	@Test
	void aThrowingAcceptTargetsAnswersNull() throws Exception {
		Object plugin = guarded(true);
		Method m = plugin.getClass().getMethod("acceptTargets", java.util.Set.class, java.util.Set.class);

		assertNull(m.invoke(plugin, java.util.Set.of(), java.util.Set.of()));
	}

	/** A void hook that throws simply returns. */
	@Test
	void aThrowingOnLoadReturnsQuietly() throws Exception {
		Object plugin = guarded(true);
		plugin.getClass().getMethod("onLoad", String.class).invoke(plugin, "pkg");
	}

	/** The original body is kept, renamed and made private, so nothing is lost — only wrapped. */
	@Test
	void theOriginalBodySurvivesUnderAPrivateAlias() throws Exception {
		Class<?> type = guarded(true).getClass();

		Method alias = type.getDeclaredMethod("forbric$unguarded$shouldApplyMixin", String.class, String.class);
		assertNotNull(alias);
		assertTrue(java.lang.reflect.Modifier.isPrivate(alias.getModifiers()),
				"the unguarded body must not stay callable from outside");
	}

	/** A class that is not a mixin config plugin is not this transformer's business. */
	@Test
	void aClassThatIsNotAPluginIsUntouched() {
		byte[] in = pluginClass(true, false);
		assertSame(in, guard.transform(NAME, in, ctx()));
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	private static TransformContext ctx() {
		return new TransformContext(EnvType.CLIENT, false, "intermediary");
	}

	/** Transform a synthetic plugin, define it, and instantiate it. Definition is where a bad frame would show. */
	private Object guarded(boolean throwing) throws Exception {
		byte[] out = guard.transform(NAME, pluginClass(throwing, true), ctx());
		assertTrue(out != pluginClass(throwing, true), "the plugin was not guarded");

		Class<?> type = new ClassLoader(GuestMixinPluginGuardTest.class.getClassLoader()) {
			Class<?> define(byte[] b) {
				return defineClass(NAME, b, 0, b.length);
			}
		}.define(out);
		return type.getDeclaredConstructor().newInstance();
	}

	/**
	 * A minimal {@code IMixinConfigPlugin}: {@code shouldApplyMixin}, {@code acceptTargets} and {@code onLoad}
	 * either throw or answer normally.
	 */
	private static byte[] pluginClass(boolean throwing, boolean declaresInterface) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, NAME.replace('.', '/'), null, "java/lang/Object",
				declaresInterface ? new String[] {PLUGIN_INTERFACE} : null);

		MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		ctor.visitCode();
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		ctor.visitInsn(Opcodes.RETURN);
		ctor.visitMaxs(0, 0);
		ctor.visitEnd();

		body(cw, "shouldApplyMixin", "(Ljava/lang/String;Ljava/lang/String;)Z", throwing, Opcodes.ICONST_0,
				Opcodes.IRETURN);
		body(cw, "acceptTargets", "(Ljava/util/Set;Ljava/util/Set;)V", throwing, -1, Opcodes.RETURN);
		body(cw, "onLoad", "(Ljava/lang/String;)V", throwing, -1, Opcodes.RETURN);

		cw.visitEnd();
		return cw.toByteArray();
	}

	private static void body(ClassWriter cw, String name, String desc, boolean throwing, int constant, int ret) {
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, desc, null, null);
		mv.visitCode();
		if (throwing) {
			mv.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
			mv.visitInsn(Opcodes.DUP);
			mv.visitLdcInsn("guest plugin blew up");
			mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>",
					"(Ljava/lang/String;)V", false);
			mv.visitInsn(Opcodes.ATHROW);
		} else {
			if (constant >= 0) mv.visitInsn(constant);
			mv.visitInsn(ret);
		}
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	/**
	 * The rejection is the hot path: every class the game loads reaches this transformer, and about three in a
	 * hundred-jar pack are plugins. It used to cost a full ClassNode — every method and instruction of every
	 * class parsed and allocated — to read one line of the header.
	 */
	@org.junit.jupiter.api.Test
	void aClassThatIsNotAPluginIsRejectedFromTheHeaderAlone() {
		org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Ordinary", null, "java/lang/Object",
				new String[] {"java/lang/Runnable"});
		cw.visitEnd();

		assertFalse(GuestMixinPluginGuard.declaresThePluginInterface(cw.toByteArray()));
	}

	@org.junit.jupiter.api.Test
	void aPluginIsRecognisedFromTheHeaderAlone() {
		org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Plugin", null, "java/lang/Object",
				new String[] {"org/spongepowered/asm/mixin/extensibility/IMixinConfigPlugin"});
		cw.visitEnd();

		assertTrue(GuestMixinPluginGuard.declaresThePluginInterface(cw.toByteArray()));
	}

	@org.junit.jupiter.api.Test
	void bytesThatAreNotAClassAnswerNoRatherThanThrowing() {
		// Something else in the chain will fail on these and say so properly; a guard is not the place to raise it.
		assertFalse(GuestMixinPluginGuard.declaresThePluginInterface(new byte[] {1, 2, 3}));
		assertFalse(GuestMixinPluginGuard.declaresThePluginInterface(new byte[0]));
		assertFalse(GuestMixinPluginGuard.declaresThePluginInterface(null));
	}

	// --- postApply, where a raw post-Mixin patch lives ------------------------------------------------------------

	/** A bare-return postApply (most plugins') gets the plain wrapper: nothing to observe, nothing paid. */
	@Test
	void aPostApplyThatDoesNothingIsNotObserved() {
		byte[] out = guard.transform(NAME, postApplyPlugin(false, false), ctx());
		assertFalse(calls(out, "beforePostApply") || calls(out, "afterPostApply") || calls(out, "abandonPostApply"),
				"an empty postApply has no patch whose call site another mod could have taken");
	}

	/**
	 * A postApply with a body is observed on both sides of it, and a throw out of it is still contained — the
	 * hand-written handler frame has to hold with the hooks in place, so the class is defined and called.
	 */
	@Test
	void aPostApplyThatPatchesIsObservedAndAThrowIsStillContained() throws Exception {
		byte[] out = guard.transform(NAME, postApplyPlugin(true, true), ctx());
		assertTrue(calls(out, "beforePostApply") && calls(out, "afterPostApply") && calls(out, "abandonPostApply"));
		Object plugin = define(out);
		plugin.getClass().getMethod("postApply", String.class, org.objectweb.asm.tree.ClassNode.class, String.class,
				org.spongepowered.asm.mixin.extensibility.IMixinInfo.class)
				.invoke(plugin, "a.Target", new org.objectweb.asm.tree.ClassNode(), "a.mixin.TargetMixin", null);
	}

	/** And one that works runs its body once, between the two observations, and returns normally. */
	@Test
	void anObservedPostApplyStillRunsItsBody() throws Exception {
		Object plugin = define(guard.transform(NAME, postApplyPlugin(true, false), ctx()));
		org.objectweb.asm.tree.ClassNode target = new org.objectweb.asm.tree.ClassNode();
		plugin.getClass().getMethod("postApply", String.class, org.objectweb.asm.tree.ClassNode.class, String.class,
				org.spongepowered.asm.mixin.extensibility.IMixinInfo.class)
				.invoke(plugin, "a.Target", target, "a.mixin.TargetMixin", null);
		assertTrue("patched".equals(target.sourceFile), "the plugin's own postApply body did not run");
	}

	private static Object define(byte[] bytes) throws Exception {
		Class<?> type = new ClassLoader(GuestMixinPluginGuardTest.class.getClassLoader()) {
			Class<?> define(byte[] b) {
				return defineClass(NAME, b, 0, b.length);
			}
		}.define(bytes);
		return type.getDeclaredConstructor().newInstance();
	}

	private static boolean calls(byte[] bytes, String hook) {
		org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
		new org.objectweb.asm.ClassReader(bytes).accept(node, 0);
		for (org.objectweb.asm.tree.MethodNode method : node.methods) {
			for (org.objectweb.asm.tree.AbstractInsnNode insn : method.instructions) {
				if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call && call.name.equals(hook)) return true;
			}
		}
		return false;
	}

	/**
	 * A plugin whose {@code postApply} has Mixin's descriptor and either a bare {@code return} or a body: it marks the
	 * target ({@code sourceFile = "patched"}), or throws.
	 */
	private static byte[] postApplyPlugin(boolean body, boolean throwing) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, NAME.replace('.', '/'), null, "java/lang/Object",
				new String[] {PLUGIN_INTERFACE});
		MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		ctor.visitCode();
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		ctor.visitInsn(Opcodes.RETURN);
		ctor.visitMaxs(0, 0);
		ctor.visitEnd();

		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "postApply", GuestMixinPluginGuard.POST_APPLY_DESC, null, null);
		mv.visitCode();
		if (throwing) {
			mv.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
			mv.visitInsn(Opcodes.DUP);
			mv.visitLdcInsn("raw patch blew up");
			mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false);
			mv.visitInsn(Opcodes.ATHROW);
		} else {
			if (body) {
				mv.visitVarInsn(Opcodes.ALOAD, 2);
				mv.visitLdcInsn("patched");
				mv.visitFieldInsn(Opcodes.PUTFIELD, "org/objectweb/asm/tree/ClassNode", "sourceFile", "Ljava/lang/String;");
			}
			mv.visitInsn(Opcodes.RETURN);
		}
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}
}
