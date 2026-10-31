package net.forbric.kernel.runtime.transfer;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
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
import net.forbric.kernel.util.Caught;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.ICapabilityInvalidationListener;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * Fallbacks for loaded server block entities only. Native providers always get the first answer. Face (including
 * null) is passed unchanged; lookup recursion is rejected by endpoint identity. Dynamic foreign providers are
 * resolved for every operation, so a caller caching our wrapper cannot pin an obsolete capability instance.
 * Among foreign providers the block entity's OWNER answers first, and a generic wrapper of another ecosystem never
 * speaks for it; see TransferPrecedence.
 *
 * <p>Energy takes the same path as items and fluids: the same seams, endpoints, precedence, invalidation and recursion
 * guard. The Fabric side of energy is Team Reborn Energy, an ordinary mod that may be absent, so this class never names
 * a Reborn type: RebornEnergyBridge supplies {@link FabricEnergy} when, and only when, the boot seam found Reborn
 * installed. Without it NeoForge energy is not bridged to Fabric at all.
 */
public final class BlockTransferBridge {
	private BlockTransferBridge() { }
	private static final AtomicBoolean INSTALLED = new AtomicBoolean();
	private static volatile boolean enabled;
	// Ownership is the mod that registered the block entity TYPE, not the jar its class came from: a mod may reuse
	// another jar's class (the M33 fixture ships every machine class in its Fabric jar). Cached once the registry
	// names the type. Vanilla and unknown namespaces have no owner and keep the previous order.
	private static final Map<BlockEntityType<?>, Optional<Ecosystem>> OWNERS = new ConcurrentHashMap<>();
	/** What one query asks for. Part of the recursion key: an item lookup never blocks an energy lookup. */
	enum Kind { ITEM, FLUID, ENERGY }
	private record Query(Level level, BlockPos pos, Direction face, Kind kind) { }
	/**
	 * The Fabric side of energy, supplied by RebornEnergyBridge only when Team Reborn Energy is installed. Reborn
	 * stores cross it as Object, so a pack without Reborn never loads a Reborn class through this bridge.
	 */
	interface FabricEnergy {
		/** Reborn's store for this block: its whole lookup when generic, otherwise only providers for exactly this block. */
		Object find(Level level, BlockPos pos, BlockState state, BlockEntity entity, Direction face, boolean generic);
		/** A NeoForge view of whichever store {@code storage} resolves to at each operation. */
		EnergyHandler view(Supplier<Object> storage, BooleanSupplier valid, LongSupplier generation);
	}
	private static volatile FabricEnergy fabricEnergy;
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
		// A failure registering either callback leaves both directions dormant, even if one callback was added.
		enabled = true;
	}

	/**
	 * Fabric has no public way to run a fallback before its own, and its first two answer for every
	 * SidedStorageBlockEntity and every Container. The provider is registered through the public registerFallback, then
	 * moved to the front of the list that call appended it to: the one list field of the lookup whose last element is
	 * now this provider. Found by what registerFallback did, not by what the lookup's implementation calls the list, so
	 * any implementation that keeps its fallbacks in a list is served. If no such list is found, or it cannot be
	 * reordered, the provider stays appended and a Fabric consumer sees Fabric's generic view first, as it did before.
	 * BlockTransferBridgeTest pins both halves, on a stand-in lookup and on the real Fabric one.
	 */
	static <A> void ahead(BlockApiLookup<A, Direction> lookup, BlockApiLookup.BlockApiProvider<A, Direction> provider) {
		lookup.registerFallback(provider);
		List<Object> list = appendedTo(lookup, provider);
		boolean moved = false;
		if (list != null) {
			try {
				list.remove(list.size() - 1);
				try {
					list.add(0, provider);
					moved = true;
				} catch (RuntimeException refused) {
					Caught.warn(refused, "[Forbric/Transfer] %s would not take a fallback at its front; it is appended "
							+ "again", lookup.getClass().getName());
					list.add(provider);
				}
			} catch (RuntimeException refused) {
				// a list that cannot be reordered keeps the provider where registerFallback put it
				Caught.warn(refused, "[Forbric/Transfer] could not put a fallback ahead of Fabric's generic ones in %s; it "
						+ "stays appended", lookup.getClass().getName());
			}
		}
		if (!moved) TransferIssues.report("FABRIC_FALLBACK_ORDER", lookup, "Fabric's generic fallbacks answer before a NeoForge owner's provider");
	}

	/** The one instance list field of {@code lookup} whose last element is {@code provider}; null when none or several. */
	@SuppressWarnings("unchecked")
	private static List<Object> appendedTo(Object lookup, Object provider) {
		List<Object> only = null;
		for (Class<?> type = lookup.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
			for (java.lang.reflect.Field field : type.getDeclaredFields()) {
				if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || !List.class.isAssignableFrom(field.getType())) continue;
				Object value;
				try {
					field.setAccessible(true);
					value = field.get(lookup);
				} catch (ReflectiveOperationException | RuntimeException unreadable) {
					Caught.warn(unreadable, "[Forbric/Transfer] could not read %s.%s while looking for the list a fallback "
							+ "was appended to", type.getName(), field.getName());
					continue;
				}
				if (!(value instanceof List<?> list) || list.isEmpty() || list.get(list.size() - 1) != provider) continue;
				if (only != null && only != list) return null;
				only = (List<Object>) list;
			}
		}
		return only;
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
		Endpoint endpoint = endpoint(level, pos, entity, face, Kind.ITEM);
		Answer answer = endpoint == null ? null : TransferPrecedence.answer(Ecosystem.FABRIC, endpoint);
		return answer == null ? null : NativeTransferAdapters.fabric(itemView(endpoint, answer), TransferResources.ITEMS);
	}
	private static Storage<FluidVariant> fabricFluids(Level level, BlockPos pos, BlockEntity entity, Direction face) {
		Endpoint endpoint = endpoint(level, pos, entity, face, Kind.FLUID);
		Answer answer = endpoint == null ? null : TransferPrecedence.answer(Ecosystem.FABRIC, endpoint);
		return answer == null ? null : NativeTransferAdapters.fabric(fluidView(endpoint, answer), TransferResources.FLUIDS);
	}
	/** A NeoForge-typed live view of whichever source answered; every operation resolves that source again. */
	private static ResourceHandler<ItemResource> itemView(Endpoint endpoint, Answer answer) {
		return switch (answer) {
			case NEOFORGE -> LiveTransferEndpoints.neo(endpoint::neoItems, endpoint::valid, endpoint::generation, ItemResource.EMPTY);
			case FABRIC, FABRIC_EXPLICIT -> {
				boolean generic = answer == Answer.FABRIC;
				yield NativeTransferAdapters.neo(LiveTransferEndpoints.fabric(() -> endpoint.fabricItems(generic), endpoint::valid, endpoint::generation, ItemVariant.blank()), TransferResources.ITEMS);
			}
		};
	}
	private static ResourceHandler<FluidResource> fluidView(Endpoint endpoint, Answer answer) {
		return switch (answer) {
			case NEOFORGE -> LiveTransferEndpoints.neo(endpoint::neoFluids, endpoint::valid, endpoint::generation, FluidResource.EMPTY);
			case FABRIC, FABRIC_EXPLICIT -> {
				boolean generic = answer == Answer.FABRIC;
				yield NativeTransferAdapters.neo(LiveTransferEndpoints.fabric(() -> endpoint.fabricFluids(generic), endpoint::valid, endpoint::generation, FluidVariant.blank()), TransferResources.FLUIDS);
			}
		};
	}

	/** Energy, the same way: every operation resolves the answering source again. */
	private static EnergyHandler energyView(Endpoint endpoint, Answer answer) {
		return switch (answer) {
			case NEOFORGE -> LiveTransferEndpoints.energy(endpoint::neoEnergy, endpoint::valid, endpoint::generation);
			case FABRIC, FABRIC_EXPLICIT -> {
				boolean generic = answer == Answer.FABRIC;
				FabricEnergy side = fabricEnergy;
				if (side == null) throw new IllegalStateException("Fabric answered an energy query without Team Reborn Energy");
				yield side.view(() -> endpoint.fabricEnergy(generic), endpoint::valid, endpoint::generation);
			}
		};
	}
	/** RebornEnergyBridge installs itself here, after install(); a second call replaces nothing. */
	static void fabricEnergy(FabricEnergy side) { if (fabricEnergy == null) fabricEnergy = java.util.Objects.requireNonNull(side); }
	/** Whether install() connected the bridge (it stays dormant when switched off or when a hook is missing). */
	static boolean installed() { return enabled; }
	/**
	 * A Fabric (Reborn) consumer's energy query, from RebornEnergyBridge's two fallbacks: before Fabric's own
	 * fallbacks for a NeoForge owner, after them for everything else, exactly as items and fluids.
	 */
	static EnergyHandler energyForFabric(Level level, BlockPos pos, BlockEntity entity, Direction face, boolean beforeGeneric) {
		if (entity == null || TransferPrecedence.fabricAsksBeforeGeneric(owner(entity)) != beforeGeneric) return null;
		Endpoint endpoint = endpoint(level, pos, entity, face, Kind.ENERGY);
		Answer answer = endpoint == null ? null : TransferPrecedence.answer(Ecosystem.FABRIC, endpoint);
		return answer == null ? null : energyView(endpoint, answer);
	}

	/** The single null-result seam in BlockCapability.getCapability, after all native providers declined. */
	public static Object neoFallback(Object capability, Object rawLevel, Object rawPos, Object rawState, Object rawEntity, Object context) {
		if (!enabled || !(rawLevel instanceof Level level) || !(rawPos instanceof BlockPos pos)
				|| !(rawEntity instanceof BlockEntity entity) || (context != null && !(context instanceof Direction))) return null;
		Kind kind;
		if (capability == Capabilities.Item.BLOCK) kind = Kind.ITEM;
		else if (capability == Capabilities.Fluid.BLOCK) kind = Kind.FLUID;
		else if (capability == Capabilities.Energy.BLOCK) kind = Kind.ENERGY;
		else return null;
		Endpoint endpoint = endpoint(level, pos, entity, (Direction) context, kind);
		// A NeoForge owner: only Fabric's explicit providers answer for it. Fabric's generic Container wrapper would
		// expose every slot on every face as a write bridge whose rollback runs the mod's own setItem.
		Answer answer = endpoint == null ? null : TransferPrecedence.answer(Ecosystem.NEOFORGE, endpoint);
		if (answer == null) return null;
		return switch (kind) {
			case ITEM -> itemView(endpoint, answer);
			case FLUID -> fluidView(endpoint, answer);
			case ENERGY -> energyView(endpoint, answer);
		};
	}

	private static Endpoint endpoint(Level level, BlockPos pos, BlockEntity entity, Direction face, Kind kind) {
		if (!enabled || !(level instanceof ServerLevel server) || !server.getServer().isSameThread() || entity == null || !server.hasChunkAt(pos)
				|| entity.isRemoved() || server.getBlockEntity(pos) != entity) return null;
		return new Endpoint(server, pos.immutable(), entity, face, kind, owner(entity));
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
		final Kind kind;
		final Ecosystem owner;
		long epoch;
		// The level holds capability listeners weakly; retain this one for exactly the wrapper's lifetime.
		final ICapabilityInvalidationListener listener;
		Endpoint(ServerLevel level, BlockPos pos, BlockEntity entity, Direction face, Kind kind, Ecosystem owner) {
			this.level = new WeakReference<>(level); this.entity = new WeakReference<>(entity);
			this.pos = pos; this.face = face; this.kind = kind; this.owner = owner;
			listener = () -> { invalidate(); return valid(); };
			level.registerCapabilityListener(pos, listener);
		}
		void invalidate() { epoch++; }
		long generation() { return epoch; }
		boolean valid() {
			ServerLevel world = level.get(); BlockEntity blockEntity = entity.get();
			return world != null && world.getServer().isSameThread() && blockEntity != null && !blockEntity.isRemoved() && world.hasChunkAt(pos)
					&& world.getBlockEntity(pos) == blockEntity;
		}
		<T> T lookup(Supplier<T> action) {
			if (!valid()) return null;
			Query query = new Query(level.get(), pos, face, kind);
			Set<Query> active = LOOKUPS.get();
			if (!active.add(query)) return null;
			try { return action.get(); }
			finally { active.remove(query); if (active.isEmpty()) LOOKUPS.remove(); }
		}
		public Ecosystem owner() { return owner; }
		public boolean neo() {
			return switch (kind) { case ITEM -> neoItems() != null; case FLUID -> neoFluids() != null; case ENERGY -> neoEnergy() != null; };
		}
		public boolean fabric(boolean generic) {
			return switch (kind) {
				case ITEM -> fabricItems(generic) != null;
				case FLUID -> fabricFluids(generic) != null;
				case ENERGY -> fabricEnergy(generic) != null;
			};
		}
		ResourceHandler<ItemResource> neoItems() {
			return lookup(() -> level.get().getCapability(Capabilities.Item.BLOCK, pos, entity.get().getBlockState(), entity.get(), face));
		}
		ResourceHandler<FluidResource> neoFluids() {
			return lookup(() -> level.get().getCapability(Capabilities.Fluid.BLOCK, pos, entity.get().getBlockState(), entity.get(), face));
		}
		EnergyHandler neoEnergy() {
			return lookup(() -> level.get().getCapability(Capabilities.Energy.BLOCK, pos, entity.get().getBlockState(), entity.get(), face));
		}
		/** Reborn's store on this face, as an opaque object; null without Team Reborn Energy. */
		Object fabricEnergy(boolean generic) {
			FabricEnergy side = fabricEnergy;
			if (side == null) return null;
			return lookup(() -> { BlockEntity target = entity.get(); return side.find(level.get(), pos, target.getBlockState(), target, face, generic); });
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
