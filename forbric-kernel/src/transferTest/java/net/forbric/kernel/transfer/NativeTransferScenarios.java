package net.forbric.kernel.transfer;

import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.forbric.kernel.runtime.transfer.NativeTransferAdapters;
import net.forbric.kernel.runtime.transfer.TransferCodec;
import net.forbric.kernel.runtime.transfer.LiveTransferEndpoints;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.resource.Resource;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;

/** No Minecraft bootstrap is needed: transactions and handler contracts are tested against a real marker resource. */
public final class NativeTransferScenarios {
	public record Token(String name) implements Resource { public boolean isEmpty() { return "empty".equals(name); } }
	private static final Token VALUE = new Token("test:resource"), EMPTY = new Token("empty");
	private static TransferCodec<Token, Token> codec(long units) {
		return new TransferCodec<>() {
			public Token toNeo(Token resource) { return resource; }
			public Token toFabric(Token resource) { return resource; }
			public boolean isFabricBlank(Token resource) { return EMPTY.equals(resource); }
			public long fabricUnits() { return units; }
		};
	}
	private static void eq(long wanted, long actual) { if (wanted != actual) throw new AssertionError(wanted + " != " + actual); }
	private static void yes(boolean value) { if (!value) throw new AssertionError("condition failed"); }
	private static void closed() {
		yes(!Transaction.isOpen());
		yes(net.neoforged.neoforge.transfer.transaction.Transaction.getCurrentOpenedTransaction() == null);
	}
	private static void expectedFailure(Runnable action) {
		try { action.run(); } catch (IllegalStateException expected) { return; }
		throw new AssertionError("expected IllegalStateException");
	}
	public static void fabricNestedRollback() {
		NeoTank tank = new NeoTank(100); var storage = NativeTransferAdapters.fabric(tank, codec(1));
		try (Transaction root = Transaction.openOuter()) {
			eq(10, storage.insert(VALUE, 10, root));
			try (Transaction child = root.openNested()) { eq(20, storage.insert(VALUE, 20, child)); child.commit(); }
			eq(30, tank.amount);
			try (Transaction child = root.openNested()) { eq(7, storage.extract(VALUE, 7, child)); }
			eq(30, tank.amount);
		}
		eq(0, tank.amount); eq(0, tank.notifications); closed();
	}
	public static void neoNestedRollback() {
		FabricTank tank = new FabricTank(100); var handler = NativeTransferAdapters.neo(tank, codec(1));
		try (var root = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
			try (var child = net.neoforged.neoforge.transfer.transaction.Transaction.open(root)) {
				eq(30, handler.insert(0, VALUE, 30, child)); child.commit();
			}
			try (var child = net.neoforged.neoforge.transfer.transaction.Transaction.open(root)) { eq(9, handler.extract(0, VALUE, 9, child)); }
			eq(30, tank.amount);
		}
		eq(0, tank.amount); eq(0, tank.notifications); closed();
	}
	public static void notifications() {
		NeoTank neo = new NeoTank(100); FabricTank fabric = new FabricTank(100);
		var storage = NativeTransferAdapters.fabric(neo, codec(1));
		try (Transaction root = Transaction.openOuter()) {
			eq(20, fabric.insert(VALUE, 20, root)); eq(20, storage.insert(VALUE, 20, root));
			root.commit();
		}
		eq(20, neo.amount); eq(20, fabric.amount); eq(1, neo.notifications); eq(1, fabric.notifications); closed();
	}
	public static void quantization() {
		FabricTank tank = new FabricTank(1000); tank.amount = 170;
		var handler = NativeTransferAdapters.neo(tank, codec(81));
		try (var root = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
			eq(2, handler.extract(0, VALUE, 3, root)); eq(8, tank.amount); root.commit();
		}
		eq(8, tank.amount);
		try (var root = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
			eq(0, handler.extract(0, VALUE, 1, root)); root.commit();
		}
		eq(8, tank.amount);
		NeoTank neo = new NeoTank(100); neo.amount = 2;
		var fabric = NativeTransferAdapters.fabric(neo, codec(81));
		try (Transaction root = Transaction.openOuter()) { eq(81, fabric.extract(VALUE, 161, root)); root.commit(); }
		eq(1, neo.amount); closed();
	}
	public static void providerFailure() {
		NeoTank tank = new NeoTank(100) {
			@Override public int insert(int slot, Token resource, int maximum, net.neoforged.neoforge.transfer.transaction.TransactionContext tx) {
				super.insert(slot, resource, maximum, tx); throw new IllegalStateException("provider failed after changing itself");
			}
		};
		var storage = NativeTransferAdapters.fabric(tank, codec(1));
		try (Transaction root = Transaction.openOuter()) { expectedFailure(() -> storage.insert(VALUE, 12, root)); eq(0, tank.amount); }
		closed();
	}
	public static void reentry() {
		FabricTank destination = new FabricTank(100); var mapping = codec(1);
		var neoView = NativeTransferAdapters.neo(destination, mapping);
		// This extra native provider is intentional: an actual callback crossing back, not merely unwrapping our adapter.
		ResourceHandler<Token> provider = new NeoTank(100) {
			@Override public int insert(int slot, Token resource, int amount, net.neoforged.neoforge.transfer.transaction.TransactionContext tx) {
				return neoView.insert(slot, resource, amount, tx);
			}
		};
		var storage = NativeTransferAdapters.fabric(provider, mapping);
		try (Transaction root = Transaction.openOuter()) { eq(15, storage.insert(VALUE, 15, root)); eq(15, destination.amount); }
		eq(0, destination.amount); closed();
		yes(NativeTransferAdapters.fabric(neoView, mapping) == destination);
		yes(NativeTransferAdapters.neo(storage, mapping) == provider);
	}
	public static void simulation() {
		NeoTank tank = new NeoTank(23); var storage = NativeTransferAdapters.fabric(tank, codec(1));
		for (int i = 0; i < 3; i++) {
			try (Transaction root = Transaction.openOuter()) { eq(23, storage.insert(VALUE, 100, root)); }
			eq(0, tank.amount); closed();
		}
		try (Transaction root = Transaction.openOuter()) { eq(23, storage.insert(VALUE, 100, root)); root.commit(); }
		eq(23, tank.amount); eq(1, tank.notifications); closed();
	}
	public static void missingHooks() {
		NeoTank tank = new NeoTank(23); var storage = NativeTransferAdapters.fabric(tank, codec(1));
		try (Transaction root = Transaction.openOuter()) { expectedFailure(() -> storage.insert(VALUE, 1, root)); }
		eq(0, tank.amount); closed();
	}
	public static void unrelatedRoots() {
		NeoTank tank = new NeoTank(23); var storage = NativeTransferAdapters.fabric(tank, codec(1));
		try (Transaction fabric = Transaction.openOuter(); var neo = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
			expectedFailure(() -> storage.insert(VALUE, 1, fabric));
		}
		eq(0, tank.amount); closed();
	}
	public static void nativeOnly() {
		NeoTank neo = new NeoTank(100);
		try (var transaction = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) { neo.insert(0, VALUE, 10, transaction); transaction.commit(); }
		eq(10, neo.amount); eq(1, neo.notifications);
		// Fabric runs its own final callback at OUTER_CLOSING without a bridge; the helper does not change that.
		FabricTank fabric = new FabricTank(100); fabric.checkBothClosed = false;
		try (Transaction transaction = Transaction.openOuter()) { fabric.insert(VALUE, 10, transaction); transaction.commit(); }
		eq(10, fabric.amount); eq(1, fabric.notifications); closed();
	}
	public static void conservation() {
		FabricTank source = new FabricTank(10000); source.amount = 8191;
		NeoTank destination = new NeoTank(33);
		var target = NativeTransferAdapters.fabric(destination, codec(81));
		try (Transaction root = Transaction.openOuter()) {
			long accepted;
			try (Transaction simulation = root.openNested()) { accepted = target.insert(VALUE, source.amount, simulation); }
			eq(0, destination.amount); eq(2673, accepted);
			eq(accepted, source.extract(VALUE, accepted, root)); eq(accepted, target.insert(VALUE, accepted, root));
			root.commit();
		}
		eq(8191, source.amount + 81 * destination.amount); eq(1, source.notifications); eq(1, destination.notifications);
		// Reverse the whole amount through a NeoForge-origin transaction and check the same accounting.
		var intoSource = NativeTransferAdapters.neo(source, codec(81));
		try (var root = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
			int extracted = destination.extract(0, VALUE, 33, root); eq(extracted, intoSource.insert(0, VALUE, extracted, root)); root.commit();
		}
		eq(8191, source.amount); eq(0, destination.amount); closed();
	}
	public static void liveEndpoints() {
		NeoTank first = new NeoTank(100), replacement = new NeoTank(100);
		var current = new java.util.concurrent.atomic.AtomicReference<ResourceHandler<Token>>(first);
		var present = new java.util.concurrent.atomic.AtomicBoolean(true);
		var generation = new java.util.concurrent.atomic.AtomicLong();
		var live = LiveTransferEndpoints.neo(current::get, present::get, generation::get, EMPTY);
		var view = NativeTransferAdapters.fabric(live, codec(1));
		try (Transaction root = Transaction.openOuter()) { view.insert(VALUE, 10, root); root.commit(); }
		current.set(replacement); generation.incrementAndGet();
		try (Transaction root = Transaction.openOuter()) { view.insert(VALUE, 20, root); root.commit(); }
		eq(10, first.amount); eq(20, replacement.amount);
		present.set(false);
		try (Transaction root = Transaction.openOuter()) { eq(0, view.insert(VALUE, 5, root)); }
		present.set(true);
		NeoTank invalidating = new NeoTank(100) {
			@Override public int insert(int slot, Token resource, int max, net.neoforged.neoforge.transfer.transaction.TransactionContext tx) {
				int moved = super.insert(slot, resource, max, tx); generation.incrementAndGet(); return moved;
			}
		};
		current.set(invalidating);
		try (Transaction root = Transaction.openOuter()) { eq(0, view.insert(VALUE, 5, root)); eq(0, invalidating.amount); }
		closed();
	}
	public static void notificationFailure() {
		NeoTank bad = new NeoTank(100) { protected void onRootCommit(Long original) { super.onRootCommit(original); throw new IllegalStateException("final failed"); } };
		FabricTank other = new FabricTank(100); var storage = NativeTransferAdapters.fabric(bad, codec(1));
		try (Transaction root = Transaction.openOuter()) {
			other.insert(VALUE, 10, root); storage.insert(VALUE, 10, root);
			expectedFailure(root::commit);
		}
		eq(1, bad.notifications); eq(1, other.notifications); eq(10, bad.amount); eq(10, other.amount); closed();
	}
	public static void invalidAmounts() {
		NeoTank bad = new NeoTank(100) {
			@Override public int insert(int slot, Token resource, int max, net.neoforged.neoforge.transfer.transaction.TransactionContext tx) {
				super.insert(slot, resource, max, tx); return max + 1;
			}
		};
		var storage = NativeTransferAdapters.fabric(bad, codec(1));
		try (Transaction root = Transaction.openOuter()) { expectedFailure(() -> storage.insert(VALUE, 10, root)); eq(0, bad.amount); }
		closed();
	}
	public static void unbalancedNeoChild() {
		FabricTank source = new FabricTank(100); source.amount = 50; NeoTank destination = new NeoTank(100);
		var target = NativeTransferAdapters.fabric(destination, codec(1));
		try (Transaction root = Transaction.openOuter()) {
			eq(10, source.extract(VALUE, 10, root)); eq(10, target.insert(VALUE, 10, root));
			var nativeParent = net.neoforged.neoforge.transfer.transaction.Transaction.getCurrentOpenedTransaction();
			try (var unmatched = net.neoforged.neoforge.transfer.transaction.Transaction.open(nativeParent)) {
				expectedFailure(root::commit); eq(0, source.notifications); eq(0, destination.notifications);
				yes(Transaction.getCurrentUnsafe() == root);
			}
		}
		eq(50, source.amount); eq(0, destination.amount); closed();
	}
	public static void unbalancedFabricChild() {
		NeoTank source = new NeoTank(100); source.amount = 50; FabricTank destination = new FabricTank(100);
		var target = NativeTransferAdapters.neo(destination, codec(1));
		try (var root = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
			eq(10, source.extract(0, VALUE, 10, root)); eq(10, target.insert(0, VALUE, 10, root));
			try (Transaction unmatched = Transaction.getCurrentUnsafe().openNested()) {
				expectedFailure(root::commit); eq(0, source.notifications); eq(0, destination.notifications);
				yes(net.neoforged.neoforge.transfer.transaction.Transaction.getCurrentOpenedTransaction() == root);
			}
		}
		eq(50, source.amount); eq(0, destination.amount); closed();
	}
	public static void optionalWatch() {
		var notifications = new java.util.concurrent.atomic.AtomicInteger();
		var watch = new net.forbric.kernel.runtime.transfer.ForgeCapabilityWatch(notifications::incrementAndGet);
		var first = net.minecraftforge.common.util.LazyOptional.of(() -> "same handler");
		var last = net.minecraftforge.common.util.LazyOptional.of(() -> "same handler");
		yes("same handler".equals(watch.observe(first)));
		for (int i = 0; i < 1000; i++) watch.observe(net.minecraftforge.common.util.LazyOptional.of(() -> "same handler"));
		watch.observe(last); first.invalidate(); eq(0, notifications.get());
		last.invalidate(); eq(1, notifications.get()); yes(!watch.isWatching());
		java.lang.ref.WeakReference<Object>[] weak = weakAfterReplacement(watch);
		for (int i = 0; i < 30 && (weak[0].get() != null || weak[1].get() != null); i++) {
			System.gc(); try { Thread.sleep(10); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
		}
		yes(weak[0].get() == null && weak[1].get() == null);
	}
	/**
	 * The bridge builds a fresh endpoint, and so a fresh watch, for every public query. A Forge provider that
	 * returns its one cached LazyOptional must still hold ONE subscription from us, not one per query until the
	 * block entity is invalidated; every still-watching endpoint must still hear the invalidation.
	 */
	public static void transientWatchesShareOneSubscription() throws Exception {
		var notifications = new java.util.concurrent.atomic.AtomicInteger();
		var cached = net.minecraftforge.common.util.LazyOptional.of(() -> "cached handler");
		for (int i = 0; i < 10_000; i++) yes("cached handler".equals(new net.forbric.kernel.runtime.transfer.ForgeCapabilityWatch(() -> { }).observe(cached)));
		var kept = new net.forbric.kernel.runtime.transfer.ForgeCapabilityWatch(notifications::incrementAndGet);
		var moved = new net.forbric.kernel.runtime.transfer.ForgeCapabilityWatch(notifications::incrementAndGet);
		kept.observe(cached); moved.observe(cached); kept.observe(cached);
		var other = net.minecraftforge.common.util.LazyOptional.of(() -> "other handler");
		moved.observe(other);
		eq(1, listeners(cached).size()); eq(1, listeners(other).size());
		cached.invalidate();
		eq(1, notifications.get()); yes(!kept.isWatching()); yes(moved.isWatching());
		other.invalidate(); eq(2, notifications.get()); yes(!moved.isWatching());
		// An optional that is already invalid notifies at once, exactly like LazyOptional.addListener itself.
		var dead = new net.forbric.kernel.runtime.transfer.ForgeCapabilityWatch(notifications::incrementAndGet);
		dead.observe(cached); eq(3, notifications.get()); yes(!dead.isWatching());
	}
	private static java.util.Set<?> listeners(net.minecraftforge.common.util.LazyOptional<?> optional) throws Exception {
		var field = net.minecraftforge.common.util.LazyOptional.class.getDeclaredField("listeners"); field.setAccessible(true);
		return (java.util.Set<?>) field.get(optional);
	}
	@SuppressWarnings("unchecked")
	private static java.lang.ref.WeakReference<Object>[] weakAfterReplacement(net.forbric.kernel.runtime.transfer.ForgeCapabilityWatch watch) {
		Object handler = new Object();
		var optional = net.minecraftforge.common.util.LazyOptional.of(() -> handler);
		watch.observe(optional);
		java.lang.ref.WeakReference<Object>[] refs = new java.lang.ref.WeakReference[]{new java.lang.ref.WeakReference<>(optional), new java.lang.ref.WeakReference<>(handler)};
		optional.invalidate();
		return refs;
	}
	public static void neoRollbackFailure() {
		var valid = new java.util.concurrent.atomic.AtomicBoolean(true);
		NeoTank tank = new NeoTank(100) {
			protected void revertToSnapshot(Long snapshot) { super.revertToSnapshot(snapshot); throw new IllegalStateException("broken Neo rollback notification"); }
			public int insert(int slot, Token resource, int max, net.neoforged.neoforge.transfer.transaction.TransactionContext tx) {
				int moved = super.insert(slot, resource, max, tx); valid.set(false); return moved;
			}
		};
		var storage = NativeTransferAdapters.fabric(LiveTransferEndpoints.neo(() -> tank, valid::get, EMPTY), codec(1));
		try (Transaction root = Transaction.openOuter()) { expectedFailure(() -> storage.insert(VALUE, 10, root)); }
		closed();
	}
	public static void fabricRollbackFailure() {
		var valid = new java.util.concurrent.atomic.AtomicBoolean(true);
		FabricTank tank = new FabricTank(100) {
			protected void readSnapshot(Long snapshot) { super.readSnapshot(snapshot); throw new IllegalStateException("broken Fabric rollback notification"); }
			public long insert(Token resource, long max, TransactionContext tx) {
				long moved = super.insert(resource, max, tx); valid.set(false); return moved;
			}
		};
		var handler = NativeTransferAdapters.neo(LiveTransferEndpoints.fabric(() -> tank, valid::get, EMPTY), codec(1));
		try (var root = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) { expectedFailure(() -> handler.insert(0, VALUE, 10, root)); }
		closed();
	}
	private static void expectedRuntimeFailure(Runnable action) {
		try { action.run(); } catch (RuntimeException expected) { return; }
		throw new AssertionError("expected callback failure");
	}
	public static void fabricCallbackOpensPeer() { fabricCallback(false); }
	public static void fabricCallbackClosesPeer() { fabricCallback(true); }
	private static void fabricCallback(boolean closePeer) {
		FabricTank source = new FabricTank(100); source.amount = 50; NeoTank destination = new NeoTank(100);
		var target = NativeTransferAdapters.fabric(destination, codec(1));
		var denied = new java.util.concurrent.atomic.AtomicInteger();
		try (Transaction root = Transaction.openOuter()) {
			source.extract(VALUE, 10, root); target.insert(VALUE, 10, root);
			var peer = (net.neoforged.neoforge.transfer.transaction.Transaction) net.neoforged.neoforge.transfer.transaction.Transaction.getCurrentOpenedTransaction();
			root.addCloseCallback((context, result) -> {
				try {
					if (closePeer) peer.commit(); else net.neoforged.neoforge.transfer.transaction.Transaction.open(peer);
				} catch (IllegalStateException rejected) { denied.incrementAndGet(); yes(net.neoforged.neoforge.transfer.transaction.Transaction.getCurrentOpenedTransaction() == peer); throw rejected; }
			});
			expectedRuntimeFailure(root::commit);
		}
		eq(1, denied.get()); eq(40, source.amount); eq(10, destination.amount); eq(1, source.notifications); eq(1, destination.notifications); closed();
	}
	public static void neoCallbackOpensPeer() { neoCallback(false); }
	public static void neoCallbackClosesPeer() { neoCallback(true); }
	private static void neoCallback(boolean closePeer) {
		var denied = new java.util.concurrent.atomic.AtomicInteger();
		NeoTank source = new NeoTank(100) {
			protected void revertToSnapshot(Long original) {
				super.revertToSnapshot(original);
				Transaction peer = (Transaction) Transaction.getCurrentUnsafe();
				try { if (closePeer) peer.abort(); else peer.openNested(); }
				catch (IllegalStateException rejected) { denied.incrementAndGet(); yes(Transaction.getCurrentUnsafe() == peer); throw rejected; }
			}
		};
		source.amount = 50; FabricTank destination = new FabricTank(100);
		var target = NativeTransferAdapters.neo(destination, codec(1));
		try (var root = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
			source.extract(0, VALUE, 10, root); target.insert(0, VALUE, 10, root);
			expectedRuntimeFailure(root::close);
		}
		eq(1, denied.get()); eq(50, source.amount); eq(0, destination.amount); eq(0, source.notifications); eq(0, destination.notifications); closed();
	}
	/**
	 * NeoForge's own contract: "new root transactions can safely be opened from" onRootCommit. A machine's
	 * auto-output from its final notification must work when the commit that triggered it came through a pairing,
	 * exactly as it does after a NeoForge-only commit, and the original commit must not throw afterwards.
	 */
	public static void neoFinalCommitMayTransferAgain() {
		FabricTank output = new FabricTank(100); var bridgedOutput = NativeTransferAdapters.neo(output, codec(1));
		var moved = new java.util.concurrent.atomic.AtomicInteger();
		NeoTank machine = new NeoTank(100) {
			@Override protected void onRootCommit(Long original) {
				super.onRootCommit(original);
				if (moved.get() != 0) return;
				try (var auto = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
					int out = extract(0, VALUE, 4, auto); moved.addAndGet(bridgedOutput.insert(0, VALUE, out, auto)); auto.commit();
				}
			}
		};
		var target = NativeTransferAdapters.fabric(machine, codec(1));
		try (Transaction pipe = Transaction.openOuter()) { eq(10, target.insert(VALUE, 10, pipe)); pipe.commit(); }
		eq(4, moved.get()); eq(6, machine.amount); eq(4, output.amount); eq(2, machine.notifications); eq(1, output.notifications); closed();
	}
	/** The mirror: a Fabric store's onFinalCommit after a NeoForge-origin paired commit moves into a bridged NeoForge store. */
	public static void fabricFinalCommitMayTransferAgain() {
		NeoTank output = new NeoTank(100); var bridgedOutput = NativeTransferAdapters.fabric(output, codec(1));
		var moved = new java.util.concurrent.atomic.AtomicLong();
		FabricTank machine = new FabricTank(100) {
			@Override protected void onFinalCommit() {
				super.onFinalCommit();
				if (moved.get() != 0) return;
				try (Transaction auto = Transaction.openOuter()) {
					long out = extract(VALUE, 4, auto); moved.addAndGet(bridgedOutput.insert(VALUE, out, auto)); auto.commit();
				}
			}
		};
		var target = NativeTransferAdapters.neo(machine, codec(1));
		try (var hopper = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) { eq(10, target.insert(0, VALUE, 10, hopper)); hopper.commit(); }
		eq(4, moved.get()); eq(6, machine.amount); eq(4, output.amount); eq(2, machine.notifications); eq(1, output.notifications); closed();
	}
	/**
	 * Root invariants are checked when the ORIGIN closes. By the time its peer closes the outcome is fixed: the
	 * origin already committed natively, so a second check can only fail after the fact, and it failed before the
	 * peer's native close ran, leaving that engine open on this thread for good.
	 */
	public static void fabricOriginCommitValidatesOnce() {
		var checks = new java.util.concurrent.atomic.AtomicInteger(); var mutated = new java.util.concurrent.atomic.AtomicBoolean();
		NeoTank destination = new NeoTank(100); var target = NativeTransferAdapters.fabric(destination, codec(1));
		try (Transaction root = Transaction.openOuter()) {
			eq(10, target.insert(VALUE, 10, root));
			var peer = net.neoforged.neoforge.transfer.transaction.Transaction.getCurrentOpenedTransaction();
			net.forbric.kernel.runtime.transfer.PairedTransactions.addValidation(peer, checks, () -> {
				checks.incrementAndGet(); if (mutated.get()) throw new IllegalStateException("changed after the origin committed");
			});
			// Runs inside Fabric's native close, after the origin's check and before its peer closes.
			root.addCloseCallback((context, result) -> mutated.set(true));
			root.commit();
		}
		eq(1, checks.get()); eq(10, destination.amount); eq(1, destination.notifications); closed();
		try (var again = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) { eq(1, destination.insert(0, VALUE, 1, again)); again.commit(); }
		eq(11, destination.amount); closed();
	}
	/** The same at a nested boundary with NeoForge as the origin: its Fabric peer child must still close. */
	public static void neoOriginNestedCommitValidatesOnce() {
		var checks = new java.util.concurrent.atomic.AtomicInteger(); var mutated = new java.util.concurrent.atomic.AtomicBoolean();
		FabricTank destination = new FabricTank(100); var target = NativeTransferAdapters.neo(destination, codec(1));
		NeoTank witness = new NeoTank(100) { @Override protected void releaseSnapshot(Long snapshot) { super.releaseSnapshot(snapshot); mutated.set(true); } };
		try (var root = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
			eq(1, witness.insert(0, VALUE, 1, root));
			try (var child = net.neoforged.neoforge.transfer.transaction.Transaction.open(root)) {
				eq(10, target.insert(0, VALUE, 10, child)); eq(1, witness.insert(0, VALUE, 1, child));
				net.forbric.kernel.runtime.transfer.PairedTransactions.addValidation(child, checks, () -> {
					checks.incrementAndGet(); if (mutated.get()) throw new IllegalStateException("changed after the origin committed");
				});
				// NeoForge releases the child's journal snapshot inside its native close: after the origin's check.
				child.commit();
			}
			yes(mutated.getAndSet(false)); yes(Transaction.isOpen() && Transaction.getCurrentUnsafe().nestingDepth() == 0);
			root.commit();
		}
		eq(2, checks.get()); eq(10, destination.amount); eq(2, witness.amount); closed();
	}
	static class NeoTank extends SnapshotJournal<Long> implements ResourceHandler<Token> {
		long amount; final long capacity; int notifications;
		NeoTank(long capacity) { this.capacity = capacity; }
		protected Long createSnapshot() { return amount; }
		protected void revertToSnapshot(Long snapshot) { amount = snapshot; }
		protected void onRootCommit(Long original) { closed(); notifications++; }
		public int size() { return 1; }
		public Token getResource(int slot) { return amount == 0 ? EMPTY : VALUE; }
		public long getAmountAsLong(int slot) { return amount; }
		public long getCapacityAsLong(int slot, Token resource) { return capacity; }
		public boolean isValid(int slot, Token resource) { return VALUE.equals(resource); }
		public int insert(int slot, Token resource, int maximum, net.neoforged.neoforge.transfer.transaction.TransactionContext tx) {
			if (!VALUE.equals(resource)) return 0; updateSnapshots(tx); int moved = (int) Math.min(capacity - amount, maximum); amount += moved; return moved;
		}
		public int extract(int slot, Token resource, int maximum, net.neoforged.neoforge.transfer.transaction.TransactionContext tx) {
			if (!VALUE.equals(resource)) return 0; updateSnapshots(tx); int moved = (int) Math.min(amount, maximum); amount -= moved; return moved;
		}
	}
	static class FabricTank extends SnapshotParticipant<Long> implements SingleSlotStorage<Token> {
		long amount; final long capacity; int notifications; boolean checkBothClosed = true;
		FabricTank(long capacity) { this.capacity = capacity; }
		protected Long createSnapshot() { return amount; }
		protected void readSnapshot(Long snapshot) { amount = snapshot; }
		protected void onFinalCommit() { if (checkBothClosed) closed(); notifications++; }
		public boolean isResourceBlank() { return amount == 0; }
		public Token getResource() { return amount == 0 ? EMPTY : VALUE; }
		public long getAmount() { return amount; }
		public long getCapacity() { return capacity; }
		public long insert(Token resource, long maximum, TransactionContext tx) {
			if (!VALUE.equals(resource)) return 0; updateSnapshots(tx); long moved = Math.min(capacity - amount, maximum); amount += moved; return moved;
		}
		public long extract(Token resource, long maximum, TransactionContext tx) {
			if (!VALUE.equals(resource)) return 0; updateSnapshots(tx); long moved = Math.min(amount, maximum); amount -= moved; return moved;
		}
	}
}
