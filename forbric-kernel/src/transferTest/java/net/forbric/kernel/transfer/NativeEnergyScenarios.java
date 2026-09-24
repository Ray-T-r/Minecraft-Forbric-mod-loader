package net.forbric.kernel.transfer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.forbric.kernel.runtime.transfer.EnergyUnits;
import net.forbric.kernel.runtime.transfer.ForgeEnergyAdapters;
import net.forbric.kernel.runtime.transfer.LiveTransferEndpoints;
import net.forbric.kernel.runtime.transfer.RebornEnergyAdapters;
import net.forbric.kernel.runtime.transfer.TransferIssues;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.energy.IEnergyStorage;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.energy.EnergyHandlerUtil;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import team.reborn.energy.api.EnergyStorageUtil;
import team.reborn.energy.api.base.SimpleEnergyStorage;

/**
 * Block energy across the three APIs with the real engines: Fabric's transfer transactions (transformed with the
 * kernel's pairing hooks), NeoForge's, Team Reborn Energy 5.0.0's own SimpleEnergyStorage, NeoForge's own
 * SimpleEnergyHandler and Forge's own EnergyStorage (certified through ForgeTransferShapeAudit as the game does). Only
 * the kernel's adapters are exercised; no store below is a test double except where a broken provider is the point.
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
	private static long count(List<TransferIssues.Issue> issues, String code, Class<?> type) {
		return issues.stream().filter(issue -> issue.code().equals(code) && issue.providerClass().equals(type.getName())).count();
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

	/**
	 * A transactional consumer of Forge's own EnergyStorage, from both engines. The store's own receive/extract code
	 * applies its limits; every abort restores its energy field at the depth it happened; the owner hears of it once
	 * per root commit, however many writes that root held.
	 */
	public static void transactionalConsumersOfForge() {
		EnergyStorage forge = new EnergyStorage(1000, 300, 200, 0);
		AtomicInteger changed = new AtomicInteger();
		EnergyHandler neoView = ForgeEnergyAdapters.neo(forge, forge, changed::incrementAndGet);
		yes(neoView != null, "the standard Forge store was refused");
		team.reborn.energy.api.EnergyStorage fabricView = RebornEnergyAdapters.fabric(neoView);
		try (Transaction root = Transaction.openOuter()) {
			eq(300, fabricView.insert(500, root));
			try (Transaction child = root.openNested()) { eq(100, fabricView.insert(100, child)); child.commit(); }
			try (Transaction child = root.openNested()) { eq(200, fabricView.extract(900, child)); eq(200, forge.getEnergyStored()); }
			eq(400, forge.getEnergyStored());
		}
		eq(0, forge.getEnergyStored()); eq(0, changed.get()); closed();
		for (int i = 0; i < 3; i++) {
			try (var simulation = neoRoot()) { eq(300, neoView.insert(1000, simulation)); }
			eq(0, forge.getEnergyStored()); closed();
		}
		try (var root = neoRoot()) {
			eq(300, neoView.insert(300, root)); eq(300, neoView.insert(300, root));
			try (var child = neoChild(root)) { eq(200, neoView.extract(1000, child)); child.commit(); }
			root.commit();
		}
		eq(400, forge.getEnergyStored()); eq(1, changed.get()); closed();
		// A store first touched at depth 2, while depth 0 already holds a snapshot of another one: aborting depth 0
		// must restore both, so every live snapshot learns the newcomer before it changes.
		EnergyStorage second = new EnergyStorage(1000, 1000, 1000, 10);
		EnergyHandler secondView = ForgeEnergyAdapters.neo(second, second, changed::incrementAndGet);
		try (var root = neoRoot()) {
			eq(100, neoView.extract(100, root));
			try (var child = neoChild(root)) {
				try (var grandchild = neoChild(child)) { eq(500, secondView.insert(500, grandchild)); grandchild.commit(); }
				child.commit();
			}
			eq(510, second.getEnergyStored()); eq(300, forge.getEnergyStored());
		}
		eq(10, second.getEnergyStored()); eq(400, forge.getEnergyStored()); eq(1, changed.get()); closed();
	}

	/** A Forge consumer of NeoForge and Reborn stores: simulate is a real aborted operation, execute commits it. */
	public static void forgeConsumerOfTransactionalStores() {
		Neo neo = new Neo(1000, 300, 200, 0);
		IEnergyStorage onNeo = ForgeEnergyAdapters.forge(neo);
		for (int i = 0; i < 3; i++) { eq(300, onNeo.receiveEnergy(500, true)); eq(0, neo.getAmountAsLong()); }
		eq(300, onNeo.receiveEnergy(500, false)); eq(300, neo.getAmountAsLong()); eq(1, neo.changes);
		eq(200, onNeo.extractEnergy(500, true)); eq(300, onNeo.getEnergyStored());
		eq(200, onNeo.extractEnergy(500, false)); eq(100, onNeo.getEnergyStored()); eq(1000, onNeo.getMaxEnergyStored());
		yes(onNeo.canReceive() && onNeo.canExtract(), "NeoForge's rule for a handler with capacity");
		eq(0, onNeo.receiveEnergy(0, false)); eq(0, onNeo.receiveEnergy(-5, false));
		// Inside an open transaction the caller's scope stays the owner of the final commit, on either engine.
		try (var outer = neoRoot()) { eq(50, onNeo.receiveEnergy(50, false)); eq(150, neo.getAmountAsLong()); }
		eq(100, neo.getAmountAsLong());
		try (Transaction outer = Transaction.openOuter()) { eq(50, onNeo.receiveEnergy(50, false)); eq(150, neo.getAmountAsLong()); }
		eq(100, neo.getAmountAsLong()); closed();

		Reborn generator = new Reborn(1000, 0, 200); generator.amount = 700;
		IEnergyStorage onFabric = ForgeEnergyAdapters.forge(RebornEnergyAdapters.neo(generator));
		yes(!onFabric.canReceive() && onFabric.canExtract(), "Reborn's own supportsInsertion/Extraction reach Forge");
		eq(0, onFabric.receiveEnergy(100, false));
		for (int i = 0; i < 3; i++) { eq(200, onFabric.extractEnergy(999, true)); eq(700, generator.amount); }
		eq(200, onFabric.extractEnergy(999, false)); eq(500, generator.amount); eq(1, generator.finals);
		eq(500, onFabric.getEnergyStored()); eq(1000, onFabric.getMaxEnergyStored()); closed();
	}

	/** Six directed moves, each through the consumer's own API, and the total never changes. */
	public static void conservationAcrossAllSixDirections() {
		Reborn fabric = new Reborn(10_000, 10_000, 10_000); fabric.amount = 3000;
		Neo neo = new Neo(10_000, 10_000, 10_000, 3000);
		EnergyStorage forge = new EnergyStorage(10_000, 10_000, 10_000, 3000);
		Runnable total = () -> eq(9000, fabric.amount + neo.getAmountAsLong() + forge.getEnergyStored());
		team.reborn.energy.api.EnergyStorage fabricOnNeo = RebornEnergyAdapters.fabric(neo);
		team.reborn.energy.api.EnergyStorage fabricOnForge = RebornEnergyAdapters.fabric(ForgeEnergyAdapters.neo(forge));
		EnergyHandler neoOnFabric = RebornEnergyAdapters.neo(fabric), neoOnForge = ForgeEnergyAdapters.neo(forge);
		IEnergyStorage forgeOnFabric = ForgeEnergyAdapters.forge(RebornEnergyAdapters.neo(fabric)), forgeOnNeo = ForgeEnergyAdapters.forge(neo);
		// Fabric consumer: Reborn's own move helper, a real nested extract/insert/verify.
		try (Transaction tx = Transaction.openOuter()) { eq(700, EnergyStorageUtil.move(fabric, fabricOnNeo, 700, tx)); tx.commit(); } total.run();
		try (Transaction tx = Transaction.openOuter()) { eq(600, EnergyStorageUtil.move(fabric, fabricOnForge, 600, tx)); tx.commit(); } total.run();
		// NeoForge consumer: NeoForge's own move helper.
		try (var tx = neoRoot()) { eq(500, EnergyHandlerUtil.move(neo, neoOnFabric, 500, tx)); tx.commit(); } total.run();
		try (var tx = neoRoot()) { eq(400, EnergyHandlerUtil.move(neo, neoOnForge, 400, tx)); tx.commit(); } total.run();
		// Forge consumer: the usual simulate-then-execute pair on its own store and a bridged one.
		for (IEnergyStorage target : List.of(forgeOnFabric, forgeOnNeo)) {
			int offer = forge.extractEnergy(300, true), accepted = target.receiveEnergy(offer, true);
			eq(300, accepted); eq(accepted, target.receiveEnergy(forge.extractEnergy(accepted, false), false)); total.run();
		}
		eq(3000 - 700 - 600 + 500 + 300, fabric.amount); eq(3000 + 700 - 500 - 400 + 300, neo.getAmountAsLong());
		eq(3000 + 600 + 400 - 600, forge.getEnergyStored());
		// A move that cannot complete moves nothing, in every direction.
		Neo full = new Neo(100, 100, 100, 100);
		try (Transaction tx = Transaction.openOuter()) { eq(0, EnergyStorageUtil.move(fabric, RebornEnergyAdapters.fabric(full), 50, tx)); tx.commit(); }
		try (var tx = neoRoot()) { eq(0, EnergyHandlerUtil.move(neo, ForgeEnergyAdapters.neo(new EnergyStorage(100, 100, 100, 100)), 50, tx)); tx.commit(); }
		total.run(); closed();
	}

	/**
	 * Reborn counts in long, Forge and NeoForge in int. A long request is clamped before anything moves, the int side
	 * reports exactly what it moved, and the rest stays in the source; a long amount read through an int API saturates.
	 */
	public static void longAmountsClampToIntWithoutLoss() {
		long five = 5_000_000_000L;
		Reborn reservoir = new Reborn(10_000_000_000L, Long.MAX_VALUE, Long.MAX_VALUE); reservoir.amount = five;
		EnergyHandler neoView = RebornEnergyAdapters.neo(reservoir);
		eq(five, neoView.getAmountAsLong()); eq(Integer.MAX_VALUE, neoView.getAmountAsInt()); eq(Integer.MAX_VALUE, neoView.getCapacityAsInt());
		IEnergyStorage forgeView = ForgeEnergyAdapters.forge(neoView);
		eq(Integer.MAX_VALUE, forgeView.getEnergyStored()); eq(Integer.MAX_VALUE, forgeView.getMaxEnergyStored());
		try (var tx = neoRoot()) { eq(Integer.MAX_VALUE, neoView.extract(Integer.MAX_VALUE, tx)); eq(five - Integer.MAX_VALUE, reservoir.amount); }
		eq(five, reservoir.amount);
		eq(Integer.MAX_VALUE, forgeView.extractEnergy(Integer.MAX_VALUE, true)); eq(five, reservoir.amount);
		// Fabric consumer, long request into an int store with room for all of it: exactly Integer.MAX_VALUE moves.
		Neo sink = new Neo(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 0);
		try (Transaction tx = Transaction.openOuter()) {
			eq(Integer.MAX_VALUE, RebornEnergyAdapters.fabric(sink).insert(five, tx)); // asked, not moved: aborted below
		}
		eq(0, sink.getAmountAsLong());
		try (Transaction tx = Transaction.openOuter()) { eq(Integer.MAX_VALUE, EnergyStorageUtil.move(reservoir, RebornEnergyAdapters.fabric(sink), five, tx)); tx.commit(); }
		eq(Integer.MAX_VALUE, sink.getAmountAsLong()); eq(five - Integer.MAX_VALUE, reservoir.amount);
		eq(five, reservoir.amount + sink.getAmountAsLong());
		// The same into Forge's own store: its int capacity takes Integer.MAX_VALUE of the remaining 2,852,516,353.
		EnergyStorage forge = new EnergyStorage(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 0);
		try (Transaction tx = Transaction.openOuter()) {
			eq(Integer.MAX_VALUE, EnergyStorageUtil.move(reservoir, RebornEnergyAdapters.fabric(ForgeEnergyAdapters.neo(forge)), five, tx)); tx.commit();
		}
		eq(Integer.MAX_VALUE, forge.getEnergyStored()); eq(five - 2L * Integer.MAX_VALUE, reservoir.amount);
		eq(five, reservoir.amount + sink.getAmountAsLong() + forge.getEnergyStored());
		expected(IllegalArgumentException.class, () -> { try (Transaction tx = Transaction.openOuter()) { RebornEnergyAdapters.fabric(sink).insert(-1, tx); } });
		eq(Integer.MAX_VALUE, EnergyUnits.request(Long.MAX_VALUE)); eq(7, EnergyUnits.request(7)); closed();
	}

	/**
	 * Only Forge's standard store (certified) and subclasses that declare none of IEnergyStorage's methods are written
	 * transactionally. Anything else gets no view, is never called, and is reported once per class.
	 */
	public static void unauditedForgeStoresAreRefused() {
		List<TransferIssues.Issue> issues = capture();
		AtomicInteger touched = new AtomicInteger();
		IEnergyStorage custom = new IEnergyStorage() {
			public int receiveEnergy(int max, boolean simulate) { touched.incrementAndGet(); return max; }
			public int extractEnergy(int max, boolean simulate) { touched.incrementAndGet(); return max; }
			public int getEnergyStored() { touched.incrementAndGet(); return 0; }
			public int getMaxEnergyStored() { touched.incrementAndGet(); return 100; }
			public boolean canExtract() { touched.incrementAndGet(); return true; }
			public boolean canReceive() { touched.incrementAndGet(); return true; }
		};
		for (int i = 0; i < 3; i++) yes(ForgeEnergyAdapters.neo(custom) == null, "a custom IEnergyStorage got a write bridge");
		eq(0, touched.get()); eq(1, count(issues, "FORGE_HANDLER_NOT_ROLLBACK_SAFE", custom.getClass()));
		EnergyStorage overriding = new EnergyStorage(100) {
			@Override public int receiveEnergy(int max, boolean simulate) { touched.incrementAndGet(); return super.receiveEnergy(max, simulate); }
		};
		yes(ForgeEnergyAdapters.neo(overriding) == null, "a subclass with its own receiveEnergy got a write bridge");
		eq(1, count(issues, "FORGE_HANDLER_NOT_ROLLBACK_SAFE", overriding.getClass()));
		IEnergyStorage proxy = (IEnergyStorage) java.lang.reflect.Proxy.newProxyInstance(NativeEnergyScenarios.class.getClassLoader(),
				new Class<?>[] {IEnergyStorage.class}, (self, method, arguments) -> { touched.incrementAndGet(); throw new AssertionError("proxy was called"); });
		yes(ForgeEnergyAdapters.neo(proxy) == null, "a proxy got a write bridge"); eq(0, touched.get());
		// A subclass that only adds state of its own runs exactly the audited code: it is admitted, and rolls back.
		Plain plain = new Plain(); plain.label = "unchanged";
		EnergyHandler view = ForgeEnergyAdapters.neo(plain);
		yes(view != null, "a structurally standard subclass was refused");
		try (var tx = neoRoot()) { eq(40, view.insert(40, tx)); eq(40, plain.getEnergyStored()); }
		eq(0, plain.getEnergyStored());
		try (var tx = neoRoot()) { eq(40, view.insert(40, tx)); tx.commit(); }
		eq(40, plain.getEnergyStored()); eq(0, count(issues, "FORGE_HANDLER_NOT_ROLLBACK_SAFE", Plain.class)); closed();
	}
	static final class Plain extends EnergyStorage {
		String label;
		Plain() { super(100); }
		void describe(StringBuilder out) { out.append(label); }
	}

	/** Run WITHOUT the shape certificate: even Forge's standard class gets no write view, and says why. */
	public static void uncertifiedStandardStoreIsRefused() {
		List<TransferIssues.Issue> issues = capture();
		EnergyStorage forge = new EnergyStorage(100, 100, 100, 30);
		yes(ForgeEnergyAdapters.neo(forge) == null, "an uncertified EnergyStorage got a write bridge");
		eq(30, forge.getEnergyStored());
		eq(1, count(issues, "TRANSFER_HELPER_UNVERIFIED", EnergyStorage.class));
		eq(1, count(issues, "FORGE_HANDLER_NOT_ROLLBACK_SAFE", EnergyStorage.class));
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
		// Forge's own code: a store holding more than its capacity (a loaded save can do that) "receives" a negative
		// amount and lowers its energy. The bridge rejects the answer and the abort puts the energy back.
		EnergyStorage overfull = new EnergyStorage(100, 100, 100, 0); setEnergy(overfull, 150);
		EnergyHandler view = ForgeEnergyAdapters.neo(overfull);
		try (var root = neoRoot()) { expected(IllegalStateException.class, () -> view.insert(10, root)); }
		eq(150, overfull.getEnergyStored()); closed();
	}

	private static void setEnergy(EnergyStorage storage, int energy) {
		try { var field = EnergyStorage.class.getDeclaredField("energy"); field.setAccessible(true); field.setInt(storage, energy); }
		catch (ReflectiveOperationException impossible) { throw new AssertionError(impossible); }
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

	/** A bridge is never wrapped in another bridge, in any direction. */
	public static void bridgesNeverStack() {
		Reborn fabric = new Reborn(10, 10, 10); Neo neo = new Neo(10, 10, 10, 0); EnergyStorage forge = new EnergyStorage(10);
		yes(RebornEnergyAdapters.fabric(RebornEnergyAdapters.neo(fabric)) == fabric, "Reborn -> NeoForge -> Reborn stacked");
		yes(RebornEnergyAdapters.neo(RebornEnergyAdapters.fabric(neo)) == neo, "NeoForge -> Reborn -> NeoForge stacked");
		yes(ForgeEnergyAdapters.neo(ForgeEnergyAdapters.forge(neo)) == neo, "NeoForge -> Forge -> NeoForge stacked");
		yes(ForgeEnergyAdapters.forge(ForgeEnergyAdapters.neo(forge)) == forge, "Forge -> NeoForge -> Forge stacked");
	}

	/** Final notifications of both engines run once, and only after both roots closed. */
	public static void finalNotificationsAfterBothRootsClose() {
		Reborn fabric = new Reborn(1000, 1000, 1000); Neo neo = new Neo(1000, 1000, 1000, 0);
		fabric.checkClosed = true; neo.checkClosed = true;
		EnergyStorage forge = new EnergyStorage(1000, 1000, 1000, 0);
		AtomicInteger forgeChanged = new AtomicInteger();
		List<String> order = new ArrayList<>();
		team.reborn.energy.api.EnergyStorage onNeo = RebornEnergyAdapters.fabric(neo);
		team.reborn.energy.api.EnergyStorage onForge = RebornEnergyAdapters.fabric(ForgeEnergyAdapters.neo(forge, forge, () -> {
			order.add("forge"); forgeChanged.incrementAndGet();
			yes(!Transaction.isOpen() && net.neoforged.neoforge.transfer.transaction.Transaction.getCurrentOpenedTransaction() == null, "notified before both roots closed");
		}));
		try (Transaction root = Transaction.openOuter()) {
			eq(10, fabric.insert(10, root)); eq(10, onNeo.insert(10, root)); eq(10, onForge.insert(10, root));
			try (Transaction child = root.openNested()) { eq(5, onForge.insert(5, child)); child.commit(); }
			root.commit();
		}
		eq(1, fabric.finals); eq(1, neo.changes); eq(1, forgeChanged.get()); eq(15, forge.getEnergyStored()); closed();
	}
}
