/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;

import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Builds every custom-payload codec through vanilla's {@code codec(fallback, types)} again, so a mod hooking that
 * method takes part, while the codec built is still NeoForge's protocol-aware one.
 *
 * <p>The merged packet classes call only NeoForge's {@code codec(fallback, types, protocol, flow)}; vanilla's
 * overload is still there, with its own body, and nothing calls it. Carpet adds its {@code carpet:hello} type at
 * the HEAD of vanilla's overload (and re-calls it with the longer list), so its type was in no codec: a dedicated
 * server running Carpet could not encode the hello and disconnected every player as they logged in.
 *
 * <p>{@code PayloadCodecFunnelInjector} makes NeoForge's overload call {@link #through}, which records the protocol
 * and flow for this thread and calls vanilla's; vanilla's, inside that call, hands back to NeoForge's with them.
 *
 * <p>Vanilla's two-argument overload is asked through a method handle, not by name at compile time: the genuine
 * NeoForge jar has only NeoForge's four-argument one, and VanillaMemberTwinInjector puts vanilla's back when the class
 * is defined, which is before anything builds a codec.
 */
public final class KernelPayloadCodecs {
	private record Context(ConnectionProtocol protocol, PacketFlow flow) { }

	private static final ThreadLocal<Context> CONTEXT = new ThreadLocal<>();

	private KernelPayloadCodecs() {
	}

	/** NeoForge's overload, entered from outside: build through vanilla's with this protocol and flow. */
	public static <B extends FriendlyByteBuf> StreamCodec<B, CustomPacketPayload> through(
			CustomPacketPayload.FallbackProvider<B> fallback, List<CustomPacketPayload.TypeAndCodec<? super B, ?>> types,
			ConnectionProtocol protocol, PacketFlow flow) {
		Context previous = CONTEXT.get();
		CONTEXT.set(new Context(protocol, flow));
		try {
			return Vanilla.codec(fallback, types);
		} finally {
			if (previous == null) CONTEXT.remove();
			else CONTEXT.set(previous);
		}
	}

	/** The protocol of the build in progress on this thread, or null outside one. */
	public static ConnectionProtocol protocol() {
		Context context = CONTEXT.get();
		return context == null ? null : context.protocol();
	}

	/** The flow of the build in progress on this thread, or null outside one. */
	public static PacketFlow flow() {
		Context context = CONTEXT.get();
		return context == null ? null : context.flow();
	}

	/** Vanilla's {@code CustomPacketPayload.codec(FallbackProvider, List)}, resolved once at first use. */
	private static final class Vanilla {
		private static final MethodHandle CODEC = resolve();

		private static MethodHandle resolve() {
			try {
				return MethodHandles.lookup().findStatic(CustomPacketPayload.class, "codec",
						MethodType.methodType(StreamCodec.class, CustomPacketPayload.FallbackProvider.class, List.class));
			} catch (ReflectiveOperationException drift) {
				throw new IllegalStateException("vanilla's CustomPacketPayload.codec(FallbackProvider, List) is not there", drift);
			}
		}

		@SuppressWarnings("unchecked")
		static <B extends FriendlyByteBuf> StreamCodec<B, CustomPacketPayload> codec(
				CustomPacketPayload.FallbackProvider<B> fallback, List<CustomPacketPayload.TypeAndCodec<? super B, ?>> types) {
			try {
				return (StreamCodec<B, CustomPacketPayload>) (StreamCodec<?, ?>) CODEC.invoke(fallback, types);
			} catch (RuntimeException | Error thrown) {
				throw thrown;
			} catch (Throwable checked) {
				throw new IllegalStateException(checked);
			}
		}
	}
}
