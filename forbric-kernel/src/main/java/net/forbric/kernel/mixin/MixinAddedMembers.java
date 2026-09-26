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
import java.util.Comparator;
import java.util.HashMap;
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
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * The members other guest mixins add to a class before a given mixin is applied to it: what that mixin's
 * {@code @Shadow} of such a member binds to.
 *
 * <p>{@link MixinFit} resolves a {@code @Shadow} against the merged class, and a member another mod's mixin adds is in
 * no merged class. moreculling's {@code BlockModelRenderState_fabricCullMixin} shadows {@code mesh}, the
 * {@code @Unique MutableMesh} fabric-renderer-api-v1's own {@code BlockModelRenderStateMixin} adds to
 * {@code BlockModelRenderState}; it was reported as "applies only partially — missing @Shadow field mesh" on every
 * client boot, a possible problem in the load report for a mixin that binds exactly as it does on Fabric.
 *
 * <p>Order is part of the answer. Mixin applies the mixins of one target lowest {@code priority} first and, at equal
 * priority, in the order it created them: configs by their own {@code priority}, then as registered, and within a
 * config its {@code mixins} array before the side's. A {@code @Shadow} finds only what the mixins before it have
 * added ({@code MixinTargetContext.findField} reads the target's node as it stands); applied after, the member is not
 * there yet and Mixin fails the shadow. moreculling is priority 1200 and fabric-renderer 1000, so its shadow binds.
 * {@code @Unique} members count: Mixin adds them under their own name unless the target already has one, and then the
 * shadow binds to the target's own.
 *
 * <p>What is added: a field without {@code @Shadow}, and a method that is neither a {@code @Shadow}, an
 * {@code @Overwrite} nor an injector (Mixin renames handlers), nor synthetic (a lambda gets a new name), nor abstract
 * unless it is an {@code @Accessor}/{@code @Invoker}, which Mixin generates under its own name. Only the target
 * itself: Mixin looks for a shadow in the target's node, not up its hierarchy. Only toward "resolved", and only
 * from what the configs declare: a mixin its plugin or the kernel later declines is still counted, which at worst
 * leaves a miss unreported, as it was before any of this.
 *
 * <p>{@code -Dforbric.mixinFit.addedMembers=off} judges a shadow against the merged class alone again.
 */
public final class MixinAddedMembers {
	public static final String PROPERTY = "forbric.mixinFit.addedMembers";
	private static final int DEFAULT_PRIORITY = 1000;
	private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
	private static final String SHADOW_DESC = "Lorg/spongepowered/asm/mixin/Shadow;";
	private static final String OVERWRITE_DESC = "Lorg/spongepowered/asm/mixin/Overwrite;";
	private static final String ACCESSOR_DESC = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
	private static final String INVOKER_DESC = "Lorg/spongepowered/asm/mixin/gen/Invoker;";

	/** What one mixin sees of the members other mixins added to a target before it. */
	public interface View {
		/** Whether a mixin applied to {@code target} (internal name) before this one adds the field. */
		boolean field(String target, String name, String desc);

		/** Whether a mixin applied to {@code target} before this one adds the method. */
		boolean method(String target, String name, String desc);

		/** Nothing is added: the judgement against the merged class alone. */
		View NONE = new View() {
			@Override
			public boolean field(String target, String name, String desc) {
				return false;
			}

			@Override
			public boolean method(String target, String name, String desc) {
				return false;
			}
		};
	}

	/** One prepared mixin, in the order Mixin compares them: {@code priority}, then {@code order}. */
	record Slot(String config, String entry, int priority, int order) {
		boolean appliesBefore(Slot other) {
			return priority != other.priority ? priority < other.priority : order < other.order;
		}
	}

	/** A member {@code by} adds to one target. */
	record Added(Slot by, boolean field, String name, String desc) {
	}

	/** Every prepared mixin by {@code config#entry}, and what each target gains. */
	record Index(Map<String, Slot> slots, Map<String, List<Added>> byTarget) {
	}

	private static volatile Index index;

	private MixinAddedMembers() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** Forgets the built index. For tests, which register different config sets in one JVM. */
	static void reset() {
		index = null;
	}

	/**
	 * What the mixin {@code entry} of {@code configName} will find on its targets beyond the merged class. The index
	 * is built on the first question, not here: most mixins shadow nothing that is missing.
	 *
	 * @param resource resolves a config name or {@code some/pkg/Name.class} to its bytes, as the adapter's does
	 */
	public static View before(String configName, String entry, Function<String, byte[]> resource) {
		if (!enabled() || configName == null || entry == null || resource == null) return View.NONE;
		String key = configName + "#" + entry;
		return new View() {
			@Override
			public boolean field(String target, String name, String desc) {
				return added(key, target, true, name, desc, resource);
			}

			@Override
			public boolean method(String target, String name, String desc) {
				return added(key, target, false, name, desc, resource);
			}
		};
	}

	private static boolean added(String self, String target, boolean field, String name, String desc,
			Function<String, byte[]> resource) {
		if (target == null || name == null || desc == null) return false;
		Index built = index(resource);
		Slot own = built.slots().get(self);
		// Not a mixin Mixin prepares here (the other side's array, a config nobody registered): no order to compare.
		if (own == null) return false;
		for (Added added : built.byTarget().getOrDefault(target, List.of())) {
			if (added.field() != field || !added.name().equals(name) || !added.desc().equals(desc)) continue;
			if (added.by().equals(own)) continue;
			if (added.by().appliesBefore(own)) return true;
		}
		return false;
	}

	private static Index index(Function<String, byte[]> resource) {
		Index built = index;
		if (built != null) return built;
		synchronized (MixinAddedMembers.class) {
			if (index != null) return index;
			index = build(ForbricMixinService.registeredConfigNames(), resource, ForbricMixinService.side());
			return index;
		}
	}

	/** One registered config, read. */
	private record Config(String name, int registered, int priority, int mixinPriority, String pkgPath,
			List<String> entries) {
	}

	static Index build(Iterable<String> registered, Function<String, byte[]> resource,
			net.fabricmc.api.EnvType side) {
		List<Config> configs = new ArrayList<>();
		int registration = 0;
		for (String name : registered) {
			Config config = read(name, registration++, resource, side);
			if (config != null) configs.add(config);
		}
		// MixinConfig.compareTo: the config's own priority, then the order it was created in. List.sort is stable.
		configs.sort(Comparator.comparingInt(Config::priority).thenComparingInt(Config::registered));

		Map<String, Slot> slots = new HashMap<>();
		Map<String, List<Added>> byTarget = new HashMap<>();
		int order = 0;
		for (Config config : configs) {
			for (String entry : config.entries()) {
				byte[] bytes = resource.apply(config.pkgPath() + "/" + entry.replace('.', '/') + ".class");
				if (bytes == null) continue;
				ClassNode mixin;
				try {
					mixin = new ClassNode();
					new ClassReader(bytes).accept(mixin, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
				} catch (RuntimeException unreadable) {
					continue; // one unparseable mixin must not cost the rest of the index
				}
				Slot slot = new Slot(config.name(), entry, priorityOf(mixin, config.mixinPriority()), order++);
				slots.put(config.name() + "#" + entry, slot);
				List<Added> members = membersOf(mixin, slot);
				if (members.isEmpty()) continue;
				for (String target : MixinFit.mixinTargets(mixin)) {
					byTarget.computeIfAbsent(target, t -> new ArrayList<>()).addAll(members);
				}
			}
		}
		ForbricLog.debug("[Forbric/Mixin] indexed %d prepared mixin(s) and the members they add to %d class(es) — a "
				+ "@Shadow of one of them binds on Fabric, so it is not a miss here", slots.size(), byTarget.size());
		return new Index(Map.copyOf(slots), Map.copyOf(byTarget));
	}

	private static Config read(String name, int registered, Function<String, byte[]> resource,
			net.fabricmc.api.EnvType side) {
		byte[] json = resource.apply(name);
		if (json == null) return null;
		UnmodifiableConfig config;
		try (Reader reader = new InputStreamReader(new ByteArrayInputStream(json), StandardCharsets.UTF_8)) {
			config = JsonFormat.fancyInstance().createParser().parse(reader);
		} catch (RuntimeException | java.io.IOException notAMixinConfig) {
			return null;
		}
		Object pkg = config.get(List.of("package"));
		if (pkg == null || pkg.toString().isEmpty()) return null;
		Set<String> entries = KernelGuestMixinAdapter.appliedEntries(config, side);
		return new Config(name, registered, intOf(config.get(List.of("priority"))),
				intOf(config.get(List.of("mixinPriority"))), pkg.toString().replace('.', '/'), List.copyOf(entries));
	}

	private static int intOf(Object value) {
		return value instanceof Number number ? number.intValue() : DEFAULT_PRIORITY;
	}

	/** {@code @Mixin(priority = …)}, or the config's {@code mixinPriority}, as {@code MixinInfo.readPriority} reads it. */
	static int priorityOf(ClassNode mixin, int configDefault) {
		for (List<AnnotationNode> table : java.util.Arrays.asList(mixin.invisibleAnnotations, mixin.visibleAnnotations)) {
			if (table == null) continue;
			for (AnnotationNode a : table) {
				if (!MIXIN_DESC.equals(a.desc)) continue;
				Object priority = MixinFit.value(a, "priority");
				return priority instanceof Integer value ? value : configDefault;
			}
		}
		return configDefault;
	}

	private static List<Added> membersOf(ClassNode mixin, Slot slot) {
		List<Added> out = new ArrayList<>();
		if (mixin.fields != null) {
			for (FieldNode f : mixin.fields) {
				if ((f.access & Opcodes.ACC_SYNTHETIC) != 0 || has(f.visibleAnnotations, f.invisibleAnnotations, SHADOW_DESC)) continue;
				out.add(new Added(slot, true, f.name, f.desc));
			}
		}
		if (mixin.methods != null) {
			for (MethodNode m : mixin.methods) {
				if (m.name.startsWith("<") || (m.access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0) continue;
				if (has(m.visibleAnnotations, m.invisibleAnnotations, SHADOW_DESC)
						|| has(m.visibleAnnotations, m.invisibleAnnotations, OVERWRITE_DESC)
						|| MixinFit.injectorOf(m) != null) continue;
				boolean generated = has(m.visibleAnnotations, m.invisibleAnnotations, ACCESSOR_DESC)
						|| has(m.visibleAnnotations, m.invisibleAnnotations, INVOKER_DESC);
				if ((m.access & Opcodes.ACC_ABSTRACT) != 0 && !generated) continue;
				out.add(new Added(slot, false, m.name, m.desc));
			}
		}
		return out;
	}

	private static boolean has(List<AnnotationNode> visible, List<AnnotationNode> invisible, String desc) {
		for (List<AnnotationNode> table : java.util.Arrays.asList(visible, invisible)) {
			if (table == null) continue;
			for (AnnotationNode a : table) if (desc.equals(a.desc)) return true;
		}
		return false;
	}
}
