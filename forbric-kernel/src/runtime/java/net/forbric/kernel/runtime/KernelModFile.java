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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import net.forbric.kernel.discovery.ModFileScanner;
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
 */
public final class KernelModFile implements IModFile {
	private final String modId;
	private final Path path;
	private final JarContents contents;
	private final Path jar;

	/**
	 * The info that owns this file. Set by {@link KernelModFileInfo}'s constructor, which is what builds this
	 * object: the two refer to each other, so one of them has to be filled in second.
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
		this.modId = modId;
		this.jar = jar;
		this.path = jar != null ? jar : Path.of("forbric-kernel", modId + ".jar");
		// Native visitors enumerate every published identity, including cross-ecosystem aliases. An alias
		// contributes no resources, but is still a valid file-shaped entry; null aborts the entire traversal.
		this.contents = jar == null ? JarContents.empty(this.path) : contentsOf(jar);
	}

	private static JarContents contentsOf(Path jar) {
		try {
			return JarContents.ofPath(jar);
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/Container] no JarContents for %s: %s", jar.getFileName(),
					String.valueOf(t));
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

	/** The mod's id, which is what a real mod file's id is. */
	@Override
	public String getId() {
		return modId;
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

	/** Called once by {@link KernelModFileInfo}'s constructor. */
	void setModFileInfo(IModFileInfo info) {
		this.modFileInfo = info;
	}

	// --- answered the way the proxy's defaultReturn answered them -----------------------------------------

	/** Empty, as before. The mods of this file are reached through {@code IModFileInfo.getMods()} instead. */
	@Override
	public List<IModInfo> getModInfos() {
		return List.of();
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
		return "KernelModFile[" + modId + "]";
	}
}
