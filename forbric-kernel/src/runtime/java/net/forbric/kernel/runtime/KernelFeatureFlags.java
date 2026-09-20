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

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.resources.Identifier;
import net.minecraft.world.flag.FeatureFlagRegistry;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.boot.KernelLifecycle;
import net.forbric.kernel.boot.MultiLoaderArbiter;
import net.forbric.kernel.util.ForbricLog;

/**
 * Registers the feature flags NeoForge mods declare, in place of NeoForge's own {@code FeatureFlagLoader}.
 *
 * <p>A NeoForge mod names a flag file in {@code neoforge.mods.toml} ({@code featureFlags = "META-INF/feature_flags.json"});
 * NeoForge reads it through {@code IModFile.getContents()} while {@code FeatureFlags.<clinit>} builds the registry,
 * and the mod later asks {@code FeatureFlags.REGISTRY.getFlag(...)} for its own flag. The kernel's mod files carry
 * no jar contents, so that walk answered nothing and every such mod died in its static initialiser ("Flag
 * tofucraft:experimental_extra was not registered"), taking its datapack-referenced content and the whole
 * registry load with it. This reads the same file from the jar directly and registers each flag with NeoForge's
 * rule — a flag may only live in a namespace of a mod in that jar. {@code -Dforbric.moddedFeatureFlags=off}.
 */
public final class KernelFeatureFlags {
	public static final String PROPERTY = "forbric.moddedFeatureFlags";
	static final String NEOFORGE_TOML = "META-INF/neoforge.mods.toml";
	private static final Pattern FLAG_FILE = Pattern.compile("^\\s*featureFlags\\s*=\\s*\"([^\"]+)\"", Pattern.MULTILINE);
	private static final Pattern MOD_ID = Pattern.compile("^\\s*modId\\s*=\\s*\"([^\"]+)\"", Pattern.MULTILINE);

	private static volatile Supplier<List<Path>> jars = KernelLifecycle::modJars;

	private KernelFeatureFlags() {
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** Test seam: the jars to read, replaced. */
	static void bindJarsForTest(List<Path> forTest) {
		jars = () -> forTest;
	}

	/** The redirected {@code FeatureFlagLoader.loadModdedFlags(Builder)}: same descriptor, same moment. */
	public static void loadModdedFlags(FeatureFlagRegistry.Builder builder) {
		if (!enabled()) {
			ForbricLog.warn("[Forbric/FeatureFlags] -D%s=off — NeoForge mods' declared feature flags are not registered", PROPERTY);
			return;
		}
		List<String> registered = new ArrayList<>();
		int jarsWithFlags = 0;
		for (Path jar : jars.get()) {
			Ecosystem owner = MultiLoaderArbiter.ownerOf(jar);
			if (owner != null && owner != Ecosystem.NEOFORGE) continue;
			List<String> fromJar = register(builder, jar);
			if (fromJar == null) continue;
			jarsWithFlags++;
			registered.addAll(fromJar);
		}
		if (registered.isEmpty()) {
			ForbricLog.debug("[Forbric/FeatureFlags] no NeoForge mod declares a feature flag");
			return;
		}
		ForbricLog.info("[Forbric/FeatureFlags] registered %d modded feature flag(s) from %d NeoForge jar(s) — the "
				+ "kernel's mod files carry no jar contents, so NeoForge's own loader found none: %s", registered.size(),
				jarsWithFlags, registered);
	}

	/** The flags of one jar, null when it declares none; a jar that cannot be read costs its own flags only. */
	static List<String> register(FeatureFlagRegistry.Builder builder, Path jar) {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry toml = zip.getEntry(NEOFORGE_TOML);
			if (toml == null) return null;
			String text = new String(zip.getInputStream(toml).readAllBytes(), StandardCharsets.UTF_8);
			Matcher file = FLAG_FILE.matcher(text);
			if (!file.find()) return null;
			Set<String> modIds = new LinkedHashSet<>();
			Matcher id = MOD_ID.matcher(text);
			while (id.find()) modIds.add(id.group(1));
			ZipEntry flags = zip.getEntry(file.group(1));
			if (flags == null) {
				ForbricLog.warn("[Forbric/FeatureFlags] %s names feature-flag file %s, which is not in the jar", jar.getFileName(), file.group(1));
				return List.of();
			}
			List<String> out = new ArrayList<>();
			try (InputStream in = zip.getInputStream(flags)) {
				JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
				JsonArray array = root.getAsJsonArray("flags");
				if (array == null) return List.of();
				for (JsonElement element : array) {
					String name = element.isJsonObject() ? element.getAsJsonObject().get("flag").getAsString() : element.getAsString();
					Identifier flag = Identifier.parse(name);
					if (!modIds.contains(flag.getNamespace())) {
						ForbricLog.warn("[Forbric/FeatureFlags] %s declares flag %s in a namespace no mod in that jar owns (%s) — skipped, "
								+ "as NeoForge would refuse it", jar.getFileName(), name, modIds);
						continue;
					}
					builder.create(flag, true);
					out.add(name);
				}
			}
			return out;
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/FeatureFlags] could not read the feature flags of " + jar.getFileName(), t);
			return List.of();
		}
	}
}
