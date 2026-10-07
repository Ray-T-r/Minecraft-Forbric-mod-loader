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
	 * The registry and creative entries below are fallback pins: ForbricMixinService lifts them while
	 * FabricRegistryInitializationMixinAdapter, FabricRegistryLoaderMixinAdapter and
	 * FabricCreativePagerMixinAdapter retain their callbacks on the kernel's single freeze and carrier pager.
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
	 *       reason; without it no fabric-api CLIENT can boot. "Redundant" holds for registry CONTENTS, not for
	 *       timing: on a client the kernel's root registry is frozen from the end of the pre-{@code Minecraft}
	 *       window until {@code onClientEntrypoints} reopens it, a gap in which Fabric's is still open and no
	 *       Fabric main has run. Nothing the kernel forces may run Fabric code in that gap — its datapack-registry
	 *       declaration did, through {@code RegistryDataLoader.<clinit>}, and poisoned world loading for the
	 *       session; see {@code DatapackRegistryDeclaration}.</li>
	 *   <li><b>registry-sync {@code RegistryDataLoaderMixin}</b> — binds a {@code ScopedValue IS_SERVER} in one
	 *       wrap and reads it in another, re-binding across the async boundary in two more. The re-bind wraps do
	 *       not match the merged base's {@code RegistryDataLoader.load}, so the read throws
	 *       {@code NoSuchElementException: ScopedValue not bound} on a ForkJoin worker. Cost: registry-sync's
	 *       server/client context during datapack registry load. FabricRegistryLoaderMixinAdapter now restores
	 *       the original ScopedValue bindings and async propagation on both widened overloads; this pin returns
	 *       only with that adapter switched off.</li>
	 *   <li><b>loot-api-v3 {@code ReloadableServerRegistriesMixin}</b> — its generated callback loads a local slot
	 *       the merged base's method does not have: {@code VerifyError: Bad local variable type} at
	 *       {@code ReloadableServerRegistries.handler$…$modifyLootTable} — NeoForge swapped the last two parameters
	 *       of {@code lambda$scheduleRegistryLoad$0} and split vanilla's one element map into two, so
	 *       {@code modifyLootTable}'s {@code @Local Map} can never bind. Its sibling {@code onLootTablesLoaded}
	 *       (an {@code @Inject} at RETURN in vanilla's shape) is a different matter: {@code MixinHandlerShim} wraps
	 *       it along that lambda's row of {@code lambda-permutations.txt}, so it WOULD bind. The pin stays, and it no
	 *       longer costs the API: {@link net.forbric.kernel.boot.LootTableEventDispatch} fires REPLACE / MODIFY /
	 *       ALL_LOADED from NeoForge's own {@code LootTableLoadEvent} seam ({@code -Dforbric.lootBridge=off} to see
	 *       the old behaviour). Lifting the pin on the strength of the shim would fire ALL_LOADED twice, once from the
	 *       bridge and once from {@code onLootTablesLoaded}, and still fail on {@code modifyLootTable}.</li>
	 *   <li><b>creative-tab CLIENT {@code CreativeModeInventoryScreenMixin}</b> — Fabric's creative-screen PAGER.
	 *       The merged screen already carries NeoForge's pager as a base patch ({@code CreativeTabsScreenPage},
	 *       the "&lt; N/M &gt;" buttons), so with this mixin woven BOTH pagers run at once — and they fight:
	 *       Fabric cancels {@code extractTabButton} for any tab not on ITS OWN static {@code currentPage} (page
	 *       math over vanilla {@code CreativeModeTabs.tabs()} positions), which NeoForge's page-flip buttons never
	 *       change. Net effect: a mod tab beyond the first ten was registered, sorted, searchable, and
	 *       {@code shouldDisplay()==true}, yet drawn on NO page — Fabric hid it on NeoForge's page 2 while
	 *       NeoForge's pager kept it off page 1. One pager must own the merged screen, and only NeoForge's is part
	 *       of the base. What this mixin also carried is the implementation of {@code FabricCreativeModeInventoryScreen},
	 *       and that is NOT left out with it: fabric-api's class tweaker injects the interface into the screen
	 *       regardless, so without an implementation every call is the interface default,
	 *       {@code AssertionError("Implemented by mixin")} — owo-lib makes one each time the creative inventory
	 *       opens. {@link net.forbric.kernel.transform.CreativePagerBridgeInjector} implements it from NeoForge's pager
	 *       instead. FabricCreativePagerMixinAdapter keeps the original PageUp/PageDown callback and uses that
	 *       same pager, while leaving the conflicting second page state out. With the bridge
	 *       switched off, {@link #PINNED_CONTRACTS} has the mixin adapter leave out the mixins that rely on it.</li>
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
	 * field the mixin {@code @Shadow}s; erasure hides it so the inject applies then reads null → NPE). Both are gone
	 * from this list: {@link MixinFit}'s orphaned-{@code @Shadow} check names that hazard directly, and
	 * {@link PostMixinFixups#seedOrphanedPipRenderers} repairs it at the source so the mixin can simply be KEPT.
	 *
	 * <p><b>{@code fabric-model-loading-api-v1}'s {@code ModelManagerMixin} is the measured case for dropping a
 * PARTIAL — and is now TRIMMED rather than pinned.</b> The merged (NeoForge-patched) {@code ModelManager} no longer
 * reads model JSON through {@code CuboidModel.fromStream} in {@code lambda$loadBlockModels$2}, so the mixin's
 * {@code @Redirect} there cannot bind while its sibling {@code @ModifyArg} at {@code Pair.of} does — textbook
 * half-application: every model json is handed to Fabric's deserializer with the stream already consumed, all 4666
 * block models die on {@code JsonParseException: JSON data was null or empty}, and the world builds correctly out of
 * the missing-model cube — every block the magenta/black {@code missingno} checkerboard while the GUI, fonts and
 * item icons stay perfect. No crash, no error the renderer can attribute. Pinned whole, block models load but every
 * Fabric {@code ModelLoadingPlugin} is registered and never called. {@link
 * net.forbric.kernel.transform.GuestInjectorPruner} now removes exactly those two injectors from the mixin's bytes
 * before Mixin reads them, so the other eight apply as written; the pin moves to {@link #SUPPRESSED_UNLESS_PRUNED}
 * and returns only under {@code -Dforbric.guestInjectorPruner=off}. This is precisely why PARTIAL defaults to KEEP
 * but is logged with its missing anchors: strict mode drops 107 mixins including Sodium's core render path, whereas
 * the log points at the one that actually matters. Pin measured PARTIALs here, one at a time — and trim them when
 * the unfit injectors are separable.
 */
	public static final List<String> SUPPRESSED_MIXINS = List.of(
			"fabric-registry-sync-v0.mixins.json:RegistryDataLoaderMixin",
			"fabric-registry-sync-v0.mixins.json:BootstrapMixin",
			"fabric-registry-sync-v0.mixins.json:MainMixin",
			"fabric-registry-sync-v0.client.mixins.json:MinecraftMixin",
			"fabric-loot-api-v3.mixins.json:ReloadableServerRegistriesMixin",
			"fabric-creative-tab-api-v1.client.mixins.json:CreativeModeInventoryScreenMixin");


	/**
	 * A duck interface that {@code pin}, a {@link #SUPPRESSED_MIXINS} entry, implements on {@code target} (internal
	 * names).
	 *
	 * <p>{@link KernelGuestMixinAdapter}'s cast-contract closure only sees the mixins it drops itself, one config at a
	 * time; a pin is dropped by name, before, and from another mod's config. owo-lib's
	 * {@code MixinCreativeModeInventoryScreenMixin} implements the pin's interface and calls through it, and nothing
	 * connected the two. So each row is checked for every config: while the pin is in force and the target, as it
	 * reaches Mixin, lacks any method the interface leaves to its implementer ({@link MixinFit#unsupplied}), a mixin
	 * that implements, casts to or calls through the interface is left out and reported — a lost feature instead of an
	 * {@code AssertionError}. When something does back the target
	 * ({@link net.forbric.kernel.transform.CreativePagerBridgeInjector}), the row is inert.
	 */
	public record PinnedContract(String pin, String target, String contract) {
	}

	/** Fabric's creative pager, the pin whose interface {@link net.forbric.kernel.transform.CreativePagerBridgeInjector} backs. */
	public static final String CREATIVE_PAGER_PIN = "fabric-creative-tab-api-v1.client.mixins.json:CreativeModeInventoryScreenMixin";

	/** Every pin that implements an interface. {@code MergedBaseMixinCompatPinnedContractsTest} reads each off its jar. */
	public static final List<PinnedContract> PINNED_CONTRACTS = List.of(
			new PinnedContract(CREATIVE_PAGER_PIN,
					"net/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen",
					"net/fabricmc/fabric/api/client/creativetab/v1/FabricCreativeModeInventoryScreen"));

	/**
	 * Whether {@code pin} ({@code <config>:<MixinEntry>}) is left out on this boot: listed, and neither
	 * {@code -Dforbric.mergedBaseCompat=off} nor {@code -Dforbric.keepMixins} lifted it. Lifted, the pinned mixin
	 * applies and implements its interface itself — whatever stands in for it has to stand aside.
	 */
	public static boolean pinInForce(String pin) {
		int colon = pin.indexOf(':');
		return ForbricMixinService.suppressedMixinsFor(pin.substring(0, colon)).contains(pin.substring(colon + 1));
	}

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
	 * Pinned only while {@link net.forbric.kernel.transform.GuestInjectorPruner} is switched off. Each entry is a
	 * mixin the pruner trims down to the injectors that fit; with the pruner off it would apply half, which is the
	 * state that produced 4666 missingno block models — so the kill switch has to bring the whole-mixin pin back
	 * rather than leave the mixin loose. See the {@code ModelManagerMixin} paragraph above.
	 */
	public static final List<String> SUPPRESSED_UNLESS_PRUNED = List.of(
			"fabric-model-loading-api-v1.mixins.json:ModelManagerMixin");

	/** Only framework contracts without a structural fit rule remain pinned. Guest mod mixins use MixinFit. */
	public static final List<String> KEPT_MIXINS = List.of(
			"fabric-resource-loader-v1.mixins.json:SynchronizeRegistriesTaskMixin");
}
