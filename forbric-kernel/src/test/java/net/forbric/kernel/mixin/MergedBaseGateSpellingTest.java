/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * {@link MergedBaseMixinCompat}'s refusal gate recognises the registry protocols with the same reading of their points as
 * the adapters it guards ({@code FabricRegistryInitializationMixinAdapter.conflicts},
 * {@code FabricRegistryLoaderMixinAdapter.matches}), given the class the mod was compiled against: a point written without
 * its owner or descriptor, with a dotted owner or with whitespace — wherever the method it was written for decides it —
 * is recognised exactly as the full spelling is, so a mixin the adapter cannot serve is refused whatever its spelling,
 * and one it can serve is let through. A shortened point that also names another call there is not the protocol: the gate
 * leaves it to Mixin as written.
 *
 * <p>The mixins are fabric-registry-sync's released bootstrap and registry-loader mixins, under other names; the
 * bootstrap one is also cut down to what another mod might ship — the deferred freeze alone, without the tracker callback
 * the adapter needs — and the loader one is judged against vanilla's loader, where the call it wraps is live and its
 * propagation fits nothing. The family is read as at discovery, before the mixin's own family is noted.
 */
@ResourceLock("system-properties")
class MergedBaseGateSpellingTest {
	private static final Path REGISTRY_SYNC = Path.of("build/compat-inputs/player-loading/api/fabric-registry-sync-v0-7.1.1+c7bd5b8e9e.jar");
	private static final String BOOTSTRAP = "net/minecraft/server/Bootstrap";
	private static final String LOADER = "net/minecraft/resources/RegistryDataLoader";
	private static final String FREEZE = "Lnet/minecraft/core/registries/BuiltInRegistries;bootStrap()V";
	private static final String LOAD = "L" + LOADER + ";load(L" + LOADER + "$LoaderFactory;Ljava/util/List;Ljava/util/List;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;";
	private static final String DEFERS = "source callback repeats or defers the kernel-owned registry freeze; its tracker protocol could not be adapted";
	private static final String UNFIT = "registry-loader: source ScopedValue callback propagation does not fit the current registry-loader overloads";
	private static final BiFunction<Ecosystem, String, ClassNode> STAGED = NativeCallTestEvidence.staged();
	/** As at discovery: the adapters ask with the mixin's family, not noted yet, and the config's family answers. */
	private static final BiFunction<Ecosystem, String, ClassNode> NATIVES = (asked, owner) -> STAGED.apply(asked != null ? asked : Ecosystem.FABRIC, owner);

	@AfterEach void reset() {
		MixinStubRebind.forget();
	}

	// ---- the deferred registry freeze --------------------------------------------------------------------------------------

	/** The deferred freeze alone cannot be adapted (no tracker callback to keep): refused for every spelling of its point. */
	@TestFactory Stream<DynamicTest> aDeferredFreezeWrittenAnotherWayIsRefusedWhenItCannotBeAdapted() {
		return forms(FREEZE).map(form -> DynamicTest.dynamicTest(form.id(), () -> {
			ClassNode mixin = bootstrap();
			mixin.methods.removeIf(m -> MixinFit.injectorOf(m) != null && !atTargets(m).contains(FREEZE));
			assertEquals(1, respell(mixin, FREEZE, form, NATIVES), "premise: the freeze point is respelled");
			assertTrue(FabricRegistryInitializationMixinAdapter.conflicts(mixin, NATIVES), form.id());
			assertEquals(DEFERS, MergedBaseMixinCompat.refusal(mixin, merged(), NATIVES));
		}));
	}

	/** The whole released mixin can be adapted: let through for every spelling, though recognised. */
	@TestFactory Stream<DynamicTest> aDeferredFreezeWrittenAnotherWayIsLetThroughWhenItCanBeAdapted() {
		return forms(FREEZE).map(form -> DynamicTest.dynamicTest(form.id(), () -> {
			ClassNode mixin = bootstrap();
			assertEquals(1, respell(mixin, FREEZE, form, NATIVES), "premise");
			assertTrue(FabricRegistryInitializationMixinAdapter.conflicts(mixin, NATIVES), form.id());
			assertNull(MergedBaseMixinCompat.refusal(mixin, merged(), NATIVES));
		}));
	}

	/**
	 * Vanilla's {@code bootStrap()} calls a dozen other classes' {@code bootStrap()V}: without its owner the point names
	 * them all, and crowded with another {@code BuiltInRegistries.bootStrap} overload, without its descriptor it names two.
	 * Neither is the deferred freeze.
	 */
	@TestFactory Stream<DynamicTest> aShortFreezePointThatAlsoNamesAnotherCallIsNotTheProtocol() {
		List<DynamicTest> tests = new ArrayList<>();
		tests.add(DynamicTest.dynamicTest("no owner: vanilla's other bootStrap()V calls", () -> {
			ClassNode mixin = bootstrap();
			mixin.methods.removeIf(m -> MixinFit.injectorOf(m) != null && !atTargets(m).contains(FREEZE));
			MixinPlayerWorldCallbackAdapter.set(point(mixin, FREEZE), "target", "bootStrap()V");
			assertFalse(FabricRegistryInitializationMixinAdapter.conflicts(mixin, NATIVES));
			assertNull(MergedBaseMixinCompat.refusal(mixin, merged(), NATIVES));
		}));
		ClassNode crowded = PointRespelling.copy(STAGED.apply(Ecosystem.FABRIC, BOOTSTRAP));
		try {
			assertEquals(1, PointRespelling.crowd(bootstrap(), m -> atTargets(m).contains(FREEZE), crowded, false), "premise");
		} catch (Exception unreadable) {
			throw new AssertionError(unreadable);
		}
		BiFunction<Ecosystem, String, ClassNode> natives = (asked, owner) -> owner.equals(BOOTSTRAP) ? crowded : NATIVES.apply(asked, owner);
		for (PointRespelling.Form form : PointRespelling.READ_IN_THE_WRITTEN_METHOD) if (form.dropsDesc())
			tests.add(DynamicTest.dynamicTest(form.id() + ": another overload there", () -> {
				ClassNode mixin = bootstrap();
				mixin.methods.removeIf(m -> MixinFit.injectorOf(m) != null && !atTargets(m).contains(FREEZE));
				MixinPlayerWorldCallbackAdapter.set(point(mixin, FREEZE), "target", form.spell().apply(MixinFit.parseMember(FREEZE)));
				assertFalse(FabricRegistryInitializationMixinAdapter.conflicts(mixin, natives));
				assertNull(MergedBaseMixinCompat.refusal(mixin, merged(), natives));
			}));
		return tests.stream();
	}

	// ---- the registry-loader propagation -----------------------------------------------------------------------------------

	/** Against vanilla's loader the wrapped call is live and the propagation fits nothing: refused for every spelling. */
	@TestFactory Stream<DynamicTest> aLoaderWrapWrittenAnotherWayIsRefusedWhenItCannotBeAdapted() {
		return forms(LOAD).map(form -> DynamicTest.dynamicTest(form.id(), () -> {
			ClassNode mixin = loader();
			assertEquals(1, respell(mixin, LOAD, form, NATIVES), "premise");
			assertTrue(FabricRegistryLoaderMixinAdapter.matches(mixin, NATIVES), form.id());
			assertEquals(UNFIT, MergedBaseMixinCompat.refusal(mixin, vanilla(), NATIVES));
		}));
	}

	/** Against the merged loader it is adapted: let through for every spelling. */
	@TestFactory Stream<DynamicTest> aLoaderWrapWrittenAnotherWayIsLetThroughWhenItCanBeAdapted() {
		return forms(LOAD).map(form -> DynamicTest.dynamicTest(form.id(), () -> {
			ClassNode mixin = loader();
			assertEquals(1, respell(mixin, LOAD, form, NATIVES), "premise");
			assertTrue(FabricRegistryLoaderMixinAdapter.matches(mixin, NATIVES), form.id());
			assertNull(MergedBaseMixinCompat.refusal(mixin, merged(), NATIVES));
		}));
	}

	/** The entry the wrap was written for, crowded with another owner's or another overload's {@code load}: not the protocol. */
	@TestFactory Stream<DynamicTest> aShortLoaderPointThatAlsoNamesAnotherCallIsNotTheProtocol() throws Exception {
		List<DynamicTest> tests = new ArrayList<>();
		for (boolean otherOwner : new boolean[] { true, false }) {
			ClassNode crowded = PointRespelling.copy(STAGED.apply(Ecosystem.FABRIC, LOADER));
			assertEquals(1, PointRespelling.crowd(loader(), m -> atTargets(m).contains(LOAD), crowded, otherOwner), "premise");
			BiFunction<Ecosystem, String, ClassNode> natives = (asked, owner) -> owner.equals(LOADER) ? crowded : NATIVES.apply(asked, owner);
			for (PointRespelling.Form form : PointRespelling.READ_IN_THE_WRITTEN_METHOD) {
				if (otherOwner ? !form.dropsOwner() : !form.dropsDesc()) continue;
				tests.add(DynamicTest.dynamicTest((otherOwner ? "another owner / " : "another overload / ") + form.id(), () -> {
					ClassNode mixin = loader();
					MixinPlayerWorldCallbackAdapter.set(point(mixin, LOAD), "target", form.spell().apply(MixinFit.parseMember(LOAD)));
					assertFalse(FabricRegistryLoaderMixinAdapter.matches(mixin, natives));
					assertNull(MergedBaseMixinCompat.refusal(mixin, vanilla(), natives));
				}));
			}
		}
		return tests.stream();
	}

	// ---- fixtures ----------------------------------------------------------------------------------------------------------

	/**
	 * The full spelling and every other one Mixin reads as the same member — the shortened ones only where the method the
	 * handler was written for decides them (vanilla's {@code Bootstrap.bootStrap()} calls other owners' {@code bootStrap()V},
	 * so the freeze point has no ownerless spelling).
	 */
	private static Stream<PointRespelling.Form> forms(String member) {
		List<PointRespelling.Form> forms = new ArrayList<>();
		forms.add(new PointRespelling.Form("as released", m -> member, false, false));
		for (PointRespelling.Form form : PointRespelling.allForms()) {
			try {
				if (respell(member.equals(FREEZE) ? bootstrap() : loader(), member, form, NATIVES) == 1) forms.add(form);
			} catch (Exception unreadable) {
				throw new AssertionError(unreadable);
			}
		}
		assertTrue(forms.size() >= 5, "premise: the shortened spellings apply: " + forms.stream().map(PointRespelling.Form::id).toList());
		return forms.stream();
	}

	/** Respells the point naming {@code member}, a shortened form only where the method the handler binds natively decides it. */
	private static int respell(ClassNode mixin, String member, PointRespelling.Form form, BiFunction<Ecosystem, String, ClassNode> natives) {
		ClassNode nativeClass = natives.apply(Ecosystem.FABRIC, MixinFit.mixinTargets(mixin).getFirst());
		Predicate<MethodNode> which = m -> atTargets(m).contains(member);
		if (!form.dropsOwner() && !form.dropsDesc() && form.spell().apply(MixinFit.parseMember(member)).equals(member))
			return (int) mixin.methods.stream().filter(which).count();
		return PointRespelling.points(mixin, which, form, m -> PointRespelling.bound(m, nativeClass));
	}

	private static AnnotationNode point(ClassNode mixin, String member) {
		for (MethodNode m : mixin.methods) {
			AnnotationNode injector = MixinFit.injectorOf(m);
			if (injector != null) for (AnnotationNode at : MixinFit.atNodes(injector))
				if (member.equals(MixinFit.asString(MixinFit.value(at, "target")))) return at;
		}
		throw new AssertionError("no point names " + member);
	}

	private static List<String> atTargets(MethodNode handler) {
		List<String> out = new ArrayList<>();
		AnnotationNode injector = MixinFit.injectorOf(handler);
		if (injector != null) for (AnnotationNode at : MixinFit.atNodes(injector)) out.add(MixinFit.asString(MixinFit.value(at, "target")));
		return out;
	}

	private static ClassNode bootstrap() throws Exception {
		return MixinCallbackSelectorSpellingTest.unrelatedNames(registrySync("net/fabricmc/fabric/mixin/registry/sync/BootstrapMixin"));
	}

	private static ClassNode loader() throws Exception {
		return MixinCallbackSelectorSpellingTest.unrelatedNames(registrySync("net/fabricmc/fabric/mixin/registry/sync/RegistryDataLoaderMixin"));
	}

	private static ClassNode registrySync(String entry) throws Exception {
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(REGISTRY_SYNC), REGISTRY_SYNC + " absent");
		try (ZipFile zip = new ZipFile(REGISTRY_SYNC.toFile())) {
			return MixinFit.parse(zip.getInputStream(zip.getEntry(entry + ".class")).readAllBytes());
		}
	}

	/** The staged merged base's classes, as the gate's resources read them. */
	private static Function<String, byte[]> merged() {
		return jar(TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar"), Fixture.STAGED);
	}

	/** Vanilla's classes in place of the merged base: a game whose loader the propagation does not fit. */
	private static Function<String, byte[]> vanilla() {
		return jar(TestFixtures.vanillaJar(), Fixture.MC_LIBRARIES);
	}

	private static Function<String, byte[]> jar(Path jar, Fixture kind) {
		TestFixtures.require(kind, Files.isRegularFile(jar), jar + " absent");
		return name -> {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				var entry = zip.getEntry(name);
				return entry == null ? null : zip.getInputStream(entry).readAllBytes();
			} catch (Exception unreadable) {
				return null;
			}
		};
	}
}
