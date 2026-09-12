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
 * <p><b>Every answer below is exactly what the proxy produced</b>, deliberately, so this change cannot move
 * behaviour. Three of them are almost certainly wrong — {@link #getId()}, {@link #getFileName()} and
 * {@link #getType()} return null where a real mod file has an obvious value, and {@link #getModFileInfo()}
 * returns null even though the enclosing {@link KernelModFileInfo} is right there. They are left as they were
 * because "probably right" is not evidence, and the value of this class is that they are now arguable in a diff
 * instead of invisible. Changing one is a separate change with its own gate run.
 */
public final class KernelModFile implements IModFile {
	private final String modId;
	private final Path path;
	private final JarContents contents;
	private final Path jar;

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
		this.contents = jar == null ? null : contentsOf(jar);
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
	 * <p>An empty result for a mod with no jar is the honest answer — the kernel constructs {@code @Mod} classes
	 * from its own ASM scan and never builds FML's ModFileScanData — and it reads exactly like a mod file that
	 * declares no annotations. That IS a known gap, deliberately: annotation-driven discovery walks these, so JEI,
	 * Jade, Sophisticated Core and Sodium's third-party config hooks find nothing, load, and quietly do nothing.
	 * Producing real scan data means running an FML-shaped annotation scan over every mod jar and is its own piece
	 * of work; this only guarantees the walk does not NPE.
	 */
	@Override
	public synchronized ModFileScanData getScanResult() {
		if (scanResult == null) {
			Object real = jar == null ? null : ModFileScanner.scan(jar, getClass().getClassLoader());
			scanResult = real instanceof ModFileScanData data ? data : new ModFileScanData();
		}
		return scanResult;
	}

	// --- answered the way the proxy's defaultReturn answered them -----------------------------------------

	/** Null, as before. A real mod file would say {@code modId}; nothing has been shown to read it here. */
	@Override
	public String getId() {
		return null;
	}

	/** Null, as before. A real mod file would say the jar's file name. */
	@Override
	public String getFileName() {
		return null;
	}

	/** Null, as before. The honest value would be {@code Type.MOD}; nothing has been shown to read it here. */
	@Override
	public IModFile.Type getType() {
		return null;
	}

	/**
	 * Null, as before — even though {@link KernelModFileInfo} holds this object and could be handed back. The
	 * proxy carried a comment claiming it was "set below via the enclosing IModFileInfo when asked", which it
	 * never was; a class cannot carry that kind of untrue comment for long, which is part of the point.
	 */
	@Override
	public IModFileInfo getModFileInfo() {
		return null;
	}

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
