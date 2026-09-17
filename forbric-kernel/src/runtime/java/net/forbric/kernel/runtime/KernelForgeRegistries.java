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
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.RegisterEvent;
import net.minecraftforge.registries.RegistryManager;

/**
 * The game side of traditional MinecraftForge's {@code RegisterEvent}: which registries it is posted for, and how
 * one is built and posted.
 *
 * <p>The dispatch LOOP is not here. It stays boot-side in {@code KernelForgeModContext.dispatchIsolated}, where
 * it names no game type and can therefore be driven by a test with fakes — the per-(registry, mod) isolation is
 * the property most worth asserting and there is no other way to assert it off-game.
 *
 * <h2>What is still reflective, and why</h2>
 *
 * <p>Three members are not public, and naming a non-public member is reflection whichever side you stand on.
 * What moving here buys is that every TYPE around them is checked, so a renamed or re-signatured class is a build
 * failure rather than a {@code NoSuchMethodException} at the moment every MinecraftForge mod registers nothing:
 *
 * <ul>
 *   <li>{@code RegisterEvent}'s 3-arg constructor is package-private. Its parameter TYPES are now written out
 *       and checked; only its existence is a runtime question.</li>
 *   <li>{@code ForgeRegistry.getWrapper()} is package-private and returns the package-private
 *       {@code NamespacedWrapper} — a type no code outside that package can name, on either side.</li>
 *   <li>{@code RegistryManager.registries} is package-private. {@code registryView} beside it is public but
 *       holds {@code IForgeRegistry}, and the event wants the {@code ForgeRegistry} implementation.</li>
 * </ul>
 *
 * <p>The {@code BuiltInRegistries} walk stays a field enumeration on purpose: there are ~80 of them, listing
 * them by hand would be a list that goes stale silently, and {@code Registry.class.isAssignableFrom} checks the
 * type of every field it accepts.
 */
public final class KernelForgeRegistries {
	private KernelForgeRegistries() {
	}

	/**
	 * {@code {key, vanillaRegistryOrNull, forgeRegistryOrNull}} for every registry {@code RegisterEvent} is posted
	 * for, computed once and reused across every mod.
	 *
	 * <p>The enumeration walks EVERY registry in {@code BuiltInRegistries} — with its Forge wrapper when one
	 * exists and a NULL wrapper when it does not, which is what genuine {@code GameData.postRegisterEvents} does
	 * — plus every Forge-CUSTOM registry in {@code RegistryManager.ACTIVE} (forge:fluid_type, holder_set_type,
	 * the modifier serializers, …), which are absent from {@code BuiltInRegistries} and so invisible to the first
	 * loop.
	 *
	 * <p>The vanilla-only half matters: {@code creative_mode_tab} has no Forge wrapper, so while it was skipped
	 * every mod's creative tab silently failed to exist and its content was unreachable in the creative menu.
	 * Macaw's Bridges registered its 303 blocks and items and none of them could be found.
	 *
	 * <p>Crossing as {@code Object[]} rather than a record because the boot-side loop that consumes this cannot
	 * name any of the three types.
	 */
	public static List<Object[]> targets() throws Exception {
		List<Object[]> targets = new ArrayList<>();
		Set<ForgeRegistry<?>> covered = Collections.newSetFromMap(new IdentityHashMap<>());

		for (Field f : BuiltInRegistries.class.getFields()) {
			if (!Registry.class.isAssignableFrom(f.getType())) continue;
			Registry<?> vanilla = (Registry<?>) f.get(null);
			ResourceKey<? extends Registry<?>> key = vanilla.key();
			ForgeRegistry<?> forgeReg = forgeRegistryFor(key);
			targets.add(new Object[] {key, vanilla, forgeReg});
			if (forgeReg != null) covered.add(forgeReg);
		}

		addCustomRegistries(targets, covered);
		return targets;
	}

	/**
	 * {@code RegistryManager.getRegistry} is declared {@code <V>(ResourceKey<? extends Registry<V>>)}, and a key
	 * whose element type is a wildcard cannot bind V — there is no way to spell "whatever this one happens to
	 * be". One raw cast, here, rather than at the call site: the reflective version could not see the constraint
	 * at all, so this is javac reporting something real rather than an obstacle it invented.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static ForgeRegistry<?> forgeRegistryFor(ResourceKey<? extends Registry<?>> key) {
		return RegistryManager.ACTIVE.getRegistry((ResourceKey) key);
	}

	/** The Forge-only registries, which are in no {@code BuiltInRegistries} field. */
	private static void addCustomRegistries(List<Object[]> targets, Set<ForgeRegistry<?>> covered) {
		try {
			Field registriesField = RegistryManager.class.getDeclaredField("registries");
			registriesField.setAccessible(true);
			Map<?, ?> all = (Map<?, ?>) registriesField.get(RegistryManager.ACTIVE);
			Method getWrapper = ForgeRegistry.class.getDeclaredMethod("getWrapper"); // nullable; never throws
			getWrapper.setAccessible(true);

			for (Object value : all.values()) {
				if (!(value instanceof ForgeRegistry<?> forgeReg) || !covered.add(forgeReg)) continue;
				// Per item: some custom registries (serializer / datapack registries) have no NamespacedWrapper.
				// Skipping ONE must not abort the rest — an earlier all-loop try/catch dropped fluid_type. The
				// event's Registry argument is only stored (registerFluids and friends register through the
				// ForgeRegistry), so a null there is fine.
				try {
					Object wrapper = null;
					try {
						wrapper = getWrapper.invoke(forgeReg);
					} catch (Throwable noWrapper) {
						// leave null
					}
					targets.add(new Object[] {forgeReg.getRegistryKey(), wrapper, forgeReg});
				} catch (Throwable perReg) {
					ForbricLog.debug("[Forbric/Forge] skip custom-registry RegisterEvent target: %s",
							String.valueOf(Reflect.unwrap(perReg)));
				}
			}
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Forge] could not enumerate custom Forge registries for RegisterEvent "
					+ "(fluid_type etc. may stay unbound)", Reflect.unwrap(t));
		}
	}

	/**
	 * Builds the 3-arg {@code RegisterEvent} for one target and posts it on one mod's bus.
	 *
	 * <p>Called once per (registry, mod) pair by the boot-side loop, which owns the isolation and the active
	 * container. Resolving the bus per call rather than per mod is a map lookup on the {@code BusGroup} and keeps
	 * the whole event lifetime inside this method.
	 */
	public static void post(Object busGroup, Object[] target) throws Exception {
		@SuppressWarnings("unchecked")
		ResourceKey<? extends Registry<?>> key = (ResourceKey<? extends Registry<?>>) target[0];
		RegisterEvent event = EVENT_CTOR.newInstance(key, (ForgeRegistry<?>) target[2], (Registry<?>) target[1]);
		RegisterEvent.getBus((BusGroup) busGroup).post(event);
	}

	/**
	 * {@code RegisterEvent}'s constructor is package-private, so it is resolved once rather than per pair — there
	 * are ~90 registries times every MinecraftForge mod.
	 */
	private static final Constructor<RegisterEvent> EVENT_CTOR = eventCtor();

	private static Constructor<RegisterEvent> eventCtor() {
		try {
			Constructor<RegisterEvent> c = RegisterEvent.class.getDeclaredConstructor(
					ResourceKey.class, ForgeRegistry.class, Registry.class);
			c.setAccessible(true);
			return c;
		} catch (NoSuchMethodException gone) {
			throw new IllegalStateException("MinecraftForge's RegisterEvent no longer declares "
					+ "(ResourceKey, ForgeRegistry, Registry) — every MinecraftForge mod would register nothing, "
					+ "so this fails here rather than once per mod", gone);
		}
	}
}
