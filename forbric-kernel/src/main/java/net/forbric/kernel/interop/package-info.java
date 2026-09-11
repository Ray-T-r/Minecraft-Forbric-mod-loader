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
 * The reflective hooks that GUEST bytecode calls into.
 *
 * <p>Three classes, each the landing site of an {@code INVOKESTATIC} the kernel splices into someone else's class:
 * {@link net.forbric.kernel.interop.PayloadInterop} for the custom-payload codec and dispatch split between
 * Fabric API, MinecraftForge and NeoForge; {@link net.forbric.kernel.interop.ClientShutdown} for the non-daemon
 * executors a session leaves behind; {@link net.forbric.kernel.interop.ForgeRuntimeInterop} for the
 * Forge/NeoForge {@code FluidType} ABI split.
 *
 * <h2>Why they are boot-side, and why every parameter is {@code Object}</h2>
 *
 * <p>They name no game type. Every Minecraft, Fabric, MinecraftForge and NeoForge class they touch is reached
 * reflectively through the class loader of the object they were handed, which is what lets them sit on the BOOT
 * side of the kernel's delegation split while being called from the GAME side — see
 * {@code DelegationPolicy}, which pins this package to the parent. That pinning is load-bearing: each of these
 * holds process-wide state keyed by {@code ClassLoader}, and a second copy defined game-side would start that
 * bookkeeping over from empty while the first copy still believed it had done the work.
 *
 * <p>{@code Object} in, {@code Object} out is the consequence, not the design: a boot-side method cannot name a
 * game-side type in its signature. It is the shape the kernel is trying to grow out of — the typed landing place
 * on the game side is what {@code src/runtime/java} is for — so treat every reflective lookup here as a debt
 * entry, not a pattern to copy.
 *
 * <h2>They came from the previous generation</h2>
 *
 * <p>These were the differential oracle's, supplied to the kernel as two {@code runtimeOnly} jars from the other
 * repository. That made the kernel's runtime depend on a repo it does not build, and made a class the merged base
 * hard-references invisible to the kernel's own tests. They are kernel classes now; the merged base still names
 * the old owners, and {@code ForbricMergedBaseCompatTransformer} retargets those calls on the way in.
 */
package net.forbric.kernel.interop;
