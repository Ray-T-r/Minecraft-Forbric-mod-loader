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

/**
 * The unified Forbric API: the vocabulary and services the kernel and all three compatibility layers align to,
 * instead of accommodating each other pairwise.
 *
 * <p>It is an INTERNAL spine today — nothing is published and no stability is promised — but it is written to the
 * standard of something that will be, because the alternative is a rename later that touches every call site.
 *
 * <h2>Where it sits</h2>
 *
 * <p>BOOT side, parent-pinned in {@code DelegationPolicy} for the same reason {@code net.fabricmc.api.} is: there
 * must be exactly ONE copy per JVM, or an {@link net.forbric.api.Ecosystem} constant handed across the boot/game
 * boundary stops equalling itself. Consequently nothing here may name a game type — {@code net.minecraft.*},
 * {@code net.minecraftforge.*}, {@code net.neoforged.*}, {@code net.fabricmc.fabric.*}. Game objects cross as
 * {@code java.lang.Object}, which is the convention the kernel's hooks already used before this package existed.
 *
 * <h2>Two rules it is built on, both learned the hard way</h2>
 *
 * <ol>
 *   <li><b>The hub carries per-family divergence as DATA; it does not average it away.</b> An earlier attempt at a
 *       unified subscriber registration broke three things at once, one of which silently cost Architectury its
 *       entire event layer on a dedicated server ({@code KernelEventSubscribers}' javadoc has the account). So
 *       {@link net.forbric.api.ForeignType} maps <em>names</em> only, and the two {@code ServerModLoader.load}
 *       triggers keep their different descriptors and different hooks.</li>
 *   <li><b>Not every grouping is a missing split.</b> {@code LoaderProbePolicy.Family} stays two-valued on
 *       purpose: a NeoForge mod probing for MinecraftForge's {@code FMLLoader} must still be told yes.</li>
 * </ol>
 *
 * <h2>On the dependency back into {@code net.forbric.kernel.util.ForbricLog}</h2>
 *
 * <p>Two classes here log through it, and that reads backwards for a package meant to be public. It is left as
 * it is, deliberately: {@code ForbricLog} appears only inside method bodies and in no public signature, so
 * nothing compiling against this package's surface needs it; and it is self-contained, importing only JDK types
 * and resolving log4j reflectively. The leak is therefore cosmetic rather than a packaging or classloading
 * problem, and inventing a logging seam for four call sites would be churn. Revisit it if a public artifact is
 * ever actually cut, not before.
 */
package net.forbric.api;
