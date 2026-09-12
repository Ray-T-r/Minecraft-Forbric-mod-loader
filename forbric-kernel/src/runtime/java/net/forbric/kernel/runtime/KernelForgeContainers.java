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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import net.forbric.kernel.boot.KernelForgeModContext.Handle;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.IModBusEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.javafmlmod.FMLModContainer;
import net.minecraftforge.unsafe.UnsafeHacks;

/**
 * The game side of the kernel's TRADITIONAL-Forge loading context: a {@code BusGroup} + {@code FMLModContainer} +
 * {@code FMLJavaModLoadingContext} triple, manufactured without any FancyModLoader discovery, module layer or
 * sorting.
 *
 * <p>The boot-side door is {@code KernelForgeModContext}, whose javadoc explains why traditional Forge needs its
 * own factory rather than reusing the NeoForge one: events run on EventBus 7 ({@code BusGroup} with a
 * {@code startup()} gate, not an {@code IEventBus}), a {@code @Mod} class is constructed with an
 * {@code FMLJavaModLoadingContext} rather than {@code (IEventBus, Dist, ModContainer)}, and {@code RegisterEvent}
 * is the 3-arg {@code (key, ForgeRegistry, Registry)} flavour.
 *
 * <h2>What is still reflective, and why</h2>
 *
 * <p>Both {@code FMLModContainer} and {@code FMLJavaModLoadingContext} declare only loader-facing constructors
 * (taking a {@code ModFileScanData} and a {@code ModuleLayer} the kernel deliberately does not have), so the
 * instances are allocated WITHOUT a constructor and the fields the mod-facing API reads are written directly.
 * Naming a field in order to write it is reflection by definition; what moving here buys is that every TYPE is
 * checked, so a renamed class is a build failure instead of a {@code ClassNotFoundException} during mod
 * construction. Field NAMES remain strings, and {@link #uset} fails loudly on each while
 * {@link #usetIfPresent} tolerates the ones a Forge revision may not have.
 *
 * <p>{@code ModLoadingContext.setActiveContainer} is likewise reflective: it is not public API, and the kernel is
 * standing exactly where the genuine loader would.
 */
public final class KernelForgeContainers {
	private KernelForgeContainers() {
	}

	/**
	 * Manufactures the {@code BusGroup} + container + context for {@code modId} and makes the container the active
	 * {@code ModLoadingContext} — so a {@code @Mod} constructor calling {@code FMLJavaModLoadingContext.get()}
	 * (rather than using its argument) resolves to this same context.
	 */
	public static Handle create(String modId) throws Exception {
		BusGroup busGroup = BusGroup.create("modBusFor" + modId, IModBusEvent.class);
		FMLModContainer container = UnsafeHacks.newInstance(FMLModContainer.class);
		FMLJavaModLoadingContext jctx = UnsafeHacks.newInstance(FMLJavaModLoadingContext.class);

		uset(FMLJavaModLoadingContext.class, "container", jctx, container);
		uset(FMLModContainer.class, "eventBusGroup", container, busGroup);
		// FMLModContainer.context backs its contextExtension supplier; genuine Forge sets it in the ctor we skipped.
		usetIfPresent(FMLModContainer.class, "context", container, jctx);
		uset(ModContainer.class, "modId", container, modId);
		uset(ModContainer.class, "namespace", container, modId);
		uset(ModContainer.class, "contextExtension", container, (Supplier<Object>) () -> jctx);
		// ModContainer's ctor (skipped by UnsafeHacks.newInstance) initialises these; addConfig /
		// registerExtensionPoint / the activity + dependency maps all NPE on a null.
		uset(ModContainer.class, "configs", container, new EnumMap<ModConfig.Type, Object>(ModConfig.Type.class));
		uset(ModContainer.class, "extensionPoints", container, new ConcurrentHashMap<>());
		usetIfPresent(ModContainer.class, "activityMap", container, new HashMap<>());
		usetIfPresent(ModContainer.class, "dependencies", container, new HashSet<>());
		// getModInfo() is null without this (the ctor arg we skipped); Forge's own config + display-test paths read it.
		usetIfPresent(ModContainer.class, "modInfo", container, new KernelForgeModInfo(modId));

		setActiveContainer(container);
		return new Handle(modId, busGroup, container, jctx);
	}

	/**
	 * Makes {@code container} the active {@code ModLoadingContext} — what every {@code *.get()} reads.
	 *
	 * <p>{@code ModLoadingContext.get()} is marked deprecated-for-removal in this carrier. That is worth a line
	 * because it is a fact the reflective version could not have told us: {@code getMethod("get")} reports no
	 * deprecation, so the kernel was calling a method Forge intends to delete and nothing said so. There is no
	 * alternative here — the kernel is standing exactly where the genuine loader stands, and this is how the
	 * active container is set — so it is suppressed and written down rather than worked around. When the carrier
	 * is next re-pinned and the method goes, this becomes a build failure instead of a runtime one.
	 */
	@SuppressWarnings("removal")
	public static void setActiveContainer(Object container) throws Exception {
		ModLoadingContext mlc = ModLoadingContext.get();
		Method setActive = ModLoadingContext.class.getDeclaredMethod("setActiveContainer", ModContainer.class);
		setActive.setAccessible(true);
		setActive.invoke(mlc, container);
	}

	/**
	 * Constructs {@code modClassName} against {@code handle}, preferring the {@code (FMLJavaModLoadingContext)}
	 * constructor that traditional-Forge mods declare, and stores the instance on the container.
	 */
	public static Object constructMod(String modClassName, Handle handle) throws Exception {
		Class<?> modCls = Class.forName(modClassName, true, KernelForgeContainers.class.getClassLoader());
		Object mod;
		try {
			Constructor<?> c = modCls.getDeclaredConstructor(FMLJavaModLoadingContext.class);
			c.setAccessible(true);
			mod = c.newInstance(handle.jctx());
		} catch (NoSuchMethodException noCtxCtor) {
			Constructor<?> c = modCls.getDeclaredConstructor();
			c.setAccessible(true);
			mod = c.newInstance();
		}
		usetIfPresent(FMLModContainer.class, "modInstance", handle.container(), mod);
		usetIfPresent(FMLModContainer.class, "modClass", handle.container(), modCls);
		return mod;
	}

	/** Opens the EventBus 7 {@code startup()} gate — no event dispatches before this. */
	public static void startup(Object busGroup) {
		((BusGroup) busGroup).startup();
	}

	/** Writes a field that MUST exist; a missing one is a real change in the carrier and has to be loud. */
	private static void uset(Class<?> owner, String fieldName, Object target, Object value) throws Exception {
		Field field = owner.getDeclaredField(fieldName);
		UnsafeHacks.setField(field, target, value);
	}

	/** Writes a field only some Forge revisions declare. */
	private static void usetIfPresent(Class<?> owner, String fieldName, Object target, Object value) {
		try {
			uset(owner, fieldName, target, value);
		} catch (Throwable absent) {
			net.forbric.kernel.util.ForbricLog.debug("[Forbric/ForgeCtx] %s has no %s field — skipped: %s",
					owner.getSimpleName(), fieldName, String.valueOf(absent));
		}
	}
}
