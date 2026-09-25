/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime.transfer;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.transfer.v1.item.ContainerStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil;
import net.forbric.kernel.util.ForbricLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/**
 * A hopper against Fabric item storages NeoForge's hopper cannot see.
 *
 * <p>fabric-transfer-api-v1's {@code HopperBlockEntityMixin} asks {@code ItemStorage.SIDED} when vanilla's hopper found
 * no container: after {@code getAttachedContainer} in {@code ejectItems}, after {@code getSourceContainer} in
 * {@code suckInItems}. The merged hopper is NeoForge's, which calls neither, so the mixin never attached. NeoForge
 * sees a Fabric storage only through the kernel's capability bridge, and that bridge exposes only a slotted storage on
 * a block entity: a {@code CombinedStorage}, a drawer or network storage, a block with no block entity — or every
 * Fabric storage with {@code -Dforbric.transferBridge=off} — was invisible, and the hopper moved nothing.
 *
 * <p>HopperFabricStorageInjector calls these on NeoForge's two "found nothing" branches, which is where Fabric's own
 * injectors sit relative to the lookups: after the block's container and the entity containers, before the hopper
 * picks up item entities. The bodies are Fabric's: the same lookup, faces, {@code ContainerStorage} view and
 * one-item move, and the same answer, so the hopper's cooldown follows as it does natively. A NeoForge-visible
 * storage never reaches here, so nothing moves twice.
 */
public final class KernelFabricHopperStorage {
	/** {@link #extract}: Fabric has no storage above the hopper either; NeoForge's own path continues. */
	public static final int NOT_FOUND = -1;
	private static final Set<String> NOTED = ConcurrentHashMap.newKeySet();

	private KernelFabricHopperStorage() {
	}

	/** {@code ejectItems}, nothing on the hopper's facing side for NeoForge: Fabric's answer, or false as before. */
	public static boolean insert(Object level, Object pos, Object hopper) {
		if (!(level instanceof Level world) || !(pos instanceof BlockPos at) || !(hopper instanceof HopperBlockEntity blockEntity)) return false;
		Direction facing = blockEntity.getBlockState().getValue(HopperBlock.FACING);
		Storage<ItemVariant> target = ItemStorage.SIDED.find(world, at.relative(facing), facing.getOpposite());
		if (target == null) return false;
		note("inserted into", target);
		return moveOne(ContainerStorage.of(blockEntity, facing), target) == 1;
	}

	/**
	 * {@code suckInItems}, nothing above for NeoForge: 1 or 0 when a Fabric storage is there — the hopper's answer,
	 * and it does not pick up item entities, as natively — or {@link #NOT_FOUND}.
	 */
	public static int extract(Object level, Object hopper) {
		if (!(level instanceof Level world) || !(hopper instanceof Hopper container)) return NOT_FOUND;
		Storage<ItemVariant> source = ItemStorage.SIDED.find(world,
				BlockPos.containing(container.getLevelX(), container.getLevelY() + 1.0, container.getLevelZ()), Direction.DOWN);
		if (source == null) return NOT_FOUND;
		note("extracted from", source);
		return moveOne(source, ContainerStorage.of(container, Direction.UP));
	}

	/** Fabric's move of one unit, outside any transaction: 1, 0, or {@link #NOT_FOUND} without a source. */
	static <T> int moveOne(Storage<T> from, Storage<T> to) {
		if (from == null) return NOT_FOUND;
		return StorageUtil.move(from, to, resource -> true, 1, null) == 1 ? 1 : 0;
	}

	private static void note(String how, Object storage) {
		String key = how + ' ' + storage.getClass().getName();
		if (NOTED.add(key)) {
			ForbricLog.info("[Forbric/Hopper] a hopper %s Fabric storage %s through Fabric's own lookup (NeoForge's found "
					+ "nothing there)", how, storage.getClass().getName());
		}
	}
}
