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

package net.forbric.loader.impl.forge.minecraftforge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.fabricmc.loader.api.metadata.CustomValue;

import net.forbric.loader.impl.util.ForbricLog;

/**
 * Brings up the <b>real</b> traditional MinecraftForge runtime (loaded as a Knot mod) under the Fabric substrate,
 * without running Forge's own ModLauncher/BootstrapLauncher startup (which would build a JPMS module layer + a
 * transforming classloader that fights Knot). Reflection-only, so the loader keeps no compile-time Forge dependency;
 * the {@code net.minecraftforge.*} classes exist at runtime because the Forge runtime jar is Knot-loaded alongside
 * the (Mojmap-native, Forge-patched) game.
 *
 * <p>This is the traditional-Forge sibling of {@code ForbricNeoForgeRuntime}. The two diverge because Forge's
 * {@code net.minecraftforge.fml.loading.FMLLoader} is a <b>static-only</b> class with no seedable instance ctor:
 * Forbric seeds the FML environment by reflectively setting {@code FMLLoader}'s mutable static fields
 * ({@code dist}/{@code production}/{@code naming}) BEFORE {@code FMLEnvironment} class-loads. Other Forge shapes:
 * the mod event bus is {@code eventbus.api.bus.BusGroup} (with a {@code startup()} gate), {@code @Mod} construction
 * goes through a {@code FMLJavaModLoadingContext} (built via Forge's {@code UnsafeHacks} so it needs no module
 * layer), {@code RegisterEvent} has a 3-arg ctor {@code (ResourceKey, ForgeRegistry, Registry)}, and the registration
 * window is {@code GameData.unfreezeData()}/{@code freezeData()}. Because Forge wraps each {@code BuiltInRegistries.*}
 * in a locked {@code NamespacedWrapper}, Fabric content (which calls plain {@code Registry.register}) only registers
 * after Forbric reflectively unlocks the wrappers, so the Fabric {@code main} entrypoints run inside an unlock window.
 *
 * <p>Headless mode ({@code -Dforbric.headlessRegister=true}) drives the full cycle itself (the dedicated server
 * otherwise {@code return}s at the EULA gate before {@code Bootstrap.bootStrap()}); the real client rides the
 * patched MC's own {@code ClientModLoader} flow, so the manual drive stays off there.
 */
public final class ForbricMinecraftForgeRuntime implements PreLaunchEntrypoint {
	/** Drive Bootstrap + registration at preLaunch (headless/no-EULA verification). */
	public static final String HEADLESS_REGISTER = "forbric.headlessRegister";
	/** CSV of {@code @Mod} class names to construct, overriding/augmenting custom-key discovery (testing). */
	public static final String FORGE_MODS = "forbric.forgeMods";
	/** CSV of {@code namespace:path} item ids to assert ended up in {@code BuiltInRegistries.ITEM} (testing). */
	public static final String VERIFY_ITEMS = "forbric.verifyItems";

	private static final String FORGE_CLASS_KEY = "forbric:forgeClass";
	private static final String FORGE_CLASSES_KEY = "forbric:forgeClasses";

	private final Map<String, Object> modBuses = new LinkedHashMap<>();
	private boolean registered;

	@Override
	public void onPreLaunch() {
		ClassLoader cl = getClass().getClassLoader(); // Knot transforming classloader

		// Presence gate: on a NeoForge-base instance the traditional-Forge runtime is simply not staged.
		// Resource probe only — Class.forName (even initialize=false) would DEFINE the class into the
		// unnamed module and break the module-layer ordering invariant below.
		if (cl.getResource("net/minecraftforge/fml/loading/FMLLoader.class") == null) {
			ForbricLog.info("[Forbric/Forge] no MinecraftForge runtime staged — traditional-Forge driver idle");
			return;
		}

		// HARD ORDERING INVARIANT: the synthetic GAME module layer must be defined before ANY
		// net.minecraftforge.* class is Knot-loaded (see ForbricFmlBootstrap javadoc).
		ForbricFmlBootstrap fml = new ForbricFmlBootstrap();
		fml.defineGameLayer(cl);

		seedFmlEnvironment(cl);
		fml.wireFml(cl); // after the dist seed: the immediate-window provider selection reads FMLEnvironment

		if (fml.gameLayer() != null) {
			// Genuine FML discovery: installs the LoadingModList that the patched game's own
			// ServerModLoader.load()/ClientModLoader will drive after the EULA gate / on the client.
			new ForbricFmlDiscovery(cl).run(fml.layerJars(), fml.runtimeJar());
		} else if (fml.runtimeJar() != null) {
			// No GAME layer -> no mod containers can be built, but the LoadingModList must still be installed:
			// Forge code baked into the patched base touches it unconditionally and far from here
			// (ServerStatusPing -> ModList on world load), and an uninstalled list permanently poisons
			// LoadingModListImpl$1LazyInit. Install the minimal system-mods-only list.
			new ForbricFmlDiscovery(cl).run(List.of(fml.runtimeJar()), fml.runtimeJar());
		}

		if (Boolean.getBoolean(ForbricFmlBootstrap.FML_SMOKE)) {
			fml.smoke(cl);
		}

		if (Boolean.getBoolean(HEADLESS_REGISTER)) {
			driveHeadlessRegistration(cl);
		}
	}

	// --- FML environment seed (always) ---------------------------------------------------------------

	/**
	 * Seed Forge's static {@code FMLLoader} state so the patched game's {@code FMLEnvironment.<clinit>} (which reads
	 * {@code FMLLoader.getDist()/getNaming()/isProduction()}) does not get null/garbage. Forge's FMLLoader has no
	 * instance ctor; its fields are {@code private static} but non-final, so we set them directly.
	 */
	private void seedFmlEnvironment(ClassLoader cl) {
		try {
			Class<?> fmlLoader = Class.forName("net.minecraftforge.fml.loading.FMLLoader", false, cl);
			Class<?> distCls = Class.forName("net.minecraftforge.api.distmarker.Dist", false, cl);
			EnvType env = FabricLoader.getInstance().getEnvironmentType();
			String distName = env == EnvType.SERVER ? "DEDICATED_SERVER" : "CLIENT";
			Object dist = distCls.getMethod("valueOf", String.class).invoke(null, distName);

			setStatic(fmlLoader, "dist", dist);
			setStatic(fmlLoader, "production", Boolean.TRUE);
			setStatic(fmlLoader, "naming", "mojmap");

			// Force FMLEnvironment to initialize now (it reads the seeded fields). If it already loaded with garbage
			// this is a no-op, but in the normal boot order this runs first.
			Class.forName("net.minecraftforge.fml.loading.FMLEnvironment", true, cl);
			ForbricLog.info("[Forbric/Forge] seeded FML environment: dist=" + distName + " production=true naming=mojmap");
		} catch (Throwable t) {
			ForbricLog.error("[Forbric/Forge] failed to seed FML environment", t);
		}
	}

	// --- Headless registration drive (verification / no-EULA server) ---------------------------------

	private void driveHeadlessRegistration(ClassLoader cl) {
		try {
			Class.forName("net.minecraft.SharedConstants", false, cl).getMethod("tryDetectVersion").invoke(null);
			Class.forName("net.minecraft.server.Bootstrap", false, cl).getMethod("bootStrap").invoke(null);
			ForbricLog.info("[Forbric/Forge] Bootstrap.bootStrap() invoked — registries are up");
		} catch (Throwable t) {
			ForbricLog.error("[Forbric/Forge] Bootstrap.bootStrap() failed", t);
		}
		onRegistrationWindow(cl);
	}

	private synchronized void onRegistrationWindow(ClassLoader cl) {
		if (registered) return;
		registered = true;

		try {
			for (String modClass : discoverModClasses()) {
				Object bus = buildModBus(cl, modClass);
				Object instance = constructForgeMod(cl, modClass, bus);
				modBuses.put(modClass, bus);
				startup(cl, bus); // BusGroup buffers listeners until startup()
				ForbricLog.info("[Forbric/Forge] constructed @Mod " + modClass + " -> " + instance);
			}

			// Arm the global GAME bus now that every @Mod's DEFAULT listeners are registered. The per-mod startup()
			// above only covers mod buses; the game bus (loot/reload/entity/permission events) is otherwise never
			// started because Forge's ModLauncher boot is bypassed. Idempotent + shared with the bridge path.
			ForbricFabricWindow.startDefaultGameBus(cl);

			Class<?> gameData = Class.forName("net.minecraftforge.registries.GameData", false, cl);
			gameData.getMethod("unfreezeData").invoke(null);
			ForbricLog.info("[Forbric/Forge] registration window OPEN (GameData.unfreezeData)");
			try {
				fireRegisterEvents(cl);
				// Fabric content uses plain Registry.register, which Forge's NamespacedWrapper blocks while locked.
				List<Object> unlocked = setRegistriesLocked(cl, false);
				try {
					invokeFabricMainEntrypoints();
				} finally {
					relock(unlocked);
				}
			} finally {
				gameData.getMethod("freezeData").invoke(null);
				ForbricLog.info("[Forbric/Forge] registration window CLOSED (GameData.freezeData)");
			}

			verifyItems(cl);
		} catch (Throwable t) {
			ForbricLog.error("[Forbric/Forge] registration window failed", t);
		}
	}

	/** Fire {@code RegisterEvent} (Forge's 3-arg ctor) for every Forge-backed registry, on each mod bus. */
	private void fireRegisterEvents(ClassLoader cl) throws Exception {
		if (modBuses.isEmpty()) return;

		Class<?> registryCls = Class.forName("net.minecraft.core.Registry", false, cl);
		Class<?> resourceKeyCls = Class.forName("net.minecraft.resources.ResourceKey", false, cl);
		Class<?> forgeRegCls = Class.forName("net.minecraftforge.registries.ForgeRegistry", false, cl);
		Class<?> registerEventCls = Class.forName("net.minecraftforge.registries.RegisterEvent", false, cl);
		Class<?> busGroupCls = Class.forName("net.minecraftforge.eventbus.api.bus.BusGroup", false, cl);
		Class<?> builtin = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, cl);
		Class<?> regManager = Class.forName("net.minecraftforge.registries.RegistryManager", false, cl);
		Object active = regManager.getField("ACTIVE").get(null);
		Method getRegistry = regManager.getMethod("getRegistry", resourceKeyCls);
		Method keyM = registryCls.getMethod("key");

		Constructor<?> regEventCtor = registerEventCls.getDeclaredConstructor(resourceKeyCls, forgeRegCls, registryCls);
		regEventCtor.setAccessible(true);
		Method getBus = registerEventCls.getMethod("getBus", busGroupCls);

		// (registryKey, vanillaRegistry, forgeRegistry) for each Forge-backed registry.
		List<Object[]> targets = new ArrayList<>();
		for (Field f : builtin.getFields()) {
			if (!registryCls.isAssignableFrom(f.getType())) continue;
			Object vanilla = f.get(null);
			Object key = keyM.invoke(vanilla);
			Object forgeReg = getRegistry.invoke(active, key);
			if (forgeReg != null) targets.add(new Object[] { key, vanilla, forgeReg });
		}

		for (Map.Entry<String, Object> e : modBuses.entrySet()) {
			Object eventBus = getBus.invoke(null, e.getValue());
			Method post = single(eventBus.getClass(), "post");
			for (Object[] t : targets) {
				Object event = regEventCtor.newInstance(t[0], t[2], t[1]);
				post.invoke(eventBus, event);
			}
			ForbricLog.info("[Forbric/Forge] fired RegisterEvent x" + targets.size() + " on bus for " + e.getKey());
		}
	}

	/** Drive Fabric {@code main} entrypoints so Fabric content mods register in the same open window. */
	private void invokeFabricMainEntrypoints() {
		for (EntrypointContainer<ModInitializer> c :
				FabricLoader.getInstance().getEntrypointContainers("main", ModInitializer.class)) {
			String id = c.getProvider().getMetadata().getId();
			try {
				c.getEntrypoint().onInitialize();
				ForbricLog.info("[Forbric/Forge] invoked Fabric main entrypoint of " + id);
			} catch (Throwable t) {
				ForbricLog.error("[Forbric/Forge] Fabric main entrypoint of " + id + " failed", t);
			}
		}
	}

	private void verifyItems(ClassLoader cl) throws Exception {
		String csv = System.getProperty(VERIFY_ITEMS, "").trim();
		if (csv.isEmpty()) return;

		Class<?> builtin = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, cl);
		Class<?> registryCls = Class.forName("net.minecraft.core.Registry", false, cl);
		Class<?> idCls = Class.forName("net.minecraft.resources.Identifier", false, cl);
		Object itemReg = builtin.getField("ITEM").get(null);
		Method contains = registryCls.getMethod("containsKey", idCls);
		Method idOf = idCls.getMethod("fromNamespaceAndPath", String.class, String.class);

		for (String spec : csv.split(",")) {
			spec = spec.trim();
			if (spec.isEmpty()) continue;
			String[] np = spec.split(":", 2);
			Object id = idOf.invoke(null, np[0], np[1]);
			boolean has = (Boolean) contains.invoke(itemReg, id);
			ForbricLog.info("[Forbric/VERIFY] BuiltInRegistries.ITEM contains " + spec + " = " + has);
		}
	}

	// --- registry wrapper lock toggling (so Fabric Registry.register works on a Forge base) ----------

	/** Set {@code NamespacedWrapper.locked} on every BuiltInRegistries registry; returns those that were toggled. */
	private List<Object> setRegistriesLocked(ClassLoader cl, boolean locked) throws Exception {
		Class<?> builtin = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, cl);
		Class<?> registryCls = Class.forName("net.minecraft.core.Registry", false, cl);
		List<Object> toggled = new ArrayList<>();
		for (Field f : builtin.getFields()) {
			if (!registryCls.isAssignableFrom(f.getType())) continue;
			Object reg = f.get(null);
			Field lockedField = findField(reg.getClass(), "locked");
			if (lockedField == null) continue; // not a Forge NamespacedWrapper
			lockedField.setAccessible(true);
			if (lockedField.getBoolean(reg) != locked) {
				lockedField.setBoolean(reg, locked);
				toggled.add(reg);
			}
		}
		return toggled;
	}

	private void relock(List<Object> wrappers) throws Exception {
		for (Object reg : wrappers) {
			Field lockedField = findField(reg.getClass(), "locked");
			lockedField.setAccessible(true);
			lockedField.setBoolean(reg, true);
		}
	}

	// --- @Mod discovery + construction ---------------------------------------------------------------

	private static List<String> discoverModClasses() {
		LinkedHashSet<String> classes = new LinkedHashSet<>();
		String csv = System.getProperty(FORGE_MODS, "").trim();
		if (!csv.isEmpty()) {
			for (String s : csv.split(",")) if (!s.trim().isEmpty()) classes.add(s.trim());
		}
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			if (!isForgeWrap(mod)) continue; // the NeoForge driver owns "neoforge"-family wraps
			classes.addAll(forgeClasses(mod));
		}
		return new ArrayList<>(classes);
	}

	/** Whether a wrapped mod belongs to the traditional-Forge family (missing key = legacy wrap = accept). */
	private static boolean isForgeWrap(ModContainer mod) {
		CustomValue v = mod.getMetadata().getCustomValue("forbric:ecosystem");
		return v == null || v.getType() != CustomValue.CvType.STRING || "forge".equals(v.getAsString());
	}

	private static List<String> forgeClasses(ModContainer mod) {
		List<String> classes = new ArrayList<>();
		CustomValue array = mod.getMetadata().getCustomValue(FORGE_CLASSES_KEY);
		if (array != null && array.getType() == CustomValue.CvType.ARRAY) {
			for (CustomValue element : array.getAsArray()) {
				if (element.getType() == CustomValue.CvType.STRING) classes.add(element.getAsString());
			}
		}
		if (classes.isEmpty()) {
			CustomValue single = mod.getMetadata().getCustomValue(FORGE_CLASS_KEY);
			if (single != null && single.getType() == CustomValue.CvType.STRING && !single.getAsString().isEmpty()) {
				classes.add(single.getAsString());
			}
		}
		return classes;
	}

	private static Object buildModBus(ClassLoader cl, String modClass) throws Exception {
		Class<?> busGroupCls = Class.forName("net.minecraftforge.eventbus.api.bus.BusGroup", false, cl);
		Class<?> modBusEvent = Class.forName("net.minecraftforge.fml.event.IModBusEvent", false, cl);
		return busGroupCls.getMethod("create", String.class, Class.class)
				.invoke(null, "modBusFor" + modClass, modBusEvent);
	}

	private static void startup(ClassLoader cl, Object bus) throws Exception {
		Class<?> busGroupCls = Class.forName("net.minecraftforge.eventbus.api.bus.BusGroup", false, cl);
		busGroupCls.getMethod("startup").invoke(bus);
	}

	/**
	 * Construct a Forge {@code @Mod} the genuine way: build a minimal {@code FMLJavaModLoadingContext} +
	 * {@code FMLModContainer} via Forge's {@code UnsafeHacks} (so no JPMS module layer is needed), set it as the
	 * active {@code ModLoadingContext} (so the mod's {@code FMLJavaModLoadingContext.get()} resolves), then call the
	 * mod's {@code FMLJavaModLoadingContext}-arg ctor (else no-arg). The mod pulls its bus from the context.
	 */
	private static Object constructForgeMod(ClassLoader cl, String modClassName, Object busGroup) throws Exception {
		Class<?> unsafe = Class.forName("net.minecraftforge.unsafe.UnsafeHacks", false, cl);
		Method newInstance = unsafe.getMethod("newInstance", Class.class);
		Method setField = unsafe.getMethod("setField", Field.class, Object.class, Object.class);

		Class<?> fmcCls = Class.forName("net.minecraftforge.fml.javafmlmod.FMLModContainer", false, cl);
		Class<?> jctxCls = Class.forName("net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext", false, cl);
		Class<?> mcCls = Class.forName("net.minecraftforge.fml.ModContainer", false, cl);

		Object container = newInstance.invoke(null, fmcCls);
		Object jctx = newInstance.invoke(null, jctxCls);
		String modid = modIdFor(cl, modClassName);
		uset(setField, jctxCls, "container", jctx, container);
		uset(setField, fmcCls, "eventBusGroup", container, busGroup);
		uset(setField, mcCls, "modId", container, modid);
		uset(setField, mcCls, "namespace", container, modid);
		Supplier<Object> ext = () -> jctx;
		uset(setField, mcCls, "contextExtension", container, ext);

		Class<?> mlcCls = Class.forName("net.minecraftforge.fml.ModLoadingContext", false, cl);
		Object mlc = mlcCls.getMethod("get").invoke(null);
		Method setActive = mlcCls.getDeclaredMethod("setActiveContainer", mcCls);
		setActive.setAccessible(true);
		setActive.invoke(mlc, container); // sets activeContainer + languageExtension = ext.get() = jctx

		Class<?> modCls = Class.forName(modClassName, true, cl);
		Object mod;
		try {
			Constructor<?> c = modCls.getDeclaredConstructor(jctxCls);
			c.setAccessible(true);
			mod = c.newInstance(jctx);
		} catch (NoSuchMethodException e) {
			Constructor<?> c = modCls.getDeclaredConstructor();
			c.setAccessible(true);
			mod = c.newInstance();
		}
		uset(setField, fmcCls, "modInstance", container, mod);
		return mod;
	}

	/** Read the {@code @Mod} value (modid) from the class's annotation; fall back to the simple class name. */
	private static String modIdFor(ClassLoader cl, String modClassName) {
		try {
			Class<?> modCls = Class.forName(modClassName, false, cl);
			Class<? extends java.lang.annotation.Annotation> modAnn =
					Class.forName("net.minecraftforge.fml.common.Mod", false, cl).asSubclass(java.lang.annotation.Annotation.class);
			java.lang.annotation.Annotation a = modCls.getAnnotation(modAnn);
			if (a != null) return (String) modAnn.getMethod("value").invoke(a);
		} catch (Throwable ignore) { /* fall through */ }
		int dot = modClassName.lastIndexOf('.');
		return dot >= 0 ? modClassName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : modClassName;
	}

	// --- small reflection helpers --------------------------------------------------------------------

	private static void setStatic(Class<?> c, String name, Object v) throws Exception {
		Field f = c.getDeclaredField(name);
		f.setAccessible(true);
		f.set(null, v);
	}
	private static void uset(Method setField, Class<?> declaring, String name, Object obj, Object val) throws Exception {
		setField.invoke(null, declaring.getDeclaredField(name), obj, val);
	}
	private static Field findField(Class<?> c, String name) {
		for (; c != null; c = c.getSuperclass()) {
			try {
				return c.getDeclaredField(name);
			} catch (NoSuchFieldException ignore) { /* try superclass */ }
		}
		return null;
	}
	private static Method single(Class<?> c, String name) {
		for (Method m : c.getMethods()) if (m.getName().equals(name) && m.getParameterCount() == 1) return m;
		throw new IllegalStateException("no 1-arg method " + name + " on " + c);
	}
}
