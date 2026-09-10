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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.forbric.loader.impl.forge.bridge.ForbricFabricPackCompat;

/**
 * Tri-in-one Fabric-mod compat: at the HEAD of {@code PackRepository.rebuildSelected} — the vanilla method whose
 * Fabric-resource-loader mixin ({@code handleAutoEnableDisable}) walks every pack calling
 * {@code fabric$parentsEnabled} — seed the fabric-resource-loader {@code parentsPredicate} field on any pack still
 * missing it (see {@link ForbricFabricPackCompat}). This injects at a guaranteed point on the crash path (right
 * before Fabric's handler), sidestepping the merged-base {@code Pack}-construction paths that empirically leave the
 * mixin's own constructor initializer unrun (NeoForge's two-constructor {@code Pack} shape). A no-op wherever the
 * field is absent (no fabric-resource-loader) or already set (single-ecosystem bases) — never a regression there.
 * String {@code targets} so it compiles against sponge-mixin alone; injects at HEAD only (never cancels).
 */
@Mixin(targets = "net.minecraft.server.packs.repository.PackRepository")
public class PackRepositoryFabricCompatMixin {
	@Inject(method = "rebuildSelected", at = @At("HEAD"))
	private void forbric$seedFabricParentsPredicate(java.util.Collection<String> ids,
			CallbackInfoReturnable<java.util.List<?>> cir) {
		ForbricFabricPackCompat.seedRepository(this);
	}
}
