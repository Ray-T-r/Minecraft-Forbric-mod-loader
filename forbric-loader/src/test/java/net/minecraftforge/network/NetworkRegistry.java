package net.minecraftforge.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.Connection;

/** Test stand-in: records which connections MinecraftForge was asked to start its networking for. */
public final class NetworkRegistry {
	public static final List<Connection> started = new ArrayList<>();

	private NetworkRegistry() {
	}

	public static void onConnectionStart(Connection connection) {
		started.add(connection);
	}

	public static void reset() {
		started.clear();
	}
}
