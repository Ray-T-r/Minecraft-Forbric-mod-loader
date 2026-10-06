package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.transform.LambdaInvocationThunkInjector;

/** Native selector proof, ordinary method-reference materialization, real Mixin and actual invocation, end to end. */
class MixinLambdaThunkWeaveTest {
	@TempDir Path work;
	@Test void aNativeOperationRunsOnceOnTheMaterializedLambdaWithItsCaptureAndVetoPreserved()throws Exception{
		Path target=source("Route.java","""
				package net.minecraft.fixture;
				import java.util.function.IntUnaryOperator;
				public class Route {
				 public static int wrapped,nativeCalls;
				 private final IntUnaryOperator operation=this::consume;
				 public int dispatch(int value){return operation.applyAsInt(value);}
				 public int consume(int value){nativeCalls++;return value*2;}
				 public void run(){System.out.println("[LambdaThunk] normal="+dispatch(3)+" veto="+dispatch(-2));System.out.println("[LambdaThunk] wrapped="+wrapped+" native="+nativeCalls);}
				}
				""");
		Path mixin=source("RouteMixin.java","""
				package fixture.mixin;
				import net.minecraft.fixture.Route;
				import org.spongepowered.asm.mixin.Mixin;
				import org.spongepowered.asm.mixin.injection.At;
				import com.llamalad7.mixinextras.injector.wrapoperation.*;
				import com.llamalad7.mixinextras.sugar.Local;
				@Mixin(Route.class)
				public class RouteMixin {
				 @WrapOperation(method="dispatch",at=@At(value="INVOKE",target="Lnet/minecraft/fixture/Route;consume(I)I"))
				 private int intercept(Route receiver,int value,Operation<Integer> original,@Local(argsOnly=true) int captured){
				  if(value!=captured)throw new AssertionError("argument capture changed");Route.wrapped++;
				  if(value<0)return 99;return original.call(receiver,value+1)+10;
				 }
				}
				""");
		String config="lambda-thunk.mixins.json";Path json=work.resolve(config);Files.writeString(json,"{\"required\":true,\"package\":\"fixture.mixin\",\"mixins\":[\"RouteMixin\"],\"injectors\":{\"defaultRequire\":1}}");
		ClassWriter original=new ClassWriter(ClassWriter.COMPUTE_MAXS);original.visit(Opcodes.V21,Opcodes.ACC_PUBLIC,"net/minecraft/fixture/Route",null,"java/lang/Object",null);
		MethodVisitor dispatch=original.visitMethod(Opcodes.ACC_PUBLIC,"dispatch","(I)I",null,null);dispatch.visitCode();dispatch.visitVarInsn(Opcodes.ALOAD,0);dispatch.visitVarInsn(Opcodes.ILOAD,1);dispatch.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"net/minecraft/fixture/Route","consume","(I)I",false);dispatch.visitInsn(Opcodes.IRETURN);dispatch.visitMaxs(0,0);dispatch.visitEnd();original.visitEnd();
		byte[] reference=original.toByteArray();Path referenceFile=work.resolve("route-native.bin"),index=work.resolve("native-index.tsv");Files.write(referenceFile,reference);Files.writeString(index,"# forbric-native-reference-v1\nnet/minecraft/fixture/Route\t"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(reference))+"\n",StandardCharsets.UTF_8);
		Path fixture=WeaveHarness.fixture(work,"lambda-thunk",List.of(target,mixin),Map.of(config,json,"META-INF/forbric/native-reference/FABRIC/index.tsv",index,"META-INF/forbric/native-reference/FABRIC/net/minecraft/fixture/Route.class.bin",referenceFile));
		var configs=List.of(new WeaveHarness.Config(config,"lambdathunkprobe",Ecosystem.FABRIC));
		WeaveHarness.Result repaired=WeaveHarness.run(work,"on",fixture,configs,List.of(LambdaInvocationThunkInjector.class),EnvType.CLIENT,"net.minecraft.fixture.Route","run",Map.of());
		assertTrue(repaired.printed("[LambdaThunk] normal=18 veto=99"),repaired.describe());assertTrue(repaired.printed("[LambdaThunk] wrapped=2 native=1"),repaired.describe());
		assertTrue(repaired.findings().stream().noneMatch(f->f.confirmedRequired()),repaired.describe());WeaveHarness.assertWovenAndVerified(repaired,"net/minecraft/fixture/Route",fixture);
		WeaveHarness.Result control=WeaveHarness.run(work,"off",fixture,configs,List.of(LambdaInvocationThunkInjector.class),EnvType.CLIENT,"net.minecraft.fixture.Route","run",Map.of("forbric.mixinRetarget.executionPaths","off"));
		assertTrue(control.printed("[LambdaThunk] normal=6 veto=-4"),control.describe());assertTrue(control.printed("[LambdaThunk] wrapped=0 native=2"),control.describe());
		assertFalse(control.printed("[LambdaThunk] normal=18 veto=99"),control.describe());
		WeaveHarness.Result noThunk=WeaveHarness.run(work,"no-thunk",fixture,configs,List.of(LambdaInvocationThunkInjector.class),EnvType.CLIENT,"net.minecraft.fixture.Route","run",Map.of(LambdaInvocationThunkInjector.PROPERTY,"off"));
		assertTrue(noThunk.printed("[LambdaThunk] normal=6 veto=-4"),noThunk.describe());assertTrue(noThunk.printed("[LambdaThunk] wrapped=0 native=2"),noThunk.describe());
	}
	@Test void aGuardedHelperKeepsItsCarrierArgumentWhileTheOriginalOperationAndCaptureExecute()throws Exception{
		Path target=source("Route.java","""
				package net.minecraft.fixture;
				import java.util.function.IntUnaryOperator;
				public class Route {
				 public static int wrapped,nativeCalls;public static boolean carrier;
				 private final IntUnaryOperator operation=this::piece;
				 public int dispatch(int value){return operation.applyAsInt(value);}
				 private int piece(int value){if(value==0)return 33;return consume(value,true);}
				 public int consume(int value,boolean extended){nativeCalls++;carrier=extended;return value*2;}
				 public void run(){System.out.println("[LambdaHelper] normal="+dispatch(3)+" veto="+dispatch(-2)+" guard="+dispatch(0));System.out.println("[LambdaHelper] wrapped="+wrapped+" native="+nativeCalls+" carrier="+carrier);}
				}
				""");
		Path mixin=source("RouteMixin.java","""
				package fixture.mixin;
				import net.minecraft.fixture.Route;
				import org.spongepowered.asm.mixin.Mixin;
				import org.spongepowered.asm.mixin.injection.At;
				import com.llamalad7.mixinextras.injector.wrapoperation.*;
				import com.llamalad7.mixinextras.sugar.Local;
				@Mixin(Route.class) public class RouteMixin {
				 @WrapOperation(method="dispatch",at=@At(value="INVOKE",target="Lnet/minecraft/fixture/Route;consume(I)I"))
				 private int intercept(Route receiver,int value,Operation<Integer> original,@Local(argsOnly=true) int captured){
				  if(value!=captured)throw new AssertionError("argument capture changed");Route.wrapped++;if(value<0)return 99;return original.call(receiver,value+1)+10;
				 }
				}
				""");
		String config="lambda-helper.mixins.json";Path json=work.resolve(config);Files.writeString(json,"{\"required\":true,\"package\":\"fixture.mixin\",\"mixins\":[\"RouteMixin\"],\"injectors\":{\"defaultRequire\":1}}");
		ClassWriter original=new ClassWriter(ClassWriter.COMPUTE_MAXS);original.visit(Opcodes.V21,Opcodes.ACC_PUBLIC,"net/minecraft/fixture/Route",null,"java/lang/Object",null);
		MethodVisitor dispatch=original.visitMethod(Opcodes.ACC_PUBLIC,"dispatch","(I)I",null,null);dispatch.visitCode();dispatch.visitVarInsn(Opcodes.ALOAD,0);dispatch.visitVarInsn(Opcodes.ILOAD,1);dispatch.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"net/minecraft/fixture/Route","consume","(I)I",false);dispatch.visitInsn(Opcodes.IRETURN);dispatch.visitMaxs(0,0);dispatch.visitEnd();original.visitEnd();
		byte[] reference=original.toByteArray();Path referenceFile=work.resolve("route-native.bin"),index=work.resolve("native-index.tsv");Files.write(referenceFile,reference);Files.writeString(index,"# forbric-native-reference-v1\nnet/minecraft/fixture/Route\t"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(reference))+"\n",StandardCharsets.UTF_8);
		Path fixture=WeaveHarness.fixture(work,"lambda-helper",List.of(target,mixin,runtimeSource()),Map.of(config,json,"META-INF/forbric/native-reference/FABRIC/index.tsv",index,"META-INF/forbric/native-reference/FABRIC/net/minecraft/fixture/Route.class.bin",referenceFile));
		var configs=List.of(new WeaveHarness.Config(config,"lambdahelperprobe",Ecosystem.FABRIC));
		WeaveHarness.Result repaired=WeaveHarness.run(work,"on",fixture,configs,List.of(LambdaInvocationThunkInjector.class),EnvType.CLIENT,"net.minecraft.fixture.Route","run",Map.of());
		assertTrue(repaired.printed("[LambdaHelper] normal=18 veto=99 guard=33"),repaired.describe());assertTrue(repaired.printed("[LambdaHelper] wrapped=2 native=1 carrier=true"),repaired.describe());
		assertTrue(repaired.findings().stream().noneMatch(f->f.confirmedRequired()),repaired.describe());WeaveHarness.assertWovenAndVerified(repaired,"net/minecraft/fixture/Route",fixture);
		WeaveHarness.Result control=WeaveHarness.run(work,"off",fixture,configs,List.of(LambdaInvocationThunkInjector.class),EnvType.CLIENT,"net.minecraft.fixture.Route","run",Map.of("forbric.mixinRetarget.executionPaths","off"));
		assertTrue(control.printed("[LambdaHelper] normal=6 veto=-4 guard=33"),control.describe());assertTrue(control.printed("[LambdaHelper] wrapped=0 native=2 carrier=true"),control.describe());
	}
	private static Path runtimeSource(){String source="src/runtime/java/net/forbric/kernel/runtime/KernelWrapOperations.java";Path path=Path.of(source);if(!Files.isRegularFile(path))path=Path.of("forbric-kernel").resolve(source);assertTrue(Files.isRegularFile(path),"real KernelWrapOperations source is required");return path;}
	private Path source(String name,String content)throws Exception{Path file=work.resolve(name);Files.writeString(file,content);return file;}
}
