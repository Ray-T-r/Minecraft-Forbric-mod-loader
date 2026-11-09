/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.forbric.kernel.runtime.StagedGameClassLoader;

/**
 * What {@code -Dforbric.customPayloadInteropSeam=off} hands a vanilla-compiled payload caller on NeoForge's own base,
 * run on the staged carrier (decision D63 of the MinecraftForge removal; the open question of
 * stage2-measurements.md).
 *
 * <p>With the seam off, NeoForge's {@code CustomPacketPayload$1.findCodec} asks its local map, then
 * {@code NetworkRegistry.getCodec(id, protocol, flow)}, then the caller's fallback. A vanilla-compiled caller builds that
 * class through the vanilla-shaped delegate twin, which passes no protocol and no flow, so the lookup is
 * {@code getCodec(id, null, null)}. Whether that tolerates the nulls decided whether the switch may still be offered.
 * Measured: it does not throw. A built-in payload ({@code minecraft:register}, {@code neoforge:register}) comes back with
 * its codec, because that branch asks no protocol and compares the flow by identity; any other id is looked up under a
 * null protocol, which NeoForge's protocol map (an {@code ImmutableMap}) answers with "absent" rather than a
 * {@code NullPointerException}, so it returns null after logging "A packet was received while not in the configuration
 * or play phase." with a stack dump, and {@code findCodec} goes on to the caller's fallback. So the switch stays: off,
 * a vanilla-compiled caller loses the cross-ecosystem choice the seam makes and gets one ERROR per unregistered id,
 * never a crash.
 */
class NeoForgeLookupWithoutContextStagedTest {
	@Test void neoForgesLookupToleratesNoProtocolAndNoFlow() throws Throwable {
		try (URLClassLoader game = StagedGameClassLoader.create(List.of())) {
			Class<?> identifier = Class.forName("net.minecraft.resources.Identifier", true, game);
			Method parse = identifier.getMethod("parse", String.class);
			Class<?> registry = Class.forName("net.neoforged.neoforge.network.registration.NetworkRegistry", true, game);
			Method getCodec = registry.getMethod("getCodec", identifier,
					Class.forName("net.minecraft.network.ConnectionProtocol", false, game),
					Class.forName("net.minecraft.network.protocol.PacketFlow", false, game));
			for (String builtIn : List.of("minecraft:register", "neoforge:register")) {
				assertNotNull(lookUp(getCodec, parse.invoke(null, builtIn)), builtIn + ": a built-in payload keeps its codec");
			}
			assertNull(lookUp(getCodec, parse.invoke(null, "forbric:unregistered_probe")),
					"an id NeoForge has no protocol-free registration for: null, so findCodec asks the caller's fallback");
		}
	}

	private static Object lookUp(Method getCodec, Object id) throws Throwable {
		try {
			return getCodec.invoke(null, id, null, null);
		} catch (InvocationTargetException thrown) {
			return fail("NeoForge's getCodec(" + id + ", null, null) threw: the switch must be retired or guarded (D63)", thrown.getCause());
		}
	}
}
