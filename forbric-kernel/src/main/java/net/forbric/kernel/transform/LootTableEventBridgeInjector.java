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

package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;

/**
 * Routes the two calls in {@code ReloadableServerRegistries} that bracket loot-table loading through the kernel,
 * so fabric-loot-api-v3's {@code LootTableEvents} can fire from the same point NeoForge's own
 * {@code LootTableLoadEvent} fires.
 *
 * <p>fabric-loot-api-v3's {@code ReloadableServerRegistriesMixin} cannot fit the merged base and stays pinned in
 * {@link net.forbric.kernel.mixin.MergedBaseMixinCompat}: NeoForge swapped the last two parameters of
 * {@code lambda$scheduleRegistryLoad$0} and split vanilla's one element map into two, so the mixin's handler
 * descriptor and its {@code @Local Map} can never bind (the recorded {@code VerifyError}). What survived the
 * merge, verbatim, are two single instructions in the class:
 * <ul>
 *   <li>{@code invokestatic EventHooks.loadLootTable(HolderLookup.Provider, Identifier, LootTable)} inside
 *       {@code lambda$scheduleRegistryLoad$1} — reached for every loaded {@code LootTable}, a {@code null} result
 *       drops the table. A constant-pool scan of the whole merged base finds it named there and nowhere else.</li>
 *   <li>{@code invokestatic TagLoader.loadTagsForRegistry(ResourceManager, WritableRegistry)} inside
 *       {@code lambda$scheduleRegistryLoad$0}, followed only by the registry's return — the program point the
 *       pinned mixin's {@code onLootTablesLoaded} fires {@code ALL_LOADED} from.</li>
 * </ul>
 * Each becomes the same call on {@code net.forbric.kernel.runtime.KernelLootBridge} (name and descriptor
 * unchanged, owner swapped — the {@code letDungeonsGenerateWithoutTheDataMap} shape). The runtime shim calls
 * NeoForge's real hook first, then hands the survivor to {@link net.forbric.kernel.boot.LootTableEventDispatch},
 * which performs the pinned mixin's REPLACE / MODIFY sequence through fabric's own public surface and fires
 * {@code ALL_LOADED} after the tags load. Both-or-nothing: exactly one of each call or the class stays as it is.
 *
 * <p>{@code -Dforbric.lootBridge=off}: the injector stands down and the dispatch returns identity, so NeoForge's
 * hook and {@code TagLoader} run exactly as before.
 */
public final class LootTableEventBridgeInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.lootBridge";

	static final String TARGET = "net.minecraft.server.ReloadableServerRegistries";
	static final String BRIDGE = "net/forbric/kernel/runtime/KernelLootBridge";

	static final String LOAD_LOOT_TABLE = "loadLootTable";
	static final String LOAD_LOOT_TABLE_DESC =
			"(Lnet/minecraft/core/HolderLookup$Provider;Lnet/minecraft/resources/Identifier;"
					+ "Lnet/minecraft/world/level/storage/loot/LootTable;)Lnet/minecraft/world/level/storage/loot/LootTable;";
	static final String TAG_LOADER = "net/minecraft/tags/TagLoader";
	static final String LOAD_TAGS = "loadTagsForRegistry";
	static final String LOAD_TAGS_DESC =
			"(Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/core/WritableRegistry;)V";

	/** NeoForge's event hooks — the family that won this call site. */
	static final String EVENT_HOOKS = ForeignType.EVENT_FACTORY.internal(Ecosystem.NEOFORGE);

	private int routed;

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override
	public String name() {
		return "forbric:loot-table-event-bridge";
	}

	@Override
	public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("switched off by -D" + PROPERTY);
		return AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.REQUIRED,
				"every Fabric mod's LootTableEvents.REPLACE/MODIFY/ALL_LOADED listener is registered and never called"));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (!enabled() || classBytes == null || classBytes.length == 0 || !TARGET.equals(className)) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		List<MethodInsnNode> loot = new ArrayList<>();
		List<MethodInsnNode> tags = new ArrayList<>();
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC) continue;
				if (BRIDGE.equals(call.owner)) return classBytes;    // already routed
				if (EVENT_HOOKS.equals(call.owner) && LOAD_LOOT_TABLE.equals(call.name) && LOAD_LOOT_TABLE_DESC.equals(call.desc)) {
					loot.add(call);
				} else if (TAG_LOADER.equals(call.owner) && LOAD_TAGS.equals(call.name) && LOAD_TAGS_DESC.equals(call.desc)) {
					tags.add(call);
				}
			}
		}
		if (loot.size() != 1 || tags.size() != 1) {
			ForbricLog.warn("[Forbric/LootBridge] %s has %d loot-table load site(s) and %d tag-load site(s), not one of "
					+ "each — the seam has drifted, so Fabric LootTableEvents stay unfired", className, loot.size(),
					tags.size());
			return classBytes;
		}

		loot.get(0).owner = BRIDGE;
		tags.get(0).owner = BRIDGE;
		routed += 2;
		ForbricLog.info("[Forbric/LootBridge] routed %d loot-table load site(s) and %d tag-load site(s) in "
				+ "ReloadableServerRegistries through the kernel — fabric-loot-api-v3's mixin cannot fit the merged "
				+ "base, so LootTableEvents fire from NeoForge's own LootTableLoadEvent seam", 1, 1);

		// Owner swaps only: no instruction, frame or stack change.
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** How many call sites were routed, for the boot summary. */
	public int routedSites() {
		return routed;
	}
}
