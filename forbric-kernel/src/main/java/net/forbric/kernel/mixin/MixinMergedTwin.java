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
import java.util.List;
import java.util.function.Predicate;

import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

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
		for (AnnotationNode annotation : mixin.invisibleAnnotations) {
			if (!MIXIN_DESC.equals(annotation.desc) || annotation.values == null) continue;
			added += addTwins(mixin.name, annotation, present);
		}
		return added;
	}

	@SuppressWarnings("unchecked")
	private static int addTwins(String mixinName, AnnotationNode annotation, Predicate<String> present) {
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
