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
