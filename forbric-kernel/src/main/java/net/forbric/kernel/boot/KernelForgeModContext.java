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
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
	private static final String FML_MOD_CONTAINER = ForeignType.FML_MOD_CONTAINER.binary(Ecosystem.FORGE);
	private static final String FML_JAVA_CTX = "net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext";
	private static final String MOD_CONTAINER = ForeignType.MOD_CONTAINER.binary(Ecosystem.FORGE);
	private static final String BUS_GROUP = "net.minecraftforge.eventbus.api.bus.BusGroup";

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
		Class<?> unsafe = Class.forName("net.minecraftforge.unsafe.UnsafeHacks", false, cl);
		Method newInstance = unsafe.getMethod("newInstance", Class.class);
		Method setField = unsafe.getMethod("setField", Field.class, Object.class, Object.class);

		Class<?> fmcCls = Class.forName(FML_MOD_CONTAINER, false, cl);
		Class<?> jctxCls = Class.forName(FML_JAVA_CTX, false, cl);
		Class<?> mcCls = Class.forName(MOD_CONTAINER, false, cl);

		Object busGroup = buildBusGroup(cl, modId);
		Object container = newInstance.invoke(null, fmcCls);
		Object jctx = newInstance.invoke(null, jctxCls);

		uset(setField, jctxCls, "container", jctx, container);
		uset(setField, fmcCls, "eventBusGroup", container, busGroup);
		// FMLModContainer.context backs its contextExtension supplier; genuine Forge sets it in the ctor we skipped.
		usetIfPresent(setField, fmcCls, "context", container, jctx);
		uset(setField, mcCls, "modId", container, modId);
		uset(setField, mcCls, "namespace", container, modId);
		uset(setField, mcCls, "contextExtension", container, (java.util.function.Supplier<Object>) () -> jctx);
		// ModContainer's ctor (skipped by UnsafeHacks.newInstance) initializes these; addConfig / registerExtensionPoint
		// / the activity + dependency maps all NPE on a null.
		@SuppressWarnings({"unchecked", "rawtypes"})
		Object configs = new java.util.EnumMap(
				Class.forName(ForeignType.MOD_CONFIG_TYPE.binary(Ecosystem.FORGE), false, cl).asSubclass(Enum.class));
		uset(setField, mcCls, "configs", container, configs);
		uset(setField, mcCls, "extensionPoints", container, new java.util.concurrent.ConcurrentHashMap<>());
		usetIfPresent(setField, mcCls, "activityMap", container, new java.util.HashMap<>());
		usetIfPresent(setField, mcCls, "dependencies", container, new java.util.HashSet<>());
		// getModInfo() is null without this (the ctor arg we skipped); Forge's own config + display-test paths read it.
		usetIfPresent(setField, mcCls, "modInfo", container, modInfoProxy(cl, modId));

		setActiveContainer(cl, container);
		return new Handle(modId, busGroup, container, jctx);
	}

	/** Makes {@code container} the active {@code ModLoadingContext} (what {@code *.get()} reads). */
	public static void setActiveContainer(ClassLoader cl, Object container) throws Exception {
		Class<?> mcCls = Class.forName(MOD_CONTAINER, false, cl);
		Class<?> mlcCls = Class.forName(ForeignType.MOD_LOADING_CONTEXT.binary(Ecosystem.FORGE), false, cl);
		Object mlc = mlcCls.getMethod("get").invoke(null);
		Method setActive = mlcCls.getDeclaredMethod("setActiveContainer", mcCls);
		setActive.setAccessible(true);
		setActive.invoke(mlc, container);
	}

	/**
	 * Constructs {@code modClassName} against {@code handle}, preferring the {@code (FMLJavaModLoadingContext)} ctor
	 * that traditional-Forge mods declare, and stores the instance on the container.
	 */
	public static Object constructMod(ClassLoader cl, String modClassName, Handle handle) throws Exception {
		Class<?> unsafe = Class.forName("net.minecraftforge.unsafe.UnsafeHacks", false, cl);
		Method setField = unsafe.getMethod("setField", Field.class, Object.class, Object.class);
		Class<?> fmcCls = Class.forName(FML_MOD_CONTAINER, false, cl);
		Class<?> jctxCls = Class.forName(FML_JAVA_CTX, false, cl);

		Class<?> modCls = Class.forName(modClassName, true, cl);
		Object mod;
		try {
			Constructor<?> c = modCls.getDeclaredConstructor(jctxCls);
			c.setAccessible(true);
			mod = c.newInstance(handle.jctx());
		} catch (NoSuchMethodException noCtxCtor) {
			Constructor<?> c = modCls.getDeclaredConstructor();
			c.setAccessible(true);
			mod = c.newInstance();
		}
		usetIfPresent(setField, fmcCls, "modInstance", handle.container(), mod);
		usetIfPresent(setField, fmcCls, "modClass", handle.container(), modCls);
		return mod;
	}

	/** Opens the EventBus 7 {@code startup()} gate on {@code busGroup} — no event dispatches before this. */
	public static void startup(ClassLoader cl, Object busGroup) throws Exception {
		Class.forName(BUS_GROUP, false, cl).getMethod("startup").invoke(busGroup);
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

	/** A minimal Forge-flavoured {@code IModInfo}; returns null (the old behaviour) if the SPI type is absent. */
	private static Object modInfoProxy(ClassLoader cl, String modId) {
		try {
			Class<?> iModInfo = Class.forName(ForeignType.MOD_INFO_SPI.binary(Ecosystem.FORGE), false, cl);
			Object version = defaultArtifactVersion(cl);
			InvocationHandler h = (proxy, method, args) -> switch (method.getName()) {
				case "getModId", "getNamespace", "getDisplayName" -> modId;
				case "getDescription" -> "";
				// Non-null like the NeoForge twin: a mod-list UI renders getVersion().toString() unguarded.
				case "getVersion" -> version;
				case "getModProperties" -> Map.of();
				case "getDependencies", "getForgeFeatures" -> List.of();
				case "getUpdateURL", "getModURL", "getLogoFile" -> Optional.empty();
				case "getLogoBlur" -> Boolean.FALSE;
				case "toString" -> "KernelForgeModInfo[" + modId + "]";
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == (args == null ? null : args[0]);
				default -> defaultReturn(method);
			};
			return Proxy.newProxyInstance(cl, new Class<?>[] {iModInfo}, h);
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/Forge] no Forge IModInfo SPI — container modInfo left null: %s", String.valueOf(t));
			return null;
		}
	}

	private static Object defaultReturn(Method method) {
		Class<?> r = method.getReturnType();
		if (r == boolean.class) return Boolean.FALSE;
		if (r == int.class) return 0;
		if (r == Optional.class) return Optional.empty();
		if (r == List.class) return List.of();
		if (r == Map.class) return Map.of();
		return null;
	}

	/** A non-null placeholder {@code ArtifactVersion} (reflective — no compile dep on maven-artifact), or null if absent. */
	private static Object defaultArtifactVersion(ClassLoader cl) {
		try {
			return Class.forName("org.apache.maven.artifact.versioning.DefaultArtifactVersion", true, cl)
					.getConstructor(String.class).newInstance("0.0");
		} catch (Throwable t) {
			return null;
		}
	}

	private static void uset(Method setField, Class<?> owner, String fieldName, Object target, Object value)
			throws Exception {
		setField.invoke(null, owner.getDeclaredField(fieldName), target, value);
	}

	/** Same as {@link #uset} but tolerates the field being absent — for fields that vary across Forge revisions. */
	private static void usetIfPresent(Method setField, Class<?> owner, String fieldName, Object target, Object value) {
		if (value == null) return;
		try {
			uset(setField, owner, fieldName, target, value);
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/Forge] optional field %s.%s not set: %s", owner.getSimpleName(), fieldName,
					String.valueOf(KernelBusSupport.unwrap(t)));
		}
	}

	static Method single(Class<?> cls, String name) {
		for (Method m : cls.getMethods()) {
			if (m.getName().equals(name)) return m;
		}
		throw new IllegalStateException("no method " + name + " on " + cls);
	}
}
