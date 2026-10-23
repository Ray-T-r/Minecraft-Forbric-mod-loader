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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;
import net.forbric.kernel.ui.ModListRows;
import net.forbric.kernel.util.Caught;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Config-screen dispatcher for native loader contracts and discoverable optional protocol adapters. */
public final class KernelModConfigScreens {
	/**
	 * The game's id, in every loader's registry. No jar is the game, so the catalogue never has it; its row on the Mods
	 * screen is a platform row, and its config screen is its own options screen.
	 */
	public static final String GAME = "minecraft";

	private KernelModConfigScreens() {
	}

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
			Screen nativeScreen = nativeScreen(entry, parent, probe);
			if (nativeScreen != null) return nativeScreen;
			Screen offered = extension(entry, parent, probe);
			if (offered != null) return offered;
			// The game's settings are its options screen, on every loader. Mod Menu says so through a factory of its
			// own for this id, which is what answers above when it is installed; without it no registry has one, and
			// the game's row would be the one row whose Config button nothing could ever show.
			return GAME.equals(entry.modId()) ? gameOptions(parent, probe) : null;
		} catch (Throwable t) {
			// A family that is not present, or a mod whose factory throws. Neither may cost the screen the
			// player is standing in. A probe failing is routine and stays at debug; a press that fails is the player
			// clicking Config and seeing nothing, so it says which mod's factory threw.
			if (probe) {
				Caught.expected(t);
				return null;
			}
			Caught.warn(t, "[Forbric/ModConfig] %s (%s) has a Config button, but its config screen threw when it was "
					+ "opened", entry.modId(), entry.ecosystem());
			return null;
		}
	}

	/** The family's own registry's answer, without asking any protocol adapter: null for a Fabric mod. */
	private static Screen nativeScreen(ModCatalog.Entry entry, Screen parent, boolean probe) {
		return switch (entry.ecosystem()) {
			case FABRIC -> null;
			case NEOFORGE -> Neo.resolve(entry.modId(), parent, probe);
			case FORGE -> Forge.resolve(entry.modId(), parent, probe);
		};
	}

	private static Screen gameOptions(Screen parent, boolean probe) {
		if (probe) return PRESENT;
		Minecraft minecraft = Minecraft.getInstance();
		// false: not reached from a world's pause menu -- what Mod Menu passes for the same row.
		return new net.minecraft.client.gui.screens.options.OptionsScreen(parent, minecraft.options, false);
	}

	/**
	 * Every row the Mods screen lists, in its order (see {@link ModListRows}): the installed mods, every mod a jar
	 * carries that has a config screen of its own, and the platform entries with one -- the game, and anything else a
	 * loader's registry holds that no jar is. Asks each carried mod whether it has a config screen, so the screen
	 * calls this once when it opens, not on every keystroke in its search box.
	 */
	public static List<ModListRows.Row> rows() {
		List<ModCatalog.Entry> everything = ModCatalog.everything();
		Set<String> catalogued = new HashSet<>();
		for (ModCatalog.Entry e : everything) catalogued.add(e.modId());
		return ModListRows.plan(everything, platform(catalogued), KernelModConfigScreens::has,
				KernelModConfigScreens::declaredParent);
	}

	/** Whether pressing Config on {@code row} would produce a screen. A group heading has none of its own. */
	public static boolean has(ModListRows.Row row) {
		return row != null && row.entry() != null && has(row.entry());
	}

	/** {@code row}'s config screen over {@code parent}, or null. */
	public static Screen open(ModListRows.Row row, Screen parent) {
		return row == null || row.entry() == null ? null : open(row.entry(), parent);
	}

	/**
	 * The row listing {@code modId}, or null when the Mods screen has none. For the client smoke, which finds a row the
	 * way a player does: by the name it shows.
	 */
	public static ModListRows.Row listedRow(String modId) {
		for (ModListRows.Row row : rows()) {
			if (row.id().equals(modId)) return row;
		}
		return null;
	}

	/**
	 * Ids a loader's registry holds that no jar is, each as an entry its own family is asked about -- only those with
	 * a config screen. The game is always one: its options screen is always there. The rest are found, not named: a
	 * container in NeoForge's or MinecraftForge's list, or a mod in Fabric's, whose id the catalogue does not have. On
	 * NeoForge that is NeoForge itself, which registers a config screen for its own settings; its own Mods screen
	 * shows it, and this one is that screen's replacement. Whether such a loader's own entry becomes a row is not
	 * decided here: {@link ModListRows} lists it only when the catalogue holds a mod that loader serves (NeoForge's,
	 * when the instance has a NeoForge or MinecraftForge mod).
	 */
	private static List<ModListRows.Platform> platform(Set<String> catalogued) {
		List<ModListRows.Platform> out = new ArrayList<>();
		Set<String> taken = new HashSet<>(catalogued);
		taken.add(GAME);
		out.add(new ModListRows.Platform(new ModCatalog.Entry(Ecosystem.FABRIC, GAME, "Minecraft", gameVersion(),
				"The game's own options.", List.of(), "", "", ""), null));
		try {
			Neo.platform(taken, out);
		} catch (Throwable absent) {
			Caught.expected(absent);
		}
		try {
			Forge.platform(taken, out);
		} catch (Throwable absent) {
			Caught.expected(absent);
		}
		try {
			for (net.fabricmc.loader.api.ModContainer mod : FabricLoader.getInstance().getAllMods()) {
				var meta = mod.getMetadata();
				offer(taken, out, new ModCatalog.Entry(Ecosystem.FABRIC, meta.getId(), meta.getName(),
						meta.getVersion().getFriendlyString(), meta.getDescription(), List.of(), "", "", ""));
			}
		} catch (Throwable absent) {
			Caught.expected(absent);
		}
		return out;
	}

	/** Adds {@code entry} as a platform row when its id is not taken and its family has a config screen for it. */
	private static void offer(Set<String> taken, List<ModListRows.Platform> out, ModCatalog.Entry entry) {
		if (entry.modId() == null || taken.contains(entry.modId()) || !has(entry)) return;
		taken.add(entry.modId());
		out.add(new ModListRows.Platform(entry, entry.ecosystem()));
	}

	/** The game's version as a player reads it, e.g. {@code 26.2}. */
	private static String gameVersion() {
		try {
			return net.minecraft.SharedConstants.getCurrentVersion().name();
		} catch (Throwable unknown) {
			Caught.expected(unknown);
			return "";
		}
	}

	/**
	 * The parent {@code modId} names for itself, read the way Mod Menu reads it: Fabric's metadata for that id, its
	 * {@code custom.modmenu.parent}, either an id or an object with an id, a name and a description. Null when it names
	 * none or Fabric has no such mod.
	 */
	static ModListRows.DeclaredParent declaredParent(String modId) {
		CustomValue modMenu;
		try {
			var container = FabricLoader.getInstance().getModContainer(modId);
			if (container.isEmpty()) return null;
			modMenu = container.get().getMetadata().getCustomValue("modmenu");
		} catch (RuntimeException noFabric) {
			Caught.expected(noFabric);
			return null;
		}
		if (modMenu == null || modMenu.getType() != CustomValue.CvType.OBJECT) return null;
		CustomValue parent = modMenu.getAsObject().get("parent");
		if (parent == null) return null;
		if (parent.getType() == CustomValue.CvType.STRING) return new ModListRows.DeclaredParent(parent.getAsString(), "", "");
		if (parent.getType() != CustomValue.CvType.OBJECT) return null;
		CustomValue.CvObject object = parent.getAsObject();
		return new ModListRows.DeclaredParent(string(object, "id"), string(object, "name"), string(object, "description"));
	}

	private static String string(CustomValue.CvObject object, String key) {
		CustomValue value = object.get(key);
		return value != null && value.getType() == CustomValue.CvType.STRING ? value.getAsString() : "";
	}

	/**
	 * The mods Fabric knows only by their id while another family loads them, whose own family has a config screen for
	 * them: each id with a factory that opens it. What the kernel's Mod Menu plugin hands Mod Menu.
	 *
	 * <p>On Mod Menu's list such a mod is a row like any Fabric mod -- Fabric's registry has it, as a presence identity
	 * the kernel registers so that {@code isModLoaded} tells the truth -- but Mod Menu can only find a config screen in
	 * a {@code "modmenu"} entrypoint, and the mod's Fabric build, which declared one, is not the build that runs. A
	 * universal jar arbitrated to NeoForge loses its Mod Menu entrypoint this way, and so does every NeoForge or
	 * MinecraftForge mod, which never had one. The screen they have is their own family's, asked here exactly as this
	 * class's own Config button asks it, and nothing but that family's registry is asked: Mod Menu is what is asking.
	 *
	 * <p>Decided by shape alone: the catalogue says a family other than Fabric loaded the id, and Fabric's registry
	 * holds the same id.
	 */
	public static Map<String, Function<Screen, Screen>> foreignScreensForFabric() {
		Map<String, Function<Screen, Screen>> out = new LinkedHashMap<>();
		FabricLoader fabric;
		try {
			fabric = FabricLoader.getInstance();
		} catch (RuntimeException noFabric) {
			Caught.expected(noFabric);
			return out;
		}
		for (ModCatalog.Entry entry : ModCatalog.everything()) {
			if (entry.ecosystem() == Ecosystem.FABRIC || fabric.getModContainer(entry.modId()).isEmpty()) continue;
			try {
				if (nativeScreen(entry, null, true) == null) continue;
			} catch (Throwable unreadable) {
				Caught.expected(unreadable);
				continue;
			}
			out.put(entry.modId(), parent -> {
				try {
					return nativeScreen(entry, parent, false);
				} catch (RuntimeException | LinkageError thrown) {
					Caught.warn(thrown, "[Forbric/ModConfig] %s (%s) has a Config button in Mod Menu, but its config screen "
							+ "threw when it was opened", entry.modId(), entry.ecosystem());
					return null;
				}
			});
		}
		return out;
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
		// Whatever the Mods screen lists, not only what was installed: a carried mod with a config, and the game.
		ModListRows.Row row = listedRow(modId);
		return row == null ? null : open(row, (Screen) parent);
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
		/** Every container in NeoForge's list whose id is not taken, offered as a platform row (see {@link #platform}). */
		static void platform(Set<String> taken, List<ModListRows.Platform> out) {
			net.neoforged.fml.ModList list = net.neoforged.fml.ModList.get();
			if (list == null) return;
			list.forEachModContainer((id, container) -> {
				var info = container.getModInfo();
				offer(taken, out, new ModCatalog.Entry(Ecosystem.NEOFORGE, id, info.getDisplayName(),
						String.valueOf(info.getVersion()), info.getDescription(), List.of(), "", "", ""));
			});
		}

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
		/** Every container in MinecraftForge's list whose id is not taken, offered as a platform row. */
		static void platform(Set<String> taken, List<ModListRows.Platform> out) {
			net.minecraftforge.fml.ModList.forEachModContainer((id, container) -> {
				var info = container.getModInfo();
				offer(taken, out, new ModCatalog.Entry(Ecosystem.FORGE, id, info.getDisplayName(),
						String.valueOf(info.getVersion()), info.getDescription(), List.of(), "", "", ""));
			});
		}

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
