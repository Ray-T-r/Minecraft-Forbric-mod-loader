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
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Pins how a Mixin member target is split into owner/name/desc.
 *
 * <p>This is a DIAGNOSTIC's parser, which is exactly why it needs pinning: when it is wrong it does not crash, it
 * lies. Reading the dotted owner form as part of the method name made every such {@code @At(target=…)} report as an
 * unresolved anchor forever — 11 of Shoulder Surfing's mixins looked half-applied when every anchor was present —
 * and would have made {@code -Dforbric.mixinFit=strict} drop mixins that fit.
 */
class MixinFitTest {
	/**
	 * A mixin targeting one class, with one {@code @Shadow} field that the target does not have — the simplest
	 * anchor that can fail.
	 */
	private static byte[] shadowMixin(String target) {
		org.objectweb.asm.ClassWriter cw =
				new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
		cw.visit(org.objectweb.asm.Opcodes.V21, org.objectweb.asm.Opcodes.ACC_PUBLIC, "test/TheMixin",
				null, "java/lang/Object", null);
		org.objectweb.asm.AnnotationVisitor mixin =
				cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		org.objectweb.asm.AnnotationVisitor targets = mixin.visitArray("targets");
		targets.visit(null, target);
		targets.visitEnd();
		mixin.visitEnd();
		org.objectweb.asm.FieldVisitor fv = cw.visitField(0, "notThere", "I", null, null);
		fv.visitAnnotation("Lorg/spongepowered/asm/mixin/Shadow;", false).visitEnd();
		fv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** An empty class, so the {@code @Shadow} above cannot resolve against it. */
	private static byte[] emptyClass(String name) {
		org.objectweb.asm.ClassWriter cw =
				new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
		cw.visit(org.objectweb.asm.Opcodes.V21, org.objectweb.asm.Opcodes.ACC_PUBLIC, name, null,
				"java/lang/Object", null);
		cw.visitEnd();
		return cw.toByteArray();
	}

	/**
	 * The discriminator this repo now reports on, and the reason it is worth reporting.
	 *
	 * <p>An anchor that misses on a merged-base class is routine — measured across every gate log here, 1226 of
	 * them, all on runs that pass. One that misses on ANOTHER MOD's class did not occur once in that same set,
	 * and the one real instance found was Iris beside a Sodium build it no longer fits. Same unresolved anchor,
	 * completely different meaning, so {@code evaluate} has to tell them apart.
	 */
	@Test
	void anAnchorThatMissesOnAnotherModsClassIsSeparatedFromOneThatMissesOnTheGame() {
		String target = "net/example/OtherMod";
		java.util.function.Function<String, byte[]> resolver =
				name -> (target + ".class").equals(name) ? emptyClass(target) : null;

		MixinFit.Result asGame = MixinFit.evaluate(shadowMixin(target), resolver, name -> true);
		assertEquals(MixinFit.Verdict.UNFIT, asGame.verdict(), "the anchor misses either way");
		assertTrue(asGame.foreign().isEmpty(), "a merged-base miss is routine and must stay quiet");

		MixinFit.Result asMod = MixinFit.evaluate(shadowMixin(target), resolver, name -> false);
		assertEquals(1, asMod.foreign().size(), "a miss on another mod's class is the signal");
		assertTrue(asMod.foreign().get(0).contains("notThere"), asMod.foreign().toString());
	}

	@Test
	void anAnchorThatRESOLVESIsNeverReportedAsForeign() {
		// The obvious way to get this wrong: report every cross-mod mixin instead of every cross-mod mixin that
		// did not attach. Every pack is full of the former.
		String target = "net/example/OtherMod";
		org.objectweb.asm.ClassWriter cw =
				new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
		cw.visit(org.objectweb.asm.Opcodes.V21, org.objectweb.asm.Opcodes.ACC_PUBLIC, target, null,
				"java/lang/Object", null);
		cw.visitField(0, "notThere", "I", null, null).visitEnd();
		cw.visitEnd();
		byte[] withField = cw.toByteArray();

		MixinFit.Result fit = MixinFit.evaluate(shadowMixin(target),
				name -> (target + ".class").equals(name) ? withField : null, name -> false);
		assertEquals(MixinFit.Verdict.FIT, fit.verdict());
		assertTrue(fit.foreign().isEmpty(), "it attached — there is nothing to tell the player");
	}

	@Test
	void descriptorFormOwnerIsStripped() {
		MixinFit.Member m = MixinFit.parseMember("Lnet/minecraft/client/CameraType;isFirstPerson()Z");
		assertEquals("net/minecraft/client/CameraType", m.owner());
		assertEquals("isFirstPerson", m.name());
		assertEquals("()Z", m.desc());
	}

	/** The case this fix exists for: Shoulder Surfing, malilib and litematica all write targets this way. */
	@Test
	void dottedInternalNameOwnerIsStripped() {
		MixinFit.Member m = MixinFit.parseMember("net/minecraft/client/CameraType.isFirstPerson()Z");
		assertEquals("net/minecraft/client/CameraType", m.owner());
		assertEquals("isFirstPerson", m.name(), "the owner must not end up glued to the method name");
		assertEquals("()Z", m.desc());
	}

	@Test
	void aFullyDottedOwnerBecomesAnInternalName() {
		MixinFit.Member m = MixinFit.parseMember("com.example.Foo.bar()V");
		assertEquals("com/example/Foo", m.owner(), "owners are compared against ASM's internal names");
		assertEquals("bar", m.name());
	}

	@Test
	void aConstructorTargetKeepsItsAngleBrackets() {
		MixinFit.Member m = MixinFit.parseMember("net/minecraft/client/model/Model.<init>");
		assertEquals("net/minecraft/client/model/Model", m.owner());
		assertEquals("<init>", m.name());
		assertNull(m.desc());
	}

	@Test
	void aBareNameHasNoOwner() {
		MixinFit.Member m = MixinFit.parseMember("addToTooltip");
		assertNull(m.owner());
		assertEquals("addToTooltip", m.name());
		assertNull(m.desc());
	}

	@Test
	void aFieldTargetSplitsOnTheColon() {
		MixinFit.Member m = MixinFit.parseMember("net/minecraft/client/gui/Hud.random:Lnet/minecraft/util/RandomSource;");
		assertEquals("net/minecraft/client/gui/Hud", m.owner());
		assertEquals("random", m.name());
		assertEquals("Lnet/minecraft/util/RandomSource;", m.desc());
	}

	/** Shoulder Surfing writes a space before the descriptor; Mixin ignores it, so this must too. */
	@Test
	void whitespaceInsideTheMemberIsIgnored() {
		MixinFit.Member m = MixinFit.parseMember(
				"net/minecraft/client/renderer/entity/EntityRenderer.createRenderState ()Lnet/minecraft/client/renderer/entity/state/EntityRenderState;");
		assertEquals("net/minecraft/client/renderer/entity/EntityRenderer", m.owner());
		assertEquals("createRenderState", m.name(), "a trailing space must not become part of the method name");
		assertEquals("()Lnet/minecraft/client/renderer/entity/state/EntityRenderState;", m.desc());
	}

	/**
	 * A mixin with one {@code @Inject(method = "discover", at = @At(value = "INVOKE", target = <resolve>))} —
	 * the bare-name selector fabric-model-loading-api-v1 writes for {@code discoverModelDependencies}.
	 */
	private static byte[] injectMixin(String target, String atTarget) {
		org.objectweb.asm.ClassWriter cw =
				new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
		cw.visit(org.objectweb.asm.Opcodes.V21, org.objectweb.asm.Opcodes.ACC_PUBLIC, "test/TheMixin",
				null, "java/lang/Object", null);
		org.objectweb.asm.AnnotationVisitor mixin =
				cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		org.objectweb.asm.AnnotationVisitor targets = mixin.visitArray("targets");
		targets.visit(null, target);
		targets.visitEnd();
		mixin.visitEnd();
		org.objectweb.asm.MethodVisitor mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PRIVATE, "handler",
				"(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
		org.objectweb.asm.AnnotationVisitor inject =
				mv.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true);
		org.objectweb.asm.AnnotationVisitor method = inject.visitArray("method");
		method.visit(null, "discover");
		method.visitEnd();
		org.objectweb.asm.AnnotationVisitor ats = inject.visitArray("at");
		org.objectweb.asm.AnnotationVisitor at = ats.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
		at.visit("value", "INVOKE");
		at.visit("target", atTarget);
		at.visitEnd();
		ats.visitEnd();
		inject.visitEnd();
		mv.visitCode();
		mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/**
	 * A target with two {@code discover} overloads: {@code discover()V} delegates to {@code discover(I)V}, and only
	 * the second one calls {@code callee} — the merged {@code ModelManager.discoverModelDependencies} shape.
	 */
	private static byte[] twoOverloads(String name, String calleeOwner, String calleeName, String calleeDesc) {
		org.objectweb.asm.ClassWriter cw =
				new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
		cw.visit(org.objectweb.asm.Opcodes.V21, org.objectweb.asm.Opcodes.ACC_PUBLIC, name, null,
				"java/lang/Object", null);
		org.objectweb.asm.MethodVisitor delegating = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC,
				"discover", "()V", null, null);
		delegating.visitCode();
		delegating.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
		delegating.visitInsn(org.objectweb.asm.Opcodes.ICONST_0);
		delegating.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, name, "discover", "(I)V", false);
		delegating.visitInsn(org.objectweb.asm.Opcodes.RETURN);
		delegating.visitMaxs(0, 0);
		delegating.visitEnd();
		org.objectweb.asm.MethodVisitor real = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC,
				"discover", "(I)V", null, null);
		real.visitCode();
		real.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC, calleeOwner, calleeName, calleeDesc, false);
		real.visitInsn(org.objectweb.asm.Opcodes.POP);
		real.visitInsn(org.objectweb.asm.Opcodes.RETURN);
		real.visitMaxs(0, 0);
		real.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/**
	 * Mixin binds a bare-name selector to the FIRST declared overload only (single-match quantifier; TargetSelectors
	 * stops at the first match). fabric-model-loading-api-v1's {@code discoverModelDependencies} injector lands on the
	 * three-arg stub, and its handler measured zero references at runtime: PARTIAL is the truth, and MixinStubRebind is
	 * what moves it.
	 */
	@Test
	void aBareNameSelectorIsJudgedAgainstTheFirstDeclaredOverloadAsMixinBindsIt() {
		String target = "net/example/ModelManager";
		byte[] targetBytes = twoOverloads(target, "net/example/Discovery", "resolve", "()Ljava/util/Map;");
		java.util.function.Function<String, byte[]> resolver =
				name -> (target + ".class").equals(name) ? targetBytes : null;

		// Mixin binds "discover" to discover()V, declared first — the stub. The call lives only in the second overload,
		// so Mixin finds nothing to inject at: PARTIAL, as the zero handler references measured at runtime said.
		MixinFit.Result partial = MixinFit.evaluate(
				injectMixin(target, "Lnet/example/Discovery;resolve()Ljava/util/Map;"), resolver);
		assertEquals(MixinFit.Verdict.PARTIAL, partial.verdict(),
				"a bare name binds the first declared overload only: " + partial.unresolved());
		assertEquals(1, partial.unresolved().size(), partial.unresolved().toString());
		assertTrue(partial.unresolved().get(0).contains("resolve"), partial.unresolved().toString());
	}

	/**
	 * An {@code @At} into ANOTHER class is reported under that class's name. It used to be reported under the mixin's
	 * target: owo's anchor on Fabric Loader's {@code Hooks.startServer} read "missing: @At(INVOKE) Main.startServer in
	 * main", a method that does not exist, which hid that the missing piece was Fabric Loader's {@code Hooks} call.
	 */
	@Test
	void anAnchorIntoAnotherClassIsReportedUnderThatClass() {
		String target = "net/example/ModelManager";
		byte[] targetBytes = twoOverloads(target, "net/example/Discovery", "resolve", "()Ljava/util/Map;");
		java.util.function.Function<String, byte[]> resolver =
				name -> (target + ".class").equals(name) ? targetBytes : null;

		assertEquals(java.util.List.of("@At(INVOKE) Discovery.resolve in discover"),
				MixinFit.evaluate(injectMixin(target, "Lnet/example/Discovery;resolve()Ljava/util/Map;"), resolver)
						.unresolved());
		assertEquals(java.util.List.of("@At(INVOKE) Discovery.resolve in discover"),
				MixinFit.evaluate(injectMixin(target, "net/example/Discovery.resolve()Ljava/util/Map;"), resolver)
						.unresolved(), "the dotted owner form names the same class");
	}

	/** An anchor into the target itself, or with no owner at all, keeps the form it always had. */
	@Test
	void anAnchorIntoTheTargetItselfKeepsTheTargetsName() {
		String target = "net/example/ModelManager";
		byte[] targetBytes = twoOverloads(target, target, "resolve", "()Ljava/util/Map;");
		java.util.function.Function<String, byte[]> resolver =
				name -> (target + ".class").equals(name) ? targetBytes : null;

		assertEquals(java.util.List.of("@At(INVOKE) ModelManager.resolve in discover"),
				MixinFit.evaluate(injectMixin(target, "Lnet/example/ModelManager;resolve()Ljava/util/Map;"), resolver)
						.unresolved());
		assertEquals(java.util.List.of("@At(INVOKE) ModelManager.resolve in discover"),
				MixinFit.evaluate(injectMixin(target, "resolve()Ljava/util/Map;"), resolver).unresolved());
	}

	/** A target whose method constructs {@code T} with {@code arity} arguments (all int). */
	private static byte[] constructing(String type, int arity) {
		org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
		cw.visit(org.objectweb.asm.Opcodes.V21, org.objectweb.asm.Opcodes.ACC_PUBLIC, "net/example/Builder", null, "java/lang/Object", null);
		org.objectweb.asm.MethodVisitor mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "build", "()L" + type + ";", null, null);
		mv.visitCode();
		mv.visitTypeInsn(org.objectweb.asm.Opcodes.NEW, type);
		mv.visitInsn(org.objectweb.asm.Opcodes.DUP);
		StringBuilder desc = new StringBuilder("(");
		for (int i = 0; i < arity; i++) { mv.visitInsn(org.objectweb.asm.Opcodes.ICONST_0); desc.append('I'); }
		mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, type, "<init>", desc + ")V", false);
		mv.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A mixin {@code @WrapOperation(method="build", at=@At(NEW, target=type))} whose handler takes {@code arity} ints + Operation. */
	private static byte[] wrappingNew(String type, int arity) {
		org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
		cw.visit(org.objectweb.asm.Opcodes.V21, org.objectweb.asm.Opcodes.ACC_PUBLIC, "test/TheMixin", null, "java/lang/Object", null);
		org.objectweb.asm.AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		org.objectweb.asm.AnnotationVisitor targets = mixin.visitArray("targets");
		targets.visit(null, "net/example/Builder");
		targets.visitEnd();
		mixin.visitEnd();
		StringBuilder desc = new StringBuilder("(");
		for (int i = 0; i < arity; i++) desc.append('I');
		desc.append("Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)L").append(type).append(';');
		org.objectweb.asm.MethodVisitor mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PRIVATE, "wrap", desc.toString(), null, null);
		org.objectweb.asm.AnnotationVisitor wrap = mv.visitAnnotation("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;", true);
		org.objectweb.asm.AnnotationVisitor method = wrap.visitArray("method");
		method.visit(null, "build");
		method.visitEnd();
		org.objectweb.asm.AnnotationVisitor at = wrap.visitAnnotation("at", "Lorg/spongepowered/asm/mixin/injection/At;");
		at.visit("value", "NEW");
		at.visit("target", "L" + type + ";");
		at.visitEnd();
		wrap.visitEnd();
		mv.visitCode();
		mv.visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL);
		mv.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/**
	 * {@code @At(NEW)} was never judged, so fabric-rendering-v1's snippet wrap read FIT while Mixin rejected it at
	 * apply time ("has an invalid signature": the merged constructor takes 12 arguments, the handler wraps 11).
	 */
	@Test
	void aWrapOperationOnNewIsJudgedByTheConstructorsArity() {
		String type = "net/example/Snippet";
		java.util.function.Function<String, byte[]> twelve = name -> "net/example/Builder.class".equals(name) ? constructing(type, 12) : null;
		MixinFit.Result mismatch = MixinFit.evaluate(wrappingNew(type, 11), twelve);
		// The selector itself resolves (build exists); only the NEW anchor misses → PARTIAL, kept by default.
		assertEquals(MixinFit.Verdict.PARTIAL, mismatch.verdict(), mismatch.unresolved().toString());
		assertTrue(mismatch.unresolved().get(0).contains("handler wraps a 11-arg constructor, the call site constructs with 12"),
				mismatch.unresolved().toString());

		MixinFit.Result match = MixinFit.evaluate(wrappingNew(type, 12), twelve);
		assertEquals(MixinFit.Verdict.FIT, match.verdict(), match.unresolved().toString());

		java.util.function.Function<String, byte[]> none = name -> "net/example/Builder.class".equals(name) ? emptyClass("net/example/Builder") : null;
		MixinFit.Result noMethod = MixinFit.evaluate(wrappingNew(type, 11), none);
		assertEquals(MixinFit.Verdict.UNFIT, noMethod.verdict(), "no `build` method at all: the selector misses, as before");
		assertEquals(1, noMethod.unresolved().size(), "and no NEW anchor is added for a selector that bound nowhere: " + noMethod.unresolved());
	}

	/**
	 * A sugar parameter after the Operation (fabric-resource-conditions' {@code @Local(argsOnly=true) Resource}) is the
	 * target method's, not the constructor's: the handler still wraps the constructor it names.
	 */
	@Test
	void aSugarParameterIsNotCountedAsAConstructorArgument() {
		String type = "net/example/Snippet";
		java.util.function.Function<String, byte[]> three = name -> "net/example/Builder.class".equals(name) ? constructing(type, 3) : null;
		org.objectweb.asm.tree.ClassNode mixin = MixinFit.parse(wrappingNew(type, 3));
		org.objectweb.asm.tree.MethodNode wrap = mixin.methods.stream().filter(m -> m.name.equals("wrap")).findFirst().orElseThrow();
		wrap.desc = wrap.desc.replace("Operation;)", "Operation;I)");
		@SuppressWarnings("unchecked")
		java.util.List<org.objectweb.asm.tree.AnnotationNode>[] params = new java.util.List[5];
		params[4] = new java.util.ArrayList<>(java.util.List.of(new org.objectweb.asm.tree.AnnotationNode("Lcom/llamalad7/mixinextras/sugar/Local;")));
		wrap.invisibleParameterAnnotations = params;
		org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
		mixin.accept(writer);
		MixinFit.Result result = MixinFit.evaluate(writer.toByteArray(), three);
		assertEquals(MixinFit.Verdict.FIT, result.verdict(), result.unresolved().toString());
	}

	/** An {@code @Accessor} names a field by name AND descriptor; the merge re-typed AttributeSupplier$Builder.builder. */
	@Test
	void anAccessorOnAReTypedFieldIsAnUnresolvedAnchor() {
		String target = "net/example/AttributeBuilder";
		org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
		cw.visit(org.objectweb.asm.Opcodes.V21, org.objectweb.asm.Opcodes.ACC_PUBLIC, target, null, "java/lang/Object", null);
		cw.visitField(org.objectweb.asm.Opcodes.ACC_PRIVATE, "builder", "Ljava/util/Map;", null, null).visitEnd();
		cw.visitEnd();
		byte[] targetBytes = cw.toByteArray();

		org.objectweb.asm.ClassWriter mw = new org.objectweb.asm.ClassWriter(0);
		mw.visit(org.objectweb.asm.Opcodes.V21, org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_INTERFACE | org.objectweb.asm.Opcodes.ACC_ABSTRACT,
				"test/BuilderAccessor", null, "java/lang/Object", null);
		org.objectweb.asm.AnnotationVisitor mixin = mw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		org.objectweb.asm.AnnotationVisitor targets = mixin.visitArray("targets");
		targets.visit(null, target);
		targets.visitEnd();
		mixin.visitEnd();
		org.objectweb.asm.MethodVisitor mv = mw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_ABSTRACT,
				"getBuilder", "()Lcom/google/common/collect/ImmutableMap$Builder;", null, null);
		mv.visitAnnotation("Lorg/spongepowered/asm/mixin/gen/Accessor;", false).visitEnd();
		mv.visitEnd();
		mw.visitEnd();

		MixinFit.Result r = MixinFit.evaluate(mw.toByteArray(), name -> (target + ".class").equals(name) ? targetBytes : null);
		assertEquals(1, r.unresolved().size(), r.unresolved().toString());
		assertTrue(r.unresolved().get(0).contains("@Accessor field") && r.unresolved().get(0).contains("builder:Lcom/google/common/collect/ImmutableMap$Builder;"),
				r.unresolved().toString());
	}

	/** A mixin with one @Inject(method="run") HEAD on the given target — every member anchor resolves. */
	private static byte[] injectRun(String target) {
		org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
		cw.visit(org.objectweb.asm.Opcodes.V21, org.objectweb.asm.Opcodes.ACC_PUBLIC, "test/TheMixin", null, "java/lang/Object", null);
		org.objectweb.asm.AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		org.objectweb.asm.AnnotationVisitor targets = mixin.visitArray("targets");
		targets.visit(null, target);
		targets.visitEnd();
		mixin.visitEnd();
		org.objectweb.asm.MethodVisitor mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PRIVATE, "handler",
				"(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
		org.objectweb.asm.AnnotationVisitor inject = mv.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true);
		org.objectweb.asm.AnnotationVisitor method = inject.visitArray("method");
		method.visit(null, "run");
		method.visitEnd();
		org.objectweb.asm.AnnotationVisitor ats = inject.visitArray("at");
		org.objectweb.asm.AnnotationVisitor at = ats.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
		at.visit("value", "HEAD");
		at.visitEnd();
		ats.visitEnd();
		inject.visitEnd();
		mv.visitCode();
		mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static byte[] withRun(String name) {
		org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
		cw.visit(org.objectweb.asm.Opcodes.V21, org.objectweb.asm.Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		org.objectweb.asm.MethodVisitor mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "run", "()V", null, null);
		mv.visitCode();
		mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/**
	 * A renumbered anonymous class: every member anchor resolves, and the mixin still binds to the wrong class.
	 * The soft anchor makes that PARTIAL (listed, kept) — never UNFIT, so nothing that works today is dropped.
	 */
	@Test
	void aMixinOnARenumberedAnonymousClassIsPartialNeverUnfit() {
		String relocated = "net/minecraft/network/codec/ByteBufCodecs$13";
		MixinFit.Result r = MixinFit.evaluate(injectRun(relocated), name -> (relocated + ".class").equals(name) ? withRun(relocated) : null);
		assertEquals(MixinFit.Verdict.PARTIAL, r.verdict(), r.unresolved().toString());
		assertTrue(r.unresolved().get(0).startsWith("@Mixin target") && r.unresolved().get(0).contains("ByteBufCodecs$12"), r.unresolved().toString());
		assertTrue(!r.shouldSuppress(), "PARTIAL is kept by default");

		// With NO other anchor at all it is still PARTIAL: a soft miss can never make a mixin UNFIT.
		MixinFit.Result alone = MixinFit.evaluate(shadowlessMixin(relocated), name -> (relocated + ".class").equals(name) ? withRun(relocated) : null);
		assertEquals(MixinFit.Verdict.PARTIAL, alone.verdict(), alone.unresolved().toString());

		// A capture-only drift (chat_heads' ChatComponent$1) is not flagged.
		String captureOnly = "net/minecraft/client/gui/components/ChatComponent$1";
		assertEquals(MixinFit.Verdict.FIT, MixinFit.evaluate(injectRun(captureOnly), name -> (captureOnly + ".class").equals(name) ? withRun(captureOnly) : null).verdict());
		// And another mod's class of a drifted-looking name is not the game's: no soft anchor.
		String foreign = "com/example/Thing$13";
		assertEquals(MixinFit.Verdict.FIT, MixinFit.evaluate(injectRun(foreign), name -> (foreign + ".class").equals(name) ? withRun(foreign) : null, name -> false).verdict());
	}

	/** A mixin with no members at all — only the @Mixin annotation. */
	private static byte[] shadowlessMixin(String target) {
		org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
		cw.visit(org.objectweb.asm.Opcodes.V21, org.objectweb.asm.Opcodes.ACC_PUBLIC, "test/TheMixin", null, "java/lang/Object", null);
		org.objectweb.asm.AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		org.objectweb.asm.AnnotationVisitor targets = mixin.visitArray("targets");
		targets.visit(null, target);
		targets.visitEnd();
		mixin.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	@Test
	void aWildcardIsNotOursToJudge() {
		assertNull(MixinFit.parseMember("render*"), "a wildcard target must stay unjudged, not resolve to nothing");
	}
}
