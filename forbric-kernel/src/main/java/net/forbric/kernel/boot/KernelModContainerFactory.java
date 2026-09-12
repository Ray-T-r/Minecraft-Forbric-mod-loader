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

package net.forbric.kernel.boot;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;

/**
 * The boot-side door to the kernel's mod-container factory, which lives on the GAME side.
 *
 * <p>Manufacturing a {@code ModContainer} means naming {@code net.neoforged.fml.ModContainer},
 * {@code neoforgespi.language.IModInfo}/{@code IModFileInfo}/{@code IConfigurable},
 * {@code neoforgespi.locating.IModFile} and {@code bus.api.IEventBus} — game types, which boot-side code cannot
 * name. So it was done here with {@code Class.forName} for each of them, four {@link java.lang.reflect.Proxy}
 * instances switching on method NAMES, and an ASM-emitted {@code ModContainer} subclass: 373 lines in which
 * nothing was checked by a compiler.
 *
 * <p>The proxies were the expensive part. Each answered the methods it named and sent every other one to a
 * {@code defaultReturn} — null for objects, false for booleans, empty for collections. Across the four SPI
 * interfaces that is 37 methods, of which 21 were named; the remaining 16 answered "nothing" with no record
 * anywhere that they had. A method renamed in a future NeoForge would have joined them silently.
 *
 * <p>All of it now lives in {@code net.forbric.kernel.runtime.KernelContainers}, compiled against the staged
 * jars, where javac enumerates every one of those 38 methods and refuses to build until each is answered
 * deliberately. What remains here is the door: one class lookup, two methods, cached.
 */
public final class KernelModContainerFactory {
	private static final String GAME_SIDE = "net.forbric.kernel.runtime.KernelContainers";

	private static volatile Method containerMethod;
	private static volatile Method modInfoMethod;

	private KernelModContainerFactory() {
	}

	/** Builds a {@code ModContainer} for {@code modId} whose {@code getEventBus()} returns {@code bus}. */
	public static Object create(ClassLoader cl, String modId, Object bus) throws Exception {
		return create(cl, modId, bus, null);
	}

	/**
	 * @param jar the mod's real jar, so its {@code IModFile} can hand back the mod's OWN contents. A mod that
	 *            reads files out of its own jar through the SPI —
	 *            {@code getModInfo().getOwningFile().getFile().getContents()} — gets nothing without it. Tectonic
	 *            builds its bundled datapack that way ({@code JarContentsPackResources} over
	 *            {@code resourcepacks/tectonic}); against a null it produced a null Pack, and
	 *            {@code PackRepository.discoverAvailable} then died on
	 *            {@code Cannot invoke Pack.streamSelfAndChildren() because "pack" is null}, failing the world
	 *            load. Null for a presence alias, which has no jar of its own.
	 */
	public static Object create(ClassLoader cl, String modId, Object bus, Path jar) throws Exception {
		Method m = containerMethod;
		if (m == null) {
			m = gameSide(cl).getMethod("container", String.class, Object.class, Path.class);
			containerMethod = m;
		}
		return invoke(m, modId, bus, jar);
	}

	/**
	 * The full {@code IModInfo} chain for {@code modId} — owning file, mod file, configurable — not a
	 * getModId()-only stub.
	 *
	 * <p>Package-private so {@link NeoEnumExtensions} can hand NeoForge the same hardened object: its
	 * {@code EnumPrototype.load} reports problems through {@code ModLoadingIssue.withAffectedMod}, which walks
	 * {@code getOwningFile().getFile().getFilePath()} exactly like the mod-bus listener error path does.
	 */
	static Object modInfo(ClassLoader cl, String modId) throws Exception {
		return modInfo(cl, modId, null);
	}

	static Object modInfo(ClassLoader cl, String modId, Path jar) throws Exception {
		Method m = modInfoMethod;
		if (m == null) {
			m = gameSide(cl).getMethod("modInfo", String.class, Path.class);
			modInfoMethod = m;
		}
		return invoke(m, modId, jar);
	}

	private static Class<?> gameSide(ClassLoader cl) throws ClassNotFoundException {
		return Class.forName(GAME_SIDE, true, cl);
	}

	/**
	 * Unwraps the reflective wrapper so callers see what the game side actually threw.
	 *
	 * <p>Without this every failure inside a container build arrives as an
	 * {@code InvocationTargetException: null} whose real cause is one frame down — and this factory's whole
	 * history is of errors that masked other errors.
	 */
	private static Object invoke(Method m, Object... args) throws Exception {
		try {
			return m.invoke(null, args);
		} catch (InvocationTargetException wrapped) {
			Throwable cause = wrapped.getCause();
			if (cause instanceof Exception e) throw e;
			if (cause instanceof Error e) throw e;
			throw wrapped;
		}
	}
}
