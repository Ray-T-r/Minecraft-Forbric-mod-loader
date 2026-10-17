/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;

/**
 * {@link SplitterPacketContextInjector}'s output, run: NeoForge's packet splitter encodes a packet whose Fabric codec
 * reads {@code PacketContext.get()} (Polymer's recipe codec does) and the codec gets the connection's own context,
 * where as merged it got null, the recipe packet failed to encode and the client was disconnected at world join.
 *
 * <p>The hook is the kernel's real {@code KernelPacketContext}, compiled from {@code src/runtime/java} against stand-ins
 * for netty's handler context and pipeline, vanilla's {@code PacketEncoder} holding Fabric's context (the field
 * fabric-api's mixin adds), and Fabric's context API, whose {@code VALUE} is a {@code java.lang.ScopedValue}. That class
 * is a preview API on JDK 21, which CI's kernel job runs, so every stand-in reaches it by reflection, as the hook does:
 * nothing here is compiled against it, and nothing needs {@code --enable-preview}.
 */
@ExecutesInjector(SplitterPacketContextInjector.class)
@ResourceLock("system-properties")
class SplitterPacketContextInjectorExecutionTest {
	private static final Path HOOK_SOURCE = Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelPacketContext.java");
	private static final String SPLITTER = "net.neoforged.neoforge.network.filters.GenericPacketSplitter";

	private static final Map<String, String> STAND_INS = Map.of(
			"io.netty.channel.ChannelHandlerContext", """
					package io.netty.channel;

					public interface ChannelHandlerContext {
						ChannelPipeline pipeline();
					}
					""",
			"io.netty.channel.ChannelPipeline", """
					package io.netty.channel;

					public interface ChannelPipeline extends Iterable<java.util.Map.Entry<String, Object>> {
					}
					""",
			"net.minecraft.network.protocol.Packet", """
					package net.minecraft.network.protocol;

					public interface Packet {
						String write();
					}
					""",
			"net.fabricmc.fabric.api.networking.v1.context.PacketContext", """
					package net.fabricmc.fabric.api.networking.v1.context;

					import org.relay.wire.Envelope;

					public interface PacketContext {
						String connection();

						/** The context bound around this encode, or null outside one. */
						static PacketContext get() {
							try {
								Class<?> scoped = Class.forName("java.lang.ScopedValue");
								boolean bound = (Boolean) scoped.getMethod("isBound").invoke(Envelope.CURRENT);
								return bound ? (PacketContext) scoped.getMethod("get").invoke(Envelope.CURRENT) : null;
							} catch (ReflectiveOperationException e) {
								throw new IllegalStateException(e);
							}
						}
					}
					""",
			"org.relay.wire.Envelope", """
					package org.relay.wire;

					import net.fabricmc.fabric.api.networking.v1.context.PacketContext;

					/** The context behind the public interface, under a name of its own: the kernel finds it by what get() reads. */
					public record Envelope(String connection) implements PacketContext {
						/** A java.lang.ScopedValue, made by reflection: it is a preview API on JDK 21. */
						public static final Object CURRENT;

						static {
							try {
								CURRENT = Class.forName("java.lang.ScopedValue").getMethod("newInstance").invoke(null);
							} catch (ReflectiveOperationException e) {
								throw new ExceptionInInitializerError(e);
							}
						}
					}
					""",
			"net.minecraft.network.PacketEncoder", """
					package net.minecraft.network;

					import net.fabricmc.fabric.api.networking.v1.context.PacketContext;

					/** Vanilla's encoder, with the context field fabric-api's mixin adds. */
					public class PacketEncoder<T> {
						private final PacketContext fabric_context;

						public PacketEncoder(PacketContext context) {
							this.fabric_context = context;
						}
					}
					""",
			SPLITTER, """
					package net.neoforged.neoforge.network.filters;

					import io.netty.channel.ChannelHandlerContext;
					import java.util.List;
					import net.minecraft.network.protocol.Packet;

					/** NeoForge's: it encodes each packet itself, to measure it, ahead of PacketEncoder. */
					public class GenericPacketSplitter {
						protected void encode(ChannelHandlerContext ctx, Packet packet, List<Object> out) throws Exception {
							out.add(packet.write());
						}
					}
					""",
			"fixture.RecipesPacket", """
					package fixture;

					import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
					import net.minecraft.network.protocol.Packet;

					/** update_recipes with a Polymer ingredient: its codec reads the packet context. */
					public class RecipesPacket implements Packet {
						public String write() {
							return "recipes for " + PacketContext.get().connection();
						}
					}
					""",
			"fixture.Pipeline", """
					package fixture;

					import io.netty.channel.ChannelHandlerContext;
					import io.netty.channel.ChannelPipeline;
					import java.util.Iterator;
					import java.util.List;
					import java.util.Map;
					import org.relay.wire.Envelope;
					import net.minecraft.network.PacketEncoder;

					/** A connection's pipeline: NeoForge's splitter, then vanilla's encoder holding Fabric's context. */
					public class Pipeline implements ChannelHandlerContext, ChannelPipeline {
						private final List<Map.Entry<String, Object>> handlers;

						public Pipeline(Object splitter, String connection) {
							handlers = List.of(Map.entry("splitter", splitter), Map.entry("encoder", new PacketEncoder<>(new Envelope(connection))));
						}

						public ChannelPipeline pipeline() {
							return this;
						}

						public Iterator<Map.Entry<String, Object>> iterator() {
							return handlers.iterator();
						}
					}
					""");

	@AfterEach void reset() {
		System.clearProperty(SplitterPacketContextInjector.PROPERTY);
	}

	private static Map<String, byte[]> compile(Path work) throws Exception {
		assertTrue(Files.isRegularFile(HOOK_SOURCE), "the game-side hook's source is part of the checkout: " + HOOK_SOURCE.toAbsolutePath());
		Map<String, String> sources = new HashMap<>(STAND_INS);
		sources.put("net/forbric/kernel/runtime/KernelPacketContext.java", Files.readString(HOOK_SOURCE));
		return InjectorExecution.compile(work, sources);
	}

	/** The splitter encoding update_recipes on a connection: what it put out. */
	private static List<Object> encode(ClassLoader loader) throws Throwable {
		Object splitter = InjectorExecution.construct(loader.loadClass(SPLITTER));
		Object ctx = InjectorExecution.construct(loader.loadClass("fixture.Pipeline"), splitter, "player-1");
		List<Object> out = new ArrayList<>();
		InjectorExecution.invoke(splitter, "encode", ctx, InjectorExecution.construct(loader.loadClass("fixture.RecipesPacket")), out);
		return out;
	}

	@Test void aFabricCodecInsideTheSplitterSeesTheConnectionsContext(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = compile(work);
		String internal = SPLITTER.replace('.', '/');
		byte[] repaired = InjectorExecution.transform(new SplitterPacketContextInjector(), SPLITTER, original.get(internal), EnvType.SERVER);
		assertNotSame(original.get(internal), repaired, "the splitter's encode was moved aside");
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(internal, repaired);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(repaired, loader));

		assertEquals(List.of("recipes for player-1"), encode(loader), "the codec reads the context PacketEncoder holds");

		assertThrows(NullPointerException.class, () -> encode(InjectorExecution.load(original)),
				"premise: as merged, the codec reads null inside NeoForge's splitter");
		assertSame(repaired, InjectorExecution.transform(new SplitterPacketContextInjector(), SPLITTER, repaired, EnvType.SERVER),
				"a splitter whose body is already moved is not wrapped twice");
	}

	@Test void switchedOffTheSplitterEncodesWhereItDid(@TempDir Path work) throws Exception {
		byte[] bytes = compile(work).get(SPLITTER.replace('.', '/'));
		System.setProperty(SplitterPacketContextInjector.PROPERTY, "off");
		assertSame(bytes, InjectorExecution.transform(new SplitterPacketContextInjector(), SPLITTER, bytes, EnvType.SERVER));
	}
}
