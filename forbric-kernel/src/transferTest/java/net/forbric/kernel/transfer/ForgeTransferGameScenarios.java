package net.forbric.kernel.transfer;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleVariantStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.forbric.kernel.runtime.transfer.ForgeFluidMetadata;
import net.forbric.kernel.runtime.transfer.ForgeLegacyFacades;
import net.forbric.kernel.runtime.transfer.ForgeSnapshotAdapters;
import net.forbric.kernel.runtime.transfer.NativeTransferAdapters;
import net.forbric.kernel.runtime.transfer.TransferResources;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.fluid.FluidStacksResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;

/**
 * Real carrier + real Minecraft tests, invoked after the genuine kernel's registration window by the canary.
 * Forge FluidStack reads ForgeRegistries.FLUIDS in its constructor; replacing that registry with a unit-test stub
 * would skip the very compatibility contract these tests need to exercise. No fixture pretends to bootstrap FML.
 */
public final class ForgeTransferGameScenarios {
	private ForgeTransferGameScenarios() { }
	public static void watchdogDump() { deepWatchdog(35); }
	private static void deepWatchdog(int depth) {
		if (depth > 0) { deepWatchdog(depth - 1); return; }
		// Only constructs diagnostic text. It never invokes the watchdog run/exit or writes a crash report.
		var report = net.minecraft.server.dedicated.ServerWatchdog.createWatchdogCrashReport("Forbric diagnostic proof", Thread.currentThread().threadId());
		String text = report.getFriendlyReport(net.minecraft.ReportType.TEST);
		int dump = text.indexOf("-- Thread Dump --"); yes(dump >= 0);
		yes(text.substring(dump).split("deepWatchdog", -1).length > 35);
		yes(net.forbric.api.CompatibilityFindings.all().stream().anyMatch(f -> f.id().contains("ServerWatchdogMixin#printEntireThreadDump")
				&& f.confidence() == net.forbric.api.CompatibilityFinding.Confidence.RESOLVED));
	}
	private static void eq(long wanted, long actual) { if (wanted != actual) throw new AssertionError(wanted + " != " + actual); }
	private static void yes(boolean value) { if (!value) throw new AssertionError("condition failed"); }
	private static void closed() {
		yes(!Transaction.isOpen());
		yes(net.neoforged.neoforge.transfer.transaction.Transaction.getCurrentOpenedTransaction() == null);
	}
	private static ItemStack taggedStone(int amount) {
		ItemStack stack = new ItemStack(Items.STONE, amount); CompoundTag data = new CompoundTag();
		CompoundTag child = new CompoundTag(); child.putString("owner", "unchanged"); data.put("nested", child);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data)); return stack;
	}
	public static void itemRollback() {
		NonNullList<ItemStack> backing = NonNullList.withSize(2, ItemStack.EMPTY); backing.set(0, taggedStone(19));
		ItemStack original = backing.get(0).copy(); ItemStackHandler handler = new ItemStackHandler(backing);
		AtomicInteger changed = new AtomicInteger();
		var storage = NativeTransferAdapters.fabric(ForgeSnapshotAdapters.items(handler, handler, changed::incrementAndGet), TransferResources.ITEMS);
		ItemVariant variant = ItemVariant.of(original);
		try (Transaction outer = Transaction.openOuter()) {
			eq(7, storage.extract(variant, 7, outer));
			try (Transaction nested = outer.openNested()) { eq(8, storage.insert(variant, 8, nested)); nested.commit(); }
			try (Transaction nested = outer.openNested()) { eq(3, storage.extract(variant, 3, nested)); }
			eq(20, handler.getStackInSlot(0).getCount());
		}
		eq(19, backing.get(0).getCount()); yes(ItemStack.isSameItemSameComponents(original, backing.get(0)));
		eq(0, changed.get()); closed();
	}
	public static void sharedJournal() {
		ItemStackHandler handler = new ItemStackHandler(1); handler.setStackInSlot(0, new ItemStack(Items.STONE, 9));
		AtomicInteger changed = new AtomicInteger();
		var first = ForgeSnapshotAdapters.items(handler, handler, changed::incrementAndGet);
		var second = ForgeSnapshotAdapters.items(handler, handler, changed::incrementAndGet);
		ItemResource stone = ItemResource.of(Items.STONE);
		try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
			eq(4, first.insert(0, stone, 4, tx)); eq(2, second.extract(0, stone, 2, tx));
		}
		eq(9, handler.getStackInSlot(0).getCount()); eq(0, changed.get());
		try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
			eq(4, first.insert(0, stone, 4, tx)); eq(2, second.extract(0, stone, 2, tx)); tx.commit();
		}
		eq(11, handler.getStackInSlot(0).getCount()); eq(1, changed.get()); closed();
	}
	public static void itemAliases() {
		NonNullList<ItemStack> shared = NonNullList.withSize(1, ItemStack.EMPTY);
		ItemStackHandler a = new ItemStackHandler(shared), b = new ItemStackHandler(shared);
		var av = ForgeSnapshotAdapters.items(a); var bv = ForgeSnapshotAdapters.items(b); ItemResource stone = ItemResource.of(Items.STONE);
		try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
			eq(10, av.insert(0, stone, 10, tx)); eq(20, bv.insert(0, stone, 20, tx)); eq(30, shared.get(0).getCount());
		}
		eq(0, a.getStackInSlot(0).getCount()); eq(0, b.getStackInSlot(0).getCount());
		ItemStack external = taggedStone(10); ItemStack value = external.copy(); ItemResource tagged = ItemResource.of(external);
		ItemStackHandler first = new ItemStackHandler(1), second = new ItemStackHandler(1);
		first.setStackInSlot(0, external); second.setStackInSlot(0, external);
		var fv = ForgeSnapshotAdapters.items(first); var sv = ForgeSnapshotAdapters.items(second);
		try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) { eq(5, fv.insert(0, tagged, 5, tx)); }
		eq(10, external.getCount()); yes(first.getStackInSlot(0) == external && second.getStackInSlot(0) == external);
		try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
			eq(5, fv.insert(0, tagged, 5, tx));
			try (var child = net.neoforged.neoforge.transfer.transaction.Transaction.open(tx)) { eq(20, sv.insert(0, tagged, 20, child)); child.commit(); }
			eq(35, external.getCount());
			CompoundTag changed = new CompoundTag(); changed.putString("changed", "temporary"); external.set(DataComponents.CUSTOM_DATA, CustomData.of(changed));
		}
		eq(10, external.getCount()); yes(ItemStack.isSameItemSameComponents(value, external));
		yes(first.getStackInSlot(0) == external && second.getStackInSlot(0) == external); closed();
	}
	public static void fluidAliases() {
		FluidStack external = new FluidStack(Fluids.WATER, 10);
		FluidTank a = new FluidTank(100), b = new FluidTank(100); a.setFluid(external); b.setFluid(external);
		var av = ForgeSnapshotAdapters.fluids(a); FluidResource water = FluidResource.of(Fluids.WATER);
		try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) { eq(5, av.insert(0, water, 5, tx)); }
		eq(10, external.getAmount()); yes(a.getFluid() == external && b.getFluid() == external);
		var bv = ForgeSnapshotAdapters.fluids(b);
		try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) { eq(5, av.insert(0, water, 5, tx)); eq(20, bv.insert(0, water, 20, tx)); }
		eq(10, external.getAmount()); yes(a.getFluid() == external && b.getFluid() == external);
		FluidTank emptyA = new FluidTank(100), emptyB = new FluidTank(100);
		var ea = ForgeSnapshotAdapters.fluids(emptyA); var eb = ForgeSnapshotAdapters.fluids(emptyB);
		try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) { eq(4, ea.insert(0, water, 4, tx)); eq(9, eb.insert(0, water, 9, tx)); tx.commit(); }
		eq(4, emptyA.getFluidAmount()); eq(9, emptyB.getFluidAmount()); closed();
	}
	public static void backingReplacement() {
		ItemStackHandler handler = new ItemStackHandler(1); handler.setStackInSlot(0, new ItemStack(Items.STONE, 10));
		AtomicInteger callbacks = new AtomicInteger(); var view = ForgeSnapshotAdapters.items(handler, handler, callbacks::incrementAndGet);
		try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
			eq(5, view.insert(0, ItemResource.of(Items.STONE), 5, tx)); handler.setSize(2);
			boolean rejected = false; try { tx.commit(); } catch (IllegalStateException expected) { rejected = true; }
			yes(rejected); eq(0, callbacks.get());
		}
		eq(1, handler.getSlots()); eq(10, handler.getStackInSlot(0).getCount());
		// A different backing AFTER the transaction is legal; an existing facade must acquire a fresh journal.
		handler.setSize(2);
		try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) { eq(3, view.insert(1, ItemResource.of(Items.STONE), 3, tx)); tx.commit(); }
		eq(2, handler.getSlots()); eq(3, handler.getStackInSlot(1).getCount()); eq(1, callbacks.get()); closed();
	}
	public static void legacyItems() {
		FabricItems fabric = new FabricItems(23);
		IItemHandler facade = ForgeLegacyFacades.items(NativeTransferAdapters.neo(fabric, TransferResources.ITEMS));
		ItemStack input = taggedStone(31);
		for (int i = 0; i < 3; i++) { eq(8, facade.insertItem(0, input, true).getCount()); eq(0, fabric.amount); }
		eq(8, facade.insertItem(0, input, false).getCount()); eq(23, fabric.amount); eq(31, input.getCount());
		ItemStack simulated = facade.extractItem(0, 99, true); eq(23, simulated.getCount()); eq(23, fabric.amount);
		yes(ItemStack.isSameItemSameComponents(input, simulated));
		eq(23, facade.extractItem(0, 99, false).getCount()); eq(0, fabric.amount);
		ItemStacksResourceHandler neo = new ItemStacksResourceHandler(1);
		IItemHandler neoFacade = ForgeLegacyFacades.items(neo);
		eq(0, neoFacade.insertItem(0, input, false).getCount()); eq(31, neo.getAmountAsLong(0));
		eq(31, neoFacade.extractItem(0, 31, false).getCount()); eq(0, neo.getAmountAsLong(0)); closed();
	}
	public static void fluidRollback() {
		FluidTank nativeTank = new FluidTank(1000); nativeTank.fill(new FluidStack(Fluids.WATER, 200), IFluidHandler.FluidAction.EXECUTE);
		AtomicInteger changed = new AtomicInteger();
		var storage = NativeTransferAdapters.fabric(ForgeSnapshotAdapters.fluids(nativeTank, nativeTank, changed::incrementAndGet), TransferResources.FLUIDS);
		FluidVariant water = FluidVariant.of(Fluids.WATER);
		try (Transaction outer = Transaction.openOuter()) {
			eq(8100, storage.insert(water, 8117, outer));
			try (Transaction nested = outer.openNested()) { eq(4050, storage.extract(water, 4100, nested)); nested.commit(); }
			eq(250, nativeTank.getFluidAmount());
		}
		eq(200, nativeTank.getFluidAmount()); eq(0, changed.get());
		FabricFluids source = new FabricFluids(100000); source.variant = water; source.amount = 81017;
		try (Transaction outer = Transaction.openOuter()) {
			long accepted = storage.insert(water, source.amount, outer); eq(64800, accepted);
			eq(accepted, source.extract(water, accepted, outer)); outer.commit();
		}
		eq(97217, source.amount + 81L * nativeTank.getFluidAmount()); eq(1, changed.get()); closed();
	}
	public static void legacyFluids() {
		FabricFluids fabric = new FabricFluids(10000);
		IFluidHandler facade = ForgeLegacyFacades.fluids(NativeTransferAdapters.neo(fabric, TransferResources.FLUIDS));
		FluidStack input = new FluidStack(Fluids.WATER, 200);
		for (int i = 0; i < 3; i++) { eq(123, facade.fill(input, IFluidHandler.FluidAction.SIMULATE)); eq(0, fabric.amount); }
		eq(123, facade.fill(input, IFluidHandler.FluidAction.EXECUTE)); eq(9963, fabric.amount); eq(200, input.getAmount());
		eq(123, facade.drain(200, IFluidHandler.FluidAction.SIMULATE).getAmount()); eq(9963, fabric.amount);
		eq(123, facade.drain(200, IFluidHandler.FluidAction.EXECUTE).getAmount()); eq(0, fabric.amount);
		FluidStacksResourceHandler neo = new FluidStacksResourceHandler(1, 90);
		IFluidHandler neoFacade = ForgeLegacyFacades.fluids(neo);
		eq(90, neoFacade.fill(input, IFluidHandler.FluidAction.EXECUTE));
		eq(90, neoFacade.drain(input, IFluidHandler.FluidAction.EXECUTE).getAmount()); eq(0, neo.getAmountAsLong(0)); closed();
	}
	public static void unknownHandlers() {
		yes(ForgeSnapshotAdapters.items(new ItemStackHandler(1) { }) == null);
		yes(ForgeSnapshotAdapters.fluids(new FluidTank(100) { }) == null);
		AtomicInteger touched = new AtomicInteger();
		IItemHandler proxy = (IItemHandler) java.lang.reflect.Proxy.newProxyInstance(ForgeTransferGameScenarios.class.getClassLoader(),
				new Class<?>[]{IItemHandler.class}, (p, method, arguments) -> { touched.incrementAndGet(); throw new AssertionError("proxy was called"); });
		yes(ForgeSnapshotAdapters.items(proxy) == null); eq(0, touched.get());
		Predicate<FluidStack> unknown = stack -> { touched.incrementAndGet(); return true; };
		FluidTank tank = new FluidTank(100, unknown); yes(ForgeSnapshotAdapters.fluids(tank) == null); eq(0, touched.get());
		Predicate<FluidStack> pure = stack -> stack.getFluid() == Fluids.WATER;
		ForgeSnapshotAdapters.approvePureValidator(pure); tank.setValidator(pure); yes(ForgeSnapshotAdapters.fluids(tank) != null);
		var view = ForgeSnapshotAdapters.fluids(tank); tank.setValidator(unknown);
		try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
			eq(0, view.insert(0, FluidResource.of(Fluids.WATER), 10, tx)); tx.commit();
		}
		eq(0, touched.get()); eq(0, tank.getFluidAmount()); closed();
	}
	public static void metadataDenied() {
		CompoundTag tag = new CompoundTag(); tag.putString("owner", "preserve-me");
		FluidStack legacy = new FluidStack(Fluids.WATER, 17, tag);
		yes(ForgeFluidMetadata.toNeo(legacy) == null); yes(legacy.getTag().equals(tag)); eq(17, legacy.getAmount());
		FluidResource components = FluidResource.of(Fluids.WATER, DataComponentPatch.builder().set(DataComponents.CUSTOM_DATA, CustomData.of(tag)).build());
		yes(ForgeFluidMetadata.toForge(components, 17) == null);
		FluidTank tank = new FluidTank(100); var target = ForgeSnapshotAdapters.fluids(tank);
		try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) { eq(0, target.insert(0, components, 17, tx)); tx.commit(); }
		eq(0, tank.getFluidAmount()); closed();
	}
	public static void metadataCodec() {
		ForgeFluidMetadata.register(Fluids.WATER, new ForgeFluidMetadata.Codec() {
			public DataComponentPatch toComponents(CompoundTag tag) { return DataComponentPatch.builder().set(DataComponents.CUSTOM_DATA, CustomData.of(tag)).build(); }
			public CompoundTag toTag(DataComponentPatch patch) {
				var value = patch.getPatch(DataComponents.CUSTOM_DATA); return value == null || value.isEmpty() ? null : value.get().copyTag();
			}
		});
		CompoundTag tag = new CompoundTag(); CompoundTag nested = new CompoundTag(); nested.putInt("number", 42); tag.put("nested", nested);
		FluidStack legacy = new FluidStack(Fluids.WATER, 37, tag); FluidResource resource = ForgeFluidMetadata.toNeo(legacy);
		yes(resource != null); FluidStack restored = ForgeFluidMetadata.toForge(resource, 37); yes(restored != null && restored.getTag().equals(tag));
		FluidTank tank = new FluidTank(100); tank.setFluid(legacy.copy());
		var target = ForgeSnapshotAdapters.fluids(tank);
		try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) { eq(12, target.extract(0, resource, 12, tx)); }
		eq(37, tank.getFluidAmount()); yes(tank.getFluid().getTag().equals(tag));
		ForgeFluidMetadata.register(Fluids.LAVA, new ForgeFluidMetadata.Codec() {
			public DataComponentPatch toComponents(CompoundTag ignored) { return DataComponentPatch.EMPTY; }
			public CompoundTag toTag(DataComponentPatch ignored) { return new CompoundTag(); }
		});
		yes(ForgeFluidMetadata.toNeo(new FluidStack(Fluids.LAVA, 1, tag)) == null); closed();
	}
	private static final class FabricItems extends SingleVariantStorage<ItemVariant> {
		final long capacity; FabricItems(long capacity) { this.capacity = capacity; }
		protected ItemVariant getBlankVariant() { return ItemVariant.blank(); }
		protected long getCapacity(ItemVariant resource) { return capacity; }
	}
	private static final class FabricFluids extends SingleVariantStorage<FluidVariant> {
		final long capacity; FabricFluids(long capacity) { this.capacity = capacity; }
		protected FluidVariant getBlankVariant() { return FluidVariant.blank(); }
		protected long getCapacity(FluidVariant resource) { return capacity; }
	}
}
