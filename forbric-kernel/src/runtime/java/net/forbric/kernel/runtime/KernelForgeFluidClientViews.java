/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import net.forbric.kernel.util.Caught;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * NeoForge's client extensions of a MinecraftForge mod's fluid type ({@link KernelForgeFluidTypeViews.View}), answered
 * from the MinecraftForge type's own client extensions (ForgeFluidViewExtensionsInjector asks here first thing in
 * NeoForge's {@code IClientFluidTypeExtensions.of(FluidType)}).
 *
 * <p>NeoForge looks a type's extensions up in the map its registration event fills; the view is in no such map, so it
 * got NeoForge's defaults. Of the four questions NeoForge's extensions answer, the merged client asks MinecraftForge's
 * extensions itself for three: the fog distances ({@code ForgeHooksClient.setupFog}, beside NeoForge's own call), and the
 * overlay of the fluid the eyes are in ({@code ScreenEffectRenderer} asks MinecraftForge's extension of
 * {@code getEyeInFluidType()}, then NeoForge's of its own eye type). The fog colour it asks only NeoForge
 * ({@code ClientHooks.getFogColor}; MinecraftForge's {@code ForgeHooksClient.getFogColor} lost its call site), so a
 * MinecraftForge fluid's fog stayed the sky's colour. The view's extensions therefore answer the fog colour from the
 * MinecraftForge extension — under the same condition MinecraftForge's hook asks it, which NeoForge's hook shares — and
 * leave the overlay and the fog distances at NeoForge's defaults, so none of them is applied twice. A type with no client
 * extensions of its own (MinecraftForge's {@code DEFAULT}) changes nothing.
 */
public final class KernelForgeFluidClientViews {
	private KernelForgeFluidClientViews() {
	}

	/** NeoForge's extensions for {@code type} when it is the kernel's view of a MinecraftForge type, else null (NeoForge decides). */
	public static Object neoExtensions(Object type) {
		if (!(type instanceof KernelForgeFluidTypeViews.View view)) return null;
		Object extensions = view.clientExtensions;
		if (extensions == null) view.clientExtensions = extensions = new NeoExtensions(view.forge);
		return extensions;
	}

	/** The view's NeoForge extensions: the fog colour is the MinecraftForge type's, the rest NeoForge's defaults. */
	static final class NeoExtensions implements net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions {
		final net.minecraftforge.fluids.FluidType forge;

		NeoExtensions(net.minecraftforge.fluids.FluidType forge) {
			this.forge = forge;
		}

		@Override
		public void modifyFogColor(Camera camera, float partialTick, ClientLevel level, int renderDistance, float darkenWorldAmount,
				Vector4f fluidFogColor) {
			try {
				var extensions = net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions.of(forge);
				if (extensions == net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions.DEFAULT) return;
				Vector3f colour = extensions.modifyFogColor(camera, partialTick, level, renderDistance, darkenWorldAmount,
						new Vector3f(fluidFogColor.x, fluidFogColor.y, fluidFogColor.z));
				if (colour != null) fluidFogColor.set(colour.x, colour.y, colour.z, fluidFogColor.w);
			} catch (RuntimeException | LinkageError broken) {
				Caught.warn(broken, "[Forbric/Fluid] the MinecraftForge client extensions of %s threw while choosing a fog colour; "
						+ "the fog keeps the colour it had", forge);
			}
		}

		@Override public String toString() { return "NeoForge client extensions of " + forge; }
	}
}
