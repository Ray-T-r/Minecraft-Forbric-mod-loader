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

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.zip.ZipFile;

import net.forbric.api.Ecosystem;
import net.forbric.api.Side;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;

/** Registers the Forge carrier's own subscribers, without re-posting any client or gameplay event. */
public final class KernelForgeInternalSubscribers {
    static final String FML_LOGIC = "net.minecraftforge.fml.javafmlmod.AutomaticEventSubscriber$EventBusSubscriberLogic";
    private static final Map<ClassLoader, Set<String>> ATTEMPTED = new WeakHashMap<>();

    private KernelForgeInternalSubscribers() {}

    /** Counts classes, not callback invocations. Failed partial registrations are deliberately not retried. */
    public record Result(int registered, int alreadyAttempted, int wrongSide, int foreignOwner,
            int missingModBus, int failed) {}

    /** Today's internal Forge subscribers use global event buses; no manufactured baseline context is needed. */
    public static Result register(ClassLoader loader, List<Path> runtimeJars, Side side) {
        return register(loader, runtimeJars, side, null);
    }

    /** Optional genuine baseline context for a future carrier's explicit MOD or mod-event AUTO listeners. */
    public static Result register(ClassLoader loader, List<Path> runtimeJars, Side side,
            KernelForgeModContext.Handle baseline) {
        if ("off".equalsIgnoreCase(System.getProperty("forbric.forgeInternalSubscribers"))) {
            ForbricLog.info("[Forbric/EBS] Forge-internal subscribers disabled by forbric.forgeInternalSubscribers=off");
            return new Result(0, 0, 0, 0, 0, 0);
        }
        if (baseline != null && !"forge".equals(baseline.modId())) {
            throw new IllegalArgumentException("the internal Forge subscribers require the forge baseline context");
        }
        List<KernelEventSubscribers.Subscriber> subscribers = scan(runtimeJars);
        Set<String> attempted;
        synchronized (ATTEMPTED) { attempted = ATTEMPTED.computeIfAbsent(loader, ignored -> new LinkedHashSet<>()); }
        Result result = registerSelected(subscribers, side, baseline != null, attempted,
                new NativeRegistrar(loader, baseline));
        ForbricLog.info("[Forbric/EBS] registered %d Forge-internal @EventBusSubscriber class(es); "
                        + "%d already attempted, %d wrong-side, %d foreign-owner/family, %d missing mod bus, %d failed",
                result.registered(), result.alreadyAttempted(), result.wrongSide(), result.foreignOwner(),
                result.missingModBus(), result.failed());
        return result;
    }

    /** Reuses the guest scanner's family/side/bus decoding; class loading happens only after selection. */
    static List<KernelEventSubscribers.Subscriber> scan(List<Path> runtimeJars) {
        Map<String, KernelEventSubscribers.Subscriber> unique = new LinkedHashMap<>();
        for (Path jar : runtimeJars) {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                for (var entry : zip.stream().filter(e -> e.getName().endsWith(".class"))
                        .sorted(java.util.Comparator.comparing(java.util.zip.ZipEntry::getName)).toList()) {
                    try (var stream = zip.getInputStream(entry)) {
                        var subscriber = KernelEventSubscribers.scanClassBytes(stream.readAllBytes());
                        if (subscriber != null) unique.putIfAbsent(subscriber.family() + ":" + subscriber.className(), subscriber);
                    }
                }
            } catch (Exception failure) {
                ForbricLog.warn("[Forbric/EBS] could not scan Forge-internal subscribers in " + jar, failure);
            }
        }
        return List.copyOf(unique.values());
    }

    @FunctionalInterface
    interface Registrar {
        void register(KernelEventSubscribers.Subscriber subscriber, KernelEventSubscribers.BusChoice bus) throws Exception;
    }

    /** A complete class is marked before native registration, which can fail after wiring only some methods. */
    static Result registerSelected(List<KernelEventSubscribers.Subscriber> subscribers, Side side,
            boolean hasModBus, Set<String> attempted, Registrar registrar) {
        int registered = 0, duplicate = 0, wrongSide = 0, foreign = 0, noBus = 0, failed = 0;
        for (var subscriber : subscribers) {
            if (subscriber.family() != Ecosystem.FORGE || !"forge".equals(subscriber.modId())) {
                foreign++;
                continue;
            }
            if (!KernelEventSubscribers.matchesSide(subscriber.dists(), side)) {
                wrongSide++;
                continue;
            }
            var bus = KernelEventSubscribers.busGroupChoice(subscriber.bus(), hasModBus);
            if (bus == KernelEventSubscribers.BusChoice.SKIP) {
                noBus++;
                ForbricLog.warn("[Forbric/EBS] Forge-internal %s requires the forge mod bus, but its baseline "
                        + "context is absent; not registering it on the wrong bus", subscriber.className());
                continue;
            }
            synchronized (attempted) {
                if (!attempted.add(subscriber.className())) { duplicate++; continue; }
                try {
                    registrar.register(subscriber, bus);
                    registered++;
                } catch (Exception | LinkageError failure) {
                    failed++;
                    ForbricLog.warn("[Forbric/EBS] could not register Forge-internal " + subscriber.className()
                            + "; not retrying a possibly partial class registration", Reflect.unwrap(failure));
                }
            }
        }
        return new Result(registered, duplicate, wrongSide, foreign, noBus, failed);
    }

    /** The native per-method registrar supports one-listener classes and Forge's own event validation rules. */
    private static final class NativeRegistrar implements Registrar {
        private final ClassLoader loader;
        private final KernelForgeModContext.Handle baseline;
        private Method register;
        private Object defaultGroup;

        NativeRegistrar(ClassLoader loader, KernelForgeModContext.Handle baseline) {
            this.loader = loader;
            this.baseline = baseline;
        }

        @Override
        public void register(KernelEventSubscribers.Subscriber subscriber, KernelEventSubscribers.BusChoice bus) throws Exception {
            if (register == null) {
                Class<?> groupType = Class.forName("net.minecraftforge.eventbus.api.bus.BusGroup", false, loader);
                defaultGroup = groupType.getField("DEFAULT").get(null);
                Class<?> logic = Class.forName(FML_LOGIC, false, loader);
                register = logic.getMethod("register", groupType, Class.class);
                register.setAccessible(true);
            }
            Object group = switch (bus) {
                case DEFAULT -> defaultGroup;
                case MOD -> baseline.busGroup();
                case AUTO -> null; // FML chooses DEFAULT or the active mod's group from each event type.
                case SKIP -> throw new IllegalStateException("unselected subscriber reached the native registrar");
            };
            Object previous = null;
            Object contextInstance = null;
            Method activate = null;
            if (baseline != null) {
                Class<?> context = Class.forName("net.minecraftforge.fml.ModLoadingContext", false, loader);
                contextInstance = context.getMethod("get").invoke(null);
                var active = context.getDeclaredField("activeContainer");
                active.setAccessible(true);
                previous = active.get(contextInstance);
                Class<?> container = Class.forName("net.minecraftforge.fml.ModContainer", false, loader);
                activate = context.getDeclaredMethod("setActiveContainer", container);
                activate.setAccessible(true);
            }
            try {
                if (activate != null) activate.invoke(contextInstance, baseline.container());
                Class<?> type = Class.forName(subscriber.className(), true, loader);
                register.invoke(null, group, type);
            } finally {
                if (activate != null) activate.invoke(contextInstance, previous);
            }
        }
    }
}
