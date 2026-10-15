/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
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
 * MixinRenamedBodyEdges through the real weave: a guest {@code HEAD} of {@code PersistentEntitySectionManager.addEntity}
 * (fabric-lifecycle's ServerEntityEvents.ALLOW_LOAD), on the merged shape where {@code addEntity} is a dispatcher in front
 * of {@code addEntityWithoutEvent} (the body, renamed: a carrier-renames.txt RENAME row) and players come in through
 * {@code addNewEntityWithoutEvent}, which calls the renamed body directly. On the renamed body the edge hears every entity;
 * with {@code -Dforbric.renamedBodyEdges=off} it hears none that comes in the direct way.
 */
class MixinRenamedBodyEdgesWeaveTest {
	private static final String CONFIG = "renamededges.mixins.json";
	private static final String MOD = "loadgate";
	private static final String TARGET = "net/minecraft/world/level/entity/PersistentEntitySectionManager";
	private static final String EVERY = "[RenamedEdge] allowed=[zombie, player] added=[zombie, player]";
	private static final String DISPATCHED_ONLY = "[RenamedEdge] allowed=[zombie] added=[zombie, player]";
	private static final String MOVED_LOG = "now edges addEntityWithoutEvent(Lnet/minecraft/world/level/entity/EntityAccess;Z)Z";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result moved, off;

	private static final String ACCESS = """
			package net.minecraft.world.level.entity;
			public interface EntityAccess { String name(); }
			""";

	/** The merged manager: addEntity posts its event and dispatches; the direct path skips it. */
	private static final String MANAGER = """
			package net.minecraft.world.level.entity;
			import java.util.ArrayList;
			import java.util.List;
			public class PersistentEntitySectionManager<T extends EntityAccess> {
			 public static final List<String> allowed=new ArrayList<>(), added=new ArrayList<>();
			 public boolean addNewEntity(T entity){return addEntity(entity,false);}
			 public boolean addNewEntityWithoutEvent(T entity){return addEntityWithoutEvent(entity,false);}
			 private boolean addEntity(T entity,boolean worldGenSpawned){return addEntityWithoutEvent(entity,worldGenSpawned);}
			 private boolean addEntityWithoutEvent(T entity,boolean worldGenSpawned){added.add(entity.name());return true;}
			 public void run(){
			  PersistentEntitySectionManager<EntityAccess> manager=new PersistentEntitySectionManager<>();
			  manager.addNewEntity(()->"zombie");
			  manager.addNewEntityWithoutEvent(()->"player");
			  System.out.println("[RenamedEdge] allowed="+allowed+" added="+added);
			 }
			}
			""";

	private static final String MIXIN = """
			package fixture.renamededges.mixin;
			import net.minecraft.world.level.entity.EntityAccess;
			import net.minecraft.world.level.entity.PersistentEntitySectionManager;
			import org.spongepowered.asm.mixin.Mixin;
			import org.spongepowered.asm.mixin.injection.At;
			import org.spongepowered.asm.mixin.injection.Inject;
			import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
			@Mixin(PersistentEntitySectionManager.class)
			public class LoadMixin {
			 @Inject(method="addEntity",at=@At("HEAD"),cancellable=true)
			 private void allow(EntityAccess entity,boolean worldGenSpawned,CallbackInfoReturnable<Boolean> cir){PersistentEntitySectionManager.allowed.add(entity.name());}
			}
			""";

	@BeforeAll static void weaveBothWays() throws Exception {
		Path json = work.resolve(CONFIG);
		Files.writeString(json, "{\"required\":true,\"package\":\"fixture.renamededges.mixin\",\"mixins\":[\"LoadMixin\"],\"injectors\":{\"defaultRequire\":1}}");
		fixture = WeaveHarness.fixture(work, "renamededges", List.of(source("EntityAccess.java", ACCESS),
				source("PersistentEntitySectionManager.java", MANAGER), source("LoadMixin.java", MIXIN)), Map.of(CONFIG, json));
		moved = run("moved", Map.of());
		off = run("off", Map.of(MixinRenamedBodyEdges.PROPERTY, "off"));
	}

	@Test void theEdgeHearsEveryEntityWhicheverWayItComesIn() throws Exception {
		assertTrue(movedHolds(moved), moved.describe());
		WeaveHarness.assertWovenAndVerified(moved, TARGET, fixture);
	}

	@Test void switchedOffTheEdgeMissesTheDirectWay() {
		assertTrue(offHolds(off), off.describe());
	}

	/** The switch is the runs' only difference, so each run's predicate must reject the other. */
	@Test void theControlFlipsEveryAssertion() {
		assertTrue(movedHolds(moved) && !movedHolds(off), "the move predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(moved), "the control predicate does not separate the runs");
	}

	private static boolean movedHolds(WeaveHarness.Result run) {
		return run.printedLine(EVERY) && run.printed(MOVED_LOG) && run.findings().stream().noneMatch(WeaveHarness.Finding::confirmedRequired);
	}

	private static boolean offHolds(WeaveHarness.Result run) {
		return run.printedLine(DISPATCHED_ONLY) && !run.printed(MOVED_LOG);
	}

	private static Path source(String name, String text) throws Exception {
		Path file = work.resolve("src").resolve(name);
		Files.createDirectories(file.getParent());
		Files.writeString(file, text);
		return file;
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER,
				"net.minecraft.world.level.entity.PersistentEntitySectionManager", "run", properties);
	}
}
