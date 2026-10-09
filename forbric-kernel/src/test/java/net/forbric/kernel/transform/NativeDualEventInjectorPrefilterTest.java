/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import net.forbric.api.NativeEventDelivery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The injector runs for every class the game loads, so a class that cannot declare or call any registered source hook
 * is handed back before it is parsed, and the SDK classes a proof reads are parsed once per run. Names here are
 * invented: the prefilter keys on the registered contract, never on who calls it.
 */
class NativeDualEventInjectorPrefilterTest {
    private static final String ID = "test:prefilter-observer", DESC = "(Ljava/lang/Object;Ljava/lang/Object;)V";
    private static final String ROOT = "later.signal.";

    private static Map<String, byte[]> classes(Path work) throws Exception {
        NativeEventDelivery.register(new NativeEventDelivery.Contract(ID,
                new NativeEventDelivery.Hook("later/signal/Origin", "announce", DESC),
                new NativeEventDelivery.Hook("later/signal/Mirror", "announce", DESC), "later/signal/Notice",
                new NativeEventDelivery.Hook("later/signal/Wire", "send", "(Llater/signal/Notice;)Llater/signal/Notice;")));
        return InjectorExecution.compile(work, Map.of(
                ROOT + "Notice", "package later.signal; public final class Notice {public final Object a,b;public Notice(Object a,Object b){this.a=a;this.b=b;}}",
                ROOT + "Wire", "package later.signal; public interface Wire {Notice send(Notice notice);}",
                ROOT + "Mirror", "package later.signal; public final class Mirror {public static int count;public static void announce(Object a,Object b){count++;}}",
                ROOT + "Origin", """
                        package later.signal;
                        public final class Origin {
                            public static final Wire WIRE=notice->notice;
                            public static void announce(Object a,Object b){WIRE.send(new Notice(a,b));}
                        }
                        """,
                // The proved pair: the counterpart completes, then the source hook with the same operands.
                ROOT + "Relay", "package later.signal; public final class Relay {public static void run(Object a,Object b){Mirror.announce(a,b);Origin.announce(a,b);}}",
                ROOT + "Relay2", "package later.signal; public final class Relay2 {public static void run(Object a,Object b){Mirror.announce(a,b);Origin.announce(a,b);}}",
                // Look-alikes: the counterpart alone; the hook's name and descriptor on another owner; the hook named
                // only as text. None of them may change.
                ROOT + "Bystander", "package later.signal; public final class Bystander {public static void run(Object a,Object b){Mirror.announce(a,b);}}",
                ROOT + "Echo", "package later.signal; public final class Echo {public static void announce(Object a,Object b){}}",
                ROOT + "Imitator", "package later.signal; public final class Imitator {public static void run(Object a,Object b){Mirror.announce(a,b);Echo.announce(a,b);}}",
                ROOT + "Quoted", """
                        package later.signal;
                        public final class Quoted {
                            public static String[] words(){return new String[]{"later/signal/Origin","announce","(Ljava/lang/Object;Ljava/lang/Object;)V"};}
                        }
                        """), List.of(Path.of(NativeEventDelivery.class.getProtectionDomain().getCodeSource().getLocation().toURI())));
    }

    /** Resource lookups the injector made, by path. */
    private static Function<String, byte[]> counting(Map<String, byte[]> classes, Map<String, Integer> reads) {
        return path -> {
            reads.merge(path, 1, Integer::sum);
            return classes.get(path.substring(0, path.length() - ".class".length()));
        };
    }

    private static byte[] run(NativeDualEventInjector injector, Map<String, byte[]> classes, String simple) {
        return injector.transform(ROOT + simple, classes.get("later/signal/" + simple), null);
    }

    @Test
    void aClassThatCannotNameASourceHookIsReturnedUnparsed(@TempDir Path work) throws Exception {
        Map<String, byte[]> classes = classes(work);
        Map<String, Integer> reads = new HashMap<>();
        NativeDualEventInjector injector = new NativeDualEventInjector(counting(classes, reads));
        for (String simple : List.of("Bystander", "Echo", "Imitator", "Mirror", "Notice", "Wire")) {
            byte[] input = classes.get("later/signal/" + simple);
            assertSame(input, run(injector, classes, simple), simple + " is returned untouched");
        }
        assertEquals(0, injector.classesParsed(), "none of them was parsed");
        assertEquals(Map.of(), reads, "and no SDK class was read for them");
    }

    @Test
    void aClassThatOnlyQuotesTheHookPassesThePrefilterAndTheProofStillLeavesIt(@TempDir Path work) throws Exception {
        Map<String, byte[]> classes = classes(work);
        NativeDualEventInjector injector = new NativeDualEventInjector(counting(classes, new HashMap<>()));
        byte[] input = classes.get("later/signal/Quoted");
        assertSame(input, run(injector, classes, "Quoted"));
        assertEquals(1, injector.classesParsed(), "the prefilter is conservative: text that names the hook is parsed");
    }

    @Test
    void theProvedCallerAndTheSourceHookAreStillRewritten(@TempDir Path work) throws Exception {
        Map<String, byte[]> classes = classes(work);
        NativeDualEventInjector injector = new NativeDualEventInjector(counting(classes, new HashMap<>()));
        byte[] relay = classes.get("later/signal/Relay"), origin = classes.get("later/signal/Origin");
        byte[] relayOut = run(injector, classes, "Relay"), originOut = run(injector, classes, "Origin");
        assertNotSame(relay, relayOut, "the proved caller is wrapped");
        assertNotSame(origin, originOut, "the source hook captures its event");
        assertEquals(2, injector.classesParsed());
        // A run's cached SDK parses give exactly what a fresh instance (nothing cached) gives.
        assertArrayEquals(relayOut, run(new NativeDualEventInjector(counting(classes, new HashMap<>())), classes, "Relay"));
        assertArrayEquals(originOut, run(new NativeDualEventInjector(counting(classes, new HashMap<>())), classes, "Origin"));
    }

    @Test
    void eachSdkClassIsParsedOncePerRun(@TempDir Path work) throws Exception {
        Map<String, byte[]> classes = classes(work);
        Map<String, Integer> reads = new HashMap<>();
        NativeDualEventInjector injector = new NativeDualEventInjector(counting(classes, reads));
        for (String simple : List.of("Relay", "Relay2", "Relay", "Quoted", "Origin"))
            assertNotNull(run(injector, classes, simple));
        assertEquals(1, reads.get("later/signal/Origin.class"), "source owner: " + reads);
        assertEquals(1, reads.get("later/signal/Mirror.class"), "counterpart owner: " + reads);
    }
}
