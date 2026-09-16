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

import net.minecraftforge.client.settings.IKeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;

/**
 * Translates a key binding's MinecraftForge-typed state into the NeoForge-typed state the merged base uses.
 *
 * <p>The merged {@code KeyMapping} carries BOTH ecosystems' key-conflict fields — same names, different types —
 * and every piece of live logic reads NeoForge's. {@code same()} resolves conflicts through
 * {@code getKeyConflictContext()} returning NeoForge's interface; {@code isActiveAndMatches},
 * {@code setToDefault} and {@code isConflictContextAndModifierActive} all delegate into
 * {@code IKeyMappingExtension}. The MinecraftForge-typed fields survived the merge with nothing left that reads
 * them, so a Forge mod's conflict context was written to a field no one consults.
 *
 * <p>Both ecosystems' {@code IKeyConflictContext} have the same two methods, differing only in which package's
 * interface {@code conflicts} takes — so the two can be adapted in both directions, and the adapters unwrap
 * rather than stack when a value crosses back.
 *
 * <p>{@code KeyModifier} is an enum on both sides and maps by name. NeoForge has one constant MinecraftForge
 * does not, {@code CONTROL_OR_COMMAND}; it means "control, or command on macOS", which is what MinecraftForge's
 * {@code CONTROL} does, so that is where it goes.
 *
 * <p>Every method takes and returns {@code Object}: the injected bytecode supplies its own casts, and the
 * {@code KernelRuntimeClasses} seam can then state these signatures without the boot side needing either
 * carrier on its classpath.
 */
public final class KernelForgeKeyBindings {

	private KernelForgeKeyBindings() {
	}

	/** A NeoForge conflict context backed by a MinecraftForge one. */
	private record ForgeBacked(IKeyConflictContext forge)
			implements net.neoforged.neoforge.client.settings.IKeyConflictContext {
		@Override
		public boolean isActive() {
			return forge.isActive();
		}

		@Override
		public boolean conflicts(net.neoforged.neoforge.client.settings.IKeyConflictContext other) {
			return forge.conflicts((IKeyConflictContext) toForgeContext(other));
		}
	}

	/** A MinecraftForge conflict context backed by a NeoForge one. */
	private record NeoBacked(net.neoforged.neoforge.client.settings.IKeyConflictContext neo)
			implements IKeyConflictContext {
		@Override
		public boolean isActive() {
			return neo.isActive();
		}

		@Override
		public boolean conflicts(IKeyConflictContext other) {
			return neo.conflicts((net.neoforged.neoforge.client.settings.IKeyConflictContext) toNeoContext(other));
		}
	}

	/** A MinecraftForge conflict context, seen as a NeoForge one. Unwraps rather than double-wrapping. */
	public static Object toNeoContext(Object forge) {
		if (forge == null) return null;
		if (forge instanceof NeoBacked backed) return backed.neo();
		return new ForgeBacked((IKeyConflictContext) forge);
	}

	/** The reverse. */
	public static Object toForgeContext(Object neo) {
		if (neo == null) return null;
		if (neo instanceof ForgeBacked backed) return backed.forge();
		return new NeoBacked((net.neoforged.neoforge.client.settings.IKeyConflictContext) neo);
	}

	/** MinecraftForge's {@code KeyModifier} as NeoForge's. Same names, so the mapping is the name. */
	public static Object toNeoModifier(Object forge) {
		if (forge == null) return null;
		return net.neoforged.neoforge.client.settings.KeyModifier
				.valueOf(((KeyModifier) forge).name());
	}

	/**
	 * The reverse. {@code CONTROL_OR_COMMAND} has no MinecraftForge constant and becomes {@code CONTROL}: that is
	 * what it means on every platform MinecraftForge's own CONTROL covers, and the alternative — NONE — would
	 * silently drop a modifier the player had bound.
	 */
	public static Object toForgeModifier(Object neo) {
		// NONE, not null. MinecraftForge's own KeyMappingLookup.put reads this accessor and immediately uses the
		// result as an EnumMap key — computeIfAbsent on a null bucket, which is an NPE raised inside Forge's code
		// and blamed on the mod that was constructing a key binding. There is also no such thing as a "null
		// modifier" in either family: an unmodified binding IS NONE, which is what the field is initialised to.
		if (neo == null) return KeyModifier.NONE;
		String name = ((net.neoforged.neoforge.client.settings.KeyModifier) neo).name();
		if ("CONTROL_OR_COMMAND".equals(name)) return KeyModifier.CONTROL;
		try {
			return KeyModifier.valueOf(name);
		} catch (IllegalArgumentException added) {
			// A constant NeoForge grows later: NONE is the only honest answer, and it is what an unmodified
			// binding already means.
			return KeyModifier.NONE;
		}
	}
}
