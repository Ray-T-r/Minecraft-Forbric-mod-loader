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

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.kernel.discovery.ForbricModDiscoverer;
import net.forbric.kernel.discovery.ModFileScanner;
import net.forbric.kernel.metadata.forge.ForgeModEntry;
import net.forbric.kernel.metadata.forge.ModsTomlParser;
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;
import net.neoforged.fml.jarcontents.JarContents;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.ModFileScanData;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

/**
 * The {@code IModFile} behind a kernel-constructed mod: its real jar when it has one, a placeholder when it does
 * not.
 *
 * <h2>What this class made visible</h2>
 *
 * <p>It replaces a {@link java.lang.reflect.Proxy} that switched on method NAMES and answered everything it did
 * not name through a {@code defaultReturn} — null for objects, false for booleans, empty for collections. Four
 * of this interface's ten methods were named; the other six were answered by that fallback, and nothing anywhere
 * said so. Written as a class, javac lists all ten and each one is now a line with a reason next to it.
 *
 * <p>The class was first written answering every one of those the way the proxy had, deliberately, so that the
 * rewrite could not move behaviour — and it recorded that four of them were almost certainly wrong. This is that
 * separate change: {@link #getId()}, {@link #getFileName()}, {@link #getType()} and {@link #getModFileInfo()}
 * now answer with the values a real mod file has. Each was null, and null is not a value any consumer expects
 * from them: a mod filtering the file list by type dropped every kernel-loaded mod, and a mod walking from a
 * file back to its info — the direction NeoForge's own error path takes — dereferenced null.
 *
 * <h2>One per jar, behind a switch</h2>
 *
 * <p>FML builds ONE {@code ModFile} per jar, however many {@code [[mods]]} it declares, and every one of those mods'
 * {@code getOwningFile().getFile()} is that object. The carrier's own bytecode says what that object answers
 * ({@code javap} on {@code ModFile} and {@code ModFileInfo} in {@code neoforge-runtime.jar}, 26.2.0.93):
 * {@code ModFileInfo}'s constructor reads {@code getConfigList("mods").stream().map(ModInfo::new).toList()}, so its
 * {@code getMods()} is every {@code [[mods]]} entry of the jar's {@code neoforge.mods.toml}, in file order; the
 * {@code ModFile} constructor then sets {@code id = modFileInfo.getMods().getFirst().getModId()}, so the file's id is
 * the FIRST entry, whichever mod asks; and {@code getModInfos()} is {@code modFileInfo.getMods()}. There is one file
 * info per jar too, so two mods of one jar have the same {@code getOwningFile()}.
 *
 * <p>By default the kernel builds one file per MOD, and the place that shows is {@code ModList.getAllScanData()},
 * which natively streams {@code sortedList → getOwningFile → getFile → distinct → getScanResult} — the
 * {@code distinct()} is by identity, and two file objects for one jar are two entries. The NeoForge canary has a
 * second {@code @Mod} in its jar, and the index lists that jar twice (gate-m21 prints "2 file(s)" with one jar
 * staged), so a mod that walks the index — JEI collecting {@code @JeiPlugin}s, Jade its providers — meets every class
 * of such a jar twice. The two entries are the same index object ({@code ModFileScanner.scanShared}), so nothing is
 * scanned twice; the walk is.
 *
 * <p>{@code -Dforbric.modFilePerJar=on} gives the native shape: {@link #sharedFor} hands every NeoForge declaration
 * of a jar the same file, which owns one {@link KernelModFileInfo} listing all of them in {@code [[mods]]} order and
 * is named after the first. Only NeoForge's: a universal jar whose Fabric half the kernel also describes as a mod
 * ({@code KernelDeclarationReaders}) keeps that Fabric mod on a file of its own — natively no NeoForge file lists a
 * Fabric mod, and the walk up from a NeoForge mod must not land on one. The switch is OFF in Stage 2 of the
 * MinecraftForge removal, which must not change behaviour on the merged base, and what a mod walking the index meets
 * changes with it; the genuine-base stage turns it on (stage2-measurements.md, Stage 4 obligations).
 */
public final class KernelModFile implements IModFile {
	/**
	 * Every shared jar's file, by the real path {@code ModFileScanner} keys its shared index on, so a symlink or
	 * {@code /tmp} for {@code /private/tmp} cannot make one jar two files. Filled only with the switch on, and never
	 * with a jar-less presence alias, which has no file to share.
	 */
	private static final Map<Path, KernelModFile> BY_JAR = new ConcurrentHashMap<>();

	/** The switch {@link #sharedFor} is behind; off unless set to {@code on}. */
	static final String PER_JAR_SWITCH = "forbric.modFilePerJar";

	private static final AtomicBoolean ANNOUNCED = new AtomicBoolean();

	/**
	 * Whether a NeoForge declaration with a jar gets that jar's one shared file. Off by default: a file per mod is what
	 * the merged base has always had, and Stage 2 keeps it. {@code -Dforbric.modFilePerJar=on} is the native shape,
	 * which gate-m21 measures in a boot of its own.
	 */
	static boolean perJar() {
		return "on".equalsIgnoreCase(System.getProperty(PER_JAR_SWITCH, "off"));
	}

	/**
	 * The jar's one shared file, built for the first declaration that asks. {@link KernelModFileInfo#forMod} is the
	 * only caller, and it has already checked the switch and that the declaration is NeoForge's.
	 */
	static KernelModFile sharedFor(String modId, Path jar) {
		if (ANNOUNCED.compareAndSet(false, true)) {
			ForbricLog.info("[Forbric/Container] every NeoForge mod of one jar now shares that jar's IModFile and file "
					+ "info, as FML builds them (-D%s=on)", PER_JAR_SWITCH);
		}
		return BY_JAR.computeIfAbsent(ModFileScanner.cacheKey(jar), key -> new KernelModFile(modId, jar, true));
	}

	private final String modId;
	private final Path path;
	private final JarContents contents;
	private final Path jar;

	/** Whether this is a jar's one shared file ({@link #sharedFor}) rather than one mod's own. */
	private final boolean shared;

	/**
	 * A shared file's mods, by id, as one-slot holders: each {@link KernelModInfo} hands its slot over before filling
	 * it in (it and its file info refer to each other), so an empty slot is a mod still inside its constructor and is
	 * skipped. In join order; {@link #mods} sorts by the manifest.
	 */
	private final Map<String, IModInfo[]> members = new LinkedHashMap<>();

	/** The {@code [[mods]]} ids of the jar's {@code neoforge.mods.toml}, in file order; read once, when first asked. */
	private List<String> declaredOrder;

	/**
	 * The info that owns this file. Set by {@link KernelModFileInfo}, which is what asks for this object: the two
	 * refer to each other, so one of them has to be filled in second. A shared file has the one file info of its
	 * jar, which every mod of the jar also answers as its {@code getOwningFile()}.
	 */
	private IModFileInfo modFileInfo;

	/** Memoised: most instances are never asked, and walking a hundred jars for nobody is pure boot cost. */
	private ModFileScanData scanResult;

	/**
	 * @param jar the mod's real jar, or null for a presence alias which has none. A mod that reads files out of
	 *            its own jar through {@code getModInfo().getOwningFile().getFile().getContents()} gets nothing
	 *            without it: Tectonic builds its bundled datapack that way, and against a null it produced a null
	 *            Pack, after which {@code PackRepository.discoverAvailable} died on "Cannot invoke
	 *            Pack.streamSelfAndChildren() because pack is null" and the world would not load.
	 */
	public KernelModFile(String modId, Path jar) {
		this(modId, jar, false);
	}

	private KernelModFile(String modId, Path jar, boolean shared) {
		this.modId = modId;
		this.jar = jar;
		this.shared = shared;
		this.path = jar != null ? jar : Path.of("forbric-kernel", modId + ".jar");
		// Native visitors enumerate every published identity, including cross-ecosystem aliases. An alias
		// contributes no resources, but is still a valid file-shaped entry; null aborts the entire traversal.
		this.contents = jar == null ? JarContents.empty(this.path) : contentsOf(jar);
	}

	private static JarContents contentsOf(Path jar) {
		try {
			return JarContents.ofPath(jar);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Container] no JarContents for %s — a mod reading files out of its own jar gets "
					+ "nothing", jar.getFileName());
			return null;
		}
	}

	@Override
	public Path getFilePath() {
		return path;
	}

	@Override
	public JarContents getContents() {
		return contents;
	}

	/**
	 * Built on demand, and never null.
	 *
	 * <p>It is reached from further away than it looks: {@code ModList.getAllScanData()} streams sortedList →
	 * getOwningFile → getFile → getScanResult, so EVERY published mod is asked for one the moment anything calls
	 * it. Sodium does, right after its config walk, and NPE'd on a null inside {@code Minecraft.<init>} before
	 * the window ever opened.
	 *
	 * <p>The scan is real: {@code ModFileScanner.scan} walks the jar's classes and builds FML's own
	 * {@code ModFileScanData}, so annotation-driven discovery (JEI plugins, Jade providers, Sophisticated Core,
	 * Sodium's third-party config hooks) finds what it would on the carrier. Only a jar-less presence alias — a
	 * mod id the kernel publishes without a file behind it — gets the empty result, which reads exactly like a
	 * mod file that declares no annotations.
	 *
	 * <p>The index is the one the seeded {@code LoadingModList}'s {@code ModFile} for the same jar hands out
	 * ({@code ModFileScanner.scanShared}): natively {@code ModList} is built out of {@code LoadingModList}, so
	 * both hand out the same {@code ModFile} and therefore the same index — and the jar is walked once, however
	 * many entries of the two lists ask.
	 */
	@Override
	public synchronized ModFileScanData getScanResult() {
		if (scanResult == null) {
			Object real = jar == null ? null : ModFileScanner.scanShared(jar, getClass().getClassLoader());
			scanResult = real instanceof ModFileScanData data ? data : new ModFileScanData();
		}
		return scanResult;
	}

	/**
	 * The mod's id, which is what a real mod file's id is. A shared file answers with its jar's first
	 * {@code [[mods]]} entry, as {@code ModFile}'s constructor sets it, whichever of the jar's mods was built first;
	 * a jar whose manifest names none answers with the first of its mods the kernel built.
	 */
	@Override
	public String getId() {
		if (!shared) return modId;
		List<String> order = declaredOrder();
		if (!order.isEmpty()) return order.getFirst();
		List<IModInfo> mods = mods();
		return mods.isEmpty() ? modId : mods.getFirst().getModId();
	}

	/** The jar's file name, or the placeholder path's for a mod that has no jar. Never null. */
	@Override
	public String getFileName() {
		Path name = path.getFileName();
		return name == null ? modId + ".jar" : name.toString();
	}

	/**
	 * {@code MOD}, which is what every file the kernel publishes is.
	 *
	 * <p>Null was not a neutral answer: consumers filter the file list by type, and one comparing against
	 * {@code Type.MOD} dropped every kernel-loaded mod from whatever it was building.
	 */
	@Override
	public IModFile.Type getType() {
		return IModFile.Type.MOD;
	}

	/** The info that owns this file — the walk back up, which NeoForge's own error path takes. */
	@Override
	public IModFileInfo getModFileInfo() {
		return modFileInfo;
	}

	/** Called by the {@link KernelModFileInfo} that owns this file; the first one keeps it. */
	synchronized void setModFileInfo(IModFileInfo info) {
		if (this.modFileInfo == null) this.modFileInfo = info;
	}

	/**
	 * Adds a NeoForge declaration of this shared file's jar and answers the jar's one file info, built by
	 * {@code firstInfo} for the first declaration. A second declaration of an id the file already lists — the same mod
	 * described twice — keeps the first one's slot, which is its container's.
	 */
	synchronized KernelModFileInfo join(String id, IModInfo[] slot, Supplier<KernelModFileInfo> firstInfo) {
		members.putIfAbsent(id, slot);
		if (modFileInfo == null) setModFileInfo(firstInfo.get());
		return (KernelModFileInfo) modFileInfo;
	}

	/**
	 * A shared file's mods, as {@code ModFileInfo.getMods()} lists them: in the order of the jar's {@code [[mods]]}
	 * entries, then any the manifest does not name (a {@code @Mod} class with no entry of its own, which the kernel
	 * still gives a container) in the order they joined.
	 */
	synchronized List<IModInfo> mods() {
		List<String> order = declaredOrder();
		List<Map.Entry<String, IModInfo[]>> entries = new ArrayList<>(members.entrySet());
		entries.sort(Comparator.comparingInt(entry -> {
			int at = order.indexOf(entry.getKey());
			return at < 0 ? Integer.MAX_VALUE : at;
		}));
		List<IModInfo> out = new ArrayList<>(entries.size());
		for (Map.Entry<String, IModInfo[]> entry : entries) {
			if (entry.getValue()[0] != null) out.add(entry.getValue()[0]);
		}
		return List.copyOf(out);
	}

	private synchronized List<String> declaredOrder() {
		if (declaredOrder == null) declaredOrder = jar == null ? List.of() : declaredIn(jar);
		return declaredOrder;
	}

	/** The {@code [[mods]]} ids {@code jar}'s {@code neoforge.mods.toml} declares, in file order; empty without one. */
	static List<String> declaredIn(Path jar) {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(ForbricModDiscoverer.NEOFORGE_MANIFEST);
			if (entry == null) return List.of();
			List<String> ids = new ArrayList<>();
			try (InputStream in = zip.getInputStream(entry)) {
				for (ForgeModEntry mod : ModsTomlParser.parse(in).getMods()) {
					if (mod.getModId() != null && !ids.contains(mod.getModId())) ids.add(mod.getModId());
				}
			}
			return List.copyOf(ids);
		} catch (Exception unreadable) {
			Caught.warn(unreadable, "[Forbric/Container] could not read the [[mods]] order of %s — its shared file "
					+ "lists its mods in the order they were built", jar.getFileName());
			return List.of();
		}
	}

	// --- answered the way the proxy's defaultReturn answered them -----------------------------------------

	/**
	 * Empty, as before, for one mod's own file: its mods are reached through {@code IModFileInfo.getMods()} instead.
	 * A shared file answers as {@code ModFile.getModInfos()} does, with its file info's mods.
	 */
	@Override
	public List<IModInfo> getModInfos() {
		return shared ? mods() : List.of();
	}

	/** Null, as before. */
	@Override
	public Supplier<Map<String, Object>> getSubstitutionMap() {
		return null;
	}

	/** Null, as before. The kernel does not discover mod files the way FML's locators do. */
	@Override
	public ModFileDiscoveryAttributes getDiscoveryAttributes() {
		return null;
	}

	@Override
	public String toString() {
		return "KernelModFile[" + (shared ? getId() : modId) + "]";
	}
}
