/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;

/**
 * A custom payload read again through the wire: what one codec of a channel makes of the bytes another codec of the same
 * channel writes for it.
 *
 * <p>Two ecosystems that both speak a channel's wire format each decode it into their own payload class. Only one codec
 * decodes the packet, so the other ecosystem's handler can be handed a payload of a class it has never seen. The bytes are
 * the contract both were written to, so the payload as the other ecosystem would have read it is its own codec's decode of
 * what the first codec encodes — natively, exactly that codec would have read those bytes. Nothing here knows a payload
 * class: a codec that cannot write a payload (it casts it, and throws) is not its codec.
 */
final class WirePayloads {
	private static final String BUFFER = "net.minecraft.network.RegistryFriendlyByteBuf";
	private static final String BYTE_BUF = "io.netty.buffer.ByteBuf";
	private static final String UNPOOLED = "io.netty.buffer.Unpooled";
	private static final String REGISTRY_ACCESS = "net.minecraft.core.RegistryAccess";
	private static final String ENCODER = "net.minecraft.network.codec.StreamEncoder";
	private static final String DECODER = "net.minecraft.network.codec.StreamDecoder";

	private WirePayloads() {
	}

	/**
	 * {@code payload} as {@code target} decodes its bytes: {@code payload} itself when {@code target} writes it already (it is
	 * that codec's class), else the first of {@code sources} that writes it, read back by {@code target}; null when neither.
	 */
	static Object decodedBy(Object target, Object payload, Collection<?> sources) {
		if (target == null || payload == null) return null;
		ClassLoader loader = payload.getClass().getClassLoader();
		if (written(target, payload, loader) != null) return payload;
		for (Object source : sources) {
			if (source == null || source == target) continue;
			Object buffer = written(source, payload, loader);
			if (buffer == null) continue;
			Object read = read(target, buffer);
			if (read != null) return read;
		}
		return null;
	}

	/** Whether {@code codec} writes {@code payload} — the payload is of the class that codec reads into. */
	static boolean writes(Object codec, Object payload) {
		return codec != null && payload != null && written(codec, payload, payload.getClass().getClassLoader()) != null;
	}

	/** A buffer holding what {@code codec} writes for {@code payload}, or null when it cannot write it. */
	private static Object written(Object codec, Object payload, ClassLoader loader) {
		try {
			Object buffer = buffer(loader);
			if (buffer == null) return null;
			Method encode = Class.forName(ENCODER, false, loader).getMethod("encode", Object.class, Object.class);
			encode.invoke(codec, buffer, payload);
			return buffer;
		} catch (InvocationTargetException refused) {
			return null;    // the codec cast the payload, or could not write it: not this codec's payload
		} catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
			return null;
		}
	}

	private static Object read(Object codec, Object buffer) {
		try {
			Method decode = Class.forName(DECODER, false, codec.getClass().getClassLoader() != null
					? codec.getClass().getClassLoader() : buffer.getClass().getClassLoader()).getMethod("decode", Object.class);
			return decode.invoke(codec, buffer);
		} catch (InvocationTargetException refused) {
			return null;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
			return null;
		}
	}

	/** A fresh registry-aware buffer with no registries: a configuration-phase codec reads it as its plain buffer. */
	private static Object buffer(ClassLoader loader) throws ReflectiveOperationException {
		Class<?> byteBuf = Class.forName(BYTE_BUF, false, loader);
		Class<?> access = Class.forName(REGISTRY_ACCESS, false, loader);
		Object raw = Class.forName(UNPOOLED, false, loader).getMethod("buffer").invoke(null);
		Object empty = access.getField("EMPTY").get(null);
		return Class.forName(BUFFER, false, loader).getConstructor(byteBuf, access).newInstance(raw, empty);
	}
}
