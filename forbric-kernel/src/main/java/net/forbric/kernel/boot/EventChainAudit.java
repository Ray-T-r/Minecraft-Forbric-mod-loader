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
import net.forbric.kernel.transform.NativeDualEventInjector;
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
 * <p>A bridge that stands down because the merged game delivered the MinecraftForge event itself on the same path
 * ({@code NativeEventDelivery}) forwards nothing, so it leaves no pair. It reports the stand-down here instead
 * ({@link #nativelyDelivered}), and the report lists those native deliveries per (outer event, inner event) pair. A
 * stand-down is the bridge's own account, not an observation, so the audit checks it against the buses: the game's
 * own MinecraftForge dispatch of that event is a dispatch of the same class made by the same game method as the
 * NeoForge one, a sibling of it (same thread, same enclosing dispatch or none), from the call site on the other side
 * of it in that method's code, within the last few such dispatches. Each stand-down paired with one is counted as
 * {@code observed}. The same pairing finds a forward the game ALSO delivered natively — the stand-down that never
 * happened, because nothing recognised the game's call — and counts it on the forwarding pair as {@code withNative}.
 * Both that and a dispatch in which one bridge stood down for an event while kernel code forwarded it anyway are
 * {@code nativeAndForwarded}: the event reached MinecraftForge's listeners twice.
 *
 * <p>A stand-down for a native call the game may still bypass ({@code NativeEventDelivery}'s faithful deliveries) is
 * settled where the game bypasses it: {@code excused} where MinecraftForge's own body bypasses its call there too, and a
 * {@code deferred} forward where only the merged game does. So every stand-down ends one of three ways — observed beside
 * the game's own dispatch, excused, or forwarded late — and a deferred forward's MinecraftForge dispatch counts as a
 * forward, not as one of the game's.
 *
 * <p>A mod-bus post fans one event out to every mod's bus, one dispatch each from the same call site: those dispatches
 * are one post ({@code distinct}), and pair with the game's own MinecraftForge dispatch as one.
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
		/** MinecraftForge event classes a bridge stood down for in this dispatch, delivered natively instead. */
		Set<String> natively = Set.of();
		/** The game method that posted this (first frame outside the buses and both loaders), and the call's offset in it. */
		String caller; int callerBci = -1;
		/** Dispatches that finished directly inside this one, most recent first: see {@link #pairWithSiblings}. */
		ArrayDeque<Sibling> siblings;
		/** Entered as the root of a deferred forward: a kernel forward made after the dispatch it was owed for. */
		boolean deferredRoot;
		Frame(boolean neo, Object event) { this.neo = neo; this.event = event; this.type = event.getClass(); }
	}

	/** A finished dispatch that was not a kernel forward, kept beside its siblings for {@link #pairWithSiblings}. */
	private static final class Sibling {
		final boolean neo; final String type, outerKey, caller; final int callerBci;
		/** The event dispatched: a mod-bus fan-out dispatches the same one to every mod's bus. */
		final Object event;
		/** For a NeoForge dispatch: the MinecraftForge classes kernel code forwarded, and those a bridge stood down for. */
		Set<String> forwarded, natively;
		/** MinecraftForge classes already paired across this dispatch and a sibling. */
		Set<String> paired = Set.of();
		Sibling(Frame frame, String outerKey) {
			this.neo = frame.neo; this.type = frame.type.getName(); this.outerKey = outerKey; this.event = frame.event;
			this.caller = frame.caller; this.callerBci = frame.callerBci;
			if (frame.children.isEmpty()) this.forwarded = Set.of();
			else { Set<String> names = new HashSet<>(); for (Class<?> c : frame.children.keySet()) names.add(c.getName()); this.forwarded = names; }
			this.natively = frame.natively;
		}
		boolean relatesTo(String forge) { return forwarded.contains(forge) || natively.contains(forge); }
		void pair(String forge) { if (paired.isEmpty()) paired = new HashSet<>(2); paired.add(forge); }
		/** The next mod's dispatch of the same mod-bus post: one post, so one sibling. */
		boolean continuedBy(Frame frame) {
			return neo && frame.neo && event == frame.event && Objects.equals(caller, frame.caller) && callerBci == frame.callerBci;
		}
		void absorb(Sibling next) {
			if (!next.forwarded.isEmpty()) { forwarded = new HashSet<>(forwarded); forwarded.addAll(next.forwarded); }
			if (!next.natively.isEmpty()) { natively = new LinkedHashSet<>(natively); natively.addAll(next.natively); }
		}
	}

	/** How many finished sibling dispatches back a native MinecraftForge dispatch is looked for. */
	private static final int SIBLING_WINDOW = 8;

	private static final class Child {
		int posts, cancelled, failed;
		final Map<Integer, Integer> byListener = new LinkedHashMap<>();
	}

	static final class Pair {
		/** Forwards the game also delivered natively: a sibling MinecraftForge dispatch of the class from the same method. */
		final LongAdder withNative = new LongAdder();
		final LongAdder outerPosts = new LongAdder(), forwardedOnce = new LongAdder(), duplicated = new LongAdder(),
				innerCancelled = new LongAdder(), cancelCarried = new LongAdder(), cancelLost = new LongAdder(),
				innerFailures = new LongAdder(), singleInvocations = new LongAdder(), multiInvocations = new LongAdder();
		/** Invocations that posted more than once in a forward that is mostly one to one. */
		long drift() { long single = singleInvocations.sum(), multi = multiInvocations.sum(); return single > multi ? multi : 0; }
		volatile String duplicatedExample, cancelLostExample;
	}

	/**
	 * One (outer event, natively delivered inner event) pair: dispatches a bridge stood down in, those the game's own
	 * dispatch of the inner event was seen beside ({@link #pairWithSiblings}), and those kernel code forwarded anyway.
	 */
	static final class NativePair {
		final LongAdder outerPosts = new LongAdder(), observed = new LongAdder(), alsoForwarded = new LongAdder();
		/** Stand-downs settled where the game bypassed the native call: excused as MinecraftForge's own body does, or forwarded late. */
		final LongAdder excused = new LongAdder(), deferred = new LongAdder();
	}

	static final class Outer {
		final LongAdder posts = new LongAdder(), withoutForward = new LongAdder(), withoutForwardCancelled = new LongAdder(),
				failures = new LongAdder(), asForward = new LongAdder();
		/** Posts, a mod-bus fan-out counted once. */
		final LongAdder distinct = new LongAdder();
	}

	private static final ThreadLocal<ArrayDeque<Frame>> STACK = ThreadLocal.withInitial(ArrayDeque::new);
	/** Per thread: the stack depth a deferred forward runs at, or -1 when none is running. */
	private static final ThreadLocal<int[]> DEFERRING = ThreadLocal.withInitial(() -> new int[] {-1});
	/** Per thread: dispatches that finished outside any other dispatch, most recent first. */
	private static final ThreadLocal<ArrayDeque<Sibling>> ROOT_SIBLINGS = ThreadLocal.withInitial(ArrayDeque::new);
	private static final Map<String, Pair> PAIRS = new ConcurrentHashMap<>();
	private static final Map<String, NativePair> NATIVES = new ConcurrentHashMap<>();
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

	/**
	 * A bridge stood down for {@code neoEvent}: the merged game delivered these MinecraftForge events (internal names)
	 * natively on its path. Credited to the NeoForge dispatch of that very event on this thread.
	 */
	public static void nativelyDelivered(Object neoEvent, Set<String> forgeEvents) {
		if (neoEvent == null || forgeEvents == null || forgeEvents.isEmpty() || !enabled()) return;
		for (Frame frame : STACK.get()) {
			if (!frame.neo || frame.event != neoEvent) continue;
			if (frame.natively.isEmpty()) frame.natively = new LinkedHashSet<>();
			for (String name : forgeEvents) frame.natively.add(name.replace('/', '.'));
			return;
		}
	}

	/**
	 * A bridge stood down for {@code neoEvent} on a native call the game then bypassed where MinecraftForge's own body
	 * bypasses it too: genuine MinecraftForge would not have delivered these MinecraftForge events (internal names).
	 */
	public static void excused(Object neoEvent, Set<String> forgeEvents) {
		if (neoEvent == null || forgeEvents == null || !enabled()) return;
		for (String name : forgeEvents) nativePairOf(neoEvent, name).excused.increment();
	}

	/**
	 * A bridge stood down for {@code neoEvent} on a native call the game then bypassed where only the merged game does:
	 * {@code forward} delivers it now. Its MinecraftForge dispatch is the kernel's forward, not one of the game's.
	 */
	public static void deferredForward(Object neoEvent, Set<String> forgeEvents, Runnable forward) {
		if (neoEvent == null || forgeEvents == null || !enabled()) { forward.run(); return; }
		for (String name : forgeEvents) nativePairOf(neoEvent, name).deferred.increment();
		int[] deferring = DEFERRING.get();
		int before = deferring[0];
		deferring[0] = STACK.get().size();
		try { forward.run(); } finally { deferring[0] = before; }
	}

	private static NativePair nativePairOf(Object neoEvent, String forge) {
		return NATIVES.computeIfAbsent(key(true, neoEvent.getClass()) + " -> " + key(false, forge.replace('/', '.')), k -> new NativePair());
	}

	private static void enter(boolean neo, Object event) {
		if (event == null) return;
		if (HOOKED.compareAndSet(false, true) && enabled())
			Runtime.getRuntime().addShutdownHook(new Thread(EventChainAudit::write, "forbric-event-chain-audit"));
		ArrayDeque<Frame> stack = STACK.get();
		Frame frame = new Frame(neo, event), parent = stack.peek();
		boolean kernel = poster(frame);
		if (parent != null && parent.neo != neo) { frame.kernelPoster = kernel; frame.parentListener = parent.listener; }
		frame.deferredRoot = !neo && kernel && DEFERRING.get()[0] == stack.size();
		stack.push(frame);
	}

	private static boolean belowTheBus(String name) {
		return !name.equals(EventChainAudit.class.getName()) && !name.startsWith("net.minecraftforge.eventbus.") && !name.startsWith("net.neoforged.bus.")
				&& !name.startsWith("java.") && !name.startsWith("jdk.") && !name.startsWith("sun.") && !FACADES.contains(name);
	}

	/**
	 * One walk for both questions about who posted {@code frame}. Answers whether the first frame below the buses, the
	 * hook facades and this class is kernel code (a kernel forward), and records in the frame the first method outside
	 * the buses and both loaders' own code — the game method whose call it is, whichever family's helper made the post
	 * — with the call's bytecode offset there.
	 */
	private static boolean poster(Frame frame) {
		return WALKER.walk(frames -> {
			Boolean kernel = null;
			for (Iterator<StackWalker.StackFrame> it = frames.iterator(); it.hasNext(); ) {
				StackWalker.StackFrame f = it.next();
				String name = f.getClassName();
				if (!belowTheBus(name)) continue;
				if (kernel == null) kernel = name.startsWith("net.forbric.kernel.");
				if (name.startsWith("net.minecraftforge.") || name.startsWith("net.neoforged.")) continue;
				// The kernel's own wrapper around one call of a game method (NativeDualEventInjector's scope or recording)
				// sits between the hook and that method; the call is the method's, at the site the wrapper replaced.
				String method = f.getMethodName();
				if (method.startsWith(NativeDualEventInjector.WRAPPER_PREFIX) || method.startsWith(NativeDualEventInjector.SOURCE_PREFIX)) continue;
				// By name: the descriptor needs a walker that retains class references. Overloads of one name share it,
				// and the call-site order in pairWithSiblings still keeps their turns apart.
				frame.caller = name + "#" + method;
				frame.callerBci = f.getByteCodeIndex();
				break;
			}
			return kernel != null && kernel;
		});
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
		for (String inner : frame.natively) {
			NativePair pair = NATIVES.computeIfAbsent(outerKey + " -> FORGE:" + inner, k -> new NativePair());
			pair.outerPosts.increment();
			for (Class<?> forwarded : frame.children.keySet()) if (forwarded.getName().equals(inner)) pair.alsoForwarded.increment();
		}
		Outer outer = OUTERS.computeIfAbsent(outerKey, k -> new Outer());
		outer.posts.increment();
		Frame parent = stack.peek();
		ArrayDeque<Sibling> level = parent == null ? ROOT_SIBLINGS.get() : parent.siblings;
		Sibling previous = level == null ? null : level.peekFirst();
		boolean fanOut = previous != null && previous.continuedBy(frame);
		if (!fanOut) outer.distinct.increment();
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
		Sibling done = new Sibling(frame, outerKey);
		if (parent != null && parent.neo != neo && frame.kernelPoster) {
			outer.asForward.increment();
			Child child = parent.children.computeIfAbsent(frame.type, k -> new Child());
			child.posts++;
			child.byListener.merge(frame.parentListener, 1, Integer::sum);
			if (cancelled) child.cancelled++;
			if (failure != null) child.failed++;
			return;
		}
		if (frame.deferredRoot) {
			// A forward owed for a dispatch that has finished: the kernel's, not the game's own MinecraftForge dispatch.
			outer.asForward.increment();
			return;
		}
		if (parent != null && parent.neo != neo) parent.incidental.merge(frame.type, 1, Integer::sum);
		ArrayDeque<Sibling> siblings = parent == null ? ROOT_SIBLINGS.get()
				: parent.siblings != null ? parent.siblings : (parent.siblings = new ArrayDeque<>());
		if (fanOut) {
			// The next mod's dispatch of one mod-bus post: folded into the post's sibling, paired as one.
			siblings.removeFirst();
			previous.absorb(done);
			done = previous;
		}
		pairWithSiblings(done, siblings);
		siblings.addFirst(done);
		if (siblings.size() > SIBLING_WINDOW) siblings.removeLast();
	}

	/**
	 * Pairs a finished dispatch with the other family's dispatch of the same event that the same game method made
	 * beside it: a NeoForge dispatch that forwarded, or stood down for, MinecraftForge class F, and a MinecraftForge
	 * dispatch of F that was not a kernel forward. Siblings only — same thread, same enclosing dispatch or none — and
	 * from the same method, the earlier one's call site before the later one's in that method's code (a loop's next
	 * turn calls from the same sites again, so a later dispatch from an earlier site starts a new turn). Looked for
	 * among the last {@value #SIBLING_WINDOW} siblings, stopping at the previous dispatch of the same kind from that
	 * method, and each side pairs once. A stand-down so paired is {@code observed}; a forward so paired was delivered
	 * twice ({@code withNative}).
	 */
	private static void pairWithSiblings(Sibling done, ArrayDeque<Sibling> siblings) {
		if (done.caller == null || siblings.isEmpty()) return;
		if (!done.neo) {
			for (Sibling earlier : siblings) {
				if (!done.caller.equals(earlier.caller)) continue;
				if (earlier.neo && earlier.relatesTo(done.type)) {
					if (earlier.callerBci < done.callerBci && !earlier.paired.contains(done.type)) credit(earlier, done.type, done);
					return;
				}
				if (!earlier.neo && earlier.type.equals(done.type)) return;
			}
			return;
		}
		for (String forge : done.forwarded) pairBack(done, forge, siblings);
		for (String forge : done.natively) if (!done.forwarded.contains(forge)) pairBack(done, forge, siblings);
	}

	private static void pairBack(Sibling neo, String forge, ArrayDeque<Sibling> siblings) {
		for (Sibling earlier : siblings) {
			if (!neo.caller.equals(earlier.caller)) continue;
			if (!earlier.neo && earlier.type.equals(forge)) {
				if (earlier.callerBci < neo.callerBci && !earlier.paired.contains(forge)) credit(neo, forge, earlier);
				return;
			}
			if (earlier.neo && earlier.type.equals(neo.type)) return;
		}
	}

	private static void credit(Sibling neo, String forge, Sibling nativeForge) {
		neo.pair(forge);
		nativeForge.pair(forge);
		String inner = key(false, forge);
		if (neo.forwarded.contains(forge)) {
			Pair pair = PAIRS.get(neo.outerKey + " -> " + inner);
			if (pair != null) pair.withNative.increment();
		}
		if (neo.natively.contains(forge)) {
			NativePair pair = NATIVES.get(neo.outerKey + " -> " + inner);
			if (pair != null) pair.observed.increment();
		}
	}

	private static String key(boolean neo, Class<?> type) { return key(neo, type.getName()); }

	private static String key(boolean neo, String type) { return (neo ? "NEO:" : "FORGE:") + type; }

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
	public record Violations(long duplicated, long multiplicityDrift, long cancelLost, long innerFailures, long unbalanced,
			long nativeAndForwarded) {
		public boolean clean() {
			return duplicated == 0 && multiplicityDrift == 0 && cancelLost == 0 && innerFailures == 0 && unbalanced == 0
					&& nativeAndForwarded == 0;
		}
	}

	public static Violations violations() {
		long duplicated = 0, drift = 0, lost = 0, failures = 0, twice = 0;
		for (Pair pair : PAIRS.values()) {
			duplicated += pair.duplicated.sum(); drift += pair.drift(); lost += pair.cancelLost.sum(); failures += pair.innerFailures.sum();
			twice += pair.withNative.sum();
		}
		for (NativePair pair : NATIVES.values()) twice += pair.alsoForwarded.sum();
		return new Violations(duplicated, drift, lost, failures, UNBALANCED.sum(), twice);
	}

	static int pairCount() { return PAIRS.size(); }

	static Pair pair(String outer, String inner) { return PAIRS.get(outer + " -> " + inner); }

	static NativePair nativePair(String outer, String inner) { return NATIVES.get(outer + " -> " + inner); }

	static Outer outer(String key) { return OUTERS.get(key); }

	static long incidental(String outer, String inner) { LongAdder n = INCIDENTAL.get(outer + " -> " + inner); return n == null ? 0 : n.sum(); }

	static void reset() {
		PAIRS.clear(); NATIVES.clear(); OUTERS.clear(); FORWARDED_OUTERS.clear(); INCIDENTAL.clear(); UNBALANCED.reset(); STACK.remove();
		ROOT_SIBLINGS.remove(); DEFERRING.remove();
	}

	public static synchronized String json() {
		Violations v = violations();
		StringBuilder out = new StringBuilder("{\n  \"schemaVersion\": 1,\n");
		out.append("  \"violations\": {\"duplicated\": ").append(v.duplicated()).append(", \"multiplicityDrift\": ").append(v.multiplicityDrift())
				.append(", \"cancelLost\": ").append(v.cancelLost())
				.append(", \"innerFailures\": ").append(v.innerFailures()).append(", \"unbalanced\": ").append(v.unbalanced())
				.append(", \"nativeAndForwarded\": ").append(v.nativeAndForwarded()).append("},\n");
		out.append("  \"pairs\": [");
		List<String> keys = new ArrayList<>(PAIRS.keySet()); Collections.sort(keys);
		for (int i = 0; i < keys.size(); i++) {
			Pair p = PAIRS.get(keys.get(i)); String[] ends = keys.get(i).split(" -> ");
			out.append(i == 0 ? "\n" : ",\n").append("    {\"outer\": ").append(quote(ends[0])).append(", \"inner\": ").append(quote(ends[1]))
					.append(", \"outerPosts\": ").append(p.outerPosts.sum()).append(", \"forwardedOnce\": ").append(p.forwardedOnce.sum())
					.append(", \"duplicated\": ").append(p.duplicated.sum()).append(", \"innerCancelled\": ").append(p.innerCancelled.sum())
					.append(", \"cancelCarried\": ").append(p.cancelCarried.sum()).append(", \"cancelLost\": ").append(p.cancelLost.sum())
					.append(", \"innerFailures\": ").append(p.innerFailures.sum()).append(", \"singleInvocations\": ").append(p.singleInvocations.sum())
					.append(", \"multiInvocations\": ").append(p.multiInvocations.sum()).append(", \"multiplicityDrift\": ").append(p.drift())
					.append(", \"withNative\": ").append(p.withNative.sum());
			if (p.duplicatedExample != null) out.append(", \"duplicatedExample\": ").append(quote(p.duplicatedExample));
			if (p.cancelLostExample != null) out.append(", \"cancelLostExample\": ").append(quote(p.cancelLostExample));
			out.append('}');
		}
		out.append(keys.isEmpty() ? "],\n" : "\n  ],\n").append("  \"natives\": [");
		List<String> natives = new ArrayList<>(NATIVES.keySet()); Collections.sort(natives);
		for (int i = 0; i < natives.size(); i++) {
			NativePair p = NATIVES.get(natives.get(i)); String[] ends = natives.get(i).split(" -> ");
			out.append(i == 0 ? "\n" : ",\n").append("    {\"outer\": ").append(quote(ends[0])).append(", \"inner\": ").append(quote(ends[1]))
					.append(", \"outerPosts\": ").append(p.outerPosts.sum()).append(", \"observed\": ").append(p.observed.sum())
					.append(", \"alsoForwarded\": ").append(p.alsoForwarded.sum()).append(", \"excused\": ").append(p.excused.sum())
					.append(", \"deferred\": ").append(p.deferred.sum()).append('}');
		}
		out.append(natives.isEmpty() ? "],\n" : "\n  ],\n").append("  \"outers\": [");
		// Every dispatch of every event either bus saw, so a gate can put a pair's counts against all of its outer
		// event's posts and a native pair's against all of its inner event's dispatches that were not kernel forwards.
		List<String> all = new ArrayList<>(OUTERS.keySet()); Collections.sort(all);
		for (int i = 0; i < all.size(); i++) {
			Outer o = OUTERS.get(all.get(i));
			out.append(i == 0 ? "\n" : ",\n").append("    {\"event\": ").append(quote(all.get(i))).append(", \"posts\": ").append(o.posts.sum())
					.append(", \"asForward\": ").append(o.asForward.sum()).append(", \"withoutForwardCancelled\": ")
					.append(o.withoutForwardCancelled.sum()).append(", \"distinct\": ").append(o.distinct.sum()).append('}');
		}
		out.append(all.isEmpty() ? "],\n" : "\n  ],\n").append("  \"forwardedOuters\": [");
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
			ForbricLog.info("[Forbric/EventChain] %d cross-bus pair(s) observed, %d delivered natively; duplicated=%d multiplicityDrift=%d cancelLost=%d innerFailures=%d unbalanced=%d nativeAndForwarded=%d -> %s",
					pairCount(), NATIVES.size(), v.duplicated(), v.multiplicityDrift(), v.cancelLost(), v.innerFailures(), v.unbalanced(),
					v.nativeAndForwarded(), target);
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
