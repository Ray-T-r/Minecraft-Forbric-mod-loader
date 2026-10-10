/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.TestFixtures;

/**
 * {@link MixinDecodeScopeAdapter} finds a decode guard's host, and its marker consumer's lambda, as Mixin binds their
 * selectors — not by comparing a selector with a method's name. fabric-api's resource-condition guards under other class
 * and handler names, every selector written another way Mixin binds to the same method (a dotted owner, an owner prefix,
 * whitespace, two spellings of one method): each is scoped exactly as the released spelling is. A selector naming
 * another owner, or a consumer whose selector binds another method than the lambda the parse hands its result to, leaves
 * the mixin exactly as written.
 */
@ResourceLock("system-properties")
class MixinDecodeScopeSpellingTest {
	private static final String REGISTRY = "net/minecraft/resources/RegistryLoadTask$PendingRegistration";
	private static final String LISTENER = "net/minecraft/server/packs/resources/SimpleJsonResourceReloadListener";
	private static final String WRAP_METHOD = "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;";

	/** Selector rewrites Mixin binds to the same method: those of {@link PointRespelling#SELECTORS}, and whitespace alone. */
	private static Map<String, BiFunction<String, String, List<String>>> forms() {
		Map<String, BiFunction<String, String, List<String>>> forms = new LinkedHashMap<>(PointRespelling.SELECTORS);
		forms.put("owner prefix with whitespace", (owner, s) -> List.of(" L" + owner + "; " + s + " "));
		return forms;
	}

	@Test void theReleasedGuardsUnderOtherNamesAreScoped() throws Exception {
		Function<String, ClassNode> classes = classes();
		assertRegistryScoped(registry(), classes);
		assertJsonScoped(json(), classes);
	}

	@TestFactory Stream<DynamicTest> theRegistryGuardWrittenAnotherWayIsScoped() {
		return forms().entrySet().stream().map(form -> DynamicTest.dynamicTest(form.getKey(), () -> {
			ClassNode mixin = registry();
			assertEquals(1, PointRespelling.selectors(mixin, PointRespelling::any, form.getValue()), "premise: the guard is respelled");
			assertRegistryScoped(mixin, classes());
		}));
	}

	@TestFactory Stream<DynamicTest> theJsonGuardAndItsConsumerWrittenAnotherWayAreScoped() {
		List<DynamicTest> tests = new ArrayList<>();
		for (var form : forms().entrySet()) {
			tests.add(DynamicTest.dynamicTest(form.getKey(), () -> {
				ClassNode mixin = json();
				assertEquals(2, PointRespelling.selectors(mixin, PointRespelling::any, form.getValue()), "premise: guard and consumer respelled");
				assertJsonScoped(mixin, classes());
			}));
			tests.add(DynamicTest.dynamicTest("consumer only, " + form.getKey(), () -> {
				ClassNode mixin = json();
				assertEquals(1, PointRespelling.selectors(mixin, m -> injector(m).desc.endsWith("/Inject;"), form.getValue()), "premise");
				assertJsonScoped(mixin, classes());
			}));
		}
		return tests.stream();
	}

	// ---- look-alikes ------------------------------------------------------------------------------------------------------

	@Test void aGuardNamingAnotherOwnerIsLeftAlone() throws Exception {
		for (String selector : List.of("Lnet/minecraft/resources/RegistryDataLoader;loadFromResource", "org.example.elsewhere.Registry.loadFromResource")) {
			ClassNode mixin = registry();
			select(mixin.methods.stream().filter(m -> MixinFit.injectorOf(m) != null).findFirst().orElseThrow(), selector);
			assertLeftAlone(mixin, classes());
		}
	}

	/**
	 * The marker consumer is moved only onto the lambda the native parse hands its result to. Its selector binding another
	 * method of the listener — the outer {@code scanDirectory}, or that lambda spelled for another owner — is another
	 * callback: neither it nor the guard paired with it changes.
	 */
	@Test void aConsumerBindingAnotherMethodIsLeftAlone() throws Exception {
		String lambda = consumerSelector(json());
		ClassNode listener = classes().apply(LISTENER);
		for (String selector : List.of(LISTENER.replace('/', '.') + ".scanDirectory", "Lorg/example/elsewhere/Listener;" + lambda)) {
			ClassNode mixin = json();
			select(consumer(mixin), selector);
			MethodNode bound = MixinTargetSelectors.one(consumer(mixin), listener);
			assertTrue(bound == null || bound.name.equals("scanDirectory"), "premise: " + selector + " binds another method, or none");
			assertLeftAlone(mixin, classes());
		}
	}

	/** The guard's own selector binding a method without the parse: nothing to scope. */
	@Test void aJsonGuardBindingAnotherMethodIsLeftAlone() throws Exception {
		ClassNode mixin = json();
		MethodNode guard = mixin.methods.stream().filter(m -> MixinFit.injectorOf(m) != null && injector(m).desc.endsWith("/WrapOperation;")).findFirst().orElseThrow();
		ClassNode listener = classes().apply(LISTENER);
		MethodNode other = listener.methods.stream().filter(m -> !m.name.startsWith("<") && !m.name.equals("scanDirectory")
				&& !m.name.startsWith("lambda$")).findFirst().orElseThrow();
		select(guard, LISTENER.replace('/', '.') + "." + other.name + other.desc);
		assertSame(other, MixinTargetSelectors.one(guard, listener), "premise: the guard binds " + other.name);
		assertLeftAlone(mixin, classes());
	}

	// ---- reading ----------------------------------------------------------------------------------------------------------

	private static void assertRegistryScoped(ClassNode mixin, Function<String, ClassNode> classes) {
		assertEquals(1, MixinDecodeScopeAdapter.adapt(mixin, classes));
		List<MethodNode> scopes = mixin.methods.stream().filter(m -> MixinDecodeScopeAdapterTest.has(m, WRAP_METHOD)).toList();
		assertEquals(1, scopes.size(), "one method scope");
		assertEquals(List.of("loadFromResource"), MixinFit.stringList(MixinFit.value(scopes.getFirst().visibleAnnotations.stream()
				.filter(a -> a.desc.equals(WRAP_METHOD)).findFirst().orElseThrow(), "method")).stream().map(s -> s.substring(0, s.indexOf('('))).toList());
		assertEquals(1, records(mixin));
	}

	private static void assertJsonScoped(ClassNode mixin, Function<String, ClassNode> classes) {
		assertEquals(2, MixinDecodeScopeAdapter.adapt(mixin, classes));
		assertNull(MixinFit.injectorOf(consumerByBody(mixin)), "the consumer's own injector moved into the native consumer's wrap");
		assertEquals(1, records(mixin));
	}

	private static void assertLeftAlone(ClassNode mixin, Function<String, ClassNode> classes) {
		byte[] before = MixinDecodeScopeAdapterTest.bytes(mixin);
		assertEquals(0, MixinDecodeScopeAdapter.adapt(mixin, classes));
		assertArrayEquals(before, MixinDecodeScopeAdapterTest.bytes(mixin), "the mixin was rewritten");
	}

	private static long records(ClassNode mixin) {
		return mixin.methods.stream().flatMap(m -> Arrays.stream(m.instructions.toArray()))
				.filter(i -> i instanceof MethodInsnNode c && c.owner.endsWith("KernelSourceDecodeScopes") && c.name.equals("record")).count();
	}

	private static ClassNode registry() throws Exception {
		return renamed("RegistryLoadTaskPendingRegistrationMixin");
	}

	private static ClassNode json() throws Exception {
		return renamed("SimpleJsonResourceReloadListenerMixin");
	}

	/** fabric-api's guard mixin under other class and handler names. */
	private static ClassNode renamed(String name) throws Exception {
		Path api = Path.of(System.getProperty("forbric.fabricApi", TestFixtures.fabricApi().toString()));
		TestFixtures.requireFiles(TestFixtures.Fixture.STAGED, "actual Fabric API required", api);
		ClassNode mixin = MixinCallbackSelectorSpellingTest.unrelatedNames(MixinDecodeScopeAdapterTest.guest(api, name));
		assertTrue(mixin.methods.stream().filter(m -> MixinFit.injectorOf(m) != null).allMatch(m -> m.name.startsWith("respelled")), "renamed");
		return mixin;
	}

	/** The marker consumer: the one {@code @Inject} at {@code HEAD}. */
	private static MethodNode consumer(ClassNode mixin) {
		return mixin.methods.stream().filter(m -> MixinFit.injectorOf(m) != null && injector(m).desc.endsWith("/Inject;")).findFirst().orElseThrow();
	}

	/** The consumer after a move took its injector: the one {@code (…, Object, CallbackInfo)V} body that cancels. */
	private static MethodNode consumerByBody(ClassNode mixin) {
		List<MethodNode> found = mixin.methods.stream().filter(m -> m.desc.endsWith("Ljava/lang/Object;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V")
				&& Arrays.stream(m.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode c && c.name.equals("cancel"))).toList();
		assertEquals(1, found.size(), "one marker consumer");
		return found.getFirst();
	}

	private static String consumerSelector(ClassNode mixin) {
		List<String> selectors = MixinFit.stringList(MixinFit.value(injector(consumer(mixin)), "method"));
		assertEquals(1, selectors.size());
		return selectors.getFirst();
	}

	private static AnnotationNode injector(MethodNode method) {
		return MixinFit.injectorOf(method);
	}

	private static void select(MethodNode handler, String selector) {
		MixinPlayerWorldCallbackAdapter.set(MixinFit.injectorOf(handler), "method", new ArrayList<>(List.of(selector)));
	}

	/** The staged merged classes, each read afresh. */
	private static Function<String, ClassNode> classes() {
		Path base = Path.of(System.getProperty("forbric.predicateBase", TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar").toString()));
		TestFixtures.requireFiles(TestFixtures.Fixture.STAGED, "indexed game base required", base);
		return name -> {
			try (ZipFile game = new ZipFile(base.toFile())) {
				var entry = game.getEntry(name + ".class");
				return entry == null ? null : MixinDecodeScopeAdapterTest.read(game.getInputStream(entry).readAllBytes());
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		};
	}
}
