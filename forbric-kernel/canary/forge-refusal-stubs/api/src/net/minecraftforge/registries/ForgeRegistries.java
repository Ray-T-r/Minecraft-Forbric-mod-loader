package net.minecraftforge.registries;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraftforge.fluids.FluidType;

/** Compile-only: the one MinecraftForge registry key the fixtures name. Never shipped. */
public final class ForgeRegistries {
	private ForgeRegistries() {
	}

	public static final class Keys {
		public static final ResourceKey<Registry<FluidType>> FLUID_TYPES = null;

		private Keys() {
		}
	}
}
