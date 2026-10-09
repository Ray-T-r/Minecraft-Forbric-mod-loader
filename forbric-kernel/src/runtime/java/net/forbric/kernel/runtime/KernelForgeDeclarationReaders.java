/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import net.forbric.api.DiscoveredMod;
import net.forbric.api.ModPresence;
import net.forbric.kernel.boot.CrossEcosystemDeclarations;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;

/**
 * MinecraftForge's {@code ModList} as a class that reads mods' declarations sees it — the twin of
 * {@link KernelDeclarationReaders}, whose javadoc has the reasoning. The differences are the family's own:
 * MinecraftForge's {@code ModList} is static, so the hooks take no receiver, its container list is
 * {@code getLoadedMods()}, and a declaring Fabric mod's container is MinecraftForge's own
 * {@code LowCodeModContainer} — the container it gives a mod with no {@code @Mod} class and no bus group. The guard is
 * the same: the native call outside it, the Fabric mods inside it, and on any failure the native answer unchanged plus
 * a {@code SUSPECTED} finding.
 */
public final class KernelForgeDeclarationReaders {
	private record Declarer(String id, String spelling, IModInfo info, ModContainer container) {
	}

	private static final Object LOCK = new Object();
	private static volatile List<DiscoveredMod> builtFrom;
	private static volatile List<Declarer> declarers = List.of();

	private KernelForgeDeclarationReaders() {
	}

	/** Replaces {@code ModList.getMods()}. */
	public static List<IModInfo> getMods() {
		List<IModInfo> mods = ModList.getMods();
		try {
			List<Declarer> extra = absent();
			if (extra.isEmpty()) return mods;
			List<IModInfo> all = new ArrayList<>(mods.size() + extra.size());
			all.addAll(mods);
			for (Declarer declarer : extra) all.add(declarer.info());
			return all;
		} catch (Throwable failure) {
			CrossEcosystemDeclarations.readerFailed("getMods", failure);
			return mods;
		}
	}

	/** Replaces {@code ModList.getLoadedMods()}. */
	public static List<ModContainer> getLoadedMods() {
		List<ModContainer> mods = ModList.getLoadedMods();
		try {
			List<Declarer> extra = absent();
			if (extra.isEmpty()) return mods;
			List<ModContainer> all = new ArrayList<>(mods.size() + extra.size());
			all.addAll(mods);
			for (Declarer declarer : extra) all.add(declarer.container());
			return all;
		} catch (Throwable failure) {
			CrossEcosystemDeclarations.readerFailed("getLoadedMods", failure);
			return mods;
		}
	}

	/** Replaces {@code ModList.getModContainerById(String)}. */
	public static Optional<? extends ModContainer> getModContainerById(String id) {
		Optional<? extends ModContainer> native_ = ModList.getModContainerById(id);
		if (native_.isPresent()) return native_;
		try {
			Declarer declarer = byId(id);
			return declarer == null ? native_ : Optional.of(declarer.container());
		} catch (Throwable failure) {
			CrossEcosystemDeclarations.readerFailed("getModContainerById", failure);
			return native_;
		}
	}

	/** Replaces {@code ModList.getModFileById(String)}. */
	public static IModFileInfo getModFileById(String id) {
		IModFileInfo native_ = ModList.getModFileById(id);
		if (native_ != null) return native_;
		try {
			if (nativelyAnswers(id)) return native_;
			Declarer declarer = byId(id);
			return declarer == null ? native_ : declarer.info().getOwningFile();
		} catch (Throwable failure) {
			CrossEcosystemDeclarations.readerFailed("getModFileById", failure);
			return native_;
		}
	}

	/** Replaces {@code ModList.forEachModContainer(BiConsumer)}; guarded as {@link KernelDeclarationReaders}'s is. */
	public static void forEachModContainer(BiConsumer<String, ModContainer> action) {
		ModList.forEachModContainer(action);
		for (Declarer declarer : absentOrNone("forEachModContainer")) {
			try {
				action.accept(declarer.id(), declarer.container());
			} catch (Throwable failure) {
				CrossEcosystemDeclarations.readerFailed("forEachModContainer", failure);
			}
		}
	}

	/** Replaces {@code ModList.forEachModInOrder(Consumer)}; guarded as {@link KernelDeclarationReaders}'s is. */
	public static void forEachModInOrder(Consumer<ModContainer> action) {
		ModList.forEachModInOrder(action);
		for (Declarer declarer : absentOrNone("forEachModInOrder")) {
			try {
				action.accept(declarer.container());
			} catch (Throwable failure) {
				CrossEcosystemDeclarations.readerFailed("forEachModInOrder", failure);
			}
		}
	}

	/** Replaces {@code ModList.applyForEachModContainer(Function)}; as lazy, and guarded, as the NeoForge twin. */
	public static <T> Stream<T> applyForEachModContainer(Function<ModContainer, T> function) {
		Stream<T> native_ = ModList.applyForEachModContainer(function);
		List<Declarer> extra = absentOrNone("applyForEachModContainer");
		if (extra.isEmpty()) return native_;
		return Stream.concat(native_, extra.stream().flatMap(declarer -> {
			try {
				return Stream.of(function.apply(declarer.container()));
			} catch (Throwable failure) {
				CrossEcosystemDeclarations.readerFailed("applyForEachModContainer", failure);
				return Stream.<T>empty();
			}
		}));
	}

	/** {@link #absent}, or none when that cannot be told — the reader then gets the native answer alone. */
	private static List<Declarer> absentOrNone(String hook) {
		try {
			return absent();
		} catch (Throwable failure) {
			CrossEcosystemDeclarations.readerFailed(hook, failure);
			return List.of();
		}
	}

	private static Declarer byId(String id) {
		if (id == null) return null;
		Declarer spelled = null;
		String spelling = ModPresence.spellingKey(id);
		for (Declarer declarer : declaringNow()) {
			if (declarer.id().equals(id)) return declarer;
			if (spelled == null && declarer.spelling().equals(spelling)) spelled = declarer;
		}
		return spelled;
	}

	private static List<Declarer> absent() {
		List<Declarer> now = declaringNow();
		if (now.isEmpty()) return now;
		List<Declarer> absent = new ArrayList<>(now.size());
		for (Declarer declarer : now) {
			if (!nativelyAnswers(declarer.id())) absent.add(declarer);
		}
		return absent;
	}

	/** As {@link KernelDeclarationReaders}: only the Fabric mods whose table holds something at this moment. */
	private static List<Declarer> declaringNow() {
		List<Declarer> all = all();
		if (all.isEmpty()) return all;
		List<Declarer> now = new ArrayList<>(all.size());
		for (Declarer declarer : all) {
			if (!declarer.info().getModProperties().isEmpty()) now.add(declarer);
		}
		return now;
	}

	/** As {@link KernelDeclarationReaders}: an unpublished index cannot answer by id; the mod infos still can. */
	private static boolean nativelyAnswers(String id) {
		try {
			return ModList.getModContainerById(id).isPresent();
		} catch (RuntimeException unindexed) {
			for (IModInfo info : ModList.getMods()) {
				if (id.equals(info.getModId())) return true;
			}
			return false;
		}
	}

	private static List<Declarer> all() {
		if (!CrossEcosystemDeclarations.enabled()) return List.of();
		List<DiscoveredMod> fabric = ModPresence.fabricMods();
		if (fabric != builtFrom) {
			synchronized (LOCK) {
				if (fabric != builtFrom) {
					declarers = build(fabric);
					builtFrom = fabric;
				}
			}
		}
		return declarers;
	}

	private static List<Declarer> build(List<DiscoveredMod> fabric) {
		List<Declarer> built = new ArrayList<>();
		for (DiscoveredMod mod : fabric) {
			String id = mod.getId();
			if (id == null || id.isBlank() || !CrossEcosystemDeclarations.mayDeclare(mod)) continue;
			ModContainer container = (ModContainer) KernelForgeContainers.lowCode(id, jarOf(mod));
			built.add(new Declarer(id, ModPresence.spellingKey(id), container.getModInfo(), container));
		}
		return List.copyOf(built);
	}

	private static Path jarOf(DiscoveredMod mod) {
		if (mod.getSource() == null) return null;
		try {
			Path path = Path.of(mod.getSource());
			return Files.isRegularFile(path) ? path : null;
		} catch (RuntimeException notAPath) {
			return null;
		}
	}
}
