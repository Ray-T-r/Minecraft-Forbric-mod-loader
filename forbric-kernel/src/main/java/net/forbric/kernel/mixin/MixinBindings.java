/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.List;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;

/**
 * The shared readers of {@link MixinTargetSelectors} and {@link MixinCallbackShape}, for the kernel's transformers and
 * boot proofs outside this package: which method an injector binds, and what an {@code @At} target names, decided the
 * way Mixin decides them, never by how either is spelled. Nothing here reads a selector or a target itself.
 */
public final class MixinBindings {
	private MixinBindings() {
	}

	/** The one method of {@code target} the handler's injector binds; null when it binds none, several, or cannot be judged. */
	public static MethodNode bound(MethodNode handler, ClassNode target) {
		return target == null ? null : MixinTargetSelectors.one(handler, target);
	}

	/** The methods of {@code target} that {@code selectors} bind for a handler of the given staticness; null where Mixin refuses them. */
	public static List<MethodNode> bound(List<String> selectors, ClassNode target, boolean staticHandler) {
		return MixinTargetSelectors.bound(selectors, target, staticHandler);
	}

	/** Whether two injectors are on the same host: each binds exactly one method of {@code target}, and it is the same one. */
	public static boolean sameHost(MethodNode first, MethodNode second, ClassNode target) {
		MethodNode host = bound(first, target);
		return host != null && host == bound(second, target);
	}

	/** {@link MixinCallbackShape#covers}: whether {@code at}'s target can name {@code member}, however it is spelled. */
	public static boolean covers(AnnotationNode at, String member) {
		return MixinCallbackShape.covers(at, member);
	}

	/** {@link MixinCallbackShape#names}: whether {@code at}'s target names {@code member} and nothing else, read in {@code body}. */
	public static boolean names(AnnotationNode at, String member, MethodNode body) {
		return MixinCallbackShape.names(at, member, body);
	}

	/** {@link MixinCallbackShape#selected}: the instructions of {@code body} whose member {@code at}'s target names. */
	public static List<AbstractInsnNode> selected(AnnotationNode at, MethodNode body) {
		return MixinCallbackShape.selected(at, body);
	}

	/**
	 * The class {@code mixin}'s mod was compiled against for {@code owner}: its own platform's native game, where the
	 * mixin's platform is recorded and the native references are bound; null otherwise.
	 */
	public static ClassNode nativeClass(String mixin, String owner) {
		Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin);
		return ecosystem == null ? null : NativeGameReferences.reference(ecosystem, owner);
	}
}
