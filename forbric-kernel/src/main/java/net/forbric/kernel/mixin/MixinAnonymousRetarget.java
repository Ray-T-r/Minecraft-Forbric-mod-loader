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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Points a guest mixin at the anonymous class the byte merge moved its target's body into.
 *
 * <p>javac numbers anonymous classes per outer class in source order, so a carrier patch that adds one before
 * vanilla's renumbers every later one. The merge keeps one class per name, and the loser's body moves: on this
 * base {@code ByteBufCodecs$30} is a carrier's codec, and vanilla's — the one every mod was compiled against —
 * is at {@code $32}. {@link MergedBaseAnonymousDrift} is the measured table of where each body went.
 *
 * <p>{@link MixinFit} already reports this, which is how it was found: Polymer's {@code ByteBufCodecsHolderMixin}
 * and {@code ByteBufCodecsRegistryMixin} were auto-suppressed as UNFIT with the reason naming the new home. But
 * reporting is all it did, and the cost of those two mixins going missing is not a missing feature — Polymer then
 * sent {@code polymer:hello} with a codec nothing could encode, and the client was disconnected at world join
 * with {@code HelloS2CPayload cannot be cast to DiscardedPayload}. A stack that names Netty, vanilla's codec and
 * the mod, and nothing about an anonymous class that changed number.
 *
 * <p>Replaced, not appended — the difference from {@link MixinMergedTwin}. A renamed TWIN is still vanilla's class
 * under another name, so a mixin that meant it keeps it. A renumbered name is a DIFFERENT class that merely
 * inherited the number, and leaving the mixin on it is the bug: every anchor resolves and the injections bind to
 * unrelated code.
 *
 * <p>Only when the table names exactly ONE candidate and it exists here. Several candidates — the same body
 * duplicated across numbers — and it declines, because the mixin would then be moved somewhere by a coin flip,
 * and a wrong home is worse than the honest UNFIT report this replaces.
 *
 * <p>{@code -Dforbric.mixinAnonymousDrift=off} leaves every target as compiled.
 */
public final class MixinAnonymousRetarget {
	public static final String PROPERTY = "forbric.mixinAnonymousDrift";
	private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";

	private MixinAnonymousRetarget() {
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/**
	 * Where {@code internalName}'s body went, when the table names exactly one home and it is here.
	 *
	 * <p>Shared with {@link MixinFit} so the verdict and the rewrite can never disagree: judging the mixin against
	 * the class it will NOT be applied to is how a mixin gets suppressed for anchors that do resolve, or kept for
	 * anchors that do not.
	 *
	 * @return the home's internal name, or {@code null} when this target is not moving
	 */
	public static String home(String internalName, Predicate<String> present) {
		if (!enabled() || internalName == null || present == null) return null;
		List<String> candidates = MergedBaseAnonymousDrift.RELOCATED.get(internalName);
		if (candidates == null || candidates.size() != 1) return null;
		String home = candidates.get(0);
		return present.test(home) ? home : null;
	}

	/**
	 * Rewrites every {@code targets} entry naming a relocated anonymous class to where its body went.
	 *
	 * @param present answers whether a binary class name exists in the merged base; a candidate that is not here
	 *                is never written in, so this cannot invent a target
	 * @return how many targets were moved
	 */
	public static int retarget(ClassNode mixin, Predicate<String> present) {
		if (!enabled() || mixin == null || present == null || mixin.invisibleAnnotations == null) return 0;

		int moved = 0;
		Set<String> movedFrom = new LinkedHashSet<>();
		for (AnnotationNode annotation : mixin.invisibleAnnotations) {
			if (!MIXIN_DESC.equals(annotation.desc) || annotation.values == null) continue;
			moved += retarget(mixin.name, annotation, present, movedFrom);
		}
		// The same second half MixinMergedTwin needs: an @At that pins the OLD owner matches nothing in the new
		// class, and Mixin adds the handler anyway — the injection would go missing without a word.
		if (!movedFrom.isEmpty()) MixinMergedTwin.unpinInjectionPointOwners(mixin, movedFrom);
		return moved;
	}

	@SuppressWarnings("unchecked")
	private static int retarget(String mixinName, AnnotationNode annotation, Predicate<String> present,
			Set<String> movedFrom) {
		int targetsAt = -1;
		List<String> targets = List.of();
		for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
			if ("targets".equals(annotation.values.get(i)) && annotation.values.get(i + 1) instanceof List<?> list) {
				targetsAt = i + 1;
				targets = (List<String>) list;
			}
		}
		if (targetsAt < 0) return 0;

		List<String> rewritten = new ArrayList<>(targets);
		int moved = 0;
		for (int i = 0; i < rewritten.size(); i++) {
			String target = rewritten.get(i);
			String internal = target.replace('.', '/');
			String home = home(internal, name -> present.test(name.replace('/', '.')));
			if (home == null) continue;
			// Keep the spelling the mixin used; Mixin accepts either in `targets`.
			rewritten.set(i, target.indexOf('/') >= 0 ? home : home.replace('/', '.'));
			movedFrom.add(internal);
			moved++;
			ForbricLog.info("[Forbric/Mixin] %s targets %s, which on this base is a carrier's class that inherited "
					+ "the number — vanilla's body, the one this mixin was compiled against, is at %s, so the "
					+ "target is moved there rather than applying cleanly to unrelated code",
					mixinName.replace('/', '.'), target.replace('/', '.'), home.replace('/', '.'));
		}
		if (moved > 0) annotation.values.set(targetsAt, rewritten);
		return moved;
	}
}
