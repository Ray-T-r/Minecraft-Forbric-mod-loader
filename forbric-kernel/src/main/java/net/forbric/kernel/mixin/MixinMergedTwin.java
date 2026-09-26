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
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Gives a guest mixin the merge's RENAMED twin of its target as a second target.
 *
 * <p>Two anonymous classes could not keep one name through the byte merge, so NeoForge's copy was renamed with a
 * {@code $forbricneo} suffix: {@code CustomPacketPayload$1} and {@code CustomPacketPayload$1$forbricneo} both
 * exist in the merged base. A guest mixin names the vanilla one, because that is the only name its own platform
 * has — and the merged code that actually runs instantiates the twin. The mixin applies cleanly, to the half
 * nothing calls.
 *
 * <p>Bad Packets is the case that paid for it. Its {@code MixinCustomPacketPayload_1} makes that stream codec
 * implement {@code ChannelCodecFinder$Holder}, and its {@code ClientboundCustomPayloadPacket} mixin then casts
 * the live codec to that interface. The live codec is the twin, so joining a world died with
 * {@code ClassCastException: CustomPacketPayload$1$forbricneo cannot be cast to ChannelCodecFinder$Holder}, which
 * the client reports as "Failed to connect to the server — Internal Exception: ExceptionInInitializerError".
 * Nothing in that names the merge, the twin, or Bad Packets' mixin.
 *
 * <p>The twin is added, never substituted: the vanilla-named class is still real and other code still reaches it,
 * so a mixin that meant it keeps it. Targets are appended to {@code @Mixin}'s {@code targets} (the String form),
 * because a renamed anonymous class cannot be spelled as a class literal.
 *
 * <p>{@code -Dforbric.mixinMergedTwins=off} leaves every target list as compiled.
 */
public final class MixinMergedTwin {
	public static final String PROPERTY = "forbric.mixinMergedTwins";
	static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
	static final String AT_DESC = "Lorg/spongepowered/asm/mixin/injection/At;";
	/** The suffix {@code MergedBaseBuilder} gives NeoForge's copy of a class whose name collided. */
	public static final String NEO_SUFFIX = "$forbricneo";

	private MixinMergedTwin() {
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/**
	 * Appends {@code <target>$forbricneo} to {@code mixin}'s {@code targets} for every target that has one.
	 *
	 * @param present answers whether a binary class name exists in the merged base; a target whose twin does not
	 *                exist is left exactly as it was, so this can never invent a target
	 * @return how many twins were added
	 */
	public static int addTwins(ClassNode mixin, Predicate<String> present) {
		if (!enabled() || mixin == null || present == null || mixin.invisibleAnnotations == null) return 0;
		int added = 0;
		Set<String> twinned = new LinkedHashSet<>();
		for (AnnotationNode annotation : mixin.invisibleAnnotations) {
			if (!MIXIN_DESC.equals(annotation.desc) || annotation.values == null) continue;
			added += addTwins(mixin.name, annotation, present, twinned);
		}
		if (!twinned.isEmpty()) {
			unpinInjectionPointOwners(mixin, twinned);
			unmapShadows(mixin);
		}
		return added;
	}

	static final String SHADOW_DESC = "Lorg/spongepowered/asm/mixin/Shadow;";

	/**
	 * Turns {@code remap} off on every {@code @Shadow} of a mixin that just gained a twin — the third half.
	 *
	 * <p>Mixin refuses a remappable shadow in any mixin with more than one target
	 * ({@code MixinInfo$State.validateRemappables}: "Found a remappable @Shadow annotation on val$builder"), and a
	 * second target is exactly what the twin is. The refusal is an {@code InvalidMixinException}, so the mixin is
	 * dropped from BOTH targets, not just the new one: fabric-api's {@code TagAppenderMixin$TagAppender1Mixin}
	 * shadows the anonymous class's captured {@code val$builder} and was lost entirely the moment its twin was
	 * added. Remapping is a refmap lookup from compile-time to runtime names; this game runs under the names the
	 * mod compiled against, so {@code remap = false} changes no name — it only lets the second target stand.
	 */
	static int unmapShadows(ClassNode mixin) {
		int unmapped = 0;
		if (mixin.fields != null) {
			for (org.objectweb.asm.tree.FieldNode field : mixin.fields) unmapped += unmap(field.visibleAnnotations);
		}
		for (MethodNode method : mixin.methods) unmapped += unmap(method.visibleAnnotations);
		if (unmapped > 0) {
			ForbricLog.info("[Forbric/Mixin] %s: %d @Shadow(s) no longer ask to be remapped — Mixin refuses a remappable "
					+ "shadow in a mixin with two targets and would drop it from both; this game runs under the "
					+ "names the mod compiled against, so no name changes", mixin.name.replace('/', '.'), unmapped);
		}
		return unmapped;
	}

	private static int unmap(List<AnnotationNode> annotations) {
		if (annotations == null) return 0;
		int unmapped = 0;
		for (AnnotationNode annotation : annotations) {
			if (!SHADOW_DESC.equals(annotation.desc)) continue;
			if (annotation.values == null) annotation.values = new ArrayList<>();
			int at = -1;
			for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
				if ("remap".equals(annotation.values.get(i))) at = i + 1;
			}
			if (at >= 0) {
				if (Boolean.FALSE.equals(annotation.values.get(at))) continue;
				annotation.values.set(at, Boolean.FALSE);
			} else {
				annotation.values.add("remap");
				annotation.values.add(Boolean.FALSE);
			}
			unmapped++;
		}
		return unmapped;
	}

	/**
	 * Drops the OWNER from every injection point that pins one of {@code twinned} — the second half of the fix.
	 *
	 * <p>A mixin's {@code @At(target = "…/CustomPacketPayload$1.findCodec(…)…")} names the owner class, because
	 * that is the class it was compiled against. Inside the twin, the same call has the TWIN as its owner, so the
	 * injection point matches nothing there: Mixin adds the handler method to the class and wires no call to it.
	 * That is invisible — the class gains the interface and the field the mod asked for, and only the injection is
	 * missing, so Bad Packets' encode hook was silently absent from the very class this pass had just given it.
	 *
	 * <p>Mixin's member selectors treat an absent owner as "any owner", and the name and descriptor stay pinned,
	 * so the point still cannot match a different method. Only owners that actually HAVE a twin are unpinned.
	 */
	static void unpinInjectionPointOwners(ClassNode mixin, Set<String> twinned) {
		List<String> prefixes = new ArrayList<>();
		for (String owner : twinned) prefixes.add(owner.replace('.', '/') + ".");
		int unpinned = 0;
		for (MethodNode method : mixin.methods) {
			unpinned += unpinAll(method.visibleAnnotations, prefixes);
			unpinned += unpinAll(method.invisibleAnnotations, prefixes);
		}
		if (unpinned > 0) {
			ForbricLog.info("[Forbric/Mixin] %s: %d injection point(s) no longer pin the class they were compiled "
					+ "against — the merge's renamed twin owns the same call, and an owner-pinned point matches "
					+ "nothing there while Mixin still adds the handler, so the injection goes missing in silence",
					mixin.name.replace('/', '.'), unpinned);
		}
	}

	private static int unpinAll(List<AnnotationNode> annotations, List<String> prefixes) {
		if (annotations == null) return 0;
		int unpinned = 0;
		for (AnnotationNode annotation : annotations) unpinned += unpin(annotation, prefixes);
		return unpinned;
	}

	/** Walks an annotation's values — {@code @At} sits nested inside {@code @Inject}, {@code @WrapOperation}, … */
	@SuppressWarnings("unchecked")
	private static int unpin(AnnotationNode annotation, List<String> prefixes) {
		if (annotation == null || annotation.values == null) return 0;
		int unpinned = 0;
		for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
			Object name = annotation.values.get(i);
			Object value = annotation.values.get(i + 1);
			if (AT_DESC.equals(annotation.desc) && "target".equals(name) && value instanceof String target) {
				for (String prefix : prefixes) {
					if (!target.startsWith(prefix)) continue;
					annotation.values.set(i + 1, target.substring(prefix.length()));
					unpinned++;
					break;
				}
			} else if (value instanceof AnnotationNode nested) {
				unpinned += unpin(nested, prefixes);
			} else if (value instanceof List<?> list) {
				for (Object item : list) {
					if (item instanceof AnnotationNode nested) unpinned += unpin(nested, prefixes);
				}
			}
		}
		return unpinned;
	}

	@SuppressWarnings("unchecked")
	private static int addTwins(String mixinName, AnnotationNode annotation, Predicate<String> present,
			Set<String> twinned) {
		int targetsAt = -1;
		List<String> targets = List.of();
		for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
			if ("targets".equals(annotation.values.get(i)) && annotation.values.get(i + 1) instanceof List<?> list) {
				targetsAt = i + 1;
				targets = (List<String>) list;
			}
		}
		if (targetsAt < 0) return 0;

		List<String> grown = new ArrayList<>(targets);
		int added = 0;
		for (String target : targets) {
			// Mixin accepts both spellings in `targets`; the twin keeps whichever the mixin used.
			String twin = target + NEO_SUFFIX;
			if (grown.contains(twin) || !present.test(twin.replace('/', '.'))) continue;
			grown.add(twin);
			twinned.add(target);
			added++;
			ForbricLog.info("[Forbric/Mixin] %s also applies to %s — the byte merge could not keep one name for "
					+ "both ecosystems' copy of that class, and the merged code that runs instantiates the renamed "
					+ "one, so a mixin naming only the vanilla name binds to the half nothing calls",
					mixinName.replace('/', '.'), twin.replace('/', '.'));
		}
		if (added > 0) annotation.values.set(targetsAt, grown);
		return added;
	}
}
