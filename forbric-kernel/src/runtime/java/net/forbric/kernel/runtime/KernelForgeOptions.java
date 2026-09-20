package net.forbric.kernel.runtime;

import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.KeyMapping;

/** Forge's pending-key preservation, using the merged writer's existing known-key output. */
public final class KernelForgeOptions {
	private KernelForgeOptions() { }
	public static void writePendingKeys(Map<String, String> pending, KeyMapping[] mappings, PrintWriter writer) {
		Set<String> written = new HashSet<>();
		for (KeyMapping mapping : mappings) written.add("key_" + mapping.getName());
		for (var entry : pending.entrySet()) {
			if (!written.contains(entry.getKey())) writer.println(entry.getKey() + ":" + entry.getValue());
		}
	}
}
