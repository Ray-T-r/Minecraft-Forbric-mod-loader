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

package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Handing a handler the arguments it was written to receive, out of a target that takes them in another order.
 *
 * <p>The live case: {@code LevelRenderer.addSkyPass}'s lambda is {@code (GpuBufferSlice, SkyRenderState)} and
 * static in vanilla and on MinecraftForge's base, and {@code (SkyRenderState, Matrix4fc, GpuBufferSlice)} and an
 * instance method on NeoForge's. The merge kept NeoForge's and the pruner dropped the other, so Apoli's sky-skip
 * has a live injection point and the wrong parameter list.
 *
 * <p>The generated wrapper is not asserted as text: it is DEFINED and CALLED, with recorded arguments, so the
 * test fails on a mapping that compiles and hands over the wrong object — which is the only failure mode of this
 * class that matters.
 */
public class MixinHandlerShimTest {
	private static final String TARGET = "net/example/Renderer";
	private static final String MIXIN = "net/example/RendererMixin";
	private static final String CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
	private static final String SELECTOR = "lambda$addSkyPass$0";

	/** What the inner handler was handed, in order, by the generated wrapper. */
	public static final List<Object> RECEIVED = new ArrayList<>();

	@Test
	void theWrapperHandsTheHandlerItsOwnArgumentsInItsOwnOrder() throws Exception {
		RECEIVED.clear();
		ClassNode mixin = mixin("(Ljava/lang/Object;Ljava/lang/String;" + CI + ")V", true);
		assertEquals(1, MixinHandlerShim.adapt(mixin, targets(instanceTarget())));

		Class<?> defined = define(mixin);
		Method outer = null;
		for (Method m : defined.getDeclaredMethods()) {
			if (m.getName().equals("handler") && !m.getName().endsWith(MixinHandlerShim.INNER_SUFFIX)
					&& m.getParameterCount() == 4) {
				outer = m;
			}
		}
		assertNotNull(outer, "the wrapper must be the one that keeps the handler's name");
		outer.setAccessible(true);

		Object payload = new Object();
		outer.invoke(defined.getDeclaredConstructor().newInstance(), "sky", 7, payload, null);

		assertEquals(List.of(payload, "sky"), RECEIVED,
				"the handler asked for (Object, String) and the target offers (String, int, Object) — it must "
						+ "receive the Object and the String, in that order");
	}

	@Test
	void theOriginalHandlerKeepsItsBodyAndLosesItsAnnotation() {
		RECEIVED.clear();
		ClassNode mixin = mixin("(Ljava/lang/Object;Ljava/lang/String;" + CI + ")V", true);
		MixinHandlerShim.adapt(mixin, targets(instanceTarget()));

		MethodNode inner = methodNamed(mixin, "handler" + MixinHandlerShim.INNER_SUFFIX);
		assertNotNull(inner, "the mod's own code is moved aside, not rewritten");
		assertTrue(inner.visibleAnnotations == null || inner.visibleAnnotations.isEmpty(),
				"only one of the two may carry the @Inject, or Mixin injects twice");
		assertNotNull(methodNamed(mixin, "handler").visibleAnnotations.get(0));
	}

	/** No pruner record for that name: nothing says the mod was written for a shape this class ever had. */
	@Test
	void aHandlerWithNoDroppedShapeToPointAtIsLeftAlone() {
		ClassNode mixin = mixin("(Ljava/lang/Object;Ljava/lang/String;" + CI + ")V", true);
		setSelector(mixin, "lambda$nothingWasDropped$0");
		ClassNode target = new ClassNode();
		target.name = TARGET;
		target.methods = new ArrayList<>(List.of(instanceMethod("lambda$nothingWasDropped$0",
				"(Ljava/lang/String;ILjava/lang/Object;)V")));
		assertEquals(0, MixinHandlerShim.adapt(mixin, targets(target)),
				"without the pruner's record this is just a handler Mixin may well accept as written");
	}

	/** A handler that already fits the surviving shape needs nothing, whatever was dropped beside it. */
	@Test
	void aHandlerThatAlreadyFitsIsLeftAlone() {
		ClassNode mixin = mixin("(Ljava/lang/String;ILjava/lang/Object;" + CI + ")V", true);
		assertEquals(0, MixinHandlerShim.adapt(mixin, targets(instanceTarget())));
	}

	/**
	 * A handler that fits the SURVIVOR is left alone even when a shape it also fits was dropped beside it.
	 *
	 * <p>The pruner drops one body per name and a class can lose one shaped like the one it kept. Wrapping then
	 * would put a wrapper around a handler that binds perfectly well — the churn this class exists not to cause.
	 */
	@Test
	void aHandlerThatFitsTheSurvivorIsLeftAloneEvenWhenItsShapeWasAlsoDropped() {
		net.forbric.kernel.transform.DuplicateLambdaPruneInjector.recordDroppedForTest(
				TARGET, "lambda$sameShape$0", "(Ljava/lang/String;ILjava/lang/Object;)V");
		ClassNode mixin = mixin("(Ljava/lang/String;ILjava/lang/Object;" + CI + ")V", true);
		setSelector(mixin, "lambda$sameShape$0");
		ClassNode target = new ClassNode();
		target.name = TARGET;
		target.methods = new ArrayList<>(List.of(instanceMethod("lambda$sameShape$0",
				"(Ljava/lang/String;ILjava/lang/Object;)V")));

		assertEquals(0, MixinHandlerShim.adapt(mixin, targets(target)));
	}

	/**
	 * A type the target offers TWICE cannot be mapped: choosing is a coin toss, and a coin toss here hands the
	 * injection the wrong object silently, which is worse than the exception it would replace.
	 */
	@Test
	void anAmbiguousParameterTypeIsRefused() {
		net.forbric.kernel.transform.DuplicateLambdaPruneInjector.recordDroppedForTest(
				TARGET, "ambiguous", "(Ljava/lang/String;)V");
		ClassNode mixin = mixin("(Ljava/lang/String;" + CI + ")V", true);
		setSelector(mixin, "ambiguous");
		ClassNode target = new ClassNode();
		target.name = TARGET;
		target.methods = new ArrayList<>(List.of(
				instanceMethod("ambiguous", "(Ljava/lang/String;Ljava/lang/String;)V")));
		assertEquals(0, MixinHandlerShim.adapt(mixin, targets(target)));
	}

	@Test
	void theSwitchIsOnByDefaultAndOffWrapsNothing() {
		String previous = System.getProperty(MixinHandlerShim.PROPERTY);
		try {
			System.clearProperty(MixinHandlerShim.PROPERTY);
			assertTrue(MixinHandlerShim.enabled());

			System.setProperty(MixinHandlerShim.PROPERTY, "off");
			ClassNode mixin = mixin("(Ljava/lang/Object;Ljava/lang/String;" + CI + ")V", true);
			assertEquals(0, MixinHandlerShim.adapt(mixin, targets(instanceTarget())));
			assertNull(methodNamed(mixin, "handler" + MixinHandlerShim.INNER_SUFFIX));
		} finally {
			if (previous == null) System.clearProperty(MixinHandlerShim.PROPERTY);
			else System.setProperty(MixinHandlerShim.PROPERTY, previous);
		}
	}

	// --- fixtures ---

	/** NeoForge's surviving shape, with vanilla's recorded as dropped by the pruner. */
	private static ClassNode instanceTarget() {
		net.forbric.kernel.transform.DuplicateLambdaPruneInjector.recordDroppedForTest(
				TARGET, SELECTOR, "(Ljava/lang/Object;Ljava/lang/String;)V");
		ClassNode target = new ClassNode();
		target.name = TARGET;
		target.methods = new ArrayList<>(List.of(instanceMethod(SELECTOR,
				"(Ljava/lang/String;ILjava/lang/Object;)V")));
		return target;
	}

	private static MethodNode instanceMethod(String name, String desc) {
		return new MethodNode(Opcodes.ASM9, Opcodes.ACC_PRIVATE, name, desc, null, null);
	}

	/** A mixin whose static handler records what it is given, so the mapping can be checked by calling it. */
	private static ClassNode mixin(String handlerDesc, boolean staticHandler) {
		ClassNode mixin = new ClassNode();
		mixin.version = Opcodes.V21;
		mixin.access = Opcodes.ACC_PUBLIC;
		mixin.name = MIXIN;
		mixin.superName = "java/lang/Object";
		mixin.methods = new ArrayList<>();

		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		at.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType(TARGET)))));
		mixin.visibleAnnotations = new ArrayList<>(List.of(at));

		MethodNode ctor = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		ctor.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
		ctor.instructions.add(new org.objectweb.asm.tree.MethodInsnNode(
				Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
		ctor.instructions.add(new InsnNode(Opcodes.RETURN));
		ctor.maxStack = 1;
		ctor.maxLocals = 1;
		mixin.methods.add(ctor);

		MethodNode handler = new MethodNode(Opcodes.ASM9,
				Opcodes.ACC_PRIVATE | (staticHandler ? Opcodes.ACC_STATIC : 0), "handler", handlerDesc, null, null);
		AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
		inject.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of(SELECTOR))));
		handler.visibleAnnotations = new ArrayList<>(List.of(inject));
		// Records every argument except the trailing CallbackInfo, so the mapping is observable.
		int params = Type.getArgumentTypes(handlerDesc).length;
		for (int i = 0; i < params - 1; i++) {
			handler.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETSTATIC,
					"net/forbric/kernel/mixin/MixinHandlerShimTest", "RECEIVED", "Ljava/util/List;"));
			handler.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, i));
			handler.instructions.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKEINTERFACE,
					"java/util/List", "add", "(Ljava/lang/Object;)Z", true));
			handler.instructions.add(new InsnNode(Opcodes.POP));
		}
		handler.instructions.add(new InsnNode(Opcodes.RETURN));
		handler.maxStack = 3;
		handler.maxLocals = params + 1;
		mixin.methods.add(handler);
		return mixin;
	}

	private static void setSelector(ClassNode mixin, String selector) {
		AnnotationNode inject = methodNamed(mixin, "handler").visibleAnnotations.get(0);
		inject.values.set(1, new ArrayList<>(List.of(selector)));
	}

	private static MethodNode methodNamed(ClassNode mixin, String name) {
		for (MethodNode m : mixin.methods) if (m.name.equals(name)) return m;
		return null;
	}

	private static Function<String, ClassNode> targets(ClassNode target) {
		Map<String, ClassNode> byName = Map.of(target.name, target);
		return byName::get;
	}

	private static Class<?> define(ClassNode mixin) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		mixin.accept(writer);
		byte[] bytes = writer.toByteArray();
		ClassLoader loader = new ClassLoader(MixinHandlerShimTest.class.getClassLoader()) {
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException {
				if (!name.equals(MIXIN.replace('/', '.'))) throw new ClassNotFoundException(name);
				return defineClass(name, bytes, 0, bytes.length);
			}
		};
		try {
			return loader.loadClass(MIXIN.replace('/', '.'));
		} catch (ClassNotFoundException e) {
			throw new AssertionError("the generated wrapper does not even load — it cannot be right", e);
		}
	}
}
