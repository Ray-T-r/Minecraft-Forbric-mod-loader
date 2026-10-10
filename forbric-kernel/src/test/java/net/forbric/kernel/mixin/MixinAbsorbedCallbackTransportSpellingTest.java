/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import net.forbric.kernel.classloading.ForbricClassLoader;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * An AFTER callback whose call the merged game moved into a static helper follows the call as Mixin binds the injector
 * and resolves its point, however either is written. The host is the method the selectors bind in the class the mod was
 * compiled against — owner-qualified, dotted, spaced, doubled or a bare name binding the first method of that name — and
 * the call is the one the point selects there, its owner or descriptor left out where that method decides it. Before,
 * the host was found by comparing the selector with {@code name}/{@code name + desc} and the point was compared with the
 * call's full spelling, so none of these moved. A selector naming another owner or binding another method, a bare name
 * whose first method is another overload, and a short point that also selects another call there are left as they were.
 *
 * <p>Classes of their own ({@code org/example/relay}): no game, carrier or mod name takes part.
 */
class MixinAbsorbedCallbackTransportSpellingTest implements Opcodes {
	private static final String HOST = "org/example/relay/Pipeline", STAGE = "org/example/relay/Stage", SLOT = "org/example/relay/Slot";
	private static final String CROWD = "org/example/elsewhere/Crowd";
	private static final String DESC = "(L" + SLOT + ";I)V", CI = "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";
	private static final String FULL = "L" + SLOT + ";fill(I)V";

	/** What the native host also does before its call, the same in the merged host: nothing, or a look-alike call. */
	private enum Crowd { NONE, OTHER_OWNER, OTHER_DESCRIPTOR }

	/** {@code drive}, the host the mod was compiled against: the slot is filled last. {@code first}: an overload declared before it. */
	private static ClassNode nativeHost(Crowd crowd, boolean overloadFirst, boolean overloadAfter) {
		ClassNode node = node(HOST);
		if (overloadFirst) node.methods.add(empty("drive", "(J)V"));
		node.methods.add(body("drive", DESC, crowd, new MethodInsnNode(INVOKEVIRTUAL, SLOT, "fill", "(I)V", false)));
		if (overloadAfter) node.methods.add(empty("drive", "(J)V"));
		node.methods.add(empty("idle", DESC));
		return node;
	}

	/** The merged host: the same prefix, the call moved into {@code Stage.apply}, which fills the slot last. */
	private static ClassNode mergedHost(Crowd crowd) {
		ClassNode node = node(HOST);
		node.methods.add(body("drive", DESC, crowd, new MethodInsnNode(INVOKESTATIC, STAGE, "apply", DESC, false)));
		node.methods.add(empty("idle", DESC));
		return node;
	}

	private static ClassNode stage() {
		ClassNode node = node(STAGE);
		node.methods.add(body("apply", DESC, Crowd.NONE, new MethodInsnNode(INVOKEVIRTUAL, SLOT, "fill", "(I)V", false)));
		return node;
	}

	private static MethodNode body(String name, String desc, Crowd crowd, MethodInsnNode last) {
		MethodNode method = new MethodNode(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
		InsnList code = method.instructions;
		if (crowd == Crowd.OTHER_OWNER) { code.add(new VarInsnNode(ILOAD, 1)); code.add(new MethodInsnNode(INVOKESTATIC, CROWD, "fill", "(I)V", false)); }
		if (crowd == Crowd.OTHER_DESCRIPTOR) {
			code.add(new VarInsnNode(ALOAD, 0)); code.add(new InsnNode(LCONST_0));
			code.add(new MethodInsnNode(INVOKEVIRTUAL, SLOT, "fill", "(J)V", false));
		}
		code.add(new VarInsnNode(ALOAD, 0)); code.add(new VarInsnNode(ILOAD, 1));
		code.add(last);
		code.add(new InsnNode(RETURN));
		method.maxStack = 3; method.maxLocals = 2;
		return method;
	}

	private static MethodNode empty(String name, String desc) {
		MethodNode method = new MethodNode(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
		method.instructions.add(new InsnNode(RETURN));
		method.maxLocals = Type.getArgumentsAndReturnSizes(desc) >> 2;
		return method;
	}

	private static ClassNode node(String name) {
		ClassNode node = new ClassNode();
		node.version = V21; node.access = ACC_PUBLIC; node.name = name; node.superName = "java/lang/Object";
		return node;
	}

	/** The observer: one static AFTER callback, {@code method = selectors}, its point {@code target}. */
	private static ClassNode watcher(List<String> selectors, String target) {
		ClassNode node = node("org/example/relay/RelayWatcher");
		AnnotationNode mixin = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		mixin.values = new ArrayList<>(List.of("value", List.of(Type.getObjectType(HOST))));
		node.invisibleAnnotations = new ArrayList<>(List.of(mixin));
		MethodNode handler = new MethodNode(ACC_PRIVATE | ACC_STATIC, "afterFill", "(L" + SLOT + ";IL" + CI + ";)V", null, null);
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "INVOKE", "target", target,
				"shift", new String[] {"Lorg/spongepowered/asm/mixin/injection/At$Shift;", "AFTER"}));
		AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
		inject.values = new ArrayList<>(List.of("method", new ArrayList<>(selectors), "at", List.of(at)));
		handler.visibleAnnotations = new ArrayList<>(List.of(inject));
		handler.instructions.add(new InsnNode(RETURN));
		handler.maxLocals = 3;
		node.methods.add(handler);
		return node;
	}

	private static int adapt(ClassNode watcher, ClassNode nativeHost, ClassNode mergedHost) throws Exception {
		ClassNode stage = stage();
		Function<String, ClassNode> current = owner -> owner.equals(HOST) ? mergedHost : owner.equals(STAGE) ? stage : null;
		try (ForbricClassLoader loader = new ForbricClassLoader(new URL[0], getLoader())) {
			try {
				return MixinAbsorbedCallbackTransport.adapt(watcher, current, owner -> owner.equals(HOST) ? nativeHost : null, loader);
			} finally {
				MixinAbsorbedCallbackTransport.release(loader);
			}
		}
	}

	private static ClassLoader getLoader() { return MixinAbsorbedCallbackTransportSpellingTest.class.getClassLoader(); }

	/** Moved: the original handler kept without its injector, a wrap on the helper's call in its place. */
	private static void assertMoved(ClassNode watcher, String why) {
		MethodNode kept = watcher.methods.stream().filter(m -> m.name.contains("$forbricsourcecallback")).findFirst()
				.orElseThrow(() -> new AssertionError(why + ": the callback is kept aside"));
		assertNull(MixinFit.injectorOf(kept), why);
		MethodNode wrap = watcher.methods.stream().filter(m -> MixinFit.injectorOf(m) != null).findFirst().orElseThrow();
		AnnotationNode at = MixinFit.atNodes(MixinFit.injectorOf(wrap)).getFirst();
		assertEquals("L" + STAGE + ";apply" + DESC, MixinFit.value(at, "target"), why);
		assertEquals(List.of("drive" + DESC), MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(wrap), "method")), why);
	}

	private static void assertUnchanged(List<String> selectors, String point, ClassNode nativeHost, ClassNode mergedHost, String why) throws Exception {
		ClassNode watcher = watcher(selectors, point);
		byte[] before = TransportSpellings.bytes(watcher);
		assertEquals(0, adapt(watcher, nativeHost, mergedHost), why);
		assertArrayEquals(before, TransportSpellings.bytes(watcher), why + ": the observer is not touched");
	}

	@Test void writtenAsTheSampleMovesAsBefore() throws Exception {
		ClassNode watcher = watcher(List.of("drive" + DESC), FULL);
		assertEquals(1, adapt(watcher, nativeHost(Crowd.NONE, false, false), mergedHost(Crowd.NONE)));
		assertMoved(watcher, "name and descriptor, full point");
	}

	/** Every way Mixin binds {@code drive}: before, only {@code drive} and {@code drive(…)V} as written found it. */
	@Test void aSelectorWrittenAnotherWayBindsTheSameHost() throws Exception {
		for (List<String> selectors : List.of(
				List.of("L" + HOST + ";drive" + DESC),
				List.of(HOST.replace('/', '.') + ".drive"),
				List.of(" " + HOST.replace('/', '.') + " . drive " + DESC),
				List.of("drive", "L" + HOST + ";drive" + DESC))) {
			ClassNode watcher = watcher(selectors, FULL);
			assertEquals(1, adapt(watcher, nativeHost(Crowd.NONE, false, false), mergedHost(Crowd.NONE)), selectors.toString());
			assertMoved(watcher, selectors.toString());
		}
	}

	/** A bare name binds the first {@code drive} declared: an overload after it no longer hides the host (before: two matches). */
	@Test void aBareNameBindsTheFirstMethodOfThatName() throws Exception {
		ClassNode watcher = watcher(List.of("drive"), FULL);
		assertEquals(1, adapt(watcher, nativeHost(Crowd.NONE, false, true), mergedHost(Crowd.NONE)));
		assertMoved(watcher, "bare name, overload declared after");
	}

	/** The point spaced, dotted, or without the owner or descriptor that the written method decides. */
	@Test void aPointWrittenAnotherWayNamesTheSameCall() throws Exception {
		for (String point : List.of(" L" + SLOT + "; fill (I)V", SLOT.replace('/', '.') + ".fill(I)V", "fill(I)V", "L" + SLOT + ";fill",
				" " + SLOT.replace('/', '.') + " . fill", "fill")) {
			ClassNode watcher = watcher(List.of("drive"), point);
			assertEquals(1, adapt(watcher, nativeHost(Crowd.NONE, false, false), mergedHost(Crowd.NONE)), point);
			assertMoved(watcher, point);
		}
	}

	// ---- look-alikes: left alone ---------------------------------------------------------------------------------------

	@Test void aSelectorForAnotherOwnerOrMethodIsLeftAlone() throws Exception {
		assertUnchanged(List.of("Lorg/example/elsewhere/Other;drive" + DESC), FULL, nativeHost(Crowd.NONE, false, false), mergedHost(Crowd.NONE),
				"a selector naming another owner");
		assertUnchanged(List.of("idle"), FULL, nativeHost(Crowd.NONE, false, false), mergedHost(Crowd.NONE), "a selector binding another method");
		assertUnchanged(List.of("L" + HOST + ";idle" + DESC), FULL, nativeHost(Crowd.NONE, false, false), mergedHost(Crowd.NONE),
				"an owner-qualified selector binding another method");
	}

	/** The first {@code drive} declared is the overload, which fills no slot: Mixin binds it, not the host. */
	@Test void aBareNameWhoseFirstMethodIsAnotherOverloadIsLeftAlone() throws Exception {
		assertUnchanged(List.of("drive"), FULL, nativeHost(Crowd.NONE, true, false), mergedHost(Crowd.NONE), "bare name, overload declared first");
	}

	/** A point naming another owner's or another descriptor's call selects nothing the host moved. */
	@Test void aPointNamingAnotherMemberIsLeftAlone() throws Exception {
		for (String point : List.of("L" + CROWD + ";fill(I)V", "L" + SLOT + ";fill(J)V", "org.example.elsewhere.Crowd.fill"))
			assertUnchanged(List.of("drive"), point, nativeHost(Crowd.NONE, false, false), mergedHost(Crowd.NONE), point);
	}

	/**
	 * Where the host also calls {@code fill} on another owner (or with another descriptor), a point without that owner
	 * (descriptor) selects two calls there: not this callback. The full spelling still names the one call and moves.
	 */
	@Test void aShortPointThatAlsoSelectsAnotherCallIsLeftAlone() throws Exception {
		assertUnchanged(List.of("drive"), "fill(I)V", nativeHost(Crowd.OTHER_OWNER, false, false), mergedHost(Crowd.OTHER_OWNER), "no owner, beside Crowd.fill(I)V");
		assertUnchanged(List.of("drive"), "L" + SLOT + ";fill", nativeHost(Crowd.OTHER_DESCRIPTOR, false, false), mergedHost(Crowd.OTHER_DESCRIPTOR),
				"no descriptor, beside Slot.fill(J)V");
		for (Crowd crowd : List.of(Crowd.OTHER_OWNER, Crowd.OTHER_DESCRIPTOR)) {
			ClassNode watcher = watcher(List.of("drive"), FULL);
			assertEquals(1, adapt(watcher, nativeHost(crowd, false, false), mergedHost(crowd)), "control: the full spelling beside " + crowd);
		}
	}
}
