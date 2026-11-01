package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * MixinFluidReactionAdapter's HEAD wrap on the released HEAD handlers of {@code shouldSpreadLiquid} — fabric-block-api's
 * flow veto (fabric-api 0.161.0) and Create Fly's fluid reaction — against NeoForge's staged liquid block, whose
 * placement asks vanilla's method and whose neighbour change asks NeoForge's registry instead. On the merged base
 * {@code NativeEntryRelay} served these handlers; on NeoForge's base it planned nothing, so without this wrap both run on
 * placement alone.
 */
@ResourceLock("system-properties")
class MixinFluidReactionHeadTest {
	static final Path CREATE_FLY = Path.of("build/compat-inputs/create-fly/create-fly.jar");
	/** fabric-block-api 3.1.0, the first with the flow veto; the pinned fabric-api (0.155.2, block-api 3.0.3) predates it. */
	static final Path FABRIC_API = Path.of("build/compat-inputs/sweep90-nowover/mods/fabric-api-0.161.0+26.2.jar");
	static final String FABRIC_MIXIN = "net/fabricmc/fabric/mixin/block/LiquidBlockMixin";
	static final String CREATE_MIXIN = "com/zurrtum/create/mixin/LiquidBlockMixin";

	static ClassNode fabric() throws Exception {
		return node(released(), Ecosystem.FABRIC);
	}

	static byte[] released() throws Exception {
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(FABRIC_API), "real fixture required: " + FABRIC_API);
		byte[] bytes = MixinFitStagedTest.readFromNestedJar(FABRIC_API, "fabric-block-api-v1-", FABRIC_MIXIN + ".class");
		assertNotNull(bytes, FABRIC_MIXIN + " absent from the nested fabric-block-api-v1 of " + FABRIC_API);
		return bytes;
	}

	static ClassNode create() throws Exception {
		ClassNode c = CarpetMixinAdapterTest.from(Fixture.THIRD_PARTY, CREATE_FLY, CREATE_MIXIN);
		MixinStubRebind.noteEcosystem(c.name, Ecosystem.FABRIC);
		return c;
	}

	static ClassNode node(byte[] bytes, Ecosystem family) {
		ClassNode c = new ClassNode();
		new ClassReader(bytes).accept(c, 0);
		MixinStubRebind.noteEcosystem(c.name, family);
		return c;
	}

	static int adapt(ClassNode c) {
		return MixinFluidReactionAdapter.adapt(c, CarpetMixinAdapterTest::target);
	}

	/** The handler keeps its HEAD injector (placement), and the wrap runs its copy, then — unless it cancelled — the registry. */
	static void assertWrapped(ClassNode c) throws Exception {
		CarpetMixinAdapterTest.assertTheBaseSplitsPlacementFromANeighbourChange();
		MethodNode kept = CarpetMixinAdapterTest.method(c, "shouldSpreadLiquid");
		assertEquals("HEAD", MixinFit.asString(MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(kept)).getFirst(), "value")), "premise: a HEAD handler");
		MethodNode wrap = CarpetMixinAdapterTest.assertKeptAndCopied(c, "shouldSpreadLiquid");
		int reaches = CarpetMixinAdapterTest.at(wrap, call -> call.owner.equals(MixinFluidReactionAdapter.RUNTIME));
		int rule = CarpetMixinAdapterTest.at(wrap, call -> call.name.equals("shouldSpreadLiquid$forbricOriginal"));
		int cancelled = CarpetMixinAdapterTest.at(wrap, call -> call.name.equals("isCancelled"));
		int registry = CarpetMixinAdapterTest.at(wrap, call -> call.name.equals("call"));
		assertEquals(-1, reaches, "a HEAD handler runs on every neighbour change, as vanilla enters the method on every one");
		assertTrue(0 <= rule && rule < cancelled && cancelled < registry, "the handler, its veto, then the registry: " + List.of(rule, cancelled, registry));
		AnnotationNode inject = MixinFit.injectorOf(wrap);
		assertTrue(inject.desc.endsWith("/WrapOperation;"), inject.desc);
		CarpetMixinAdapterTest.verify(c);
		assertEquals(0, adapt(c), "a second pass leaves the wrapped mixin alone");
	}

	@Test void fabricBlockApisFlowVetoAlsoRunsOnANeighbourChange() throws Exception {
		ClassNode c = fabric();
		assertEquals(1, adapt(c));
		assertWrapped(c);
	}

	@Test void createFlysFluidReactionAlsoRunsOnANeighbourChange() throws Exception {
		ClassNode c = create();
		assertEquals(1, adapt(c));
		assertWrapped(c);
	}

	/** With the HEAD switch off (or the whole neighbour-change wrap off) both handlers stay as compiled: placement only. */
	@Test void switchedOffTheHeadHandlersStayAsCompiled() throws Exception {
		for (String property : List.of(MixinFluidReactionAdapter.HEAD_PROPERTY, MixinFluidReactionAdapter.NEIGHBOUR_PROPERTY)) {
			String old = System.setProperty(property, "off");
			try {
				for (ClassNode c : List.of(fabric(), create())) {
					byte[] before = CarpetMixinAdapterTest.bytes(c);
					assertEquals(0, adapt(c), property + " " + c.name);
					assertArrayEquals(before, CarpetMixinAdapterTest.bytes(c), property + " " + c.name);
				}
			} finally {
				if (old == null) System.clearProperty(property);
				else System.setProperty(property, old);
			}
		}
	}

	/** Only a mixin known to be a Fabric mod's: recorded as NeoForge's, or with no family, the released bytes stay. */
	@Test void onlyAFabricMixinGetsTheHeadWrap() throws Exception {
		byte[] released = released();
		for (Ecosystem family : new Ecosystem[] {Ecosystem.NEOFORGE, null}) {
			ClassNode c = node(released, Ecosystem.FABRIC);
			c.name = "forbric/test/fluidhead/" + family + "/LiquidBlockMixin";
			MixinStubRebind.noteEcosystem(c.name, family);
			byte[] before = CarpetMixinAdapterTest.bytes(c);
			assertEquals(0, adapt(c), String.valueOf(family));
			assertArrayEquals(before, CarpetMixinAdapterTest.bytes(c), String.valueOf(family));
		}
	}

	/** A mixin with a TAIL or reaction-point handler keeps that arm; a HEAD handler beside it is not wrapped too. */
	@Test void aTailOrReactionPointHandlerKeepsItsOwnArm() throws Exception {
		ClassNode c = CarpetMixinAdapterTest.mixin(CarpetMixinAdapterTest.NAMES.get(4));
		MethodNode head = MixinShearsRelay.copyOf(CarpetMixinAdapterTest.method(fabric(), "shouldSpreadLiquid"), "extraHead");
		head.access = Opcodes.ACC_PRIVATE;
		c.methods.add(head);
		assertEquals(1, adapt(c));
		assertNotNull(CarpetMixinAdapterTest.method(c, "receiveFluidToDeepslate$forbricOriginal"));
		assertNull(CarpetMixinAdapterTest.method(c, "extraHead$forbricOriginal"));
		MethodNode wrap = CarpetMixinAdapterTest.method(c, "forbric$neighbourFluidReaction");
		assertTrue(CarpetMixinAdapterTest.at(wrap, call -> call.owner.equals(MixinFluidReactionAdapter.RUNTIME)) >= 0, "the reaction-point wrap");
		for (var insn : wrap.instructions) if (insn instanceof MethodInsnNode call) assertNotEquals("extraHead$forbricOriginal", call.name);
	}
}
