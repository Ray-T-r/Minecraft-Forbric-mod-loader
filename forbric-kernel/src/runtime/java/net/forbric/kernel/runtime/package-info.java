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
 * The kernel's GAME-side half — the only kernel code that may name {@code net.minecraft.*},
 * {@code net.minecraftforge.*} and {@code net.neoforged.*} as TYPES rather than as strings.
 *
 * <h2>Why this package exists</h2>
 *
 * <p>The boot side is parent-loaded and the game is not, so boot-side code cannot name a game type at all: every
 * cross-ecosystem hook written up there has to be {@code Object} in, {@code Object} out, reached by
 * {@code Class.forName} + {@code getMethod} + {@code invoke}, or synthesized with ASM at runtime. That is not a
 * style choice, it is the only thing the classloader boundary permits — and it costs the kernel its compiler.
 * A misspelled method name in a reflective shim is not an error, it is a {@code NoSuchMethodException} caught
 * three frames away, or worse a {@link java.lang.reflect.Proxy} whose {@code switch} on method NAMES silently
 * falls through to a default and answers "nothing" forever.
 *
 * <p>Classes compiled into this package are compiled AGAINST the staged jars (the merged base plus the two
 * ecosystem runtime carriers) and loaded BY {@code ForbricClassLoader}, so javac checks them. A renamed SPI
 * method becomes a build failure here instead of a silent wrong answer at runtime.
 *
 * <h2>What may and may not live here</h2>
 *
 * <p>Only what the staged jars actually define. The compile classpath of this source set is the three staged
 * jars and the boot-side output — nothing else. In particular:
 *
 * <ul>
 *   <li><b>Yes:</b> public types of the merged base, and public types of the two carriers
 *       ({@code net.neoforged.fml.ModContainer}, {@code net.neoforged.neoforgespi.language.IModInfo},
 *       {@code net.neoforged.bus.api.IEventBus}, {@code net.minecraftforge.*} equivalents).
 *   <li><b>No:</b> anything from fabric-api. It is a mod the USER installs, not a staged artifact, so it is not
 *       on this classpath and never will be. A class that must implement a fabric-api interface (the HUD layer
 *       that is simultaneously a fabric-api {@code HudElement} and a NeoForge {@code GuiLayer}) still has to be
 *       synthesized with ASM — see {@code KernelHudBridge}. That is not technical debt, it is the boundary.
 *   <li><b>No:</b> package-private carrier types. {@code net.minecraftforge.registries.NamespacedWrapper} and
 *       {@code NamespacedDefaultedWrapper} carry no {@code public} modifier, so outside code cannot name them
 *       at all; the registry-parity work stays bytecode surgery for that reason.
 * </ul>
 *
 * <h2>How it is delivered</h2>
 *
 * <p>Compiled to {@code forbric-kernel-runtime.jar} and carried INSIDE the boot jar at
 * {@code META-INF/jars/}, from where {@code KernelBundledJars} extracts it and hands it to
 * {@code ForbricClassLoader} as an owned jar. {@code DelegationPolicy} pins this package to
 * {@code ALWAYS_GAME}, so it can only ever be defined by that loader.
 *
 * <p>The embedding is wired at Gradle CONFIGURATION time and only when the staged jars are present, so a clone
 * with no staged artifacts still builds the boot jar and passes the boot-side gate — and loses nothing it could
 * have used, because a machine that cannot build this jar also cannot run the game it links against.
 */
package net.forbric.kernel.runtime;
