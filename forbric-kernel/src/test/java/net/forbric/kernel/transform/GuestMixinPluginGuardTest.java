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
}
