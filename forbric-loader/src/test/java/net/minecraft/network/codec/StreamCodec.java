package net.minecraft.network.codec;

public interface StreamCodec<B, V> {
	void encode(B buf, V value);

	V decode(B buf);
}
