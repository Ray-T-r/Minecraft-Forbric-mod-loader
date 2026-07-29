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

package net.forbric.kernel.boot;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import net.forbric.kernel.classloading.ForbricClassLoader;

/**
 * A full-power {@link MethodHandles.Lookup} whose lookup class lives on the GAME side.
 *
 * <p>Some ecosystem code (Forge's EventBus, which spins listener lambdas with {@code LambdaMetafactory}) requires
 * a full-power lookup — one holding the MODULE bit — over a class it is registering. A boot-side
 * {@code MethodHandles.lookup()} cannot produce that for a game class: teleporting it with
 * {@code privateLookupIn} across the boot→game module boundary drops the MODULE bit, and
 * {@code LambdaMetafactory} then rejects the caller ({@code LambdaConversionException: Invalid caller}).
 *
 * <p>The only way to mint a full-power lookup for a class is from code running in that class's own module. So the
 * kernel defines a one-method helper class DIRECTLY into {@link ForbricClassLoader} (via
 * {@link ForbricClassLoader#defineRuntimeClass}); calling its {@code lookup()} yields a lookup whose class is
 * game-side and in the game loader's unnamed module. {@link #privateLookupIn} then teleports that WITHIN the game
 * module — a full-power result LambdaMetafactory accepts.
 */
public final class KernelGameLookup {
	private static final String HELPER = "net.forbric.kernel.runtime.KernelGameLookupHelper";
	private static final String HELPER_INTERNAL = HELPER.replace('.', '/');

	private static volatile MethodHandles.Lookup gameLookup;

	private KernelGameLookup() {
	}

	/** The game-side full-power lookup, defined+cached on first use. */
	public static MethodHandles.Lookup get(ForbricClassLoader loader) throws ReflectiveOperationException {
		MethodHandles.Lookup l = gameLookup;
		if (l != null) return l;

		synchronized (KernelGameLookup.class) {
			if (gameLookup != null) return gameLookup;

			Class<?> helper = loader.defineRuntimeClass(HELPER, generate());
			Method lookupM = helper.getMethod("lookup");
			gameLookup = (MethodHandles.Lookup) lookupM.invoke(null);
			return gameLookup;
		}
	}

	/**
	 * A full-power lookup over {@code target}, suitable for {@code LambdaMetafactory}. {@code target} must be a
	 * game-side class (defined by {@code loader}); the teleport stays within the game module.
	 */
	public static MethodHandles.Lookup privateLookupIn(Class<?> target, ForbricClassLoader loader)
			throws ReflectiveOperationException {
		return MethodHandles.privateLookupIn(target, get(loader));
	}

	/**
	 * {@code public final class KernelGameLookupHelper { public static Lookup lookup() { return
	 * MethodHandles.lookup(); } }} — the smallest class whose {@code lookup()} returns a full-power, game-side
	 * lookup.
	 */
	private static byte[] generate() {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, HELPER_INTERNAL, null, "java/lang/Object", null);

		String lookupDesc = "()" + Type.getDescriptor(MethodHandles.Lookup.class);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "lookup", lookupDesc, null, null);
		mv.visitCode();
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/invoke/MethodHandles", "lookup",
				"()Ljava/lang/invoke/MethodHandles$Lookup;", false);
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(1, 0);
		mv.visitEnd();

		cw.visitEnd();
		return cw.toByteArray();
	}
}
