package net.neoforged.neoforge.network.payload;

import java.util.List;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public class CommonVersionPayload implements CustomPacketPayload {
	private static final CustomPacketPayload.Type<CommonVersionPayload> TYPE =
			new CustomPacketPayload.Type<>(new Identifier("c", "version"));
	private final List<Integer> versions;

	public CommonVersionPayload(List<Integer> versions) {
		this.versions = versions;
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public List<Integer> versions() {
		return versions;
	}
}
