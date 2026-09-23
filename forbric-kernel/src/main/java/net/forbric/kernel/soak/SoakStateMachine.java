package net.forbric.kernel.soak;

import java.util.Arrays;

/** Pure decision model. Only advancing server AND world ticks can earn measured active time. */
public final class SoakStateMachine {
	public static final long RELEASE_MIN_SECONDS = 7200;
	public enum State { WAIT_WORLD, RUNNING, WAIT_DISCONNECT, BETWEEN_WORLDS, FINAL_SETTLE, FINISHED, FAILED }
	public enum Kind { NONE, MOVE, DISCONNECT, OPEN_WORLD, STOP }
	public record Action(Kind kind, int point) { public static Action none() { return new Action(Kind.NONE, -1); } }
	public record Config(long seconds, boolean control, int dwellTicks, int routesPerSession, int minSessions,
			int warmupTicks, long betweenSeconds, long settleSeconds, long timeoutSeconds) {
		public Config {
			Math.multiplyExact(seconds, 1_000_000_000L);
			if (seconds < 1 || (!control && seconds < RELEASE_MIN_SECONDS)) throw new IllegalArgumentException("release soak requires at least 7200 seconds; short runs must explicitly be controls");
			if (dwellTicks < 1 || routesPerSession < 2 || minSessions < 2 || warmupTicks < 0 || betweenSeconds < 0 || settleSeconds < 0 || timeoutSeconds < 1)
				throw new IllegalArgumentException("invalid soak configuration");
		}
	}
	public record Sample(long nanoTime, int server, int tick, long gameTime, boolean paused, boolean player,
			int playerPoint, boolean[] loaded) {
		public Sample { if (loaded.length != 6) throw new IllegalArgumentException("six fixed chunk probes required"); loaded = loaded.clone(); }
		@Override public boolean[] loaded() { return loaded.clone(); }
	}
	private final Config config;
	private State state = State.WAIT_WORLD;
	private long phaseAt, progressAt, activeNanos, actualTicks, movedAt;
	private Sample previous;
	private int server, joins, sessions, desired = -1, visitsThisSession, arrivedTick;
	private boolean awaitingArrival;
	private String failure;
	private final long[] visits = new long[6], unloads = new long[6], reloads = new long[6];
	private final boolean[] seen = new boolean[6], wasLoaded = new boolean[6], unloaded = new boolean[6];
	public SoakStateMachine(Config config, long now) { this.config = config; phaseAt = progressAt = now; }
	public State state() { return state; }
	public long activeNanos() { return activeNanos; }
	public long actualTicks() { return actualTicks; }
	public int joins() { return joins; }
	public int sessions() { return sessions; }
	public long[] visits() { return visits.clone(); }
	public long[] unloads() { return unloads.clone(); }
	public long[] reloads() { return reloads.clone(); }
	public String failure() { return failure; }

	public void joined(Sample sample) {
		if (state != State.WAIT_WORLD || !sample.player() || sample.server() <= server) throw new IllegalStateException("expected a new, occupied integrated server");
		server = sample.server(); joins++; previous = sample; state = State.RUNNING; phaseAt = progressAt = sample.nanoTime();
		desired = -1; visitsThisSession = 0; awaitingArrival = false; arrivedTick = sample.tick();
		Arrays.fill(seen, false); Arrays.fill(wasLoaded, false); Arrays.fill(unloaded, false);
	}
	public Action observe(Sample sample) {
		if (state != State.RUNNING) return Action.none();
		if (sample.server() != server) return fail("server identity changed without a normal disconnect");
		long tickDelta = (long) sample.tick() - previous.tick(), worldDelta = sample.gameTime() - previous.gameTime();
		long elapsed = sample.nanoTime() - previous.nanoTime();
		if (tickDelta < 0 || worldDelta < 0 || elapsed < 0) return fail("simulation counters moved backwards within one server");
		long real = Math.min(tickDelta, worldDelta);
		if (sample.player() && previous.player() && !sample.paused() && !previous.paused() && real > 0) {
			actualTicks += real;
			activeNanos += Math.min(elapsed, real * 50_000_000L);
			progressAt = sample.nanoTime();
		}
		previous = sample;
		for (int i = 0; i < 6; i++) {
			boolean loaded = sample.loaded()[i];
			if (loaded) { if (unloaded[i] && !wasLoaded[i]) reloads[i]++; seen[i] = true; }
			else if (seen[i] && wasLoaded[i]) { unloaded[i] = true; unloads[i]++; }
			wasLoaded[i] = loaded;
		}
		if (sample.nanoTime() - progressAt > config.timeoutSeconds() * 1_000_000_000L) return fail("no advancing occupied, unpaused simulation");
		if (desired < 0) {
			if (sample.tick() - arrivedTick >= config.warmupTicks()) return move(0, sample.nanoTime());
		} else if (awaitingArrival) {
			if (sample.playerPoint() == desired && sample.loaded()[desired]) {
				visits[desired]++; visitsThisSession++; arrivedTick = sample.tick(); awaitingArrival = false;
			} else if (sample.nanoTime() - movedAt > config.timeoutSeconds() * 1_000_000_000L) {
				// Ticks keep advancing while a displaced player or a chunk that never reports loaded waits here, so the
				// stall timeout above cannot see it; without this bound only the launcher's kill ends the run.
				return fail("probe " + desired + " was not reached within " + config.timeoutSeconds() + " seconds");
			}
		} else if (sample.tick() - arrivedTick >= config.dwellTicks()) {
			if (visitsThisSession >= 6 * config.routesPerSession()) { state = State.WAIT_DISCONNECT; phaseAt = sample.nanoTime(); return new Action(Kind.DISCONNECT, -1); }
			return move((desired + 1) % 6, sample.nanoTime());
		}
		return Action.none();
	}
	private Action move(int point, long now) { desired = point; awaitingArrival = true; movedAt = now; return new Action(Kind.MOVE, point); }
	public void disconnected(long now) {
		if (state != State.WAIT_DISCONNECT) throw new IllegalStateException("disconnect was not requested");
		sessions++; previous = null; phaseAt = now;
		state = activityComplete() ? State.FINAL_SETTLE : State.BETWEEN_WORLDS;
	}
	public Action heartbeat(long now) {
		if ((state == State.WAIT_WORLD || state == State.WAIT_DISCONNECT) && now - phaseAt > config.timeoutSeconds() * 1_000_000_000L) return fail("world open/close timed out");
		if (state == State.RUNNING && now - progressAt > config.timeoutSeconds() * 1_000_000_000L) return fail("simulation stopped responding");
		if (state == State.BETWEEN_WORLDS && now - phaseAt >= config.betweenSeconds() * 1_000_000_000L) {
			state = State.WAIT_WORLD; phaseAt = now; return new Action(Kind.OPEN_WORLD, -1);
		}
		if (state == State.FINAL_SETTLE && now - phaseAt >= config.settleSeconds() * 1_000_000_000L) {
			state = State.FINISHED; return new Action(Kind.STOP, -1);
		}
		return Action.none();
	}
	public boolean activityComplete() {
		return activeNanos >= config.seconds() * 1_000_000_000L && actualTicks >= config.seconds() * 20L
				&& sessions >= config.minSessions() && Arrays.stream(visits).allMatch(n -> n >= 2)
				&& Arrays.stream(unloads).allMatch(n -> n > 0) && Arrays.stream(reloads).allMatch(n -> n > 0);
	}
	public Action fail(String reason) { failure = reason; state = State.FAILED; return new Action(Kind.STOP, -1); }
}
