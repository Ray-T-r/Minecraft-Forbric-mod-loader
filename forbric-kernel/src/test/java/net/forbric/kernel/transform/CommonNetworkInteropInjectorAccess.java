/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Test access to CommonNetworkInteropInjector's package-private repairs from other packages. */
public final class CommonNetworkInteropInjectorAccess {
	private CommonNetworkInteropInjectorAccess() {
	}

	public static boolean dropRepeatedDeclarations(ClassNode owner, MethodNode send) {
		return CommonNetworkInteropInjector.dropRepeatedDeclarations(owner, send);
	}
}
