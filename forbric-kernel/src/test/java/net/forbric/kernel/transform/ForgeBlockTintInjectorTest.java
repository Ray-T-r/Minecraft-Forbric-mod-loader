package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipFile;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

class ForgeBlockTintInjectorTest {
	private static final ForgeBlockTintInjector INJECTOR = new ForgeBlockTintInjector();
	@AfterEach void clear() { System.clearProperty("forbric.forgeClientInit"); }

	@Test void redirectsOnePostWithoutChangingTheStackOrInstructionSequence() throws Exception {
		byte[] before = fixture(1, true);
		byte[] after = INJECTOR.transform(ForgeBlockTintInjector.TARGET, before, null);
		assertNotSame(before, after);
		MethodNode original = createDefault(before), changed = createDefault(after);
		assertEquals(opcodes(original), opcodes(changed));
		assertEquals(0, posts(changed, ForeignType.FML_MOD_LOADER.internal(Ecosystem.NEOFORGE)));
		assertEquals(1, posts(changed, ForgeBlockTintInjector.HOOK));
		new Analyzer<>(new BasicVerifier()).analyze(ForgeBlockTintInjector.TARGET.replace('.', '/'), changed);
		assertSame(after, INJECTOR.transform(ForgeBlockTintInjector.TARGET, after, null));
	}

	@Test void ambiguousMissingOrDifferentEventsStandDown() {
		for (byte[] bytes : List.of(fixture(0, true), fixture(2, true), fixture(1, false))) {
			assertSame(bytes, INJECTOR.transform(ForgeBlockTintInjector.TARGET, bytes, null));
		}
		byte[] other = {1, 2};
		assertSame(other, INJECTOR.transform("example.Other", other, null));
	}

	@Test void clientInitControlRestoresTheOriginalCall() {
		byte[] bytes = fixture(1, true);
		System.setProperty("forbric.forgeClientInit", "off");
		assertSame(bytes, INJECTOR.transform(ForgeBlockTintInjector.TARGET, bytes, null));
	}

	/**
	 * The real merged createDefault posts NeoForge's registration once and reaches MinecraftForge's once: through the
	 * funnel, or — where a merge kept MinecraftForge's own edit — by calling ForgeHooksClient.onBlockColorsInit itself,
	 * which is then left exactly as it is. Either way the claim lands.
	 */
	@Test void theActualMergedClassHasExactlyThisSeam() throws Exception {
		Path jar = TestFixtures.stagedBase();
		TestFixtures.requireFile(Fixture.STAGED, jar, "requires merged base");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			byte[] before = zip.getInputStream(zip.getEntry(ForgeBlockTintInjector.TARGET.replace('.', '/') + ".class")).readAllBytes();
			assertEquals(1, posts(createDefault(before), ForeignType.FML_MOD_LOADER.internal(Ecosystem.NEOFORGE)));
			long nativeForge = forgeRegistrations(createDefault(before));
			java.util.Set<String> hits = new java.util.HashSet<>();
			byte[] after = INJECTOR.transform(ForgeBlockTintInjector.TARGET, before, null, hits::add);
			assertEquals(java.util.Set.of(ForgeBlockTintInjector.CLAIM), hits);
			if (nativeForge > 0) {
				assertEquals(1, nativeForge, "MinecraftForge's registration, once");
				assertSame(before, after, "funnelling NeoForge's post too would register MinecraftForge's tints twice");
				// The funnel itself, on the real class: the same body with its MinecraftForge registration taken out.
				before = withoutForgeRegistration(before);
				hits.clear();
				after = INJECTOR.transform(ForgeBlockTintInjector.TARGET, before, null, hits::add);
				assertEquals(java.util.Set.of(ForgeBlockTintInjector.CLAIM), hits);
			}
			assertNotSame(before, after);
			assertEquals(opcodes(createDefault(before)), opcodes(createDefault(after)));
			assertEquals(1, posts(createDefault(after), ForgeBlockTintInjector.HOOK));
			assertSame(after, INJECTOR.transform(ForgeBlockTintInjector.TARGET, after, null));
		}
	}

	/** How a createDefault written by {@link #colors} asks MinecraftForge, if it does. */
	private enum Ask { NOT, SAME_COLORS, NULL, OTHER_BRANCH }

	/**
	 * {@code BlockColors colors = new BlockColors(); [ask]; ModLoader.postEvent(new BlockTintEvent(colors)); return colors;}
	 * where the ask is MinecraftForge's {@code onBlockColorsInit} handed the same colors, handed null, or handed the same
	 * colors only when a static flag says so.
	 */
	private static byte[] colors(Ask ask) {
		ClassWriter out = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
			@Override protected String getCommonSuperClass(String a, String b) { return "java/lang/Object"; }
		};
		String target = ForgeBlockTintInjector.TARGET.replace('.', '/'), event = ForeignType.BLOCK_TINT_EVENT.internal(Ecosystem.NEOFORGE);
		out.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, target, null, "java/lang/Object", null);
		out.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "flag", "Z", null, null).visitEnd();
		var init = out.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode(); init.visitVarInsn(Opcodes.ALOAD, 0); init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitInsn(Opcodes.RETURN); init.visitMaxs(0, 0); init.visitEnd();
		var method = out.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "createDefault", "()L" + target + ";", null, null);
		method.visitCode();
		method.visitTypeInsn(Opcodes.NEW, target); method.visitInsn(Opcodes.DUP);
		method.visitMethodInsn(Opcodes.INVOKESPECIAL, target, "<init>", "()V", false);
		method.visitVarInsn(Opcodes.ASTORE, 0);
		org.objectweb.asm.Label skip = new org.objectweb.asm.Label();
		if (ask == Ask.OTHER_BRANCH) {
			method.visitFieldInsn(Opcodes.GETSTATIC, target, "flag", "Z");
			method.visitJumpInsn(Opcodes.IFEQ, skip);
		}
		if (ask != Ask.NOT) {
			if (ask == Ask.NULL) method.visitInsn(Opcodes.ACONST_NULL); else method.visitVarInsn(Opcodes.ALOAD, 0);
			method.visitMethodInsn(Opcodes.INVOKESTATIC, ForeignType.CLIENT_HOOKS.internal(Ecosystem.FORGE), "onBlockColorsInit", "(L" + target + ";)V", false);
		}
		if (ask == Ask.OTHER_BRANCH) method.visitLabel(skip);
		method.visitTypeInsn(Opcodes.NEW, event); method.visitInsn(Opcodes.DUP); method.visitVarInsn(Opcodes.ALOAD, 0);
		method.visitMethodInsn(Opcodes.INVOKESPECIAL, event, "<init>", "(L" + target + ";)V", false);
		method.visitMethodInsn(Opcodes.INVOKESTATIC, ForeignType.FML_MOD_LOADER.internal(Ecosystem.NEOFORGE), "postEvent", ForgeBlockTintInjector.POST, false);
		method.visitVarInsn(Opcodes.ALOAD, 0); method.visitInsn(Opcodes.ARETURN);
		method.visitMaxs(0, 0); method.visitEnd();
		out.visitEnd();
		return out.toByteArray();
	}

	/** A body that hands its own colors to MinecraftForge on the path of NeoForge's post is never funnelled, and satisfies the claim. */
	@Test void aBodyThatAlreadyAsksMinecraftForgeStandsDown() {
		byte[] bytes = colors(Ask.SAME_COLORS);
		java.util.Set<String> hits = new java.util.HashSet<>();
		assertSame(bytes, INJECTOR.transform(ForgeBlockTintInjector.TARGET, bytes, null, hits::add));
		assertEquals(java.util.Set.of(ForgeBlockTintInjector.CLAIM), hits);
		hits.clear();
		INJECTOR.transform(ForgeBlockTintInjector.TARGET, fixture(2, true), null, hits::add);
		assertEquals(java.util.Set.of(), hits, "an ambiguous body neither funnelled nor asking MinecraftForge is a miss");
	}

	/**
	 * Near misses: MinecraftForge's call handed something else, or on a branch NeoForge's post does not share, is not
	 * MinecraftForge registering the live colors. The funnel goes in, and the claim lands through it.
	 */
	@Test void aMinecraftForgeCallOnOtherColorsOrAnotherBranchIsFunnelledAnyway() {
		for (Ask ask : List.of(Ask.NULL, Ask.OTHER_BRANCH)) {
			byte[] bytes = colors(ask);
			java.util.Set<String> hits = new java.util.HashSet<>();
			byte[] after = INJECTOR.transform(ForgeBlockTintInjector.TARGET, bytes, null, hits::add);
			assertNotSame(bytes, after, ask + ": not a stand-down");
			assertEquals(1, posts(createDefault(after), ForgeBlockTintInjector.HOOK), ask + ": NeoForge's post goes through the funnel");
			assertEquals(java.util.Set.of(ForgeBlockTintInjector.CLAIM), hits, ask + ": through the funnel");
		}
		// Negative control: with no MinecraftForge call at all the same body is funnelled the same way.
		assertNotSame(colors(Ask.NOT), INJECTOR.transform(ForgeBlockTintInjector.TARGET, colors(Ask.NOT), null));
	}

	/** An ambiguous body (two posts, so never funnelled) with a MinecraftForge call on other colors does not land the claim. */
	@Test void aMinecraftForgeCallOnOtherColorsDoesNotLandTheClaim() {
		ClassNode node = parse(fixture(2, true));
		MethodNode method = createDefault(node);
		InsnList forge = new InsnList();
		forge.add(new InsnNode(Opcodes.ACONST_NULL));
		forge.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ForeignType.CLIENT_HOOKS.internal(Ecosystem.FORGE), "onBlockColorsInit",
				"(L" + ForgeBlockTintInjector.TARGET.replace('.', '/') + ";)V", false));
		method.instructions.insert(forge);
		ClassWriter out = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(out);
		byte[] bytes = out.toByteArray();
		java.util.Set<String> hits = new java.util.HashSet<>();
		assertSame(bytes, INJECTOR.transform(ForgeBlockTintInjector.TARGET, bytes, null, hits::add));
		assertEquals(java.util.Set.of(), hits, "a null handed to MinecraftForge is not the live colors reaching it");
	}

	@Test void typedFunnelPostsNeoBeforeAskingForgeWithTheEventsBlockColors() throws Exception {
		Path runtime = Path.of("build/classes/java/runtime", ForgeBlockTintInjector.HOOK + ".class");
		TestFixtures.require(Fixture.GAME_SIDE, Files.isRegularFile(runtime), "requires runtime source set");
		ClassNode node = parse(Files.readAllBytes(runtime));
		MethodNode method = node.methods.stream().filter(m -> m.name.equals("postBlockTintSources")).findFirst().orElseThrow();
		assertEquals(ForgeBlockTintInjector.POST, method.desc);
		int neo = -1, getter = -1, forge = -1;
		for (int i = 0; i < method.instructions.size(); i++) {
			if (!(method.instructions.get(i) instanceof MethodInsnNode call)) continue;
			if (call.owner.equals(ForeignType.FML_MOD_LOADER.internal(Ecosystem.NEOFORGE)) && call.name.equals("postEvent")) neo = i;
			if (call.owner.equals(ForeignType.BLOCK_TINT_EVENT.internal(Ecosystem.NEOFORGE)) && call.name.equals("getBlockColors")) getter = i;
			if (call.owner.equals(ForeignType.CLIENT_HOOKS.internal(Ecosystem.FORGE)) && call.name.equals("onBlockColorsInit")) forge = i;
		}
		assertTrue(neo >= 0 && getter > neo && forge > getter, "one live instance must flow through both registration APIs");
	}

	private static ClassNode parse(byte[] bytes) { ClassNode n = new ClassNode(); new ClassReader(bytes).accept(n, 0); return n; }

	/** createDefault with MinecraftForge's own registration (its colors load and the call) taken out. */
	private static byte[] withoutForgeRegistration(byte[] bytes) {
		ClassNode node = parse(bytes);
		MethodNode method = createDefault(node);
		for (var insn : method.instructions.toArray()) {
			if (!(insn instanceof MethodInsnNode call) || !call.owner.equals(ForeignType.CLIENT_HOOKS.internal(Ecosystem.FORGE))
					|| !call.name.equals("onBlockColorsInit")) continue;
			var load = call.getPrevious();
			while (load.getOpcode() < 0) load = load.getPrevious();
			method.instructions.remove(load);
			method.instructions.remove(call);
		}
		ClassWriter out = new ClassWriter(0); node.accept(out);
		return out.toByteArray();
	}
	private static MethodNode createDefault(byte[] bytes) {
		return createDefault(parse(bytes));
	}
	private static MethodNode createDefault(ClassNode node) {
		return node.methods.stream().filter(m -> m.name.equals("createDefault")).findFirst().orElseThrow();
	}
	private static long forgeRegistrations(MethodNode method) {
		return Arrays.stream(method.instructions.toArray()).filter(i -> i instanceof MethodInsnNode call
				&& call.owner.equals(ForeignType.CLIENT_HOOKS.internal(Ecosystem.FORGE)) && call.name.equals("onBlockColorsInit")).count();
	}
	private static List<Integer> opcodes(MethodNode m) {
		return Arrays.stream(m.instructions.toArray()).filter(i -> i.getOpcode() >= 0).map(i -> i.getOpcode()).toList();
	}
	private static long posts(MethodNode method, String owner) {
		return Arrays.stream(method.instructions.toArray()).filter(i -> i instanceof MethodInsnNode call
				&& call.owner.equals(owner) && call.desc.equals(ForgeBlockTintInjector.POST)).count();
	}
	private static byte[] fixture(int posts, boolean expectedEvent) {
		ClassWriter out = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		String target = ForgeBlockTintInjector.TARGET.replace('.', '/');
		out.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, target, null, "java/lang/Object", null);
		var method = out.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "createDefault", "()L" + target + ";", null, null);
		method.visitCode();
		for (int i = 0; i < posts; i++) {
			String event = expectedEvent ? ForeignType.BLOCK_TINT_EVENT.internal(Ecosystem.NEOFORGE) : "example/OtherEvent";
			method.visitTypeInsn(Opcodes.NEW, event); method.visitInsn(Opcodes.DUP); method.visitInsn(Opcodes.ACONST_NULL);
			method.visitMethodInsn(Opcodes.INVOKESPECIAL, event, "<init>", "(L" + target + ";)V", false);
			method.visitMethodInsn(Opcodes.INVOKESTATIC, ForeignType.FML_MOD_LOADER.internal(Ecosystem.NEOFORGE),
					"postEvent", ForgeBlockTintInjector.POST, false);
		}
		method.visitInsn(Opcodes.ACONST_NULL); method.visitInsn(Opcodes.ARETURN); method.visitMaxs(0, 0); method.visitEnd();
		out.visitEnd(); return out.toByteArray();
	}
}
