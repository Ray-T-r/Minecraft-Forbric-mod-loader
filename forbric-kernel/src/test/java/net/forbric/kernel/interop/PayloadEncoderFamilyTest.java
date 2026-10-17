/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import net.forbric.kernel.classloading.LoaderProbePolicy.Family;

/**
 * Whose codec writes a payload first: a carrier's own payload by its platform package, a Fabric mod's payload by the jar its
 * class was defined from. fabric-api's payloads are a Fabric mod's, so the verdict is the same under its packages and under
 * any other name it might be relocated to; a class under fabric-api's package that no Fabric mod jar defined is no one's.
 */
class PayloadEncoderFamilyTest {
	@Test void theCarriersOwnPayloadsByTheirPlatformPackage() {
		assertEquals(Family.NEOFORGE, PayloadInterop.encoderFamily("net.neoforged.neoforge.network.payload.MinecraftRegisterPayload", null));
		assertEquals(Family.FORGE, PayloadInterop.encoderFamily("net.minecraftforge.network.ForgePayload", null));
	}

	@Test void aFabricModsPayloadByItsJarWhateverItsPackage() {
		assertEquals(Family.FABRIC, PayloadInterop.encoderFamily("net.fabricmc.fabric.impl.networking.RegistrationPayload", Family.FABRIC));
		assertEquals(Family.FABRIC, PayloadInterop.encoderFamily("org.qzx.relocated.networking.RegistrationPayload", Family.FABRIC));
		assertNull(PayloadInterop.encoderFamily("net.fabricmc.fabric.impl.networking.RegistrationPayload", null), "no Fabric mod jar defined it");
	}

	@Test void anyOtherPayloadIsLeftToTheRegistryEntries() {
		assertNull(PayloadInterop.encoderFamily("org.qzx.neomod.SyncPayload", Family.NEOFORGE));
		assertNull(PayloadInterop.encoderFamily("net.minecraft.network.protocol.common.custom.BrandPayload", null));
	}
}
