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

package net.forbric.kernel.mixin;

import java.util.List;
import java.util.Set;

/**
 * The guest mixins that are known not to fit the merged base, and why.
 *
 * <p>Every entry here was found empirically, by bisecting a real fabric-api 0.154.0 boot on the merged base with
 * {@code run/mixin-inventory.sh} and {@code -Dforbric.disableMixinConfigs}, and each is the SMALLEST unit that
 * restores a clean boot. They ship as defaults so an installed instance works out of the box; a user or the
 * inventory tool can turn them off wholesale with {@code -Dforbric.mergedBaseCompat=off}, and add to them with
 * {@code -Dforbric.suppressMixins} / {@code -Dforbric.disableMixinConfigs}.
 *
 * <p>This list is a debt, not a design. Each entry is a mixin whose target the vanilla+Forge+NeoForge byte-merge
 * moved, and each one costs a real feature. The mixin ADAPTER (plan M7) exists to retarget them instead, at which
 * point entries leave this list. Nothing here is suppressed to paper over a kernel bug — the boot is clean and
 * loud without them.
 *
 * <p><b>The owned-target ones are now DERIVED, not hand-listed.</b> {@link KernelGuestMixinAdapter} scans every
 * guest mixin's {@code @Mixin} target and auto-suppresses the ones that hit a class the merge rebuilt from
 * Forge/NeoForge (the whole {@code net/minecraft/client/gui/render/}, {@code .../resources/model/}, … pipeline) —
 * on a real fabric-api client that derives ~70, where this list used to name two by hand. What stays below is only
 * what the adapter CANNOT see: a mixin that applies to a vanilla-owned class and then breaks at RUNTIME, whose
 * target carries no Forge/NeoForge provenance signal.
 */
public final class MergedBaseMixinCompat {
	private MergedBaseMixinCompat() {
	}

	/** {@code -Dforbric.mergedBaseCompat=off} disables the built-in lists (used by the inventory tool). */
	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty("forbric.mergedBaseCompat", "on"));
	}

	/**
	 * Individual mixins to drop, as {@code <config>:<MixinEntry>}. The rest of each config still applies.
	 *
	 * <ul>
	 *   <li><b>registry-sync {@code BootstrapMixin} + {@code MainMixin}</b> — a coupled pair: the first redirects
	 *       {@code BuiltInRegistries.createContents()} to DELAY the registry freeze, the second re-runs
	 *       {@code BuiltInRegistries.bootStrap()} after mod init to perform it. Dropping only one gives
	 *       {@code IllegalStateException: Registry is already frozen}. Both are redundant here: the sovereign
	 *       kernel owns the single freeze and opens its own registration window around mod init. (Independently,
	 *       {@code BootstrapMixin.afterInitialize} could not apply anyway — its {@code @At(INVOKE,
	 *       target=Bootstrap.wrapStreams()V)} anchor does not exist, because Forge/NeoForge won the byte-merge of
	 *       {@code Bootstrap.bootStrap} and their version never calls {@code wrapStreams()}.)</li>
	 *   <li><b>registry-sync CLIENT {@code MinecraftMixin}</b> — the client twin of the same freeze-timing scheme:
	 *       it re-runs {@code BuiltInRegistries.bootStrap()} from {@code Minecraft.<init>} after the kernel already
	 *       froze, giving {@code IllegalStateException: Registry is already frozen}. Same redundancy as its three
	 *       common-config siblings above — the kernel owns the single freeze — so it is suppressed for the same
	 *       reason; without it no fabric-api CLIENT can boot.</li>
	 *   <li><b>registry-sync {@code RegistryDataLoaderMixin}</b> — binds a {@code ScopedValue IS_SERVER} in one
	 *       wrap and reads it in another, re-binding across the async boundary in two more. The re-bind wraps do
	 *       not match the merged base's {@code RegistryDataLoader.load}, so the read throws
	 *       {@code NoSuchElementException: ScopedValue not bound} on a ForkJoin worker. Cost: registry-sync's
	 *       server/client leniency during datapack registry load — a remote-sync concern, outside v1 scope.</li>
	 *   <li><b>loot-api-v3 {@code ReloadableServerRegistriesMixin}</b> — its generated callback loads a local slot
	 *       the merged base's method does not have: {@code VerifyError: Bad local variable type} at
	 *       {@code ReloadableServerRegistries.handler$…$modifyLootTable}. Cost: the loot-table modification API.</li>
	 *   <li><b>creative-tab CLIENT {@code CreativeModeInventoryScreenMixin}</b> — Fabric's creative-screen PAGER.
	 *       The merged screen already carries NeoForge's pager as a base patch ({@code CreativeTabsScreenPage},
	 *       the "&lt; N/M &gt;" buttons), so with this mixin woven BOTH pagers run at once — and they fight:
	 *       Fabric cancels {@code extractTabButton} for any tab not on ITS OWN static {@code currentPage} (page
	 *       math over vanilla {@code CreativeModeTabs.tabs()} positions), which NeoForge's page-flip buttons never
	 *       change. Net effect: a mod tab beyond the first ten was registered, sorted, searchable, and
	 *       {@code shouldDisplay()==true}, yet drawn on NO page — Fabric hid it on NeoForge's page 2 while
	 *       NeoForge's pager kept it off page 1. One pager must own the merged screen, and only NeoForge's is part
	 *       of the base. Cost: the {@code FabricCreativeModeInventoryScreen} duck interface is no longer implanted,
	 *       so a Fabric mod extending creative-screen paging would ClassCastException — none of the current set
	 *       does, and that API could never work correctly under the NeoForge pager anyway.</li>
	 * </ul>
	 *
	 * <p>{@code fabric-resource-loader-v1}'s {@code PackRepositoryMixin} USED to be suppressed here — it made the
	 * vanilla datapack stop contributing (13 empty dynamic registries, "Missing data pack fabric-convention-tags-v2"),
	 * the server half of the Fabric gap. It is no longer suppressed: the actual fault was PackMixin's
	 * {@code parentsPredicate} field initializer being MIS-WOVEN by Mixin into NeoForge's recursive {@code Pack}
	 * constructor loop (so top-level packs kept a null predicate and every one read as hidden), and
	 * {@link PostMixinFixups} repairs that at the source. Now the whole module runs on both sides.
	 *
	 * <p>{@code fabric-rendering-v1}'s {@code GuiRendererMixin} + {@code GameRendererMixin} USED to be hand-listed
	 * here and are the reason {@link KernelGuestMixinAdapter} exists — the archetypal owned-target case (NeoForge won
	 * {@code GuiRenderer.<init>}, re-typed its {@code List} param, orphaned the {@code pictureInPictureRenderers}
	 * field the mixin {@code @Shadow}s; erasure hides it so the inject applies then reads null → NPE). The adapter now
	 * derives both from the {@code net/minecraft/client/gui/render/} prefix, so they are gone from this list.
	 */
	public static final List<String> SUPPRESSED_MIXINS = List.of(
			"fabric-registry-sync-v0.mixins.json:RegistryDataLoaderMixin",
			"fabric-registry-sync-v0.mixins.json:BootstrapMixin",
			"fabric-registry-sync-v0.mixins.json:MainMixin",
			"fabric-registry-sync-v0.client.mixins.json:MinecraftMixin",
			"fabric-loot-api-v3.mixins.json:ReloadableServerRegistriesMixin",
			"fabric-creative-tab-api-v1.client.mixins.json:CreativeModeInventoryScreenMixin");

	/**
	 * Whole mixin configs to leave unregistered, because no sub-selection of their mixins is coherent.
	 *
	 * <p>Empty. {@code fabric-resource-loader-v1.mixins.json} used to be here, on the reasoning that its
	 * {@code PackMixin} both supplies the {@code FabricPack} duck-interface and breaks the vanilla data pack, making
	 * the module all-or-nothing. <b>That was wrong, and it cost the largest feature gap on the Fabric side.</b>
	 * Bisecting with {@code -Dforbric.enableMixinConfigs} showed {@code PackMixin} is innocent — suppressing it
	 * leaves the 13 empty dynamic registries fixed but yields {@code ClassCastException: Pack cannot be cast to
	 * FabricPack}, whereas suppressing {@code PackRepositoryMixin} instead and keeping the other 13 mixins is
	 * gate-m2b GREEN. With the module registered, the client now serves all 45 fabric-api module packs
	 * ({@code Reloading ResourceManager: vanilla, fabric-api, …, fabric-resource-loader-v1, …}).
	 *
	 * <p>Keep this list empty unless a module genuinely has no coherent sub-selection: a whole-config entry hides
	 * which single mixin is at fault, and the entry outlives the merged base it was measured against.
	 * {@code -Dforbric.enableMixinConfigs=<config>} re-tests one without an edit-and-rebuild.
	 */
	public static final Set<String> DISABLED_CONFIGS = Set.of();

	/**
	 * Mixins the {@link KernelGuestMixinAdapter} must NOT auto-suppress, as {@code <config>:<MixinEntry>} — the
	 * inverse of {@link #SUPPRESSED_MIXINS}, for what the adapter structurally cannot see.
	 *
	 * <p>The adapter judges a mixin by its TARGET's provenance, which is the right signal for behaviour. It is the
	 * wrong signal for a mixin that also contributes a duck-type INTERFACE, because the mod then casts the target to
	 * that interface: suppressing it does not merely drop a feature, it makes the cast throw.
	 *
	 * <ul>
	 *   <li><b>Jade {@code GuiGraphicsExtractorMixin}</b> — {@code implements JadeGuiGraphics} on the owned
	 *       {@code GuiGraphicsExtractor}. Suppressed, Jade's {@code OverlayRenderer} threw
	 *       {@code ClassCastException: GuiGraphicsExtractor cannot be cast to JadeGuiGraphics} on every frame it drew
	 *       its overlay — which aborted {@code Minecraft.renderFrame} at {@code extract}, BEFORE {@code render} and
	 *       {@code GpuSurface.present}, so the frame was never drawn OR presented. Symptom: Jade shows nothing when
	 *       you look at a block, and from that moment the display freezes on the last good frame, so the game looks
	 *       like it stopped responding to keys (input polling is fine — {@code Minecraft.run} calls
	 *       {@code RenderSystem.pollEvents} BEFORE {@code runTick}, so the throw never blocks it). Safe to keep: the
	 *       merged {@code GuiGraphicsExtractor} still has the {@code minecraft} field it {@code @Shadow}s and the
	 *       {@code containsPointInScissor} method it injects into.</li>
	 * </ul>
	 *
	 * <p>This is a hand list ON PURPOSE. The obvious generalisation — "keep every mixin that contributes a non-Mixin
	 * interface" — was implemented and MEASURED: it keeps 27 mixins on a fabric-api + Jade client, including
	 * {@code fabric-rendering-v1}'s {@code GuiRendererMixin}, and crashes the client during {@code Minecraft.<init>}
	 * with {@code NullPointerException: Cannot invoke "java.util.Map.size()" because "m" is null} at
	 * {@code GuiRenderer.handler$…$mutableSpecialElementRenderers} — i.e. it reintroduces the exact orphaned-@Shadow
	 * archetype the adapter was built to prevent. fabric-api's renderer mixins contribute interfaces AND carry
	 * behaviour that the merge broke, so "contributes an interface" cannot separate them from Jade's. A sound general
	 * rule would have to prove the mixin's {@code @Shadow}n fields are still ASSIGNED in the merged target; until
	 * that exists, entries are added here one measured mixin at a time. Extend without a rebuild via
	 * {@code -Dforbric.keepMixins=<config>:<MixinEntry>,…}.
	 */
	public static final List<String> KEPT_MIXINS = List.of(
			"jade.mixins.json:GuiGraphicsExtractorMixin");
}
