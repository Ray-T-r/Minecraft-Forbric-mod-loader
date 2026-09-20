/*
 * Copyright 2026 The Forbric Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Real carrier events/predicate builders and the real vanilla Data record, with inert game and bus boundaries. */
final class ForgeSpawnFixture extends ClassLoader {
	static final String FORGE = "net.minecraftforge.event.entity.SpawnPlacementRegisterEvent";
	static final String NEO = "net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent";
	static final String ROOT = "net.forbric.kernel.runtime.KernelForgeSpawnPlacements";
	static final String SPAWN = "net.minecraft.world.entity.SpawnPlacements";
	private static final String BUS = "net/minecraftforge/eventbus/api/bus/EventBus";
	private final Map<String, byte[]> definitions = new HashMap<>();
	private final boolean hideForge;
	private final Path runtime;
	final Class<?> entityType, predicate, placementType, heightType, dataType;
	final Map<Object, Object> vanilla;
	int forgePosts;
	Consumer<Object> forgeListener = event -> { };

	ForgeSpawnFixture(boolean hideForge) throws Exception {
		super(ForgeSpawnFixture.class.getClassLoader());
		this.hideForge = hideForge;
		runtime = Path.of(System.getProperty("forbric.test.runtimeClasses", "build/classes/java/runtime"));
		assumeTrue(Files.isRegularFile(runtime.resolve(ROOT.replace('.', '/') + ".class")), "runtime source set not compiled");
		for (String suffix : new String[] {"", "$MergedSpawnPredicate", "$Operation"}) {
			put(FORGE + suffix, staged("forge-runtime/forge-runtime.jar", FORGE + suffix));
			put(NEO + suffix, staged("neoforge-runtime/neoforge-runtime.jar", NEO + suffix));
		}
		put(SPAWN + "$Data", staged("merged-base/patched-mc-merged-26.2.jar", SPAWN + "$Data"));
		put(SPAWN + "$SpawnPredicate", staged("merged-base/patched-mc-merged-26.2.jar", SPAWN + "$SpawnPredicate"));
		for (String name : new String[] {"net.minecraft.world.entity.Entity", "net.minecraft.world.entity.EntityType",
				"net.minecraft.world.level.ServerLevelAccessor", "net.minecraft.world.entity.EntitySpawnReason",
				"net.minecraft.core.BlockPos", "net.minecraft.util.RandomSource", "net.minecraft.world.entity.SpawnPlacementType",
				"net.minecraft.world.level.levelgen.Heightmap$Types", "net.neoforged.bus.api.Event"}) put(name, empty(name));
		put("net.neoforged.fml.event.IModBusEvent", marker("net.neoforged.fml.event.IModBusEvent"));
        ClassWriter other = type("fixture/OtherModBusEvent", "net/neoforged/bus/api/Event",
                new String[] {"net/neoforged/fml/event/IModBusEvent"});
        constructor(other, "net/neoforged/bus/api/Event"); other.visitEnd(); put("fixture.OtherModBusEvent", other.toByteArray());
		put("net.minecraftforge.eventbus.internal.Event", marker("net.minecraftforge.eventbus.internal.Event"));
		ClassWriter mutable = type("net/minecraftforge/eventbus/api/event/MutableEvent", "java/lang/Object",
				new String[] {"net/minecraftforge/eventbus/internal/Event"});
		constructor(mutable, "java/lang/Object"); mutable.visitEnd();
		put("net.minecraftforge.eventbus.api.event.MutableEvent", mutable.toByteArray());
		put("net.minecraftforge.eventbus.api.bus.EventBus", bus());
		ClassWriter factory = type("fixture/SpawnBusFactory", "java/lang/Object", null);
		factory.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "bus", "L" + BUS + ";", null, null).visitEnd();
		factory.visitEnd(); put("fixture.SpawnBusFactory", factory.toByteArray());
		put(SPAWN, spawnTable()); put("net.neoforged.fml.ModLoader", modLoader());

		entityType = loadClass("net.minecraft.world.entity.EntityType");
		predicate = loadClass(SPAWN + "$SpawnPredicate");
		placementType = loadClass("net.minecraft.world.entity.SpawnPlacementType");
		heightType = loadClass("net.minecraft.world.level.levelgen.Heightmap$Types");
		dataType = loadClass(SPAWN + "$Data");
		vanilla = mapField(loadClass(SPAWN), null, "DATA_BY_TYPE");
		if (!hideForge) {
			Class<?> eventBus = loadClass(BUS.replace('/', '.'));
			Object proxy = Proxy.newProxyInstance(this, new Class<?>[] {eventBus}, (self, method, arguments) -> {
				if (method.getName().equals("post")) { forgePosts++; forgeListener.accept(arguments[0]); return false; }
				return objectMethod(self, method, arguments);
			});
			loadClass("fixture.SpawnBusFactory").getField("bus").set(null, proxy);
		}
	}

	Object entity() throws Exception { return entityType.getConstructor().newInstance(); }
	Object placement() throws Exception { return placementType.getConstructor().newInstance(); }
	Object heightmap() throws Exception { return heightType.getConstructor().newInstance(); }
	Object constant(boolean value) {
		return Proxy.newProxyInstance(this, new Class<?>[] {predicate}, (self, method, args) ->
				method.getName().equals("test") ? value : objectMethod(self, method, args));
	}
	void seed(Object key, Object rule, Object placement, Object heightmap) throws Exception {
		Constructor<?> constructor = dataType.getDeclaredConstructor(heightType, placementType, predicate);
		constructor.setAccessible(true); vanilla.put(key, constructor.newInstance(heightmap, placement, rule));
	}
	Object event() throws Exception {
		Map<Object, Object> neo = new LinkedHashMap<>();
		for (var entry : vanilla.entrySet()) {
			neo.put(entry.getKey(), merged(NEO, get(dataType, entry.getValue(), "predicate"),
					get(dataType, entry.getValue(), "placement"), get(dataType, entry.getValue(), "heightMap")));
		}
		return loadClass(NEO).getConstructor(Map.class).newInstance(neo);
	}
	Object merged(String family, Object rule, Object placement, Object heightmap) throws Exception {
		return loadClass(family + "$MergedSpawnPredicate").getConstructor(predicate, placementType, heightType)
				.newInstance(rule, placement, heightmap);
	}
	Map<Object, Object> eventMap(Object event) throws Exception { return mapField(event.getClass(), event, "map"); }
	void register(Object event, Object key, Object rule, String operation, Object placement, Object heightmap) {
		try {
			String family = event.getClass().getName(); Class<?> op = loadClass(family + "$Operation");
			event.getClass().getMethod("register", entityType, placementType, heightType, predicate, op)
					.invoke(event, key, placement, heightmap, rule, op.getField(operation).get(null));
		} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
	boolean evaluate(Object builder) throws Exception {
		Object built = builder.getClass().getMethod("build").invoke(builder);
		return (boolean) predicate.getDeclaredMethods()[0].invoke(built, new Object[5]);
	}
	void onNeo(Consumer<Object> listener) throws Exception { loadClass("net.neoforged.fml.ModLoader").getField("listener").set(null, listener); }
	int neoPosts() throws Exception { return loadClass("net.neoforged.fml.ModLoader").getField("posts").getInt(null); }
	Object lastNeoEvent() throws Exception { return loadClass("net.neoforged.fml.ModLoader").getField("lastEvent").get(null); }
	void run(Object event) throws Exception {
		loadClass(ROOT).getMethod("postBothFamilies", loadClass("net.neoforged.bus.api.Event")).invoke(null, event);
	}
	void writeBack(Object event) throws Exception {
		for (var entry : eventMap(event).entrySet()) {
			Object merged = entry.getValue(); Class<?> owner = merged.getClass();
			seed(entry.getKey(), owner.getMethod("build").invoke(merged), owner.getMethod("getSpawnType").invoke(merged),
					owner.getMethod("getHeightmapType").invoke(merged));
		}
	}
	static Object get(Class<?> owner, Object instance, String name) throws Exception {
		Field field = owner.getDeclaredField(name); field.setAccessible(true); return field.get(instance);
	}
	@SuppressWarnings("unchecked") private static Map<Object, Object> mapField(Class<?> owner, Object instance, String name) throws Exception {
		return (Map<Object, Object>) get(owner, instance, name);
	}

	@Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
		if (!definitions.containsKey(name) && !name.startsWith(ROOT)) return super.loadClass(name, resolve);
		synchronized (getClassLoadingLock(name)) {
			if (hideForge && name.startsWith("net.minecraftforge.")) throw new ClassNotFoundException(name);
			Class<?> type = findLoadedClass(name);
			if (type == null) {
				byte[] bytes = definitions.get(name);
				try { if (bytes == null) bytes = Files.readAllBytes(runtime.resolve(name.replace('.', '/') + ".class")); }
				catch (Exception e) { throw new ClassNotFoundException(name, e); }
				type = defineClass(name, bytes, 0, bytes.length);
			}
			if (resolve) resolveClass(type); return type;
		}
	}
	private void put(String name, byte[] bytes) { definitions.put(name, bytes); }
	static byte[] staged(String jar, String name) throws Exception {
		String old = System.getenv("FORBRIC_OLD");
		Path run = old == null || old.isBlank() ? Path.of("..", "forbric-loader", "run") : Path.of(old, "run");
		Path path = run.resolve(jar); assumeTrue(Files.isRegularFile(path), "staged artifact absent: " + path);
		try (ZipFile zip = new ZipFile(path.toFile())) {
			assertNotNull(zip.getEntry(name.replace('.', '/') + ".class"));
			return zip.getInputStream(zip.getEntry(name.replace('.', '/') + ".class")).readAllBytes();
		}
	}
	private static Object objectMethod(Object self, Method method, Object[] args) {
		return switch (method.getName()) {
			case "toString" -> "spawn-test-proxy"; case "hashCode" -> System.identityHashCode(self);
			case "equals" -> self == args[0]; default -> null;
		};
	}
	private static ClassWriter type(String name, String parent, String[] interfaces) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, parent, interfaces); return writer;
	}
	private static void constructor(ClassWriter writer, String parent) {
		MethodVisitor m = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		m.visitCode(); m.visitVarInsn(Opcodes.ALOAD, 0); m.visitMethodInsn(Opcodes.INVOKESPECIAL, parent, "<init>", "()V", false);
		m.visitInsn(Opcodes.RETURN); m.visitMaxs(0, 0); m.visitEnd();
	}
	private static byte[] empty(String name) {
		ClassWriter writer = type(name.replace('.', '/'), "java/lang/Object", null); constructor(writer, "java/lang/Object");
		writer.visitEnd(); return writer.toByteArray();
	}
	private static byte[] marker(String name) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
				name.replace('.', '/'), null, "java/lang/Object", null); writer.visitEnd(); return writer.toByteArray();
	}
	private static byte[] bus() {
		ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, BUS, null, "java/lang/Object", null);
		w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "post", "(Lnet/minecraftforge/eventbus/internal/Event;)Z", null, null).visitEnd();
		MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "create", "(Ljava/lang/Class;)L" + BUS + ";", null, null);
		m.visitCode(); m.visitFieldInsn(Opcodes.GETSTATIC, "fixture/SpawnBusFactory", "bus", "L" + BUS + ";");
		m.visitInsn(Opcodes.ARETURN); m.visitMaxs(0, 0); m.visitEnd(); w.visitEnd(); return w.toByteArray();
	}
	private static byte[] spawnTable() {
		String owner = SPAWN.replace('.', '/'); ClassWriter w = type(owner, "java/lang/Object", null);
		w.visitNestMember(owner + "$Data"); w.visitNestMember(owner + "$SpawnPredicate");
		w.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "DATA_BY_TYPE", "Ljava/util/Map;", null, null).visitEnd();
		MethodVisitor m = w.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null); m.visitCode();
		m.visitTypeInsn(Opcodes.NEW, "java/util/LinkedHashMap"); m.visitInsn(Opcodes.DUP);
		m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/LinkedHashMap", "<init>", "()V", false);
		m.visitFieldInsn(Opcodes.PUTSTATIC, owner, "DATA_BY_TYPE", "Ljava/util/Map;"); m.visitInsn(Opcodes.RETURN);
		m.visitMaxs(0, 0); m.visitEnd(); w.visitEnd(); return w.toByteArray();
	}
	private static byte[] modLoader() {
		String owner = "net/neoforged/fml/ModLoader", event = "Lnet/neoforged/bus/api/Event;";
		ClassWriter w = type(owner, "java/lang/Object", null);
		w.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "listener", "Ljava/util/function/Consumer;", null, null).visitEnd();
		w.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "posts", "I", null, null).visitEnd();
		w.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "lastEvent", event, null, null).visitEnd();
		MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "postEvent", "(" + event + ")V", null, null);
		m.visitCode(); m.visitFieldInsn(Opcodes.GETSTATIC, owner, "posts", "I"); m.visitInsn(Opcodes.ICONST_1); m.visitInsn(Opcodes.IADD);
		m.visitFieldInsn(Opcodes.PUTSTATIC, owner, "posts", "I"); m.visitVarInsn(Opcodes.ALOAD, 0); m.visitFieldInsn(Opcodes.PUTSTATIC, owner, "lastEvent", event);
		Label end = new Label(); m.visitFieldInsn(Opcodes.GETSTATIC, owner, "listener", "Ljava/util/function/Consumer;"); m.visitJumpInsn(Opcodes.IFNULL, end);
		m.visitFieldInsn(Opcodes.GETSTATIC, owner, "listener", "Ljava/util/function/Consumer;"); m.visitVarInsn(Opcodes.ALOAD, 0);
		m.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Consumer", "accept", "(Ljava/lang/Object;)V", true);
		m.visitLabel(end); m.visitInsn(Opcodes.RETURN); m.visitMaxs(0, 0); m.visitEnd(); w.visitEnd(); return w.toByteArray();
	}
}
