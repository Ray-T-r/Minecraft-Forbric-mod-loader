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
import net.forbric.kernel.util.Reflect;

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
	/**
	 * Posts one traditional-MinecraftForge mod-lifecycle event at every mod, then runs what they deferred.
	 *
	 * <p>The kernel posted these to NeoForge mods and to nobody else, for as long as there has been a
	 * traditional-Forge side. The two families' buses are not the same shape and the NeoForge path could not
	 * simply be pointed at them: NeoForge dispatches on an {@code IEventBus} instance held per mod, while
	 * EventBus 7 resolves a bus from the EVENT plus that mod's {@code BusGroup} —
	 * {@code FMLCommonSetupEvent.getBus(ctx.getModBusGroup())}, which is how a real mod subscribes
	 * (disassembled from BiomesOPlentyForge's constructor).
	 *
	 * <p>What it cost while missing: every traditional-Forge mod that does its real work from setup did nothing.
	 * BiomesOPlenty registered its 498 blocks and 503 items — those come from {@code RegisterEvent}, which the
	 * kernel did post — and then generated no biomes at all, because the call that registers them with
	 * TerraBlender hangs off {@code FMLCommonSetupEvent}: {@code commonSetup} → {@code enqueueWork} →
	 * {@code BiomesOPlenty.init} → {@code ModBiomes.setupTerraBlender} → {@code Regions.register}. The world came
	 * out looking vanilla with every BOP block still in the creative menu, and nothing anywhere said why. Xaero's
	 * world map lost its minimap integration the same way, off {@code FMLClientSetupEvent}.
	 *
	 * <p><b>The deferred queue is half the work.</b> {@code ParallelDispatchEvent.enqueueWork} does not run the
	 * runnable; it files it on the {@code DeferredWorkQueue} that the {@code ModLoadingStage} itself owns. Posting
	 * the event without draining that queue afterwards delivers the event and still runs none of the work — which
	 * for BOP is precisely the call that was missing. Drained once per phase, after every mod has seen it, which
	 * is the order genuine FML uses.
	 *
	 * @param stage the {@code ModLoadingStage} constant this phase belongs to; it carries the queue
	 * @return how many mods the event reached
	 */
	public static int fireSetupPhase(ClassLoader cl, List<Handle> handles, String eventClassName, String stage,
			String label) throws Exception {
		if (handles.isEmpty()) return 0;
		Class<?> eventCls = Class.forName(eventClassName, false, cl);
		Class<?> busGroupCls = Class.forName(BUS_GROUP, false, cl);
		Class<?> containerCls = Class.forName("net.minecraftforge.fml.ModContainer", false, cl);
		Class<?> stageCls = Class.forName("net.minecraftforge.fml.ModLoadingStage", false, cl);

		Object stageValue = Enum.valueOf(stageCls.asSubclass(Enum.class), stage);
		Constructor<?> ctor = eventCls.getDeclaredConstructor(containerCls, stageCls);
		ctor.setAccessible(true);
		Method getBus = eventCls.getMethod("getBus", busGroupCls);

		int fired = 0;
		try {
			for (Handle handle : handles) {
				try {
					Object bus = getBus.invoke(null, handle.busGroup());
					// The mod that is registering must be the active container while its listener runs, exactly as
					// in fireRegisterEvents — otherwise whatever it registers is namespaced under the last one.
					setActiveContainer(cl, handle.container());
					single(bus.getClass(), "post").invoke(bus, ctor.newInstance(handle.container(), stageValue));
					fired++;
				} catch (Throwable t) {
					// Per mod, as genuine FML does: it collects these rather than dying on the first, and one mod's
					// broken setup must not cost every mod after it the same phase.
					ForbricLog.warn("[Forbric/Lifecycle] " + handle.modId() + " threw during traditional-Forge "
							+ label, Reflect.unwrap(t));
				}
			}
		} finally {
			setActiveContainer(cl, null);
		}

		// Now the work they filed. Before this line the event has been delivered and nothing it asked for has run.
		try {
			Object queue = stageCls.getMethod("getDeferredWorkQueue").invoke(stageValue);
			if (queue != null) queue.getClass().getMethod("runTasks").invoke(queue);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] traditional-Forge " + label + " was delivered but its deferred "
					+ "work did not run — a mod that registers from enqueueWork has done nothing",
					Reflect.unwrap(t));
		}
		return fired;
	}

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

		// Resolve each mod's (id, container, event bus, post) once — the registry-major loops below reuse them N×.
		List<Object[]> dispatch = new ArrayList<>();
		for (Handle handle : handles) {
			Object eventBus = getBus.invoke(null, handle.busGroup());
			dispatch.add(new Object[] {handle.container(), eventBus, single(eventBus.getClass(), "post"),
					handle.modId()});
		}

		List<Object[]> targets = registerEventTargets(cl);
		// One mod's listener must not take the stream down with it. EventBus 7's post has no exception table, so a
		// DeferredRegister supplier that throws — an unbound cross-registry RegistryObject, a config value read
		// before its spec is loaded, a NoClassDefFoundError out of a JiJ dependency — used to propagate out of this
		// whole method. The caller (KernelForgeBaseline.register) catches once, for everything, so the cost was:
		// every MinecraftForge mod after the failing one in this registry, every remaining registry for ALL of them,
		// and the ForgeMod baseline's own content — reported as one line, naming neither the mod nor the registry.
		// It then surfaced six layers away as "Registry Object not present: minecraft:empty" or a player kicked with
		// "Invalid player data". The NeoForge twin (KernelLifecycle.fireRegisterEvents) has isolated per bus since
		// BUG 14; this is the same guarantee for the other family, one step finer — per bus AND per registry, so a
		// mod that fails on BLOCK can still register its ITEMs if it is able to.
		try {
			dispatchIsolated(targets, dispatch,
					mod -> setActiveContainer(cl, mod[0]),
					(mod, target) -> ((Method) mod[2]).invoke(mod[1],
							regEventCtor.newInstance(target[0], target[2], target[1])));
		} finally {
			// Genuine Forge clears it after each dispatch; leaving a stale container active would silently namespace
			// whatever registers next (Fabric mains, the bake) under the last mod.
			setActiveContainer(cl, null);
		}
		return targets.size();
	}

	/** Makes one mod's container active before it is posted to. */
	@FunctionalInterface
	interface Activate {
		void apply(Object[] mod) throws Exception;
	}

	/** Posts one registry target at one mod. */
	@FunctionalInterface
	interface PostOne {
		void apply(Object[] mod, Object[] target) throws Exception;
	}

	/**
	 * The registry-major dispatch loop, with each (registry, mod) pair isolated. Split out so a test can drive it
	 * without game classes — the isolation is the whole point and there is no other way to assert it off-game.
	 *
	 * <p>Registry-major / mod-minor is preserved: a {@code DeferredRegister}'s {@code RegistryObject}s bind during
	 * their OWN registry's event, so every mod must fire for BLOCK before any fires for ITEM.
	 *
	 * <p>Stack once per mod. A mod that fails on one registry usually fails on the next twenty, and twenty identical
	 * stacks bury the first one; every failure still gets a line naming both the mod and the registry.
	 *
	 * @return how many (registry, mod) pairs were attempted
	 */
	static int dispatchIsolated(List<Object[]> targets, List<Object[]> mods, Activate activate, PostOne post) {
		java.util.Set<String> reported = new java.util.LinkedHashSet<>();
		int attempted = 0;
		for (Object[] target : targets) {
			for (Object[] mod : mods) {
				attempted++;
				String modId = String.valueOf(mod[3]);
				try {
					activate.apply(mod);
					post.apply(mod, target);
				} catch (Throwable perMod) {
					if (reported.add(modId)) {
						ForbricLog.warn("[Forbric/Forge] " + modId + " threw handling RegisterEvent for " + target[0]
								+ " — that registration is lost; every other MinecraftForge mod and the ForgeMod "
								+ "baseline still register", Reflect.unwrap(perMod));
					} else {
						ForbricLog.warn("[Forbric/Forge] %s also threw handling RegisterEvent for %s", modId,
								String.valueOf(target[0]));
					}
				}
			}
		}
		return attempted;
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
							String.valueOf(Reflect.unwrap(perReg)));
				}
			}
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Forge] could not enumerate custom Forge registries for RegisterEvent "
					+ "(fluid_type etc. may stay unbound)", Reflect.unwrap(t));
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
