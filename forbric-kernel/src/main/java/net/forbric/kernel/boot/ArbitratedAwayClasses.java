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

package net.forbric.kernel.boot;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.forbric.api.Ecosystem;

/**
 * The classes that exist only in the build of a duplicated mod the kernel did not load.
 *
 * <p>When one mod id is claimed by two jars — a Fabric build and a NeoForge build of the same mod — exactly one
 * is loaded, and {@link DuplicateModArbiter} already measures what that costs: sodium's losing FABRIC build
 * carries 727 classes the winning NEOFORGE build does not. Until now that measurement was a log line and
 * nothing else read it.
 *
 * <p>It needs a reader, because those 727 classes are a hole only this instance has. Iris ships
 * {@code mixins.iris.fabric.json:MixinFluidRendererImpl}, {@code @Mixin(sodium.fabric.render.FluidRendererImpl)},
 * whose whole job is to implant the {@code VertexEncoderInterface} that Iris then casts the fluid renderer to.
 * With sodium's NeoForge build loaded that target does not exist, so the mixin is inert — silently, because a
 * mixin whose target never loads is not an error anywhere. The player turned shaders on two minutes after a
 * boot that reported every mod loaded, and got
 * {@code ClassCastException: sodium.neoforge.render.FluidRendererImpl cannot be cast to VertexEncoderInterface}
 * on the first chunk of water.
 *
 * <p>Neither loader can produce that pairing — it exists because this one put a Fabric mod and a NeoForge mod in
 * the same game and then chose between two builds of what they both depend on. So naming it is the kernel's job,
 * and the remedy is the kernel's own lever: pick the other build in {@code forbric-mods.txt}, or install the
 * dependent's build from the same family.
 */
public final class ArbitratedAwayClasses {
	/**
	 * {@code -Dforbric.arbitratedAwayMixins=off} turns the mixin-side warning off.
	 *
	 * <p>It was OFF while the measurement behind it was inexact, and the two rounds it took are worth keeping,
	 * because both errors pointed the same way — over-reporting — and both would have put a wrong line on the
	 * Mods screen:
	 * <ul>
	 * <li>the class sets were compared TOP-LEVEL ONLY, so a build that nests its shared half looked like it was
	 *     missing hundreds of classes the other build plainly had (sodium's NeoForge build bundles the common
	 *     {@code sodium.client.*} in {@code META-INF/jars/}); fixed by recursing into bundled jars;</li>
	 * <li>"only the losing build has it" was read as "nothing in this instance has it". A losing build routinely
	 *     bundles a THIRD mod's classes — sodium's Fabric build ships fabric-api's
	 *     {@code ExtendedBlockModelSubmit}, which the player's own fabric-api supplies regardless; fixed by
	 *     subtracting every class the jars that did load provide, in
	 *     {@code DuplicateModArbiter.classesStillLoaded}.</li>
	 * <li>and the same mistake once more with a different supplier: a losing build that shades a LIBRARY the
	 *     game's own {@code libraries/} serves anyway. glitchcore's Fabric build carries all 189
	 *     {@code com.electronwill.nightconfig.core.*} classes, and after the first two fixes those 189 were still
	 *     the entire recorded set for that mod; fixed by {@code DuplicateModArbiter.onTheLaunchClasspath}.</li>
	 * </ul>
	 * Each round was found by reading what the live 28-mod instance actually recorded rather than by reasoning
	 * about it, and each time the residue looked plausible until it was read. The counts on that instance went
	 * 4 mods marked → 2 → 1 (sodium, 481 loser-only classes narrowed to 19) → 0, which is correct for it today:
	 * the one true finding this was written for, iris' {@code MixinFluidRendererImpl} against sodium's
	 * Fabric-only {@code FluidRendererImpl}, went away when iris' NeoForge build replaced its Fabric build. The
	 * true-positive path is held by {@code KernelGuestMixinAdapterTest} instead.
	 */
	static final String PROPERTY = "forbric.arbitratedAwayMixins";

	public static boolean warningEnabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** Dotted class name → which arbitration removed it. */
	private static final Map<String, Loss> LOST = new ConcurrentHashMap<>();

	/**
	 * One arbitrated-away class's provenance.
	 *
	 * @param modId    the duplicated mod id
	 * @param loser    the ecosystem whose build was not loaded, and which had this class
	 * @param winner   the ecosystem whose build was loaded, or null when it could not be named
	 * @param loserJar the file name of the build that was not loaded
	 */
	public record Loss(String modId, Ecosystem loser, Ecosystem winner, String loserJar) {
		/** One sentence for whoever reads the failure, naming the lever that changes it. */
		public String describe(String className) {
			return className + " is a class only " + modId + "'s " + loser + " build (" + loserJar + ") has, and "
					+ "this instance loaded its " + (winner == null ? "other" : winner.toString()) + " build";
		}
	}

	private ArbitratedAwayClasses() {
	}

	/**
	 * Records what one arbitration removed. Called by {@link DuplicateModArbiter} as it reports it.
	 *
	 * <p>Public so the mixin layer's test can seed the one fact this registry exists to carry; nothing else
	 * outside this package writes it.
	 */
	public static void record(List<String> classNames, Loss loss) {
		for (String name : classNames) LOST.putIfAbsent(name, loss);
		// Said out loud because the reader is in another package loaded at another moment: if this line is absent
		// when a guest mixin scan runs, the registry the scan consults is not the one this wrote.
		List<String> shown = classNames.subList(0, Math.min(6, classNames.size()));
		net.forbric.kernel.util.ForbricLog.info("[Forbric/DupeId] remembered %d class(es) only %s's %s build has, "
				+ "so a guest mixin that targets one can be named rather than going silently inert (%d known): "
				+ "%s%s", classNames.size(), loss.modId(), loss.loser(), LOST.size(), String.join(", ", shown),
				classNames.size() > shown.size() ? ", …" : "");
	}

	/** What removed {@code className}, or null when no arbitration did. */
	public static Loss lost(String className) {
		return LOST.get(className);
	}

	/** How many classes an arbitration removed; a diagnostics and test seam. */
	public static int size() {
		return LOST.size();
	}

	/** Test seam. */
	public static void reset() {
		LOST.clear();
	}
}
