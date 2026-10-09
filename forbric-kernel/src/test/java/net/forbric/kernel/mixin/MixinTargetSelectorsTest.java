/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.injection.selectors.ISelectorContext;
import org.spongepowered.asm.mixin.injection.selectors.ITargetSelector;
import org.spongepowered.asm.mixin.injection.selectors.TargetSelector;
import org.spongepowered.asm.mixin.injection.selectors.TargetSelectors;
import org.spongepowered.asm.mixin.refmap.IMixinContext;
import org.spongepowered.asm.mixin.refmap.ReferenceMapper;

/**
 * Selector equivalence: for each selector list below, against one class with overloads, constructors, statics, a
 * case-only near miss and methods another mixin merged in, {@link MixinTargetSelectors} binds exactly the methods
 * Mixin's own {@code TargetSelectors} selects for that injector, for an instance and for a static handler — and refuses
 * (null) exactly where Mixin throws.
 */
class MixinTargetSelectorsTest {
	private static final String TARGET = "example/selectors/Target";

	/** name-only, name+desc, owner-qualified in both spellings, quantifiers, patterns, arrays, blanks and malformed ones. */
	static final List<List<String>> TABLE = List.of(
			List.of("tick"), List.of("tick()V"), List.of("L" + TARGET + ";tick()V"), List.of("example.selectors.Target.tick()V"),
			List.of("example.selectors.Target.tick"), List.of("Lexample/selectors/Other;tick()V"), List.of(" tick ( ) V "),
			List.of("TICK"), List.of("Tick"), List.of("tick*"), List.of("tick+"), List.of("tick{1,2}"), List.of("tick{2,3}"),
			List.of("*"), List.of("setBlock"), List.of("setBlock(Lexample/selectors/Pos;II)Z"), List.of("setBlock*"),
			List.of("setBlock(Lexample/selectors/Pos;III)Z"), List.of("/^set/"), List.of("/Block/ desc=/II\\)Z$/"),
			List.of("name=/^get/"), List.of("owner=/Target$/"), List.of("/^nothing$/"), List.of("<init>"), List.of("<init>(I)V"),
			List.of("<clinit>"), List.of("(I)I"), List.of("getValue:(I)I"), List.of("getValue"), List.of("getValue*"),
			List.of("Lexample/selectors/Target;"), List.of(""), List.of("merged"), List.of("merged*"), List.of("lambda$run$0"),
			List.of("tick", "tick()V"), List.of("tick", "baseTick"), List.of("tick", "Lexample/selectors/Other;tick()V"),
			List.of("tick("), List.of("ti-ck"), List.of("tick()Q"), List.of("absent"), List.of("absent+"), List.of("/[/"));

	@TestFactory Stream<DynamicTest> everySelectorBindsWhatMixinBinds() {
		ClassNode target = target();
		return TABLE.stream().flatMap(selectors -> Stream.of(false, true).map(isStatic -> DynamicTest.dynamicTest(
				selectors + (isStatic ? " / static handler" : " / instance handler"), () -> {
					List<String> expected = names(mixinBinds(target, selectors, isStatic));
					List<String> actual = names(MixinTargetSelectors.bound(selectors, target, isStatic));
					assertEquals(expected, actual);
				})));
	}

	@Test void theTableCoversBindingRefusingAndEmptyOutcomes() {
		ClassNode target = target();
		assertEquals(List.of("tick()V"), names(MixinTargetSelectors.bound(List.of("example.selectors.Target.tick"), target, false)));
		assertEquals(List.of("setBlock(Lexample/selectors/Pos;I)Z"), names(MixinTargetSelectors.bound(List.of("setBlock"), target, false)),
				"a bare name binds the first declared overload");
		assertNull(MixinTargetSelectors.bound(List.of("Lexample/selectors/Other;tick()V"), target, false), "an owner other than the target is refused");
		assertNull(MixinTargetSelectors.bound(List.of("tick{2,3}"), target, false), "fewer matches than the minimum fail the injector");
		assertEquals(List.of(), MixinTargetSelectors.bound(List.of("TICK"), target, false), "a case-only near miss is not an exact match");
		assertEquals(List.of(), MixinTargetSelectors.bound(List.of("merged*"), target, false), "a multi-match selector skips merged methods");
		assertEquals(List.of("getValue()I", "getValue(I)I"), names(MixinTargetSelectors.bound(List.of("getValue*"), target, false)),
				"a multi-match selector skips statics for an instance handler");
		assertEquals(List.of("getValue(J)I", "getValue()I", "getValue(I)I"), names(MixinTargetSelectors.bound(List.of("getValue*"), target, true)),
				"in declaration order");
	}

	@Test void formsThisDoesNotModelAreNotGuessed() {
		ClassNode target = target();
		assertNull(MixinTargetSelectors.bound(List.of("tick()V -> lambda$run$0"), target, false), "a nested selection");
		assertNull(MixinTargetSelectors.bound(List.of("@Desc(\"tick\")"), target, false), "a dynamic selector");
		MethodNode dynamic = handler(List.of("tick"));
		MixinFit.injectorOf(dynamic).values.addAll(List.of("target", List.of(new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Desc;"))));
		assertNull(MixinTargetSelectors.bound(dynamic, target), "an @Desc target");
	}

	@Test void aHandlerBindsOnlyTheOneMethodItsSelectorsAgreeOn() {
		ClassNode target = target();
		for (String spelling : List.of("tick", "tick()V", "L" + TARGET + ";tick()V", "example.selectors.Target.tick()V", "/^tick$/", "tick*"))
			assertTrue(MixinTargetSelectors.bindsOnly(handler(List.of(spelling)), target, "tick()V"), spelling);
		assertTrue(MixinTargetSelectors.bindsOnly(handler(List.of("tick", "tick()V")), target, "tick()V"));
		assertFalse(MixinTargetSelectors.bindsOnly(handler(List.of("tick", "baseTick")), target, "tick()V"), "two methods are not one");
		assertFalse(MixinTargetSelectors.bindsOnly(handler(List.of("/Tick$/")), target, "tick()V"), "a pattern that also binds baseTick");
		assertFalse(MixinTargetSelectors.bindsOnly(handler(List.of("setBlock")), target, "setBlock(Lexample/selectors/Pos;II)Z"),
				"a bare name binds the first overload, not this one");
	}

	@Test void aNativeMemberIsReadOffTheNativeClassOrOnlyOffAPinnedDescriptor() {
		ClassNode target = target();
		assertEquals("setBlock(Lexample/selectors/Pos;I)Z", MixinTargetSelectors.nativeMember(handler(List.of("setBlock")), target, TARGET));
		assertEquals("setBlock(Lexample/selectors/Pos;II)Z",
				MixinTargetSelectors.nativeMember(handler(List.of("L" + TARGET + ";setBlock(Lexample/selectors/Pos;II)Z")), null, TARGET));
		assertEquals("setBlock(Lexample/selectors/Pos;II)Z", MixinTargetSelectors.nativeMember(handler(List.of(
				"setBlock(Lexample/selectors/Pos;II)Z", "example.selectors.Target.setBlock(Lexample/selectors/Pos;II)Z")), null, TARGET));
		assertNull(MixinTargetSelectors.nativeMember(handler(List.of("setBlock")), null, TARGET), "which overload a bare name binds needs the class");
		assertEquals("dropped(I)V", MixinTargetSelectors.nativeMember(handler(List.of("dropped(I)V")), target, TARGET),
				"a pinned descriptor still names a member the class at hand does not declare");
		assertNull(MixinTargetSelectors.nativeMember(handler(List.of("dropped")), target, TARGET));
		assertNull(MixinTargetSelectors.nativeMember(handler(List.of("/^setBlock$/")), null, TARGET));
		assertNull(MixinTargetSelectors.nativeMember(handler(List.of("Lexample/selectors/Other;setBlock(Lexample/selectors/Pos;II)Z")), null, TARGET));
		assertNull(MixinTargetSelectors.nativeMember(handler(List.of("setBlock(Lexample/selectors/Pos;I)Z", "setBlock(Lexample/selectors/Pos;II)Z")), null, TARGET));
	}

	// -----------------------------------------------------------------------------------------------------------------

	static ClassNode target() {
		ClassNode c = new ClassNode();
		c.version = Opcodes.V21; c.access = Opcodes.ACC_PUBLIC; c.name = TARGET; c.superName = "java/lang/Object";
		method(c, 0, "<init>", "()V"); method(c, 0, "<init>", "(I)V"); method(c, Opcodes.ACC_STATIC, "<clinit>", "()V");
		method(c, 0, "tick", "()V"); method(c, 0, "baseTick", "()V"); method(c, 0, "Tick", "()V");
		method(c, 0, "setBlock", "(Lexample/selectors/Pos;I)Z"); method(c, 0, "setBlock", "(Lexample/selectors/Pos;II)Z");
		method(c, Opcodes.ACC_STATIC, "helper", "(I)I"); method(c, Opcodes.ACC_STATIC, "getValue", "(J)I");
		method(c, 0, "getValue", "()I"); method(c, 0, "getValue", "(I)I");
		method(c, Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC, "lambda$run$0", "(I)V");
		MethodNode merged = method(c, 0, "merged", "()V");
		merged.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;")));
		return c;
	}

	private static MethodNode method(ClassNode c, int access, String name, String desc) {
		MethodNode m = new MethodNode(access, name, desc, null, null);
		c.methods.add(m);
		return m;
	}

	static MethodNode handler(List<String> selectors) {
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, "callback", "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
		AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
		inject.values = new ArrayList<>(List.of("method", new ArrayList<>(selectors)));
		handler.visibleAnnotations = new ArrayList<>(List.of(inject));
		return handler;
	}

	private static List<String> names(List<MethodNode> methods) {
		return methods == null ? null : methods.stream().map(m -> m.name + m.desc).toList();
	}

	/** The methods Mixin's own TargetSelectors selects for a handler with {@code selectors}; null where it throws. */
	static List<MethodNode> mixinBinds(ClassNode target, List<String> selectors, boolean isStatic) {
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE | (isStatic ? Opcodes.ACC_STATIC : 0), "callback", "()V", null, null);
		ClassLoader loader = MixinTargetSelectorsTest.class.getClassLoader();
		IMixinContext mixin = (IMixinContext) Proxy.newProxyInstance(loader, new Class<?>[] {IMixinContext.class}, (proxy, method, args) ->
				switch (method.getName()) {
					case "getTargetClassRef" -> target.name;
					case "getTargetClassName" -> target.name.replace('/', '.');
					case "getClassRef" -> "example/selectors/Mixin";
					case "getClassName" -> "example.selectors.Mixin";
					case "getReferenceMapper" -> ReferenceMapper.DEFAULT_MAPPER;
					case "getOption" -> false;
					case "getPriority" -> 1000;
					case "toString" -> "example.selectors.Mixin";
					default -> null;
				});
		ISelectorContext context = (ISelectorContext) Proxy.newProxyInstance(loader, new Class<?>[] {ISelectorContext.class}, (proxy, method, args) ->
				switch (method.getName()) {
					case "getMixin" -> mixin;
					case "getMethod" -> handler;
					case "remap" -> args[0];
					case "getElementDescription", "toString" -> "test callback";
					case "getSelectorCoordinate" -> "method";
					default -> null;
				});
		try {
			Set<ITargetSelector> parsed = new LinkedHashSet<>();
			for (String selector : selectors) parsed.add(TargetSelector.parse(selector, context));
			TargetSelectors targets = new TargetSelectors(context, target);
			targets.parse(parsed);
			targets.find();
			Set<MethodNode> bound = new LinkedHashSet<>();
			for (TargetSelectors.SelectedMethod selected : targets) bound.add(selected.getMethod());
			return List.copyOf(bound);
		} catch (Throwable refused) {
			return null;
		}
	}
}
