package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The listeners an annotation scan cannot see.
 *
 * <p>DeadEventAudit's input is {classes with a class-level @EventBusSubscriber} × {@SubscribeEvent methods},
 * read with SKIP_CODE. A listener registered with {@code bus.addListener(...)} lives in a method body, so the
 * jars that register that way are invisible to the audit — and reporting nothing reads exactly like nothing
 * being wrong.
 */
class ForgeBusSubscriptionsTest {

	/** MinecraftForge's BusGroupImpl, in the one respect this reads. */
	public static final class BusGroupShaped {
		public final Map<Class<?>, Object> eventBuses = new LinkedHashMap<>();
	}

	/** Same field, one class further up, because the real one is not flat either. */
	public static class Base {
		protected final Map<Class<?>, Object> eventBuses = new LinkedHashMap<>();
	}

	public static final class Derived extends Base {
	}

	@Test void theEventsABusGroupHasBusesForAreItsSubscriptions() {
		BusGroupShaped group = new BusGroupShaped();
		group.eventBuses.put(String.class, new Object());
		group.eventBuses.put(Integer.class, new Object());
		assertEquals(Set.of("java/lang/Integer", "java/lang/String"), ForgeBusSubscriptions.eventsOf(group));
	}

	@Test void theFieldIsFoundOnASuperclassToo() {
		Derived derived = new Derived();
		derived.eventBuses.put(Long.class, new Object());
		assertEquals(Set.of("java/lang/Long"), ForgeBusSubscriptions.eventsOf(derived));
	}

	@Test void anythingItCannotReadIsEmptyAndNotAnException() {
		// It runs inside an audit that exists to add information. An audit that can fail a boot is worse than
		// one that is incomplete.
		assertTrue(ForgeBusSubscriptions.eventsOf(null).isEmpty());
		assertTrue(ForgeBusSubscriptions.eventsOf("not a bus group").isEmpty());
		assertTrue(ForgeBusSubscriptions.byMod(null).isEmpty());
	}

	@Test void eachModsEventsAreAttributedToThatMod() {
		BusGroupShaped a = new BusGroupShaped();
		a.eventBuses.put(String.class, new Object());
		BusGroupShaped b = new BusGroupShaped();
		b.eventBuses.put(Integer.class, new Object());
		Map<String, KernelForgeModContext.Handle> mods = new LinkedHashMap<>();
		mods.put("alpha", new KernelForgeModContext.Handle("alpha", a, null, null));
		mods.put("beta", new KernelForgeModContext.Handle("beta", b, null, null));
		mods.put("silent", new KernelForgeModContext.Handle("silent", new BusGroupShaped(), null, null));

		Map<String, Set<String>> byMod = ForgeBusSubscriptions.byMod(mods);
		assertEquals(Set.of("java/lang/String"), byMod.get("alpha"));
		assertEquals(Set.of("java/lang/Integer"), byMod.get("beta"));
		// Attribution is the whole reason to read the per-mod group rather than the shared game bus; a mod with
		// no listeners must not appear at all, or the audit starts naming mods that are waiting for nothing.
		assertTrue(!byMod.containsKey("silent"), byMod.toString());
	}

	@Test void mergingKeepsWhatTheAnnotationScanAlreadyFound() {
		Map<String, Set<String>> scanned = Map.of("alpha", Set.of("forge/AnnotatedEvent"));
		Map<String, Set<String>> fromBuses = Map.of("alpha", Set.of("forge/AddListenerEvent"),
				"beta", Set.of("forge/Other"));
		Map<String, Set<String>> merged = ForgeBusSubscriptions.merge(scanned, fromBuses);
		assertEquals(Set.of("forge/AnnotatedEvent", "forge/AddListenerEvent"), merged.get("alpha"));
		assertEquals(Set.of("forge/Other"), merged.get("beta"));
	}
}
