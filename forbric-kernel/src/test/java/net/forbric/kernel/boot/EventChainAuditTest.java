package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.jar.JarFile;
import net.forbric.kernel.transform.EventChainAuditInjector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The audit on the REAL buses: NeoForge's EventBus and MinecraftForge's bus records from the staged runtime jars,
 * rewritten by {@link EventChainAuditInjector}, with a NeoForge listener standing in for a kernel bridge.
 */
@ResourceLock("system-properties")
class EventChainAuditTest {
	private static final String NEO_PROBE = "probe.NeoProbe", FORGE_PROBE = "probe.ForgeProbe", MOD_CODE = "probe.ModCode";
	private Object neoBus, forgeBus;
	private Class<?> neoProbe, forgeProbe;
	private Method post, fire, setCanceled;

	@BeforeEach void buses() throws Exception {
		System.setProperty(EventChainAudit.PROPERTY, "unused-in-tests.json");
		EventChainAudit.reset();
		Path staged = Path.of(System.getProperty("forbric.stagedRoot"));
		String log4j = System.getProperty("forbric.log4jApiForTests", "");
		assertFalse(log4j.isBlank(), "the build passes the log4j-api jar the real buses need");
		var loader = new AuditedBuses(List.of(staged.resolve("neoforge-runtime/neoforge-runtime.jar"), staged.resolve("forge-runtime/forge-runtime.jar"), Path.of(log4j)));
		neoProbe = loader.loadClass(NEO_PROBE); forgeProbe = loader.loadClass(FORGE_PROBE);
		neoBus = loader.loadClass("net.neoforged.bus.api.BusBuilder").getMethod("builder").invoke(null);
		neoBus = neoBus.getClass().getMethod("build").invoke(neoBus);
		Class<?> cancellableBus = loader.loadClass("net.minecraftforge.eventbus.api.bus.CancellableEventBus");
		forgeBus = cancellableBus.getMethod("create", Class.class).invoke(null, forgeProbe);
		post = cancellableBus.getMethod("post", loader.loadClass("net.minecraftforge.eventbus.internal.Event"));
		fire = cancellableBus.getMethod("fire", loader.loadClass("net.minecraftforge.eventbus.internal.Event"));
		setCanceled = loader.loadClass("net.neoforged.bus.api.ICancellableEvent").getMethod("setCanceled", boolean.class);
	}

	@AfterEach void off() { System.clearProperty(EventChainAudit.PROPERTY); EventChainAudit.reset(); }

	@Test void aForwardedCancelThatReachesTheOuterEventIsCarried() throws Exception {
		forgeListener(true); bridge(1, true, false);
		postNeo();
		var pair = pair();
		assertEquals(1, pair.forwardedOnce.sum()); assertEquals(1, pair.innerCancelled.sum()); assertEquals(1, pair.cancelCarried.sum());
		assertTrue(EventChainAudit.violations().clean(), EventChainAudit.json());
	}

	@Test void aForwardedCancelTheBridgeDropsIsLost() throws Exception {
		forgeListener(true); bridge(1, false, false);
		postNeo();
		assertEquals(1, pair().cancelLost.sum());
		assertEquals(1, EventChainAudit.violations().cancelLost());
	}

	@Test void aBridgeInstalledTwiceIsDoubleDelivery() throws Exception {
		forgeListener(false); bridge(1, true, false); bridge(1, true, false);
		postNeo();
		assertEquals(1, pair().duplicated.sum());
		assertEquals(1, EventChainAudit.violations().duplicated());
	}

	@Test void aListenerThatAlwaysFansOutIsNotDoubleDelivery() throws Exception {
		forgeListener(false); bridge(5, true, true);
		postNeo(); postNeo(); postNeo();
		assertEquals(3, pair().multiInvocations.sum());
		assertTrue(EventChainAudit.violations().clean(), EventChainAudit.json());
	}

	@Test void aOneToOneForwardThatSometimesPostsTwiceDrifts() throws Exception {
		forgeListener(false);
		int[] forwards = {1};
		addNeo(event -> { for (int i = 0; i < forwards[0]; i++) invoke(post, forgeBus, forgeEvent()); });
		postNeo(); postNeo(); postNeo(); forwards[0] = 2; postNeo();
		assertEquals(1, EventChainAudit.violations().multiplicityDrift(), EventChainAudit.json());
		assertEquals(0, EventChainAudit.violations().duplicated());
	}

	@Test void aPostMadeByModCodeInsideTheDispatchIsIncidental() throws Exception {
		forgeListener(false);
		Method modPost = neoProbe.getClassLoader().loadClass(MOD_CODE).getMethod("post", Object.class, Object.class);
		addNeo(event -> invoke(modPost, null, forgeBus, forgeEvent()));
		postNeo();
		assertNull(EventChainAudit.pair("NEO:" + NEO_PROBE, "FORGE:" + FORGE_PROBE), EventChainAudit.json());
		assertEquals(1, EventChainAudit.incidental("NEO:" + NEO_PROBE, "FORGE:" + FORGE_PROBE));
	}

	@Test void aThrowingForwardIsAnInnerFailureAndStillPropagates() throws Exception {
		Predicate<Object> thrower = event -> { throw new IllegalStateException("listener failed"); };
		forgeBus.getClass().getMethod("addListener", Predicate.class).invoke(forgeBus, thrower);
		bridge(1, true, false);
		assertThrows(Exception.class, this::postNeo);
		assertEquals(1, pair().innerFailures.sum());
		assertEquals(0, EventChainAudit.violations().unbalanced(), "a throwing dispatch still leaves every frame it entered");
	}

	@Test void onceForwardedAnUnforwardedPostIsCounted() throws Exception {
		forgeListener(false);
		boolean[] skip = {false};
		Consumer<Object> bridge = event -> { if (!skip[0]) invoke(post, forgeBus, forgeEvent()); };
		addNeo(bridge);
		postNeo(); skip[0] = true; postNeo();
		var outer = EventChainAudit.outer("NEO:" + NEO_PROBE);
		assertEquals(2, outer.posts.sum()); assertEquals(1, outer.withoutForward.sum());
	}

	@Test void nothingCrossesWhenNoBridgeIsInstalled() throws Exception {
		forgeListener(false);
		postNeo(); invoke(post, forgeBus, forgeEvent());
		assertEquals(0, EventChainAudit.pairCount());
		assertTrue(EventChainAudit.violations().clean());
	}

	private EventChainAudit.Pair pair() {
		var pair = EventChainAudit.pair("NEO:" + NEO_PROBE, "FORGE:" + FORGE_PROBE);
		assertNotNull(pair, EventChainAudit.json());
		return pair;
	}

	private void forgeListener(boolean cancel) throws Exception {
		Predicate<Object> listener = event -> cancel;
		forgeBus.getClass().getMethod("addListener", Predicate.class).invoke(forgeBus, listener);
	}

	/** A NeoForge listener shaped like the kernel's bridges: post MinecraftForge's event, carry its cancel back. */
	private void bridge(int forwards, boolean carry, boolean viaFire) throws Exception {
		addNeo(event -> {
			for (int i = 0; i < forwards; i++) {
				Object forge = forgeEvent();
				boolean cancelled = viaFire ? false : (boolean) invoke(post, forgeBus, forge);
				if (viaFire) invoke(fire, forgeBus, forge);
				if (cancelled && carry) invoke(setCanceled, event, true);
			}
		});
	}

	private void addNeo(Consumer<Object> listener) throws Exception {
		Class<?> priority = neoBus.getClass().getClassLoader().loadClass("net.neoforged.bus.api.EventPriority");
		Object lowest = priority.getField("LOWEST").get(null);
		neoBus.getClass().getMethod("addListener", priority, boolean.class, Class.class, Consumer.class).invoke(neoBus, lowest, false, neoProbe, listener);
	}

	private void postNeo() throws Exception {
		Method neoPost = neoBus.getClass().getMethod("post", neoBus.getClass().getClassLoader().loadClass("net.neoforged.bus.api.Event"));
		neoPost.invoke(neoBus, neoProbe.getConstructor().newInstance());
	}

	private Object forgeEvent() {
		try { return forgeProbe.getConstructor().newInstance(); } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
	}

	private static Object invoke(Method method, Object target, Object... args) {
		try { return method.invoke(target, args); }
		catch (java.lang.reflect.InvocationTargetException thrown) {
			if (thrown.getCause() instanceof RuntimeException runtime) throw runtime;
			throw new IllegalStateException(thrown.getCause());
		} catch (IllegalAccessException e) { throw new IllegalStateException(e); }
	}

	/** Child-first over the two runtime jars, with the three bus classes rewritten and the two probe events generated. */
	private static final class AuditedBuses extends ClassLoader {
		private final List<JarFile> jars = new ArrayList<>();
		private final Map<String, byte[]> generated = new HashMap<>();
		AuditedBuses(List<Path> jarPaths) throws IOException {
			super(EventChainAuditTest.class.getClassLoader());
			// Multi-release: log4j-api keeps its Java 9+ StackLocator under META-INF/versions.
			for (Path path : jarPaths) jars.add(new JarFile(path.toFile(), true, java.util.zip.ZipFile.OPEN_READ, Runtime.version()));
			generated.put(NEO_PROBE, event("probe/NeoProbe", "net/neoforged/bus/api/Event", "net/neoforged/bus/api/ICancellableEvent"));
			generated.put(FORGE_PROBE, event("probe/ForgeProbe", "net/minecraftforge/eventbus/api/event/MutableEvent",
					"net/minecraftforge/eventbus/api/event/characteristic/Cancellable"));
			generated.put(MOD_CODE, modCode());
		}
		@Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			synchronized (getClassLoadingLock(name)) {
				Class<?> loaded = findLoadedClass(name);
				if (loaded != null) return loaded;
				byte[] bytes = generated.get(name);
				if (bytes == null) bytes = fromJars(name);
				if (bytes == null) return super.loadClass(name, resolve);
				if (name.equals("net.neoforged.bus.EventBus")) bytes = EventChainAuditInjector.rewrite(bytes, true, false);
				if (name.equals("net.minecraftforge.eventbus.internal.CancellableEventBusImpl")) bytes = EventChainAuditInjector.rewrite(bytes, false, true);
				if (name.equals("net.minecraftforge.eventbus.internal.EventBusImpl")) bytes = EventChainAuditInjector.rewrite(bytes, false, false);
				return defineClass(name, bytes, 0, bytes.length);
			}
		}
		private byte[] fromJars(String name) {
			String entry = name.replace('.', '/') + ".class";
			for (JarFile jar : jars) {
				var found = jar.getJarEntry(entry);
				if (found == null) continue;
				try (var in = jar.getInputStream(found)) { return in.readAllBytes(); } catch (IOException e) { throw new IllegalStateException(e); }
			}
			return null;
		}
		/** A mod's own class posting to MinecraftForge's bus: not kernel code. */
		private static byte[] modCode() {
			ClassWriter writer = new ClassWriter(0);
			writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "probe/ModCode", null, "java/lang/Object", null);
			MethodVisitor post = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "post", "(Ljava/lang/Object;Ljava/lang/Object;)V", null, null);
			post.visitCode();
			post.visitVarInsn(Opcodes.ALOAD, 0); post.visitTypeInsn(Opcodes.CHECKCAST, "net/minecraftforge/eventbus/api/bus/CancellableEventBus");
			post.visitVarInsn(Opcodes.ALOAD, 1); post.visitTypeInsn(Opcodes.CHECKCAST, "net/minecraftforge/eventbus/internal/Event");
			post.visitMethodInsn(Opcodes.INVOKEINTERFACE, "net/minecraftforge/eventbus/api/bus/CancellableEventBus", "post", "(Lnet/minecraftforge/eventbus/internal/Event;)Z", true);
			post.visitInsn(Opcodes.POP); post.visitInsn(Opcodes.RETURN); post.visitMaxs(2, 2); post.visitEnd(); writer.visitEnd();
			return writer.toByteArray();
		}
		private static byte[] event(String name, String superName, String marker) {
			ClassWriter writer = new ClassWriter(0);
			writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, superName, new String[] {marker});
			MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
			init.visitCode(); init.visitVarInsn(Opcodes.ALOAD, 0); init.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
			init.visitInsn(Opcodes.RETURN); init.visitMaxs(1, 1); init.visitEnd(); writer.visitEnd();
			return writer.toByteArray();
		}
	}
}
