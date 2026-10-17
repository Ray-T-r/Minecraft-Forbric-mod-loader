/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.lang.reflect.*;
import java.util.*;
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;

/** Fires Forge's own declaration event and carries its custom codecs to the loader's active NeoForge list. */
final class ForgeDatapackDeclarations {
	static final String PROPERTY = "forbric.forgeDatapackRegistries";
	private ForgeDatapackDeclarations() { }

	static void declare(ClassLoader loader, Class<?> neoHooks) throws ReflectiveOperationException {
		if ("off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"))) return;
		Class<?> eventClass;
		try { eventClass = Class.forName(ForeignType.DATAPACK_NEW_REGISTRY_EVENT.binary(Ecosystem.FORGE), true, loader); }
		catch (ClassNotFoundException absent) {
			Caught.expected(absent);
			return;
		}
		Object event = eventClass.getConstructor().newInstance();
		Object bus = eventClass.getField("BUS").get(null);
		Class.forName(ForeignType.EVENT_BUS.binary(Ecosystem.FORGE), false, loader)
				.getMethod("post", Class.forName(ForeignType.EVENT.binary(Ecosystem.FORGE), false, loader)).invoke(bus, event);
		Method process = eventClass.getDeclaredMethod("process"); process.setAccessible(true); process.invoke(event);
		Field list = eventClass.getDeclaredField("registryDataList"); list.setAccessible(true);
		Class<?> forgeData = Class.forName(ForeignType.DATAPACK_REGISTRY_DATA.binary(Ecosystem.FORGE), false, loader);
		Method loaderData = forgeData.getMethod("loaderData"), networkCodec = forgeData.getMethod("networkCodec");
		Class<?> dataClass = Class.forName("net.minecraft.resources.RegistryDataLoader$RegistryData", false, loader);
		Method key = dataClass.getMethod("key");
		Class<?> neoData = Class.forName(ForeignType.DATAPACK_REGISTRY_DATA.binary(Ecosystem.NEOFORGE), false, loader);
		Constructor<?> wrap = neoData.getDeclaredConstructor(dataClass, Class.forName("com.mojang.serialization.Codec", false, loader));
		wrap.setAccessible(true);
		Method add = neoHooks.getDeclaredMethod("addRegistryCodec", neoData); add.setAccessible(true);
		Set<Object> existing = new HashSet<>();
		for (Object data : (List<?>)neoHooks.getMethod("getDataPackRegistries").invoke(null)) existing.add(key.invoke(data));
		List<String> declared = new ArrayList<>();
		for (Object entry : (List<?>)list.get(event)) {
			Object data = loaderData.invoke(entry), registryKey = key.invoke(data);
			// The existing modifier bridge adds lenient codecs for these two baseline registries.
			// Preserve that contract; native Forge still received its declaration event and retains its list.
			String id = registryKey.toString();
			if (id.contains(" / forge:biome_modifier]") || id.contains(" / forge:structure_modifier]")) continue;
			if (!existing.add(registryKey)) continue;
			add.invoke(null, wrap.newInstance(data, networkCodec.invoke(entry)));
			declared.add(id);
		}
		ForbricLog.info("[Forbric/DatapackRegistries] fired MinecraftForge's declaration event and mirrored %d "
				+ "custom registry codec(s) into the active loader list: %s", declared.size(), declared);
	}
}
