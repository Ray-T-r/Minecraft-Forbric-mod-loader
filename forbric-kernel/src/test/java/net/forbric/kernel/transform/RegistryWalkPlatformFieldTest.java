/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.FieldInsnNode;

/**
 * A walk's registry is read from a static field of a class the class loader defines as the platform's — the game and the
 * carriers ({@code DelegationPolicy.alwaysGame}) — never from a mod's, and fabric-api's classes are a mod's like any other:
 * the same field under fabric-api's package and under an unrelated mod's package reads the same.
 */
class RegistryWalkPlatformFieldTest {
	private static boolean read(String owner) {
		return RegistryWalkProof.registryRead(new FieldInsnNode(Opcodes.GETSTATIC, owner, "BLOCK", "Lnet/minecraft/core/Registry;"));
	}

	@Test void theGameAndTheCarriersArePlatform() {
		assertTrue(read("net/minecraft/core/registries/BuiltInRegistries"));
		assertTrue(read("net/neoforged/neoforge/registries/NeoForgeRegistries"));
		assertTrue(read("net/minecraftforge/registries/ForgeRegistries"));
	}

	@Test void aModsClassIsNotFabricApisIncluded() {
		for (String owner : java.util.List.of("net/fabricmc/fabric/impl/qzx/Holders", "net/fabricmc/fabric/api/qzx/Holders", "org/qzx/othermod/Holders"))
			assertFalse(read(owner), owner);
	}

	@Test void onlyARegistryTypedStaticReadCounts() {
		assertFalse(RegistryWalkProof.registryRead(new FieldInsnNode(Opcodes.GETFIELD, "net/minecraft/core/registries/BuiltInRegistries", "BLOCK", "Lnet/minecraft/core/Registry;")));
		assertFalse(RegistryWalkProof.registryRead(new FieldInsnNode(Opcodes.GETSTATIC, "net/minecraft/core/registries/BuiltInRegistries", "BLOCK", "Ljava/lang/Object;")));
	}
}
