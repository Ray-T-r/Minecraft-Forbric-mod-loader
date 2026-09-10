package net.minecraftforge.event;

import java.util.function.Consumer;

import net.minecraft.network.Connection;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraftforge.network.tasks.SyncConfigTask;
import net.minecraftforge.network.tasks.SyncRegistriesTask;

/** Test stand-in: offers the tasks MinecraftForge's own handler would, in its order. */
public final class ForgeEventFactory {
	public static boolean gathered;

	private ForgeEventFactory() {
	}

	public static void gatherLoginConfigTasks(Connection connection, Consumer<ConfigurationTask> sink) {
		gathered = true;
		sink.accept(new SyncRegistriesTask());
		sink.accept(new SyncConfigTask());
	}

	public static void reset() {
		gathered = false;
	}
}
