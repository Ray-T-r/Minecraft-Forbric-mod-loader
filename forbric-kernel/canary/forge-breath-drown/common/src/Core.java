package forbric.breathdrown.PKG;

import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * The world probe of gate-m57 (canary/forge-breath-drown). The same class is built into every family's jar, each under
 * its own package (the builder writes the package), and exactly one of them drives: the one whose family
 * {@code -Dforbric.breathDrownDriver} names. The others only listen.
 *
 * <p>The driver plays a fixed list of scenarios, one at a time, on the server thread: a mob (or the probe's own player)
 * put in a fluid with a given air supply, its air and health sampled at the end of every server tick, and every family's
 * breathe, drown and damage listener reporting what it saw of that body through a sink the driver puts in the system
 * properties (a JDK type, so a listener in another jar, under another loader, can reach it). Each listener acts on the
 * body's entity tags; a tag only the other family understands does nothing, so the same scenario is a plain repeat on a
 * loader that has only one of the two families. Liquid scenarios place a family's liquid block and take it up and pour it
 * out with a bucket through the server's own use-item path.
 *
 * <p>Also measured, and not judged by the gate: how far the body's random source moved each tick (its seed is set at
 * the start of every scenario, so the sequence is the same on every run of one loader) and which entity events the
 * server broadcast for the body. The result is one JSON file ({@code -Dforbric.breathDrownProbe}), then the server halts.
 */
public final class Core {
	public static final String FAMILY = "PKG";
	private static final String SINK = "forbric.breathdrown.sink";
	/** The seed every scenario's body starts its random source from. */
	private static final long SEED = 0x5EED_DA7AL;
	private static final long LCG_MULTIPLIER = 0x5DEECE66DL, LCG_INCREMENT = 0xBL, LCG_MASK = (1L << 48) - 1;
	/** Kinds a listener reports every tick: counted, their ticks not recorded. */
	private static final Set<String> EVERY_TICK = Set.of("breathe", "breathe-ann");
	private static final Set<String> ORDERED = Set.of("breathe", "drown");
	/** Each pool's place, by fluid, fixed so a loader that lacks one family's fluid builds the others where every loader does. */
	private static final List<String> POOL_ORDER = List.of("minecraft:water", "minecraft:air", "forbricbreathforge:murk", "forbricbreathneo:murk");

	private static int ticks;
	private static boolean done;
	private static final List<Map<String, Object>> rows = new ArrayList<>();
	private static final Deque<Scenario> queue = new ArrayDeque<>();
	private static final Map<String, BlockPos> pools = new LinkedHashMap<>();
	private static Scenario current;
	private static ServerPlayer player;
	private static String error;

	private Core() {}

	public static boolean tagged(LivingEntity entity, String tag) {
		return entity.entityTags().contains(tag);
	}

	/** What a family's listener saw of {@code entity}: handed to whichever jar drives, through the system properties. */
	@SuppressWarnings("unchecked")
	public static void report(String family, String kind, LivingEntity entity, String line) {
		Object sink = System.getProperties().get(SINK);
		if (sink instanceof Consumer<?> consumer) ((Consumer<Object[]>) consumer).accept(new Object[] {family, kind, entity, line});
	}

	private static void receive(Object[] seen) {
		Scenario s = current;
		if (s == null || s.entity != seen[2]) return;
		String family = (String) seen[0], kind = (String) seen[1], line = (String) seen[3];
		View view = s.views.computeIfAbsent(family, f -> new LinkedHashMap<>()).computeIfAbsent(kind, k -> new View());
		view.count++;
		if (!EVERY_TICK.contains(kind)) view.at.add(s.air.size());
		if (view.notes.size() < 4 && (view.notes.isEmpty() || !view.notes.get(view.notes.size() - 1).equals(line))) view.notes.add(line);
		if (ORDERED.contains(kind)) s.thisTick.computeIfAbsent(kind, k -> new ArrayList<>()).add(family);
	}

	static final class View {
		int count;
		final List<Integer> at = new ArrayList<>();
		final List<String> notes = new ArrayList<>();
	}

	static final class Scenario {
		final String name, fluid, entityType;
		final int air0, length;
		final List<String> tags = new ArrayList<>();
		String bucket;
		LivingEntity entity;
		int waited, step;
		long lastSeed = -1;
		final List<Integer> air = new ArrayList<>(), rng = new ArrayList<>();
		final List<Float> health = new ArrayList<>();
		final List<String> entityEvents = new ArrayList<>(), result = new ArrayList<>();
		final Map<String, Map<String, View>> views = new LinkedHashMap<>();
		final Map<String, List<String>> thisTick = new LinkedHashMap<>();
		final Map<String, String> order = new LinkedHashMap<>();
		String first, last, tracking = "untracked", rngNote;

		Scenario(String name, String fluid, String entityType, int air0, int length) {
			this.name = name;
			this.fluid = fluid;
			this.entityType = entityType;
			this.air0 = air0;
			this.length = length;
		}

		Scenario tag(String... t) { tags.addAll(List.of(t)); return this; }
	}

	private static Scenario pig(String name, String fluid, int air0, int length, String... tags) {
		return new Scenario(name, fluid, "minecraft:pig", air0, length).tag(tags);
	}

	private static Scenario bucket(String name, String fluid, String bucket) {
		Scenario s = new Scenario(name, fluid, "player", 0, 0);
		s.bucket = bucket;
		return s;
	}

	/**
	 * The scenarios. Tags: {@code f-*} act in the MinecraftForge listeners, {@code n-*} in the NeoForge ones. Drown rows
	 * start a pig in water at air -15 (five ticks short of the point MinecraftForge asks at), the read rows at air 5 so
	 * the first drowning shows; breathe rows use open air, where both native loaders refill the same way.
	 */
	private static void plan() {
		String water = "minecraft:water", air = "minecraft:air", forgeMurk = "forbricbreathforge:murk", neoMurk = "forbricbreathneo:murk";
		// Drowning, each family alone, then both: NeoForge's listeners answer first, MinecraftForge's last.
		queue.add(pig("drown-read", water, 5, 50));
		queue.add(pig("drown-f-cancel", water, -15, 30, "f-cancel"));
		queue.add(pig("drown-f-notdrowning", water, -15, 30, "f-notdrowning"));
		queue.add(pig("drown-f-damage3", water, -15, 30, "f-damage3"));
		queue.add(pig("drown-f-damage0", water, -15, 30, "f-damage0"));
		queue.add(pig("drown-f-ann-cancel", water, -15, 30, "f-ann-cancel"));
		queue.add(pig("drown-n-cancel", water, -15, 30, "n-cancel"));
		queue.add(pig("drown-n-notdrowning", water, -15, 30, "n-notdrowning"));
		queue.add(pig("drown-n-damage3", water, -15, 30, "n-damage3"));
		queue.add(pig("drown-n-bubbles0", water, -15, 30, "n-bubbles0"));
		queue.add(pig("drown-n-drowning", water, 5, 30, "n-drowning"));
		queue.add(pig("drown-n-damage3-f-damage0", water, -15, 30, "n-damage3", "f-damage0"));
		queue.add(pig("drown-n-cancel-f-damage3", water, -15, 30, "n-cancel", "f-damage3"));
		queue.add(pig("drown-n-damage3-f-cancel", water, -15, 30, "n-damage3", "f-cancel"));
		// Breathing in open air.
		queue.add(pig("breathe-read", air, 200, 30));
		queue.add(pig("breathe-f-refill2", air, 200, 30, "f-refill2"));
		queue.add(pig("breathe-f-cant", air, 200, 30, "f-cant"));
		queue.add(pig("breathe-f-ann-refill2", air, 200, 30, "f-ann-refill2"));
		queue.add(pig("breathe-f-can-water", water, 200, 30, "f-can"));
		queue.add(pig("breathe-n-refill0", air, 200, 30, "n-refill0"));
		queue.add(pig("breathe-n-cant", air, 200, 30, "n-cant"));
		queue.add(pig("breathe-n-refill0-f-refill2", air, 200, 30, "n-refill0", "f-refill2"));
		queue.add(pig("breathe-n-cant-f-can", air, 200, 30, "n-cant", "f-can"));
		// Each family's own drowning liquid.
		queue.add(pig("drown-read-forgemurk", forgeMurk, 5, 50));
		queue.add(pig("drown-f-damage3-forgemurk", forgeMurk, -15, 30, "f-damage3"));
		queue.add(pig("drown-read-neomurk", neoMurk, 5, 50));
		// The probe's player drowning: the whole damage chain.
		queue.add(new Scenario("player-drown-read", water, "player", 5, 70));
		queue.add(new Scenario("player-drown-f-hurt-halve", water, "player", 5, 70).tag("f-hurt-halve"));
		queue.add(new Scenario("player-drown-f-ann-damage-cancel", water, "player", 5, 70).tag("f-ann-damage-cancel"));
		queue.add(new Scenario("player-drown-n-incoming-cancel", water, "player", 5, 70).tag("n-incoming-cancel"));
		// PROBE-EXTRA-ROWS
		// Liquid blocks placed, taken up and poured out with a bucket.
		queue.add(bucket("water-bucket", water, "minecraft:water_bucket"));
		queue.add(bucket("forge-bucket", forgeMurk, "forbricbreathforge:murk_bucket"));
		queue.add(bucket("neo-bucket", neoMurk, "forbricbreathneo:murk_bucket"));
	}

	/** Called by every family's jar at the end of every server tick; only the driver acts. */
	public static void tick(MinecraftServer server) {
		if (done || !FAMILY.equals(System.getProperty("forbric.breathDrownDriver"))) return;
		++ticks;
		try {
			if (ticks == 20) {
				System.getProperties().put(SINK, (Consumer<Object[]>) Core::receive);
				plan();
				return;
			}
			if (ticks < 40) return;
			if (current == null) {
				while (current == null && !queue.isEmpty()) {
					// A scenario starts only once its pool's chunk ticks entities: a mob in a chunk that does not yet would
					// be left unticked for a tick or more, and its onset would move with how fast the chunk system was.
					Scenario next = queue.peek();
					String skip = missing(next);
					if (skip == null) {
						try {
							if (!next.entityType.equals("player") && !server.overworld().isPositionEntityTicking(pool(server, next.fluid))) { next.waited++; return; }
							queue.poll();
							skip = setup(server, next);
						} catch (Throwable t) {
							queue.remove(next);
							t.printStackTrace();
							skip = "setup failed: " + t;
							if (next.entity != null && !(next.entity instanceof ServerPlayer)) next.entity.discard();
						}
					} else queue.poll();
					if (skip == null) current = next;
					else {
						Map<String, Object> row = new LinkedHashMap<>();
						row.put("name", next.name);
						row.put("skipped", skip);
						rows.add(row);
						System.out.println("[BreathDrown] SKIP " + next.name + ": " + skip);
					}
				}
				if (current == null) finish(server);
				return;   // the body is in place; the next tick's end is its first sample
			}
			if (current.bucket != null) bucketStep(server, current);
			else step(server, current);
		} catch (Throwable t) {
			t.printStackTrace();
			error = String.valueOf(t);
			finish(server);
		}
	}

	/** Why {@code s} cannot run on this loader (its fluid or bucket is not registered here), or null. */
	private static String missing(Scenario s) {
		if (!s.fluid.equals("minecraft:air") && !BuiltInRegistries.BLOCK.containsKey(Identifier.parse(s.fluid))) return "no block " + s.fluid;
		if (s.bucket != null && !BuiltInRegistries.ITEM.containsKey(Identifier.parse(s.bucket))) return "no item " + s.bucket;
		return null;
	}

	private static BlockPos pool(MinecraftServer server, String fluid) {
		BlockPos made = pools.get(fluid);
		if (made != null) return made;
		int i = POOL_ORDER.indexOf(fluid);
		if (i < 0) throw new IllegalArgumentException("no pool place for " + fluid);
		BlockPos center = new BlockPos(4 + 8 * i, 100, 4);
		ServerLevel level = server.overworld();
		level.setChunkForced(center.getX() >> 4, center.getZ() >> 4, true);
		pools.put(fluid, center);   // before the fluid goes in: a block that throws on placement fails each scenario, not the run
		BlockState state = fluid.equals("minecraft:air") ? Blocks.AIR.defaultBlockState() : BuiltInRegistries.BLOCK.getValue(Identifier.parse(fluid)).defaultBlockState();
		for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) for (int y = -1; y <= 4; y++) {
			boolean wall = Math.abs(x) == 2 || Math.abs(z) == 2 || y == -1 || y == 4;
			level.setBlock(center.offset(x, y, z), wall ? Blocks.GLASS.defaultBlockState() : state, 2);
		}
		return center;
	}

	/** The probe's stand-in player for the entity-event watcher (any player will do): the last one made, or a new one. */
	private static ServerPlayer player(MinecraftServer server) {
		return player != null ? player : freshPlayer(server);
	}

	/**
	 * A new probe player for every player scenario, so nothing carries over from the last one (its food and saturation,
	 * its hurt window, its regeneration timer): a ServerPlayer on a connection that goes nowhere, not in the player
	 * list, ticked by the probe with doTick(). It flies (stays where it is put), is not invulnerable, and has full food
	 * but no saturation, so it does not heal within a scenario and its health shows exactly the damage it took.
	 */
	private static ServerPlayer freshPlayer(MinecraftServer server) {
		ServerLevel level = server.overworld();
		GameProfile profile = new GameProfile(UUID.fromString("5a1d2c3e-4f50-4a6b-8c7d-9e0f1a2b3c4e"), "BreathDrown");
		ServerPlayer p = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
		new ServerGamePacketListenerImpl(server, new Connection(PacketFlow.SERVERBOUND), p, CommonListenerCookie.createInitial(profile, false));
		if (p.connection == null) throw new IllegalStateException("the probe player has no packet listener");
		// A player is invulnerable until its client says it has loaded the world (or a timeout the connection's own
		// tick counts down, which never runs here): this one has no client, so hand its listener the packet one sends.
		p.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
		p.getAbilities().invulnerable = false;
		p.getAbilities().flying = true;
		p.getAbilities().mayfly = true;
		p.setHealth(p.getMaxHealth());
		p.getFoodData().setFoodLevel(20);
		p.getFoodData().setSaturation(0f);
		p.setDeltaMovement(0, 0, 0);
		player = p;
		return p;
	}

	private static String setup(MinecraftServer server, Scenario s) throws Exception {
		ServerLevel level = server.overworld();
		if (s.bucket != null) {
			s.entity = freshPlayer(server);
			return null;
		}
		BlockPos at = pool(server, s.fluid);
		LivingEntity e;
		if (s.entityType.equals("player")) {
			ServerPlayer p = freshPlayer(server);
			for (String t : s.tags) p.addTag(t);
			p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
			e = p;
		} else {
			EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(s.entityType));
			Mob mob = (Mob) type.create(level, EntitySpawnReason.COMMAND);
			mob.setNoAi(true);
			mob.setPersistenceRequired();
			mob.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
			for (String t : s.tags) mob.addTag(t);
			level.addFreshEntity(mob);
			e = mob;
			s.tracking = watch(level, mob, s);
		}
		e.setDeltaMovement(0, 0, 0);
		e.setAirSupply(s.air0);
		e.getRandom().setSeed(SEED);
		s.lastSeed = seed(e.getRandom(), s);
		s.entity = e;
		return null;
	}

	/**
	 * Watches the entity events the server broadcasts for {@code mob}: a stand-in connection in the set of players its
	 * tracker sends to, recording each entity event's id and the sample it came before. Measured only; nothing judges it.
	 */
	private static String watch(ServerLevel level, Mob mob, Scenario s) {
		try {
			Field entityMap = ChunkMap.class.getDeclaredField("entityMap");
			entityMap.setAccessible(true);
			Object tracked = ((Map<?, ?>) entityMap.get(level.getChunkSource().chunkMap)).get(mob.getId());
			if (tracked == null) return "untracked: no tracker";
			Field seenBy = tracked.getClass().getDeclaredField("seenBy");
			seenBy.setAccessible(true);
			ServerPlayer stand = player(level.getServer());
			Object watcher = Proxy.newProxyInstance(Core.class.getClassLoader(), new Class<?>[] {ServerPlayerConnection.class}, (proxy, method, args) -> {
				switch (method.getName()) {
					case "send": {
						Packet<?> packet = (Packet<?>) args[0];
						if (packet instanceof ClientboundEntityEventPacket event && s.entity == mob) s.entityEvents.add(s.air.size() + ":" + event.getEventId());
						return null;
					}
					case "getPlayer": return stand;
					case "hashCode": return System.identityHashCode(proxy);
					case "equals": return proxy == args[0];
					case "toString": return "breath-drown entity-event watcher";
					default: return null;
				}
			});
			@SuppressWarnings("unchecked") Set<Object> set = (Set<Object>) seenBy.get(tracked);
			set.add(watcher);
			return "watched";
		} catch (Throwable t) {
			return "untracked: " + t;
		}
	}

	/** The random source's state, read without drawing from it; -1 when it is not a seeded linear-congruential source. */
	private static long seed(RandomSource random, Scenario s) {
		try {
			Field f = null;
			for (Class<?> c = random.getClass(); c != null && f == null; c = c.getSuperclass())
				for (Field candidate : c.getDeclaredFields()) if (candidate.getType() == AtomicLong.class) { f = candidate; break; }
			if (f == null) { s.rngNote = "no seed field in " + random.getClass().getName(); return -1; }
			f.setAccessible(true);
			return ((AtomicLong) f.get(random)).get();
		} catch (Throwable t) {
			s.rngNote = "seed unreadable: " + t;
			return -1;
		}
	}

	/** How many linear-congruential steps lie between two seeds (a nextInt is one, a nextDouble two); -1 past 20000. */
	static int steps(long from, long to) {
		long x = from;
		for (int k = 0; k <= 20000; k++) {
			if (x == to) return k;
			x = (x * LCG_MULTIPLIER + LCG_INCREMENT) & LCG_MASK;
		}
		return -1;
	}

	private static void step(MinecraftServer server, Scenario s) throws Exception {
		LivingEntity e = s.entity;
		if (e instanceof ServerPlayer p) p.doTick();
		if (s.air.isEmpty()) s.first = describe(e);
		s.air.add(e.getAirSupply());
		s.health.add(e.getHealth());
		if (s.lastSeed >= 0) {
			long now = seed(e.getRandom(), s);
			s.rng.add(now < 0 ? -1 : steps(s.lastSeed, now));
			s.lastSeed = now;
		}
		for (Map.Entry<String, List<String>> tick : s.thisTick.entrySet()) {
			Set<String> families = new LinkedHashSet<>(tick.getValue());
			if (families.size() > 1) s.order.putIfAbsent(tick.getKey(), String.join(",", families));
		}
		s.thisTick.clear();
		if (s.air.size() < s.length) return;
		s.last = describe(e);
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("name", s.name);
		row.put("waited", s.waited);
		row.put("air", s.air);
		row.put("health", s.health);
		row.put("views", views(s));
		row.put("order", s.order);
		row.put("rng", s.rng);
		if (s.rngNote != null) row.put("rngNote", s.rngNote);
		row.put("entityEvents", s.entityEvents);
		row.put("tracking", s.tracking);
		row.put("first", s.first);
		row.put("last", s.last);
		if (e instanceof ServerPlayer p) row.put("gameMode", p.gameMode.getGameModeForPlayer().getName());
		rows.add(row);
		System.out.println("[BreathDrown] ROW " + s.name);
		if (!(e instanceof ServerPlayer)) e.discard();
		current = null;
	}

	private static Map<String, Object> views(Scenario s) {
		Map<String, Object> out = new LinkedHashMap<>();
		for (Map.Entry<String, Map<String, View>> family : s.views.entrySet()) {
			Map<String, Object> kinds = new LinkedHashMap<>();
			for (Map.Entry<String, View> kind : family.getValue().entrySet()) {
				Map<String, Object> v = new LinkedHashMap<>();
				v.put("count", kind.getValue().count);
				if (!EVERY_TICK.contains(kind.getKey())) v.put("at", kind.getValue().at);
				v.put("notes", kind.getValue().notes);
				kinds.put(kind.getKey(), v);
			}
			out.put(family.getKey(), kinds);
		}
		return out;
	}

	/**
	 * One tick per step: place the liquid's source in a glass basin; take it up with an empty bucket, looking straight
	 * down at it; pour the filled bucket out onto a glass block; record what each step left, and finish.
	 */
	private static void bucketStep(MinecraftServer server, Scenario s) {
		ServerLevel level = server.overworld();
		ServerPlayer p = (ServerPlayer) s.entity;
		int index = POOL_ORDER.indexOf(s.fluid);
		BlockPos basin = new BlockPos(4 + 8 * index, 100, 40), target = basin.offset(4, 0, 0);
		try {
			switch (s.step++) {
				case 0 -> {
					level.setChunkForced(basin.getX() >> 4, basin.getZ() >> 4, true);
					for (int x = -1; x <= 5; x++) for (int z = -1; z <= 1; z++) {
						level.setBlock(basin.offset(x, -1, z), Blocks.GLASS.defaultBlockState(), 2);
						for (int y = 0; y <= 3; y++) level.setBlock(basin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
					}
					for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) if (x != 0 || z != 0) level.setBlock(basin.offset(x, 0, z), Blocks.GLASS.defaultBlockState(), 2);
					level.setBlock(basin, BuiltInRegistries.BLOCK.getValue(Identifier.parse(s.fluid)).defaultBlockState(), 3);
					s.result.add("place=" + fluid(level, basin));
				}
				case 1 -> {
					look(p, basin);
					p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse("minecraft:bucket"))));
					InteractionResult used = p.gameMode.useItem(p, level, p.getMainHandItem(), InteractionHand.MAIN_HAND);
					s.result.add("pickup=" + used.getClass().getSimpleName() + " hand=" + item(p.getMainHandItem()) + " left=" + fluid(level, basin));
				}
				case 2 -> {
					look(p, target.below());
					InteractionResult used = p.gameMode.useItem(p, level, p.getMainHandItem(), InteractionHand.MAIN_HAND);
					s.result.add("pour=" + used.getClass().getSimpleName() + " hand=" + item(p.getMainHandItem()) + " placed=" + fluid(level, target));
				}
				default -> {
					Map<String, Object> row = new LinkedHashMap<>();
					row.put("name", s.name);
					row.put("result", s.result);
					rows.add(row);
					System.out.println("[BreathDrown] ROW " + s.name + " " + s.result);
					p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
					current = null;
				}
			}
		} catch (Throwable t) {
			t.printStackTrace();
			s.result.add("step " + (s.step - 1) + " threw " + t);
			s.step = 99;
		}
	}

	/** Stands the player two blocks above {@code block}, looking straight down at it. */
	private static void look(ServerPlayer p, BlockPos block) {
		p.setPos(block.getX() + 0.5, block.getY() + 2.0, block.getZ() + 0.5);
		p.setYRot(0f);
		p.setXRot(90f);
		p.setYHeadRot(0f);
		p.setDeltaMovement(0, 0, 0);
	}

	private static String fluid(ServerLevel level, BlockPos pos) {
		FluidState state = level.getFluidState(pos);
		return BuiltInRegistries.FLUID.getKey(state.getType()) + (state.isEmpty() ? "" : state.isSource() ? "/source" : "/flowing")
				+ " block=" + BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
	}

	private static String item(ItemStack stack) {
		return stack.isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(stack.getItem()) + "x" + stack.getCount();
	}

	private static String describe(LivingEntity e) {
		StringBuilder effects = new StringBuilder();
		for (MobEffectInstance i : e.getActiveEffects()) effects.append(effects.length() == 0 ? "" : "+").append(i.getEffect().getRegisteredName());
		BlockPos eye = BlockPos.containing(e.getX(), e.getEyeY(), e.getZ());
		return "blockAtEye=" + BuiltInRegistries.BLOCK.getKey(e.level().getBlockState(eye).getBlock())
				+ " y=" + String.format(Locale.ROOT, "%.2f", e.getY()) + " air=" + e.getAirSupply() + "/" + e.getMaxAirSupply()
				+ " health=" + e.getHealth() + " effects=" + effects;
	}

	private static void finish(MinecraftServer server) {
		if (done) return;
		done = true;
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("driver", FAMILY);
		out.put("error", error);
		out.put("rows", rows);
		String json = new GsonBuilder().serializeNulls().disableHtmlEscaping().create().toJson(out);
		System.out.println("[BreathDrown] DONE rows=" + rows.size() + " error=" + error);
		try {
			String path = System.getProperty("forbric.breathDrownProbe");
			if (path != null) Files.writeString(Path.of(path), json);
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		server.halt(false);
	}
}
