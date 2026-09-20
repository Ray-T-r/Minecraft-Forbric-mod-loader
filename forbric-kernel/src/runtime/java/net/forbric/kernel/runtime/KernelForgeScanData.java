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
import net.minecraftforge.forgespi.language.ModFileScanData;

/**
 * The MinecraftForge twin of {@link KernelScanData}: the same boot-side scan, materialised into traditional
 * Forge's {@code ModFileScanData} instead of NeoForge's.
 *
 * <p><b>Why it exists.</b> A seeded MinecraftForge {@code ModFile} used to carry an EMPTY scan data — present so
 * {@code ModList.getAllScanData()} and Forge's own {@code CapabilityManager.injectCapabilities} would not meet a
 * null, but holding nothing. Every traditional-Forge mod that finds its own members through that index therefore
 * found none, silently. SuperMartijn642's Core Lib is the case that paid for this file: it injects a mod's
 * {@code @RegistryEntryAcceptor} static fields from {@code ModList.getAllScanData()}, so with an empty index
 * Packed Up's {@code MenuType} field stayed null and the CLIENT died in {@code Minecraft.<init>} with
 * "Container screen registered with null menu type!" — a crash whose text names neither the index nor the kernel.
 *
 * <p>The two SPIs are separate classes with identical shapes, which is exactly why this is a second compiled file
 * and not a shared reflective helper: {@code AnnotationData}'s five components and {@code ClassData}'s three are
 * checked by javac here, on the SPI this half actually targets. A reflective builder written once against "the"
 * record would keep compiling while one of the two reordered.
 */
public final class KernelForgeScanData {
	private KernelForgeScanData() {
	}

	/** Builds MinecraftForge's index from the boot side's scan. See {@link KernelScanData#build} for the seam. */
	public static Object build(List<ModFileScanner.Found> found, List<ModFileScanner.ClassEntry> classes) {
		ModFileScanData scanData = new ModFileScanData();

		Set<ModFileScanData.AnnotationData> annotations = scanData.getAnnotations();
		for (ModFileScanner.Found f : found) {
			annotations.add(new ModFileScanData.AnnotationData(
					Type.getType(f.annotationDesc()),
					f.target(),
					Type.getObjectType(f.ownerInternalName()),
					f.memberName(),
					// MinecraftForge's own scanner stores an enum member as ModFileScanData.EnumData(Type, String).
					// SuperMartijn642's Core Lib casts straight to it, so a String here is a ClassCastException the
					// moment this index stops being empty.
					ModFileScanner.wrapEnums(f.values(), (desc, value) ->
							new ModFileScanData.EnumData(Type.getType(desc), value))));
		}

		Set<ModFileScanData.ClassData> classSet = scanData.getClasses();
		for (ModFileScanner.ClassEntry c : classes) {
			classSet.add(new ModFileScanData.ClassData(c.name(), c.parent(), c.interfaces()));
		}

		return scanData;
	}
}
