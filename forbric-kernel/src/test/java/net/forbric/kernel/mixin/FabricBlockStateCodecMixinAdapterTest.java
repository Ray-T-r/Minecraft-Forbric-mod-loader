package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** fabric-model-loading's real block-state codec mixin against the real merged and vanilla targets (issue #16). */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class FabricBlockStateCodecMixinAdapterTest {
	private static final String MIXIN = "net/fabricmc/fabric/mixin/client/model/loading/BlockStateModelUnbakedMixin";
	private static final String REGISTRY = "net/fabricmc/fabric/impl/client/model/loading/CustomUnbakedBlockStateModelRegistry";

	private static ClassNode mixin() throws Exception {
		return StagedFabricMixinFixture.mixin("fabric-model-loading-api-v1", MIXIN);
	}

	private static ClassNode target(boolean vanilla) throws Exception {
		return StagedFabricMixinFixture.game(FabricBlockStateCodecMixinAdapter.TARGET, vanilla);
	}

	private static MethodNode handler(ClassNode mixin, int ordinal) {
		return mixin.methods.stream().filter(m -> MixinFit.injectorOf(m) != null)
				.filter(m -> Integer.valueOf(ordinal).equals(MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(m)).getFirst(), "ordinal")))
				.findFirst().orElseThrow();
	}

	private static List<String> code(MethodNode method) {
		return java.util.stream.StreamSupport.stream(method.instructions.spliterator(), false).filter(i -> i.getOpcode() >= 0).map(i -> switch (i) {
			case VarInsnNode v -> "ALOAD " + v.var;
			case MethodInsnNode m -> m.owner + "." + m.name;
			case FieldInsnNode f -> f.owner + "." + f.name;
			default -> String.valueOf(i.getOpcode());
		}).toList();
	}

	@Test void bothRedirectsMakeTheCallTheyReplaceAndHandBothCodecsToTheKernel() throws Exception {
		ClassNode mixin = mixin();
		ClassNode target = target(false);
		assertTrue(FabricBlockStateCodecMixinAdapter.neoForgeBuildsBoth(target), "the merged <clinit> builds NeoForge's codecs");
		byte[] targetBefore = StagedFabricMixinFixture.bytes(target);
		assertEquals(2, FabricBlockStateCodecMixinAdapter.adapt(mixin, n -> target));
		String registry = REGISTRY;
		List<String> fields = List.of("WEIGHTED_MODEL_CODEC", "MODEL_CODEC");
		for (int ordinal = 0; ordinal < 2; ordinal++) {
			MethodNode handler = handler(mixin, ordinal);
			assertEquals(List.of("ALOAD 0", "ALOAD 1", "ALOAD 2", "com/mojang/serialization/Codec.flatComapMap",
					registry + "." + fields.get(ordinal), FabricBlockStateCodecMixinAdapter.RUNTIME + ".either",
					String.valueOf(Opcodes.ARETURN)), code(handler), "ordinal " + ordinal);
			new Analyzer<>(new BasicVerifier()).analyze(mixin.name, handler);
			assertEquals(List.of("<clinit>()V"), MixinFit.value(MixinFit.injectorOf(handler), "method"), "the injection itself is unchanged");
		}
		assertArrayEquals(targetBefore, StagedFabricMixinFixture.bytes(target), "the target is not touched; Mixin applies the handlers");
		assertEquals(0, FabricBlockStateCodecMixinAdapter.adapt(mixin, n -> target), "a second pass finds nothing to do");
	}

	/** The rule reads what the handlers do: the same mixin relocated, its handlers and field owner renamed, is adapted alike. */
	@Test void theSameRedirectsUnderUnrelatedNamesAreAdaptedAlike() throws Exception {
		ClassNode original = mixin();
		ClassNode renamed = new ClassNode();
		original.accept(new org.objectweb.asm.commons.ClassRemapper(renamed, new org.objectweb.asm.commons.Remapper() {
			@Override public String map(String name) {
				return name.equals(MIXIN) ? "org/qzx/models/QzxCodecHooks" : name.equals(REGISTRY) ? "org/qzx/models/QzxDialects" : name;
			}

			@Override public String mapMethodName(String owner, String name, String desc) {
				return owner.equals(MIXIN) && !name.startsWith("<") ? "qzx" + name : name;
			}

			@Override public String mapFieldName(String owner, String name, String desc) {
				return owner.equals(REGISTRY) ? "QZX_" + name : name;
			}
		}));
		ClassNode target = target(false);
		assertEquals(2, FabricBlockStateCodecMixinAdapter.adapt(renamed, n -> target));
		for (int ordinal = 0; ordinal < 2; ordinal++) {
			List<String> code = code(handler(renamed, ordinal));
			assertTrue(code.contains("com/mojang/serialization/Codec.flatComapMap") && code.contains(FabricBlockStateCodecMixinAdapter.RUNTIME + ".either")
					&& code.stream().anyMatch(c -> c.startsWith("org/qzx/models/QzxDialects.QZX_")), code.toString());
		}
	}

	@Test void vanillaHasNoNeoForgeCodecToKeep() throws Exception {
		ClassNode mixin = mixin();
		byte[] before = StagedFabricMixinFixture.bytes(mixin);
		ClassNode vanilla = target(true);
		assertFalse(FabricBlockStateCodecMixinAdapter.neoForgeBuildsBoth(vanilla));
		assertEquals(0, FabricBlockStateCodecMixinAdapter.adapt(mixin, n -> vanilla));
		assertArrayEquals(before, StagedFabricMixinFixture.bytes(mixin));
	}

	@Test void aHandlerThatDoesMoreThanReturnACodecFieldIsLeftAlone() throws Exception {
		ClassNode mixin = mixin();
		MethodNode weighted = handler(mixin, 0);
		weighted.instructions.insert(new InsnNode(Opcodes.POP));
		weighted.instructions.insert(new VarInsnNode(Opcodes.ALOAD, 1));
		ClassNode target = target(false);
		assertEquals(1, FabricBlockStateCodecMixinAdapter.adapt(mixin, n -> target), "a handler that does anything else stays as written; the other is adapted");
		assertFalse(code(handler(mixin, 0)).contains(FabricBlockStateCodecMixinAdapter.RUNTIME + ".either"));
		assertTrue(code(handler(mixin, 1)).contains(FabricBlockStateCodecMixinAdapter.RUNTIME + ".either"));
	}

	@Test void theOffSwitchLeavesFabricsCodecsInPlace() throws Exception {
		String old = System.setProperty(FabricBlockStateCodecMixinAdapter.PROPERTY, "off");
		try {
			ClassNode mixin = mixin();
			byte[] before = StagedFabricMixinFixture.bytes(mixin);
			ClassNode target = target(false);
			assertEquals(0, FabricBlockStateCodecMixinAdapter.adapt(mixin, n -> target));
			assertArrayEquals(before, StagedFabricMixinFixture.bytes(mixin));
		} finally {
			if (old == null) System.clearProperty(FabricBlockStateCodecMixinAdapter.PROPERTY);
			else System.setProperty(FabricBlockStateCodecMixinAdapter.PROPERTY, old);
		}
	}
}
