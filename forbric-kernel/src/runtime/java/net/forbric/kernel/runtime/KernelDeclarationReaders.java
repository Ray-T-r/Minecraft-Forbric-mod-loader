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
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;

/**
 * NeoForge's {@code ModList} as a class that reads mods' declarations sees it: with the Fabric mods that declare
 * something in it too.
 *
 * <p>Reached only from a class {@code DeclarationReaderModListInjector} rewrote — one that reads
 * {@code IModInfo.getModProperties()} — and only for the {@code ModList} calls it makes. Each method answers exactly
 * what the native call answers and then, after it, the Fabric mods whose {@code fabric.mod.json} declares something
 * the other family spells as a property (see {@code CrossEcosystemDeclarations}). A Fabric mod that declares nothing
 * is not added: a reader looking for a key it does not carry would only skip it. A mod {@code ModList} already
 * answers for — an id a NeoForge jar owns, or a presence alias — is never added a second time.
 *
 * <p><b>The containers.</b> {@code ModPresence} declines to invent a {@code ModContainer} for a Fabric mod, because a
 * caller asking for one by id may want its event bus or its configs. A reader that has just enumerated a Fabric mod
 * out of this list and then looks it up by id — Sodium does exactly that for the name and version on the options
 * page — must find the same mod, or the list contradicts itself. So inside such a class, and nowhere else, each
 * declaring Fabric mod has a container: the kernel's own {@link KernelModContainer} with no event bus, the shape
 * NeoForge itself gives a mod without one ({@code getEventBus()} is null and {@code acceptEvent} returns at once).
 * Its {@code IModInfo} is a {@link KernelModInfo} describing the mod from what discovery read out of its own jar.
 *
 * <p>Everything here is a view: nothing is written into {@code ModList}, so no other class, and no handshake, ever
 * sees a Fabric mod in it.
 *
 * <p><b>Never a new failure.</b> A reader is typically a library's startup code — a config loader running inside
 * {@code Minecraft.<init>} with no handler around its walk — so anything thrown here would be a startup crash that
 * the native call could never have caused. Each hook therefore makes the native call first, outside any guard (what it
 * throws is the reader's own business, exactly as without this class), and adds the Fabric mods inside one: if that
 * part throws, the reader gets the native answer unchanged and the failure is a {@code SUSPECTED} finding naming it
 * ({@code CrossEcosystemDeclarations.readerFailed}). The same holds for the reader's own callback applied to a Fabric
 * mod's container: natively it never ran on one.
 */
public final class KernelDeclarationReaders {
	/** One declaring Fabric mod, built once per published Fabric list so a reader always meets the same objects. */
	private record Declarer(String id, String spelling, IModInfo info, ModContainer container) {
	}

	private static final Object LOCK = new Object();
	private static volatile List<DiscoveredMod> builtFrom;
	private static volatile List<Declarer> declarers = List.of();

	private KernelDeclarationReaders() {
	}

	/** Replaces {@code ModList.getMods()}. */
	public static List<IModInfo> getMods(ModList list) {
		List<IModInfo> mods = list.getMods();
		try {
			List<Declarer> extra = absentFrom(list);
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

	/** Replaces {@code ModList.getSortedMods()}. */
	public static List<ModContainer> getSortedMods(ModList list) {
		List<ModContainer> mods = list.getSortedMods();
		try {
			List<Declarer> extra = absentFrom(list);
			if (extra.isEmpty()) return mods;
			List<ModContainer> all = new ArrayList<>(mods.size() + extra.size());
			all.addAll(mods);
			for (Declarer declarer : extra) all.add(declarer.container());
			return all;
		} catch (Throwable failure) {
			CrossEcosystemDeclarations.readerFailed("getSortedMods", failure);
			return mods;
		}
	}

	/** Replaces {@code ModList.getModContainerById(String)}. */
	public static Optional<? extends ModContainer> getModContainerById(ModList list, String id) {
		Optional<? extends ModContainer> native_ = list.getModContainerById(id);
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
	public static IModFileInfo getModFileById(ModList list, String id) {
		IModFileInfo native_ = list.getModFileById(id);
		if (native_ != null) return native_;
		try {
			if (nativelyAnswers(list, id)) return native_;
			Declarer declarer = byId(id);
			return declarer == null ? native_ : declarer.info().getOwningFile();
		} catch (Throwable failure) {
			CrossEcosystemDeclarations.readerFailed("getModFileById", failure);
			return native_;
		}
	}

	/**
	 * Replaces {@code ModList.forEachModContainer(BiConsumer)}. The reader's action runs on each declaring Fabric mod
	 * inside the same guard: natively it never ran on one, so a Fabric mod it cannot handle is skipped, not thrown.
	 */
	public static void forEachModContainer(ModList list, BiConsumer<String, ModContainer> action) {
		list.forEachModContainer(action);
		for (Declarer declarer : absentOrNone(list, "forEachModContainer")) {
			try {
				action.accept(declarer.id(), declarer.container());
			} catch (Throwable failure) {
				CrossEcosystemDeclarations.readerFailed("forEachModContainer", failure);
			}
		}
	}

	/** Replaces {@code ModList.forEachModInOrder(Consumer)}; guarded as {@link #forEachModContainer} is. */
	public static void forEachModInOrder(ModList list, Consumer<ModContainer> action) {
		list.forEachModInOrder(action);
		for (Declarer declarer : absentOrNone(list, "forEachModInOrder")) {
			try {
				action.accept(declarer.container());
			} catch (Throwable failure) {
				CrossEcosystemDeclarations.readerFailed("forEachModInOrder", failure);
			}
		}
	}

	/**
	 * Replaces {@code ModList.applyForEachModContainer(Function)}. As lazy as the native stream: the reader's function
	 * reaches a declaring Fabric mod when the stream does, and a Fabric mod it cannot handle is left out, not thrown.
	 */
	public static <T> Stream<T> applyForEachModContainer(ModList list, Function<ModContainer, T> function) {
		Stream<T> native_ = list.applyForEachModContainer(function);
		List<Declarer> extra = absentOrNone(list, "applyForEachModContainer");
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

	/** {@link #absentFrom}, or none when that cannot be told — the reader then gets the native answer alone. */
	private static List<Declarer> absentOrNone(ModList list, String hook) {
		try {
			return absentFrom(list);
		} catch (Throwable failure) {
			CrossEcosystemDeclarations.readerFailed(hook, failure);
			return List.of();
		}
	}

	/**
	 * The declaring Fabric mod a reader asked for by id — as written, or with NeoForge's spelling of it: a NeoForge id
	 * cannot contain {@code -}, so a NeoForge-side reader may only be able to ask for {@code cloth_config}.
	 */
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

	/** The declarers {@code list} does not already answer for, in Fabric's order. */
	private static List<Declarer> absentFrom(ModList list) {
		List<Declarer> now = declaringNow();
		if (now.isEmpty()) return now;
		List<Declarer> absent = new ArrayList<>(now.size());
		for (Declarer declarer : now) {
			if (!nativelyAnswers(list, declarer.id())) absent.add(declarer);
		}
		return absent;
	}

	/**
	 * The Fabric mods whose table holds something at this moment. A mod whose only declarations are entrypoint names
	 * under keys no reader has asked for by name declares nothing yet (see {@code CrossEcosystemDeclarations}).
	 */
	private static List<Declarer> declaringNow() {
		List<Declarer> all = all();
		if (all.isEmpty()) return all;
		List<Declarer> now = new ArrayList<>(all.size());
		for (Declarer declarer : all) {
			if (!declarer.info().getModProperties().isEmpty()) now.add(declarer);
		}
		return now;
	}

	/**
	 * Whether {@code list} itself answers for {@code id}. A list nobody has published containers into yet throws on a
	 * by-id lookup (its index is still null); its mod infos can still say, and a reader whose own call would have
	 * worked must not fail on this one.
	 */
	private static boolean nativelyAnswers(ModList list, String id) {
		try {
			return list.getModContainerById(id).isPresent();
		} catch (RuntimeException unindexed) {
			for (IModInfo info : list.getMods()) {
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
			KernelModInfo info = new KernelModInfo(id, jarOf(mod), mod);
			built.add(new Declarer(id, ModPresence.spellingKey(id), info, new KernelModContainer(info, null)));
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
