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

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.json.JsonFormat;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Derives, per mixin config, which of its guest mixins must not apply to the merged base — the general form of
 * {@link MergedBaseMixinCompat#SUPPRESSED_MIXINS}'s hand-written entries.
 *
 * <p>A guest Fabric/Forge mixin is written against VANILLA bytecode. In the merged base, Forge or NeoForge may have
 * won the byte-merge of the class the mixin targets and restructured it — a field the mixin {@code @Shadow}s is
 * never assigned, an {@code @Inject} anchor moved, a param was re-typed. Generic erasure lets many such mixins APPLY
 * with no error and then misbehave at runtime (the archetype: {@code fabric-rendering-v1}'s {@code GuiRendererMixin}
 * reads {@code GuiRenderer.pictureInPictureRenderers}, which the NeoForge-won merge never assigns → NPE). Whether a
 * mixin applies cleanly is therefore not a usable signal; the target class's provenance is.
 *
 * <p>So this scans each mixin's {@code @Mixin} target and drops the ones that target a class the merged base rebuilt
 * from Forge/NeoForge — {@link #OWNED_TARGETS} exact names plus {@link #OWNED_TARGET_PREFIXES} package prefixes,
 * the set proven in the old {@code forbric-loader} against full fabric-api + sodium/iris on the merged client.
 * PURE accessor/invoker mixins are KEPT even when they target an owned class: they inject no behaviour, and OTHER
 * code casts the target to the {@code @Accessor} interface they contribute — dropping one turns a working read into
 * a {@code ClassCastException}. Mixins on {@link MergedBaseMixinCompat#KEPT_MIXINS} are KEPT for the same reason one
 * level up: {@code @Mixin(Foo.class) class FooMixin implements Bar} is the mod's cast contract on {@code Foo}, so
 * suppressing it breaks every {@code (Bar) foo} the mod performs — that list explains why it is measured per mixin
 * rather than derived. Mixin also abandons the ENTIRE target class if one mixin fails during context
 * creation, so an owned-class mixin that would fail takes a co-located load-bearing mixin down with it; suppressing
 * it up front is what keeps the rest.
 *
 * <p>This runs at the point {@link ForbricMixinService} rewrites a config's JSON, so it needs no separate mod-jar
 * inventory: the config names its mixin package, and each mixin class is a game resource resolvable through the same
 * loader. Hand entries in {@link MergedBaseMixinCompat} stay authoritative for cases this cannot see (a runtime
 * break with no owned target); this removes the need to hand-list the owned-target ones.
 */
public final class KernelGuestMixinAdapter {
	/** Exact merged classes Forge/NeoForge rebuilt that a guest mixin must not inject into. */
	private static final Set<String> OWNED_TARGETS = Set.of(
			"net/minecraft/client/gui/GuiGraphicsExtractor",
			"net/minecraft/client/renderer/EndFlashState",
			"net/minecraft/client/renderer/GameRenderer",
			"net/minecraft/client/renderer/ItemInHandRenderer",
			"net/minecraft/client/renderer/LevelRenderer",
			"net/minecraft/client/renderer/LightmapRenderStateExtractor",
			"net/minecraft/client/renderer/OrderedSubmitNodeCollector",
			"net/minecraft/client/renderer/Projection",
			"net/minecraft/client/renderer/ScreenEffectRenderer",
			"net/minecraft/client/renderer/SkyRenderer",
			"net/minecraft/client/renderer/SubmitNodeCollection",
			"net/minecraft/client/renderer/SubmitNodeStorage",
			"net/minecraft/client/renderer/WeatherEffectRenderer",
			"net/minecraft/server/network/config/SynchronizeRegistriesTask",
			"net/minecraft/tags/TagNetworkSerialization");

	/** Package prefixes of the merged renderer/model pipeline Forge/NeoForge rebuilt wholesale. */
	private static final List<String> OWNED_TARGET_PREFIXES = List.of(
			"net/minecraft/client/gui/render/",
			"net/minecraft/client/particle/",
			"net/minecraft/client/renderer/block/",
			"net/minecraft/client/renderer/blockentity/",
			"net/minecraft/client/renderer/chunk/",
			"net/minecraft/client/renderer/debug/",
			"net/minecraft/client/renderer/entity/",
			"net/minecraft/client/renderer/extract/",
			"net/minecraft/client/renderer/feature/",
			"net/minecraft/client/renderer/fog/",
			"net/minecraft/client/renderer/item/",
			"net/minecraft/client/renderer/rendertype/",
			"net/minecraft/client/renderer/state/",
			"net/minecraft/client/resources/model/");

	private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
	private static final String ACCESSOR_DESC = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
	private static final String INVOKER_DESC = "Lorg/spongepowered/asm/mixin/gen/Invoker;";

	/** {@code -Dforbric.guestMixinAdapter=off} turns the derived scan off (leaving only the hand list). */
	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty("forbric.guestMixinAdapter", "on"));
	}

	private KernelGuestMixinAdapter() {
	}

	/**
	 * The mixin entries in {@code configJson} (as they appear in its {@code mixins}/{@code client}/{@code server}
	 * arrays) that target a Forge/NeoForge-owned merged class and are not pure accessors. {@code resource} resolves
	 * a resource path ({@code some/pkg/Name.class}) to its bytes, or null. Best-effort: any parse/scan failure on
	 * one entry skips that entry, never the config.
	 */
	public static List<String> ownedNonAccessorMixins(String configName, byte[] configJson,
			Function<String, byte[]> resource) {
		if (!enabled()) return List.of();

		UnmodifiableConfig config;
		try (Reader reader = new InputStreamReader(new ByteArrayInputStream(configJson), StandardCharsets.UTF_8)) {
			config = JsonFormat.fancyInstance().createParser().parse(reader);
		} catch (RuntimeException | java.io.IOException notAMixinConfig) {
			return List.of();
		}

		String pkg = asString(config.get(List.of("package")));
		if (pkg == null || pkg.isEmpty()) return List.of();

		LinkedHashSet<String> mixins = new LinkedHashSet<>();
		addMixinEntries(config.get(List.of("mixins")), mixins);
		addMixinEntries(config.get(List.of("client")), mixins);
		addMixinEntries(config.get(List.of("server")), mixins);
		if (mixins.isEmpty()) return List.of();

		String pkgPath = pkg.replace('.', '/');
		List<String> suppress = new ArrayList<>();
		for (String mixin : mixins) {
			byte[] classBytes = resource.apply(pkgPath + "/" + mixin.replace('.', '/') + ".class");
			if (classBytes == null) continue;
			try {
				String target = mixinTarget(classBytes);
				if (target == null || !isOwnedTarget(target)) continue;
				if (isPureAccessorMixin(classBytes)) {
					ForbricLog.debug("[Forbric/Mixin] keeping accessor/invoker mixin %s:%s though it targets "
							+ "Forge/NeoForge-owned merged class %s", configName, mixin, target.replace('/', '.'));
					continue;
				}
				if (isExplicitlyKept(configName, mixin)) {
					ForbricLog.info("[Forbric/Mixin] keeping mixin %s:%s though it targets Forge/NeoForge-owned "
							+ "merged class %s — it contributes an interface the mod casts the target to",
							configName, mixin, target.replace('/', '.'));
					continue;
				}
				suppress.add(mixin);
				ForbricLog.info("[Forbric/Mixin] auto-suppressing guest mixin %s:%s — targets Forge/NeoForge-owned "
						+ "merged class %s (written against vanilla bytecode the merge rebuilt)", configName, mixin,
						target.replace('/', '.'));
			} catch (RuntimeException perMixin) {
				ForbricLog.debug("[Forbric/Mixin] could not scan guest mixin %s:%s — %s", configName, mixin,
						String.valueOf(perMixin));
			}
		}
		return suppress;
	}

	/** The internal name of the class a {@code @Mixin} targets ({@code value} Class or {@code targets} String), or null. */
	private static String mixinTarget(byte[] classBytes) {
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		String t = mixinTarget(node.visibleAnnotations);
		return t != null ? t : mixinTarget(node.invisibleAnnotations);
	}

	private static String mixinTarget(List<AnnotationNode> annotations) {
		if (annotations == null) return null;
		for (AnnotationNode a : annotations) {
			if (!MIXIN_DESC.equals(a.desc) || a.values == null) continue;
			for (int i = 0; i + 1 < a.values.size(); i += 2) {
				Object key = a.values.get(i);
				if ("value".equals(key) || "targets".equals(key)) {
					String t = firstTarget(a.values.get(i + 1));
					if (t != null) return t;
				}
			}
		}
		return null;
	}

	private static String firstTarget(Object value) {
		if (value instanceof List<?> list) {
			for (Object element : list) {
				String t = firstTarget(element);
				if (t != null) return t;
			}
			return null;
		}
		if (value instanceof Type type) return type.getInternalName();
		if (value instanceof String s) {
			String name = s.trim();
			if (name.startsWith("L") && name.endsWith(";")) name = name.substring(1, name.length() - 1);
			return name.replace('.', '/');
		}
		return null;
	}

	private static boolean isOwnedTarget(String target) {
		if (OWNED_TARGETS.contains(target)) return true;
		for (String prefix : OWNED_TARGET_PREFIXES) {
			if (target.startsWith(prefix)) return true;
		}
		return false;
	}

	/**
	 * A mixin whose only members are {@code @Accessor}/{@code @Invoker} methods and which declares no fields. Such a
	 * mixin injects no behaviour; keeping it registered lets code that casts the target to its generated interface
	 * keep working, so it is never suppressed.
	 */
	private static boolean isPureAccessorMixin(byte[] classBytes) {
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		if (node.fields != null && !node.fields.isEmpty()) return false;

		boolean sawAccessor = false;
		if (node.methods != null) {
			for (MethodNode m : node.methods) {
				if (m.name.startsWith("<")) continue;
				if ((m.access & Opcodes.ACC_SYNTHETIC) != 0) continue;
				if (!hasAnnotation(m.visibleAnnotations, ACCESSOR_DESC) && !hasAnnotation(m.invisibleAnnotations, ACCESSOR_DESC)
						&& !hasAnnotation(m.visibleAnnotations, INVOKER_DESC)
						&& !hasAnnotation(m.invisibleAnnotations, INVOKER_DESC)) {
					return false;
				}
				sawAccessor = true;
			}
		}
		return sawAccessor;
	}

	/**
	 * Whether {@code configName:mixin} is on the never-auto-suppress list — {@link MergedBaseMixinCompat#KEPT_MIXINS}
	 * plus anything named by {@code -Dforbric.keepMixins} (csv of {@code <config>:<MixinEntry>}).
	 *
	 * <p>These are mixins that target an owned class but ALSO contribute a duck-type interface the mod casts the
	 * target to, so suppressing them converts a dropped feature into a {@code ClassCastException}. See
	 * {@link MergedBaseMixinCompat#KEPT_MIXINS} for why this is a measured hand list and not the obvious
	 * "keep everything that implements an interface" rule.
	 */
	private static boolean isExplicitlyKept(String configName, String mixin) {
		String entry = configName + ":" + mixin;
		if (MergedBaseMixinCompat.enabled() && MergedBaseMixinCompat.KEPT_MIXINS.contains(entry)) return true;

		String csv = System.getProperty("forbric.keepMixins");
		if (csv == null || csv.isEmpty()) return false;

		for (String raw : csv.split(",")) {
			if (entry.equals(raw.trim())) return true;
		}
		return false;
	}

	private static boolean hasAnnotation(List<AnnotationNode> annotations, String desc) {
		if (annotations == null) return false;
		for (AnnotationNode a : annotations) {
			if (desc.equals(a.desc)) return true;
		}
		return false;
	}

	private static void addMixinEntries(Object value, Set<String> out) {
		if (!(value instanceof List<?> list)) return;
		for (Object element : list) {
			if (element instanceof String s && !s.isBlank()) out.add(s.trim());
		}
	}

	private static String asString(Object value) {
		return value == null ? null : value.toString();
	}
}
