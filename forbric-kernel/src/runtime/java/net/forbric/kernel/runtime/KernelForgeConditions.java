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

package net.forbric.kernel.runtime;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraftforge.common.crafting.conditions.ICondition;
import net.minecraftforge.registries.ForgeRegistries;

import net.forbric.kernel.util.ForbricLog;

/**
 * The MinecraftForge half of the same wall {@link KernelNeoConditions} holds up on the NeoForge side.
 *
 * <h2>Why there are three evaluators and this is the third</h2>
 *
 * <p>The merged {@code ResourceManagerRegistryLoadTask} carries BOTH ecosystems' patches at once, which javap on
 * the staged base shows plainly: {@code load} calls
 * {@code net/minecraftforge/common/crafting/conditions/ConditionCodec.wrap} at offset 15, while its own
 * {@code lambda$load$1} builds NeoForge's {@code ConditionalOps}. {@code LootPool} names the same MinecraftForge
 * class. So MinecraftForge's condition evaluator is live, global, and strict — over every datapack-registry
 * element, whichever ecosystem shipped the file, and (since ForgeLootPoolConditionsInjector, see
 * {@link #poolElementCodec}) over every loot pool.
 *
 * <p>Its failure path is the one already paid for once on the NeoForge side. {@code OptionalConditionalDecoder}
 * parses the {@code forge:condition} value through {@code ICondition.CODEC}; a registry dispatch that cannot
 * resolve the type returns {@code DataResult.error}, which becomes "Failed to load registries due to errors",
 * which is a world that does not open. The NeoForge half of this was fixed when waystones demonstrated it; this
 * is the mirror that had not been hit yet by the mods tested so far.
 *
 * <h2>Not SAFE_CODEC</h2>
 *
 * <p>MinecraftForge already ships something that looks like the answer and is not.
 * {@code ICondition.SAFE_CODEC} is {@code CODEC.orElse(FalseCondition.INSTANCE)} — verified in {@code <clinit>}
 * at offsets 24-35 — so a condition it cannot parse evaluates to FALSE and the element is DROPPED. That turns a
 * loud failure into content silently missing, which is worse than the crash it replaces and is the exact shape
 * this project keeps paying for.
 *
 * <p>A condition type MinecraftForge does not own is not MinecraftForge's to judge. It decodes to a condition
 * that does not veto, the element loads, and the ecosystem that owns the id decides. Failing the parse loses the
 * element, the registry and the world; ignoring one condition's opinion loses one condition's opinion.
 */
public final class KernelForgeConditions {

	/** Decoded in place of a condition whose type belongs to another ecosystem's registry. */
	private static final ICondition FOREIGN = new ICondition() {
		@Override
		public boolean test(ICondition.IContext context, DynamicOps<?> ops) {
			return true;
		}

		@Override
		public MapCodec<? extends ICondition> codec() {
			// Never registered, so it can be decoded and never re-encoded. encode() below refuses first, with a
			// message that says which of those two happened.
			return MapCodec.unit(this);
		}

		@Override
		public String toString() {
			return "forbric:foreign-condition";
		}
	};

	private static final Set<String> REPORTED = Collections.newSetFromMap(new ConcurrentHashMap<>());

	/** {@code -Dforbric.forgeConditions=off}: decode strictly, exactly as the carrier's own codec would. Read per decode. */
	public static final String PROPERTY = "forbric.forgeConditions";

	/** Whether a condition type id is registered; substitutable so a unit test never initialises the registry. */
	private static volatile java.util.function.Predicate<String> known = id -> {
		// An id that does not even parse is "known": the strict codec then fails it exactly as before.
		Identifier parsed = Identifier.tryParse(id);
		return parsed == null || ForgeRegistries.CONDITION_SERIALIZERS.get().containsKey(parsed);
	};

	private KernelForgeConditions() {
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** Test seam: the registry lookup, replaced. */
	static void bindKnownTypesForTest(java.util.function.Predicate<String> knownTypes) {
		known = knownTypes;
	}

	/**
	 * Wraps MinecraftForge's own {@code ICondition} codec, which is what {@code ICondition.<clinit>} hands over.
	 *
	 * <p>One inserted instruction, stack-neutral: the dispatch codec goes in, the lenient one comes out, and the
	 * existing {@code PUTSTATIC} stores it. {@code OPTIONAL_FEILD_CODEC} and {@code SAFE_CODEC} are both derived
	 * from {@code CODEC} later in the same {@code <clinit>} (offsets 21 and 35), so wrapping at the store covers
	 * all three readers without naming any of them.
	 */
	public static Codec<ICondition> lenient(Codec<ICondition> strict) {
		return new Codec<>() {
			@Override
			public <T> DataResult<Pair<ICondition, T>> decode(DynamicOps<T> ops, T input) {
				if (!enabled()) return strict.decode(ops, input);
				String foreign = foreignType(ops, input);
				if (foreign != null) {
					report(foreign);
					return DataResult.success(Pair.of(FOREIGN, ops.empty()));
				}
				return strict.decode(ops, input);
			}

			@Override
			public <T> DataResult<T> encode(ICondition value, DynamicOps<T> ops, T prefix) {
				if (value == FOREIGN) {
					return DataResult.error(() -> "a resource condition belonging to another ecosystem was read "
							+ "and cannot be written back");
				}
				return strict.encode(value, ops, prefix);
			}

			@Override
			public String toString() {
				return "Forbric(" + strict + ")";
			}
		};
	}

	/**
	 * The {@code type} of this condition when it names something MinecraftForge's registry does not have, else
	 * null.
	 *
	 * <p>Anything unreadable returns null, which hands the input back to the strict codec: a malformed condition
	 * has to keep producing MinecraftForge's own error, or this leniency would swallow genuinely broken data.
	 * The registry itself is consulted rather than an error MESSAGE being pattern-matched — a message is
	 * upstream's to reword, and a leniency that silently stops applying is a shape with a price tag on it here.
	 */
	private static <T> String foreignType(DynamicOps<T> ops, T input) {
		try {
			Optional<Map<T, T>> map = ops.getMapValues(input)
					.map(stream -> stream.collect(java.util.stream.Collectors.toMap(Pair::getFirst,
							Pair::getSecond, (a, b) -> b)))
					.result();
			if (map.isEmpty()) return null;
			T type = null;
			for (Map.Entry<T, T> entry : map.get().entrySet()) {
				if (ops.getStringValue(entry.getKey()).result().filter("type"::equals).isPresent()) {
					type = entry.getValue();
				}
			}
			if (type == null) return null;
			Optional<String> name = ops.getStringValue(type).result();
			if (name.isEmpty()) return null;
			return known.test(name.get()) ? null : name.get();
		} catch (Throwable t) {
			// A judgement that cannot be made hands the input to the strict codec — but silently is how a leniency
			// stops applying without anyone noticing, so the first failure is named.
			if (!judgementFailureReported) {
				judgementFailureReported = true;
				ForbricLog.warn("[Forbric/Conditions] could not judge a resource condition's type against "
						+ "MinecraftForge's registry — the strict codec decides this one and every later one", t);
			}
			return null;
		}
	}

	private static volatile boolean judgementFailureReported;

	/** Launch-time switch for {@link #poolElementCodec}; the pool list codec that asks is built once. */
	public static final String POOL_PROPERTY = "forbric.forgePoolConditions";

	/**
	 * The element codec NeoForge's pool list codec ({@code CommonHooks.lootPoolsCodec}) decodes each loot pool with:
	 * MinecraftForge's {@code LootPool.CONDITIONAL_CODEC}, which judges a pool-level {@code forge:condition} and puts an
	 * empty pool in place of one whose condition is false — what MinecraftForge's own {@code LootTable} reads pools
	 * with. NeoForge's wrapper around it still judges {@code neoforge:conditions} first (ForgeLootPoolConditionsInjector).
	 */
	public static Codec<LootPool> poolElementCodec(Codec<LootPool> neoforge) {
		if ("off".equalsIgnoreCase(System.getProperty(POOL_PROPERTY, "on"))) return neoforge;
		return LootPool.CONDITIONAL_CODEC;
	}

	/** {@code -Dforbric.forgeConditionContext=off}: the Forge event answers {@code EMPTY} instead of adapting NeoForge's. */
	public static final String CONTEXT_PROPERTY = "forbric.forgeConditionContext";
	private static volatile boolean contextFailureReported;

	/**
	 * The condition context MinecraftForge's {@code AddReloadListenerEvent.getConditionContext()} hands a listener.
	 *
	 * <p>The carrier compiles that accessor as {@code ReloadableServerResources.getConditionContext()} returning
	 * FORGE's {@code ICondition.IContext}; the merged class declares only the NeoForge-typed one, so the call is a
	 * {@code NoSuchMethodError} the moment a Forge data loader asks for its context. The transformer redirects that
	 * one invocation here (receiver in, context out, same stack) and this adapts NeoForge's live context over
	 * Forge's interface. {@code getTag} is a generic method, so the adapter is an anonymous class, not a lambda.
	 * Forge's {@code wrap(ops)} default and {@code TAGS_INVALID} semantics are untouched. Any failure — including a
	 * null NeoForge context — answers {@code EMPTY}, which is what a Forge listener gets on genuine Forge before
	 * the context exists.
	 */
	public static ICondition.IContext contextOf(net.minecraft.server.ReloadableServerResources resources) {
		if ("off".equalsIgnoreCase(System.getProperty(CONTEXT_PROPERTY, "on"))) return ICondition.IContext.EMPTY;
		try {
			net.neoforged.neoforge.common.conditions.ICondition.IContext neo = resources.getConditionContext();
			if (neo == null) return ICondition.IContext.EMPTY;
			return new ICondition.IContext() {
				@Override
				public <T> java.util.Collection<net.minecraft.core.Holder<T>> getTag(net.minecraft.tags.TagKey<T> key) {
					return neo.getTag(key);
				}
			};
		} catch (Throwable t) {
			if (!contextFailureReported) {
				contextFailureReported = true;
				ForbricLog.warn("[Forbric/Conditions] could not adapt NeoForge's condition context for MinecraftForge's "
						+ "AddReloadListenerEvent — Forge listeners get an EMPTY context", t);
			}
			return ICondition.IContext.EMPTY;
		}
	}

	private static void report(String type) {
		if (!REPORTED.add(type)) return;
		ForbricLog.warn("[Forbric/Conditions] resource condition '%s' is not in MinecraftForge's condition "
				+ "registry, so its evaluator — which the merged base runs over every datapack-registry element "
				+ "and every loot pool — could not judge it and used to fail the whole registry load with it. It "
				+ "is being ignored here instead; the ecosystem that owns that id decides. %d distinct "
				+ "condition(s) so far", type, REPORTED.size());
	}
}
