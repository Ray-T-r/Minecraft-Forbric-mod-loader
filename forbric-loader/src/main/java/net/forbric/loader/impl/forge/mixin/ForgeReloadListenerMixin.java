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

package net.forbric.loader.impl.forge.mixin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.forbric.loader.impl.util.ForbricLog;

/**
 * Guarantees Forge's {@code ForgeInternalHandler.INSTANCE} (the {@code LootModifierManager}) is built for every
 * {@code ReloadableServerResources} the game constructs — deterministically, independent of whether the Forge
 * game-bus {@code AddReloadListenerEvent} dispatch actually reached {@code onResourceReload}.
 *
 * <p><b>Why this is needed.</b> Forge lazily builds {@code LootModifierManager} inside the {@code @SubscribeEvent}
 * {@code ForgeInternalHandler.onResourceReload(AddReloadListenerEvent)}, and {@code getLootModifierManager()} throws
 * {@code "Can not retrieve LootModifierManager until resources have loaded once"} until then. On the Forbric
 * client-hosted integrated server that event did not populate the manager during the world's data reload, so the
 * first loot roll (e.g. water destroying a block underwater → {@code Block.dropResources}) crashed. The patched MC
 * genuinely calls {@code ForgeEventFactory.onResourceReload} → {@code AddReloadListenerEvent.BUS.fire(...)}, and
 * the listener is registered by {@code ForgeMod.<init>}, yet {@code INSTANCE} stayed null — the game-bus dispatch
 * to that listener did not take effect on this path. Rather than depend on that fragile dispatch, we build the
 * manager ourselves from the freshly-constructed resources' own registries.
 *
 * <p><b>Mechanism (reflection only, no Forge/MC compile dependency, keeps the loader Apache-2.0).</b> At the
 * constructor tail we have {@code this} (a fully-built {@code ReloadableServerResources}) and can obtain its
 * {@code HolderLookup.Provider} via {@code fullRegistries().lookup()}. If {@code getLootModifierManager()} still
 * throws, we construct a real {@code AddReloadListenerEvent(this, provider)} (public ctor) and invoke
 * {@code ForgeInternalHandler.onResourceReload(event)} — which sets {@code INSTANCE}. This runs BEFORE Forge's own
 * fire on the reload path, so when the genuine dispatch does work it simply rebuilds and overwrites {@code INSTANCE}
 * (also wiring the manager into the reload so datapack global loot modifiers actually load); when it does not, our
 * build prevents the crash. The {@code LootModifierManager} ctor is lazy (it's a {@code SimpleJsonResourceReloadListener}
 * whose data loads in {@code apply()}), so building it here is cheap and never touches unloaded resources.
 *
 * <p>String {@code targets} + reflective body so this compiles against sponge-mixin alone. It shares the class with
 * Fabric's own {@code fabric-resource-loader-v1} {@code ReloadableServerResourcesMixin} but injects a different
 * method ({@code <init>} vs {@code lambda$loadResources$2}), so there is no injector conflict.
 */
@Mixin(targets = "net.minecraft.server.ReloadableServerResources")
public class ForgeReloadListenerMixin {
	private static final AtomicBoolean FALLBACK_LOGGED = new AtomicBoolean();

	@Inject(method = "<init>", at = @At("RETURN"))
	private void forbric$ensureForgeLootManager(CallbackInfo ci) {
		ClassLoader cl = getClass().getClassLoader(); // the Knot classloader that carries MC + the Forge runtime

		Class<?> fih;
		Method getLootModifierManager;
		try {
			fih = Class.forName("net.minecraftforge.common.ForgeInternalHandler", false, cl);
			getLootModifierManager = fih.getDeclaredMethod("getLootModifierManager");
			getLootModifierManager.setAccessible(true);
		} catch (Throwable notForge) {
			return; // no traditional-Forge runtime present (e.g. pure-Fabric/intermediary mode) — nothing to do
		}

		try {
			getLootModifierManager.invoke(null);
			return; // already built (Forge's own reload dispatch populated it) — leave it alone
		} catch (InvocationTargetException expected) {
			// getLootModifierManager throws IllegalStateException while INSTANCE is null — build it below
		} catch (Throwable t) {
			ForbricLog.warn("could not probe Forge LootModifierManager state", t);
			return;
		}

		try {
			Object self = this; // the ReloadableServerResources instance this mixin was merged into
			Object holder = self.getClass().getMethod("fullRegistries").invoke(self);   // ReloadableServerRegistries$Holder
			Object provider = holder.getClass().getMethod("lookup").invoke(holder);     // HolderLookup$Provider

			Class<?> eventCls = Class.forName("net.minecraftforge.event.AddReloadListenerEvent", false, cl);
			Class<?> resourcesCls = Class.forName("net.minecraft.server.ReloadableServerResources", false, cl);
			Class<?> providerCls = Class.forName("net.minecraft.core.HolderLookup$Provider", false, cl);
			Object event = eventCls.getConstructor(resourcesCls, providerCls).newInstance(self, provider);

			Method onResourceReload = fih.getDeclaredMethod("onResourceReload", eventCls);
			onResourceReload.setAccessible(true);
			onResourceReload.invoke(null, event); // sets ForgeInternalHandler.INSTANCE from these registries

			if (FALLBACK_LOGGED.compareAndSet(false, true)) {
				ForbricLog.info("ensured Forge's LootModifierManager is initialized for the reloaded server resources"
						+ " (block/entity loot is safe). Forge's own reload dispatch, when it runs, rebuilds it and"
						+ " loads any datapack global loot modifiers; run with -Dforbric.debug for per-reload detail.");
			}
			ForbricLog.debug("ForgeReloadListenerMixin: built LootModifierManager at ReloadableServerResources <init>");
		} catch (Throwable t) {
			ForbricLog.warn("failed to initialize Forge LootModifierManager at ReloadableServerResources <init>", t);
		}
	}
}
