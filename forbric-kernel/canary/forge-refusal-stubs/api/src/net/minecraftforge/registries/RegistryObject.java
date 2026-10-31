package net.minecraftforge.registries;

import java.util.function.Supplier;

/** Compile-only: what a deferred register hands back for an entry. Never shipped. */
public final class RegistryObject<T> implements Supplier<T> {
	private RegistryObject() {
	}

	@Override
	public T get() {
		throw new UnsupportedOperationException();
	}
}
