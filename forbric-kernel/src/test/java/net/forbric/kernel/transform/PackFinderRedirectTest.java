package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;

/**
 * A scanned redirect must not redirect its own target.
 *
 * <p>{@code letMinecraftForgeAddPackFinders} has no fixed anchor: it rewrites every call to NeoForge's
 * {@code populatePackRepository} wherever it appears. The kernel method it rewrites them TO is itself a call to
 * that method, so without an exclusion the repair points it at itself — and the first pack repository built
 * recurses until the stack ends. That is what happened on the first live run: StackOverflowError, and the
 * server never reached Done.
 *
 * <p>A repair with a fixed anchor cannot do this, because it only ever edits the class it named. A scanned one
 * has to say what it is not allowed to touch, and nothing else will say it.
 */
class PackFinderRedirectTest {
	private static final String NEO_LOADER = "net/neoforged/neoforge/resource/ResourcePackLoader";
	private static final String KERNEL = "net/forbric/kernel/runtime/KernelPackFinders";
	private static final String DESC =
			"(Lnet/minecraft/server/packs/repository/PackRepository;Lnet/minecraft/server/packs/PackType;Z)V";
	private static final TransformContext CTX = new TransformContext(EnvType.SERVER, false, "intermediary");

	@Test
	void aGameClassIsRedirectedToTheKernel() {
		List<String> after = callsIn(transform("net/minecraft/server/packs/repository/ServerPacksSource"));
		assertEquals(List.of(KERNEL + ".populatePackRepository"), after,
				"a game call site has to end up pointing at the kernel");
	}

	@Test
	void theKernelsOwnCallIsLeftAlone() {
		// Rewriting this one makes it call itself. The failure is not subtle and not survivable: the first pack
		// repository built takes the stack with it.
		List<String> after = callsIn(transform(KERNEL));
		assertEquals(List.of(NEO_LOADER + ".populatePackRepository"), after,
				"the redirect target must still call NeoForge, or it recurses into itself");
	}

	/** How a method written by {@link #packMethod} asks MinecraftForge for finders, if it does. */
	private enum Ask { NOT, SAME_REPOSITORY, NULL, OTHER_REPOSITORY, OTHER_BRANCH }

	private static final String DESC_TWO =
			"(Lnet/minecraft/server/packs/repository/PackRepository;Lnet/minecraft/server/packs/PackType;ZLnet/minecraft/server/packs/repository/PackRepository;)V";

	/**
	 * {@code ResourcePackLoader.populatePackRepository(repository, type, flag); [ask]} where the ask is
	 * {@code ForgeEventFactory.addPackFindersServer(r::addPackFinder)} for this repository, for another one (the fourth
	 * parameter), with null, or for this repository only when the flag says so.
	 */
	private static void packMethod(ClassWriter cw, String name, Ask ask) {
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, DESC_TWO, null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitVarInsn(Opcodes.ALOAD, 1);
		mv.visitVarInsn(Opcodes.ILOAD, 2);
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, NEO_LOADER, "populatePackRepository", DESC, false);
		org.objectweb.asm.Label skip = new org.objectweb.asm.Label();
		if (ask == Ask.OTHER_BRANCH) {
			mv.visitVarInsn(Opcodes.ILOAD, 2);
			mv.visitJumpInsn(Opcodes.IFEQ, skip);
		}
		if (ask != Ask.NOT) {
			if (ask == Ask.NULL) {
				mv.visitInsn(Opcodes.ACONST_NULL);
			} else {
				mv.visitVarInsn(Opcodes.ALOAD, ask == Ask.OTHER_REPOSITORY ? 3 : 0);
				String repository = "net/minecraft/server/packs/repository/PackRepository";
				mv.visitInvokeDynamicInsn("accept", "(L" + repository + ";)Ljava/util/function/Consumer;",
						new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
								"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
										+ "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false),
						org.objectweb.asm.Type.getType("(Ljava/lang/Object;)V"),
						new org.objectweb.asm.Handle(Opcodes.H_INVOKEVIRTUAL, repository, "addPackFinder",
								"(Lnet/minecraft/server/packs/repository/RepositorySource;)V", false),
						org.objectweb.asm.Type.getType("(Lnet/minecraft/server/packs/repository/RepositorySource;)V"));
			}
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraftforge/event/ForgeEventFactory", "addPackFindersServer",
					"(Ljava/util/function/Consumer;)V", false);
		}
		if (ask == Ask.OTHER_BRANCH) mv.visitLabel(skip);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	/**
	 * A method that already asks MinecraftForge's mods for pack finders for the same repository, on the path of NeoForge's
	 * call — a merge that keeps both sides' edits writes ForgeEventFactory.addPackFindersServer back next to NeoForge's
	 * call — keeps NeoForge's call: through the kernel MinecraftForge's mods would be asked twice. A method beside it
	 * that does not ask is still redirected, and so is each near miss: MinecraftForge asked for another repository, for
	 * none, or only on a branch NeoForge's call does not share.
	 */
	@Test
	void aMethodThatAlreadyAsksMinecraftForgeIsLeftAlone() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
			@Override protected String getCommonSuperClass(String a, String b) { return "java/lang/Object"; }
		};
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "net/minecraft/client/gui/screens/worldselection/CreateWorldScreen", null, "java/lang/Object", null);
		for (Ask ask : Ask.values()) packMethod(cw, ask.name().toLowerCase(java.util.Locale.ROOT), ask);
		cw.visitEnd();
		byte[] before = cw.toByteArray();
		byte[] out = new ForbricMergedBaseCompatTransformer(name -> null)
				.transform("net.minecraft.client.gui.screens.worldselection.CreateWorldScreen", before, CTX);
		ClassNode node = new ClassNode();
		new ClassReader(out == null ? before : out).accept(node, 0);
		for (MethodNode m : node.methods) {
			List<String> calls = new ArrayList<>();
			for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof MethodInsnNode mi && mi.name.equals("populatePackRepository")) calls.add(mi.owner);
			}
			assertEquals(List.of(m.name.equals("same_repository") ? NEO_LOADER : KERNEL), calls, m.name);
		}
	}

	private static byte[] transform(String owner) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "populatePackRepository", DESC,
				null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitVarInsn(Opcodes.ALOAD, 1);
		mv.visitVarInsn(Opcodes.ILOAD, 2);
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, NEO_LOADER, "populatePackRepository", DESC, false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(4, 4);
		mv.visitEnd();
		cw.visitEnd();
		byte[] before = cw.toByteArray();
		byte[] out = new ForbricMergedBaseCompatTransformer(name -> null)
				.transform(owner.replace('/', '.'), before, CTX);
		return out == null ? before : out;
	}

	private static List<String> callsIn(byte[] classBytes) {
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		List<String> calls = new ArrayList<>();
		for (MethodNode m : node.methods) {
			if (m.instructions == null) continue;
			for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof MethodInsnNode mi) calls.add(mi.owner + "." + mi.name);
			}
		}
		return calls;
	}
}
