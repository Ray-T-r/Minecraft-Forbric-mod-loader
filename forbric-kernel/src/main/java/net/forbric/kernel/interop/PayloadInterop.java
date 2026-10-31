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
import java.util.WeakHashMap;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.classloading.LoaderProbePolicy;
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;

/**
 * Runtime bridge for the Fabric/NeoForge custom-payload codec path.
 *
 * <p>The Minecraft base keeps NeoForge's four-argument {@code CustomPacketPayload.codec(...)} plumbing, but
 * Fabric API registers payload codecs in its own side/protocol registries. The vanilla/Neo lookup is id-only, which
 * is unsafe when two loader APIs intentionally use the same vanilla channel id with different Java payload classes
 * ({@code minecraft:register}/{@code minecraft:unregister}). This helper returns a tiny {@code StreamCodec} proxy
 * that chooses the concrete codec by runtime payload type for encode, and by available protocol registry for decode.
 *
 * <p>No Minecraft, Fabric or NeoForge type is referenced directly here. The loader core is parent-loaded, so
 * all game/loader API interaction is reflective against the Knot class loader that owns the live classes.
 */
public final class PayloadInterop {
	private static final String NEO_NETWORK_REGISTRY = ForeignType.NETWORK_REGISTRY.binary(Ecosystem.NEOFORGE);
	private static final String NEO_REGISTER_PAYLOAD = "net.neoforged.neoforge.network.payload.MinecraftRegisterPayload";
	private static final String NEO_UNREGISTER_PAYLOAD = "net.neoforged.neoforge.network.payload.MinecraftUnregisterPayload";
	private static final String NEO_COMMON_VERSION_PAYLOAD = "net.neoforged.neoforge.network.payload.CommonVersionPayload";
	private static final String NEO_COMMON_REGISTER_PAYLOAD = "net.neoforged.neoforge.network.payload.CommonRegisterPayload";
	private static final String NEO_PAYLOAD_REGISTRATION = "net.neoforged.neoforge.network.registration.PayloadRegistration";
	private static final String CLIENTBOUND_CUSTOM_PAYLOAD_PACKET = "net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket";
	private static final String SERVERBOUND_CUSTOM_PAYLOAD_PACKET = "net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket";
	private static final String FORBRIC_MIRROR_VERSION = "forbric-bridge";
	private static final Map<ClassLoader, Boolean> MIRRORED_LOADERS = Collections.synchronizedMap(new WeakHashMap<>());

	private PayloadInterop() {
	}

	public static void bootstrapMirrors(ClassLoader cl) {
		ClassLoader loader = cl != null ? cl : loaderFor();

		// Lock-free fast path. This is called at the head of the codec lookup and of the channel-registration
		// handler, so it is on the packet path — and after the first call for a loader it has nothing to do but
		// still took a monitor every time, on the Netty threads. There is one game loader in practice, so a
		// single volatile read settles it; anything else falls through to the map exactly as before, and a stale
		// read costs one extra trip down the slow path, which is idempotent.
		if (loader == mirroredLoader) return;

		synchronized (MIRRORED_LOADERS) {
			if (MIRRORED_LOADERS.putIfAbsent(loader, Boolean.TRUE) != null) {
				mirroredLoader = loader;
				return;
			}
		}

		try {
			mirrorMergedPayloadRegistries(loader);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric] could not bootstrap merged custom-payload mirrors");
		} finally {
			// Published even when the mirroring threw: the map already says "done for this loader", so the fast
			// path and the slow path must agree, or every later packet pays the monitor for a retry that the map
			// will refuse anyway.
			mirroredLoader = loader;
		}
	}

	/**
	 * The loader {@link #bootstrapMirrors} last completed for, read without a lock.
	 *
	 * <p>Not a cache of the work — {@link #MIRRORED_LOADERS} is still what decides whether it runs. This only
	 * lets the common case answer "already done" without taking a monitor on a packet thread.
	 */
	private static volatile ClassLoader mirroredLoader;

	/**
	 * Called from bytecode patched into merged {@code CustomPacketPayload$1$forbricneo.findCodec(...)}.
	 *
	 * @return an object implementing the live {@code net.minecraft.network.codec.StreamCodec} interface.
	 */
	public static Object findCodec(Map<?, ?> localCodecs, Object id, Object protocol, Object packetFlow, Object fallback) {
		return codec(localCodecs, id, protocol, packetFlow, fallback, null);
	}

	/** Final return arbitration: guest lookup/callbacks have already run, and their chosen codec remains a candidate. */
	public static Object afterGuestCodec(Map<?, ?> localCodecs, Object id, Object protocol, Object packetFlow, Object fallback,
			Object chosen) {
		if (chosen != null && Proxy.isProxyClass(chosen.getClass())) {
			InvocationHandler handler = Proxy.getInvocationHandler(chosen);
			// An Operation may intentionally replace its id, receiver, flow or protocol. Its already unified result
			// owns that complete context; the outer invocation must not replace it with the original inputs.
			if (handler instanceof CodecInvocationHandler) return chosen;
		}
		return codec(localCodecs, id, protocol, packetFlow, fallback, chosen);
	}

	private static Object codec(Map<?, ?> localCodecs, Object id, Object protocol, Object packetFlow, Object fallback, Object chosen) {
		bootstrapMirrors(loaderFor(id, protocol, packetFlow));
		Object local = localCodecs != null ? localCodecs.get(id) : null;
		Object fabricEntry = fabricTypeAndCodec(id, protocol, packetFlow);
		Object fabric = typeAndCodecCodec(fabricEntry);
		Object neo = neoCodec(id, protocol, packetFlow);
		Object fallbackCodec = fallbackCodec(fallback, id);

		Object direct = uniqueCodec(local, fabric, neo, chosen, fallbackCodec);
		probe(() -> "codec for " + id + " " + protocol + "/" + packetFlow + ": local=" + (local != null)
				+ " fabric=" + (fabric != null) + " neo=" + (neo != null)
				+ " fallback=" + (fallbackCodec != null)
				+ (direct != null ? " -> unified single candidate " + direct.getClass().getSimpleName() : " -> unified candidates"));

		ClassLoader loader = loaderFor(id, protocol, packetFlow);
		Class<?> streamCodec = load(loader, "net.minecraft.network.codec.StreamCodec");
		if (streamCodec == null) {
			return direct != null ? direct : firstNonNull(chosen, local, fabric, neo, fallbackCodec);
		}

		CandidateSet candidates = new CandidateSet(id, local, fabricEntry, fabric, neo, chosen, fallbackCodec);
		return Proxy.newProxyInstance(loader, new Class<?>[] { streamCodec }, new CodecInvocationHandler(candidates));
	}

	/** What a payload handed to a Fabric receiver leaves to do once that receiver is done, on this thread. */
	private static final class AfterReceiver {
		final Object receiver;
		final Object handed;
		/** How many {@code handle}s of the same receiver the handed payload has gone on into (a subclass calling super's). */
		int nested;
		Runnable neoForgeHalf;
		boolean protocolMovesAreNeoForges;

		AfterReceiver(Object receiver, Object handed) {
			this.receiver = receiver;
			this.handed = handed;
		}
	}

	private static final ThreadLocal<AfterReceiver> AFTER_RECEIVER = new ThreadLocal<>();

	/**
	 * Called at the head of a Fabric payload receiver's {@code handle(CustomPacketPayload)} — a class implementing fabric-api's
	 * public {@code PacketSender} that declares that method ({@code CommonNetworkInteropInjector}). Returns the payload
	 * the receiver goes on with.
	 *
	 * <p>Fabric and NeoForge both speak the peer's channel declarations ({@code minecraft:register}/{@code unregister}) and
	 * the common-networking negotiation ({@code c:version}/{@code c:register}), each with its own payload class for the
	 * same wire format; one codec decoded the packet, so this receiver can be handed NeoForge's class. The receiver gets the
	 * payload as Fabric's own codec reads the same bytes ({@link WirePayloads}) and handles it with its own code, as on
	 * Fabric; NeoForge is told with the payload as NeoForge's codec reads them. Nothing here names Fabric's payload classes
	 * or its receiver's members.
	 *
	 * <p>The order of the halves is a wire contract on BOTH ends:
	 * <ul>
	 *   <li>A channel declaration: NeoForge's bookkeeping first. On the server,
	 *       Fabric's registration handling runs its configuration start synchronously, and Fabric's registry-sync task
	 *       sends {@code fabric:registry/sync} from inside it; NeoForge's {@code checkPacket} vetoes any channel the client
	 *       has not declared, and it learns the client's channels from exactly this {@code onMinecraftRegister}. Then
	 *       Fabric's own handling, which on the client sends the client's own {@code minecraft:register}.</li>
	 *   <li>The common negotiation: both ecosystems queue the server's tasks for it and answer it on the client, and the two
	 *       answers must not both go out, nor a task be finished twice. Fabric's receiver runs first, for Fabric's own state
	 *       (the negotiated version, the peer's channels); the moves it makes on the wire — finishing a common-networking
	 *       task, sending a {@code c:version}/{@code c:register} answer through its {@code PacketSender} — stand aside while
	 *       it runs ({@link #fabricSendStandsAside}, {@link #finishEquivalentCommonTask}). NeoForge's handler then runs once,
	 *       when the receiver is done, and makes them: its answer carries every channel NeoForge knows, the Fabric ones the
	 *       kernel mirrored into it included, and its task finish is the one the server's queue expects.</li>
	 * </ul>
	 */
	public static Object fabricInbound(Object receiver, Object payload) {
		AfterReceiver going = AFTER_RECEIVER.get();
		if (going != null && going.receiver == receiver && going.handed == payload) {
			// The receiver's own override handing the payload on to the handle it overrides: already arbitrated.
			going.nested++;
			return payload;
		}
		AFTER_RECEIVER.remove();
		if (payload == null) return payload;
		String id = payloadId(payload);
		probe(() -> "Fabric receiver " + simpleName(receiver) + " <- " + id + " [" + simpleName(payload) + "]");
		bootstrapMirrors(loaderFor(receiver, payload));
		boolean registration = isDinnerboneChannelRegistration(id), negotiation = isCommonNegotiation(id);
		if (!registration && !negotiation) {
			if (ForbricLog.debugEnabled()) describeFrozenRegistrySnapshot(payload);
			return payload;
		}
		Object connection = valueOfType(receiver, "net.minecraft.network.Connection");
		Object listener = connection == null ? null : invokeNoArg(connection, "getPacketListener");
		Object protocol = listener == null ? null : invokeNoArg(listener, "protocol");
		Object flow = connection == null ? null : invokeNoArg(connection, "getReceiving");
		if (connection == null || protocol == null || flow == null) {
			probe(() -> "  no connection phase on the receiver; left to its own body as handed");
			return payload;
		}
		Object idValue = invokeNoArg(invokeNoArg(payload, "type"), "id");
		Object fabricCodec = typeAndCodecCodec(fabricTypeAndCodecOnly(idValue, protocol, flow));
		Object neoCodec = neoCodec(idValue, protocol, flow);
		Object neo = WirePayloads.decodedBy(neoCodec, payload, java.util.Arrays.asList(fabricCodec));
		Object fabric = WirePayloads.decodedBy(fabricCodec, payload, java.util.Arrays.asList(neoCodec));
		probe(() -> "  as NeoForge reads it: " + simpleName(neo) + "; as Fabric reads it: " + simpleName(fabric));
		if (fabric == null) {
			probe(() -> "  Fabric's codec could not read it; the receiver gets it as handed, NeoForge is not told here");
			return payload;
		}
		AfterReceiver after = new AfterReceiver(receiver, fabric);
		if (registration) {
			Registration declared = registration(neo);
			if (declared != null) {
				syncNeoChannels(connection, declared.register, declared.channels);
				probe(() -> "  " + (declared.register ? "register" : "unregister") + " " + declared.channels.size()
						+ " channel(s) told to NeoForge: " + declared.channels);
			} else {
				probe(() -> "  NeoForge could not read the declaration; its half was skipped");
			}
		} else if (neo != null) {
			after.protocolMovesAreNeoForges = true;
			if ("c:version".equals(id)) {
				after.neoForgeHalf = () -> invokeNeoNetworkRegistry("checkCommonVersion", listener, neo);
			} else {
				after.neoForgeHalf = () -> {
					declaredPlayChannels(connection, neo);
					invokeNeoNetworkRegistry("onCommonRegister", listener, neo);
				};
			}
		}
		AFTER_RECEIVER.set(after);
		return fabric;
	}

	/** Called before each return of the same {@code handle}: NeoForge's half of a common negotiation. */
	public static void fabricInboundDone(Object receiver) {
		AfterReceiver after = AFTER_RECEIVER.get();
		if (after == null || after.receiver != receiver) return;
		if (after.nested > 0) {
			after.nested--;
			return;
		}
		AFTER_RECEIVER.remove();
		if (after.neoForgeHalf != null) after.neoForgeHalf.run();
	}

	/** Whether a Fabric receiver is handling a common negotiation on this thread, whose wire moves are NeoForge's. */
	private static boolean protocolMovesAreNeoForges() {
		AfterReceiver after = AFTER_RECEIVER.get();
		return after != null && after.protocolMovesAreNeoForges;
	}

	/** What one connection has declared it listens on (minecraft:register less unregister), in the phase it was sent in. */
	private static final class Declared {
		String protocol;
		final Set<String> channels = new LinkedHashSet<>();
	}

	private static final Map<Object, Declared> DECLARED = Collections.synchronizedMap(new WeakHashMap<>());

	/**
	 * Called at the head of {@code Connection.sendPacket(Packet, ChannelFutureListener, boolean)}: true when {@code packet} is a
	 * channel declaration ({@code minecraft:register}) naming only channels this side already declared on {@code connection}
	 * in the phase it is in, which no receiver learns anything from, so the send is left out. Every other packet, an
	 * {@code unregister} included, goes out and keeps the record in step.
	 *
	 * <p>The channels are read as NeoForge's codec reads the declaration's bytes, whichever ecosystem's payload class it
	 * is ({@link WirePayloads}); a declaration neither codec can read goes out as it is.
	 */
	public static boolean declarationAlreadyMade(Object connection, Object packet) {
		if (connection == null || packet == null) return false;
		// A custom-payload packet (the client- or server-bound one), through the cached accessor; anything else at once.
		if (!packet.getClass().getName().endsWith("CustomPayloadPacket")) return false;
		Method accessor = payloadAccessor(packet.getClass());
		if (accessor == null) return false;
		Object payload;
		try {
			payload = accessor.invoke(packet);
		} catch (ReflectiveOperationException | RuntimeException e) {
			Caught.warn(e, "[Forbric/Net] could not read the payload of %s; it is sent as it is", packet.getClass().getName());
			return false;
		}
		if (payload == null) return false;
		String id = cachedPayloadId(payload);
		if (!isDinnerboneChannelRegistration(id)) return false;
		try {
			Object listener = invokeNoArg(connection, "getPacketListener");
			Object protocol = listener == null ? null : invokeNoArg(listener, "protocol");
			Object flow = invokeNoArg(connection, "getSending");
			if (protocol == null || flow == null) return false;
			Object idValue = invokeNoArg(invokeNoArg(payload, "type"), "id");
			Object neoCodec = neoCodec(idValue, protocol, flow);
			Object neo = WirePayloads.decodedBy(neoCodec, payload, java.util.Arrays.asList(
					typeAndCodecCodec(fabricTypeAndCodecOnly(idValue, protocol, flow))));
			Registration declared = registration(neo);
			if (declared == null) return false;
			Set<String> channels = new LinkedHashSet<>();
			for (Object channel : declared.channels) channels.add(String.valueOf(channel));
			if (!repeatsWhatWasDeclared(connection, String.valueOf(protocol), declared.register, channels)) return false;
			probe(() -> "  left out a repeated " + id + " of " + channels.size() + " channel(s) already declared in " + protocol);
			ForbricLog.debug("[Forbric/Net] left out a repeated channel declaration of %d channel(s) already declared on the connection in %s",
					channels.size(), protocol);
			return true;
		} catch (RuntimeException unreadable) {
			Caught.warn(unreadable, "[Forbric/Net] could not read a channel declaration on its way out; it is sent as it is");
			return false;
		}
	}

	/** A no-argument accessor of a class, by name, looked up once per class: this runs for every custom payload sent. */
	private static ClassValue<Method> accessor(String name) {
		return new ClassValue<>() {
			@Override
			protected Method computeValue(Class<?> type) {
				Method found = findMethod(type, name);
				if (found != null) found.setAccessible(true);
				return found;
			}
		};
	}

	private static final ClassValue<Method> PAYLOAD_TYPE = accessor("type"), TYPE_ID = accessor("id");

	/** {@link #payloadId}, through accessors looked up once per class. */
	private static String cachedPayloadId(Object payload) {
		try {
			Method typeAccessor = PAYLOAD_TYPE.get(payload.getClass());
			Object type = typeAccessor == null ? null : typeAccessor.invoke(payload);
			Method idAccessor = type == null ? null : TYPE_ID.get(type.getClass());
			return idAccessor == null ? null : String.valueOf(idAccessor.invoke(type));
		} catch (ReflectiveOperationException | RuntimeException e) {
			Caught.warn(e, "[Forbric/Net] could not read the id of payload %s", payload.getClass().getName());
			return null;
		}
	}

	/**
	 * Keeps {@code connection}'s record of what it declared in {@code phase} — a new phase starts it afresh, a register
	 * adds, an unregister takes away — and answers whether this register names only channels already declared there.
	 */
	static boolean repeatsWhatWasDeclared(Object connection, String phase, boolean register, Set<String> channels) {
		synchronized (DECLARED) {
			Declared record = DECLARED.computeIfAbsent(connection, ignored -> new Declared());
			if (!phase.equals(record.protocol)) {
				record.protocol = phase;
				record.channels.clear();
			}
			if (!register) {
				record.channels.removeAll(channels);
				return false;
			}
			if (!channels.isEmpty() && record.channels.containsAll(channels)) return true;
			record.channels.addAll(channels);
			return false;
		}
	}

	/**
	 * Called at the head of a Fabric receiver's {@code sendPacket(Packet, ChannelFutureListener)} — fabric-api's public
	 * {@code PacketSender} method: true when the packet is that receiver's own answer to a common negotiation it is
	 * handling, which NeoForge's handler sends instead once it is done (see {@link #fabricInbound}).
	 */
	public static boolean fabricSendStandsAside(Object sender, Object packet) {
		if (!protocolMovesAreNeoForges() || packet == null) return false;
		Object payload = invokeNoArg(packet, "payload");
		String id = payload == null ? null : payloadId(payload);
		if (!isCommonNegotiation(id)) return false;
		probe(() -> "  Fabric's own " + id + " answer stands aside; NeoForge's handler answers once");
		return true;
	}

	/**
	 * A peer's {@code c:register} for the PLAY phase, received on the server: the channels its Fabric mods declared for
	 * play, which the channel census counts against the payload types registered (Fabric's own receiver records them on
	 * the connection for its play addon).
	 */
	private static void declaredPlayChannels(Object connection, Object neoRegister) {
		Object receiving = invokeNoArg(connection, "getReceiving");
		if (!"SERVERBOUND".equals(enumName(receiving))) return;
		Object declaredFor = invokeNoArg(neoRegister, "protocol");
		Object channels = invokeNoArg(neoRegister, "channels");
		if (!"PLAY".equals(enumName(declaredFor)) || !(channels instanceof Collection<?> ids) || ids.isEmpty()) return;
		ForbricLog.info("[Forbric/Net] handed %d Fabric PLAY channel(s) the client declared during configuration to "
				+ "Fabric's own c:register receiver, which records them on the connection for its play addon", ids.size());
		NetworkChannelCensus.declared(net.forbric.api.Ecosystem.FABRIC, ids);
		NetworkChannelCensus.report();
	}

	/**
	 * Debug-only: for a NeoForge {@code neoforge:frozen_registry} payload, names the registry it carries, the
	 * registry's runtime class, how many entries its {@code MappedRegistry.byKey} actually holds, and every snapshot
	 * entry that is not a real local key. Written to explain "Failed to sync registries from the server:
	 * NullPointerException: holder is null" out of {@code MappedRegistry.registerIdMapping}, which NeoForge's handler
	 * reports without naming a registry. The first run of it ruled out aliases and missing entries (every remote
	 * name was a real local key) and the second — the class and the {@code byKey} count — found the cause: the
	 * registries that were MinecraftForge {@code NamespacedWrapper}s answered {@code containsKey} from their
	 * delegate while their inherited {@code byKey} held zero entries (that wrapper left with MinecraftForge).
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
			Caught.expected(e);
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

	/**
	 * Called from a mixin on {@code ServerConfigurationPacketListenerImpl.finishCurrentTask}. Fabric and NeoForge
	 * both implement the same common-networking handshake on {@code c:version}/{@code c:register}, but expose
	 * different {@code ConfigurationTask.Type}s. Treat those task ids as aliases on the merged base.
	 */
	public static boolean finishEquivalentCommonTask(Object listener, Object requestedType) {
		if (protocolMovesAreNeoForges() && isCommonTask(taskId(requestedType))) {
			// A Fabric receiver finishing its common-networking task while NeoForge's handler is yet to answer the same
			// payload: NeoForge's finish is the one, and comes when the receiver is done (see fabricInbound).
			probe(() -> "  Fabric's own finish of " + taskId(requestedType) + " stands aside; NeoForge's handler finishes once");
			return true;
		}
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
			Caught.warn(e, "[Forbric] could not complete equivalent common-networking task %s while current task was %s",
					requested, current);
			return false;
		}
	}

	private record MirrorRegistration(Object id, Object type, Object codec, Object protocol, Object flow,
			String source) {
		String version() {
			return mirrorVersion(source, enumName(protocol));
		}
	}

	/**
	 * The version a mirror announces in NeoForge's negotiation, which both peers compare channel by channel: where the
	 * channel came from ({@code fabric}, {@code local}) and its phase — never its direction. It named the direction once,
	 * and a channel registered for both directions took the name of whichever was mirrored second; Fabric's registries are
	 * found in {@code Class.getMethods()} order, which is not the same in the client's JVM and the server's, so one peer
	 * announced {@code PLAY/SERVERBOUND} and the other {@code PLAY/CLIENTBOUND} for the same channel and NeoForge refused
	 * the client ("Incompatible client!").
	 */
	static String mirrorVersion(String source, String protocol) {
		int cut = source.indexOf('/');
		return FORBRIC_MIRROR_VERSION + ":" + (cut < 0 ? source : source.substring(0, cut)) + "/" + protocol;
	}

	/**
	 * The version of one registration standing for two: the same when both say the same, else every part of both in one
	 * order, so which of the two came first never shows.
	 */
	static String mergedMirrorVersion(String existing, String mirrored) {
		if (existing == null || existing.equals(mirrored)) return mirrored;
		String prefix = FORBRIC_MIRROR_VERSION + ":";
		java.util.TreeSet<String> parts = new java.util.TreeSet<>();
		for (String version : List.of(existing, mirrored)) {
			String bare = version.startsWith(prefix) ? version.substring(prefix.length()) : version;
			parts.addAll(java.util.Arrays.asList(bare.split("\\+")));
		}
		return prefix + String.join("+", parts);
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
			Caught.warn(e, "[Forbric] could not inspect merged packet codec %s::%s", packetClassName, fieldName);
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
		for (FabricPayloadRegistries.Registry registry : FabricPayloadRegistries.all(loader)) {
			String source = "fabric/" + enumName(registry.protocol()) + "/" + enumName(registry.flow());
			for (Map.Entry<?, ?> entry : new ArrayList<>(registry.types().entrySet())) {
				Object typeAndCodec = entry.getValue();
				Object type = typeAndCodecType(typeAndCodec);
				Object codec = typeAndCodecCodec(typeAndCodec);
				if (entry.getKey() == null || type == null || codec == null) continue;
				out.add(new MirrorRegistration(entry.getKey(), type, codec, registry.protocol(), registry.flow(), source));
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
						registration.version(), Boolean.TRUE);
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
				Caught.warn(e, "[Forbric] NeoForge's payload registrations are read-only; payload %s is not mirrored into "
						+ "NeoForge", registration.id());
				return false;
			} catch (ReflectiveOperationException | RuntimeException e) {
				Caught.warn(e, "[Forbric] could not mirror payload %s into NeoForge", registration.id());
				return false;
		}
	}

	private static boolean mergeNeoPayloadFlowIfNeeded(Map<Object, Object> protocolMap, MirrorRegistration registration,
			Object existing, Constructor<?> ctor) throws ReflectiveOperationException {
		Object existingFlow = invokeNoArg(existing, "flow");
		if (!(existingFlow instanceof Optional<?> optional) || optional.isEmpty()) return false;
		if (optional.get() == registration.flow()) return false;

		String existingVersion = invokeNoArg(existing, "version") instanceof String version ? version : null;
		Object merged = ctor.newInstance(invokeNoArg(existing, "type"), invokeNoArg(existing, "codec"),
				List.of(registration.protocol()), Optional.empty(), mergedMirrorVersion(existingVersion, registration.version()),
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
		FabricPayloadRegistries.Registry registry = FabricPayloadRegistries.find(loader, protocol, packetFlow);
		if (registry == null) return null;
		Object existing = registry.types().get(id);
		if (existing != null) return existing;

		Object type = invokeNoArg(registration, "type");
		Object codec = invokeNoArg(registration, "codec");
		if (type == null || codec == null) return null;
		Object mirrored = FabricPayloadRegistries.register(loader, registry, type, codec);
		return mirrored != null ? mirrored : registry.types().get(id);
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
			Caught.warn(e, "[Forbric] could not look up NeoForge's registration of payload %s", id);
			probe(() -> "  neo: registry lookup threw " + e);
			return null;
		}
	}

	private static final class CodecInvocationHandler implements InvocationHandler {
		private final CandidateSet candidates;

		/**
		 * The codec's own exception, not reflection's wrapper of it: an encoder failure used to surface as
		 * UndeclaredThrowableException → InvocationTargetException → the real cause, three frames deep.
		 */
		private static Object forward(Method method, Object codec, Object[] args) throws Throwable {
			try {
				return method.invoke(codec, args);
			} catch (java.lang.reflect.InvocationTargetException thrown) {
				throw thrown.getCause() == null ? thrown : thrown.getCause();
			}
		}

		private CodecInvocationHandler(CandidateSet candidates) {
			this.candidates = candidates;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			String name = method.getName();
			if ("encode".equals(name) && args != null && args.length == 2) {
				Object codec = candidates.selectEncode(args[1]);
				if (codec == null) throw new IllegalStateException("No custom payload codec for " + candidates.id);
				return forward(method, codec, args);
			}
			if ("decode".equals(name) && args != null && args.length == 1) {
				Object codec = candidates.selectDecode();
				if (codec == null) throw new IllegalStateException("No custom payload codec for " + candidates.id);
				return forward(method, codec, args);
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

	/**
	 * Whose codec writes a payload of this class first: the carrier whose own package the class is in (NeoForge's
	 * platform payloads), or the family of the mod jar the class was defined from — a Fabric mod's payload,
	 * fabric-api's own payloads included (fabric-api is a mod jar of the Fabric family, whatever its packages are called), is
	 * Fabric's to write. {@code null} for anything else: a payload of the game, a library, or a mod of another family, whose
	 * codec is chosen by the registry entries below.
	 */
	static LoaderProbePolicy.Family encoderFamily(String payloadClass, LoaderProbePolicy.Family definingFamily) {
		if (payloadClass.startsWith("net.neoforged.")) return LoaderProbePolicy.Family.NEOFORGE;
		if (definingFamily == LoaderProbePolicy.Family.FABRIC) return LoaderProbePolicy.Family.FABRIC;
		return null;
	}

	/** The loader family of the mod jar {@code type} was defined from; null for the game, a carrier, a library or the kernel. */
	private static LoaderProbePolicy.Family definingFamily(Class<?> type) {
		return type.getClassLoader() instanceof ForbricClassLoader forbric ? forbric.familyOfClass(type.getName()) : null;
	}

	private static final class CandidateSet {
		private final Object id;
		private final Object local;
		private final Object fabricType;
		private final Object fabric;
		private final Object neo;
		private final Object fallback;
		private final Object guest;

		private CandidateSet(Object id, Object local, Object fabricEntry, Object fabric, Object neo, Object guest, Object fallback) {
			this.id = id;
			this.local = local;
			this.fabricType = typeAndCodecType(fabricEntry);
			this.fabric = fabric;
			this.neo = neo;
			this.fallback = fallback;
			this.guest = guest;
		}
		private Object selectEncode(Object payload) {
			if (payload != null) {
				String payloadClass = payload.getClass().getName();
				LoaderProbePolicy.Family writer = encoderFamily(payloadClass, definingFamily(payload.getClass()));
				if (writer == LoaderProbePolicy.Family.NEOFORGE) return firstNonNull(neo, local, fabric, guest, fallback);
				if (writer == LoaderProbePolicy.Family.FABRIC) return firstNonNull(fabric, local, neo, guest, fallback);
				// An unknown guest payload may share an id with a known registry type. Its original chosen codec
				// is retained rather than sending that Java object unconditionally into Fabric's encoder.
				if (guest != null) return guest;
				Object payloadType = invokeNoArg(payload, "type");
				if (fabric != null && fabricType != null && fabricType.equals(payloadType)) return fabric;
				if (fabric != null && !payloadClass.startsWith("net.neoforged.")) return fabric;
			}
			return firstNonNull(local, neo, fabric, guest, fallback);
		}

		private Object selectDecode() {
			if (isDinnerboneChannelRegistration(id)) {
				return firstNonNull(neo, fabric, local, guest, fallback);
			}
			if (isCommonNegotiation(id)) {
				return firstNonNull(neo, local, fabric, guest, fallback);
			}
			return firstNonNull(local, fabric, neo, guest, fallback);
		}
	}

	/**
	 * Whether NeoForge itself registered a handler for this PLAY-phase payload.
	 *
	 * <p>Called from the rewritten {@code ServerGamePacketListenerImpl.handleCustomPayload} (see
	 * {@code CommonNetworkInteropInjector.letNeoForgePayloadsThrough}), which hands a payload the merged base's
	 * MinecraftForge override did not take to NeoForge's {@code NetworkRegistry.handleModdedPayload}. Handing over
	 * UNCONDITIONALLY would be wrong: NeoForge's dispatcher is strict about what it does not recognise and
	 * disconnects on a channel it never registered, where vanilla's own play override simply drops a payload
	 * nobody took.
	 *
	 * <p>So the fall-through is gated on the one question that makes it safe: is this a payload NeoForge knows?
	 * If it is, NeoForge's dispatcher is exactly where it should go, and that is the population that was being
	 * dropped. If it is not — a Fabric payload NeoForge was never told about, an unregistered id, anything at all
	 * in doubt — the answer is false and the method returns as it did before. (A Fabric payload the kernel mirrored
	 * into NeoForge's registry answers true and lands on the no-op handler the mirror installed with it, which is
	 * the same drop.) Fail-CLOSED on purpose: the old behaviour silently dropped a NeoForge mod's packet, the
	 * wrong failure mode disconnects the player, and those are not the same size.
	 *
	 * @param payload the {@code CustomPacketPayload} the packet carried
	 */
	public static boolean neoForgeWillHandle(Object payload) {
		if (payload == null) return false;
		try {
			ClassLoader loader = loaderFor(payload);
			Class<?> registryClass = load(loader, NEO_NETWORK_REGISTRY);
			if (registryClass == null) return false;

			Object id = invokeNoArg(invokeNoArg(payload, "type"), "id");
			if (id == null) return false;

			Field registrationsField = findField(registryClass, "PAYLOAD_REGISTRATIONS");
			if (registrationsField == null) return false;
			registrationsField.setAccessible(true);
			Object raw = registrationsField.get(null);
			if (!(raw instanceof Map<?, ?> byProtocol)) return false;

			// Any protocol: the play listener only ever sees PLAY, but reading the protocol constant reflectively
			// to compare would add a failure mode for no gain — an id registered under any protocol is an id
			// NeoForge owns, which is the whole question here.
			for (Object protocolMap : byProtocol.values()) {
				if (protocolMap instanceof Map<?, ?> ids && ids.containsKey(id)) {
					probe(() -> "  neo owns " + id + " — handing the play payload to its dispatcher");
					return true;
				}
			}
			probe(() -> "  neo does not own " + id + " — not handing it over (NeoForge's dispatcher would end the "
					+ "connection with \"No Channel for " + id + "\")");
			return false;
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric] could not ask NeoForge whether it owns payload %s; not falling through",
					payloadId(payload));
			probe(() -> "  could not ask NeoForge whether it owns this payload (" + t + ") — not falling through");
			return false;
		}
	}

	/**
	 * Called from the head of NeoForge's {@code NetworkRegistry.checkPacket} (both overloads): a payload on a channel
	 * another ecosystem negotiated is not NeoForge's to police ({@link #notNeoForgesToPolice}). Its check compares the
	 * payload's channel with what NeoForge negotiated, and a channel Fabric or Polymer negotiated is not in that list by
	 * construction — so the mod that owns it could not even SEND on it ("Payload polymer:handshake may not be sent to
	 * the server!"). Says whether the packet's payload is exempt from that check.
	 */
	public static boolean exemptFromNeoChannelCheck(Object packet) {
		if (packet == null) return false;
		Method accessor = payloadAccessor(packet.getClass());
		if (accessor == null) return false;
		try {
			Object payload = accessor.invoke(packet);
			if (payload == null) return false;
			return notNeoForgesToPolice(payload);
		} catch (ReflectiveOperationException | RuntimeException e) {
			Caught.warn(e, "[Forbric] could not read the payload of %s; it is left to NeoForge's channel check",
					packet.getClass().getName());
			return false;
		}
	}

	/**
	 * Called right after NeoForge's {@code NetworkRegistry.isModdedPayload} where the common packet listeners decide
	 * whether a RECEIVED payload goes to NeoForge's dispatcher ({@code ForeignPayloadReceiveInjector}). NeoForge's
	 * dispatcher knows only the channels NeoForge registered and closes the connection on any other — the receiving
	 * half of the verdict {@link #exemptFromNeoChannelCheck} already takes away from its send check. A channel another
	 * ecosystem negotiated goes down vanilla's path instead, where the mod that owns it listens: Carpet's client
	 * takes {@code carpet:hello} at {@code ClientPacketListener.handleUnknownCustomPayload}, and was disconnected
	 * with "No Channel for carpet:hello" before it got there.
	 */
	public static boolean neoForgeDispatches(Object payload, boolean modded) {
		return modded && !notNeoForgesToPolice(payload);
	}

	/** Channels already let past, so the log says it once per channel rather than once per packet. */
	private static final Set<String> UNPOLICED = Collections.synchronizedSet(new LinkedHashSet<>());

	/**
	 * Whether {@code payload}'s channel belongs to a negotiation NeoForge is not part of.
	 *
	 * <p>NeoForge's check asks the CONNECTION whether it negotiated this channel. Under Forbric the other two
	 * ecosystems negotiate their own — Fabric's {@code c:register}, Polymer's and Carpet's handshakes — and such a
	 * channel is absent from NeoForge's view by construction. Its answer for a channel it does not know is to
	 * close the connection, which is a verdict about a conversation it is not in.
	 *
	 * <p>Polymer is the case that paid for it. It registers its payloads through its own codec patch rather than
	 * Fabric's registry, so nothing mirrors them into NeoForge, and joining a world died on "Payload
	 * polymer:handshake may not be sent to the server!" after the packet had already encoded perfectly well.
	 *
	 * <p>Two guards keep this narrow. A payload class from {@code net.minecraft} or {@code net.neoforged} is never
	 * exempt whatever the registry holds: {@code minecraft:brand} and {@code neoforge:register} are not in
	 * PAYLOAD_REGISTRATIONS either, and letting those past would turn "not NeoForge's channel" into "no channel is
	 * policed". And a payload NeoForge DID register is still policed, so every NeoForge mod keeps the check its
	 * own loader gives it — including the ones the kernel mirrors in from Fabric's registry.
	 */
	private static boolean notNeoForgesToPolice(Object payload) {
		String payloadClass = payload.getClass().getName();
		if (payloadClass.startsWith("net.minecraft.") || payloadClass.startsWith("net.neoforged.")) return false;

		Class<?> registryClass = load(payload.getClass().getClassLoader(), NEO_NETWORK_REGISTRY);
		if (registryClass == null) return false;
		try {
			Object type = invokeNoArg(payload, "type");
			Object id = type == null ? null : invokeNoArg(type, "id");
			if (id == null) return false;

			Field registrationsField = findField(registryClass, "PAYLOAD_REGISTRATIONS");
			if (registrationsField == null) return false;
			registrationsField.setAccessible(true);
			@SuppressWarnings("unchecked")
			Map<Object, Map<Object, Object>> registrations =
					(Map<Object, Map<Object, Object>>) registrationsField.get(null);
			if (registrations == null || registrations.isEmpty()) return false;
			for (Map<Object, Object> protocolMap : registrations.values()) {
				if (protocolMap != null && protocolMap.containsKey(id)) return false;
			}

			if (UNPOLICED.add(String.valueOf(id))) {
				ForbricLog.info("[Forbric/Net] %s is not a channel NeoForge registered, so its channel check is not "
						+ "the authority on it — another ecosystem negotiated this one, and NeoForge's answer for a "
						+ "channel it does not know is to close the connection", id);
			}
			return true;
		} catch (ReflectiveOperationException | RuntimeException e) {
			Caught.warn(e, "[Forbric/Net] could not read NeoForge's payload registrations; %s is left to its channel "
					+ "check", payloadId(payload));
			return false;
		}
	}

	/**
	 * The {@code payload()} accessor for a packet class, resolved once per class and then remembered.
	 *
	 * <p>This is on the hot path and was not cached. {@link #exemptFromNeoChannelCheck} runs at the head of the
	 * outbound-packet check, so it is reached for EVERY packet the game sends — and it went through the generic
	 * reflective lookup each time, which walks the class's whole superclass chain calling
	 * {@code getDeclaredMethod} and then every interface. Most packets have no {@code payload()} at all, so the
	 * common case was the most expensive one: the full walk, a {@code NoSuchMethodException} constructed and
	 * discarded at each step, and a defensive copy of the {@code Method} array behind each call.
	 *
	 * <p>{@link ClassValue} rather than a map: it is keyed by class without keeping the class alive, needs no
	 * lock, and a miss is cached as a null exactly like a hit.
	 */
	static Method payloadAccessor(Class<?> packetClass) {
		return PAYLOAD_ACCESSOR.get(packetClass);
	}

	private static final ClassValue<Method> PAYLOAD_ACCESSOR = new ClassValue<>() {
		@Override
		protected Method computeValue(Class<?> type) {
			Method accessor = findMethod(type, "payload");
			if (accessor != null) accessor.setAccessible(true);
			return accessor;
		}
	};

	private static Object fabricTypeAndCodec(Object id, Object protocol, Object packetFlow) {
		Object entry = fabricTypeAndCodecOnly(id, protocol, packetFlow);
		return entry != null ? entry : mirrorNeoPayloadIntoFabricRegistry(loaderFor(id, protocol, packetFlow), id, protocol, packetFlow);
	}

	/** Fabric's own type and codec for {@code id} in this phase and direction, without mirroring anything into it. */
	private static Object fabricTypeAndCodecOnly(Object id, Object protocol, Object packetFlow) {
		FabricPayloadRegistries.Registry registry = FabricPayloadRegistries.find(loaderFor(id, protocol, packetFlow), protocol, packetFlow);
		return registry == null || id == null ? null : registry.types().get(id);
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
			Caught.warn(e, "[Forbric] could not read NeoForge's built-in payload codecs for %s", id);
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
		if (NEO_REGISTER_PAYLOAD.equals(className)) {
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
				Caught.warn(e, "[Forbric] could not mirror channel-registration payload into NeoForge networking");
				return;
			}
		}
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

	private static boolean isCommonTask(String id) {
		return "c:version".equals(id) || "c:register".equals(id) || "neoforge:common_version".equals(id)
				|| "neoforge:common_register".equals(id);
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
			Caught.warn(e, "[Forbric] could not read %s.%s", owner.getName(), name);
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
				Caught.expected(ignored);
			} catch (ReflectiveOperationException | RuntimeException e) {
				Caught.warn(e, "[Forbric] could not read %s.%s", c.getName(), name);
				return null;
			}
		}
		return null;
	}

	/**
	 * The value of the one instance field, in {@code owner}'s class or a superclass, declared as the class named
	 * {@code typeName} or a subtype of it — read by what the field holds, not by what it is called. Null when there is
	 * none, or more than one.
	 */
	private static Object valueOfType(Object owner, String typeName) {
		if (owner == null) return null;
		Class<?> type = load(owner.getClass().getClassLoader(), typeName);
		if (type == null) return null;
		Field only = null;
		for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
			for (Field field : c.getDeclaredFields()) {
				if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || !type.isAssignableFrom(field.getType())) continue;
				if (only != null) return null;
				only = field;
			}
		}
		if (only == null) return null;
		try {
			only.setAccessible(true);
			return only.get(owner);
		} catch (ReflectiveOperationException | RuntimeException e) {
			Caught.warn(e, "[Forbric] could not read %s.%s", only.getDeclaringClass().getName(), only.getName());
			return null;
		}
	}

	private static Field findField(Class<?> owner, String name) {
		for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
			try {
				return c.getDeclaredField(name);
			} catch (NoSuchFieldException ignored) {
				// try superclass
				Caught.expected(ignored);
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
			Caught.warn(e, "[Forbric] %s.%s threw; answering null", method.getDeclaringClass().getName(), name);
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
			Caught.warn(e, "[Forbric] %s.%s threw", owner.getName(), name);
			return INVOKE_FAILED;
		}
	}

	private static Method findMethod(Class<?> owner, String name, Class<?>... params) {
		for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
			try {
				return c.getDeclaredMethod(name, params);
			} catch (NoSuchMethodException ignored) {
				// try superclass
				Caught.expected(ignored);
			}
		}
		for (Class<?> itf : owner.getInterfaces()) {
			try {
				return itf.getMethod(name, params);
			} catch (NoSuchMethodException ignored) {
				// try next interface
				Caught.expected(ignored);
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
			Caught.expected(e);
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
