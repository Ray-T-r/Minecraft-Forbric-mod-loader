package net.forbric.kernel.runtime.transfer;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.resource.Resource;

/**
 * Slot-preserving item/fluid adapters. Amounts are never rounded after mutation: a sub-quantum Fabric result is
 * rolled back, then retried once with an exactly representable maximum. Native providers keep their validation,
 * limits and transaction journals. An arbitrary unslotted Fabric Storage cannot implement indexed insertion and
 * is intentionally not accepted. Introspection is conservative: Fabric has no per-resource isValid method.
 */
public final class NativeTransferAdapters {
	private NativeTransferAdapters() { }

	@SuppressWarnings("unchecked")
	public static <F, N extends Resource> SlottedStorage<F> fabric(ResourceHandler<N> nativeHandler, TransferCodec<F, N> codec) {
		Objects.requireNonNull(nativeHandler); check(codec);
		if (nativeHandler instanceof FromFabric<?, ?> own && own.codec == codec) return (SlottedStorage<F>) own.storage;
		return new FromNeo<>(nativeHandler, codec);
	}
	@SuppressWarnings("unchecked")
	public static <F, N extends Resource> ResourceHandler<N> neo(SlottedStorage<F> nativeStorage, TransferCodec<F, N> codec) {
		Objects.requireNonNull(nativeStorage); check(codec);
		if (nativeStorage instanceof FromNeo<?, ?> own && own.codec == codec) return (ResourceHandler<N>) own.handler;
		return new FromFabric<>(nativeStorage, codec);
	}
	private static void check(TransferCodec<?, ?> codec) {
		Objects.requireNonNull(codec);
		if (codec.fabricUnits() <= 0) throw new IllegalArgumentException("A transfer unit must be positive");
	}
	private static void amount(long amount) {
		if (amount < 0) throw new IllegalArgumentException("Negative transfer amount");
	}
	private static long validResult(long moved, long maximum) {
		if (moved < 0 || moved > maximum) throw new IllegalStateException("Provider returned an invalid transfer amount: " + moved + "/" + maximum);
		return moved;
	}
	private static void requireSuccessfulRollback(LiveTransferEndpoints.Unavailable failure, Object provider) {
		// try-with-resources suppresses a close/rollback failure onto the original invalidation exception.
		// Returning zero in that case would assert a rollback the native participant failed to perform.
		if (failure.getSuppressed().length != 0) {
			TransferIssues.report("ROLLBACK_FAILED", provider, "Endpoint invalidated and its native rollback failed; resource state is unknown");
			throw new IllegalStateException("Cross-API rollback failed; resource state is unknown", failure);
		}
	}
	private static long multiply(long nativeAmount, long units) {
		// Saturating the read-only advertised capacity is safe; a transfer itself is bounded to an int request.
		if (nativeAmount < 0) throw new IllegalStateException("Provider returned a negative amount/capacity");
		return nativeAmount > Long.MAX_VALUE / units ? Long.MAX_VALUE : nativeAmount * units;
	}

	private static final class FromNeo<F, N extends Resource> implements SlottedStorage<F> {
		final ResourceHandler<N> handler;
		final TransferCodec<F, N> codec;
		FromNeo(ResourceHandler<N> handler, TransferCodec<F, N> codec) { this.handler = handler; this.codec = codec; }
		public int getSlotCount() { return handler.size(); }
		public SingleSlotStorage<F> getSlot(int slot) {
			Objects.checkIndex(slot, getSlotCount());
			return new Slot(slot);
		}
		public long insert(F resource, long maximum, TransactionContext transaction) { return move(-1, resource, maximum, transaction, true); }
		public long extract(F resource, long maximum, TransactionContext transaction) { return move(-1, resource, maximum, transaction, false); }
		long move(int slot, F resource, long maximum, TransactionContext context, boolean insert) {
			amount(maximum);
			if (maximum == 0 || codec.isFabricBlank(resource)) return 0;
			N nativeResource = codec.toNeo(resource);
			if (nativeResource == null) return 0;
			int request = (int) Math.min(Integer.MAX_VALUE, maximum / codec.fabricUnits());
			if (request == 0) return 0;
			var parent = PairedTransactions.neo(context);
			try (var transaction = net.neoforged.neoforge.transfer.transaction.Transaction.open(parent)) {
				int moved;
				if (slot < 0) moved = insert ? handler.insert(nativeResource, request, transaction) : handler.extract(nativeResource, request, transaction);
				else moved = insert ? handler.insert(slot, nativeResource, request, transaction) : handler.extract(slot, nativeResource, request, transaction);
				validResult(moved, request);
				transaction.commit();
				return moved * codec.fabricUnits();
			} catch (LiveTransferEndpoints.Unavailable invalidated) {
				requireSuccessfulRollback(invalidated, handler);
				TransferIssues.report("ENDPOINT_INVALIDATED", handler, invalidated.getMessage() + "; the nested operation was rolled back");
				return 0;
			}
		}
		public Iterator<StorageView<F>> iterator() {
			return new Iterator<>() {
				int next;
				public boolean hasNext() { return next < getSlotCount(); }
				public StorageView<F> next() { if (!hasNext()) throw new NoSuchElementException(); return getSlot(next++); }
			};
		}
		private final class Slot implements SingleSlotStorage<F> {
			final int index;
			Slot(int index) { this.index = index; }
			public boolean isResourceBlank() { return codec.isFabricBlank(getResource()); }
			public F getResource() { return codec.toFabric(handler.getResource(index)); }
			public long getAmount() { return multiply(handler.getAmountAsLong(index), codec.fabricUnits()); }
			public long getCapacity() { return multiply(handler.getCapacityAsLong(index, handler.getResource(index)), codec.fabricUnits()); }
			public long insert(F resource, long maximum, TransactionContext context) { return move(index, resource, maximum, context, true); }
			public long extract(F resource, long maximum, TransactionContext context) { return move(index, resource, maximum, context, false); }
		}
	}

	private static final class FromFabric<F, N extends Resource> implements ResourceHandler<N> {
		final SlottedStorage<F> storage;
		final TransferCodec<F, N> codec;
		FromFabric(SlottedStorage<F> storage, TransferCodec<F, N> codec) { this.storage = storage; this.codec = codec; }
		public int size() { return storage.getSlotCount(); }
		public N getResource(int index) { return codec.toNeo(storage.getSlot(index).getResource()); }
		public long getAmountAsLong(int index) { return storage.getSlot(index).getAmount() / codec.fabricUnits(); }
		public long getCapacityAsLong(int index, N resource) { return storage.getSlot(index).getCapacity() / codec.fabricUnits(); }
		// Fabric cannot answer resource-specific validity without attempting a transaction. Avoid a false negative
		// when the storage is merely full; insert still delegates to its real filter and may return zero.
		public boolean isValid(int index, N resource) { return codec.toFabric(resource) != null && storage.getSlot(index).supportsInsertion(); }
		public int insert(int index, N resource, int maximum, net.neoforged.neoforge.transfer.transaction.TransactionContext context) {
			return move(index, resource, maximum, context, true);
		}
		public int extract(int index, N resource, int maximum, net.neoforged.neoforge.transfer.transaction.TransactionContext context) {
			return move(index, resource, maximum, context, false);
		}
		int move(int index, N resource, int maximum, net.neoforged.neoforge.transfer.transaction.TransactionContext context, boolean insert) {
			amount(maximum);
			if (maximum == 0) return 0;
			F converted = codec.toFabric(resource);
			if (converted == null || codec.isFabricBlank(converted)) return 0;
			SingleSlotStorage<F> slot = storage.getSlot(index);
			long request = Math.multiplyExact((long) maximum, codec.fabricUnits());
			TransactionContext parent = PairedTransactions.fabric(context);
			// A provider may accept less than requested, including a fraction of one mB. Every unsuccessful trial
			// is aborted in its OWN nested scope; no rounded amount is ever returned for an unrounded mutation.
			for (int attempt = 0; attempt < 2 && request > 0; attempt++) {
				try (Transaction transaction = parent.openNested()) {
					long moved = validResult(insert ? slot.insert(converted, request, transaction) : slot.extract(converted, request, transaction), request);
					if (moved % codec.fabricUnits() == 0) {
						transaction.commit();
						return Math.toIntExact(moved / codec.fabricUnits());
					}
					request = moved / codec.fabricUnits() * codec.fabricUnits();
				} catch (LiveTransferEndpoints.Unavailable invalidated) {
					requireSuccessfulRollback(invalidated, storage);
					TransferIssues.report("ENDPOINT_INVALIDATED", storage, invalidated.getMessage() + "; the nested operation was rolled back");
					return 0;
				}
			}
			return 0;
		}
	}
}
