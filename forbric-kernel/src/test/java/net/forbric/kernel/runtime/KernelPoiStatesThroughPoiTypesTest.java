/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.transform.ForbricMergedBaseCompatTransformer;

/**
 * Lane K4, the half {@link KernelPoiStatesTest} stands in for: which call is a Fabric mod's. The kernel helper is
 * called from the head of {@code PoiTypes.registerBlockStates} and looks past every {@code PoiTypes} frame — vanilla's
 * {@code register}, and the {@code @Invoker} methods Mixin merges into {@code PoiTypes} (Balm's
 * {@code callRegisterBlockStates}, pillager_deterrent's {@code invokeRegister}) — to the first class that is not
 * {@code PoiTypes}, and asks the kernel loader's ecosystem record whether that class is a Fabric mod's.
 *
 * <p>The {@code PoiTypes} here is defined by a real {@link ForbricClassLoader} from a jar no mod owns, as the base is in
 * the game. Its {@code registerBlockStates} and lambda are the staged base's own bytecode, retyped so they run off-game
 * ({@code BlockState} to {@code Object}, {@code Util.pauseInIde} to identity, a bare {@code Holder} interface), then
 * transformed by the real compat transformer; the routes in are the three call shapes measured on the genuine stage.
 * The callers are classes of a jar the loader records as a Fabric mod's, and the same code in a jar it records as a
 * NeoForge mod's. The same harness checks {@code liveTable}, which NeoForge's own writers of the map ask for the table
 * {@code PoiTypes} reads, against a {@code TYPE_BY_STATE} a mod replaced (as lithium-fabric does).
 */
class KernelPoiStatesThroughPoiTypesTest {
	private static final String POI_TYPES = "net/minecraft/world/entity/ai/village/poi/PoiTypes";
	private static final String HOLDER = "net/minecraft/core/Holder";
	private static final String BLOCK_STATE = "net/minecraft/world/level/block/state/BlockState";
	private static final String UTIL = "net/minecraft/util/Util";
	private static final String UTIL_STAND_IN = "forbrictest/poi/Util";
	private static final String SETS = "(L" + HOLDER + ";Ljava/util/Set;)V";
	/** The three routes into registerBlockStates, each a PoiTypes method as the game has it after Mixin. */
	private static final List<String> ROUTES = List.of("register", "callRegisterBlockStates$balm_$md$k4$0",
			"invokeRegister$pillager_deterrent_$md$k4$1");

	@TempDir Path work;
	private ForbricClassLoader loader;
	private Class<?> poiTypes, holder, fabricCaller, neoCaller;

	@BeforeEach
	void game() throws Exception {
		byte[] staged = TestFixtures.requireEntry(Fixture.STAGED, TestFixtures.stagedBase(), POI_TYPES + ".class");
		Path runtime = Path.of(System.getProperty("forbric.test.runtimeClasses",
				Path.of(System.getProperty("user.dir"), "build", "classes", "java", "runtime").toString())).normalize();
		TestFixtures.require(Fixture.GAME_SIDE, Files.isDirectory(runtime), "the compiled game side is absent");

		Path base = jar("base.jar", Map.of(POI_TYPES, poiTypes(staged), HOLDER, holderInterface(), UTIL_STAND_IN, utilStandIn()));
		Path fabric = jar("fabric-mod.jar", Map.of("forbrictest/poi/fabric/Caller", caller("forbrictest/poi/fabric/Caller")));
		Path neo = jar("neoforge-mod.jar", Map.of("forbrictest/poi/neo/Caller", caller("forbrictest/poi/neo/Caller")));
		loader = new ForbricClassLoader(new URL[] {runtime.toUri().toURL(), base.toUri().toURL(), fabric.toUri().toURL(),
				neo.toUri().toURL()}, getClass().getClassLoader());
		loader.setModOrigins(List.of(mod(fabric, "k4fabric", Ecosystem.FABRIC), mod(neo, "k4neo", Ecosystem.NEOFORGE)));
		poiTypes = loader.loadClass(POI_TYPES.replace('/', '.'));
		holder = loader.loadClass(HOLDER.replace('/', '.'));
		fabricCaller = loader.loadClass("forbrictest.poi.fabric.Caller");
		neoCaller = loader.loadClass("forbrictest.poi.neo.Caller");
	}

	@AfterEach
	void close() throws Exception {
		if (loader != null) loader.close();
	}

	private static DiscoveredMod mod(Path jar, String id, Ecosystem ecosystem) {
		return new DiscoveredMod(ecosystem, id, "1", id, List.of(), List.of(), null, jar.toString());
	}

	private Path jar(String file, Map<String, byte[]> classes) throws Exception {
		Path path = work.resolve(file);
		try (JarOutputStream zip = new JarOutputStream(Files.newOutputStream(path))) {
			for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
				zip.putNextEntry(new JarEntry(entry.getKey() + ".class"));
				zip.write(entry.getValue());
				zip.closeEntry();
			}
		}
		return path;
	}

	/** The staged registerBlockStates and its lambda, retyped to run off-game, plus the three routes; then the repair. */
	private static byte[] poiTypes(byte[] staged) {
		ClassNode raw = new ClassNode();
		new ClassReader(staged).accept(raw, 0);
		ClassNode poi = new ClassNode();
		poi.version = Opcodes.V21; // the base's code, in a class file any test JDK defines
		poi.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
		poi.name = POI_TYPES;
		poi.superName = "java/lang/Object";
		poi.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "TYPE_BY_STATE", "Ljava/util/Map;", null, null));
		for (MethodNode m : raw.methods) {
			if (m.name.equals("registerBlockStates") || m.name.equals("lambda$registerBlockStates$0")) poi.methods.add(m);
		}
		assertEquals(2, poi.methods.size(), "the staged PoiTypes has registerBlockStates and its lambda");

		MethodNode clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
		clinit.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/util/HashMap"));
		clinit.instructions.add(new InsnNode(Opcodes.DUP));
		clinit.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false));
		clinit.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.PUTSTATIC, POI_TYPES, "TYPE_BY_STATE", "Ljava/util/Map;"));
		clinit.instructions.add(new InsnNode(Opcodes.RETURN));
		poi.methods.add(clinit);
		poi.methods.add(forward(ROUTES.get(0), "registerBlockStates")); // vanilla's register, after Registry.register
		poi.methods.add(forward(ROUTES.get(1), "registerBlockStates")); // Balm's @Invoker, merged into PoiTypes
		poi.methods.add(forward(ROUTES.get(2), ROUTES.get(0))); // pillager_deterrent's @Invoker of register

		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		ClassVisitor retype = new ClassRemapper(writer, new SimpleRemapper(Map.of(BLOCK_STATE, "java/lang/Object", UTIL, UTIL_STAND_IN)));
		poi.accept(retype);
		byte[] transformed = new ForbricMergedBaseCompatTransformer().transform(POI_TYPES.replace('/', '.'), writer.toByteArray(), null);
		ClassNode check = new ClassNode();
		new ClassReader(transformed).accept(check, 0);
		boolean repaired = false;
		for (MethodNode m : check.methods) {
			if (m.name.equals("registerBlockStates") && m.instructions.getFirst() != null) {
				for (var insn : m.instructions) {
					if (insn instanceof MethodInsnNode call && call.owner.equals("net/forbric/kernel/runtime/KernelPoiStates")) repaired = true;
				}
			}
		}
		assertTrue(repaired, "the compat transformer repairs this PoiTypes as it repairs the base's");
		return transformed;
	}

	private static MethodNode forward(String name, String target) {
		MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, SETS, null, null);
		InsnList code = m.instructions;
		code.add(new VarInsnNode(Opcodes.ALOAD, 0));
		code.add(new VarInsnNode(Opcodes.ALOAD, 1));
		code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, POI_TYPES, target, SETS, false));
		code.add(new InsnNode(Opcodes.RETURN));
		return m;
	}

	private static byte[] holderInterface() {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, HOLDER, null, "java/lang/Object", null);
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static byte[] utilStandIn() {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, UTIL_STAND_IN, null, "java/lang/Object", null);
		var m = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "pauseInIde", "(Ljava/lang/Throwable;)Ljava/lang/Throwable;", null, null);
		m.visitCode();
		m.visitVarInsn(Opcodes.ALOAD, 0);
		m.visitInsn(Opcodes.ARETURN);
		m.visitMaxs(0, 0);
		m.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	/** A mod class with one static method per route: {@code call<route>(Object holder, Set states)}. */
	private static byte[] caller(String name) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
		for (String route : ROUTES) {
			var m = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "via" + ROUTES.indexOf(route),
					"(Ljava/lang/Object;Ljava/util/Set;)V", null, null);
			m.visitCode();
			m.visitVarInsn(Opcodes.ALOAD, 0);
			m.visitTypeInsn(Opcodes.CHECKCAST, HOLDER);
			m.visitVarInsn(Opcodes.ALOAD, 1);
			m.visitMethodInsn(Opcodes.INVOKESTATIC, POI_TYPES, route, SETS, false);
			m.visitInsn(Opcodes.RETURN);
			m.visitMaxs(0, 0);
			m.visitEnd();
		}
		writer.visitEnd();
		return writer.toByteArray();
	}

	private Object holder(String name) {
		return Proxy.newProxyInstance(loader, new Class<?>[] {holder}, (proxy, method, args) -> switch (method.getName()) {
			case "toString" -> "Reference{" + name + "}";
			case "hashCode" -> System.identityHashCode(proxy);
			case "equals" -> proxy == args[0];
			default -> throw new UnsupportedOperationException(method.getName());
		});
	}

	@SuppressWarnings("unchecked")
	private Map<Object, Object> typeByState() throws Exception {
		return (Map<Object, Object>) poiTypes.getField("TYPE_BY_STATE").get(null);
	}

	/** The states NeoForge's PoiTypeCallbacks.onAdd maps for a type when it is added, before the mod's own call. */
	private Set<Object> addedByTheCallback(Object type, String... names) throws Exception {
		Set<Object> states = new LinkedHashSet<>();
		for (String name : names) {
			Object state = new String(name);
			states.add(state);
			typeByState().put(state, type);
		}
		return states;
	}

	private static void call(Class<?> caller, int route, Object type, Set<Object> states) throws Throwable {
		try {
			caller.getMethod("via" + route, Object.class, Set.class).invoke(null, type, states);
		} catch (InvocationTargetException e) {
			throw e.getCause();
		}
	}

	private int takenOver() throws Exception {
		Method count = loader.loadClass("net.forbric.kernel.runtime.KernelPoiStates").getDeclaredMethod("takenOver");
		count.setAccessible(true);
		return (int) count.invoke(null);
	}

	@Test
	void aFabricModsCallMapsTheCallbacksStatesByEveryRouteIntoPoiTypes() throws Throwable {
		List<Object> types = new ArrayList<>();
		for (int route = 0; route < ROUTES.size(); route++) {
			Object type = holder("k4fabric:type_" + route);
			types.add(type);
			Set<Object> states = addedByTheCallback(type, "Block{k4fabric:b" + route + "}[half=lower]",
					"Block{k4fabric:b" + route + "}[half=upper]");
			call(fabricCaller, route, type, states); // threw "defined in more than one PoI type" without the repair
			for (Object state : states) assertSame(type, typeByState().get(state), ROUTES.get(route));
		}
		assertEquals(6, takenOver(), "each route's two states were the callback's, and were taken over once");
	}

	@Test
	void aNeoForgeModsCallByTheSameRoutesKeepsNeoForgesThrow() throws Throwable {
		for (int route = 0; route < ROUTES.size(); route++) {
			final int via = route;
			Object type = holder("k4neo:type_" + route);
			Set<Object> states = addedByTheCallback(type, "Block{k4neo:b" + route + "}");
			Object first = states.iterator().next();
			IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> call(neoCaller, via, type, states));
			assertEquals(first + " is defined in more than one PoI type", thrown.getMessage(),
					"native NeoForge: a registerBlockStates call after the callback mapped the type is vanilla's conflict");
			assertSame(type, typeByState().get(first), "the callback's mapping stays");
		}
		assertEquals(0, takenOver());
	}

	@Test
	void aFabricModMappingItsStatesTwiceStillGetsVanillasThrow() throws Throwable {
		Object type = holder("k4fabric:twice");
		Set<Object> states = addedByTheCallback(type, "Block{k4fabric:twice}");
		call(fabricCaller, 1, type, states);
		IllegalStateException twice = assertThrows(IllegalStateException.class, () -> call(fabricCaller, 1, type, states));
		assertEquals(states.iterator().next() + " is defined in more than one PoI type", twice.getMessage());
	}

	@Test
	void theLoadersEcosystemRecordDecidesWhichCodeIsAFabricMods() throws Exception {
		Method fabric = loader.loadClass("net.forbric.kernel.runtime.KernelPoiStates").getDeclaredMethod("loadedAsFabric", Class.class);
		fabric.setAccessible(true);
		assertInstanceOf(ForbricClassLoader.class, fabricCaller.getClassLoader());
		assertTrue((boolean) fabric.invoke(null, fabricCaller), "a class of a jar the loader records as a Fabric mod's");
		assertFalse((boolean) fabric.invoke(null, neoCaller), "a class of a NeoForge mod's jar");
		assertFalse((boolean) fabric.invoke(null, poiTypes), "the base, which no mod owns");
		assertFalse((boolean) fabric.invoke(null, getClass()), "a class another loader defined");
		assertFalse((boolean) fabric.invoke(null, String.class));
	}

	@SuppressWarnings("unchecked")
	private Map<Object, Object> liveTable(Map<Object, Object> neoForges) throws Exception {
		Method live = loader.loadClass("net.forbric.kernel.runtime.KernelPoiStates").getMethod("liveTable", Map.class);
		return (Map<Object, Object>) live.invoke(null, neoForges);
	}

	@Test
	void whileTypeByStateIsNeoForgesMapNeoForgesWritersWriteIt() throws Exception {
		Map<Object, Object> neoForges = typeByState();
		assertSame(neoForges, liveTable(neoForges), "the native NeoForge case: nothing changes");
	}

	@Test
	void whenAModReplacedTypeByStateNeoForgesWritersWriteTheReplacement() throws Throwable {
		Map<Object, Object> neoForges = typeByState();
		Map<Object, Object> lithiums = new LinkedHashMap<>(neoForges); // lithium-fabric's PoiTypesMixin, at the end of <clinit>
		poiTypes.getField("TYPE_BY_STATE").set(null, lithiums);
		assertSame(lithiums, liveTable(neoForges), "NeoForge's callback and PoiTypeExtender write the table PoiTypes reads");
		assertSame(lithiums, liveTable(lithiums));

		Object type = holder("k4fabric:after_lithium");
		Object state = new String("Block{k4fabric:after_lithium}");
		liveTable(neoForges).put(state, type); // what the callback's onAdd now does
		call(fabricCaller, 1, type, new LinkedHashSet<>(List.of(state)));
		assertSame(type, lithiums.get(state), "and a Fabric mod's own call on that table still maps its state");
		assertEquals(1, takenOver());
	}

	@Test
	void aTypeNoCallbackMappedIsVanillasBusinessForEveryCaller() throws Throwable {
		Map<Object, Object> before = new LinkedHashMap<>(typeByState());
		Object type = holder("k4fabric:late");
		Set<Object> states = new LinkedHashSet<>(List.of(new String("Block{k4fabric:late}")));
		call(fabricCaller, 0, type, states);
		assertSame(type, typeByState().get(states.iterator().next()));
		assertEquals(before.size() + 1, typeByState().size());
		assertEquals(0, takenOver(), "nothing was the callback's");
	}
}
