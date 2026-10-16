package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;

/**
 * {@link NativeSliceOccurrences} on fabric-api's own block-entity removal redirects (client and dedicated server), read
 * from the staged fabric-api jar, against the staged merged {@code LevelChunk} and vanilla's as the class Fabric mods are
 * compiled against. Their slice starts at {@code createBlockEntity}; NeoForge takes the removed block entity out before
 * that call, so the slice holds no removal. The removal is found again by its operands and conditions — on
 * {@code blockEntities}, never the pending-NBT map — and the redirect's body is untouched.
 */
@org.junit.jupiter.api.parallel.ResourceLock("system-properties")
class NativeSliceOccurrencesTest {
	private static final String CHUNK = "net/minecraft/world/level/chunk/LevelChunk";
	private static final String HANDLER_DESC = "(Ljava/util/Map;Ljava/lang/Object;)Ljava/lang/Object;";

	@AfterEach void clear() {
		System.clearProperty(NativeSliceOccurrences.PROPERTY);
	}

	private static ClassNode mixin(String side) throws Exception {
		ClassNode mixin = StagedFabricMixinFixture.mixin("fabric-lifecycle-events-v1", "net/fabricmc/fabric/mixin/event/lifecycle/" + side + "/LevelChunkMixin");
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		return mixin;
	}

	private static MethodNode handler(ClassNode mixin) {
		return mixin.methods.stream().filter(m -> m.name.equals("onRemoveBlockEntity") && m.desc.equals(HANDLER_DESC)).findFirst().orElseThrow();
	}

	private static int adapt(ClassNode mixin, ClassNode merged) throws Exception {
		ClassNode vanilla = StagedFabricMixinFixture.game(CHUNK, true);
		return NativeSliceOccurrences.adapt(mixin, name -> name.equals(CHUNK) ? merged : null, (ecosystem, name) -> name.equals(CHUNK) ? vanilla : null);
	}

	@Test void theClientRemovalNamesTheBlockEntityMapsRemovalAndNeverThePendingOne() throws Exception {
		for (String side : new String[] {"client", "server"}) {
			ClassNode mixin = mixin(side), merged = StagedFabricMixinFixture.game(CHUNK, false);
			MethodNode handler = handler(mixin);
			String body = MixinInstructionFingerprint.hash(handler);
			assertEquals(1, adapt(mixin, merged), side);
			AnnotationNode injector = MixinFit.injectorOf(handler);
			assertNull(MixinFit.value(injector, "slice"), side);
			// Ordinal 0: the merged getBlockEntity takes the removed block entity out of blockEntities before it asks the pending map.
			assertEquals(0, MixinFit.value(MixinFit.atNodes(injector).getFirst(), "ordinal"), side);
			assertEquals(body, MixinInstructionFingerprint.hash(handler), side + ": the redirect's body is as written");
			assertEquals(0, adapt(mixin, merged), side + ": a point already moved is left alone");
		}
	}

	/** On vanilla's own body the slice still holds the removal: nothing is moved. */
	@Test void onTheClassItWasWrittenForNothingMoves() throws Exception {
		assertEquals(0, adapt(mixin("client"), StagedFabricMixinFixture.game(CHUNK, true)));
	}

	/** Another map (not the native operand) or a callback group leaves the point as compiled. */
	@Test void anotherMapOrAGroupLeavesThePointAlone() throws Exception {
		ClassNode renamed = StagedFabricMixinFixture.game(CHUNK, false);
		for (MethodNode method : renamed.methods)
			if (method.name.equals("getBlockEntity"))
				for (var instruction : method.instructions) if (instruction instanceof FieldInsnNode field && field.name.equals("blockEntities")) field.name = "unprovedMap";
		assertEquals(0, adapt(mixin("client"), renamed));
		ClassNode grouped = mixin("client");
		handler(grouped).visibleAnnotations.add(new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Group;"));
		assertEquals(0, adapt(grouped, StagedFabricMixinFixture.game(CHUNK, false)));
	}

	@Test void switchedOffNothingMoves() throws Exception {
		System.setProperty(NativeSliceOccurrences.PROPERTY, "off");
		assertEquals(0, adapt(mixin("client"), StagedFabricMixinFixture.game(CHUNK, false)));
	}
}
