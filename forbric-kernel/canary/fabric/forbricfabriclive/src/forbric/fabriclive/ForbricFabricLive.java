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

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.api.metadata.CustomValue;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/**
 * The Fabric canary's {@code main} entrypoint. It asserts, from inside a real mod, that the sovereign kernel
 * provides a working Fabric ecosystem — and prints one grep-able line per proven property for the M2 gate.
 *
 * <p>Nothing here is Forbric-specific: every call is ordinary published Fabric API that any third-party mod makes.
 */
public final class ForbricFabricLive implements ModInitializer {
	/** The registry entry this mod adds; the server entrypoint reads it back after the freeze. */
	public static final Identifier CANARY_STAT = Identifier.fromNamespaceAndPath("forbricfabriclive", "canary");
	public static final Identifier CANARY_BLOCK = Identifier.fromNamespaceAndPath("forbricfabriclive", "canary_block");

	@Override
	public void onInitialize() {
		System.out.println("[ForbricFabricLive] onInitialize (Fabric main entrypoint) on the sovereign kernel");

		FabricLoader loader = FabricLoader.getInstance();

		System.out.println("[ForbricFabricLive] env=" + loader.getEnvironmentType()
				+ " gameVersion=" + loader.getRawGameVersion()
				+ " mods=" + loader.getAllMods().size()
				+ " namespace=" + loader.getMappingResolver().getCurrentRuntimeNamespace());

		// Builtin mods must be resolvable: every real mod declares depends on these.
		System.out.println("[ForbricFabricLive] builtins minecraft=" + loader.isModLoaded("minecraft")
				+ " java=" + loader.isModLoaded("java")
				+ " fabricloader=" + loader.isModLoaded("fabricloader"));

		// The JiJ-nested library must have been extracted, discovered, and registered.
		System.out.println("[ForbricFabricLive] jij nested lib loaded=" + loader.isModLoaded("forbricfabriclib"));

		// Our own metadata + custom values must round-trip out of fabric.mod.json.
		ModContainer self = loader.getModContainer("forbricfabriclive").orElseThrow();
		CustomValue canary = self.getMetadata().getCustomValue("forbric:canary");
		System.out.println("[ForbricFabricLive] metadata version=" + self.getMetadata().getVersion().getFriendlyString()
				+ " customKind=" + canary.getAsObject().get("kind").getAsString()
				+ " customExpects=" + canary.getAsObject().get("expects").getAsArray().size());

		// findPath must reach inside our own jar through the zip filesystem.
		System.out.println("[ForbricFabricLive] findPath(fabric.mod.json) present="
				+ self.findPath("fabric.mod.json").isPresent());

		// The object share, used by mods to talk without a compile dependency.
		loader.getObjectShare().put("forbricfabriclive:hello", "world");
		System.out.println("[ForbricFabricLive] objectShare roundtrip="
				+ loader.getObjectShare().get("forbricfabriclive:hello"));

		// A CUSTOM entrypoint key with an arbitrary type — the shape Jade ("jade"), ModMenu, and JEI all use.
		// Two declarations: a plain class, and a Class::STATIC_FIELD reference.
		int probes = 0;

		for (EntrypointContainer<Runnable> c : loader.getEntrypointContainers("forbric:probe", Runnable.class)) {
			c.getEntrypoint().run();
			probes++;
		}

		System.out.println("[ForbricFabricLive] custom entrypoint key 'forbric:probe' ran " + probes + " probe(s)");

		// The registration window must be OPEN: a Fabric mod registers content by calling Registry.register
		// directly from onInitialize. CUSTOM_STAT is a Registry<Identifier>, so this needs no item/block plumbing.
		Registry.register(BuiltInRegistries.CUSTOM_STAT, CANARY_STAT, CANARY_STAT);
		// And one BLOCK, for gate-m14: a Forbric client that carries this canary registers its block before any
		// other Fabric mod's, so every block a shared mod registers after it sits one id further along than on a
		// server without the canary — which is exactly the difference a registry sync has to correct. A block
		// with no model is fine: nothing renders it, the gate only asks the server to place one and the client to
		// read its NAME back.
		Registry.register(BuiltInRegistries.BLOCK, CANARY_BLOCK, new net.minecraft.world.level.block.Block(
				net.minecraft.world.level.block.state.BlockBehaviour.Properties.of()
						.setId(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BLOCK, CANARY_BLOCK))));
		System.out.println("[ForbricFabricLive] registered block " + CANARY_BLOCK + " at raw id "
				+ BuiltInRegistries.BLOCK.getId(BuiltInRegistries.BLOCK.getValue(CANARY_BLOCK)));
		// And one ITEM, for the same reason: item ids are what inventory sync carries, and unlike block states they
		// have no neighbouring id to land harmlessly on — an unremapped item registry hands the player the wrong
		// item, which is the cleanest thing a gate can read back.
		// Built reflectively, not because the canary needs to be clever, but because naming Item's members makes
		// javac complete Item's whole member table, which drags in the merged LivingEntity.class — and the byte-merge
		// left a type-annotation attribute on it that javac refuses ("Cannot attach type annotations"). The game
		// loads that class fine; only javac's reader is that strict.
		try {
			Class<?> itemCls = Class.forName("net.minecraft.world.item.Item");
			Class<?> propsCls = Class.forName("net.minecraft.world.item.Item$Properties");
			Object props = propsCls.getConstructor().newInstance();
			props = propsCls.getMethod("setId", net.minecraft.resources.ResourceKey.class).invoke(props,
					net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ITEM, CANARY_BLOCK));
			Object item = itemCls.getConstructor(propsCls).newInstance(props);
			@SuppressWarnings({"unchecked", "rawtypes"})
			Object registered = Registry.register((Registry) BuiltInRegistries.ITEM, CANARY_BLOCK, item);
			System.out.println("[ForbricFabricLive] registered item " + CANARY_BLOCK + " at raw id "
					+ ((net.minecraft.core.Registry<Object>) (Registry) BuiltInRegistries.ITEM).getId(registered));
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("canary item could not be registered", e);
		}
		System.out.println("[ForbricFabricLive] registered custom stat, registry contains it="
				+ BuiltInRegistries.CUSTOM_STAT.containsKey(CANARY_STAT));
	}
}
