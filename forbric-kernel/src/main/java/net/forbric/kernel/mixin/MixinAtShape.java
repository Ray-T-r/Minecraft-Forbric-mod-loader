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
import java.util.Set;

import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Gives a guest mixin's single-point injectors the {@code at} shape the Mixin this instance runs declares.
 *
 * <p>{@code @Redirect}, {@code @ModifyArg}, {@code @ModifyArgs} and {@code @ModifyVariable} declare
 * {@code At at()} — one injection point — in Mixin 0.8.7. A mod compiled against another fork's annotation
 * carries {@code at=[@At(...)]}, an array with one element (architectury-fabric 21.1.10's MixinNaturalSpawner and
 * MixinBaseSpawner). Mixin itself reads either shape; MixinExtras' pre-apply transformer casts the value to one
 * {@code AnnotationNode} and dies with a {@code ClassCastException} on the first target class, which took the
 * whole popular-set server down at {@code EntityTypes.<clinit>}. A one-element array and the element mean the
 * same thing, so the array is unwrapped on the node Mixin receives; an array of two or more is left alone, since
 * no single-point injector can mean that. {@code -Dforbric.mixinAtShape=off} leaves every shape as compiled.
 */
public final class MixinAtShape {
	public static final String PROPERTY = "forbric.mixinAtShape";
	static final String AT_DESC = "Lorg/spongepowered/asm/mixin/injection/At;";
	/** The injectors whose {@code at} is a single {@code @At} in Mixin 0.8.7. */
	static final Set<String> SINGLE_AT = Set.of(
			"Lorg/spongepowered/asm/mixin/injection/Redirect;",
			"Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
			"Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
			"Lorg/spongepowered/asm/mixin/injection/ModifyVariable;");

	private MixinAtShape() {
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** Unwraps every one-element {@code at} array on a single-point injector of {@code mixin}; returns how many. */
	public static int normalise(ClassNode mixin) {
		if (!enabled() || mixin == null || mixin.methods == null) return 0;
		int unwrapped = 0;
		for (MethodNode m : mixin.methods) {
			unwrapped += normalise(m.visibleAnnotations, mixin.name);
			unwrapped += normalise(m.invisibleAnnotations, mixin.name);
		}
		return unwrapped;
	}

	private static int normalise(List<AnnotationNode> annotations, String mixinName) {
		if (annotations == null) return 0;
		int unwrapped = 0;
		for (AnnotationNode injector : annotations) {
			if (!SINGLE_AT.contains(injector.desc) || injector.values == null) continue;
			for (int i = 0; i + 1 < injector.values.size(); i += 2) {
				if (!"at".equals(injector.values.get(i))) continue;
				if (injector.values.get(i + 1) instanceof List<?> list && list.size() == 1
						&& list.get(0) instanceof AnnotationNode single && AT_DESC.equals(single.desc)) {
					injector.values.set(i + 1, single);
					unwrapped++;
					ForbricLog.info("[Forbric/Mixin] %s: @%s.at was compiled as a one-element array (another Mixin fork's "
							+ "shape); unwrapped to the single injection point the Mixin this instance runs declares, before "
							+ "MixinExtras reads it", mixinName.replace('/', '.'),
							injector.desc.substring(injector.desc.lastIndexOf('/') + 1, injector.desc.length() - 1));
				}
			}
		}
		return unwrapped;
	}
}
