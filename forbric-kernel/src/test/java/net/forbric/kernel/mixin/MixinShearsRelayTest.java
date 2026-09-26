package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import net.forbric.api.Ecosystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * BCLib's six wraps of vanilla's is(Items.SHEARS) also answer the carrier's canPerformAction(SHEARS_*) the merge put in
 * its place, with the carrier's answer as their original; the rows are pinned to both bodies.
 */
@ResourceLock("system-properties")
class MixinShearsRelayTest {
	private static final Path BCLIB = Path.of("build/compat-inputs/sweep90/mods/bclib-26.201.2.jar");
	private static final String SHEARS = "org/betterx/bclib/mixin/common/shears/";
	private static final String HANDLER = "bclib_isShears";

	@AfterEach void reset() {
		System.clearProperty(MixinShearsRelay.PROPERTY);
		MixinStubRebind.forget();
	}

	/** A row stays true only while vanilla asks is(SHEARS) there and the merged body asks the row's carrier question instead. */
	@Test void everyRowIsAVanillaShearsCheckTheMergeReplaced() throws Exception {
		for (MixinShearsRelay.Row row : MixinShearsRelay.ROWS) {
			MethodNode vanilla = only(StagedFabricMixinFixture.game(row.target(), true), row.method());
			MethodNode merged = only(StagedFabricMixinFixture.game(row.target(), false), row.method());
			assertEquals(1, shearsChecks(vanilla), row + " on vanilla");
			assertEquals(0, shearsChecks(merged), row + ": a rebuilt base that restores is(SHEARS) makes the row wrong");
			assertEquals(1, MixinShearsRelay.carrierCalls(merged, row), row + " on the merged base");
			assertFalse(row.because().isBlank());
		}
	}

	@Test void aWrapWhoseCallIsGoneMovesToTheCarrierCall() throws Exception {
		for (String name : List.of("PumpkinBlockMixin", "TripWireBlockMixin", "SheepMixin", "SnowGolemMixin")) {
			ClassNode mixin = fabric(name);
			MixinShearsRelay.Row row = rowFor(mixin);
			ClassNode target = StagedFabricMixinFixture.game(row.target(), false);
			MixinFit.Result before = MixinFit.evaluate(StagedFabricMixinFixture.bytes(mixin), resolver(target));
			assertNotEquals(MixinFit.Verdict.FIT, before.verdict(), name + ": " + before.reason());
			assertEquals(1, MixinShearsRelay.adapt(mixin, n -> target), name);
			MethodNode outer = only(mixin, HANDLER), inner = only(mixin, HANDLER + MixinHandlerShim.INNER_SUFFIX);
			assertEquals("(Lnet/minecraft/world/item/ItemStack;" + row.type() + "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)Z", outer.desc);
			assertEquals(MixinShearsRelay.carrierTarget(row), MixinFit.value(StagedFabricMixinFixture.at(mixin, HANDLER), "target"));
			assertNull(MixinFit.injectorOf(inner), "the handler itself is a plain method now");
			assertEquals(MixinShearsRelay.HANDLER_DESC, inner.desc);
			List<String> calls = calls(outer);
			assertEquals(List.of("relay", HANDLER + MixinHandlerShim.INNER_SUFFIX), calls);
			new Analyzer<>(new BasicVerifier()).analyze(mixin.name, outer);
			MixinFit.Result after = MixinFit.evaluate(StagedFabricMixinFixture.bytes(mixin), resolver(target));
			assertEquals(MixinFit.Verdict.FIT, after.verdict(), name + ": " + after.reason());
			assertEquals(0, MixinShearsRelay.adapt(mixin, n -> target), name + ": second adaptation is a no-op");
		}
	}

	@Test void aWrapStillBoundToOtherIsCallsKeepsThemAndAnswersTheCarrierToo() throws Exception {
		for (String name : List.of("BeehiveBlockMixin", "MushroomCowMixin")) {
			ClassNode mixin = fabric(name);
			MixinShearsRelay.Row row = rowFor(mixin);
			ClassNode target = StagedFabricMixinFixture.game(row.target(), false);
			assertEquals(1, MixinShearsRelay.adapt(mixin, n -> target), name);
			assertEquals("Lnet/minecraft/world/item/ItemStack;is(Ljava/lang/Object;)Z", MixinFit.value(StagedFabricMixinFixture.at(mixin, HANDLER), "target"),
					name + ": the glass-bottle / bowl binding stays as compiled");
			MethodNode added = only(mixin, HANDLER + MixinShearsRelay.ADDED_SUFFIX);
			assertEquals(MixinShearsRelay.carrierTarget(row), MixinFit.value(StagedFabricMixinFixture.at(mixin, HANDLER + MixinShearsRelay.ADDED_SUFFIX), "target"));
			MethodNode copy = only(mixin, HANDLER + MixinShearsRelay.ADDED_SUFFIX + "$inner");
			assertNull(MixinFit.injectorOf(copy));
			assertEquals(FabricEntityMixinAnchors.bodyHash(only(mixin, HANDLER)), FabricEntityMixinAnchors.bodyHash(copy), "the same body");
			new Analyzer<>(new BasicVerifier()).analyze(mixin.name, added);
			new Analyzer<>(new BasicVerifier()).analyze(mixin.name, copy);
			assertEquals(0, MixinShearsRelay.adapt(mixin, n -> target), name + ": second adaptation is a no-op");
		}
	}

	@Test void onlyAFabricModsReviewedShapeIsRelayed() throws Exception {
		ClassNode target = StagedFabricMixinFixture.game("net/minecraft/world/level/block/PumpkinBlock", false);
		ClassNode unknown = read("PumpkinBlockMixin");
		assertEquals(0, MixinShearsRelay.adapt(unknown, n -> target), "an owner nobody recorded");
		ClassNode neo = read("PumpkinBlockMixin");
		MixinStubRebind.noteEcosystem(neo.name, Ecosystem.NEOFORGE);
		assertEquals(0, MixinShearsRelay.adapt(neo, n -> target), "a NeoForge mod was written against the carrier call");

		ClassNode ordinal = fabric("PumpkinBlockMixin");
		StagedFabricMixinFixture.at(ordinal, HANDLER).values.addAll(List.of("ordinal", 0));
		assertEquals(0, MixinShearsRelay.adapt(ordinal, n -> target), "an ordinal counts vanilla's calls");
		ClassNode grouped = fabric("PumpkinBlockMixin");
		only(grouped, HANDLER).visibleAnnotations.add(new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Group;"));
		assertEquals(0, MixinShearsRelay.adapt(grouped, n -> target));
		ClassNode sugar = fabric("PumpkinBlockMixin");
		only(sugar, HANDLER).desc = "(Lnet/minecraft/world/item/ItemStack;Ljava/lang/Object;Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;I)Z";
		assertEquals(0, MixinShearsRelay.adapt(sugar, n -> target), "a capture of the vanilla method's locals");

		ClassNode twice = StagedFabricMixinFixture.game("net/minecraft/world/level/block/PumpkinBlock", false);
		MethodNode body = only(twice, "useItemOn");
		for (AbstractInsnNode insn : body.instructions) {
			if (insn instanceof MethodInsnNode c && c.name.equals("canPerformAction")) {
				body.instructions.insert(insn, new InsnNode(Opcodes.POP));
				body.instructions.insert(insn, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, c.owner, c.name, c.desc, false));
				body.instructions.insert(insn, new FieldInsnNode(Opcodes.GETSTATIC, "net/neoforged/neoforge/common/ItemAbilities", "SHEARS_CARVE",
						"Lnet/neoforged/neoforge/common/ItemAbility;"));
				body.instructions.insert(insn, new VarInsnNode(Opcodes.ALOAD, 1));
				break;
			}
		}
		assertEquals(0, MixinShearsRelay.adapt(fabric("PumpkinBlockMixin"), n -> twice), "two carrier questions: which one is vanilla's is a guess");

		System.setProperty(MixinShearsRelay.PROPERTY, "off");
		assertEquals(0, MixinShearsRelay.adapt(fabric("PumpkinBlockMixin"), n -> target));
	}

	private static List<String> calls(MethodNode method) {
		List<String> calls = new java.util.ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode c) calls.add(c.name);
		return calls;
	}

	private static int shearsChecks(MethodNode method) {
		int checks = 0;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode c && c.name.equals("is") && c.desc.equals("(Ljava/lang/Object;)Z")
					&& c.getPrevious() instanceof FieldInsnNode f && f.name.equals("SHEARS") && f.owner.equals("net/minecraft/world/item/Items")) checks++;
		}
		return checks;
	}

	private static MixinShearsRelay.Row rowFor(ClassNode mixin) {
		String target = MixinOverloadPin.targetsOf(mixin).getFirst();
		return MixinShearsRelay.ROWS.stream().filter(r -> r.target().equals(target)).findFirst().orElseThrow();
	}

	private static java.util.function.Function<String, byte[]> resolver(ClassNode target) {
		byte[] bytes = StagedFabricMixinFixture.bytes(target);
		return file -> file.equals(target.name + ".class") ? bytes : null;
	}

	private static ClassNode fabric(String name) throws Exception {
		ClassNode mixin = read(name);
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		return mixin;
	}

	private static ClassNode read(String name) throws Exception {
		Assumptions.assumeTrue(Files.isRegularFile(BCLIB), "sweep pack absent");
		try (ZipFile zip = new ZipFile(BCLIB.toFile())) {
			return MixinFit.parse(zip.getInputStream(zip.getEntry(SHEARS + name + ".class")).readAllBytes());
		}
	}

	private static MethodNode only(ClassNode node, String name) {
		List<MethodNode> found = node.methods.stream().filter(m -> m.name.equals(name)).toList();
		assertEquals(1, found.size(), name + " in " + node.name);
		return found.getFirst();
	}
}
