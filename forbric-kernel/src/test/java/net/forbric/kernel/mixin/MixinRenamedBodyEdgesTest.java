/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import net.forbric.api.Ecosystem;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.*;

/**
 * fabric-lifecycle's ServerEntityEvents.ALLOW_LOAD is a cancellable HEAD of vanilla's
 * {@code PersistentEntitySectionManager.addEntity}. NeoForge renamed that whole body to {@code addEntityWithoutEvent},
 * left {@code addEntity} as the event-posting dispatcher, and adds every player through a new path that calls the
 * renamed body directly: the HEAD never saw a player. Moving the HEAD onto the renamed body saw every run, but after the
 * dispatcher's join event — every non-player entity was offered to NeoForge's join listeners before Fabric's veto. So
 * the HEAD stays on the dispatcher, and the forwarder that bypasses it ({@code addNewEntityWithoutEvent}) gets a copy.
 * A method whose renamed body only it reaches keeps its edges as they are.
 */
class MixinRenamedBodyEdgesTest {
	private static final String MANAGER = "net/minecraft/world/level/entity/PersistentEntitySectionManager";

	@Test void allowLoadStaysOnTheDispatcherAndTheBypassingForwarderGetsACopy() throws Exception {
		ClassNode merged = StagedFabricMixinFixture.game(MANAGER, false);
		MethodNode dispatcher = method(merged, "addEntity"), renamed = method(merged, "addEntityWithoutEvent"),
				forwarder = method(merged, "addNewEntityWithoutEvent");
		assertTrue(MixinRenamedBodyEdges.runsWithoutPassingThrough(merged, renamed, dispatcher), "addNewEntityWithoutEvent calls the renamed body");
		assertNotNull(MixinRenamedBodyEdges.forwarder(merged, forwarder, renamed), "it only forwards: its argument and false");
		assertNull(MixinRenamedBodyEdges.forwarder(merged, dispatcher, renamed), "the dispatcher posts an event first");
		ClassNode mixin = lifecycle();
		assertEquals(1, MixinRenamedBodyEdges.adapt(mixin, name -> name.equals(MANAGER) ? merged : null));
		List<MethodNode> injectors = mixin.methods.stream().filter(m -> MixinFit.injectorOf(m) != null).toList();
		assertEquals(2, injectors.size(), "the guest's own and the forwarder's copy");
		assertEquals(List.of("addEntity"), MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(method(mixin, "beforeAddEntity")), "method")),
				"the veto stays in front of the join event");
		MethodNode copy = injectors.stream().filter(m -> !m.name.equals("beforeAddEntity")).findFirst().orElseThrow();
		assertEquals(List.of(forwarder.name + forwarder.desc), MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(copy), "method")));
		assertEquals("(Lnet/minecraft/world/level/entity/EntityAccess;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V", copy.desc);
		assertEquals(Boolean.TRUE, MixinFit.value(MixinFit.injectorOf(copy), "cancellable"));
	}

	@Test void onVanillaOrWithTheSwitchOffNothingMoves() throws Exception {
		ClassNode vanilla = StagedFabricMixinFixture.game(MANAGER, true);
		assertEquals(0, MixinRenamedBodyEdges.adapt(lifecycle(), name -> name.equals(MANAGER) ? vanilla : null), "no rename on vanilla");
		ClassNode merged = StagedFabricMixinFixture.game(MANAGER, false);
		String old = System.setProperty(MixinRenamedBodyEdges.PROPERTY, "off");
		try {
			assertEquals(0, MixinRenamedBodyEdges.adapt(lifecycle(), name -> merged));
		} finally {
			if (old == null) System.clearProperty(MixinRenamedBodyEdges.PROPERTY); else System.setProperty(MixinRenamedBodyEdges.PROPERTY, old);
		}
	}

	@Test void aRenamedBodyOnlyTheNamedMethodReachesKeepsTheEdgeWhereItWas() throws Exception {
		ClassNode merged = StagedFabricMixinFixture.game(MANAGER, false);
		MethodNode dispatcher = method(merged, "addEntity"), renamed = method(merged, "addEntityWithoutEvent");
		// Every other caller gone: each run of the body starts at the dispatcher, and an edge there already sees all of them.
		for (MethodNode m : merged.methods) {
			if (m == dispatcher || m == renamed) continue;
			for (AbstractInsnNode i : m.instructions.toArray()) {
				if (i instanceof MethodInsnNode call && call.name.equals(renamed.name) && call.desc.equals(renamed.desc)) call.name = "somethingElse";
			}
		}
		assertFalse(MixinRenamedBodyEdges.runsWithoutPassingThrough(merged, renamed, dispatcher));
		assertEquals(0, MixinRenamedBodyEdges.adapt(lifecycle(), name -> merged));
	}

	/**
	 * NeoForge's ServerConfigurationPacketListenerImpl.startConfiguration has vanilla's body renamed to runConfiguration,
	 * which NeoForge's reply handler calls later: startConfiguration never calls it. fabric-networking's cancellable HEAD
	 * gates the start of configuration (and calls startConfiguration again once the client is ready); it stays there.
	 */
	@Test void aMethodThatLeavesTheBodyToALaterContinuationKeepsItsEdge() throws Exception {
		String listener = "net/minecraft/server/network/ServerConfigurationPacketListenerImpl";
		ClassNode merged = StagedFabricMixinFixture.game(listener, false);
		MethodNode start = method(merged, "startConfiguration"), run = method(merged, "runConfiguration");
		assertFalse(MixinRenamedBodyEdges.forwardsTo(merged, start, run));
		ClassNode mixin = StagedFabricMixinFixture.mixin("fabric-networking-api-v1", "net/fabricmc/fabric/mixin/networking/ServerConfigurationPacketListenerImplMixin");
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		assertEquals(0, MixinRenamedBodyEdges.adapt(mixin, name -> name.equals(listener) ? merged : null));
	}

	private static ClassNode lifecycle() throws Exception {
		ClassNode mixin = StagedFabricMixinFixture.mixin("fabric-lifecycle-events-v1", "net/fabricmc/fabric/mixin/event/lifecycle/PersistentEntitySectionManagerMixin");
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		return mixin;
	}

	private static MethodNode method(ClassNode owner, String name) {
		return owner.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError(name));
	}
}
