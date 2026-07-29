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

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.forbric.loader.impl.forge.runtime.ForbricClientDualLifecycle;

/**
 * Tri-in-one CLIENT twin of {@link NeoDualLifecycleMixin}: on the MERGED (Forge+NeoForge+Fabric) client base,
 * {@code Minecraft.<init>} drives NeoForge's client mod-loading ({@code setupModResourcePacks} .. {@code finish})
 * but the byte-merge dropped Forge's own {@code ClientModLoader.begin(...)}, so Forge client mods never construct.
 * This injects right AFTER NeoForge's {@code ClientModLoader.setupModResourcePacks(PackRepository)} — the point
 * where the live Minecraft, its PackRepository and its ReloadableResourceManager are all populated AND the resource
 * manager's reload-listener list is still mutable (the later {@code finish()} point is past that freeze, so Forge's
 * {@code begin()} would throw an {@code UnsupportedOperationException} registering its reload listeners). This is
 * the same spot the pure-Forge ctor calls {@code begin()}. See {@link ForbricClientDualLifecycle} for the full
 * rationale and the registry-freeze handling.
 *
 * <p>{@code require = 0}: the {@code setupModResourcePacks} injection point exists ONLY on the merged NeoForge-won
 * client ctor.
 * On a single-ecosystem traditional-Forge client (whose patched ctor calls Forge's {@code begin} natively) or a
 * pure-Fabric client, that call isn't present, so this mixin must apply zero injections silently rather than fail —
 * that is what keeps the shipped single-Forge client deployment un-regressed. String {@code targets} + reflective
 * body so it compiles against sponge-mixin alone, with no NeoForge/Forge compile dependency; lives in the
 * {@code required: false} {@code forbric-neoforge-bridge.mixins.json}.
 */
@Mixin(targets = "net.minecraft.client.Minecraft")
public class ForbricClientDualLifecycleMixin {
	@Inject(
			method = "<init>(Lnet/minecraft/client/main/GameConfig;)V",
			at = @At(
					value = "INVOKE",
					target = "Lnet/neoforged/neoforge/client/loading/ClientModLoader;setupModResourcePacks(Lnet/minecraft/server/packs/repository/PackRepository;)V",
					shift = At.Shift.AFTER),
			require = 0,
			expect = 0)
	private void forbric$runForgeClientLifecycle(CallbackInfo ci) {
		ForbricClientDualLifecycle.runForgeClientLifecycle(ForbricClientDualLifecycleMixin.class.getClassLoader());
	}
}
