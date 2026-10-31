package net.minecraftforge.fml.javafmlmod;

import net.minecraftforge.eventbus.api.bus.BusGroup;

/** Compile-only: what MinecraftForge hands a {@code @Mod} constructor. Never shipped. */
public class FMLJavaModLoadingContext {
	FMLJavaModLoadingContext() {
	}

	public BusGroup getModBusGroup() {
		throw new UnsupportedOperationException();
	}
}
