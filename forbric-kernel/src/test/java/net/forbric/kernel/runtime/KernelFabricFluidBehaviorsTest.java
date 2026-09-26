package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * A tag a Fabric mod registered a fluid behaviour for answers NeoForge's (and MinecraftForge's) fluid-type questions
 * from that behaviour, over the COMPILED game-side classes, the staged game and fabric-api 0.161's own
 * content-registries module.
 */
@ResourceLock("system-properties")
class KernelFabricFluidBehaviorsTest {
	private static final Path MODS = Path.of("build/compat-inputs/sweep90/mods");
	@TempDir Path temp;
	private URLClassLoader loader;
	private Class<?> tagKey, entity, living, boat, behaviourType;
	private Object oil, tar;
	private final List<String> asked = new ArrayList<>();

	/** The game side over the staged game, with fabric-api's content-registries module on it or not. */
	private void load(boolean withFabricApi) throws Exception {
		Path api = MODS.resolve("fabric-api-0.161.0+26.2.jar");
		assumeTrue(Files.isRegularFile(api), "sweep pack absent");
		List<URL> extra = new ArrayList<>();
		if (withFabricApi) {
			Path registries = temp.resolve("fabric-content-registries.jar");
			try (ZipFile zip = new ZipFile(api.toFile())) {
				ZipEntry nested = zip.stream().filter(e -> e.getName().startsWith("META-INF/jars/fabric-content-registries-v0-")).findFirst().orElseThrow();
				Files.write(registries, zip.getInputStream(nested).readAllBytes());
			}
			extra.add(registries.toUri().toURL());
		}
		// The kernel's boot-side classes too: the game side logs through ForbricLog.
		extra.add(Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main").toUri().toURL());
		loader = StagedGameClassLoader.create(extra);
		tagKey = loader.loadClass("net.minecraft.tags.TagKey");
		entity = loader.loadClass("net.minecraft.world.entity.Entity");
		living = loader.loadClass("net.minecraft.world.entity.LivingEntity");
		boat = loader.loadClass("net.minecraft.world.entity.vehicle.boat.AbstractBoat");
		if (withFabricApi) behaviourType = loader.loadClass("net.fabricmc.fabric.api.registry.fluid.FluidBehavior");
		oil = tag("oil");
		tar = tag("tar");
	}

	@AfterEach void close() throws Exception {
		System.clearProperty(net.forbric.kernel.transform.FabricFluidBehaviorInjector.PROPERTY);
		if (loader != null) loader.close();
	}

	private Object tag(String path) throws Exception {
		Class<?> identifier = loader.loadClass("net.minecraft.resources.Identifier");
		Class<?> resourceKey = loader.loadClass("net.minecraft.resources.ResourceKey");
		Object fluids = loader.loadClass("net.minecraft.core.registries.Registries").getField("FLUID").get(null);
		Object id = identifier.getMethod("fromNamespaceAndPath", String.class, String.class).invoke(null, "forbrictest", path);
		return tagKey.getMethod("create", resourceKey, identifier).invoke(null, fluids, id);
	}

	/** A behaviour that swims, does not drown and carries boats, and says what it was asked about which tag. */
	private void register(Object tag) throws Exception {
		Object behaviour = Proxy.newProxyInstance(loader, new Class<?>[] { behaviourType }, (proxy, method, args) -> {
			asked.add(method.getName() + ":" + args[0]);
			return switch (method.getName()) {
				case "canSwimInFluid", "canSupportBoat" -> true;
				case "canDrownInFluid" -> false;
				default -> null;
			};
		});
		loader.loadClass("net.fabricmc.fabric.impl.content.registry.fluid.EntityFluidInteractionRegistryImpl")
				.getMethod("register", tagKey, behaviourType).invoke(null, tag, behaviour);
	}

	private Object byTag(Object tag) throws Exception {
		return loader.loadClass("net.forbric.kernel.runtime.KernelFluidTypes").getMethod("byTag", tagKey).invoke(null, tag);
	}

	private Object forgeType(Object tag) throws Exception {
		Method forge = loader.loadClass("net.forbric.kernel.runtime.KernelFabricFluidBehaviors").getDeclaredMethod("forgeType", tagKey);
		forge.setAccessible(true);
		return forge.invoke(null, tag);
	}

	/** canSwim, canDrownIn, supportsBoating of {@code type}, then that nothing else was asked of the behaviour. */
	private void answersFromTheBehaviour(Object type, String family) throws Exception {
		asked.clear();
		// Asked through the public FluidType, as the game asks it: the kernel's type classes are package-private.
		Class<?> api = type.getClass().getSuperclass();
		assertEquals(family, api.getName());
		assertEquals(true, api.getMethod("canSwim", entity).invoke(type, (Object) null));
		assertEquals(false, api.getMethod("canDrownIn", living).invoke(type, (Object) null));
		assertEquals(true, api.getMethod("supportsBoating", boat).invoke(type, (Object) null));
		assertEquals(List.of("canSwimInFluid:" + oil, "canDrownInFluid:" + oil, "canSupportBoat:" + oil), asked,
				"each answer is the behaviour's, asked about its own tag");
		asked.clear();
		// The tracker gathers the current (NeoForge does only for a type that pushes) and NeoForge applies none of it:
		// fabric-api's own tick applies it, once, at the behaviour's scale.
		assertEquals(true, api.getMethod("canPushEntity", entity).invoke(type, (Object) null), "the behaviour's current is gathered");
		assertEquals(0.0, api.getMethod("motionScale", entity).invoke(type, (Object) null), "and not applied a second time");
		// Everything else is the empty type's — today's answer; fabric-api's tick scales the fall distance itself.
		assertEquals(1.0F, api.getMethod("getFallDistanceModifier", entity).invoke(type, (Object) null));
		assertEquals(0, api.getMethod("getDensity").invoke(type));
		assertEquals(0, api.getMethod("getViscosity").invoke(type));
		assertEquals("block.minecraft.air", api.getMethod("getDescriptionId").invoke(type));
		assertEquals(List.of(), asked);
	}

	@Test void aRegisteredTagAnswersNeoForgesQuestionsFromItsBehaviour() throws Exception {
		load(true);
		assertNull(byTag(oil), "nothing registered: NeoForge's IllegalArgumentException stands, not fabric-api's NullPointerException");
		register(oil);
		Object type = byTag(oil);
		assertNotNull(type);
		assertSame(type, byTag(oil), "one type per tag: EntityFluidInteraction tracks by type identity");
		assertNull(byTag(tar), "a tag nobody registered still throws");
		answersFromTheBehaviour(type, "net.neoforged.neoforge.fluids.FluidType");
	}

	@Test void minecraftForgesFamilyAnswersTheSame() throws Exception {
		load(true);
		register(oil);
		Object forge = forgeType(oil);
		assertSame(forge, forgeType(oil));
		answersFromTheBehaviour(forge, "net.minecraftforge.fluids.FluidType");
	}

	@Test void withoutFabricApiNoTagHasAType() throws Exception {
		load(false);
		assertNull(byTag(oil));
	}

	/** A registry that is there but does not link is absent, not thrown: a fluid's type is asked inside resource reloads. */
	@Test void aRegistryThatDoesNotLinkIsTreatedAsAbsent() throws Exception {
		load(false);
		ClassLoader broken = new ClassLoader(loader) {
			@Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
				if (name.equals("net.fabricmc.fabric.impl.content.registry.fluid.EntityFluidInteractionRegistryImpl")) {
					throw new NoClassDefFoundError("net/minecraft/tags/TagKey");
				}
				return super.loadClass(name, resolve);
			}
		};
		Method resolve = loader.loadClass("net.forbric.kernel.runtime.KernelFabricFluidBehaviors").getDeclaredMethod("resolve", ClassLoader.class);
		resolve.setAccessible(true);
		resolve.invoke(null, broken);
		assertNull(byTag(oil), "and every later question answers as without fabric-api");
	}

	/**
	 * A behaviour lookup that breaks after the registry resolved — here fabric-api's tracked set throws an Error, as a
	 * half-linked module would — answers "no behaviour" in both families: a fluid's type is asked inside resource reloads.
	 */
	@Test void aLookupThatBreaksAnswersNoBehaviourType() throws Exception {
		load(true);
		register(oil);
		Class<?> behaviours = loader.loadClass("net.forbric.kernel.runtime.KernelFabricFluidBehaviors");
		MethodHandle broken = MethodHandles.insertArguments(MethodHandles.throwException(Collection.class, NoClassDefFoundError.class), 0,
				new NoClassDefFoundError("net/fabricmc/fabric/api/registry/fluid/FluidBehavior"));
		Constructor<?> handles = loader.loadClass("net.forbric.kernel.runtime.KernelFabricFluidBehaviors$Handles").getDeclaredConstructors()[0];
		handles.setAccessible(true);
		set(behaviours, "handles", handles.newInstance(broken, broken, broken, broken, broken));
		set(behaviours, "resolved", true);
		Class<?> types = loader.loadClass("net.forbric.kernel.runtime.KernelFluidTypes");
		Class<?> state = loader.loadClass("net.minecraft.world.level.material.FluidState");
		Method neo = types.getDeclaredMethod("behaviourType", state);
		Method forge = types.getDeclaredMethod("forgeBehaviourType", loader.loadClass("net.minecraft.world.level.material.Fluid"), state);
		neo.setAccessible(true);
		forge.setAccessible(true);
		// The state is never reached: the tracked set is asked first.
		assertNull(neo.invoke(null, (Object) null), "NeoForge's family: the tag-implied type");
		assertNull(forge.invoke(null, null, null), "MinecraftForge's family: the tag-implied type");
	}

	private static void set(Class<?> owner, String name, Object value) throws Exception {
		java.lang.reflect.Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		field.set(null, value);
	}

	/**
	 * Two threads asking first — the server's entity tick and the render thread's air bubbles: the one that finds the
	 * other resolving waits for it, and never reads "no registry" meanwhile (NeoForge's throw, the crash this fixes).
	 */
	@Test void aQuestionDuringTheFirstResolveWaitsForIt() throws Exception {
		load(true);
		register(oil);
		CountDownLatch inside = new CountDownLatch(1), release = new CountDownLatch(1);
		ClassLoader slow = new ClassLoader(loader) {
			@Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
				if (name.equals("net.fabricmc.fabric.impl.content.registry.fluid.EntityFluidInteractionRegistryImpl")) {
					inside.countDown();
					try {
						release.await();
					} catch (InterruptedException e) {
						throw new ClassNotFoundException(name, e);
					}
				}
				return super.loadClass(name, resolve);
			}
		};
		Method resolve = loader.loadClass("net.forbric.kernel.runtime.KernelFabricFluidBehaviors").getDeclaredMethod("resolve", ClassLoader.class);
		resolve.setAccessible(true);
		AtomicReference<Throwable> failed = new AtomicReference<>();
		AtomicReference<Object> answer = new AtomicReference<>();
		Thread first = new Thread(() -> {
			try {
				resolve.invoke(null, slow);
			} catch (Throwable t) {
				failed.set(t);
			}
		}, "first-resolve");
		first.start();
		assertTrue(inside.await(30, TimeUnit.SECONDS), "the first thread is resolving");
		Thread second = new Thread(() -> {
			try {
				answer.set(byTag(oil));
			} catch (Throwable t) {
				failed.set(t);
			}
		}, "second-question");
		second.start();
		// Until it answered, or waits on the first: both are decided by then.
		for (long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30); second.isAlive() && second.getState() != Thread.State.BLOCKED
				&& System.nanoTime() < end; ) {
			Thread.sleep(1);
		}
		release.countDown();
		first.join(30_000);
		second.join(30_000);
		assertNull(failed.get());
		assertNotNull(answer.get(), "the second thread got the behaviour's type, not NeoForge's throw");
	}

	@Test void offReportsNoBehaviourType() throws Exception {
		load(true);
		register(oil);
		System.setProperty(net.forbric.kernel.transform.FabricFluidBehaviorInjector.PROPERTY, "off");
		assertNull(byTag(oil));
	}
}
