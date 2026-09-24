package net.forbric.kernel.runtime.transfer;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import team.reborn.energy.api.EnergyStorage;

/**
 * Team Reborn Energy (the Fabric ecosystem's energy API) and NeoForge's EnergyHandler, through PairedTransactions:
 * each operation runs in a REAL nested transaction of the provider's own engine, opened under the peer of the
 * consumer's current transaction, and commits into it. Nothing moves outside the consumer's scope, a nested abort
 * restores the provider through its own journal, and final notifications wait for both roots.
 *
 * <p>Units are 1:1 (see EnergyUnits). A Reborn request larger than an int is asked of NeoForge as Integer.MAX_VALUE;
 * the rest is never moved and stays in its source. A provider answer outside [0, request] is rejected before the
 * nested scope commits, so it is rolled back.
 *
 * <p>Only this class, RebornEnergyBridge and nothing else in the runtime names a Reborn type. They are loaded only
 * when KernelTransferInterop found Team Reborn Energy installed; without it no Reborn class is ever requested.
 */
public final class RebornEnergyAdapters {
	private RebornEnergyAdapters() { }

	/** A NeoForge view of a Reborn store; our own Reborn view of a NeoForge handler unwraps to that handler. */
	public static EnergyHandler neo(EnergyStorage storage) {
		Objects.requireNonNull(storage);
		if (storage instanceof FromNeo own) return own.handler();
		return new FromFabric(storage);
	}
	/** A Reborn view of a NeoForge handler; our own NeoForge view of a Reborn store unwraps to that store. */
	public static EnergyStorage fabric(EnergyHandler handler) {
		Objects.requireNonNull(handler);
		if (handler instanceof FromFabric own) return own.storage();
		return new FromNeo(handler);
	}

	/** A Reborn store resolved again for every operation, under LiveTransferEndpoints' rules. */
	public static EnergyStorage live(Supplier<EnergyStorage> lookup, BooleanSupplier valid, LongSupplier generation) {
		return new Live(lookup, valid, generation);
	}

	private record FromFabric(EnergyStorage storage) implements EnergyHandler, EnergyAbilities {
		public long getAmountAsLong() { return EnergyUnits.reported(storage.getAmount()); }
		public long getCapacityAsLong() { return EnergyUnits.reported(storage.getCapacity()); }
		public int insert(int maximum, net.neoforged.neoforge.transfer.transaction.TransactionContext context) { return move(maximum, context, true); }
		public int extract(int maximum, net.neoforged.neoforge.transfer.transaction.TransactionContext context) { return move(maximum, context, false); }
		private int move(int maximum, net.neoforged.neoforge.transfer.transaction.TransactionContext context, boolean insert) {
			if (maximum < 0) throw new IllegalArgumentException("Negative energy amount: " + maximum);
			if (maximum == 0) return 0;
			TransactionContext parent = PairedTransactions.fabric(context);
			try (Transaction nested = parent.openNested()) {
				long moved = EnergyUnits.moved(insert ? storage.insert(maximum, nested) : storage.extract(maximum, nested), maximum);
				nested.commit();
				return (int) moved;
			} catch (LiveTransferEndpoints.Unavailable invalidated) {
				NativeTransferAdapters.requireSuccessfulRollback(invalidated, storage);
				TransferIssues.report("ENDPOINT_INVALIDATED", storage, invalidated.getMessage() + "; the nested operation was rolled back");
				return 0;
			}
		}
		public boolean canInsert() { return storage.supportsInsertion(); }
		public boolean canExtract() { return storage.supportsExtraction(); }
		@Override public boolean equals(Object other) { return this == other; }
		@Override public int hashCode() { return System.identityHashCode(this); }
	}

	private record FromNeo(EnergyHandler handler) implements EnergyStorage {
		public boolean supportsInsertion() { return EnergyAbilities.fabricCanInsert(handler); }
		public boolean supportsExtraction() { return EnergyAbilities.fabricCanExtract(handler); }
		public long insert(long maximum, TransactionContext context) { return move(maximum, context, true); }
		public long extract(long maximum, TransactionContext context) { return move(maximum, context, false); }
		private long move(long maximum, TransactionContext context, boolean insert) {
			int request = EnergyUnits.request(maximum);
			if (request == 0) return 0;
			var parent = PairedTransactions.neo(context);
			try (var nested = net.neoforged.neoforge.transfer.transaction.Transaction.open(parent)) {
				long moved = EnergyUnits.moved(insert ? handler.insert(request, nested) : handler.extract(request, nested), request);
				nested.commit();
				return moved;
			} catch (LiveTransferEndpoints.Unavailable invalidated) {
				NativeTransferAdapters.requireSuccessfulRollback(invalidated, handler);
				TransferIssues.report("ENDPOINT_INVALIDATED", handler, invalidated.getMessage() + "; the nested operation was rolled back");
				return 0;
			}
		}
		public long getAmount() { return EnergyUnits.reported(handler.getAmountAsLong()); }
		public long getCapacity() { return EnergyUnits.reported(handler.getCapacityAsLong()); }
		@Override public boolean equals(Object other) { return this == other; }
		@Override public int hashCode() { return System.identityHashCode(this); }
	}

	private record Live(Supplier<EnergyStorage> lookup, BooleanSupplier valid, LongSupplier generation) implements EnergyStorage {
		private EnergyStorage current() { return valid.getAsBoolean() ? lookup.get() : null; }
		public boolean supportsInsertion() { var s = current(); return s != null && s.supportsInsertion(); }
		public boolean supportsExtraction() { var s = current(); return s != null && s.supportsExtraction(); }
		public long insert(long maximum, TransactionContext context) {
			var s = current(); if (s == null) return 0;
			long before = generation.getAsLong();
			long moved = s.insert(maximum, context); LiveTransferEndpoints.stillValid(valid); LiveTransferEndpoints.unchanged(before, generation); return moved;
		}
		public long extract(long maximum, TransactionContext context) {
			var s = current(); if (s == null) return 0;
			long before = generation.getAsLong();
			long moved = s.extract(maximum, context); LiveTransferEndpoints.stillValid(valid); LiveTransferEndpoints.unchanged(before, generation); return moved;
		}
		public long getAmount() { var s = current(); return s == null ? 0 : s.getAmount(); }
		public long getCapacity() { var s = current(); return s == null ? 0 : s.getCapacity(); }
		@Override public boolean equals(Object other) { return this == other; }
		@Override public int hashCode() { return System.identityHashCode(this); }
	}
}
