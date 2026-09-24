package net.forbric.kernel.runtime.transfer;

import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * The safe opposite direction: an old Forge caller can use a transactional Fabric/NeoForge provider. Every
 * simulate call performs a real nested operation then aborts; execute commits that operation. If a native outer
 * scope already exists, it remains the owner of the ultimate commit/rollback. There is no delayed execution based
 * on an earlier simulation and no promise that two distinct old-style calls form one atomic operation.
 */
public final class ForgeLegacyFacades {
	private ForgeLegacyFacades() { }
	public static IItemHandler items(ResourceHandler<ItemResource> handler) { return new Items(java.util.Objects.requireNonNull(handler)); }
	public static IFluidHandler fluids(ResourceHandler<FluidResource> handler) { return new Fluids(java.util.Objects.requireNonNull(handler)); }
	static ResourceHandler<ItemResource> unwrapItems(IItemHandler handler) { return handler instanceof Items own ? own.handler : null; }
	static ResourceHandler<FluidResource> unwrapFluids(IFluidHandler handler) { return handler instanceof Fluids own ? own.handler : null; }
	static net.neoforged.neoforge.transfer.transaction.Transaction scope() {
		var parent = net.neoforged.neoforge.transfer.transaction.Transaction.getCurrentOpenedTransaction();
		if (parent == null && Transaction.isOpen()) parent = PairedTransactions.neo(Transaction.getCurrentUnsafe());
		return net.neoforged.neoforge.transfer.transaction.Transaction.open(parent);
	}
	/**
	 * The endpoint went away during the operation (its block replaced, its capabilities invalidated). The scope was
	 * closed uncommitted, so the provider's own engine rolled it back; a Forge caller, which has no transaction to
	 * abort, is told nothing moved -- as the energy facade and the Reborn/NeoForge views tell theirs -- instead of an
	 * exception thrown into the Forge mod's tick.
	 */
	private static void invalidated(LiveTransferEndpoints.Unavailable failure, Object handler) {
		NativeTransferAdapters.requireSuccessfulRollback(failure, handler);
		TransferIssues.report("ENDPOINT_INVALIDATED", handler, failure.getMessage() + "; the operation was rolled back");
	}
	private static int amount(long amount) {
		if (amount < 0) throw new IllegalStateException("Provider advertised a negative amount");
		return (int) Math.min(Integer.MAX_VALUE, amount);
	}
	private record Items(ResourceHandler<ItemResource> handler) implements IItemHandler {
		public int getSlots() { return handler.size(); }
		public ItemStack getStackInSlot(int slot) { return handler.getResource(slot).toStack(amount(handler.getAmountAsLong(slot))); }
		public int getSlotLimit(int slot) { return amount(handler.getCapacityAsLong(slot, handler.getResource(slot))); }
		public boolean isItemValid(int slot, ItemStack stack) { return !stack.isEmpty() && handler.isValid(slot, ItemResource.of(stack)); }
		public ItemStack insertItem(int slot, ItemStack input, boolean simulate) {
			if (input.isEmpty()) return ItemStack.EMPTY;
			int maximum = input.getCount();
			try (var transaction = scope()) {
				int moved = handler.insert(slot, ItemResource.of(input), maximum, transaction);
				ForgeSnapshotAdapters.checkAmount(moved, maximum);
				if (!simulate) transaction.commit();
				return input.copyWithCount(maximum - moved);
			} catch (LiveTransferEndpoints.Unavailable gone) {
				invalidated(gone, handler);
				return input.copy();
			}
		}
		public ItemStack extractItem(int slot, int maximum, boolean simulate) {
			if (maximum < 0) throw new IllegalArgumentException("Negative item amount");
			if (maximum == 0) return ItemStack.EMPTY;
			ItemResource resource = handler.getResource(slot);
			if (resource.isEmpty()) return ItemStack.EMPTY;
			// IItemHandler's contract, as ItemStackHandler and NeoForge's own adapter implement it: the result is at
			// most ONE stack, even when the store holds more and the caller asks for more. getStackInSlot may still
			// report the whole amount. An oversized ItemStack is not even encodable by the item codec.
			int limit = Math.min(maximum, resource.getMaxStackSize());
			try (var transaction = scope()) {
				int moved = handler.extract(slot, resource, limit, transaction);
				ForgeSnapshotAdapters.checkAmount(moved, limit);
				if (!simulate) transaction.commit();
				return resource.toStack(moved);
			} catch (LiveTransferEndpoints.Unavailable gone) {
				invalidated(gone, handler);
				return ItemStack.EMPTY;
			}
		}
	}
	private record Fluids(ResourceHandler<FluidResource> handler) implements IFluidHandler {
		public int getTanks() { return handler.size(); }
		public FluidStack getFluidInTank(int tank) {
			FluidStack stack = ForgeFluidMetadata.toForge(handler.getResource(tank), amount(handler.getAmountAsLong(tank)));
			return stack == null ? FluidStack.EMPTY : stack;
		}
		public int getTankCapacity(int tank) { return amount(handler.getCapacityAsLong(tank, handler.getResource(tank))); }
		public boolean isFluidValid(int tank, FluidStack stack) {
			FluidResource resource = ForgeFluidMetadata.toNeo(stack);
			return resource != null && !resource.isEmpty() && handler.isValid(tank, resource);
		}
		public int fill(FluidStack stack, FluidAction action) {
			java.util.Objects.requireNonNull(action);
			if (stack.isEmpty()) return 0;
			FluidResource resource = ForgeFluidMetadata.toNeo(stack); if (resource == null) return 0;
			try (var transaction = scope()) {
				int moved = handler.insert(resource, stack.getAmount(), transaction);
				ForgeSnapshotAdapters.checkAmount(moved, stack.getAmount());
				if (action.execute()) transaction.commit();
				return moved;
			} catch (LiveTransferEndpoints.Unavailable gone) {
				invalidated(gone, handler);
				return 0;
			}
		}
		public FluidStack drain(FluidStack stack, FluidAction action) {
			java.util.Objects.requireNonNull(action);
			if (stack.isEmpty()) return FluidStack.EMPTY;
			FluidResource resource = ForgeFluidMetadata.toNeo(stack);
			return resource == null ? FluidStack.EMPTY : drain(resource, stack.getAmount(), action);
		}
		public FluidStack drain(int maximum, FluidAction action) {
			java.util.Objects.requireNonNull(action);
			if (maximum < 0) throw new IllegalArgumentException("Negative fluid amount");
			if (maximum == 0) return FluidStack.EMPTY;
			for (int slot = 0; slot < handler.size(); slot++) {
				FluidResource resource = handler.getResource(slot);
				if (!resource.isEmpty() && handler.getAmountAsLong(slot) > 0 && ForgeFluidMetadata.toForge(resource, 1) != null) {
					FluidStack extracted = drain(resource, maximum, action);
					if (!extracted.isEmpty()) return extracted;
				}
			}
			return FluidStack.EMPTY;
		}
		private FluidStack drain(FluidResource resource, int maximum, FluidAction action) {
			if (ForgeFluidMetadata.toForge(resource, 1) == null) return FluidStack.EMPTY;
			try (var transaction = scope()) {
				int moved = handler.extract(resource, maximum, transaction);
				ForgeSnapshotAdapters.checkAmount(moved, maximum);
				FluidStack result = ForgeFluidMetadata.toForge(resource, moved);
				// Codecs are called again deliberately; a stateful or newly failing codec cannot lose the extracted data.
				if (result == null) return FluidStack.EMPTY;
				if (action.execute()) transaction.commit();
				return result;
			} catch (LiveTransferEndpoints.Unavailable gone) {
				invalidated(gone, handler);
				return FluidStack.EMPTY;
			}
		}
	}
}
