/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;

/**
 * Every event one family's bus posts while the other family's bus is dispatching, on the same thread, checked the
 * same way for all of them.
 *
 * <p>The kernel's bus-to-bus bridges are listeners: a NeoForge listener that posts MinecraftForge's event (or the
 * reverse), synchronously, inside the outer dispatch. So a post that begins and ends inside the other family's
 * dispatch is that dispatch's forward, and {@code EventChainAuditInjector} reports both buses' entry and exit here
 * without knowing any bridge by name. Per (outer event, inner event) pair and per outer post this records:
 * <ul>
 *   <li>how many times the inner event was posted: once is a forward, more than once is double delivery;</li>
 *   <li>whether an inner cancel reached the outer event when the outer event can be cancelled;</li>
 *   <li>whether the inner dispatch threw;</li>
 *   <li>for an outer event that has been forwarded at least once, how many of its posts were not forwarded, and
 *   whether the outer event was already cancelled then (NeoForge's forwards do not receive cancelled events).</li>
 * </ul>
 * Only the immediate parent frame of the other family is credited, so a listener that triggers some other event
 * (a tick that spawns an entity) credits that event's own dispatch, not the tick. And only a post the KERNEL made
 * is a forward: the first frame below the bus and the hook facades must be kernel code. A mod listener that
 * builds four thousand item tooltips inside a NeoForge event posts four thousand MinecraftForge tooltip events;
 * those are counted as incidental, not as a bridge that fired four thousand times.
 *
 * <p>Double delivery is judged per NeoForge listener: two listeners of one dispatch forwarding the same event is
 * a bridge installed twice (or two bridges for one event). One listener posting several is a fan-out when it
 * always does (one event per item), and multiplicity drift when that forward is otherwise one to one.
 *
 * <p>What this does not see: composites that call both families' hooks one after the other at a call site
 * (portal, spawner, loot, fuel, tooltips) are not nested posts; their chains have their own gates. Result fields
 * other than cancellation are not compared.
 *
 * <p>Off unless {@code -Dforbric.eventChainAudit=<report.json>} names where the report goes; it is written when the
 * JVM exits and on {@link #write()}.
 */
public final class EventChainAudit {
	public static final String PROPERTY = "forbric.eventChainAudit";
	private static final String NEO_CANCELLABLE = "net.neoforged.bus.api.ICancellableEvent";

	private static final class Frame {
		final boolean neo; final Object event; final Class<?> type;
		/** Per inner event class of the other family, forwarded by kernel code. */
		final Map<Class<?>, Child> children = new LinkedHashMap<>();
		/** Per inner event class of the other family, posted by anything else inside this dispatch. */
		final Map<Class<?>, Integer> incidental = new LinkedHashMap<>();
		/** The NeoForge listener running now (its index in this dispatch), or -1. */
		int listener = -1;
		/** Set on an inner frame at entry: who posted it, and which listener of the parent was running. */
		boolean kernelPoster; int parentListener = -1;
		Frame(boolean neo, Object event) { this.neo = neo; this.event = event; this.type = event.getClass(); }
	}

	private static final class Child {
		int posts, cancelled, failed;
		final Map<Integer, Integer> byListener = new LinkedHashMap<>();
	}

	static final class Pair {
		final LongAdder outerPosts = new LongAdder(), forwardedOnce = new LongAdder(), duplicated = new LongAdder(),
				innerCancelled = new LongAdder(), cancelCarried = new LongAdder(), cancelLost = new LongAdder(),
				innerFailures = new LongAdder(), singleInvocations = new LongAdder(), multiInvocations = new LongAdder();
		/** Invocations that posted more than once in a forward that is mostly one to one. */
		long drift() { long single = singleInvocations.sum(), multi = multiInvocations.sum(); return single > multi ? multi : 0; }
		volatile String duplicatedExample, cancelLostExample;
	}

	static final class Outer {
		final LongAdder posts = new LongAdder(), withoutForward = new LongAdder(), withoutForwardCancelled = new LongAdder(),
				failures = new LongAdder();
	}

	private static final ThreadLocal<ArrayDeque<Frame>> STACK = ThreadLocal.withInitial(ArrayDeque::new);
	private static final Map<String, Pair> PAIRS = new ConcurrentHashMap<>();
	private static final Map<String, Outer> OUTERS = new ConcurrentHashMap<>();
	/** Outer events that have been seen with an inner post at least once: only their unforwarded posts count. */
	private static final Set<String> FORWARDED_OUTERS = ConcurrentHashMap.newKeySet();
	private static final LongAdder UNBALANCED = new LongAdder();
	private static final Map<String, LongAdder> INCIDENTAL = new ConcurrentHashMap<>();
	private static final StackWalker WALKER = StackWalker.getInstance();
	/** Hook facades: a post they make is the post of whoever called them. */
	private static final Set<String> FACADES = facades();
	private static Set<String> facades() {
		Set<String> facades = new HashSet<>(Set.of("net.minecraftforge.client.event.ForgeEventFactoryClient", "net.neoforged.neoforge.common.CommonHooks"));
		for (ForeignType pair : List.of(ForeignType.EVENT_FACTORY, ForeignType.EVENT_HOOKS, ForeignType.CLIENT_HOOKS, ForeignType.SERVER_LIFECYCLE_HOOKS))
			for (Ecosystem family : List.of(Ecosystem.FORGE, Ecosystem.NEOFORGE)) facades.add(pair.binary(family));
		return Set.copyOf(facades);
	}
	private static final Map<Class<?>, Optional<Method>> CANCEL_PROBES = new ConcurrentHashMap<>();
	private static final AtomicBoolean HOOKED = new AtomicBoolean();

	private EventChainAudit() { }

	public static boolean enabled() {
		String target = System.getProperty(PROPERTY);
		return target != null && !target.isBlank() && !"off".equalsIgnoreCase(target);
	}

	public static void neoEnter(Object bus, Object event) { enter(true, event); }
	public static void neoExit(Object event, Throwable failure) { exit(true, event, cancelled(event), cancellable(event), failure); }
	public static void forgeEnter(Object bus, Object event) { enter(false, event); }
	/** NeoForge's dispatch loop is about to call listener {@code index} of the innermost NeoForge dispatch. */
	public static void neoListener(int index) { Frame top = STACK.get().peek(); if (top != null && top.neo) top.listener = index; }
	public static void neoListenerDone() { Frame top = STACK.get().peek(); if (top != null && top.neo) top.listener = -1; }
	public static void forgeExit(Object event, boolean cancellable, boolean cancelled, Throwable failure) {
		exit(false, event, cancelled, cancellable, failure);
	}

	private static void enter(boolean neo, Object event) {
		if (event == null) return;
		if (HOOKED.compareAndSet(false, true) && enabled())
			Runtime.getRuntime().addShutdownHook(new Thread(EventChainAudit::write, "forbric-event-chain-audit"));
		ArrayDeque<Frame> stack = STACK.get();
		Frame frame = new Frame(neo, event), parent = stack.peek();
		if (parent != null && parent.neo != neo) { frame.kernelPoster = postedByKernel(); frame.parentListener = parent.listener; }
		stack.push(frame);
	}

	/** Whether the first frame below the buses, the hook facades and this class is kernel code. */
	static boolean postedByKernel() {
		return WALKER.walk(frames -> frames.map(StackWalker.StackFrame::getClassName).filter(name -> !name.equals(EventChainAudit.class.getName())
				&& !name.startsWith("net.minecraftforge.eventbus.") && !name.startsWith("net.neoforged.bus.") && !name.startsWith("java.")
				&& !name.startsWith("jdk.") && !name.startsWith("sun.") && !FACADES.contains(name)).findFirst()
				.map(name -> name.startsWith("net.forbric.kernel.")).orElse(false));
	}

	private static void exit(boolean neo, Object event, boolean cancelled, boolean cancellable, Throwable failure) {
		if (event == null) return;
		ArrayDeque<Frame> stack = STACK.get();
		Frame frame = stack.peek();
		// An exit that is not the innermost entry means a dispatch escaped its wrapper; drop to it rather than
		// crediting the wrong parent, and count it so the report cannot look clean.
		if (frame == null || frame.event != event || frame.neo != neo) {
			UNBALANCED.increment();
			while (frame != null && !(frame.event == event && frame.neo == neo)) { stack.pop(); frame = stack.peek(); }
			if (frame == null) return;
		}
		stack.pop();
		String outerKey = key(neo, frame.type);
		Outer outer = OUTERS.computeIfAbsent(outerKey, k -> new Outer());
		outer.posts.increment();
		if (failure != null) outer.failures.increment();
		if (frame.children.isEmpty()) {
			if (FORWARDED_OUTERS.contains(outerKey)) {
				outer.withoutForward.increment();
				if (cancelled) outer.withoutForwardCancelled.increment();
			}
		} else {
			FORWARDED_OUTERS.add(outerKey);
			for (var entry : frame.children.entrySet()) {
				Pair pair = PAIRS.computeIfAbsent(outerKey + " -> " + key(!neo, entry.getKey()), k -> new Pair());
				Child child = entry.getValue();
				pair.outerPosts.increment();
				if (child.posts == 1) pair.forwardedOnce.increment();
				for (int k : child.byListener.values()) (k == 1 ? pair.singleInvocations : pair.multiInvocations).increment();
				if (child.byListener.size() > 1) {
					pair.duplicated.increment();
					if (pair.duplicatedExample == null) pair.duplicatedExample = child.byListener.size() + " listeners of one "
							+ frame.type.getName() + " forwarded " + entry.getKey().getName();
				}
				if (child.failed > 0) pair.innerFailures.increment();
				if (child.cancelled > 0) {
					pair.innerCancelled.increment();
					if (!cancellable) continue;
					if (cancelled) pair.cancelCarried.increment();
					else {
						pair.cancelLost.increment();
						if (pair.cancelLostExample == null) pair.cancelLostExample = entry.getKey().getName() + " was cancelled, " + frame.type.getName() + " was not";
					}
				}
			}
		}
		for (var entry : frame.incidental.entrySet())
			INCIDENTAL.computeIfAbsent(outerKey + " -> " + key(!neo, entry.getKey()), k -> new LongAdder()).add(entry.getValue());
		Frame parent = stack.peek();
		if (parent != null && parent.neo != neo) {
			if (!frame.kernelPoster) { parent.incidental.merge(frame.type, 1, Integer::sum); return; }
			Child child = parent.children.computeIfAbsent(frame.type, k -> new Child());
			child.posts++;
			child.byListener.merge(frame.parentListener, 1, Integer::sum);
			if (cancelled) child.cancelled++;
			if (failure != null) child.failed++;
		}
	}

	private static String key(boolean neo, Class<?> type) { return (neo ? "NEO:" : "FORGE:") + type.getName(); }

	private static boolean cancellable(Object event) { return probe(event.getClass()).isPresent(); }

	private static boolean cancelled(Object event) {
		Optional<Method> probe = probe(event.getClass());
		if (probe.isEmpty()) return false;
		try { return (boolean) probe.get().invoke(event); }
		catch (ReflectiveOperationException | RuntimeException unreadable) { return false; }
	}

	private static Optional<Method> probe(Class<?> type) {
		return CANCEL_PROBES.computeIfAbsent(type, t -> {
			for (Class<?> c = t; c != null; c = c.getSuperclass())
				for (Class<?> i : allInterfaces(c)) if (i.getName().equals(NEO_CANCELLABLE)) {
					try { return Optional.of(i.getMethod("isCanceled")); } catch (NoSuchMethodException none) { return Optional.empty(); }
				}
			return Optional.empty();
		});
	}

	private static Set<Class<?>> allInterfaces(Class<?> type) {
		Set<Class<?>> all = new LinkedHashSet<>();
		ArrayDeque<Class<?>> pending = new ArrayDeque<>(List.of(type.getInterfaces()));
		while (!pending.isEmpty()) { Class<?> next = pending.poll(); if (all.add(next)) pending.addAll(List.of(next.getInterfaces())); }
		return all;
	}

	/** Totals over every pair: the numbers a gate asserts are zero. */
	public record Violations(long duplicated, long multiplicityDrift, long cancelLost, long innerFailures, long unbalanced) {
		public boolean clean() { return duplicated == 0 && multiplicityDrift == 0 && cancelLost == 0 && innerFailures == 0 && unbalanced == 0; }
	}

	public static Violations violations() {
		long duplicated = 0, drift = 0, lost = 0, failures = 0;
		for (Pair pair : PAIRS.values()) {
			duplicated += pair.duplicated.sum(); drift += pair.drift(); lost += pair.cancelLost.sum(); failures += pair.innerFailures.sum();
		}
		return new Violations(duplicated, drift, lost, failures, UNBALANCED.sum());
	}

	static int pairCount() { return PAIRS.size(); }

	static Pair pair(String outer, String inner) { return PAIRS.get(outer + " -> " + inner); }

	static Outer outer(String key) { return OUTERS.get(key); }

	static long incidental(String outer, String inner) { LongAdder n = INCIDENTAL.get(outer + " -> " + inner); return n == null ? 0 : n.sum(); }

	static void reset() { PAIRS.clear(); OUTERS.clear(); FORWARDED_OUTERS.clear(); INCIDENTAL.clear(); UNBALANCED.reset(); STACK.remove(); }

	public static synchronized String json() {
		Violations v = violations();
		StringBuilder out = new StringBuilder("{\n  \"schemaVersion\": 1,\n");
		out.append("  \"violations\": {\"duplicated\": ").append(v.duplicated()).append(", \"multiplicityDrift\": ").append(v.multiplicityDrift())
				.append(", \"cancelLost\": ").append(v.cancelLost())
				.append(", \"innerFailures\": ").append(v.innerFailures()).append(", \"unbalanced\": ").append(v.unbalanced()).append("},\n");
		out.append("  \"pairs\": [");
		List<String> keys = new ArrayList<>(PAIRS.keySet()); Collections.sort(keys);
		for (int i = 0; i < keys.size(); i++) {
			Pair p = PAIRS.get(keys.get(i)); String[] ends = keys.get(i).split(" -> ");
			out.append(i == 0 ? "\n" : ",\n").append("    {\"outer\": ").append(quote(ends[0])).append(", \"inner\": ").append(quote(ends[1]))
					.append(", \"outerPosts\": ").append(p.outerPosts.sum()).append(", \"forwardedOnce\": ").append(p.forwardedOnce.sum())
					.append(", \"duplicated\": ").append(p.duplicated.sum()).append(", \"innerCancelled\": ").append(p.innerCancelled.sum())
					.append(", \"cancelCarried\": ").append(p.cancelCarried.sum()).append(", \"cancelLost\": ").append(p.cancelLost.sum())
					.append(", \"innerFailures\": ").append(p.innerFailures.sum()).append(", \"singleInvocations\": ").append(p.singleInvocations.sum())
					.append(", \"multiInvocations\": ").append(p.multiInvocations.sum()).append(", \"multiplicityDrift\": ").append(p.drift());
			if (p.duplicatedExample != null) out.append(", \"duplicatedExample\": ").append(quote(p.duplicatedExample));
			if (p.cancelLostExample != null) out.append(", \"cancelLostExample\": ").append(quote(p.cancelLostExample));
			out.append('}');
		}
		out.append(keys.isEmpty() ? "],\n" : "\n  ],\n").append("  \"forwardedOuters\": [");
		List<String> outers = new ArrayList<>(FORWARDED_OUTERS); Collections.sort(outers);
		for (int i = 0; i < outers.size(); i++) {
			Outer o = OUTERS.get(outers.get(i)); if (o == null) continue;
			out.append(i == 0 ? "\n" : ",\n").append("    {\"event\": ").append(quote(outers.get(i))).append(", \"posts\": ").append(o.posts.sum())
					.append(", \"withoutForward\": ").append(o.withoutForward.sum()).append(", \"withoutForwardCancelled\": ")
					.append(o.withoutForwardCancelled.sum()).append(", \"failures\": ").append(o.failures.sum()).append('}');
		}
		out.append(outers.isEmpty() ? "],\n" : "\n  ],\n").append("  \"incidental\": [");
		List<String> incidental = new ArrayList<>(INCIDENTAL.keySet()); Collections.sort(incidental);
		for (int i = 0; i < incidental.size(); i++) {
			String[] ends = incidental.get(i).split(" -> ");
			out.append(i == 0 ? "\n" : ",\n").append("    {\"outer\": ").append(quote(ends[0])).append(", \"inner\": ").append(quote(ends[1]))
					.append(", \"posts\": ").append(INCIDENTAL.get(incidental.get(i)).sum()).append('}');
		}
		return out.append(incidental.isEmpty() ? "]\n}\n" : "\n  ]\n}\n").toString();
	}

	/** Writes the report to the path the property names. Never throws: the audit must not end a session. */
	public static synchronized void write() {
		if (!enabled()) return;
		try {
			Path target = Path.of(System.getProperty(PROPERTY));
			if (target.getParent() != null) Files.createDirectories(target.getParent());
			Files.writeString(target, json(), StandardCharsets.UTF_8);
			Violations v = violations();
			ForbricLog.info("[Forbric/EventChain] %d cross-bus pair(s) observed; duplicated=%d multiplicityDrift=%d cancelLost=%d innerFailures=%d unbalanced=%d -> %s",
					pairCount(), v.duplicated(), v.multiplicityDrift(), v.cancelLost(), v.innerFailures(), v.unbalanced(), target);
		} catch (IOException | RuntimeException failed) {
			ForbricLog.warn("[Forbric/EventChain] could not write the event-chain report: %s", String.valueOf(failed));
		}
	}

	private static String quote(String text) {
		StringBuilder out = new StringBuilder("\"");
		for (char c : text.toCharArray()) {
			if (c == '"' || c == '\\') out.append('\\').append(c);
			else if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
			else out.append(c);
		}
		return out.append('"').toString();
	}
}
