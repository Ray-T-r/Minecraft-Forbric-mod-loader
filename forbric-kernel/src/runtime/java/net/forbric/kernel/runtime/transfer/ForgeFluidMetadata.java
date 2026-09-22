package net.forbric.kernel.runtime.transfer;

import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.neoforged.neoforge.transfer.fluid.FluidResource;

/**
 * Forge 26.2 still uses CompoundTag for fluid metadata; Fabric/NeoForge use DataComponentPatch. Empty metadata
 * converts directly. A non-empty value requires an explicit fluid-specific codec AND an exact round trip on
 * every conversion. A missing/throwing/lossy codec declines transfer instead of stripping data.
 */
public final class ForgeFluidMetadata {
	private ForgeFluidMetadata() { }
	public interface Codec {
		DataComponentPatch toComponents(CompoundTag tag);
		CompoundTag toTag(DataComponentPatch components);
	}
	private static final Map<Fluid, Codec> CODECS = new IdentityHashMap<>();
	public static synchronized void register(Fluid fluid, Codec codec) {
		java.util.Objects.requireNonNull(fluid); java.util.Objects.requireNonNull(codec);
		if (CODECS.putIfAbsent(fluid, codec) != null) throw new IllegalArgumentException("A fluid metadata codec is already registered");
	}
	private static synchronized Codec codec(Fluid fluid) { return CODECS.get(fluid); }
	private static boolean empty(CompoundTag tag) { return tag == null || tag.isEmpty(); }
	private static boolean same(CompoundTag a, CompoundTag b) { return empty(a) ? empty(b) : a.equals(b); }

	public static FluidResource toNeo(FluidStack stack) {
		if (stack.isEmpty()) return FluidResource.EMPTY;
		if (!stack.hasTag() || empty(stack.getTag())) return FluidResource.of(stack.getFluid());
		Codec codec = codec(stack.getFluid());
		try {
			if (codec != null) {
				CompoundTag original = stack.getTag().copy();
				DataComponentPatch patch = codec.toComponents(original.copy());
				if (patch != null && !patch.isEmpty() && same(original, codec.toTag(patch))) return FluidResource.of(stack.getFluid(), patch);
			}
		} catch (RuntimeException invalid) { /* A codec that cannot round-trip this value grants no write capability. */ }
		TransferIssues.report("FLUID_METADATA_UNREPRESENTABLE", stack,
				"Forge fluid tag has no verified lossless component mapping; the fluid was not transferred");
		return null;
	}

	public static FluidStack toForge(FluidResource resource, int amount) {
		if (resource.isEmpty() || amount == 0) return FluidStack.EMPTY;
		if (amount < 0) throw new IllegalArgumentException("Negative fluid amount");
		if (resource.isComponentsPatchEmpty()) return new FluidStack(resource.getFluid(), amount);
		Codec codec = codec(resource.getFluid());
		try {
			if (codec != null) {
				DataComponentPatch original = resource.getComponentsPatch();
				CompoundTag tag = codec.toTag(original);
				if (!empty(tag) && original.equals(codec.toComponents(tag.copy()))) {
					return new FluidStack(resource.getFluid(), amount, tag.copy());
				}
			}
		} catch (RuntimeException invalid) { /* Reject, never silently erase a component. */ }
		TransferIssues.report("FLUID_METADATA_UNREPRESENTABLE", resource,
				"Fluid components have no verified lossless Forge tag mapping; the fluid was not transferred");
		return null;
	}
}
