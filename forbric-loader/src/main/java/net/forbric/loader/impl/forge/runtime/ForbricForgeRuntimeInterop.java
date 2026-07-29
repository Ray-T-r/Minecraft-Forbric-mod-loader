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

package net.forbric.loader.impl.forge.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.forbric.loader.impl.util.ForbricLog;

/**
 * Small runtime helpers used by bytecode repaired in the merged Minecraft base.
 */
public final class ForbricForgeRuntimeInterop {
	private static final Map<ClassLoader, Map<String, Object>> FORGE_FLUID_TYPES = new ConcurrentHashMap<>();

	private ForbricForgeRuntimeInterop() {
	}

	/**
	 * Bridges a vanilla/NeoForge fluid instance to the traditional-Forge {@code FluidType} singleton family.
	 *
	 * <p>The merged base has both extension interfaces on {@code Fluid}, but the concrete vanilla subclasses may
	 * only inherit NeoForge's {@code getFluidType()} implementation. Forge's {@code IForgeFluid} default methods
	 * require the same method name with a Forge return type, so the bytecode transformer adds a tiny bridge that
	 * calls here.
	 */
	public static Object forgeFluidType(Object fluid) {
		ClassLoader cl = fluid != null ? fluid.getClass().getClassLoader()
				: Thread.currentThread().getContextClassLoader();
		String field = forgeFluidTypeField(fluid, cl);
		return forgeModRegistryObject(cl, field);
	}

	private static String forgeFluidTypeField(Object fluid, ClassLoader cl) {
		if (fluid == null) return "EMPTY_TYPE";
		if (isInstance(cl, "net.minecraft.world.level.material.WaterFluid", fluid)) return "WATER_TYPE";
		if (isInstance(cl, "net.minecraft.world.level.material.LavaFluid", fluid)) return "LAVA_TYPE";
		if (isInstance(cl, "net.minecraft.world.level.material.EmptyFluid", fluid)) return "EMPTY_TYPE";
		ForbricLog.warn("[Forbric/ForgeRuntime] unknown vanilla fluid family %s; using Forge EMPTY_TYPE fallback",
				fluid.getClass().getName());
		return "EMPTY_TYPE";
	}

	private static boolean isInstance(ClassLoader cl, String className, Object value) {
		try {
			return Class.forName(className, false, cl).isInstance(value);
		} catch (Throwable ignored) {
			return false;
		}
	}

	private static Object forgeModRegistryObject(ClassLoader cl, String fieldName) {
		Map<String, Object> byField = FORGE_FLUID_TYPES.computeIfAbsent(cl, ignored -> new ConcurrentHashMap<>());
		return byField.computeIfAbsent(fieldName, field -> {
			try {
				Class<?> forgeMod = Class.forName("net.minecraftforge.common.ForgeMod", false, cl);
				Field registryObjectField = forgeMod.getField(field);
				Object registryObject = registryObjectField.get(null);
				Method get = registryObject.getClass().getMethod("get");
				return get.invoke(registryObject);
			} catch (Throwable t) {
				Throwable cause = unwrap(t);
				ForbricLog.warn("[Forbric/ForgeRuntime] could not resolve ForgeMod.%s; fluid behavior may be degraded",
						field, cause);
				return null;
			}
		});
	}

	private static Throwable unwrap(Throwable t) {
		while (t instanceof InvocationTargetException invocation && invocation.getCause() != null) {
			t = invocation.getCause();
		}
		return t;
	}
}
