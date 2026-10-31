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

/**
 * fabric-networking's real common-listener payload mixin against the real genuine and vanilla play listeners: on NeoForge's
 * base the play listener hands what fabric-api's play receiver declined to super, where fabric-api's configuration-only
 * handler threw "Unknown addon" and disconnected the player (97-mod pack, world join).
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class FabricCommonPayloadMixinAdapterTest {
	private static final String MIXIN = "net/fabricmc/fabric/mixin/networking/ServerCommonPacketListenerImplMixin";
	private static final String SWITCH = "forbric.playPayloadFallThrough";

	private static ClassNode mixin() throws Exception {
		return StagedFabricMixinFixture.mixin("fabric-networking-api-v1", MIXIN);
	}

	private static ClassNode game(boolean vanilla) throws Exception {
		return StagedFabricMixinFixture.game(FabricCommonPayloadMixinAdapter.GAME_LISTENER, vanilla);
	}

	private static MethodNode refusing(ClassNode mixin) {
		List<MethodNode> found = mixin.methods.stream().filter(FabricCommonPayloadMixinAdapter::refusesOtherListeners).toList();
		assertEquals(1, found.size(), "fabric-api's one configuration-only payload handler");
		return found.getFirst();
	}

	private static List<String> head(MethodNode method, int count) {
		return java.util.stream.StreamSupport.stream(method.instructions.spliterator(), false).filter(i -> i.getOpcode() >= 0).limit(count)
				.map(i -> switch (i) {
					case VarInsnNode v -> "ALOAD " + v.var;
					case TypeInsnNode t -> (t.getOpcode() == Opcodes.INSTANCEOF ? "INSTANCEOF " : "NEW ") + t.desc;
					case JumpInsnNode j -> j.getOpcode() == Opcodes.IFEQ ? "IFEQ" : String.valueOf(j.getOpcode());
					case MethodInsnNode m -> m.owner + "." + m.name;
					default -> i.getOpcode() == Opcodes.RETURN ? "RETURN" : String.valueOf(i.getOpcode());
				}).toList();
	}

	@Test void onNeoForgesBaseThePlayListenerReturnsBeforeTheRefusal() throws Exception {
		System.clearProperty(SWITCH);
		ClassNode game = game(false);
		assertTrue(FabricCommonPayloadMixinAdapter.playListenerCallsSuper(game),
				"premise: NeoForge's play listener calls super.handleCustomPayload (javap: aload_0, aload_1, invokespecial)");
		ClassNode mixin = mixin();
		MethodNode handler = refusing(mixin);
		List<String> before = head(handler, 3);
		byte[] gameBefore = StagedFabricMixinFixture.bytes(game);

		assertEquals(1, FabricCommonPayloadMixinAdapter.adapt(mixin, n -> n.equals(FabricCommonPayloadMixinAdapter.GAME_LISTENER) ? game : null));

		assertEquals(List.of("ALOAD 0", "INSTANCEOF " + FabricCommonPayloadMixinAdapter.GAME_LISTENER, "IFEQ", "RETURN"), head(handler, 4),
				"a play listener's payload goes on to NeoForge's body; nothing else of fabric-api's handler runs for it");
		assertEquals(before, head(handler, 7).subList(4, 7), "the configuration listener runs fabric-api's handler as written");
		new Analyzer<>(new BasicVerifier()).analyze(mixin.name, handler);
		assertEquals(List.of("handleCustomPayload"), MixinFit.value(MixinFit.injectorOf(handler), "method"), "the injection itself is unchanged");
		assertArrayEquals(gameBefore, StagedFabricMixinFixture.bytes(game), "the play listener is not touched");
		assertEquals(0, mixin.methods.stream().filter(m -> m != handler).filter(FabricCommonPayloadMixinAdapter::refusesOtherListeners).count(),
				"its other injector (handlePong) is not a payload handler");
	}

	/** Vanilla's play override is empty: no play payload ever reaches fabric-api's common handler, so there is nothing to do. */
	@Test void onVanillaNothingChanges() throws Exception {
		System.clearProperty(SWITCH);
		ClassNode game = game(true);
		assertFalse(FabricCommonPayloadMixinAdapter.playListenerCallsSuper(game), "premise: vanilla's override is a bare return");
		ClassNode mixin = mixin();
		byte[] before = StagedFabricMixinFixture.bytes(mixin);
		assertEquals(0, FabricCommonPayloadMixinAdapter.adapt(mixin, n -> game));
		assertArrayEquals(before, StagedFabricMixinFixture.bytes(mixin));
	}

	@Test void theOffSwitchLeavesTheHandlerAsWritten() throws Exception {
		ClassNode game = game(false);
		ClassNode mixin = mixin();
		byte[] before = StagedFabricMixinFixture.bytes(mixin);
		System.setProperty(SWITCH, "off");
		try {
			assertEquals(0, FabricCommonPayloadMixinAdapter.adapt(mixin, n -> game));
		} finally {
			System.clearProperty(SWITCH);
		}
		assertArrayEquals(before, StagedFabricMixinFixture.bytes(mixin));
	}

	/** Read by what the handler does: the same mixin under unrelated names is adapted alike; a handler that refuses nothing is not. */
	@Test void theRuleReadsWhatTheHandlerDoes() throws Exception {
		System.clearProperty(SWITCH);
		ClassNode original = mixin();
		ClassNode renamed = new ClassNode();
		original.accept(new org.objectweb.asm.commons.ClassRemapper(renamed, new org.objectweb.asm.commons.Remapper() {
			@Override public String map(String name) {
				return name.equals(MIXIN) ? "org/qzx/net/QzxListenerHooks" : name;
			}

			@Override public String mapMethodName(String owner, String name, String desc) {
				return owner.equals(MIXIN) && !name.startsWith("<") ? "qzx" + name : name;
			}
		}));
		ClassNode game = game(false);
		assertEquals(1, FabricCommonPayloadMixinAdapter.adapt(renamed, n -> game));

		ClassNode quiet = mixin();
		MethodNode handler = refusing(quiet);
		for (AbstractInsnNode insn : handler.instructions.toArray()) {
			if (insn instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW && t.desc.equals("java/lang/IllegalStateException")) t.desc = "java/lang/Object";
		}
		assertFalse(FabricCommonPayloadMixinAdapter.refusesOtherListeners(handler), "a handler that throws no IllegalStateException refuses no listener");
		assertEquals(0, FabricCommonPayloadMixinAdapter.adapt(quiet, n -> game));
	}
}
