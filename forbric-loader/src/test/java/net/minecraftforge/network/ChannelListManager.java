package net.minecraftforge.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.Connection;

/** Test stand-in for MinecraftForge's channel-list manager: records every declaration it is asked to send. */
public final class ChannelListManager {
	public static final List<Connection> declared = new ArrayList<>();

	private ChannelListManager() {
	}

	public static void addChannels(Connection connection) {
		declared.add(connection);
	}
}
