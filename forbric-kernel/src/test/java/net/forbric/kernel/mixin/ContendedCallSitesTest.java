package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.api.Ecosystem;

/**
 * {@link ContendedCallSites} on hand-built nodes: what counts as a call site another mod's injector took, and what
 * counts as a raw patch naming it. {@code ContendedCallSitesWeaveTest} runs the same through the real Mixin.
 */
class ContendedCallSitesTest {
	private static final String TARGET = "t/Stage";
	private static final String SOLID = "t/Paints.solid(Ljava/lang/String;)Ljava/lang/String;";
	private static final String MIXIN = "x/GlassMixin";
	private static final String HANDLER = "glass$solid";
	private static final String MERGED_HANDLER = "redirect$zza000$" + HANDLER;

	@AfterEach
	void forget() {
		ContendedCallSites.reset();
		MixinStubRebind.forget();
		MixinConfigOwners.reset();
	}

	/** A raw patch names the instruction it looks for in an ldc; calling the same method is not naming it. */
	@Test
	void aStringConstantIsReadAndAMethodReferenceIsNot() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "p/Patcher", null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
		mv.visitCode();
		mv.visitLdcInsn("outline");
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, "t/Paints", "solid", "(Ljava/lang/String;)Ljava/lang/String;", false);
		mv.visitInsn(Opcodes.POP);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();

		List<String> constants = new ArrayList<>();
		ContendedCallSites.stringConstants(cw.toByteArray(), constants::add);
		assertTrue(constants.contains("outline"), constants.toString());
		assertFalse(constants.contains("solid"), "a call's own name is a method reference, not a string constant: " + constants);
		assertFalse(constants.contains("t/Paints"), constants.toString());
	}

	/**
	 * The patcher is judged by the jar its plugin was defined from, spelled the way the kernel's loader spells it — raw,
	 * for a mod jar named with brackets, spaces, CJK and a plus — and a jar that only calls the method does not name it.
	 */
	@Test
	void thePluginsOwnJarIsReadWhateverItsFileIsCalled(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
		java.nio.file.Path naming = jar(dir.resolve("[补丁] cloak patch+1.0.jar"), true);
		java.nio.file.Path calling = jar(dir.resolve("[补丁] cloak call+1.0.jar"), false);
		MixinFit.Member site = MixinFit.parseMember(SOLID);
		Class<?> fromNaming = defineFrom(naming);
		assertEquals(naming.toRealPath(), ContendedCallSites.codeRoot(fromNaming).toRealPath());
		assertTrue(ContendedCallSites.names(fromNaming, site));
		assertFalse(ContendedCallSites.names(defineFrom(calling), site));
	}

	/** A jar with {@code p/Patcher}: ldc of the call's name and owner, or only a call to it. */
	private static java.nio.file.Path jar(java.nio.file.Path path, boolean names) throws Exception {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "p/Patcher", null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
		mv.visitCode();
		if (names) {
			mv.visitLdcInsn("t/Paints");
			mv.visitLdcInsn("solid");
			mv.visitInsn(Opcodes.POP2);
		} else {
			mv.visitLdcInsn("cloak");
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "t/Paints", "solid", "(Ljava/lang/String;)Ljava/lang/String;", false);
			mv.visitInsn(Opcodes.POP);
		}
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		try (var out = new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(path))) {
			out.putNextEntry(new java.util.jar.JarEntry("p/Patcher.class"));
			out.write(cw.toByteArray());
			out.closeEntry();
		}
		return path;
	}

	/** Defines {@code p/Patcher} out of {@code jar} with the code source spelled raw, as {@code file:} plus the path. */
	private static Class<?> defineFrom(java.nio.file.Path jar) throws Exception {
		byte[] bytes;
		try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
			bytes = zip.getInputStream(zip.getEntry("p/Patcher.class")).readAllBytes();
		}
		java.security.ProtectionDomain domain = new java.security.ProtectionDomain(new java.security.CodeSource(
				new java.net.URL("file:" + jar.toAbsolutePath()), (java.security.CodeSigner[]) null), null);
		return new ClassLoader(ContendedCallSitesTest.class.getClassLoader()) {
			Class<?> define() {
				return defineClass("p.Patcher", bytes, 0, bytes.length, domain);
			}
		}.define();
	}

	@Test
	void aMethodThatCallsAnotherModsHandlerAndNoLongerHasTheCallIsACandidate() {
		owners();
		List<ContendedCallSites.Candidate> found = ContendedCallSites.candidates(woven(false), "cloakpatch");
		assertEquals(1, found.size(), found.toString());
		ContendedCallSites.Candidate candidate = found.get(0);
		assertEquals("render", candidate.method());
		assertEquals("glassmod", candidate.modId());
		assertEquals("Lt/Paints;solid(Ljava/lang/String;)Ljava/lang/String;", ContendedCallSites.spell(candidate.claim().site()));
	}

	/** An injector limited to one occurrence that left another one in the method took nothing a patch cannot find. */
	@Test
	void anOccurrenceLeftInTheMethodIsNoCandidate() {
		owners();
		assertEquals(List.of(), ContendedCallSites.candidates(woven(true), "cloakpatch"));
	}

	/** A mod's own plugin and its own injector are its own business. */
	@Test
	void theInjectingModsOwnPluginIsNoContender() {
		owners();
		assertEquals(List.of(), ContendedCallSites.candidates(woven(false), "glassmod"));
	}

	/** A handler that did not come from a mixin the kernel handed Mixin is nobody's claim. */
	@Test
	void anUnrecordedMixinClaimsNothing() {
		MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned("glass.mixins.json", "glassmod", Ecosystem.NEOFORGE)));
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.NEOFORGE, "glass.mixins.json");
		assertEquals(List.of(), ContendedCallSites.candidates(woven(false), "cloakpatch"));
	}

	/**
	 * gate-m9 asserts on this line's wording for the real pair in its pack, so the gate's pattern is held against the
	 * line this class writes for that pair, and must not match the line for another call or another pair of mods.
	 */
	@Test
	void gateM9sPatternMatchesTheLineWrittenForThePairInItsPack() throws Exception {
		String script = java.nio.file.Files.readString(java.nio.file.Path.of("run/gate-m9-client.sh"));
		java.util.regex.Matcher check = java.util.regex.Pattern.compile(
				"check \"the cape call site contention is reported, naming both mods\" \\\\\\s*\"([^\"]+)\" \"\\$LOG\"").matcher(script);
		assertTrue(check.find(), "gate-m9 no longer checks the contention report");
		java.util.regex.Pattern gate = java.util.regex.Pattern.compile(check.group(1));
		String plugin = "customskinloader.bootstrap.fabric.v1.MixinConfigPlugin";
		String injector = "shouldersurfing.common.mixins.json:CapeLayerMixin.entitySolid";
		assertTrue(gate.matcher(ContendedCallSites.line("net.minecraft.client.renderer.entity.layers.CapeLayer.submit",
				"RenderTypes.entitySolid", "shouldersurfing", "@Redirect", injector, "customskinloader-bootstrap", plugin)).find());
		assertFalse(gate.matcher(ContendedCallSites.line("net.minecraft.client.renderer.entity.layers.CapeLayer.submit",
				"RenderTypes.entityCutout", "shouldersurfing", "@Redirect", injector, "customskinloader-bootstrap", plugin)).find());
		assertFalse(gate.matcher(ContendedCallSites.line("net.minecraft.client.renderer.entity.layers.CapeLayer.submit",
				"RenderTypes.entitySolid", "othermod", "@Redirect", injector, "customskinloader-bootstrap", plugin)).find());
	}

	/** The fingerprint sees a patch made in place, which is how a raw patch usually renames a call. */
	@Test
	void aCallRenamedInPlaceChangesTheFingerprint() {
		MethodNode render = woven(true).methods.get(0);
		List<String> before = ContendedCallSites.fingerprint(render);
		for (var insn : render.instructions) if (insn instanceof MethodInsnNode call && call.name.equals("solid")) call.name = "translucent";
		assertNotEquals(before, ContendedCallSites.fingerprint(render));
	}

	private static void owners() {
		ContendedCallSites.remember(mixin());
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.NEOFORGE, "glass.mixins.json");
		MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned("glass.mixins.json", "glassmod", Ecosystem.NEOFORGE),
				new MixinConfigOwners.Owned("cloak.mixins.json", "cloakpatch", Ecosystem.FABRIC)));
	}

	/** {@code @Mixin(Stage) class GlassMixin { @Redirect(method="render", at=@At(INVOKE, solid)) String glass$solid(String) }}. */
	private static ClassNode mixin() {
		ClassNode mixin = new ClassNode();
		mixin.name = MIXIN;
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		at.values = new ArrayList<>(List.of("value", List.of(Type.getObjectType(TARGET))));
		mixin.invisibleAnnotations = new ArrayList<>(List.of(at));
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, HANDLER, "(Ljava/lang/String;)Ljava/lang/String;", null, null);
		AnnotationNode point = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		point.values = new ArrayList<>(List.of("value", "INVOKE", "target", SOLID));
		AnnotationNode redirect = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Redirect;");
		redirect.values = new ArrayList<>(List.of("method", List.of("render"), "at", point));
		handler.visibleAnnotations = new ArrayList<>(List.of(redirect));
		mixin.methods.add(handler);
		return mixin;
	}

	/** Stage as Mixin hands it to postApply: render calls the merged handler, and, if asked, still calls solid once. */
	private static ClassNode woven(boolean occurrenceLeft) {
		ClassNode stage = new ClassNode();
		stage.name = TARGET;
		MethodNode render = new MethodNode(Opcodes.ACC_PUBLIC, "render", "()Ljava/lang/String;", null, null);
		if (occurrenceLeft) {
			render.instructions.add(new LdcInsnNode("trim"));
			render.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "t/Paints", "solid", "(Ljava/lang/String;)Ljava/lang/String;", false));
			render.instructions.add(new InsnNode(Opcodes.POP));
		}
		render.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		render.instructions.add(new LdcInsnNode("cloak"));
		render.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, TARGET, MERGED_HANDLER, "(Ljava/lang/String;)Ljava/lang/String;", false));
		render.instructions.add(new InsnNode(Opcodes.ARETURN));
		stage.methods.add(render);

		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, MERGED_HANDLER, "(Ljava/lang/String;)Ljava/lang/String;", null, null);
		AnnotationNode merged = new AnnotationNode("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;");
		merged.values = new ArrayList<>(List.of("mixin", MIXIN.replace('/', '.'), "priority", 500));
		handler.visibleAnnotations = new ArrayList<>(List.of(merged));
		handler.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
		handler.instructions.add(new InsnNode(Opcodes.ARETURN));
		stage.methods.add(handler);
		return stage;
	}
}
