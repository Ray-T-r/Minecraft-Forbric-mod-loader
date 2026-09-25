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
 @AfterEach void reset(){System.clearProperty(FabricEntityMixinAnchors.PROPERTY);System.clearProperty(FabricEntityMixinAnchors.TICK_PROPERTY);}
 private ClassNode effects()throws Exception{return StagedFabricMixinFixture.mixin("fabric-entity-events-v1",ROOT+"effect/LivingEntityMixin");}
 private ClassNode elytra()throws Exception{return StagedFabricMixinFixture.mixin("fabric-entity-events-v1",ROOT+"elytra/LivingEntityMixin");}
 private ClassNode beds()throws Exception{return StagedFabricMixinFixture.mixin("fabric-entity-events-v1",ROOT+"LivingEntityMixin");}
 @Test void actualBedBridgePreservesNativeCustomBedsAndFabricHandledOccupation()throws Exception{
  ClassNode mixin=beds(),target=StagedFabricMixinFixture.living(false);assertEquals(1,FabricEntityMixinAnchors.adapt(mixin,n->target));
  assertNull(MixinFit.injectorOf(StagedFabricMixinFixture.method(mixin,"setOccupiedState")));
  MethodNode bridge=StagedFabricMixinFixture.method(mixin,"forbric$setBedOccupied");
  assertTrue(String.valueOf(MixinFit.value(StagedFabricMixinFixture.at(mixin,"forbric$setBedOccupied"),"target")).contains("BlockState;setBedOccupied"));
  new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(mixin.name,bridge);
  assertEquals(0,FabricEntityMixinAnchors.adapt(mixin,n->target));
 }
 @Test void unknownOccupationHandlerBodyIsNotReimplemented()throws Exception{
  ClassNode mixin=beds(),target=StagedFabricMixinFixture.living(false);
  StagedFabricMixinFixture.method(mixin,"setOccupiedState").instructions.insert(new InsnNode(Opcodes.NOP));
  assertEquals(0,FabricEntityMixinAnchors.adapt(mixin,n->target));assertNotNull(MixinFit.injectorOf(StagedFabricMixinFixture.method(mixin,"setOccupiedState")));
 }
 @Test void sleepAndOccupationAlternativesKeepTheirGroupContract()throws Exception{
  ClassNode bed=beds(),living=StagedFabricMixinFixture.living(false);
  StagedFabricMixinFixture.method(bed,"setOccupiedState").visibleAnnotations.add(new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Group;"));
  assertEquals(0,FabricEntityMixinAnchors.adapt(bed,n->living));
  ClassNode sleep=StagedFabricMixinFixture.mixin("fabric-entity-events-v1",ROOT+"ServerPlayerMixin"),player=StagedFabricMixinFixture.game("net/minecraft/server/level/ServerPlayer",false);
  StagedFabricMixinFixture.method(sleep,"hasNoMonstersNearby").visibleAnnotations.add(new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Group;"));
  assertEquals(0,FabricEntityMixinAnchors.adapt(sleep,n->player));
 }
 @Test void nearbyMonsterDecisionFollowsTheActuallyInvokedNativeLambda()throws Exception{
  ClassNode mixin=StagedFabricMixinFixture.mixin("fabric-entity-events-v1",ROOT+"ServerPlayerMixin");
  ClassNode target=StagedFabricMixinFixture.game("net/minecraft/server/level/ServerPlayer",false);
  assertEquals(1,FabricEntityMixinAnchors.adapt(mixin,n->target));
  List<String> methods=MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(StagedFabricMixinFixture.method(mixin,"hasNoMonstersNearby")),"method"));
  assertEquals(1,methods.size());assertTrue(methods.getFirst().startsWith("lambda$startSleepInBed$"));assertTrue(methods.getFirst().endsWith("(Lnet/minecraft/core/BlockPos;)Lcom/mojang/datafixers/util/Either;"));
 }
 @Test void actualEffectHandlersMoveToNativeValidationAndPreRemovalSnapshotStages()throws Exception{
  ClassNode mixin=effects(),target=StagedFabricMixinFixture.living(false);
  assertEquals(3,FabricEntityMixinAnchors.adapt(mixin,n->target));
  assertTrue(String.valueOf(MixinFit.value(StagedFabricMixinFixture.at(mixin,"beforeForceAddEffect"),"target")).contains("CommonHooks;canMobEffectBeApplied"));
  AnnotationNode remove=StagedFabricMixinFixture.at(mixin,"beforeRemoveAllEffects");assertEquals("NEW",MixinFit.value(remove,"value"));assertEquals("java/util/HashMap",MixinFit.value(remove,"target"));
  assertEquals(0,FabricEntityMixinAnchors.adapt(mixin,n->target),"second adaptation is a no-op");
 }
 @Test void clearAllVetoWrapsNeoForgesPerEffectQuestion()throws Exception{
  ClassNode mixin=effects(),target=StagedFabricMixinFixture.living(false);
  assertEquals(3,FabricEntityMixinAnchors.adapt(mixin,n->target));
  assertNull(MixinFit.injectorOf(StagedFabricMixinFixture.method(mixin,"allowRemoveAllEffects")),"the dead clear() wrap is gone");
  MethodNode handler=StagedFabricMixinFixture.method(mixin,"forbric$allowEarlyRemove");
  AnnotationNode wrap=MixinFit.injectorOf(handler);
  assertEquals("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",wrap.desc);
  assertEquals(List.of("removeAllEffects"),MixinFit.stringList(MixinFit.value(wrap,"method")));
  assertEquals("Lnet/neoforged/neoforge/event/EventHooks;onEffectRemoved(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/effect/MobEffectInstance;)Z",
    MixinFit.value(StagedFabricMixinFixture.at(mixin,"forbric$allowEarlyRemove"),"target"));
  new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(mixin.name,handler);
  assertEquals(0,FabricEntityMixinAnchors.adapt(mixin,n->target),"second adaptation is a no-op");
 }
 @Test void clearAllVetoNeedsExactlyOneNativeQuestionAndTheKnownHandler()throws Exception{
  ClassNode target=StagedFabricMixinFixture.living(false),mixin=effects();
  MethodNode remove=StagedFabricMixinFixture.method(target,"removeAllEffects");
  for(var i:remove.instructions)if(i instanceof MethodInsnNode c&&c.name.equals("onEffectRemoved")){remove.instructions.insert(i,new MethodInsnNode(Opcodes.INVOKESTATIC,c.owner,c.name,c.desc,false));break;}
  FabricEntityMixinAnchors.adapt(mixin,n->target);
  assertNotNull(MixinFit.injectorOf(StagedFabricMixinFixture.method(mixin,"allowRemoveAllEffects")),"two native questions: not guessed");
  ClassNode plain=StagedFabricMixinFixture.living(false),changed=effects();
  StagedFabricMixinFixture.method(changed,"allowRemoveAllEffects").instructions.insert(new MethodInsnNode(Opcodes.INVOKEINTERFACE,"java/util/Map","put","(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",true));
  FabricEntityMixinAnchors.adapt(changed,n->plain);
  assertTrue(changed.methods.stream().noneMatch(m->m.name.equals("forbric$allowEarlyRemove")),"a handler that does more is not reimplemented");
 }
 @Test void elytraVetoAndCustomFlightAreBeforeBothNativeAttributeAndEquipmentPaths()throws Exception{
  ClassNode mixin=elytra(),target=StagedFabricMixinFixture.living(false);
  assertEquals(2,FabricEntityMixinAnchors.adapt(mixin,n->target),"the gliding decision and the flight tick");
  AnnotationNode injector=MixinFit.injectorOf(StagedFabricMixinFixture.method(mixin,"injectElytraCheck"));
  assertEquals(List.of("canGlide(Z)Z"),MixinFit.stringList(MixinFit.value(injector,"method")));
  assertTrue(String.valueOf(MixinFit.value(StagedFabricMixinFixture.at(mixin,"injectElytraCheck"),"target")).contains("NeoForgeMod;GLIDING_FLIGHT:"));
  assertEquals(Boolean.TRUE,MixinFit.value(injector,"cancellable"));assertEquals(1,MixinFit.value(injector,"allow"));
 }
 @Test void nativeVanillaBodiesAreUnchanged()throws Exception{
  ClassNode vanilla=StagedFabricMixinFixture.living(true);
  for(ClassNode mixin:List.of(effects(),elytra(),beds())){byte[] before=StagedFabricMixinFixture.bytes(mixin);assertEquals(0,FabricEntityMixinAnchors.adapt(mixin,n->vanilla));assertArrayEquals(before,StagedFabricMixinFixture.bytes(mixin));}
 }
 @Test void switchAndForeignMixinStayUntouched()throws Exception{
  ClassNode target=StagedFabricMixinFixture.living(false),mixin=effects();byte[] before=StagedFabricMixinFixture.bytes(mixin);
  System.setProperty(FabricEntityMixinAnchors.PROPERTY,"off");assertEquals(0,FabricEntityMixinAnchors.adapt(mixin,n->target));assertArrayEquals(before,StagedFabricMixinFixture.bytes(mixin));
  System.clearProperty(FabricEntityMixinAnchors.PROPERTY);mixin.name="another/EffectMixin";assertEquals(0,FabricEntityMixinAnchors.adapt(mixin,n->target));
 }
 @Test void movedGlidingGuardOrAmbiguousRemovalAllocationIsRefused()throws Exception{
  ClassNode target=StagedFabricMixinFixture.living(false),mixin=elytra();
  MethodNode extended=target.methods.stream().filter(m->m.name.equals("canGlide")&&m.desc.equals("(Z)Z")).findFirst().orElseThrow();
  for(var i:extended.instructions)if(i instanceof FieldInsnNode f&&f.name.equals("GLIDING_FLIGHT"))f.name="UNRELATED";
  assertEquals(1,FabricEntityMixinAnchors.adapt(mixin,n->target),"only the flight tick moves");
  assertEquals(List.of("canGlide"),MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(StagedFabricMixinFixture.method(mixin,"injectElytraCheck")),"method")));
  assertTrue(String.valueOf(MixinFit.value(StagedFabricMixinFixture.at(mixin,"injectElytraCheck"),"target")).contains("EquipmentSlot;VALUES:"));
  ClassNode finalTarget=StagedFabricMixinFixture.living(false);mixin=effects();
  StagedFabricMixinFixture.method(finalTarget,"removeAllEffects").instructions.insert(new TypeInsnNode(Opcodes.NEW,"java/util/HashMap"));
  assertEquals(2,FabricEntityMixinAnchors.adapt(mixin,n->finalTarget),"force-add and the clear-all veto; the snapshot stays");
  assertEquals("INVOKE",MixinFit.value(StagedFabricMixinFixture.at(mixin,"beforeRemoveAllEffects"),"value"));
 }
 @Test void explicitAlternativeGroupAndChangedConstructorPhaseAreNotGuessed()throws Exception{
  ClassNode target=StagedFabricMixinFixture.living(false),mixin=effects();
  StagedFabricMixinFixture.method(mixin,"beforeForceAddEffect").visibleAnnotations.add(new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Group;"));
  StagedFabricMixinFixture.at(mixin,"beforeRemoveAllEffects").values.addAll(List.of("shift",new String[]{"Lorg/spongepowered/asm/mixin/injection/At$Shift;","AFTER"}));
  StagedFabricMixinFixture.method(mixin,"allowRemoveAllEffects").visibleAnnotations.add(new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Group;"));
  assertEquals(0,FabricEntityMixinAnchors.adapt(mixin,n->target));
 }
 private static final String GET_RANDOM="Lnet/minecraft/util/Util;getRandom(Ljava/util/List;Lnet/minecraft/util/RandomSource;)Ljava/lang/Object;";
 /** NeoForge put an empty-list guard before vanilla's glider-slot choice; Fabric's tick moves onto the guard. */
 @Test void elytraFlightTickPrecedesNeoForgesEmptyGliderGuard()throws Exception{
  ClassNode mixin=elytra(),target=StagedFabricMixinFixture.living(false);
  assertEquals(2,FabricEntityMixinAnchors.adapt(mixin,n->target));
  AnnotationNode injector=MixinFit.injectorOf(StagedFabricMixinFixture.method(mixin,"injectElytraTick"));
  AnnotationNode at=StagedFabricMixinFixture.at(mixin,"injectElytraTick");
  assertEquals("INVOKE",MixinFit.value(at,"value"));assertEquals("Ljava/util/List;isEmpty()Z",MixinFit.value(at,"target"));
  assertEquals(List.of("updateFallFlying()V"),MixinFit.stringList(MixinFit.value(injector,"method")));
  assertEquals(1,MixinFit.value(injector,"allow"));assertEquals(Boolean.TRUE,MixinFit.value(injector,"cancellable"));
  assertEquals(0,FabricEntityMixinAnchors.adapt(mixin,n->target),"a second pass changes nothing");
 }
 /** Every path to the slot choice passes the guard; one path through the guard skips the slot choice and reaches the glide event. */
 @Test void theMovedTickAnchorDominatesTheSlotChoiceAndIsReachedWithoutIt()throws Exception{
  MethodNode fly=StagedFabricMixinFixture.method(StagedFabricMixinFixture.living(false),"updateFallFlying");
  java.util.Map<Integer,java.util.Set<Integer>> edges=new java.util.HashMap<>();
  new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicInterpreter()){
   @Override protected void newControlFlowEdge(int from,int to){edges.computeIfAbsent(from,k->new java.util.HashSet<>()).add(to);}
  }.analyze("net/minecraft/world/entity/LivingEntity",fly);
  int guard=-1,choice=-1,glide=-1;
  for(int i=0;i<fly.instructions.size();i++){var insn=fly.instructions.get(i);
   if(insn instanceof MethodInsnNode c&&c.name.equals("isEmpty")&&c.owner.equals("java/util/List"))guard=i;
   if(insn instanceof MethodInsnNode c&&c.name.equals("getRandom"))choice=i;
   if(insn instanceof FieldInsnNode f&&f.name.equals("ELYTRA_GLIDE"))glide=i;}
  assertTrue(guard>0&&choice>guard&&glide>0);
  assertFalse(reaches(edges,0,choice,guard),"the slot choice is reachable without passing the guard");
  assertTrue(reaches(edges,0,guard,-1));
  int jump=guard+1;while(!(fly.instructions.get(jump) instanceof JumpInsnNode))jump++;
  int skip=fly.instructions.indexOf(((JumpInsnNode)fly.instructions.get(jump)).label);
  assertTrue(reaches(edges,skip,glide,choice),"the guard's skip reaches the glide event without the slot choice");
 }
 private static boolean reaches(java.util.Map<Integer,java.util.Set<Integer>> edges,int from,int to,int avoid){
  java.util.Deque<Integer> work=new java.util.ArrayDeque<>(List.of(from));java.util.Set<Integer> seen=new java.util.HashSet<>();
  while(!work.isEmpty()){int n=work.pop();if(n==to)return true;if(n==avoid||!seen.add(n))continue;work.addAll(edges.getOrDefault(n,java.util.Set.of()));}
  return false;
 }
 @Test void tickGuardShapesThatAreNotProvenAreNotGuessed()throws Exception{
  java.util.List<java.util.function.Consumer<ClassNode[]>> drifts=List.of(
   c->{MethodNode fly=StagedFabricMixinFixture.method(c[1],"updateFallFlying");fly.instructions.insert(new MethodInsnNode(Opcodes.INVOKEINTERFACE,"java/util/List","isEmpty","()Z",true));},
   c->{MethodNode fly=StagedFabricMixinFixture.method(c[1],"updateFallFlying");for(var i:fly.instructions)if(i instanceof MethodInsnNode m&&m.name.equals("isEmpty")){((VarInsnNode)i.getPrevious()).var=0;}},
   c->{MethodNode fly=StagedFabricMixinFixture.method(c[1],"updateFallFlying");for(var i:fly.instructions)if(i instanceof MethodInsnNode m&&m.name.equals("isEmpty")){((JumpInsnNode)i.getNext()).label=new LabelNode();}},
   c->StagedFabricMixinFixture.at(c[0],"injectElytraTick").values.addAll(List.of("shift",new String[]{"Lorg/spongepowered/asm/mixin/injection/At$Shift;","AFTER"})),
   c->StagedFabricMixinFixture.method(c[0],"injectElytraTick").visibleAnnotations.add(new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Group;")),
   c->StagedFabricMixinFixture.at(c[0],"injectElytraTick").values.addAll(List.of("ordinal",0)));
  for(var drift:drifts){
   ClassNode mixin=elytra(),target=StagedFabricMixinFixture.living(false);
   drift.accept(new ClassNode[]{mixin,target});
   FabricEntityMixinAnchors.adapt(mixin,n->target);
   assertEquals(GET_RANDOM,MixinFit.value(StagedFabricMixinFixture.at(mixin,"injectElytraTick"),"target"),"a drifted shape moved the tick");
  }
 }
 @Test void tickSwitchLeavesOnlyTheGlidingDecisionMoved()throws Exception{
  System.setProperty(FabricEntityMixinAnchors.TICK_PROPERTY,"off");
  ClassNode mixin=elytra(),target=StagedFabricMixinFixture.living(false);
  assertEquals(1,FabricEntityMixinAnchors.adapt(mixin,n->target));
  assertEquals(GET_RANDOM,MixinFit.value(StagedFabricMixinFixture.at(mixin,"injectElytraTick"),"target"));
 }
}
