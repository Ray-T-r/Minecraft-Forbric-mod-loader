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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.transform.ForbricMergedBaseCompatTransformer;

/**
 * MinecraftForge's {@code forge:condition}, judged by its own code at ConditionalOps' funnel, over the staged
 * carriers: the real {@code ConditionCodec}, {@code NotCondition}, {@code TagEmptyCondition}, {@code ForgeHooks.readConditional}
 * and {@code LootPool}'s kind of in-place decoder on the MinecraftForge side; the real {@code ConditionalOps} -- defined
 * through the merged-base transformer, so carrying the funnel exactly as the game does -- and NeoForge's own condition
 * codecs on the other. The one thing substituted is each family's condition-type registry, which only a running game
 * builds ({@link ConditionRegistryStandIn}).
 */
@org.junit.jupiter.api.parallel.ResourceLock("system-properties")
class KernelForgeConditionsRealCodecTest {
	private static final String NOT_TIN_EMPTY = "{\"type\":\"forge:not\",\"value\":{\"type\":\"forge:tag_empty\",\"tag\":\"c:ingots/tin\"}}";
	private static final String RECIPE = "{\"forge:condition\":" + NOT_TIN_EMPTY + ",\"carrier\":7}";

	// --- (a) the context MinecraftForge's evaluator is given -------------------------------------------------------

	@Test
	void aRecipeGuardedByNotTagEmptyLoadsExactlyWhenItsTagHasEntries() throws Exception {
		try (Game game = new Game()) {
			for (int entries : new int[] {1, 0}) {
				Object neo = game.neoContext(Map.of("c:ingots/tin", entries));
				Object ops = game.registryOps();
				game.giveMinecraftForgeItsContext(ops, neo);                 // what the repaired RecipeManager.prepare does
				Optional<?> recipe = game.parse(game.dataFile(game.carrier()), game.conditionalOps(ops, neo), RECIPE);
				assertEquals(entries > 0 ? Optional.of(7) : Optional.empty(), recipe,
						"forge:not(forge:tag_empty c:ingots/tin) with " + entries + " entr(ies) in the tag");
			}
		}
	}

	@Test
	void withoutTheHandOffTheMergedRecipeManagerDropsItWhileTheTagHasEntries() throws Exception {
		// The defect, reproduced with the same real codecs: NeoForge's prepare builds ConditionalOps over ops that carry
		// no MinecraftForge context, so MinecraftForge reads EMPTY and every tag is empty. Also what the switch restores.
		try (Game game = new Game()) {
			Object neo = game.neoContext(Map.of("c:ingots/tin", 1));
			assertEquals(Optional.empty(), game.parse(game.dataFile(game.carrier()), game.conditionalOps(game.registryOps(), neo), RECIPE),
					"the merged prepare as NeoForge left it");
			System.setProperty("forbric.forgeConditionContext", "off");
			try {
				Object ops = game.registryOps();
				game.giveMinecraftForgeItsContext(ops, neo);
				assertEquals(Optional.empty(), game.parse(game.dataFile(game.carrier()), game.conditionalOps(ops, neo), RECIPE),
						"-Dforbric.forgeConditionContext=off leaves MinecraftForge EMPTY");
			} finally {
				System.clearProperty("forbric.forgeConditionContext");
			}
		}
	}

	@Test
	void theHandOffAnswersWhatGenuineMinecraftForgesConditionContextAnswers() throws Exception {
		// Genuine MinecraftForge: ConditionContext(pendingTags).wrap(registries.createSerializationContext(JsonOps)).
		try (Game game = new Game()) {
			for (int entries : new int[] {1, 0}) {
				Object genuine = game.registryOps();
				game.call(game.forgeConditionContext(Map.of("c:ingots/tin", entries)), "wrap", genuine);
				Object ours = game.registryOps();
				Object neo = game.neoContext(Map.of("c:ingots/tin", entries));
				game.giveMinecraftForgeItsContext(ours, neo);
				assertEquals(game.parse(game.dataFile(game.carrier()), game.conditionalOps(genuine, game.neoEmpty()), RECIPE),
						game.parse(game.dataFile(game.carrier()), game.conditionalOps(ours, neo), RECIPE),
						"same verdict as genuine MinecraftForge with " + entries + " entr(ies)");
			}
		}
	}

	@Test
	void aDatapackRegistryElementKeepsTheEmptyContextGenuineMinecraftForgeGivesIt() throws Exception {
		// Genuine MinecraftForge's ResourceManagerRegistryLoadTask decodes over plain RegistryOps: ConditionCodec.wrap
		// reads EMPTY there. Nothing hands the registry loader a context, so the same element answers the same way.
		try (Game game = new Game()) {
			Object neo = game.neoContext(Map.of("c:ingots/tin", 1));
			Object genuine = game.call(game.staticCall("net.minecraftforge.common.crafting.conditions.ConditionCodec", "wrap", game.carrier()),
					"parse", game.registryOps(), game.json(RECIPE));
			assertEquals(Optional.of(Optional.empty()), game.call(genuine, "result"), "genuine MinecraftForge drops it here");
			assertEquals(Optional.empty(), game.parse(game.registryElement(game.carrier()), game.conditionalOps(game.registryOps(), neo), RECIPE));
		}
	}

	// --- (b) a loot pool's forge:condition: MinecraftForge's in-place empty pool, judged once ------------------------

	@Test
	void aFalsePoolConditionLeavesMinecraftForgesEmptyPoolInPlaceAndIsJudgedOnce() throws Exception {
		try (Game game = new Game()) {
			Object pools = game.dataFile(game.inPlacePool());
			Object ops = game.conditionalOps(game.registryOps(), game.neoEmpty());
			assertEquals(Optional.of(-1), game.parse(pools, ops, "{\"forge:condition\":{\"type\":\"forbrictest:no\"},\"carrier\":7}"),
					"the empty pool, in place -- not a pool missing from the list");
			assertEquals(1, game.forgeTests.getAndSet(0), "judged once");
			assertEquals(Optional.of(7), game.parse(pools, ops, "{\"forge:condition\":{\"type\":\"forbrictest:yes\"},\"carrier\":7}"));
			assertEquals(1, game.forgeTests.getAndSet(0), "judged once");
		}
	}

	@Test
	void aroundAnUnregisteredForgeDecoderTheFunnelDropsThePoolAndJudgesItTwice() throws Exception {
		// The negative control, and the defect it replaces: the same MinecraftForge decoder, not registered as one that
		// judges forge:condition itself, so the funnel asks first -- the pool vanishes and a true one is asked twice.
		try (Game game = new Game()) {
			Object pools = game.dataFile(game.forgeCheckingDecode());
			Object ops = game.conditionalOps(game.registryOps(), game.neoEmpty());
			assertEquals(Optional.empty(), game.parse(pools, ops, "{\"forge:condition\":{\"type\":\"forbrictest:no\"},\"carrier\":7}"));
			game.forgeTests.set(0);
			assertEquals(Optional.of(7), game.parse(pools, ops, "{\"forge:condition\":{\"type\":\"forbrictest:yes\"},\"carrier\":7}"));
			assertEquals(2, game.forgeTests.get());
		}
	}

	@Test
	void insideALootTableThePoolIsStillOnlyMinecraftForgesToJudge() throws Exception {
		// What the game decodes: the table file is the outermost conditional decode, its pools nested in it.
		try (Game game = new Game()) {
			Object table = game.dataFile(game.fieldOf(game.listOf(game.conditional(game.forgeCheckingDecode())), "pools"));
			Object ops = game.conditionalOps(game.registryOps(), game.neoEmpty());
			Optional<?> decoded = game.parse(table, ops, "{\"pools\":[{\"forge:condition\":{\"type\":\"forbrictest:no\"},\"carrier\":7}]}");
			assertEquals(Optional.of(List.of(Optional.of(-1))), decoded, "the nested pool is not the funnel's: MinecraftForge's own decoder puts the empty pool in");
			assertEquals(1, game.forgeTests.get());
		}
	}

	// --- (c) forge:conditional, the other half of ForgeHooks.readConditional ----------------------------------------

	@Test
	void theFirstVariantWhoseConditionHoldsIsTheFile() throws Exception {
		try (Game game = new Game()) {
			Object file = game.dataFile(game.carrier());
			Object ops = game.conditionalOps(game.registryOps(), game.neoEmpty());
			assertEquals(Optional.of(2), game.parse(file, ops, "{\"forge:conditional\":["
					+ "{\"forge:condition\":{\"type\":\"forbrictest:no\"},\"carrier\":1},"
					+ "{\"forge:condition\":{\"type\":\"forbrictest:yes\"},\"carrier\":2},"
					+ "{\"forge:condition\":{\"type\":\"forbrictest:yes\"},\"carrier\":3}]}"));
			assertEquals(2, game.forgeTests.getAndSet(0), "asked until one held, as readConditional does");
			assertEquals(Optional.empty(), game.parse(file, ops, "{\"forge:conditional\":[{\"forge:condition\":{\"type\":\"forbrictest:no\"},\"carrier\":1}]}"),
					"no variant holds: the file is skipped");
			assertTrue(game.error(file, ops, "{\"forge:conditional\":[7]}").contains("forge:conditional"), "a variant that is not an object fails the file");
		}
	}

	@Test
	void switchedOffNobodyReadsTheVariants() throws Exception {
		// What 41b4f8fd left: the array unread, so NeoForge decodes the file's top level and finds no carrier there.
		try (Game game = new Game()) {
			System.setProperty("forbric.forgeConditionFunnel", "off");
			try {
				String error = game.error(game.dataFile(game.carrier()), game.conditionalOps(game.registryOps(), game.neoEmpty()),
						"{\"forge:conditional\":[{\"forge:condition\":{\"type\":\"forbrictest:yes\"},\"carrier\":2}]}");
				assertTrue(error.contains("carrier"), error);
				assertEquals(0, game.forgeTests.get());
			} finally {
				System.clearProperty("forbric.forgeConditionFunnel");
			}
		}
	}

	// --- (d) a file carrying both families' dialects ----------------------------------------------------------------

	@Test
	void whereNeoForgeJudgesItsOwnConditionsMinecraftForgeIsNotAsked() throws Exception {
		try (Game game = new Game()) {
			Object file = game.dataFile(game.carrier());
			Object ops = game.conditionalOps(game.registryOps(), game.neoEmpty());
			assertEquals(Optional.of(3), game.parse(file, ops,
					"{\"neoforge:conditions\":[{\"type\":\"neoforge:always\"}],\"forge:condition\":{\"type\":\"forbrictest:no\"},\"carrier\":3}"),
					"NeoForge keeps it: a forge:condition saying otherwise does not drop a file NeoForge judged");
			assertEquals(Optional.empty(), game.parse(file, ops,
					"{\"neoforge:conditions\":[{\"type\":\"neoforge:never\"}],\"forge:condition\":{\"type\":\"forbrictest:yes\"},\"carrier\":3}"),
					"and NeoForge's no stands");
			assertEquals(0, game.forgeTests.get(), "MinecraftForge was asked about neither");
		}
	}

	@Test
	void whereNeoForgeCannotJudgeItsConditionsTheFamilyThatOwnsThemDecides() throws Exception {
		try (Game game = new Game()) {
			Object file = game.dataFile(game.carrier());
			Object ops = game.conditionalOps(game.registryOps(), game.neoEmpty());
			assertEquals(Optional.empty(), game.parse(file, ops,
					"{\"neoforge:conditions\":[{\"type\":\"forbrictest:no\"}],\"forge:condition\":{\"type\":\"forbrictest:no\"},\"carrier\":3}"),
					"a condition type only MinecraftForge has: NeoForge lets it through, MinecraftForge says no");
			assertEquals(1, game.forgeTests.getAndSet(0));
			assertEquals(Optional.of(3), game.parse(file, ops,
					"{\"neoforge:conditions\":[{\"type\":\"forbrictest:yes\"}],\"forge:condition\":{\"type\":\"forbrictest:yes\"},\"carrier\":3}"));
			assertEquals(1, game.forgeTests.get());
		}
	}

	@Test
	void aFileWithOnlyMinecraftForgesDialectIsStillJudgedByIt() throws Exception {
		// The negative control for the two above: standing aside is about NeoForge's dialect being there, not about
		// the funnel having stopped asking.
		try (Game game = new Game()) {
			Object file = game.dataFile(game.carrier());
			Object ops = game.conditionalOps(game.registryOps(), game.neoEmpty());
			assertEquals(Optional.empty(), game.parse(file, ops, "{\"forge:condition\":{\"type\":\"forbrictest:no\"},\"carrier\":3}"));
			assertEquals(1, game.forgeTests.get());
		}
	}

	@Test
	void aPoolWhoseNeoForgeConditionsNeoForgeJudgesKeepsThatVerdictAndIsNotAskedAgain() throws Exception {
		// The pool codec's other branch: where NeoForge judged the pool on the way in, MinecraftForge's condition beside
		// it is not asked a second, possibly contrary time -- over an empty context it would replace a kept pool with the
		// empty one. Asking anyway turns the first into -1 and counts a test.
		try (Game game = new Game()) {
			Object pools = game.dataFile(game.inPlacePool());
			Object ops = game.conditionalOps(game.registryOps(), game.neoEmpty());
			assertEquals(Optional.of(7), game.parse(pools, ops,
					"{\"neoforge:conditions\":[{\"type\":\"neoforge:always\"}],\"forge:condition\":{\"type\":\"forbrictest:no\"},\"carrier\":7}"),
					"NeoForge kept the pool: the pool itself, not MinecraftForge's empty one");
			assertEquals(Optional.empty(), game.parse(pools, ops,
					"{\"neoforge:conditions\":[{\"type\":\"neoforge:never\"}],\"forge:condition\":{\"type\":\"forbrictest:yes\"},\"carrier\":7}"),
					"NeoForge's no stands");
			assertEquals(0, game.forgeTests.get(), "MinecraftForge was asked about neither pool");
		}
	}

	@Test
	void aPoolWhoseNeoForgeConditionsNeoForgeCannotJudgeIsMinecraftForgesInPlace() throws Exception {
		// The other direction, and the control for the one above: a type only MinecraftForge has passes NeoForge's
		// leniency unjudged, so MinecraftForge's pool codec decides -- once, leaving its empty pool in place.
		try (Game game = new Game()) {
			Object pools = game.dataFile(game.inPlacePool());
			Object ops = game.conditionalOps(game.registryOps(), game.neoEmpty());
			assertEquals(Optional.of(-1), game.parse(pools, ops,
					"{\"neoforge:conditions\":[{\"type\":\"forbrictest:no\"}],\"forge:condition\":{\"type\":\"forbrictest:no\"},\"carrier\":7}"));
			assertEquals(1, game.forgeTests.get(), "judged once, by MinecraftForge's own pool codec");
		}
	}

	// --- (e) a condition type MinecraftForge's registry lacks: who owns it decides ------------------------------------

	private static final String ABSENT = "{\"forge:condition\":{\"type\":\"absentmod:enabled\"},\"carrier\":7}";

	@Test
	void aTypeNoEcosystemRegistersSkipsADataFileAsGenuineMinecraftForgeDoes() throws Exception {
		try (Game game = new Game()) {
			Object ops = game.conditionalOps(game.registryOps(), game.neoEmpty());
			assertEquals(Optional.empty(), game.parse(game.dataFile(game.carrier()), ops, ABSENT),
					"skipped: no ecosystem here registers absentmod:enabled, and MinecraftForge reads such a type as false");
			assertEquals(null, game.genuineReadConditional(ABSENT), "genuine MinecraftForge's readConditional skips it too");
		}
	}

	@Test
	void aTypeAnotherEcosystemRegistersIsLeftToThatEcosystem() throws Exception {
		// NeoForge's own type and one only fabric-api registers: a multi-loader file's other dialect, which MinecraftForge
		// does not veto. The test above is the control: the same file naming a type nobody registers is skipped.
		try (Game game = new Game()) {
			Object ops = game.conditionalOps(game.registryOps(), game.neoEmpty());
			for (String type : List.of("neoforge:always", "fabrictest:owned")) {
				assertEquals(Optional.of(7), game.parse(game.dataFile(game.carrier()), ops, "{\"forge:condition\":{\"type\":\"" + type + "\"},\"carrier\":7}"), type);
			}
		}
	}

	@Test
	void aFileBothFamiliesWouldRejectIsNotLoaded() throws Exception {
		// Both dialects name a type nobody registers. NeoForge's leniency lets its own through (it might be another
		// family's), so MinecraftForge decides -- and like genuine MinecraftForge, and genuine NeoForge, does not load it.
		String file = "{\"neoforge:conditions\":[{\"type\":\"absentmod:enabled\"}],\"forge:condition\":{\"type\":\"absentmod:enabled\"},\"carrier\":3}";
		try (Game game = new Game()) {
			assertEquals(Optional.empty(), game.parse(game.dataFile(game.carrier()), game.conditionalOps(game.registryOps(), game.neoEmpty()), file));
			assertEquals(null, game.genuineReadConditional(file), "genuine MinecraftForge skips it");
			System.setProperty("forbric.neoConditions", "off");
			System.setProperty("forbric.forgeConditionFunnel", "off");
			try {
				assertTrue(game.error(game.dataFile(game.carrier()), game.conditionalOps(game.registryOps(), game.neoEmpty()), file).contains("absentmod:enabled"),
						"genuine NeoForge fails it");
			} finally {
				System.clearProperty("forbric.neoConditions");
				System.clearProperty("forbric.forgeConditionFunnel");
			}
		}
	}

	@Test
	void aVariantGuardedByATypeNoEcosystemRegistersIsPassedOverAsOnMinecraftForge() throws Exception {
		String file = "{\"forge:conditional\":[{\"forge:condition\":{\"type\":\"absentmod:enabled\"},\"carrier\":1},{\"carrier\":2}]}";
		try (Game game = new Game()) {
			assertEquals(Optional.of(2), game.parse(game.dataFile(game.carrier()), game.conditionalOps(game.registryOps(), game.neoEmpty()), file),
					"the next variant, not the one an absent mod's condition guards");
			assertEquals("{\"carrier\":2}", game.genuineReadConditional(file), "the variant genuine MinecraftForge's readConditional chooses");
		}
	}

	@Test
	void onADatapackRegistryElementATypeNoEcosystemRegistersIsIgnoredRatherThanLosingTheRegistry() throws Exception {
		// The deliberate exception: there MinecraftForge's own answer is a parse failure, which fails the whole registry
		// load. An element that fails like that on MinecraftForge is another family's build's file; it loads.
		try (Game game = new Game()) {
			Object ops = game.conditionalOps(game.registryOps(), game.neoEmpty());
			assertEquals(Optional.of(7), game.parse(game.registryElement(game.carrier()), ops, ABSENT));
			System.setProperty("forbric.forgeConditions", "off");
			try {
				assertTrue(game.error(game.registryElement(game.carrier()), ops, ABSENT).contains("absentmod:enabled"),
						"MinecraftForge's own answer there: the element, and with it the registry, fails");
			} finally {
				System.clearProperty("forbric.forgeConditions");
			}
		}
	}

	// --- (f) only where MinecraftForge's two readers read ------------------------------------------------------------

	private static final String NO = "{\"forge:condition\":{\"type\":\"forbrictest:no\"},\"carrier\":7}";

	@Test
	void outsideMinecraftForgesReadersNoForgeConditionIsJudged() throws Exception {
		try (Game game = new Game()) {
			Object ops = game.conditionalOps(game.registryOps(), game.neoEmpty());
			assertEquals(Optional.of(7), game.parse(game.conditional(game.carrier()), ops, NO),
					"a conditional decode neither reader makes -- a mod's own, the network registry path -- is NeoForge's alone");
			Object jsonOps = game.type("com.mojang.serialization.JsonOps").getField("INSTANCE").get(null);
			Object list = game.staticCall("net.neoforged.neoforge.common.conditions.ConditionalOps", "decodeListWithElementConditions", game.carrier());
			Object decoded = game.call(game.call(list, "parse", jsonOps, game.json("[{\"carrier\":1}," + NO.replace("7", "2") + "]")), "result");
			assertEquals(Optional.of(List.of(1, 2)), decoded, "NeoForge's element-conditional lists (overlay entries, sprite sources) keep every element");
			assertEquals(0, game.forgeTests.get(), "MinecraftForge was asked about none of them");
			// The negative control: the same element, read as a data file, is judged and skipped.
			assertEquals(Optional.empty(), game.parse(game.dataFile(game.carrier()), ops, NO));
			assertEquals(1, game.forgeTests.get());
		}
	}

	@Test
	void aPackMcmetaOverlayEntryIsNotDroppedOverAForgeCondition() throws Exception {
		// The real overlay section codec, read as ResourceMetadata reads it: MinecraftForge's patches never look for a
		// condition there, so an entry carrying one is mounted, exactly as NeoForge alone mounts it.
		try (Game game = new Game()) {
			Object jsonOps = game.type("com.mojang.serialization.JsonOps").getField("INSTANCE").get(null);
			Object packType = game.type("net.minecraft.server.packs.PackType").getField("SERVER_DATA").get(null);
			Object codec = game.staticCall("net.minecraft.server.packs.OverlayMetadataSection", "codecForPackType", packType);
			String section = "{\"entries\":[{\"directory\":\"keep\",\"min_format\":90,\"max_format\":1000},"
					+ "{\"directory\":\"judged\",\"min_format\":90,\"max_format\":1000,\"forge:condition\":{\"type\":\"forge:false\"}}]}";
			String ours = String.valueOf(game.call(game.call(codec, "parse", jsonOps, game.json(section)), "result"));
			assertTrue(ours.contains("overlay=keep") && ours.contains("overlay=judged"), ours);
			System.setProperty("forbric.forgeConditionFunnel", "off");
			try {
				assertEquals(String.valueOf(game.call(game.call(codec, "parse", jsonOps, game.json(section)), "result")), ours, "NeoForge as shipped");
			} finally {
				System.clearProperty("forbric.forgeConditionFunnel");
			}
		}
	}

	@Test
	void theMergedScanJudgesEachFileTheWayMinecraftForgesReaderDid() throws Exception {
		// Genuine scanDirectory, with the transformer's mark on the codec it builds: readConditional's verdict per file.
		Map<String, String> files = new LinkedHashMap<>();
		files.put("kept", "{\"forge:condition\":{\"type\":\"forbrictest:yes\"},\"carrier\":1}");
		files.put("dropped", "{\"forge:condition\":{\"type\":\"forbrictest:no\"},\"carrier\":2}");
		files.put("plain", "{\"carrier\":3}");
		files.put("absent", "{\"forge:condition\":{\"type\":\"absentmod:enabled\"},\"carrier\":4}");
		files.put("variant", "{\"forge:conditional\":[{\"forge:condition\":{\"type\":\"absentmod:enabled\"},\"carrier\":5},{\"carrier\":6}]}");
		try (Game game = new Game()) {
			Object ops = game.conditionalOps(game.registryOps(), game.neoEmpty());
			assertEquals(Map.of("kept", 1, "plain", 3, "variant", 6), game.scan(files, game.carrier(), ops));
			// The negative control: with nothing asking MinecraftForge every file loads but the variant, which NeoForge
			// cannot read.
			System.setProperty("forbric.forgeConditionFunnel", "off");
			try {
				assertEquals(Map.of("kept", 1, "dropped", 2, "plain", 3, "absent", 4), game.scan(files, game.carrier(), ops));
			} finally {
				System.clearProperty("forbric.forgeConditionFunnel");
			}
		}
	}

	@Test
	void aLoadTheRegistryLoaderAnnouncedIsJudgedAndNoOtherLoadIs() throws Exception {
		// The merged loadFromResource, defined through the transformer: a load ResourceManagerRegistryLoadTask announced
		// is MinecraftForge's registry reader; the network path and a mod's own call are not, as on MinecraftForge.
		try (Game game = new Game()) {
			Object ops = game.conditionalOps(game.registryOps(), game.neoEmpty());
			assertEquals("skipped", game.load(game.carrier(), ops, NO, true), "announced: judged, and skipped the way RegistryLoadTask skips");
			assertEquals(1, game.forgeTests.getAndSet(0));
			assertEquals(7, game.load(game.carrier(), ops, NO, false), "not announced: NeoForge's alone");
			assertEquals(0, game.forgeTests.get());
			game.staticCall("net.forbric.kernel.runtime.KernelForgeConditions", "nextLoadIsMinecraftForgesRegistryElement", game.resource(NO));
			assertEquals(7, game.load(game.carrier(), ops, NO, false), "an announcement names one resource: a load of another is not judged");
			assertEquals(0, game.forgeTests.get());
			assertEquals(7, game.load(game.carrier(), ops, ABSENT, true), "a type nobody registers does not lose the registry");
			System.setProperty("forbric.forgeConditions", "off");
			try {
				assertTrue(String.valueOf(game.load(game.carrier(), ops, ABSENT, true)).startsWith("failed: "), "MinecraftForge's own answer: a failed element");
			} finally {
				System.clearProperty("forbric.forgeConditions");
			}
		}
	}

	// --- the harness --------------------------------------------------------------------------------------------------

	/** One game-side loader per test, so the funnel's counters and the per-loader registry stand-ins never leak. */
	private static final class Game implements AutoCloseable {
		private static final String FORGE_ICONDITION = "net.minecraftforge.common.crafting.conditions.ICondition";
		private static final String NEO_ICONDITION = "net.neoforged.neoforge.common.conditions.ICondition";
		private static final String CONDITIONAL_OPS = "net.neoforged.neoforge.common.conditions.ConditionalOps";
		private static final String JSON_LISTENER = "net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener";
		private static final String PENDING_REGISTRATION = "net.minecraft.resources.RegistryLoadTask$PendingRegistration";
		private static final String HELPER = "net.forbric.kernel.runtime.KernelForgeConditions";
		/** Defined through the merged-base transformer, as the game defines them. */
		private static final java.util.Set<String> TRANSFORMED = java.util.Set.of(CONDITIONAL_OPS, FORGE_ICONDITION, NEO_ICONDITION,
				JSON_LISTENER, PENDING_REGISTRATION);

		final AtomicInteger forgeTests = new AtomicInteger();
		private final URLClassLoader loader;
		private final Class<?> codec;
		private final Class<?> dataResult;

		Game() throws Exception {
			List<URL> urls = new ArrayList<>();
			try (URLClassLoader base = KernelConditionKillSwitchesTest.gameSideLoader()) {
				urls.addAll(List.of(base.getURLs()));
			}
			for (String library : List.of("org/apache/commons/commons-lang3", "com/mojang/logging", "org/joml/joml", "com/mojang/authlib")) urls.add(newest(library));
			loader = new URLClassLoader(urls.toArray(new URL[0]), KernelForgeConditionsRealCodecTest.class.getClassLoader()) {
				@Override
				protected Class<?> findClass(String name) throws ClassNotFoundException {
					if (!TRANSFORMED.contains(name)) return super.findClass(name);
					try (InputStream in = getResourceAsStream(name.replace('.', '/') + ".class")) {
						if (in == null) throw new ClassNotFoundException(name);
						byte[] bytes = new ForbricMergedBaseCompatTransformer().transform(name, in.readAllBytes(), null);
						if (name.equals(FORGE_ICONDITION) || name.equals(NEO_ICONDITION)) bytes = standInRegistry(bytes);
						return defineClass(name, bytes, 0, bytes.length);
					} catch (IOException e) {
						throw new ClassNotFoundException(name, e);
					}
				}
			};
			codec = type("com.mojang.serialization.Codec");
			dataResult = type("com.mojang.serialization.DataResult");

			Map<String, Object> forge = new LinkedHashMap<>();
			for (String[] real : new String[][] {{"forge:true", "TrueCondition"}, {"forge:false", "FalseCondition"},
					{"forge:not", "NotCondition"}, {"forge:tag_empty", "TagEmptyCondition"}}) {
				forge.put(real[0], type("net.minecraftforge.common.crafting.conditions." + real[1]).getField("CODEC").get(null));
			}
			forge.put("forbrictest:yes", countedForgeCondition(true));
			forge.put("forbrictest:no", countedForgeCondition(false));
			Map<String, Object> neo = new LinkedHashMap<>();
			neo.put("neoforge:always", type("net.neoforged.neoforge.common.conditions.AlwaysCondition").getField("CODEC").get(null));
			neo.put("neoforge:never", type("net.neoforged.neoforge.common.conditions.NeverCondition").getField("CODEC").get(null));
			ConditionRegistryStandIn.install(loader, "forge", registryCodec(forge));
			ConditionRegistryStandIn.install(loader, "neoforge", registryCodec(neo));
			bindKnown("net.forbric.kernel.runtime.KernelForgeConditions", forge);
			bindKnown("net.forbric.kernel.runtime.KernelNeoConditions", neo);
			// fabric-api is on no test classpath: its registry is reduced to the one type it registers here. MinecraftForge's
			// leniency asks NeoForge's and fabric-api's registries through its production lookup, which is not replaced.
			Method fabric = type("net.forbric.kernel.runtime.KernelFabricConditions").getDeclaredMethod("bindRegisteredTypesForTest", java.util.function.Predicate.class);
			fabric.setAccessible(true);
			fabric.invoke(null, (java.util.function.Predicate<String>) "fabrictest:owned"::equals);
		}

		@Override
		public void close() throws IOException {
			ConditionRegistryStandIn.forget(loader);
			loader.close();
		}

		Object json(String text) throws Exception {
			return type("com.google.gson.JsonParser").getMethod("parseString", String.class).invoke(null, text);
		}

		Object registryOps() throws Exception {
			Object jsonOps = type("com.mojang.serialization.JsonOps").getField("INSTANCE").get(null);
			Object empty = type("net.minecraft.core.RegistryAccess").getField("EMPTY").get(null);
			return type("net.minecraft.resources.RegistryOps").getMethod("create", type("com.mojang.serialization.DynamicOps"),
					type("net.minecraft.core.HolderLookup$Provider")).invoke(null, jsonOps, empty);
		}

		Object conditionalOps(Object registryOps, Object neoContext) throws Exception {
			Constructor<?> ctor = type(CONDITIONAL_OPS).getConstructor(type("net.minecraft.resources.RegistryOps"), type(NEO_ICONDITION + "$IContext"));
			return ctor.newInstance(registryOps, neoContext);
		}

		Object neoEmpty() throws Exception {
			return type(NEO_ICONDITION + "$IContext").getField("EMPTY").get(null);
		}

		/** NeoForge's live context as the merged ReloadableServerResources builds it, reduced to the tags it reads. */
		Object neoContext(Map<String, Integer> tags) throws Exception {
			Class<?> context = type(NEO_ICONDITION + "$IContext");
			return Proxy.newProxyInstance(loader, new Class<?>[] {context}, (proxy, method, args) -> switch (method.getName()) {
				case "getTag" -> holders(args[0], tags);
				case "isTagLoaded" -> true;
				case "toString" -> "neoContext" + tags;
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == args[0];
				default -> InvocationHandler.invokeDefault(proxy, method, args);
			});
		}

		/** Genuine MinecraftForge's ConditionContext over this reload's pending tags. */
		Object forgeConditionContext(Map<String, Integer> tags) throws Exception {
			Object items = type("net.minecraft.core.registries.Registries").getField("ITEM").get(null);
			Object pending = Proxy.newProxyInstance(loader, new Class<?>[] {type("net.minecraft.core.Registry$PendingTags")}, (proxy, method, args) -> switch (method.getName()) {
				case "key" -> items;
				case "getPending" -> holders(args[0], tags);
				default -> throw new UnsupportedOperationException(method.getName());
			});
			return type("net.minecraftforge.common.crafting.conditions.ConditionContext").getConstructor(List.class).newInstance(List.of(pending));
		}

		void giveMinecraftForgeItsContext(Object ops, Object neo) throws Exception {
			type("net.forbric.kernel.runtime.KernelForgeConditions").getMethod("giveMinecraftForgeItsContext",
					type("net.minecraft.resources.RegistryOps"), type(NEO_ICONDITION + "$IContext"), type(FORGE_ICONDITION + "$IContext"))
					.invoke(null, ops, neo, null);
		}

		/** A codec reading {@code {"carrier": n}}: the element every case decodes. */
		Object carrier() throws Exception {
			return fieldOf(codec.getField("INT").get(null), "carrier");
		}

		Object fieldOf(Object of, String name) throws Exception {
			return call(call(of, "fieldOf", name), "codec");
		}

		Object listOf(Object of) throws Exception {
			return call(of, "listOf");
		}

		/** ConditionalOps' public factory, as every NeoForge reader calls it: Optional of the element. */
		Object conditional(Object inner) throws Exception {
			return type(CONDITIONAL_OPS).getMethod("createConditionalCodec", codec).invoke(null, inner);
		}

		/** What a merged SimpleJsonResourceReloadListener scan parses each data file with: ConditionalOps' codec, marked. */
		Object dataFile(Object inner) throws Exception {
			return staticCall(HELPER, "readsDataFiles", conditional(inner));
		}

		/** What the merged loadFromResource decodes a load ResourceManagerRegistryLoadTask announced with. */
		Object registryElement(Object inner) throws Exception {
			return staticCall(HELPER, "readsRegistryElements", conditional(inner));
		}

		/** Genuine MinecraftForge's data-file reader over a file, strict: what readConditional hands the decoder, or null. */
		Object genuineReadConditional(String file) throws Exception {
			System.setProperty("forbric.forgeConditions", "off");
			try {
				Object read = staticCall("net.minecraftforge.common.ForgeHooks", "readConditional", registryOps(), json(file));
				return read == null ? null : String.valueOf(read);
			} finally {
				System.clearProperty("forbric.forgeConditions");
			}
		}

		Object id(String text) throws Exception {
			return staticCall("net.minecraft.resources.Identifier", "parse", text);
		}

		/** A resource from one pack, holding {@code json}. */
		Object resource(String json) throws Exception {
			Object pack = Proxy.newProxyInstance(loader, new Class<?>[] {type("net.minecraft.server.packs.PackResources")}, (proxy, method, args) -> switch (method.getName()) {
				case "packId" -> "forbrictest";
				case "toString" -> "forbrictest pack";
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == args[0];
				default -> throw new UnsupportedOperationException(method.getName());
			});
			Object stream = Proxy.newProxyInstance(loader, new Class<?>[] {type("net.minecraft.server.packs.resources.IoSupplier")}, (proxy, method, args) -> switch (method.getName()) {
				case "get" -> new java.io.ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == args[0];
				default -> throw new UnsupportedOperationException(method.getName());
			});
			return type("net.minecraft.server.packs.resources.Resource").getConstructor(type("net.minecraft.server.packs.PackResources"),
					type("net.minecraft.server.packs.resources.IoSupplier")).newInstance(pack, stream);
		}

		/**
		 * The merged {@code loadFromResource} over one resource, as {@code ResourceManagerRegistryLoadTask} calls it when
		 * {@code announced} (the call it makes right before) and as the network path or a mod calls it otherwise: the
		 * element, or {@code "skipped"} for RegistryLoadTask's own skip marker, or the failure's message.
		 */
		Object load(Object decoder, Object ops, String json, boolean announced) throws Exception {
			Object resource = resource(json);
			Object key = staticCall("net.minecraft.resources.ResourceKey", "create",
					staticCall("net.minecraft.resources.ResourceKey", "createRegistryKey", id("forbrictest:elements")), id("forbrictest:probe"));
			if (announced) staticCall(HELPER, "nextLoadIsMinecraftForgesRegistryElement", resource);
			Object either = staticCall(PENDING_REGISTRATION, "loadFromResource", decoder, ops, key, resource);
			Optional<?> left = (Optional<?>) call(either, "left");
			if (left.isPresent()) return left.get();
			Object right = ((Optional<?>) call(either, "right")).orElseThrow();
			java.lang.reflect.Field marker = type("net.minecraft.resources.RegistryLoadTask").getDeclaredField("SKIPPED_ELEMENT_MARKER");
			marker.setAccessible(true);
			return right == marker.get(null) ? "skipped" : "failed: " + right;
		}

		/**
		 * Genuine {@code SimpleJsonResourceReloadListener.scanDirectory} -- the merged class, defined through the transformer
		 * as the game defines it -- over these files (name to json) under {@code probe/}: what each file that loaded became, by its id.
		 */
		Map<String, Object> scan(Map<String, String> files, Object codecInstance, Object ops) throws Exception {
			Map<Object, Object> resources = new LinkedHashMap<>();
			for (Map.Entry<String, String> file : files.entrySet()) resources.put(id("forbrictest:probe/" + file.getKey() + ".json"), resource(file.getValue()));
			Object manager = Proxy.newProxyInstance(loader, new Class<?>[] {type("net.minecraft.server.packs.resources.ResourceManager")}, (proxy, method, args) -> switch (method.getName()) {
				case "listResources" -> {
					Map<Object, Object> matching = new LinkedHashMap<>();
					@SuppressWarnings("unchecked") java.util.function.Predicate<Object> filter = (java.util.function.Predicate<Object>) args[1];
					for (Map.Entry<Object, Object> entry : resources.entrySet()) {
						if (String.valueOf(call(entry.getKey(), "getPath")).startsWith(args[0] + "/") && filter.test(entry.getKey())) matching.put(entry.getKey(), entry.getValue());
					}
					yield matching;
				}
				case "toString" -> "forbrictest resources";
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == args[0];
				default -> throw new UnsupportedOperationException(method.getName());
			});
			Map<Object, Object> out = new LinkedHashMap<>();
			staticCall(JSON_LISTENER, "scanDirectory", manager, staticCall("net.minecraft.resources.FileToIdConverter", "json", "probe"), ops, codecInstance, out);
			Map<String, Object> named = new java.util.TreeMap<>();
			for (Map.Entry<Object, Object> entry : out.entrySet()) named.put(String.valueOf(call(entry.getKey(), "getPath")), entry.getValue());
			return named;
		}

		/** MinecraftForge's own in-place conditional decoder, which LootPool.CONDITIONAL_CODEC is one of: -1 is the empty pool. */
		Object forgeCheckingDecode() throws Exception {
			return staticCall("net.minecraftforge.common.crafting.conditions.ConditionCodec", "checkingDecode", carrier(), (Supplier<Integer>) () -> -1);
		}

		/**
		 * {@code KernelForgeConditions.poolElementCodec}'s body over that decoder. NeoForge's own dialect probe is reached the
		 * way the game reaches it: through the funnel the transformed ConditionalOps puts around a codec that judges
		 * forge:condition itself.
		 */
		Object inPlacePool() throws Exception {
			Method inPlace = type(HELPER).getDeclaredMethod("inPlace", codec, codec);
			inPlace.setAccessible(true);
			return inPlace.invoke(null, forgeCheckingDecode(), carrier());
		}

		/** The decoded value, or a failure naming the decode error. */
		Optional<?> parse(Object codecInstance, Object ops, String input) throws Exception {
			Object result = call(codecInstance, "parse", ops, json(input));
			Optional<?> error = (Optional<?>) dataResult.getMethod("error").invoke(result);
			if (error.isPresent()) throw new AssertionError(input + " -> " + call(error.get(), "message"));
			return (Optional<?>) ((Optional<?>) dataResult.getMethod("result").invoke(result)).orElseThrow();
		}

		String error(Object codecInstance, Object ops, String input) throws Exception {
			Object result = call(codecInstance, "parse", ops, json(input));
			Optional<?> error = (Optional<?>) dataResult.getMethod("error").invoke(result);
			assertTrue(error.isPresent(), input + " decoded: " + dataResult.getMethod("result").invoke(result));
			return String.valueOf(call(error.get(), "message"));
		}

		Object staticCall(String owner, String name, Object... args) throws Exception {
			return invoke(type(owner), null, name, args);
		}

		Object call(Object target, String name, Object... args) throws Exception {
			return invoke(target.getClass(), target, name, args);
		}

		Class<?> type(String name) throws ClassNotFoundException {
			return Class.forName(name, true, loader);
		}

		private Object holders(Object tagKey, Map<String, Integer> tags) throws Exception {
			String id = String.valueOf(call(tagKey, "location"));
			List<Object> holders = new ArrayList<>();
			for (int i = 0; i < tags.getOrDefault(id, 0); i++) holders.add(staticCall("net.minecraft.core.Holder", "direct", "entry" + i));
			return holders;
		}

		/** A MinecraftForge condition that counts how often it is tested: the "judged once" evidence. */
		private Object countedForgeCondition(boolean answer) throws Exception {
			Class<?> condition = type(FORGE_ICONDITION);
			Object[] unit = new Object[1];
			Object instance = Proxy.newProxyInstance(loader, new Class<?>[] {condition}, (proxy, method, args) -> switch (method.getName()) {
				case "test" -> {
					forgeTests.incrementAndGet();
					yield answer;
				}
				case "codec" -> unit[0];
				case "toString" -> "forbrictest:" + (answer ? "yes" : "no");
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == args[0];
				default -> throw new UnsupportedOperationException(method.getName());
			});
			unit[0] = type("com.mojang.serialization.MapCodec").getMethod("unit", Object.class).invoke(null, instance);
			return unit[0];
		}

		/** The registry's codec: a type id in, that type's MapCodec out -- what both dispatches read the "type" key with. */
		private Object registryCodec(Map<String, Object> types) throws Exception {
			Method success = dataResult.getMethod("success", Object.class);
			Method error = dataResult.getMethod("error", Supplier.class);
			Function<String, Object> lookup = id -> {
				try {
					return types.containsKey(id) ? success.invoke(null, types.get(id)) : error.invoke(null, (Supplier<String>) () -> "unknown condition type " + id);
				} catch (ReflectiveOperationException e) {
					throw new IllegalStateException(e);
				}
			};
			Function<Object, String> reverse = mapCodec -> types.entrySet().stream().filter(e -> e.getValue() == mapCodec).map(Map.Entry::getKey).findFirst().orElseThrow();
			return codec.getMethod("comapFlatMap", Function.class, Function.class).invoke(codec.getField("STRING").get(null), lookup, reverse);
		}

		private void bindKnown(String helper, Map<String, Object> types) throws Exception {
			Method bind = type(helper).getDeclaredMethod("bindKnownTypesForTest", java.util.function.Predicate.class);
			bind.setAccessible(true);
			bind.invoke(null, (java.util.function.Predicate<String>) types::containsKey);
		}

		/**
		 * Rewrites the one lookup an {@code ICondition} makes for its registry's codec -- MinecraftForge's
		 * {@code ForgeRegistries.CONDITION_SERIALIZERS.get().getCodec()}, NeoForge's
		 * {@code NeoForgeRegistries.CONDITION_SERIALIZERS.byNameCodec()} -- into a call to {@link ConditionRegistryStandIn}.
		 */
		private static byte[] standInRegistry(byte[] bytes) {
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node, 0);
			int rewritten = 0;
			for (MethodNode method : node.methods) {
				for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (!(insn instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETSTATIC || !"CONDITION_SERIALIZERS".equals(field.name)) continue;
					List<AbstractInsnNode> lookup = new ArrayList<>(List.of(field));
					for (AbstractInsnNode next = field.getNext(); next instanceof MethodInsnNode call; next = next.getNext()) {
						lookup.add(call);
						if (call.name.equals("getCodec") || call.name.equals("byNameCodec")) break;
					}
					MethodInsnNode last = (MethodInsnNode) lookup.get(lookup.size() - 1);
					if (!last.name.equals("getCodec") && !last.name.equals("byNameCodec")) continue;
					InsnList standIn = new InsnList();
					standIn.add(new LdcInsnNode(field.owner.startsWith("net/minecraftforge/") ? "forge" : "neoforge"));
					standIn.add(new LdcInsnNode(Type.getObjectType(node.name)));
					standIn.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/forbric/kernel/runtime/ConditionRegistryStandIn", "codec",
							"(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;", false));
					standIn.add(new TypeInsnNode(Opcodes.CHECKCAST, "com/mojang/serialization/Codec"));
					method.instructions.insertBefore(field, standIn);
					insn = last;
					for (AbstractInsnNode gone : lookup) method.instructions.remove(gone);
					insn = method.instructions.getFirst();
					method.maxStack = Math.max(method.maxStack, 4);
					rewritten++;
				}
			}
			if (rewritten != 1) throw new IllegalStateException(node.name + ": expected one registry lookup to stand in for, found " + rewritten);
			ClassWriter writer = new ClassWriter(0);
			node.accept(writer);
			return writer.toByteArray();
		}

		private static Object invoke(Class<?> owner, Object target, String name, Object... args) throws Exception {
			Method chosen = null;
			for (Class<?> type = owner; type != null && chosen == null; type = type.getSuperclass()) chosen = find(type, target, name, args);
			if (chosen == null) {
				for (Class<?> face : allInterfaces(owner)) if ((chosen = find(face, target, name, args)) != null) break;
			}
			if (chosen == null) throw new NoSuchMethodException(owner.getName() + "." + name + " with " + args.length + " argument(s)");
			chosen.setAccessible(true);
			try {
				return chosen.invoke(target, args);
			} catch (InvocationTargetException e) {
				if (e.getCause() instanceof Exception cause) throw cause;
				throw e;
			}
		}

		private static Method find(Class<?> type, Object target, String name, Object[] args) {
			if (!Modifier.isPublic(type.getModifiers()) && target != null) return null;
			for (Method method : type.getDeclaredMethods()) {
				if (!method.getName().equals(name) || method.getParameterCount() != args.length || !Modifier.isPublic(method.getModifiers())) continue;
				if ((target == null) != Modifier.isStatic(method.getModifiers())) continue;
				Class<?>[] params = method.getParameterTypes();
				boolean fits = true;
				for (int i = 0; i < params.length && fits; i++) fits = args[i] == null || box(params[i]).isInstance(args[i]);
				if (fits) return method;
			}
			return null;
		}

		private static List<Class<?>> allInterfaces(Class<?> type) {
			List<Class<?>> out = new ArrayList<>();
			for (Class<?> c = type; c != null; c = c.getSuperclass()) {
				for (Class<?> face : c.getInterfaces()) {
					if (!out.contains(face)) out.add(face);
					for (Class<?> parent : allInterfaces(face)) if (!out.contains(parent)) out.add(parent);
				}
			}
			return out;
		}

		private static Class<?> box(Class<?> type) {
			if (!type.isPrimitive()) return type;
			return type == int.class ? Integer.class : type == boolean.class ? Boolean.class : type == long.class ? Long.class : Object.class;
		}

		private static URL newest(String pattern) throws Exception {
			Path under = TestFixtures.minecraftDir().resolve("libraries").resolve(pattern);
			TestFixtures.require(Fixture.MC_LIBRARIES, Files.isDirectory(under), "no staged " + pattern + " jar in the local Minecraft libraries");
			try (var stream = Files.walk(under)) {
				return stream.filter(f -> f.toString().endsWith(".jar") && !f.toString().contains("sources"))
						.sorted(java.util.Comparator.comparing(f -> f.getFileName().toString())).reduce((a, b) -> b).orElseThrow().toUri().toURL();
			}
		}
	}
}
