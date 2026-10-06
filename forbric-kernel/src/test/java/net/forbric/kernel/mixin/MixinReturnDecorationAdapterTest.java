package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

class MixinReturnDecorationAdapterTest {
 private record Inputs(ClassNode mixin,ClassNode current,ClassNode original) { }
 @Test void actualPositionCallbackFollowsTheConservedReturnBeforeItsNativeSnapshot() throws Exception {
  Inputs input=inputs();MethodNode handler=input.mixin.methods.stream().filter(m->m.name.equals("onReturnGetInWallBlockState")).findFirst().orElseThrow();
  String body=MixinInstructionFingerprint.hash(handler);
  assertEquals(1,adapt(input));assertEquals(body,MixinInstructionFingerprint.hash(handler));
  AnnotationNode injection=MixinFit.injectorOf(handler);String selector=MixinFit.stringList(MixinFit.value(injection,"method")).getFirst();
  assertTrue(selector.startsWith("getViewBlockingStateAndPos("));
  AnnotationNode point=MixinFit.atNodes(injection).getFirst();assertEquals("INVOKE",MixinFit.value(point,"value"));
  assertTrue(MixinFit.value(point,"target").toString().contains("MutableBlockPos;immutable"));assertEquals(0,adapt(input));
 }
 @Test void aChangedNativePrefixAndAUsedReturnValueCannotBorrowTheProjectionProof() throws Exception {
  Inputs input=inputs();MethodNode candidate=input.current.methods.stream().filter(m->m.name.equals("getViewBlockingStateAndPos")).findFirst().orElseThrow();
  for(var instruction:candidate.instructions)if(instruction instanceof JumpInsnNode branch){branch.setOpcode(Opcodes.IFNE);break;}
  assertEquals(0,adapt(input));input=inputs();MethodNode handler=input.mixin.methods.stream().filter(m->m.name.equals("onReturnGetInWallBlockState")).findFirst().orElseThrow();
  handler.instructions.insert(new InsnNode(Opcodes.POP));handler.instructions.insert(new VarInsnNode(Opcodes.ALOAD,0));assertEquals(0,adapt(input));
 }
 @Test void missingLocalMetadataAndCancellationCannotBeMoved() throws Exception {
  Inputs input=inputs();input.current.methods.stream().filter(m->m.name.equals("getViewBlockingStateAndPos")).findFirst().orElseThrow().localVariables.clear();assertEquals(0,adapt(input));
  input=inputs();MethodNode handler=input.mixin.methods.stream().filter(m->m.name.equals("onReturnGetInWallBlockState")).findFirst().orElseThrow();MixinFit.injectorOf(handler).values.addAll(List.of("cancellable",true));assertEquals(0,adapt(input));
 }

 @Test void aNewComputationBeforeTheSnapshotCannotMoveTheCallbackPastItsOriginalValue() throws Exception {
  Inputs input=inputs();MethodNode candidate=input.current.methods.stream().filter(m->m.name.equals("getViewBlockingStateAndPos")).findFirst().orElseThrow();
  for(var instruction:candidate.instructions)if(instruction instanceof MethodInsnNode call&&call.name.equals("immutable")){
   var position=new VarInsnNode(Opcodes.ALOAD,1);var effect=new MethodInsnNode(Opcodes.INVOKESTATIC,"fixture/Effects","mutate","(Lnet/minecraft/core/BlockPos$MutableBlockPos;)V",false);
   candidate.instructions.insertBefore(call,position);candidate.instructions.insert(position,effect);break;
  }
  assertEquals(0,adapt(input));
 }
 private static int adapt(Inputs input){return MixinReturnDecorationAdapter.adapt(input.mixin,Ecosystem.FABRIC,name->input.current,name->input.original);}
 private static Inputs inputs()throws Exception {
  Path base=Path.of(System.getProperty("forbric.predicateBase"));Path api=Path.of(System.getProperty("forbric.fabricApi"));
  TestFixtures.require(TestFixtures.Fixture.STAGED,Files.isRegularFile(base)&&Files.isRegularFile(api),"actual API and native-indexed game required");
  String owner="net/minecraft/client/renderer/ScreenEffectRenderer";ClassNode current,original,mixin=null;
  try(ZipFile game=new ZipFile(base.toFile())){current=parse(game.getInputStream(game.getEntry(owner+".class")).readAllBytes());NativeGameReferences references=new NativeGameReferences(path->{try{var e=game.getEntry(path);return e==null?null:game.getInputStream(e).readAllBytes();}catch(Exception bad){throw new IllegalStateException(bad);}});original=references.get(Ecosystem.FABRIC,owner);}
  TestFixtures.require(TestFixtures.Fixture.STAGED,original!=null,"native-indexed reference required");
  try(ZipFile outer=new ZipFile(api.toFile())){var module=outer.stream().filter(e->e.getName().startsWith("META-INF/jars/fabric-renderer-api-v1-")).findFirst().orElseThrow();try(ZipInputStream inner=new ZipInputStream(outer.getInputStream(module))){for(var e=inner.getNextEntry();e!=null;e=inner.getNextEntry())if(e.getName().equals("net/fabricmc/fabric/mixin/client/renderer/block/particle/ScreenEffectRendererMixin.class"))mixin=parse(inner.readAllBytes());}}
  assertNotNull(mixin);return new Inputs(mixin,current,original);
 }
 private static ClassNode parse(byte[] bytes){ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);return node;}
}
