/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.runtime;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraftforge.common.capabilities.CapabilityDispatcher;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityProvider;
import net.minecraftforge.common.capabilities.ICapabilityProviderImpl;
import net.minecraftforge.event.AttachCapabilitiesEvent;

/**
 * The composed MinecraftForge capability provider for the three root types the merge put under NeoForge's
 * {@code AttachmentHolder} instead of Forge's {@code CapabilityProvider}.
 *
 * <p>Java has no multiple inheritance, so no merge can give {@code Entity} both superclasses; composition is the
 * only correct shape, and it is Forge's own — {@code LevelChunk} carries a {@code CapabilityProvider$AsField}
 * exactly like this. The transformer adds a lazily-created field of that type to each root plus straight-line
 * delegates; this class supplies the three {@code AsField} subclasses (whose two abstract methods are what the
 * carrier's own {@code CapabilityProvider$Entities/$BlockEntities/$Levels} do: fire the per-type
 * {@code AttachCapabilitiesEvent} bus, ask it for listeners) and the null-checking helpers, so that every
 * synthesised method is branch-free — the frame recomputer never touches these classes.
 *
 * <p>Everything else — gathering, dispatching, LazyOptional invalidation, NBT (de)serialisation, lazy replay —
 * is Forge's {@code CapabilityProvider}/{@code CapabilityDispatcher} code, in Forge's own lazy mode: the
 * {@code AttachCapabilitiesEvent} fires on the first query or deserialise, not in the constructor.
 * {@code -Dforbric.forgeCapabilities=off} means nothing references this class.
 */
public final class KernelForgeCapabilities {
	public static final String PROPERTY = "forbric.forgeCapabilities";

	private KernelForgeCapabilities() {
	}

	/** Exposes the one protected-final member a delegate on the owner needs. */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	abstract static class Composed extends CapabilityProvider.AsField {
		Composed(Object owner) {
			super((ICapabilityProviderImpl) owner, true);
		}

		public CapabilityDispatcher dispatcher() {
			return getCapabilities();
		}
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	static final class Entities extends Composed {
		Entities(Object owner) {
			super(owner);
		}

		@Override
		protected AttachCapabilitiesEvent fireAttachCapabilitiesEvent(ICapabilityProviderImpl owner) {
			return (AttachCapabilitiesEvent.Entities) AttachCapabilitiesEvent.Entities.BUS.fire(
					new AttachCapabilitiesEvent.Entities((Entity) (Object) owner));
		}

		@Override
		protected boolean shouldFireAttachCapabilitiesEvent() {
			return AttachCapabilitiesEvent.Entities.BUS.hasListeners();
		}
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	static final class BlockEntities extends Composed {
		BlockEntities(Object owner) {
			super(owner);
		}

		@Override
		protected AttachCapabilitiesEvent fireAttachCapabilitiesEvent(ICapabilityProviderImpl owner) {
			return (AttachCapabilitiesEvent.BlockEntities) AttachCapabilitiesEvent.BlockEntities.BUS.fire(
					new AttachCapabilitiesEvent.BlockEntities((BlockEntity) (Object) owner));
		}

		@Override
		protected boolean shouldFireAttachCapabilitiesEvent() {
			return AttachCapabilitiesEvent.BlockEntities.BUS.hasListeners();
		}
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	static final class Levels extends Composed {
		Levels(Object owner) {
			super(owner);
		}

		@Override
		protected AttachCapabilitiesEvent fireAttachCapabilitiesEvent(ICapabilityProviderImpl owner) {
			return (AttachCapabilitiesEvent.Levels) AttachCapabilitiesEvent.Levels.BUS.fire(
					new AttachCapabilitiesEvent.Levels((Level) (Object) owner));
		}

		@Override
		protected boolean shouldFireAttachCapabilitiesEvent() {
			return AttachCapabilitiesEvent.Levels.BUS.hasListeners();
		}
	}

	// ---- the accessor's one branch, held here so the synthesised forbric$caps() has none

	@SuppressWarnings("rawtypes")
	public static CapabilityProvider.AsField entity(CapabilityProvider.AsField existing, Object owner) {
		return existing != null ? existing : create(new Entities(owner));
	}

	@SuppressWarnings("rawtypes")
	public static CapabilityProvider.AsField blockEntity(CapabilityProvider.AsField existing, Object owner) {
		return existing != null ? existing : create(new BlockEntities(owner));
	}

	@SuppressWarnings("rawtypes")
	public static CapabilityProvider.AsField level(CapabilityProvider.AsField existing, Object owner) {
		return existing != null ? existing : create(new Levels(owner));
	}

	@SuppressWarnings("rawtypes")
	private static CapabilityProvider.AsField create(CapabilityProvider.AsField field) {
		// Forge's LevelChunk constructor does exactly this after newing its AsField; in lazy mode it defers the
		// gather until the first query, so the AttachCapabilitiesEvent fires then.
		field.initInternal();
		return field;
	}

	// ---- read-without-creating helpers: invalidating or saving a never-gathered provider is a no-op on Forge too

	@SuppressWarnings("rawtypes")
	public static void invalidate(CapabilityProvider.AsField field) {
		if (field != null) field.invalidateCaps();
	}

	@SuppressWarnings("rawtypes")
	public static void revive(CapabilityProvider.AsField field) {
		if (field != null) field.reviveCaps();
	}

	@SuppressWarnings("rawtypes")
	public static CapabilityDispatcher dispatcher(CapabilityProvider.AsField field) {
		return field == null ? null : ((Composed) field).dispatcher();
	}

	@SuppressWarnings("rawtypes")
	public static CompoundTag serialize(CapabilityProvider.AsField field, HolderLookup.Provider lookup) {
		return field == null ? null : field.serializeInternal(lookup);
	}

	@SuppressWarnings("rawtypes")
	public static CompoundTag serializeBlockEntity(CapabilityProvider.AsField field, ValueOutput output, Object owner) {
		return field == null ? null : field.serializeInternal(lookupOf(owner));
	}

	/** Forge's own save shape: {@code output.storeNullable("ForgeCaps", CompoundTag.CODEC, serializeCaps(...))}. */
	@SuppressWarnings("rawtypes")
	public static void saveBlockEntity(CapabilityProvider.AsField field, ValueOutput output, Object owner) {
		if (field == null) return;
		try {
			CompoundTag tag = field.serializeInternal(lookupOf(owner));
			if (tag != null) output.storeNullable("ForgeCaps", CompoundTag.CODEC, tag);
		} catch (Throwable t) {
			reportOnce("saving a block entity's MinecraftForge capabilities", t);
		}
	}

	@SuppressWarnings("rawtypes")
	public static void saveEntity(CapabilityProvider.AsField field, ValueOutput output, Object owner) {
		if (field == null) return;
		try {
			CompoundTag tag = field.serializeInternal(((Entity) owner).registryAccess());
			if (tag != null) output.storeNullable("ForgeCaps", CompoundTag.CODEC, tag);
		} catch (Throwable t) {
			reportOnce("saving an entity's MinecraftForge capabilities", t);
		}
	}

	/** Forge's own load shape; in lazy mode the tag is parked and replayed on the first getCapabilities. */
	@SuppressWarnings("rawtypes")
	public static void load(CapabilityProvider.AsField field, ValueInput input) {
		try {
			input.read("ForgeCaps", CompoundTag.CODEC).ifPresent(tag -> field.deserializeInternal(input.lookup(), tag));
		} catch (Throwable t) {
			reportOnce("loading MinecraftForge capabilities from ForgeCaps", t);
		}
	}

	/** The merged NeoForge saveAdditional's own choice for a level-less block entity: an empty registry access. */
	private static HolderLookup.Provider lookupOf(Object blockEntity) {
		Level level = blockEntity instanceof BlockEntity be ? be.getLevel() : null;
		return level != null ? level.registryAccess() : RegistryAccess.EMPTY;
	}

	private static volatile boolean reported;

	private static void reportOnce(String what, Throwable t) {
		if (reported) return;
		reported = true;
		ForbricLog.warn("[Forbric/Capabilities] " + what + " threw — that data is skipped; later failures of this "
				+ "kind are not repeated", Reflect.unwrap(t));
	}

	// ---- registration stage

	/**
	 * Forge's own {@code INJECT_CAPABILITIES} stage: {@code CapabilityManager.injectCapabilities()} scans mod
	 * scan data for {@code @AutoRegisterCapability} and marks each as registered. Advisory on this base
	 * ({@code isRegistered()} is read only by Forge's own manager), and the mod scan data is still empty under
	 * the kernel, so the count says so rather than pretending.
	 */
	public static int injectCapabilities() {
		CapabilityManager.injectCapabilities();
		int annotated = 0;
		try {
			for (net.minecraftforge.forgespi.language.ModFileScanData scan : net.minecraftforge.fml.ModList.getAllScanData()) {
				for (net.minecraftforge.forgespi.language.ModFileScanData.AnnotationData annotation : scan.getAnnotations()) {
					if ("Lnet/minecraftforge/common/capabilities/AutoRegisterCapability;".equals(annotation.annotationType().getDescriptor())) annotated++;
				}
			}
		} catch (Throwable t) {
			return -1;
		}
		return annotated;
	}
}
