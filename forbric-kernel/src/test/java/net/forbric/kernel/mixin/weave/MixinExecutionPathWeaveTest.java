package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import org.objectweb.asm.*;

/** The generic helper rule must execute the original Operation and preserve a caller's cancellation and captures. */
class MixinExecutionPathWeaveTest {
	@TempDir Path work;

	@Test void theRealWeaverKeepsTheOperationCaptureAndCallerCancellation() throws Exception {
		Path target = source("Paths.java", """
				package net.minecraft.fixture;
				public class Paths {
				 public static int beforeCalls, wrappedCalls, nativeCalls;
				 public static int dispatch(int value) { return piece(value, true); }
				 private static int piece(int value, boolean unused) { return Action.scale(value) + 1; }
				 public static void run() {
				  int normal = dispatch(3), cancelled = dispatch(-2);
				  System.out.println("[ExecutionPath] normal=" + normal + " cancelled=" + cancelled);
				  System.out.println("[ExecutionPath] before=" + beforeCalls + " wrapped=" + wrappedCalls + " native=" + nativeCalls);
				 }
				}
				""");
		Path action = source("Action.java", """
				package net.minecraft.fixture;
				public class Action {
				 public static int scale(int value) { Paths.nativeCalls++; return value * 2; }
				}
				""");
		Path mixin = source("PathsMixin.java", """
				package fixture.mixin;
				import net.minecraft.fixture.Paths;
				import org.spongepowered.asm.mixin.Mixin;
				import org.spongepowered.asm.mixin.injection.At;
				import org.spongepowered.asm.mixin.injection.Inject;
				import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
				import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
				import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
				import com.llamalad7.mixinextras.sugar.Local;
				@Mixin(Paths.class)
				public class PathsMixin {
				 @WrapOperation(method="dispatch", at=@At(value="INVOKE", target="Lnet/minecraft/fixture/Action;scale(I)I"))
				 private static int wrap(int value, Operation<Integer> original, @Local(argsOnly=true) int captured) {
				  if (value != captured) throw new AssertionError("the caller's argument capture changed");
				  Paths.wrappedCalls++;
				  return original.call(value + 1) + 10;
				 }
				 @Inject(method="dispatch", at=@At(value="INVOKE", target="Lnet/minecraft/fixture/Action;scale(I)I"), cancellable=true)
				 private static void before(int value, CallbackInfoReturnable<Integer> result) {
				  Paths.beforeCalls++;
				  if (value < 0) result.setReturnValue(99);
				 }
				}
				""");
		String config = "execution-path.mixins.json";
		Path json = work.resolve(config);
		Files.writeString(json, """
				{"required":true,"package":"fixture.mixin","mixins":["PathsMixin"],"injectors":{"defaultRequire":1}}
				""");
		ClassWriter original = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		original.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "net/minecraft/fixture/Paths", null, "java/lang/Object", null);
		MethodVisitor dispatch = original.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "dispatch", "(I)I", null, null);
		dispatch.visitCode(); dispatch.visitVarInsn(Opcodes.ILOAD, 0);
		dispatch.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/fixture/Action", "scale", "(I)I", false);
		dispatch.visitInsn(Opcodes.ICONST_1); dispatch.visitInsn(Opcodes.IADD); dispatch.visitInsn(Opcodes.IRETURN);
		dispatch.visitMaxs(0, 0); dispatch.visitEnd(); original.visitEnd();
		byte[] reference = original.toByteArray();
		Path referenceFile = work.resolve("paths-native.bin"), index = work.resolve("native-index.tsv");
		Files.write(referenceFile, reference);
		Files.writeString(index, "# forbric-native-reference-v1\nnet/minecraft/fixture/Paths\t"
				+ HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(reference)) + "\n", StandardCharsets.UTF_8);
		Path fixture = WeaveHarness.fixture(work, "execution-path", List.of(target, action, mixin), Map.of(config, json,
				"META-INF/forbric/native-reference/FABRIC/index.tsv", index,
				"META-INF/forbric/native-reference/FABRIC/net/minecraft/fixture/Paths.class.bin", referenceFile));
		WeaveHarness.Result repaired = WeaveHarness.run(work, "on", fixture, config, "executionpathprobe", Ecosystem.FABRIC,
				EnvType.SERVER, "net.minecraft.fixture.Paths", "run", Map.of());
		assertTrue(repaired.printed("[ExecutionPath] normal=19 cancelled=99"), repaired.describe());
		assertTrue(repaired.printed("[ExecutionPath] before=2 wrapped=1 native=1"), repaired.describe());
		assertTrue(repaired.findings().stream().noneMatch(f -> f.confirmedRequired()), repaired.describe());
		WeaveHarness.assertWovenAndVerified(repaired, "net/minecraft/fixture/Paths", fixture);

		WeaveHarness.Result control = WeaveHarness.run(work, "off", fixture, config, "executionpathprobe", Ecosystem.FABRIC,
				EnvType.SERVER, "net.minecraft.fixture.Paths", "run", Map.of("forbric.mixinRetarget.executionPaths", "off"));
		assertTrue(control.printed("[ExecutionPath] normal=7 cancelled=-3"), control.describe());
		assertTrue(control.printed("[ExecutionPath] before=0 wrapped=0 native=2"), control.describe());
		assertFalse(control.printed("[ExecutionPath] normal=19 cancelled=99"), control.describe());
	}

	private Path source(String name, String content) throws Exception {
		Path path = work.resolve(name); Files.writeString(path, content); return path;
	}
}
