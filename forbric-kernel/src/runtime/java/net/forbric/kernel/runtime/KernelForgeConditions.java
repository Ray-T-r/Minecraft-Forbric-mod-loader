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
import java.util.concurrent.atomic.AtomicInteger;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Decoder;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraftforge.common.crafting.conditions.ConditionCodec;
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
	 *
	 * <p>Two things keep that judgement MinecraftForge's and keep it single. The codec is registered as one that judges
	 * {@code forge:condition} itself ({@link #judgesForgeConditionItself}), so ConditionalOps' funnel
	 * ({@link #alsoAskForge}) stands aside for the NeoForge wrapper built around it: asked there, a false condition
	 * dropped the pool from the list instead of leaving MinecraftForge's empty pool in its place, and every pool was
	 * judged twice. And a pool whose NeoForge conditions NeoForge judges has had its verdict from that wrapper, which
	 * stands ({@link #judgedByNeoForge}): its {@code forge:condition} is not asked a second, possibly contrary time.
	 */
	public static Codec<LootPool> poolElementCodec(Codec<LootPool> neoforge) {
		if ("off".equalsIgnoreCase(System.getProperty(POOL_PROPERTY, "on"))) return neoforge;
		return inPlace(LootPool.CONDITIONAL_CODEC, LootPool.CODEC,
				net.neoforged.neoforge.common.conditions.ConditionalOps.DEFAULT_CONDITIONS_KEY, NEOFORGE_JUDGES);
	}

	/**
	 * An element codec that judges {@code forge:condition} itself, the way MinecraftForge does, inside a NeoForge
	 * conditional wrapper: {@code forge} (MinecraftForge's own conditional decoder) for an element NeoForge has not
	 * judged, {@code plain} for one whose NeoForge conditions NeoForge judged on the way in. Encodes as {@code forge}
	 * does. Registered so the funnel never asks again around it. {@link #poolElementCodec}'s body; the tests' seam.
	 */
	static <A> Codec<A> inPlace(Codec<A> forge, Codec<A> plain, String neoKey,
			java.util.function.BiPredicate<DynamicOps<?>, Object> neoForgeJudges) {
		return judgesForgeConditionItself(new Codec<A>() {
			@Override
			public <T> DataResult<Pair<A, T>> decode(DynamicOps<T> ops, T input) {
				return (judgedByNeoForge(ops, input, neoKey, neoForgeJudges) ? plain : forge).decode(ops, input);
			}

			@Override
			public <T> DataResult<T> encode(A value, DynamicOps<T> ops, T prefix) {
				return forge.encode(value, ops, prefix);
			}

			@Override
			public String toString() {
				return "Forbric/InPlace(" + forge + ")";
			}
		});
	}

	/** In game: NeoForge's own condition list codec reads the dialect, and meets no type NeoForge's registry lacks. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static final java.util.function.BiPredicate<DynamicOps<?>, Object> NEOFORGE_JUDGES =
			(ops, conditions) -> KernelNeoConditions.judgesItsOwnDialect((DynamicOps) ops, conditions);

	/** Codecs that judge {@code forge:condition} themselves, by identity: the funnel never asks again around them. */
	private static final Set<Object> JUDGES_ITSELF =
			Collections.synchronizedSet(Collections.newSetFromMap(new java.util.IdentityHashMap<>()));

	/** Registers a codec built on MinecraftForge's own conditional decoder; the funnel stands aside around it. */
	public static <C> C judgesForgeConditionItself(C codec) {
		JUDGES_ITSELF.add(codec);
		return codec;
	}

	/**
	 * Whether NeoForge judges this element: it carries the conditions of the NeoForge decoder reading it ({@code neoKey},
	 * the key that decoder judges -- {@code neoforge:conditions} for every factory the game calls) and NeoForge can
	 * judge every condition named there ({@code neoForgeJudges}, in game {@link KernelNeoConditions#judgesItsOwnDialect}).
	 *
	 * <p>Who judges an element written in both dialects, from the two platforms' own rules: genuine NeoForge reads
	 * only {@code neoforge:conditions}, genuine MinecraftForge only {@code forge:condition}, and a file carrying both
	 * says the same thing twice so that either one alone decides it. The loaders reading data here are NeoForge's, and
	 * MinecraftForge's own context is {@code EMPTY} on every path but recipes -- so where NeoForge can judge its own
	 * dialect, its verdict stands and MinecraftForge is not asked: a file written for NeoForge is never dropped by a
	 * {@code forge:condition} answered over an empty context, or answered twice. Where it cannot -- a condition
	 * type only MinecraftForge's registry has, which NeoForge's leniency lets through because the ecosystem owning the
	 * id decides -- MinecraftForge decides, so a Forge mod's own condition is not silently ignored because it also
	 * wrote NeoForge's spelling of it. An element without NeoForge's dialect is MinecraftForge's alone, as before.
	 */
	static <T> boolean judgedByNeoForge(DynamicOps<T> ops, T input, String neoKey,
			java.util.function.BiPredicate<DynamicOps<?>, Object> neoForgeJudges) {
		if (neoKey == null) return false;
		Optional<T> dialect = ops.get(input, neoKey).result();
		return dialect.isPresent() && neoForgeJudges.test(ops, dialect.get());
	}

	/** {@code -Dforbric.forgeConditionContext=off}: the Forge event answers {@code EMPTY} instead of adapting NeoForge's. */
	public static final String CONTEXT_PROPERTY = "forbric.forgeConditionContext";
	private static volatile boolean contextFailureReported;

	/**
	 * The condition context MinecraftForge's {@code AddReloadListenerEvent.getConditionContext()} hands a listener.
	 *
	 * <p>The carrier compiles that accessor as {@code ReloadableServerResources.getConditionContext()} returning
	 * FORGE's {@code ICondition.IContext}. The transformer redirects that invocation to the active NeoForge
	 * context here (receiver in, context out, same stack) and this adapts NeoForge's live context over
	 * Forge's interface. {@code getTag} is a generic method, so the adapter is an anonymous class, not a lambda.
	 * Forge's {@code wrap(ops)} default and {@code TAGS_INVALID} semantics are untouched. Any failure — including a
	 * null NeoForge context — answers {@code EMPTY}, which is what a Forge listener gets on genuine Forge before
	 * the context exists.
	 */
	public static ICondition.IContext contextOf(net.minecraft.server.ReloadableServerResources resources) {
		if ("off".equalsIgnoreCase(System.getProperty(CONTEXT_PROPERTY, "on"))) return ICondition.IContext.EMPTY;
		try {
			net.neoforged.neoforge.common.conditions.ICondition.IContext neo = net.forbric.api.VirtualGetters.get(net.minecraft.server.ReloadableServerResources.class, "getConditionContext", net.neoforged.neoforge.common.conditions.ICondition.IContext.class, resources);
			if (neo == null) return ICondition.IContext.EMPTY;
			return forgeViewOf(neo);
		} catch (Throwable t) {
			if (!contextFailureReported) {
				contextFailureReported = true;
				ForbricLog.warn("[Forbric/Conditions] could not adapt NeoForge's condition context for MinecraftForge's "
						+ "AddReloadListenerEvent — Forge listeners get an EMPTY context", t);
			}
			return ICondition.IContext.EMPTY;
		}
	}

	/** NeoForge's live condition context seen through MinecraftForge's interface: a tag reads what NeoForge's reads. */
	static ICondition.IContext forgeViewOf(net.neoforged.neoforge.common.conditions.ICondition.IContext neo) {
		return new ICondition.IContext() {
			@Override
			public <T> java.util.Collection<net.minecraft.core.Holder<T>> getTag(net.minecraft.tags.TagKey<T> key) {
				return neo.getTag(key);
			}

			@Override
			public String toString() {
				return "Forbric/MinecraftForge(" + neo + ")";
			}
		};
	}

	private static volatile boolean recipeContextAnnounced;

	/**
	 * Hands MinecraftForge's condition evaluator the context genuine MinecraftForge gives the decode this ops is for.
	 *
	 * <p>Genuine MinecraftForge builds {@code ConditionContext(pendingTags)} in {@code ReloadableServerResources} and
	 * hands it to {@code RecipeManager}, whose {@code prepare} wraps its serialization ops in it, so a recipe's
	 * {@code forge:tag_empty} reads the tags this reload is loading. The merge kept NeoForge's {@code prepare}, which
	 * builds NeoForge's {@code ConditionalOps} over the same ops with NeoForge's context, and left MinecraftForge's
	 * context field in the class with nothing reading it -- so MinecraftForge's evaluator saw {@code EMPTY} and every
	 * tag was empty: a recipe guarded by {@code forge:not(forge:tag_empty ...)} was dropped while its tag had entries.
	 *
	 * <p>The transformer calls this right before NeoForge's {@code ConditionalOps} is built, with the ops and NeoForge's
	 * context on the stack and the class's own MinecraftForge context (set only by MinecraftForge's constructor). The
	 * context is that field when something set it, else NeoForge's live context seen through MinecraftForge's interface
	 * ({@link #forgeViewOf}), which reads the same pending tags {@code ConditionContext(pendingTags)} would -- the
	 * context {@link #contextOf} already gives MinecraftForge's reload event for the same reload. The ops is changed in
	 * place, the way {@code IContext.wrap} changes it. Only that decode gets it: the datapack-registry loader, the loot
	 * tables and the advancements keep {@code EMPTY}, as on genuine MinecraftForge (its advancement manager's
	 * constructor takes the context and never uses it). {@code -Dforbric.forgeConditionContext=off} leaves it EMPTY.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static void giveMinecraftForgeItsContext(net.minecraft.resources.RegistryOps ops,
			net.neoforged.neoforge.common.conditions.ICondition.IContext neo, ICondition.IContext own) {
		if (ops == null || "off".equalsIgnoreCase(System.getProperty(CONTEXT_PROPERTY, "on"))) return;
		try {
			ICondition.IContext context = own != null ? own
					: neo == null || neo == net.neoforged.neoforge.common.conditions.ICondition.IContext.EMPTY
							? ICondition.IContext.EMPTY : forgeViewOf(neo);
			if (context == ICondition.IContext.EMPTY) return;
			context.wrap(ops);
			if (!recipeContextAnnounced) {
				recipeContextAnnounced = true;
				ForbricLog.info("[Forbric/Conditions] MinecraftForge's recipe conditions read the tags this reload loads "
						+ "again: the merged RecipeManager.prepare is NeoForge's, which never handed MinecraftForge its "
						+ "condition context (%s)", own != null ? "the manager's own" : "NeoForge's live one, seen through MinecraftForge's interface");
			}
		} catch (Throwable t) {
			if (!contextFailureReported) {
				contextFailureReported = true;
				ForbricLog.warn("[Forbric/Conditions] could not hand MinecraftForge its recipe condition context — its tag "
						+ "conditions read every tag as empty", t);
			}
		}
	}

	/** {@code -Dforbric.forgeConditionFunnel=off}: ConditionalOps decodes without asking MinecraftForge. Read per decode. */
	public static final String FUNNEL_PROPERTY = "forbric.forgeConditionFunnel";
	private static final String KEY = "forge:condition";
	/** The other half of {@code ForgeHooks.readConditional}: variants, the first whose condition holds is the file. */
	private static final String VARIANTS_KEY = "forge:conditional";
	private static final AtomicInteger JUDGED = new AtomicInteger();
	private static final AtomicInteger REJECTED = new AtomicInteger();
	private static volatile boolean announced;

	/** How many funnel decodes this thread is inside; MinecraftForge judges only the outermost. */
	private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

	/** The element, untouched: what MinecraftForge's own decoder reads once its condition lets the element through. */
	private static final Decoder<Boolean> LET_THROUGH = new Decoder<>() {
		@Override
		public <T> DataResult<Pair<Boolean, T>> decode(DynamicOps<T> ops, T input) {
			return DataResult.success(Pair.of(Boolean.TRUE, input));
		}
	};

	/**
	 * Wraps {@code ConditionalOps}' one codec factory so MinecraftForge's dialect is judged where MinecraftForge judges it.
	 *
	 * <p>MinecraftForge judges its key in two of its own patches, and the merge kept NeoForge's side of both:
	 * {@code ResourceManagerRegistryLoadTask.load} wraps every datapack-registry element decoder in
	 * {@code ConditionCodec.wrap}, and {@code SimpleJsonResourceReloadListener.scanDirectory} hands every data file to
	 * {@code ForgeHooks.readConditional}. The merged bodies are NeoForge's; MinecraftForge's lambdas are left in the
	 * class with nothing calling them. So a Forge mod's conditional biome modifier, recipe or loot table always loaded.
	 * Both NeoForge bodies decode through this one factory, which is where the judgement is asked again.
	 *
	 * <p>Only where MinecraftForge would have asked, and only once:
	 * <ul>
	 *   <li>at the outermost decode -- the data file or the registry element. Both of MinecraftForge's readers look at
	 *       the top of the file; the conditional decodes NeoForge nests inside an element (its pool list, its
	 *       element-conditional lists) are not places MinecraftForge reads {@code forge:condition}. A loot pool's is
	 *       MinecraftForge's own codec's to judge, in place ({@link #poolElementCodec}).</li>
	 *   <li>never around a codec that judges it itself ({@link #judgesForgeConditionItself}): {@code inner} is the
	 *       factory's own argument, the codec NeoForge's conditional wraps.</li>
	 *   <li>never over an element NeoForge judges ({@link #judgedByNeoForge}): {@code neoKey} is the factory's other
	 *       argument, the key NeoForge's decoder judges.</li>
	 * </ul>
	 *
	 * <p>MinecraftForge's own code judges it, not a reimplementation. A {@code forge:condition} goes through
	 * {@code ConditionCodec.wrap}: one that does not hold skips the element exactly as NeoForge's own skip does (an
	 * empty result), one MinecraftForge cannot parse fails the element (on a data file MinecraftForge's reader would
	 * skip it silently through {@code SAFE_CODEC}; here the same file is skipped with the parse error logged), and a
	 * type another ecosystem owns is let through by {@link #lenient} -- the condition codec this decoder reads with.
	 * A {@code forge:conditional} array -- the other half of {@code ForgeHooks.readConditional}, and what
	 * MinecraftForge's own {@code ConditionalAdvancement} writes -- goes through {@code readConditional} itself: the
	 * first variant whose condition holds is what NeoForge decodes, and none holding skips the element. Genuine
	 * MinecraftForge reads that array only in data files; the registry loader would fail an element made of one, so
	 * reading it there too only turns that failure into the data file's answer.
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	public static Codec alsoAskForge(Codec conditional, Codec inner, String neoKey) {
		if (inner != null && JUDGES_ITSELF.contains(inner)) return conditional;
		return alsoAsk(conditional, neoKey, NEOFORGE_JUDGES, ConditionCodec.wrap(LET_THROUGH),
				(ops, json) -> net.minecraftforge.common.ForgeHooks.readConditional((DynamicOps) ops, json));
	}

	/**
	 * {@link #alsoAskForge} with its three judges given: {@code neoForgeJudges} for whether NeoForge judges an element's
	 * own NeoForge conditions ({@link #judgedByNeoForge}), {@code forge} for {@code forge:condition} (empty means it does
	 * not hold), {@code variants} for a {@code forge:conditional} element (null means no variant holds). The tests' seam.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	static Codec alsoAsk(Codec conditional, String neoKey, java.util.function.BiPredicate<DynamicOps<?>, Object> neoForgeJudges,
			Decoder<Optional<Boolean>> forge,
			java.util.function.BiFunction<DynamicOps<?>, com.google.gson.JsonElement, com.google.gson.JsonElement> variants) {
		return new Codec<Object>() {
			@Override
			public <T> DataResult<Pair<Object, T>> decode(DynamicOps<T> ops, T input) {
				int[] depth = DEPTH.get();
				boolean outermost = depth[0]++ == 0;
				try {
					if (outermost && !"off".equalsIgnoreCase(System.getProperty(FUNNEL_PROPERTY, "on"))
							&& !judgedByNeoForge(ops, input, neoKey, neoForgeJudges)) {
						if (input instanceof com.google.gson.JsonObject json && json.has(VARIANTS_KEY)) {
							com.google.gson.JsonElement chosen;
							try {
								chosen = variants.apply(ops, json);
							} catch (RuntimeException unreadable) {
								return DataResult.error(() -> VARIANTS_KEY + ": " + unreadable.getMessage());
							}
							judged(VARIANTS_KEY, chosen != null);
							if (chosen == null) return DataResult.success(Pair.of((Object) Optional.empty(), ops.empty()));
							return ((Codec<Object>) conditional).decode(ops, (T) chosen);
						}
						if (ops.get(input, KEY).result().isPresent()) {
							DataResult<Pair<Optional<Boolean>, T>> verdict = forge.decode(ops, input);
							var failed = verdict.error();
							if (failed.isPresent()) return DataResult.error(failed.get().messageSupplier());
							boolean keep = verdict.result().orElseThrow().getFirst().isPresent();
							judged(KEY, keep);
							if (!keep) return DataResult.success(Pair.of((Object) Optional.empty(), ops.empty()));
						}
					}
					return ((Codec<Object>) conditional).decode(ops, input);
				} finally {
					depth[0]--;
				}
			}

			@Override
			public <T> DataResult<T> encode(Object value, DynamicOps<T> ops, T prefix) {
				return ((Codec<Object>) conditional).encode(value, ops, prefix);
			}

			@Override
			public String toString() {
				return "Forbric/MinecraftForge(" + conditional + ")";
			}
		};
	}

	private static void judged(String key, boolean keep) {
		int judged = JUDGED.incrementAndGet();
		if (!announced) {
			announced = true;
			ForbricLog.info("[Forbric/Conditions] MinecraftForge's %s is judged again, by its own ConditionCodec, where "
					+ "every conditional decode funnels (ConditionalOps): the merged datapack-registry and data-file "
					+ "loaders are NeoForge's, which never ask it", KEY);
		}
		if (!keep) {
			ForbricLog.info("[Forbric/Conditions] %s said no to a data file — %d of %d element(s) carrying MinecraftForge's "
					+ "dialect have been rejected", key, REJECTED.incrementAndGet(), judged);
		}
	}

	/** Test seam: how many elements carrying the key were judged, and how many of them were rejected. */
	static int[] funnelCountsForTest() {
		return new int[] {JUDGED.get(), REJECTED.get()};
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
