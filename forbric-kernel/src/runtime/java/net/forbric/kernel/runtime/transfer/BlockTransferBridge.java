package net.forbric.kernel.runtime.transfer;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import net.fabricmc.fabric.api.lookup.v1.block.BlockApiLookup;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SidedStorageBlockEntity;
import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;
import net.forbric.kernel.runtime.transfer.TransferPrecedence.Answer;
import net.forbric.kernel.runtime.transfer.TransferPrecedence.ForgeAnswer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.ICapabilityInvalidationListener;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.VanillaContainerWrapper;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.InvWrapper;

/**
 * Fallbacks for loaded server block entities only. Native providers always get the first answer. Face (including
 * null) is passed unchanged; lookup recursion is rejected by endpoint identity. Dynamic foreign providers are
 * resolved for every operation, so a caller caching our wrapper cannot pin an obsolete capability instance.
 * Among foreign providers the block entity's OWNER answers first, and a generic wrapper of another ecosystem never
 * speaks for it; see TransferPrecedence.
 */
public final class BlockTransferBridge {
	private BlockTransferBridge() { }
	private static final AtomicBoolean INSTALLED = new AtomicBoolean();
	private static volatile boolean enabled;
	private static volatile boolean forgeEnabled;
	private static final Map<BlockEntity, List<WeakReference<Endpoint>>> ENDPOINTS = new WeakHashMap<>();
	/** The class that answers Forge's getCapability for this block entity class; null if none can be read. */
	private static final ClassValue<Class<?>> FORGE_QUERY_OWNER = new ClassValue<>() {
		protected Class<?> computeValue(Class<?> type) {
			try { return type.getMethod("getCapability", Capability.class, Direction.class).getDeclaringClass(); }
			catch (NoSuchMethodException absent) { return null; }
		}
	};
	private static final ClassValue<Boolean> VANILLA_WRITES = new ClassValue<>() {
		protected Boolean computeValue(Class<?> type) {
			if (!BaseContainerBlockEntity.class.isAssignableFrom(type)) return false;
			for (Class<?> at = type; at != BaseContainerBlockEntity.class && at != RandomizableContainerBlockEntity.class; at = at.getSuperclass()) {
				for (Method method : at.getDeclaredMethods()) if (method.getName().equals("setItem") || method.getName().equals("onTransfer")) return false;
			}
			return true;
		}
	};
	// Ownership is the mod that registered the block entity TYPE, not the jar its class came from: a mod may reuse
	// another jar's class (the M33 fixture ships every machine class in its Fabric jar). Cached once the registry
	// names the type. Vanilla and unknown namespaces have no owner and keep the previous order.
	private static final Map<BlockEntityType<?>, Optional<Ecosystem>> OWNERS = new ConcurrentHashMap<>();
	private record Query(Level level, BlockPos pos, Direction face, boolean fluid) { }
	private static final ThreadLocal<Set<Query>> LOOKUPS = ThreadLocal.withInitial(HashSet::new);

	/** After the mod registration window; only invoke when the selected Fabric transfer and NeoForge APIs exist. */
	public static void install() {
		if ("off".equalsIgnoreCase(System.getProperty("forbric.transferBridge", "on"))) return;
		PairedTransactions.checkHooks();
		try { BlockCapability.class.getDeclaredMethod("forbric$transferFallback"); }
		catch (NoSuchMethodException drift) { throw new IllegalStateException("NeoForge transfer capability fallback hook is missing", drift); }
		if (!INSTALLED.compareAndSet(false, true)) return;
		ItemStorage.SIDED.registerFallback(BlockTransferBridge::itemsAfterGeneric);
		FluidStorage.SIDED.registerFallback(BlockTransferBridge::fluidsAfterGeneric);
		ahead(ItemStorage.SIDED, BlockTransferBridge::itemsBeforeGeneric);
		ahead(FluidStorage.SIDED, BlockTransferBridge::fluidsBeforeGeneric);
		try { BlockEntity.class.getDeclaredMethod("forbric$forgeTransferFallback"); forgeEnabled = true; }
		catch (NoSuchMethodException absent) { TransferIssues.report("FORGE_QUERY_HOOK_MISSING", null,
				"Forge block transfer fallback is not installed; Fabric/NeoForge transfers remain available"); }
		// A failure registering either callback leaves both directions dormant, even if one callback was added.
		enabled = true;
	}

	/**
	 * Fabric has no public way to run a fallback before its own, and its first two answer for every
	 * SidedStorageBlockEntity and every Container. Its lookup exposes the live list; if that ever changes, the
	 * provider is appended instead and a Fabric consumer sees Fabric's generic view first, as it did before.
	 * BlockTransferBridgeTest pins both halves: this behaviour, and the list the real Fabric lookup hands out.
	 */
	@SuppressWarnings("unchecked")
	static <A> void ahead(BlockApiLookup<A, Direction> lookup, BlockApiLookup.BlockApiProvider<A, Direction> provider) {
		try {
			((List<BlockApiLookup.BlockApiProvider<A, Direction>>) lookup.getClass().getMethod("getFallbackProviders").invoke(lookup)).add(0, provider);
		} catch (ReflectiveOperationException | RuntimeException drift) {
			lookup.registerFallback(provider);
			TransferIssues.report("FABRIC_FALLBACK_ORDER", lookup, "Fabric's generic fallbacks answer before a Forge or NeoForge owner's provider");
		}
	}
	private static Storage<ItemVariant> itemsBeforeGeneric(Level level, BlockPos pos, BlockState state, BlockEntity entity, Direction face) {
		return entity != null && TransferPrecedence.fabricAsksBeforeGeneric(owner(entity)) ? fabricItems(level, pos, entity, face) : null;
	}
	private static Storage<ItemVariant> itemsAfterGeneric(Level level, BlockPos pos, BlockState state, BlockEntity entity, Direction face) {
		return entity != null && !TransferPrecedence.fabricAsksBeforeGeneric(owner(entity)) ? fabricItems(level, pos, entity, face) : null;
	}
	private static Storage<FluidVariant> fluidsBeforeGeneric(Level level, BlockPos pos, BlockState state, BlockEntity entity, Direction face) {
		return entity != null && TransferPrecedence.fabricAsksBeforeGeneric(owner(entity)) ? fabricFluids(level, pos, entity, face) : null;
	}
	private static Storage<FluidVariant> fluidsAfterGeneric(Level level, BlockPos pos, BlockState state, BlockEntity entity, Direction face) {
		return entity != null && !TransferPrecedence.fabricAsksBeforeGeneric(owner(entity)) ? fabricFluids(level, pos, entity, face) : null;
	}
	private static Storage<ItemVariant> fabricItems(Level level, BlockPos pos, BlockEntity entity, Direction face) {
		Endpoint endpoint = endpoint(level, pos, entity, face, false);
		Answer answer = endpoint == null ? null : TransferPrecedence.answer(Ecosystem.FABRIC, endpoint);
		return answer == null ? null : NativeTransferAdapters.fabric(itemView(endpoint, answer), TransferResources.ITEMS);
	}
	private static Storage<FluidVariant> fabricFluids(Level level, BlockPos pos, BlockEntity entity, Direction face) {
		Endpoint endpoint = endpoint(level, pos, entity, face, true);
		Answer answer = endpoint == null ? null : TransferPrecedence.answer(Ecosystem.FABRIC, endpoint);
		return answer == null ? null : NativeTransferAdapters.fabric(fluidView(endpoint, answer), TransferResources.FLUIDS);
	}
	/** A NeoForge-typed live view of whichever source answered; every operation resolves that source again. */
	private static ResourceHandler<ItemResource> itemView(Endpoint endpoint, Answer answer) {
		return switch (answer) {
			case NEOFORGE -> LiveTransferEndpoints.neo(endpoint::neoItems, endpoint::valid, endpoint::generation, ItemResource.EMPTY);
			case FORGE -> LiveTransferEndpoints.neo(endpoint::forgeItems, endpoint::valid, endpoint::generation, ItemResource.EMPTY);
			case NEOFORGE_CONTAINER -> LiveTransferEndpoints.neo(endpoint::containerItems, endpoint::valid, endpoint::generation, ItemResource.EMPTY);
			case FABRIC, FABRIC_EXPLICIT -> {
				boolean generic = answer == Answer.FABRIC;
				yield NativeTransferAdapters.neo(LiveTransferEndpoints.fabric(() -> endpoint.fabricItems(generic), endpoint::valid, endpoint::generation, ItemVariant.blank()), TransferResources.ITEMS);
			}
		};
	}
	private static ResourceHandler<FluidResource> fluidView(Endpoint endpoint, Answer answer) {
		return switch (answer) {
			case NEOFORGE -> LiveTransferEndpoints.neo(endpoint::neoFluids, endpoint::valid, endpoint::generation, FluidResource.EMPTY);
			case FORGE -> LiveTransferEndpoints.neo(endpoint::forgeFluids, endpoint::valid, endpoint::generation, FluidResource.EMPTY);
			case NEOFORGE_CONTAINER -> throw new IllegalStateException("A Container wrapper holds no fluids");
			case FABRIC, FABRIC_EXPLICIT -> {
				boolean generic = answer == Answer.FABRIC;
				yield NativeTransferAdapters.neo(LiveTransferEndpoints.fabric(() -> endpoint.fabricFluids(generic), endpoint::valid, endpoint::generation, FluidVariant.blank()), TransferResources.FLUIDS);
			}
		};
	}

	/** The single null-result seam in BlockCapability.getCapability, after all native providers declined. */
	public static Object neoFallback(Object capability, Object rawLevel, Object rawPos, Object rawState, Object rawEntity, Object context) {
		if (!enabled || !(rawLevel instanceof Level level) || !(rawPos instanceof BlockPos pos)
				|| !(rawEntity instanceof BlockEntity entity) || (context != null && !(context instanceof Direction))) return null;
		boolean fluid;
		if (capability == Capabilities.Item.BLOCK) fluid = false;
		else if (capability == Capabilities.Fluid.BLOCK) fluid = true;
		else return null;
		Endpoint endpoint = endpoint(level, pos, entity, (Direction) context, fluid);
		// A Forge or NeoForge owner: its Forge capability (audited, or refused) first, and only Fabric's explicit
		// providers after it. Fabric's generic Container wrapper would expose every slot on every face as a write
		// bridge whose rollback runs the mod's own setItem.
		Answer answer = endpoint == null ? null : TransferPrecedence.answer(Ecosystem.NEOFORGE, endpoint);
		if (answer == null) return null;
		return fluid ? fluidView(endpoint, answer) : itemView(endpoint, answer);
	}

	/**
	 * After the composed Forge provider declined. Never let a super-call preempt a subclass's native answer. The one
	 * game class whose override is let through is BaseContainerBlockEntity, and only for a Fabric or NeoForge owner:
	 * the override answers ITEM_HANDLER itself (see forgeOwnerFirst) and passes everything else, fluids included,
	 * straight to this super-call, so such a block entity's fluids were never reachable from Forge at all.
	 */
	public static Object forgeFallback(Object existing, Object rawEntity, Object capability, Object context) {
		if (!enabled || !forgeEnabled || !(existing instanceof LazyOptional<?> result) || result.isPresent()
				|| !(rawEntity instanceof BlockEntity entity) || (context != null && !(context instanceof Direction))) return existing;
		Class<?> query = FORGE_QUERY_OWNER.get(entity.getClass());
		if (query != BlockEntity.class && !(query == BaseContainerBlockEntity.class && foreignToForge(owner(entity)))) return existing;
		boolean fluid;
		if (capability == ForgeCapabilities.ITEM_HANDLER) fluid = false;
		else if (capability == ForgeCapabilities.FLUID_HANDLER) fluid = true;
		else return existing;
		Endpoint endpoint = endpoint(entity.getLevel(), entity.getBlockPos(), entity, (Direction) context, fluid);
		if (endpoint == null) return existing;
		LazyOptional<?> bridged = forgeView(endpoint, false);
		return bridged == null ? existing : bridged;
	}
	/**
	 * BaseContainerBlockEntity answers ITEM_HANDLER with Forge's generic InvWrapper over the whole Container before
	 * any provider is asked. For a block entity a Fabric or NeoForge mod owns (and that does not override the
	 * query itself), the owner's real item capability answers first; the generic wrapper remains the answer when
	 * the owner has none. Fabric's own generic Container view is not the owner's capability: a Fabric mod's plain
	 * barrel keeps Forge's InvWrapper, as it does under Forge.
	 */
	public static Object forgeOwnerFirst(Object generic, Object rawEntity, Object capability, Object context) {
		if (!enabled || !forgeEnabled || capability != ForgeCapabilities.ITEM_HANDLER || !(generic instanceof LazyOptional<?> result)
				|| !(rawEntity instanceof BlockEntity entity) || (context != null && !(context instanceof Direction))
				|| FORGE_QUERY_OWNER.get(entity.getClass()) != BaseContainerBlockEntity.class || !foreignToForge(owner(entity))
				|| !result.isPresent()) return generic;
		Endpoint endpoint = endpoint(entity.getLevel(), entity.getBlockPos(), entity, (Direction) context, false);
		if (endpoint == null) return generic;
		LazyOptional<?> owned = forgeView(endpoint, true);
		return owned == null ? generic : owned;
	}
	private static boolean foreignToForge(Ecosystem owner) { return owner == Ecosystem.FABRIC || owner == Ecosystem.NEOFORGE; }
	/**
	 * Forge's own generic view of a whole Container: exactly InvWrapper, what BaseContainerBlockEntity hands out when
	 * a mod leaves the query alone. A subclass may add its own rules, so it stays a handler for the audit to judge.
	 */
	static boolean wholeContainer(Object handler) { return handler != null && handler.getClass() == InvWrapper.class; }
	/**
	 * Whether NeoForge's own VanillaContainerWrapper writes this Container exactly as the game would. That wrapper
	 * writes, and on abort restores, through setItem(slot, stack, true), which BaseContainerBlockEntity implements
	 * WITHOUT calling the two-argument setItem a mod overrides. So every class below the vanilla base must leave
	 * both setItem forms and onTransfer alone. A mod that re-declares any of them has writes of its own (a recipe
	 * check, a progress reset, a craft on insert) that the wrapper would skip or replay on every simulate, and the
	 * Container is not offered to NeoForge consumers at all.
	 */
	static boolean vanillaWrites(Class<?> type) { return VANILLA_WRITES.get(type); }
	private static LazyOptional<?> forgeView(Endpoint endpoint, boolean replacingGenericView) {
		Answer answer = TransferPrecedence.answer(Ecosystem.FORGE, endpoint, replacingGenericView);
		if (answer == null) return null;
		if (endpoint.fluid) { var found = fluidView(endpoint, answer); return endpoint.track(LazyOptional.of(() -> ForgeLegacyFacades.fluids(found))); }
		var found = itemView(endpoint, answer); return endpoint.track(LazyOptional.of(() -> ForgeLegacyFacades.items(found)));
	}
	/** The existing composition calls this after native invalidateCaps; it does not replace that provider. */
	public static void forgeInvalidated(Object rawEntity) {
		if (!(rawEntity instanceof BlockEntity entity)) return;
		synchronized (ENDPOINTS) {
			List<WeakReference<Endpoint>> endpoints = ENDPOINTS.get(entity); if (endpoints == null) return;
			endpoints.removeIf(reference -> reference.get() == null);
			for (var reference : List.copyOf(endpoints)) { Endpoint endpoint = reference.get(); if (endpoint != null) endpoint.invalidate(); }
		}
	}

	private static Endpoint endpoint(Level level, BlockPos pos, BlockEntity entity, Direction face, boolean fluid) {
		if (!enabled || !(level instanceof ServerLevel server) || !server.getServer().isSameThread() || entity == null || !server.hasChunkAt(pos)
				|| entity.isRemoved() || server.getBlockEntity(pos) != entity) return null;
		return new Endpoint(server, pos.immutable(), entity, face, fluid, owner(entity));
	}
	static Ecosystem owner(BlockEntity entity) {
		BlockEntityType<?> type = entity.getType();
		Optional<Ecosystem> known = OWNERS.get(type);
		if (known == null) {
			Identifier key = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
			if (key == null) return null; // not registered (yet): decide again next time rather than caching a guess
			known = Optional.ofNullable(TransferPrecedence.ownerOf(key.getNamespace(), ModCatalog.everything()));
			OWNERS.put(type, known);
		}
		return known.orElse(null);
	}
	private static final class Endpoint implements TransferPrecedence.Site {
		final WeakReference<ServerLevel> level;
		final WeakReference<BlockEntity> entity;
		final BlockPos pos;
		final Direction face;
		final boolean fluid;
		final Ecosystem owner;
		long epoch;
		final List<LazyOptional<?>> exposed = new ArrayList<>();
		final ForgeCapabilityWatch watch = new ForgeCapabilityWatch(this::invalidate);
		// The level holds capability listeners weakly; retain this one for exactly the wrapper's lifetime.
		final ICapabilityInvalidationListener listener;
		Endpoint(ServerLevel level, BlockPos pos, BlockEntity entity, Direction face, boolean fluid, Ecosystem owner) {
			this.level = new WeakReference<>(level); this.entity = new WeakReference<>(entity);
			this.pos = pos; this.face = face; this.fluid = fluid; this.owner = owner;
			listener = () -> { invalidate(); return valid(); };
			level.registerCapabilityListener(pos, listener);
			synchronized (ENDPOINTS) {
				var endpoints = ENDPOINTS.computeIfAbsent(entity, key -> new ArrayList<>());
				endpoints.removeIf(reference -> reference.get() == null); endpoints.add(new WeakReference<>(this));
			}
		}
		void invalidate() { epoch++; watch.clear(); var old = List.copyOf(exposed); exposed.clear(); for (var optional : old) optional.invalidate(); }
		<T> LazyOptional<T> track(LazyOptional<T> optional) { exposed.add(optional); return optional; }
		<T> T observe(LazyOptional<T> optional) {
			return watch.observe(optional);
		}
		long generation() { return epoch; }
		void committed() {
			// The journal calls this once per BE only after the real root commits. A removed/replaced/unloaded
			// host must not be dirtied through an old cached endpoint, even though the handler object survives.
			if (valid()) { BlockEntity target = entity.get(); if (target != null) target.setChanged(); }
		}
		boolean valid() {
			ServerLevel world = level.get(); BlockEntity blockEntity = entity.get();
			return world != null && world.getServer().isSameThread() && blockEntity != null && !blockEntity.isRemoved() && world.hasChunkAt(pos)
					&& world.getBlockEntity(pos) == blockEntity;
		}
		<T> T lookup(Supplier<T> action) {
			if (!valid()) return null;
			Query query = new Query(level.get(), pos, face, fluid);
			Set<Query> active = LOOKUPS.get();
			if (!active.add(query)) return null;
			try { return action.get(); }
			finally { active.remove(query); if (active.isEmpty()) LOOKUPS.remove(); }
		}
		public Ecosystem owner() { return owner; }
		public boolean neo() { return (fluid ? neoFluids() : neoItems()) != null; }
		public ForgeAnswer forge() {
			if (!forgeEnabled) return ForgeAnswer.NONE;
			ForgeAnswer answer = lookup(() -> {
				if (fluid) return audited(forgeHandler(ForgeCapabilities.FLUID_HANDLER)) != null ? ForgeAnswer.AUDITED : ForgeAnswer.NONE;
				IItemHandler handler = forgeHandler(ForgeCapabilities.ITEM_HANDLER);
				if (wholeContainer(handler)) return ForgeAnswer.WHOLE_CONTAINER;
				return audited(handler) != null ? ForgeAnswer.AUDITED : ForgeAnswer.NONE;
			});
			return answer == null ? ForgeAnswer.NONE : answer;
		}
		public boolean neoContainer() { return containerItems() != null; }
		public boolean fabric(boolean generic) { return (fluid ? fabricFluids(generic) : fabricItems(generic)) != null; }
		ResourceHandler<ItemResource> neoItems() {
			return lookup(() -> level.get().getCapability(Capabilities.Item.BLOCK, pos, entity.get().getBlockState(), entity.get(), face));
		}
		ResourceHandler<FluidResource> neoFluids() {
			return lookup(() -> level.get().getCapability(Capabilities.Fluid.BLOCK, pos, entity.get().getBlockState(), entity.get(), face));
		}
		ResourceHandler<ItemResource> forgeItems() {
			return forgeEnabled ? lookup(() -> audited(forgeHandler(ForgeCapabilities.ITEM_HANDLER))) : null;
		}
		ResourceHandler<FluidResource> forgeFluids() {
			return forgeEnabled ? lookup(() -> audited(forgeHandler(ForgeCapabilities.FLUID_HANDLER))) : null;
		}
		/**
		 * NeoForge's own wrapper of the whole Container a Forge owner exposes through Forge's InvWrapper, the same one
		 * NeoForge gives its consumers for a vanilla chest or barrel; null unless it writes that Container as the game
		 * would. Resolved again for every operation, like every other view.
		 */
		ResourceHandler<ItemResource> containerItems() {
			if (!forgeEnabled) return null;
			return lookup(() -> {
				IItemHandler handler = forgeHandler(ForgeCapabilities.ITEM_HANDLER);
				if (!wholeContainer(handler)) return null;
				Container container = ((InvWrapper) handler).getInv();
				if (vanillaWrites(container.getClass())) return VanillaContainerWrapper.of(container);
				TransferIssues.report("CONTAINER_WRITES_NOT_VANILLA", entity.get(), "The Container behind this block's Forge InvWrapper "
						+ "is not a BaseContainerBlockEntity writing through the game's own setItem; NeoForge's Container wrapper would "
						+ "bypass or replay its writes, so NeoForge consumers were not given it");
				return null;
			});
		}
		/** Forge's answer on this face; only inside a lookup. */
		private <T> T forgeHandler(Capability<T> capability) {
			BlockEntity target = entity.get();
			return (Object) target instanceof ICapabilityProvider provider ? observe(provider.getCapability(capability, face)) : null;
		}
		/** Forge's InvWrapper is not an owner's handler: it is neither audited nor reported as refused. */
		private ResourceHandler<ItemResource> audited(IItemHandler handler) {
			return wholeContainer(handler) ? null : ForgeSnapshotAdapters.items(handler, entity.get(), this::committed);
		}
		private ResourceHandler<FluidResource> audited(IFluidHandler handler) {
			return ForgeSnapshotAdapters.fluids(handler, entity.get(), this::committed);
		}
		@SuppressWarnings("unchecked") SlottedStorage<ItemVariant> fabricItems(boolean generic) {
			return lookup(() -> {
				Storage<ItemVariant> storage = fabric(ItemStorage.SIDED, SidedStorageBlockEntity::getItemStorage, generic);
				if (storage != null && !(storage instanceof SlottedStorage<?>)) TransferIssues.report("UNSLOTTED_STORAGE", storage,
						"NeoForge's indexed item API cannot represent this Fabric storage; cross-API transfer was not exposed");
				return storage instanceof SlottedStorage<?> slots ? (SlottedStorage<ItemVariant>) slots : null;
			});
		}
		@SuppressWarnings("unchecked") SlottedStorage<FluidVariant> fabricFluids(boolean generic) {
			return lookup(() -> {
				Storage<FluidVariant> storage = fabric(FluidStorage.SIDED, SidedStorageBlockEntity::getFluidStorage, generic);
				if (storage != null && !(storage instanceof SlottedStorage<?>)) TransferIssues.report("UNSLOTTED_STORAGE", storage,
						"NeoForge's indexed fluid API cannot represent this Fabric storage; cross-API transfer was not exposed");
				return storage instanceof SlottedStorage<?> slots ? (SlottedStorage<FluidVariant>) slots : null;
			});
		}
		/** The full Fabric lookup, or only the providers Fabric has for exactly this block (TransferPrecedence decides). */
		private <A> A fabric(BlockApiLookup<A, Direction> lookup, BiFunction<SidedStorageBlockEntity, Direction, A> sided, boolean generic) {
			BlockEntity target = entity.get(); BlockState state = target.getBlockState();
			if (generic) return lookup.find(level.get(), pos, state, target, face);
			var provider = lookup.getProvider(state.getBlock());
			A found = provider == null ? null : provider.find(level.get(), pos, state, target, face);
			return found == null && (Object) target instanceof SidedStorageBlockEntity storage ? sided.apply(storage, face) : found;
		}
	}
}
