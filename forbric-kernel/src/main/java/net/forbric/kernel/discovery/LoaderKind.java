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

package net.forbric.kernel.discovery;

import net.forbric.api.Ecosystem;

/**
 * The loader a jar's manifest is written for — the three a jar can declare, where {@link Ecosystem} is the ones Forbric
 * loads.
 *
 * <p>A jar still declares MinecraftForge: {@code META-INF/mods.toml} is in thousands of jars, {@link LoaderHalfJudge}
 * reads it to decide whether the jar is refused, and the refusal names it to the player. But nothing loads it any more,
 * so it is not an ecosystem, and spelling a refused jar's half as one is how a refusal ends up reading as a claim. Here
 * the MinecraftForge half has a name of its own, {@link #MINECRAFTFORGE}, whose {@link #ecosystem()} is {@code null}:
 * no code can hand it to a loader by accident.
 *
 * <p>Declared in {@link Ecosystem}'s order, MinecraftForge between Fabric and NeoForge, so a table that used to be
 * sorted by the half's ecosystem (the recorded metadata failures) sorts exactly as it did.
 */
public enum LoaderKind {
	/** {@code fabric.mod.json}. */
	FABRIC(Ecosystem.FABRIC, "FABRIC"),
	/** {@code META-INF/mods.toml}: traditional MinecraftForge, which Forbric no longer loads. */
	MINECRAFTFORGE(null, "FORGE"),
	/** {@code META-INF/neoforge.mods.toml}. */
	NEOFORGE(Ecosystem.NEOFORGE, "NEOFORGE");

	private final Ecosystem ecosystem;
	private final String spelling;

	LoaderKind(Ecosystem ecosystem, String spelling) {
		this.ecosystem = ecosystem;
		this.spelling = spelling;
	}

	/** The ecosystem that loads a half of this kind, or {@code null} for {@link #MINECRAFTFORGE}: nothing does. */
	public Ecosystem ecosystem() {
		return ecosystem;
	}

	/**
	 * How this loader is written in log lines, finding evidence and the sweep tool's table: the name the half had while
	 * it was an {@link Ecosystem}, {@code FORGE} for MinecraftForge. Gates pin those words ({@code family=FORGE},
	 * "suppressing [FORGE]"), and {@code run/diff-oracle.sh} pins the spelling independently of kernel code.
	 */
	public String spelling() {
		return spelling;
	}

	/**
	 * The kind of a half recorded under {@code ecosystem}. {@code Ecosystem.FORGE} still reaches here from the one place
	 * that spells a manifest by it — discovery recording that a universal jar's {@code mods.toml} cannot be read — until
	 * that constant goes.
	 */
	public static LoaderKind of(Ecosystem ecosystem) {
		if (ecosystem == null) return null;
		return switch (ecosystem) {
			case FABRIC -> FABRIC;
			case FORGE -> MINECRAFTFORGE;
			case NEOFORGE -> NEOFORGE;
		};
	}
}
