/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiFunction;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;

/**
 * {@link CarrierGuardedPoints} on unmodified upstream fabric-api: its elytra veto, written at vanilla's equipment read in
 * {@code canGlide()}, goes ahead of NeoForge's gliding-attribute answer in {@code canGlide(boolean)} (which
 * {@code canGlide()} enters with a constant); its flight tick, written at vanilla's glider-slot choice, goes ahead of
 * NeoForge's empty-slot guard. Handler names and bodies play no part; an injector that only observes stays where it binds.
 */
@ResourceLock("system-properties")
class CarrierGuardedPointsStagedTest {
	private static final String LIVING = "net/minecraft/world/entity/LivingEntity";

	@AfterEach void reset() {
		System.clearProperty(CarrierGuardedPoints.PROPERTY);
	}

	private static BiFunction<Ecosystem, String, ClassNode> natives() {
		Path jar = TestFixtures.mergedBase();
		TestFixtures.requireFile(TestFixtures.Fixture.STAGED, jar, "actual game required");
		NativeGameReferences references = new NativeGameReferences(path -> {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				var entry = zip.getEntry(path);
				return entry == null ? null : zip.getInputStream(entry).readAllBytes();
			} catch (Exception unreadable) {
				return null;
			}
		});
		return references::get;
	}

	private static ClassNode elytra() throws Exception {
		ClassNode mixin = StagedFabricMixinFixture.mixin("fabric-entity-events-v1", "net/fabricmc/fabric/mixin/entity/event/elytra/LivingEntityMixin");
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		return mixin;
	}

	private static int adapt(ClassNode mixin) throws Exception {
		ClassNode merged = StagedFabricMixinFixture.living(false);
		return CarrierGuardedPoints.adapt(mixin, owner -> owner.equals(LIVING) ? merged : null, natives());
	}

	@Test void theElytraVetoAndFlightTickGoAheadOfTheCarriersOwnBranches() throws Exception {
		ClassNode mixin = elytra();
		assertEquals(2, adapt(mixin));
		AnnotationNode check = MixinFit.injectorOf(StagedFabricMixinFixture.method(mixin, "injectElytraCheck"));
		assertEquals(List.of("canGlide(Z)Z"), MixinFit.stringList(MixinFit.value(check, "method")), "the overload canGlide() forwards to");
		assertEquals("Lnet/neoforged/neoforge/common/NeoForgeMod;GLIDING_FLIGHT:Lnet/minecraft/core/Holder;",
				MixinFit.value(StagedFabricMixinFixture.at(mixin, "injectElytraCheck"), "target"), "the carrier's first step after the movement checks");
		assertEquals(Boolean.TRUE, MixinFit.value(check, "cancellable"));
		assertEquals(1, MixinFit.value(check, "allow"), "the injector's own constraints stay");
		assertEquals("Ljava/util/List;isEmpty()Z", MixinFit.value(StagedFabricMixinFixture.at(mixin, "injectElytraTick"), "target"),
				"the carrier's guard of the slot list the native choice picks from");
		assertEquals(0, adapt(mixin), "a second pass changes nothing");
	}

	/** An injector that cannot stop the method only observes the point: where the carrier goes around it, it must not run. */
	@Test void anObserverIsLeftWhereItBinds() throws Exception {
		ClassNode mixin = elytra();
		for (var method : mixin.methods) {
			AnnotationNode injector = MixinFit.injectorOf(method);
			if (injector != null) for (int i = 0; i < injector.values.size(); i += 2) if ("cancellable".equals(injector.values.get(i))) injector.values.set(i + 1, Boolean.FALSE);
		}
		assertEquals(0, adapt(mixin));
	}

	@Test void onTheNativeBodyAndSwitchedOffNothingMoves() throws Exception {
		ClassNode vanilla = natives().apply(Ecosystem.FABRIC, LIVING);
		assertEquals(0, CarrierGuardedPoints.adapt(elytra(), owner -> vanilla, natives()));
		System.setProperty(CarrierGuardedPoints.PROPERTY, "off");
		assertEquals(0, adapt(elytra()));
	}

	/** The mod's handler names are nobody's key: the same mixin under other names moves the same. */
	@Test void theMoveKnowsNoName() throws Exception {
		ClassNode mixin = elytra();
		mixin.name = "org/example/GliderGate";
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		StagedFabricMixinFixture.method(mixin, "injectElytraCheck").name = "gate$allow";
		StagedFabricMixinFixture.method(mixin, "injectElytraTick").name = "gate$tick";
		assertEquals(2, adapt(mixin));
	}
}
