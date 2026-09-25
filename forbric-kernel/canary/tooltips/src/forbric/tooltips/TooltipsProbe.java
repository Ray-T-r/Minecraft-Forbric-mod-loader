package forbric.tooltips;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.GsonBuilder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.enchantment.Enchantments;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.tooltip.TooltipAppender;
import net.neoforged.neoforge.event.RegisterTooltipAppendersEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Item tooltips rendered on a dedicated server through the merged ItemStack: vanilla's component lines (lore,
 * enchantments, attribute modifiers, durability), a NeoForge mod's appender placed before lore, a Fabric mod's
 * component tooltip providers (canary/tooltips/fabric) in each position fabric-item-api offers, and controls that hold
 * with or without any of them (the name, the advanced id line, a plain stick, a hidden and an absent provider).
 */
@Mod("forbrictooltips")
public final class TooltipsProbe {
	private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, "forbrictooltips");
	private static final DeferredHolder<DataComponentType<?>, DataComponentType<ItemLore>> NEO_LORE =
			COMPONENTS.registerComponentType("neo_lore", builder -> builder.persistent(ItemLore.CODEC));
	private static final List<Map<String, Object>> cases = new ArrayList<>();
	private static final Map<String, List<String>> renders = new LinkedHashMap<>();
	private static boolean appendersPosted, lateBeforeEvent;
	private static MinecraftServer server;
	private static int ticks;

	public TooltipsProbe(IEventBus bus) {
		COMPONENTS.register(bus);
		bus.addListener(RegisterTooltipAppendersEvent.class, event -> {
			appendersPosted = true;
			lateBeforeEvent = System.getProperty("forbric.m51.lateRegistered") != null;
			event.registerComponentAppenderBefore(NEO_LORE.get(), DataComponents.LORE, TooltipAppender.createComponentAppender(NEO_LORE.get()));
		});
		NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> {
			if (server != null && event.getServer() != server) return;
			if (++ticks == 20) { server = event.getServer(); run(); }
		});
	}

	@SuppressWarnings("unchecked")
	private static DataComponentType<ItemLore> fabric(String name) {
		return (DataComponentType<ItemLore>) BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(Identifier.fromNamespaceAndPath("forbrictooltipsfabric", name));
	}

	/** One Fabric provider's line on the stack: "fbt:<name>". */
	private static ItemStack fbt(ItemStack stack, String... names) {
		for (String name : names) {
			DataComponentType<ItemLore> type = fabric(name);
			if (type != null) lore(stack, type, "fbt:" + name);
		}
		return stack;
	}

	private static ItemStack lore(ItemStack stack, DataComponentType<ItemLore> type, String... lines) {
		List<Component> list = new ArrayList<>();
		for (String line : lines) list.add(Component.literal(line));
		stack.set(type, new ItemLore(list));
		return stack;
	}

	private static final Map<String, String> names = new LinkedHashMap<>();

	private static List<String> render(String name, ItemStack stack, TooltipFlag flag) {
		names.put(name, stack.getHoverName().getString());
		List<String> lines = new ArrayList<>();
		for (Component line : stack.getTooltipLines(Item.TooltipContext.of(server.overworld()), null, flag)) {
			lines.add(line.getContents() instanceof TranslatableContents tr ? "tr:" + tr.getKey() : line.getString());
		}
		renders.put(name + (flag.isAdvanced() ? ".advanced" : ".normal"), lines);
		return lines;
	}

	private static void run() {
		ItemStack both = lore(lore(new ItemStack(Items.STICK), DataComponents.LORE, "lore-1"), NEO_LORE.get(), "neo:before_lore");
		ItemStack withFabric = fbt(lore(lore(new ItemStack(Items.STICK), DataComponents.LORE, "lore-1"), NEO_LORE.get(), "neo:before_lore"),
				"first", "chain", "before_lore", "after_lore", "late", "last", "hidden");
		if (fabric("hidden") != null) withFabric.set(DataComponents.TOOLTIP_DISPLAY, new TooltipDisplay(false, new LinkedHashSet<>(List.of(fabric("hidden")))));
		ItemStack sword = fbt(new ItemStack(Items.IRON_SWORD), "after_damage", "last");
		sword.set(DataComponents.DAMAGE, 5);
		sword.enchant(server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS), 3);
		ItemStack loreOnly = lore(new ItemStack(Items.STICK), DataComponents.LORE, "lore-1");
		ItemStack plain = new ItemStack(Items.STICK);

		List<String> bothNormal = render("both", both, TooltipFlag.NORMAL);
		List<String> fabricNormal = render("fabric", withFabric, TooltipFlag.NORMAL);
		List<String> fabricAdvanced = render("fabric", withFabric, TooltipFlag.ADVANCED);
		render("both", both, TooltipFlag.ADVANCED);
		List<String> swordNormal = render("sword", sword, TooltipFlag.NORMAL);
		List<String> swordAdvanced = render("sword", sword, TooltipFlag.ADVANCED);
		List<String> loreNormal = render("lore", loreOnly, TooltipFlag.NORMAL);
		List<String> plainNormal = render("plain", plain, TooltipFlag.NORMAL);
		List<String> plainAdvanced = render("plain", plain, TooltipFlag.ADVANCED);

		test("vanilla.lore", () -> require(count(loreNormal, "lore-1") == 1, "lore line: " + loreNormal));
		test("vanilla.attributes", () -> require(swordNormal.stream().anyMatch(l -> l.startsWith("tr:item.modifiers.")),
				"no attribute-modifier header: " + swordNormal));
		test("vanilla.enchantment", () -> require(count(swordNormal, "tr:enchantment.minecraft.sharpness") == 1,
				"no sharpness line: " + swordNormal));
		test("vanilla.durability", () -> require(count(swordAdvanced, "tr:item.durability") == 1, "no durability line: " + swordAdvanced));
		test("neo.eventPosted", () -> require(appendersPosted, "RegisterTooltipAppendersEvent never reached the mod"));
		test("neo.before", () -> {
			int at = bothNormal.indexOf("neo:before_lore");
			require(at >= 0 && at + 1 < bothNormal.size() && bothNormal.get(at + 1).equals("lore-1"),
					"the NeoForge appender is not right before lore: " + bothNormal);
		});
		test("fabric.first", () -> require(fabricNormal.indexOf("fbt:first") == 1 && fabricAdvanced.indexOf("fbt:first") == 1,
				"first is not right after the name: " + fabricNormal));
		test("fabric.chain", () -> require(fabricNormal.indexOf("fbt:chain") == 2, "the provider after first is not next: " + fabricNormal));
		test("fabric.before", () -> {
			int at = fabricNormal.indexOf("fbt:before_lore");
			require(at > 0 && fabricNormal.get(at - 1).equals("neo:before_lore") && at + 2 < fabricNormal.size()
					&& fabricNormal.get(at + 1).equals("fbt:late") && fabricNormal.get(at + 2).equals("lore-1"),
					"before-lore providers are not right before lore: " + fabricNormal);
		});
		test("fabric.late", () -> require(!lateBeforeEvent && "true".equals(System.getProperty("forbric.m51.lateRegistered"))
				&& count(fabricNormal, "fbt:late") == 1, "a provider registered after NeoForge's event is missing: " + fabricNormal
				+ " (registered before the event: " + lateBeforeEvent + ")"));
		test("fabric.after", () -> {
			int at = fabricNormal.indexOf("lore-1");
			require(at > 0 && at + 1 < fabricNormal.size() && fabricNormal.get(at + 1).equals("fbt:after_lore"),
					"after-lore provider is not right after lore: " + fabricNormal);
		});
		test("fabric.afterDamage", () -> {
			int durability = swordAdvanced.indexOf("tr:item.durability");
			int normal = swordNormal.indexOf("fbt:after_damage");
			require(normal > swordNormal.indexOf("tr:item.modifiers.mainhand") && normal + 1 == swordNormal.indexOf("fbt:last")
					&& durability >= 0 && swordAdvanced.indexOf("fbt:after_damage") == durability + 1,
					"after-durability provider misplaced: " + swordNormal + " " + swordAdvanced);
		});
		test("fabric.last", () -> {
			int id = fabricAdvanced.indexOf("minecraft:stick");
			require(!fabricNormal.isEmpty() && fabricNormal.get(fabricNormal.size() - 1).equals("fbt:last")
					&& id > 0 && fabricAdvanced.get(id - 1).equals("fbt:last"), "last is not last: " + fabricNormal + " " + fabricAdvanced);
		});
		test("control.hidden", () -> require(renders.values().stream().noneMatch(r -> r.contains("fbt:hidden")),
				"a provider the stack hides was drawn"));
		test("control.absent", () -> require(renders.values().stream().noneMatch(r -> r.contains("fbt:absent")),
				"a provider the stack does not carry was drawn"));
		test("control.name", () -> {
			for (var render : renders.entrySet()) {
				String name = names.get(render.getKey().substring(0, render.getKey().indexOf('.')));
				require(render.getValue().size() > 0 && render.getValue().get(0).equals(name),
						render.getKey() + " does not start with the item name " + name + ": " + render.getValue());
			}
		});
		test("control.advancedId", () -> require(plainAdvanced.contains("minecraft:stick") && swordAdvanced.contains("minecraft:iron_sword"),
				"no id line in advanced: " + plainAdvanced + " " + swordAdvanced));
		test("control.plainStick", () -> require(plainNormal.equals(List.of(plain.getHoverName().getString())), "plain stick: " + plainNormal));
		test("control.noDuplicates", () -> {
			for (var render : renders.entrySet()) {
				Set<String> seen = new HashSet<>();
				for (String line : render.getValue()) {
					require(line.isBlank() || seen.add(line), render.getKey() + " draws " + line + " twice: " + render.getValue());
				}
			}
		});
		finish();
	}

	private static int count(List<String> lines, String line) {
		return (int) lines.stream().filter(line::equals).count();
	}

	private interface Probe { void run() throws Exception; }

	private static void test(String name, Probe probe) {
		boolean pass = false;
		String detail = "";
		try { probe.run(); pass = true; } catch (Throwable failure) { detail = failure.toString(); }
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("name", name); row.put("pass", pass); row.put("detail", detail);
		cases.add(row);
		System.out.println("[M51Tooltips] " + (pass ? "PASS " : "FAIL ") + name + (pass ? "" : " — " + detail));
	}

	private static void require(boolean condition, String detail) {
		if (!condition) throw new IllegalStateException(detail);
	}

	private static void finish() {
		try {
			Map<String, Object> report = new LinkedHashMap<>();
			report.put("phase", System.getProperty("forbric.tooltipPhase"));
			report.put("cases", cases);
			report.put("renders", renders);
			Path path = Path.of(System.getProperty("forbric.tooltipProbe"));
			Files.createDirectories(path.toAbsolutePath().getParent());
			Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(report));
			System.out.println("[M51Tooltips] RESULT " + cases.stream().filter(c -> Boolean.TRUE.equals(c.get("pass"))).count() + "/" + cases.size());
		} catch (Exception failure) {
			failure.printStackTrace();
		} finally {
			server.halt(false);
		}
	}
}
