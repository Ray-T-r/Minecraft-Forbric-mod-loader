package net.minecraft.core;

/** Fixture stand-in: vanilla's "is this instance of that type?", which an item stack answers for its item. */
public interface TypedInstance<T> {
	T typeInstance();

	default boolean is(T type) {
		return typeInstance() == type;
	}
}
