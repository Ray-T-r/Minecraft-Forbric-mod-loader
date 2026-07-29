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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.json.JsonFormat;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
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
 * mixin applies cleanly is therefore not a usable signal.
 *
 * <p><b>Provenance is not a usable signal either.</b> This used to drop every mixin whose target matched a
 * hand-curated owned-class/package table. That was wrong at the root: the merged base IS NeoForge's patched
 * Minecraft ({@code MergedBaseBuilder} takes NeoForge as the base and splices Forge in — {@code forge=195
 * neo=10161 MERGED=611}), so "Forge/NeoForge owns this class" describes ~93% of the jar. Measured over the 163
 * suppressions that rule actually made, 108 targeted a class byte-identical to NeoForge's own jar, and restoring
 * them costs nothing. The table also could not see the failures that matter: fabric-block-api-v1 redirects
 * {@code BlockState.isAir()} inside {@code LevelChunkSection.setBlockState}, which the merged base calls as
 * {@code isEmpty()} — silently dead, on a class the table never listed.
 *
 * <p>So the question is asked directly instead: {@link MixinFit} resolves every anchor the mixin names — each
 * {@code @Shadow} member, each injector's target method, each {@code @At(target=…)} — against the merged target's
 * real bytecode, and reports whether they still exist. See {@link MixinFit.Result#shouldSuppress()} for why a
 * partially-resolving mixin is kept by default rather than dropped.
 *
 * <p>Two exemptions survive. PURE accessor/invoker mixins are always kept: they inject no behaviour, and other code
 * casts the target to the {@code @Accessor} interface they contribute. And whenever a mixin IS dropped, every mixin
 * depending on an interface it contributed is dropped with it — see {@link MixinFit#contributedInterfaces} — because
 * a lone drop converts the mod's {@code (Bar) foo} casts into {@code ClassCastException}s.
 *
 * <p>This runs at the point {@link ForbricMixinService} rewrites a config's JSON, so it needs no separate mod-jar
 * inventory: the config names its mixin package, and each mixin class is a game resource resolvable through the same
 * loader. Hand entries in {@link MergedBaseMixinCompat} stay authoritative for cases this cannot see (a runtime
 * break with no owned target); this removes the need to hand-list the owned-target ones.
 */
public final class KernelGuestMixinAdapter {
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
	 * arrays) that no longer fit the merged base, plus anything transitively broken by dropping them.
	 * {@code resource} resolves a resource path ({@code some/pkg/Name.class}) to its bytes, or null. Best-effort:
	 * any parse/scan failure on one entry skips that entry, never the config.
	 */
	public static List<String> unfitMixins(String configName, byte[] configJson, Function<String, byte[]> resource) {
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
		Map<String, byte[]> loaded = new LinkedHashMap<>();
		List<String> suppress = new ArrayList<>();

		for (String mixin : mixins) {
			byte[] classBytes = resource.apply(pkgPath + "/" + mixin.replace('.', '/') + ".class");
			if (classBytes == null) continue;
			loaded.put(mixin, classBytes);
			try {
				if (isPureAccessorMixin(classBytes)) continue;
				if (isExplicitlyKept(configName, mixin)) continue;

				MixinFit.Result fit = MixinFit.evaluate(classBytes, resource);
				if (!fit.shouldSuppress()) {
					if (fit.verdict() == MixinFit.Verdict.PARTIAL) {
						ForbricLog.info("[Forbric/Mixin] guest mixin %s:%s applies only partially on the merged base "
								+ "— %s (kept; -Dforbric.mixinFit=strict drops these)", configName, mixin,
								fit.reason());
					}
					continue;
				}
				suppress.add(mixin);
				ForbricLog.info("[Forbric/Mixin] auto-suppressing guest mixin %s:%s — %s on the merged base (%s)",
						configName, mixin, fit.verdict(), fit.reason());
			} catch (RuntimeException perMixin) {
				ForbricLog.debug("[Forbric/Mixin] could not scan guest mixin %s:%s — %s", configName, mixin,
						String.valueOf(perMixin));
			}
		}

		closeOverCastContracts(configName, loaded, suppress);
		return suppress;
	}

	/**
	 * Drops every mixin that depends on a duck-type interface a dropped mixin was contributing, until nothing new is
	 * dropped.
	 *
	 * <p>Without this, suppressing one half of a cast contract is worse than suppressing neither. Verified live:
	 * {@code fabric-rendering-v1}'s {@code GuiRendererMixin implements GuiRendererExtensions} and is dropped as a
	 * HAZARD (it {@code @Shadow}s the orphaned {@code pictureInPictureRenderers}); its sibling
	 * {@code GameRendererMixin} does {@code checkcast GuiRendererExtensions} and resolves cleanly, so it would be
	 * kept — and would then throw {@code ClassCastException} on a path that works today.
	 */
	private static void closeOverCastContracts(String configName, Map<String, byte[]> loaded, List<String> suppress) {
		for (int round = 0; round < 8; round++) {
			Set<String> contracts = new LinkedHashSet<>();
			for (String dropped : suppress) {
				byte[] bytes = loaded.get(dropped);
				if (bytes != null) contracts.addAll(MixinFit.contributedInterfaces(MixinFit.parse(bytes)));
			}
			if (contracts.isEmpty()) return;

			List<String> added = new ArrayList<>();
			for (Map.Entry<String, byte[]> e : loaded.entrySet()) {
				if (suppress.contains(e.getKey())) continue;
				try {
					if (!MixinFit.referencesAny(MixinFit.parse(e.getValue()), contracts)) continue;
				} catch (RuntimeException unreadable) {
					continue;
				}
				added.add(e.getKey());
				ForbricLog.info("[Forbric/Mixin] auto-suppressing guest mixin %s:%s — it casts the target to an "
						+ "interface a suppressed sibling contributes, which would ClassCastException",
						configName, e.getKey());
			}
			if (added.isEmpty()) return;
			suppress.addAll(added);
		}
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
