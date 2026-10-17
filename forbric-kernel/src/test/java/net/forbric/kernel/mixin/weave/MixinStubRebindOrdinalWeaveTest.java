package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MixinStubRebind;

/**
 * The stub rebind of a {@code @ModifyVariable} discriminated by {@code ordinal} alone, through the real weave.
 *
 * <p>A stand-in {@code ServerExplosion} has the shipped carrier-stubs.txt row's shape (the row is keyed on the game's
 * class, so the stand-in carries that name; the guest shares no name with any mod): vanilla's {@code hurtEntities()} is
 * the body, as merged it is a stub nothing calls, forwarding {@code List.of()} to the carrier's
 * {@code hurtEntities(List)}, whose new argument is a {@code List} as the entity list is. The guest,
 * {@code com.coral.drift}, spares the cow with {@code @ModifyVariable(at = @At("STORE"), ordinal = 0)} on the list.
 * Mixin counts that ordinal over every {@code List} in the table, arguments included, so on the body the same ordinal
 * picks the carrier's argument.
 * <ul>
 *   <li>pinned — the native method's ordinal-0 list and the body's one non-added {@code List} hold the same value
 *       ({@code this.gather()}): the handler moves with {@code index = 2} and spares the cow, as on the native class;</li>
 *   <li>off — {@code -Dforbric.mixinStubRebind.modifyVariable=off}: it stays on the stub nothing calls and never runs;</li>
 *   <li>unproved — the body stores another value ({@code this.gatherAll()}): nothing is pinned, nothing moves.</li>
 * </ul>
 */
class MixinStubRebindOrdinalWeaveTest {
	private static final String OWNER = "net/minecraft/world/level/ServerExplosion";
	private static final String CONFIG = "drift.mixins.json";
	private static final String SPARED = "[Drift] seen=[drift:[zombie, cow], hit:zombie]";
	private static final String NEVER_RAN = "[Drift] seen=[hit:zombie, hit:cow]";
	private static final String NEVER_RAN_ALL = "[Drift] seen=[hit:zombie, hit:cow, hit:pig]";
	private static final String PINNED = "takes slot 2 — the local its ordinal picks in the method it was written for";
	private static final String MOVED = "now targets net.minecraft.world.level.ServerExplosion.hurtEntities(Ljava/util/List;)V";

	@TempDir static Path work;
	private static Path pinnedFixture, unprovedFixture;
	private static WeaveHarness.Result pinned, off, unproved, natively;

	@BeforeAll static void weave() throws Exception {
		Path nativeFixture = fixture("native", """
				 public void explode() { hurtEntities(); }
				 private void hurtEntities() {
				  List<String> entities = this.gather();
				  for (String entity : entities) SEEN.add("hit:" + entity);
				 }
				""");
		Map<String, byte[]> original = NativeWeaveReferences.classes(nativeFixture);
		pinnedFixture = NativeWeaveReferences.with(work, fixture("pinned", merged("this.gather()")), original);
		unprovedFixture = NativeWeaveReferences.with(work, fixture("unproved", merged("this.gatherAll()")), original);
		pinned = run("pinned", pinnedFixture, Map.of());
		off = run("off", pinnedFixture, Map.of(MixinStubRebind.MODIFY_VARIABLE_PROPERTY, "off"));
		unproved = run("unproved", unprovedFixture, Map.of());
		natively = run("native", nativeFixture, Map.of());
	}

	@Test void theNativeGuestSparesTheCow() {
		assertTrue(natively.printed(SPARED), natively.describe());
	}

	@Test void pinnedByWhatItHoldsTheHandlerSparesTheCowOnTheBody() throws Exception {
		assertTrue(pinned.printed(SPARED), pinned.describe());
		assertTrue(pinned.printed(PINNED), pinned.describe());
		assertTrue(pinned.printed(MOVED), pinned.describe());
		WeaveHarness.assertWovenAndVerified(pinned, OWNER, pinnedFixture);
	}

	@Test void switchedOffTheHandlerStaysOnTheStubNothingCalls() {
		assertTrue(off.printed(NEVER_RAN), off.describe());
		assertFalse(off.printed(MOVED), off.describe());
	}

	@Test void aLocalHoldingAnotherValueIsNotPinned() {
		assertTrue(unproved.printed(NEVER_RAN_ALL), unproved.describe());
		assertFalse(unproved.printed(PINNED), unproved.describe());
		assertFalse(unproved.printed(MOVED), unproved.describe());
	}

	@Test void theControlFlipsEveryAssertion() {
		assertTrue(pinned.printed(SPARED) && !off.printed(SPARED) && !unproved.printed("drift:"));
	}

	private static String merged(String stored) {
		return """
				 public void explode() { hurtEntities(new ArrayList<>(List.of("stone"))); }
				 @Deprecated private void hurtEntities() { hurtEntities(List.of()); }
				 private void hurtEntities(List<String> blocks) {
				  List<String> entities = %s;
				  entities.removeAll(blocks);
				  for (String entity : entities) SEEN.add("hit:" + entity);
				 }
				""".formatted(stored);
	}

	private static Path fixture(String name, String body) throws Exception {
		Path explosion = source(name + "/ServerExplosion.java", """
				package net.minecraft.world.level;
				import java.util.*;
				public class ServerExplosion {
				 public static final List<String> SEEN = new ArrayList<>();
				 private List<String> gather() { return new ArrayList<>(List.of("zombie", "cow")); }
				 private List<String> gatherAll() { return new ArrayList<>(List.of("zombie", "cow", "pig")); }
				%s}
				""".formatted(body));
		Path probe = source(name + "/Probe.java", "package fixture.drift;public class Probe{public static void probe(){"
				+ "new net.minecraft.world.level.ServerExplosion().explode();System.out.println(\"[Drift] seen=\"+net.minecraft.world.level.ServerExplosion.SEEN);}}");
		Path mixin = source(name + "/BlastMixin.java", """
				package com.coral.drift.mixin;
				import java.util.ArrayList;
				import java.util.List;
				import net.minecraft.world.level.ServerExplosion;
				import org.spongepowered.asm.mixin.Mixin;
				import org.spongepowered.asm.mixin.injection.At;
				import org.spongepowered.asm.mixin.injection.ModifyVariable;
				@Mixin(ServerExplosion.class)
				public class BlastMixin {
				 @ModifyVariable(method = "hurtEntities", at = @At("STORE"), ordinal = 0, require = 0)
				 private List<String> drift$spare(List<String> entities) {
				  ServerExplosion.SEEN.add("drift:" + entities);
				  List<String> kept = new ArrayList<>(entities);
				  kept.remove("cow");
				  return kept;
				 }
				}
				""");
		Path json = source(name + "/" + CONFIG, "{\"required\":false,\"package\":\"com.coral.drift.mixin\",\"mixins\":[\"BlastMixin\"],\"injectors\":{\"defaultRequire\":0}}");
		return WeaveHarness.fixture(work, name, List.of(explosion, probe, mixin), Map.of(CONFIG, json), List.of("-g"));
	}

	private static WeaveHarness.Result run(String label, Path fixture, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, "drift", Ecosystem.FABRIC, EnvType.SERVER, "fixture.drift.Probe", "probe", properties);
	}

	private static Path source(String name, String text) throws Exception {
		Path file = work.resolve("src").resolve(name);
		Files.createDirectories(file.getParent());
		Files.writeString(file, text);
		return file;
	}
}
