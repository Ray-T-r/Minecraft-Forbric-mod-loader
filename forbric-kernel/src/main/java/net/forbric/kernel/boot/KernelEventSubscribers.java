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

import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.util.ForbricLog;

/**
 * Registers a Forge-family mod's {@code @Mod$EventBusSubscriber} classes on the game event bus — the kernel's
 * native replacement for FML's {@code AutomaticEventSubscriber}.
 *
 * <p>Many Forge mods declare their game-event listeners not in the {@code @Mod} constructor but on a separate
 * class annotated {@code @Mod.EventBusSubscriber} whose static {@code @SubscribeEvent} methods FML auto-registers
 * during mod loading. The kernel does no FML scan, so those listeners would never fire (e.g. a mod's
 * {@code ServerTickEvent} handler). This ASM-scans each mod jar for the annotation and registers each such class
 * on {@code BusGroup.DEFAULT} (the game bus) via {@code BusGroup.register(Lookup, Class)}.
 *
 * <p>Scope note: the {@code bus} attribute (GAME vs MOD) and side filtering are approximated — all subscribers go
 * to the game bus, which is where tick/lifecycle listeners live. MOD-bus subscribers (setup events) are a later
 * refinement.
 */
public final class KernelEventSubscribers {
	private static final String EBS_FORGE = "Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber;";
	private static final String EBS_NEO = "Lnet/neoforged/fml/common/EventBusSubscriber;";

	private KernelEventSubscribers() {
	}

	/** Scans + registers every {@code @EventBusSubscriber} class in {@code modJars} on the Forge game bus. */
	public static void registerAll(ClassLoader cl, List<Path> modJars) {
		Object gameBus;
		Object register;
		try {
			Class<?> busGroup = Class.forName("net.minecraftforge.eventbus.api.bus.BusGroup", false, cl);
			gameBus = busGroup.getField("DEFAULT").get(null);
			register = busGroup.getMethod("register", MethodHandles.Lookup.class, Class.class);
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/EBS] Forge BusGroup absent — skipping @EventBusSubscriber registration");
			return;
		}

		if (!(cl instanceof ForbricClassLoader)) {
			ForbricLog.debug("[Forbric/EBS] not running under the sovereign loader — skipping @EventBusSubscriber");
			return;
		}
		ForbricClassLoader loader = (ForbricClassLoader) cl;

		int total = 0;
		for (Path jar : modJars) {
			for (String className : scan(jar)) {
				try {
					Class<?> c = Class.forName(className, true, cl);
					// The Lookup MUST be a full-power, GAME-side lookup. Forge's EventBus spins the listener
					// dispatch with LambdaMetafactory (needs the MODULE bit) and resolves the @SubscribeEvent event
					// types through the lookup's loader. A boot-side MethodHandles.lookup() has the APP loader
					// (→ NoClassDefFoundError: TickEvent$ServerTickEvent$Post at post time), and teleporting it
					// across the boot→game boundary drops full power (→ LambdaConversionException). KernelGameLookup
					// mints the lookup from inside the game module so both problems vanish.
					MethodHandles.Lookup lookup = KernelGameLookup.privateLookupIn(c, loader);
					((java.lang.reflect.Method) register).invoke(gameBus, lookup, c);
					total++;
					ForbricLog.debug("[Forbric/EBS] registered @EventBusSubscriber %s on the game bus", className);
				} catch (Throwable t) {
					Throwable real = KernelBusSupport.unwrap(t);
					StringBuilder chain = new StringBuilder();
					for (Throwable x = real; x != null; x = x.getCause()) chain.append("\n      caused by: ").append(x);
					ForbricLog.warn("[Forbric/EBS] could not register " + className + chain, real);
				}
			}
		}
		if (total > 0) ForbricLog.info("[Forbric/EBS] registered %d @EventBusSubscriber class(es) on the game bus", total);
	}

	/** A NeoForge {@code @EventBusSubscriber} class + the {@code Dist}s it declares ({@code value()}, empty = all). */
	private record NeoSubscriber(String className, java.util.Set<String> dists) {
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
	 * <p>Separate from {@link #registerAll}: that one drives MinecraftForge's {@code BusGroup}, a different bus
	 * implementation that NeoForge subscribers cannot be registered on.
	 */
	public static void registerNeoForgeInternal(ClassLoader cl, List<Path> jars, Object modBus, boolean client) {
		Object gameBus;
		Class<?> subscribeEvent;
		Class<?> eventType;
		Class<?> modBusEventType;
		java.lang.reflect.Method register;
		try {
			gameBus = Class.forName("net.neoforged.neoforge.common.NeoForge", false, cl).getField("EVENT_BUS").get(null);
			subscribeEvent = Class.forName("net.neoforged.bus.api.SubscribeEvent", false, cl);
			eventType = Class.forName("net.neoforged.bus.api.Event", false, cl);
			modBusEventType = Class.forName("net.neoforged.fml.event.IModBusEvent", false, cl);
			register = Class.forName("net.neoforged.bus.api.IEventBus", false, cl).getMethod("register", Object.class);
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/EBS] NeoForge bus API absent — skipping internal @EventBusSubscriber scan");
			return;
		}

		String side = client ? "CLIENT" : "DEDICATED_SERVER";
		int wired = 0;
		int skippedSide = 0;
		for (Path jar : jars) {
			for (NeoSubscriber sub : scanNeo(jar)) {
				if (!sub.dists().isEmpty() && !sub.dists().contains(side)) {
					skippedSide++;
					continue;
				}
				if (NEO_INTERNAL_SKIP.contains(sub.className())) {
					ForbricLog.info("[Forbric/EBS] skipping NeoForge-internal %s — needs ecosystem client assets the "
							+ "kernel does not serve yet (see NEO_INTERNAL_SKIP)", sub.className());
					continue;
				}
				try {
					Class<?> owner = Class.forName(sub.className(), true, cl);
					for (java.lang.reflect.Method m : owner.getDeclaredMethods()) {
						@SuppressWarnings("unchecked")
						boolean subscribed = m.isAnnotationPresent(
								(Class<? extends java.lang.annotation.Annotation>) subscribeEvent);
						if (!subscribed || !java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
						if (m.getParameterCount() != 1) continue;
						Class<?> event = m.getParameterTypes()[0];
						if (!eventType.isAssignableFrom(event)) continue;

						Object bus = modBusEventType.isAssignableFrom(event) ? modBus : gameBus;
						if (bus == null) continue; // no baseline mod bus yet — mod-bus listeners can't be placed
						m.setAccessible(true); // NeoForge's own handlers are private static
						register.invoke(bus, m);
						wired++;
					}
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

	/** ASM-scans a jar for NeoForge {@code @EventBusSubscriber} classes, capturing the declared {@code Dist}s. */
	private static List<NeoSubscriber> scanNeo(Path jar) {
		List<NeoSubscriber> found = new ArrayList<>();
		try (FileSystem fs = FileSystems.newFileSystem(jar, (ClassLoader) null)) {
			for (Path root : fs.getRootDirectories()) {
				try (Stream<Path> walk = Files.walk(root)) {
					walk.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
						NeoSubscriber s = scanNeoClass(p);
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

	private static NeoSubscriber scanNeoClass(Path classFile) {
		try {
			byte[] bytes = Files.readAllBytes(classFile);
			String[] name = new String[1];
			boolean[] hit = new boolean[1];
			java.util.Set<String> dists = new java.util.LinkedHashSet<>();
			new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
				@Override
				public void visit(int v, int access, String n, String sig, String sup, String[] itf) {
					name[0] = n;
				}

				@Override
				public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
					if (!EBS_NEO.equals(descriptor)) return null;
					hit[0] = true;
					return new AnnotationVisitor(Opcodes.ASM9) {
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
			return hit[0] ? new NeoSubscriber(name[0].replace('/', '.'), dists) : null;
		} catch (Throwable t) {
			return null;
		}
	}

	/** ASM-scans a jar for classes carrying {@code @Mod$EventBusSubscriber} (Forge or NeoForge). */
	private static List<String> scan(Path jar) {
		List<String> found = new ArrayList<>();
		try (FileSystem fs = FileSystems.newFileSystem(jar, (ClassLoader) null)) {
			for (Path root : fs.getRootDirectories()) {
				try (Stream<Path> walk = Files.walk(root)) {
					walk.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
						String cn = scanClass(p);
						if (cn != null) found.add(cn);
					});
				}
			}
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/EBS] could not scan %s: %s", jar.getFileName(),
					String.valueOf(KernelBusSupport.unwrap(t)));
		}
		return found;
	}

	private static String scanClass(Path classFile) {
		try {
			byte[] bytes = Files.readAllBytes(classFile);
			String[] result = new String[1];
			new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
				private String name;

				@Override
				public void visit(int v, int access, String n, String sig, String sup, String[] itf) {
					this.name = n;
				}

				@Override
				public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
					if (EBS_FORGE.equals(descriptor) || EBS_NEO.equals(descriptor)) {
						result[0] = name.replace('/', '.');
					}
					return null;
				}
			}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			return result[0];
		} catch (Throwable t) {
			return null;
		}
	}
}
