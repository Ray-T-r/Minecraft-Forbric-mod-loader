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

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.common.collect.ImmutableMap;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;

import net.forbric.kernel.util.ForbricLog;

/**
 * The two write shapes {@code WidenedFieldTwinInjector} needs: a guarded narrowing for a field the merge widened
 * ({@code RangedBowAttackGoal.mob}: vanilla {@code Monster}, merged {@code Mob}) and a drain for a field it
 * re-typed ({@code AttributeSupplier$Builder.builder}: vanilla {@code ImmutableMap.Builder}, merged {@code Map}).
 */
public final class KernelWidenedFields {
	private static final Set<String> NOT_MONSTERS = ConcurrentHashMap.newKeySet();

	private KernelWidenedFields() {
	}

	/**
	 * {@code mob} as the {@code Monster} vanilla's descriptor promises, or {@code null} when NeoForge's widening
	 * is being used — a mod reading the vanilla-typed twin then gets null rather than a ClassCastException, and
	 * the class is named once so the null has a line in the log.
	 */
	public static Monster asMonster(Mob mob) {
		if (mob instanceof Monster monster) return monster;
		if (mob != null && NOT_MONSTERS.add(mob.getClass().getName())) {
			ForbricLog.info("[Forbric/WidenedFields] %s is a ranged-attack Mob that is not a Monster — NeoForge's widening "
					+ "in use; a mod reading the goal's vanilla-typed `mob` field sees null for it", mob.getClass().getName());
		}
		return null;
	}

	/**
	 * Everything put into the vanilla-typed twin builder that the merged map does not already hold. Called at the
	 * head of every reader of the map, so a Fabric {@code putAll} on the twin ({@code FabricDefaultAttributeRegistryImpl.
	 * createFromExistingSupplier}) is visible to {@code build()}; a value the map already has — one added through
	 * NeoForge's own {@code add} — wins, which is Fabric's copy-then-override order.
	 */
	public static void drain(Map<Object, Object> map, ImmutableMap.Builder<Object, Object> twin) {
		if (map == null || twin == null) return;
		twin.buildKeepingLast().forEach(map::putIfAbsent);
	}
}
