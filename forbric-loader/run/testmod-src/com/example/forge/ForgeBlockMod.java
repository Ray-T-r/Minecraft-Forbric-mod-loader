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

package com.example.forge;
import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
@Mod("forbricforge")
public class ForgeBlockMod {
  public ForgeBlockMod(IEventBus modBus) {
    System.out.println(">>> [ForgeBlockMod] @Mod constructed; subscribing to common setup");
    modBus.register(this);
  }
  @SubscribeEvent
  public void onCommonSetup(FMLCommonSetupEvent e) {
    // ROBUST PROOF: run real Minecraft registry code (remapped Mojmap -> intermediary) from a Forge @Mod handler
    Block stone = BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("minecraft", "stone"));
    Identifier stoneKey = BuiltInRegistries.BLOCK.getKey(stone);
    System.out.println(">>> [ForgeBlockMod] LIVE GAME ACCESS via Forge lifecycle: BuiltInRegistries.BLOCK has "
        + BuiltInRegistries.BLOCK.size() + " blocks; looked up minecraft:stone -> " + stone.getClass().getName()
        + " key=" + stoneKey + " <<<");
    // Attempt actual registration (documents the intrusive-holder timing nuance):
    try {
      Identifier id = Identifier.fromNamespaceAndPath("forbricforge", "example_block");
      ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
      Block block = new Block(BlockBehaviour.Properties.of().setId(key));
      Registry.register(BuiltInRegistries.BLOCK, key, block);
      System.out.println(">>> [ForgeBlockMod] REGISTERED forbricforge:example_block; present=" + BuiltInRegistries.BLOCK.containsKey(id) + " <<<");
    } catch (Throwable t) {
      System.out.println("[ForgeBlockMod] registration attempt (timing-sensitive in this MC build): " + t);
    }
  }
}
