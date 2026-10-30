/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * {@link ThinnedCallOrdinals} reads the method a point counts in, and the call it counts, as Mixin binds the selector and
 * resolves the target. ViaFabricPlus' 1.12.2 placement hook (ordinal 2 of {@code ItemStack.isEmpty()} in
 * {@code performUseItemOn}, which the merged body makes once) under other class and handler names, its selector written
 * with a dotted owner, an owner prefix, whitespace or two spellings, its target with whitespace, a dotted owner, and —
 * where the method as the mod was compiled against it decides it — without its owner or descriptor: each is recounted to
 * the merged body's 0 exactly as the released spelling is. A shortened target that also counts another owner's or
 * overload's call (natively or in the merged body), a target naming another owner, and a selector binding another method
 * or two keep the ordinal the mod wrote.
 */
@ResourceLock("system-properties")
class ThinnedCallOrdinalsSpellingTest {
	private static final Path MERGED = TestFixtures.stagedBase();
	private static final Path VIAFABRICPLUS = Path.of("../build/lb-vfp/jars/ViaFabricPlus-5.0.2.jar");
	private static final String OWNER = "net/minecraft/client/multiplayer/MultiPlayerGameMode";
	private static final String CALL = "Lnet/minecraft/world/item/ItemStack;isEmpty()Z";

	@AfterEach void reset() {
		MixinStubRebind.forget();
	}

	private record Hook(ClassNode mixin, MethodNode handler, ClassNode merged, ClassNode natives) {
		int adapt() {
			return ThinnedCallOrdinals.adapt(mixin, name -> name.equals(OWNER) ? merged : null, (family, owner) -> owner.equals(OWNER) ? natives : null);
		}
		Object ordinal() {
			return MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(handler)).getFirst(), "ordinal");
		}
		boolean which(MethodNode method) { return method == handler; }
	}

	@Test void theReleasedHookUnderOtherNamesIsRecounted() throws Exception {
		assertRecounted(hook());
	}

	@TestFactory Stream<DynamicTest> theSelectorWrittenAnotherWayIsRecounted() {
		Map<String, BiFunction<String, String, List<String>>> forms = new LinkedHashMap<>(PointRespelling.SELECTORS);
		forms.put("owner prefix with whitespace", (owner, s) -> List.of(" L" + owner + "; " + s + " "));
		return forms.entrySet().stream().map(form -> DynamicTest.dynamicTest(form.getKey(), () -> {
			Hook hook = hook();
			assertEquals(1, PointRespelling.selectors(hook.mixin(), hook::which, form.getValue()), "premise");
			assertRecounted(hook);
		}));
	}

	@TestFactory Stream<DynamicTest> theTargetWrittenAnotherWayIsRecounted() {
		return PointRespelling.allForms().stream().map(form -> DynamicTest.dynamicTest(form.id(), () -> {
			Hook hook = hook();
			assertEquals(1, PointRespelling.points(hook.mixin(), hook::which, form, m -> PointRespelling.bound(m, hook.natives())), "premise: the target is respelled");
			assertRecounted(hook);
		}));
	}

	@Test void selectorAndTargetBothWrittenAnotherWayAreRecounted() throws Exception {
		Hook hook = hook();
		select(hook.handler(), List.of("net.minecraft.client.multiplayer.MultiPlayerGameMode.performUseItemOn"));
		MixinPlayerWorldCallbackAdapter.set(MixinFit.atNodes(MixinFit.injectorOf(hook.handler())).getFirst(), "target", " net.minecraft.world.item.ItemStack . isEmpty ()Z");
		assertRecounted(hook);
	}

	// ---- look-alikes ------------------------------------------------------------------------------------------------------

	/**
	 * The native body crowded with another owner's {@code isEmpty()Z} or another {@code isEmpty} overload: the released
	 * spelling still counts one method and is recounted; a target without that owner or descriptor counts both, which
	 * the merged body's count does not describe.
	 */
	@TestFactory Stream<DynamicTest> aShortTargetThatAlsoCountsAnotherCallNativelyKeepsItsOrdinal() {
		List<DynamicTest> tests = new ArrayList<>();
		for (boolean otherOwner : new boolean[] { true, false }) {
			String crowd = otherOwner ? "another owner" : "another overload";
			tests.add(DynamicTest.dynamicTest(crowd + " / released spelling still recounted", () -> {
				Hook hook = crowded(hook(), otherOwner, true);
				assertRecounted(hook);
			}));
			for (PointRespelling.Form form : PointRespelling.READ_IN_THE_WRITTEN_METHOD) {
				if (otherOwner ? !form.dropsOwner() : !form.dropsDesc()) continue;
				tests.add(DynamicTest.dynamicTest(crowd + " / " + form.id(), () -> {
					Hook plain = hook(), hook = crowded(plain, otherOwner, true);
					// Shortened where the uncrowded method decides it: what the mod could have written against vanilla.
					assertEquals(1, PointRespelling.points(hook.mixin(), hook::which, form, m -> PointRespelling.bound(m, plain.natives())), "premise");
					assertKept(hook);
				}));
			}
		}
		return tests.stream();
	}

	/** The same in the merged body alone: there Mixin would count the other call too, so the recount does not hold. */
	@Test void aShortTargetThatAlsoCountsAnotherCallInTheMergedBodyKeepsItsOrdinal() throws Exception {
		Hook hook = crowded(hook(), true, false);
		assertEquals(1, PointRespelling.points(hook.mixin(), hook::which, PointRespelling.READ_IN_THE_WRITTEN_METHOD.getFirst(),
				m -> PointRespelling.bound(m, hook.natives())), "premise: no owner");
		assertKept(hook);
	}

	@Test void aTargetNamingAnotherOwnerKeepsItsOrdinal() throws Exception {
		for (String target : List.of("Lnet/minecraft/world/item/ItemInstance;isEmpty()Z", "org.example.elsewhere.Stack.isEmpty()Z")) {
			Hook hook = hook();
			MixinPlayerWorldCallbackAdapter.set(MixinFit.atNodes(MixinFit.injectorOf(hook.handler())).getFirst(), "target", target);
			assertKept(hook);
		}
	}

	@Test void aSelectorBindingAnotherMethodOrTwoKeepsItsOrdinal() throws Exception {
		for (List<String> selectors : List.of(List.of("net.minecraft.client.multiplayer.MultiPlayerGameMode.useItemOn"),
				List.of("performUseItemOn", "useItemOn"), List.of("Lnet/minecraft/client/Minecraft;performUseItemOn"))) {
			Hook hook = hook();
			select(hook.handler(), selectors);
			assertKept(hook);
		}
	}

	// ---- reading ----------------------------------------------------------------------------------------------------------

	private static void assertRecounted(Hook hook) {
		assertEquals(2, hook.ordinal(), "premise: vanilla's count");
		assertEquals(1, hook.adapt());
		assertEquals(0, hook.ordinal(), "the merged body's one isEmpty() is vanilla's third");
		assertEquals(0, hook.adapt(), "counted once");
	}

	private static void assertKept(Hook hook) {
		byte[] before = CarpetMixinAdapterTest.bytes(hook.mixin());
		assertEquals(0, hook.adapt());
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(hook.mixin()), "the mixin was rewritten");
	}

	/**
	 * {@code hook} with its native ({@code nativeSide}) or merged method crowded by {@link PointRespelling#crowd}, which reads
	 * the point as released: crowd before respelling it.
	 */
	private static Hook crowded(Hook hook, boolean otherOwner, boolean nativeSide) {
		ClassNode copy = PointRespelling.copy(nativeSide ? hook.natives() : hook.merged());
		assertEquals(1, PointRespelling.crowd(hook.mixin(), hook::which, copy, otherOwner), "premise: crowded");
		return nativeSide ? new Hook(hook.mixin(), hook.handler(), hook.merged(), copy) : new Hook(hook.mixin(), hook.handler(), copy, hook.natives());
	}

	private static void select(MethodNode handler, List<String> selectors) {
		MixinPlayerWorldCallbackAdapter.set(MixinFit.injectorOf(handler), "method", new ArrayList<>(selectors));
	}

	/** ViaFabricPlus' hook under other names, the merged class, and the class as the mod was compiled against it. */
	private static Hook hook() throws Exception {
		TestFixtures.requireFile(Fixture.STAGED, MERGED, "actual game required");
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(VIAFABRICPLUS), VIAFABRICPLUS + " absent");
		ClassNode released, merged;
		try (ZipFile zip = new ZipFile(VIAFABRICPLUS.toFile())) {
			released = MixinFit.parse(zip.getInputStream(zip.getEntry("com/viaversion/viafabricplus/injection/mixin/features/v1_12_2/MixinMultiPlayerGameMode.class")).readAllBytes());
		}
		try (ZipFile zip = new ZipFile(MERGED.toFile())) {
			merged = MixinFit.parse(zip.getInputStream(zip.getEntry(OWNER + ".class")).readAllBytes());
		}
		ClassNode natives = NativeCallTestEvidence.staged().apply(Ecosystem.FABRIC, OWNER);
		TestFixtures.require(Fixture.STAGED, natives != null, "the class as the mod was compiled against it");
		ClassNode mixin = MixinCallbackSelectorSpellingTest.unrelatedNames(released);
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		MethodNode handler = null;
		for (MethodNode method : mixin.methods) {
			AnnotationNode injector = MixinFit.injectorOf(method);
			if (injector != null && MixinFit.atNodes(injector).stream().anyMatch(at -> CALL.equals(MixinFit.value(at, "target")))) {
				assertNull(handler, "one hook at isEmpty()");
				handler = method;
			}
		}
		assertNotNull(handler, "the placement hook");
		assertTrue(handler.name.startsWith("respelled"), "renamed");
		return new Hook(mixin, handler, merged, natives);
	}
}
