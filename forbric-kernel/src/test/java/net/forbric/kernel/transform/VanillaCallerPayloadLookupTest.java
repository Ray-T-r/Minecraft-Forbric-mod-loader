/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The lookup a vanilla-compiled caller reaches on the genuine NeoForge base, run. Vanilla's
 * {@code CustomPacketPayload.codec(FallbackProvider, List)} (a copied twin there) builds the codec class through its
 * vanilla-shaped constructor (a delegate twin), which hands NeoForge's constructor null for the protocol and the flow;
 * the kernel's seam then passes those nulls into {@code PayloadInterop.findCodec}. CustomPayloadInteropSeamWeaveTest
 * proves that chain in the genuine bytes as woven; this runs the kernel's real {@code PayloadInterop} on the far end of
 * it, over {@link PostMixinCodecReturnArbitrationTest#SOURCES}' stand-in registries of all three ecosystems, through the
 * same fixture lookup with no protocol and no flow.
 *
 * <p>What it must do is what vanilla's own lookup does, plus what needs no protocol to know: a codec the caller handed it
 * for the id wins, an id nobody registered goes to the caller's fallback (asked once), and the registries that are not
 * keyed by protocol — NeoForge's built-in payloads, MinecraftForge's channels — still encode their own payloads. Fabric's
 * registries are one per protocol and flow, so with neither known none of them is consulted, and nothing throws.
 * (The merged base never reaches this: its vanilla-shaped {@code $1} keeps vanilla's id-only lookup.)
 */
class VanillaCallerPayloadLookupTest {
	private static final String NEO_PAYLOAD = "net.neoforged.neoforge.network.payload.MinecraftRegisterPayload";
	private static final String FORGE_PAYLOAD = "net.minecraftforge.network.ForgePayload";

	@Test void withNoProtocolOrFlowTheLookupKeepsVanillasChoicesAndTheRegistriesThatNeedNone(@TempDir Path work) throws Throwable {
		Map<String, byte[]> classes = InjectorExecution.compile(work, PostMixinCodecReturnArbitrationTest.SOURCES);
		ClassLoader loader = InjectorExecution.load(classes);
		// PayloadInterop finds the game through the loader of the first non-null of id, protocol and flow. In the game the
		// id is an Identifier, defined by the game's loader; here it is a String, so the game's loader is the context one.
		Thread thread = Thread.currentThread();
		ClassLoader context = thread.getContextClassLoader();
		thread.setContextClassLoader(loader);
		try {
			lookUpWithoutContext(loader);
		} finally {
			thread.setContextClassLoader(context);
		}
	}

	private static void lookUpWithoutContext(ClassLoader loader) throws Throwable {
		Object fabric = codec(loader, "fabric", "org.quarry.net.Register");
		Object neo = codec(loader, "neo", NEO_PAYLOAD);
		Object forge = codec(loader, "forge", FORGE_PAYLOAD);
		register(loader, "minecraft:register", fabric, neo, forge);

		// The caller's own codec for its id wins, as in vanilla's lookup.
		Object local = codec(loader, "local", "fixture.UnknownPayload");
		Map<Object, Object> locals = new HashMap<>();
		locals.put("vanilla:known", local);
		Object lookup = vanillaShaped(loader, locals);
		assertEquals("local", encode(loader, InjectorExecution.invoke(lookup, "lookup", "vanilla:known"), "fixture.UnknownPayload"));

		// An id no ecosystem registered: the caller's fallback, asked once.
		Object unknown = vanillaShaped(loader, new HashMap<>());
		Object factory = unknown.getClass().getField("fallback").get(unknown);
		factory.getClass().getField("codec").set(factory, codec(loader, "fallback", "fixture.UnknownPayload"));
		assertEquals("fallback", encode(loader, InjectorExecution.invoke(unknown, "lookup", "nobody:registered"), "fixture.UnknownPayload"));
		assertEquals(1, factory.getClass().getField("calls").getInt(factory), "the fallback is asked once");

		// A shared id: the registries keyed by no protocol still write their own payloads; Fabric's, one per protocol and
		// flow, is not among them when neither is known.
		Object shared = InjectorExecution.invoke(vanillaShaped(loader, new HashMap<>()), "lookup", "minecraft:register");
		assertEquals("neo", encode(loader, shared, NEO_PAYLOAD));
		assertEquals("forge", encode(loader, shared, FORGE_PAYLOAD));
		assertEquals(0, fabric.getClass().getField("writes").getInt(fabric), "Fabric's per-protocol codec is never chosen");
		assertEquals(NEO_PAYLOAD, InjectorExecution.invoke(shared, "decode", InjectorExecution.construct(loader.loadClass("fixture.Buffer")))
				.getClass().getName(), "and NeoForge's protocol-free codec reads it back");
	}

	/** The fixture lookup as a vanilla-shaped codec class leaves it: no protocol, no flow, a fallback that counts calls. */
	private static Object vanillaShaped(ClassLoader loader, Map<Object, Object> locals) throws Throwable {
		return InjectorExecution.construct(loader.loadClass("fixture.Lookup"), locals, null, null,
				InjectorExecution.construct(loader.loadClass("fixture.Fallback")));
	}

	private static Object codec(ClassLoader loader, String by, String payload) throws Throwable {
		return InjectorExecution.construct(loader.loadClass("fixture.Codec"), by, loader.loadClass(payload));
	}

	/** Which codec wrote {@code payload} through {@code codec}. */
	private static Object encode(ClassLoader loader, Object codec, String payload) throws Throwable {
		Object buffer = InjectorExecution.construct(loader.loadClass("fixture.Buffer"));
		InjectorExecution.invoke(codec, "encode", buffer, InjectorExecution.construct(loader.loadClass(payload)));
		return buffer.getClass().getField("by").get(buffer);
	}

	@SuppressWarnings("unchecked")
	private static void register(ClassLoader loader, Object id, Object fabric, Object neo, Object forge) throws Throwable {
		Object channel = loader.loadClass("org.quarry.net.Channels").getField("SERVERBOUND_CONFIGURATION").get(null);
		((Map<Object, Object>) channel.getClass().getField("entries").get(channel)).put(id, InjectorExecution.construct(
				loader.loadClass("net.minecraft.network.protocol.common.custom.CustomPacketPayload$TypeAndCodec"), "fabric-type", fabric));
		((Map<Object, Object>) loader.loadClass("net.neoforged.neoforge.network.registration.NetworkRegistry")
				.getField("BUILTIN_PAYLOADS").get(null)).put(id, neo);
		((Map<Object, Object>) loader.loadClass("net.minecraftforge.network.NetworkRegistry").getField("CODECS").get(null)).put(id, forge);
	}
}
