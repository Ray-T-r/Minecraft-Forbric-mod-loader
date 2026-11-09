package forbric.worldupgrade;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.google.gson.GsonBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * What both world-upgrade canaries share: the gate's ownership proof, the scripts the NeoForge canary runs, the fixed
 * positions and values, and the read-back report (values + registry census) gate-m58 compares.
 *
 * <p>The canaries act only in a world gate-m58 owns: {@code -Dforbric.worldUpgradeRoot} holds a {@code .m58-owned} file
 * whose text is {@code -Dforbric.worldUpgradeToken}, and the running world is {@code -Dforbric.worldUpgradeWorld}. Any
 * other world gets one {@code DISARMED} line and nothing else, so a jar left in some mods folder never writes into a
 * player's save.
 *
 * <p>Phases ({@code -Dforbric.worldUpgradePhase}): {@code write} force-loads the chunks around the origin when the server
 * has started, runs the world script ({@code -Dforbric.worldUpgradeCommands}, when given) {@link #SCRIPT_TICK} ticks
 * later, sets every attachment and SavedData value through the APIs at {@link #VALUES_TICK}, and runs the player script
 * ({@code -Dforbric.worldUpgradePlayerCommands}) at each login; {@code read} changes nothing and reports what it finds.
 * Lines: {@code [M58Upgrade] OK|FAIL command ...} for every script line (the gate requires every line OK: a mod whose
 * population command does not work is not in the world, D53), {@code [M58Upgrade] PASS|FAIL read ...}.
 *
 * <p>The scripts run a few seconds after the start, not at ServerStarted: a forced chunk is only loaded on a later tick,
 * and a setblock into a chunk that is not loaded yet fails ("That position is not loaded").
 */
public final class UpgradeProbe {
	/** Where the scripts put the chest both canaries attach to. */
	public static final int CHEST_X = 0, CHEST_Y = -60, CHEST_Z = 0;
	/** The pig the world script summons (NoAI, persistent): the entity both canaries attach to. */
	public static final UUID PIG = new UUID(0x00000058_0000_0001L, 0x0000_0000_0000_0058L);
	/** Chunks -2..2 on both axes, the box gate-m58 exports. */
	public static final String FORCELOAD = "forceload add -32 -32 47 47";
	public static final int SCRIPT_TICK = 40, VALUES_TICK = 80;

	private UpgradeProbe() {
	}

	public static String phase() {
		return System.getProperty("forbric.worldUpgradePhase", "");
	}

	/** True when this world is the one the gate owns; prints the DISARMED line otherwise. */
	public static boolean armed(MinecraftServer server, String who) {
		try {
			String token = System.getProperty("forbric.worldUpgradeToken", "");
			Path root = Path.of(System.getProperty("forbric.worldUpgradeRoot", "")).toAbsolutePath().normalize();
			Path expected = Path.of(System.getProperty("forbric.worldUpgradeWorld", "")).toAbsolutePath().normalize();
			Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			if (token.isBlank() || !List.of("write", "read").contains(phase()) || !world.equals(expected)
					|| !Files.readString(root.resolve(".m58-owned")).trim().equals(token)) {
				System.out.println("[M58Upgrade] DISARMED " + who + ": " + world + " is not the gate-owned world");
				return false;
			}
			return true;
		} catch (Exception unarmed) {
			System.out.println("[M58Upgrade] DISARMED " + who + ": no ownership proof (" + unarmed + ")");
			return false;
		}
	}

	/** Whether the world script is to run in this boot (phase write and a script named). */
	public static boolean writesWorld() {
		return "write".equals(phase()) && !System.getProperty("forbric.worldUpgradeCommands", "").isBlank();
	}

	/** Runs one command as the server (or at and as {@code as}, with the server's permissions); true when it succeeded. */
	public static boolean command(MinecraftServer server, String line, ServerPlayer as) {
		boolean[] ok = {false};
		CommandSourceStack source = server.createCommandSourceStack();
		if (as != null) source = source.withEntity(as).withLevel(as.level()).withPosition(as.position());
		source = source.withCallback((success, result) -> ok[0] = success);
		try {
			server.getCommands().performPrefixedCommand(source, line);
		} catch (Exception e) {
			System.out.println("[M58Upgrade] FAIL command " + line + ": " + e);
			return false;
		}
		return ok[0];
	}

	/** Runs every non-blank, non-# line of the script named by {@code property}; prints one OK/FAIL line each. */
	public static void runScript(MinecraftServer server, String property, ServerPlayer as) {
		String file = System.getProperty(property, "");
		if (file.isBlank()) return;
		try {
			List<String> lines = Files.readAllLines(Path.of(file), StandardCharsets.UTF_8);
			String name = Path.of(file).getFileName().toString();
			int n = 0, failed = 0;
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i).strip();
				if (line.isEmpty() || line.startsWith("#")) continue;
				n++;
				if (command(server, line, as)) {
					System.out.println("[M58Upgrade] OK command " + name + ":" + (i + 1) + " " + line);
				} else {
					failed++;
					System.out.println("[M58Upgrade] FAIL command " + name + ":" + (i + 1) + " " + line);
				}
			}
			System.out.println("[M58Upgrade] ran " + n + " command(s) from " + name + ", " + failed + " failed");
		} catch (Exception e) {
			System.out.println("[M58Upgrade] FAIL could not run " + file + ": " + e);
		}
	}

	/** Collects expected/actual pairs for one report and prints the verdict. */
	public static final class Report {
		private final String who;
		private final Map<String, Object> values = new LinkedHashMap<>();
		private final List<String> wrong = new ArrayList<>();
		private final Map<String, List<String>> extraRegistries = new TreeMap<>();

		public Report(String who) {
			this.who = who;
		}

		public void expect(String name, Object expected, Object actual) {
			values.put(name, actual);
			if (expected == null ? actual != null : !expected.equals(actual)) {
				wrong.add(name + " expected " + expected + " got " + actual);
			}
		}

		/** Adds a name list to the census beside the vanilla registries (e.g. attachment types). */
		public void registry(String key, List<String> names) {
			extraRegistries.put(key, names);
		}

		/** Prints PASS/FAIL and writes {@code <output>/<file>} with the values and (when asked) the registry census. */
		public void finish(String file, boolean census) {
			if (wrong.isEmpty()) {
				System.out.println("[M58Upgrade] PASS read " + who + ": " + values.size() + " value(s) as written");
			} else {
				System.out.println("[M58Upgrade] FAIL read " + who + ": " + String.join("; ", wrong));
			}
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("who", who);
			out.put("phase", phase());
			out.put("token", System.getProperty("forbric.worldUpgradeToken", ""));
			out.put("pass", wrong.isEmpty());
			out.put("values", values);
			out.put("wrong", wrong);
			if (census) {
				Map<String, List<String>> registries = new TreeMap<>(census());
				registries.putAll(extraRegistries);
				out.put("registries", registries);
			}
			try {
				Path dir = Path.of(System.getProperty("forbric.worldUpgradeOutput", "."));
				Files.createDirectories(dir);
				Files.writeString(dir.resolve(file), new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(out));
			} catch (Exception e) {
				System.out.println("[M58Upgrade] FAIL could not write " + file + ": " + e);
			}
		}
	}

	/**
	 * Every name in the registries a save refers to by name (palettes, item stacks, entity and block-entity ids,
	 * component keys). Raw ids are not written: 26.2 saves do not store them.
	 */
	public static Map<String, List<String>> census() {
		Map<String, List<String>> out = new TreeMap<>();
		put(out, "minecraft:block", BuiltInRegistries.BLOCK);
		put(out, "minecraft:item", BuiltInRegistries.ITEM);
		put(out, "minecraft:entity_type", BuiltInRegistries.ENTITY_TYPE);
		put(out, "minecraft:block_entity_type", BuiltInRegistries.BLOCK_ENTITY_TYPE);
		put(out, "minecraft:data_component_type", BuiltInRegistries.DATA_COMPONENT_TYPE);
		put(out, "minecraft:fluid", BuiltInRegistries.FLUID);
		put(out, "minecraft:menu", BuiltInRegistries.MENU);
		return out;
	}

	public static void put(Map<String, List<String>> out, String key, Registry<?> registry) {
		List<String> names = new ArrayList<>();
		for (var id : registry.keySet()) names.add(id.toString());
		names.sort(null);
		out.put(key, names);
	}

	/**
	 * The ids of every Fabric attachment type registered in this JVM: fabric-data-attachment-api keeps them in a private
	 * static map of {@code AttachmentRegistryImpl} (no public list). {@code fallback} when that map cannot be read.
	 */
	public static List<String> fabricAttachmentIds(List<String> fallback) {
		try {
			Class<?> impl = Class.forName("net.fabricmc.fabric.impl.attachment.AttachmentRegistryImpl");
			for (Field field : impl.getDeclaredFields()) {
				if (!Modifier.isStatic(field.getModifiers()) || !Map.class.isAssignableFrom(field.getType())) continue;
				field.setAccessible(true);
				Map<?, ?> map = (Map<?, ?>) field.get(null);
				List<String> names = new ArrayList<>();
				for (Object key : map.keySet()) names.add(String.valueOf(key));
				if (names.isEmpty()) continue;
				names.sort(null);
				return names;
			}
		} catch (Throwable unreadable) {
			System.out.println("[M58Upgrade] could not list Fabric's attachment types (" + unreadable + "); the census names the canary's own");
		}
		return fallback;
	}
}
