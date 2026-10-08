/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.runtime;

import java.util.Optional;
import java.util.function.BiFunction;

import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;
import net.forbric.kernel.util.ForbricLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Config-screen dispatcher for native loader contracts and discoverable optional protocol adapters. */
public final class KernelModConfigScreens {
	private KernelModConfigScreens() {
	}

	/** Mods whose config screen already threw on a press, so the warning is said once and not on every click. */
	private static final java.util.Set<String> PRESS_FAILURES = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** Whether {@link #open} would produce a screen. Cheap enough to ask per selection change. */
	public static boolean has(ModCatalog.Entry entry) {
		return entry != null && open(entry, null, true) != null;
	}

	/** The mod's config screen, or {@code null} if its ecosystem has no factory registered for it. */
	public static Screen open(ModCatalog.Entry entry, Screen parent) {
		return open(entry, parent, false);
	}

	/**
	 * @param probe when true, nothing is constructed that would be thrown away — the family is asked only whether
	 *              a factory EXISTS. A probe that built the screen would run a mod's screen constructor on every
	 *              selection change, which is a mod's code running because the player moved the highlight.
	 */
	private static Screen open(ModCatalog.Entry entry, Screen parent, boolean probe) {
		if (entry == null) return null;
		try {
			Screen nativeScreen = switch (entry.ecosystem()) {
				case FABRIC -> null;
				case NEOFORGE -> Neo.resolve(entry.modId(), parent, probe);
				case FORGE -> Forge.resolve(entry.modId(), parent, probe);
			};
			return nativeScreen != null ? nativeScreen : extension(entry, parent, probe);
		} catch (Throwable t) {
			// A family that is not present, or a mod whose factory throws. Neither may cost the screen the
			// player is standing in. A probe failing is routine and stays at debug; a press that fails is the player
			// clicking Config and seeing nothing, so it says which mod's factory threw, once per mod.
			if (probe || !PRESS_FAILURES.add(entry.modId())) {
				ForbricLog.debug("[Forbric/ModConfig] no config screen for %s (%s): %s", entry.modId(),
						entry.ecosystem(), String.valueOf(t));
			} else {
				ForbricLog.warn("[Forbric/ModConfig] %s (%s) has a Config button, but its config screen threw when it "
						+ "was opened: %s", entry.modId(), entry.ecosystem(), String.valueOf(t));
			}
			return null;
		}
	}

	/**
	 * How many mods of each ecosystem have a config screen, and the first one that does.
	 *
	 * <p>Exists for the client smoke. The whole claim of this class is that it asks THREE registries, and the
	 * only way to see that is a count per family: a Fabric-only count would be indistinguishable from Mod Menu's
	 * own answer, which is the thing this replaces.
	 */
	public static String summary() {
		return probe(Ecosystem.FABRIC) + " Fabric, " + probe(Ecosystem.NEOFORGE) + " NeoForge, "
				+ probe(Ecosystem.FORGE) + " MinecraftForge";
	}

	private static int probe(Ecosystem ecosystem) {
		int n = 0;
		for (ModCatalog.Entry entry : ModCatalog.all()) {
			if (entry.ecosystem() == ecosystem && has(entry)) n++;
		}
		return n;
	}

	/** The first mod of {@code ecosystem} with a config screen, or {@code null}. For the smoke, and for it only. */
	public static ModCatalog.Entry firstWithConfig(String ecosystem) {
		for (ModCatalog.Entry entry : ModCatalog.all()) {
			if (entry.ecosystem().name().equalsIgnoreCase(ecosystem) && has(entry)) return entry;
		}
		return null;
	}

	/**
	 * Opens {@code modId}'s config screen over {@code parent}. For the client smoke.
	 *
	 * <p>{@code Object} rather than {@code Screen} in both positions so the boot side can name this method in
	 * {@code KernelRuntimeClasses} — that registry is what turns a renamed game-side method into one line at
	 * startup instead of a {@code NoSuchMethodException} in the middle of a test run.
	 */
	public static Object openById(String modId, Object parent) {
		for (ModCatalog.Entry entry : ModCatalog.all()) {
			if (entry.modId().equals(modId)) return open(entry, (Screen) parent);
		}
		return null;
	}

	/** A stand-in the probe can return: non-null means "yes", and it is never shown. */
	private static final Screen PRESENT = new Screen(net.minecraft.network.chat.Component.empty()) {
	};

	private static Screen extension(ModCatalog.Entry entry, Screen parent, boolean probe) {
        var factory = net.forbric.api.ProtocolExtensions.forLoader(KernelModConfigScreens.class.getClassLoader())
            .configurationScreen(entry);
        return factory == null ? null : probe ? PRESENT : (Screen) factory.open(parent);
    }

	/** NeoForge's answer: an extension point on the mod's own container, exactly as its own Mods screen reads it. */
	private static final class Neo {
		static Screen resolve(String modId, Screen parent, boolean probe) {
			Optional<? extends net.neoforged.fml.ModContainer> container =
					net.neoforged.fml.ModList.get().getModContainerById(modId);
			if (container.isEmpty()) return null;
			Optional<net.neoforged.neoforge.client.gui.IConfigScreenFactory> factory =
					net.neoforged.neoforge.client.gui.IConfigScreenFactory.getForMod(container.get().getModInfo());
			if (factory.isEmpty()) return null;
			return probe ? PRESENT : factory.get().createScreen(container.get(), parent);
		}
	}

	/** Traditional MinecraftForge's answer: a different registry, a different shape, the same question. */
	private static final class Forge {
		static Screen resolve(String modId, Screen parent, boolean probe) {
			// Static, with no get(): the two families' ModList classes share a name and not much else.
			Optional<? extends net.minecraftforge.fml.ModContainer> container =
					net.minecraftforge.fml.ModList.getModContainerById(modId);
			if (container.isEmpty()) return null;
			Optional<BiFunction<Minecraft, Screen, Screen>> factory =
					net.minecraftforge.client.ConfigScreenHandler.getScreenFactoryFor(
							container.get().getModInfo());
			if (factory.isEmpty()) return null;
			return probe ? PRESENT : factory.get().apply(Minecraft.getInstance(), parent);
		}
	}
}
