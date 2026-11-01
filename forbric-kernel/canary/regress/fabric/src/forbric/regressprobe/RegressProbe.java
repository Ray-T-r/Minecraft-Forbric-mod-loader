package forbric.regressprobe;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.item.v1.CustomDamageHandler;
import net.fabricmc.fabric.api.item.v1.FabricItem;
import net.fabricmc.fabric.api.registry.FuelValueEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.animal.golem.SnowGolem;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Fabric behaviours measured on a dedicated server (run/compat/regress-gate.py): fabric-item-api's custom damage handler,
 * its stack-aware furnace remainder, and BCLib's tag shears on sheep and snow golems, beside items with an entity entry of
 * their own (a Fabric one here, a NeoForge one in the probe's NeoForge half). Writes regress-probe.json, then stops.
 */
public class RegressProbe implements ModInitializer {
	static final AtomicInteger DAMAGE_CALLS = new AtomicInteger();
	static final AtomicInteger REMAINDER_ASKED = new AtomicInteger();
	static Item DAMAGE_TOOL, REMAINDER_FUEL, TAG_SHEARS, OWN_ENTRY_SHEARS;
	record Case(String name, boolean pass, String detail) { }
	static final List<Case> CASES = new ArrayList<>();

	static ResourceKey<Item> key(String path) {
		return ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("regressprobe", path));
	}

	@Override public void onInitialize() {
		CustomDamageHandler handler = (stack, amount, entity, slot, breakCallback) -> { DAMAGE_CALLS.incrementAndGet(); return 0; };
		Item.Properties damage = new Item.Properties().setId(key("damage_tool")).durability(100);
		damage = ((FabricItem.Properties) (Object) damage).customDamage(handler);
		DAMAGE_TOOL = Registry.register(BuiltInRegistries.ITEM, key("damage_tool"), new Item(damage));
		REMAINDER_FUEL = Registry.register(BuiltInRegistries.ITEM, key("remainder_fuel"), new RemainderItem(new Item.Properties().setId(key("remainder_fuel"))));
		TAG_SHEARS = Registry.register(BuiltInRegistries.ITEM, key("tag_shears"), new Item(new Item.Properties().setId(key("tag_shears"))));
		OWN_ENTRY_SHEARS = Registry.register(BuiltInRegistries.ITEM, key("own_entry_shears"), new OwnEntryShears(new Item.Properties().setId(key("own_entry_shears"))));
		FuelValueEvents.BUILD.register((builder, context) -> builder.add(REMAINDER_FUEL, 200));
		ServerLifecycleEvents.SERVER_STARTED.register(RegressProbe::run);
	}

	static void test(String name, Supplier<String> body) {
		String detail;
		boolean pass;
		try { detail = body.get(); pass = detail.startsWith("ok"); }
		catch (Throwable t) { detail = t.toString(); pass = false; }
		CASES.add(new Case(name, pass, detail));
		System.out.println("[regressprobe] " + name + " " + (pass ? "PASS" : "FAIL") + " " + detail);
	}

	static String expect(boolean ok, String detail) { return (ok ? "ok " : "no ") + detail; }

	static void run(MinecraftServer server) {
		ServerLevel level = server.overworld();
		BlockPos base = new BlockPos(0, 120, 0);
		// 1. fabric-item-api's CustomDamageHandler: hurtAndBreak(int, LivingEntity, EquipmentSlot) asks it on Fabric.
		test("damage.mob", () -> {
			Zombie zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
			int before = DAMAGE_CALLS.get();
			ItemStack stack = new ItemStack(DAMAGE_TOOL);
			stack.hurtAndBreak(1, zombie, EquipmentSlot.MAINHAND);
			return expect(DAMAGE_CALLS.get() == before + 1 && stack.getDamageValue() == 0, "handler calls=" + (DAMAGE_CALLS.get() - before) + " damage=" + stack.getDamageValue());
		});
		test("damage.player", () -> {
			FakePlayer player = FakePlayer.get(level);
			int before = DAMAGE_CALLS.get();
			ItemStack stack = new ItemStack(DAMAGE_TOOL);
			stack.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
			return expect(DAMAGE_CALLS.get() == before + 1 && stack.getDamageValue() == 0, "handler calls=" + (DAMAGE_CALLS.get() - before) + " damage=" + stack.getDamageValue() + " infinite=" + player.hasInfiniteMaterials());
		});
		test("damage.vanillaControl", () -> {
			Zombie zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
			ItemStack stack = new ItemStack(Items.IRON_SWORD);
			stack.hurtAndBreak(1, zombie, EquipmentSlot.MAINHAND);
			return expect(stack.getDamageValue() == 1, "iron sword damage=" + stack.getDamageValue());
		});
		test("damage.breaks", () -> {
			// The handler sees the break callback as the vanilla call would run it: a 1-durability tool it lets through breaks.
			Zombie zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
			ItemStack stack = new ItemStack(Items.IRON_SWORD);
			stack.setDamageValue(stack.getMaxDamage() - 1);
			stack.hurtAndBreak(1, zombie, EquipmentSlot.MAINHAND);
			return expect(stack.isEmpty(), "worn iron sword after one more point: " + stack);
		});
		// 2. fabric-item-api's stack-aware crafting remainder for furnace fuel.
		test("fuel.remainder.consumeFuel", () -> {
			try {
				Method consume = AbstractFurnaceBlockEntity.class.getDeclaredMethod("consumeFuel", NonNullList.class, ItemStack.class);
				consume.setAccessible(true);
				NonNullList<ItemStack> items = NonNullList.withSize(3, ItemStack.EMPTY);
				items.set(1, new ItemStack(REMAINDER_FUEL));
				int asked = REMAINDER_ASKED.get();
				consume.invoke(null, items, items.get(1));
				return expect(items.get(1).is(Items.STICK), "slot after burning one remainder_fuel: " + items.get(1) + " asked=" + (REMAINDER_ASKED.get() - asked));
			} catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
		});
		test("fuel.remainder.lavaControl", () -> {
			try {
				Method consume = AbstractFurnaceBlockEntity.class.getDeclaredMethod("consumeFuel", NonNullList.class, ItemStack.class);
				consume.setAccessible(true);
				NonNullList<ItemStack> items = NonNullList.withSize(3, ItemStack.EMPTY);
				items.set(1, new ItemStack(Items.LAVA_BUCKET));
				consume.invoke(null, items, items.get(1));
				return expect(items.get(1).is(Items.BUCKET), "slot after burning a lava bucket: " + items.get(1));
			} catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
		});
		test("fuel.remainder.furnace", () -> {
			BlockPos pos = base.offset(4, 0, 0);
			level.setBlock(pos, Blocks.FURNACE.defaultBlockState(), 3);
			FurnaceBlockEntity furnace = (FurnaceBlockEntity) level.getBlockEntity(pos);
			furnace.setItem(0, new ItemStack(Items.RAW_IRON));
			furnace.setItem(1, new ItemStack(REMAINDER_FUEL));
			boolean fuel = level.fuelValues().isFuel(new ItemStack(REMAINDER_FUEL));
			BlockState state = level.getBlockState(pos);
			AbstractFurnaceBlockEntity.serverTick(level, pos, state, furnace);
			return expect(furnace.getItem(1).is(Items.STICK), "isFuel=" + fuel + " fuel slot after the first tick: " + furnace.getItem(1));
		});
		// 3. BCLib's tag shears (c:tools/shear) on mobs. Native Fabric with BCLib: vanilla's mobInteract shears for anything in
		// the tag, before Player.interactOn asks the item's own entity entry; NeoForge: a NeoForge item's own entry runs.
		TagKey<Item> shearTag = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("c", "tools/shear"));
		Item neoOwnEntry = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("regressprobeneo", "own_entry_shears"));
		test("shears.tagLoaded", () -> expect(new ItemStack(TAG_SHEARS).is(shearTag) && new ItemStack(OWN_ENTRY_SHEARS).is(shearTag)
				&& neoOwnEntry != Items.AIR && new ItemStack(neoOwnEntry).is(shearTag), "tag_shears, own_entry_shears and regressprobeneo:own_entry_shears in c:tools/shear"));
		test("shears.sheep.vanilla", () -> {
			Sheared s = shearSheep(level, base, Items.SHEARS);
			// Sheared once: one IShearable drop of 1-3 wool and one point of durability.
			return expect(s.sheared() && s.wool() >= 1 && s.wool() <= 3 && s.damage() == 1 && !s.named(), s.toString());
		});
		test("shears.sheep.tag", () -> { Sheared s = shearSheep(level, base, TAG_SHEARS); return expect(s.sheared() && s.wool() >= 1 && !s.named(), s.toString()); });
		test("shears.sheep.ownEntry.fabric", () -> {
			Sheared s = shearSheep(level, base, OWN_ENTRY_SHEARS);
			return expect(s.sheared() && !s.named(), "a Fabric item's own entry is pre-empted, as on Fabric: " + s);
		});
		test("shears.sheep.ownEntry.neo", () -> {
			Sheared s = shearSheep(level, base, neoOwnEntry);
			return expect(!s.sheared() && s.named(), "a NeoForge item's own entry runs, as on NeoForge: " + s);
		});
		for (Item tool : List.of(Items.SHEARS, TAG_SHEARS)) {
			String which = tool == Items.SHEARS ? "vanilla" : "tag";
			test("shears.snowGolem." + which, () -> {
				SnowGolem golem = EntityTypes.SNOW_GOLEM.create(level, EntitySpawnReason.COMMAND);
				golem.setPos(base.getX() + 10.5, base.getY(), base.getZ() + 0.5);
				level.addFreshEntity(golem);
				boolean before = golem.hasPumpkin();
				FakePlayer player = FakePlayer.get(level);
				player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(tool));
				Object result = player.interactOn(golem, InteractionHand.MAIN_HAND, golem.position());
				boolean after = golem.hasPumpkin();
				golem.discard();
				clearDrops(level, base);
				return expect(before && !after, "result=" + result + " pumpkin before=" + before + " after=" + after);
			});
		}
		test("shears.stickControl", () -> {
			Sheared s = shearSheep(level, base, Items.STICK);
			return expect(!s.sheared() && s.wool() == 0, "a stick does not shear: " + s);
		});
		StringBuilder json = new StringBuilder("{\"cases\":[");
		for (int i = 0; i < CASES.size(); i++) {
			Case c = CASES.get(i);
			if (i > 0) json.append(',');
			json.append("{\"name\":\"").append(c.name()).append("\",\"pass\":").append(c.pass()).append(",\"detail\":\"")
					.append(c.detail().replace("\\", "\\\\").replace("\"", "'")).append("\"}");
		}
		json.append("]}\n");
		try { Files.writeString(Path.of("regress-probe.json"), json); } catch (Exception e) { throw new RuntimeException(e); }
		System.out.println("[regressprobe] done " + CASES.stream().filter(Case::pass).count() + "/" + CASES.size());
		server.halt(false);
	}

	record Sheared(Object result, boolean sheared, long wool, int damage, boolean named) { }

	/** A fresh sheep at the shearing spot, a fake player holding {@code tool}, one interaction; drops counted, then cleared. */
	static Sheared shearSheep(ServerLevel level, BlockPos base, Item tool) {
		clearDrops(level, base);
		Sheep sheep = EntityTypes.SHEEP.create(level, EntitySpawnReason.COMMAND);
		sheep.setPos(base.getX() + 8.5, base.getY(), base.getZ() + 0.5);
		level.addFreshEntity(sheep);
		FakePlayer player = FakePlayer.get(level);
		ItemStack held = new ItemStack(tool);
		player.setItemInHand(InteractionHand.MAIN_HAND, held);
		Object result = player.interactOn(sheep, InteractionHand.MAIN_HAND, sheep.position());
		boolean sheared = sheep.isSheared();
		boolean named = sheep.hasCustomName();
		long wool = level.getEntitiesOfClass(ItemEntity.class, sheep.getBoundingBox().inflate(4)).stream()
				.filter(e -> e.getItem().is(ItemTags.WOOL)).mapToLong(e -> e.getItem().getCount()).sum();
		sheep.discard();
		clearDrops(level, base);
		return new Sheared(result, sheared, wool, held.getDamageValue(), named);
	}

	static void clearDrops(ServerLevel level, BlockPos base) {
		for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(base).inflate(24))) drop.discard();
	}
}
