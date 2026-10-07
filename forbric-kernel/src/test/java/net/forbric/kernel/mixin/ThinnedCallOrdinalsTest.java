/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/** ThinnedCallOrdinals' row for ViaFabricPlus' 1.12.2 placement hook, on the real jars and on its own. */
class ThinnedCallOrdinalsTest {
	private static final Path MERGED = TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final Path VANILLA = TestFixtures.vanillaJar();
	private static final Path VIAFABRICPLUS = Path.of("../build/lb-vfp/jars/ViaFabricPlus-5.0.2.jar");
	private static final ThinnedCallOrdinals.Site SITE = ThinnedCallOrdinals.SITES.getFirst();
	private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	private static final String SLICE = "Lorg/spongepowered/asm/mixin/injection/Slice;";

	@AfterEach void reset() {
		System.clearProperty(ThinnedCallOrdinals.PROPERTY);
		MixinStubRebind.forget();
	}

	/**
	 * The row read off the jars themselves: vanilla makes the call as often as the row has entries and only the ones it
	 * keeps are followed by the row's next call; the merged body makes it as often as the row keeps, each followed by it.
	 */
	@Test void theRowIsWhatVanillaAndTheMergedBaseSay() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED), "actual game required");
		TestFixtures.require(Fixture.MC_LIBRARIES, Files.isRegularFile(VANILLA), "vanilla jar required");
		MethodNode vanilla = method(read(VANILLA, SITE.owner()), SITE.method());
		List<Boolean> followed = new ArrayList<>();
		for (AbstractInsnNode insn = vanilla.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode call && member(call).equals(SITE.call())) followed.add(member(nextCall(insn)).equals(SITE.next()));
		}
		List<Boolean> expected = new ArrayList<>();
		for (int ordinal : SITE.ordinals()) expected.add(ordinal >= 0);
		assertEquals(expected, followed, "vanilla's occurrences, and which are followed by " + SITE.next());
		assertTrue(ThinnedCallOrdinals.hostHasTheSiteShape(method(read(MERGED, SITE.owner()), SITE.method()), SITE));
	}

	@Test void releasedViaFabricPlusCountsOnTheMergedBody() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED), "actual game required");
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(VIAFABRICPLUS), VIAFABRICPLUS + " absent");
		ClassNode target = read(MERGED, SITE.owner());
		ClassNode mixin = read(VIAFABRICPLUS, "com/viaversion/viafabricplus/injection/mixin/features/v1_12_2/MixinMultiPlayerGameMode");
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		assertEquals(List.of(2), ordinals(mixin, "interactBlock1_12_2"));
		assertEquals(1, ThinnedCallOrdinals.adapt(mixin, name -> target));
		assertEquals(List.of(0), ordinals(mixin, "interactBlock1_12_2"));
	}

	@Test void whatItLeavesAlone() {
		ClassNode target = host(1);
		// A NeoForge mod was compiled against the merged count.
		ClassNode neo = mixin("com/example/neo/PlaceMixin", 0);
		MixinStubRebind.noteEcosystem(neo.name, Ecosystem.NEOFORGE);
		assertEquals(0, ThinnedCallOrdinals.adapt(neo, name -> target));
		// An occurrence the carrier replaced has no merged counterpart.
		ClassNode replaced = mixin("com/example/fabric/ReplacedMixin", 1);
		assertEquals(0, ThinnedCallOrdinals.adapt(replaced, name -> target));
		assertEquals(List.of(1), ordinals(replaced, "hook"));
		// A slice counts within itself.
		ClassNode sliced = mixin("com/example/fabric/SlicedMixin", 2);
		MixinFit.injectorOf(sliced.methods.getFirst()).values.addAll(List.of("slice", new AnnotationNode(SLICE)));
		assertEquals(0, ThinnedCallOrdinals.adapt(sliced, name -> target));
		// A host whose count is not the row's: the row does not describe it.
		assertEquals(0, ThinnedCallOrdinals.adapt(mixin("com/example/fabric/OtherHostMixin", 2), name -> host(2)));
		// The switch.
		System.setProperty(ThinnedCallOrdinals.PROPERTY, "off");
		assertEquals(0, ThinnedCallOrdinals.adapt(mixin("com/example/fabric/OffMixin", 2), name -> target));
		System.clearProperty(ThinnedCallOrdinals.PROPERTY);
		ClassNode moved = mixin("com/example/fabric/MovedMixin", 2);
		assertEquals(1, ThinnedCallOrdinals.adapt(moved, name -> target));
		assertEquals(List.of(0), ordinals(moved, "hook"));
	}

	/** The row's method making the row's call {@code times} times, each followed by the row's next call. */
	private static ClassNode host(int times) {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V21;
		node.name = SITE.owner();
		node.superName = "java/lang/Object";
		String desc = SITE.method().substring(SITE.method().indexOf('('));
		MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE, SITE.method().substring(0, SITE.method().indexOf('(')), desc, null, null);
		for (int i = 0; i < times; i++) {
			method.instructions.add(call(SITE.call()));
			method.instructions.add(call(SITE.next()));
		}
		method.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ACONST_NULL));
		method.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ARETURN));
		node.methods = new ArrayList<>(List.of(method));
		return node;
	}

	private static MethodInsnNode call(String member) {
		MixinFit.Member m = MixinFit.parseMember(member);
		return new MethodInsnNode(Opcodes.INVOKEVIRTUAL, m.owner(), m.name(), m.desc(), false);
	}

	/** A Fabric mixin on the row's class with one @Inject at the row's call, at {@code ordinal}. */
	private static ClassNode mixin(String name, int ordinal) {
		ClassNode mixin = new ClassNode();
		mixin.version = Opcodes.V21;
		mixin.name = name;
		mixin.superName = "java/lang/Object";
		AnnotationNode type = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		type.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType(SITE.owner())))));
		mixin.invisibleAnnotations = new ArrayList<>(List.of(type));
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "INVOKE", "target", SITE.call(), "ordinal", ordinal));
		AnnotationNode inject = new AnnotationNode(INJECT);
		inject.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of("performUseItemOn")), "at", new ArrayList<>(List.of(at))));
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, "hook",
				"(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V", null, null);
		handler.visibleAnnotations = new ArrayList<>(List.of(inject));
		mixin.methods = new ArrayList<>(List.of(handler));
		MixinStubRebind.noteEcosystem(name, Ecosystem.FABRIC);
		return mixin;
	}

	private static List<Integer> ordinals(ClassNode mixin, String handler) {
		List<Integer> out = new ArrayList<>();
		for (MethodNode m : mixin.methods) {
			if (!m.name.equals(handler)) continue;
			for (AnnotationNode at : MixinFit.atNodes(MixinFit.injectorOf(m))) out.add((Integer) MixinFit.value(at, "ordinal"));
		}
		return out;
	}

	private static String member(MethodInsnNode call) {
		return "L" + call.owner + ";" + call.name + call.desc;
	}

	private static MethodInsnNode nextCall(AbstractInsnNode from) {
		for (AbstractInsnNode insn = from.getNext(); insn != null; insn = insn.getNext()) if (insn instanceof MethodInsnNode c) return c;
		throw new AssertionError("no call after " + from);
	}

	private static MethodNode method(ClassNode node, String nameAndDesc) {
		return node.methods.stream().filter(m -> (m.name + m.desc).equals(nameAndDesc)).findFirst().orElseThrow();
	}

	private static ClassNode read(Path jar, String name) throws Exception {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(name + ".class");
			assertNotNull(entry, name + " in " + jar);
			ClassNode node = new ClassNode();
			new ClassReader(zip.getInputStream(entry).readAllBytes()).accept(node, 0);
			return node;
		}
	}
}
