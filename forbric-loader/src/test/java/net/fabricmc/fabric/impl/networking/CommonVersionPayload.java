package net.fabricmc.fabric.impl.networking;

import java.util.Collection;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public class CommonVersionPayload implements CustomPacketPayload {
	private static final CustomPacketPayload.Type<CommonVersionPayload> TYPE =
			new CustomPacketPayload.Type<>(new Identifier("c", "version"));
	private final int[] versions;

	public CommonVersionPayload(int... versions) {
		this.versions = versions;
	}

	public CommonVersionPayload(Collection<Integer> versions) {
		this.versions = versions.stream().mapToInt(Integer::intValue).toArray();
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public int[] versions() {
		return versions;
	}
}
