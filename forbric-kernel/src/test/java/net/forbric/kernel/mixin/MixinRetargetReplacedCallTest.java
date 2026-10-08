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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.zip.ZipFile;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * R7 on the real merged {@code StructureTemplate}: NeoForge's {@code placeInWorld} calls {@code addEntitiesToWorld} where
 * vanilla called {@code placeEntities}, which is gone. MoogsStructureLib's Fabric {@code EntityProcessorMixin}, as compiled,
 * records its context BEFORE the vanilla call, clears it AFTER, and places processed entities itself at the HEAD of
 * {@code placeEntities}, cancelling; on the merged base both points missed and the HEAD selector bound MinecraftForge's
 * reshaped {@code placeEntities}, which its handler does not fit.
 */
@ResourceLock("system-properties")
class MixinRetargetReplacedCallTest {
	private static final String TEMPLATE = "net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate";
	private static final String MIXIN = "test/EntityProcessorMixin";
	private static MergedBaseCalleeSwaps.Replaced row() { return NativeCallTestEvidence.structureRow(); }
	private static final String CALLBACK = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
	private static final String CALLBACK_RETURNABLE = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";

	@AfterEach void reset() {
		System.clearProperty(MixinRetarget.REPLACED_CALL_PROPERTY);
		MixinRetarget.reset();
		MixinStubRebind.forget();
	}

	private static Function<String, byte[]> merged() throws Exception {
		Path jar = TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(jar), "staged merged base required");
		byte[] template;
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			template = zip.getInputStream(zip.getEntry(TEMPLATE + ".class")).readAllBytes();
		}
		return name -> name.equals(TEMPLATE + ".class") ? template : null;
	}

	@Test void moogsPlacementFollowsNeoForgesAddEntitiesToWorld() throws Exception {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		Function<String, byte[]> resolver = merged();
		MixinFit.Result before = NativeCallTestEvidence.evaluate(moogsShaped(), resolver);
		assertEquals(MixinFit.Verdict.PARTIAL, before.verdict());
		assertEquals(List.of("@At(INVOKE) StructureTemplate.placeEntities in placeInWorld",
				"@Inject target StructureTemplate." + row().vanilla() + " is gone: the carrier replaced it with addEntitiesToWorld, "
						+ "and the name binds placeEntities" + forgeShape(resolver) + ", which the handler was not written for"),
				before.unresolved().stream().distinct().toList(), "the HEAD selector is a miss too: Mixin rejects its descriptor");

		MixinRetarget.Plan plan = NativeCallTestEvidence.plan(MixinFit.parse(moogsShaped()), resolver);
		assertEquals(List.of(MixinRetarget.Element.AT_TARGET, MixinRetarget.Element.AT_TARGET, MixinRetarget.Element.PROJECT),
				plan.rewrites().stream().map(MixinRetarget.Rewrite::element).toList(), plan.describe());
		assertEquals(row().replacementMember(), plan.rewrites().get(0).to());
		byte[] rewritten = MixinRetarget.rewritten(moogsShaped(), plan);
		assertEquals(MixinFit.Verdict.FIT, NativeCallTestEvidence.evaluate(rewritten, resolver).verdict());

		ClassNode served = MixinFit.parse(rewritten);
		MethodNode head = served.methods.stream().filter(m -> m.name.equals("processAndPlaceEntities")).findFirst().orElseThrow();
		assertEquals(Type.getMethodDescriptor(Type.VOID_TYPE, append(Type.getArgumentTypes(row().replacement().substring(
				row().replacement().indexOf('('))), Type.getType(CALLBACK))), head.desc);
		assertEquals(List.of(row().replacement()), MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(head), "method")));
		List<String> reads = new ArrayList<>();
		for (AbstractInsnNode insn : head.instructions) if (insn instanceof MethodInsnNode call) reads.add(call.name);
		assertEquals(List.of("getMirror", "getRotation", "getRotationPivot", "getBoundingBox", "shouldFinalizeEntities",
				MixinHandlerShim.asideName(MIXIN, "processAndPlaceEntities", MixinRetarget.PROJECTED_SUFFIX)), reads, "vanilla's arguments, read off the settings");
		assertNull(MixinFit.injectorOf(served.methods.stream().filter(m -> m.name.equals(MixinHandlerShim.asideName(MIXIN,
				"processAndPlaceEntities", MixinRetarget.PROJECTED_SUFFIX))).findFirst().orElseThrow()));
	}

	/** RED control: with R7 off the plan is empty and the verdict keeps both misses. */
	@Test void withTheRuleOffNothingMoves() throws Exception {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		System.setProperty(MixinRetarget.REPLACED_CALL_PROPERTY, "off");
		Function<String, byte[]> resolver = merged();
		assertTrue(NativeCallTestEvidence.plan(MixinFit.parse(moogsShaped()), resolver).isEmpty());
		assertEquals(MixinFit.Verdict.PARTIAL, NativeCallTestEvidence.evaluate(moogsShaped(), resolver).verdict());
	}

	/** Only the row's ecosystems: a NeoForge mod was compiled against addEntitiesToWorld, and its miss is its own. */
	@Test void aModOfAnotherEcosystemIsLeftAlone() throws Exception {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.NEOFORGE);
		Function<String, byte[]> resolver = merged();
		assertTrue(NativeCallTestEvidence.plan(MixinFit.parse(moogsShaped()), resolver).isEmpty());
		assertEquals(List.of("@At(INVOKE) StructureTemplate.placeEntities in placeInWorld",
				"@Inject target StructureTemplate.placeEntities binds placeEntities" + forgeShape(resolver)
						+ ", which the handler was not written for"),
				NativeCallTestEvidence.evaluate(moogsShaped(), resolver).unresolved().stream().distinct().toList(),
				"the HEAD selector's miss is seen without the row: only the move is the row's");
	}

	/**
	 * The HEAD injector alone: no anchor resolves, so the mixin is UNFIT, and the adapter still asks R7 and takes the move
	 * (MixinRetarget.adopt), where it used to ask only for a PARTIAL and drop this one as dead weight.
	 */
	@Test void aMixinWithOnlyTheHeadInjectorIsMovedNotDropped() throws Exception {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		Function<String, byte[]> resolver = merged();
		byte[] head = headOnly();
		MixinFit.Result fit = NativeCallTestEvidence.evaluate(head, resolver);
		assertEquals(MixinFit.Verdict.UNFIT, fit.verdict(), fit.reason());
		MixinRetarget.Adoption adoption = NativeCallTestEvidence.adopt(head, fit, resolver, b -> NativeCallTestEvidence.evaluate(b, resolver));
		assertTrue(adoption != null, "the adapter takes R7's move for an UNFIT mixin");
		assertEquals(MixinFit.Verdict.FIT, adoption.after().verdict());
		assertEquals(List.of(MixinRetarget.Element.PROJECT), adoption.plan().rewrites().stream().map(MixinRetarget.Rewrite::element).toList());
	}

	/** RED control: R7 off, or a mod of no known ecosystem — the UNFIT stands and the adapter drops and reports it. */
	@Test void withoutTheRuleTheHeadOnlyMixinStaysUnfit() throws Exception {
		Function<String, byte[]> resolver = merged();
		byte[] head = headOnly();
		MixinFit.Result unknown = NativeCallTestEvidence.evaluate(head, resolver);
		assertEquals(MixinFit.Verdict.UNFIT, unknown.verdict(), "a config two mods claim: no row applies, the miss is still seen");
		assertNull(NativeCallTestEvidence.adopt(head, unknown, resolver, b -> NativeCallTestEvidence.evaluate(b, resolver)));
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		System.setProperty(MixinRetarget.REPLACED_CALL_PROPERTY, "off");
		MixinFit.Result fit = NativeCallTestEvidence.evaluate(head, resolver);
		assertEquals(MixinFit.Verdict.UNFIT, fit.verdict());
		assertNull(NativeCallTestEvidence.adopt(head, fit, resolver, b -> NativeCallTestEvidence.evaluate(b, resolver)));
	}

	/**
	 * A rewrite that keeps an injector Mixin rejects outright is never taken for an UNFIT mixin: the HEAD injector moves,
	 * but a second injector whose name binds placeInWorld, which it was not written for, would make Mixin fail the
	 * rewritten mixin -- which the adapter otherwise leaves out cleanly. The same mixin without it is taken (the control).
	 */
	@Test void anUnfitMixinsRewriteMayKeepNoRejection() throws Exception {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		Function<String, byte[]> resolver = merged();
		byte[] both = withRefusedInjector(headOnly());
		MixinFit.Result fit = NativeCallTestEvidence.evaluate(both, resolver);
		assertEquals(MixinFit.Verdict.UNFIT, fit.verdict(), fit.reason());
		assertEquals(2, fit.rejected().size(), fit.rejected().toString());
		MixinRetarget.Plan plan = NativeCallTestEvidence.plan(MixinFit.parse(both), resolver);
		MixinFit.Result after = NativeCallTestEvidence.evaluate(MixinRetarget.rewritten(both, plan), resolver);
		assertEquals(MixinFit.Verdict.PARTIAL, after.verdict(), "the plan does move the HEAD injector: " + after.reason());
		assertEquals(List.of("onPlaceInWorld"), after.rejected().stream().map(MixinFit.Rejection::handler).toList());
		assertNull(NativeCallTestEvidence.adopt(both, fit, resolver, b -> NativeCallTestEvidence.evaluate(b, resolver)));
		assertNotNull(NativeCallTestEvidence.adopt(headOnly(), NativeCallTestEvidence.evaluate(headOnly(), resolver), resolver,
				b -> NativeCallTestEvidence.evaluate(b, resolver)), "control: without the refused injector the rewrite is taken");
	}

	/**
	 * A PARTIAL mixin's rewrite may keep the rejections it already had (the adapter prunes them as it would without the
	 * plan), never add one: judged against a verdict that had none, the same rewrite is refused.
	 */
	@Test void aPartialMixinsRewriteMayKeepOnlyTheRejectionsItHad() throws Exception {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		Function<String, byte[]> resolver = merged();
		byte[] three = withRefusedInjector(moogsShaped());
		MixinFit.Result fit = NativeCallTestEvidence.evaluate(three, resolver);
		assertEquals(MixinFit.Verdict.PARTIAL, fit.verdict(), fit.reason());
		MixinRetarget.Adoption adoption = NativeCallTestEvidence.adopt(three, fit, resolver, b -> NativeCallTestEvidence.evaluate(b, resolver));
		assertNotNull(adoption);
		assertEquals(List.of("onPlaceInWorld"), adoption.after().rejected().stream().map(MixinFit.Rejection::handler).toList());
		MixinFit.Result withoutRejections = new MixinFit.Result(fit.verdict(), fit.unresolved(), fit.resolved(), fit.total(), fit.foreign());
		assertNull(NativeCallTestEvidence.adopt(three, withoutRejections, resolver, b -> NativeCallTestEvidence.evaluate(b, resolver)),
				"RED control: a rejection the verdict did not have is one the plan added");
	}

	/** {@code mixinBytes} plus a HEAD injector whose name binds placeInWorld, which its handler was not written for. */
	private static byte[] withRefusedInjector(byte[] mixinBytes) {
		ClassNode mixin = MixinFit.parse(mixinBytes);
		mixin.methods.add(handler("onPlaceInWorld", "(Ljava/lang/String;" + CALLBACK + ")V", "placeInWorld",
				point("HEAD", null, null), false));
		ClassWriter writer = new ClassWriter(0);
		mixin.accept(writer);
		return writer.toByteArray();
	}

	/**
	 * The body a rewrite renames aside is merged into the target as a plain method, by name: two mixins on one target
	 * whose handlers share a name must not land on one name, or Mixin keeps one body ("Method overwrite conflict")
	 * and both wrappers call it. The aside name carries the mixin's mark.
	 */
	@Test void twoMixinsWithOneHandlerNameMoveTheirBodiesApart() throws Exception {
		Function<String, byte[]> resolver = merged();
		List<String> asides = new ArrayList<>();
		for (String name : List.of(MIXIN, "test/OtherEntityProcessorMixin")) {
			ClassNode mixin = MixinFit.parse(headOnly());
			mixin.name = name;
			MixinStubRebind.noteEcosystem(name, Ecosystem.FABRIC);
			ClassWriter writer = new ClassWriter(0);
			mixin.accept(writer);
			byte[] bytes = writer.toByteArray();
			MixinRetarget.Plan plan = NativeCallTestEvidence.plan(MixinFit.parse(bytes), resolver);
			ClassNode served = MixinFit.parse(MixinRetarget.rewritten(bytes, plan));
			served.methods.stream().filter(m -> MixinFit.injectorOf(m) == null && m.name.startsWith("processAndPlaceEntities"))
					.forEach(m -> asides.add(m.name));
		}
		assertEquals(2, asides.size(), asides.toString());
		assertTrue(!asides.get(0).equals(asides.get(1)), "one name for both bodies: " + asides);
		assertEquals(List.of(MixinHandlerShim.asideName(MIXIN, "processAndPlaceEntities", MixinRetarget.PROJECTED_SUFFIX),
				MixinHandlerShim.asideName("test/OtherEntityProcessorMixin", "processAndPlaceEntities", MixinRetarget.PROJECTED_SUFFIX)), asides);
	}

	/** The descriptor of the first {@code placeEntities} the merged class declares: the one a bare name binds. */
	private static String forgeShape(Function<String, byte[]> resolver) {
		ClassNode node = MixinFit.parse(resolver.apply(TEMPLATE + ".class"));
		return node.methods.stream().filter(m -> m.name.equals("placeEntities")).findFirst().orElseThrow().desc;
	}

	/** EntityProcessorMixin with its HEAD injector only. */
	private static byte[] headOnly() {
		ClassNode mixin = MixinFit.parse(moogsShaped());
		mixin.methods.removeIf(m -> !m.name.equals("processAndPlaceEntities"));
		ClassWriter writer = new ClassWriter(0);
		mixin.accept(writer);
		return writer.toByteArray();
	}

	/** A HEAD handler that captures something else than vanilla's arguments is not one the projection can serve. */
	@Test void aHandlerNotWrittenForVanillasArgumentsIsLeftAlone() throws Exception {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		Function<String,byte[]> resolver=merged();
		ClassNode mixin = MixinFit.parse(moogsShaped());
		MethodNode head = mixin.methods.stream().filter(m -> m.name.equals("processAndPlaceEntities")).findFirst().orElseThrow();
		head.desc = "(Lnet/minecraft/world/level/ServerLevelAccessor;" + CALLBACK + ")V";
		MixinRetarget.Plan plan = NativeCallTestEvidence.plan(mixin, resolver);
		assertTrue(plan.rewrites().stream().noneMatch(r -> r.element() == MixinRetarget.Element.PROJECT), plan.describe());
	}

	private static Type[] append(Type[] types, Type last) {
		Type[] out = java.util.Arrays.copyOf(types, types.length + 1);
		out[types.length] = last;
		return out;
	}

	/** EntityProcessorMixin's three injectors, as compiled against vanilla. */
	private static byte[] moogsShaped() {
		ClassNode mixin = new ClassNode();
		mixin.version = Opcodes.V21;
		mixin.access = Opcodes.ACC_PUBLIC;
		mixin.name = MIXIN;
		mixin.superName = "java/lang/Object";
		AnnotationNode type = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		type.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType(TEMPLATE)))));
		mixin.invisibleAnnotations = new ArrayList<>(List.of(type));
		String caller = row().caller();
		String around = "(" + caller.substring(caller.indexOf('(') + 1, caller.indexOf(')')) + CALLBACK_RETURNABLE + ")V";
		mixin.methods.add(handler("captureContext", around, "placeInWorld", point("INVOKE", row().vanillaMember(), null), false));
		mixin.methods.add(handler("clearContext", around, "placeInWorld", point("INVOKE", row().vanillaMember(), "AFTER"), false));
		String vanilla = row().vanilla().substring(row().vanilla().indexOf('('));
		mixin.methods.add(handler("processAndPlaceEntities", Type.getMethodDescriptor(Type.VOID_TYPE,
				append(Type.getArgumentTypes(vanilla), Type.getType(CALLBACK))), "placeEntities", point("HEAD", null, null), true));
		ClassWriter writer = new ClassWriter(0);
		mixin.accept(writer);
		return writer.toByteArray();
	}

	private static AnnotationNode point(String value, String target, String shift) {
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", value));
		if (target != null) at.values.addAll(List.of("target", target));
		if (shift != null) at.values.addAll(List.of("shift", new String[] {"Lorg/spongepowered/asm/mixin/injection/At$Shift;", shift}));
		return at;
	}

	private static MethodNode handler(String name, String desc, String selector, AnnotationNode at, boolean cancellable) {
		AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
		inject.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of(selector)), "at", new ArrayList<>(List.of(at))));
		if (cancellable) inject.values.addAll(List.of("cancellable", true));
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, name, desc, null, null);
		handler.visibleAnnotations = new ArrayList<>(List.of(inject));
		handler.instructions.add(new InsnNode(Opcodes.RETURN));
		handler.maxLocals = Type.getArgumentsAndReturnSizes(desc) >> 2;
		return handler;
	}
}
