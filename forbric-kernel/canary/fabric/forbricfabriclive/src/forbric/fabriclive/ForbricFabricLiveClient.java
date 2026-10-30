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

package forbric.fabriclive;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** The canary's {@code client} entrypoint: the client-only fabric-api surfaces, each gated on its module. */
public final class ForbricFabricLiveClient implements ClientModInitializer {
	/** The block {@link #registerLateBlock} adds, when a gate asks for it. */
	public static final Identifier LATE_BLOCK = Identifier.fromNamespaceAndPath("forbricfabriclive", "late_block");

	/**
	 * Set by gate-m26 only ({@code -Dforbricfabriclive.lateBlock=true}). The other gates that stage this canary on a
	 * client (gate-m14 compares its registries against a pure Fabric server's) keep exactly the content they had.
	 */
	static final String LATE_BLOCK_SWITCH = "forbricfabriclive.lateBlock";

	@Override
	public void onInitializeClient() {
		System.out.println("[ForbricFabricLive] onInitializeClient (Fabric client entrypoint)");
		if (FabricLoader.getInstance().isModLoaded("fabric-model-loading-api-v1")) ModelProbe.install();
		if (Boolean.getBoolean(LATE_BLOCK_SWITCH)) registerLateBlock();
	}

	/**
	 * A block registered from {@code onInitializeClient}, and its blockstate id read back in a world.
	 *
	 * <p>This is the LATE registration wave the kernel's blockstate→id refill exists for. The kernel fills the map
	 * once when its registration window closes, before {@code Minecraft.<init>}; the Fabric client entrypoints run
	 * later, inside {@code Minecraft.<init>}, with the registries reopened, and a block registered there has no id
	 * until the map is rebuilt as that window closes. Without the refill {@code Block.getId} answers 0 for it, which is
	 * AIR, and every {@code block_update} about it describes air. MinecraftForge mods register in the same window today
	 * and the MinecraftForge canary carries this check for them; this is the check that stays when they go, because
	 * Fabric client entrypoints are what registers there afterwards.
	 *
	 * <p>The id is read in a world, on the render thread, after everything that could rebuild the map has run: a
	 * daemon thread waits for the client to have a level and hands the read to the game's own executor. A read from
	 * here would see the map before the window closes and prove nothing either way.
	 */
	private static void registerLateBlock() {
		Block block;
		try {
			block = Registry.register(BuiltInRegistries.BLOCK, LATE_BLOCK, new Block(BlockBehaviour.Properties.of()
					.setId(ResourceKey.create(Registries.BLOCK, LATE_BLOCK))));
			System.out.println("[ForbricFabricLive/LATEBLOCK] registered " + LATE_BLOCK
					+ " from onInitializeClient at raw id " + BuiltInRegistries.BLOCK.getId(block));
		} catch (Throwable failure) {
			System.out.println("[ForbricFabricLive/LATEBLOCK] registration FAILED: " + failure);
			return;
		}
		Thread probe = new Thread(() -> readBackInWorld(block), "ForbricFabricLive late-block probe");
		probe.setDaemon(true);
		probe.start();
	}

	private static void readBackInWorld(Block block) {
		long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(6);
		try {
			while (System.nanoTime() < deadline) {
				Minecraft minecraft = Minecraft.getInstance();
				if (minecraft != null && minecraft.level != null) {
					minecraft.execute(() -> {
						try {
							int id = Block.getId(block.defaultBlockState());
							System.out.println("[ForbricFabricLive/LATEBLOCK] " + LATE_BLOCK + " id=" + id
									+ " roundTrip=" + Block.stateById(id));
						} catch (Throwable failure) {
							System.out.println("[ForbricFabricLive/LATEBLOCK] read-back FAILED: " + failure);
						}
					});
					return;
				}
				Thread.sleep(500);
			}
			System.out.println("[ForbricFabricLive/LATEBLOCK] read-back FAILED: no world within six minutes");
		} catch (InterruptedException stopped) {
			Thread.currentThread().interrupt();
		}
	}
}
