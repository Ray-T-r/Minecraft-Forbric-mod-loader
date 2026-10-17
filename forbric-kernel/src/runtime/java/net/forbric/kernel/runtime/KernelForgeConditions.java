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
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Decoder;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraftforge.common.crafting.conditions.ConditionCodec;
import net.minecraftforge.common.crafting.conditions.ICondition;
import net.minecraftforge.registries.ForgeRegistries;

import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;

/**
 * MinecraftForge's resource conditions on the merged base: judged where MinecraftForge judges them, with what it judges
 * them with -- the MinecraftForge half of the wall {@link KernelNeoConditions} holds up on the NeoForge side.
 *
 * <h2>Where MinecraftForge reads {@code forge:condition}</h2>
 *
 * <p>In two of its own patches, and the merge kept NeoForge's body of both: {@code SimpleJsonResourceReloadListener}'s
 * directory scan hands every data file to {@code ForgeHooks.readConditional}, and {@code ResourceManagerRegistryLoadTask}
 * wraps every datapack-registry element decoder in {@code ConditionCodec.wrap}. MinecraftForge's lambdas are left in
 * those classes with nothing calling them. The merged bodies decode through NeoForge's {@code ConditionalOps}, whose one
 * codec factory is where the question is asked again ({@link #alsoAskForge}) -- but that factory also serves decodes
 * MinecraftForge never judged (pack.mcmeta overlay entries, atlas sprite sources, the network registry path, a mod's own
 * {@code ConditionalOps} use). So the transformer marks the two readers -- {@link #readsDataFiles} in the scan,
 * {@link #nextLoadIsMinecraftForgesRegistryElement} with {@link #readerFor} in the registry loader -- and the funnel
 * judges only inside them, at the reader's own decode, with the reader's own code: {@code readConditional} for a data file, {@code ConditionCodec.wrap} for a registry element. A loot pool's
 * {@code forge:condition} is MinecraftForge's own pool codec's to judge, in place ({@link #poolElementCodec}).
 *
 * <h2>A condition type MinecraftForge's registry does not have</h2>
 *
 * <p>Genuine MinecraftForge reads it as {@code FalseCondition} in a data file ({@code ICondition.SAFE_CODEC} is
 * {@code CODEC.orElse(FalseCondition.INSTANCE)}, verified in {@code <clinit>}) and fails the parse of a registry element
 * -- which there is "Failed to load registries due to errors", a world that does not open. Which of those applies here
 * depends on who owns the type ({@link #lenient}):
 * <ul>
 *   <li>another ecosystem on this instance registers it (NeoForge's condition registry, fabric-api's resource-condition
 *       registry): a multi-loader file's other dialect, not MinecraftForge's to judge. It decodes as a condition that
 *       does not veto, and the ecosystem that owns the id decides;</li>
 *   <li>nobody does -- typically the mod that registers it is not installed: MinecraftForge's own answer, so a data file
 *       or a {@code forge:conditional} variant guarded by it is skipped, as on MinecraftForge;</li>
 *   <li>except on a datapack-registry element, where MinecraftForge's answer would lose the registry and the world. A
 *       registry element that fails like that on MinecraftForge cannot have been tested there; it is another family's
 *       build's file, whose loader never reads {@code forge:condition}. It loads, and the log names the type.</li>
 * </ul>
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
	private static final Set<String> UNOWNED_REPORTED = Collections.newSetFromMap(new ConcurrentHashMap<>());

	/** {@code -Dforbric.forgeConditions=off}: decode strictly, exactly as the carrier's own codec would. Read per decode. */
	public static final String PROPERTY = "forbric.forgeConditions";

	/** Whether a condition type id is registered; substitutable so a unit test never initialises the registry. */
	private static volatile Predicate<String> known = id -> {
		// An id that does not even parse is "known": the strict codec then fails it exactly as before.
		Identifier parsed = Identifier.tryParse(id);
		return parsed == null || ForgeRegistries.CONDITION_SERIALIZERS.get().containsKey(parsed);
	};

	/**
	 * Whether another ecosystem on this instance registers a condition type id MinecraftForge's registry lacks:
	 * NeoForge's condition registry or fabric-api's resource-condition registry. Substitutable for the tests.
	 */
	private static volatile Predicate<String> ownedElsewhere =
			id -> KernelNeoConditions.registers(id) || KernelFabricConditions.registers(id);

	private KernelForgeConditions() {
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** Test seam: the registry lookup, replaced. */
	static void bindKnownTypesForTest(Predicate<String> knownTypes) {
		known = knownTypes;
	}

	/** Test seam: the other ecosystems' registries, replaced. */
	static void bindOwnedElsewhereForTest(Predicate<String> otherRegistries) {
		ownedElsewhere = otherRegistries;
	}

	/**
	 * Wraps MinecraftForge's own {@code ICondition} codec, which is what {@code ICondition.<clinit>} hands over.
	 *
	 * <p>One inserted instruction, stack-neutral: the dispatch codec goes in, the lenient one comes out, and the
	 * existing {@code PUTSTATIC} stores it. {@code OPTIONAL_FEILD_CODEC} and {@code SAFE_CODEC} are both derived
	 * from {@code CODEC} later in the same {@code <clinit>} (offsets 21 and 35), so wrapping at the store covers
	 * all three readers without naming any of them.
	 *
	 * <p>A type MinecraftForge's registry has is MinecraftForge's: strict. One it lacks is judged by who owns it (see the
	 * class comment): owned by another ecosystem here, it does not veto; owned by nobody, MinecraftForge's own answer
	 * stands -- an error, which {@code SAFE_CODEC} reads as false -- except inside the registry-element reader, where
	 * that answer would lose the world and the element loads instead.
	 */
	public static Codec<ICondition> lenient(Codec<ICondition> strict) {
		return new Codec<>() {
			@Override
			public <T> DataResult<Pair<ICondition, T>> decode(DynamicOps<T> ops, T input) {
				if (!enabled()) return strict.decode(ops, input);
				String unknown = unknownType(ops, input);
				if (unknown == null) return strict.decode(ops, input);
				if (ownedElsewhere(unknown)) {
					report(unknown);
					return DataResult.success(Pair.of(FOREIGN, ops.empty()));
				}
				if (READER.get() == Reader.REGISTRY_ELEMENT) {
					reportUnowned(unknown, true);
					return DataResult.success(Pair.of(FOREIGN, ops.empty()));
				}
				reportUnowned(unknown, false);
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
	private static <T> String unknownType(DynamicOps<T> ops, T input) {
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
			// stops applying without anyone noticing, so the failure is named.
			Caught.warn(t, "[Forbric/Conditions] could not judge a resource condition's type against "
					+ "MinecraftForge's registry — the strict codec decides this one");
			return null;
		}
	}

	/**
	 * Whether another ecosystem registers {@code type}. A lookup that fails keeps the answer this class gave before it
	 * asked at all -- not MinecraftForge's to judge -- and says so: failing closed over a lookup would drop data.
	 */
	private static boolean ownedElsewhere(String type) {
		try {
			return ownedElsewhere.test(type);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Conditions] could not ask the other ecosystems' condition registries who owns "
					+ "'%s' — a type MinecraftForge lacks is treated as theirs", type);
			return true;
		}
	}

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
	 * ({@link #alsoAskForge}) does not ask around the NeoForge wrapper built around it: asked there, a false condition
	 * dropped the pool from the list instead of leaving MinecraftForge's empty pool in its place, and every pool was
	 * judged twice. And a pool whose NeoForge conditions NeoForge judged has had its verdict from that wrapper, which
	 * stands: its {@code forge:condition} is not asked a second, possibly contrary time ({@link #inPlace}).
	 */
	public static Codec<LootPool> poolElementCodec(Codec<LootPool> neoforge) {
		if ("off".equalsIgnoreCase(System.getProperty(POOL_PROPERTY, "on"))) return neoforge;
		return inPlace(LootPool.CONDITIONAL_CODEC, LootPool.CODEC);
	}

	/**
	 * An element codec that judges {@code forge:condition} itself, the way MinecraftForge does, inside a NeoForge
	 * conditional wrapper: {@code forge} (MinecraftForge's own conditional decoder) for an element NeoForge has not
	 * judged, {@code plain} for one whose NeoForge conditions NeoForge judged on the way in. Encodes as {@code forge}
	 * does. Registered so the funnel never asks again around it. {@link #poolElementCodec}'s body; the tests' seam.
	 *
	 * <p>Which of the two is not something this codec can see for itself: NeoForge's {@code ConditionalDecoder} hands its
	 * inner codec the element with the conditions key filtered out (it rebuilds the map from the entries that are not
	 * that key), so {@code neoforge:conditions} is never in the input here. The NeoForge wrapper's own input still has it,
	 * so the verdict is taken there -- {@link #alsoAskForge} stands a {@link #tellsWhetherNeoForgeJudged} in front of a
	 * wrapper around a codec registered here -- and read here, for this codec only.
	 */
	@SuppressWarnings("unchecked")
	static <A> Codec<A> inPlace(Codec<A> forge, Codec<A> plain) {
		Codec<A>[] self = new Codec[1];
		self[0] = judgesForgeConditionItself(new Codec<A>() {
			@Override
			public <T> DataResult<Pair<A, T>> decode(DynamicOps<T> ops, T input) {
				Verdict verdict = NEOFORGE_VERDICT.get();
				boolean judged = verdict != null && verdict.inner() == self[0] && verdict.judged();
				return (judged ? plain : forge).decode(ops, input);
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
		return self[0];
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

	/** Whether NeoForge judged the element its wrapper around {@code inner} is decoding on this thread. */
	private record Verdict(Object inner, boolean judged) {
	}

	private static final ThreadLocal<Verdict> NEOFORGE_VERDICT = new ThreadLocal<>();

	/**
	 * What ConditionalOps' factory returns around a codec that judges {@code forge:condition} itself: the NeoForge
	 * wrapper, which MinecraftForge is never asked around, telling {@code inner} -- for the length of its decode --
	 * whether NeoForge judged the element ({@link #judgedByNeoForge} over the wrapper's own input, which still carries
	 * the key). The tests' seam for {@code neoForgeJudges}.
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	static Codec tellsWhetherNeoForgeJudged(Codec conditional, Object inner, String neoKey,
			java.util.function.BiPredicate<DynamicOps<?>, Object> neoForgeJudges) {
		return new Codec<Object>() {
			@Override
			public <T> DataResult<Pair<Object, T>> decode(DynamicOps<T> ops, T input) {
				Verdict outer = NEOFORGE_VERDICT.get();
				NEOFORGE_VERDICT.set(new Verdict(inner, judgedByNeoForge(ops, input, neoKey, neoForgeJudges)));
				try {
					return ((Codec<Object>) conditional).decode(ops, input);
				} finally {
					if (outer == null) NEOFORGE_VERDICT.remove();
					else NEOFORGE_VERDICT.set(outer);
				}
			}

			@Override
			public <T> DataResult<T> encode(Object value, DynamicOps<T> ops, T prefix) {
				return ((Codec<Object>) conditional).encode(value, ops, prefix);
			}

			@Override
			public String toString() {
				return "Forbric/NeoForgeVerdict(" + conditional + ")";
			}
		};
	}

	/**
	 * Whether NeoForge judges this element: it carries the conditions of the NeoForge decoder reading it ({@code neoKey},
	 * the key that decoder judges -- {@code neoforge:conditions} for every factory the game calls) and NeoForge can
	 * judge every condition named there ({@code neoForgeJudges}, in game {@link KernelNeoConditions#judgesItsOwnDialect}).
	 * Asked of the input a NeoForge wrapper is handed, never of what it hands its inner codec, which no longer has the key.
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
			Caught.warn(t, "[Forbric/Conditions] could not adapt NeoForge's condition context for MinecraftForge's "
					+ "AddReloadListenerEvent — Forge listeners get an EMPTY context");
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
			Caught.warn(t, "[Forbric/Conditions] could not hand MinecraftForge its recipe condition context — its tag "
					+ "conditions read every tag as empty");
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

	/** MinecraftForge's two readers of {@code forge:condition}, as the merged base runs them. */
	enum Reader {
		/** {@code SimpleJsonResourceReloadListener}'s directory scan: {@code ForgeHooks.readConditional} on every file. */
		DATA_FILE,
		/** {@code ResourceManagerRegistryLoadTask}: {@code ConditionCodec.wrap} around every datapack-registry element. */
		REGISTRY_ELEMENT
	}

	/** The reader this thread is decoding for; absent outside both, where MinecraftForge never read the key. */
	private static final ThreadLocal<Reader> READER = new ThreadLocal<>();

	/** How many funnel decodes this thread is inside since its reader began; MinecraftForge judges only the first. */
	private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

	/** The element, untouched: what MinecraftForge's own decoder reads once its condition lets the element through. */
	private static final Decoder<Boolean> LET_THROUGH = new Decoder<>() {
		@Override
		public <T> DataResult<Pair<Boolean, T>> decode(DynamicOps<T> ops, T input) {
			return DataResult.success(Pair.of(Boolean.TRUE, input));
		}
	};

	/**
	 * Marks the codec a {@code SimpleJsonResourceReloadListener} scan parses each data file with as MinecraftForge's
	 * data-file reader: every decode through it is one {@code ForgeHooks.readConditional} judged on MinecraftForge.
	 *
	 * <p>The transformer inserts it right after the scan's {@code ConditionalOps.createConditionalCodec} call, one
	 * instruction with nothing on the stack moved. Recipes, loot tables, predicates, advancements and every mod's own
	 * listener of that kind read their files through those scans -- on MinecraftForge, all through
	 * {@code readConditional}.
	 */
	@SuppressWarnings("rawtypes")
	public static Codec readsDataFiles(Codec codec) {
		return reader(Reader.DATA_FILE, codec);
	}

	/**
	 * Marks a codec as MinecraftForge's registry-element reader: every decode through it is one element
	 * {@code ResourceManagerRegistryLoadTask} decoded through {@code ConditionCodec.wrap} on MinecraftForge.
	 * {@link #readerFor}'s answer for the loads that reader makes.
	 */
	@SuppressWarnings("rawtypes")
	public static Codec readsRegistryElements(Codec codec) {
		return reader(Reader.REGISTRY_ELEMENT, codec);
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static Codec reader(Reader reader, Codec codec) {
		return new Codec<Object>() {
			@Override
			public <T> DataResult<Pair<Object, T>> decode(DynamicOps<T> ops, T input) {
				return reading(reader, () -> ((Codec<Object>) codec).decode(ops, input));
			}

			@Override
			public <T> DataResult<T> encode(Object value, DynamicOps<T> ops, T prefix) {
				return ((Codec<Object>) codec).encode(value, ops, prefix);
			}

			@Override
			public String toString() {
				return "Forbric/MinecraftForge" + (reader == Reader.DATA_FILE ? "DataFile(" : "RegistryElement(") + codec + ")";
			}
		};
	}

	/** The resource {@code ResourceManagerRegistryLoadTask} is about to hand {@code loadFromResource}, on this thread. */
	private static final ThreadLocal<Resource> NEXT_REGISTRY_ELEMENT = new ThreadLocal<>();

	/**
	 * Called by {@code ResourceManagerRegistryLoadTask} right before it calls {@code loadFromResource(..., resource)}:
	 * the load of that resource is one MinecraftForge's registry reader makes.
	 *
	 * <p>Both halves are needed because the decode happens inside {@code loadFromResource}, which the network registry
	 * path ({@code NetworkRegistryLoadTask}, where MinecraftForge never judged the key) and any mod call too: the call
	 * site knows who is reading, the method body builds the codec. The call itself stays where it is -- a mod's mixin
	 * anchored on it keeps its anchor. A load that throws before {@link #readerFor} leaves a resource no other load is
	 * handed; the next announcement replaces it.
	 */
	public static void nextLoadIsMinecraftForgesRegistryElement(Resource resource) {
		NEXT_REGISTRY_ELEMENT.set(resource);
	}

	/**
	 * Called by {@code loadFromResource} right after it builds its conditional codec, with the resource it is loading:
	 * {@link #readsRegistryElements} of that codec when this is the load {@link #nextLoadIsMinecraftForgesRegistryElement}
	 * announced, the codec unchanged otherwise.
	 */
	@SuppressWarnings("rawtypes")
	public static Codec readerFor(Codec conditional, Resource resource) {
		if (resource == null || NEXT_REGISTRY_ELEMENT.get() != resource) return conditional;
		NEXT_REGISTRY_ELEMENT.remove();
		return readsRegistryElements(conditional);
	}

	/** Runs {@code decode} as {@code reader}'s: its first funnel decode is the one MinecraftForge judges. */
	static <R> R reading(Reader reader, Supplier<R> decode) {
		Reader previous = READER.get();
		int[] depth = DEPTH.get();
		int previousDepth = depth[0];
		READER.set(reader);
		depth[0] = 0;
		try {
			return decode.get();
		} finally {
			depth[0] = previousDepth;
			if (previous == null) READER.remove();
			else READER.set(previous);
		}
	}

	/**
	 * Wraps {@code ConditionalOps}' one codec factory so MinecraftForge's dialect is judged where MinecraftForge judges it.
	 *
	 * <p>The factory serves every conditional decode NeoForge's code makes; MinecraftForge read {@code forge:condition}
	 * in two of them, and judges it here only there, only once, and with the reader's own code:
	 * <ul>
	 *   <li>inside one of its readers ({@link #readsDataFiles}, {@link #readsRegistryElements}), at that reader's first
	 *       decode -- the data file or the registry element. The conditional decodes NeoForge nests inside one (its
	 *       pool list, its element-conditional lists) and those outside both (a pack.mcmeta overlay entry, an atlas
	 *       sprite source, a client's network registry load) are not places MinecraftForge reads the key.</li>
	 *   <li>never around a codec that judges it itself ({@link #judgesForgeConditionItself}): {@code inner} is the
	 *       factory's own argument, the codec NeoForge's conditional wraps. That codec is told instead whether NeoForge
	 *       judged the element ({@link #tellsWhetherNeoForgeJudged}).</li>
	 *   <li>never over an element NeoForge judges ({@link #judgedByNeoForge}): {@code neoKey} is the factory's other
	 *       argument, the key NeoForge's decoder judges.</li>
	 * </ul>
	 *
	 * <p>A data file goes through {@code ForgeHooks.readConditional} itself, as MinecraftForge's scan hands it over: a
	 * {@code forge:conditional} array becomes its first variant whose condition holds, a {@code forge:condition} that
	 * does not hold skips the file exactly as NeoForge's own skip does (an empty result), and a condition
	 * {@code SAFE_CODEC} cannot read is one that does not hold. A registry element goes through
	 * {@code ConditionCodec.wrap}: the same skip, and a condition MinecraftForge cannot read fails the element, as it
	 * does there. A type another ecosystem owns is let through by {@link #lenient} -- the condition codec both readers
	 * read with.
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	public static Codec alsoAskForge(Codec conditional, Codec inner, String neoKey) {
		if (inner != null && JUDGES_ITSELF.contains(inner)) return tellsWhetherNeoForgeJudged(conditional, inner, neoKey, NEOFORGE_JUDGES);
		return alsoAsk(conditional, neoKey, NEOFORGE_JUDGES, ConditionCodec.wrap(LET_THROUGH),
				(ops, json) -> net.minecraftforge.common.ForgeHooks.readConditional((DynamicOps) ops, json));
	}

	/**
	 * {@link #alsoAskForge} with its three judges given: {@code neoForgeJudges} for whether NeoForge judges an element's
	 * own NeoForge conditions ({@link #judgedByNeoForge}), {@code registryElement} for a registry element's
	 * {@code forge:condition} (empty means it does not hold), {@code dataFile} for a data file (null means skip it,
	 * otherwise what to decode). The tests' seam.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	static Codec alsoAsk(Codec conditional, String neoKey, java.util.function.BiPredicate<DynamicOps<?>, Object> neoForgeJudges,
			Decoder<Optional<Boolean>> registryElement,
			java.util.function.BiFunction<DynamicOps<?>, com.google.gson.JsonElement, com.google.gson.JsonElement> dataFile) {
		return new Codec<Object>() {
			@Override
			public <T> DataResult<Pair<Object, T>> decode(DynamicOps<T> ops, T input) {
				int[] depth = DEPTH.get();
				boolean first = depth[0]++ == 0;
				try {
					Reader reader = READER.get();
					if (first && reader != null && !"off".equalsIgnoreCase(System.getProperty(FUNNEL_PROPERTY, "on"))
							&& !judgedByNeoForge(ops, input, neoKey, neoForgeJudges)) {
						if (reader == Reader.DATA_FILE) {
							if (input instanceof com.google.gson.JsonObject json && (json.has(VARIANTS_KEY) || json.has(KEY))) {
								String key = json.has(VARIANTS_KEY) ? VARIANTS_KEY : KEY;
								com.google.gson.JsonElement chosen;
								try {
									chosen = dataFile.apply(ops, json);
								} catch (RuntimeException unreadable) {
									Caught.expected(unreadable);
									return DataResult.error(() -> key + ": " + unreadable.getMessage());
								}
								judged(key, chosen != null);
								if (chosen == null) return DataResult.success(Pair.of((Object) Optional.empty(), ops.empty()));
								return ((Codec<Object>) conditional).decode(ops, (T) chosen);
							}
						} else if (ops.get(input, KEY).result().isPresent()) {
							DataResult<Pair<Optional<Boolean>, T>> verdict = registryElement.decode(ops, input);
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
			ForbricLog.info("[Forbric/Conditions] MinecraftForge's %s is judged again, by its own readers, inside the merged "
					+ "data-file and datapack-registry loaders (NeoForge's, which never ask it) where every conditional "
					+ "decode funnels (ConditionalOps)", KEY);
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
				+ "registry; another ecosystem on this instance registers it, so it is not MinecraftForge's to judge — "
				+ "MinecraftForge's evaluator ignores it and the ecosystem that owns that id decides. %d distinct "
				+ "condition(s) so far", type, REPORTED.size());
	}

	/** A type nobody here registers: MinecraftForge's own answer, except where that answer is the whole registry. */
	private static void reportUnowned(String type, boolean registryElement) {
		if (!UNOWNED_REPORTED.add(type + (registryElement ? "|registry" : "|file"))) return;
		if (registryElement) {
			ForbricLog.warn("[Forbric/Conditions] resource condition '%s' on a datapack-registry element is registered by "
					+ "no ecosystem on this instance; MinecraftForge's own registry loader would fail the whole registry "
					+ "load over it, so it is ignored and the element loads", type);
		} else {
			ForbricLog.info("[Forbric/Conditions] resource condition '%s' is registered by no ecosystem on this instance "
					+ "(the mod that owns it is not installed) — MinecraftForge reads it as a condition that does not "
					+ "hold, and skips what it guards", type);
		}
	}
}
