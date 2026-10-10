/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

import net.forbric.api.Ecosystem;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.mixin.MixinCallbackSelectorSpellingTest.Contract;

/**
 * The released callbacks every callback adapter was built from, renamed, with every {@code @At} target written another
 * way Mixin resolves to the same member: with whitespace (Mixin's MemberInfo strips it), with the owner dotted, and —
 * where the adapter reads it in the method the mod was compiled against — without an owner. Each adapter must restore
 * exactly as many callbacks as for the original spelling. A target without an owner names the member only where that
 * method calls no other owner's member of that name and descriptor; without the method, it names nothing.
 */
class MixinCallbackPointSpellingTest {
	private static final BiFunction<Ecosystem, String, ClassNode> NATIVE = (family, name) -> CreateInjectionAdaptersTest.nativeTarget(name);

	/** Each {@code @At} target spelled {@code Lowner;name(desc)} (or {@code :desc}), respelled by {@code respell(member)}. */
	private static boolean respell(ClassNode mixin, Function<MixinFit.Member, String> respell) {
		boolean changed = false;
		for (MethodNode method : mixin.methods) {
			AnnotationNode injector = MixinFit.injectorOf(method);
			if (injector == null) continue;
			for (AnnotationNode at : MixinFit.atNodes(injector)) {
				String target = MixinFit.asString(MixinFit.value(at, "target"));
				MixinFit.Member member = MixinFit.parseMember(target);
				if (target == null || !target.startsWith("L") || member == null || member.owner() == null || member.desc() == null) continue;
				MixinPlayerWorldCallbackAdapter.set(at, "target", respell.apply(member));
				changed = true;
			}
		}
		return changed;
	}

	private static String separator(MixinFit.Member member) { return member.desc().startsWith("(") ? "" : ":"; }

	static final Map<String, Function<MixinFit.Member, String>> SAME_MEMBER = new LinkedHashMap<>();
	static {
		SAME_MEMBER.put("with whitespace", m -> "L" + m.owner() + "; " + m.name() + " " + separator(m) + m.desc());
		SAME_MEMBER.put("dotted owner", m -> m.owner().replace('/', '.') + "." + m.name() + separator(m) + m.desc());
	}

	@TestFactory Stream<DynamicTest> renamedCallbacksWithTheirPointsWrittenAnotherWayAreRestoredTheSame() {
		return MixinCallbackSelectorSpellingTest.contracts().stream().flatMap(contract -> SAME_MEMBER.entrySet().stream()
				.map(form -> DynamicTest.dynamicTest(contract.id() + " / " + form.getKey(), () -> run(contract, form.getValue()))));
	}

	private static Contract create(String id, String entry, MixinCallbackSelectorSpellingTest.Adapter adapter, int count) {
		return new Contract(id, () -> CreateGuestMixinFixture.mixin("com/zurrtum/create/" + entry), adapter, count);
	}

	/**
	 * The released callbacks again, each given to its adapter with the class it was compiled against at hand (the
	 * production default), where a target without an owner is read: the counts of
	 * {@link MixinCallbackSelectorSpellingTest#contracts}.
	 */
	static List<Contract> withNativeBodies() {
		List<Contract> contracts = new ArrayList<>();
		Map<String, Integer> carriers = Map.of("client/mixin/ClientPacketListenerMixin", 1, "mixin/LevelChunkMixin", 1,
				"client/mixin/EntityFluidInteractionMixin", 2, "client/mixin/ModelManagerMixin", 1, "client/mixin/LoadBlockModelMixin", 1,
				"mixin/PersistentEntitySectionManagerCallbackMixin", 1, "mixin/ItemStackMixin", 2);
		carriers.forEach((entry, count) -> contracts.add(create("carrier " + entry, entry, (mixin, targets) -> MixinCarrierCallbackAdapters.adapt(mixin, targets, NATIVE), count)));
		Map.of("mixin/SignalGetterMixin", 1, "mixin/EntityMixin", 1, "client/mixin/MultiPlayerGameModeMixin", 2, "mixin/ServerPlayerGameModeMixin", 1).forEach((entry, count) ->
				contracts.add(create("interaction " + entry, entry, (mixin, targets) -> MixinBlockInteractionAdapters.adapt(mixin, targets, NATIVE), count)));
		contracts.add(create("breathing", "mixin/LivingEntityMixin", (mixin, targets) -> MixinBreathingCallbackAdapter.adapt(mixin, targets, NATIVE), 2));
		contracts.add(create("living step sound", "mixin/LivingEntityMixin", (mixin, targets) -> MixinEntitySoundCallbackAdapter.adapt(mixin, targets, NATIVE), 1));
		contracts.add(create("entity step sound", "mixin/EntityMixin", (mixin, targets) -> MixinEntitySoundCallbackAdapter.adapt(mixin, targets, NATIVE), 1));
		contracts.add(create("hud", "client/mixin/HudMixin", (mixin, targets) -> MixinHudContextAdapter.adapt(mixin, targets, NATIVE), 1));
		contracts.add(create("structure", "mixin/StructureTemplateMixin", (mixin, targets) -> MixinStructurePlacementAdapter.adapt(mixin, targets, NATIVE), 3));
		for (Contract contract : MixinCallbackSelectorSpellingTest.contracts())
			// The camera roll is handed its class already; the sprite loader reads its point in the body it moves to.
			if (contract.id().equals("camera roll") || contract.id().equals("sprite loader")) contracts.add(contract);
		return contracts;
	}

	/**
	 * Every target written without its owner where, in the vanilla method its handler binds, no other owner's member has
	 * that name and descriptor — there Mixin selects the same instructions. Elsewhere dropping the owner makes another
	 * callback, not another spelling ({@link #aPointWithoutItsOwnerThatSelectsMoreIsAnotherCallback}).
	 */
	@TestFactory Stream<DynamicTest> aPointWithoutItsOwnerIsReadInTheMethodTheModWasCompiledAgainst() {
		return withNativeBodies().stream().map(contract -> DynamicTest.dynamicTest(contract.id() + " / no owner", () -> {
			ClassNode mixin = MixinCallbackSelectorSpellingTest.unrelatedNames(contract.source().read());
			ClassNode vanilla = CreateInjectionAdaptersTest.nativeTarget(MixinFit.mixinTargets(mixin).getFirst());
			for (MethodNode method : mixin.methods) {
				AnnotationNode injector = MixinFit.injectorOf(method);
				MethodNode bound = injector == null || vanilla == null ? null : MixinTargetSelectors.one(method, vanilla);
				if (bound == null) continue;
				for (AnnotationNode at : MixinFit.atNodes(injector)) {
					String target = MixinFit.asString(MixinFit.value(at, "target"));
					MixinFit.Member member = MixinFit.parseMember(target);
					if (target == null || !target.startsWith("L") || member == null || member.owner() == null || member.desc() == null) continue;
					if (onlyOwner(bound, member)) MixinPlayerWorldCallbackAdapter.set(at, "target", member.name() + separator(member) + member.desc());
				}
			}
			assertEquals(contract.count(), contract.adapter().apply(mixin, MixinCallbackSelectorSpellingTest::target), contract.id());
			CarpetMixinAdapterTest.verify(mixin);
			assertEquals(0, contract.adapter().apply(mixin, MixinCallbackSelectorSpellingTest::target), "idempotence");
		}));
	}

	/** Whether {@code method} accesses {@code member} at least once and no other owner's member of its name and descriptor. */
	private static boolean onlyOwner(MethodNode method, MixinFit.Member member) {
		int same = 0;
		for (AbstractInsnNode insn : method.instructions) {
			String owner = insn instanceof MethodInsnNode call && call.name.equals(member.name()) && call.desc.equals(member.desc()) ? call.owner
					: insn instanceof FieldInsnNode field && field.name.equals(member.name()) && field.desc.equals(member.desc()) ? field.owner : null;
			if (owner == null) continue;
			if (!owner.equals(member.owner())) return false;
			same++;
		}
		return same > 0;
	}

	/**
	 * Create's HUD wrap with its point written without the owner, judged against a vanilla {@code Hud} whose
	 * {@code extractHotbarAndDecorations} also makes another class's call of that name and descriptor: there the point
	 * is both calls, a callback the mod did not write, and it is left exactly as written. Against vanilla's own method
	 * it is the one call, and moves.
	 */
	@Test void aPointWithoutItsOwnerThatSelectsMoreIsAnotherCallback() throws Exception {
		TestFixtures.requireFiles(Fixture.MC_LIBRARIES, "vanilla Minecraft 26.2", TestFixtures.vanillaJar());
		TestFixtures.requireFiles(Fixture.THIRD_PARTY, "Create Fly", java.nio.file.Path.of(System.getProperty("forbric.createFlyJar", "build/compat-inputs/create-fly/create-fly.jar")));
		String hud = "net/minecraft/client/gui/Hud", gui = "net/minecraft/client/gui/Gui";
		ClassNode vanilla = CreateInjectionAdaptersTest.nativeTarget(hud), crowded = new ClassNode();
		vanilla.accept(crowded);
		MethodNode host = crowded.methods.stream().filter(m -> m.name.equals("extractHotbarAndDecorations")).findFirst().orElseThrow();
		MethodInsnNode call = null;
		for (AbstractInsnNode insn : host.instructions) if (insn instanceof MethodInsnNode m && m.name.equals("nextContextualInfoState")) call = m;
		assertNotNull(call);
		InsnList other = new InsnList();
		other.add(new InsnNode(Opcodes.ACONST_NULL));
		other.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, gui, call.name, call.desc, false));
		other.add(new InsnNode(Opcodes.POP));
		host.instructions.insert(other);
		for (ClassNode against : List.of(vanilla, crowded)) {
			ClassNode mixin = MixinCallbackSelectorSpellingTest.unrelatedNames(CreateGuestMixinFixture.mixin("com/zurrtum/create/client/mixin/HudMixin"));
			for (MethodNode method : mixin.methods) {
				AnnotationNode injector = MixinFit.injectorOf(method);
				if (injector == null) continue;
				for (AnnotationNode at : MixinFit.atNodes(injector)) {
					MixinFit.Member member = MixinFit.parseMember(MixinFit.asString(MixinFit.value(at, "target")));
					if (member != null && member.name().equals(call.name)) MixinPlayerWorldCallbackAdapter.set(at, "target", member.name() + member.desc());
				}
			}
			byte[] before = CarpetMixinAdapterTest.bytes(mixin);
			int adapted = MixinHudContextAdapter.adapt(mixin, MixinCallbackSelectorSpellingTest::target, (family, name) -> name.equals(hud) ? against : null);
			if (against == vanilla) { assertEquals(1, adapted); continue; }
			assertEquals(0, adapted);
			assertArrayEquals(before, CarpetMixinAdapterTest.bytes(mixin));
		}
	}

	private static void run(Contract contract, Function<MixinFit.Member, String> form) throws Exception {
		ClassNode mixin = MixinCallbackSelectorSpellingTest.unrelatedNames(contract.source().read());
		respell(mixin, form);
		assertEquals(contract.count(), contract.adapter().apply(mixin, MixinCallbackSelectorSpellingTest::target), contract.id());
		CarpetMixinAdapterTest.verify(mixin);
		assertEquals(0, contract.adapter().apply(mixin, MixinCallbackSelectorSpellingTest::target), "idempotence");
	}

	// ---- what a target without an owner names ----------------------------------------------------------------------

	private static final String GOAT = "org/example/pasture/Goat", SHEEP = "org/example/pasture/Sheep";

	@Test void aTargetWithoutAnOwnerNamesTheMemberOnlyWhereTheBodyCallsNoOtherOwnersMemberOfThatName() {
		AnnotationNode bleat = CallbackSourceFixture.at("INVOKE", "bleat(I)V"), any = CallbackSourceFixture.at("INVOKE", "L" + GOAT + ";bleat");
		String member = "L" + GOAT + ";bleat(I)V";
		assertTrue(MixinCallbackShape.names(bleat, member, body(GOAT)), "the goat's call alone");
		assertTrue(MixinCallbackShape.names(any, member, body(GOAT)), "no descriptor: the one overload called");
		assertFalse(MixinCallbackShape.names(bleat, member, body(GOAT, SHEEP)), "the sheep's call too: another member");
		assertFalse(MixinCallbackShape.names(bleat, member, body()), "a body that makes no such call says nothing");
		assertFalse(MixinCallbackShape.names(bleat, member, null), "without the body nothing says which owner");
		assertTrue(MixinCallbackShape.names(CallbackSourceFixture.at("INVOKE", " org.example.pasture.Goat . bleat ( I ) V "), member, null),
				"whitespace and a dotted owner need no body");
		assertFalse(MixinCallbackShape.names(CallbackSourceFixture.at("INVOKE", "L" + SHEEP + ";bleat(I)V"), member, body(GOAT, SHEEP)), "another owner");
		assertFalse(MixinCallbackShape.names(CallbackSourceFixture.at("INVOKE", "L" + GOAT + ";bleat(J)V"), member, body(GOAT)), "another overload");
	}

	/** A method calling {@code bleat(I)V} once on each of {@code owners}. */
	private static MethodNode body(String... owners) {
		MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "graze", "()V", null, null);
		for (String owner : owners) {
			method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
			method.instructions.add(new InsnNode(Opcodes.ICONST_1));
			method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "bleat", "(I)V", false));
		}
		method.instructions.add(new InsnNode(Opcodes.RETURN));
		return method;
	}
}
