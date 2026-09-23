package net.forbric.kernel.runtime.soak;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import net.forbric.kernel.soak.SoakJson;
import net.forbric.kernel.soak.SoakStateMachine;
import net.forbric.kernel.soak.SoakStateMachine.Action;
import net.forbric.kernel.soak.SoakStateMachine.Config;
import net.forbric.kernel.soak.SoakStateMachine.Sample;
import net.forbric.kernel.soak.SoakStateMachine.State;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

/** Opt-in, owned-world integrated-server soak. No references to a retired server survive except weak probes. */
public final class ClientSoakController {
	private static ClientSoakController instance;
	private final Path run, world, output;
	private final String nonce, worldName;
	private final Config config;
	private final SoakStateMachine machine;
	private final long started = System.nanoTime();
	private final AtomicReference<Observation> pending = new AtomicReference<>();
	private final AtomicReference<String> asynchronousFailure = new AtomicReference<>();
	private final AtomicBoolean sampling = new AtomicBoolean();
	private final List<Retired> retired = new ArrayList<>();
	private final List<String> nativeRetentionRoots;
	// Written by the client thread on every controller tick, read by the watchdog. Minecraft.disconnect and the
	// world-load screen spin renderFrame without calling tick(), so a wedged integrated server stops these updates.
	private final AtomicLong lastTick = new AtomicLong(System.nanoTime());
	private volatile String blockedIn = "client tick";
	private IntegratedServer current;
	private long lastSample, lastIdle, lastGc, sequence;
	private int serial;
	private int respawnRequests;
	private long lastRespawnRequest;
	private boolean joining, ownershipChecked;
	private volatile boolean finished, resultWritten;
	private record Observation(Sample sample, List<Integer> chunks, String dimension, double x, double z) { }
	private record Retired(int serial, long at, WeakReference<IntegratedServer> reference) { }
	private static final int[] COORDINATES = { 2048, 8192, 2048, 8192, 2048, 8192 };

	private ClientSoakController(Minecraft minecraft) throws Exception {
		nonce = required("forbric.soakNonce");
		if (!nonce.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("invalid soak nonce");
		run = Path.of(required("forbric.soakRun")).toRealPath();
		worldName = "ForbricSoak_" + nonce;
		world = run.resolve("saves").resolve(worldName).toRealPath();
		output = run.resolve("evidence");
		if (!minecraft.gameDirectory.toPath().toRealPath().equals(run)) throw new IllegalStateException("not the owned soak game directory");
		owner(run.resolve(".forbric-soak-owner")); owner(world.resolve(".forbric-soak-world"));
		if (Files.isSymbolicLink(run.resolve("saves").resolve(worldName)) || !world.startsWith(run)) throw new IllegalStateException("world ownership path escaped run");
		Files.createDirectories(output);
		config = new Config(number("seconds", 7200), Boolean.getBoolean("forbric.soakControl"),
				(int) number("dwellTicks", 600), (int) number("routes", 2), (int) number("sessions", 3),
				(int) number("warmupTicks", 40), number("betweenSeconds", 10), number("settleSeconds", 60), number("timeoutSeconds", 300));
		machine = new SoakStateMachine(config, started);
		String roots = System.getProperty("forbric.soak.nativeRetentionRoots", "");
		nativeRetentionRoots = roots.isBlank() ? List.of() : List.of(roots.split(","));
		minecraft.options.pauseOnLostFocus = false;
		minecraft.options.renderDistance().set(4);
		minecraft.options.simulationDistance().set(4);
		write("start", fields("releaseEligible", !config.control(), "requiredSeconds", config.seconds(),
				"dwellTicks", config.dwellTicks(), "routes", config.routesPerSession(), "minSessions", config.minSessions(),
				"world", world.toString(), "java", System.getProperty("java.version"), "nativeRetentionRoots", nativeRetentionRoots));
		Thread watchdog = new Thread(this::watch, "forbric-soak-watchdog");
		watchdog.setDaemon(true);
		watchdog.start();
	}
	public static void onTick(Object object) {
		if (!Boolean.getBoolean("forbric.clientSoak")) return;
		Minecraft minecraft = (Minecraft) object;
		try {
			if (instance == null) instance = new ClientSoakController(minecraft);
			instance.tick(minecraft);
		} catch (Throwable failure) {
			if (instance == null) throw new IllegalStateException("soak ownership/configuration rejected", failure);
			instance.fail(minecraft, failure);
		}
	}
	private void tick(Minecraft minecraft) throws Exception {
		if (finished) return;
		long now = System.nanoTime();
		lastTick.set(now);
		String error = asynchronousFailure.getAndSet(null);
		if (error != null) throw new IllegalStateException(error);
		Observation observation = pending.getAndSet(null);
		if (observation != null) {
			Sample sample = observation.sample();
			Map<String, Object> payload = fields("server", sample.server(), "sampleNano", sample.nanoTime(), "tick", sample.tick(),
					"gameTime", sample.gameTime(), "paused", sample.paused(), "occupied", sample.player(),
					"point", sample.playerPoint(), "loaded", booleans(sample.loaded()), "chunks", observation.chunks(),
					"dimension", observation.dimension(), "x", observation.x(), "z", observation.z());
			if (joining) {
				// A saved test player can arrive dead or drown while the client is still loading. Such time
				// is preparation, not occupied gameplay; wait for the native respawn handshake to complete.
				if (sample.player() && minecraft.player != null && !minecraft.player.isDeadOrDying()) {
					machine.joined(sample); joining = false; write("join", payload);
				} else write("waiting-for-live-player", payload);
			}
			else { Action action = machine.observe(sample); write("sample", payload); apply(minecraft, action); }
		}
		if (machine.state() == State.WAIT_WORLD && current == null && minecraft.player != null && minecraft.level != null) {
			IntegratedServer server = minecraft.getSingleplayerServer();
			if (server != null) {
				if (!server.getWorldPath(LevelResource.ROOT).toRealPath().equals(world)) throw new IllegalStateException("integrated server is not the nonce-owned copied world");
				owner(world.resolve(".forbric-soak-world")); ownershipChecked = true;
				for (Retired old : retired) if (old.reference().get() == server) throw new IllegalStateException("reopening reused the old integrated server instance");
				current = server; serial++; joining = true; lastSample = 0; respawnRequests = 0; lastRespawnRequest = 0;
			}
		}
		if (joining && minecraft.player != null && minecraft.player.isDeadOrDying()
				&& now - lastRespawnRequest >= 5_000_000_000L) {
			if (++respawnRequests > 3) throw new IllegalStateException("native initial-player respawn did not complete");
			lastRespawnRequest = now;
			write("respawn-request", fields("server", serial, "attempt", respawnRequests));
			minecraft.player.respawn();
		}
		if ((machine.state() == State.RUNNING || joining) && current != null && now - lastSample >= 1_000_000_000L && sampling.compareAndSet(false, true)) {
			lastSample = now;
			IntegratedServer server = current; int id = serial; boolean preparing = joining;
			server.execute(() -> {
				try { pending.set(observe(server, id, preparing)); }
				catch (Throwable failure) { asynchronousFailure.set("server observation failed: " + failure); }
				finally { sampling.set(false); }
			});
		}
		if (machine.state() == State.WAIT_DISCONNECT && current != null && minecraft.player == null && minecraft.level == null
				&& minecraft.getSingleplayerServer() == null && current.isStopped() && !sampling.get()) {
			retired.add(new Retired(serial, now, new WeakReference<>(current))); current = null; pending.set(null);
			machine.disconnected(now); write("disconnect", fields("server", serial, "normalSaveRequested", true, "stopped", true));
			dumpThreads("disconnect-" + serial); requestGc(now);
		}
		if ((machine.state() == State.BETWEEN_WORLDS || machine.state() == State.FINAL_SETTLE) && now - lastIdle >= 1_000_000_000L) {
			lastIdle = now;
			if (now - lastGc >= 5_000_000_000L) requestGc(now);
			write("idle", fields("state", machine.state().name()));
		}
		apply(minecraft, machine.heartbeat(now));
	}
	private Observation observe(IntegratedServer server, int id, boolean preparing) {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		ServerPlayer player = players.size() == 1 ? players.get(0) : null;
		boolean alive = player != null && !player.isDeadOrDying();
		if (preparing && alive) preparePlayer(player);
		boolean[] loaded = new boolean[6]; List<Integer> chunks = new ArrayList<>();
		for (int dimension = 0; dimension < 3; dimension++) {
			ServerLevel level = level(server, dimension);
			if (level == null) throw new IllegalStateException("missing required vanilla dimension " + dimension);
			chunks.add(level.getChunkSource().getLoadedChunksCount());
			for (int side = 0; side < 2; side++) { int point = dimension * 2 + side, chunk = COORDINATES[point] >> 4; loaded[point] = level.getChunkSource().hasChunk(chunk, chunk); }
		}
		int point = -1; String dimension = "none"; double x = 0, z = 0;
		if (player != null) {
			dimension = player.level().dimension().identifier().toString(); x = player.getX(); z = player.getZ();
			for (int i = 0; i < 6; i++) if (player.level() == level(server, i / 2) && Math.abs(x - COORDINATES[i] - .5) < 2 && Math.abs(z - COORDINATES[i] - .5) < 2) point = i;
		}
		return new Observation(new Sample(System.nanoTime(), id, server.getTickCount(), server.overworld().getGameTime(),
				server.isPaused(), alive, point, loaded), List.copyOf(chunks), dimension, x, z);
	}
	private static void preparePlayer(ServerPlayer player) {
		player.setGameMode(GameType.CREATIVE); player.setInvulnerable(true); player.setNoGravity(true);
		player.getAbilities().mayfly = true; player.getAbilities().flying = true; player.onUpdateAbilities();
	}
	private static ServerLevel level(IntegratedServer server, int dimension) {
		return server.getLevel(switch (dimension) { case 0 -> Level.OVERWORLD; case 1 -> Level.NETHER; default -> Level.END; });
	}
	private void apply(Minecraft minecraft, Action action) throws Exception {
		switch (action.kind()) {
			case NONE -> { }
			case MOVE -> {
				int point = action.point(); IntegratedServer server = current;
				write("move", fields("server", serial, "point", point));
				server.execute(() -> {
					try {
						List<ServerPlayer> players = server.getPlayerList().getPlayers();
						if (players.size() != 1) throw new IllegalStateException("soak requires exactly one actual connected player");
						ServerPlayer player = players.get(0);
						preparePlayer(player);
						if (!player.teleportTo(level(server, point / 2), COORDINATES[point] + .5, 160, COORDINATES[point] + .5,
								Set.of(), 0, 0, true)) throw new IllegalStateException("native dimension teleport refused point " + point);
					} catch (Throwable failure) { asynchronousFailure.set("movement failed: " + failure); }
				});
			}
			case DISCONNECT -> { write("save-and-disconnect", fields("server", serial)); blocking("native save-and-disconnect", () -> minecraft.disconnectWithSavingScreen()); }
			case OPEN_WORLD -> {
				owner(world.resolve(".forbric-soak-world")); write("open", fields("world", worldName));
				blocking("native world open", () -> minecraft.createWorldOpenFlows().openWorld(worldName,
						() -> asynchronousFailure.set("native WorldOpenFlows cancelled or failed opening copied world")));
			}
			case STOP -> finish(minecraft);
		}
	}
	private void requestGc(long now) throws IOException { lastGc = now; System.gc(); write("gc-request", fields()); }
	private void fail(Minecraft minecraft, Throwable failure) {
		if (finished) return;
		machine.fail(failure.toString());
		try { finish(minecraft); }
		catch (Throwable secondary) { failure.addSuppressed(secondary); failure.printStackTrace(); minecraft.stop(); }
	}
	private void finish(Minecraft minecraft) throws Exception {
		if (finished) return;
		finished = true;
		if (current != null && ownershipChecked) { blocking("native save-and-disconnect while finishing", () -> minecraft.disconnectWithSavingScreen()); current = null; }
		List<Map<String, Object>> weak = weakEvidence();
		boolean completed = machine.state() == State.FINISHED && machine.activityComplete();
		List<Map<String, Object>> released = List.of(), after = weak;
		if (completed && alive(weak) && !nativeRetentionRoots.isEmpty()) {
			// Measurement is over and every session's server has stopped. Cut only the reviewed native roots' entries
			// for those servers; if the servers then become collectable, nothing else held them.
			released = releaseNativeRoots();
			for (int attempt = 0; attempt < 10 && alive(after = weakEvidence()); attempt++) { System.gc(); Thread.sleep(200); }
		}
		boolean retained = alive(after);
		String status = !completed ? "FAIL" : retained ? "REVIEW_REQUIRED" : config.control() ? "CONTROL_PASS" : "RELEASE_PASS";
		Map<String, Object> result = fields("status", status, "releaseEligible", !config.control(), "requiredSeconds", config.seconds(),
				"activeNanos", machine.activeNanos(), "actualTicks", machine.actualTicks(), "sessions", machine.sessions(), "joins", machine.joins(),
				"visits", longs(machine.visits()), "unloads", longs(machine.unloads()), "reloads", longs(machine.reloads()),
				"failure", machine.failure(), "oldServers", weak, "nativeRetentionRelease", released, "oldServersAfterNativeRelease", after,
				"retentionMeaning", "reachable after explicit GC and settling; evidence for review, not a proven leak");
		write("finish", result); dumpThreads("final");
		writeResult(result);
		System.out.println("[Forbric/ClientSoak] " + status + " activeTicks=" + machine.actualTicks() + " sessions=" + machine.sessions());
		minecraft.stop();
	}
	private static boolean alive(List<Map<String, Object>> weak) { return weak.stream().anyMatch(item -> Boolean.TRUE.equals(item.get("alive"))); }
	/** Strong references to the retired servers live only in this frame, which has returned before the GC above. */
	private List<Map<String, Object>> releaseNativeRoots() {
		Set<MinecraftServer> stopped = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Retired entry : retired) { IntegratedServer server = entry.reference().get(); if (server != null) stopped.add(server); }
		try { return NativeRetentionRelease.release(nativeRetentionRoots, stopped, ClientSoakController.class.getClassLoader()); }
		finally { stopped.clear(); }
	}
	private synchronized void writeResult(Map<String, Object> result) throws IOException {
		if (resultWritten) return;
		resultWritten = true;
		result.put("nonce", nonce); result.put("pid", ProcessHandle.current().pid());
		Files.writeString(output.resolve("controller-result.json"), SoakJson.encode(result) + "\n", StandardCharsets.UTF_8);
	}
	private interface Blocking { void run() throws Exception; }
	private void blocking(String what, Blocking call) throws Exception {
		blockedIn = what; lastTick.set(System.nanoTime());
		try { call.run(); } finally { blockedIn = "client tick"; lastTick.set(System.nanoTime()); }
	}
	/** Records a verdict when the client thread stops returning to the controller (see lastTick), then halts the
	 *  owned JVM: nothing on a thread wedged inside a native loop can be recovered, and without this the launcher
	 *  only learns of it at its own timeout, with no controller result and no thread dump. */
	private void watch() {
		long limit = config.timeoutSeconds() * 1_000_000_000L;
		while (true) {
			try { Thread.sleep(5_000); } catch (InterruptedException stop) { return; }
			long silent = System.nanoTime() - lastTick.get();
			// finish() itself may be what is wedged (its save-and-disconnect), so only a written result stands us down.
			if (resultWritten || silent <= limit) continue;
			String reason = "client thread did not return to the soak controller for " + silent / 1_000_000_000L + " seconds (in " + blockedIn + ")";
			synchronized (this) {
				if (resultWritten) continue;
				finished = true;
				try {
					Map<String, Object> result = fields("status", "FAIL", "releaseEligible", !config.control(), "requiredSeconds", config.seconds(),
							"activeNanos", machine.activeNanos(), "actualTicks", machine.actualTicks(), "sessions", machine.sessions(), "joins", machine.joins(),
							"visits", longs(machine.visits()), "unloads", longs(machine.unloads()), "reloads", longs(machine.reloads()),
							"failure", reason, "watchdog", true, "oldServers", weakEvidence());
					write("watchdog", fields("reason", reason)); dumpThreads("watchdog");
					writeResult(result);
				} catch (Throwable evidence) { evidence.printStackTrace(); }
				System.out.println("[Forbric/ClientSoak] FAIL " + reason);
				Runtime.getRuntime().halt(75);
			}
		}
	}
	private List<Map<String, Object>> weakEvidence() {
		long now = System.nanoTime(); List<Map<String, Object>> result = new ArrayList<>();
		for (Retired entry : retired) { IntegratedServer server = entry.reference().get(); result.add(fields("server", entry.serial(), "ageNanos", now - entry.at(), "alive", server != null, "stopped", server == null || server.isStopped())); }
		return result;
	}
	private synchronized void write(String type, Map<String, Object> values) throws IOException {
		Map<String, Object> event = fields("type", type, "nonce", nonce, "pid", ProcessHandle.current().pid(), "sequence", ++sequence,
				"utc", Instant.now().toString(), "nano", System.nanoTime(), "elapsedNanos", System.nanoTime() - started);
		event.putAll(values);
		event.put("heapUsed", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
		event.put("heapCommitted", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getCommitted());
		event.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
		event.put("gcCollections", ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> Math.max(0, bean.getCollectionCount())).sum());
		event.put("oldServers", weakEvidence());
		Files.writeString(output.resolve("telemetry.jsonl"), SoakJson.encode(event) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}
	private void dumpThreads(String name) throws IOException {
		StringBuilder text = new StringBuilder();
		for (var entry : Thread.getAllStackTraces().entrySet()) { text.append(entry.getKey().getName()).append(" state=").append(entry.getKey().getState()).append('\n'); for (StackTraceElement frame : entry.getValue()) text.append("  ").append(frame).append('\n'); }
		Files.writeString(output.resolve("threads-" + name + ".txt"), text, StandardCharsets.UTF_8);
	}
	private void owner(Path marker) throws IOException { if (!Files.readString(marker).strip().equals(nonce)) throw new IllegalStateException("ownership marker mismatch: " + marker); }
	private static String required(String key) { String value = System.getProperty(key); if (value == null || value.isBlank()) throw new IllegalArgumentException("missing " + key); return value; }
	private static long number(String key, long defaultValue) { return Long.parseLong(System.getProperty("forbric.soak." + key, Long.toString(defaultValue))); }
	private static List<Boolean> booleans(boolean[] values) { List<Boolean> list = new ArrayList<>(); for (boolean value : values) list.add(value); return list; }
	private static List<Long> longs(long[] values) { List<Long> list = new ArrayList<>(); for (long value : values) list.add(value); return list; }
	private static Map<String, Object> fields(Object... values) { Map<String, Object> map = new LinkedHashMap<>(); for (int i = 0; i < values.length; i += 2) map.put((String) values[i], values[i + 1]); return map; }
}
