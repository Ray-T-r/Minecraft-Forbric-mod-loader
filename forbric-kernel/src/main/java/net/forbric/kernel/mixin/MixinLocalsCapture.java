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
