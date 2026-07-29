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

package net.forbric.loader.impl.transformer;

import net.fabricmc.api.EnvType;

/**
 * Immutable, per-invocation context handed to every {@link ClassTransformer}.
 *
 * <p>It lets transformers written for either ecosystem share one call signature: a Fabric built-in,
 * a Forge coremod, and a remapper all read the same environment, dev flag, canonical runtime
 * namespace, and the origin of the class currently being transformed.
 */
public final class TransformContext {
	/** Which loader a class (or transformer) originates from. */
	public enum Ecosystem {
		/** Vanilla game class. */
		GAME,
		/** A Fabric mod class. */
		FABRIC,
		/** A Forge mod class. */
		FORGE,
		/** Origin not yet attributed. */
		UNKNOWN
	}

	private final EnvType envType;
	private final boolean development;
	private final String runtimeNamespace;
	private final Ecosystem ecosystem;
	private final String sourceModId;

	public TransformContext(EnvType envType, boolean development, String runtimeNamespace) {
		this(envType, development, runtimeNamespace, Ecosystem.UNKNOWN, null);
	}

	public TransformContext(EnvType envType, boolean development, String runtimeNamespace, Ecosystem ecosystem, String sourceModId) {
		if (envType == null) throw new NullPointerException("envType");
		if (runtimeNamespace == null) throw new NullPointerException("runtimeNamespace");
		if (ecosystem == null) throw new NullPointerException("ecosystem");

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

	/** The ecosystem the class being transformed belongs to. */
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
				+ ", ecosystem=" + ecosystem
				+ ", mod=" + sourceModId
				+ '}';
	}
}
