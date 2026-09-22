package net.forbric.kernel.runtime.transfer;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.List;

import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;

/**
 * Couples REAL Fabric and NeoForge transactions. No transaction context is simulated: NeoForge journals cast
 * their context to their final Transaction class. The narrow lifecycle hooks close the peer at the same nesting
 * boundary, and defer final notifications until BOTH engines have closed. Cross-API transfer from close/final
 * callbacks is deliberately rejected; arbitrary external side effects are not made reversible by this bridge.
 */
public final class PairedTransactions {
	private PairedTransactions() { }
	private static final ThreadLocal<State> LOCAL = ThreadLocal.withInitial(State::new);
	private static volatile boolean hooksChecked;
	private static final class State {
		final IdentityHashMap<Object, Pair> pairs = new IdentityHashMap<>();
		final IdentityHashMap<Object, IdentityHashMap<Object, Runnable>> validations = new IdentityHashMap<>();
		final ArrayDeque<Runnable> finals = new ArrayDeque<>();
		int closing;
		boolean flushing;
	}
	private static final class Pair {
		final Transaction fabric;
		final net.neoforged.neoforge.transfer.transaction.Transaction neo;
		Object origin;
		boolean committed;
		boolean closingPeer;
		Pair(Transaction fabric, net.neoforged.neoforge.transfer.transaction.Transaction neo) {
			this.fabric = fabric;
			this.neo = neo;
		}
	}

	/** A native NeoForge scope paired with the supplied currently-open native Fabric scope. */
	public static net.neoforged.neoforge.transfer.transaction.TransactionContext neo(TransactionContext context) {
		State state = usable();
		if (context != Transaction.getCurrentUnsafe()) throw new IllegalArgumentException("Fabric context is not the current transaction");
		Pair existing = state.pairs.get(context);
		if (existing != null) { checkPeer(existing, context); return existing.neo; }
		for (int depth = 0; depth <= context.nestingDepth(); depth++) {
			Transaction fabric = context.getOpenTransaction(depth);
			if (state.pairs.containsKey(fabric)) continue;
			Pair parent = depth == 0 ? null : state.pairs.get(context.getOpenTransaction(depth - 1));
			var current = net.neoforged.neoforge.transfer.transaction.Transaction.getCurrentOpenedTransaction();
			if (current != (parent == null ? null : parent.neo)) {
				throw new IllegalStateException("An unrelated NeoForge transaction is already open");
			}
			var neo = net.neoforged.neoforge.transfer.transaction.Transaction.open(current);
			link(state, new Pair(fabric, neo));
		}
		return state.pairs.get(context).neo;
	}

	/** A native Fabric scope paired with the supplied currently-open native NeoForge scope. */
	public static TransactionContext fabric(net.neoforged.neoforge.transfer.transaction.TransactionContext context) {
		State state = usable();
		if (context != net.neoforged.neoforge.transfer.transaction.Transaction.getCurrentOpenedTransaction()) {
			throw new IllegalArgumentException("NeoForge context is not the current transaction");
		}
		Pair existing = state.pairs.get(context);
		if (existing != null) { checkPeer(existing, context); return existing.fabric; }
		List<net.neoforged.neoforge.transfer.transaction.Transaction> ancestors = NeoAccess.ancestors(context);
		for (int depth = 0; depth <= context.depth(); depth++) {
			var neo = ancestors.get(depth);
			if (state.pairs.containsKey(neo)) continue;
			Pair parent = depth == 0 ? null : state.pairs.get(ancestors.get(depth - 1));
			TransactionContext current = Transaction.isOpen() ? Transaction.getCurrentUnsafe() : null;
			if (current != (parent == null ? null : parent.fabric)) {
				throw new IllegalStateException("An unrelated Fabric transaction is already open");
			}
			Transaction fabric = parent == null ? Transaction.openOuter() : parent.fabric.openNested();
			link(state, new Pair(fabric, neo));
		}
		return state.pairs.get(context).fabric;
	}

	private static void link(State state, Pair pair) {
		state.pairs.put(pair.fabric, pair);
		state.pairs.put(pair.neo, pair);
	}

	private static State usable() {
		checkHooks();
		State state = LOCAL.get();
		if (state.closing != 0 || state.flushing) throw new IllegalStateException("Cross-API transfer during a transaction close callback is unsupported");
		return state;
	}

	/** Resource-specific pre-commit invariants, attached to the real NeoForge root (including nested-only use). */
	public static void addValidation(net.neoforged.neoforge.transfer.transaction.TransactionContext context,
			Object owner, Runnable validation) {
		State state = usable();
		if (context != net.neoforged.neoforge.transfer.transaction.Transaction.getCurrentOpenedTransaction())
			throw new IllegalArgumentException("NeoForge context is not the current transaction");
		Object root = NeoAccess.ancestors(context).get(0);
		state.validations.computeIfAbsent(root, ignored -> new IdentityHashMap<>()).put(owner, validation);
	}
	public static void removeValidation(Object owner) {
		State state = LOCAL.get();
		state.validations.values().forEach(group -> group.remove(owner));
		state.validations.values().removeIf(IdentityHashMap::isEmpty);
	}
	private static void checkPeer(Pair pair, Object origin) {
		if (origin == pair.fabric) {
			if (net.neoforged.neoforge.transfer.transaction.Transaction.getCurrentOpenedTransaction() != pair.neo)
				throw new IllegalStateException("Paired NeoForge scope is not current; close its child before closing/using the Fabric scope");
		} else if (!Transaction.isOpen() || Transaction.getCurrentUnsafe() != pair.fabric) {
			throw new IllegalStateException("Paired Fabric scope is not current; close its child before closing/using the NeoForge scope");
		}
	}
	/** Invoked before either native manager changes its depth; final callbacks after both roots close may open native-only scopes. */
	public static void beforeOpen() {
		if (LOCAL.get().closing != 0) throw new IllegalStateException("A transaction close callback cannot open a new native scope during a paired close");
	}

	/** Called after the native engine validates its current scope, before it changes any snapshots. */
	public static void beforeClose(Object transaction, boolean committed) {
		State state = LOCAL.get();
		Pair pair = state.pairs.get(transaction);
		if (pair != null && pair.origin != null && pair.origin != transaction && !pair.closingPeer)
			throw new IllegalStateException("Only the transaction coordinator may close the peer during a paired close");
		// The peer must be closable BEFORE the native source commits or aborts. Checking only in afterClose
		// would leave one engine committed when an unpaired peer child makes the other root refuse to close.
		if (pair != null && pair.origin == null) checkPeer(pair, transaction);
		if (committed) {
			Object neo = transaction instanceof net.neoforged.neoforge.transfer.transaction.Transaction ? transaction : pair == null ? null : pair.neo;
			if (neo != null) {
				Object root = NeoAccess.ancestors(neo).get(0);
				var validations = state.validations.get(root);
				if (validations != null) for (Runnable validation : List.copyOf(validations.values())) validation.run();
			}
		}
		// A validator failure leaves the scope and its checks intact; a later abort can still restore it.
		state.validations.remove(transaction);
		if (pair == null) return;
		if (pair.origin == null) {
			pair.origin = transaction;
			pair.committed = committed;
			state.closing++;
		} else if (pair.committed != committed) {
			throw new IllegalStateException("Paired transactions disagree about commit/abort");
		}
	}

	/** The native close ran, including its snapshot callbacks. Always release the paired scope, even on failure. */
	public static void afterClose(Object transaction, Throwable nativeFailure) {
		State state = LOCAL.get();
		Pair pair = state.pairs.get(transaction);
		if (pair == null || pair.origin != transaction) return;
		Throwable failure = nativeFailure;
		try {
			pair.closingPeer = true;
			if (transaction == pair.fabric) {
				if (pair.committed) pair.neo.commit(); else pair.neo.close();
			} else {
				if (pair.committed) pair.fabric.commit(); else pair.fabric.abort();
			}
		} catch (Throwable peerFailure) {
			failure = combine(failure, peerFailure);
		} finally {
			pair.closingPeer = false;
			state.pairs.remove(pair.fabric);
			state.pairs.remove(pair.neo);
			state.closing--;
		}
		if (state.pairs.isEmpty() && state.closing == 0) {
			state.flushing = true;
			try {
				while (!state.finals.isEmpty()) {
					try { state.finals.removeFirst().run(); }
					catch (Throwable notificationFailure) { failure = combine(failure, notificationFailure); }
				}
			} finally {
				state.flushing = false;
				LOCAL.remove();
			}
		}
		if (nativeFailure == null && failure != null) rethrow(failure);
	}

	/** Replaces only OuterCloseCallback invocation, preserving its original order within each native engine. */
	public static void fabricFinal(Object callback, Object result) {
		enqueueOrRun(() -> ((TransactionContext.OuterCloseCallback) callback)
				.afterOuterClose((TransactionContext.Result) result));
	}

	/** Replaces only the NeoForge manager's callOnRootCommit dispatch; the journal itself remains native. */
	public static void neoFinal(Object journal) {
		enqueueOrRun(() -> NeoAccess.finish(journal));
	}

	private static void enqueueOrRun(Runnable callback) {
		State state = LOCAL.get();
		if (!state.pairs.isEmpty() || state.closing != 0) state.finals.addLast(callback);
		else callback.run();
	}

	private static Throwable combine(Throwable first, Throwable second) {
		if (first == null) return second;
		if (first != second) first.addSuppressed(second);
		return first;
	}
	private static void rethrow(Throwable failure) {
		if (failure instanceof Error error) throw error;
		if (failure instanceof RuntimeException runtime) throw runtime;
		throw new IllegalStateException(failure);
	}

	/** Refuse any transfer before mutation if even one of the required hooks failed to land. */
	public static void checkHooks() {
		if (hooksChecked) return;
		for (String name : List.of("net.neoforged.neoforge.transfer.transaction.Transaction",
				"net.neoforged.neoforge.transfer.transaction.TransactionManager",
				"net.fabricmc.fabric.impl.transfer.transaction.TransactionManagerImpl$TransactionImpl",
				"net.fabricmc.fabric.impl.transfer.transaction.TransactionManagerImpl")) {
			try {
				Class.forName(name, false, PairedTransactions.class.getClassLoader()).getDeclaredMethod("forbric$transferHooks");
			} catch (ReflectiveOperationException missingHook) {
				throw new IllegalStateException("Transfer transaction hook is missing: " + name, missingHook);
			}
		}
		hooksChecked = true;
	}

	private static final class NeoAccess {
		private static final Field MANAGER;
		private static final Field STACK;
		private static final Method FINISH;
		static {
			try {
				MANAGER = net.neoforged.neoforge.transfer.transaction.Transaction.class.getDeclaredField("manager");
				MANAGER.setAccessible(true);
				STACK = MANAGER.getType().getDeclaredField("stack");
				STACK.setAccessible(true);
				FINISH = net.neoforged.neoforge.transfer.transaction.SnapshotJournal.class.getDeclaredMethod("callOnRootCommit");
				FINISH.setAccessible(true);
			} catch (ReflectiveOperationException drift) { throw new ExceptionInInitializerError(drift); }
		}
		@SuppressWarnings("unchecked")
		static List<net.neoforged.neoforge.transfer.transaction.Transaction> ancestors(Object transaction) {
			try { return (List<net.neoforged.neoforge.transfer.transaction.Transaction>) STACK.get(MANAGER.get(transaction)); }
			catch (IllegalAccessException impossible) { throw new IllegalStateException(impossible); }
		}
		static void finish(Object journal) {
			try { FINISH.invoke(journal); }
			catch (InvocationTargetException failure) { rethrow(failure.getCause()); }
			catch (IllegalAccessException impossible) { throw new IllegalStateException(impossible); }
		}
	}
}
