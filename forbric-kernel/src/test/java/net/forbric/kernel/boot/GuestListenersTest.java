/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import net.forbric.api.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * The listeners a guest registers in code, which the class-level {@code @EventBusSubscriber} scan never saw.
 *
 * <p>MinecraftForge's EventBus 7 keeps each game event on a static bus shared by every mod; a voice-chat mod's
 * {@code RenderNameTagEvent.BUS.addListener(this::on)} and a mod's {@code ItemTossEvent.BUS.addListener(...)} were invisible
 * to the dead-event audit, and both events are never posted by the merged game. These pin each shape that is read, the
 * shapes that are not a listener, and that a mod found this way is named exactly as an annotation subscriber is.
 */
class GuestListenersTest {
	private static final String NAME_TAG = "net/minecraftforge/client/event/RenderNameTagEvent";
	private static final String TOSS = "net/minecraftforge/event/entity/item/ItemTossEvent";
	private static final String CANCELLABLE_BUS = "net/minecraftforge/eventbus/api/bus/CancellableEventBus";
	private static final String EVENT_BUS = "net/minecraftforge/eventbus/api/bus/EventBus";
	private static final String BUS_GROUP = "net/minecraftforge/eventbus/api/bus/BusGroup";
	private static final String NEO_BUS = "net/neoforged/bus/api/IEventBus";
	private static final Handle METAFACTORY = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
			"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
					+ "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
	@TempDir Path temporary;

	/** A class {@code name} whose {@code init(Object)} runs {@code body}; it also declares {@code on(event)} for method references. */
	private static byte[] guest(String name, String event, Consumer<MethodVisitor> body) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
			@Override protected String getCommonSuperClass(String a, String b) {
				return "java/lang/Object";
			}
		};
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "init", "(Ljava/lang/Object;)V", null, null);
		init.visitCode();
		body.accept(init);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();
		MethodVisitor on = cw.visitMethod(Opcodes.ACC_PUBLIC, "on", "(L" + event + ";)V", null, null);
		on.visitCode();
		on.visitInsn(Opcodes.RETURN);
		on.visitMaxs(0, 0);
		on.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** Pushes {@code this::on} as a {@code Consumer} of {@code event}. */
	private static void methodReference(MethodVisitor mv, String owner, String event) {
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitInvokeDynamicInsn("accept", "(L" + owner + ";)Ljava/util/function/Consumer;", METAFACTORY,
				Type.getType("(Ljava/lang/Object;)V"), new Handle(Opcodes.H_INVOKEVIRTUAL, owner, "on", "(L" + event + ";)V", false),
				Type.getType("(L" + event + ";)V"));
	}

	private static void addListener(MethodVisitor mv, String busType) {
		mv.visitMethodInsn(busType.equals(NEO_BUS) ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, busType, "addListener",
				"(Ljava/util/function/Consumer;)Ljava/lang/Object;", busType.equals(NEO_BUS));
		mv.visitInsn(Opcodes.POP);
	}

	private static Set<String> events(byte[] bytes) {
		assertTrue(GuestListeners.namesABus(new ClassReader(bytes)), "the constant pool names a bus");
		return GuestListeners.scan(bytes).events();
	}

	@Test void aListenerOnAnEventsStaticBusListensForThatEvent() {
		byte[] voice = guest("voice/ClientMod", NAME_TAG, mv -> {
			mv.visitFieldInsn(Opcodes.GETSTATIC, NAME_TAG, "BUS", "L" + CANCELLABLE_BUS + ";");
			methodReference(mv, "voice/ClientMod", NAME_TAG);
			addListener(mv, CANCELLABLE_BUS);
		});
		assertEquals(Set.of(NAME_TAG), events(voice));
	}

	@Test void aListenerObjectOnAnEventsStaticBusIsReadFromTheBus() {
		// No lambda to read a type from: a listener object of the mod's own class. Only the receiver names the event.
		byte[] object = guest("voice/ObjectMod", NAME_TAG, mv -> {
			mv.visitFieldInsn(Opcodes.GETSTATIC, NAME_TAG, "BUS", "L" + CANCELLABLE_BUS + ";");
			mv.visitVarInsn(Opcodes.ALOAD, 1);
			mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Consumer");
			addListener(mv, CANCELLABLE_BUS);
		});
		assertEquals(Set.of(NAME_TAG), events(object));
	}

	@Test void aBusKeptInALocalIsTracedThroughIt() {
		byte[] local = guest("lucid/MainMod", TOSS, mv -> {
			mv.visitFieldInsn(Opcodes.GETSTATIC, TOSS, "BUS", "L" + CANCELLABLE_BUS + ";");
			mv.visitVarInsn(Opcodes.ASTORE, 2);
			mv.visitVarInsn(Opcodes.ALOAD, 2);
			methodReference(mv, "lucid/MainMod", TOSS);
			addListener(mv, CANCELLABLE_BUS);
		});
		assertEquals(Set.of(TOSS), events(local));
	}

	@Test void anEventsGetBusListensForThatEvent() {
		String setup = "net/minecraftforge/fml/event/lifecycle/FMLCommonSetupEvent";
		byte[] modBus = guest("m/Mod", setup, mv -> {
			mv.visitVarInsn(Opcodes.ALOAD, 1);
			mv.visitTypeInsn(Opcodes.CHECKCAST, BUS_GROUP);
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, setup, "getBus", "(L" + BUS_GROUP + ";)L" + EVENT_BUS + ";", false);
			methodReference(mv, "m/Mod", setup);
			addListener(mv, EVENT_BUS);
		});
		assertEquals(Set.of(setup), events(modBus));
	}

	@Test void aBusFromElsewhereStillNamesItsEventThroughTheListenersType() {
		// The bus arrives as a parameter (a helper registering on a bus it was handed): the receiver says nothing, the
		// method reference's instantiated type does. NeoForge's IEventBus.addListener(Consumer) is read the same way.
		for (String bus : List.of(CANCELLABLE_BUS, NEO_BUS)) {
			byte[] helper = guest("h/Helper", TOSS, mv -> {
				mv.visitVarInsn(Opcodes.ALOAD, 1);
				mv.visitTypeInsn(Opcodes.CHECKCAST, bus);
				methodReference(mv, "h/Helper", TOSS);
				addListener(mv, bus);
			});
			assertEquals(Set.of(TOSS), events(helper), bus);
		}
	}

	@Test void postingOnABusIsNotListening() {
		byte[] poster = guest("p/Poster", TOSS, mv -> {
			mv.visitFieldInsn(Opcodes.GETSTATIC, TOSS, "BUS", "L" + CANCELLABLE_BUS + ";");
			mv.visitInsn(Opcodes.ACONST_NULL);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CANCELLABLE_BUS, "post", "(Ljava/lang/Object;)Z", false);
			mv.visitInsn(Opcodes.POP);
		});
		assertEquals(Set.of(), events(poster));
	}

	@Test void aClassHandedToABusToRegisterIsRead() {
		byte[] registrar = guest("c/Mod", TOSS, mv -> {
			mv.visitFieldInsn(Opcodes.GETSTATIC, BUS_GROUP, "DEFAULT", "L" + BUS_GROUP + ";");
			mv.visitInsn(Opcodes.ACONST_NULL);
			mv.visitLdcInsn(Type.getObjectType("c/events/ForgeEvents"));
			mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, BUS_GROUP, "register",
					"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/Class;)Ljava/util/Collection;", true);
			mv.visitInsn(Opcodes.POP);
		});
		assertEquals(Set.of("c/events/ForgeEvents"), GuestListeners.scan(registrar).registeredClasses());
	}

	@Test void anInstanceRegisteredOnMinecraftForgesGameBusIsRead() {
		// libIPN: MinecraftForge.EVENT_BUS.register(new CommonForgeEventHandler()), the bus field declared as Forge's
		// EventBusMigrationHelper; the handler's @SubscribeEvent methods (boolean ones among them) carry no class-level
		// @EventBusSubscriber.
		String helper = "net/minecraftforge/common/EventBusMigrationHelper";
		byte[] init = guest("ipn/ClientInit", TOSS, mv -> {
			mv.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraftforge/common/MinecraftForge", "EVENT_BUS", "L" + helper + ";");
			mv.visitTypeInsn(Opcodes.NEW, "ipn/Handler");
			mv.visitInsn(Opcodes.DUP);
			mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "ipn/Handler", "<init>", "()V", false);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, helper, "register", "(Ljava/lang/Object;)Ljava/util/Collection;", false);
			mv.visitInsn(Opcodes.POP);
		});
		assertTrue(GuestListeners.namesABus(new ClassReader(init)));
		assertEquals(Set.of("ipn/Handler"), GuestListeners.scan(init).registeredClasses());
	}

	@Test void aHelperHandedTheBusArgumentIsFollowedToItsCallers() {
		// JourneyMap's shape: ForgeEventHandlerManager.register(EventHandler handler) calls BusGroup.DEFAULT.register(lookup,
		// handler), and registerHandlers() calls register(new ForgeEvents()). The registered class is the helper's parameter,
		// named at the helper's call site in the same class; a listener a helper is handed is followed the same way.
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
			@Override protected String getCommonSuperClass(String a, String b) {
				return "java/lang/Object";
			}
		};
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "jm/Manager", null, "java/lang/Object", null);
		MethodVisitor register = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "register", "(Ljava/lang/Object;)V", null, null);
		register.visitCode();
		register.visitFieldInsn(Opcodes.GETSTATIC, BUS_GROUP, "DEFAULT", "L" + BUS_GROUP + ";");
		register.visitInsn(Opcodes.ACONST_NULL);
		register.visitVarInsn(Opcodes.ALOAD, 0);
		register.visitMethodInsn(Opcodes.INVOKEINTERFACE, BUS_GROUP, "register",
				"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/Object;)Ljava/util/Collection;", true);
		register.visitInsn(Opcodes.POP);
		register.visitInsn(Opcodes.RETURN);
		register.visitMaxs(0, 0);
		register.visitEnd();
		MethodVisitor listen = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "listen", "(L" + CANCELLABLE_BUS + ";Ljava/util/function/Consumer;)V", null, null);
		listen.visitCode();
		listen.visitVarInsn(Opcodes.ALOAD, 0);
		listen.visitVarInsn(Opcodes.ALOAD, 1);
		addListener(listen, CANCELLABLE_BUS);
		listen.visitInsn(Opcodes.RETURN);
		listen.visitMaxs(0, 0);
		listen.visitEnd();
		MethodVisitor all = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "registerHandlers", "()V", null, null);
		all.visitCode();
		all.visitTypeInsn(Opcodes.NEW, "jm/ForgeEvents");
		all.visitInsn(Opcodes.DUP);
		all.visitMethodInsn(Opcodes.INVOKESPECIAL, "jm/ForgeEvents", "<init>", "()V", false);
		all.visitMethodInsn(Opcodes.INVOKESTATIC, "jm/Manager", "register", "(Ljava/lang/Object;)V", false);
		all.visitFieldInsn(Opcodes.GETSTATIC, TOSS, "BUS", "L" + CANCELLABLE_BUS + ";");
		all.visitInsn(Opcodes.ACONST_NULL);
		all.visitMethodInsn(Opcodes.INVOKESTATIC, "jm/Manager", "listen", "(L" + CANCELLABLE_BUS + ";Ljava/util/function/Consumer;)V", false);
		all.visitInsn(Opcodes.RETURN);
		all.visitMaxs(0, 0);
		all.visitEnd();
		cw.visitEnd();
		GuestListeners.Found found = GuestListeners.scan(cw.toByteArray());
		assertEquals(Set.of("jm/ForgeEvents"), found.registeredClasses());
		assertEquals(Set.of(TOSS), found.events());
	}

	@Test void aBooleanReturningSubscribeEventMethodIsAListenerToo() {
		// MinecraftForge's cancellable listeners may return boolean (a predicate); the annotation scan reads the parameter,
		// so an @SubscribeEvent boolean onToss(ItemTossEvent) is a listener for ItemTossEvent like a void one.
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "c/Cancels", null, "java/lang/Object", null);
		cw.visitAnnotation("Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber;", true).visitEnd();
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "onToss", "(L" + TOSS + ";)Z", null, null);
		mv.visitAnnotation("Lnet/minecraftforge/eventbus/api/listener/SubscribeEvent;", true).visitEnd();
		mv.visitCode();
		mv.visitInsn(Opcodes.ICONST_1);
		mv.visitInsn(Opcodes.IRETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();
		cw.visitEnd();
		KernelEventSubscribers.Subscriber subscriber = KernelEventSubscribers.scanClassBytes(cw.toByteArray());
		assertEquals(Set.of(TOSS), subscriber.subscribedEvents());
		assertEquals(1, DeadEventAudit.audit(Map.of("tossmod", subscriber.subscribedEvents()), Set.of()).size(),
				"a cancellable listener for an event the merged game never posts is a finding");
	}

	private Path jar(String name, Map<String, byte[]> entries) throws Exception {
		Path jar = temporary.resolve(name);
		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
			for (Map.Entry<String, byte[]> e : entries.entrySet()) {
				zip.putNextEntry(new ZipEntry(e.getKey()));
				zip.write(e.getValue());
				zip.closeEntry();
			}
		}
		return jar;
	}

	/** A MinecraftForge {@code @Mod(id)} class. */
	private static byte[] modClass(String name, String id) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		var av = cw.visitAnnotation("Lnet/minecraftforge/fml/common/Mod;", true);
		av.visit("value", id);
		av.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A class with one {@code @SubscribeEvent} method for {@code event}, optionally {@code @OnlyIn(Dist.CLIENT)}. */
	private static byte[] annotatedListener(String name, String event, boolean clientOnly) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		if (clientOnly) {
			var only = cw.visitAnnotation("Lnet/minecraftforge/api/distmarker/OnlyIn;", false);
			only.visitEnum("value", "Lnet/minecraftforge/api/distmarker/Dist;", "CLIENT");
			only.visitEnd();
		}
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "on", "(L" + event + ";)V", null, null);
		mv.visitAnnotation("Lnet/minecraftforge/eventbus/api/listener/SubscribeEvent;", true).visitEnd();
		mv.visitCode();
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 2);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	@Test void aModFoundListeningInCodeIsNamedLikeAnAnnotationSubscriber() throws Exception {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("voice/VoiceMod.class", modClass("voice/VoiceMod", "voicemod"));
		entries.put("voice/ClientMod.class", guest("voice/ClientMod", NAME_TAG, mv -> {
			mv.visitFieldInsn(Opcodes.GETSTATIC, NAME_TAG, "BUS", "L" + CANCELLABLE_BUS + ";");
			methodReference(mv, "voice/ClientMod", NAME_TAG);
			addListener(mv, CANCELLABLE_BUS);
		}));
		entries.put("voice/Registrar.class", guest("voice/Registrar", TOSS, mv -> {
			mv.visitFieldInsn(Opcodes.GETSTATIC, BUS_GROUP, "DEFAULT", "L" + BUS_GROUP + ";");
			mv.visitInsn(Opcodes.ACONST_NULL);
			mv.visitLdcInsn(Type.getObjectType("voice/TossEvents"));
			mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, BUS_GROUP, "register",
					"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/Class;)Ljava/util/Collection;", true);
			mv.visitInsn(Opcodes.POP);
		}));
		entries.put("voice/TossEvents.class", annotatedListener("voice/TossEvents", TOSS, false));
		Path jar = jar("voice.jar", entries);
		KernelEventSubscribers.JarScan scanned = KernelEventSubscribers.scanJar(jar);
		assertTrue(scanned.subscribers().isEmpty(), "no class-level @EventBusSubscriber: the annotation scan alone sees nothing");

		Map<String, Set<String>> client = new LinkedHashMap<>();
		KernelEventSubscribers.addCodeListeners(jar, scanned, Side.CLIENT, client);
		assertEquals(Map.of("voicemod", Set.of(NAME_TAG, TOSS)), client, "attributed to the jar's mod");
		List<DeadEventAudit.Finding> findings = DeadEventAudit.audit(client, Set.of());
		assertEquals(Set.of(NAME_TAG, TOSS), Set.copyOf(findings.stream().map(DeadEventAudit.Finding::event).toList()),
				"both events the merged game never posts name the mod");

		// On a dedicated server the name tag is a client event (CLIENT_ONLY), and so not judged there; the toss is.
		Map<String, Set<String>> server = new LinkedHashMap<>();
		KernelEventSubscribers.addCodeListeners(jar, scanned, Side.DEDICATED_SERVER, server);
		Map<String, Set<String>> deadNow = new LinkedHashMap<>(), bridgeable = new LinkedHashMap<>();
		DeadEventAudit.split(server, Side.DEDICATED_SERVER, deadNow, bridgeable);
		assertEquals(Map.of("voicemod", Set.of(TOSS)), deadNow);
	}

	@Test void aJarWithSeveralModsGivesTheListenerToTheModClassThatNamesIt() throws Exception {
		// voicechat's shape: two @Mod classes in one package (the mod and an API dummy mod), the client listener constructed
		// by the mod class only. The name prefix alone would not settle it; the constant pool does.
		Map<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("v/ApiDummyMod.class", modClass("v/ApiDummyMod", "voice_api"));
		ClassWriter mod = new ClassWriter(0);
		mod.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "v/VoiceMod", null, "java/lang/Object", null);
		var id = mod.visitAnnotation("Lnet/minecraftforge/fml/common/Mod;", true);
		id.visit("value", "voice");
		id.visitEnd();
		MethodVisitor init = mod.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitTypeInsn(Opcodes.NEW, "v/ClientListener");
		init.visitInsn(Opcodes.POP);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(1, 1);
		init.visitEnd();
		mod.visitEnd();
		entries.put("v/VoiceMod.class", mod.toByteArray());
		entries.put("v/ClientListener.class", guest("v/ClientListener", NAME_TAG, mv -> {
			mv.visitFieldInsn(Opcodes.GETSTATIC, NAME_TAG, "BUS", "L" + CANCELLABLE_BUS + ";");
			methodReference(mv, "v/ClientListener", NAME_TAG);
			addListener(mv, CANCELLABLE_BUS);
		}));
		Path jar = jar("voice2.jar", entries);
		Map<String, Set<String>> client = new LinkedHashMap<>();
		KernelEventSubscribers.addCodeListeners(jar, KernelEventSubscribers.scanJar(jar), Side.CLIENT, client);
		assertEquals(Map.of("voice", Set.of(NAME_TAG)), client);
	}

	@Test void aClientOnlyListenerIsNotCountedOnADedicatedServer() throws Exception {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("d/DMod.class", modClass("d/DMod", "dmod"));
		entries.put("d/Registrar.class", guest("d/Registrar", TOSS, mv -> {
			mv.visitFieldInsn(Opcodes.GETSTATIC, BUS_GROUP, "DEFAULT", "L" + BUS_GROUP + ";");
			mv.visitInsn(Opcodes.ACONST_NULL);
			mv.visitLdcInsn(Type.getObjectType("d/ClientEvents"));
			mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, BUS_GROUP, "register",
					"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/Class;)Ljava/util/Collection;", true);
			mv.visitInsn(Opcodes.POP);
		}));
		entries.put("d/ClientEvents.class", annotatedListener("d/ClientEvents", TOSS, true));
		Path jar = jar("d.jar", entries);
		KernelEventSubscribers.JarScan scanned = KernelEventSubscribers.scanJar(jar);
		Map<String, Set<String>> server = new LinkedHashMap<>(), client = new LinkedHashMap<>();
		KernelEventSubscribers.addCodeListeners(jar, scanned, Side.DEDICATED_SERVER, server);
		KernelEventSubscribers.addCodeListeners(jar, scanned, Side.CLIENT, client);
		assertEquals(Map.of(), server, "a dedicated server never loads an @OnlyIn(CLIENT) class");
		assertEquals(Map.of("dmod", Set.of(TOSS)), client);
		assertFalse(scanned.code().isEmpty());
	}
}
