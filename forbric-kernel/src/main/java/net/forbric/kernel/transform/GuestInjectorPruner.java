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

package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Removes named injector methods from a GUEST MIXIN class before Mixin reads it, so that a mixin whose other
 * injectors fit the merged base can apply instead of being pinned whole.
 *
 * <p>The one entry so far is fabric-model-loading-api-v1's {@code ModelManagerMixin}. NeoForge won the byte-merge
 * of {@code ModelManager.lambda$loadBlockModels$2} and replaced vanilla's {@code CuboidModel.fromStream(Reader)}
 * there with its own {@code UnbakedModelParser.parse(Reader)} — the dispatch point for NeoForge {@code "loader"}
 * model formats. Fabric's {@code @Redirect cancelVanillaDeserialize} targets {@code fromStream}, so it cannot
 * bind; its sibling {@code @ModifyArg actuallyDeserializeModel} at {@code Pair.of} DOES bind and hands an
 * already-consumed {@code Reader} to Fabric's deserializer registry. Every one of the 4666 block models then dies
 * on {@code JsonParseException: JSON data was null or empty} and the whole world renders as the missingno
 * checkerboard — the measured PARTIAL that made {@link net.forbric.kernel.mixin.MergedBaseMixinCompat} pin the
 * mixin. Pinning it cost every {@code ModelLoadingPlugin}: block-state resolvers, extra models and per-model
 * modifiers registered by Fabric mods were never called.
 *
 * <p>The two deserializer injectors are the ONLY ones that cannot fit. The other eight — plugin preparation at
 * reload HEAD, the on-load model and block-state modifiers, the thread-local dispatcher around collect and bake,
 * extra-model resolution and the post-upload capture — anchor on instructions the merged {@code ModelManager}
 * still has. Removing the pair from the mixin's bytes lets Mixin apply the rest as written, while NeoForge's
 * parser keeps the call site, so NeoForge {@code "loader"} models keep working too. The residual loss is Fabric's
 * {@code fabric:type} custom model formats ({@code UnbakedModelDeserializer}), which now parse through
 * NeoForge's Gson instead — no staged mod uses them.
 *
 * <p>Guest mixin classes reach the transform chain through {@code ForbricClassLoader.getPreMixinClassBytes},
 * which is also what {@link net.forbric.kernel.mixin.MixinFit} and Mixin itself read, so the pruned bytes are
 * the only bytes anyone judges or applies. Both methods must be present, each carrying an injector annotation
 * whose {@code method} list names {@code lambda$loadBlockModels$2}; a fabric-api that reshapes either leaves the
 * class untouched, with a warning, and the whole mixin then reads PARTIAL as it did before this class existed.
 *
 * <p>{@code -Dforbric.guestInjectorPruner=off} restores the previous behaviour EXACTLY: the pruner stands down and
 * {@code MergedBaseMixinCompat.SUPPRESSED_UNLESS_PRUNED} puts the whole-mixin pin back — never the half-applied
 * state.
 */
public final class GuestInjectorPruner implements ClassTransformer {
	public static final String PROPERTY = "forbric.guestInjectorPruner";

	static final String MODEL_MANAGER_MIXIN = "net.fabricmc.fabric.mixin.client.model.loading.ModelManagerMixin";
	static final String MODEL_LAMBDA = "lambda$loadBlockModels$2";

	/** One injector method to remove, and the target-method selector prefix its annotation must carry. */
	record Prune(String name, String desc, String selectorPrefix) {
		String key() {
			return name + desc;
		}
	}

	static final Map<String, List<Prune>> TABLE = Map.of(MODEL_MANAGER_MIXIN, List.of(
			new Prune("cancelVanillaDeserialize",
					"(Ljava/io/Reader;)Lnet/minecraft/client/resources/model/cuboid/CuboidModel;", MODEL_LAMBDA),
			new Prune("actuallyDeserializeModel",
					"(Ljava/lang/Object;Ljava/io/Reader;)Ljava/lang/Object;", MODEL_LAMBDA)));

	/** The mixin config each entry is declared in, which names the owning mod on the finding. */
	static final Map<String, String> CONFIGS = Map.of(MODEL_MANAGER_MIXIN, "fabric-model-loading-api-v1.mixins.json");

	/** Every annotation that makes a mixin method an injector: Mixin's own and MixinExtras'. */
	static final Set<String> INJECTOR_DESCS = Set.of(
			"Lorg/spongepowered/asm/mixin/injection/Inject;",
			"Lorg/spongepowered/asm/mixin/injection/Redirect;",
			"Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
			"Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
			"Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
			"Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
			"Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;",
			"Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
			"Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
			"Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;",
			"Lcom/llamalad7/mixinextras/injector/WrapWithCondition;",
			"Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;");

	private int pruned;

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override
	public String name() {
		return "forbric:guest-injector-pruner";
	}

	@Override
	public AnchorSet anchors() {
		List<AnchorSet.Anchor> anchors = new ArrayList<>();
		for (String mixin : TABLE.keySet()) {
			anchors.add(new AnchorSet.Anchor(mixin, AnchorSet.Severity.REQUIRED,
					"the whole mixin stays pinned, so every Fabric ModelLoadingPlugin -- block-state resolvers, extra "
							+ "models, model modifiers -- is registered and never called"));
		}
		return AnchorSet.of(anchors.toArray(new AnchorSet.Anchor[0]));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		List<Prune> prunes = TABLE.get(className);
		if (prunes == null || !enabled()) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		if (node.methods == null) return classBytes;

		// Both-or-nothing: the pair only makes sense together. Half of it gone is exactly the half-applied state
		// this class exists to avoid, so any drift in either stands the whole edit down.
		List<MethodNode> victims = new ArrayList<>();
		for (Prune prune : prunes) {
			MethodNode found = null;
			for (MethodNode m : node.methods) {
				if (prune.name().equals(m.name) && prune.desc().equals(m.desc)) { found = m; break; }
			}
			if (found == null) {
				// Absent on a second pass is what idempotence looks like; absent on the first is drift.
				if (alreadyPruned(node, prunes)) return classBytes;
				ForbricLog.warn("[Forbric/GuestInjectorPruner] %s has no %s%s — fabric-api reshaped the mixin, leaving "
						+ "it untouched (it will read PARTIAL and stay pinned)", className, prune.name(), prune.desc());
				return classBytes;
			}
			if (!isInjectorInto(found, prune.selectorPrefix())) {
				ForbricLog.warn("[Forbric/GuestInjectorPruner] %s.%s no longer injects into %s — fabric-api reshaped "
						+ "the mixin, leaving it untouched (it will read PARTIAL and stay pinned)", className,
						prune.name(), prune.selectorPrefix());
				return classBytes;
			}
			victims.add(found);
		}

		node.methods.removeAll(victims);
		pruned += victims.size();
		// Removed, so never run: a confirmed finding for each, naming what NeoForge's parser does not cover. The
		// log line below is not the report.
		for (MethodNode victim : victims) {
			net.forbric.kernel.mixin.MixinCompatibility.recordRemovedInjector(CONFIGS.get(className), className,
					victim.name, victim.desc, "the kernel removed this injector: NeoForge's UnbakedModelParser now "
							+ "reads block models at its call site, so Fabric's fabric:type custom model formats "
							+ "(UnbakedModelDeserializer) are not consulted",
					List.of("kernel pruned " + victim.name + victim.desc + " from " + className,
							"target selector " + MODEL_LAMBDA, "source=GuestInjectorPruner"));
		}
		ForbricLog.info("[Forbric/GuestInjectorPruner] pruned %d injector(s) from %s — NeoForge replaced "
				+ "CuboidModel.fromStream with UnbakedModelParser.parse at that site, so fabric's @Redirect could not "
				+ "bind while its @ModifyArg did and re-read a consumed Reader (every block model missingno); the "
				+ "other %d injector(s) apply as written", victims.size(), className,
				countInjectors(node));

		// Only whole methods were removed: no instruction, frame or local changed, so nothing needs recomputing.
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** Whether {@code m} carries an injector annotation whose {@code method} list has a selector starting with {@code prefix}. */
	static boolean isInjectorInto(MethodNode m, String prefix) {
		for (AnnotationNode a : allAnnotations(m)) {
			if (!INJECTOR_DESCS.contains(a.desc)) continue;
			List<Object> values = a.values;
			if (values == null) continue;
			for (int i = 0; i + 1 < values.size(); i += 2) {
				if (!"method".equals(values.get(i))) continue;
				Object v = values.get(i + 1);
				if (v instanceof List<?> list) {
					for (Object s : list) if (s instanceof String str && str.startsWith(prefix)) return true;
				} else if (v instanceof String str && str.startsWith(prefix)) {
					return true;
				}
			}
		}
		return false;
	}

	private static List<AnnotationNode> allAnnotations(MethodNode m) {
		List<AnnotationNode> out = new ArrayList<>();
		if (m.visibleAnnotations != null) out.addAll(m.visibleAnnotations);
		if (m.invisibleAnnotations != null) out.addAll(m.invisibleAnnotations);
		return out;
	}

	private static boolean alreadyPruned(ClassNode node, List<Prune> prunes) {
		for (Prune p : prunes) {
			for (MethodNode m : node.methods) {
				if (p.name().equals(m.name) && p.desc().equals(m.desc)) return false;
			}
		}
		return true;
	}

	private static int countInjectors(ClassNode node) {
		int n = 0;
		for (MethodNode m : node.methods) {
			for (AnnotationNode a : allAnnotations(m)) {
				if (INJECTOR_DESCS.contains(a.desc)) { n++; break; }
			}
		}
		return n;
	}

	/** How many injector methods were removed, for the boot summary. */
	public int prunedInjectors() {
		return pruned;
	}
}
