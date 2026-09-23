package net.forbric.kernel.runtime.transfer;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.NonNullList;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import net.forbric.kernel.transform.ForgeTransferShapeAudit;

/**
 * Reversible access to explicitly audited Forge implementations. One object-graph journal per native transaction
 * thread preserves aliases, including two handlers sharing a NonNullList, different lists sharing an ItemStack,
 * and tanks/external callers sharing a FluidStack. Restoring only container values would leave those aliases
 * mutated. Every live nested snapshot learns newly encountered objects, retaining its earlier copy of shared ones.
 * Subclasses, proxies, unapproved validators and unapproved transformed standard classes receive no write facade.
 */
public final class ForgeSnapshotAdapters {
	private ForgeSnapshotAdapters() { }
	private static final Field STACKS = field(ItemStackHandler.class, "stacks");
	private static final Field COMPONENTS = field(ItemStack.class, "components");
	private static final Field COUNT = field(ItemStack.class, "count");
	private static final Field POP_TIME = field(ItemStack.class, "popTime");
	private static final Field VALIDATOR = field(FluidTank.class, "validator");
	private static final Set<Predicate<FluidStack>> PURE_VALIDATORS = Collections.newSetFromMap(new IdentityHashMap<>());
	private static final ThreadLocal<ForgeJournal> CURRENT = new ThreadLocal<>();
	private static volatile boolean itemHelpersReady, fluidHelpersReady;
	private static boolean defaultValidatorKnown;
	private static final ClassValue<Boolean> CERTIFIED = new ClassValue<>() {
		protected Boolean computeValue(Class<?> type) {
			try { type.getDeclaredMethod(ForgeTransferShapeAudit.MARKER); return true; }
			catch (NoSuchMethodException | LinkageError unverified) { return false; }
		}
	};
	private static final ClassValue<Boolean> PURE_ITEM = new ClassValue<>() {
		protected Boolean computeValue(Class<?> type) {
			try {
				return type.getMethod("getMaxStackSize", ItemStack.class).getDeclaringClass().getName().equals("net.neoforged.neoforge.common.extensions.IItemExtension")
						&& type.getMethod("components").getDeclaringClass() == Item.class
						&& type.getMethod("builtInRegistryHolder").getDeclaringClass() == Item.class
						&& type.getMethod("computeDefaultResource", java.util.function.Function.class).getDeclaringClass() == Item.class;
			} catch (ReflectiveOperationException | LinkageError unknown) { return false; }
		}
	};

	private static Field field(Class<?> type, String name) {
		try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
		catch (ReflectiveOperationException drift) { throw new ExceptionInInitializerError(drift); }
	}
	@SuppressWarnings("unchecked") private static NonNullList<ItemStack> backing(ItemStackHandler handler) {
		try { return (NonNullList<ItemStack>) STACKS.get(handler); }
		catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
	}
	@SuppressWarnings("unchecked") private static Predicate<FluidStack> validator(FluidTank tank) {
		try { return (Predicate<FluidStack>) VALIDATOR.get(tank); }
		catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
	}
	/** Register only an explicitly audited, side-effect-free predicate INSTANCE; never a name-based guess. */
	public static synchronized void approvePureValidator(Predicate<FluidStack> validator) { PURE_VALIDATORS.add(java.util.Objects.requireNonNull(validator)); }
	public static boolean supportsItems(IItemHandler handler) {
		return handler != null && handler.getClass() == ItemStackHandler.class
				&& backing((ItemStackHandler) handler).getClass() == NonNullList.class && audited(ItemStackHandler.class) && itemHelpers();
	}
	public static synchronized boolean supportsFluids(IFluidHandler handler) {
		if (handler == null || handler.getClass() != FluidTank.class || !audited(FluidTank.class) || !fluidHelpers()) return false;
		// Do not run even a probe constructor until its final definition and helpers have been certified.
		if (!defaultValidatorKnown) { PURE_VALIDATORS.add(validator(new FluidTank(0))); defaultValidatorKnown = true; }
		return PURE_VALIDATORS.contains(validator((FluidTank) handler));
	}
	private static boolean audited(Class<?> type) {
		boolean approved = CERTIFIED.get(type);
		if (!approved) TransferIssues.reportType("TRANSFER_HELPER_UNVERIFIED", type.getName(), ForgeTransferShapeAudit.declined(type.getName()));
		return approved;
	}
	private static boolean helpers(List<String> names) {
		for (String name : names) try {
			if (!audited(Class.forName(name, false, ForgeSnapshotAdapters.class.getClassLoader()))) return false;
		} catch (ClassNotFoundException | LinkageError absent) {
			TransferIssues.reportType("TRANSFER_HELPER_UNVERIFIED", name, "required transfer helper is absent"); return false;
		}
		return true;
	}
	private static boolean itemHelpers() {
		if (itemHelpersReady) return true;
		if (!helpers(ForgeTransferShapeAudit.ITEM_HELPERS) || !safeAddedInterfaces(ItemStack.class) || !safeAddedInterfaces(Item.class)) return false;
		itemHelpersReady = true; return true;
	}
	private static boolean fluidHelpers() {
		if (fluidHelpersReady) return true;
		if (!helpers(ForgeTransferShapeAudit.FLUID_HELPERS)) return false;
		fluidHelpersReady = true; return true;
	}
	private static boolean safeAddedInterfaces(Class<?> owner) {
		Set<String> critical = Set.of("get", "getOrDefault", "getComponents", "getComponentsPatch", "components", "builtInRegistryHolder", "computeDefaultResource", "getDefaultMaxStackSize", "getMaxStackSize", "getCount", "setCount", "grow", "shrink", "copy", "copyWithCount", "isEmpty", "typeHolder", "getItem", "gatherCapabilities");
		for (Class<?> contract : owner.getInterfaces()) {
			if (!ForgeTransferShapeAudit.NON_TRANSFER_FABRIC_INTERFACES.contains(contract.getName().replace('.', '/'))) continue;
			boolean safe = contract.getInterfaces().length == 0;
			for (var method : contract.getDeclaredMethods()) if (!java.lang.reflect.Modifier.isStatic(method.getModifiers()) && critical.contains(method.getName())) safe = false;
			if (!safe) { TransferIssues.reportType("TRANSFER_HELPER_UNVERIFIED", contract.getName(), "an added interface can override a transfer-critical helper"); return false; }
		}
		return true;
	}
	private static boolean pureItem(Item item) {
		boolean pure = PURE_ITEM.get(item.getClass());
		if (!pure) TransferIssues.report("ITEM_TRANSFER_HELPER_UNVERIFIED", item, "custom capacity/component helper needs an explicit reversible adapter");
		return pure;
	}
	private static void refused(Object handler, String kind) {
		TransferIssues.report("FORGE_HANDLER_NOT_ROLLBACK_SAFE", handler,
				"The " + kind + " provider is not an audited reversible implementation; transactional insertion and extraction were not exposed");
	}
	public static ResourceHandler<ItemResource> items(IItemHandler handler) { return items(handler, handler, () -> { }); }
	public static ResourceHandler<ItemResource> items(IItemHandler handler, Object owner, Runnable changed) {
		java.util.Objects.requireNonNull(changed);
		var unwrapped = ForgeLegacyFacades.unwrapItems(handler); if (unwrapped != null) return unwrapped;
		if (!supportsItems(handler)) { if (handler != null) refused(handler, "item"); return null; }
		return new ItemView((ItemStackHandler) handler, owner, changed);
	}
	public static ResourceHandler<FluidResource> fluids(IFluidHandler handler) { return fluids(handler, handler, () -> { }); }
	public static ResourceHandler<FluidResource> fluids(IFluidHandler handler, Object owner, Runnable changed) {
		java.util.Objects.requireNonNull(changed);
		var unwrapped = ForgeLegacyFacades.unwrapFluids(handler); if (unwrapped != null) return unwrapped;
		if (!supportsFluids(handler)) { if (handler != null) refused(handler, "fluid"); return null; }
		return new FluidView((FluidTank) handler, owner, changed);
	}
	private static ForgeJournal journal() {
		ForgeJournal journal = CURRENT.get();
		if (journal == null || !journal.isInTransaction()) { journal = new ForgeJournal(); CURRENT.set(journal); }
		return journal;
	}
	private record TankBinding(FluidStack fluid, int capacity, Predicate<FluidStack> validator) {
		static TankBinding of(FluidTank tank) { return new TankBinding(tank.getFluid(), tank.getCapacity(), ForgeSnapshotAdapters.validator(tank)); }
		boolean matches(FluidTank tank) { return tank.getFluid() == fluid && tank.getCapacity() == capacity && ForgeSnapshotAdapters.validator(tank) == validator; }
	}
	private record FluidValue(int amount, net.minecraft.nbt.CompoundTag tag) {
		static FluidValue of(FluidStack stack) { return new FluidValue(stack.getAmount(), stack.hasTag() ? stack.getTag().copy() : null); }
		void restore(FluidStack stack) { stack.setTag(tag == null ? null : tag.copy()); stack.setAmount(amount); }
	}
	private record ItemValue(int count, int popTime, PatchedDataComponentMap components) {
		static ItemValue of(ItemStack stack) {
			try { return new ItemValue(COUNT.getInt(stack), POP_TIME.getInt(stack), ((PatchedDataComponentMap) COMPONENTS.get(stack)).copy()); }
			catch (IllegalAccessException impossible) { throw new IllegalStateException(impossible); }
		}
		void restore(ItemStack stack) throws IllegalAccessException {
			COUNT.setInt(stack, count); POP_TIME.setInt(stack, popTime);
			((PatchedDataComponentMap) COMPONENTS.get(stack)).restorePatch(components.asPatch());
		}
	}
	private static final class GraphSnapshot {
		final IdentityHashMap<ItemStackHandler, NonNullList<ItemStack>> bindings = new IdentityHashMap<>();
		final IdentityHashMap<NonNullList<ItemStack>, List<ItemStack>> slots = new IdentityHashMap<>();
		final IdentityHashMap<ItemStack, ItemValue> items = new IdentityHashMap<>();
		final IdentityHashMap<FluidTank, TankBinding> tanks = new IdentityHashMap<>();
		final IdentityHashMap<FluidStack, FluidValue> fluids = new IdentityHashMap<>();
		final IdentityHashMap<Object, Runnable> notifications;
		GraphSnapshot(IdentityHashMap<Object, Runnable> notifications) { this.notifications = new IdentityHashMap<>(notifications); }
		void include(ItemStackHandler handler) {
			NonNullList<ItemStack> list = backing(handler); bindings.putIfAbsent(handler, list);
			if (slots.containsKey(list)) return;
			List<ItemStack> originals = List.copyOf(list); slots.put(list, originals);
			for (ItemStack original : originals) if (original != ItemStack.EMPTY) items.computeIfAbsent(original, ItemValue::of);
		}
		void include(FluidTank tank) {
			tanks.putIfAbsent(tank, TankBinding.of(tank)); FluidStack original = tank.getFluid();
			if (original != FluidStack.EMPTY) fluids.computeIfAbsent(original, FluidValue::of);
		}
		void restore() {
			try {
				// Restore the actual mutable stack objects before reconnecting their containers. External aliases
				// and OTHER containers then see rollback too, instead of retaining the uncommitted count/tag.
				for (var entry : items.entrySet()) entry.getValue().restore(entry.getKey());
				for (var entry : fluids.entrySet()) entry.getValue().restore(entry.getKey());
				for (var entry : slots.entrySet()) {
					NonNullList<ItemStack> list = entry.getKey(); List<ItemStack> originals = entry.getValue();
					while (list.size() > originals.size()) list.remove(list.size() - 1);
					while (list.size() < originals.size()) list.add(ItemStack.EMPTY);
					for (int i = 0; i < originals.size(); i++) list.set(i, originals.get(i));
				}
				for (var entry : bindings.entrySet()) STACKS.set(entry.getKey(), entry.getValue());
				for (var entry : tanks.entrySet()) {
					FluidTank tank = entry.getKey(); TankBinding state = entry.getValue();
					tank.setFluid(state.fluid()); tank.setCapacity(state.capacity()); tank.setValidator(state.validator());
				}
			} catch (IllegalAccessException impossible) { throw new IllegalStateException(impossible); }
		}
	}
	private static final class ForgeJournal extends SnapshotJournal<GraphSnapshot> {
		final IdentityHashMap<ItemStackHandler, NonNullList<ItemStack>> items = new IdentityHashMap<>();
		final IdentityHashMap<NonNullList<ItemStack>, Integer> sizes = new IdentityHashMap<>();
		final IdentityHashMap<FluidTank, TankBinding> tanks = new IdentityHashMap<>();
		final List<GraphSnapshot> snapshots = new ArrayList<>();
		IdentityHashMap<Object, Runnable> notifications = new IdentityHashMap<>();
		void prepare(ItemStackHandler handler, TransactionContext context) {
			validate(); PairedTransactions.addValidation(context, this, this::validate);
			for (GraphSnapshot snapshot : snapshots) snapshot.include(handler);
			var list = backing(handler); items.putIfAbsent(handler, list); sizes.putIfAbsent(list, list.size());
			updateSnapshots(context);
		}
		void prepare(FluidTank tank, TransactionContext context) {
			validate(); PairedTransactions.addValidation(context, this, this::validate);
			for (GraphSnapshot snapshot : snapshots) snapshot.include(tank);
			tanks.putIfAbsent(tank, TankBinding.of(tank)); updateSnapshots(context);
		}
		void nativeFluidOperationFinished(FluidTank tank) { tanks.put(tank, TankBinding.of(tank)); }
		void validate() {
			for (var entry : items.entrySet()) if (backing(entry.getKey()) != entry.getValue())
				throw new IllegalStateException("Forge inventory backing changed during a transaction; abort before using its replacement");
			for (var entry : sizes.entrySet()) if (entry.getKey().size() != entry.getValue())
				throw new IllegalStateException("Forge inventory size changed outside the transaction");
			for (var entry : tanks.entrySet()) if (!entry.getValue().matches(entry.getKey()))
				throw new IllegalStateException("Forge tank binding/capacity/validator changed outside the transaction");
		}
		protected GraphSnapshot createSnapshot() {
			GraphSnapshot snapshot = new GraphSnapshot(notifications);
			for (var handler : items.keySet()) snapshot.include(handler);
			for (var tank : tanks.keySet()) snapshot.include(tank);
			snapshots.add(snapshot); return snapshot;
		}
		protected void revertToSnapshot(GraphSnapshot snapshot) {
			snapshot.restore(); notifications = new IdentityHashMap<>(snapshot.notifications);
			for (var handler : items.keySet()) items.put(handler, backing(handler));
			sizes.clear(); for (var list : items.values()) sizes.put(list, list.size());
			for (var tank : tanks.keySet()) tanks.put(tank, TankBinding.of(tank));
		}
		protected void releaseSnapshot(GraphSnapshot snapshot) {
			snapshots.remove(snapshot); if (snapshots.isEmpty()) clear();
		}
		protected void onRootCommit(GraphSnapshot original) {
			List<Runnable> callbacks = new ArrayList<>(notifications.values()); clear();
			RuntimeException failure = null;
			for (Runnable callback : callbacks) try { callback.run(); }
			catch (RuntimeException thrown) { if (failure == null) failure = thrown; else failure.addSuppressed(thrown); }
			if (failure != null) throw failure;
		}
		void clear() {
			items.clear(); sizes.clear(); tanks.clear(); snapshots.clear(); notifications.clear();
			PairedTransactions.removeValidation(this);
			if (CURRENT.get() == this) CURRENT.remove();
		}
	}
	private record ItemView(ItemStackHandler handler, Object owner, Runnable changed) implements ResourceHandler<ItemResource> {
		public int size() { return handler.getSlots(); }
		public ItemResource getResource(int slot) {
			ItemStack stack = handler.getStackInSlot(slot);
			return stack.isEmpty() || !pureItem(stack.getItem()) ? ItemResource.EMPTY : ItemResource.of(stack);
		}
		public long getAmountAsLong(int slot) { return getResource(slot).isEmpty() ? 0 : handler.getStackInSlot(slot).getCount(); }
		public long getCapacityAsLong(int slot, ItemResource resource) {
			return !resource.isEmpty() && !pureItem(resource.getItem()) ? 0 : Math.min(handler.getSlotLimit(slot), resource.isEmpty() ? Integer.MAX_VALUE : resource.getMaxStackSize());
		}
		public boolean isValid(int slot, ItemResource resource) { return !resource.isEmpty() && pureItem(resource.getItem()) && handler.isItemValid(slot, resource.toStack()); }
		public int insert(int slot, ItemResource resource, int maximum, TransactionContext tx) {
			if (maximum < 0) throw new IllegalArgumentException("Negative item amount");
			if (maximum == 0 || resource.isEmpty()) return 0;
			if (!pureItem(resource.getItem())) return 0;
			if (!supportsItems(handler)) { refused(handler, "item"); return 0; }
			ForgeJournal journal = journal(); journal.prepare(handler, tx);
			int moved = maximum - handler.insertItem(slot, resource.toStack(maximum), false).getCount(); checkAmount(moved, maximum);
			if (moved > 0) journal.notifications.put(owner, changed); return moved;
		}
		public int extract(int slot, ItemResource resource, int maximum, TransactionContext tx) {
			if (maximum < 0) throw new IllegalArgumentException("Negative item amount");
			if (maximum == 0 || resource.isEmpty() || !resource.matches(handler.getStackInSlot(slot))) return 0;
			if (!pureItem(resource.getItem())) return 0;
			if (!supportsItems(handler)) { refused(handler, "item"); return 0; }
			ForgeJournal journal = journal(); journal.prepare(handler, tx);
			int moved = handler.extractItem(slot, maximum, false).getCount(); checkAmount(moved, maximum);
			if (moved > 0) journal.notifications.put(owner, changed); return moved;
		}
	}
	private record FluidView(FluidTank handler, Object owner, Runnable changed) implements ResourceHandler<FluidResource> {
		public int size() { return 1; }
		private boolean writable() { boolean supported = supportsFluids(handler); if (!supported) refused(handler, "fluid"); return supported; }
		public FluidResource getResource(int slot) { java.util.Objects.checkIndex(slot, 1); var resource = ForgeFluidMetadata.toNeo(handler.getFluid()); return resource == null ? FluidResource.EMPTY : resource; }
		public long getAmountAsLong(int slot) { return getResource(slot).isEmpty() ? 0 : handler.getFluidAmount(); }
		public long getCapacityAsLong(int slot, FluidResource resource) { java.util.Objects.checkIndex(slot, 1); return writable() && ForgeFluidMetadata.toForge(resource, 1) != null ? handler.getCapacity() : 0; }
		public boolean isValid(int slot, FluidResource resource) {
			java.util.Objects.checkIndex(slot, 1); FluidStack stack = ForgeFluidMetadata.toForge(resource, 1);
			return writable() && stack != null && !stack.isEmpty() && handler.isFluidValid(stack);
		}
		/**
		 * The Forge request for a resource the tank already holds. An empty-but-present tag ({}) is plain fluid to the
		 * other APIs, yet FluidTank's fill and drain compare tags with null != {}: a request rebuilt from the plain
		 * resource never matched such a tank, and every bridged fill and drain moved 0 for as long as it held that
		 * stack. A copy of the tank's own stack carries its exact tag.
		 */
		private FluidStack request(FluidResource resource, int maximum) {
			FluidStack held = handler.getFluid();
			if (!held.isEmpty() && held.hasTag() && held.getTag().isEmpty() && resource.isComponentsPatchEmpty()
					&& held.getFluid() == resource.getFluid()) return new FluidStack(held, maximum);
			return ForgeFluidMetadata.toForge(resource, maximum);
		}
		public int insert(int slot, FluidResource resource, int maximum, TransactionContext tx) {
			java.util.Objects.checkIndex(slot, 1); if (maximum < 0) throw new IllegalArgumentException("Negative fluid amount");
			if (maximum == 0 || resource.isEmpty() || !writable()) return 0;
			FluidStack stack = request(resource, maximum); if (stack == null) return 0;
			ForgeJournal journal = journal(); journal.prepare(handler, tx);
			int moved = handler.fill(stack, IFluidHandler.FluidAction.EXECUTE); checkAmount(moved, maximum);
			journal.nativeFluidOperationFinished(handler);
			if (moved > 0) journal.notifications.put(owner, changed); return moved;
		}
		public int extract(int slot, FluidResource resource, int maximum, TransactionContext tx) {
			java.util.Objects.checkIndex(slot, 1); if (maximum < 0) throw new IllegalArgumentException("Negative fluid amount");
			if (maximum == 0 || resource.isEmpty() || !writable()) return 0;
			FluidStack stack = request(resource, maximum); if (stack == null) return 0;
			ForgeJournal journal = journal(); journal.prepare(handler, tx);
			int moved = handler.drain(stack, IFluidHandler.FluidAction.EXECUTE).getAmount(); checkAmount(moved, maximum);
			journal.nativeFluidOperationFinished(handler);
			if (moved > 0) journal.notifications.put(owner, changed); return moved;
		}
	}
	static void checkAmount(int moved, int maximum) {
		if (moved < 0 || moved > maximum) throw new IllegalStateException("Provider returned invalid amount " + moved + "/" + maximum);
	}
}
