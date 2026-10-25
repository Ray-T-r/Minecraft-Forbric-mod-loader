/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import net.forbric.kernel.mixin.NativeTagSourceFacadeInjector;
import net.forbric.kernel.mixin.NativeTagTrackerSourceInjector;
import net.forbric.kernel.transform.EmptyFluidTypeTrackerInjector;
import net.forbric.kernel.transform.ForgeEntityFluidsInjector;
import net.forbric.kernel.transform.ForgeFluidViewExtensionsInjector;
import net.forbric.kernel.transform.ForgeFluidWaterRulesInjector;
import net.forbric.kernel.transform.OwnFluidTypeClausesInjector;
import net.forbric.kernel.transform.TransformPhase;
import org.junit.jupiter.api.Test;

/**
 * The edits that make a MinecraftForge mod's fluid answer as on native MinecraftForge run in the game, read from
 * KernelBoot's own registrations. Their unit tests drive each edit directly, so without this an edit left out of the
 * chain stayed green in every one of them (the in-game probe was the only witness). MinecraftForge's entity-fluid state
 * is registered before the native tag-tracker source, which proves the constructor and update it copies from as they are
 * after the state's edit.
 */
class ForgeFluidParityRegistrationTest {
	@Test void theFluidParityEditsAreRegisteredInTheCoremodPhase() throws Exception {
		for (Class<?> edit : List.of(ForgeEntityFluidsInjector.class, ForgeFluidViewExtensionsInjector.class, ForgeFluidWaterRulesInjector.class)) {
			assertEquals(TransformPhase.COREMOD, KernelBootChain.registration(edit.getName()).phase(), edit.getSimpleName());
		}
	}

	@Test void minecraftForgesEntityFluidStateIsEditedBeforeTheNativeTagTrackerSource() throws Exception {
		KernelBootChain.Registration state = KernelBootChain.registration(ForgeEntityFluidsInjector.class.getName());
		KernelBootChain.Registration source = KernelBootChain.registration(NativeTagTrackerSourceInjector.class.getName());
		assertEquals(source.phase(), state.phase());
		assertEquals(source.sortIndex(), state.sortIndex());
		assertTrue(state.position() < source.position(), state.describe() + " must come before " + source.describe());
	}

	/**
	 * The two edits that keep a type the kernel derived for a fluid from claiming what its family never claimed run in the
	 * game too, after the native tag-tracker source and its facade, which prove the walk as they find it.
	 */
	@Test void theForeignFluidTypeClaimEditsAreRegisteredAfterTheNativeTagSource() throws Exception {
		KernelBootChain.Registration facade = KernelBootChain.registration(NativeTagSourceFacadeInjector.class.getName());
		for (Class<?> edit : List.of(OwnFluidTypeClausesInjector.class, EmptyFluidTypeTrackerInjector.class)) {
			KernelBootChain.Registration registration = KernelBootChain.registration(edit.getName());
			assertEquals(TransformPhase.COREMOD, registration.phase(), edit.getSimpleName());
			assertEquals(facade.sortIndex(), registration.sortIndex());
			assertTrue(facade.position() < registration.position(), registration.describe() + " must come after " + facade.describe());
		}
	}
}
