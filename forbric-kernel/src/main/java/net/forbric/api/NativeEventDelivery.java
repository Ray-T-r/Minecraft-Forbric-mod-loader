/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.api;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Which MinecraftForge events the merged game is delivering ITSELF, on the path a NeoForge event is being posted from,
 * so that a bridge forwarding that NeoForge event to its MinecraftForge counterpart can stand down instead of handing
 * a mod the same event twice.
 *
 * <h2>The shape it answers</h2>
 *
 * <p>A bridge listens to a NeoForge event and posts the MinecraftForge one, because the merge used to keep only one
 * family's hook at a site. Where a merged method now calls BOTH families' hooks on one path, the MinecraftForge event
 * is posted natively and forwarded as well. {@code NativeDualEventInjector} wraps the NeoForge call on such a path in a
 * {@link Scope} naming the MinecraftForge events delivered natively there, and the NeoForge events the call constructs
 * are captured into the scope by identity; a bridge's listener asks {@link #covered} with the event it was handed.
 *
 * <p>Two sources name those MinecraftForge events. A MinecraftForge delivery that always follows the NeoForge one is
 * proved from the caller's control flow and named in the scope directly. One that ran EARLIER in the same invocation
 * is recorded as it happens ({@link #record}): what the MinecraftForge hook actually constructed, so a hook that posts
 * only under a condition, or a call both families guard with the same test, covers exactly the times it delivered.
 * The record is per caller method and per thread, cleared when the method is entered ({@link #enterNative}), and
 * spent when a bridge stands down on its account, so a NeoForge post repeated without a new MinecraftForge one is
 * forwarded again.
 *
 * <p>Identity, not class: a listener of the outer event may post another event of the same class, and that one has no
 * native counterpart. Construction inside a wrapped hook is captured only while that hook is not itself dispatching
 * ({@link #dispatching}), so an event a listener constructs is never taken for the hook's own. Caller and mod names
 * never enter into it.
 */
public final class NativeEventDelivery {
	private static final ThreadLocal<ArrayDeque<Scope>> SCOPES = new ThreadLocal<>();
	private static final ThreadLocal<ArrayDeque<Recording>> RECORDINGS = new ThreadLocal<>();
	/** Per thread: caller group → the MinecraftForge events recorded as delivered since that caller was entered. */
	private static final ThreadLocal<Map<String, Set<String>>> PENDING = new ThreadLocal<>();
	/** Scopes and recordings open on any thread: the hot paths read these volatiles and return while nothing is open. */
	private static final AtomicInteger OPEN = new AtomicInteger(), RECORDING = new AtomicInteger();
	/** The {@code ;}-joined event lists wrappers pass, parsed once each. */
	private static final Map<String, Set<String>> LISTS = new ConcurrentHashMap<>();
	/** NeoForge hook owners whose event constructions and dispatches report here. */
	private static final Set<String> INSTRUMENTED = ConcurrentHashMap.newKeySet();

	private NativeEventDelivery() {
	}

	/**
	 * Records that the NeoForge hook class {@code owner} (internal name) reports its event constructions and
	 * dispatches. A scope opened around a call into a hook that does not would capture whatever a listener constructed
	 * first, so {@link #begin(String, String, String, Class)} opens an inert scope for it instead.
	 */
	public static void instrumented(String owner) {
		INSTRUMENTED.add(Objects.requireNonNull(owner));
	}

	/** Whether {@code owner} (internal name) was instrumented in this run. */
	public static boolean isInstrumented(String owner) {
		return INSTRUMENTED.contains(owner);
	}

	private static Set<String> list(String joined) {
		return LISTS.computeIfAbsent(joined == null ? "" : joined,
				value -> value.isEmpty() ? Set.of() : Set.of(value.split(";")));
	}

	// ------------------------------------------------------------------ the caller's native MinecraftForge deliveries

	/** Inserted at the entry of a caller with recorded deliveries: nothing an earlier invocation recorded carries over. */
	public static void enterNative(String group) {
		Map<String, Set<String>> pending = PENDING.get();
		if (pending != null && pending.remove(group) != null && pending.isEmpty()) PENDING.remove();
	}

	/**
	 * Opens a recording around one MinecraftForge delivery in caller {@code group}: which of {@code candidates} the call
	 * actually constructs (at its own level, not its listeners') is what {@link Recording#close} commits.
	 */
	public static Recording record(String group, String candidates) {
		ArrayDeque<Recording> recordings = RECORDINGS.get();
		if (recordings == null) RECORDINGS.set(recordings = new ArrayDeque<>());
		Recording recording = new Recording(group, list(candidates), Thread.currentThread());
		recordings.addLast(recording);
		RECORDING.incrementAndGet();
		return recording;
	}

	private static Recording innermostRecording() {
		if (RECORDING.get() == 0) return null;
		ArrayDeque<Recording> recordings = RECORDINGS.get();
		return recordings == null ? null : recordings.peekLast();
	}

	/** Inserted right after an instrumented MinecraftForge hook constructs an event. */
	public static void nativeConstructed(Object event) {
		Recording recording = innermostRecording();
		if (recording != null && recording.depth == 0 && event != null) recording.add(event);
	}

	/** Inserted right before an instrumented MinecraftForge hook posts. */
	public static void nativeDispatching() {
		Recording recording = innermostRecording();
		if (recording != null) recording.depth++;
	}

	/** Inserted right after an instrumented MinecraftForge hook's post returns normally. */
	public static void nativeDispatched() {
		Recording recording = innermostRecording();
		if (recording != null && recording.depth > 0) recording.depth--;
	}

	/** One MinecraftForge delivery being recorded. */
	public static final class Recording implements AutoCloseable {
		private final String group;
		private final Set<String> candidates;
		private final Thread thread;
		private Set<String> delivered = Set.of();
		private int depth;
		private boolean closed;

		private Recording(String group, Set<String> candidates, Thread thread) {
			this.group = group;
			this.candidates = candidates;
			this.thread = thread;
		}

		/** An inline post hands over the event it is about to dispatch, and seals the recording. */
		public void delivered(Object event) {
			if (event != null) add(event);
			depth++;
		}

		private void add(Object event) {
			String name = event.getClass().getName().replace('.', '/');
			if (!candidates.contains(name) || delivered.contains(name)) return;
			if (delivered.isEmpty()) delivered = new HashSet<>(2);
			delivered.add(name);
		}

		@Override
		public void close() {
			if (closed) return;
			ArrayDeque<Recording> recordings = RECORDINGS.get();
			if (Thread.currentThread() != thread || recordings == null || recordings.peekLast() != this) {
				throw new IllegalStateException("Native delivery recordings must close in invocation order");
			}
			recordings.removeLast();
			closed = true;
			RECORDING.decrementAndGet();
			if (recordings.isEmpty()) RECORDINGS.remove();
			if (delivered.isEmpty()) return;
			Map<String, Set<String>> pending = PENDING.get();
			if (pending == null) PENDING.set(pending = new HashMap<>());
			pending.computeIfAbsent(group, k -> new HashSet<>(2)).addAll(delivered);
		}
	}

	// ------------------------------------------------------------------ the NeoForge delivery and its scope

	/**
	 * Opens a scope around one call into the NeoForge hook class {@code hook}, whose own constructed events the hook
	 * reports.
	 *
	 * @param natively   MinecraftForge events a delivery proved to follow on every path delivers, {@code ;}-joined
	 * @param group      the caller whose recorded deliveries this may consume
	 * @param candidates the recorded MinecraftForge events that may cover this delivery, {@code ;}-joined
	 * @param hook       the class the wrapped call goes to; loading it here is what lets an instrumentation that has
	 *                   not run be told apart from one that has
	 */
	public static Scope begin(String natively, String group, String candidates, Class<?> hook) {
		boolean live = hook != null && INSTRUMENTED.contains(hook.getName().replace('.', '/'));
		return live ? open(list(natively), group, candidates) : open(Set.of(), null, "");
	}

	/** The same for an inline post, whose event the wrapper hands over itself with {@link Scope#capture}. */
	public static Scope begin(String natively, String group, String candidates) {
		return open(list(natively), group, candidates);
	}

	private static Scope open(Set<String> proved, String group, String candidates) {
		Set<String> recorded = Set.of();
		Map<String, Set<String>> pending = group == null ? null : PENDING.get();
		Set<String> delivered = pending == null ? null : pending.get(group);
		if (delivered != null && !delivered.isEmpty()) {
			Set<String> taken = new HashSet<>(list(candidates));
			taken.retainAll(delivered);
			if (!taken.isEmpty()) recorded = taken;
		}
		Set<String> covers = proved;
		if (!recorded.isEmpty()) {
			covers = new HashSet<>(recorded);
			covers.addAll(proved);
		}
		ArrayDeque<Scope> scopes = SCOPES.get();
		if (scopes == null) SCOPES.set(scopes = new ArrayDeque<>());
		Scope scope = new Scope(covers, group, recorded, Thread.currentThread());
		scopes.addLast(scope);
		OPEN.incrementAndGet();
		return scope;
	}

	private static Scope innermost() {
		if (OPEN.get() == 0) return null;
		ArrayDeque<Scope> scopes = SCOPES.get();
		return scopes == null ? null : scopes.peekLast();
	}

	/** Inserted right after an instrumented NeoForge hook constructs an event: captured while it is not dispatching. */
	public static void constructed(Object event) {
		Scope scope = innermost();
		if (scope != null && scope.depth == 0 && event != null) scope.add(event);
	}

	/** Inserted right before an instrumented NeoForge hook posts: what listeners construct from here on is theirs. */
	public static void dispatching() {
		Scope scope = innermost();
		if (scope != null) scope.depth++;
	}

	/** Inserted right after an instrumented NeoForge hook's post returns normally. */
	public static void dispatched() {
		Scope scope = innermost();
		if (scope != null && scope.depth > 0) scope.depth--;
	}

	/**
	 * Whether {@code event} is one a scope on this thread captured, on a path that natively delivers any of
	 * {@code forgeEvents}. A bridge whose MinecraftForge events these are stands down for exactly this event.
	 */
	public static boolean covered(Object event, Set<String> forgeEvents) {
		if (event == null || forgeEvents == null || forgeEvents.isEmpty() || OPEN.get() == 0) return false;
		ArrayDeque<Scope> scopes = SCOPES.get();
		if (scopes == null) return false;
		for (Scope scope : scopes) {
			if (scope.holds(event) && !Collections.disjoint(scope.covers, forgeEvents)) {
				scope.use(forgeEvents);
				return true;
			}
		}
		return false;
	}

	/** One wrapped NeoForge call: the MinecraftForge events its path delivers natively, and the events it posted. */
	public static final class Scope implements AutoCloseable {
		private final Set<String> covers;
		private final String group;
		private final Set<String> recorded;
		private final Thread thread;
		private Object[] events = new Object[2];
		private Set<String> used = Set.of();
		private int size;
		private int depth;
		private boolean closed;

		private Scope(Set<String> covers, String group, Set<String> recorded, Thread thread) {
			this.covers = covers;
			this.group = group;
			this.recorded = recorded;
			this.thread = thread;
		}

		/**
		 * A bridge stood down on this scope's account: a recorded delivery it relied on is spent, so a NeoForge post the
		 * same caller makes again without a new MinecraftForge one is forwarded. Spent only when used, so an unrelated
		 * NeoForge delivery between the two does not take it from the one it belongs to.
		 */
		private void use(Set<String> forgeEvents) {
			if (recorded.isEmpty()) return;
			for (String event : forgeEvents) {
				if (!recorded.contains(event) || used.contains(event)) continue;
				if (used.isEmpty()) used = new HashSet<>(2);
				used.add(event);
			}
		}

		/** The MinecraftForge events this path delivers natively. */
		public Set<String> covers() {
			return covers;
		}

		/**
		 * Hands this scope the event an inline post is about to dispatch, and seals it: nothing a listener constructs
		 * while that post runs is captured.
		 */
		public void capture(Object event) {
			if (event != null) add(event);
			depth++;
		}

		private void add(Object event) {
			if (holds(event)) return;
			if (size == events.length) events = Arrays.copyOf(events, size * 2);
			events[size++] = event;
		}

		private boolean holds(Object event) {
			for (int i = 0; i < size; i++) if (events[i] == event) return true;
			return false;
		}

		@Override
		public void close() {
			if (closed) return;
			ArrayDeque<Scope> scopes = SCOPES.get();
			if (Thread.currentThread() != thread || scopes == null || scopes.peekLast() != this) {
				throw new IllegalStateException("Native delivery scopes must close in invocation order");
			}
			scopes.removeLast();
			closed = true;
			OPEN.decrementAndGet();
			if (scopes.isEmpty()) SCOPES.remove();
			if (used.isEmpty()) return;
			Map<String, Set<String>> pending = PENDING.get();
			Set<String> delivered = pending == null ? null : pending.get(group);
			if (delivered == null) return;
			delivered.removeAll(used);
			if (delivered.isEmpty()) {
				pending.remove(group);
				if (pending.isEmpty()) PENDING.remove();
			}
		}
	}
}
