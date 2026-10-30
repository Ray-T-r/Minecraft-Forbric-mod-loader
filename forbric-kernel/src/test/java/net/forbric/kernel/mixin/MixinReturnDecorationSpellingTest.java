/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;

/**
 * {@link MixinReturnDecorationAdapter} reads the callback's method as Mixin binds it in the class the mod was compiled
 * against: fabric-api's released return callback under other names, its selector written owner-prefixed, dotted, as two
 * spellings, with its descriptor and whitespace, or as a bare name that a compiled-against class with a later-declared
 * overload still binds to the same method, follows the conserved return exactly as the released spelling does. A bare
 * name the compiled-against class binds to another overload, a selector binding another method, and one naming another
 * owner's method leave the mixin exactly as written.
 */
@ResourceLock("system-properties")
class MixinReturnDecorationSpellingTest {
	private static final String HOST = "net/minecraft/client/renderer/ScreenEffectRenderer";

	@Test void theReleasedCallbackUnderOtherNamesFollowsTheReturn() throws Exception {
		Inputs in = inputs();
		ClassNode mixin = in.mixin();
		assertEquals(1, in.adapt(mixin, in.original()));
		assertTrue(MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(in.handler(mixin)), "method")).getFirst().startsWith(in.selector() + "AndPos("),
				"the callback follows into the method that now returns the extra context");
		assertEquals(0, in.adapt(mixin, in.original()), "idempotence");
	}

	@TestFactory Stream<DynamicTest> selectorsWrittenAnotherWayFollowTheSameReturn() throws Exception {
		Inputs in = inputs();
		String reference = in.released();
		String desc = descriptor(in.original(), in.selector());
		Map<String, Consumer<ClassNode>> forms = new LinkedHashMap<>();
		for (var form : PointRespelling.SELECTORS.entrySet())
			forms.put(form.getKey(), mixin -> assertEquals(1, PointRespelling.selectors(mixin, m -> m == in.handler(mixin), form.getValue()), "premise"));
		forms.put("descriptor and whitespace", mixin -> select(in.handler(mixin), List.of("  " + in.selector() + " " + desc + " ")));
		forms.put("dotted owner with descriptor", mixin -> select(in.handler(mixin), List.of(HOST.replace('/', '.') + "." + in.selector() + desc)));
		return forms.entrySet().stream().map(form -> DynamicTest.dynamicTest(form.getKey(), () -> {
			ClassNode mixin = in.mixin();
			form.getValue().accept(mixin);
			assertFollowsAs(in, reference, mixin, in.original());
		}));
	}

	/** A bare name, where the compiled-against class also declares an overload AFTER the one it binds: still that one. */
	@Test void aBareNameTheCompiledAgainstClassBindsToTheFirstDeclaredFollowsTheSameReturn() throws Exception {
		Inputs in = inputs();
		String reference = in.released();
		assertFollowsAs(in, reference, in.mixin(), overloaded(in, false));
	}

	// ---- look-alikes ------------------------------------------------------------------------------------------------------

	/** The same overload declared BEFORE vanilla's: the bare name was written for it, and it returns no such local. */
	@Test void aBareNameTheCompiledAgainstClassBindsToAnotherOverloadIsLeftAlone() throws Exception {
		Inputs in = inputs();
		assertLeftAlone(in, in.mixin(), overloaded(in, true));
	}

	@Test void aSelectorBindingAnotherMethodIsLeftAlone() throws Exception {
		Inputs in = inputs();
		MethodNode other = in.original().methods.stream().filter(m -> !m.name.startsWith("<") && !m.name.equals(in.selector())).findFirst().orElseThrow();
		for (List<String> selectors : List.of(List.of(HOST.replace('/', '.') + "." + other.name + other.desc), List.of("L" + HOST + ";" + other.name))) {
			ClassNode mixin = in.mixin();
			select(in.handler(mixin), selectors);
			assertLeftAlone(in, mixin, in.original());
		}
	}

	/** Mixin refuses a selector whose owner is not the mixin's target: no method is bound, whatever its name. */
	@Test void aSelectorNamingAnotherOwnersMethodIsLeftAlone() throws Exception {
		Inputs in = inputs();
		for (String selector : List.of("Lnet/minecraft/client/renderer/GameRenderer;" + in.selector(), "org.example.elsewhere.Overlay." + in.selector())) {
			ClassNode mixin = in.mixin();
			select(in.handler(mixin), List.of(selector));
			assertLeftAlone(in, mixin, in.original());
		}
	}

	// ---- reading ----------------------------------------------------------------------------------------------------------

	/** The callback rewrites its selector to the method it follows into, so a respelled one adapts to the same bytes. */
	private static void assertFollowsAs(Inputs in, String reference, ClassNode mixin, ClassNode original) {
		assertEquals(1, in.adapt(mixin, original), "adapted");
		assertEquals(reference, java.util.HexFormat.of().formatHex(CarpetMixinAdapterTest.bytes(mixin)), "adapted exactly as the released spelling");
		assertEquals(0, in.adapt(mixin, original), "idempotence");
	}

	private static void assertLeftAlone(Inputs in, ClassNode mixin, ClassNode original) {
		byte[] before = CarpetMixinAdapterTest.bytes(mixin);
		assertEquals(0, in.adapt(mixin, original));
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(mixin), "the mixin was rewritten");
	}

	/** The native class with an overload of the released method, declared before ({@code first}) or after it, returning null. */
	private static ClassNode overloaded(Inputs in, boolean first) {
		ClassNode host = in.original();
		String desc = descriptor(host, in.selector());
		int staticness = host.methods.stream().filter(m -> m.name.equals(in.selector())).findFirst().orElseThrow().access & Opcodes.ACC_STATIC;
		Type[] arguments = Type.getArgumentTypes(desc);
		Type[] more = java.util.Arrays.copyOf(arguments, arguments.length + 1);
		more[arguments.length] = Type.INT_TYPE;
		MethodNode decoy = new MethodNode(Opcodes.ACC_PUBLIC | staticness, in.selector(), Type.getMethodDescriptor(Type.getReturnType(desc), more), null, null);
		decoy.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
		decoy.instructions.add(new InsnNode(Opcodes.ARETURN));
		decoy.maxStack = 1; decoy.maxLocals = more.length + (staticness == 0 ? 1 : 0);
		if (first) host.methods.addFirst(decoy); else host.methods.add(decoy);
		return host;
	}

	private static String descriptor(ClassNode natives, String name) {
		List<MethodNode> named = natives.methods.stream().filter(m -> m.name.equals(name)).toList();
		assertEquals(1, named.size(), "premise: one " + name);
		return named.getFirst().desc;
	}

	private static void select(MethodNode handler, List<String> selectors) {
		MixinPlayerWorldCallbackAdapter.set(MixinFit.injectorOf(handler), "method", new ArrayList<>(selectors));
	}

	/** The staged merged class, vanilla's as fabric-api was compiled against it, and the renamed released callback. */
	private record Inputs(byte[] currentBytes, byte[] originalBytes, byte[] releasedBytes, String handlerName, String selector) {
		ClassNode mixin() { return parse(releasedBytes); }
		ClassNode original() { return parse(originalBytes); }
		MethodNode handler(ClassNode mixin) { return mixin.methods.stream().filter(m -> m.name.equals(handlerName)).findFirst().orElseThrow(); }
		int adapt(ClassNode mixin, ClassNode natives) {
			ClassNode merged = parse(currentBytes);
			return MixinReturnDecorationAdapter.adapt(mixin, Ecosystem.FABRIC, name -> merged, name -> natives);
		}
		String released() {
			ClassNode mixin = mixin();
			assertEquals(1, adapt(mixin, original()));
			return java.util.HexFormat.of().formatHex(CarpetMixinAdapterTest.bytes(mixin));
		}
	}

	private static Inputs inputs() throws Exception {
		Path base = Path.of(System.getProperty("forbric.predicateBase", TestFixtures.stagedBase().toString()));
		Path api = Path.of(System.getProperty("forbric.fabricApi", TestFixtures.fabricApi().toString()));
		TestFixtures.requireFiles(TestFixtures.Fixture.STAGED, "native-indexed merged base and the actual Fabric API", base, api);
		byte[] current, original;
		try (ZipFile game = new ZipFile(base.toFile())) {
			current = game.getInputStream(game.getEntry(HOST + ".class")).readAllBytes();
			NativeGameReferences references = new NativeGameReferences(path -> {
				try { var e = game.getEntry(path); return e == null ? null : game.getInputStream(e).readAllBytes(); }
				catch (java.io.IOException unreadable) { throw new IllegalStateException(unreadable); }
			}, null, TestFixtures.launchSources(base));
			ClassNode node = references.get(Ecosystem.FABRIC, HOST);
			TestFixtures.require(TestFixtures.Fixture.STAGED, node != null, "native-indexed vanilla ScreenEffectRenderer required");
			original = CarpetMixinAdapterTest.bytes(node);
		}
		byte[] guest = null;
		try (ZipFile outer = new ZipFile(api.toFile())) {
			var module = outer.stream().filter(e -> e.getName().startsWith("META-INF/jars/fabric-renderer-api-v1-")).findFirst().orElseThrow();
			try (ZipInputStream inner = new ZipInputStream(outer.getInputStream(module))) {
				for (var e = inner.getNextEntry(); e != null; e = inner.getNextEntry())
					if (e.getName().equals("net/fabricmc/fabric/mixin/client/renderer/block/particle/ScreenEffectRendererMixin.class")) guest = inner.readAllBytes();
			}
		}
		assertNotNull(guest, "the return callback");
		ClassNode renamed = MixinCallbackSelectorSpellingTest.unrelatedNames(parse(guest));
		// The callback: the one @Inject at a MixinExtras expression point, found by what it is, never by its name.
		MethodNode handler = null;
		for (MethodNode method : renamed.methods) {
			AnnotationNode injector = MixinFit.injectorOf(method);
			if (injector == null || !injector.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")
					|| MixinFit.atNodes(injector).stream().noneMatch(at -> "MIXINEXTRAS:EXPRESSION".equals(MixinFit.value(at, "value")))) continue;
			assertNull(handler, "one return callback");
			handler = method;
		}
		assertNotNull(handler, "the return callback");
		assertTrue(handler.name.startsWith("respelled"), "renamed");
		List<String> selectors = MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(handler), "method"));
		assertEquals(1, selectors.size());
		return new Inputs(current, original, CarpetMixinAdapterTest.bytes(renamed), handler.name, selectors.getFirst());
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}
}
