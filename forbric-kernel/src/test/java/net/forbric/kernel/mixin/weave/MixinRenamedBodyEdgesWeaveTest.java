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
import net.forbric.kernel.mixin.MixinRenamedBodyEdges;

/**
 * MixinRenamedBodyEdges through the real weave, on a stand-in {@code PersistentEntitySectionManager} shaped like the
 * merged one where the shipped carrier-renames.txt row reads it: {@code addEntity} (the dispatcher) runs the carrier's
 * join listeners ({@code join:}) and then the renamed body {@code addEntityWithoutEvent} ({@code body:});
 * {@code addNewEntityWithoutEvent} — how every player is added — forwards to the renamed body directly. A Fabric mod's
 * cancellable HEAD of vanilla's {@code addEntity} ({@code allow:}, vetoing names starting with "vetoed") must, as on
 * native Fabric, decide before anything else runs for every entity, and must also decide for the ones added through the
 * forwarder — once each.
 * <ul>
 *   <li>on — the veto stays first on the dispatcher (a vetoed sheep is never offered to the join listeners) and the
 *       forwarder's copy covers players (a vetoed player is never added);</li>
 *   <li>off — {@code -Dforbric.renamedBodyEdges=off}: the dispatcher path is right but players are never offered to the
 *       veto (the defect this rule was first written for).</li>
 * </ul>
 * The previous form of the rule moved the HEAD onto the renamed body: then the log would read
 * {@code join:vetoedSheep, allow:vetoedSheep} — the order {@link #theVetoDecidesBeforeTheJoinListenersAndPlayersAreOfferedToIt}
 * rules out.
 */
class MixinRenamedBodyEdgesWeaveTest {
	private static final String OWNER = "net/minecraft/world/level/entity/PersistentEntitySectionManager";
	private static final String CONFIG = "edges.mixins.json";
	private static final String NATIVE_ORDER = "[Edges] log=[allow:zombie, join:zombie, body:zombie, allow:vetoedSheep, allow:player, body:player, allow:vetoedPlayer]";
	private static final String NATIVE_ADDED = "[Edges] added=true,false,true,false";
	private static final String OFF_ORDER = "[Edges] log=[allow:zombie, join:zombie, body:zombie, allow:vetoedSheep, body:player, body:vetoedPlayer]";
	private static final String OFF_ADDED = "[Edges] added=true,false,true,true";
	private static final String EDGED = "also edges [addNewEntityWithoutEvent(Lnet/minecraft/world/level/entity/EntityAccess;)Z]";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result on, off;

	@BeforeAll static void weave() throws Exception {
		Path access = source("EntityAccess.java", "package net.minecraft.world.level.entity;public interface EntityAccess{}");
		Path manager = source("PersistentEntitySectionManager.java", """
				package net.minecraft.world.level.entity;
				public class PersistentEntitySectionManager<T extends EntityAccess> {
				 public static final java.util.List<String> LOG = new java.util.ArrayList<>();
				 public boolean addNewEntity(T entity) { return addEntity(entity, false); }
				 private boolean addEntity(T entity, boolean worldGenSpawned) { LOG.add("join:" + entity); return addEntityWithoutEvent(entity, worldGenSpawned); }
				 public boolean addNewEntityWithoutEvent(T entity) { return addEntityWithoutEvent(entity, false); }
				 private boolean addEntityWithoutEvent(T entity, boolean worldGenSpawned) { LOG.add("body:" + entity); return true; }
				}
				""");
		Path probe = source("Probe.java", """
				package fixture.edges;
				import net.minecraft.world.level.entity.*;
				public class Probe {
				 record Thing(String name) implements EntityAccess { public String toString() { return name; } }
				 public static void probe() {
				  PersistentEntitySectionManager<Thing> m = new PersistentEntitySectionManager<>();
				  boolean a = m.addNewEntity(new Thing("zombie")), b = m.addNewEntity(new Thing("vetoedSheep"));
				  boolean c = m.addNewEntityWithoutEvent(new Thing("player")), d = m.addNewEntityWithoutEvent(new Thing("vetoedPlayer"));
				  System.out.println("[Edges] added=" + a + "," + b + "," + c + "," + d);
				  System.out.println("[Edges] log=" + PersistentEntitySectionManager.LOG);
				 }
				}
				""");
		Path mixin = source("ManagerMixin.java", """
				package fixture.edges.mixin;
				import net.minecraft.world.level.entity.*;
				import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.*;
				import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
				@Mixin(PersistentEntitySectionManager.class)public class ManagerMixin {
				 @Inject(method="addEntity",at=@At("HEAD"),cancellable=true)
				 private <T extends EntityAccess> void beforeAddEntity(T entity, boolean worldGenSpawned, CallbackInfoReturnable<Boolean> cir) {
				  PersistentEntitySectionManager.LOG.add("allow:" + entity);
				  if (entity.toString().startsWith("vetoed")) cir.setReturnValue(false);
				 }
				}
				""");
		Path json = source(CONFIG, "{\"required\":true,\"package\":\"fixture.edges.mixin\",\"mixins\":[\"ManagerMixin\"],\"injectors\":{\"defaultRequire\":1}}");
		fixture = WeaveHarness.fixture(work, "edges", List.of(access, manager, probe, mixin), Map.of(CONFIG, json));
		on = run("on", Map.of());
		off = run("off", Map.of(MixinRenamedBodyEdges.PROPERTY, "off"));
	}

	@Test void theVetoDecidesBeforeTheJoinListenersAndPlayersAreOfferedToIt() throws Exception {
		assertTrue(on.printed(NATIVE_ADDED), on.describe());
		assertTrue(on.printed(NATIVE_ORDER), "each entity meets the veto once, first: " + on.describe());
		assertTrue(on.printed(EDGED), on.describe());
		WeaveHarness.assertWovenAndVerified(on, OWNER, fixture);
	}

	@Test void switchedOffPlayersNeverMeetTheVeto() throws Exception {
		assertTrue(off.printed(OFF_ADDED), off.describe());
		assertTrue(off.printed(OFF_ORDER), off.describe());
		assertFalse(off.printed(EDGED), off.describe());
		assertFalse(off.printed(NATIVE_ORDER), off.describe());
		WeaveHarness.assertWovenAndVerified(off, OWNER, fixture);
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, "edgesprobe", Ecosystem.FABRIC, EnvType.SERVER, "fixture.edges.Probe", "probe",
				properties);
	}

	private static Path source(String name, String text) throws Exception {
		Path file = work.resolve("src").resolve(name);
		Files.createDirectories(file.getParent());
		Files.writeString(file, text);
		return file;
	}
}
