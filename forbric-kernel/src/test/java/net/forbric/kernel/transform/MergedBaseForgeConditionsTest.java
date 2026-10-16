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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.kernel.GateLogContract;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * The MinecraftForge half of the condition wrap — the third evaluator, and the one nothing had covered.
 *
 * <p>Two of these assertions are about the merged base rather than about the repair, because the repair is only
 * worth having if its premise still holds: MinecraftForge's evaluator has to actually be on the datapack path,
 * and it has to actually be strict. Both are read out of the staged bytes rather than assumed.
 *
 * <p>The last two are about where that evaluator is asked: forge:condition is judged at ConditionalOps' one codec
 * factory, the funnel the merged registry and data-file loaders decode through. MinecraftForge's wrap goes in once,
 * before Fabric's, so Fabric's stays the last thing before the exit (which is what SupersededMixins reads as its
 * repair being in the defined class). That wrap decodes through ICondition.CODEC, which is why the leniency above
 * still matters to it.
 *
 * <p>The rest are about the context MinecraftForge's evaluator is given. Genuine MinecraftForge hands its recipes a
 * tag-aware {@code ConditionContext} and nothing else one: its datapack-registry loader decodes over plain
 * {@code RegistryOps}, and its advancement manager takes the context and drops it. The merged RecipeManager is
 * NeoForge's, so the recipe hand-off is put back there and nowhere else.
 */
@org.junit.jupiter.api.parallel.ResourceLock("system-properties")
class MergedBaseForgeConditionsTest {
	private static final Path STAGED = TestFixtures.stagedRoot();
	private static final Path FORGE_CARRIER = STAGED.resolve("forge-runtime/forge-runtime.jar");
	private static final Path MERGED_BASE = STAGED.resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final String ENTRY = "net/minecraftforge/common/crafting/conditions/ICondition.class";
	private static final String BINARY = "net.minecraftforge.common.crafting.conditions.ICondition";
	private static final String KERNEL = "net/forbric/kernel/runtime/KernelForgeConditions";
	private static final String CONDITION_CODEC = "net/minecraftforge/common/crafting/conditions/ConditionCodec";
	private static final String CONDITIONAL_OPS_BINARY = "net.neoforged.neoforge.common.conditions.ConditionalOps";
	private static final String CONDITIONAL_OPS_ENTRY = "net/neoforged/neoforge/common/conditions/ConditionalOps.class";
	private static final String FACTORY = "createConditionalCodecWithConditions";
	private static final String FACTORY_DESC = "(Lcom/mojang/serialization/Codec;Ljava/lang/String;)Lcom/mojang/serialization/Codec;";
	private static final String RECIPE_MANAGER = "net/minecraft/world/item/crafting/RecipeManager";
	private static final String FORGE_CONTEXT = "net/minecraftforge/common/crafting/conditions/ICondition$IContext";

	@Test
	void theCodecIsWrappedBeforeItIsStoredAndBeforeTheOtherTwoAreDerivedFromIt() throws Exception {
		MethodNode clinit = clinit(repaired());
		AbstractInsnNode[] body = clinit.instructions.toArray();

		int wrap = -1;
		int store = -1;
		List<Integer> derived = new ArrayList<>();
		for (int i = 0; i < body.length; i++) {
			if (body[i] instanceof MethodInsnNode call && KERNEL.equals(call.owner) && "lenient".equals(call.name)) {
				assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
				assertEquals("(Lcom/mojang/serialization/Codec;)Lcom/mojang/serialization/Codec;", call.desc,
						"a Codec in and a Codec out, or the PUTSTATIC after it is storing something else");
				wrap = i;
			} else if (body[i] instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC) {
				if ("CODEC".equals(field.name)) store = i;
				else if ("OPTIONAL_FEILD_CODEC".equals(field.name) || "SAFE_CODEC".equals(field.name)) {
					derived.add(i);
				}
			}
		}

		assertTrue(wrap >= 0, "MinecraftForge's ICondition.CODEC is not wrapped — another ecosystem's condition "
				+ "id then fails the whole registry load, because the merged base runs ITS evaluator over every "
				+ "datapack registry element and every loot pool");
		assertTrue(store > wrap, "the wrap must happen before CODEC is stored");
		for (int i : derived) {
			assertTrue(i > store, "OPTIONAL_FEILD_CODEC and SAFE_CODEC are derived from CODEC later in the same "
					+ "initializer, which is what makes one wrap cover all three readers");
		}
		assertEquals(2, derived.size(), "both derived codecs are expected here; if one is gone, the claim that "
				+ "wrapping CODEC covers every reader needs re-deriving rather than re-asserting");
	}

	@Test
	void minecraftForgesEvaluatorIsStillOnTheDatapackPathAtAll() throws Exception {
		// The premise. If the merge ever stops carrying MinecraftForge's half of this method, the repair above is
		// dead weight and should be removed rather than left looking useful.
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent");

		List<String> callers = new ArrayList<>();
		for (String owner : List.of("net/minecraft/resources/ResourceManagerRegistryLoadTask",
				"net/minecraft/world/level/storage/loot/LootPool")) {
			ClassNode node = new ClassNode();
			byte[] bytes = read(MERGED_BASE, owner + ".class");
			assertTrue(bytes != null, "content drift: " + owner + " absent from this base");
			new ClassReader(bytes).accept(node, 0);

			for (MethodNode method : node.methods) {
				if (method.instructions == null) continue;
				for (AbstractInsnNode insn : method.instructions) {
					if (insn instanceof MethodInsnNode call && CONDITION_CODEC.equals(call.owner)) {
						callers.add(owner + "." + method.name);
					}
				}
			}
		}

		assertTrue(!callers.isEmpty(),
				"nothing in the merged base drives MinecraftForge's ConditionCodec any more — the third evaluator "
						+ "is gone, so this repair is now unnecessary rather than merely quiet");
	}

	@Test
	void safeCodecIsNotTheAnswerBecauseItDropsWhatItCannotParse() throws Exception {
		// MinecraftForge ships something that looks like this repair and is worse: SAFE_CODEC is
		// CODEC.orElse(FalseCondition.INSTANCE), so an unparseable condition evaluates FALSE and the element is
		// silently DROPPED. Pinning that here so nobody reaches for it as a shortcut later.
		MethodNode clinit = clinit(parse(original()));

		boolean orElseFalse = false;
		for (AbstractInsnNode insn = clinit.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
					&& field.owner.endsWith("/FalseCondition") && "INSTANCE".equals(field.name)) {
				orElseFalse = true;
			}
		}
		assertTrue(orElseFalse, "SAFE_CODEC no longer falls back to FalseCondition — re-check whether it has "
				+ "become a usable alternative to this repair instead of a content-dropping one");
	}

	@Test
	void aSecondPassDoesNotWrapItTwice() throws Exception {
		byte[] once = new ForbricMergedBaseCompatTransformer().transform(BINARY, original(), null);
		byte[] twice = new ForbricMergedBaseCompatTransformer().transform(BINARY, once, null);
		assertSame(once, twice, "wrapping the wrapper would work and would also make the log lie about how many "
				+ "conditions were ignored");
	}

	@Test
	void minecraftForgeIsAskedBeforeFabricAtTheOnlyExit() throws Exception {
		byte[] repaired = new ForbricMergedBaseCompatTransformer().transform(CONDITIONAL_OPS_BINARY, conditionalOps(), null);
		assertEquals(List.of("KernelForgeConditions.alsoAskForge", "KernelFabricConditions.alsoAskFabric"), wraps(repaired));
		assertSame(repaired, new ForbricMergedBaseCompatTransformer().transform(CONDITIONAL_OPS_BINARY, repaired, null), "a second pass wraps nothing twice");
	}

	@Test
	void aFactoryFabricAlreadyWrapsGetsMinecraftForgesWrapInFrontOfIt() throws Exception {
		// The same class with only Fabric's wrap in it -- what an earlier kernel left -- still ends Forge then Fabric.
		ClassNode node = new ClassNode();
		new ClassReader(conditionalOps()).accept(node, 0);
		MethodNode factory = node.methods.stream().filter(m -> m.name.equals(FACTORY) && m.desc.equals(FACTORY_DESC)).findFirst().orElseThrow();
		AbstractInsnNode exit = null;
		for (AbstractInsnNode insn : factory.instructions) if (insn.getOpcode() == Opcodes.ARETURN) exit = insn;
		factory.instructions.insertBefore(exit, new MethodInsnNode(Opcodes.INVOKESTATIC, "net/forbric/kernel/runtime/KernelFabricConditions",
				"alsoAskFabric", "(Lcom/mojang/serialization/Codec;)Lcom/mojang/serialization/Codec;", false));
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		byte[] repaired = new ForbricMergedBaseCompatTransformer().transform(CONDITIONAL_OPS_BINARY, writer.toByteArray(), null);
		assertEquals(List.of("KernelForgeConditions.alsoAskForge", "KernelFabricConditions.alsoAskFabric"), wraps(repaired));
	}

	@Test
	void theFunnelIsHandedTheFactorysOwnTwoArgumentsAndTheStackStillFits() throws Exception {
		// The funnel stands aside around a codec that judges forge:condition itself and over an element NeoForge judges,
		// so it must be handed the factory's own arguments -- slot 0, the codec NeoForge's conditional wraps, and slot 1,
		// the key NeoForge judges -- on top of the finished codec, with maxStack raised to hold them.
		byte[] repaired = new ForbricMergedBaseCompatTransformer().transform(CONDITIONAL_OPS_BINARY, conditionalOps(), null);
		ClassNode node = parse(repaired);
		MethodNode factory = node.methods.stream().filter(m -> m.name.equals(FACTORY) && m.desc.equals(FACTORY_DESC)).findFirst().orElseThrow();
		MethodInsnNode ask = null;
		for (AbstractInsnNode insn : factory.instructions) {
			if (insn instanceof MethodInsnNode call && KERNEL.equals(call.owner) && "alsoAskForge".equals(call.name)) ask = call;
		}
		assertTrue(ask != null, "the funnel is not inserted");
		assertEquals("(Lcom/mojang/serialization/Codec;Lcom/mojang/serialization/Codec;Ljava/lang/String;)Lcom/mojang/serialization/Codec;", ask.desc);
		AbstractInsnNode key = ask.getPrevious(), inner = key.getPrevious();
		assertTrue(inner instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == 0, "the wrapped codec, the factory's first argument");
		assertTrue(key instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == 1, "NeoForge's key, the factory's second argument");
		new Analyzer<>(new BasicVerifier()).analyze(node.name, factory);  // throws on a stack deeper than maxStack
	}

	@Test
	void switchedOffTheTransformLogSaysNothingAsksMinecraftForge() throws Exception {
		String[] on = new String[1];
		GateLogContract.capture(() -> new ForbricMergedBaseCompatTransformer().transform(CONDITIONAL_OPS_BINARY, conditionalOps(), null), on);
		assertTrue(on[0].contains("forge:condition has an evaluator again"), on[0]);
		String[] off = new String[1];
		System.setProperty("forbric.forgeConditionFunnel", "off");
		try {
			byte[] repaired = GateLogContract.capture(() -> new ForbricMergedBaseCompatTransformer().transform(CONDITIONAL_OPS_BINARY, conditionalOps(), null), off);
			assertEquals(List.of("KernelForgeConditions.alsoAskForge", "KernelFabricConditions.alsoAskFabric"), wraps(repaired),
					"the wrap is still inserted: the switch is read per decode");
		} finally {
			System.clearProperty("forbric.forgeConditionFunnel");
		}
		assertTrue(!off[0].contains("forge:condition has an evaluator again"), "switched off, the log must not claim MinecraftForge is asked: " + off[0]);
		assertTrue(off[0].contains("nothing asks MinecraftForge about forge:condition"), off[0]);
	}

	@Test
	void genuineMinecraftForgeHandsItsRecipesTheirContextAndItsAdvancementsNone() throws Exception {
		// The premise both ways, read off genuine MinecraftForge's own game. RecipeManager.prepare wraps its ops in the
		// context field before scanning; ServerAdvancementManager's two-argument constructor never loads the context it is
		// given, and hands its super the three-argument constructor that builds plain ops.
		Path genuine = TestFixtures.forgeMergeInput();
		ClassNode recipes = parse(TestFixtures.requireEntry(Fixture.STAGED, genuine, RECIPE_MANAGER + ".class"));
		MethodNode prepare = recipes.methods.stream().filter(m -> m.name.equals("prepare") && m.desc.startsWith("(Lnet/minecraft/server/packs/resources/ResourceManager;")
				&& !m.desc.endsWith(")Ljava/lang/Object;")).findFirst().orElseThrow();
		boolean readsContext = false, wraps = false;
		for (AbstractInsnNode insn : prepare.instructions) {
			if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD && field.desc.equals("L" + FORGE_CONTEXT + ";")) readsContext = true;
			if (insn instanceof MethodInsnNode call && call.owner.equals(FORGE_CONTEXT) && call.name.equals("wrap")) wraps = true;
		}
		assertTrue(readsContext && wraps, "genuine RecipeManager.prepare no longer wraps its ops in its MinecraftForge context -- re-derive the hand-off");

		ClassNode advancements = parse(TestFixtures.requireEntry(Fixture.STAGED, genuine, "net/minecraft/server/ServerAdvancementManager.class"));
		MethodNode withContext = advancements.methods.stream().filter(m -> m.name.equals("<init>") && m.desc.contains(FORGE_CONTEXT)).findFirst().orElseThrow();
		for (AbstractInsnNode insn : withContext.instructions) {
			assertTrue(!(insn instanceof VarInsnNode load && load.var == 2),
					"genuine ServerAdvancementManager now reads the context it is given -- advancements are no longer EMPTY there, re-derive");
		}
	}

	@Test
	void theMergedRecipeManagerHandsMinecraftForgeItsContextRightBeforeNeoForgesOpsAreBuilt() throws Exception {
		byte[] merged = TestFixtures.requireEntry(Fixture.STAGED, MERGED_BASE, RECIPE_MANAGER + ".class");
		ClassNode before = parse(merged);
		assertTrue(before.fields.stream().anyMatch(f -> f.desc.equals("L" + FORGE_CONTEXT + ";")), "premise: MinecraftForge's context field is in the merged class");
		for (MethodNode method : before.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				assertTrue(!(insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD && field.desc.equals("L" + FORGE_CONTEXT + ";")),
						"premise: nothing in the merged RecipeManager reads it (" + method.name + " does)");
			}
		}

		byte[] repaired = new ForbricMergedBaseCompatTransformer().transform(RECIPE_MANAGER.replace('/', '.'), merged, null);
		ClassNode node = parse(repaired);
		MethodNode site = null;
		MethodInsnNode hand = null;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && KERNEL.equals(call.owner) && "giveMinecraftForgeItsContext".equals(call.name)) {
					assertTrue(hand == null, "handed over twice");
					site = method;
					hand = call;
				}
			}
		}
		assertTrue(hand != null, "the merged RecipeManager never hands MinecraftForge its recipe context");
		AbstractInsnNode next = hand.getNext();
		while (next.getOpcode() < 0) next = next.getNext();
		assertTrue(next instanceof MethodInsnNode init && init.owner.equals("net/neoforged/neoforge/common/conditions/ConditionalOps") && init.name.equals("<init>"),
				"right before NeoForge's ConditionalOps is constructed, with its ops on the stack");
		AbstractInsnNode field = hand.getPrevious(), self = field.getPrevious(), dup = self.getPrevious();
		assertEquals(Opcodes.DUP2, dup.getOpcode(), "the ops and NeoForge's context, duplicated, so the constructor still gets them");
		assertTrue(self instanceof VarInsnNode load && load.var == 0 && field instanceof FieldInsnNode read && read.desc.equals("L" + FORGE_CONTEXT + ";"),
				"and the manager's own MinecraftForge context, for when MinecraftForge's constructor set it");
		new Analyzer<>(new BasicVerifier()).analyze(node.name, site);
		assertSame(repaired, new ForbricMergedBaseCompatTransformer().transform(RECIPE_MANAGER.replace('/', '.'), repaired, null), "a second pass hands nothing over twice");
	}

	@Test
	void genuineMinecraftForgesRecipeManagerReadsItsOwnContextAndIsLeftAlone() throws Exception {
		// The negative control: the repair is for a context field nothing reads. Where MinecraftForge's own prepare reads
		// it, there is nothing to give back.
		byte[] genuine = TestFixtures.requireEntry(Fixture.STAGED, TestFixtures.forgeMergeInput(), RECIPE_MANAGER + ".class");
		byte[] out = new ForbricMergedBaseCompatTransformer().transform(RECIPE_MANAGER.replace('/', '.'), genuine, null);
		for (MethodNode method : parse(out).methods) {
			for (AbstractInsnNode insn : method.instructions) {
				assertTrue(!(insn instanceof MethodInsnNode call && "giveMinecraftForgeItsContext".equals(call.name)), "handed over where MinecraftForge already reads it");
			}
		}
	}

	private static final String JSON_LISTENER = "net/minecraft/server/packs/resources/SimpleJsonResourceReloadListener";
	private static final String REGISTRY_LOAD_TASK = "net/minecraft/resources/ResourceManagerRegistryLoadTask";
	private static final String NETWORK_LOAD_TASK = "net/minecraft/resources/NetworkRegistryLoadTask";
	private static final String PENDING_REGISTRATION = "net/minecraft/resources/RegistryLoadTask$PendingRegistration";
	private static final String CREATE_CONDITIONAL = "createConditionalCodec";
	private static final String CODEC_TO_CODEC = "(Lcom/mojang/serialization/Codec;)Lcom/mojang/serialization/Codec;";

	@Test
	void everyCodecTheMergedScansParseFilesWithIsMarkedAsMinecraftForgesDataFileReader() throws Exception {
		// MinecraftForge's scan handed every file to readConditional; the merged scans build ConditionalOps' codec once and
		// parse each file with it. That codec, and only a codec a method parses with, is marked.
		byte[] merged = TestFixtures.requireEntry(Fixture.STAGED, MERGED_BASE, JSON_LISTENER + ".class");
		byte[] repaired = new ForbricMergedBaseCompatTransformer().transform(JSON_LISTENER.replace('/', '.'), merged, null);
		ClassNode node = parse(repaired);
		List<String> marked = new ArrayList<>();
		for (MethodNode method : node.methods) {
			if (method.instructions == null) continue;
			boolean parses = false;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && call.owner.equals("com/mojang/serialization/Codec") && call.name.equals("parse")) parses = true;
			}
			for (AbstractInsnNode insn : method.instructions) {
				if (!(insn instanceof MethodInsnNode call && (call.owner + ".class").equals(CONDITIONAL_OPS_ENTRY) && call.name.equals(CREATE_CONDITIONAL)
						&& call.desc.equals(CODEC_TO_CODEC))) continue;
				boolean mark = call.getNext() instanceof MethodInsnNode next && KERNEL.equals(next.owner) && "readsDataFiles".equals(next.name)
						&& next.desc.equals(CODEC_TO_CODEC);
				assertEquals(parses, mark, method.name + ": marked exactly where the codec it builds parses a file");
				if (mark) marked.add(method.name);
			}
			new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
		}
		assertEquals(List.of("scanDirectory", "scanDirectoryWithModifier"), marked.stream().sorted().toList(),
				"the two merged scans -- the plain one and the one recipes use -- are MinecraftForge's data-file reader");
		assertSame(repaired, new ForbricMergedBaseCompatTransformer().transform(JSON_LISTENER.replace('/', '.'), repaired, null), "a second pass marks nothing twice");
	}

	@Test
	void theRegistryLoaderAnnouncesEachLoadAndLoadFromResourceMarksTheOneAnnounced() throws Exception {
		byte[] task = TestFixtures.requireEntry(Fixture.STAGED, MERGED_BASE, REGISTRY_LOAD_TASK + ".class");
		byte[] announcing = new ForbricMergedBaseCompatTransformer().transform(REGISTRY_LOAD_TASK.replace('/', '.'), task, null);
		ClassNode node = parse(announcing);
		int announced = 0;
		for (MethodNode method : node.methods) {
			if (method.instructions == null) continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (!(insn instanceof MethodInsnNode call && call.owner.equals(PENDING_REGISTRATION) && call.name.equals("loadFromResource"))) continue;
				AbstractInsnNode announce = call.getPrevious(), dup = announce.getPrevious();
				assertTrue(announce instanceof MethodInsnNode a && KERNEL.equals(a.owner) && "nextLoadIsMinecraftForgesRegistryElement".equals(a.name)
						&& a.desc.equals("(Lnet/minecraft/server/packs/resources/Resource;)V") && dup.getOpcode() == Opcodes.DUP,
						method.name + ": the resource on top of the stack is announced right before the call, which stays where it is");
				announced++;
			}
			new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
		}
		assertTrue(announced >= 1, "the merged registry loader calls loadFromResource nowhere -- re-derive where its elements are decoded");
		assertSame(announcing, new ForbricMergedBaseCompatTransformer().transform(REGISTRY_LOAD_TASK.replace('/', '.'), announcing, null), "a second pass announces nothing twice");

		byte[] pending = TestFixtures.requireEntry(Fixture.STAGED, MERGED_BASE, PENDING_REGISTRATION + ".class");
		byte[] marking = new ForbricMergedBaseCompatTransformer().transform(PENDING_REGISTRATION.replace('/', '.'), pending, null);
		ClassNode registration = parse(marking);
		MethodNode load = registration.methods.stream().filter(m -> m.name.equals("loadFromResource")).findFirst().orElseThrow();
		MethodInsnNode build = null;
		for (AbstractInsnNode insn : load.instructions) {
			if (insn instanceof MethodInsnNode call && call.name.equals(CREATE_CONDITIONAL) && call.desc.equals(CODEC_TO_CODEC)) build = call;
		}
		assertTrue(build != null, "premise: the merged loadFromResource decodes through ConditionalOps");
		AbstractInsnNode resource = build.getNext(), mark = resource.getNext();
		assertTrue(resource instanceof VarInsnNode r && r.getOpcode() == Opcodes.ALOAD && r.var == 3, "the resource argument");
		assertTrue(mark instanceof MethodInsnNode m && KERNEL.equals(m.owner) && "readerFor".equals(m.name)
				&& m.desc.equals("(Lcom/mojang/serialization/Codec;Lnet/minecraft/server/packs/resources/Resource;)Lcom/mojang/serialization/Codec;"),
				"marks the codec it built, for that resource");
		new Analyzer<>(new BasicVerifier()).analyze(registration.name, load);
		assertSame(marking, new ForbricMergedBaseCompatTransformer().transform(PENDING_REGISTRATION.replace('/', '.'), marking, null), "a second pass marks nothing twice");
	}

	@Test
	void theNetworkRegistryPathIsNotAnnounced() throws Exception {
		// The negative control: the client's network registry load reaches loadFromResource too (findAndLoadFromResource),
		// and MinecraftForge never judged forge:condition there.
		byte[] network = TestFixtures.requireEntry(Fixture.STAGED, MERGED_BASE, NETWORK_LOAD_TASK + ".class");
		ClassNode before = parse(network);
		assertTrue(before.methods.stream().anyMatch(m -> m.instructions != null && java.util.stream.StreamSupport.stream(m.instructions.spliterator(), false)
				.anyMatch(i -> i instanceof MethodInsnNode c && c.owner.equals(PENDING_REGISTRATION) && c.name.equals("findAndLoadFromResource"))),
				"premise: the network path loads through PendingRegistration");
		for (MethodNode method : parse(new ForbricMergedBaseCompatTransformer().transform(NETWORK_LOAD_TASK.replace('/', '.'), network, null)).methods) {
			if (method.instructions == null) continue;
			for (AbstractInsnNode insn : method.instructions) {
				assertTrue(!(insn instanceof MethodInsnNode call && KERNEL.equals(call.owner)), method.name + " announces a load as MinecraftForge's");
			}
		}
	}

	private static byte[] conditionalOps() throws Exception {
		return TestFixtures.requireEntry(Fixture.STAGED, STAGED.resolve("neoforge-runtime/neoforge-runtime.jar"), CONDITIONAL_OPS_ENTRY);
	}

	/** The kernel calls right before the factory's only exit, in order. */
	private static List<String> wraps(byte[] bytes) {
		ClassNode node = parse(bytes);
		MethodNode factory = node.methods.stream().filter(m -> m.name.equals(FACTORY) && m.desc.equals(FACTORY_DESC)).findFirst().orElseThrow();
		List<AbstractInsnNode> code = new ArrayList<>();
		for (AbstractInsnNode insn : factory.instructions) if (insn.getOpcode() >= 0) code.add(insn);
		assertEquals(1, code.stream().filter(i -> i.getOpcode() == Opcodes.ARETURN).count());
		List<String> calls = new ArrayList<>();
		for (int i = code.indexOf(code.stream().filter(x -> x.getOpcode() == Opcodes.ARETURN).findFirst().orElseThrow()) - 1;
				i >= 0 && code.get(i) instanceof MethodInsnNode call && call.owner.startsWith("net/forbric/kernel/runtime/"); i--)
			calls.add(0, call.owner.substring(call.owner.lastIndexOf('/') + 1) + "." + call.name);
		return calls;
	}

	private static ClassNode repaired() throws Exception {
		return parse(new ForbricMergedBaseCompatTransformer().transform(BINARY, original(), null));
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] original() throws IOException {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(FORGE_CARRIER), "staged MinecraftForge carrier absent");
		byte[] bytes = read(FORGE_CARRIER, ENTRY);
		assertTrue(bytes != null, "content drift: ICondition absent from this carrier");
		return bytes;
	}

	private static byte[] read(Path jar, String entry) throws IOException {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry e = zip.getEntry(entry);
			if (e == null) return null;
			try (InputStream in = zip.getInputStream(e)) {
				return in.readAllBytes();
			}
		}
	}

	private static MethodNode clinit(ClassNode node) {
		for (MethodNode method : node.methods) {
			if ("<clinit>".equals(method.name)) return method;
		}
		throw new AssertionError("ICondition has no static initializer");
	}
}
