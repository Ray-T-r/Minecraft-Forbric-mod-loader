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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.mixin.MergedBaseCalleeSwaps;

/**
 * The descriptor-identical callee swaps between stock 26.2 and the merged base, pinned; the safety test that says
 * none of them may be rewritten at the call site; and the rows MixinRetarget follows, which must be among them.
 *
 * <p>A swap is: inside a vanilla method that still exists in the merged base (same owner, name, descriptor), a
 * callee {@code owner.name desc} of the vanilla body that the merged body never calls, while the merged body
 * calls {@code owner.other desc} (same owner, same descriptor, a different name) that the vanilla body never
 * calls. Synthetic names ({@code access$N}, {@code lambda$…}) are renumbering, not swaps.
 */
class MergedBaseCalleeSwapTest {
	private static final Path RUN = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"), "run").normalize();
	private static final Path MERGED = RUN.resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final Path FORGE_RT = RUN.resolve("forge-runtime/forge-runtime.jar");
	private static final Path NEO_RT = RUN.resolve("neoforge-runtime/neoforge-runtime.jar");

	/** {@code owner.method | callee-owner.vanilla→merged desc}, one line per swap — 25 on the staged base. */
	static final Set<String> PINNED = new TreeSet<>(List.of(
			"net/minecraft/client/Minecraft.rollbackResourcePacks(Ljava/lang/Throwable;Lnet/minecraft/client/GameLoadCookie;)V | net/minecraft/server/packs/repository/PackRepository.getSelectedIds→getSelectedPacks ()Ljava/util/Collection;",
			"net/minecraft/client/data/models/EquipmentAssetProvider.humanoidAndMountArmor(Ljava/lang/String;)Lnet/minecraft/client/resources/model/EquipmentClientInfo; | net/minecraft/resources/Identifier.withDefaultNamespace→parse (Ljava/lang/String;)Lnet/minecraft/resources/Identifier;",
			"net/minecraft/client/data/models/EquipmentAssetProvider.onlyHumanoid(Ljava/lang/String;)Lnet/minecraft/client/resources/model/EquipmentClientInfo; | net/minecraft/resources/Identifier.withDefaultNamespace→parse (Ljava/lang/String;)Lnet/minecraft/resources/Identifier;",
			"net/minecraft/client/data/models/EquipmentAssetProvider.run(Lnet/minecraft/data/CachedOutput;)Ljava/util/concurrent/CompletableFuture; | net/minecraft/client/data/models/EquipmentAssetProvider.bootstrap→registerModels (Ljava/util/function/BiConsumer;)V",
			"net/minecraft/client/gui/screens/TitleScreen.extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V | net/minecraft/util/ARGB.white→as8BitChannel (F)I",
			"net/minecraft/gametest/framework/GameTestServer.logFailedTest(Lnet/minecraft/gametest/framework/GameTestInfo;)V | org/slf4j/Logger.info→error (Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V",
			"net/minecraft/gametest/framework/GameTestServer.logFailedTest(Lnet/minecraft/gametest/framework/GameTestInfo;)V | org/slf4j/Logger.info→error (Ljava/lang/String;[Ljava/lang/Object;)V",
			"net/minecraft/server/level/ServerLevel.addPlayer(Lnet/minecraft/server/level/ServerPlayer;)V | net/minecraft/world/level/entity/PersistentEntitySectionManager.addNewEntity→addNewEntityWithoutEvent (Lnet/minecraft/world/level/entity/EntityAccess;)Z",
			"net/minecraft/server/level/ServerPlayer.teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer; | net/minecraft/server/level/ServerPlayer.unsetRemoved→revive ()V",
			"net/minecraft/server/network/ServerConnectionListener.startMemoryChannel()Ljava/net/SocketAddress; | net/minecraft/server/network/EventLoopGroupHolder.eventLoopGroup→serverEventLoopGroup ()Lio/netty/channel/EventLoopGroup;",
			"net/minecraft/server/network/ServerConnectionListener.startTcpServerListener(Ljava/net/InetAddress;I)V | net/minecraft/server/network/EventLoopGroupHolder.eventLoopGroup→serverEventLoopGroup ()Lio/netty/channel/EventLoopGroup;",
			"net/minecraft/server/packs/metadata/pack/PackFormat$IntermediaryFormat.lambda$validateNewFormat$4(Ljava/lang/String;Ljava/lang/String;Lnet/minecraft/util/InclusiveRange;)Ljava/lang/String; | net/minecraft/util/InclusiveRange.maxInclusive→minInclusive ()Ljava/lang/Comparable;",
			"net/minecraft/util/Util.doFetchChoiceType(Lcom/mojang/datafixers/DSL$TypeReference;Ljava/lang/String;)Lcom/mojang/datafixers/types/Type; | org/slf4j/Logger.error→debug (Ljava/lang/String;Ljava/lang/Object;)V",
			"net/minecraft/world/entity/Entity.updateSwimming()V | net/minecraft/world/entity/Entity.isUnderWater→canStartSwimming ()Z",
			"net/minecraft/world/entity/player/Player.hurtServer(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)Z | java/lang/Math.min→max (FF)F",
			"net/minecraft/world/inventory/AnvilMenu.createResult()V | net/minecraft/world/inventory/AnvilMenu.broadcastChanges→createResultInternal ()V",
			"net/minecraft/world/level/block/Blocks.lambda$static$241(Lnet/minecraft/world/item/DyeColor;)Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties; | net/minecraft/world/level/block/state/BlockBehaviour$Properties.noOcclusion→requiresCorrectToolForDrops ()Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;",
			"net/minecraft/world/level/block/DoorBlock.playerWillDestroy(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/entity/player/Player;)Lnet/minecraft/world/level/block/state/BlockState; | net/minecraft/world/entity/player/Player.preventsBlockDrops→isCreative ()Z",
			"net/minecraft/world/level/chunk/LevelChunk.lambda$replaceWithPacketData$0(Lnet/minecraft/util/ProblemReporter$ScopedCollector;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/BlockEntityType;Lnet/minecraft/nbt/CompoundTag;)V | net/minecraft/world/level/block/entity/BlockEntity.loadWithComponents→handleUpdateTag (Lnet/minecraft/world/level/storage/ValueInput;)V",
			"net/minecraft/world/level/chunk/LevelChunkSection$1BlockCounter.accept(Lnet/minecraft/world/level/block/state/BlockState;I)V | net/minecraft/world/level/block/state/BlockState.isAir→isEmpty ()Z",
			"net/minecraft/world/level/chunk/LevelChunkSection.setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState; | net/minecraft/world/level/block/state/BlockState.isAir→isEmpty ()Z",
			"net/minecraft/world/level/chunk/storage/SerializableChunkData.copyOf(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;)Lnet/minecraft/world/level/chunk/storage/SerializableChunkData; | net/minecraft/world/level/chunk/status/ChunkStatus.heightmapsAfter→getChunkSaveHeightmaps ()Ljava/util/EnumSet;",
			"net/minecraft/world/level/chunk/storage/SerializableChunkData.lambda$parse$1(Lnet/minecraft/world/level/chunk/status/ChunkStatus;Ljava/util/Map;Lnet/minecraft/nbt/CompoundTag;)V | net/minecraft/world/level/chunk/status/ChunkStatus.heightmapsAfter→getChunkSaveHeightmaps ()Ljava/util/EnumSet;",
			"net/minecraft/world/level/chunk/storage/SerializableChunkData.read(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/ai/village/poi/PoiManager;Lnet/minecraft/world/level/chunk/storage/RegionStorageInfo;Lnet/minecraft/world/level/ChunkPos;)Lnet/minecraft/world/level/chunk/ProtoChunk; | net/minecraft/world/level/chunk/status/ChunkStatus.heightmapsAfter→getChunkSaveHeightmaps ()Ljava/util/EnumSet;",
			"net/minecraft/world/level/storage/loot/LootTable.getRandomItems(Lnet/minecraft/world/level/storage/loot/LootContext;)Lit/unimi/dsi/fastutil/objects/ObjectArrayList; | net/minecraft/world/level/storage/loot/LootTable.getRandomItems→getRandomItemsRaw (Lnet/minecraft/world/level/storage/loot/LootContext;Ljava/util/function/Consumer;)V"));

	/**
	 * The swaps that DO pass the pure-delegate safety test on raw bytes, each with the reason it is still not
	 * rewritten at the call site. The safety test is necessary, not sufficient: a delegate can be pure in the jar
	 * and composed at load time.
	 */
	static final Map<String, String> ADMITTED_NOT_REWRITTEN = Map.of(
			"net/minecraft/server/level/ServerPlayer.teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer; | net/minecraft/server/level/ServerPlayer.unsetRemoved→revive ()V",
			"Entity.revive() is where ForgeCapabilityCompositionTransformer re-attaches MinecraftForge's reviveCaps at load time, so "
					+ "the merged callee is not a pure delegate once composed; rewriting the site back to unsetRemoved would drop "
					+ "capability revival on every cross-dimension teleport",
			"net/minecraft/client/data/models/EquipmentAssetProvider.run(Lnet/minecraft/data/CachedOutput;)Ljava/util/concurrent/CompletableFuture; | net/minecraft/client/data/models/EquipmentAssetProvider.bootstrap→registerModels (Ljava/util/function/BiConsumer;)V",
			"client data generation only; NeoForge's registerModels is its mod-datagen extension point and no staged mixin anchors "
					+ "on bootstrap — nothing to gain, and the extension point would be lost");

	@Test
	void theCensusOfCalleeSwapsIsExactlyThePinnedSet() throws Exception {
		Path vanilla = vanillaJar();
		assumeTrue(Files.isRegularFile(MERGED) && Files.isRegularFile(vanilla), "staged merged base or stock 26.2 absent");
		Set<String> found = new TreeSet<>();
		for (Swap swap : swaps(vanilla)) found.add(swap.line());
		assertEquals(PINNED, found, "descriptor-identical callee swaps between stock 26.2 and the merged base — re-derive, then decide");
	}

	/**
	 * Every swap that passes the safety test is listed with its reason in {@link #ADMITTED_NOT_REWRITTEN}; a new
	 * one means: build the base-side call-site rewrite (one INVOKE name per site, stack-neutral) beside
	 * SnippetConstructorFunnel, or write down why not.
	 */
	@Test
	void everyAdmittedSwapIsEitherRewrittenOrExplained() throws Exception {
		Path vanilla = vanillaJar();
		assumeTrue(Files.isRegularFile(MERGED) && Files.isRegularFile(vanilla) && Files.isRegularFile(FORGE_RT)
				&& Files.isRegularFile(NEO_RT), "staged merged base, carriers or stock 26.2 absent");
		Map<String, ClassNode> world = new HashMap<>();
		List<String> admitted = new ArrayList<>();
		for (Swap swap : swaps(vanilla)) {
			if (isPureDelegatePair(swap, world)) admitted.add(swap.line());
		}
		assertEquals(new TreeSet<>(ADMITTED_NOT_REWRITTEN.keySet()), new TreeSet<>(admitted),
				"a swap now passes the safety test — build the base-side call-site rewrite (one INVOKE name per site, "
						+ "stack-neutral) beside SnippetConstructorFunnel, or explain in ADMITTED_NOT_REWRITTEN why not");
	}

	@Test
	void everyKnownRowIsAmongTheSwapsTheCensusFinds() throws Exception {
		Path vanilla = vanillaJar();
		assumeTrue(Files.isRegularFile(MERGED) && Files.isRegularFile(vanilla), "staged merged base or stock 26.2 absent");
		Set<String> found = new TreeSet<>();
		for (Swap swap : swaps(vanilla)) found.add(swap.line());
		for (MergedBaseCalleeSwaps.Swap row : MergedBaseCalleeSwaps.KNOWN) {
			String line = row.target() + "." + row.method() + " | " + row.owner() + "." + row.vanillaName() + "→" + row.mergedName() + " " + row.desc();
			assertTrue(found.contains(line), "KNOWN row is not a swap the merged base makes any more: " + line);
		}
	}

	// ---------------------------------------------------------------------------------------------------------------

	record Swap(String owner, String method, String calleeOwner, String vanillaName, String mergedName, String desc) {
		String line() {
			return owner + "." + method + " | " + calleeOwner + "." + vanillaName + "→" + mergedName + " " + desc;
		}
	}

	private static List<Swap> swaps(Path vanilla) throws IOException {
		List<Swap> out = new ArrayList<>();
		try (ZipFile v = new ZipFile(vanilla.toFile()); ZipFile m = new ZipFile(MERGED.toFile())) {
			for (ZipEntry entry : v.stream().toList()) {
				if (!entry.getName().endsWith(".class") || !entry.getName().startsWith("net/minecraft/") && !entry.getName().startsWith("com/mojang/")) continue;
				ZipEntry mergedEntry = m.getEntry(entry.getName());
				if (mergedEntry == null) continue;
				ClassNode vn = read(v, entry), mn = read(m, mergedEntry);
				Map<String, MethodNode> mergedMethods = new HashMap<>();
				for (MethodNode mm : mn.methods) mergedMethods.put(mm.name + mm.desc, mm);
				for (MethodNode vm : vn.methods) {
					MethodNode mm = mergedMethods.get(vm.name + vm.desc);
					if (mm == null) continue;
					Map<String, Integer> vc = calls(vm), mc = calls(mm);
					for (Map.Entry<String, Integer> e : vc.entrySet()) {
						String key = e.getKey();    // owner|name|desc
						if (mc.containsKey(key)) continue;
						String[] parts = key.split("\\|");
						if (synthetic(parts[1])) continue;
						// Exactly ONE candidate replacement: same owner, same descriptor, a different real name, absent
						// from the vanilla body, called as often. Two candidates (Identifier.withDefaultNamespace →
						// parse or withPrefix) is a rewrite of the expression, not a swap of the callee.
						List<String> candidates = new ArrayList<>();
						for (Map.Entry<String, Integer> f : mc.entrySet()) {
							String[] q = f.getKey().split("\\|");
							if (!q[0].equals(parts[0]) || !q[2].equals(parts[2]) || q[1].equals(parts[1]) || synthetic(q[1])) continue;
							if (vc.containsKey(f.getKey())) continue;
							if (!f.getValue().equals(e.getValue())) continue;
							candidates.add(q[1]);
						}
						if (candidates.size() == 1) out.add(new Swap(vn.name, vm.name + vm.desc, parts[0], parts[1], candidates.get(0), parts[2]));
					}
				}
			}
		}
		return out;
	}

	private static boolean synthetic(String name) {
		return name.startsWith("access$") || name.startsWith("lambda$") || name.contains("$");
	}

	private static Map<String, Integer> calls(MethodNode m) {
		Map<String, Integer> out = new LinkedHashMap<>();
		if (m.instructions == null) return out;
		for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode c) out.merge(c.owner + "|" + c.name + "|" + c.desc, 1, Integer::sum);
		}
		return out;
	}

	/**
	 * The safety test: the merged callee, resolved on the merged hierarchy including the carriers' extension
	 * interfaces, is a body of loads + one call to the vanilla-named callee (same descriptor) + return; or the
	 * reverse.
	 */
	private static boolean isPureDelegatePair(Swap swap, Map<String, ClassNode> world) throws IOException {
		MethodNode merged = resolve(swap.calleeOwner(), swap.mergedName(), swap.desc(), world);
		MethodNode vanilla = resolve(swap.calleeOwner(), swap.vanillaName(), swap.desc(), world);
		return (merged != null && delegatesTo(merged, swap.vanillaName(), swap.desc()))
				|| (vanilla != null && delegatesTo(vanilla, swap.mergedName(), swap.desc()));
	}

	private static boolean delegatesTo(MethodNode body, String callee, String desc) {
		if (body.instructions == null || body.instructions.size() == 0) return false;
		MethodInsnNode call = null;
		for (AbstractInsnNode insn = body.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			int op = insn.getOpcode();
			if (op < 0) continue;
			if (insn instanceof VarInsnNode) continue;
			if (insn instanceof MethodInsnNode c) {
				if (call != null || !callee.equals(c.name) || !desc.equals(c.desc)) return false;
				call = c;
				continue;
			}
			if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) continue;
			return false;
		}
		return call != null;
	}

	/** Superclass chain first, then every interface reachable — where NeoForge's extension defaults live. */
	private static MethodNode resolve(String owner, String name, String desc, Map<String, ClassNode> world) throws IOException {
		Deque<String> queue = new ArrayDeque<>();
		Set<String> seen = new HashSet<>();
		queue.add(owner);
		while (!queue.isEmpty()) {
			String current = queue.poll();
			if (!seen.add(current)) continue;
			ClassNode node = load(current, world);
			if (node == null) continue;
			for (MethodNode m : node.methods) if (m.name.equals(name) && m.desc.equals(desc) && m.instructions != null && m.instructions.size() > 0) return m;
			if (node.superName != null) queue.add(node.superName);
			if (node.interfaces != null) queue.addAll(node.interfaces);
		}
		return null;
	}

	private static ClassNode load(String internal, Map<String, ClassNode> world) throws IOException {
		if (world.containsKey(internal)) return world.get(internal);
		ClassNode node = null;
		for (Path jar : List.of(MERGED, NEO_RT, FORGE_RT)) {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				ZipEntry entry = zip.getEntry(internal + ".class");
				if (entry != null) { node = read(zip, entry); break; }
			}
		}
		world.put(internal, node);
		return node;
	}

	private static ClassNode read(ZipFile zip, ZipEntry entry) throws IOException {
		try (InputStream in = zip.getInputStream(entry)) {
			ClassNode node = new ClassNode();
			new ClassReader(in.readAllBytes()).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			return node;
		}
	}

	private static Path vanillaJar() {
		String env = System.getenv("MC_DIR");
		return Path.of(env != null && !env.isBlank() ? env : System.getProperty("user.home") + "/Library/Application Support/minecraft")
				.resolve("versions/26.2/26.2.jar");
	}
}
