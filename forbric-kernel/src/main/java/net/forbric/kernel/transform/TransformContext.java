/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.transform;

import net.fabricmc.api.EnvType;

import net.forbric.api.Ecosystem;

/**
 * Immutable, per-invocation context handed to every {@link ClassTransformer}.
 *
 * <p><b>{@link #getEcosystem()} is attribution the chain does not yet fill in.</b> It was declared with the SPI and
 * never wired: {@code withSource} had no callers and {@code getEcosystem} no readers, so every transformer ran as
 * "unknown" and none could ask which ecosystem a class came from. That is a large part of why they identify
 * ecosystems by hardcoding their class names instead. It now names the one {@link Ecosystem} type rather than a
 * private four-valued enum that was missing NeoForge, so a consumer can be given one.
 *
 * <p>It lets transformers written for either ecosystem share one call signature: a Fabric built-in,
 * a Forge coremod, and a remapper all read the same environment, dev flag, canonical runtime
 * namespace, and the origin of the class currently being transformed.
 */
public final class TransformContext {
	private final EnvType envType;
	private final boolean development;
	private final String runtimeNamespace;
	private final Ecosystem ecosystem;
	private final String sourceModId;

	public TransformContext(EnvType envType, boolean development, String runtimeNamespace) {
		this(envType, development, runtimeNamespace, null, null);
	}

	public TransformContext(EnvType envType, boolean development, String runtimeNamespace, Ecosystem ecosystem, String sourceModId) {
		if (envType == null) throw new NullPointerException("envType");
		if (runtimeNamespace == null) throw new NullPointerException("runtimeNamespace");

		this.envType = envType;
		this.development = development;
		this.runtimeNamespace = runtimeNamespace;
		this.ecosystem = ecosystem;
		this.sourceModId = sourceModId;
	}

	/** The physical side this process is running as. */
	public EnvType getEnvType() {
		return envType;
	}

	/** Whether the loader is running in a development (named-mappings) environment. */
	public boolean isDevelopment() {
		return development;
	}

	/** The canonical namespace all loaded bytecode is remapped to (default {@code "intermediary"}). */
	public String getRuntimeNamespace() {
		return runtimeNamespace;
	}

	/**
	 * The ecosystem the class being transformed belongs to, or {@code null} for a game class or one whose origin
	 * was not attributed.
	 *
	 * <p>Null rather than a {@code GAME}/{@code UNKNOWN} constant: those are not ecosystems, and putting them in
	 * the shared vocabulary would force every switch over {@link Ecosystem} to handle two cases that can never
	 * name a mod.
	 */
	public Ecosystem getEcosystem() {
		return ecosystem;
	}

	/** The id of the mod the class belongs to, or {@code null} if unknown / a game class. */
	public String getSourceModId() {
		return sourceModId;
	}

	/** Returns a copy attributed to the given origin. */
	public TransformContext withSource(Ecosystem ecosystem, String sourceModId) {
		return new TransformContext(envType, development, runtimeNamespace, ecosystem, sourceModId);
	}

	@Override
	public String toString() {
		return "TransformContext{env=" + envType
				+ ", dev=" + development
				+ ", ns=" + runtimeNamespace
				+ ", ecosystem=" + (ecosystem == null ? "game" : ecosystem)
				+ ", mod=" + sourceModId
				+ '}';
	}
}
