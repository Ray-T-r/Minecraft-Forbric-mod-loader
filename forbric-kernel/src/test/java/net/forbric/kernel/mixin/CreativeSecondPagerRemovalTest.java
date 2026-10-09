package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.kernel.transform.CreativePagerBridgeInjector;

/**
 * A second creative pager is taken out by what touches its state, not by member names, and nothing else the mixin brings
 * goes with it. The fixture is not fabric-creative-tab-api's shape: another class and member names, an instance page
 * field set in the constructor, the key handler on a full selector calling only one page turn, a private helper the
 * pager uses, an unrelated injector and a method of the mod's own duck interface.
 */
class CreativeSecondPagerRemovalTest {
	private static final String SCREEN = "net/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen";
	private static final String MIXIN = "org/example/tabs/mixin/PagedInventoryMixin";
	private static final String DUCK = "org/example/tabs/TabCounting";
	private static final String CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
	private static final String CIR = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";

	@Test void thePagerGoesAndEveryOtherMemberStays() throws Exception {
		ClassNode mixin = pagedMixin(false);
		assertTrue(FabricCreativePagerMixinAdapter.matches(mixin));
		assertEquals(1, FabricCreativePagerMixinAdapter.adapt(mixin, name -> null));
		List<String> names = mixin.methods.stream().map(m -> m.name).toList();
		assertTrue(names.containsAll(List.of("<init>", "onNextKey", "onRenderLabels", "example$tabCount")), names.toString());
		assertFalse(names.contains("getCurrentPage") || names.contains("switchToPage") || names.contains("example$clamp"), names.toString());
		assertTrue(mixin.fields.stream().noneMatch(f -> f.name.equals("shownPage")), "the second page state is gone");
		assertEquals(List.of(CreativePagerBridgeInjector.API, DUCK), mixin.interfaces, "both contracts stay declared");
		MethodNode init = StagedFabricMixinFixture.method(mixin, "<init>");
		assertTrue(Arrays.stream(init.instructions.toArray()).noneMatch(i -> i instanceof FieldInsnNode f && f.name.equals("shownPage")));
		for (MethodNode m : mixin.methods) if (m.instructions.size() > 0) new Analyzer<>(new BasicVerifier()).analyze(mixin.name, m);
		// The key handler is untouched: it still asks the interface to turn the page, which the carrier now answers.
		MethodNode keys = StagedFabricMixinFixture.method(mixin, "onNextKey");
		assertTrue(Arrays.stream(keys.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode c && c.name.equals("switchToNextPage")));
		assertEquals(0, FabricCreativePagerMixinAdapter.adapt(mixin, name -> null), "nothing left to take out");
		// What is left still writes and reads as a class.
		ClassWriter writer = new ClassWriter(0);
		mixin.accept(writer);
		new ClassReader(writer.toByteArray()).accept(new ClassNode(), 0);
	}

	/** RED: the mod's own interface method reads the page; nothing answers it once the pager is gone, so nothing is removed. */
	@Test void aDuckMethodOnThePageStateLeavesTheMixinAlone() throws Exception {
		ClassNode mixin = pagedMixin(true);
		byte[] before = StagedFabricMixinFixture.bytes(mixin);
		assertEquals(0, FabricCreativePagerMixinAdapter.adapt(mixin, name -> null));
		assertArrayEquals(before, StagedFabricMixinFixture.bytes(mixin));
	}

	/** RED: a mixin answering the interface without a page of its own is not a second pager. */
	@Test void anInterfaceImplementationWithoutPageStateIsNotTouched() throws Exception {
		ClassNode mixin = shell(MIXIN);
		mixin.methods.add(method(Opcodes.ACC_PUBLIC, "getCurrentPage", "()I", code -> {
			code.add(new InsnNode(Opcodes.ICONST_0));
			code.add(new InsnNode(Opcodes.IRETURN));
		}));
		mixin.methods.add(method(Opcodes.ACC_PUBLIC, "switchToPage", "(I)Z", code -> {
			code.add(new InsnNode(Opcodes.ICONST_0));
			code.add(new InsnNode(Opcodes.IRETURN));
		}));
		byte[] before = StagedFabricMixinFixture.bytes(mixin);
		assertFalse(FabricCreativePagerMixinAdapter.matches(mixin));
		assertEquals(0, FabricCreativePagerMixinAdapter.adapt(mixin, name -> null));
		assertArrayEquals(before, StagedFabricMixinFixture.bytes(mixin));
	}

	/** The installed interface's own defaults count as answered: an override of one that turns the page is pager too. */
	@Test void anOverriddenInterfaceDefaultIsAnsweredByTheInterface() throws Exception {
		ClassNode mixin = pagedMixin(false);
		mixin.methods.add(method(Opcodes.ACC_PUBLIC, "switchToNextPage", "()Z", code -> {
			code.add(new VarInsnNode(Opcodes.ALOAD, 0));
			code.add(new VarInsnNode(Opcodes.ALOAD, 0));
			code.add(new FieldInsnNode(Opcodes.GETFIELD, MIXIN, "shownPage", "I"));
			code.add(new InsnNode(Opcodes.ICONST_1));
			code.add(new InsnNode(Opcodes.IADD));
			code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MIXIN, "switchToPage", "(I)Z", false));
			code.add(new InsnNode(Opcodes.IRETURN));
		}));
		ClassNode withoutInterface = copy(mixin);
		assertEquals(0, FabricCreativePagerMixinAdapter.adapt(withoutInterface, name -> null),
				"unknown to the carrier and to anything installed: declined");
		ClassNode api = new ClassNode();
		api.name = CreativePagerBridgeInjector.API;
		api.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
		api.methods.add(method(Opcodes.ACC_PUBLIC, "switchToNextPage", "()Z", code -> {
			code.add(new InsnNode(Opcodes.ICONST_0));
			code.add(new InsnNode(Opcodes.IRETURN));
		}));
		assertEquals(1, FabricCreativePagerMixinAdapter.adapt(mixin, name -> name.equals(api.name) ? api : null));
		assertTrue(mixin.methods.stream().noneMatch(m -> m.name.equals("switchToNextPage")));
		assertNotNull(StagedFabricMixinFixture.method(mixin, "onNextKey"));
	}

	private static ClassNode pagedMixin(boolean duckReadsPage) {
		ClassNode mixin = shell(MIXIN);
		mixin.interfaces.add(DUCK);
		mixin.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "shownPage", "I", null, null));
		mixin.methods.add(method(Opcodes.ACC_PUBLIC, "<init>", "()V", code -> {
			code.add(new VarInsnNode(Opcodes.ALOAD, 0));
			code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
			code.add(new VarInsnNode(Opcodes.ALOAD, 0));
			code.add(new InsnNode(Opcodes.ICONST_0));
			code.add(new FieldInsnNode(Opcodes.PUTFIELD, MIXIN, "shownPage", "I"));
			code.add(new InsnNode(Opcodes.RETURN));
		}));
		mixin.methods.add(method(Opcodes.ACC_PUBLIC, "getCurrentPage", "()I", code -> {
			code.add(new VarInsnNode(Opcodes.ALOAD, 0));
			code.add(new FieldInsnNode(Opcodes.GETFIELD, MIXIN, "shownPage", "I"));
			code.add(new InsnNode(Opcodes.IRETURN));
		}));
		mixin.methods.add(method(Opcodes.ACC_PUBLIC, "switchToPage", "(I)Z", code -> {
			code.add(new VarInsnNode(Opcodes.ALOAD, 0));
			code.add(new VarInsnNode(Opcodes.ALOAD, 0));
			code.add(new VarInsnNode(Opcodes.ILOAD, 1));
			code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, MIXIN, "example$clamp", "(I)I", false));
			code.add(new FieldInsnNode(Opcodes.PUTFIELD, MIXIN, "shownPage", "I"));
			code.add(new InsnNode(Opcodes.ICONST_1));
			code.add(new InsnNode(Opcodes.IRETURN));
		}));
		mixin.methods.add(method(Opcodes.ACC_PRIVATE, "example$clamp", "(I)I", code -> {
			code.add(new VarInsnNode(Opcodes.ILOAD, 1));
			code.add(new InsnNode(Opcodes.ICONST_0));
			code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(II)I", false));
			code.add(new InsnNode(Opcodes.IRETURN));
		}));
		// One handler, one direction, full selector: only PageDown, and it calls the interface, never the field.
		MethodNode keys = method(Opcodes.ACC_PRIVATE, "onNextKey", "(Lnet/minecraft/client/input/KeyEvent;" + CIR + ")V", code -> {
			LabelNode skip = new LabelNode();
			code.add(new VarInsnNode(Opcodes.ALOAD, 0));
			code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MIXIN, "switchToNextPage", "()Z", false));
			code.add(new JumpInsnNode(Opcodes.IFEQ, skip));
			code.add(new VarInsnNode(Opcodes.ALOAD, 2));
			code.add(new InsnNode(Opcodes.ICONST_1));
			code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false));
			code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CIR.substring(1, CIR.length() - 1), "setReturnValue", "(Ljava/lang/Object;)V", false));
			code.add(skip);
			code.add(new InsnNode(Opcodes.RETURN));
		});
		keys.visibleAnnotations = new ArrayList<>(List.of(inject("keyPressed(Lnet/minecraft/client/input/KeyEvent;)Z", "HEAD")));
		mixin.methods.add(keys);
		MethodNode labels = method(Opcodes.ACC_PRIVATE, "onRenderLabels", "(" + CI + ")V", code -> {
			code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/example/tabs/Labels", "drawn", "()V", false));
			code.add(new InsnNode(Opcodes.RETURN));
		});
		labels.visibleAnnotations = new ArrayList<>(List.of(inject("extractLabels", "TAIL")));
		mixin.methods.add(labels);
		mixin.methods.add(method(Opcodes.ACC_PUBLIC, "example$tabCount", "()I", code -> {
			if (duckReadsPage) {
				code.add(new VarInsnNode(Opcodes.ALOAD, 0));
				code.add(new FieldInsnNode(Opcodes.GETFIELD, MIXIN, "shownPage", "I"));
			} else {
				code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/example/tabs/Labels", "count", "()I", false));
			}
			code.add(new InsnNode(Opcodes.IRETURN));
		}));
		return mixin;
	}

	private static ClassNode shell(String name) {
		ClassNode mixin = new ClassNode();
		mixin.version = Opcodes.V21;
		mixin.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT;
		mixin.name = name;
		mixin.superName = "java/lang/Object";
		mixin.interfaces = new ArrayList<>(List.of(CreativePagerBridgeInjector.API));
		AnnotationNode target = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		target.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType(SCREEN)))));
		mixin.invisibleAnnotations = new ArrayList<>(List.of(target));
		return mixin;
	}

	private static AnnotationNode inject(String selector, String point) {
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", point));
		AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
		inject.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of(selector)), "at", new ArrayList<>(List.of(at))));
		return inject;
	}

	private static MethodNode method(int access, String name, String desc, java.util.function.Consumer<InsnList> body) {
		MethodNode m = new MethodNode(access, name, desc, null, null);
		body.accept(m.instructions);
		m.maxStack = 4;
		m.maxLocals = Type.getArgumentsAndReturnSizes(desc) >> 2;
		return m;
	}

	private static ClassNode copy(ClassNode node) {
		return MixinFit.parse(StagedFabricMixinFixture.bytes(node));
	}
}
