package net.forbric.kernel.mixin;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
@ResourceLock("system-properties")
class FabricEntityMixinAnchorsTest {
 private static final String ROOT="net/fabricmc/fabric/mixin/entity/event/";
 @AfterEach void reset(){System.clearProperty(FabricEntityMixinAnchors.PROPERTY);}
 private ClassNode beds()throws Exception{return StagedFabricMixinFixture.mixin("fabric-entity-events-v1",ROOT+"LivingEntityMixin");}
 @Test void actualBedBridgePreservesNativeCustomBedsAndFabricHandledOccupation()throws Exception{
  ClassNode mixin=beds(),target=StagedFabricMixinFixture.living(false);assertEquals(2,FabricEntityMixinAnchors.adapt(mixin,n->target),"occupation and sleeping direction");
  assertNull(MixinFit.injectorOf(StagedFabricMixinFixture.method(mixin,"setOccupiedState")));
  MethodNode bridge=StagedFabricMixinFixture.method(mixin,"forbric$setBedOccupied");
  assertTrue(String.valueOf(MixinFit.value(StagedFabricMixinFixture.at(mixin,"forbric$setBedOccupied"),"target")).contains("BlockState;setBedOccupied"));
  new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(mixin.name,bridge);
  assertEquals(0,FabricEntityMixinAnchors.adapt(mixin,n->target));
 }
 @Test void unknownOccupationHandlerBodyIsNotReimplemented()throws Exception{
  ClassNode mixin=beds(),target=StagedFabricMixinFixture.living(false);
  StagedFabricMixinFixture.method(mixin,"setOccupiedState").instructions.insert(new InsnNode(Opcodes.NOP));
  assertEquals(1,FabricEntityMixinAnchors.adapt(mixin,n->target),"only the sleeping direction");assertNotNull(MixinFit.injectorOf(StagedFabricMixinFixture.method(mixin,"setOccupiedState")));
 }
 @Test void sleepAndOccupationAlternativesKeepTheirGroupContract()throws Exception{
  ClassNode bed=beds(),living=StagedFabricMixinFixture.living(false);
  StagedFabricMixinFixture.method(bed,"setOccupiedState").visibleAnnotations.add(new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Group;"));
  assertEquals(1,FabricEntityMixinAnchors.adapt(bed,n->living),"the grouped occupation stays; the sleeping direction is its own handler");
  assertNotNull(MixinFit.injectorOf(StagedFabricMixinFixture.method(bed,"setOccupiedState")));
 }
 @Test void nativeVanillaBodiesAreUnchanged()throws Exception{
  ClassNode vanilla=StagedFabricMixinFixture.living(true);
  for(ClassNode mixin:List.of(beds())){byte[] before=StagedFabricMixinFixture.bytes(mixin);assertEquals(0,FabricEntityMixinAnchors.adapt(mixin,n->vanilla));assertArrayEquals(before,StagedFabricMixinFixture.bytes(mixin));}
 }
 @Test void switchAndForeignMixinStayUntouched()throws Exception{
  ClassNode target=StagedFabricMixinFixture.living(false),mixin=beds();byte[] before=StagedFabricMixinFixture.bytes(mixin);
  System.setProperty(FabricEntityMixinAnchors.PROPERTY,"off");assertEquals(0,FabricEntityMixinAnchors.adapt(mixin,n->target));assertArrayEquals(before,StagedFabricMixinFixture.bytes(mixin));
  System.clearProperty(FabricEntityMixinAnchors.PROPERTY);mixin.name="another/BedMixin";assertEquals(0,FabricEntityMixinAnchors.adapt(mixin,n->target));
 }
 /** Fabric's sleeping direction modifies NeoForge's answer: every return, the event asked only with a sleeping position. */
 @Test void sleepingDirectionModifiesNeoForgesBedAnswer()throws Exception{
  ClassNode mixin=beds(),target=StagedFabricMixinFixture.living(false);
  FabricEntityMixinAnchors.adapt(mixin,n->target);
  assertNull(MixinFit.injectorOf(StagedFabricMixinFixture.method(mixin,"onGetSleepingDirection")),"the wrap that bound nowhere is gone");
  MethodNode handler=StagedFabricMixinFixture.method(mixin,"forbric$modifySleepingDirection");
  AnnotationNode modify=MixinFit.injectorOf(handler);
  assertEquals("Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;",modify.desc);
  assertEquals(List.of("getBedOrientation()Lnet/minecraft/core/Direction;"),MixinFit.stringList(MixinFit.value(modify,"method")));
  assertEquals("RETURN",MixinFit.value(StagedFabricMixinFixture.at(mixin,"forbric$modifySleepingDirection"),"value"));
  new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(mixin.name,handler);
  assertTrue(java.util.Arrays.stream(handler.instructions.toArray()).anyMatch(i->i instanceof MethodInsnNode m&&m.name.equals("modifySleepDirection")));
  assertEquals(0,FabricEntityMixinAnchors.adapt(mixin,n->target),"a second pass changes nothing");
 }
 @Test void aChangedSleepDirectionHandlerOrASecondBedQuestionIsNotGuessed()throws Exception{
  ClassNode mixin=beds(),target=StagedFabricMixinFixture.living(false);
  StagedFabricMixinFixture.method(mixin,"onGetSleepingDirection").instructions.insert(new InsnNode(Opcodes.NOP));
  FabricEntityMixinAnchors.adapt(mixin,n->target);
  assertNotNull(MixinFit.injectorOf(StagedFabricMixinFixture.method(mixin,"onGetSleepingDirection")));
  assertTrue(mixin.methods.stream().noneMatch(m->m.name.equals("forbric$modifySleepingDirection")));
  ClassNode twice=StagedFabricMixinFixture.living(false),fresh=beds();
  MethodNode host=twice.methods.stream().filter(m->m.name.equals("getBedOrientation")).findFirst().orElseThrow();
  for(var i:host.instructions)if(i instanceof MethodInsnNode m&&m.name.equals("getBedDirection")){host.instructions.insert(i,new InsnNode(Opcodes.POP));host.instructions.insert(i,m.clone(null));
   host.instructions.insert(i,new InsnNode(Opcodes.DUP));break;}
  FabricEntityMixinAnchors.adapt(fresh,n->twice);
  assertTrue(fresh.methods.stream().noneMatch(m->m.name.equals("forbric$modifySleepingDirection")),"two native bed answers: not the body this was written against");
 }
}
