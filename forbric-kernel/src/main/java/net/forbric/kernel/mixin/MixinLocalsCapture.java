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

import java.util.List;

import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Turns a guest mixin's {@code @Inject(locals = CAPTURE_FAILHARD)} into {@code CAPTURE_FAILSOFT}.
 *
 * <p>A locals-capturing injector is written against ONE method body's local variable table. On the merged base
 * the body a mixin targets can carry the other family's patch — architectury's {@code MixinServerPlayerGameMode}
 * captures {@code (BlockEntity, BlockState)} from {@code destroyBlock}, and the merged method has NeoForge's
 * {@code BreakBlockEvent} local in between. FAIL_HARD makes that an {@code InjectionError}, an {@link Error} no
 * mixin error handler sees, thrown from inside the class load of the target: the popular-set server died in
 * {@code WorldLoader}. FAIL_SOFT is Mixin's own answer to exactly this: the one injection is skipped with a WARN
 * that names the mixin and the mod, and everything else the mixin does still applies. {@code -Dforbric.localsFailSoft=off}
 * keeps the compiled value.
 */
public final class MixinLocalsCapture {
	public static final String PROPERTY = "forbric.localsFailSoft";
	static final String INJECT_DESC = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	static final String LOCAL_CAPTURE_DESC = "Lorg/spongepowered/asm/mixin/injection/callback/LocalCapture;";

	private MixinLocalsCapture() {
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** {@code -Dforbric.requireFailSoft=off} keeps a guest injector's own require/allow as compiled. */
	public static final String REQUIRE_PROPERTY = "forbric.requireFailSoft";

	/**
	 * Lowers an explicit {@code require} of 1 or more to 0, and drops {@code allow}, on the injectors of a guest mixin
	 * whose every owning config the kernel relaxed; returns how many.
	 *
	 * <p>The config-level relaxation cannot reach these: Mixin takes an injector's own {@code require} over the
	 * config's {@code defaultRequire}, and a count it misses (or an {@code allow} it overshoots) throws
	 * {@code InjectionError} from {@code postInject} — an Error, not an {@code InvalidMixinException}, so neither
	 * {@code required:false} nor any error handler sees it. Mixin wraps it as "An unexpected critical error" and
	 * abandons the TARGET class, for every mod. creativecore's {@code @Redirect(require=1)} on a
	 * {@code RegistryFriendlyByteBuf.decorator} call NeoForge widened did exactly that to
	 * {@code ServerConfigurationPacketListenerImpl}: fabric-api's configuration events could not initialise, and
	 * the main entrypoints of fabric-registry-sync, fabric-recipe-api, fabric-particles, fabric-data-attachment,
	 * puzzleslib, fzzy_config and forgeconfigapiport all threw.
	 *
	 * <p>The author's number is not lost: {@link FinalMixinApplications#remember} records it before this runs, and
	 * an injector below it in the defined class is still a CONFIRMED required finding on the mod that declared it.
	 * The mod fails; the class, and every other mod, does not. An injector that does attach produces identical code.
	 */
	public static int softenRequirements(ClassNode mixin, java.util.function.Predicate<String> relaxedOwner) {
		if ("off".equalsIgnoreCase(System.getProperty(REQUIRE_PROPERTY, "on")) || mixin == null || mixin.methods == null
				|| relaxedOwner == null || !relaxedOwner.test(mixin.name.replace('/', '.'))) return 0;
		int softened = 0;
		for (MethodNode m : mixin.methods) {
			softened += softenRequirements(m.visibleAnnotations, mixin.name, m.name);
			softened += softenRequirements(m.invisibleAnnotations, mixin.name, m.name);
		}
		return softened;
	}

	private static int softenRequirements(List<AnnotationNode> annotations, String mixinName, String handler) {
		if (annotations == null) return 0;
		int softened = 0;
		for (AnnotationNode injector : annotations) {
			if (!FinalMixinApplications.isInjector(injector.desc) || injector.values == null) continue;
			boolean changed = false;
			Object before = null;
			for (int i = 0; i + 1 < injector.values.size(); i += 2) {
				Object key = injector.values.get(i), value = injector.values.get(i + 1);
				if ("require".equals(key) && value instanceof Number n && n.intValue() >= 1) {
					before = n;
					injector.values.set(i + 1, Integer.valueOf(0));
					changed = true;
				} else if ("allow".equals(key)) {
					injector.values.remove(i + 1);
					injector.values.remove(i);
					i -= 2;
					changed = true;
				}
			}
			if (!changed) continue;
			softened++;
			ForbricLog.info("[Forbric/Mixin] %s.%s: require %s → 0 — if the merged game no longer has what it injects into, "
					+ "this one injector goes unattached and its mod is reported, instead of Mixin abandoning the target "
					+ "class for every mod", mixinName.replace('/', '.'), handler, before == null ? "(allow dropped)" : before);
		}
		return softened;
	}

	/** Softens every FAIL_HARD locals capture on {@code mixin}'s injectors; returns how many. */
	public static int soften(ClassNode mixin) {
		if (!enabled() || mixin == null || mixin.methods == null) return 0;
		int softened = 0;
		for (MethodNode m : mixin.methods) {
			softened += soften(m.visibleAnnotations, mixin.name);
			softened += soften(m.invisibleAnnotations, mixin.name);
		}
		return softened;
	}

	private static int soften(List<AnnotationNode> annotations, String mixinName) {
		if (annotations == null) return 0;
		int softened = 0;
		for (AnnotationNode injector : annotations) {
			if (!INJECT_DESC.equals(injector.desc) || injector.values == null) continue;
			for (int i = 0; i + 1 < injector.values.size(); i += 2) {
				if (!"locals".equals(injector.values.get(i))) continue;
				if (injector.values.get(i + 1) instanceof String[] enumValue && enumValue.length == 2
						&& LOCAL_CAPTURE_DESC.equals(enumValue[0]) && "CAPTURE_FAILHARD".equals(enumValue[1])) {
					injector.values.set(i + 1, new String[] { LOCAL_CAPTURE_DESC, "CAPTURE_FAILSOFT" });
					softened++;
					ForbricLog.info("[Forbric/Mixin] %s: @Inject.locals CAPTURE_FAILHARD → CAPTURE_FAILSOFT — if the merged "
							+ "method's locals differ from the ones this mixin was written against, that one injection is "
							+ "skipped with Mixin's own warning instead of killing the game", mixinName.replace('/', '.'));
				}
			}
		}
		return softened;
	}
}
