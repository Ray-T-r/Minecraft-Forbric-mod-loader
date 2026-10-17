/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.ExpectCaught;

/**
 * {@link RecipeSyncFailSoftInjector}'s output, run: a recipe whose own serializer cannot network-encode it (Enchant
 * Craft's) is left out of NeoForge's recipe sync and the rest are sent, where as merged encoding the payload throws and
 * the joining player is disconnected.
 *
 * <p>The hook is the kernel's real {@code KernelRecipeSync}, compiled from {@code src/runtime/java} against stand-ins
 * for the game, NeoForge and netty types it names. The stand-in {@code PacketDistributor.sendToPlayer} encodes every
 * recipe of the payload through the same {@code RecipeHolder.STREAM_CODEC}, as sending does.
 */
@ExecutesInjector(RecipeSyncFailSoftInjector.class)
class RecipeSyncFailSoftInjectorExecutionTest {
	private static final Path HOOK_SOURCE = Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelRecipeSync.java");
	private static final String HOOKS = RecipeSyncFailSoftInjector.COMMON_HOOKS;
	private static final String HOLDER = "net.minecraft.world.item.crafting.RecipeHolder";

	private static final Map<String, String> STAND_INS = Map.ofEntries(
			Map.entry("io.netty.buffer.ByteBuf", """
					package io.netty.buffer;
					public class ByteBuf {
						public boolean release() {
							return true;
						}
					}
					"""),
			Map.entry("io.netty.buffer.Unpooled", """
					package io.netty.buffer;
					public final class Unpooled {
						public static ByteBuf buffer() {
							return new ByteBuf();
						}
					}
					"""),
			Map.entry("net.minecraft.core.RegistryAccess", "package net.minecraft.core; public interface RegistryAccess { }"),
			Map.entry("net.neoforged.neoforge.network.connection.ConnectionType",
					"package net.neoforged.neoforge.network.connection; public enum ConnectionType { NEOFORGE, OTHER }"),
			Map.entry("net.minecraft.network.RegistryFriendlyByteBuf", """
					package net.minecraft.network;

					import io.netty.buffer.ByteBuf;
					import net.minecraft.core.RegistryAccess;
					import net.neoforged.neoforge.network.connection.ConnectionType;

					public class RegistryFriendlyByteBuf {
						public RegistryFriendlyByteBuf(ByteBuf source, RegistryAccess access, ConnectionType type) {
						}
					}
					"""),
			Map.entry("net.minecraft.network.codec.StreamCodec", """
					package net.minecraft.network.codec;
					public interface StreamCodec<B, V> {
						void encode(B buffer, V value);
					}
					"""),
			Map.entry("net.minecraft.server.level.ServerPlayer", """
					package net.minecraft.server.level;

					import net.minecraft.core.RegistryAccess;
					import net.neoforged.neoforge.network.connection.ConnectionType;

					public class ServerPlayer {
						public static class Connection {
							public ConnectionType getConnectionType() {
								return ConnectionType.NEOFORGE;
							}
						}

						public final Connection connection = new Connection();

						public RegistryAccess registryAccess() {
							return new RegistryAccess() { };
						}
					}
					"""),
			Map.entry("net.minecraft.resources.ResourceKey", "package net.minecraft.resources; public record ResourceKey(String identifier) { }"),
			Map.entry("net.minecraft.world.item.crafting.Recipe", """
					package net.minecraft.world.item.crafting;
					public interface Recipe {
						String getSerializer();

						/** What the serializer's stream codec does with it; Enchant Craft's unit codec refuses its own recipe. */
						void encodeWith(Object buffer);
					}
					"""),
			Map.entry(HOLDER, """
					package net.minecraft.world.item.crafting;

					import net.minecraft.network.RegistryFriendlyByteBuf;
					import net.minecraft.network.codec.StreamCodec;
					import net.minecraft.resources.ResourceKey;

					public record RecipeHolder<T extends Recipe>(ResourceKey id, T value) {
						public static final StreamCodec<RegistryFriendlyByteBuf, RecipeHolder<?>> STREAM_CODEC =
								(buffer, holder) -> holder.value().encodeWith(buffer);
					}
					"""),
			Map.entry("net.minecraft.world.item.crafting.RecipeMap", """
					package net.minecraft.world.item.crafting;

					import java.util.List;

					public record RecipeMap(List<RecipeHolder<?>> values) {
					}
					"""),
			Map.entry("net.minecraft.core.registries.BuiltInRegistries", """
					package net.minecraft.core.registries;

					public class BuiltInRegistries {
						public static class Registry {
							public String getKey(Object serializer) {
								return String.valueOf(serializer);
							}
						}

						public static final Registry RECIPE_SERIALIZER = new Registry();
					}
					"""),
			Map.entry("net.neoforged.neoforge.network.payload.RecipeContentPayload", """
					package net.neoforged.neoforge.network.payload;

					import java.util.List;
					import java.util.Set;
					import net.minecraft.world.item.crafting.RecipeHolder;
					import net.minecraft.world.item.crafting.RecipeMap;

					public record RecipeContentPayload(Set<?> recipeTypes, List<RecipeHolder<?>> recipes) {
						public static RecipeContentPayload create(Set<?> recipeTypes, RecipeMap recipes) {
							return new RecipeContentPayload(recipeTypes, recipes.values());
						}
					}
					"""),
			Map.entry("net.neoforged.neoforge.network.PacketDistributor", """
					package net.neoforged.neoforge.network;

					import java.util.ArrayList;
					import java.util.List;
					import io.netty.buffer.Unpooled;
					import net.minecraft.network.RegistryFriendlyByteBuf;
					import net.minecraft.server.level.ServerPlayer;
					import net.minecraft.world.item.crafting.RecipeHolder;
					import net.neoforged.neoforge.network.payload.RecipeContentPayload;

					public class PacketDistributor {
						public static final List<Object> sent = new ArrayList<>();

						/** Encoding the payload: one recipe that will not encode and the player is disconnected. */
						public static void sendToPlayer(ServerPlayer player, RecipeContentPayload payload) {
							RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.registryAccess(),
									player.connection.getConnectionType());
							for (RecipeHolder<?> recipe : payload.recipes()) RecipeHolder.STREAM_CODEC.encode(buffer, recipe);
							sent.add(payload);
						}
					}
					"""),
			Map.entry(HOOKS, """
					package net.neoforged.neoforge.common;

					import java.util.Set;
					import net.minecraft.server.level.ServerPlayer;
					import net.minecraft.world.item.crafting.RecipeMap;
					import net.neoforged.neoforge.network.PacketDistributor;
					import net.neoforged.neoforge.network.payload.RecipeContentPayload;

					public class CommonHooks {
						public static void sendRecipes(ServerPlayer player, Set<?> recipeTypes, RecipeMap recipes) {
							RecipeContentPayload payload = RecipeContentPayload.create(recipeTypes, recipes);
							PacketDistributor.sendToPlayer(player, payload);
						}
					}
					"""),
			Map.entry("fixture.Shaped", """
					package fixture;
					public record Shaped(String name) implements net.minecraft.world.item.crafting.Recipe {
						public String getSerializer() {
							return "minecraft:crafting_shaped";
						}

						public void encodeWith(Object buffer) {
						}
					}
					"""),
			Map.entry("fixture.ApplyEnchant", """
					package fixture;
					public record ApplyEnchant() implements net.minecraft.world.item.crafting.Recipe {
						public String getSerializer() {
							return "enchantcraft:apply_enchant";
						}

						public void encodeWith(Object buffer) {
							throw new IllegalStateException("Can't encode fixture.ApplyEnchant, expected a different instance");
						}
					}
					"""));

	private static Object holder(ClassLoader loader, String id, Object recipe) throws Throwable {
		Object key = InjectorExecution.construct(loader.loadClass("net.minecraft.resources.ResourceKey"), id);
		return InjectorExecution.construct(loader.loadClass(HOLDER), key, recipe);
	}

	private static void send(ClassLoader loader, Object... holders) throws Throwable {
		Object map = InjectorExecution.construct(loader.loadClass("net.minecraft.world.item.crafting.RecipeMap"), List.of(holders));
		Object player = InjectorExecution.construct(loader.loadClass("net.minecraft.server.level.ServerPlayer"));
		InjectorExecution.invokeStatic(loader.loadClass(HOOKS), "sendRecipes", player, Set.of("minecraft:crafting"), map);
	}

	private static List<?> sent(ClassLoader loader) throws ReflectiveOperationException {
		return (List<?>) InjectorExecution.getStatic(loader.loadClass("net.neoforged.neoforge.network.PacketDistributor"), "sent");
	}

	@ExpectCaught("left recipe enchantcraft:apply_enchant")
	@Test void aRecipeItsOwnSerializerCannotEncodeIsLeftOutAndTheRestAreSent(@TempDir Path work) throws Throwable {
		assertTrue(Files.isRegularFile(HOOK_SOURCE), "the game-side hook's source is part of the checkout: " + HOOK_SOURCE.toAbsolutePath());
		Map<String, String> sources = new HashMap<>(STAND_INS);
		sources.put("net/forbric/kernel/runtime/KernelRecipeSync.java", Files.readString(HOOK_SOURCE));
		Map<String, byte[]> original = InjectorExecution.compile(work, sources);
		String internal = HOOKS.replace('.', '/');
		byte[] filtered = InjectorExecution.transform(new RecipeSyncFailSoftInjector(), HOOKS, original.get(internal), EnvType.SERVER);
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(internal, filtered);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(filtered, loader));

		Class<?> shaped = loader.loadClass("fixture.Shaped");
		Object planks = holder(loader, "minecraft:oak_planks", InjectorExecution.construct(shaped, "planks"));
		Object enchant = holder(loader, "enchantcraft:apply_enchant", InjectorExecution.construct(loader.loadClass("fixture.ApplyEnchant")));
		Object sticks = holder(loader, "minecraft:stick", InjectorExecution.construct(shaped, "sticks"));
		send(loader, planks, enchant, sticks);
		assertEquals(1, sent(loader).size(), "the player is not disconnected: the payload goes out");
		assertEquals(List.of(planks, sticks), InjectorExecution.invoke(sent(loader).get(0), "recipes"),
				"only the recipe that will not encode is left out, and the order stands");

		send(loader, planks, sticks);
		assertEquals(List.of(planks, sticks), InjectorExecution.invoke(sent(loader).get(1), "recipes"), "nothing to leave out");

		ClassLoader merged = InjectorExecution.load(original);
		Class<?> stockShaped = merged.loadClass("fixture.Shaped");
		assertThrows(IllegalStateException.class, () -> send(merged,
				holder(merged, "minecraft:oak_planks", InjectorExecution.construct(stockShaped, "planks")),
				holder(merged, "enchantcraft:apply_enchant", InjectorExecution.construct(merged.loadClass("fixture.ApplyEnchant")))),
				"premise: as merged, encoding the payload throws and the joining player is dropped");
		assertSame(filtered, InjectorExecution.transform(new RecipeSyncFailSoftInjector(), HOOKS, filtered, EnvType.SERVER),
				"a filtered sendRecipes is left alone");
	}
}
