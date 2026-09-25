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
import java.util.function.BooleanSupplier;

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
 *
 * <p>The second entry is fabric-item-api-v1's {@code ItemStackMixin}. Its five tooltip injectors thread one
 * {@code @Share("index")} through vanilla's {@code addDetailsToTooltip}, which NeoForge turned into a dispatcher over
 * its own appender lists: three bound in a renamed body nothing calls, one drew every Fabric line at once above the
 * item id in advanced tooltips, one bound nowhere. The kernel draws Fabric's component tooltips from NeoForge's
 * appenders instead ({@code KernelNeoTooltips}), so the five go — only while that bridge is on, or they would draw
 * the same lines twice — and {@code hookDamage} (custom damage handlers) applies as written. Nothing is recorded for
 * them: the bridge does their job, and {@code FabricApiModuleLossAudit} names a mod's use of the registry when it
 * is off.
 */
public final class GuestInjectorPruner implements ClassTransformer {
	public static final String PROPERTY = "forbric.guestInjectorPruner";

	static final String MODEL_MANAGER_MIXIN = "net.fabricmc.fabric.mixin.client.model.loading.ModelManagerMixin";
	static final String MODEL_LAMBDA = "lambda$loadBlockModels$2";
	static final String ITEM_STACK_MIXIN = "net.fabricmc.fabric.mixin.item.ItemStackMixin";
	private static final String SHARED_INDEX = "Lcom/llamalad7/mixinextras/sugar/ref/LocalIntRef;";

	/**
	 * One injector method to remove, and the target-method selector its annotation must carry: a prefix, or with
	 * {@code exact} the whole selector — {@code addDetailsToTooltip} is also the prefix of the two renamed bodies.
	 */
	record Prune(String name, String desc, String selectorPrefix, boolean exact) {
		Prune(String name, String desc, String selectorPrefix) {
			this(name, desc, selectorPrefix, false);
		}

		String key() {
			return name + desc;
		}
	}

	static final Map<String, List<Prune>> TABLE = Map.of(MODEL_MANAGER_MIXIN, List.of(
			new Prune("cancelVanillaDeserialize",
					"(Ljava/io/Reader;)Lnet/minecraft/client/resources/model/cuboid/CuboidModel;", MODEL_LAMBDA),
			new Prune("actuallyDeserializeModel",
					"(Ljava/lang/Object;Ljava/io/Reader;)Ljava/lang/Object;", MODEL_LAMBDA)),
			ITEM_STACK_MIXIN, List.of(
			new Prune("preAppendComponentTooltip", "(Lnet/minecraft/core/component/DataComponentType;Lnet/minecraft/world/item/Item$TooltipContext;"
					+ "Lnet/minecraft/world/item/component/TooltipDisplay;Lnet/minecraft/world/item/TooltipFlag;Ljava/util/function/Consumer;"
					+ SHARED_INDEX + ")Lnet/minecraft/core/component/DataComponentType;", "addDetailsToTooltip", true),
			new Prune("preShouldDisplay", "(Lnet/minecraft/core/component/DataComponentType;Lnet/minecraft/world/item/Item$TooltipContext;"
					+ "Lnet/minecraft/world/item/component/TooltipDisplay;Lnet/minecraft/world/item/TooltipFlag;Ljava/util/function/Consumer;"
					+ SHARED_INDEX + ")Lnet/minecraft/core/component/DataComponentType;", "addDetailsToTooltip", true),
			new Prune("preAttributeModifiers", "(Lnet/minecraft/world/item/Item$TooltipContext;Lnet/minecraft/world/item/component/TooltipDisplay;"
					+ "Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/TooltipFlag;Ljava/util/function/Consumer;"
					+ "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;" + SHARED_INDEX + ")V", "addDetailsToTooltip", true),
			new Prune("postTooltipsAdvanced", "(Lnet/minecraft/world/item/Item$TooltipContext;Lnet/minecraft/world/item/component/TooltipDisplay;"
					+ "Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/TooltipFlag;Ljava/util/function/Consumer;"
					+ "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;" + SHARED_INDEX + ")V", "addDetailsToTooltip", true),
			new Prune("postTooltipsNonAdvanced", "(ZLnet/minecraft/world/item/Item$TooltipContext;Lnet/minecraft/world/item/component/TooltipDisplay;"
					+ "Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/TooltipFlag;Ljava/util/function/Consumer;"
					+ SHARED_INDEX + ")Z", "addDetailsToTooltip", true)));

	/** The mixin config each entry is declared in, which names the owning mod on the finding. */
	static final Map<String, String> CONFIGS = Map.of(MODEL_MANAGER_MIXIN, "fabric-model-loading-api-v1.mixins.json",
			ITEM_STACK_MIXIN, "fabric-item-api-v1.mixins.json");

	/** Whether an entry applies on this boot, beyond the pruner's own switch. */
	private static final Map<String, BooleanSupplier> ACTIVE = Map.of(MODEL_MANAGER_MIXIN, () -> true,
			ITEM_STACK_MIXIN, GuestInjectorPruner::fabricTooltipBridgeOn);

	/** What is lost when an entry's class loads and is not pruned. */
	private static final Map<String, String> COSTS = Map.of(MODEL_MANAGER_MIXIN,
			"the whole mixin stays pinned, so every Fabric ModelLoadingPlugin -- block-state resolvers, extra "
					+ "models, model modifiers -- is registered and never called",
			ITEM_STACK_MIXIN, "fabric-item-api's tooltip injectors stay where the retarget put them, so the kernel's "
					+ "tooltip bridge stands down and a Fabric mod's component tooltips are missing from normal tooltips");

	/** Why an entry's injectors cannot stay, for the log line. */
	private static final Map<String, String> REASONS = Map.of(MODEL_MANAGER_MIXIN,
			"NeoForge replaced CuboidModel.fromStream with UnbakedModelParser.parse at that site, so fabric's @Redirect "
					+ "could not bind while its @ModifyArg did and re-read a consumed Reader (every block model missingno)",
			ITEM_STACK_MIXIN, "NeoForge's ItemStack draws tooltips from its appender lists, where the kernel draws "
					+ "Fabric's component tooltip providers now; these would have drawn them a second time, or nowhere");

	/** What happens to an entry's mixin when a reshaped fabric-api leaves it untouched. */
	private static final Map<String, String> DRIFT = Map.of(MODEL_MANAGER_MIXIN, "it will read PARTIAL and apply half — the state that made every block "
					+ "model missingno",
			ITEM_STACK_MIXIN, "it is retargeted as before and the kernel's tooltip bridge stands down; Fabric component "
					+ "tooltip providers show only above the item id in advanced tooltips");

	/** The finding a removed injector records, or none when a kernel repair does its job. */
	private static final Map<String, String> LOSSES = Map.of(MODEL_MANAGER_MIXIN,
			"the kernel removed this injector: NeoForge's UnbakedModelParser now reads block models at its call site, so "
					+ "Fabric's fabric:type custom model formats (UnbakedModelDeserializer) are not consulted");

	private static volatile boolean fabricTooltipsPruned;

	/**
	 * fabric-item-api's tooltip injectors go only while the kernel draws Fabric's providers from NeoForge's appenders:
	 * NeoForge's appenders built, and the bridge on.
	 */
	public static boolean fabricTooltipBridgeOn() {
		return !"off".equalsIgnoreCase(System.getProperty("forbric.neoTooltipAppenders", "on"))
				&& !"off".equalsIgnoreCase(System.getProperty(FABRIC_TOOLTIP_BRIDGE, "on"));
	}

	/** {@code -Dforbric.fabricTooltipBridge=off} leaves fabric-item-api's tooltip injectors where they were. */
	public static final String FABRIC_TOOLTIP_BRIDGE = "forbric.fabricTooltipBridge";

	/** Whether fabric-item-api's five tooltip injectors were removed on this boot — the bridge draws only then. */
	public static boolean fabricTooltipInjectorsPruned() {
		return fabricTooltipsPruned;
	}

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
			if (!ACTIVE.get(mixin).getAsBoolean()) continue;
			anchors.add(new AnchorSet.Anchor(mixin, AnchorSet.Severity.REQUIRED, COSTS.get(mixin)));
		}
		return AnchorSet.of(anchors.toArray(new AnchorSet.Anchor[0]));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		List<Prune> prunes = TABLE.get(className);
		if (prunes == null || !enabled() || !ACTIVE.get(className).getAsBoolean()) return classBytes;

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
				if (alreadyPruned(node, prunes)) {
					if (ITEM_STACK_MIXIN.equals(className)) fabricTooltipsPruned = true;
					return classBytes;
				}
				ForbricLog.warn("[Forbric/GuestInjectorPruner] %s has no %s%s — fabric-api reshaped the mixin, leaving "
						+ "it untouched (%s)", className, prune.name(), prune.desc(), DRIFT.get(className));
				return classBytes;
			}
			if (!(prune.exact() ? isInjectorExactlyInto(found, prune.selectorPrefix()) : isInjectorInto(found, prune.selectorPrefix()))) {
				ForbricLog.warn("[Forbric/GuestInjectorPruner] %s.%s no longer injects into %s — fabric-api reshaped "
						+ "the mixin, leaving it untouched (%s)", className, prune.name(), prune.selectorPrefix(), DRIFT.get(className));
				return classBytes;
			}
			victims.add(found);
		}

		node.methods.removeAll(victims);
		pruned += victims.size();
		if (ITEM_STACK_MIXIN.equals(className)) fabricTooltipsPruned = true;
		// Removed, so never run: a confirmed finding for each where nothing does its job, naming what is not
		// covered. The log line below is not the report.
		String loss = LOSSES.get(className);
		for (MethodNode victim : loss == null ? List.<MethodNode>of() : victims) {
			net.forbric.kernel.mixin.MixinCompatibility.recordRemovedInjector(CONFIGS.get(className), className,
					victim.name, victim.desc, loss,
					List.of("kernel pruned " + victim.name + victim.desc + " from " + className,
							"target selector " + prunes.get(0).selectorPrefix(), "source=GuestInjectorPruner"));
		}
		ForbricLog.info("[Forbric/GuestInjectorPruner] pruned %d injector(s) from %s — %s; the other %d injector(s) "
				+ "apply as written", victims.size(), className, REASONS.get(className), countInjectors(node));

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

	/**
	 * Whether {@code m} carries an injector annotation whose {@code method} list is exactly {@code selector}, and a
	 * {@code @Share} parameter — the shape of fabric-item-api's five, which only move together.
	 */
	static boolean isInjectorExactlyInto(MethodNode m, String selector) {
		boolean shared = false;
		for (List<AnnotationNode> parameter : m.invisibleParameterAnnotations == null ? new List[0] : m.invisibleParameterAnnotations) {
			if (parameter != null) for (AnnotationNode a : parameter) shared |= "Lcom/llamalad7/mixinextras/sugar/Share;".equals(a.desc);
		}
		for (List<AnnotationNode> parameter : m.visibleParameterAnnotations == null ? new List[0] : m.visibleParameterAnnotations) {
			if (parameter != null) for (AnnotationNode a : parameter) shared |= "Lcom/llamalad7/mixinextras/sugar/Share;".equals(a.desc);
		}
		if (!shared) return false;
		for (AnnotationNode a : allAnnotations(m)) {
			if (!INJECTOR_DESCS.contains(a.desc) || a.values == null) continue;
			for (int i = 0; i + 1 < a.values.size(); i += 2) {
				if (!"method".equals(a.values.get(i))) continue;
				Object v = a.values.get(i + 1);
				if (v instanceof List<?> list) return list.size() == 1 && selector.equals(list.get(0));
				return selector.equals(v);
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
