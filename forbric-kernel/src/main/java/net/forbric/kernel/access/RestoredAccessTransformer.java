/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.access;

import net.forbric.kernel.transform.ClassTransformer;
import net.forbric.kernel.transform.TransformContext;

/** At the start of FABRIC_BUILTIN: COREMOD has restored members, Mixin has not observed them yet. */
public final class RestoredAccessTransformer implements ClassTransformer {
	private final ClassTweakerTransformer fabric;
	private final AccessTransformer forge;
	public RestoredAccessTransformer(ClassTweakerTransformer fabric, AccessTransformer forge) {
		this.fabric = fabric;
		this.forge = forge;
	}
	@Override public String name() { return "forbric-restored-access"; }
	@Override public byte[] transform(String name, byte[] bytes, TransformContext context) {
		if (bytes == null || bytes.length == 0) return bytes;
		if (fabric != null) bytes = fabric.replayRestored(name, bytes);
		if (forge != null) bytes = forge.replayRestored(name, bytes, context);
		return bytes;
	}
}
