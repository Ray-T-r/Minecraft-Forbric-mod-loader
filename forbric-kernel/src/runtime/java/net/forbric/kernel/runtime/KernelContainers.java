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

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.HashMap;

import net.forbric.kernel.util.ForbricLog;
import net.minecraftforge.unsafe.UnsafeHacks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.javafmlmod.FMLModContainer;
import net.neoforged.neoforgespi.language.IModInfo;

/**
 * The game side of the kernel's mod-container factory — the one entry point the boot side calls by name.
 *
 * <p>Everything here used to be reflection on the boot side: {@code Class.forName} for six types, four
 * {@link java.lang.reflect.Proxy} instances switching on method names, and an ASM-generated {@code ModContainer}
 * subclass. None of it could be checked by a compiler, and the proxies in particular answered every method they
 * did not name with a silent null. Sixteen of the thirty-seven SPI methods involved were answered that way.
 *
 * <h2>What is still reflective, and why that is not a shortfall</h2>
 *
 * <p>{@link #genuineFmlContainer} still reads fields by name. That is irreducible: it allocates a
 * {@code FMLModContainer} WITHOUT running a constructor (its only one is the loader-facing 4-arg form, which
 * would run genuine FancyModLoader mod-class discovery) and then writes {@code final} fields directly. Naming a
 * final field to write it is reflection by definition. What the game side buys here is that every TYPE is
 * checked — a renamed class is now a build failure — while only the field names remain strings.
 */
public final class KernelContainers {
	private KernelContainers() {
	}

	/**
	 * An {@code IModInfo} for {@code modId}, backed by {@code jar} when the mod has one.
	 *
	 * <p>Returns {@code Object} because the caller is boot-side and cannot name the type. This is the only cast
	 * in the chain, and it sits exactly at the boundary that is untyped by construction.
	 */
	public static Object modInfo(String modId, Path jar) {
		return new KernelModInfo(modId, jar);
	}

	/**
	 * A {@code ModContainer} for {@code modId} whose {@code getEventBus()} returns {@code bus}.
	 *
	 * @param bus a {@code net.neoforged.bus.api.IEventBus}, handed over untyped from the boot side
	 * @param jar the mod's own jar, or null for a presence alias
	 */
	public static Object container(String modId, Object bus, Path jar) {
		IEventBus eventBus = (IEventBus) bus;
		IModInfo modInfo = new KernelModInfo(modId, jar);

		ModContainer genuine = genuineFmlContainer(modId, modInfo, eventBus);
		if (genuine != null) return genuine;

		ForbricLog.debug("[Forbric/Container] built ModContainer for '%s'", modId);
		return new KernelModContainer(modInfo, eventBus);
	}

	/**
	 * Allocates a genuine {@code FMLModContainer} and fills only the fields the mod-facing API reads.
	 *
	 * <p>Why the genuine class and not the kernel's own subclass: a subclass satisfies every abstract-typed call
	 * but NOT an {@code instanceof}. Real NeoForge library mods resolve their bus with
	 * {@code ModList.get().getModContainerById(id)} and then narrow to {@code FMLModContainer} — Bookshelf does,
	 * and threw {@code IllegalStateException: Mod 'bookshelf' is not an FML mod!} against the generated type,
	 * aborting its construction.
	 *
	 * <p>{@code scanResults}/{@code modClasses}/{@code layer} stay null: they only feed the genuine loader's own
	 * construction path, which never runs here.
	 *
	 * <p>Returns null — caller falls back to {@link KernelModContainer} — if anything is missing, so a runtime
	 * without javafmlmod still boots.
	 */
	private static ModContainer genuineFmlContainer(String modId, IModInfo modInfo, IEventBus bus) {
		try {
			FMLModContainer container = UnsafeHacks.newInstance(FMLModContainer.class);
			set(FMLModContainer.class, "eventBus", container, bus);
			set(ModContainer.class, "modId", container, modId);
			set(ModContainer.class, "namespace", container, modId);
			set(ModContainer.class, "modInfo", container, modInfo);
			set(ModContainer.class, "extensionPoints", container, new HashMap<>());

			ForbricLog.debug("[Forbric/Container] built genuine FMLModContainer for '%s'", modId);
			return container;
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/Container] no genuine FMLModContainer for '%s' (%s) — using the kernel's "
					+ "own subclass", modId, String.valueOf(t));
			return null;
		}
	}

	private static void set(Class<?> owner, String name, Object target, Object value) throws Exception {
		Field field = owner.getDeclaredField(name);
		UnsafeHacks.setField(field, target, value);
	}
}
