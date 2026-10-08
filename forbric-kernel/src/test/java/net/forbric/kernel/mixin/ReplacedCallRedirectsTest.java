package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * {@link ReplacedCallRedirects} on handlers in the shape of ViaFabricPlus 5.0.2's three, against the staged merged
 * classes: each forwards the vanilla call it redirects unless an older server is targeted (here a static flag), and
 * after the move forwards the carrier's call in the same place.
 */
@ResourceLock("system-properties")
class ReplacedCallRedirectsTest {
	private static final String SCREEN = "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen";
	private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
	private static final String SHOVEL = "net/minecraft/world/item/ShovelItem";
	private static final String KEY_MAPPING = "net/minecraft/client/KeyMapping";
	private static final String KEY_EVENT = "net/minecraft/client/input/KeyEvent";
	private static final String KEY = "com/mojang/blaze3d/platform/InputConstants$Key";
	private static final String STACK = "net/minecraft/world/item/ItemStack";
	private static final String STATE = "net/minecraft/world/level/block/state/BlockState";
	private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
	private static final String MATCHES = "L" + KEY_MAPPING + ";matches(L" + KEY_EVENT + ";)Z";
	private static final String ACTIVE = "L" + KEY_MAPPING + ";isActiveAndMatches(L" + KEY + ";)Z";
	private static final String SAME = "L" + STACK + ";isSameItem(L" + STACK + ";L" + STACK + ";)Z";
	private static final String CONTINUE = "Lnet/neoforged/neoforge/common/CommonHooks;canContinueUsing(L" + STACK + ";L" + STACK + ";)Z";
	private static final String GET = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;";
	private static final String MODIFIED = "L" + STATE + ";getToolModifiedState(Lnet/minecraft/world/item/context/UseOnContext;"
			+ "Lnet/neoforged/neoforge/common/ItemAbility;Z)L" + STATE + ";";
	private static final String FLATTENABLES = "Lnet/minecraft/world/item/ShovelItem;FLATTENABLES:Ljava/util/Map;";

	@AfterEach void reset() {
		System.clearProperty(ReplacedCallRedirects.PROPERTY);
		MixinStubRebind.forget();
	}

	/** ViaFabricPlus' hotbar keys: the KeyEvent only reaches the call, so the handler takes NeoForge's key instead. */
	@Test void aHotbarKeyRedirectForwardsNeoForgesKeyCheck() throws Exception {
		ClassNode mixin = hotbar("HotbarMixin", Ecosystem.FABRIC, false);
		assertEquals(1, adapt(mixin, merged(SCREEN)));
		MethodNode handler = handler(mixin);
		assertEquals("(L" + KEY_MAPPING + ";L" + KEY + ";)Z", handler.desc);
		AnnotationNode at = StagedFabricMixinFixture.at(mixin, "redirected");
		assertEquals(ACTIVE, MixinFit.value(at, "target"));
		assertEquals(1, MixinFit.value(at, "ordinal"), "the hotbar slots' call, as in vanilla");
		assertEquals(List.of("ALOAD 1", "ALOAD 2", "INVOKEVIRTUAL " + KEY_MAPPING + ".isActiveAndMatches"), forwarding(handler, ACTIVE));
		assertEquals(0, calls(handler, MATCHES));
		assertTrue(handler.localVariables.stream().anyMatch(l -> l.index == 2 && l.desc.equals("L" + KEY + ";")), "the key's slot is named");
		verify(mixin, handler);
		assertEquals(0, adapt(mixin, merged(SCREEN)), "a moved redirect is not moved again");
	}

	/** Vanilla passed (in hand, in use); NeoForge passes (in use, in hand): the handler's own test keeps vanilla's meaning. */
	@Test void anItemUseRedirectKeepsVanillasOrderAndForwardsNeoForgesQuestion() throws Exception {
		ClassNode mixin = itemUse("ItemUseMixin", Ecosystem.FABRIC);
		assertEquals(1, adapt(mixin, merged(LIVING)));
		MethodNode handler = handler(mixin);
		assertEquals("(L" + STACK + ";L" + STACK + ";)Z", handler.desc);
		AnnotationNode at = StagedFabricMixinFixture.at(mixin, "redirected");
		assertEquals(CONTINUE, MixinFit.value(at, "target"));
		assertNull(MixinFit.value(at, "ordinal"));
		// NeoForge's call, with NeoForge's arguments in NeoForge's order.
		assertEquals(List.of("ALOAD 1", "ALOAD 2", "INVOKESTATIC net/neoforged/neoforge/common/CommonHooks.canContinueUsing"),
				forwarding(handler, CONTINUE));
		// The identity test read (a = in hand, b = in use) and still does: in hand is now slot 2, in use slot 1.
		List<String> compared = new ArrayList<>();
		for (AbstractInsnNode insn : handler.instructions) {
			if (insn.getOpcode() == Opcodes.IF_ACMPNE) compared = List.of(text(real(insn, -2)), text(real(insn, -1)));
		}
		assertEquals(List.of("ALOAD 2", "ALOAD 1"), compared);
		verify(mixin, handler);
	}

	/** ViaFabricPlus' shovel: no path on old servers; otherwise the call that asks the block, slice dropped. */
	@Test void aShovelPathRedirectForwardsTheToolModification() throws Exception {
		ClassNode mixin = shovel("ShovelMixin", false);
		assertEquals(1, adapt(mixin, merged(SHOVEL)));
		MethodNode handler = handler(mixin);
		assertEquals("(L" + STATE + ";Lnet/minecraft/world/item/context/UseOnContext;Lnet/neoforged/neoforge/common/ItemAbility;Z)L"
				+ STATE + ";", handler.desc);
		AnnotationNode redirect = MixinFit.injectorOf(handler), at = StagedFabricMixinFixture.at(mixin, "redirected");
		assertEquals(MODIFIED, MixinFit.value(at, "target"));
		assertEquals(0, MixinFit.value(at, "ordinal"), "the SHOVEL_FLATTEN call, not the campfire's");
		assertNull(MixinFit.value(redirect, "slice"));
		assertEquals(List.of("ALOAD 1", "ALOAD 2", "ALOAD 3", "ILOAD 4", "INVOKEVIRTUAL " + STATE + ".getToolModifiedState"),
				forwarding(handler, MODIFIED));
		assertEquals(5, handler.maxLocals);
		verify(mixin, handler);
	}

	@Test void aHandlerThatUsesWhatTheCarrierDropsOrForwardsTwiceIsLeftAlone() throws Exception {
		ClassNode reads = hotbar("ReadsEventMixin", Ecosystem.FABRIC, true);
		assertUntouched(reads, merged(SCREEN), "the KeyEvent is read besides the forwarding");
		ClassNode twice = hotbar("TwiceMixin", Ecosystem.FABRIC, false);
		MethodNode handler = handler(twice);
		InsnList again = new InsnList();
		again.add(new VarInsnNode(Opcodes.ALOAD, 1));
		again.add(new VarInsnNode(Opcodes.ALOAD, 2));
		again.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, KEY_MAPPING, "matches", "(L" + KEY_EVENT + ";)Z", false));
		again.add(new InsnNode(Opcodes.POP));
		handler.instructions.insert(again);
		assertUntouched(twice, merged(SCREEN), "two forwardings");
		ClassNode other = shovel("OtherReturnMixin", true);
		assertUntouched(other, merged(SHOVEL), "a return that is neither null nor the forwarded value");
	}

	/** A row moves only its listed families' mods, only on the shape it was written for, and only while switched on. */
	@Test void theFamilyTheHostAndTheSwitchDecide() throws Exception {
		assertUntouched(hotbar("NeoForgeMixin", Ecosystem.NEOFORGE, false), merged(SCREEN), "NeoForge mods call NeoForge's");
		assertEquals(1, adapt(hotbar("ForgeHotbarMixin", Ecosystem.FORGE, false), merged(SCREEN)),
				"MinecraftForge's own screen still calls matches(KeyEvent)");
		assertUntouched(itemUse("ForgeItemUseMixin", Ecosystem.FORGE), merged(LIVING), "MinecraftForge's own asks ForgeHooks");
		assertUntouched(hotbar("VanillaHostMixin", Ecosystem.FABRIC, false), vanilla(SCREEN), "vanilla's call is there");
		System.setProperty(ReplacedCallRedirects.PROPERTY, "off");
		assertUntouched(hotbar("OffMixin", Ecosystem.FABRIC, false), merged(SCREEN), "switched off");
	}

	/** The released handlers, so a ViaFabricPlus that changes one is noticed here and not in a game. */
	@Test void releasedViaFabricPlusMovesItsThreeRedirects() throws Exception {
		Path jar = Path.of("../build/random100-20260929/downloads/dmLiENU0/ViaFabricPlus-5.0.2.jar");
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(jar), jar + " absent");
		String base = "com/viaversion/viafabricplus/injection/mixin/features/";
		Object[][] cases = { { base + "v1_4_2/MixinAbstractContainerScreen", SCREEN, "disableHotbarKeys", ACTIVE },
				{ base + "v1_14_3/MixinLivingEntity", LIVING, "replaceItemStackEqualsCheck", CONTINUE },
				{ base + "v1_8/item/MixinShovelItem", SHOVEL, "disablePathAction", MODIFIED } };
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (Object[] c : cases) {
				ClassNode mixin = MixinFit.parse(zip.getInputStream(zip.getEntry(c[0] + ".class")).readAllBytes());
				MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
				assertEquals(1, adapt(mixin, merged((String) c[1])), (String) c[0]);
				MethodNode handler = StagedFabricMixinFixture.method(mixin, (String) c[2]);
				assertEquals(c[3], MixinFit.value(StagedFabricMixinFixture.at(mixin, (String) c[2]), "target"));
				verify(mixin, handler);
			}
		}
	}

	// --- the handlers ---

	/** {@code return instance.matches(event) && !Legacy.on} — or, with {@code readsEvent}, also reads the event. */
	private static ClassNode hotbar(String name, Ecosystem owner, boolean readsEvent) {
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, "redirected", "(L" + KEY_MAPPING + ";L" + KEY_EVENT + ";)Z", null, null);
		LabelNode start = new LabelNode(), no = new LabelNode(), end = new LabelNode();
		InsnList code = handler.instructions;
		code.add(start);
		if (readsEvent) {
			code.add(new VarInsnNode(Opcodes.ALOAD, 2));
			code.add(new InsnNode(Opcodes.POP));
		}
		code.add(new VarInsnNode(Opcodes.ALOAD, 1));
		code.add(new VarInsnNode(Opcodes.ALOAD, 2));
		code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, KEY_MAPPING, "matches", "(L" + KEY_EVENT + ";)Z", false));
		code.add(new JumpInsnNode(Opcodes.IFEQ, no));
		code.add(new FieldInsnNode(Opcodes.GETSTATIC, "test/redirect/Legacy", "on", "Z"));
		code.add(new JumpInsnNode(Opcodes.IFNE, no));
		code.add(new InsnNode(Opcodes.ICONST_1));
		code.add(new InsnNode(Opcodes.IRETURN));
		code.add(no);
		code.add(new InsnNode(Opcodes.ICONST_0));
		code.add(new InsnNode(Opcodes.IRETURN));
		code.add(end);
		handler.localVariables = new ArrayList<>(List.of(new LocalVariableNode("this", "Ltest/redirect/" + name + ";", null, start, end, 0),
				new LocalVariableNode("instance", "L" + KEY_MAPPING + ";", null, start, end, 1),
				new LocalVariableNode("event", "L" + KEY_EVENT + ";", null, start, end, 2)));
		handler.maxLocals = 3;
		handler.maxStack = 2;
		return mixin(name, SCREEN, handler, redirect("checkHotbarKeyPressed", MATCHES, 1, null, false), owner);
	}

	/** {@code if (Legacy.on) return a == b; return ItemStack.isSameItem(a, b);} */
	private static ClassNode itemUse(String name, Ecosystem owner) {
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, "redirected", "(L" + STACK + ";L" + STACK + ";)Z", null, null);
		LabelNode modern = new LabelNode(), differ = new LabelNode();
		InsnList code = handler.instructions;
		code.add(new FieldInsnNode(Opcodes.GETSTATIC, "test/redirect/Legacy", "on", "Z"));
		code.add(new JumpInsnNode(Opcodes.IFEQ, modern));
		code.add(new VarInsnNode(Opcodes.ALOAD, 1));
		code.add(new VarInsnNode(Opcodes.ALOAD, 2));
		code.add(new JumpInsnNode(Opcodes.IF_ACMPNE, differ));
		code.add(new InsnNode(Opcodes.ICONST_1));
		code.add(new InsnNode(Opcodes.IRETURN));
		code.add(differ);
		code.add(new InsnNode(Opcodes.ICONST_0));
		code.add(new InsnNode(Opcodes.IRETURN));
		code.add(modern);
		code.add(new VarInsnNode(Opcodes.ALOAD, 1));
		code.add(new VarInsnNode(Opcodes.ALOAD, 2));
		code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, STACK, "isSameItem", "(L" + STACK + ";L" + STACK + ";)Z", false));
		code.add(new InsnNode(Opcodes.IRETURN));
		handler.maxLocals = 3;
		handler.maxStack = 2;
		return mixin(name, LIVING, handler, redirect("updatingUsingItem", SAME, null, null, false), owner);
	}

	/** {@code if (Legacy.on) return null; return map.get(block);} — or, with {@code otherReturn}, a third value. */
	private static ClassNode shovel(String name, boolean otherReturn) {
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, "redirected", "(Ljava/util/Map;Ljava/lang/Object;)Ljava/lang/Object;", null, null);
		LabelNode modern = new LabelNode();
		InsnList code = handler.instructions;
		code.add(new FieldInsnNode(Opcodes.GETSTATIC, "test/redirect/Legacy", "on", "Z"));
		code.add(new JumpInsnNode(Opcodes.IFEQ, modern));
		code.add(otherReturn ? new LdcInsnNode("no") : new InsnNode(Opcodes.ACONST_NULL));
		code.add(new InsnNode(Opcodes.ARETURN));
		code.add(modern);
		code.add(new VarInsnNode(Opcodes.ALOAD, 1));
		code.add(new VarInsnNode(Opcodes.ALOAD, 2));
		code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true));
		code.add(new InsnNode(Opcodes.ARETURN));
		handler.maxLocals = 3;
		handler.maxStack = 2;
		return mixin(name, SHOVEL, handler, redirect("useOn", GET, 0, FLATTENABLES, true), Ecosystem.FABRIC);
	}

	private static AnnotationNode redirect(String method, String target, Integer ordinal, String slice, boolean noRemap) {
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "INVOKE", "target", target));
		if (ordinal != null) { at.values.add("ordinal"); at.values.add(ordinal); }
		if (noRemap) { at.values.add("remap"); at.values.add(false); }
		AnnotationNode redirect = new AnnotationNode(REDIRECT);
		redirect.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of(method))));
		if (slice != null) {
			AnnotationNode from = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
			from.values = new ArrayList<>(List.of("value", "FIELD", "target", slice, "opcode", Opcodes.GETSTATIC));
			AnnotationNode s = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Slice;");
			s.values = new ArrayList<>(List.of("from", from));
			redirect.values.add("slice");
			redirect.values.add(s);
		}
		redirect.values.add("at");
		redirect.values.add(at);
		return redirect;
	}

	private static ClassNode mixin(String name, String target, MethodNode handler, AnnotationNode redirect, Ecosystem owner) {
		ClassNode mixin = new ClassNode();
		mixin.version = Opcodes.V21;
		mixin.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT;
		mixin.name = "test/redirect/" + name;
		mixin.superName = "java/lang/Object";
		AnnotationNode targets = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		targets.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType(target)))));
		mixin.invisibleAnnotations = new ArrayList<>(List.of(targets));
		handler.visibleAnnotations = new ArrayList<>(List.of(redirect));
		mixin.methods.add(handler);
		if (owner != null) MixinStubRebind.noteEcosystem(mixin.name, owner);
		return mixin;
	}

	// --- helpers ---

	private static Function<String, ClassNode> merged(String owner) throws Exception {
		ClassNode node = StagedFabricMixinFixture.game(owner, false);
		return name -> name.equals(owner) ? node : null;
	}

	private static Function<String, ClassNode> vanilla(String owner) throws Exception {
		ClassNode node = StagedFabricMixinFixture.game(owner, true);
		return name -> name.equals(owner) ? node : null;
	}

	private static MethodNode handler(ClassNode mixin) {
		return mixin.methods.stream().filter(m -> MixinFit.injectorOf(m) != null).findFirst().orElseThrow();
	}

	private static void assertUntouched(ClassNode mixin, Function<String, ClassNode> targets, String why) {
		byte[] before = StagedFabricMixinFixture.bytes(mixin);
		assertEquals(0, adapt(mixin, targets), why);
		assertArrayEquals(before, StagedFabricMixinFixture.bytes(mixin), why + " — the mixin was rewritten");
	}

	/** The instructions feeding the one call of {@code member}, and the call. */
	private static List<String> forwarding(MethodNode handler, String member) {
		MixinFit.Member m = MixinFit.parseMember(member);
		for (AbstractInsnNode insn : handler.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(m.owner()) && call.name.equals(m.name())) {
				List<String> out = new ArrayList<>();
				int operands = Type.getArgumentTypes(call.desc).length + (call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
				for (int i = operands; i >= 1; i--) out.add(text(real(insn, -i)));
				out.add(text(insn));
				return out;
			}
		}
		return List.of();
	}

	private static int calls(MethodNode handler, String member) {
		MixinFit.Member m = MixinFit.parseMember(member);
		int n = 0;
		for (AbstractInsnNode insn : handler.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(m.owner()) && call.name.equals(m.name()) && call.desc.equals(m.desc())) n++;
		}
		return n;
	}

	/** The real instruction {@code offset} (negative) real instructions before {@code insn}. */
	private static AbstractInsnNode real(AbstractInsnNode insn, int offset) {
		AbstractInsnNode at = insn;
		for (int i = 0; i < -offset; i++) {
			at = at.getPrevious();
			while (at != null && at.getOpcode() < 0) at = at.getPrevious();
		}
		return at;
	}

	private static String text(AbstractInsnNode insn) {
		String op = org.objectweb.asm.util.Printer.OPCODES[insn.getOpcode()];
		if (insn instanceof VarInsnNode v) return op + " " + v.var;
		if (insn instanceof MethodInsnNode m) return op + " " + m.owner + "." + m.name;
		return op;
	}

	private static void verify(ClassNode mixin, MethodNode handler) throws Exception {
		new Analyzer<>(new BasicVerifier()).analyze(mixin.name, handler);
		ClassNode reread = MixinFit.parse(StagedFabricMixinFixture.bytes(mixin));
		new Analyzer<>(new BasicVerifier()).analyze(reread.name, reread.methods.stream()
				.filter(m -> m.name.equals(handler.name)).findFirst().orElseThrow());
	}
    private static int adapt(ClassNode mixin, java.util.function.Function<String,ClassNode> targets){return ReplacedCallRedirects.adapt(mixin,targets,NativeCallTestEvidence.staged());}

}
