package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

class CompatibilityPromptTickInjectorTest {
	private static final String OWNER = "net/forbric/kernel/runtime/KernelCompatibilityPrompts";

	@Test void installsOneClientTickCallbackAndIsIdempotent() {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "net/minecraft/client/Minecraft", null, "java/lang/Object", null);
		var tick = writer.visitMethod(Opcodes.ACC_PUBLIC, "tick", "()V", null, null);
		tick.visitCode(); tick.visitInsn(Opcodes.RETURN); tick.visitMaxs(0, 1); tick.visitEnd(); writer.visitEnd();
		CompatibilityPromptTickInjector injector = new CompatibilityPromptTickInjector();
		byte[] out = injector.transform("net.minecraft.client.Minecraft", writer.toByteArray(), null);
		assertEquals(1, calls(parse(out), OWNER, "tick"));
		assertSame(out, injector.transform("net.minecraft.client.Minecraft", out, null));
		assertSame(out, injector.transform("net.minecraft.server.MinecraftServer", out, null));
	}

	@Test void bootChecksAfterItsEvidenceAndResetsBeforeReadingMods() throws Exception {
		ClassNode boot = parse(Files.readAllBytes(Path.of("build/classes/java/main/net/forbric/kernel/boot/KernelBoot.class")));
		MethodNode launch = boot.methods.stream().filter(m -> m.name.equals("launch")).findFirst().orElseThrow();
		int reset = -1, report = -1, decision = -1, step = 0;
		for (AbstractInsnNode instruction : launch.instructions) {
			if (instruction instanceof MethodInsnNode call) {
				if (call.owner.equals("net/forbric/api/CompatibilityFindings") && call.name.equals("reset")) reset = step;
				if (call.owner.equals("net/forbric/kernel/boot/KernelLoadReport") && call.name.equals("write")) report = step;
				if (call.owner.equals("net/forbric/kernel/ui/CompatibilityDecision") && call.name.equals("requireContinuation")) decision = step;
			}
			step++;
		}
		assertTrue(reset >= 0 && report > reset && decision > report, "collect and settle before asking the player");
		assertEquals(1, calls(boot, "net/forbric/kernel/mixin/MixinCompatibility", "reset"));
		assertTrue(net.forbric.kernel.boot.KernelRuntimeClasses.all().containsKey(OWNER.replace('/', '.')));
	}

	@Test void gameUiCallsExistOnTheActualMinecraft262Base() throws Exception {
		Path game = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"),
				"run", "merged-base", "patched-mc-merged-26.2.jar").normalize();
		org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(game), "staged game absent");
		try (ZipFile zip = new ZipFile(game.toFile())) {
			ClassNode minecraft = parse(zip.getInputStream(zip.getEntry("net/minecraft/client/Minecraft.class")).readAllBytes());
			for (String method : java.util.List.of("disconnectWithSavingScreen", "stop", "tick")) {
				assertTrue(minecraft.methods.stream().anyMatch(m -> m.name.equals(method) && m.desc.equals("()V")), method);
			}
			ClassNode gui = parse(zip.getInputStream(zip.getEntry("net/minecraft/client/gui/Gui.class")).readAllBytes());
			assertTrue(gui.methods.stream().anyMatch(m -> m.name.equals("setScreen") && m.desc.equals("(Lnet/minecraft/client/gui/screens/Screen;)V")));
			ClassNode confirm = parse(zip.getInputStream(zip.getEntry("net/minecraft/client/gui/screens/ConfirmScreen.class")).readAllBytes());
			assertTrue(confirm.methods.stream().anyMatch(m -> m.name.equals("<init>") && m.desc.equals(
					"(Lit/unimi/dsi/fastutil/booleans/BooleanConsumer;Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/Component;)V")));
		}
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node;
	}
	private static int calls(ClassNode node, String owner, String name) {
		int count = 0;
		for (MethodNode method : node.methods) for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) count++;
		}
		return count;
	}
}
