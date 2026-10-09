/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.transform.InjectorExecution;

/**
 * A mod that decorates the Mixin weaver through Fabric's Knot — {@code delegate} on the loader that defined its
 * classes, then {@code mixinTransformer} on that — reaches the weaver the kernel's class pipeline reads, unrewritten.
 *
 * <p>The decorators are written differently from each other and from JECharacters' static {@code hook()}: one is an
 * instance method that finds the loader through {@code getClass()} and keeps everything in fields, under a
 * try/catch; the other is reached from a helper, takes the thread's context loader, walks the superclass chain for the
 * field, and reads the weaver through Knot's private {@code getMixinTransformer()} before writing the field. Each wraps
 * what it finds in a {@link Proxy} that names itself.
 */
@ResourceLock("system-properties")
@ResourceLock("MixinWeaverSlot")
class KnotWeaverFieldTest {
	private static final Map<String, String> SOURCES = Map.of(
			"fixture.weavedecor.Tagged", """
					package fixture.weavedecor;

					import java.lang.reflect.Proxy;
					import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

					final class Tagged {
						static IMixinTransformer wrap(String tag, IMixinTransformer inner) {
							return (IMixinTransformer) Proxy.newProxyInstance(Tagged.class.getClassLoader(),
									new Class<?>[] {IMixinTransformer.class},
									(proxy, method, args) -> method.getName().equals("toString") ? tag + "(" + inner + ")"
											: method.invoke(inner, args));
						}
					}
					""",
			"fixture.weavedecor.FieldDecorator", """
					package fixture.weavedecor;

					import java.lang.reflect.Field;
					import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

					public final class FieldDecorator {
						private Object knotDelegate;
						private Field slot;
						public IMixinTransformer found;

						public void decorate() {
							try {
								ClassLoader loader = getClass().getClassLoader();
								Field delegateField = loader.getClass().getDeclaredField("delegate");
								delegateField.setAccessible(true);
								knotDelegate = delegateField.get(loader);
								slot = knotDelegate.getClass().getDeclaredField("mixinTransformer");
								slot.setAccessible(true);
								found = (IMixinTransformer) slot.get(knotDelegate);
								slot.set(knotDelegate, Tagged.wrap("field", found));
							} catch (ReflectiveOperationException failed) {
								throw new IllegalStateException("Knot's weaver is out of reach", failed);
							}
						}
					}
					""",
			"fixture.weavedecor.ContextDecorator", """
					package fixture.weavedecor;

					import java.lang.reflect.Field;
					import java.lang.reflect.Method;
					import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

					public final class ContextDecorator {
						public static IMixinTransformer decorate() throws ReflectiveOperationException {
							return Walk.replace(Thread.currentThread().getContextClassLoader());
						}

						static final class Walk {
							static IMixinTransformer replace(ClassLoader loader) throws ReflectiveOperationException {
								Field holder = null;
								for (Class<?> type = loader.getClass(); holder == null && type != null; type = type.getSuperclass()) {
									for (Field field : type.getDeclaredFields()) if (field.getName().equals("delegate")) holder = field;
								}
								if (holder == null) throw new NoSuchFieldException("delegate");
								holder.setAccessible(true);
								Object knot = holder.get(loader);
								Method getter = knot.getClass().getDeclaredMethod("getMixinTransformer");
								getter.setAccessible(true);
								IMixinTransformer before = (IMixinTransformer) getter.invoke(knot);
								Field target = knot.getClass().getDeclaredField("mixinTransformer");
								target.setAccessible(true);
								target.set(knot, Tagged.wrap("context", before));
								return before;
							}
						}
					}
					""",
			"fixture.weavedecor.WrongField", """
					package fixture.weavedecor;

					public final class WrongField {
						public static Object read(String loaderField, String delegateField) throws ReflectiveOperationException {
							ClassLoader loader = WrongField.class.getClassLoader();
							java.lang.reflect.Field first = loader.getClass().getDeclaredField(loaderField);
							first.setAccessible(true);
							Object held = first.get(loader);
							java.lang.reflect.Field second = held.getClass().getDeclaredField(delegateField);
							second.setAccessible(true);
							return second.get(held);
						}

						public static void store(Object value) throws ReflectiveOperationException {
							ClassLoader loader = WrongField.class.getClassLoader();
							java.lang.reflect.Field first = loader.getClass().getDeclaredField("delegate");
							first.setAccessible(true);
							Object held = first.get(loader);
							java.lang.reflect.Field second = held.getClass().getDeclaredField("mixinTransformer");
							second.setAccessible(true);
							second.set(held, value);
						}
					}
					""");

	@TempDir static Path work;
	private static Path jar;

	private final IMixinTransformer mixins = named("mixin");

	@BeforeAll static void compile() throws Exception {
		Map<String, byte[]> classes = InjectorExecution.compile(work, SOURCES);
		jar = work.resolve("weavedecor.jar");
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
			for (var entry : classes.entrySet()) {
				out.putNextEntry(new JarEntry(entry.getKey() + ".class"));
				out.write(entry.getValue());
				out.closeEntry();
			}
		}
	}

	@AfterEach void reset() {
		MixinWeaverSlot.reset();
		System.clearProperty(MixinPlatformIdentity.PROPERTY);
	}

	private static IMixinTransformer named(String name) {
		return (IMixinTransformer) Proxy.newProxyInstance(KnotWeaverFieldTest.class.getClassLoader(),
				new Class<?>[] {IMixinTransformer.class}, (proxy, method, args) -> method.getName().equals("toString") ? name : null);
	}

	/** A game loader as the Mixin bootstrap leaves it: the weaver installed in the slot, Knot's field attached. */
	private ForbricClassLoader bootedLoader() throws Exception {
		ForbricClassLoader loader = new ForbricClassLoader(new URL[] {jar.toUri().toURL()}, getClass().getClassLoader());
		MixinWeaverSlot.install(mixins);
		loader.knotDelegate().attach();
		return loader;
	}

	private static String weaving(IMixinTransformer fallback) {
		return String.valueOf(MixinWeaverSlot.currentOr(fallback));
	}

	@Test void aDecoratorWrittenIntoKnotsFieldWeavesFromThenOnAndTheNextOneWrapsIt() throws Throwable {
		try (ForbricClassLoader loader = bootedLoader()) {
			assertEquals("mixin", weaving(mixins), "until a mod writes the field, Mixin's own transformer weaves");

			Object first = InjectorExecution.construct(loader.loadClass("fixture.weavedecor.FieldDecorator"));
			InjectorExecution.invoke(first, "decorate");
			assertSame(mixins, first.getClass().getField("found").get(first), "the field held the weaver the kernel weaves with");
			assertEquals("field(mixin)", weaving(mixins), "the decorator weaves every class after this");

			Thread thread = Thread.currentThread();
			ClassLoader context = thread.getContextClassLoader();
			thread.setContextClassLoader(loader);
			try {
				Object before = InjectorExecution.invokeStatic(loader.loadClass("fixture.weavedecor.ContextDecorator"), "decorate");
				assertEquals("field(mixin)", String.valueOf(before), "a second mod finds the first one's decorator there");
			} finally {
				thread.setContextClassLoader(context);
			}
			assertEquals("context(field(mixin))", weaving(mixins));
		}
	}

	/**
	 * Knot's field and FML's processor field are two views of one slot: a wrapper written into either is what the
	 * next mod finds in the other, so mods of both platforms decorate one chain.
	 */
	@Test void knotsFieldAndAnotherPlatformsViewHoldOneChain() throws Throwable {
		try (ForbricClassLoader loader = bootedLoader()) {
			AtomicReference<Object> fml = new AtomicReference<>(MixinWeaverSlot.currentOr(MixinWeaverSlot.original()));
			MixinWeaverSlot.watch("a second platform's field", fml::get, fml::set);

			Object decorator = InjectorExecution.construct(loader.loadClass("fixture.weavedecor.FieldDecorator"));
			InjectorExecution.invoke(decorator, "decorate");
			assertEquals("field(mixin)", weaving(mixins));
			assertEquals("field(mixin)", String.valueOf(fml.get()), "the other view now holds the Knot decorator");

			IMixinTransformer neo = named("neo(" + fml.get() + ")");
			String expected = "neo(field(mixin))";
			fml.set(neo);
			assertEquals(expected, weaving(mixins));

			Thread thread = Thread.currentThread();
			ClassLoader context = thread.getContextClassLoader();
			thread.setContextClassLoader(loader);
			try {
				Object found = InjectorExecution.invokeStatic(loader.loadClass("fixture.weavedecor.ContextDecorator"), "decorate");
				assertSame(neo, found, "the Knot field was handed the other platform's decorator");
			} finally {
				thread.setContextClassLoader(context);
			}
			assertEquals("context(" + expected + ")", weaving(mixins));
		}
	}

	/**
	 * Another view registered after Knot's, holding the weaver it was seeded with, must not outvote a decorator a mod
	 * wrote into Knot's field before the pipeline next looked: the decorator weaves, and the other view is handed it.
	 */
	@Test void aSeedNeverOutvotesADecoratorWrittenBeforeAnythingLooked() throws Throwable {
		try (ForbricClassLoader loader = bootedLoader()) {
			AtomicReference<Object> other = new AtomicReference<>(mixins);
			MixinWeaverSlot.watch("a second platform's field", other::get, other::set);
			Object decorator = InjectorExecution.construct(loader.loadClass("fixture.weavedecor.FieldDecorator"));
			InjectorExecution.invoke(decorator, "decorate");
			assertEquals("field(mixin)", weaving(mixins));
			assertEquals("field(mixin)", String.valueOf(other.get()));
		}
	}

	/** A view registered after a decorator went in is seeded with that decorator, not with Mixin's own transformer. */
	@Test void aFieldAttachedLaterStartsFromWhatWeavesNow() throws Throwable {
		MixinWeaverSlot.install(mixins);
		IMixinTransformer earlier = named("earlier");
		MixinWeaverSlot.watch(() -> earlier);
		try (ForbricClassLoader loader = new ForbricClassLoader(new URL[] {jar.toUri().toURL()}, getClass().getClassLoader())) {
			loader.knotDelegate().attach();
			Object decorator = InjectorExecution.construct(loader.loadClass("fixture.weavedecor.FieldDecorator"));
			InjectorExecution.invoke(decorator, "decorate");
			assertSame(earlier, decorator.getClass().getField("found").get(decorator));
			assertEquals("field(earlier)", weaving(mixins));
		}
	}

	@Test void onlyKnotsOwnFieldsExistAndOnlyATransformerIsEverWoven() throws Throwable {
		try (ForbricClassLoader loader = bootedLoader()) {
			Class<?> wrong = loader.loadClass("fixture.weavedecor.WrongField");
			assertThrows(NoSuchFieldException.class, () -> InjectorExecution.invokeStatic(wrong, "read", "transformer", "mixinTransformer"),
					"the loader has Knot's delegate field, not any field a mod might guess");
			assertThrows(NoSuchFieldException.class, () -> InjectorExecution.invokeStatic(wrong, "read", "delegate", "transformer"),
					"and the delegate has Knot's mixinTransformer, nothing else");
			assertSame(mixins, InjectorExecution.invokeStatic(wrong, "read", "delegate", "mixinTransformer"));

			assertThrows(IllegalArgumentException.class, () -> InjectorExecution.invokeStatic(wrong, "store", "not a weaver"),
					"Knot's field type keeps anything but a transformer out");
			InjectorExecution.invokeStatic(wrong, "store", (Object) null);
			assertEquals("mixin", weaving(mixins), "a mod clearing the field does not leave the pipeline without a weaver");
		}
	}

	@Test void switchedOffTheFieldIsEmptyAndWhatAModWritesWeavesNothing() throws Throwable {
		System.setProperty(MixinPlatformIdentity.PROPERTY, "off");
		try (ForbricClassLoader loader = bootedLoader()) {
			Object decorator = InjectorExecution.construct(loader.loadClass("fixture.weavedecor.FieldDecorator"));
			InjectorExecution.invoke(decorator, "decorate");
			assertNull(decorator.getClass().getField("found").get(decorator), "the field was never seeded");
			assertEquals("mixin", weaving(mixins), "and the decorator written into it is not read");
		}
	}

	@Test void beforeMixinIsUpTheFieldIsEmptyAsKnotsIs() throws Throwable {
		try (ForbricClassLoader loader = new ForbricClassLoader(new URL[] {jar.toUri().toURL()}, getClass().getClassLoader())) {
			Object decorator = InjectorExecution.construct(loader.loadClass("fixture.weavedecor.FieldDecorator"));
			InjectorExecution.invoke(decorator, "decorate");
			assertNull(decorator.getClass().getField("found").get(decorator));
		}
	}
}
