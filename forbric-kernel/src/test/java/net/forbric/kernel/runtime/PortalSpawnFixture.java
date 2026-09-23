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
import java.util.Optional;
import javax.tools.ToolProvider;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

/** Runs the compiled runtime wrapper/legacy bridge against spies, optionally using both actual carrier hooks. */
final class PortalSpawnFixture implements AutoCloseable {
	private final URLClassLoader loader;
	private final Class<?> probe, wrapper;
	final Object level, position;

	PortalSpawnFixture(Path directory, boolean realCarrierHooks) throws Exception {
		this(directory, realCarrierHooks, null);
	}

	PortalSpawnFixture(Path directory, boolean realCarrierHooks, byte[] callerBytes) throws Exception {
		Path classes = directory.resolve("classes"); Files.createDirectories(classes);
		List<String> arguments = new ArrayList<>(List.of("-proc:none", "-d", classes.toString()));
		for (var source : sources().entrySet()) {
			Path path = directory.resolve("src/" + source.getKey().replace('.', '/') + ".java");
			Files.createDirectories(path.getParent()); Files.writeString(path, source.getValue()); arguments.add(path.toString());
		}
		ByteArrayOutputStream errors = new ByteArrayOutputStream();
		assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, errors, errors, arguments.toArray(String[]::new)), errors.toString());
		if (callerBytes != null) {
			ClassNode original = new ClassNode(), shell = new ClassNode();
			new ClassReader(callerBytes).accept(original, 0);
			Path target = classes.resolve("net/minecraft/world/level/block/BaseFireBlock.class");
			new ClassReader(Files.readAllBytes(target)).accept(shell, 0);
			shell.methods.removeIf(m -> m.name.equals("onPlace"));
			shell.methods.add(original.methods.stream().filter(m -> m.name.equals("onPlace")).findFirst().orElseThrow());
			ClassWriter writer = new ClassWriter(0); shell.accept(writer); Files.write(target, writer.toByteArray());
		}
		if (realCarrierHooks) {
			copyCarrierHook(classes, "forge-runtime/forge-runtime.jar", "net.minecraftforge.event.ForgeEventFactory");
			copyCarrierHook(classes, "neoforge-runtime/neoforge-runtime.jar", "net.neoforged.neoforge.event.EventHooks");
		}
		Path runtime = Path.of(System.getProperty("forbric.test.runtimeClasses", "build/classes/java/runtime"));
		assertTrue(Files.isRegularFile(runtime.resolve("net/forbric/kernel/runtime/KernelPortalSpawn.class")),
				"this behavioral probe requires the compiled GAME runtime");
		loader = new URLClassLoader(new URL[] {classes.toUri().toURL(), runtime.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
		probe = type("fixture.PortalProbe"); wrapper = type("net.forbric.kernel.runtime.KernelPortalSpawn");
		level = type("net.minecraft.world.level.Level").getConstructor().newInstance();
		position = type("net.minecraft.core.BlockPos").getConstructor().newInstance();
	}

	void installLegacyBridge() throws Exception {
		type("net.forbric.kernel.runtime.KernelGameResultBridges").getMethod("installPortalSpawn", Object.class)
				.invoke(null, probe.getField("bus").get(null));
	}

	Object shape() throws Exception { return type("net.minecraft.world.level.portal.PortalShape").getConstructor().newInstance(); }
	void set(String name, Object value) throws Exception { probe.getField(name).set(null, value); }
	int count(String name) throws Exception { return probe.getField(name).getInt(null); }
	Object get(String name) throws Exception { return probe.getField(name).get(null); }
	void place(Optional<?> input) throws Exception {
		set("input", input); set("builtShape", null);
		Class<?> fire = type("net.minecraft.world.level.block.BaseFireBlock"), state = type("net.minecraft.world.level.block.state.BlockState");
		var method = fire.getDeclaredMethod("onPlace", state, level.getClass(), position.getClass(), state, boolean.class);
		method.setAccessible(true);
		try { method.invoke(fire.getConstructor().newInstance(), state.getConstructor().newInstance(), level, position, state.getConstructor().newInstance(), false); }
		catch (InvocationTargetException wrapped) {
			if (wrapped.getCause() instanceof RuntimeException failure) throw failure;
			if (wrapped.getCause() instanceof Error failure) throw failure;
			throw wrapped;
		}
	}
	@SuppressWarnings("unchecked") List<String> trace() throws Exception { return (List<String>) probe.getField("trace").get(null); }
	boolean guarded() throws Exception {
		var method = wrapper.getDeclaredMethod("dispatchingNeo"); method.setAccessible(true); return (boolean) method.invoke(null);
	}
	Object call(Optional<?> input) throws Exception { return callHook(wrapper, input); }
	Object directNeo(Optional<?> input) throws Exception { return callHook(type("net.neoforged.neoforge.event.EventHooks"), input); }
	private Object callHook(Class<?> owner, Optional<?> input) throws Exception {
		try {
			return owner.getMethod("onTrySpawnPortal", type("net.minecraft.world.level.LevelAccessor"),
					type("net.minecraft.core.BlockPos"), Optional.class).invoke(null, level, position, input);
		} catch (InvocationTargetException wrapped) {
			if (wrapped.getCause() instanceof RuntimeException failure) throw failure;
			if (wrapped.getCause() instanceof Error failure) throw failure;
			throw wrapped;
		}
	}
	private Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name, true, loader); }
	@Override public void close() throws Exception { loader.close(); }

	private static void copyCarrierHook(Path classes, String jar, String owner) throws Exception {
		ClassNode original = new ClassNode();
		new ClassReader(ForgeSpawnFixture.staged(jar, owner)).accept(original, 0);
		var hook = original.methods.stream().filter(m -> m.name.equals("onTrySpawnPortal")).findFirst().orElseThrow();
		ClassWriter writer = new ClassWriter(0);
		writer.visit(original.version, original.access, original.name, null, "java/lang/Object", null);
		hook.accept(writer); writer.visitEnd();
		Files.write(classes.resolve(owner.replace('.', '/') + ".class"), writer.toByteArray());
	}

	private static Map<String, String> sources() {
		Map<String, String> out = new LinkedHashMap<>();
		for (String name : List.of("net.minecraft.core.BlockPos", "net.minecraft.world.level.block.Block", "net.minecraft.world.item.ItemStack",
				"net.minecraft.world.entity.LivingEntity")) {
			int split = name.lastIndexOf('.');
			out.put(name, "package " + name.substring(0, split) + "; public class " + name.substring(split + 1) + " {}");
		}
		out.put("net.minecraft.world.level.LevelAccessor", "package net.minecraft.world.level; public interface LevelAccessor { net.minecraft.world.level.block.state.BlockState getBlockState(net.minecraft.core.BlockPos p); }");
		out.put("net.minecraft.world.level.LevelReader", "package net.minecraft.world.level; public interface LevelReader {}");
		out.put("net.minecraft.world.level.Level", "package net.minecraft.world.level; public class Level implements LevelAccessor, LevelReader { public net.minecraft.world.level.block.state.BlockState getBlockState(net.minecraft.core.BlockPos p) { return new net.minecraft.world.level.block.state.BlockState(); } public boolean removeBlock(net.minecraft.core.BlockPos p,boolean moving){return true;} }");
		out.put("net.minecraft.core.Direction", "package net.minecraft.core; public class Direction { public enum Axis { X } }");
		out.put("net.minecraft.world.level.block.state.BlockState", """
			package net.minecraft.world.level.block.state;
			public class BlockState { public net.minecraft.world.level.block.Block getBlock(){return new net.minecraft.world.level.block.Block();}
			 public boolean is(Object block){return false;} public boolean canSurvive(net.minecraft.world.level.LevelReader l,net.minecraft.core.BlockPos p){return true;} }
			""");
		out.put("net.minecraft.world.level.portal.PortalShape", """
			package net.minecraft.world.level.portal;
			public class PortalShape { public static java.util.Optional<PortalShape> findEmptyPortalShape(net.minecraft.world.level.LevelAccessor l,net.minecraft.core.BlockPos p,net.minecraft.core.Direction.Axis a){return fixture.PortalProbe.input;}
			 public void createPortalBlocks(net.minecraft.world.level.LevelAccessor l){fixture.PortalProbe.builtShape=this;} }
			""");
		out.put("net.minecraft.world.level.block.BaseFireBlock", """
			package net.minecraft.world.level.block;
			public class BaseFireBlock {
			 public void onPlace(net.minecraft.world.level.block.state.BlockState a,net.minecraft.world.level.Level l,net.minecraft.core.BlockPos p,net.minecraft.world.level.block.state.BlockState b,boolean moving){}
			 private static boolean inPortalDimension(net.minecraft.world.level.Level l){return true;}
			}
			""");
		out.put("net.neoforged.bus.api.Event", "package net.neoforged.bus.api; public class Event {}");
		out.put("net.neoforged.bus.api.EventPriority", "package net.neoforged.bus.api; public enum EventPriority { LOWEST }");
		out.put("net.neoforged.bus.api.IEventBus", "package net.neoforged.bus.api; public interface IEventBus { <T extends Event> void addListener(EventPriority p, boolean canceled, Class<T> type, java.util.function.Consumer<T> c); Event post(Event e); }");
		out.put("net.minecraftforge.eventbus.internal.Event", "package net.minecraftforge.eventbus.internal; public interface Event {}");
		out.put("net.minecraftforge.eventbus.api.bus.CancellableEventBus", "package net.minecraftforge.eventbus.api.bus; public interface CancellableEventBus { boolean post(net.minecraftforge.eventbus.internal.Event e); }");
		out.put("net.neoforged.neoforge.common.NeoForge", "package net.neoforged.neoforge.common; public class NeoForge { public static final net.neoforged.bus.api.IEventBus EVENT_BUS = fixture.PortalProbe.bus; }");
		out.put("net.neoforged.neoforge.event.level.BlockEvent", eventSource(false));
		out.put("net.minecraftforge.event.level.BlockEvent", eventSource(true));
		out.put("net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent", """
			package net.neoforged.neoforge.event.entity.living;
			public class LivingEntityUseItemEvent { public static class Finish extends net.neoforged.bus.api.Event {
			public net.minecraft.world.item.ItemStack getResultStack(){return null;} public void setResultStack(net.minecraft.world.item.ItemStack x){}
			public net.minecraft.world.item.ItemStack getItem(){return null;} public int getDuration(){return 0;}
			public net.minecraft.world.entity.LivingEntity getEntity(){return null;} } }
			""");
		out.put("net.neoforged.neoforge.event.EventHooks", hookSource(false));
		out.put("net.minecraftforge.event.ForgeEventFactory", hookSource(true));
		out.put("net.forbric.kernel.util.ForbricLog", "package net.forbric.kernel.util; public class ForbricLog { public static void info(String s){} public static void info(String s,Object...a){} public static void warn(String s,Throwable t){} public static void warn(String s,Object...a){} }");
		out.put("net.forbric.kernel.util.Reflect", "package net.forbric.kernel.util; public class Reflect { public static Throwable unwrap(Throwable t){return t;} }");
		out.put("fixture.PortalProbe", """
			package fixture;
			import java.util.*; import java.util.function.*;
			import net.minecraft.world.level.portal.PortalShape;
			import net.neoforged.bus.api.*;
			import net.neoforged.neoforge.event.level.BlockEvent.PortalSpawnEvent;
			public class PortalProbe {
			 public static final List<String> trace = new ArrayList<>();
			 public static int neoCalls, forgeCalls; public static boolean neoCanceled, forgeCanceled;
			 public static Optional<PortalShape> input; public static PortalShape builtShape;
			 public static RuntimeException neoFailure, forgeFailure; public static Runnable nested;
			 public static UnaryOperator<Optional<PortalShape>> neoResult = x->x, forgeResult = x->x;
			 public static final Bus bus = new Bus();
			 public static final net.minecraftforge.eventbus.api.bus.CancellableEventBus forgeBus = e -> {
			   forgeCalls++; trace.add("forge"); if(forgeFailure!=null)throw forgeFailure; return forgeCanceled;
			 };
			 public static class Bus implements IEventBus {
			   private Consumer<PortalSpawnEvent> listener;
			   public <T extends Event> void addListener(EventPriority p, boolean canceled, Class<T> t, Consumer<T> c) {
			     if(t==PortalSpawnEvent.class) listener=e->c.accept(t.cast(e));
			   }
			   public Event post(Event raw) {
			     PortalSpawnEvent e=(PortalSpawnEvent)raw; neoCalls++; trace.add("neo");
			     if(neoFailure!=null)throw neoFailure;
			     Runnable callback=nested; nested=null; if(callback!=null)callback.run();
			     e.setCanceled(neoCanceled); if(listener!=null&&!e.isCanceled())listener.accept(e); return e;
			   }
			 }
			}
			""");
		return out;
	}

	private static String eventSource(boolean forge) {
		return "package " + (forge ? "net.minecraftforge" : "net.neoforged.neoforge") + ".event.level; "
				+ "public class BlockEvent { public static class PortalSpawnEvent "
				+ (forge ? "implements net.minecraftforge.eventbus.internal.Event" : "extends net.neoforged.bus.api.Event") + " {"
				+ (forge ? " public static final net.minecraftforge.eventbus.api.bus.CancellableEventBus BUS = fixture.PortalProbe.forgeBus;" : "")
				+ " private final net.minecraft.world.level.LevelAccessor level; private final net.minecraft.core.BlockPos pos;"
				+ " private final net.minecraft.world.level.portal.PortalShape shape; private boolean canceled;"
				+ " public PortalSpawnEvent(net.minecraft.world.level.LevelAccessor l,net.minecraft.core.BlockPos p,net.minecraft.world.level.block.state.BlockState s,net.minecraft.world.level.portal.PortalShape v){level=l;pos=p;shape=v;}"
				+ " public net.minecraft.world.level.LevelAccessor getLevel(){return level;} public net.minecraft.core.BlockPos getPos(){return pos;}"
				+ " public net.minecraft.world.level.portal.PortalShape getPortalSize(){return shape;} public boolean isCanceled(){return canceled;} public void setCanceled(boolean x){canceled=x;} } }";
	}

	private static String hookSource(boolean forge) {
		String prefix = forge ? "net.minecraftforge" : "net.neoforged.neoforge";
		return "package " + prefix + ".event; import java.util.*; import net.minecraft.world.level.portal.PortalShape; "
				+ "public class " + (forge ? "ForgeEventFactory" : "EventHooks") + " {"
				+ " public static Optional<PortalShape> onTrySpawnPortal(net.minecraft.world.level.LevelAccessor l, net.minecraft.core.BlockPos p, Optional<PortalShape> shape){"
				+ " if(shape.isEmpty())return shape; var event=new " + prefix + ".event.level.BlockEvent.PortalSpawnEvent(l,p,l.getBlockState(p),shape.get());"
				+ (forge ? " if(event.BUS.post(event))return Optional.empty(); return fixture.PortalProbe.forgeResult.apply(shape);"
						: "net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(event); if(event.isCanceled())return Optional.empty(); return fixture.PortalProbe.neoResult.apply(shape);")
				+ " } public static net.minecraft.world.item.ItemStack onItemUseFinish(net.minecraft.world.entity.LivingEntity e,net.minecraft.world.item.ItemStack a,int n,net.minecraft.world.item.ItemStack b){return b;} }";
	}
}
