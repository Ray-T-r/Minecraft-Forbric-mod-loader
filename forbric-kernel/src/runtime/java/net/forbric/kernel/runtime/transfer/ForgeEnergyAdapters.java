package net.forbric.kernel.runtime.transfer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import net.forbric.kernel.transform.ForgeTransferShapeAudit;
import net.minecraftforge.energy.EmptyEnergyStorage;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.energy.IEnergyStorage;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * MinecraftForge energy on both sides of a transaction.
 *
 * <p>{@link #neo}: a transactional view of a Forge store. Forge's IEnergyStorage has no transactions, only
 * simulate/execute, so a write can be taken back only by restoring the store's own state. That is proved for exactly
 * one implementation: Forge's standard {@code net.minecraftforge.energy.EnergyStorage}, whose final definition carries
 * the ForgeTransferShapeAudit certificate, and a subclass of it only when no class below it declares any of the six
 * IEnergyStorage methods (so the standard receive/extract code is what runs, and its whole state is the int
 * {@code energy} field). Writes go through the store's own receiveEnergy/extractEnergy (its capacity and
 * maxReceive/maxExtract limits apply); one journal per thread snapshots the {@code energy} field of every store it
 * touches, restores it on abort at any nesting depth, and dirties each block entity once per root commit. Any other
 * IEnergyStorage gets no view at all and is reported once per class as FORGE_HANDLER_NOT_ROLLBACK_SAFE; there is no
 * switch that grants it one. A non-transactional write to the same store while a transaction holding it is open is
 * undone with that transaction if it aborts, as with the item and fluid journals.
 *
 * <p>The standard store can hold more than its capacity (or less than nothing): its deserializeNBT sets the field
 * unclamped, so a save made before a config lowered the capacity loads that way. Its own receiveEnergy then answers a
 * NEGATIVE amount and lowers the field. That is Forge's code on a state Forge allows, not a broken provider, so the
 * view refuses the operation instead of throwing into the consumer's tick: the field is put back as it was and nothing
 * moves. The store still gives its energy away normally, down into its bounds.
 *
 * <p>Forge's own {@code EmptyEnergyStorage} (that exact class) holds nothing and accepts nothing. It is the owner's
 * answer "no energy here", not a store to audit: it becomes an empty view that answers for the owner, and no finding.
 *
 * <p>{@link #forge}: the opposite direction, a Forge consumer of a transactional store. Every simulate is a real
 * operation in a transaction that is then aborted, and every execute commits it; an already-open NeoForge (or paired
 * Fabric) transaction stays the owner of the final commit, exactly as ForgeLegacyFacades.
 */
public final class ForgeEnergyAdapters {
	private ForgeEnergyAdapters() { }
	private static final Field ENERGY = field("energy");
	/** Every IEnergyStorage method. A subclass declaring any of them no longer runs the audited code. */
	private static final Set<String> CRITICAL = Set.of("receiveEnergy", "extractEnergy", "getEnergyStored", "getMaxEnergyStored", "canReceive", "canExtract");
	private static final ThreadLocal<Journal> CURRENT = new ThreadLocal<>();
	private static final ClassValue<Boolean> CERTIFIED = new ClassValue<>() {
		protected Boolean computeValue(Class<?> type) {
			try { type.getDeclaredMethod(ForgeTransferShapeAudit.MARKER); return true; }
			catch (NoSuchMethodException | LinkageError unverified) { return false; }
		}
	};
	private static final ClassValue<Boolean> STANDARD_SHAPE = new ClassValue<>() {
		protected Boolean computeValue(Class<?> type) {
			if (!EnergyStorage.class.isAssignableFrom(type)) return false;
			try {
				for (Class<?> at = type; at != EnergyStorage.class; at = at.getSuperclass())
					for (Method method : at.getDeclaredMethods()) if (CRITICAL.contains(method.getName())) return false;
				return true;
			} catch (LinkageError unreadable) { return false; }
		}
	};

	private static Field field(String name) {
		try { Field field = EnergyStorage.class.getDeclaredField(name); field.setAccessible(true); return field; }
		catch (ReflectiveOperationException drift) { throw new ExceptionInInitializerError(drift); }
	}
	private static int read(EnergyStorage storage) {
		try { return ENERGY.getInt(storage); } catch (IllegalAccessException impossible) { throw new IllegalStateException(impossible); }
	}
	private static void write(EnergyStorage storage, int value) {
		try { ENERGY.setInt(storage, value); } catch (IllegalAccessException impossible) { throw new IllegalStateException(impossible); }
	}

	/** Whether {@code storage} may be written transactionally: the certified standard class, or a subclass of its exact shape. */
	public static boolean supports(IEnergyStorage storage) {
		return storage instanceof EnergyStorage standard && STANDARD_SHAPE.get(standard.getClass()) && certified();
	}
	private static boolean certified() {
		boolean approved = CERTIFIED.get(EnergyStorage.class);
		if (!approved) TransferIssues.reportType("TRANSFER_HELPER_UNVERIFIED", EnergyStorage.class.getName(),
				ForgeTransferShapeAudit.declined(EnergyStorage.class.getName()));
		return approved;
	}
	private static void refused(Object storage) {
		TransferIssues.report("FORGE_HANDLER_NOT_ROLLBACK_SAFE", storage,
				"The energy provider is not an audited reversible implementation; transactional insertion and extraction were not exposed");
	}

	public static EnergyHandler neo(IEnergyStorage storage) { return neo(storage, storage, () -> { }); }
	/** A NeoForge view of an audited Forge store, or null. {@code changed} runs once per root commit that moved energy. */
	public static EnergyHandler neo(IEnergyStorage storage, Object owner, Runnable changed) {
		Objects.requireNonNull(changed);
		if (storage == null) return null;
		// A Forge view of a transactional store handed back to us (a cable passing on its neighbour's handler): the
		// store itself, never a bridge of a bridge.
		if (storage instanceof Facade own) return own.handler();
		if (storage.getClass() == EmptyEnergyStorage.class) return Empty.INSTANCE;
		if (!supports(storage)) { refused(storage); return null; }
		return new View((EnergyStorage) storage, owner, changed);
	}

	/** A Forge view of a transactional store. */
	public static IEnergyStorage forge(EnergyHandler handler) {
		Objects.requireNonNull(handler);
		if (handler instanceof View own) return own.storage();
		if (handler == Empty.INSTANCE) return EmptyEnergyStorage.INSTANCE;
		return new Facade(handler);
	}

	private static Journal journal() {
		Journal journal = CURRENT.get();
		if (journal == null || !journal.isInTransaction()) { journal = new Journal(); CURRENT.set(journal); }
		return journal;
	}

	/** Identity semantics on purpose: SnapshotJournal hands snapshots back, and two equal-valued ones are different depths. */
	private static final class Snapshot {
		final IdentityHashMap<EnergyStorage, Integer> values = new IdentityHashMap<>();
		final IdentityHashMap<Object, Runnable> notifications;
		Snapshot(IdentityHashMap<Object, Runnable> notifications) { this.notifications = new IdentityHashMap<>(notifications); }
	}
	private static final class Journal extends SnapshotJournal<Snapshot> {
		final Set<EnergyStorage> tracked = Collections.newSetFromMap(new IdentityHashMap<>());
		final List<Snapshot> snapshots = new ArrayList<>();
		IdentityHashMap<Object, Runnable> notifications = new IdentityHashMap<>();
		void prepare(EnergyStorage storage, TransactionContext context) {
			// A store first touched at depth n must still be restored if a shallower scope aborts: every live snapshot
			// learns its value now, before this operation changes it.
			for (Snapshot snapshot : snapshots) snapshot.values.putIfAbsent(storage, read(storage));
			tracked.add(storage);
			updateSnapshots(context);
		}
		protected Snapshot createSnapshot() {
			Snapshot snapshot = new Snapshot(notifications);
			for (EnergyStorage storage : tracked) snapshot.values.put(storage, read(storage));
			snapshots.add(snapshot);
			return snapshot;
		}
		protected void revertToSnapshot(Snapshot snapshot) {
			for (var entry : snapshot.values.entrySet()) write(entry.getKey(), entry.getValue());
			notifications = new IdentityHashMap<>(snapshot.notifications);
		}
		protected void releaseSnapshot(Snapshot snapshot) {
			snapshots.remove(snapshot);
			if (snapshots.isEmpty()) clear();
		}
		protected void onRootCommit(Snapshot original) {
			List<Runnable> callbacks = new ArrayList<>(notifications.values()); clear();
			RuntimeException failure = null;
			for (Runnable callback : callbacks) try { callback.run(); }
			catch (RuntimeException thrown) { if (failure == null) failure = thrown; else failure.addSuppressed(thrown); }
			if (failure != null) throw failure;
		}
		void clear() {
			tracked.clear(); snapshots.clear(); notifications.clear();
			if (CURRENT.get() == this) CURRENT.remove();
		}
	}

	private record View(EnergyStorage storage, Object owner, Runnable changed) implements EnergyHandler, EnergyAbilities {
		// Reads never move energy; a malformed negative Forge amount reads as empty rather than failing a render.
		public long getAmountAsLong() { return Math.max(0, storage.getEnergyStored()); }
		public long getCapacityAsLong() { return Math.max(0, storage.getMaxEnergyStored()); }
		public int insert(int maximum, TransactionContext transaction) { return move(maximum, transaction, true); }
		public int extract(int maximum, TransactionContext transaction) { return move(maximum, transaction, false); }
		private int move(int maximum, TransactionContext transaction, boolean insert) {
			if (maximum < 0) throw new IllegalArgumentException("Negative energy amount: " + maximum);
			if (maximum == 0) return 0;
			if (!supports(storage)) { refused(storage); return 0; }
			Journal journal = journal(); journal.prepare(storage, transaction);
			// The store's own code, with its own capacity and receive/extract limits.
			int before = read(storage);
			int moved = insert ? storage.receiveEnergy(maximum, false) : storage.extractEnergy(maximum, false);
			if (moved < 0 || moved > maximum) {
				// Only a store outside its own bounds answers this (see the class comment); the certified code cannot
				// otherwise. Nothing moved: the field is exactly what it was, with or without a later abort.
				write(storage, before);
				return 0;
			}
			if (moved > 0) journal.notifications.put(owner, changed);
			return moved;
		}
		public boolean canInsert() { return storage.canReceive(); }
		public boolean canExtract() { return storage.canExtract(); }
		@Override public boolean equals(Object other) { return this == other; }
		@Override public int hashCode() { return System.identityHashCode(this); }
	}

	/** The view of Forge's EmptyEnergyStorage: nothing stored, nothing accepted, no direction. Never touches the store. */
	private enum Empty implements EnergyHandler, EnergyAbilities {
		INSTANCE;
		public long getAmountAsLong() { return 0; }
		public long getCapacityAsLong() { return 0; }
		public int insert(int maximum, TransactionContext transaction) { return nothing(maximum); }
		public int extract(int maximum, TransactionContext transaction) { return nothing(maximum); }
		private static int nothing(int maximum) {
			if (maximum < 0) throw new IllegalArgumentException("Negative energy amount: " + maximum);
			return 0;
		}
		public boolean canInsert() { return false; }
		public boolean canExtract() { return false; }
	}

	private record Facade(EnergyHandler handler) implements IEnergyStorage {
		public int receiveEnergy(int maximum, boolean simulate) { return move(maximum, simulate, true); }
		public int extractEnergy(int maximum, boolean simulate) { return move(maximum, simulate, false); }
		private int move(int maximum, boolean simulate, boolean insert) {
			if (maximum <= 0) return 0;
			try (var transaction = ForgeLegacyFacades.scope()) {
				int moved = (int) EnergyUnits.moved(insert ? handler.insert(maximum, transaction) : handler.extract(maximum, transaction), maximum);
				if (!simulate) transaction.commit();
				return moved;
			} catch (LiveTransferEndpoints.Unavailable invalidated) {
				// The endpoint went away during the operation (its block replaced, its capabilities invalidated). The
				// scope above was closed uncommitted, so the provider's own engine rolled it back; a Forge caller, which
				// has no transaction to abort, is told nothing moved, exactly as the Reborn and NeoForge views tell theirs.
				NativeTransferAdapters.requireSuccessfulRollback(invalidated, handler);
				TransferIssues.report("ENDPOINT_INVALIDATED", handler, invalidated.getMessage() + "; the operation was rolled back");
				return 0;
			}
		}
		public int getEnergyStored() { return EnergyUnits.saturated(handler.getAmountAsLong()); }
		public int getMaxEnergyStored() { return EnergyUnits.saturated(handler.getCapacityAsLong()); }
		public boolean canReceive() { return EnergyAbilities.forgeCanInsert(handler); }
		public boolean canExtract() { return EnergyAbilities.forgeCanExtract(handler); }
		@Override public boolean equals(Object other) { return this == other; }
		@Override public int hashCode() { return System.identityHashCode(this); }
	}
}
