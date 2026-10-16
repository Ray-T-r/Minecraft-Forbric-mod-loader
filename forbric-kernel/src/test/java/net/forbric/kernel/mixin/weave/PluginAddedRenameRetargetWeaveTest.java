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
import net.forbric.kernel.mixin.KernelGuestMixinAdapter;

/**
 * KernelGuestMixinAdapter's late judgment, shown by behaviour alone, on a repair nothing but MixinRetarget's plan makes:
 * an anchored {@code @Inject} in a body a carrier renamed (R3, along the shipped carrier-renames.txt row of
 * {@code PersistentEntitySectionManager.addEntity -> addEntityWithoutEvent}). No as-loaded adapter moves an anchored
 * injector along a RENAME — MixinRenamedBodyEdges takes edges only, the builder-stub and callback adapters other shapes —
 * so whether the hook runs depends on the mixin being judged at all. The stand-in has the merged shape: {@code addEntity}
 * posts the carrier's event and hands the rest to {@code addEntityWithoutEvent}, which makes vanilla's
 * {@code setLevelCallback} call.
 * <ul>
 *   <li>plugin — the config lists nothing and its plugin adds the mixin: judged when Mixin reads it, retargeted, and the
 *       hook hears the call;</li>
 *   <li>plugin, late judgment off — the only difference: unjudged, the hook binds nothing (require 0) and never runs;</li>
 *   <li>listed, late judgment off — the same mixin listed: the config-time pass retargets it whatever the switch says, so the
 *       switch is what decides for the plugin-added one, and the rename rule works on this fixture.</li>
 * </ul>
 */
class PluginAddedRenameRetargetWeaveTest {
	private static final String CONFIG = "renamed.mixins.json";
	private static final String MOD = "renamedhook";
	private static final String TARGET = "net/minecraft/world/level/entity/PersistentEntitySectionManager";
	private static final String HEARD = "[Renamed] trail=[event:zombie, hook:zombie, callback:zombie]";
	private static final String MISSED = "[Renamed] trail=[event:zombie, callback:zombie]";

	@TempDir static Path work;
	private static WeaveHarness.Result plugin, pluginOff, listedOff;

	@BeforeAll static void weave() throws Exception {
		List<Path> game = List.of(
				source("game/EntityInLevelCallback.java", "package net.minecraft.world.level.entity;public interface EntityInLevelCallback{}"),
				source("game/EntityAccess.java", "package net.minecraft.world.level.entity;public interface EntityAccess{void setLevelCallback(EntityInLevelCallback callback);}"),
				source("game/PersistentEntitySectionManager.java", """
						package net.minecraft.world.level.entity;
						public class PersistentEntitySectionManager<T extends EntityAccess> {
						 public static final java.util.List<String> TRAIL = new java.util.ArrayList<>();
						 public boolean addNewEntity(T entity) { return addEntity(entity, false); }
						 private boolean addEntity(T entity, boolean worldGenSpawned) { TRAIL.add("event:" + entity); return addEntityWithoutEvent(entity, worldGenSpawned); }
						 public boolean addNewEntityWithoutEvent(T entity) { return addEntityWithoutEvent(entity, false); }
						 private boolean addEntityWithoutEvent(T entity, boolean worldGenSpawned) { entity.setLevelCallback(new EntityInLevelCallback() {}); return true; }
						}
						"""),
				source("game/Probe.java", """
						package fixture.renamed;
						import net.minecraft.world.level.entity.*;
						public class Probe {
						 record Thing(String name) implements EntityAccess {
						  public void setLevelCallback(EntityInLevelCallback callback) { PersistentEntitySectionManager.TRAIL.add("callback:" + name); }
						  public String toString() { return name; }
						 }
						 public static void probe() {
						  new PersistentEntitySectionManager<Thing>().addNewEntity(new Thing("zombie"));
						  System.out.println("[Renamed] trail=" + PersistentEntitySectionManager.TRAIL);
						 }
						}
						"""),
				source("mixin/ManagerMixin.java", """
						package fixture.renamed.mixin;
						import net.minecraft.world.level.entity.*;
						import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.*;
						import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
						@Mixin(PersistentEntitySectionManager.class)public class ManagerMixin {
						 @Inject(method="addEntity",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/entity/EntityAccess;setLevelCallback(Lnet/minecraft/world/level/entity/EntityInLevelCallback;)V"),require=0)
						 private <T extends EntityAccess> void hook(T entity, boolean worldGenSpawned, CallbackInfoReturnable<Boolean> cir) { PersistentEntitySectionManager.TRAIL.add("hook:" + entity); }
						}
						"""),
				source("mixin/Plugin.java", """
						package fixture.renamed.mixin;
						import java.util.List;import java.util.Set;
						import org.objectweb.asm.tree.ClassNode;
						import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
						public class Plugin implements IMixinConfigPlugin {
						 public void onLoad(String mixinPackage){}
						 public String getRefMapperConfig(){return null;}
						 public boolean shouldApplyMixin(String targetClassName,String mixinClassName){return true;}
						 public void acceptTargets(Set<String> myTargets,Set<String> otherTargets){}
						 public List<String> getMixins(){return List.of("ManagerMixin");}
						 public void preApply(String targetClassName,ClassNode targetClass,String mixinClassName,IMixinInfo mixinInfo){}
						 public void postApply(String targetClassName,ClassNode targetClass,String mixinClassName,IMixinInfo mixinInfo){}
						}
						"""));
		Path pluginJson = source("plugin/" + CONFIG, "{\"required\":true,\"package\":\"fixture.renamed.mixin\",\"plugin\":\"fixture.renamed.mixin.Plugin\",\"mixins\":[],\"injectors\":{\"defaultRequire\":0}}");
		Path listedJson = source("listed/" + CONFIG, "{\"required\":true,\"package\":\"fixture.renamed.mixin\",\"mixins\":[\"ManagerMixin\"],\"injectors\":{\"defaultRequire\":0}}");
		Path pluginFixture = WeaveHarness.fixture(work, "plugin", game, Map.of(CONFIG, pluginJson));
		Path listedFixture = WeaveHarness.fixture(work, "listed", game, Map.of(CONFIG, listedJson));
		plugin = run("plugin", pluginFixture, Map.of());
		pluginOff = run("plugin-off", pluginFixture, Map.of(KernelGuestMixinAdapter.LATE_PROPERTY, "off"));
		listedOff = run("listed-off", listedFixture, Map.of(KernelGuestMixinAdapter.LATE_PROPERTY, "off"));
	}

	@Test void aPluginAddedHookInARenamedBodyIsRetargetedAndRuns() throws Exception {
		assertTrue(plugin.printedLine(HEARD), plugin.describe());
	}

	@Test void withTheLateJudgmentOffThePluginAddedHookNeverRuns() throws Exception {
		assertTrue(pluginOff.printedLine(MISSED), pluginOff.describe());
	}

	@Test void aListedHookIsRetargetedWhateverTheLateSwitchSays() throws Exception {
		assertTrue(listedOff.printedLine(HEARD), listedOff.describe());
	}

	private static WeaveHarness.Result run(String label, Path jar, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, jar, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER, "fixture.renamed.Probe", "probe", properties);
	}

	private static Path source(String name, String text) throws Exception {
		Path file = work.resolve("src").resolve(name);
		Files.createDirectories(file.getParent());
		Files.writeString(file, text);
		return file;
	}
}
