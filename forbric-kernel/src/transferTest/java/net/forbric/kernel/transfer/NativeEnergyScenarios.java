package net.forbric.kernel.transfer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.forbric.kernel.runtime.transfer.EnergyUnits;
import net.forbric.kernel.runtime.transfer.LiveTransferEndpoints;
import net.forbric.kernel.runtime.transfer.RebornEnergyAdapters;
import net.forbric.kernel.runtime.transfer.TransferIssues;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.energy.EnergyHandlerUtil;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import team.reborn.energy.api.EnergyStorageUtil;
import team.reborn.energy.api.base.SimpleEnergyStorage;

/**
 * Block energy across Team Reborn Energy and NeoForge with the real engines: Fabric's transfer transactions
 * (transformed with the kernel's pairing hooks), NeoForge's, Team Reborn Energy 5.0.0's own SimpleEnergyStorage and
 * NeoForge's own SimpleEnergyHandler. Only the kernel's adapters are exercised; no store below is a test double except
 * where a broken provider is the point.
 */
public final class NativeEnergyScenarios {
	private NativeEnergyScenarios() { }
	private static void eq(long wanted, long actual) { if (wanted != actual) throw new AssertionError(wanted + " != " + actual); }
	private static void yes(boolean value, String what) { if (!value) throw new AssertionError(what); }
	private static void closed() {
		yes(!Transaction.isOpen(), "a Fabric transaction was left open");
		yes(net.neoforged.neoforge.transfer.transaction.Transaction.getCurrentOpenedTransaction() == null, "a NeoForge transaction was left open");
	}
	private static void expected(Class<? extends Throwable> type, Runnable action) {
		try { action.run(); } catch (Throwable thrown) { if (type.isInstance(thrown)) return; throw new AssertionError("expected " + type.getSimpleName() + ", got " + thrown, thrown); }
		throw new AssertionError("expected " + type.getSimpleName());
	}
	private static net.neoforged.neoforge.transfer.transaction.Transaction neoRoot() { return net.neoforged.neoforge.transfer.transaction.Transaction.openRoot(); }
	private static net.neoforged.neoforge.transfer.transaction.Transaction neoChild(TransactionContext parent) { return net.neoforged.neoforge.transfer.transaction.Transaction.open(parent); }

	/** Reborn's standard store, counting its final-commit notifications. */
	static class Reborn extends SimpleEnergyStorage {
		int finals; boolean checkClosed;
		Reborn(long capacity, long maxInsert, long maxExtract) { super(capacity, maxInsert, maxExtract); }
		@Override protected void onFinalCommit() { if (checkClosed) closed(); finals++; }
	}
	/** NeoForge's standard handler, counting its root-commit notifications. */
	static class Neo extends SimpleEnergyHandler {
		int changes; boolean checkClosed;
		Neo(int capacity, int maxInsert, int maxExtract, int energy) { super(capacity, maxInsert, maxExtract, energy); }
		@Override protected void onEnergyChanged(int previous) { if (checkClosed) closed(); changes++; }
	}
	private static List<TransferIssues.Issue> capture() {
		List<TransferIssues.Issue> issues = new ArrayList<>(); TransferIssues.setReporter(issues::add); return issues;
	}

	/** A Fabric (Reborn) consumer of a NeoForge handler: nested commit and abort, repeated simulation, limits, one final. */
	public static void fabricConsumerOfNeo() {
		Neo neo = new Neo(1000, 300, 200, 0);
		team.reborn.energy.api.EnergyStorage view = RebornEnergyAdapters.fabric(neo);
		for (int i = 0; i < 3; i++) {
			try (Transaction simulation = Transaction.openOuter()) { eq(300, view.insert(1000, simulation)); }
			eq(0, neo.getAmountAsLong()); closed();
		}
		try (Transaction root = Transaction.openOuter()) {
			eq(300, view.insert(500, root));
			try (Transaction child = root.openNested()) { eq(100, view.insert(100, child)); child.commit(); }
			eq(400, neo.getAmountAsLong());
			try (Transaction child = root.openNested()) {
				eq(200, view.extract(500, child));
				try (Transaction grandchild = child.openNested()) { eq(150, view.extract(150, grandchild)); grandchild.commit(); }
				eq(50, neo.getAmountAsLong());
			}
			eq(400, neo.getAmountAsLong());
		}
		eq(0, neo.getAmountAsLong()); eq(0, neo.changes); closed();
		try (Transaction root = Transaction.openOuter()) { eq(300, view.insert(300, root)); eq(100, view.extract(100, root)); root.commit(); }
		eq(200, neo.getAmountAsLong()); eq(1, neo.changes); eq(1000, view.getCapacity()); eq(200, view.getAmount()); closed();
	}

	/** A NeoForge consumer of a Reborn store: the same, from the other engine. */
	public static void neoConsumerOfFabric() {
		Reborn fabric = new Reborn(1000, 300, 200);
		EnergyHandler view = RebornEnergyAdapters.neo(fabric);
		for (int i = 0; i < 3; i++) {
			try (var simulation = neoRoot()) { eq(300, view.insert(1000, simulation)); }
			eq(0, fabric.amount); closed();
		}
		try (var root = neoRoot()) {
			eq(300, view.insert(500, root));
			try (var child = neoChild(root)) { eq(100, view.insert(100, child)); child.commit(); }
			try (var child = neoChild(root)) {
				eq(200, view.extract(500, child));
				try (var grandchild = neoChild(child)) { eq(150, view.extract(150, grandchild)); grandchild.commit(); }
				eq(50, fabric.amount);
			}
			eq(400, fabric.amount);
		}
		eq(0, fabric.amount); eq(0, fabric.finals); closed();
		try (var root = neoRoot()) { eq(300, view.insert(300, root)); eq(100, view.extract(100, root)); root.commit(); }
		eq(200, fabric.amount); eq(1, fabric.finals); eq(1000, view.getCapacityAsLong()); closed();
	}

	/** Both directed moves between Fabric (Reborn) and NeoForge, each through the consumer's own API, and the total never changes. */
	public static void conservationAcrossBothDirections() {
		Reborn fabric = new Reborn(10_000, 10_000, 10_000); fabric.amount = 3000;
		Neo neo = new Neo(10_000, 10_000, 10_000, 3000);
		Runnable total = () -> eq(6000, fabric.amount + neo.getAmountAsLong());
		team.reborn.energy.api.EnergyStorage fabricOnNeo = RebornEnergyAdapters.fabric(neo);
		EnergyHandler neoOnFabric = RebornEnergyAdapters.neo(fabric);
		// Fabric consumer: Reborn's own move helper, a real nested extract/insert/verify.
		try (Transaction tx = Transaction.openOuter()) { eq(700, EnergyStorageUtil.move(fabric, fabricOnNeo, 700, tx)); tx.commit(); } total.run();
		// NeoForge consumer: NeoForge's own move helper.
		try (var tx = neoRoot()) { eq(500, EnergyHandlerUtil.move(neo, neoOnFabric, 500, tx)); tx.commit(); } total.run();
		eq(3000 - 700 + 500, fabric.amount); eq(3000 + 700 - 500, neo.getAmountAsLong());
		// A move that cannot complete moves nothing, in either direction.
		Neo full = new Neo(100, 100, 100, 100);
		try (Transaction tx = Transaction.openOuter()) { eq(0, EnergyStorageUtil.move(fabric, RebornEnergyAdapters.fabric(full), 50, tx)); tx.commit(); }
		Reborn fullFabric = new Reborn(100, 100, 100); fullFabric.amount = 100;
		try (var tx = neoRoot()) { eq(0, EnergyHandlerUtil.move(neo, RebornEnergyAdapters.neo(fullFabric), 50, tx)); tx.commit(); }
		total.run(); closed();
	}

	/**
	 * Reborn counts in long, NeoForge in int. A long request is clamped before anything moves, the int side
	 * reports exactly what it moved, and the rest stays in the source; a long amount read through an int API saturates.
	 */
	public static void longAmountsClampToIntWithoutLoss() {
		long five = 5_000_000_000L;
		Reborn reservoir = new Reborn(10_000_000_000L, Long.MAX_VALUE, Long.MAX_VALUE); reservoir.amount = five;
		EnergyHandler neoView = RebornEnergyAdapters.neo(reservoir);
		eq(five, neoView.getAmountAsLong()); eq(Integer.MAX_VALUE, neoView.getAmountAsInt()); eq(Integer.MAX_VALUE, neoView.getCapacityAsInt());
		try (var tx = neoRoot()) { eq(Integer.MAX_VALUE, neoView.extract(Integer.MAX_VALUE, tx)); eq(five - Integer.MAX_VALUE, reservoir.amount); }
		eq(five, reservoir.amount);
		// Fabric consumer, long request into an int store with room for all of it: exactly Integer.MAX_VALUE moves.
		Neo sink = new Neo(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 0);
		try (Transaction tx = Transaction.openOuter()) {
			eq(Integer.MAX_VALUE, RebornEnergyAdapters.fabric(sink).insert(five, tx)); // asked, not moved: aborted below
		}
		eq(0, sink.getAmountAsLong());
		try (Transaction tx = Transaction.openOuter()) { eq(Integer.MAX_VALUE, EnergyStorageUtil.move(reservoir, RebornEnergyAdapters.fabric(sink), five, tx)); tx.commit(); }
		eq(Integer.MAX_VALUE, sink.getAmountAsLong()); eq(five - Integer.MAX_VALUE, reservoir.amount);
		eq(five, reservoir.amount + sink.getAmountAsLong());
		expected(IllegalArgumentException.class, () -> { try (Transaction tx = Transaction.openOuter()) { RebornEnergyAdapters.fabric(sink).insert(-1, tx); } });
		eq(Integer.MAX_VALUE, EnergyUnits.request(Long.MAX_VALUE)); eq(7, EnergyUnits.request(7)); closed();
	}

	/** A result outside [0, request] is rejected before its nested scope commits, so the provider is rolled back. */
	public static void invalidProviderAmountsRollBack() {
		Neo lying = new Neo(1000, 1000, 1000, 0) { @Override public int insert(int max, TransactionContext tx) { super.insert(max, tx); return max + 1; } };
		try (Transaction root = Transaction.openOuter()) {
			expected(IllegalStateException.class, () -> RebornEnergyAdapters.fabric(lying).insert(10, root));
			eq(0, lying.getAmountAsLong());
		}
		closed();
		Reborn negative = new Reborn(1000, 1000, 1000) { @Override public long extract(long max, net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext tx) { super.extract(max, tx); return -1; } };
		negative.amount = 100;
		try (var root = neoRoot()) { expected(IllegalStateException.class, () -> RebornEnergyAdapters.neo(negative).extract(10, root)); eq(100, negative.amount); }
		closed();
	}

	/** Views resolve their store for every operation, and an endpoint invalidated mid-operation moves nothing. */
	public static void liveEndpointsResolveAndRefuseAfterInvalidation() {
		Neo first = new Neo(1000, 1000, 1000, 0), replacement = new Neo(1000, 1000, 1000, 0);
		var current = new java.util.concurrent.atomic.AtomicReference<EnergyHandler>(first);
		var present = new java.util.concurrent.atomic.AtomicBoolean(true);
		AtomicLong generation = new AtomicLong();
		team.reborn.energy.api.EnergyStorage view = RebornEnergyAdapters.fabric(LiveTransferEndpoints.energy(current::get, present::get, generation::get));
		try (Transaction tx = Transaction.openOuter()) { eq(10, view.insert(10, tx)); tx.commit(); }
		current.set(replacement); generation.incrementAndGet();
		try (Transaction tx = Transaction.openOuter()) { eq(20, view.insert(20, tx)); tx.commit(); }
		eq(10, first.getAmountAsLong()); eq(20, replacement.getAmountAsLong());
		present.set(false);
		try (Transaction tx = Transaction.openOuter()) { eq(0, view.insert(5, tx)); tx.commit(); }
		yes(!view.supportsInsertion() && view.getAmount() == 0, "a gone endpoint still looks usable");
		present.set(true);
		Neo invalidating = new Neo(1000, 1000, 1000, 0) {
			@Override public int insert(int max, TransactionContext tx) { int moved = super.insert(max, tx); generation.incrementAndGet(); return moved; }
		};
		current.set(invalidating);
		try (Transaction tx = Transaction.openOuter()) { eq(0, view.insert(5, tx)); eq(0, invalidating.getAmountAsLong()); tx.commit(); }
		eq(0, invalidating.getAmountAsLong()); closed();
		// And a live Reborn store under a NeoForge consumer.
		Reborn fabric = new Reborn(1000, 1000, 1000);
		var fabricPresent = new java.util.concurrent.atomic.AtomicBoolean(true);
		EnergyHandler neoView = RebornEnergyAdapters.neo(RebornEnergyAdapters.live(() -> fabric, fabricPresent::get, () -> 0L));
		try (var tx = neoRoot()) { eq(7, neoView.insert(7, tx)); tx.commit(); }
		fabricPresent.set(false);
		try (var tx = neoRoot()) { eq(0, neoView.insert(7, tx)); tx.commit(); }
		eq(7, fabric.amount); closed();
	}

	/**
	 * A NeoForge consumer's view of a Reborn store through its own live endpoint (RebornEnergyAdapters.live):
	 * resolved again for every operation, and when the endpoint is invalidated or removed DURING an operation, Reborn's
	 * own journal rolls the store back and the consumer is told nothing moved.
	 */
	public static void liveRebornStoresMoveNothingOnceInvalidated() {
		List<TransferIssues.Issue> issues = capture();
		AtomicLong generation = new AtomicLong();
		AtomicBoolean present = new AtomicBoolean(true);
		Reborn first = new Reborn(1000, 1000, 1000), replacement = new Reborn(1000, 1000, 1000);
		AtomicReference<team.reborn.energy.api.EnergyStorage> current = new AtomicReference<>(first);
		EnergyHandler view = RebornEnergyAdapters.neo(RebornEnergyAdapters.live(current::get, present::get, generation::get));
		try (var tx = neoRoot()) { eq(10, view.insert(10, tx)); tx.commit(); }
		current.set(replacement); generation.incrementAndGet();
		try (var tx = neoRoot()) { eq(20, view.insert(20, tx)); tx.commit(); }
		eq(10, first.amount); eq(20, replacement.amount);
		// Its capability listener fires while it is being filled.
		Reborn invalidating = new Reborn(1000, 1000, 1000) {
			@Override public long insert(long max, net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext tx) {
				long moved = super.insert(max, tx); generation.incrementAndGet(); return moved;
			}
		};
		current.set(invalidating);
		try (var tx = neoRoot()) { eq(0, view.insert(5, tx)); eq(0, invalidating.amount); tx.commit(); }
		eq(0, invalidating.amount); eq(0, invalidating.finals);
		// It is removed while it is being drained.
		Reborn removing = new Reborn(1000, 1000, 1000) {
			@Override public long extract(long max, net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext tx) {
				long moved = super.extract(max, tx); present.set(false); return moved;
			}
		};
		removing.amount = 100; current.set(removing);
		try (var tx = neoRoot()) { eq(0, view.extract(5, tx)); tx.commit(); }
		eq(100, removing.amount); eq(0, removing.finals);
		// Gone: empty, and nothing is asked of the store.
		try (var tx = neoRoot()) { eq(0, view.insert(5, tx)); tx.commit(); }
		yes(view.getAmountAsLong() == 0 && view.getCapacityAsLong() == 0, "a gone endpoint still reports a store");
		eq(100, removing.amount); closed();
		yes(issues.stream().anyMatch(issue -> issue.code().equals("ENDPOINT_INVALIDATED")), "the invalidation was not reported: " + issues);
	}

	/** A bridge is never wrapped in another bridge, in any direction. */
	public static void bridgesNeverStack() {
		Reborn fabric = new Reborn(10, 10, 10); Neo neo = new Neo(10, 10, 10, 0);
		yes(RebornEnergyAdapters.fabric(RebornEnergyAdapters.neo(fabric)) == fabric, "Reborn -> NeoForge -> Reborn stacked");
		yes(RebornEnergyAdapters.neo(RebornEnergyAdapters.fabric(neo)) == neo, "NeoForge -> Reborn -> NeoForge stacked");
	}

	/** Final notifications of both engines run once, and only after both roots closed. */
	public static void finalNotificationsAfterBothRootsClose() {
		Reborn fabric = new Reborn(1000, 1000, 1000); Neo neo = new Neo(1000, 1000, 1000, 0);
		fabric.checkClosed = true; neo.checkClosed = true;
		team.reborn.energy.api.EnergyStorage onNeo = RebornEnergyAdapters.fabric(neo);
		try (Transaction root = Transaction.openOuter()) {
			eq(10, fabric.insert(10, root)); eq(10, onNeo.insert(10, root));
			try (Transaction child = root.openNested()) { eq(5, onNeo.insert(5, child)); child.commit(); }
			root.commit();
		}
		eq(1, fabric.finals); eq(1, neo.changes); eq(15, neo.getAmountAsLong()); closed();
	}
}
