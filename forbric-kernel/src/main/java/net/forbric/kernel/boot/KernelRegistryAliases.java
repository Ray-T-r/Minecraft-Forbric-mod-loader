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

/**
 * Answers fabric-registry-sync's alias-target question for NeoForge's alias-first registration order.
 *
 * <p>A registry alias lets a mod rename an entry without breaking the old id. NeoForge keeps aliases in
 * {@code BaseMappedRegistry}, fabric-api in {@code fabric-registry-sync-v0}'s {@code MappedRegistryMixin}, whose
 * {@code addAlias} warns when the alias target is not registered yet. This class is what that warning asks
 * ({@link #aliasTargetPresent}, called from the bytecode {@code PostMixinFixups} points it at).
 *
 * <p>It used to also be the reading half of aliases on MinecraftForge's registry wrappers, whose overridden lookups
 * never ran fabric-api's alias rewrite; that half went with MinecraftForge. The name stays (renaming is Stage 6).
 */
public final class KernelRegistryAliases {
	private KernelRegistryAliases() {
	}

	/** {@code off} lets fabric-registry-sync warn about NeoForge's alias-first order as it does about any other. */
	static final String DEFERRED_REGISTER_ORDER = "forbric.aliasPresenceParity.deferredRegister";
	private static final String NEO_DEFERRED_REGISTER = "net.neoforged.neoforge.registries.DeferredRegister";

	/**
	 * Whether fabric-registry-sync's {@code addAlias} should consider its alias target present.
	 *
	 * <p>Called from that method's target-missing warning, which PostMixinFixups points at the registry's own
	 * {@code keySet()}; {@code keys} is that set. It answers the lookup exactly and never through an alias.
	 *
	 * <p>It also answers yes while NeoForge's {@code DeferredRegister.addEntries} is the caller. NeoForge applies a
	 * mod's aliases BEFORE it registers that mod's entries, in the same call, and its own {@code addAlias} never
	 * warns about it. On the merged base fabric-api's mixin-added {@code addAlias} overrides NeoForge's, so the
	 * order NeoForge chose produced "Adding sophisticatedcore:render_info_tag as an alias for …, but the latter
	 * doesn't exist" on every boot, for an entry registered a few lines later. A NeoForge mod gets its own
	 * ecosystem's behaviour there, not a Fabric warning about NeoForge's design. Walked only when the key set says
	 * no, and only a few frames: {@code addAlias} is rare, and the lambda NeoForge calls it through is hidden.
	 * {@code -Dforbric.aliasPresenceParity.deferredRegister=off} warns there again.
	 */
	public static boolean aliasTargetPresent(java.util.Set<?> keys, Object target) {
		if (keys != null && keys.contains(target)) return true;
		if ("off".equalsIgnoreCase(System.getProperty(DEFERRED_REGISTER_ORDER, "on"))) return false;
		return StackWalker.getInstance().walk(frames -> frames.limit(16).anyMatch(frame ->
				NEO_DEFERRED_REGISTER.equals(frame.getClassName()) && "addEntries".equals(frame.getMethodName())));
	}
}
