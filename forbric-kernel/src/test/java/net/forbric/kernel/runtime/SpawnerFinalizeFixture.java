/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.tools.ToolProvider;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.Opcodes;
import net.fabricmc.api.EnvType;
import net.forbric.kernel.transform.SpawnerFinalizeInjector;
import net.forbric.kernel.transform.TransformContext;

/** Actual carrier hook bytecode and compiled runtime helper; controllable events/world at the boundary. */
final class SpawnerFinalizeFixture implements AutoCloseable {
	private final URLClassLoader loader;
	private final Class<?> probe;
	private final Object spawner, level, position;

	SpawnerFinalizeFixture(Path directory) throws Exception {
		Path classes = directory.resolve("classes"); Files.createDirectories(classes);
		List<String> args = new ArrayList<>(List.of("-proc:none", "-d", classes.toString()));
		for (var source : sources().entrySet()) {
			Path path = directory.resolve("src/" + source.getKey().replace('.', '/') + ".java");
			Files.createDirectories(path.getParent()); Files.writeString(path, source.getValue()); args.add(path.toString());
		}
		ByteArrayOutputStream errors = new ByteArrayOutputStream();
		assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, errors, errors, args.toArray(String[]::new)), errors.toString());
		copyHook(classes, "forge-runtime/forge-runtime.jar", "net.minecraftforge.event.ForgeEventFactory", "onFinalizeSpawnSpawner");
		copyHook(classes, "neoforge-runtime/neoforge-runtime.jar", "net.neoforged.neoforge.event.EventHooks", "finalizeMobSpawnSpawner");
		Path caller = classes.resolve("net/minecraft/world/level/BaseSpawner.class");
		byte[] before = Files.readAllBytes(caller);
		// Keep an otherwise identical, untransformed caller for behavioral comparisons with the native hook.
		ClassNode callerNode = new ClassNode(); new ClassReader(before).accept(callerNode, 0);
		MethodNode originalTick = callerNode.methods.stream().filter(m -> m.name.equals("serverTick")).findFirst().orElseThrow();
		MethodNode nativeTick = new MethodNode(Opcodes.ASM9, originalTick.access, "nativeServerTick", originalTick.desc,
				originalTick.signature, originalTick.exceptions.toArray(String[]::new));
		originalTick.accept(nativeTick); callerNode.methods.add(nativeTick);
		ClassWriter callerWriter = new ClassWriter(0); callerNode.accept(callerWriter); before = callerWriter.toByteArray();
		byte[] after = new SpawnerFinalizeInjector().transform("net.minecraft.world.level.BaseSpawner", before,
				new TransformContext(EnvType.SERVER, false, "mojmap"));
		assertNotSame(before, after, "the behavioral caller must actually receive its ValueInput argument");
		Files.write(caller, after);
		Path runtime = Path.of(System.getProperty("forbric.test.runtimeClasses", "build/classes/java/runtime"));
		assertTrue(Files.isRegularFile(runtime.resolve("net/forbric/kernel/runtime/KernelSpawnerFinalize.class")));
		loader = new URLClassLoader(new URL[] { classes.toUri().toURL(), runtime.toUri().toURL(),
				Path.of("build/classes/java/main").toUri().toURL() }, ClassLoader.getPlatformClassLoader());
		probe = type("fixture.SpawnerProbe");
		spawner = type("net.minecraft.world.level.BaseSpawner").getConstructor().newInstance();
		level = type("net.minecraft.server.level.ServerLevel").getConstructor().newInstance();
		position = type("net.minecraft.core.BlockPos").getConstructor().newInstance();
	}

	void tick() throws Exception { tick("serverTick"); }
	void nativeTick() throws Exception { tick("nativeServerTick"); }
	private void tick(String name) throws Exception {
		try { spawner.getClass().getMethod(name, level.getClass(), position.getClass()).invoke(spawner, level, position); }
		catch (InvocationTargetException wrapped) {
			if (wrapped.getCause() instanceof RuntimeException failure) throw failure;
			if (wrapped.getCause() instanceof Error failure) throw failure;
			throw wrapped;
		}
	}
	Object value(String name) throws Exception { return probe.getField(name).get(null); }
	void set(String name, Object value) throws Exception { probe.getField(name).set(null, value); }
	int count(String name) throws Exception { return probe.getField(name).getInt(null); }
	Object data() throws Exception { return type("fixture.Data").getConstructor().newInstance(); }
	Object difficulty() throws Exception { return type("net.minecraft.world.DifficultyInstance").getConstructor().newInstance(); }
	Object input() throws Exception { return type("fixture.Input").getConstructor().newInstance(); }
	@SuppressWarnings("unchecked") List<String> trace() throws Exception { return (List<String>) value("trace"); }
	List<?> findings() throws Exception { return (List<?>) type("net.forbric.api.CompatibilityFindings").getMethod("all").invoke(null); }
	private Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name, true, loader); }
	@Override public void close() throws Exception { loader.close(); }

	private static void copyHook(Path classes, String jar, String owner, String method) throws Exception {
		ClassNode original = new ClassNode(); new ClassReader(ForgeSpawnFixture.staged(jar, owner)).accept(original, 0);
		var hook = original.methods.stream().filter(m -> m.name.equals(method)).findFirst().orElseThrow();
		ClassWriter writer = new ClassWriter(0); writer.visit(original.version, original.access, original.name, null, "java/lang/Object", null);
		hook.accept(writer); writer.visitEnd(); Path path = classes.resolve(owner.replace('.', '/') + ".class");
		Files.createDirectories(path.getParent()); Files.write(path, writer.toByteArray());
	}

	private static Map<String, String> sources() {
		Map<String, String> out = new LinkedHashMap<>();
		for (String name : List.of("net.minecraft.core.BlockPos", "net.minecraft.world.DifficultyInstance",
				"net.minecraft.nbt.CompoundTag", "com.mojang.datafixers.util.Either")) {
			int dot = name.lastIndexOf('.'); out.put(name, "package " + name.substring(0, dot) + "; public class " + name.substring(dot + 1) + " {}");
		}
		for (String name : List.of("net.minecraft.world.level.storage.ValueInput", "net.minecraft.world.entity.SpawnGroupData",
				"net.minecraft.world.entity.EntityProcessor", "net.minecraft.util.ProblemReporter",
				"net.minecraft.world.level.ServerLevelAccessor", "net.minecraftforge.eventbus.internal.Event")) {
			int dot = name.lastIndexOf('.'); out.put(name, "package " + name.substring(0, dot) + "; public interface " + name.substring(dot + 1) + " {}");
		}
		out.put("net.minecraft.core.HolderLookup", "package net.minecraft.core; public class HolderLookup { public interface Provider {} }");
		out.put("net.minecraft.world.entity.EntitySpawnReason", "package net.minecraft.world.entity; public enum EntitySpawnReason { SPAWNER, NATURAL }");
		out.put("net.minecraft.world.level.Level", "package net.minecraft.world.level; public class Level {}");
		out.put("fixture.Input", "package fixture; public class Input implements net.minecraft.world.level.storage.ValueInput {}");
		out.put("fixture.Data", "package fixture; public class Data implements net.minecraft.world.entity.SpawnGroupData {}");
		out.put("net.minecraft.world.entity.Entity", "package net.minecraft.world.entity; public class Entity { public double getX(){return 0;} public double getY(){return 0;} public double getZ(){return 0;} }");
		out.put("net.minecraft.world.entity.Mob", """
			package net.minecraft.world.entity;
			public class Mob extends Entity {
			 private boolean cancelled;
			 public void setSpawnCancelled(boolean value){cancelled=value;} public boolean isSpawnCancelled(){return cancelled;}
			 public SpawnGroupData finalizeSpawn(net.minecraft.world.level.ServerLevelAccessor level,net.minecraft.world.DifficultyInstance difficulty,EntitySpawnReason reason,SpawnGroupData data){
			  fixture.SpawnerProbe.finalizes++; fixture.SpawnerProbe.trace.add("finalize");
			  fixture.SpawnerProbe.finalDifficulty=difficulty; fixture.SpawnerProbe.finalData=data; return fixture.SpawnerProbe.finalResult;
			 }
			}
			""");
		out.put("net.minecraft.server.level.ServerLevel", """
			package net.minecraft.server.level;
			public class ServerLevel extends net.minecraft.world.level.Level implements net.minecraft.world.level.ServerLevelAccessor {
			 public boolean tryAddFreshEntityWithPassengers(net.minecraft.world.entity.Entity entity){
			  fixture.SpawnerProbe.insertAttempts++; boolean allowed=!((net.minecraft.world.entity.Mob)entity).isSpawnCancelled();
			  if(allowed)fixture.SpawnerProbe.inserted++; return allowed;
			 }
			}
			""");
		out.put("net.minecraft.world.level.storage.TagValueInput", "package net.minecraft.world.level.storage; public class TagValueInput { public static ValueInput create(net.minecraft.util.ProblemReporter p,net.minecraft.core.HolderLookup.Provider r,net.minecraft.nbt.CompoundTag t){return fixture.SpawnerProbe.input;} }");
		out.put("net.minecraft.world.entity.EntityType", "package net.minecraft.world.entity; public class EntityType { public static Entity loadEntityRecursive(net.minecraft.world.level.storage.ValueInput input,net.minecraft.world.level.Level level,EntitySpawnReason reason,EntityProcessor p){fixture.SpawnerProbe.loadedInput=input;return fixture.SpawnerProbe.mob;} }");
		out.put("net.neoforged.neoforge.common.extensions.IOwnedSpawner", "package net.neoforged.neoforge.common.extensions; public interface IOwnedSpawner { com.mojang.datafixers.util.Either getOwner(); }");
		out.put("net.minecraft.world.level.BaseSpawner", """
			package net.minecraft.world.level;
			import net.minecraft.world.entity.*;
			public class BaseSpawner implements net.neoforged.neoforge.common.extensions.IOwnedSpawner {
			 public com.mojang.datafixers.util.Either getOwner(){return new com.mojang.datafixers.util.Either();}
			 public void serverTick(net.minecraft.server.level.ServerLevel level,net.minecraft.core.BlockPos position){
			  net.minecraft.world.level.storage.ValueInput input=net.minecraft.world.level.storage.TagValueInput.create(null,null,new net.minecraft.nbt.CompoundTag());
			  Mob mob=(Mob)EntityType.loadEntityRecursive(input,level,EntitySpawnReason.SPAWNER,null);
			  net.neoforged.neoforge.event.EventHooks.finalizeMobSpawnSpawner(mob,level,fixture.SpawnerProbe.difficulty,
			   EntitySpawnReason.SPAWNER,fixture.SpawnerProbe.originalData,this,fixture.SpawnerProbe.initialize);
			  level.tryAddFreshEntityWithPassengers(mob);
			 }
			}
			""");
		out.put("net.neoforged.bus.api.Event", "package net.neoforged.bus.api; public class Event {}");
		out.put("net.neoforged.bus.api.IEventBus", "package net.neoforged.bus.api; public interface IEventBus { Event post(Event event); }");
		out.put("net.minecraftforge.eventbus.api.bus.CancellableEventBus", "package net.minecraftforge.eventbus.api.bus; public interface CancellableEventBus { boolean post(net.minecraftforge.eventbus.internal.Event event); }");
		out.put("net.neoforged.neoforge.common.NeoForge", "package net.neoforged.neoforge.common; public class NeoForge { public static final net.neoforged.bus.api.IEventBus EVENT_BUS=fixture.SpawnerProbe.neoBus; }");
		out.put("net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent", event(false));
		out.put("net.minecraftforge.event.entity.living.MobSpawnEvent", event(true));
		// Compile-only declaration for the caller. Replaced with the actual carrier method before loading.
		out.put("net.neoforged.neoforge.event.EventHooks", "package net.neoforged.neoforge.event; public class EventHooks { public static net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent finalizeMobSpawnSpawner(net.minecraft.world.entity.Mob m,net.minecraft.world.level.ServerLevelAccessor l,net.minecraft.world.DifficultyInstance d,net.minecraft.world.entity.EntitySpawnReason r,net.minecraft.world.entity.SpawnGroupData g,net.neoforged.neoforge.common.extensions.IOwnedSpawner s,boolean f){return null;} }");
		out.put("fixture.SpawnerProbe", """
			package fixture;
			import java.util.*; import java.util.function.*;
			import net.minecraft.world.entity.*; import net.minecraft.world.DifficultyInstance;
			import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
			import net.minecraftforge.event.entity.living.MobSpawnEvent;
			public class SpawnerProbe {
			 public static final List<String> trace=new ArrayList<>(); public static int neoPosts,forgePosts,finalizes,insertAttempts,inserted;
			 public static boolean initialize=true,forgeCanceled; public static RuntimeException forgeFailure;
			 public static final Mob mob=new Mob(); public static net.minecraft.world.level.storage.ValueInput input=new Input(),loadedInput;
			 public static DifficultyInstance difficulty=new DifficultyInstance(),finalDifficulty;
			 public static SpawnGroupData originalData=new Data(),finalData,finalResult=new Data();
			 public static Consumer<FinalizeSpawnEvent> neo=e->{}; public static Consumer<MobSpawnEvent.FinalizeSpawn> forge=e->{};
			 public static FinalizeSpawnEvent lastNeo; public static MobSpawnEvent.FinalizeSpawn lastForge;
			 public static final net.neoforged.bus.api.IEventBus neoBus=e->{neoPosts++;trace.add("neo");lastNeo=(FinalizeSpawnEvent)e;neo.accept(lastNeo);return e;};
			 public static final net.minecraftforge.eventbus.api.bus.CancellableEventBus forgeBus=e->{
			  forgePosts++;trace.add("forge");if(forgeFailure!=null)throw forgeFailure;lastForge=(MobSpawnEvent.FinalizeSpawn)e;forge.accept(lastForge);return forgeCanceled;
			 };
			}
			""");
		return out;
	}

	private static String event(boolean forge) {
		String declaration = forge ? "public class MobSpawnEvent { public static class FinalizeSpawn implements net.minecraftforge.eventbus.internal.Event {"
				: "public class FinalizeSpawnEvent extends net.neoforged.bus.api.Event {";
		return "package " + (forge ? "net.minecraftforge" : "net.neoforged.neoforge") + ".event.entity.living; " + declaration
				+ (forge ? " public static final net.minecraftforge.eventbus.api.bus.CancellableEventBus BUS=fixture.SpawnerProbe.forgeBus;" : "")
				+ " private net.minecraft.world.entity.Mob mob; private net.minecraft.world.DifficultyInstance difficulty; private net.minecraft.world.entity.EntitySpawnReason reason;"
				+ " private net.minecraft.world.entity.SpawnGroupData data; private net.minecraft.world.level.storage.ValueInput tag; private boolean canceled;"
				+ " public " + (forge ? "FinalizeSpawn" : "FinalizeSpawnEvent") + "(net.minecraft.world.entity.Mob m,net.minecraft.world.level.ServerLevelAccessor l,double x,double y,double z,net.minecraft.world.DifficultyInstance d,net.minecraft.world.entity.EntitySpawnReason r,net.minecraft.world.entity.SpawnGroupData g,"
				+ (forge ? "net.minecraft.world.level.storage.ValueInput t,net.minecraft.world.level.BaseSpawner s" : "com.mojang.datafixers.util.Either s") + "){mob=m;difficulty=d;reason=r;data=g;" + (forge ? "tag=t;" : "") + "}"
				+ " public net.minecraft.world.DifficultyInstance getDifficulty(){return difficulty;} public void setDifficulty(net.minecraft.world.DifficultyInstance d){difficulty=d;}"
				+ " public net.minecraft.world.entity.SpawnGroupData getSpawnData(){return data;} public void setSpawnData(net.minecraft.world.entity.SpawnGroupData d){data=d;}"
				+ " public net.minecraft.world.entity.EntitySpawnReason getSpawnType(){return reason;} public net.minecraft.world.level.storage.ValueInput getSpawnTag(){return tag;} public void setSpawnTag(net.minecraft.world.level.storage.ValueInput t){tag=t;}"
				+ " public boolean isCanceled(){return canceled;} public void setCanceled(boolean c){canceled=c;} public void setSpawnCancelled(boolean c){mob.setSpawnCancelled(c);} public boolean isSpawnCancelled(){return mob.isSpawnCancelled();}"
				+ " }" + (forge ? "}" : "");
	}
}
