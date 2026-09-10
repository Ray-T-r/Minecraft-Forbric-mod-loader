package net.minecraft.network.protocol.common.custom;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

public interface CustomPacketPayload {
	Type<? extends CustomPacketPayload> type();

	final class Type<T extends CustomPacketPayload> {
		private final Identifier id;

		public Type(Identifier id) {
			this.id = id;
		}

		public Identifier id() {
			return id;
		}
	}

	record TypeAndCodec<B, T extends CustomPacketPayload>(Type<T> type, StreamCodec<? super B, T> codec) {
	}
}
