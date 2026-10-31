package net.minecraftforge.registries;

import java.util.function.Supplier;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraftforge.eventbus.api.bus.BusGroup;

/** Compile-only: MinecraftForge's deferred register, the calls the fixtures make. Never shipped. */
public class DeferredRegister<T> {
	private DeferredRegister() {
	}

	public static <B> DeferredRegister<B> create(ResourceKey<? extends Registry<B>> key, String modid) {
		throw new UnsupportedOperationException();
	}

	public <I extends T> RegistryObject<I> register(String name, Supplier<? extends I> supplier) {
		throw new UnsupportedOperationException();
	}

	public void register(BusGroup group) {
		throw new UnsupportedOperationException();
	}
}
