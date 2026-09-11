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

package net.forbric.kernel.interop;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.WeakHashMap;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;

/**
 * Runtime bridge for the merged Forge/Fabric/NeoForge custom-payload codec path.
 *
 * <p>The merged Minecraft base keeps NeoForge's four-argument {@code CustomPacketPayload.codec(...)} plumbing, but
 * Fabric API registers payload codecs in its own side/protocol registries. The vanilla/Neo lookup is id-only, which
 * is unsafe when two loader APIs intentionally use the same vanilla channel id with different Java payload classes
 * ({@code minecraft:register}/{@code minecraft:unregister}). This helper returns a tiny {@code StreamCodec} proxy
 * that chooses the concrete codec by runtime payload type for encode, and by available protocol registry for decode.
 *
 * <p>No Minecraft, Fabric, Forge or NeoForge type is referenced directly here. The loader core is parent-loaded, so
 * all game/loader API interaction is reflective against the Knot class loader that owns the live classes.
 */
public final class PayloadInterop {
	private static final String FABRIC_REGISTRY = "net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl";
	private static final String FABRIC_REGISTRATION_PAYLOAD = "net.fabricmc.fabric.impl.networking.RegistrationPayload";
	private static final String FABRIC_COMMON_VERSION_PAYLOAD = "net.fabricmc.fabric.impl.networking.CommonVersionPayload";
	private static final String FABRIC_COMMON_REGISTER_PAYLOAD = "net.fabricmc.fabric.impl.networking.CommonRegisterPayload";
	private static final String NEO_NETWORK_REGISTRY = ForeignType.NETWORK_REGISTRY.binary(Ecosystem.NEOFORGE);
	private static final String NEO_REGISTER_PAYLOAD = "net.neoforged.neoforge.network.payload.MinecraftRegisterPayload";
	private static final String NEO_UNREGISTER_PAYLOAD = "net.neoforged.neoforge.network.payload.MinecraftUnregisterPayload";
	private static final String NEO_COMMON_VERSION_PAYLOAD = "net.neoforged.neoforge.network.payload.CommonVersionPayload";
	private static final String NEO_COMMON_REGISTER_PAYLOAD = "net.neoforged.neoforge.network.payload.CommonRegisterPayload";
	private static final String NEO_PAYLOAD_REGISTRATION = "net.neoforged.neoforge.network.registration.PayloadRegistration";
	// Traditional MinecraftForge. Its custom-payload plumbing lost the byte-merge to NeoForge's on both the codec and
	// the dispatch side, so the kernel routes to its public entry points from here: ForgeHooks.getCustomPayloadCodec
	// for a channel it owns, ForgeHooks.onCustomPayload for a ForgePayload it should handle, and NetworkContext for
	// the per-connection channel bookkeeping its channels consult before sending.
	private static final String FORGE_NETWORK_REGISTRY = ForeignType.NETWORK_REGISTRY.binary(Ecosystem.FORGE);
	private static final String FORGE_HOOKS = "net.minecraftforge.common.ForgeHooks";
	private static final String FORGE_PAYLOAD = "net.minecraftforge.network.ForgePayload";
	private static final String FORGE_NETWORK_CONTEXT = "net.minecraftforge.network.NetworkContext";
	private static final String FORGE_CHANNEL_LIST = "net.minecraftforge.network.ChannelListManager";
	/** Vanilla's payload size caps, which Forge's codec provider takes as its second argument. */
	private static final int CLIENTBOUND_MAX_PAYLOAD = 1048576;
	private static final int SERVERBOUND_MAX_PAYLOAD = 32767;
	private static final Map<Object, Boolean> FORGE_CHANNELS_DECLARED = Collections.synchronizedMap(new WeakHashMap<>());
	private static final String CLIENTBOUND_CUSTOM_PAYLOAD_PACKET = "net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket";
	private static final String SERVERBOUND_CUSTOM_PAYLOAD_PACKET = "net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket";
	private static final String FORBRIC_MIRROR_VERSION = "forbric-bridge";
	private static final Map<ClassLoader, Boolean> MIRRORED_LOADERS = Collections.synchronizedMap(new WeakHashMap<>());

	private PayloadInterop() {
	}

	public static void bootstrapMirrors(ClassLoader cl) {
		ClassLoader loader = cl != null ? cl : loaderFor();
		synchronized (MIRRORED_LOADERS) {
			if (MIRRORED_LOADERS.putIfAbsent(loader, Boolean.TRUE) != null) return;
		}

		try {
			mirrorMergedPayloadRegistries(loader);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric] could not bootstrap merged custom-payload mirrors", unwrap(t));
		}
	}

	/**
	 * Called from bytecode patched into merged {@code CustomPacketPayload$1$forbricneo.findCodec(...)}.
	 *
	 * @return an object implementing the live {@code net.minecraft.network.codec.StreamCodec} interface.
	 */
	public static Object findCodec(Map<?, ?> localCodecs, Object id, Object protocol, Object packetFlow, Object fallback) {
		bootstrapMirrors(loaderFor(id, protocol, packetFlow));
		Object local = localCodecs != null ? localCodecs.get(id) : null;
		Object fabricEntry = fabricTypeAndCodec(id, protocol, packetFlow);
		Object fabric = typeAndCodecCodec(fabricEntry);
		Object neo = neoCodec(id, protocol, packetFlow);
		Object forge = forgeCodec(id, packetFlow);
		Object fallbackCodec = fallbackCodec(fallback, id);

		Object direct = uniqueCodec(local, fabric, neo, forge, fallbackCodec);
		probe(() -> "codec for " + id + " " + protocol + "/" + packetFlow + ": local=" + (local != null)
				+ " fabric=" + (fabric != null) + " neo=" + (neo != null) + " forge=" + (forge != null)
				+ " fallback=" + (fallbackCodec != null)
				+ (direct != null ? " -> direct " + direct.getClass().getSimpleName() : " -> proxy"));
		if (direct != null) return direct;

		ClassLoader loader = loaderFor(id, protocol, packetFlow);
		Class<?> streamCodec = load(loader, "net.minecraft.network.codec.StreamCodec");
		if (streamCodec == null) {
			return firstNonNull(local, fabric, neo, forge, fallbackCodec);
		}

		CandidateSet candidates = new CandidateSet(id, local, fabricEntry, fabric, neo, forge, fallbackCodec);
		return Proxy.newProxyInstance(loader, new Class<?>[] { streamCodec }, new CodecInvocationHandler(candidates));
	}

	/**
	 * Called by the Fabric-channel-addon mixin. Returns {@code Boolean.TRUE} only when the target's original
	 * {@code handle(...)} method should be considered complete and skipped.
	 */
	public static Boolean handleFabricChannelRegistrationAddon(Object addon, Object payload) {
		// Every decision this method makes, under -Dforbric.debug. Added because the one question the code could not
		// answer from its own log was the only one that mattered when multiplayer broke: did the client's channel
		// declaration reach the server at all, and if not, which of the five branches below swallowed it. Each
		// branch that returns now says so; "nothing in the log" used to be the answer to all five.
		probe(() -> "addon " + simpleName(addon) + " <- " + payloadId(payload) + " [" + simpleName(payload) + "]");
		bootstrapMirrors(loaderFor(addon, payload));
		Boolean commonNegotiation = handleFabricCommonNegotiationAddon(addon, payload);
		if (commonNegotiation != null) {
			probe(() -> "  common-networking negotiation handled it -> " + commonNegotiation);
			return commonNegotiation;
		}

		Registration registration = registration(payload);
		if (registration == null) {
			probe(() -> "  not a channel registration; leaving it to the addon's own body");
			if (ForbricLog.debugEnabled()) describeFrozenRegistrySnapshot(payload);
			return null;
		}
		probe(() -> "  " + (registration.register ? "register" : "unregister") + " " + registration.channels.size()
				+ " channel(s): " + registration.channels);

		// The order of the three halves below is a wire contract on BOTH ends, and each half pins one edge of it:
		//
		//  1. NeoForge's bookkeeping first (and Forge's, which rides on its head hook). On the server, Fabric's
		//     receiveRegistration — the mirror in step 2 — runs startConfiguration() synchronously, and Fabric's
		//     registry-sync task sends fabric:registry/sync from inside it; NeoForge's checkPacket vetoes any channel
		//     the client has not declared, and it learns the client's channels from exactly this onMinecraftRegister.
		//     Mirror first and the send throws "Payload fabric:registry/sync may not be sent to the client!" and
		//     the handshake stalls for good.
		//  2. Fabric's mirror. On the client this is what sends the client's own minecraft:register. Fabric's own
		//     body would make exactly this receiveRegistration call and return true, so a payload that is already
		//     Fabric's is mirrored here too rather than left to the body, which runs only after this method returns.
		//  3. MinecraftForge's declaration last. It is one more minecraft:register, and a Fabric server treats the
		//     FIRST register it receives as the client's complete declaration, running its registry-sync check on it
		//     right there: a Forge-only list ahead of Fabric's, and the client is kicked with "This server requires
		//     Fabric Loader and Fabric API installed on your client!". So the declaration is held back while
		//     step 1 runs and sent only after Fabric's.
		Object connection = fieldValue(addon, "connection");
		if (connection != null) {
			DECLARATION_DEFERRED.set(Boolean.TRUE);
			try {
				syncNeoChannels(connection, registration.register, registration.channels);
			} finally {
				DECLARATION_DEFERRED.set(Boolean.FALSE);
			}
		} else {
			probe(() -> "  no connection field on the addon; NeoForge's and Forge's halves were NOT told");
		}

		Object fabricPayload = payload != null && payload.getClass().getName().equals(FABRIC_REGISTRATION_PAYLOAD)
				? payload
				: createFabricRegistrationPayload(payload, registration.register, registration.channels);
		if (fabricPayload == null) {
			probe(() -> "  could NOT synthesize Fabric's payload; Fabric's half was skipped");
		} else {
			boolean mirrored = invokeReceiveRegistration(addon, registration.register, fabricPayload);
			probe(() -> "  mirrored into Fabric receiveRegistration: " + mirrored
					+ "; sendable=" + channelSet(addon, "getSendableChannels")
					+ " receivable=" + channelSet(addon, "getReceivableChannels"));
		}

		if (connection != null && registration.register) declareForgeChannels(connection);
		return fabricPayload == null ? null : Boolean.TRUE;
	}

	/**
	 * Debug-only: for a NeoForge {@code neoforge:frozen_registry} payload, names the registry it carries, the
	 * registry's runtime class, how many entries its {@code MappedRegistry.byKey} actually holds, and every snapshot
	 * entry that is not a real local key. Written to explain "Failed to sync registries from the server:
	 * NullPointerException: holder is null" out of {@code MappedRegistry.registerIdMapping}, which NeoForge's handler
	 * reports without naming a registry. The first run of it ruled out aliases and missing entries (every remote
	 * name was a real local key) and the second — the class and the {@code byKey} count — found the cause: the
	 * seventeen registries that are MinecraftForge {@code NamespacedWrapper}s answer {@code containsKey} from their
	 * delegate while their inherited {@code byKey} holds zero entries. See the kernel's RegistrySyncParityInjector.
	 */
	private static void describeFrozenRegistrySnapshot(Object payload) {
		if (payload == null || !"neoforge:frozen_registry".equals(payloadId(payload))) return;
		try {
			Object registryName = invokeNoArg(payload, "registryName");
			Object snapshot = invokeNoArg(payload, "snapshot");
			Object ids = invokeNoArg(snapshot, "getIds");                       // Int2ObjectSortedMap<Identifier>
			Object aliases = invokeNoArg(snapshot, "getAliases");
			Collection<?> names = ids instanceof Map<?, ?> m ? m.values() : List.of();
			ClassLoader loader = loaderFor(payload);
			Class<?> builtIn = load(loader, "net.minecraft.core.registries.BuiltInRegistries");
			Object root = builtIn == null ? null : staticField(builtIn, "REGISTRY");
			Object registry = root == null ? null : invoke(root, "getValue", registryName);
			if (registry == null) {
				probe(() -> "  frozen registry " + registryName + ": " + names.size() + " id(s); NO local registry by that name");
				return;
			}
			Object keySet = invokeNoArg(registry, "keySet");                     // Set<Identifier> — real keys only
			List<Object> notReal = new ArrayList<>();
			if (keySet instanceof Set<?> keys) {
				for (Object name : names) if (!keys.contains(name)) notReal.add(name);
			}
			int localSize = keySet instanceof Set<?> keys ? keys.size() : -1;
			Object byKey = fieldValue(registry, "byKey");
			int byKeySize = byKey instanceof Map<?, ?> m ? m.size() : -1;
			probe(() -> "  frozen registry " + registryName + " [" + registry.getClass().getName() + "]: " + names.size()
					+ " remote id(s) vs " + localSize + " local key(s), MappedRegistry.byKey holds " + byKeySize
					+ "; remote aliases=" + aliases + "; remote names that are not real local keys: " + notReal);
		} catch (RuntimeException e) {
			probe(() -> "  frozen registry probe failed: " + e);
		}
	}

	/**
	 * {@code System.out}, not {@code ForbricLog}: these probes exist to answer "did this branch run at all", and
	 * routing them through a logger makes a silent log pipeline indistinguishable from code that never executed —
	 * exactly the confusion they were added to end. The message is a supplier so nothing is built when debug is off.
	 */
	private static void probe(java.util.function.Supplier<String> message) {
		if (ForbricLog.debugEnabled()) System.out.println("[Forbric/Net] " + message.get());
	}

	private static String simpleName(Object o) {
		return o == null ? "null" : o.getClass().getSimpleName();
	}

	private static String channelSet(Object addon, String getter) {
		Object channels = invokeNoArg(addon, getter);
		return channels == null ? "?" : String.valueOf(channels);
	}

	/**
	 * Called from a mixin on {@code ServerConfigurationPacketListenerImpl.finishCurrentTask}. Fabric and NeoForge
	 * both implement the same common-networking handshake on {@code c:version}/{@code c:register}, but expose
	 * different {@code ConfigurationTask.Type}s. Treat those task ids as aliases on the merged base.
	 */
	public static boolean finishEquivalentCommonTask(Object listener, Object requestedType) {
		Object currentTask = fieldValue(listener, "currentTask");
		Object currentType = invokeNoArg(currentTask, "type");
		String current = taskId(currentType);
		String requested = taskId(requestedType);
		if (!equivalentCommonTask(current, requested)) return false;

		try {
			Field currentTaskField = findField(listener.getClass(), "currentTask");
			if (currentTaskField == null) return false;
			currentTaskField.setAccessible(true);
			currentTaskField.set(listener, null);
			Method startNextTask = findMethod(listener.getClass(), "startNextTask");
			if (startNextTask == null) return false;
			startNextTask.setAccessible(true);
			startNextTask.invoke(listener);
			ForbricLog.debug("[Forbric] completed equivalent common-networking task " + requested
					+ " while vanilla current task was " + current);
			return true;
		} catch (ReflectiveOperationException | RuntimeException e) {
			ForbricLog.warn("[Forbric] could not complete equivalent common-networking task " + requested
					+ " while current task was " + current, e);
			return false;
		}
	}

	private record MirrorRegistration(Object id, Object type, Object codec, Object protocol, Object flow,
			String source) {
	}

	private static void mirrorMergedPayloadRegistries(ClassLoader loader) {
		if (load(loader, NEO_NETWORK_REGISTRY) == null) {
			probe(() -> "mirror pass: NeoForge's NetworkRegistry is not visible from " + loader + "; nothing to mirror into");
			return;
		}

		int mirrored = 0;
		List<MirrorRegistration> local = reflectPacketLocalRegistrations(loader);
		List<MirrorRegistration> fabric = reflectFabricRegistrations(loader);
		for (MirrorRegistration registration : local) {
			if (mirrorPayloadIntoNeo(loader, registration)) mirrored++;
		}
		for (MirrorRegistration registration : fabric) {
			if (mirrorPayloadIntoNeo(loader, registration)) mirrored++;
		}
		int mirroredCount = mirrored;
		probe(() -> "mirror pass on " + loader + ": " + local.size() + " merged + " + fabric.size()
				+ " Fabric registration(s) seen, " + mirroredCount + " mirrored into NeoForge");
		if (mirrored > 0) {
			ForbricLog.info("[Forbric] mirrored " + mirrored
					+ " merged/Fabric custom payload registration(s) into NeoForge's decode registry");
		}
	}

	private static List<MirrorRegistration> reflectPacketLocalRegistrations(ClassLoader loader) {
		List<MirrorRegistration> out = new ArrayList<>();
		collectPacketRegistrations(loader, CLIENTBOUND_CUSTOM_PAYLOAD_PACKET, "GAMEPLAY_STREAM_CODEC", out);
		collectPacketRegistrations(loader, CLIENTBOUND_CUSTOM_PAYLOAD_PACKET, "CONFIG_STREAM_CODEC", out);
		collectPacketRegistrations(loader, SERVERBOUND_CUSTOM_PAYLOAD_PACKET, "STREAM_CODEC", out);
		collectPacketRegistrations(loader, SERVERBOUND_CUSTOM_PAYLOAD_PACKET, "CONFIG_STREAM_CODEC", out);
		return out;
	}

	private static void collectPacketRegistrations(ClassLoader loader, String packetClassName, String fieldName,
			List<MirrorRegistration> out) {
		Class<?> packetClass = load(loader, packetClassName);
		if (packetClass == null) return;
		try {
			Field streamCodec = packetClass.getField(fieldName);
			streamCodec.setAccessible(true);
			collectCodecRegistrations(streamCodec.get(null), "local/" + packetClass.getSimpleName() + "." + fieldName, out);
		} catch (ReflectiveOperationException | RuntimeException e) {
			ForbricLog.warn("[Forbric] could not inspect merged packet codec " + packetClassName + "::" + fieldName, e);
		}
	}

	private static void collectCodecRegistrations(Object codecHolder, String source, List<MirrorRegistration> out) {
		if (codecHolder == null) return;
		Object idToType = fieldValue(codecHolder, "val$idToType");
		Object protocol = fieldValue(codecHolder, "val$protocol");
		Object flow = fieldValue(codecHolder, "val$packetFlow");
		if (!(idToType instanceof Map<?, ?> map) || protocol == null || flow == null) return;

		for (Map.Entry<?, ?> entry : map.entrySet()) {
			Object typeAndCodec = entry.getValue();
			Object type = typeAndCodecType(typeAndCodec);
			Object codec = typeAndCodecCodec(typeAndCodec);
			if (entry.getKey() == null || type == null || codec == null) continue;
			out.add(new MirrorRegistration(entry.getKey(), type, codec, protocol, flow, source));
		}
	}

	private static List<MirrorRegistration> reflectFabricRegistrations(ClassLoader loader) {
		List<MirrorRegistration> out = new ArrayList<>();
		Class<?> registryClass = load(loader, FABRIC_REGISTRY);
		if (registryClass == null) return out;
		Field packetTypesField = findField(registryClass, "packetTypes");
		if (packetTypesField == null) return out;
		packetTypesField.setAccessible(true);

		for (String fieldName : List.of("SERVERBOUND_CONFIGURATION", "CLIENTBOUND_CONFIGURATION", "SERVERBOUND_PLAY",
				"CLIENTBOUND_PLAY")) {
			Object registry = staticField(registryClass, fieldName);
			if (registry == null) continue;
			try {
				Object packetTypes = packetTypesField.get(registry);
				if (!(packetTypes instanceof Map<?, ?> map)) continue;
				Object protocol = invokeNoArg(registry, "getProtocol");
				Object flow = invokeNoArg(registry, "getFlow");
				for (Map.Entry<?, ?> entry : map.entrySet()) {
					Object typeAndCodec = entry.getValue();
					Object type = typeAndCodecType(typeAndCodec);
					Object codec = typeAndCodecCodec(typeAndCodec);
					if (entry.getKey() == null || type == null || codec == null || protocol == null || flow == null) continue;
					out.add(new MirrorRegistration(entry.getKey(), type, codec, protocol, flow, "fabric/" + fieldName));
				}
			} catch (ReflectiveOperationException | RuntimeException e) {
				ForbricLog.warn("[Forbric] could not inspect Fabric payload registry " + fieldName, e);
			}
		}
		return out;
	}

	private static boolean mirrorPayloadIntoNeo(ClassLoader loader, MirrorRegistration registration) {
		Class<?> registryClass = load(loader, NEO_NETWORK_REGISTRY);
		Class<?> payloadRegistrationClass = load(loader, NEO_PAYLOAD_REGISTRATION);
		if (registryClass == null || payloadRegistrationClass == null) return false;
		try {
			Field registrationsField = findField(registryClass, "PAYLOAD_REGISTRATIONS");
			Field clientboundHandlersField = findField(registryClass, "CLIENTBOUND_HANDLERS");
			Field serverboundHandlersField = findField(registryClass, "SERVERBOUND_HANDLERS");
			if (registrationsField == null || clientboundHandlersField == null || serverboundHandlersField == null) return false;
			registrationsField.setAccessible(true);
			clientboundHandlersField.setAccessible(true);
			serverboundHandlersField.setAccessible(true);

				@SuppressWarnings("unchecked")
				Map<Object, Map<Object, Object>> registrations = (Map<Object, Map<Object, Object>>) registrationsField.get(null);
				Map<Object, Object> protocolMap = registrations.get(registration.protocol());
				if (protocolMap == null) return false;

				Constructor<?> ctor = payloadRegistrationClass.getConstructor(
						load(loader, "net.minecraft.network.protocol.common.custom.CustomPacketPayload$Type"),
					load(loader, "net.minecraft.network.codec.StreamCodec"),
					List.class,
					Optional.class,
					String.class,
					boolean.class);
				Object payloadRegistration = ctor.newInstance(registration.type(), registration.codec(),
						List.of(registration.protocol()), Optional.of(registration.flow()),
						FORBRIC_MIRROR_VERSION + ":" + registration.source(), Boolean.TRUE);
				Object existing = protocolMap.get(registration.id());
				if (existing != null) {
					if (!mergeNeoPayloadFlowIfNeeded(protocolMap, registration, existing, ctor)) return false;
					mirrorNoopHandler(loader, clientboundHandlersField, serverboundHandlersField, registration);
					return true;
				}
				protocolMap.put(registration.id(), payloadRegistration);
				mirrorNoopHandler(loader, clientboundHandlersField, serverboundHandlersField, registration);
				return true;
			} catch (UnsupportedOperationException e) {
				return false;
			} catch (ReflectiveOperationException | RuntimeException e) {
				ForbricLog.warn("[Forbric] could not mirror payload " + registration.id() + " into NeoForge", e);
				return false;
		}
	}

	private static boolean mergeNeoPayloadFlowIfNeeded(Map<Object, Object> protocolMap, MirrorRegistration registration,
			Object existing, Constructor<?> ctor) throws ReflectiveOperationException {
		Object existingFlow = invokeNoArg(existing, "flow");
		if (!(existingFlow instanceof Optional<?> optional) || optional.isEmpty()) return false;
		if (optional.get() == registration.flow()) return false;

		Object merged = ctor.newInstance(invokeNoArg(existing, "type"), invokeNoArg(existing, "codec"),
				List.of(registration.protocol()), Optional.empty(), FORBRIC_MIRROR_VERSION + ":" + registration.source(),
				Boolean.TRUE);
		protocolMap.put(registration.id(), merged);
		return true;
	}

	private static void mirrorNoopHandler(ClassLoader loader, Field clientboundHandlersField, Field serverboundHandlersField,
			MirrorRegistration registration) throws ReflectiveOperationException {
		Class<?> handlerClass = load(loader, "net.neoforged.neoforge.network.handling.IPayloadHandler");
		if (handlerClass == null) return;
		Object handler = Proxy.newProxyInstance(loader, new Class<?>[] { handlerClass }, (proxy, method, args) -> null);

			Field targetField = "CLIENTBOUND".equals(enumName(registration.flow())) ? clientboundHandlersField : serverboundHandlersField;
			@SuppressWarnings("unchecked")
			Map<Object, Map<Object, Object>> handlers = (Map<Object, Map<Object, Object>>) targetField.get(null);
			Map<Object, Object> protocolHandlers = handlers.get(registration.protocol());
			if (protocolHandlers == null) return;
			protocolHandlers.putIfAbsent(registration.id(), handler);
	}

	private static Object mirrorNeoPayloadIntoFabricRegistry(ClassLoader loader, Object id, Object protocol, Object packetFlow) {
		Object registration = neoRegistration(loader, id, protocol, packetFlow);
		if (registration == null) return null;
		Class<?> registryClass = load(loader, FABRIC_REGISTRY);
		if (registryClass == null) return null;

		String field = fabricRegistryField(protocol, packetFlow);
		if (field == null) return null;
		Object registry = staticField(registryClass, field);
		if (registry == null) return null;
		Object existing = invoke(registry, "get", id);
		if (existing != null) return existing;

		Object type = invokeNoArg(registration, "type");
		Object codec = invokeNoArg(registration, "codec");
		if (type == null || codec == null) return null;
		Object mirrored = invoke(registry, "register", type, codec);
		return mirrored != null ? mirrored : invoke(registry, "get", id);
	}

	private static Object neoRegistration(ClassLoader loader, Object id, Object protocol, Object packetFlow) {
		Class<?> registryClass = load(loader, NEO_NETWORK_REGISTRY);
		if (registryClass == null) return null;
		try {
			Field registrationsField = findField(registryClass, "PAYLOAD_REGISTRATIONS");
			if (registrationsField == null) return null;
			registrationsField.setAccessible(true);
			@SuppressWarnings("unchecked")
			Map<Object, Map<Object, Object>> registrations = (Map<Object, Map<Object, Object>>) registrationsField.get(null);
			Map<Object, Object> protocolMap = registrations.get(protocol);
			if (protocolMap == null) {
				probe(() -> "  neo: no registrations at all for protocol " + protocol + " (known: " + registrations.keySet() + ")");
				return null;
			}
			Object registration = protocolMap.get(id);
			if (registration == null) {
				probe(() -> "  neo: " + protocolMap.size() + " registration(s) under " + protocol + ", none for " + id);
				return null;
			}
			Object expectedFlow = invokeNoArg(registration, "flow");
			if (expectedFlow instanceof Optional<?> optional && optional.isPresent() && optional.get() != packetFlow) {
				probe(() -> "  neo: " + id + " is registered for flow " + optional.get() + ", asked for " + packetFlow);
				return null;
			}
			return registration;
		} catch (ReflectiveOperationException | RuntimeException e) {
			probe(() -> "  neo: registry lookup threw " + e);
			return null;
		}
	}

	private static final class CodecInvocationHandler implements InvocationHandler {
		private final CandidateSet candidates;

		private CodecInvocationHandler(CandidateSet candidates) {
			this.candidates = candidates;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			String name = method.getName();
			if ("encode".equals(name) && args != null && args.length == 2) {
				Object codec = candidates.selectEncode(args[1]);
				if (codec == null) throw new IllegalStateException("No custom payload codec for " + candidates.id);
				return method.invoke(codec, args);
			}
			if ("decode".equals(name) && args != null && args.length == 1) {
				Object codec = candidates.selectDecode();
				if (codec == null) throw new IllegalStateException("No custom payload codec for " + candidates.id);
				return method.invoke(codec, args);
			}
			if ("cast".equals(name) && (args == null || args.length == 0)) return proxy;
			if ("toString".equals(name) && (args == null || args.length == 0)) {
				return "ForbricCustomPayloadCodec[" + candidates.id + "]";
			}
			if ("hashCode".equals(name) && (args == null || args.length == 0)) return System.identityHashCode(proxy);
			if ("equals".equals(name) && args != null && args.length == 1) return proxy == args[0];
			throw new UnsupportedOperationException("Unsupported StreamCodec method: " + method);
		}
	}

	private static final class CandidateSet {
		private final Object id;
		private final Object local;
		private final Object fabricType;
		private final Object fabric;
		private final Object neo;
		private final Object forge;
		private final Object fallback;

		private CandidateSet(Object id, Object local, Object fabricEntry, Object fabric, Object neo, Object forge,
				Object fallback) {
			this.id = id;
			this.local = local;
			this.fabricType = typeAndCodecType(fabricEntry);
			this.fabric = fabric;
			this.neo = neo;
			this.forge = forge;
			this.fallback = fallback;
		}

		private Object selectEncode(Object payload) {
			if (payload != null) {
				String payloadClass = payload.getClass().getName();
				if (payloadClass.startsWith("net.neoforged.")) return firstNonNull(neo, local, fabric, fallback);
				if (payloadClass.startsWith("net.fabricmc.")) return firstNonNull(fabric, local, neo, fallback);
				// A ForgePayload is Forge's own envelope for every channel it owns, minecraft:register included
				// (ChannelListManager speaks it) — only Forge's codec knows how to write one.
				if (payloadClass.startsWith("net.minecraftforge.")) return firstNonNull(forge, fallback);
				Object payloadType = invokeNoArg(payload, "type");
				if (fabric != null && fabricType != null && fabricType.equals(payloadType)) return fabric;
				if (fabric != null && !payloadClass.startsWith("net.neoforged.")) return fabric;
			}
			return firstNonNull(local, neo, fabric, forge, fallback);
		}

		private Object selectDecode() {
			// Forge sits last on purpose: it claims minecraft:register and the c: channels too, and those must keep
			// decoding into the NeoForge/Fabric types the negotiator translates between. A channel only Forge knows
			// — a Forge mod's own — reaches it because nobody earlier has a codec for that id.
			if (isDinnerboneChannelRegistration(id)) {
				return firstNonNull(neo, fabric, local, forge, fallback);
			}
			if (isCommonNegotiation(id)) {
				return firstNonNull(neo, local, fabric, forge, fallback);
			}
			return firstNonNull(local, fabric, neo, forge, fallback);
		}
	}

	/**
	 * MinecraftForge's codec for {@code id}, when Forge has a channel by that name: {@code ForgeHooks
	 * .getCustomPayloadCodec}, the same provider Forge's own patched packets use as their fallback. Null for every
	 * other id — the provider would hand back a DiscardedPayload codec for those, which is not Forge's to decide.
	 */
	private static Object forgeCodec(Object id, Object packetFlow) {
		ClassLoader loader = loaderFor(id, packetFlow);
		Class<?> registry = load(loader, FORGE_NETWORK_REGISTRY);
		Class<?> hooks = load(loader, FORGE_HOOKS);
		if (registry == null || hooks == null || id == null) return null;
		Object target = invokeStatic(registry, "findTarget", id);
		if (target == null || target == INVOKE_FAILED) return null;
		int max = packetFlow != null && "CLIENTBOUND".equals(String.valueOf(packetFlow)) ? CLIENTBOUND_MAX_PAYLOAD : SERVERBOUND_MAX_PAYLOAD;
		Object codec = invokeStatic(hooks, "getCustomPayloadCodec", id, max);
		return codec == INVOKE_FAILED ? null : codec;
	}

	/**
	 * Called from the head of the client and server common listeners' {@code handleCustomPayload}, which NeoForge
	 * won in the merge and Forge's {@code ForgeHooks.onCustomPayload} therefore vanished from (it survived only in
	 * the play-phase server listener, which Forge overrides outright). Hands a {@code ForgePayload} — and nothing
	 * else: minecraft:register decoded as NeoForge's type must not be pushed into Forge's ChannelListManager — to
	 * Forge's dispatcher, and says whether it took it.
	 */
	public static boolean dispatchForgePayload(Object listener, Object packet) {
		if (listener == null || packet == null) return false;
		Object payload = invokeNoArg(packet, "payload");
		if (payload == null || !FORGE_PAYLOAD.equals(payload.getClass().getName())) return false;
		Object connection = fieldValue(listener, "connection");
		if (connection == null) return false;
		ClassLoader loader = loaderFor(payload);
		Class<?> hooks = load(loader, FORGE_HOOKS);
		Class<?> payloadApi = load(loader, "net.minecraft.network.protocol.common.custom.CustomPacketPayload");
		Class<?> connectionCls = load(loader, "net.minecraft.network.Connection");
		if (hooks == null || payloadApi == null || connectionCls == null) return false;
		try {
			Object handled = hooks.getMethod("onCustomPayload", payloadApi, connectionCls).invoke(null, payload, connection);
			probe(() -> "forge dispatch of " + payloadId(payload) + " on " + listener.getClass().getSimpleName() + " -> " + handled);
			if (!Boolean.TRUE.equals(handled)) {
				ForbricLog.warn("[Forbric] no MinecraftForge handler took " + payloadId(payload) + " on "
						+ listener.getClass().getSimpleName() + "; dropped, as Forge itself would");
			}
		} catch (Throwable t) {
			// Loud: a Forge mod's packet that its own dispatcher rejects is a bug in this bridge or in the mod.
			ForbricLog.warn("[Forbric] MinecraftForge's dispatcher threw on " + payloadId(payload) + " (" + listener.getClass().getSimpleName() + ")", unwrap(t));
		}
		// A ForgePayload is Forge's whether or not a handler took it. Falling through would hand it to NeoForge's
		// body, whose answer to a channel it never negotiated is to kick the client — a verdict Forge never gives.
		return true;
	}

	/**
	 * Called from the head of NeoForge's {@code NetworkRegistry.checkPacket} (both overloads): a ForgePayload is not
	 * NeoForge's to police. Its check compares the payload's channel with what NeoForge negotiated, and a Forge
	 * channel is not in that list by construction — so a Forge mod's client could not even SEND on its own channel
	 * ("Payload … may not be sent to the server!"). Says whether the packet carries a ForgePayload.
	 */
	public static boolean isForgePayloadPacket(Object packet) {
		if (packet == null) return false;
		Object payload = invokeNoArg(packet, "payload");
		return payload != null && FORGE_PAYLOAD.equals(payload.getClass().getName());
	}

	// --- MinecraftForge's login/configuration handshake ------------------------------------------------------------
	//
	// Forge's handshake is five configuration-phase tasks (register channels, mod versions, channel versions, sync
	// registries, sync configs) that tell each end what the other is running and push the server's SERVER-type
	// configs to the client. Three links of that chain lost the byte-merge to NeoForge, and the kernel's injected
	// prologues call the three hooks below to restore them. Everything here is a no-op without MinecraftForge on
	// board, and `-Dforbric.forgeHandshake=off` turns the whole thing off.

	private static final String FORGE_EVENT_FACTORY = "net.minecraftforge.event.ForgeEventFactory";
	private static final String FORGE_HOOKS_COMMON = "net.minecraftforge.common.ForgeHooks";
	private static final String FORGE_SYNC_REGISTRIES_TASK = "net.minecraftforge.network.tasks.SyncRegistriesTask";
	private static final boolean FORGE_HANDSHAKE = !"off".equals(System.getProperty("forbric.forgeHandshake"));
	private static final Map<Object, Boolean> FORGE_ACTIVATED = Collections.synchronizedMap(new WeakHashMap<>());
	private static final Map<Object, Boolean> FORGE_CONFIG_COMPLETED = Collections.synchronizedMap(new WeakHashMap<>());

	/**
	 * Head of {@code Connection.channelActive}, once the channel field is set: installs MinecraftForge's
	 * per-connection {@code ForgePacketHandler} on a CLIENT connection.
	 *
	 * <p>Forge does this from an {@code activationHandler} consumer its patch stores in {@code Connection.connect}
	 * and invokes here. The merge kept the field but lost both the store and the invocation, so on a client the
	 * {@code forge:handshake} channel attribute was never created — and every handler on Forge's configuration
	 * channel dereferences it, so the first handshake packet to arrive would have died on a null. The server side
	 * still installs it from {@code ServerLifecycleHooks.handleServerLogin}, which survived; doing it again here
	 * would inject Forge's vanilla-connection packet filter while the connection is still typed VANILLA (the
	 * intention has not been read yet), so this is deliberately client-only.
	 */
	public static void onConnectionActive(Object connection) {
		if (!FORGE_HANDSHAKE || connection == null) return;
		if (!"CLIENTBOUND".equals(String.valueOf(invokeNoArg(connection, "getReceiving")))) return;
		if (FORGE_ACTIVATED.putIfAbsent(connection, Boolean.TRUE) != null) return;
		Class<?> registry = load(loaderFor(connection), FORGE_NETWORK_REGISTRY);
		if (registry == null) return;
		try {
			registry.getMethod("onConnectionStart", connection.getClass()).invoke(null, connection);
			probe(() -> "installed MinecraftForge's per-connection handler on the client connection");
		} catch (ReflectiveOperationException | RuntimeException e) {
			ForbricLog.warn("[Forbric] could not start MinecraftForge's networking for this connection — its "
					+ "handshake will not run and Forge mods will see a vanilla peer", unwrap(e));
		}
	}

	/**
	 * Called where NeoForge queues its own early configuration tasks: adds MinecraftForge's, which the merged
	 * {@code startConfiguration}/{@code runConfiguration} (NeoForge's bodies) never gathers. Forge's own gate stays
	 * the gate — its handler adds nothing unless the connection was typed MODDED by the client's intention marker.
	 *
	 * <p>{@code SyncRegistriesTask} is dropped. The kernel already remaps the seventeen Forge-wrapped registries
	 * from NeoForge's snapshot (through Forge's own {@code injectSnapshot}), and Forge's task would apply a second
	 * snapshot over that result — a re-map of already-remapped ids, from a client half that blocks the network
	 * thread on the render thread while it does so. Everything else Forge gathers is kept, mod-added tasks included.
	 */
	public static void gatherForgeConfigurationTasks(Object listener) {
		if (!FORGE_HANDSHAKE || listener == null) return;
		Object connection = fieldValue(listener, "connection");
		Object tasks = fieldValue(listener, "configurationTasks");
		if (connection == null || !(tasks instanceof Collection<?>)) return;
		ClassLoader loader = loaderFor(listener, connection);
		Class<?> factory = load(loader, FORGE_EVENT_FACTORY);
		Class<?> syncRegistries = load(loader, FORGE_SYNC_REGISTRIES_TASK);
		if (factory == null) return;
		@SuppressWarnings("unchecked")
		Collection<Object> queue = (Collection<Object>) tasks;
		List<String> added = new ArrayList<>();
		List<String> skipped = new ArrayList<>();
		Consumer<Object> sink = task -> {
			if (task == null) return;
			if (syncRegistries != null && syncRegistries.isInstance(task)) {
				skipped.add(task.getClass().getSimpleName());
				return;
			}
			queue.add(task);
			added.add(task.getClass().getSimpleName());
		};
		try {
			factory.getMethod("gatherLoginConfigTasks", connection.getClass(), Consumer.class)
					.invoke(null, connection, sink);
		} catch (ReflectiveOperationException | RuntimeException e) {
			ForbricLog.warn("[Forbric] could not gather MinecraftForge's configuration tasks — its handshake will not "
					+ "run, so Forge mods see a vanilla peer and their server configs are not synced", unwrap(e));
			return;
		}
		if (added.isEmpty() && skipped.isEmpty()) return;
		ForbricLog.info("[Forbric/Net] queued %d MinecraftForge configuration task(s) %s%s", added.size(), added,
				skipped.isEmpty() ? "" : " (the kernel already synced the registries, so it skipped " + skipped + ")");
	}

	/**
	 * End of the client's {@code handleConfigurationFinished}, before it tells the server it is entering play:
	 * MinecraftForge's own "configuration complete" hook, which decides from the connection's type whether the
	 * server is modded and, when it is not, loads every Forge mod's default SERVER config so their values are
	 * readable in-world.
	 *
	 * <p>Forge reaches it from the tail of the code-of-conduct handler, which vanilla only calls when the server
	 * configures a code of conduct — so on almost every connection it never ran. That path is left alone; this one
	 * is deduplicated per connection so a server that does send one does not load the defaults twice.
	 */
	public static void onClientConfigurationFinished(Object listener) {
		if (!FORGE_HANDSHAKE || listener == null) return;
		Object connection = fieldValue(listener, "connection");
		if (connection == null) return;
		if (FORGE_CONFIG_COMPLETED.putIfAbsent(connection, Boolean.TRUE) != null) return;
		Class<?> hooks = load(loaderFor(listener, connection), FORGE_HOOKS_COMMON);
		if (hooks == null) return;
		try {
			hooks.getMethod("handleClientConfigurationComplete", connection.getClass()).invoke(null, connection);
		} catch (ReflectiveOperationException | RuntimeException e) {
			ForbricLog.warn("[Forbric] MinecraftForge's client-side configuration-complete hook failed — on a server "
					+ "without Forge its mods keep unloaded server configs", unwrap(e));
		}
	}

	/**
	 * Called from the head of NeoForge's {@code NetworkRegistry.onMinecraftRegister}/{@code onMinecraftUnregister}
	 * — the one place every peer channel declaration passes on this base, with or without fabric-api on board.
	 * Keeps Forge's per-connection channel set in step, and announces the local Forge channels back the first
	 * time the peer declares.
	 */
	public static void onNeoChannelRegistration(Object connection, Object channels, boolean register) {
		if (connection == null || !(channels instanceof Collection<?> set)) return;
		try {
			syncForgeChannels(connection, register, set);
			// Inside the Fabric-addon arbitration the declaration is sent later, after Fabric's own register — see
			// handleFabricChannelRegistrationAddon. Without fabric-api this hook is the only place, so send it now.
			if (register && !DECLARATION_DEFERRED.get()) declareForgeChannels(connection);
		} catch (RuntimeException e) {
			ForbricLog.warn("[Forbric] could not keep MinecraftForge's channel list in step", e);
		}
	}

	/** Set while the Fabric-addon arbitration tells NeoForge's half, so the Forge declaration waits for Fabric's. */
	private static final ThreadLocal<Boolean> DECLARATION_DEFERRED = ThreadLocal.withInitial(() -> Boolean.FALSE);

	/**
	 * Forge's per-connection view of what the peer listens on, kept from the same minecraft:register traffic the
	 * negotiator already translates for NeoForge and Fabric — Forge's own listener for that channel never runs here.
	 * {@code Channel.isRemotePresent} reads this set; a mod that checks it before sending would otherwise never send.
	 */
	private static void syncForgeChannels(Object connection, boolean register, Collection<?> channels) {
		ClassLoader loader = loaderFor(connection);
		Class<?> contextCls = load(loader, FORGE_NETWORK_CONTEXT);
		if (contextCls == null) return;
		Object context = invokeStatic(contextCls, "get", connection);
		if (context == null || context == INVOKE_FAILED) return;
		Object remote = fieldValue(context, "remoteChannels");
		if (!(remote instanceof Collection<?>)) return;
		@SuppressWarnings("unchecked")
		Collection<Object> set = (Collection<Object>) remote;
		if (register) set.addAll(channels);
		else set.removeAll(channels);
	}

	/**
	 * Announces the local Forge channels to the peer, once per connection, the first time the peer declares its own:
	 * {@code ChannelListManager.addChannels(connection)} sends a minecraft:register naming every channel Forge
	 * knows, exactly as Forge's RegisterChannelsTask would in a configuration phase the kernel does not run. A peer
	 * without Forge reads it as an ordinary register.
	 */
	private static void declareForgeChannels(Object connection) {
		if (connection == null || FORGE_CHANNELS_DECLARED.putIfAbsent(connection, Boolean.TRUE) != null) return;
		ClassLoader loader = loaderFor(connection);
		Class<?> manager = load(loader, FORGE_CHANNEL_LIST);
		Class<?> connectionCls = load(loader, "net.minecraft.network.Connection");
		if (manager == null || connectionCls == null) return;
		try {
			manager.getMethod("addChannels", connectionCls).invoke(null, connection);
			probe(() -> "declared Forge's channels to the peer");
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric] could not declare MinecraftForge's channels to the peer — a mod that asks "
					+ "Channel.isRemotePresent before sending will think they are absent", unwrap(t));
		}
	}

	private static Boolean handleFabricCommonNegotiationAddon(Object addon, Object payload) {
		if (payload == null) return null;
		String id = payloadId(payload);
		if (!isCommonNegotiation(id)) return null;
		if (load(loaderFor(addon, payload), NEO_NETWORK_REGISTRY) == null) return null;

		String payloadClass = payload.getClass().getName();
		if ("c:version".equals(id)) {
			int version = commonVersion(payload);
			if (version > 0) invoke(addon, "onCommonVersionPacket", version);
			Object listener = commonPacketListener(addon);
			Object neoPayload = NEO_COMMON_VERSION_PAYLOAD.equals(payloadClass)
					? payload
					: createNeoCommonVersionPayload(payload);
			if (listener != null && neoPayload != null) {
				invokeNeoNetworkRegistry("checkCommonVersion", listener, neoPayload);
			}
			return Boolean.TRUE;
		}

		if ("c:register".equals(id)) {
			Object fabricPayload = FABRIC_COMMON_REGISTER_PAYLOAD.equals(payloadClass)
					? payload
					: createFabricCommonRegisterPayload(payload);
			if (fabricPayload != null) invoke(addon, "onCommonRegisterPacket", fabricPayload);
			Object listener = commonPacketListener(addon);
			Object neoPayload = NEO_COMMON_REGISTER_PAYLOAD.equals(payloadClass)
					? payload
					: createNeoCommonRegisterPayload(addon, payload);
			if (listener != null && neoPayload != null) {
				invokeNeoNetworkRegistry("onCommonRegister", listener, neoPayload);
			}
			return Boolean.TRUE;
		}

		return null;
	}

	private static Object fabricTypeAndCodec(Object id, Object protocol, Object packetFlow) {
		ClassLoader loader = loaderFor(id, protocol, packetFlow);
		Class<?> registryClass = load(loader, FABRIC_REGISTRY);
		if (registryClass == null) return null;

		String field = fabricRegistryField(protocol, packetFlow);
		if (field == null) return null;
		Object registry = staticField(registryClass, field);
		if (registry == null) return null;
		Object entry = invoke(registry, "get", id);
		return entry != null ? entry : mirrorNeoPayloadIntoFabricRegistry(loader, id, protocol, packetFlow);
	}

	private static String fabricRegistryField(Object protocol, Object packetFlow) {
		String protocolName = enumName(protocol);
		String flowName = enumName(packetFlow);
		if ("CONFIGURATION".equals(protocolName) && "CLIENTBOUND".equals(flowName)) return "CLIENTBOUND_CONFIGURATION";
		if ("CONFIGURATION".equals(protocolName) && "SERVERBOUND".equals(flowName)) return "SERVERBOUND_CONFIGURATION";
		if ("PLAY".equals(protocolName) && "CLIENTBOUND".equals(flowName)) return "CLIENTBOUND_PLAY";
		if ("PLAY".equals(protocolName) && "SERVERBOUND".equals(flowName)) return "SERVERBOUND_PLAY";
		return null;
	}

	private static Object neoCodec(Object id, Object protocol, Object packetFlow) {
		ClassLoader loader = loaderFor(id, protocol, packetFlow);
		Class<?> registry = load(loader, NEO_NETWORK_REGISTRY);
		if (registry == null) return null;
		Object builtin = neoBuiltinCodec(registry, id);
		if (builtin != null) return builtin;
		Object registration = neoRegistration(loader, id, protocol, packetFlow);
		return registration == null ? null : invokeNoArg(registration, "codec");
	}

	private static Object neoBuiltinCodec(Class<?> registry, Object id) {
		try {
			Field builtinsField = findField(registry, "BUILTIN_PAYLOADS");
			if (builtinsField == null) return null;
			builtinsField.setAccessible(true);
			Object builtins = builtinsField.get(null);
			return builtins instanceof Map<?, ?> map ? map.get(id) : null;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	private static Object fallbackCodec(Object fallback, Object id) {
		if (fallback == null) return null;
		return invoke(fallback, "create", id);
	}

	private static Object typeAndCodecCodec(Object typeAndCodec) {
		return typeAndCodec == null ? null : invokeNoArg(typeAndCodec, "codec");
	}

	private static Object typeAndCodecType(Object typeAndCodec) {
		return typeAndCodec == null ? null : invokeNoArg(typeAndCodec, "type");
	}

	private static Registration registration(Object payload) {
		if (payload == null) return null;
		String className = payload.getClass().getName();
		String id = payloadId(payload);
		boolean register = "minecraft:register".equals(id);
		boolean unregister = "minecraft:unregister".equals(id);
		if (!register && !unregister) return null;

		Object channels;
		if (FABRIC_REGISTRATION_PAYLOAD.equals(className)) {
			channels = invokeNoArg(payload, "channels");
		} else if (NEO_REGISTER_PAYLOAD.equals(className)) {
			channels = invokeNoArg(payload, "newChannels");
			register = true;
		} else if (NEO_UNREGISTER_PAYLOAD.equals(className)) {
			channels = invokeNoArg(payload, "forgottenChannels");
			register = false;
		} else {
			return null;
		}
		if (!(channels instanceof Collection<?> collection)) return null;
		return new Registration(register, collection);
	}

	private static int commonVersion(Object payload) {
		Object versions = invokeNoArg(payload, "versions");
		if (versions instanceof int[] ints) {
			for (int version : ints) if (version == 1) return 1;
			return ints.length == 0 ? -1 : ints[0];
		}
		if (versions instanceof Collection<?> collection) {
			for (Object version : collection) {
				if (version instanceof Number number && number.intValue() == 1) return 1;
			}
			for (Object version : collection) {
				if (version instanceof Number number) return number.intValue();
			}
		}
		return -1;
	}

	private static Object createNeoCommonVersionPayload(Object payload) {
		ClassLoader loader = loaderFor(payload);
		Class<?> payloadClass = load(loader, NEO_COMMON_VERSION_PAYLOAD);
		if (payloadClass == null) return null;
		Object versions = invokeNoArg(payload, "versions");
		List<Integer> list = new ArrayList<>();
		if (versions instanceof int[] ints) {
			for (int version : ints) list.add(version);
		} else if (versions instanceof Collection<?> collection) {
			for (Object version : collection) {
				if (version instanceof Number number) list.add(number.intValue());
			}
		}
		try {
			for (java.lang.reflect.Constructor<?> ctor : payloadClass.getConstructors()) {
				Class<?>[] params = ctor.getParameterTypes();
				if (params.length == 1 && List.class.isAssignableFrom(params[0])) {
					return ctor.newInstance(list);
				}
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			ForbricLog.warn("[Forbric] could not synthesize NeoForge common-version payload", e);
		}
		return null;
	}

	private static Object createFabricCommonRegisterPayload(Object payload) {
		ClassLoader loader = loaderFor(payload);
		Class<?> payloadClass = load(loader, FABRIC_COMMON_REGISTER_PAYLOAD);
		if (payloadClass == null) return null;
		Object channels = invokeNoArg(payload, "channels");
		if (!(channels instanceof Set<?> set)) return null;
		int version = intValue(invokeNoArg(payload, "version"), 1);
		String protocol = protocolId(invokeNoArg(payload, "protocol"));
		try {
			for (java.lang.reflect.Constructor<?> ctor : payloadClass.getConstructors()) {
				Class<?>[] params = ctor.getParameterTypes();
				if (params.length == 3 && params[0] == int.class && params[1] == String.class
						&& Set.class.isAssignableFrom(params[2])) {
					return ctor.newInstance(version, protocol, set);
				}
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			ForbricLog.warn("[Forbric] could not synthesize Fabric common-register payload", e);
		}
		return null;
	}

	private static Object createNeoCommonRegisterPayload(Object addon, Object payload) {
		ClassLoader loader = loaderFor(addon, payload);
		Class<?> payloadClass = load(loader, NEO_COMMON_REGISTER_PAYLOAD);
		if (payloadClass == null) return null;
		Object channels = invokeNoArg(payload, "channels");
		if (!(channels instanceof Set<?> set)) return null;
		int version = intValue(invokeNoArg(payload, "version"), 1);
		Object protocol = protocolById(loader, protocolId(invokeNoArg(payload, "protocol")));
		if (protocol == null) return null;
		try {
			for (java.lang.reflect.Constructor<?> ctor : payloadClass.getConstructors()) {
				Class<?>[] params = ctor.getParameterTypes();
				if (params.length == 3 && params[0] == int.class && params[1].isInstance(protocol)
						&& Set.class.isAssignableFrom(params[2])) {
					return ctor.newInstance(version, protocol, set);
				}
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			ForbricLog.warn("[Forbric] could not synthesize NeoForge common-register payload", e);
		}
		return null;
	}

	private static Object createFabricRegistrationPayload(Object payload, boolean register, Collection<?> channels) {
		ClassLoader loader = loaderFor(payload);
		Class<?> payloadClass = load(loader, FABRIC_REGISTRATION_PAYLOAD);
		if (payloadClass == null) return null;
		try {
			Object type = staticField(payloadClass, register ? "REGISTER" : "UNREGISTER");
			for (java.lang.reflect.Constructor<?> ctor : payloadClass.getConstructors()) {
				Class<?>[] params = ctor.getParameterTypes();
				if (params.length != 2 || !List.class.isAssignableFrom(params[1])) continue;
				return ctor.newInstance(type, new ArrayList<>(channels));
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			ForbricLog.warn("[Forbric] could not synthesize Fabric channel-registration payload", e);
		}
		return null;
	}

	private static boolean invokeReceiveRegistration(Object addon, boolean register, Object fabricPayload) {
		Method method = findMethod(addon.getClass(), "receiveRegistration", boolean.class, fabricPayload.getClass());
		if (method == null) {
			// Silent before. This is the single point where the client's whole Fabric channel declaration is
			// produced — receiveRegistration is the only caller of sendInitialChannelRegistrationPacket — so a
			// quiet miss here reads downstream as "the server thinks you have no Fabric API".
			ForbricLog.warn("[Forbric] no receiveRegistration(boolean," + fabricPayload.getClass().getName()
					+ ") on " + addon.getClass().getName() + "; Fabric's channel set was left untouched");
			return false;
		}
		try {
			method.setAccessible(true);
			method.invoke(addon, register, fabricPayload);
			return true;
		} catch (ReflectiveOperationException | RuntimeException e) {
			ForbricLog.warn("[Forbric] could not mirror channel-registration payload into Fabric networking", unwrap(e));
			// The loader's own log is not always wired on a dedicated server; under -Dforbric.debug say it here too.
			probe(() -> "  receiveRegistration threw: " + stackTraceOf(unwrap(e)));
			return false;
		}
	}

	private static String stackTraceOf(Throwable t) {
		java.io.StringWriter out = new java.io.StringWriter();
		t.printStackTrace(new java.io.PrintWriter(out));
		return out.toString();
	}

	private static void syncNeoChannels(Object connection, boolean register, Collection<?> channels) {
		ClassLoader loader = loaderFor(connection);
		Class<?> registry = load(loader, NEO_NETWORK_REGISTRY);
		if (registry == null) return;
		Set<Object> set = new LinkedHashSet<>(channels);
		String methodName = register ? "onMinecraftRegister" : "onMinecraftUnregister";
		for (Method method : registry.getMethods()) {
			if (!method.getName().equals(methodName) || method.getParameterCount() != 2) continue;
			if (!method.getParameterTypes()[0].isInstance(connection)) continue;
			if (!Collection.class.isAssignableFrom(method.getParameterTypes()[1])) continue;
			try {
				method.invoke(null, connection, set);
				return;
			} catch (ReflectiveOperationException | RuntimeException e) {
				ForbricLog.warn("[Forbric] could not mirror channel-registration payload into NeoForge networking", e);
				return;
			}
		}
	}

	private static Object commonPacketListener(Object addon) {
		Object listener = fieldValue(addon, "listener");
		return listener != null ? listener : fieldValue(addon, "handler");
	}

	private static void invokeNeoNetworkRegistry(String name, Object listener, Object payload) {
		Class<?> registry = load(loaderFor(listener, payload), NEO_NETWORK_REGISTRY);
		if (registry == null) return;
		Object result = invokeStatic(registry, name, listener, payload);
		if (result == INVOKE_FAILED) {
			ForbricLog.warn("[Forbric] could not mirror common-networking payload into NeoForge: " + name);
		}
	}

	private static boolean isDinnerboneChannelRegistration(Object id) {
		String s = String.valueOf(id);
		return "minecraft:register".equals(s) || "minecraft:unregister".equals(s);
	}

	private static boolean isCommonNegotiation(Object id) {
		String s = String.valueOf(id);
		return "c:version".equals(s) || "c:register".equals(s);
	}

	private static boolean equivalentCommonTask(String a, String b) {
		if (a == null || b == null) return false;
		return ("c:version".equals(a) && "neoforge:common_version".equals(b))
				|| ("neoforge:common_version".equals(a) && "c:version".equals(b))
				|| ("c:register".equals(a) && "neoforge:common_register".equals(b))
				|| ("neoforge:common_register".equals(a) && "c:register".equals(b));
	}

	private static Object uniqueCodec(Object... codecs) {
		Object found = null;
		for (Object codec : codecs) {
			if (codec == null) continue;
			if (found == null) {
				found = codec;
			} else if (found != codec && !found.equals(codec)) {
				return null;
			}
		}
		return found;
	}

	private static Object firstNonNull(Object... values) {
		for (Object value : values) if (value != null) return value;
		return null;
	}

	private static String payloadId(Object payload) {
		Object type = invokeNoArg(payload, "type");
		Object id = type == null ? null : invokeNoArg(type, "id");
		return String.valueOf(id);
	}

	private static String taskId(Object type) {
		Object id = invokeNoArg(type, "id");
		return id == null ? null : String.valueOf(id);
	}

	private static int intValue(Object value, int fallback) {
		return value instanceof Number number ? number.intValue() : fallback;
	}

	private static String protocolId(Object protocol) {
		if (protocol == null) return "configuration";
		Object id = invokeNoArg(protocol, "id");
		if (id instanceof String s) return s;
		return String.valueOf(protocol).toLowerCase(java.util.Locale.ROOT);
	}

	private static Object protocolById(ClassLoader loader, String id) {
		Class<?> protocolClass = load(loader, "net.minecraft.network.ConnectionProtocol");
		if (protocolClass == null) return null;
		Object values = invokeStatic(protocolClass, "values");
		if (values == INVOKE_FAILED || values == null || !values.getClass().isArray()) return null;
		int length = java.lang.reflect.Array.getLength(values);
		for (int i = 0; i < length; i++) {
			Object value = java.lang.reflect.Array.get(values, i);
			if (id.equals(protocolId(value))) return value;
		}
		return null;
	}

	private static String enumName(Object value) {
		Object name = invokeNoArg(value, "name");
		return name instanceof String ? (String) name : String.valueOf(value);
	}

	private static Object staticField(Class<?> owner, String name) {
		try {
			Field field = owner.getField(name);
			field.setAccessible(true);
			return field.get(null);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	private static Object fieldValue(Object owner, String name) {
		for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass()) {
			try {
				Field field = c.getDeclaredField(name);
				field.setAccessible(true);
				return field.get(owner);
			} catch (NoSuchFieldException ignored) {
				// try superclass
			} catch (ReflectiveOperationException | RuntimeException e) {
				return null;
			}
		}
		return null;
	}

	private static Field findField(Class<?> owner, String name) {
		for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
			try {
				return c.getDeclaredField(name);
			} catch (NoSuchFieldException ignored) {
				// try superclass
			}
		}
		return null;
	}

	private static Object invokeNoArg(Object target, String name) {
		return target == null ? null : invoke(target, name);
	}

	private static Object invoke(Object target, String name, Object... args) {
		if (target == null) return null;
		Method method = findMethod(target.getClass(), name, classes(args));
		if (method == null) method = findCompatibleMethod(target.getClass(), name, args);
		if (method == null) return null;
		try {
			method.setAccessible(true);
			return method.invoke(target, args);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	private static final Object INVOKE_FAILED = new Object();

	private static Object invokeStatic(Class<?> owner, String name, Object... args) {
		Method method = findMethod(owner, name, classes(args));
		if (method == null) method = findCompatibleMethod(owner, name, args);
		if (method == null) return INVOKE_FAILED;
		try {
			method.setAccessible(true);
			return method.invoke(null, args);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return INVOKE_FAILED;
		}
	}

	private static Method findMethod(Class<?> owner, String name, Class<?>... params) {
		for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
			try {
				return c.getDeclaredMethod(name, params);
			} catch (NoSuchMethodException ignored) {
				// try superclass
			}
		}
		for (Class<?> itf : owner.getInterfaces()) {
			try {
				return itf.getMethod(name, params);
			} catch (NoSuchMethodException ignored) {
				// try next interface
			}
		}
		return null;
	}

	private static Method findCompatibleMethod(Class<?> owner, String name, Object[] args) {
		for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
			for (Method method : c.getDeclaredMethods()) {
				if (compatible(method, name, args)) return method;
			}
		}
		for (Class<?> itf : owner.getInterfaces()) {
			for (Method method : itf.getMethods()) {
				if (compatible(method, name, args)) return method;
			}
		}
		return null;
	}

	private static Throwable unwrap(Throwable t) {
		while (t instanceof java.lang.reflect.InvocationTargetException invocation && invocation.getCause() != null) {
			t = invocation.getCause();
		}
		return t;
	}

	private static boolean compatible(Method method, String name, Object[] args) {
		if (!method.getName().equals(name) || method.getParameterCount() != args.length) return false;
		Class<?>[] params = method.getParameterTypes();
		for (int i = 0; i < params.length; i++) {
			if (args[i] != null && !box(params[i]).isInstance(args[i])) return false;
		}
		return true;
	}

	private static Class<?>[] classes(Object[] args) {
		Class<?>[] out = new Class<?>[args.length];
		for (int i = 0; i < args.length; i++) out[i] = args[i] == null ? Object.class : args[i].getClass();
		return out;
	}

	private static Class<?> box(Class<?> type) {
		if (!type.isPrimitive()) return type;
		if (type == boolean.class) return Boolean.class;
		if (type == byte.class) return Byte.class;
		if (type == char.class) return Character.class;
		if (type == short.class) return Short.class;
		if (type == int.class) return Integer.class;
		if (type == long.class) return Long.class;
		if (type == float.class) return Float.class;
		if (type == double.class) return Double.class;
		return Void.class;
	}

	private static Class<?> load(ClassLoader loader, String name) {
		try {
			return Class.forName(name, false, loader);
		} catch (ClassNotFoundException | LinkageError e) {
			return null;
		}
	}

	private static ClassLoader loaderFor(Object... values) {
		for (Object value : values) {
			if (value == null) continue;
			ClassLoader loader = value.getClass().getClassLoader();
			if (loader != null) return loader;
		}
		ClassLoader context = Thread.currentThread().getContextClassLoader();
		return context != null ? context : PayloadInterop.class.getClassLoader();
	}

	private static final class Registration {
		private final boolean register;
		private final Collection<?> channels;

		private Registration(boolean register, Collection<?> channels) {
			this.register = register;
			this.channels = channels;
		}
	}
}
