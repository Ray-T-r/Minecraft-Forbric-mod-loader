package net.forbric.kernel.runtime.transfer;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.LongSupplier;

import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.resource.Resource;

/**
 * Location-bound views never retain a resolved foreign handler. Every operation resolves it anew, and a removed
 * endpoint becomes empty. If removal happens inside a provider callback, throwing before the adapter's nested
 * transaction commits lets the real engine roll the provider back instead of silently writing into a dead object.
 */
public final class LiveTransferEndpoints {
	private LiveTransferEndpoints() { }
	static final class Unavailable extends IllegalStateException {
		Unavailable(String message) { super(message); }
	}
	private static void stillValid(BooleanSupplier valid) {
		if (!valid.getAsBoolean()) throw new Unavailable("Transfer endpoint invalidated during an operation");
	}
	public static <N extends Resource> ResourceHandler<N> neo(Supplier<ResourceHandler<N>> lookup, BooleanSupplier valid, N empty) {
		return neo(lookup, valid, () -> 0L, empty);
	}
	public static <N extends Resource> ResourceHandler<N> neo(Supplier<ResourceHandler<N>> lookup, BooleanSupplier valid, LongSupplier generation, N empty) {
		return new ResourceHandler<>() {
			private ResourceHandler<N> current() { return valid.getAsBoolean() ? lookup.get() : null; }
			private ResourceHandler<N> current(int slot) { var current = current(); return current != null && slot >= 0 && slot < current.size() ? current : null; }
			public int size() { var h = current(); return h == null ? 0 : h.size(); }
			public N getResource(int slot) { var h = current(slot); return h == null ? empty : h.getResource(slot); }
			public long getAmountAsLong(int slot) { var h = current(slot); return h == null ? 0 : h.getAmountAsLong(slot); }
			public long getCapacityAsLong(int slot, N resource) { var h = current(slot); return h == null ? 0 : h.getCapacityAsLong(slot, resource); }
			public boolean isValid(int slot, N resource) { var h = current(slot); return h != null && h.isValid(slot, resource); }
			public int insert(int slot, N resource, int max, net.neoforged.neoforge.transfer.transaction.TransactionContext tx) {
				var h = current(slot); if (h == null) return 0;
				long before = generation.getAsLong();
				int amount = h.insert(slot, resource, max, tx); stillValid(valid); unchanged(before, generation); return amount;
			}
			public int extract(int slot, N resource, int max, net.neoforged.neoforge.transfer.transaction.TransactionContext tx) {
				var h = current(slot); if (h == null) return 0;
				long before = generation.getAsLong();
				int amount = h.extract(slot, resource, max, tx); stillValid(valid); unchanged(before, generation); return amount;
			}
		};
	}
	public static <F> SlottedStorage<F> fabric(Supplier<SlottedStorage<F>> lookup, BooleanSupplier valid, F empty) {
		return fabric(lookup, valid, () -> 0L, empty);
	}
	public static <F> SlottedStorage<F> fabric(Supplier<SlottedStorage<F>> lookup, BooleanSupplier valid, LongSupplier generation, F empty) {
		return new SlottedStorage<>() {
			private SlottedStorage<F> current() { return valid.getAsBoolean() ? lookup.get() : null; }
			private SingleSlotStorage<F> current(int slot) { var s = current(); return s != null && slot >= 0 && slot < s.getSlotCount() ? s.getSlot(slot) : null; }
			public int getSlotCount() { var s = current(); return s == null ? 0 : s.getSlotCount(); }
			public long insert(F resource, long max, TransactionContext tx) {
				var s = current(); if (s == null) return 0;
				long before = generation.getAsLong();
				long amount = s.insert(resource, max, tx); stillValid(valid); unchanged(before, generation); return amount;
			}
			public long extract(F resource, long max, TransactionContext tx) {
				var s = current(); if (s == null) return 0;
				long before = generation.getAsLong();
				long amount = s.extract(resource, max, tx); stillValid(valid); unchanged(before, generation); return amount;
			}
			public SingleSlotStorage<F> getSlot(int index) {
				if (index < 0) throw new IndexOutOfBoundsException(index);
				return new SingleSlotStorage<>() {
					public boolean isResourceBlank() { var s = current(index); return s == null || s.isResourceBlank(); }
					public F getResource() { var s = current(index); return s == null ? empty : s.getResource(); }
					public long getAmount() { var s = current(index); return s == null ? 0 : s.getAmount(); }
					public long getCapacity() { var s = current(index); return s == null ? 0 : s.getCapacity(); }
					public boolean supportsInsertion() { var s = current(index); return s != null && s.supportsInsertion(); }
					public boolean supportsExtraction() { var s = current(index); return s != null && s.supportsExtraction(); }
					public long insert(F resource, long max, TransactionContext tx) {
						var s = current(index); if (s == null) return 0;
						long before = generation.getAsLong();
						long amount = s.insert(resource, max, tx); stillValid(valid); unchanged(before, generation); return amount;
					}
					public long extract(F resource, long max, TransactionContext tx) {
						var s = current(index); if (s == null) return 0;
						long before = generation.getAsLong();
						long amount = s.extract(resource, max, tx); stillValid(valid); unchanged(before, generation); return amount;
					}
				};
			}
			public Iterator<StorageView<F>> iterator() {
				return new Iterator<>() {
					int next;
					public boolean hasNext() { return next < getSlotCount(); }
					public StorageView<F> next() { if (!hasNext()) throw new NoSuchElementException(); return getSlot(next++); }
				};
			}
		};
	}
	private static void unchanged(long before, LongSupplier generation) {
		if (before != generation.getAsLong()) throw new Unavailable("Transfer capability invalidated during an operation");
	}
}
