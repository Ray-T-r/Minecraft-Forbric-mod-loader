package net.neoforged.fml;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Stand-in: the loaded mods, in their order, in the three fields the kernel writes when it publishes NeoForge's baseline. */
public class ModList {
	private static final ModList INSTANCE = new ModList();
	public List<ModContainer> mods = new ArrayList<>();
	public List<ModContainer> sortedContainers = mods;
	public Map<String, ModContainer> indexedMods = new HashMap<>();

	public static ModList get() {
		return INSTANCE;
	}

	public List<ModContainer> getSortedMods() {
		return sortedContainers;
	}

	public int size() {
		return sortedContainers.size();
	}
}
