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
