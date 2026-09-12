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

package net.forbric.kernel.runtime;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.Type;

import net.forbric.kernel.discovery.ModFileScanner;
import net.neoforged.neoforgespi.language.ModFileScanData;

/**
 * Turns the kernel's own annotation scan into NeoForge's {@code ModFileScanData} — the index FML hands to mods,
 * and the only way several of them find their own extensions.
 *
 * <p>The scanning itself is pure bytecode work and stays on the boot side in {@code ModFileScanner}. Only this
 * last step needs game types, and doing it from up there took three {@code Class.forName} calls, two constructor
 * lookups written out by exact parameter list, and two {@code getMethod} calls — six independent strings holding
 * up an index whose ONLY failure mode is silence. Nothing throws when an index comes out wrong or empty; the mods
 * reading it simply find nothing. JEI is the loud exception, and only by accident: its {@code ForgePluginFinder}
 * turns an empty result into {@code IllegalArgumentException: plugins must not be empty} out of its own
 * {@code @Mod} constructor. Jade, Sophisticated Core and Sodium's third-party config hooks fail silently, which
 * is worse.
 *
 * <p>So these two constructors being checked by javac is the whole point of this file. If a future NeoForge
 * reorders {@code AnnotationData}'s five components, this stops compiling. Before, it kept working and every mod
 *'s plugin list went quietly empty.
 */
public final class KernelScanData {
	private KernelScanData() {
	}

	/**
	 * Builds the index from the boot side's scan.
	 *
	 * <p>The parameter types cross the boot/game seam safely for a reason worth stating: {@code ModFileScanner}'s
	 * records live in {@code net.forbric.kernel.discovery} and {@link Type} in {@code org.objectweb.asm}, and
	 * {@code DelegationPolicy} pins BOTH to the parent. So each is exactly one class in the JVM and the two sides
	 * are naming the same thing. A type that were game-side, or loadable from either side, could not be written
	 * into this signature at all.
	 */
	public static Object build(List<ModFileScanner.Found> found, List<ModFileScanner.ClassEntry> classes) {
		ModFileScanData scanData = new ModFileScanData();

		Set<ModFileScanData.AnnotationData> annotations = scanData.getAnnotations();
		for (ModFileScanner.Found f : found) {
			annotations.add(new ModFileScanData.AnnotationData(
					Type.getType(f.annotationDesc()),
					f.target(),
					Type.getObjectType(f.ownerInternalName()),
					f.memberName(),
					f.values()));
		}

		Set<ModFileScanData.ClassData> classSet = scanData.getClasses();
		for (ModFileScanner.ClassEntry c : classes) {
			classSet.add(new ModFileScanData.ClassData(c.name(), c.parent(), c.interfaces()));
		}

		return scanData;
	}
}
