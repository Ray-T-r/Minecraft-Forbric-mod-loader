/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.forbric.kernel.transform.InjectorExecution;

/**
 * {@code fabric:load_conditions} is judged through fabric-api's public API alone: the key's value parsed by
 * {@code ResourceCondition.CONDITION_CODEC} and asked {@code test(lookup)}. The conditions here are implemented by a class
 * of another name ({@code org.relay.cond.Flag}), as a build of the API laid out differently would implement them.
 */
class KernelFabricConditionsPublicApiTest {
	private static final Map<String, String> API = Map.of(
			"net.fabricmc.fabric.api.resource.conditions.v1.ResourceCondition", """
					package net.fabricmc.fabric.api.resource.conditions.v1;

					import com.mojang.serialization.Codec;
					import net.minecraft.resources.RegistryOps;

					public interface ResourceCondition {
						Codec<ResourceCondition> CONDITION_CODEC = Codec.STRING.xmap(org.relay.cond.Flag::new,
								condition -> ((org.relay.cond.Flag) condition).name());

						boolean test(RegistryOps.RegistryInfoLookup lookup);
					}
					""",
			"org.relay.cond.Flag", """
					package org.relay.cond;

					import net.fabricmc.fabric.api.resource.conditions.v1.ResourceCondition;
					import net.minecraft.resources.RegistryOps;

					public record Flag(String name) implements ResourceCondition {
						public boolean test(RegistryOps.RegistryInfoLookup lookup) {
							return !name.equals("off");
						}
					}
					""");

	@Test void theKeyIsJudgedByThePublicCodecAndTest(@TempDir Path work) throws Throwable {
		try (URLClassLoader game = KernelConditionKillSwitchesTest.gameSideLoader()) {
			List<Path> dependencies = new ArrayList<>();
			for (URL url : game.getURLs()) dependencies.add(Path.of(url.toURI()));
			Map<String, byte[]> compiled = InjectorExecution.compile(work, API, dependencies);
			Path classes = Files.createDirectories(work.resolve("api"));
			for (var entry : compiled.entrySet()) {
				Path file = classes.resolve(entry.getKey() + ".class");
				Files.createDirectories(file.getParent());
				Files.write(file, entry.getValue());
			}
			try (URLClassLoader withApi = new URLClassLoader(new URL[] {classes.toUri().toURL()}, game)) {
				Class<?> helper = Class.forName("net.forbric.kernel.runtime.KernelFabricConditions", true, game);
				Method factory = helper.getDeclaredMethod("publicApiEvaluator", ClassLoader.class);
				factory.setAccessible(true);
				assertNull(factory.invoke(null, game), "without the API there is nothing to ask");
				Object evaluator = factory.invoke(null, withApi);
				assertNotNull(evaluator);
				Method keep = Class.forName("net.forbric.kernel.runtime.KernelFabricConditions$Evaluator", true, game)
						.getDeclaredMethod("keep", Class.forName("com.google.gson.JsonObject", true, game),
								Class.forName("net.minecraft.resources.RegistryOps$RegistryInfoLookup", true, game));
				keep.setAccessible(true);
				Method parse = Class.forName("com.google.gson.JsonParser", true, game).getMethod("parseString", String.class);
				assertEquals(false, keep.invoke(evaluator, parse.invoke(null, "{\"fabric:load_conditions\":\"off\",\"v\":1}"), null));
				assertEquals(true, keep.invoke(evaluator, parse.invoke(null, "{\"fabric:load_conditions\":\"on\",\"v\":1}"), null));
				assertEquals(true, keep.invoke(evaluator, parse.invoke(null, "{\"fabric:load_conditions\":5,\"v\":1}"), null),
						"a value the codec cannot read keeps the element, as fabric-api keeps it");
			}
		}
	}
}
