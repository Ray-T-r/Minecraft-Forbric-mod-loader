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

import net.forbric.api.Side;
import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;
import net.forbric.api.ForeignType;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.discovery.ModAnnotationScanner;
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;

/**
 * Registers a NeoForge mod's {@code @EventBusSubscriber} classes on the game event bus — the kernel's native
 * replacement for FML's {@code AutomaticEventSubscriber}.
 *
 * <p>Many mods declare their game-event listeners not in the {@code @Mod} constructor but on a separate class
 * annotated {@code @EventBusSubscriber} whose static {@code @SubscribeEvent} methods FML auto-registers during mod
 * loading. The kernel does no FML scan, so those listeners would never fire (e.g. a mod's {@code ServerTickEvent}
 * handler). This ASM-scans each mod jar for NeoForge's annotation and registers what it finds, per METHOD through
 * {@link #wireNeoSubscriber} — the router this class also uses for NeoForge's own internal subscribers — so a class
 * declaring a single listener is wired as NeoForge wires it, and {@code Dist} is consulted: a {@code Dist.CLIENT}
 * subscriber is never loaded on a dedicated server.
 *
 * <p>Only NeoForge's annotation is a subscriber, as on NeoForge. A universal jar's glue class carrying MinecraftForge's
 * {@code @Mod.EventBusSubscriber} beside NeoForge's is NeoForge's subscriber whichever annotation comes first;
 * MinecraftForge's alone marks nothing, since Forbric loads no MinecraftForge mod.
 */
public final class KernelEventSubscribers {
	private static final String EBS_NEO = "Lnet/neoforged/fml/common/EventBusSubscriber;";

	private KernelEventSubscribers() {
	}

	/**
	 * One {@code @EventBusSubscriber} class plus the annotation attributes that decide where its listeners go.
	 *
	 * @param family which ecosystem's annotation matched (NeoForge's, the only one the scan recognises)
	 * @param dists  the declared {@code Dist[] value()}; EMPTY means "every side", not "no side"
	 * @param modId  the declared {@code modid()}, or null
	 * @param bus    the declared {@code bus()}, defaulting to {@code BOTH} when absent
	 */
	record Subscriber(String className, Ecosystem family, java.util.Set<String> dists, String modId, String bus) {
	}

	/** Whether a subscriber declaring {@code dists} runs on this side. An empty {@code value()} means every side. */
	static boolean matchesSide(java.util.Set<String> dists, Side side) {
		return dists.isEmpty() || dists.contains(side.distName());
	}

	/** Scans + registers every guest mod's NeoForge {@code @EventBusSubscriber} class on NeoForge's buses. */
	public static void registerAll(ClassLoader cl, List<Path> modJars, Side side) {
		if (!(cl instanceof ForbricClassLoader)) {
			ForbricLog.debug("[Forbric/EBS] not running under the sovereign loader — skipping @EventBusSubscriber");
			return;
		}

		NeoBusApi neo = NeoBusApi.resolve(cl);
		if (neo == null) {
			ForbricLog.debug("[Forbric/EBS] NeoForge's bus API is not present — skipping @EventBusSubscriber");
			return;
		}

		// Where each subscriber class is loaded and registered: NeoForge's modloading-worker pool (wireNeoSubscriber).
		java.util.concurrent.Executor workers = NeoParallelDispatch.parallelExecutor(cl);
		int neoMethods = 0;
		int skippedSide = 0;
		int skippedOwner = 0;
		int skippedFamily = 0;
		int skippedFailed = 0;
		for (Path jar : modJars) {
			List<Subscriber> subscribers = scan(jar);
			if (subscribers.isEmpty()) continue;
			List<ModAnnotationScanner.ModClassInfo> modsInJar = null; // scanned lazily, only if a modid() is missing

			for (Subscriber sub : subscribers) {
				// A universal jar ships one subscriber per loader, and MultiLoaderArbiter has already decided which
				// family owns this jar: a Fabric+NeoForge jar given to Fabric must not also run its NeoForge listeners,
				// or the SAME handler runs twice for every event both post — double drops, double damage, double
				// packets — and the mod has no way to notice: each call looks like the only one.
				if (MultiLoaderArbiter.suppressedFor(jar, sub.family())) {
					skippedFamily++;
					ForbricLog.debug("[Forbric/EBS] skipping %s — %s does not own %s", sub.className(),
							sub.family(), jar.getFileName());
					continue;
				}
				if (!matchesSide(sub.dists(), side)) {
					skippedSide++;
					ForbricLog.debug("[Forbric/EBS] skipping %s — declares %s, running %s", sub.className(),
							sub.dists(), side.distName());
					continue;
				}
				if (sub.modId() == null || sub.modId().isBlank()) {
					if (modsInJar == null) modsInJar = scanModClasses(jar);
				}
				String modId = ownerModId(sub, modsInJar, () -> KernelModLoader.soleClasslessNeoModIn(jar));
				// A mod whose constructor threw registered nothing and initialised nothing; its listeners would
				// touch its own half-initialised classes the first time an event fires (wthit: NoClassDefFoundError
				// inside RegisterClientReloadListenersEvent, which killed the client). A native loader never gets
				// here — it stops at the constructor — so skipping them is the only honest continuation.
				if (didNotFinishLoading(modId)) {
					skippedFailed++;
					ModCatalog.mark(modId, ModCatalog.Status.DEGRADED, "its @EventBusSubscriber " + simpleName(sub.className())
							+ " was not registered — a mod in its jar did not finish loading, so its classes may be half-initialised");
					continue;
				}

				try {
					Object modBus = neoModBus(modId);
					if (modBus == null) skippedOwner++;
					neoMethods += wireNeoSubscriber(cl, sub.className(), modBus, neoModContainer(modId), workers, neo);
				} catch (Throwable t) {
					Throwable real = Reflect.unwrap(t);
					Caught.warn(real, "[Forbric/EBS] could not register %s%s", sub.className(), causeChain(real));
					registrationFailed(modId, sub.className(), real);
				}
			}
		}

		// gate-m4-canary greps "registered [0-9]+ @EventBusSubscriber" — keep this wording verbatim.
		// KernelEventSubscribersGenuineBaseTest prints it on the genuine NeoForge base.
		int total = neoMethods;
		if (total > 0) {
			ForbricLog.info("[Forbric/EBS] registered %d @EventBusSubscriber class(es) on the game bus", total);
		}
		if (total > 0 || skippedSide > 0 || skippedOwner > 0 || skippedFamily > 0) {
			ForbricLog.info("[Forbric/EBS] %d NeoForge listener method(s); "
					+ "%d skipped as wrong-side, %d skipped (owning mod has no bus), %d skipped as the other "
					+ "family's half of a universal jar, %d skipped because the owning mod did not finish loading",
					neoMethods, skippedSide, skippedOwner, skippedFamily, skippedFailed);
		}
	}

	/**
	 * A subscriber class that could not be registered: the cause chain is logged exactly as before, and when the
	 * owning mod is known its row says which class and that its listeners will not run. A subscriber no mod owns
	 * (a jar with no mod class beside it) marks nobody — the log line is the whole report then.
	 */
	static void registrationFailed(String modId, String className, Throwable real) {
		if (modId != null && !modId.isBlank()) {
			ModCatalog.mark(modId, ModCatalog.Status.DEGRADED, "its @EventBusSubscriber " + simpleName(className)
					+ " could not be registered — its listeners will not run");
		}
	}

	private static String causeChain(Throwable real) {
		StringBuilder chain = new StringBuilder();
		for (Throwable x = real; x != null; x = x.getCause()) chain.append("\n      caused by: ").append(x);
		return chain.toString();
	}

	/** Whether the catalogue says {@code modId} is FAILED — its constructor or entrypoint threw. Unknown ids are not. */
	static boolean didNotFinishLoading(String modId) {
		if (modId == null) return false;
		// By JAR, not by id: wthit's one jar declares "waila" (whose class threw and left Waila half-initialised)
		// and "wthit" (whose client-only class constructed), and the subscriber that touched Waila belongs to
		// "wthit". A jar with a failed mod is a jar whose classes cannot be trusted to have initialised.
		String jar = null;
		for (ModCatalog.Entry e : ModCatalog.everything()) {
			if (e.modId().equals(modId)) {
				if (e.status() == ModCatalog.Status.FAILED) return true;
				jar = e.jar();
			}
		}
		if (jar == null || jar.isEmpty()) return false;
		for (ModCatalog.Entry e : ModCatalog.everything()) {
			if (jar.equals(e.jar()) && e.status() == ModCatalog.Status.FAILED) return true;
		}
		return false;
	}

	private static String simpleName(String className) {
		int dot = Math.max(className.lastIndexOf('.'), className.lastIndexOf('$'));
		return dot < 0 ? className : className.substring(dot + 1);
	}

	/** The mod bus of the NeoForge mod {@code modId}, or null when it was never published. */
	private static Object neoModBus(String modId) {
		if (modId == null) return null;
		KernelModLoader.NeoIdentity id = KernelModLoader.publishedNeoMods().get(modId);
		return id == null ? null : id.bus();
	}

	/** The container of the NeoForge mod {@code modId}, or null when it was never published. */
	private static Object neoModContainer(String modId) {
		if (modId == null) return null;
		KernelModLoader.NeoIdentity id = KernelModLoader.publishedNeoMods().get(modId);
		return id == null ? null : id.container();
	}

	/**
	 * The mod that owns {@code sub}: its declared {@code modid()}, else the jar's single distinct {@code @Mod} id of
	 * the same family. Returns null when the jar declares several ids and the annotation named none — guessing would attach a
	 * mod's listeners to a sibling's bus.
	 *
	 * <p>A NeoForge jar with no {@code @Mod} class at all falls back to its single mod that has none: FML gives that
	 * mod a container, and the container injecting an unnamed subscriber is its owner (its mod-bus listeners then
	 * reach that mod's bus, as on NeoForge, rather than no bus). {@code -Dforbric.classlessModContainers=off}
	 * builds no such container, so the fallback finds none.
	 *
	 * @param soleClasslessNeo the jar's one NeoForge mod with no {@code @Mod} class, or null; asked only when the
	 *                         fallback is reached
	 */
	static String ownerModId(Subscriber sub, List<ModAnnotationScanner.ModClassInfo> modsInJar,
			java.util.function.Supplier<String> soleClasslessNeo) {
		if (sub.modId() != null && !sub.modId().isBlank()) return sub.modId();
		String only = null;
		if (modsInJar != null) {
			for (ModAnnotationScanner.ModClassInfo info : modsInJar) {
				if (info.family != sub.family() || info.modId == null) continue;
				// NeoForge can declare a common and a client @Mod entry class for the SAME id.
				// Ownership is ambiguous only between distinct mods, not between their entry classes.
				if (only != null && !only.equals(info.modId)) return null;
				only = info.modId;
			}
		}
		if (only != null || sub.family() != Ecosystem.NEOFORGE) return only;
		return soleClasslessNeo.get();
	}

	private static List<ModAnnotationScanner.ModClassInfo> scanModClasses(Path jar) {
		try {
			return ModAnnotationScanner.scan(jar);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/EBS] could not scan %s for @Mod classes; its subscribers get no owner from it",
					jar.getFileName());
			return List.of();
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
			} catch (ClassNotFoundException absent) {
				Caught.expected(absent);
				ForbricLog.debug("[Forbric/EBS] NeoForge bus API absent");
				return null;
			} catch (Throwable t) {
				Caught.warn(t, "[Forbric/EBS] NeoForge bus API is present but unusable; no NeoForge "
						+ "@EventBusSubscriber is wired");
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
	public static void registerNeoForgeInternal(ClassLoader cl, List<Path> jars, Object modBus, Object container,
			Side side) {
		NeoBusApi api = NeoBusApi.resolve(cl);
		if (api == null) {
			ForbricLog.debug("[Forbric/EBS] NeoForge bus API absent — skipping internal @EventBusSubscriber scan");
			return;
		}

		java.util.concurrent.Executor workers = NeoParallelDispatch.parallelExecutor(cl);
		int wired = 0;
		int skippedSide = 0;
		for (Path jar : jars) {
			for (Subscriber sub : scan(jar)) {
				if (sub.family() != Ecosystem.NEOFORGE) continue;
				if (!matchesSide(sub.dists(), side)) {
					skippedSide++;
					continue;
				}
				if (NEO_INTERNAL_SKIP.contains(sub.className())) {
					ForbricLog.info("[Forbric/EBS] skipping NeoForge-internal %s — needs ecosystem client assets the "
							+ "kernel does not serve yet (see NEO_INTERNAL_SKIP)", sub.className());
					continue;
				}
				try {
					wired += wireNeoSubscriber(cl, sub.className(), modBus, container, workers, api);
				} catch (Throwable t) {
					Caught.warn(t, "[Forbric/EBS] could not wire NeoForge-internal %s; its listeners will not run",
							sub.className());
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
	 * <p>On NeoForge's {@code modloading-worker} pool ({@code workers}) with the owning mod's container active there, where
	 * {@code FMLModContainer.constructMod} injects a mod's subscribers natively: the class is loaded and initialised, and
	 * its listeners registered, on that thread. A subscriber's static initialiser saw {@code main} (the render thread on a
	 * client) and {@code "minecraft"} in {@code ModLoadingContext}; natively it sees a {@code modloading-worker} and its
	 * own mod. The caller waits, and what this throws reaches it as before.
	 *
	 * @param modBus    the owning mod's bus, or null when it has none; mod-bus listeners are then skipped
	 * @param container the owning mod's container, active while its class loads; null when it has none
	 * @param workers   NeoForge's modloading-worker pool, or null to do it on this thread
	 * @return how many listener methods were wired
	 */
	private static int wireNeoSubscriber(ClassLoader cl, String className, Object modBus, Object container,
			java.util.concurrent.Executor workers, NeoBusApi api) throws Exception {
		int[] wired = {0};
		try {
			NeoParallelDispatch.runOne(workers, className, className, name -> {
				if (container != null) KernelModLoader.setNeoActiveContainer(cl, container);
				try {
					wired[0] = wireNeoSubscriberHere(cl, name, modBus, api);
				} finally {
					if (container != null) KernelModLoader.setNeoActiveContainer(cl, null);
				}
			});
		} catch (Exception | Error direct) {
			throw direct;
		} catch (Throwable other) {
			throw new IllegalStateException(other);
		}
		return wired[0];
	}

	private static int wireNeoSubscriberHere(ClassLoader cl, String className, Object modBus, NeoBusApi api)
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
	 * ASM-scans a jar for NeoForge {@code @EventBusSubscriber} classes, keeping the attributes that decide routing.
	 */
	static List<Subscriber> scan(Path jar) {
		List<Subscriber> found = new ArrayList<>();
		try (FileSystem fs = FileSystems.newFileSystem(jar, (ClassLoader) null)) {
			for (Path root : fs.getRootDirectories()) {
				try (Stream<Path> walk = Files.walk(root)) {
					walk.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
						byte[] bytes = read(p);
						if (bytes == null) return;
						Subscriber s = scanClassBytes(bytes);
						if (s != null) found.add(s);
					});
				}
			}
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/EBS] could not scan %s for @EventBusSubscriber classes; %d found before the "
					+ "failure are kept", jar.getFileName(), found.size());
		}
		return found;
	}

	static Subscriber scanClass(Path classFile) {
		byte[] bytes = read(classFile);
		return bytes == null ? null : scanClassBytes(bytes);
	}

	private static byte[] read(Path classFile) {
		try {
			return Files.readAllBytes(classFile);
		} catch (Throwable unreadable) {
			Caught.warn(unreadable, "[Forbric/EBS] could not read %s; it is not scanned for @EventBusSubscriber",
					classFile);
			return null;
		}
	}

	/** The scan proper, split out so tests can drive it from bytes without a jar on disk; null for no subscriber. */
	static Subscriber scanClassBytes(byte[] bytes) {
		try {
			String[] name = new String[1];
			boolean[] subscriber = new boolean[1];
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
					if (!EBS_NEO.equals(descriptor)) return null;
					subscriber[0] = true;
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

			// Absent bus() is Bus.BOTH, the default that routes per event type.
			return !subscriber[0] ? null : new Subscriber(name[0].replace('/', '.'), Ecosystem.NEOFORGE, dists, modId[0],
					bus[0] == null ? "BOTH" : bus[0]);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/EBS] could not parse a class for @EventBusSubscriber; it is skipped");
			return null;
		}
	}
}
