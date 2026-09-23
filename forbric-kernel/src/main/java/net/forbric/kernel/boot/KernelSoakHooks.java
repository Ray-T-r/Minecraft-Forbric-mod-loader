package net.forbric.kernel.boot;

import java.lang.reflect.Method;
import net.forbric.kernel.util.ForbricLog;

/** Optional game-side driver; no game type is linked from BOOT and ordinary sessions do not load it. */
public final class KernelSoakHooks {
	private KernelSoakHooks() { }
	private static Method tick;
	private static boolean unavailable;
	public static boolean enabled() { return Boolean.getBoolean("forbric.clientSoak"); }
	public static void onClientTick(Object minecraft) {
		if (!enabled() || minecraft == null || unavailable) return;
		try {
			if (tick == null) tick = Class.forName("net.forbric.kernel.runtime.soak.ClientSoakController", true,
					minecraft.getClass().getClassLoader()).getMethod("onTick", Object.class);
			tick.invoke(null, minecraft);
		} catch (Throwable failure) {
			unavailable = true;
			ForbricLog.error("[Forbric/ClientSoak] FATAL controller unavailable; no soak acceptance can be recorded", failure);
			stop(minecraft);
		}
	}
	/** An owned soak run whose controller cannot start must end now: left alone, quick-play keeps the client idling
	 *  in the world with no telemetry until the launcher's multi-hour timeout. Minecraft.stop() is the normal exit
	 *  path; the launcher then finds no controller result and records FAIL. */
	static void stop(Object minecraft) {
		try { minecraft.getClass().getMethod("stop").invoke(minecraft); }
		catch (Throwable unstoppable) {
			ForbricLog.error("[Forbric/ClientSoak] FATAL could not request a normal stop; halting the owned soak JVM", unstoppable);
			Runtime.getRuntime().halt(71);
		}
	}
}
