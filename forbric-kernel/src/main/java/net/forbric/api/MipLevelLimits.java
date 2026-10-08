/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.api;

/** Shared texture constraint: an allocation cannot request levels beyond its smallest supported image. */
public final class MipLevelLimits {
	private MipLevelLimits() { }
	public static int bounded(int selected, int maximum) { return Math.min(selected, maximum); }
}
