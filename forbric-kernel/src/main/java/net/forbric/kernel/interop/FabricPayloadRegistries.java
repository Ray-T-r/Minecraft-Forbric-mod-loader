/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * fabric-api's payload type registries, reached the way a mod reaches them: through the public
 * {@code PayloadTypeRegistry} interface's static accessors. Which phase and direction each serves, and the payload types
 * it holds, are read off the instance by type — its {@code ConnectionProtocol}, its {@code PacketFlow}, and its map whose
 * values are {@code CustomPacketPayload.TypeAndCodec} — never by the names its implementation gives them, so a build of
 * the API whose implementation is laid out under other names is read the same way.
 */
final class FabricPayloadRegistries {
	static final String API = "net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry";
	private static final String PROTOCOL = "net.minecraft.network.ConnectionProtocol";
	private static final String FLOW = "net.minecraft.network.protocol.PacketFlow";
	private static final String TYPE_AND_CODEC = "net.minecraft.network.protocol.common.custom.CustomPacketPayload$TypeAndCodec";
	private static final String TYPE = "net.minecraft.network.protocol.common.custom.CustomPacketPayload$Type";
	private static final String STREAM_CODEC = "net.minecraft.network.codec.StreamCodec";

	/** One registry: the public instance, the phase and direction it serves, and its id-to-type-and-codec map. */
	record Registry(Object instance, Object protocol, Object flow, Map<?, ?> types) {
	}

	private static final Map<ClassLoader, List<Registry>> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

	private FabricPayloadRegistries() {
	}

	/** Every registry the public interface hands out, read whole; empty without fabric-api's networking API. */
	static List<Registry> all(ClassLoader loader) {
		List<Registry> cached = CACHE.get(loader);
		if (cached != null) return cached;
		List<Registry> found = read(loader);
		if (!found.isEmpty()) CACHE.put(loader, found);
		return found;
	}

	/** The registry serving {@code protocol} in direction {@code flow}, or null. */
	static Registry find(ClassLoader loader, Object protocol, Object flow) {
		for (Registry registry : all(loader)) if (registry.protocol() == protocol && registry.flow() == flow) return registry;
		return null;
	}

	/** {@code registry.register(type, codec)} through the public interface; its answer, or null when it refused. */
	static Object register(ClassLoader loader, Registry registry, Object type, Object codec) {
		try {
			Class<?> api = Class.forName(API, false, loader);
			Method register = api.getMethod("register", Class.forName(TYPE, false, loader), Class.forName(STREAM_CODEC, false, loader));
			return register.invoke(registry.instance(), type, codec);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError refused) {
			return null;
		}
	}

	private static List<Registry> read(ClassLoader loader) {
		Class<?> api, protocol, flow, typeAndCodec;
		try {
			api = Class.forName(API, false, loader);
			protocol = Class.forName(PROTOCOL, false, loader);
			flow = Class.forName(FLOW, false, loader);
			typeAndCodec = Class.forName(TYPE_AND_CODEC, false, loader);
		} catch (ClassNotFoundException | LinkageError absent) {
			return List.of();
		}
		List<Registry> out = new ArrayList<>();
		for (Method accessor : api.getMethods()) {
			if (!Modifier.isStatic(accessor.getModifiers()) || accessor.getParameterCount() != 0
					|| !api.isAssignableFrom(accessor.getReturnType())) continue;
			try {
				Object instance = accessor.invoke(null);
				if (instance == null) continue;
				Object servedProtocol = onlyOfType(instance, protocol);
				Object servedFlow = onlyOfType(instance, flow);
				Map<?, ?> types = typesOf(instance, typeAndCodec);
				if (servedProtocol != null && servedFlow != null && types != null) out.add(new Registry(instance, servedProtocol, servedFlow, types));
			} catch (ReflectiveOperationException | RuntimeException | LinkageError unreadable) {
				// one accessor that cannot be read costs that registry, not the others
			}
		}
		out.sort(IN_ONE_ORDER);
		return out;
	}

	/**
	 * {@code Class.getMethods()} hands the accessors out in no particular order, and not in the same one in two JVMs — the
	 * client's and the server's load other classes first — so the registries are put in one: configuration before play,
	 * serverbound before clientbound, the order of fabric-api's own constants. What is built from them in turn (the
	 * mirrors into NeoForge's registry, which codec a channel registered both ways keeps) then comes out the same on both
	 * peers.
	 */
	static final Comparator<Registry> IN_ONE_ORDER = Comparator.comparing((Registry registry) -> name(registry.protocol()))
			.thenComparing(registry -> name(registry.flow()), Comparator.reverseOrder());

	private static String name(Object constant) {
		return constant instanceof Enum<?> value ? value.name() : String.valueOf(constant);
	}

	/** The one instance field (this class or a superclass) holding a value of {@code type}; null when none or several. */
	private static Object onlyOfType(Object instance, Class<?> type) throws IllegalAccessException {
		Field only = null;
		for (Class<?> c = instance.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
			for (Field field : c.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers()) || field.getType() != type) continue;
				if (only != null) return null;
				only = field;
			}
		}
		if (only == null) return null;
		only.setAccessible(true);
		return only.get(instance);
	}

	/** The one map field whose declared values are {@code TypeAndCodec}s; null when none or several. */
	private static Map<?, ?> typesOf(Object instance, Class<?> typeAndCodec) throws IllegalAccessException {
		Field only = null;
		for (Class<?> c = instance.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
			for (Field field : c.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers()) || !Map.class.isAssignableFrom(field.getType())) continue;
				if (!(field.getGenericType() instanceof ParameterizedType generic) || generic.getActualTypeArguments().length != 2
						|| raw(generic.getActualTypeArguments()[1]) != typeAndCodec) continue;
				if (only != null) return null;
				only = field;
			}
		}
		if (only == null) return null;
		only.setAccessible(true);
		return only.get(instance) instanceof Map<?, ?> map ? map : null;
	}

	private static Class<?> raw(Type type) {
		if (type instanceof Class<?> c) return c;
		if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> c) return c;
		return null;
	}
}
