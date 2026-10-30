/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.zip.ZipFile;

import net.forbric.api.GameEventBridge;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.runtime.StagedGameClassLoader;
import net.forbric.kernel.transform.NativeDualEventInjector;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * MinecraftForge's {@code PlayLevelSoundEvent}, forwarded from NeoForge's on real buses with the staged events and the
 * staged hooks: once per sound whichever way the merged game asks, with the answer carried back.
 *
 * <p>The callers stand in for the merged game's five sound sites in both shapes a base can have: asking only NeoForge
 * (what the merge keeps today), and asking MinecraftForge natively on the same path as well (what a base that kept both
 * families' rewrites would do). The second shape runs through {@code NativeDualEventInjector} with the kernel's own
 * forwarded set, and the bridge through {@code GameEventMultiplexer}'s guard, as at boot. The hooks are the staged
 * carriers' own two methods, lifted into classes of their own so nothing else in either hook class has to link off-game.
 *
 * <p>The one stand-in is {@code Entity}: NeoForge's event asks it for its level, and a real one cannot be built
 * outside a running game. Its level is null, which neither event reads.
 */
class PlayLevelSoundBridgeTest {
	private static final String NEO_EVENT = "net/neoforged/neoforge/event/PlayLevelSoundEvent";
	private static final String FORGE_EVENT = "net/minecraftforge/event/PlayLevelSoundEvent";
	private static final String NEO_HOOKS = "net/neoforged/neoforge/event/EventHooks";
	private static final String FORGE_HOOKS = "net/minecraftforge/event/ForgeEventFactory";
	private static final String ENTITY = "net/minecraft/world/entity/Entity";
	private static final String LEVEL = "net/minecraft/world/level/Level";
	private static final String HOLDER = "net/minecraft/core/Holder";
	private static final String SOURCE = "net/minecraft/sounds/SoundSource";
	private static final String CALLERS = "fixture/SoundCallers";

	private static final String NEO_AT_ENTITY = "(L" + ENTITY + ";L" + HOLDER + ";L" + SOURCE + ";FF)L" + NEO_EVENT + "$AtEntity;";
	private static final String NEO_AT_POSITION = "(L" + LEVEL + ";DDDL" + HOLDER + ";L" + SOURCE + ";FF)L" + NEO_EVENT + "$AtPosition;";
	private static final String FORGE_AT_ENTITY = "(L" + LEVEL + ";L" + ENTITY + ";L" + HOLDER + ";L" + SOURCE + ";FF)L" + FORGE_EVENT + "$AtEntity;";
	private static final String FORGE_AT_POSITION = "(L" + LEVEL + ";DDDL" + HOLDER + ";L" + SOURCE + ";FF)L" + FORGE_EVENT + "$AtPosition;";

	// ---------------------------------------------------------------- the base asks only NeoForge

	@Test
	void aSoundOnlyNeoForgeIsAskedAboutReachesMinecraftForgeOnceAndItsAnswerIsWhatPlays() throws Exception {
		try (World world = World.of(false)) {
			Object quiet = world.holder("quiet"), loud = world.holder("loud");
			world.neoListener("AtEntity", event -> world.call(event, "setNewVolume", float.class, 0.5f));
			List<Object[]> seen = new ArrayList<>();
			world.forgeListener("AtEntity", event -> {
				seen.add(world.view(event));
				world.call(event, "setSound", world.type(HOLDER), loud);
				world.call(event, "setSource", world.type(SOURCE), world.source("AMBIENT"));
				world.call(event, "setNewVolume", float.class, 0.25f);
				world.call(event, "setNewPitch", float.class, 2.0f);
			});
			Object entity = world.entity();
			Object played = world.atEntity("neoAtEntity", entity, quiet, world.source("NEUTRAL"), 1.0f, 1.5f);

			assertEquals(1, seen.size(), "one sound, one MinecraftForge event");
			Object[] view = seen.get(0);
			assertSame(quiet, view[0], "the sound NeoForge's listeners left");
			assertSame(world.source("NEUTRAL"), view[1]);
			assertEquals(1.0f, view[2], "the volume the game started from is the original");
			assertEquals(0.5f, view[3], "and the one NeoForge's listener set is the current one");
			assertEquals(1.5f, view[4]);
			assertSame(entity, view[5], "the entity the sound is played at");
			assertArrayEquals(new Object[] {loud, world.source("AMBIENT"), 0.25f, 2.0f}, world.plays(played),
					"what the game plays is what the MinecraftForge mod left");
		}
	}

	@Test
	void aSoundAtAPositionCrossesWithItsPositionAndItsAnswer() throws Exception {
		try (World world = World.of(false)) {
			Object block = world.holder("block");
			List<Object[]> seen = new ArrayList<>();
			world.forgeListener("AtPosition", event -> {
				seen.add(world.view(event));
				world.call(event, "setNewPitch", float.class, 0.75f);
			});
			Object played = world.atPosition("neoAtPosition", 1.5, 64.0, -3.25, block, world.source("BLOCKS"), 0.8f, 1.0f);

			assertEquals(1, seen.size());
			Object position = seen.get(0)[5];
			assertEquals(List.of(1.5, 64.0, -3.25), List.of(world.call(position, "x"), world.call(position, "y"), world.call(position, "z")),
					"the position the sound is played at");
			assertArrayEquals(new Object[] {block, world.source("BLOCKS"), 0.8f, 0.75f}, world.plays(played),
					"a mod that changes only the pitch changes only the pitch");
		}
	}

	@Test
	void aMinecraftForgeModThatCancelsSilencesTheSoundAndOneThatOnlyLooksChangesNothing() throws Exception {
		try (World world = World.of(false)) {
			List<Object> seen = new ArrayList<>();
			// MinecraftForge's own idiom for a refusal: a cancelling listener, on the parent event's bus, which every
			// AtEntity and AtPosition reaches because the event is inheritable.
			world.forgeCanceller(event -> {
				seen.add(event);
				return world.entityOf(event) != null;
			});
			Object sound = world.holder("step");
			assertNull(world.plays(world.atEntity("neoAtEntity", world.entity(), sound, world.source("PLAYERS"), 1.0f, 1.0f)),
					"cancelled by a MinecraftForge mod: nothing plays");
			assertArrayEquals(new Object[] {sound, world.source("BLOCKS"), 1.0f, 1.0f},
					world.plays(world.atPosition("neoAtPosition", 0, 0, 0, sound, world.source("BLOCKS"), 1.0f, 1.0f)),
					"a listener that looks and does not cancel leaves the sound as it was");
			assertEquals(2, seen.size(), "the parent event's listener was asked about both");
		}
	}

	@Test
	void aSoundNeoForgeAlreadySilencedIsNotOffered() throws Exception {
		try (World world = World.of(false)) {
			List<Object> seen = new ArrayList<>();
			world.forgeListener("AtEntity", seen::add);
			world.neoListener("AtEntity", event -> {
				if ("cancel".equals(world.nameOf(world.call(event, "getSound")))) world.call(event, "setCanceled", boolean.class, true);
				else world.call(event, "setSound", world.type(HOLDER), null);
			});
			assertNull(world.plays(world.atEntity("neoAtEntity", world.entity(), world.holder("cancel"), world.source("HOSTILE"), 1f, 1f)));
			assertNull(world.plays(world.atEntity("neoAtEntity", world.entity(), world.holder("drop"), world.source("HOSTILE"), 1f, 1f)));
			assertEquals(List.of(), seen, "the game plays neither, so a MinecraftForge mod is asked about neither");
		}
	}

	// ---------------------------------------------------------------- the base asks MinecraftForge natively as well

	@Test
	void whereTheBaseAsksMinecraftForgeOnTheSamePathTheForwardStandsDown() throws Exception {
		for (String shape : List.of("AtEntity", "AtPosition")) {
			List<Float> before = deliveries(false, shape), after = deliveries(true, shape);
			// The native event carries the volume the game started from; the forward carries the one NeoForge's listener
			// set, which is how the two deliveries are told apart.
			assertEquals(List.of(1.0f, 0.5f), before, shape + ": without the stand-down a mod hears the sound twice");
			assertEquals(List.of(1.0f), after, shape + ": with it, once — natively");
		}
	}

	/** The volumes the MinecraftForge mod saw for one sound the base asks both families about. */
	private static List<Float> deliveries(boolean repaired, String shape) throws Exception {
		try (World world = World.of(repaired)) {
			world.neoListener(shape, event -> world.call(event, "setNewVolume", float.class, 0.5f));
			List<Float> volumes = new ArrayList<>();
			world.forgeListener(shape, event -> volumes.add((Float) world.call(event, "getNewVolume")));
			Object sound = world.holder("both");
			if (shape.equals("AtEntity")) world.atEntity("bothAtEntity", world.entity(), sound, world.source("MASTER"), 1.0f, 1.0f);
			else world.atPosition("bothAtPosition", 2, 3, 4, sound, world.source("MASTER"), 1.0f, 1.0f);
			return volumes;
		}
	}

	/**
	 * Negative control: a native delivery of the OTHER sound event on the same path. MinecraftForge was asked about an
	 * entity's sound, not the position's sound NeoForge posts next, so the position's forward must still run.
	 */
	@Test
	void aNativeEntitySoundDoesNotStandDownAPositionSoundsForward() throws Exception {
		try (World world = World.of(true)) {
			List<Object> entitySounds = new ArrayList<>(), positionSounds = new ArrayList<>();
			world.forgeListener("AtEntity", entitySounds::add);
			world.forgeListener("AtPosition", positionSounds::add);
			Object sound = world.holder("mixed");
			Object played = world.type(CALLERS).getMethod("forgeEntityThenNeoPosition", world.type(ENTITY), world.type(LEVEL),
					double.class, double.class, double.class, world.type(HOLDER), world.type(SOURCE), float.class, float.class)
					.invoke(null, world.entity(), null, 5.0, 6.0, 7.0, sound, world.source("WEATHER"), 1.0f, 1.0f);
			assertEquals(1, entitySounds.size(), "the native entity sound, once");
			assertEquals(1, positionSounds.size(), "the position sound, forwarded: its own event had no native delivery");
			assertArrayEquals(new Object[] {sound, world.source("WEATHER"), 1.0f, 1.0f}, world.plays(played));
		}
	}

	/** The rewritten caller: the kernel's injector found the native delivery and scoped the NeoForge call. */
	@Test
	void theInjectorScopesExactlyTheCallersThatAskBothFamilies() throws Exception {
		Map<String, byte[]> classes = World.classes();
		NativeDualEventInjector injector = World.injector(classes);
		byte[] callers = injector.transform(CALLERS.replace('/', '.'), classes.get(CALLERS), null);
		ClassNode node = new ClassNode();
		new ClassReader(callers).accept(node, 0);
		Map<String, Boolean> scoped = new HashMap<>();
		for (MethodNode method : node.methods) {
			boolean bracketed = false;
			for (NativeDualEventInjector.Bracket bracket : NativeDualEventInjector.brackets(method)) bracketed |= bracket.scope();
			scoped.put(method.name, bracketed);
		}
		assertEquals(Boolean.TRUE, scoped.get("bothAtEntity"));
		assertEquals(Boolean.TRUE, scoped.get("bothAtPosition"));
		assertEquals(Boolean.FALSE, scoped.get("neoAtEntity"), "no native delivery on its path: nothing to stand down for");
		assertEquals(Boolean.FALSE, scoped.get("neoAtPosition"));
		assertEquals(Boolean.TRUE, scoped.get("forgeEntityThenNeoPosition"), "scoped with the entity sound, which is not the "
				+ "event the position's bridge forwards — the negative control above is what that scope must not stand down");
	}

	// ---------------------------------------------------------------- the world

	/** The staged game over its libraries, with the two hooks, the callers and the Entity stand-in defined here. */
	private static final class World extends URLClassLoader {
		private final Map<String, byte[]> defined;

		private World(URL[] urls, Map<String, byte[]> defined) {
			super(urls, ClassLoader.getPlatformClassLoader());
			this.defined = defined;
		}

		static World of(boolean repaired) throws Exception {
			Map<String, byte[]> classes = classes();
			if (repaired) {
				NativeDualEventInjector injector = injector(classes);
				for (String owner : List.of(NEO_HOOKS, FORGE_HOOKS, CALLERS)) {
					byte[] before = classes.get(owner), after = injector.transform(owner.replace('/', '.'), before, null);
					assertNotSame(before, after, owner + " is a class the stand-down must rewrite");
					classes.put(owner, after);
				}
			}
			List<URL> urls = new ArrayList<>(StagedGameClassLoader.urls());
			// The boot side the game-side bridge logs through.
			urls.add(Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main").toUri().toURL());
			World world = new World(urls.toArray(new URL[0]), classes);
			GameEventBridge[] bridges = {GameEventBridge.PLAY_SOUND_AT_ENTITY, GameEventBridge.PLAY_SOUND_AT_POSITION};
			Object bus = world.type("net/neoforged/neoforge/common/NeoForge").getField("EVENT_BUS").get(null);
			Class<?> sounds = world.type("net/forbric/kernel/runtime/KernelGameSoundEvents");
			for (GameEventBridge bridge : bridges) {
				Object view = GameEventMultiplexer.guarded(world, bus, bridge);
				assertNotSame(bus, view, bridge + " is a BRIDGED row, so it installs on the guarded view");
				String entry = bridge == GameEventBridge.PLAY_SOUND_AT_ENTITY ? "installAtEntity" : "installAtPosition";
				sounds.getMethod(entry, Object.class).invoke(null, view);
			}
			world.type("net/neoforged/bus/api/IEventBus").getMethod("start").invoke(bus);
			return world;
		}

		static NativeDualEventInjector injector(Map<String, byte[]> classes) {
			Map<String, byte[]> hooks = Map.of(NEO_HOOKS, classes.get(NEO_HOOKS), FORGE_HOOKS, classes.get(FORGE_HOOKS));
			return new NativeDualEventInjector(path -> hooks.get(path.substring(0, path.length() - ".class".length())),
					DeadEventAudit.forwardedForgeEvents());
		}

		static Map<String, byte[]> classes() throws Exception {
			Path run = TestFixtures.stagedRoot();
			Path neo = run.resolve(TestFixtures.NEO_CARRIER_JAR);
			Path forge = run.resolve(TestFixtures.FORGE_INTEROP_JAR);
			if (!Files.isRegularFile(forge)) forge = run.resolve(TestFixtures.FORGE_CARRIER_JAR);
			TestFixtures.requireFiles(Fixture.STAGED, "the staged carriers are absent", neo, forge);
			Map<String, byte[]> out = new HashMap<>();
			out.put(NEO_HOOKS, lifted(neo, NEO_HOOKS, "onPlaySoundAtEntity", "onPlaySoundAtPosition"));
			out.put(FORGE_HOOKS, lifted(forge, FORGE_HOOKS, "onPlaySoundAtEntity", "onPlaySoundAtPosition"));
			out.put(ENTITY, entityStandIn());
			out.put(CALLERS, callers());
			return out;
		}

		/** The carrier's {@code owner} with only the named static methods, as they are in the carrier. */
		private static byte[] lifted(Path jar, String owner, String... names) throws Exception {
			ClassNode original = new ClassNode();
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				var entry = zip.getEntry(owner + ".class");
				assertNotNull(entry, owner + " in " + jar);
				new ClassReader(zip.getInputStream(entry).readAllBytes()).accept(original, 0);
			}
			ClassWriter writer = new ClassWriter(0);
			writer.visit(original.version, original.access, original.name, null, "java/lang/Object", null);
			int found = 0;
			for (MethodNode method : original.methods) {
				if (!List.of(names).contains(method.name)) continue;
				assertTrue((method.access & Opcodes.ACC_STATIC) != 0, owner + "." + method.name + " is a static hook");
				method.accept(writer);
				found++;
			}
			assertEquals(names.length, found, owner + " declares each of " + List.of(names) + " once");
			writer.visitEnd();
			return writer.toByteArray();
		}

		private static byte[] entityStandIn() {
			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, ENTITY, null, "java/lang/Object", null);
			MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
			init.visitCode();
			init.visitVarInsn(Opcodes.ALOAD, 0);
			init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
			init.visitInsn(Opcodes.RETURN);
			init.visitMaxs(0, 0);
			init.visitEnd();
			MethodVisitor level = writer.visitMethod(Opcodes.ACC_PUBLIC, "level", "()L" + LEVEL + ";", null, null);
			level.visitCode();
			level.visitInsn(Opcodes.ACONST_NULL);
			level.visitInsn(Opcodes.ARETURN);
			level.visitMaxs(0, 0);
			level.visitEnd();
			writer.visitEnd();
			return writer.toByteArray();
		}

		/**
		 * The merged game's sound sites, each returning the NeoForge event it reads: only NeoForge asked; MinecraftForge
		 * asked natively first, on the same path; and MinecraftForge asked about the entity's sound while NeoForge is
		 * asked about a position's.
		 */
		private static byte[] callers() {
			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, CALLERS, null, "java/lang/Object", null);
			String entityArgs = "(L" + ENTITY + ";L" + HOLDER + ";L" + SOURCE + ";FF)Ljava/lang/Object;";
			String positionArgs = "(L" + LEVEL + ";DDDL" + HOLDER + ";L" + SOURCE + ";FF)Ljava/lang/Object;";
			for (boolean both : new boolean[] {false, true}) {
				MethodVisitor at = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, both ? "bothAtEntity" : "neoAtEntity",
						entityArgs, null, null);
				at.visitCode();
				if (both) {
					at.visitVarInsn(Opcodes.ALOAD, 0);
					at.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY, "level", "()L" + LEVEL + ";", false);
					entityTail(at, 0, FORGE_HOOKS, FORGE_AT_ENTITY);
					at.visitInsn(Opcodes.POP);
				}
				entityTail(at, 0, NEO_HOOKS, NEO_AT_ENTITY);
				at.visitInsn(Opcodes.ARETURN);
				at.visitMaxs(0, 0);
				at.visitEnd();

				MethodVisitor pos = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, both ? "bothAtPosition" : "neoAtPosition",
						positionArgs, null, null);
				pos.visitCode();
				if (both) {
					positionCall(pos, 0, FORGE_HOOKS, FORGE_AT_POSITION);
					pos.visitInsn(Opcodes.POP);
				}
				positionCall(pos, 0, NEO_HOOKS, NEO_AT_POSITION);
				pos.visitInsn(Opcodes.ARETURN);
				pos.visitMaxs(0, 0);
				pos.visitEnd();
			}
			MethodVisitor mixed = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "forgeEntityThenNeoPosition",
					"(L" + ENTITY + ";L" + LEVEL + ";DDDL" + HOLDER + ";L" + SOURCE + ";FF)Ljava/lang/Object;", null, null);
			mixed.visitCode();
			mixed.visitVarInsn(Opcodes.ALOAD, 0);
			mixed.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY, "level", "()L" + LEVEL + ";", false);
			mixed.visitVarInsn(Opcodes.ALOAD, 0);
			mixed.visitVarInsn(Opcodes.ALOAD, 8);
			mixed.visitVarInsn(Opcodes.ALOAD, 9);
			mixed.visitVarInsn(Opcodes.FLOAD, 10);
			mixed.visitVarInsn(Opcodes.FLOAD, 11);
			mixed.visitMethodInsn(Opcodes.INVOKESTATIC, FORGE_HOOKS, "onPlaySoundAtEntity", FORGE_AT_ENTITY, false);
			mixed.visitInsn(Opcodes.POP);
			positionCall(mixed, 1, NEO_HOOKS, NEO_AT_POSITION);
			mixed.visitInsn(Opcodes.ARETURN);
			mixed.visitMaxs(0, 0);
			mixed.visitEnd();
			writer.visitEnd();
			return writer.toByteArray();
		}

		/** Pushes {@code (entity, holder, source, volume, pitch)} from slot {@code first} on and calls the hook. */
		private static void entityTail(MethodVisitor at, int first, String owner, String desc) {
			at.visitVarInsn(Opcodes.ALOAD, first);
			at.visitVarInsn(Opcodes.ALOAD, first + 1);
			at.visitVarInsn(Opcodes.ALOAD, first + 2);
			at.visitVarInsn(Opcodes.FLOAD, first + 3);
			at.visitVarInsn(Opcodes.FLOAD, first + 4);
			at.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "onPlaySoundAtEntity", desc, false);
		}

		/** Pushes {@code (level, x, y, z, holder, source, volume, pitch)} from slot {@code first} on and calls the hook. */
		private static void positionCall(MethodVisitor at, int first, String owner, String desc) {
			at.visitVarInsn(Opcodes.ALOAD, first);
			at.visitVarInsn(Opcodes.DLOAD, first + 1);
			at.visitVarInsn(Opcodes.DLOAD, first + 3);
			at.visitVarInsn(Opcodes.DLOAD, first + 5);
			at.visitVarInsn(Opcodes.ALOAD, first + 7);
			at.visitVarInsn(Opcodes.ALOAD, first + 8);
			at.visitVarInsn(Opcodes.FLOAD, first + 9);
			at.visitVarInsn(Opcodes.FLOAD, first + 10);
			at.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "onPlaySoundAtPosition", desc, false);
		}

		@Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			// One NativeEventDelivery for the injected hooks and the guard GameEventMultiplexer wraps the bridge in.
			if (name.startsWith("net.forbric.api.")) return PlayLevelSoundBridgeTest.class.getClassLoader().loadClass(name);
			synchronized (getClassLoadingLock(name)) {
				Class<?> done = findLoadedClass(name);
				if (done != null) return done;
				byte[] bytes = defined.get(name.replace('.', '/'));
				if (bytes != null) return defineClass(name, bytes, 0, bytes.length);
				return super.loadClass(name, resolve);
			}
		}

		Class<?> type(String internal) {
			try {
				// Not initialised here: Level's initializer needs a bootstrapped game, and naming the type is all a
				// signature lookup needs. A class is initialised when something is called on it.
				return Class.forName(internal.replace('/', '.'), false, this);
			} catch (ClassNotFoundException absent) {
				throw new AssertionError(internal + " is not in the staged game", absent);
			}
		}

		Object entity() throws Exception {
			return type(ENTITY).getConstructor().newInstance();
		}

		/** A sound holder; the bridge compares holders by identity and never resolves one. */
		Object holder(String name) throws Exception {
			return type(HOLDER).getMethod("direct", Object.class).invoke(null, name);
		}

		String nameOf(Object holder) {
			return holder == null ? null : String.valueOf(call(holder, "value"));
		}

		Object source(String name) {
			return call(null, type(SOURCE), "valueOf", name);
		}

		private static Object call(Object target, Class<?> type, String name, String argument) {
			try {
				return type.getMethod(name, String.class).invoke(target, argument);
			} catch (ReflectiveOperationException failure) {
				throw new AssertionError(name + " on " + type, failure);
			}
		}

		Object atEntity(String caller, Object entity, Object holder, Object source, float volume, float pitch) throws Exception {
			return unwrap(() -> type(CALLERS).getMethod(caller, type(ENTITY), type(HOLDER), type(SOURCE), float.class, float.class)
					.invoke(null, entity, holder, source, volume, pitch));
		}

		Object atPosition(String caller, double x, double y, double z, Object holder, Object source, float volume, float pitch)
				throws Exception {
			return unwrap(() -> type(CALLERS).getMethod(caller, type(LEVEL), double.class, double.class, double.class, type(HOLDER),
					type(SOURCE), float.class, float.class).invoke(null, null, x, y, z, holder, source, volume, pitch));
		}

		/** What the merged game plays for the NeoForge event it read: nothing when cancelled or soundless. */
		Object[] plays(Object neoEvent) {
			if ((Boolean) call(neoEvent, "isCanceled") || call(neoEvent, "getSound") == null) return null;
			return new Object[] {call(neoEvent, "getSound"), call(neoEvent, "getSource"), call(neoEvent, "getNewVolume"),
					call(neoEvent, "getNewPitch")};
		}

		/** What a MinecraftForge listener is handed: sound, source, original volume, volume, pitch, entity or position. */
		Object[] view(Object forgeEvent) {
			Object where = forgeEvent.getClass().getSimpleName().endsWith("AtEntity") ? call(forgeEvent, "getEntity")
					: call(forgeEvent, "getPosition");
			return new Object[] {call(forgeEvent, "getSound"), call(forgeEvent, "getSource"), call(forgeEvent, "getOriginalVolume"),
					call(forgeEvent, "getNewVolume"), call(forgeEvent, "getNewPitch"), where};
		}

		Object entityOf(Object forgeEvent) {
			return forgeEvent.getClass().getName().endsWith("$AtEntity") ? call(forgeEvent, "getEntity") : null;
		}

		/** A NeoForge listener at NORMAL priority, ahead of the bridge's LOWEST forward. */
		void neoListener(String shape, Consumer<Object> listener) throws Exception {
			Class<?> priority = type("net/neoforged/bus/api/EventPriority");
			Object normal = priority.getMethod("valueOf", String.class).invoke(null, "NORMAL");
			Object bus = type("net/neoforged/neoforge/common/NeoForge").getField("EVENT_BUS").get(null);
			type("net/neoforged/bus/api/IEventBus").getMethod("addListener", priority, boolean.class, Class.class, Consumer.class)
					.invoke(bus, normal, false, type(NEO_EVENT + "$" + shape), listener);
		}

		/** A MinecraftForge listener on one sound event's own bus. */
		void forgeListener(String shape, Consumer<Object> listener) throws Exception {
			Object bus = type(FORGE_EVENT + "$" + shape).getField("BUS").get(null);
			type("net/minecraftforge/eventbus/api/bus/EventBus").getMethod("addListener", Consumer.class).invoke(bus, listener);
		}

		/** A cancelling MinecraftForge listener on the parent event's bus. */
		void forgeCanceller(Predicate<Object> listener) throws Exception {
			Object bus = type(FORGE_EVENT).getField("BUS").get(null);
			type("net/minecraftforge/eventbus/api/bus/CancellableEventBus").getMethod("addListener", Predicate.class).invoke(bus, listener);
		}

		Object call(Object target, String name, Class<?> parameter, Object argument) {
			try {
				Method method = find(target.getClass(), name, parameter);
				return method.invoke(target, argument);
			} catch (ReflectiveOperationException failure) {
				throw new AssertionError(name + " on " + target, failure);
			}
		}

		Object call(Object target, String name) {
			try {
				Method method = find(target.getClass(), name, null);
				return method.invoke(target);
			} catch (ReflectiveOperationException failure) {
				throw new AssertionError(name + " on " + target, failure);
			}
		}

		private static Method find(Class<?> type, String name, Class<?> parameter) throws NoSuchMethodException {
			Method method = parameter == null ? type.getMethod(name) : type.getMethod(name, parameter);
			method.setAccessible(true);
			return method;
		}

		@FunctionalInterface
		private interface Call {
			Object run() throws Exception;
		}

		private static Object unwrap(Call call) throws Exception {
			try {
				return call.run();
			} catch (InvocationTargetException thrown) {
				if (thrown.getCause() instanceof Exception failure) throw failure;
				if (thrown.getCause() instanceof Error failure) throw failure;
				throw thrown;
			}
		}

	}

}
