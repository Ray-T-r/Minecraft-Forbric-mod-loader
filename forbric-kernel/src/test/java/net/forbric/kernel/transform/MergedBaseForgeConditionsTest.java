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
 */
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
