package net.forbric.kernel.runtime.transfer;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.ICapabilityInvalidationListener;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;

/**
 * Fallbacks for loaded server block entities only. Native providers always get the first answer. Face (including
 * null) is passed unchanged; lookup recursion is rejected by endpoint identity. Dynamic foreign providers are
 * resolved for every operation, so a caller caching our wrapper cannot pin an obsolete capability instance.
 */
public final class BlockTransferBridge {
	private BlockTransferBridge() { }
	private static final AtomicBoolean INSTALLED = new AtomicBoolean();
	private static volatile boolean enabled;
	private static volatile boolean forgeEnabled;
	private static final Map<BlockEntity, List<WeakReference<Endpoint>>> ENDPOINTS = new WeakHashMap<>();
	private static final ClassValue<Boolean> INHERITS_FORGE_QUERY = new ClassValue<>() {
		protected Boolean computeValue(Class<?> type) {
			try { return type.getMethod("getCapability", Capability.class, Direction.class).getDeclaringClass() == BlockEntity.class; }
			catch (NoSuchMethodException absent) { return false; }
		}
	};
	private record Query(Level level, BlockPos pos, Direction face, boolean fluid) { }
	private static final ThreadLocal<Set<Query>> LOOKUPS = ThreadLocal.withInitial(HashSet::new);

	/** After the mod registration window; only invoke when the selected Fabric transfer and NeoForge APIs exist. */
	public static void install() {
		if ("off".equalsIgnoreCase(System.getProperty("forbric.transferBridge", "on"))) return;
		PairedTransactions.checkHooks();
		try { BlockCapability.class.getDeclaredMethod("forbric$transferFallback"); }
		catch (NoSuchMethodException drift) { throw new IllegalStateException("NeoForge transfer capability fallback hook is missing", drift); }
		if (!INSTALLED.compareAndSet(false, true)) return;
		ItemStorage.SIDED.registerFallback(BlockTransferBridge::fabricItems);
		FluidStorage.SIDED.registerFallback(BlockTransferBridge::fabricFluids);
		try { BlockEntity.class.getDeclaredMethod("forbric$forgeTransferFallback"); forgeEnabled = true; }
		catch (NoSuchMethodException absent) { TransferIssues.report("FORGE_QUERY_HOOK_MISSING", null,
				"Forge block transfer fallback is not installed; Fabric/NeoForge transfers remain available"); }
		// A failure registering either callback leaves both directions dormant, even if one callback was added.
		enabled = true;
	}

	private static Storage<ItemVariant> fabricItems(Level level, BlockPos pos, BlockState state, BlockEntity entity, Direction face) {
		Endpoint endpoint = endpoint(level, pos, entity, face, false);
		if (endpoint == null) return null;
		if (endpoint.neoItems() != null) return NativeTransferAdapters.fabric(LiveTransferEndpoints.neo(endpoint::neoItems, endpoint::valid, endpoint::generation, ItemResource.EMPTY), TransferResources.ITEMS);
		if (endpoint.forgeItems() != null) return NativeTransferAdapters.fabric(LiveTransferEndpoints.neo(endpoint::forgeItems, endpoint::valid, endpoint::generation, ItemResource.EMPTY), TransferResources.ITEMS);
		return null;
	}
	private static Storage<FluidVariant> fabricFluids(Level level, BlockPos pos, BlockState state, BlockEntity entity, Direction face) {
		Endpoint endpoint = endpoint(level, pos, entity, face, true);
		if (endpoint == null) return null;
		if (endpoint.neoFluids() != null) return NativeTransferAdapters.fabric(LiveTransferEndpoints.neo(endpoint::neoFluids, endpoint::valid, endpoint::generation, FluidResource.EMPTY), TransferResources.FLUIDS);
		if (endpoint.forgeFluids() != null) return NativeTransferAdapters.fabric(LiveTransferEndpoints.neo(endpoint::forgeFluids, endpoint::valid, endpoint::generation, FluidResource.EMPTY), TransferResources.FLUIDS);
		return null;
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
		if (endpoint == null) return null;
		if (fluid) {
			if (endpoint.fabricFluids() != null) return NativeTransferAdapters.neo(LiveTransferEndpoints.fabric(endpoint::fabricFluids, endpoint::valid, endpoint::generation, FluidVariant.blank()), TransferResources.FLUIDS);
			return endpoint.forgeFluids() == null ? null : LiveTransferEndpoints.neo(endpoint::forgeFluids, endpoint::valid, endpoint::generation, FluidResource.EMPTY);
		}
		if (endpoint.fabricItems() != null) return NativeTransferAdapters.neo(LiveTransferEndpoints.fabric(endpoint::fabricItems, endpoint::valid, endpoint::generation, ItemVariant.blank()), TransferResources.ITEMS);
		return endpoint.forgeItems() == null ? null : LiveTransferEndpoints.neo(endpoint::forgeItems, endpoint::valid, endpoint::generation, ItemResource.EMPTY);
	}

	/** After the composed Forge provider declined. Never let a super-call preempt a subclass's native answer. */
	public static Object forgeFallback(Object existing, Object rawEntity, Object capability, Object context) {
		if (!enabled || !forgeEnabled || !(existing instanceof LazyOptional<?> result) || result.isPresent()
				|| !(rawEntity instanceof BlockEntity entity) || !INHERITS_FORGE_QUERY.get(entity.getClass())
				|| (context != null && !(context instanceof Direction))) return existing;
		boolean fluid;
		if (capability == ForgeCapabilities.ITEM_HANDLER) fluid = false;
		else if (capability == ForgeCapabilities.FLUID_HANDLER) fluid = true;
		else return existing;
		Endpoint endpoint = endpoint(entity.getLevel(), entity.getBlockPos(), entity, (Direction) context, fluid);
		if (endpoint == null) return existing;
		if (fluid) {
			ResourceHandler<FluidResource> handler;
			if (endpoint.neoFluids() != null) handler = LiveTransferEndpoints.neo(endpoint::neoFluids, endpoint::valid, endpoint::generation, FluidResource.EMPTY);
			else if (endpoint.fabricFluids() != null) handler = NativeTransferAdapters.neo(LiveTransferEndpoints.fabric(endpoint::fabricFluids, endpoint::valid, endpoint::generation, FluidVariant.blank()), TransferResources.FLUIDS);
			else return existing;
			return endpoint.track(LazyOptional.of(() -> ForgeLegacyFacades.fluids(handler)));
		}
		ResourceHandler<ItemResource> handler;
		if (endpoint.neoItems() != null) handler = LiveTransferEndpoints.neo(endpoint::neoItems, endpoint::valid, endpoint::generation, ItemResource.EMPTY);
		else if (endpoint.fabricItems() != null) handler = NativeTransferAdapters.neo(LiveTransferEndpoints.fabric(endpoint::fabricItems, endpoint::valid, endpoint::generation, ItemVariant.blank()), TransferResources.ITEMS);
		else return existing;
		return endpoint.track(LazyOptional.of(() -> ForgeLegacyFacades.items(handler)));
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
		return new Endpoint(server, pos.immutable(), entity, face, fluid);
	}
	private static final class Endpoint {
		final WeakReference<ServerLevel> level;
		final WeakReference<BlockEntity> entity;
		final BlockPos pos;
		final Direction face;
		final boolean fluid;
		long epoch;
		final List<LazyOptional<?>> exposed = new ArrayList<>();
		final ForgeCapabilityWatch watch = new ForgeCapabilityWatch(this::invalidate);
		// The level holds capability listeners weakly; retain this one for exactly the wrapper's lifetime.
		final ICapabilityInvalidationListener listener;
		Endpoint(ServerLevel level, BlockPos pos, BlockEntity entity, Direction face, boolean fluid) {
			this.level = new WeakReference<>(level); this.entity = new WeakReference<>(entity);
			this.pos = pos; this.face = face; this.fluid = fluid;
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
		ResourceHandler<ItemResource> neoItems() {
			return lookup(() -> level.get().getCapability(Capabilities.Item.BLOCK, pos, entity.get().getBlockState(), entity.get(), face));
		}
		ResourceHandler<FluidResource> neoFluids() {
			return lookup(() -> level.get().getCapability(Capabilities.Fluid.BLOCK, pos, entity.get().getBlockState(), entity.get(), face));
		}
		ResourceHandler<ItemResource> forgeItems() {
			if (!forgeEnabled) return null;
			return lookup(() -> {
				BlockEntity target = entity.get();
				if (!((Object) target instanceof ICapabilityProvider provider)) return null;
				var handler = observe(provider.getCapability(ForgeCapabilities.ITEM_HANDLER, face));
				return ForgeSnapshotAdapters.items(handler, target, this::committed);
			});
		}
		ResourceHandler<FluidResource> forgeFluids() {
			if (!forgeEnabled) return null;
			return lookup(() -> {
				BlockEntity target = entity.get();
				if (!((Object) target instanceof ICapabilityProvider provider)) return null;
				var handler = observe(provider.getCapability(ForgeCapabilities.FLUID_HANDLER, face));
				return ForgeSnapshotAdapters.fluids(handler, target, this::committed);
			});
		}
		@SuppressWarnings("unchecked") SlottedStorage<ItemVariant> fabricItems() {
			return lookup(() -> {
				Storage<ItemVariant> storage = ItemStorage.SIDED.find(level.get(), pos, entity.get().getBlockState(), entity.get(), face);
				if (storage != null && !(storage instanceof SlottedStorage<?>)) TransferIssues.report("UNSLOTTED_STORAGE", storage,
						"NeoForge's indexed item API cannot represent this Fabric storage; cross-API transfer was not exposed");
				return storage instanceof SlottedStorage<?> slots ? (SlottedStorage<ItemVariant>) slots : null;
			});
		}
		@SuppressWarnings("unchecked") SlottedStorage<FluidVariant> fabricFluids() {
			return lookup(() -> {
				Storage<FluidVariant> storage = FluidStorage.SIDED.find(level.get(), pos, entity.get().getBlockState(), entity.get(), face);
				if (storage != null && !(storage instanceof SlottedStorage<?>)) TransferIssues.report("UNSLOTTED_STORAGE", storage,
						"NeoForge's indexed fluid API cannot represent this Fabric storage; cross-API transfer was not exposed");
				return storage instanceof SlottedStorage<?> slots ? (SlottedStorage<FluidVariant>) slots : null;
			});
		}
	}
}
