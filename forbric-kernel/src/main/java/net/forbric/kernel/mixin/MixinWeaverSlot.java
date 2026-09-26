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

package net.forbric.kernel.mixin;

import java.util.function.Supplier;

import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

/**
 * The one place the class pipeline asks which Mixin transformer to weave with — so a guest that REPLACES that
 * transformer, the way it does on NeoForge, is honoured.
 *
 * <h2>Why a slot at all</h2>
 *
 * <p>On NeoForge the live weaver sits in a field: {@code FMLMixinClassProcessor.transformer}, which the processor
 * reads again for every class it handles. LibJF's ASM layer depends on exactly that — its mixin plugin walks
 * {@code TransformingClassLoader} to that field, wraps what it finds in its own {@code AsmTransformer}, and writes
 * the wrapper back, so every class after that goes through LibJF's patches and then Mixin. The kernel captured its
 * transformer once, in a local the define lambda closed over, so there was nothing a guest could replace: even with
 * the rest of that object graph in place, the wrapper would have been written into a field nobody reads.
 *
 * <p>The graph itself is {@code KernelFmlTransformerView}, game-side, which hands the guest a
 * {@code TransformingClassLoader} whose processor holds {@link #original()} and registers a reader of that field
 * here with {@link #watch}. From then on {@link #currentOr} answers with whatever the field holds, so a wrapper —
 * or a wrapper of a wrapper — is what weaves.
 *
 * <h2>What it costs everyone else</h2>
 *
 * <p>Nothing that shows: until a view is handed out there is no reader, and {@link #currentOr} is one volatile read
 * that returns the transformer it was given. {@code MixinEnvironment.setActiveTransformer}, which LibJF also calls,
 * is deliberately NOT a way in: it does not reroute weaving on NeoForge either.
 *
 * <p>{@code -Dforbric.fmlTransformerView=off} hands out no view, so the guest fails its cast exactly as before.
 */
public final class MixinWeaverSlot {
	/** {@code -Dforbric.fmlTransformerView=off}: no view, no slot, LibJF's ASM layer fails to start as before. */
	public static final String SWITCH = "forbric.fmlTransformerView";

	private static volatile IMixinTransformer original;
	private static volatile Supplier<?> slot;

	private MixinWeaverSlot() {
	}

	/** Whether a guest may be handed a view of the weaver at all. */
	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(SWITCH, "on"));
	}

	/** Records the transformer Mixin gave the kernel, before anything can ask for a view of it. */
	static void install(IMixinTransformer weaver) {
		original = weaver;
	}

	/** The transformer the kernel weaves with when no guest has replaced it; null before Mixin is up. */
	public static IMixinTransformer original() {
		return original;
	}

	/**
	 * Makes {@code reader} the answer to "which transformer weaves now". The view calls this once, with a reader of
	 * the processor field it handed out; a reader that yields anything but a transformer is ignored per class.
	 */
	public static void watch(Supplier<?> reader) {
		slot = reader;
	}

	/**
	 * The transformer to weave this class with: whatever a guest put in the slot, else {@code fallback}.
	 *
	 * <p>Not guarded against a wrapper that throws. On NeoForge a throwing wrapper fails the class it was handed,
	 * and swallowing it here would weave a class the guest meant to patch without the patch, silently.
	 */
	public static IMixinTransformer currentOr(IMixinTransformer fallback) {
		Supplier<?> reader = slot;
		if (reader == null) return fallback;
		return reader.get() instanceof IMixinTransformer current ? current : fallback;
	}

	/** Forgets the slot and the transformer — for tests, and so a relaunch in one process starts clean. */
	public static void reset() {
		original = null;
		slot = null;
	}
}
