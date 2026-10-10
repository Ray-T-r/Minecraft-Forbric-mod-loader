/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.transform.FabricFreezePointInjector;

/**
 * {@link FabricFreezeHookMixinAdapter} reads a freeze observer's selectors and its {@code bootStrap()} point as Mixin reads
 * them, against the staged merged {@code BuiltInRegistries} (hooked, and without code, as the mixin service hands it over)
 * and the vanilla one a Fabric mod is compiled against: Create Fly's released mixin under other names with its selectors
 * written every other way Mixin binds to {@code freeze()}, and an observer of {@code bootStrap()}'s call of
 * {@code freeze()} — not LiquidBounce's: another mod's names, one handler, a CallbackInfo-only body that keeps a value in
 * a local — whose point drops its owner, its descriptor or both, dots its owner or carries whitespace. Each moves exactly
 * as the released spelling does. A point that also names another call there (another owner's {@code freeze}, another
 * overload, a second call of it), names another owner's member, or is read with no class that has its code, and a
 * selector binding another method or a second one, stay exactly as written.
 */
@ResourceLock("system-properties")
class FabricFreezeHookSpellingTest {
	private static final String BIR = FabricFreezeHookMixinAdapter.BUILT_IN_REGISTRIES;
	private static final String HEAD = FabricFreezePointInjector.HEAD_HOOK + FabricFreezePointInjector.HOOK_DESC;
	private static final String TAIL = FabricFreezePointInjector.TAIL_HOOK + FabricFreezePointInjector.HOOK_DESC;
	private static final String CREATE_MIXIN = "com/zurrtum/create/mixin/BuiltInRegistriesMixin";
	private static final String MIXIN = "org/example/catalog/mixin/RegistryObserverMixin";
	private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	private static final String CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
	private static final String FREEZE_CALL = "L" + BIR + ";freeze()V";
	private static final BiFunction<Ecosystem, String, ClassNode> STAGED = NativeCallTestEvidence.staged();

	private Supplier<Boolean> registrySync;

	@BeforeEach void registrySyncIsInTheGame() {
		registrySync = FabricFreezeHookMixinAdapter.registrySyncPresent;
		FabricFreezeHookMixinAdapter.registrySyncPresent = () -> true;
	}

	@AfterEach void reset() {
		FabricFreezeHookMixinAdapter.registrySyncPresent = registrySync;
		MixinStubRebind.forget();
	}

	// ---- Create Fly, renamed, its selectors written every other way -----------------------------------------------------

	/** Every selector form Mixin binds to {@code freeze()} in the merged class, on the released mixin under other names. */
	@TestFactory Stream<DynamicTest> createFlysObserversWithTheirSelectorsWrittenAnotherWayMoveToTheirHooks() {
		Map<String, Function<String, List<String>>> forms = new LinkedHashMap<>();
		for (var form : PointRespelling.SELECTORS.entrySet()) forms.put(form.getKey(), selector -> form.getValue().apply(BIR, selector));
		forms.put("bare name", selector -> List.of("freeze"));
		forms.put("owner prefix, bare name", selector -> List.of("L" + BIR + ";freeze"));
		forms.put("dotted owner with whitespace", selector -> List.of(" net.minecraft.core.registries.BuiltInRegistries . freeze ( ) V"));
		return forms.entrySet().stream().map(form -> DynamicTest.dynamicTest(form.getKey(), () -> {
			ClassNode mixin = create();
			int changed = 0;
			for (MethodNode handler : mixin.methods) {
				AnnotationNode injector = MixinFit.injectorOf(handler);
				if (injector == null) continue;
				List<String> out = new ArrayList<>();
				for (String selector : MixinFit.stringList(MixinFit.value(injector, "method"))) out.addAll(form.getValue().apply(selector));
				MixinPlayerWorldCallbackAdapter.set(injector, "method", out);
				changed++;
			}
			assertEquals(2, changed, "premise: both observers are respelled");
			assertEquals(2, FabricFreezeHookMixinAdapter.adapt(mixin, merged(), STAGED));
			assertEquals(Map.of("HEAD", HEAD, "TAIL", TAIL), hookByPoint(mixin));
			CarpetMixinAdapterTest.verify(mixin);
			assertEquals(0, FabricFreezeHookMixinAdapter.adapt(mixin, merged(), STAGED), "idempotence");
		}));
	}

	/** Released spelling, renamed: the baseline every form above is measured against. */
	@Test void createFlyUnderOtherNamesMovesAsReleased() throws Exception {
		ClassNode mixin = create();
		assertEquals(2, FabricFreezeHookMixinAdapter.adapt(mixin, merged(), STAGED));
		assertEquals(Map.of("HEAD", HEAD, "TAIL", TAIL), hookByPoint(mixin));
	}

	// ---- an observer of bootStrap()'s call of freeze(), its point written every other way ----------------------------

	/** Every point form that names only {@code BuiltInRegistries.freeze()} in vanilla's {@code bootStrap()}, before and after the call. */
	@TestFactory Stream<DynamicTest> anObserverOfTheCallWrittenAnotherWayMovesToTheEdgeOfItsHook() {
		Map<String, String> points = points();
		List<DynamicTest> tests = new ArrayList<>();
		for (var point : points.entrySet()) for (String shift : new String[] { null, "BEFORE", "AFTER" }) {
			for (List<String> selectors : List.of(List.of("bootStrap"), List.of("net.minecraft.core.registries.BuiltInRegistries.bootStrap()V"),
					List.of("bootStrap()V", " L" + BIR + "; bootStrap"))) {
				tests.add(DynamicTest.dynamicTest(point.getKey() + " / shift " + shift + " / " + selectors, () -> {
					ClassNode mixin = observer(selectors, point.getValue(), shift);
					assertEquals(1, FabricFreezeHookMixinAdapter.adapt(mixin, merged(), STAGED));
					MethodNode handler = handler(mixin);
					assertEquals(List.of("AFTER".equals(shift) ? TAIL : HEAD), MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(handler), "method")));
					AnnotationNode at = MixinFit.atNodes(MixinFit.injectorOf(handler)).getFirst();
					assertEquals(List.of("value", "AFTER".equals(shift) ? "TAIL" : "HEAD"), at.values, "the edge of the hook the call stood for");
					CarpetMixinAdapterTest.verify(mixin);
					assertEquals(0, FabricFreezeHookMixinAdapter.adapt(mixin, merged(), STAGED), "idempotence");
				}));
			}
		}
		return tests.stream();
	}

	/**
	 * Without the class the mod was compiled against, the point is read in the merged {@code bootStrap()} it binds, where
	 * Mixin injects — when that class was read with its code. Read without it, only the full spelling names the call.
	 */
	@TestFactory Stream<DynamicTest> withoutTheNativeClassThePointIsReadWhereMixinInjects() {
		return points().entrySet().stream().map(point -> DynamicTest.dynamicTest(point.getKey(), () -> {
			assertEquals(1, FabricFreezeHookMixinAdapter.adapt(observer(List.of("bootStrap"), point.getValue(), null), mergedWithCode(), null));
			ClassNode mixin = observer(List.of("bootStrap"), point.getValue(), null);
			MixinFit.Member member = MixinFit.parseMember(point.getValue());
			if (member.owner() != null && member.desc() != null) assertEquals(1, FabricFreezeHookMixinAdapter.adapt(mixin, merged(), null));
			else assertStays(mixin, merged(), null);
		}));
	}

	// ---- look-alikes that are another callback ---------------------------------------------------------------------------

	/** Vanilla's {@code bootStrap()} with another owner's {@code freeze()V} or another {@code freeze} overload called in it too. */
	@TestFactory Stream<DynamicTest> aShortPointThatAlsoNamesAnotherCallThereStays() {
		List<DynamicTest> tests = new ArrayList<>();
		for (boolean otherOwner : new boolean[] { true, false }) {
			ClassNode crowded = PointRespelling.copy(STAGED.apply(Ecosystem.FABRIC, BIR));
			assertTrue(PointRespelling.crowd(observer(List.of("bootStrap"), FREEZE_CALL, null), PointRespelling::any, crowded, otherOwner) > 0, "premise");
			BiFunction<Ecosystem, String, ClassNode> natives = (family, owner) -> owner.equals(BIR) ? crowded : STAGED.apply(family, owner);
			// The full spelling still names only BuiltInRegistries.freeze(): the crowd is another member, not a second call of it.
			tests.add(DynamicTest.dynamicTest((otherOwner ? "another owner" : "another overload") + " / full spelling still moves",
					() -> assertEquals(1, FabricFreezeHookMixinAdapter.adapt(observer(List.of("bootStrap"), FREEZE_CALL, null), merged(), natives))));
			for (var point : points().entrySet()) {
				MixinFit.Member member = MixinFit.parseMember(point.getValue());
				boolean reaches = otherOwner ? member.owner() == null : member.desc() == null;
				if (!reaches) continue;
				tests.add(DynamicTest.dynamicTest((otherOwner ? "another owner" : "another overload") + " / " + point.getKey(),
						() -> assertStays(observer(List.of("bootStrap"), point.getValue(), null), merged(), natives)));
			}
		}
		return tests.stream();
	}

	/** A {@code bootStrap()} that calls {@code freeze()} twice: one edge of the hook cannot stand for two calls. */
	@Test void aPointSelectingTwoCallsOfFreezeStays() {
		ClassNode twice = PointRespelling.copy(STAGED.apply(Ecosystem.FABRIC, BIR));
		MethodNode bootStrap = twice.methods.stream().filter(m -> m.name.equals("bootStrap") && m.desc.equals("()V")).findFirst().orElseThrow();
		bootStrap.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, BIR, "freeze", "()V", false));
		BiFunction<Ecosystem, String, ClassNode> natives = (family, owner) -> owner.equals(BIR) ? twice : STAGED.apply(family, owner);
		for (String point : points().values()) assertStays(observer(List.of("bootStrap"), point, null), merged(), natives);
	}

	@Test void aPointNamingAnotherOwnersFreezeStays() {
		for (String point : List.of("Lorg/example/elsewhere/Ledger;freeze()V", "org.example.elsewhere.Ledger.freeze", "Lnet/minecraft/core/Registry;freeze()V"))
			assertStays(observer(List.of("bootStrap"), point, null), merged(), STAGED);
	}

	/** A selector that binds another method of {@code BuiltInRegistries}, or {@code freeze()} and a second one, however spelled. */
	@Test void aSelectorBindingAnotherOrASecondMethodStays() {
		for (List<String> selectors : List.of(List.of("net.minecraft.core.registries.BuiltInRegistries.createContents"), List.of("validate"),
				List.of(" L" + BIR + "; freeze", "bootStrap()V"), List.of("freeze*", "bootStrap"))) {
			ClassNode edge = observer(selectors, null, null);
			MixinPlayerWorldCallbackAdapter.set(MixinFit.atNodes(MixinFit.injectorOf(handler(edge))).getFirst(), "value", "TAIL");
			assertStays(edge, merged(), STAGED);
			assertStays(observer(selectors, FREEZE_CALL, null), merged(), STAGED);
		}
	}

	// ---- fixtures --------------------------------------------------------------------------------------------------------

	/** Point spellings Mixin reads as {@code BuiltInRegistries.freeze()V} wherever it is the one {@code freeze} called. */
	private static Map<String, String> points() {
		Map<String, String> points = new LinkedHashMap<>();
		points.put("full", FREEZE_CALL);
		points.put("whitespace", "L" + BIR + "; freeze ()V");
		points.put("dotted owner", "net.minecraft.core.registries.BuiltInRegistries.freeze()V");
		points.put("no owner", "freeze()V");
		points.put("no descriptor", "L" + BIR + ";freeze");
		points.put("dotted owner, no descriptor", " net.minecraft.core.registries.BuiltInRegistries . freeze");
		points.put("bare name", "freeze");
		return points;
	}

	private static ClassNode create() throws Exception {
		ClassNode mixin = MixinCallbackSelectorSpellingTest.unrelatedNames(CreateGuestMixinFixture.mixin(CREATE_MIXIN));
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		return mixin;
	}

	/**
	 * One static {@code (CallbackInfo)V} observer, {@code @Inject} at {@code INVOKE} {@code point} (HEAD where null) with
	 * an optional shift. Its body keeps the callback in a local and asks it nothing.
	 */
	private static ClassNode observer(List<String> selectors, String point, String shift) {
		ClassNode mixin = new ClassNode();
		mixin.version = Opcodes.V21;
		mixin.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT;
		mixin.name = MIXIN;
		mixin.superName = "java/lang/Object";
		AnnotationNode target = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		target.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType(BIR)))));
		mixin.invisibleAnnotations = new ArrayList<>(List.of(target));
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "catalogueTabs", "(" + CI + ")V", null, null);
		handler.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
		handler.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ASTORE, 1));
		handler.instructions.add(new InsnNode(Opcodes.RETURN));
		handler.maxStack = 1;
		handler.maxLocals = 2;
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(point == null ? List.of("value", "HEAD") : List.of("value", "INVOKE", "target", point));
		if (shift != null) at.values.addAll(List.of("shift", new String[] { "Lorg/spongepowered/asm/mixin/injection/At$Shift;", shift }));
		AnnotationNode inject = new AnnotationNode(INJECT);
		inject.values = new ArrayList<>(List.of("method", new ArrayList<>(selectors), "at", new ArrayList<>(List.of(at))));
		handler.visibleAnnotations = new ArrayList<>(List.of(inject));
		mixin.methods.add(handler);
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		return mixin;
	}

	private static MethodNode handler(ClassNode mixin) {
		return mixin.methods.stream().filter(m -> MixinFit.injectorOf(m) != null).findFirst().orElseThrow();
	}

	/** Each moved observer's original point value ({@code HEAD}/{@code TAIL}) to the hook its selector now names. */
	private static Map<String, String> hookByPoint(ClassNode mixin) {
		Map<String, String> out = new java.util.TreeMap<>();
		for (MethodNode handler : mixin.methods) {
			AnnotationNode injector = MixinFit.injectorOf(handler);
			if (injector == null) continue;
			List<String> methods = MixinFit.stringList(MixinFit.value(injector, "method"));
			assertEquals(1, methods.size(), methods.toString());
			out.put(String.valueOf(MixinFit.value(MixinFit.atNodes(injector).getFirst(), "value")), methods.getFirst());
		}
		return out;
	}

	private static void assertStays(ClassNode mixin, Function<String, ClassNode> targets, BiFunction<Ecosystem, String, ClassNode> natives) {
		byte[] before = CarpetMixinAdapterTest.bytes(mixin);
		Set<String> rows = new TreeSet<>(FabricFreezeHookMixinAdapter.moved());
		assertEquals(0, FabricFreezeHookMixinAdapter.adapt(mixin, targets, natives), mixin.name);
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(mixin), "the mixin was rewritten");
		assertEquals(rows, new TreeSet<>(FabricFreezeHookMixinAdapter.moved()), "no move recorded");
	}

	/** The staged merged {@code BuiltInRegistries} with the kernel's hooks, without code: what the mixin service hands the adapter. */
	private static Function<String, ClassNode> merged() {
		return read(ClassReader.SKIP_CODE);
	}

	private static Function<String, ClassNode> mergedWithCode() {
		return read(0);
	}

	private static Function<String, ClassNode> read(int flags) {
		byte[] plain = CarpetMixinAdapterTest.bytes(AdapterPointSpellingTest.merged(BIR));
		byte[] hooked = new FabricFreezePointInjector().transform(FabricFreezePointInjector.TARGET, plain, null);
		ClassNode node = new ClassNode();
		new ClassReader(hooked).accept(node, flags);
		return name -> name.equals(BIR) ? node : null;
	}
}
