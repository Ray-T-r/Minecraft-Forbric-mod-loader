package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * fabric-item-api's real {@code ItemStackMixin.hookDamage} against the real genuine and vanilla {@code ItemStack}: vanilla's
 * {@code hurtAndBreak(int, LivingEntity, EquipmentSlot)} calls the {@code ServerPlayer} overload the wrap names, NeoForge's
 * calls its {@code LivingEntity} overload, so the wrap attached nowhere and a Fabric item's CustomDamageHandler never ran.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class FabricItemDamageMixinAdapterTest {
	private static final String MIXIN = "net/fabricmc/fabric/mixin/item/ItemStackMixin";
	private static final String SWITCH = "forbric.itemDamageHandler";

	private static ClassNode mixin() throws Exception {
		return StagedFabricMixinFixture.mixin("fabric-item-api-v1", MIXIN);
	}

	private static ClassNode stack(boolean vanilla) throws Exception {
		return StagedFabricMixinFixture.game(FabricItemDamageMixinAdapter.STACK, vanilla);
	}

	private static MethodNode host(ClassNode stack) {
		return stack.methods.stream().filter(m -> FabricItemDamageMixinAdapter.HOST.equals(m.name + m.desc)).findFirst().orElseThrow();
	}

	private static MethodNode named(ClassNode c, String prefix) {
		List<MethodNode> found = c.methods.stream().filter(m -> m.name.startsWith(prefix)).toList();
		assertEquals(1, found.size(), prefix + ": " + found.stream().map(m -> m.name).toList());
		return found.getFirst();
	}

	@Test void onNeoForgesBaseTheWrapMovesToTheLivingEntityOverload() throws Exception {
		System.clearProperty(SWITCH);
		ClassNode stack = stack(false);
		MethodNode host = host(stack);
		assertEquals(List.of(0, 1), List.of(FabricItemDamageMixinAdapter.calls(host, FabricItemDamageMixinAdapter.VANILLA_CALL),
				FabricItemDamageMixinAdapter.calls(host, FabricItemDamageMixinAdapter.BASE_CALL)),
				"premise: NeoForge's hurtAndBreak(int, LivingEntity, EquipmentSlot) calls its LivingEntity overload, never the ServerPlayer one");
		ClassNode mixin = mixin();
		MethodNode original = named(mixin, "hookDamage");
		String innerDesc = original.desc;
		assertEquals(1, FabricItemDamageMixinAdapter.adapt(mixin, n -> n.equals(FabricItemDamageMixinAdapter.STACK) ? stack : null));

		MethodNode outer = mixin.methods.stream().filter(m -> m.name.equals("hookDamage")).findFirst().orElseThrow();
		MethodNode inner = named(mixin, "hookDamage" + MixinHandlerShim.INNER_SUFFIX);
		AnnotationNode wrap = MixinFit.injectorOf(outer);
		assertEquals("L" + FabricItemDamageMixinAdapter.STACK + ";hurtAndBreak" + FabricItemDamageMixinAdapter.BASE_CALL,
				MixinFit.value(MixinFit.atNodes(wrap).getFirst(), "target"), "NeoForge's call");
		assertSame(host, MixinTargetSelectors.one(outer, stack), "the same host method");
		assertEquals("L" + FabricItemDamageMixinAdapter.LIVING + ";", Type.getArgumentTypes(outer.desc)[3].getDescriptor(), "NeoForge's operand");
		assertTrue(MixinFit.sugar(outer, 6) && MixinFit.sugar(outer, 7), "the entity and slot @Locals stay with the injector");
		assertFalse(MixinFit.sugar(outer, 3));
		assertNull(MixinFit.injectorOf(inner), "the released body runs only from the wrap");
		assertEquals(innerDesc, inner.desc, "the released body is called as written");
		List<String> calls = new java.util.ArrayList<>();
		for (var insn : outer.instructions) if (insn instanceof MethodInsnNode call) calls.add(call.name);
		assertEquals(List.of("playerOf", "playerOf", "relay", inner.name), calls,
				"the entity as vanilla passed it, an Operation that gives NeoForge the entity back, then the released body");
		for (MethodNode m : List.of(outer, inner)) new Analyzer<>(new BasicVerifier()).analyze(mixin.name, m);
		assertEquals(0, FabricItemDamageMixinAdapter.adapt(mixin, n -> stack), "idempotence");
	}

	/** Vanilla's host makes the call the wrap names: nothing to move. */
	@Test void onVanillaNothingChanges() throws Exception {
		System.clearProperty(SWITCH);
		ClassNode stack = stack(true);
		assertEquals(1, FabricItemDamageMixinAdapter.calls(host(stack), FabricItemDamageMixinAdapter.VANILLA_CALL), "premise");
		ClassNode mixin = mixin();
		byte[] before = StagedFabricMixinFixture.bytes(mixin);
		assertEquals(0, FabricItemDamageMixinAdapter.adapt(mixin, n -> stack));
		assertArrayEquals(before, StagedFabricMixinFixture.bytes(mixin));
	}

	@Test void theOffSwitchLeavesTheHandlerAsWritten() throws Exception {
		ClassNode stack = stack(false);
		ClassNode mixin = mixin();
		byte[] before = StagedFabricMixinFixture.bytes(mixin);
		System.setProperty(SWITCH, "off");
		try {
			assertEquals(0, FabricItemDamageMixinAdapter.adapt(mixin, n -> stack));
		} finally {
			System.clearProperty(SWITCH);
		}
		assertArrayEquals(before, StagedFabricMixinFixture.bytes(mixin));
	}

	/** Another occurrence, or a handler that reads more than the call's operands without sugar, is not the reviewed shape. */
	@Test void anotherShapeIsLeftAsWritten() throws Exception {
		System.clearProperty(SWITCH);
		ClassNode stack = stack(false);
		ClassNode ordinal = mixin();
		MixinPlayerWorldCallbackAdapter.set(MixinFit.atNodes(MixinFit.injectorOf(named(ordinal, "hookDamage"))).getFirst(), "ordinal", 0);
		byte[] before = StagedFabricMixinFixture.bytes(ordinal);
		assertEquals(0, FabricItemDamageMixinAdapter.adapt(ordinal, n -> stack), "an ordinal");
		assertArrayEquals(before, StagedFabricMixinFixture.bytes(ordinal));
		ClassNode unsugared = mixin();
		MethodNode handler = named(unsugared, "hookDamage");
		handler.invisibleParameterAnnotations[6] = null;
		assertEquals(0, FabricItemDamageMixinAdapter.adapt(unsugared, n -> stack), "a trailing parameter that is not sugar");
		ClassNode grouped = mixin();
		named(grouped, "hookDamage").visibleAnnotations.add(new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Group;"));
		assertEquals(0, FabricItemDamageMixinAdapter.adapt(grouped, n -> stack), "a group");
		assertEquals(0, (Opcodes.ACC_STATIC & named(mixin(), "hookDamage").access), "premise: the released handler is an instance method");
	}
}
