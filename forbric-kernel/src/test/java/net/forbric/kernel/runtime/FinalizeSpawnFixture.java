/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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

/**
 * The compiled KernelFinalizeSpawn against NeoForge's actual spawner hook bytecode, with the game and both buses
 * replaced by counting stand-ins at the boundary: every event post, every finalization and the veto flag are observed.
 */
final class FinalizeSpawnFixture implements AutoCloseable {
	private final URLClassLoader loader;
	private final Class<?> probe, helper;
	private final Object mob;

	FinalizeSpawnFixture(Path directory) throws Exception {
		Path classes = directory.resolve("classes"); Files.createDirectories(classes);
		List<String> args = new ArrayList<>(List.of("-proc:none", "-d", classes.toString()));
		for (var source : sources().entrySet()) {
			Path path = directory.resolve("src/" + source.getKey().replace('.', '/') + ".java");
			Files.createDirectories(path.getParent()); Files.writeString(path, source.getValue()); args.add(path.toString());
		}
		ByteArrayOutputStream errors = new ByteArrayOutputStream();
		assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, errors, errors, args.toArray(String[]::new)), errors.toString());
		// NeoForge's own spawner hook, the one finalizeTrialSpawner calls with initialize=false.
		ClassNode original = new ClassNode();
		new ClassReader(ForgeSpawnFixture.staged("neoforge-runtime/neoforge-runtime.jar", "net.neoforged.neoforge.event.EventHooks")).accept(original, 0);
		var hook = original.methods.stream().filter(m -> m.name.equals("finalizeMobSpawnSpawner")).findFirst().orElseThrow();
		ClassWriter writer = new ClassWriter(0); writer.visit(original.version, original.access, original.name, null, "java/lang/Object", null);
		hook.accept(writer); writer.visitEnd();
		Files.write(classes.resolve("net/neoforged/neoforge/event/EventHooks.class"), writer.toByteArray());
		Path runtime = Path.of(System.getProperty("forbric.test.runtimeClasses", "build/classes/java/runtime"));
		assertTrue(Files.isRegularFile(runtime.resolve("net/forbric/kernel/runtime/KernelFinalizeSpawn.class")));
		loader = new URLClassLoader(new URL[] { classes.toUri().toURL(), runtime.toUri().toURL(),
				Path.of("build/classes/java/main").toUri().toURL() }, ClassLoader.getPlatformClassLoader());
		probe = type("fixture.FinalizeProbe");
		helper = type("net.forbric.kernel.runtime.KernelFinalizeSpawn");
		mob = probe.getField("mob").get(null);
	}

	/** {@code KernelFinalizeSpawn.finalizeMobSpawn(mob, level, difficulty, COMMAND, data)}. */
	Object finalizeMobSpawn() throws Exception {
		return call(method("finalizeMobSpawn"), mob, value("level"), value("difficulty"), reason(), value("originalData"));
	}

	/** {@code KernelFinalizeSpawn.finalizeTrialSpawner(..., spawner, initialize)}. */
	Object finalizeTrialSpawner(boolean initialize) throws Exception {
		return call(method("finalizeTrialSpawner"), mob, value("level"), value("difficulty"), reason(), value("originalData"),
				value("spawner"), initialize);
	}

	private Object reason() throws Exception {
		return type("net.minecraft.world.entity.EntitySpawnReason").getField("TRIAL_SPAWNER").get(null);
	}

	private Method method(String name) {
		for (Method method : helper.getMethods()) if (method.getName().equals(name)) return method;
		throw new AssertionError(name);
	}

	private Object call(Method method, Object... args) throws Exception {
		try { return method.invoke(null, args); }
		catch (InvocationTargetException wrapped) {
			if (wrapped.getCause() instanceof RuntimeException failure) throw failure;
			if (wrapped.getCause() instanceof Error failure) throw failure;
			throw wrapped;
		}
	}

	Object value(String name) throws Exception { return probe.getField(name).get(null); }
	void set(String name, Object value) throws Exception { probe.getField(name).set(null, value); }
	int count(String name) throws Exception { return probe.getField(name).getInt(null); }
	boolean vetoed() throws Exception { return (boolean) mob.getClass().getMethod("isSpawnCancelled").invoke(mob); }
	Object newData() throws Exception { return type("fixture.Data").getConstructor().newInstance(); }
	Object newDifficulty() throws Exception { return type("net.minecraft.world.DifficultyInstance").getConstructor().newInstance(); }
	@SuppressWarnings("unchecked") List<String> trace() throws Exception { return (List<String>) value("trace"); }
	boolean canceled(Object event) throws Exception { return (boolean) event.getClass().getMethod("isCanceled").invoke(event); }
	Object eventValue(Object event, String getter) throws Exception { return event.getClass().getMethod(getter).invoke(event); }
	private Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name, true, loader); }
	@Override public void close() throws Exception { loader.close(); }

	private static Map<String, String> sources() {
		Map<String, String> out = new LinkedHashMap<>();
		for (String name : List.of("net.minecraft.world.DifficultyInstance", "com.mojang.datafixers.util.Either",
				"net.minecraft.world.level.BaseSpawner", "net.minecraft.world.level.storage.ValueInput")) {
			int dot = name.lastIndexOf('.'); out.put(name, "package " + name.substring(0, dot) + "; public class " + name.substring(dot + 1) + " {}");
		}
		for (String name : List.of("net.minecraft.world.entity.SpawnGroupData", "net.minecraft.world.level.ServerLevelAccessor",
				"net.minecraftforge.eventbus.internal.Event")) {
			int dot = name.lastIndexOf('.'); out.put(name, "package " + name.substring(0, dot) + "; public interface " + name.substring(dot + 1) + " {}");
		}
		out.put("net.minecraft.world.entity.EntitySpawnReason", "package net.minecraft.world.entity; public enum EntitySpawnReason { COMMAND, TRIAL_SPAWNER }");
		out.put("fixture.Data", "package fixture; public class Data implements net.minecraft.world.entity.SpawnGroupData {}");
		out.put("fixture.Level", "package fixture; public class Level implements net.minecraft.world.level.ServerLevelAccessor {}");
		out.put("net.minecraft.world.entity.Entity", "package net.minecraft.world.entity; public class Entity { public double getX(){return 1;} public double getY(){return 2;} public double getZ(){return 3;} }");
		out.put("net.minecraft.world.entity.Mob", """
			package net.minecraft.world.entity;
			public class Mob extends Entity {
			 private boolean cancelled;
			 public void setSpawnCancelled(boolean value){cancelled=value;} public boolean isSpawnCancelled(){return cancelled;}
			 public SpawnGroupData finalizeSpawn(net.minecraft.world.level.ServerLevelAccessor level,net.minecraft.world.DifficultyInstance difficulty,EntitySpawnReason reason,SpawnGroupData data){
			  fixture.FinalizeProbe.finalizes++; fixture.FinalizeProbe.trace.add("finalize");
			  fixture.FinalizeProbe.finalDifficulty=difficulty; fixture.FinalizeProbe.finalData=data; fixture.FinalizeProbe.finalReason=reason;
			  return fixture.FinalizeProbe.finalResult;
			 }
			}
			""");
		out.put("net.neoforged.neoforge.common.extensions.IOwnedSpawner", "package net.neoforged.neoforge.common.extensions; public interface IOwnedSpawner { com.mojang.datafixers.util.Either getOwner(); }");
		out.put("fixture.Spawner", "package fixture; public class Spawner implements net.neoforged.neoforge.common.extensions.IOwnedSpawner { public com.mojang.datafixers.util.Either getOwner(){return new com.mojang.datafixers.util.Either();} }");
		out.put("net.neoforged.bus.api.Event", "package net.neoforged.bus.api; public class Event {}");
		out.put("net.neoforged.bus.api.IEventBus", "package net.neoforged.bus.api; public interface IEventBus { Event post(Event event); }");
		out.put("net.minecraftforge.eventbus.api.bus.CancellableEventBus", "package net.minecraftforge.eventbus.api.bus; public interface CancellableEventBus { boolean post(net.minecraftforge.eventbus.internal.Event event); }");
		out.put("net.neoforged.neoforge.common.NeoForge", "package net.neoforged.neoforge.common; public class NeoForge { public static final net.neoforged.bus.api.IEventBus EVENT_BUS=fixture.FinalizeProbe.neoBus; }");
		String common = " private net.minecraft.world.entity.Mob mob; private net.minecraft.world.DifficultyInstance difficulty; private net.minecraft.world.entity.EntitySpawnReason reason;"
				+ " private net.minecraft.world.entity.SpawnGroupData data; private boolean canceled; public Object spawner;"
				+ " public net.minecraft.world.DifficultyInstance getDifficulty(){return difficulty;} public void setDifficulty(net.minecraft.world.DifficultyInstance d){difficulty=d;}"
				+ " public net.minecraft.world.entity.SpawnGroupData getSpawnData(){return data;} public void setSpawnData(net.minecraft.world.entity.SpawnGroupData d){data=d;}"
				+ " public boolean isCanceled(){return canceled;} public void setCanceled(boolean c){canceled=c;} public net.minecraft.world.entity.Mob getEntity(){return mob;}"
				+ " public void setSpawnCancelled(boolean c){mob.setSpawnCancelled(c);} public boolean isSpawnCancelled(){return mob.isSpawnCancelled();}";
		out.put("net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent", "package net.neoforged.neoforge.event.entity.living; public class FinalizeSpawnEvent extends net.neoforged.bus.api.Event {"
				+ common + " public net.minecraft.world.entity.EntitySpawnReason getSpawnType(){return reason;}"
				+ " public FinalizeSpawnEvent(net.minecraft.world.entity.Mob m,net.minecraft.world.level.ServerLevelAccessor l,double x,double y,double z,net.minecraft.world.DifficultyInstance d,"
				+ "net.minecraft.world.entity.EntitySpawnReason r,net.minecraft.world.entity.SpawnGroupData g,com.mojang.datafixers.util.Either s){mob=m;difficulty=d;reason=r;data=g;spawner=s;} }");
		out.put("net.minecraftforge.event.entity.living.MobSpawnEvent", "package net.minecraftforge.event.entity.living; public class MobSpawnEvent { public static class FinalizeSpawn implements net.minecraftforge.eventbus.internal.Event {"
				+ " public static final net.minecraftforge.eventbus.api.bus.CancellableEventBus BUS=fixture.FinalizeProbe.forgeBus;"
				+ common + " public Object tag; public net.minecraft.world.entity.EntitySpawnReason getSpawnReason(){return reason;}"
				+ " public FinalizeSpawn(net.minecraft.world.entity.Mob m,net.minecraft.world.level.ServerLevelAccessor l,double x,double y,double z,net.minecraft.world.DifficultyInstance d,"
				+ "net.minecraft.world.entity.EntitySpawnReason r,net.minecraft.world.entity.SpawnGroupData g,net.minecraft.world.level.storage.ValueInput t,net.minecraft.world.level.BaseSpawner s){mob=m;difficulty=d;reason=r;data=g;tag=t;spawner=s;} } }");
		// Compile-only; replaced by NeoForge's actual hook before loading.
		out.put("net.neoforged.neoforge.event.EventHooks", "package net.neoforged.neoforge.event; public class EventHooks { public static net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent finalizeMobSpawnSpawner(net.minecraft.world.entity.Mob m,net.minecraft.world.level.ServerLevelAccessor l,net.minecraft.world.DifficultyInstance d,net.minecraft.world.entity.EntitySpawnReason r,net.minecraft.world.entity.SpawnGroupData g,net.neoforged.neoforge.common.extensions.IOwnedSpawner s,boolean f){return null;} }");
		out.put("fixture.FinalizeProbe", """
			package fixture;
			import java.util.*; import java.util.function.*;
			import net.minecraft.world.entity.*; import net.minecraft.world.DifficultyInstance;
			import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
			import net.minecraftforge.event.entity.living.MobSpawnEvent;
			public class FinalizeProbe {
			 public static final List<String> trace=new ArrayList<>(); public static int neoPosts,forgePosts,finalizes;
			 public static boolean forgeCanceled;
			 public static final Mob mob=new Mob(); public static final Level level=new Level(); public static final Spawner spawner=new Spawner();
			 public static DifficultyInstance difficulty=new DifficultyInstance(),finalDifficulty;
			 public static SpawnGroupData originalData=new Data(),finalData,finalResult=new Data(); public static EntitySpawnReason finalReason;
			 public static Consumer<FinalizeSpawnEvent> neo=e->{}; public static Consumer<MobSpawnEvent.FinalizeSpawn> forge=e->{};
			 public static FinalizeSpawnEvent lastNeo; public static MobSpawnEvent.FinalizeSpawn lastForge;
			 public static final net.neoforged.bus.api.IEventBus neoBus=e->{neoPosts++;trace.add("neo");lastNeo=(FinalizeSpawnEvent)e;neo.accept(lastNeo);return e;};
			 public static final net.minecraftforge.eventbus.api.bus.CancellableEventBus forgeBus=e->{
			  forgePosts++;trace.add("forge");lastForge=(MobSpawnEvent.FinalizeSpawn)e;forge.accept(lastForge);return forgeCanceled;
			 };
			}
			""");
		return out;
	}
}
