/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.boot;

import java.lang.invoke.MethodHandles;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.discovery.ModAnnotationScanner;
import net.forbric.kernel.util.ForbricLog;

/**
 * Registers a Forge-family mod's {@code @Mod$EventBusSubscriber} classes on the game event bus — the kernel's
 * native replacement for FML's {@code AutomaticEventSubscriber}.
 *
 * <p>Many Forge mods declare their game-event listeners not in the {@code @Mod} constructor but on a separate
 * class annotated {@code @Mod.EventBusSubscriber} whose static {@code @SubscribeEvent} methods FML auto-registers
 * during mod loading. The kernel does no FML scan, so those listeners would never fire (e.g. a mod's
 * {@code ServerTickEvent} handler). This ASM-scans each mod jar for the annotation and registers what it finds.
 *
 * <p><b>The two families are NOT interchangeable and each needs its own entry point.</b> An earlier version scanned
 * for both annotations, discarded which one matched, and handed every result to MinecraftForge's
 * {@code BusGroup.register(Lookup, Class)}. That was three bugs at once, all of them visible in the gate logs:
 *
 * <ul>
 *   <li><b>Per-CLASS registration on MinecraftForge is the wrong entry point.</b> {@code BusGroup.register} routes
 *       through {@code EventListenerFactory}, which throws {@code IllegalArgumentException: Only a single listener
 *       found in class …} when a subscriber declares exactly one listener — a performance hint, fatal here because
 *       the caller swallowed it. Genuine FML never takes that path: {@code AutomaticEventSubscriber} calls
 *       {@link #FML_EBS_LOGIC}, which builds listeners PER METHOD. gate-m4 lost {@code NoChatReports$Events} and
 *       {@code GeckoLibClient} to this.</li>
 *   <li><b>A NeoForge subscriber cannot be registered on a MinecraftForge {@code BusGroup}.</b> It carries
 *       {@code net.neoforged.bus.api.SubscribeEvent}, which MinecraftForge's scan does not recognise, so the class
 *       registers zero listeners and fails with {@code No listeners found}. gate-m4 lost collective's
 *       {@code RegisterCollectiveNeoForgeEvents}; gate-m7-neo lost all six of Architectury's, i.e. its ENTIRE event
 *       layer, silently, on a dedicated server.</li>
 *   <li><b>{@code Dist} was never consulted</b>, so a {@code @Dist.CLIENT} subscriber was loaded and registered on a
 *       dedicated server — noise at best, {@code NoClassDefFoundError} at worst.</li>
 * </ul>
 *
 * <p>So: the scan keeps which annotation matched, and each family goes to its own registrar. MinecraftForge reuses
 * FML's own {@code EventBusSubscriberLogic} rather than re-deriving its validation matrix (priority,
 * {@code Cancellable}, {@code alwaysCancelling}, boolean-returning predicates, 2-arg monitor listeners) — that is
 * the highest-drift code we could otherwise own, and it mints its own full-power lookup so {@link KernelGameLookup}
 * is not needed on that path. NeoForge reuses {@link #wireNeoSubscriber}, the per-method router this class already
 * had for NeoForge's own internal subscribers.
 */
public final class KernelEventSubscribers {
	private static final String EBS_FORGE = "Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber;";
	private static final String EBS_NEO = "Lnet/neoforged/fml/common/EventBusSubscriber;";

	/**
	 * FML's own {@code @EventBusSubscriber} registration logic. The CLASS is package-private but this entry point is
	 * {@code public static void register(BusGroup, Class<?>)}, and it is exactly what
	 * {@code AutomaticEventSubscriber.inject} calls, so a {@code setAccessible(true)} reaches it.
	 */
	private static final String FML_EBS_LOGIC =
			"net.minecraftforge.fml.javafmlmod.AutomaticEventSubscriber$EventBusSubscriberLogic";

	private KernelEventSubscribers() {
	}

	/**
	 * One {@code @EventBusSubscriber} class plus the annotation attributes that decide where its listeners go.
	 *
	 * @param family which ecosystem's annotation matched — the bit the old scan threw away
	 * @param dists  the declared {@code Dist[] value()}; EMPTY means "every side", not "no side"
	 * @param modId  the declared {@code modid()}, or null
	 * @param bus    the declared {@code bus()}, defaulting to {@code BOTH} when absent
	 */
	record Subscriber(String className, Ecosystem family, java.util.Set<String> dists,
			String modId, String bus) {
	}

	/** Which {@code BusGroup} a MinecraftForge subscriber's listeners belong on. */
	enum BusChoice {
		/** {@code bus = FORGE} — the game bus. */
		DEFAULT,
		/** {@code bus = MOD} — the owning mod's own bus group. */
		MOD,
		/**
		 * {@code bus = BOTH}, the default. Pass {@code null} and let Forge route per event type — its
		 * {@code registerListener} opens with {@code if (busGroup == null)} and picks the mod bus for an
		 * {@code IModBusEvent}, {@code BusGroup.DEFAULT} otherwise. Passing DEFAULT here instead would strand
		 * every mod-bus listener.
		 */
		AUTO,
		/** {@code bus = MOD} but the owning mod has no bus group — anywhere else would be a lie that never fires. */
		SKIP
	}

	/** Whether a subscriber declaring {@code dists} runs on this side. An empty {@code value()} means every side. */
	static boolean matchesSide(java.util.Set<String> dists, boolean client) {
		return dists.isEmpty() || dists.contains(client ? "CLIENT" : "DEDICATED_SERVER");
	}

	/** Maps a MinecraftForge {@code bus()} attribute to the group to pass FML. See {@link BusChoice}. */
	static BusChoice busGroupChoice(String bus, boolean handlePresent) {
		if ("FORGE".equals(bus)) return BusChoice.DEFAULT;
		if ("MOD".equals(bus)) return handlePresent ? BusChoice.MOD : BusChoice.SKIP;
		return BusChoice.AUTO;
	}

	/** Scans + registers every guest mod's {@code @EventBusSubscriber} class, each on its own family's bus. */
	public static void registerAll(ClassLoader cl, List<Path> modJars, boolean client) {
		if (!(cl instanceof ForbricClassLoader)) {
			ForbricLog.debug("[Forbric/EBS] not running under the sovereign loader — skipping @EventBusSubscriber");
			return;
		}
		ForbricClassLoader loader = (ForbricClassLoader) cl;

		ForgeBusApi forge = ForgeBusApi.resolve(cl);
		NeoBusApi neo = NeoBusApi.resolve(cl);
		if (forge == null && neo == null) {
			ForbricLog.debug("[Forbric/EBS] neither Forge family bus API is present — skipping @EventBusSubscriber");
			return;
		}

		int forgeClasses = 0;
		int neoMethods = 0;
		int skippedSide = 0;
		int skippedOwner = 0;
		for (Path jar : modJars) {
			List<Subscriber> subscribers = scan(jar);
			if (subscribers.isEmpty()) continue;
			List<ModAnnotationScanner.ModClassInfo> modsInJar = null; // scanned lazily, only if a modid() is missing

			for (Subscriber sub : subscribers) {
				if (!matchesSide(sub.dists(), client)) {
					skippedSide++;
					ForbricLog.debug("[Forbric/EBS] skipping %s — declares %s, running %s", sub.className(),
							sub.dists(), client ? "CLIENT" : "DEDICATED_SERVER");
					continue;
				}
				if (sub.modId() == null || sub.modId().isBlank()) {
					if (modsInJar == null) modsInJar = scanModClasses(jar);
				}
				String modId = ownerModId(sub, modsInJar);

				try {
					if (sub.family() == Ecosystem.FORGE) {
						if (forge == null) continue;
						if (registerForgeSubscriber(cl, loader, forge, sub, modId)) forgeClasses++;
						else skippedOwner++;
					} else {
						if (neo == null) continue;
						Object modBus = neoModBus(modId);
						if (modBus == null) skippedOwner++;
						neoMethods += wireNeoSubscriber(cl, sub.className(), modBus, neo);
					}
				} catch (Throwable t) {
					Throwable real = KernelBusSupport.unwrap(t);
					StringBuilder chain = new StringBuilder();
					for (Throwable x = real; x != null; x = x.getCause()) chain.append("\n      caused by: ").append(x);
					ForbricLog.warn("[Forbric/EBS] could not register " + sub.className() + chain, real);
				}
			}
		}

		// gate-m4-canary greps "registered [0-9]+ @EventBusSubscriber" — keep this wording verbatim.
		int total = forgeClasses + neoMethods;
		if (total > 0) {
			ForbricLog.info("[Forbric/EBS] registered %d @EventBusSubscriber class(es) on the game bus", total);
		}
		if (total > 0 || skippedSide > 0 || skippedOwner > 0) {
			ForbricLog.info("[Forbric/EBS] %d MinecraftForge class(es) + %d NeoForge listener method(s); "
					+ "%d skipped as wrong-side, %d skipped (owning mod has no bus)",
					forgeClasses, neoMethods, skippedSide, skippedOwner);
		}
	}

	/**
	 * Registers one MinecraftForge subscriber through FML's own logic. Returns false when it was deliberately
	 * skipped (a {@code bus = MOD} subscriber whose mod was never constructed).
	 *
	 * <p>The owning mod's container is made active around the call because {@code registerListener}'s null-group
	 * branch resolves the mod bus via {@code FMLJavaModLoadingContext.get()} — with no active container that
	 * lookup fails, and every {@code IModBusEvent} listener in a {@code bus = BOTH} class would be lost.
	 */
	private static boolean registerForgeSubscriber(ClassLoader cl, ForbricClassLoader loader, ForgeBusApi forge,
			Subscriber sub, String modId) throws Exception {
		KernelForgeModContext.Handle handle = modId == null ? null
				: KernelModLoader.publishedForgeMods().get(modId);
		BusChoice choice = busGroupChoice(sub.bus(), handle != null);
		if (choice == BusChoice.SKIP) {
			ForbricLog.warn("[Forbric/EBS] %s declares bus = MOD but mod '%s' has no bus group — skipping rather "
					+ "than parking its listeners on the game bus, where they would never fire",
					sub.className(), String.valueOf(modId));
			return false;
		}

		Object group = switch (choice) {
			case DEFAULT -> forge.defaultGroup();
			case MOD -> handle.busGroup();
			default -> null; // AUTO: Forge routes per event type
		};

		Class<?> c = Class.forName(sub.className(), true, cl);
		if (handle != null) KernelForgeModContext.setActiveContainer(cl, handle.container());
		try {
			if (forge.fmlRegister() != null) {
				forge.fmlRegister().invoke(null, group, c);
			} else {
				// Fallback for a Forge revision without EventBusSubscriberLogic. Rejects a class carrying exactly
				// one listener, and cannot honour AUTO — the defect this class exists to fix, kept only so an
				// unexpected runtime still wires the multi-listener majority.
				MethodHandles.Lookup lookup = KernelGameLookup.privateLookupIn(c, loader);
				forge.busGroupRegister().invoke(group == null ? forge.defaultGroup() : group, lookup, c);
			}
		} finally {
			if (handle != null) KernelForgeModContext.setActiveContainer(cl, null);
		}
		ForbricLog.debug("[Forbric/EBS] registered MinecraftForge @EventBusSubscriber %s (bus=%s)",
				sub.className(), choice);
		return true;
	}

	/** The mod bus of the NeoForge mod {@code modId}, or null when it was never published. */
	private static Object neoModBus(String modId) {
		if (modId == null) return null;
		KernelModLoader.NeoIdentity id = KernelModLoader.publishedNeoMods().get(modId);
		return id == null ? null : id.bus();
	}

	/**
	 * The mod that owns {@code sub}: its declared {@code modid()}, else the jar's single {@code @Mod} of the same
	 * family. Returns null when the jar declares several and the annotation named none — guessing would attach a
	 * mod's listeners to a sibling's bus.
	 */
	private static String ownerModId(Subscriber sub, List<ModAnnotationScanner.ModClassInfo> modsInJar) {
		if (sub.modId() != null && !sub.modId().isBlank()) return sub.modId();
		if (modsInJar == null) return null;
		String only = null;
		for (ModAnnotationScanner.ModClassInfo info : modsInJar) {
			if (info.family != sub.family() || info.modId == null) continue;
			if (only != null) return null;
			only = info.modId;
		}
		return only;
	}

	private static List<ModAnnotationScanner.ModClassInfo> scanModClasses(Path jar) {
		try {
			return ModAnnotationScanner.scan(jar);
		} catch (Throwable t) {
			return List.of();
		}
	}

	/** MinecraftForge's bus API, resolved once. {@code fmlRegister} is null when FML's own logic is unavailable. */
	private record ForgeBusApi(Object defaultGroup, java.lang.reflect.Method fmlRegister,
			java.lang.reflect.Method busGroupRegister) {

		static ForgeBusApi resolve(ClassLoader cl) {
			try {
				Class<?> busGroup = Class.forName("net.minecraftforge.eventbus.api.bus.BusGroup", false, cl);
				Object defaultGroup = busGroup.getField("DEFAULT").get(null);
				java.lang.reflect.Method busGroupRegister =
						busGroup.getMethod("register", MethodHandles.Lookup.class, Class.class);
				java.lang.reflect.Method fmlRegister = null;
				try {
					Class<?> logic = Class.forName(FML_EBS_LOGIC, false, cl);
					fmlRegister = logic.getMethod("register", busGroup, Class.class);
					fmlRegister.setAccessible(true);
				} catch (Throwable t) {
					ForbricLog.warn("[Forbric/EBS] %s absent — falling back to BusGroup.register(Lookup, Class), "
							+ "which rejects a subscriber class carrying exactly one listener", FML_EBS_LOGIC);
				}
				return new ForgeBusApi(defaultGroup, fmlRegister, busGroupRegister);
			} catch (Throwable t) {
				ForbricLog.debug("[Forbric/EBS] MinecraftForge BusGroup absent");
				return null;
			}
		}
	}

	/** NeoForge's bus API, resolved once. */
	private record NeoBusApi(Object gameBus, Class<?> subscribeEvent, Class<?> eventType, Class<?> modBusEventType,
			java.lang.reflect.Method register) {

		static NeoBusApi resolve(ClassLoader cl) {
			try {
				return new NeoBusApi(
						Class.forName("net.neoforged.neoforge.common.NeoForge", false, cl)
								.getField("EVENT_BUS").get(null),
						Class.forName("net.neoforged.bus.api.SubscribeEvent", false, cl),
						Class.forName("net.neoforged.bus.api.Event", false, cl),
						Class.forName(ForeignType.MOD_BUS_EVENT.binary(Ecosystem.NEOFORGE), false, cl),
						Class.forName("net.neoforged.bus.api.IEventBus", false, cl).getMethod("register", Object.class));
			} catch (Throwable t) {
				ForbricLog.debug("[Forbric/EBS] NeoForge bus API absent");
				return null;
			}
		}
	}

	/**
	 * NeoForge-internal subscribers the kernel cannot honor. Empty: {@code NeoForgeRenderPipelines} used to sit here
	 * because its pipelines' shaders ({@code assets/neoforge/shaders/*}) were unreachable, but
	 * {@link KernelClientPacks} now serves the ecosystem jars to the client {@code PackRepository}.
	 */
	private static final java.util.Set<String> NEO_INTERNAL_SKIP = java.util.Set.of();

	/**
	 * Wires NeoForge's OWN {@code @EventBusSubscriber} classes — the ones inside the neoforge RUNTIME jar. NeoForge
	 * ships as a mod (modid "neoforge") and FML scans its jar exactly like any other; the kernel scanned only mod
	 * jars, so none of NeoForge's ~10 internal subscribers were ever wired (NetworkInitialization,
	 * ClientPayloadHandler, ConfigurationInitialization, AttachmentSync, ModelDataManager, MonsterRoomHooks, …).
	 *
	 * <p>Mirrors {@code AutomaticEventSubscriber.inject} exactly: filter by the annotation's {@code Dist[] value()},
	 * then per {@code @SubscribeEvent} STATIC single-{@code Event}-arg method route by event type —
	 * {@code IModBusEvent} → the mod bus, everything else → NeoForge's game bus. Routing per METHOD (not per class)
	 * is required: a mod bus carries an {@code IModBusEvent} marker and rejects game events, and vice versa.
	 * {@code IEventBus.register} accepts the {@code Method} itself, which is how NeoForge registers one listener.
	 *
	 * <p>Separate from {@link #registerAll} only in WHICH jars and WHICH bus: the per-method routing itself is
	 * shared, in {@link #wireNeoSubscriber}.
	 */
	public static void registerNeoForgeInternal(ClassLoader cl, List<Path> jars, Object modBus, boolean client) {
		NeoBusApi api = NeoBusApi.resolve(cl);
		if (api == null) {
			ForbricLog.debug("[Forbric/EBS] NeoForge bus API absent — skipping internal @EventBusSubscriber scan");
			return;
		}

		int wired = 0;
		int skippedSide = 0;
		for (Path jar : jars) {
			for (Subscriber sub : scan(jar)) {
				if (sub.family() != Ecosystem.NEOFORGE) continue;
				if (!matchesSide(sub.dists(), client)) {
					skippedSide++;
					continue;
				}
				if (NEO_INTERNAL_SKIP.contains(sub.className())) {
					ForbricLog.info("[Forbric/EBS] skipping NeoForge-internal %s — needs ecosystem client assets the "
							+ "kernel does not serve yet (see NEO_INTERNAL_SKIP)", sub.className());
					continue;
				}
				try {
					wired += wireNeoSubscriber(cl, sub.className(), modBus, api);
				} catch (Throwable t) {
					ForbricLog.debug("[Forbric/EBS] could not wire NeoForge-internal %s: %s", sub.className(),
							String.valueOf(KernelBusSupport.unwrap(t)));
				}
			}
		}
		if (wired > 0) {
			ForbricLog.info("[Forbric/EBS] wired %d NeoForge-internal @SubscribeEvent method(s) from the runtime "
					+ "jar(s) (%d class(es) skipped as wrong-side)", wired, skippedSide);
		}
	}

	/**
	 * Registers every {@code @SubscribeEvent} listener on {@code className}, routing PER METHOD by event type:
	 * {@code IModBusEvent} → {@code modBus}, everything else → NeoForge's game bus. Per-method is required — a mod
	 * bus carries an {@code IModBusEvent} marker and rejects game events, and vice versa — and it is also what keeps
	 * a one-listener class working, since {@code IEventBus.register} takes the {@code Method} itself.
	 *
	 * @param modBus the owning mod's bus, or null when it has none; mod-bus listeners are then skipped
	 * @return how many listener methods were wired
	 */
	private static int wireNeoSubscriber(ClassLoader cl, String className, Object modBus, NeoBusApi api)
			throws Exception {
		Class<?> owner = Class.forName(className, true, cl);
		int wired = 0;
		for (java.lang.reflect.Method m : owner.getDeclaredMethods()) {
			@SuppressWarnings("unchecked")
			boolean subscribed = m.isAnnotationPresent(
					(Class<? extends java.lang.annotation.Annotation>) api.subscribeEvent());
			if (!subscribed || !java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
			if (m.getParameterCount() != 1) continue;
			Class<?> event = m.getParameterTypes()[0];
			if (!api.eventType().isAssignableFrom(event)) continue;

			Object bus = api.modBusEventType().isAssignableFrom(event) ? modBus : api.gameBus();
			if (bus == null) continue; // no mod bus — a mod-bus listener cannot be placed anywhere honest
			m.setAccessible(true); // NeoForge's own handlers, and some mods', are private static
			api.register().invoke(bus, m);
			wired++;
		}
		return wired;
	}

	/**
	 * ASM-scans a jar for {@code @EventBusSubscriber} classes of EITHER family, keeping which one matched plus the
	 * attributes that decide routing. Both families' {@code Dist} enums spell {@code CLIENT} /
	 * {@code DEDICATED_SERVER}, so the captured constant names compare directly.
	 */
	private static List<Subscriber> scan(Path jar) {
		List<Subscriber> found = new ArrayList<>();
		try (FileSystem fs = FileSystems.newFileSystem(jar, (ClassLoader) null)) {
			for (Path root : fs.getRootDirectories()) {
				try (Stream<Path> walk = Files.walk(root)) {
					walk.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
						Subscriber s = scanClass(p);
						if (s != null) found.add(s);
					});
				}
			}
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/EBS] could not scan %s: %s", jar.getFileName(),
					String.valueOf(KernelBusSupport.unwrap(t)));
		}
		return found;
	}

	static Subscriber scanClass(Path classFile) {
		try {
			return scanClassBytes(Files.readAllBytes(classFile));
		} catch (Throwable t) {
			return null;
		}
	}

	/** The scan proper, split out so tests can drive it from bytes without a jar on disk. */
	static Subscriber scanClassBytes(byte[] bytes) {
		try {
			String[] name = new String[1];
			Ecosystem[] family = new Ecosystem[1];
			String[] modId = new String[1];
			String[] bus = new String[1];
			java.util.Set<String> dists = new java.util.LinkedHashSet<>();
			new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
				@Override
				public void visit(int v, int access, String n, String sig, String sup, String[] itf) {
					name[0] = n;
				}

				@Override
				public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
					boolean forge = EBS_FORGE.equals(descriptor);
					if (!forge && !EBS_NEO.equals(descriptor)) return null;
					// A universal jar's glue class can carry BOTH. First one wins and the other is ignored: the
					// class can only be constructed once, and a second registration would double every listener.
					if (family[0] != null) return null;
					family[0] = forge ? Ecosystem.FORGE
							: Ecosystem.NEOFORGE;
					return new AnnotationVisitor(Opcodes.ASM9) {
						@Override
						public void visit(String attr, Object value) {
							if ("modid".equals(attr) && value instanceof String s) modId[0] = s;
						}

						@Override
						public void visitEnum(String attr, String desc, String value) {
							if ("bus".equals(attr)) bus[0] = value;
						}

						@Override
						public AnnotationVisitor visitArray(String arrayName) {
							if (!"value".equals(arrayName)) return null;
							return new AnnotationVisitor(Opcodes.ASM9) {
								@Override
								public void visitEnum(String n2, String desc2, String value) {
									dists.add(value);
								}
							};
						}
					};
				}
			}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

			if (family[0] == null) return null;
			// Absent bus() is Bus.BOTH — Forge's own default, and the one that routes per event type.
			return new Subscriber(name[0].replace('/', '.'), family[0], dists, modId[0],
					bus[0] == null ? "BOTH" : bus[0]);
		} catch (Throwable t) {
			return null;
		}
	}
}
