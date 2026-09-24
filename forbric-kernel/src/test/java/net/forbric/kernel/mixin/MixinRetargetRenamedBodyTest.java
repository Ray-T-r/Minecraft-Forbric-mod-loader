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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Rule R3: the selector's method lost its body to a carrier RENAME, and the renamed one has the SAME descriptor.
 *
 * <p>The live case is {@code ItemStack.addDetailsToTooltip}: NeoForge moved vanilla's body into a private
 * {@code addDetailsToTooltipComponents} taking the same arguments and left the original as a dispatcher over its
 * own tooltip handler. fabric-item-api-v1's five injections all anchor inside the moved body, so a Fabric mod's
 * registered tooltip providers were recorded and never applied.
 *
 * <p>Identical descriptor is what makes the rewrite safe — every handler parameter, {@code CallbackInfo} and
 * {@code @Local} stays as valid as it was — and it is also the guard that has to hold: a candidate of a DIFFERENT
 * shape, or two candidates that both fit, must be refused, because a rewrite to the wrong body runs the
 * injection somewhere the mod did not ask for, silently.
 */
class MixinRetargetRenamedBodyTest {
	private static final String TARGET = "net/example/ItemStack";
	private static final String DESC = "(Lnet/example/TooltipFlag;)V";
	private static final String AT_SHOWS = "L" + TARGET + ";shows()Z";
	private static final String MODIFY_ARG = "Lorg/spongepowered/asm/mixin/injection/ModifyArg;";

	@AfterEach
	void reset() {
		System.clearProperty(MixinRetarget.PROPERTY);
		MixinRetarget.reset();
	}

	@Test
	void aSelectorWhoseBodyWasRenamedIsReboundToTheRenamedOne() {
		byte[] mixin = mixin("addDetailsToTooltip");
		Function<String, byte[]> resolver = resolver(target(Map.of(
				"addDetailsToTooltip", false, "addDetailsToTooltipComponents", true)));
		assertEquals(MixinFit.Verdict.PARTIAL, MixinFit.evaluate(mixin, resolver).verdict(), "premise");

		MixinRetarget.Plan plan = MixinRetarget.plan(MixinFit.parse(mixin), resolver);
		assertEquals(1, plan.rewrites().size(), plan.describe());
		assertEquals("addDetailsToTooltipComponents" + DESC, plan.rewrites().get(0).to());

		assertEquals(MixinFit.Verdict.FIT,
				MixinFit.evaluate(MixinRetarget.rewritten(mixin, plan), resolver).verdict(),
				"the rewritten mixin must actually bind — a plan that does not improve the verdict is kept by "
						+ "nobody");
	}

	/** Nothing moved: the anchor is still in the method the mod named, so there is nothing to rebind. */
	@Test
	void aSelectorThatStillHasItsAnchorIsLeftAlone() {
		Function<String, byte[]> resolver = resolver(target(Map.of(
				"addDetailsToTooltip", true, "addDetailsToTooltipComponents", true)));
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin("addDetailsToTooltip")), resolver).isEmpty());
	}

	/**
	 * Two same-shaped methods both carrying the anchor: refuse. NeoForge really does split that body in two —
	 * {@code addDetailsToTooltipComponents} and {@code addDetailsToTooltipTail} — and picking one would be a
	 * guess that runs an injection in the wrong half.
	 */
	@Test
	void twoCandidatesThatBothFitAreRefused() {
		Map<String, Boolean> methods = new LinkedHashMap<>();
		methods.put("addDetailsToTooltip", false);
		methods.put("addDetailsToTooltipComponents", true);
		methods.put("addDetailsToTooltipTail", true);
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin("addDetailsToTooltip")), resolver(target(methods))).isEmpty(),
				"a rewrite to the wrong half is an injection running where the mod did not ask, silently");
	}

	/**
	 * A candidate of a DIFFERENT shape is refused however well its body matches: the whole safety of this rule is
	 * that the handler's parameters, CallbackInfo and @Local captures stay valid, which only an identical
	 * descriptor guarantees.
	 */
	@Test
	void aCandidateWithAnotherDescriptorIsRefused() {
		byte[] target = targetWith(
				method("addDetailsToTooltip", DESC, false),
				method("addDetailsToTooltipComponents", "(Lnet/example/TooltipFlag;I)V", true));
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin("addDetailsToTooltip")), resolver(target)).isEmpty());
	}

	/**
	 * The mod names the method by bare name, and the target OVERRIDES a superclass method of the same name and
	 * descriptor (ServerPlayer.startSleepInBed over Player's). That is still one method to Mixin, and NeoForge's
	 * lambda that now carries the body is rebound to, as apoli-legacy's preventAvianSleep needs.
	 */
	@Test
	void anOverriddenSuperclassMethodIsNotASecondCandidate() {
		byte[] base = baseWith(method("addDetailsToTooltip", DESC, false));
		byte[] target = targetWith("net/example/Base", method("addDetailsToTooltip", DESC, false),
				method("lambda$addDetailsToTooltip$0", DESC, true));
		Function<String, byte[]> resolver = Map.of(TARGET + ".class", target, "net/example/Base.class", base)::get;
		MixinRetarget.Plan plan = MixinRetarget.plan(MixinFit.parse(mixin("addDetailsToTooltip")), resolver);
		assertEquals(1, plan.rewrites().size(), plan.describe());
		assertEquals("lambda$addDetailsToTooltip$0" + DESC, plan.rewrites().get(0).to());
	}

	/** A static body cannot take an instance handler: refused whatever it calls. */
	@Test
	void aCandidateOfTheOtherStaticnessIsRefused() {
		byte[] target = targetWith(null, method("addDetailsToTooltip", DESC, false),
				new MethodSpec("lambda$addDetailsToTooltip$0", DESC, true, true));
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin("addDetailsToTooltip")), resolver(target)).isEmpty());
	}

	@Test
	void theSwitchOffPlansNothing() {
		System.setProperty(MixinRetarget.PROPERTY, "off");
		Function<String, byte[]> resolver = resolver(target(Map.of(
				"addDetailsToTooltip", false, "addDetailsToTooltipComponents", true)));
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin("addDetailsToTooltip")), resolver).isEmpty());
	}

	// --- fixtures ---

	/** {@code name → whether its body calls shows()}, all with the same descriptor. */
	private static byte[] target(Map<String, Boolean> methods) {
		Object[] specs = methods.entrySet().stream()
				.map(e -> (Object) method(e.getKey(), DESC, e.getValue())).toArray();
		return targetWith(specs);
	}

	private record MethodSpec(String name, String desc, boolean callsShows, boolean isStatic) {
		MethodSpec(String name, String desc, boolean callsShows) { this(name, desc, callsShows, false); }
	}

	private static MethodSpec method(String name, String desc, boolean callsShows) {
		return new MethodSpec(name, desc, callsShows);
	}

	private static byte[] targetWith(Object... specs) {
		return targetWith(null, specs);
	}

	private static byte[] baseWith(MethodSpec spec) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "net/example/Base", null, "java/lang/Object", null);
		MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, spec.name(), spec.desc(), null, null);
		m.visitCode(); m.visitInsn(Opcodes.RETURN); m.visitMaxs(0, 0); m.visitEnd(); cw.visitEnd();
		return cw.toByteArray();
	}

	private static byte[] targetWith(String superName, Object... specs) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, TARGET, null, superName == null ? "java/lang/Object" : superName, null);
		for (Object raw : specs) {
			MethodSpec spec = (MethodSpec) raw;
			MethodVisitor m = cw.visitMethod(Opcodes.ACC_PRIVATE | (spec.isStatic() ? Opcodes.ACC_STATIC : 0), spec.name(), spec.desc(), null, null);
			m.visitCode();
			if (spec.callsShows() && spec.isStatic()) {
				m.visitInsn(Opcodes.ACONST_NULL);
				m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TARGET, "shows", "()Z", false);
				m.visitInsn(Opcodes.POP);
			} else if (spec.callsShows()) {
				m.visitVarInsn(Opcodes.ALOAD, 0);
				m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TARGET, "shows", "()Z", false);
				m.visitInsn(Opcodes.POP);
			}
			m.visitInsn(Opcodes.RETURN);
			m.visitMaxs(0, 0);
			m.visitEnd();
		}
		MethodVisitor shows = cw.visitMethod(Opcodes.ACC_PUBLIC, "shows", "()Z", null, null);
		shows.visitCode();
		shows.visitInsn(Opcodes.ICONST_1);
		shows.visitInsn(Opcodes.IRETURN);
		shows.visitMaxs(0, 0);
		shows.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** One {@code @ModifyArg} at INVOKE shows(), selecting {@code selector} by bare name, as the real mixin does. */
	private static byte[] mixin(String selector) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "test/ItemStackMixin", null, "java/lang/Object", null);
		AnnotationVisitor at = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		AnnotationVisitor targets = at.visitArray("value");
		targets.visit(null, Type.getObjectType(TARGET));
		targets.visitEnd();
		at.visitEnd();

		MethodVisitor h = cw.visitMethod(Opcodes.ACC_PRIVATE, "handler", "()V", null, null);
		AnnotationVisitor inj = h.visitAnnotation(MODIFY_ARG, true);
		AnnotationVisitor method = inj.visitArray("method");
		method.visit(null, selector);
		method.visitEnd();
		AnnotationVisitor atNode = inj.visitAnnotation("at", "Lorg/spongepowered/asm/mixin/injection/At;");
		atNode.visit("value", "INVOKE");
		atNode.visit("target", AT_SHOWS);
		atNode.visitEnd();
		inj.visitEnd();
		h.visitCode();
		h.visitInsn(Opcodes.RETURN);
		h.visitMaxs(0, 8);
		h.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static Function<String, byte[]> resolver(byte[] targetBytes) {
		Map<String, byte[]> classes = Map.of(TARGET + ".class", targetBytes);
		return classes::get;
	}
}
