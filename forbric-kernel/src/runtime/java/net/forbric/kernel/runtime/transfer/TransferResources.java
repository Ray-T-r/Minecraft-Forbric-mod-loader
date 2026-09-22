package net.forbric.kernel.runtime.transfer;

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;

/** Exact 26.2 identity mappings. Both APIs share the game's registry objects and DataComponentPatch. */
public final class TransferResources {
	private TransferResources() { }
	public static final TransferCodec<ItemVariant, ItemResource> ITEMS = new TransferCodec<>() {
		public ItemResource toNeo(ItemVariant value) { return value.isBlank() ? ItemResource.EMPTY : ItemResource.of(value.getItem(), value.getComponentsPatch()); }
		public ItemVariant toFabric(ItemResource value) { return value.isEmpty() ? ItemVariant.blank() : ItemVariant.of(value.getItem(), value.getComponentsPatch()); }
		public boolean isFabricBlank(ItemVariant value) { return value.isBlank(); }
		public long fabricUnits() { return 1; }
	};
	public static final TransferCodec<FluidVariant, FluidResource> FLUIDS = new TransferCodec<>() {
		public FluidResource toNeo(FluidVariant value) { return value.isBlank() ? FluidResource.EMPTY : FluidResource.of(value.getFluid(), value.getComponentsPatch()); }
		public FluidVariant toFabric(FluidResource value) { return value.isEmpty() ? FluidVariant.blank() : FluidVariant.of(value.getFluid(), value.getComponentsPatch()); }
		public boolean isFabricBlank(FluidVariant value) { return value.isBlank(); }
		public long fabricUnits() { return 81; }
	};
}
