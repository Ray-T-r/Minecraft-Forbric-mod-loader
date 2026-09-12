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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;

/**
 * The kernel's native traditional-MinecraftForge per-mod loading context: a {@code BusGroup} +
 * {@code FMLModContainer} + {@code FMLJavaModLoadingContext} triple, manufactured without any FancyModLoader
 * discovery / module-layer / sorting machinery.
 *
 * <p>Traditional Forge differs from NeoForge in every joint the kernel touches, which is why it needs its own
 * factory rather than {@link KernelModContainerFactory}:
 * <ul>
 *   <li>events run on EventBus 7 — a per-mod {@code BusGroup} with a {@code startup()} gate — not an
 *       {@code IEventBus};</li>
 *   <li>a {@code @Mod} class is constructed with an {@code FMLJavaModLoadingContext}, not with
 *       ({@code IEventBus}, {@code Dist}, {@code ModContainer});</li>
 *   <li>{@code RegisterEvent} is the 3-arg {@code (key, ForgeRegistry, Registry)} flavour, posted per
 *       {@code BusGroup}.</li>
 * </ul>
 *
 * <p>Both {@code FMLModContainer} and {@code FMLJavaModLoadingContext} have only loader-facing constructors
 * (taking a {@code ModFileScanData} + {@code ModuleLayer} the kernel deliberately does not have), so instances are
 * allocated with Forge's own {@code UnsafeHacks} and the fields the mod-facing API actually reads are filled in by
 * hand. Fields normally initialized by the skipped constructor ({@code configs}, {@code extensionPoints},
 * {@code activityMap}, {@code dependencies}) must be seeded or the first mod that calls {@code addConfig} /
 * {@code registerExtensionPoint} NPEs.
 *
 * <p>This is the recipe {@link KernelForgeBaseline} proved on {@code net.minecraftforge.common.ForgeMod}; it lives
 * here so real third-party Forge {@code @Mod}s ({@link KernelModLoader}) get the same genuine context instead of
 * the {@code null} they used to receive.
 */
public final class KernelForgeModContext {
	private static final String FML_JAVA_CTX = "net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext";
	private static final String BUS_GROUP = "net.minecraftforge.eventbus.api.bus.BusGroup";
	private static final String GAME_SIDE = "net.forbric.kernel.runtime.KernelForgeContainers";

	private static final java.util.Map<String, Method> GAME_SIDE_CALLS = new java.util.concurrent.ConcurrentHashMap<>();

	/** A manufactured traditional-Forge loading context: what a {@code @Mod} ctor and the kernel each need. */
	public record Handle(String modId, Object busGroup, Object container, Object jctx) {}

	private KernelForgeModContext() {
	}

	/** True when the merged base carries traditional Forge's javafmlmod classes. */
	public static boolean available(ClassLoader cl) {
		try {
			Class.forName(FML_JAVA_CTX, false, cl);
			return true;
		} catch (ClassNotFoundException absent) {
			return false;
		}
	}

	/**
	 * Manufactures the {@code BusGroup} + container + context for {@code modId} and makes the container the active
	 * {@code ModLoadingContext} — so a {@code @Mod} ctor calling {@code FMLJavaModLoadingContext.get()} (rather than
	 * using its ctor arg) resolves to this same context.
	 */
	public static Handle create(ClassLoader cl, String modId) throws Exception {
		return (Handle) call(cl, "create", String.class).invoke(null, modId);
	}

	/** Makes {@code container} the active {@code ModLoadingContext} (what {@code *.get()} reads). */
	public static void setActiveContainer(ClassLoader cl, Object container) throws Exception {
		call(cl, "setActiveContainer", Object.class).invoke(null, container);
	}

	/**
	 * Constructs {@code modClassName} against {@code handle}, preferring the {@code (FMLJavaModLoadingContext)} ctor
	 * that traditional-Forge mods declare, and stores the instance on the container.
	 */
	public static Object constructMod(ClassLoader cl, String modClassName, Handle handle) throws Exception {
		return call(cl, "constructMod", String.class, Handle.class).invoke(null, modClassName, handle);
	}

	/** Opens the EventBus 7 {@code startup()} gate on {@code busGroup} — no event dispatches before this. */
	public static void startup(ClassLoader cl, Object busGroup) throws Exception {
		call(cl, "startup", Object.class).invoke(null, busGroup);
	}

	/**
	 * Resolves a method on the game-side factory, memoised per name.
	 *
	 * <p>The class is looked up through {@code cl} and never as a literal: this whole file is boot-side, and a
	 * literal would be a game type the boot loader cannot name. {@link KernelRuntimeClasses} checks at boot that
	 * every one of these resolves, so a rename on the game side is one line at the top of the log instead of a
	 * {@code NoSuchMethodException} in the middle of a mod's construction.
	 */
	private static Method call(ClassLoader cl, String name, Class<?>... parameters) throws Exception {
		Method cached = GAME_SIDE_CALLS.get(name);
		if (cached != null) return cached;
		Method m = Class.forName(GAME_SIDE, true, cl).getMethod(name, parameters);
		GAME_SIDE_CALLS.put(name, m);
		return m;
	}

	/**
	 * Fires the 3-arg Forge {@code RegisterEvent(key, ForgeRegistry, Registry)} on every handle's bus, flushing each
	 * mod's {@code DeferredRegister}s. Returns the number of registry targets posted per bus.
	 *
	 * <p>Dispatch is REGISTRY-major / mod-minor: the outer loop is the registry, the inner loop the mods — matching
	 * genuine Forge's {@code GameData.postRegisterEvents} (which walks the registries and, per registry,
	 * {@code ModLoader.postEventWrapContainerInModOrder}). It is not cosmetic: a {@code DeferredRegister}'s
	 * {@code RegistryObject}s bind DURING their own registry's event (the dispatcher calls
	 * {@code RegistryObject.updateReference} right after each register), not at the later bake — so with the reverse
	 * (mod-major) order a mod's {@code minecraft:item} registration could not observe another mod's blocks. Within a
	 * single registry every mod fires before the next registry begins.
	 *
	 * <p>Each mod's post runs with that mod's container ACTIVE, and the active container is cleared afterwards. This
	 * is not bookkeeping: {@code RegisterEvent.RegisterHelper.register(String, T)} — the id-less overload mods use —
	 * compiles to {@code Identifier.fromNamespaceAndPath(ModLoadingContext.get().getActiveNamespace(), name)}, so the
	 * active container decides the NAMESPACE the content lands under. Because mods now interleave within a registry,
	 * the container must be (re)set on every inner iteration, not once per mod.
	 *
	 * <p>Targets are computed once and reused across registries: the enumeration walks EVERY registry in
	 * {@code BuiltInRegistries} — with its Forge wrapper when one exists and a null wrapper when it does not, which
	 * is what genuine {@code GameData.postRegisterEvents} does — plus every Forge-CUSTOM registry in
	 * {@code RegistryManager.ACTIVE} (forge:fluid_type, holder_set_type, the modifier serializers, …), which are
	 * absent from {@code BuiltInRegistries} and so invisible to the first loop. The vanilla-only half matters:
	 * {@code creative_mode_tab} has no Forge wrapper, so while it was skipped every mod's creative tab silently
	 * failed to exist and its content was unreachable in the creative menu.
	 */
	public static int fireRegisterEvents(ClassLoader cl, List<Handle> handles) throws Exception {
		if (handles.isEmpty()) return 0;
		Class<?> registerEventCls = Class.forName(ForeignType.REGISTER_EVENT.binary(Ecosystem.FORGE), false, cl);
		Class<?> resourceKeyCls = Class.forName("net.minecraft.resources.ResourceKey", false, cl);
		Class<?> registryCls = Class.forName("net.minecraft.core.Registry", false, cl);
		Class<?> forgeRegCls = Class.forName("net.minecraftforge.registries.ForgeRegistry", false, cl);
		Class<?> busGroupCls = Class.forName(BUS_GROUP, false, cl);

		Constructor<?> regEventCtor = registerEventCls.getDeclaredConstructor(resourceKeyCls, forgeRegCls, registryCls);
		regEventCtor.setAccessible(true);
		Method getBus = registerEventCls.getMethod("getBus", busGroupCls);

		// Resolve each mod's (container, event bus, post) once — the registry-major loops below reuse them N×.
		List<Object[]> dispatch = new ArrayList<>();
		for (Handle handle : handles) {
			Object eventBus = getBus.invoke(null, handle.busGroup());
			dispatch.add(new Object[] {handle.container(), eventBus, single(eventBus.getClass(), "post")});
		}

		List<Object[]> targets = registerEventTargets(cl);
		try {
			for (Object[] t : targets) {
				for (Object[] d : dispatch) {
					setActiveContainer(cl, d[0]);
					((Method) d[2]).invoke(d[1], regEventCtor.newInstance(t[0], t[2], t[1]));
				}
			}
		} finally {
			// Genuine Forge clears it after each dispatch; leaving a stale container active would silently namespace
			// whatever registers next (Fabric mains, the bake) under the last mod.
			setActiveContainer(cl, null);
		}
		return targets.size();
	}

	/** {key, vanillaRegistryOrNull, forgeRegistry} for every Forge-backed registry, vanilla-wrapped and custom. */
	private static List<Object[]> registerEventTargets(ClassLoader cl) throws Exception {
		Class<?> registryCls = Class.forName("net.minecraft.core.Registry", false, cl);
		Class<?> resourceKeyCls = Class.forName("net.minecraft.resources.ResourceKey", false, cl);
		Class<?> forgeRegCls = Class.forName("net.minecraftforge.registries.ForgeRegistry", false, cl);
		Class<?> builtin = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, cl);
		Class<?> regManager = Class.forName(ForeignType.REGISTRY_MANAGER.binary(Ecosystem.FORGE), false, cl);
		Object active = regManager.getField("ACTIVE").get(null);
		Method getRegistry = regManager.getMethod("getRegistry", resourceKeyCls);
		Method keyM = registryCls.getMethod("key");

		List<Object[]> targets = new ArrayList<>();
		java.util.Set<Object> covered = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		for (Field f : builtin.getFields()) {
			if (!registryCls.isAssignableFrom(f.getType())) continue;
			Object vanilla = f.get(null);
			Object key = keyM.invoke(vanilla);
			Object forgeReg = getRegistry.invoke(active, key);
			if (forgeReg != null) {
				targets.add(new Object[] {key, vanilla, forgeReg});
				covered.add(forgeReg);
			} else {
				// VANILLA-ONLY registry (creative_mode_tab, trigger_type, the loot serializers, …). Forge wraps only
				// the registries it needs to extend, but genuine GameData.postRegisterEvents walks EVERY registry and
				// constructs RegisterEvent with a NULL ForgeRegistry for these — RegisterHelper then registers through
				// the vanilla Registry. Requiring a wrapper here silently dropped them, and a DeferredRegister created
				// on a vanilla ResourceKey never flushed: Macaw's Bridges registered its 303 blocks/items but its
				// CreativeModeTab was never created, so none of its content could appear in the creative menu or its
				// search. Costs nothing when no mod targets the registry — the event just has no subscribers.
				targets.add(new Object[] {key, vanilla, null});
			}
		}

		try {
			Field registriesField = regManager.getDeclaredField("registries");
			registriesField.setAccessible(true);
			Map<?, ?> all = (Map<?, ?>) registriesField.get(active);
			Method getRegistryKey = forgeRegCls.getMethod("getRegistryKey");
			Method getWrapper = forgeRegCls.getDeclaredMethod("getWrapper"); // nullable; never throws
			getWrapper.setAccessible(true);
			for (Object forgeReg : all.values()) {
				if (forgeReg == null || !covered.add(forgeReg)) continue;
				// Per-item: some custom registries (serializer/datapack registries) have no NamespacedWrapper. Skipping
				// ONE must not abort the rest (an earlier all-loop try/catch dropped fluid_type). The RegisterEvent's
				// Registry arg is only stored (registerFluids etc. register via the ForgeRegistry), so null is fine.
				try {
					Object key = getRegistryKey.invoke(forgeReg);
					Object wrapper = null;
					try {
						wrapper = getWrapper.invoke(forgeReg);
					} catch (Throwable noWrapper) {
						// leave null
					}
					targets.add(new Object[] {key, wrapper, forgeReg});
				} catch (Throwable perReg) {
					ForbricLog.debug("[Forbric/Forge] skip custom-registry RegisterEvent target: %s",
							String.valueOf(KernelBusSupport.unwrap(perReg)));
				}
			}
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Forge] could not enumerate custom Forge registries for RegisterEvent "
					+ "(fluid_type etc. may stay unbound)", KernelBusSupport.unwrap(t));
		}
		return targets;
	}

	private static Object buildBusGroup(ClassLoader cl, String modId) throws Exception {
		Class<?> busGroupCls = Class.forName(BUS_GROUP, false, cl);
		Class<?> modBusEvent = Class.forName(ForeignType.MOD_BUS_EVENT.binary(Ecosystem.FORGE), false, cl);
		return busGroupCls.getMethod("create", String.class, Class.class)
				.invoke(null, "modBusFor" + modId, modBusEvent);
	}

	static Method single(Class<?> cls, String name) {
		for (Method m : cls.getMethods()) {
			if (m.getName().equals(name)) return m;
		}
		throw new IllegalStateException("no method " + name + " on " + cls);
	}
}
