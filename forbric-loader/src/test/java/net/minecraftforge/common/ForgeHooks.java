package net.minecraftforge.common;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.Connection;

/** Test stand-in: records the connections told that configuration finished. */
public final class ForgeHooks {
	public static final List<Connection> completed = new ArrayList<>();

	private ForgeHooks() {
	}

	public static void handleClientConfigurationComplete(Connection connection) {
		completed.add(connection);
	}

	public static void reset() {
		completed.clear();
	}
}
