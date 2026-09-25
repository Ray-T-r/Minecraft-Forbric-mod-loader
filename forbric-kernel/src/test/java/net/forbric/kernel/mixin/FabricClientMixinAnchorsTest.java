package net.forbric.kernel.mixin;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
@org.junit.jupiter.api.parallel.ResourceLock("system-properties")
class FabricClientMixinAnchorsTest {
 private static final String CHUNK="net/minecraft/world/level/chunk/LevelChunk",RENDER="net/minecraft/client/renderer/LevelRenderer";
 private ClassNode lifecycle()throws Exception{return StagedFabricMixinFixture.mixin("fabric-lifecycle-events-v1","net/fabricmc/fabric/mixin/event/lifecycle/client/LevelChunkMixin");}
 private ClassNode renderer()throws Exception{return StagedFabricMixinFixture.mixin("fabric-renderer-api-v1","net/fabricmc/fabric/mixin/client/renderer/block/render/LevelRendererMixin");}
 @AfterEach void clear(){System.clearProperty(FabricClientMixinAnchors.PROPERTY);System.clearProperty(net.forbric.kernel.transform.FabricItemContractTransformer.PROPERTY);}
 @Test void removedBlockEntityTargetsItsActualMapAndNeverThePendingNbtMap()throws Exception{
  ClassNode mixin=lifecycle(),target=StagedFabricMixinFixture.game(CHUNK,false);assertEquals(1,FabricClientMixinAnchors.adapt(mixin,n->target));
  MethodNode method=mixin.methods.stream().filter(m->m.name.equals("onRemoveBlockEntity")&&m.desc.equals("(Ljava/util/Map;Ljava/lang/Object;)Ljava/lang/Object;")).findFirst().orElseThrow();AnnotationNode inject=MixinFit.injectorOf(method);
  assertNull(MixinFit.value(inject,"slice"));assertEquals(0,MixinFit.value(MixinFit.atNodes(inject).getFirst(),"ordinal"));assertEquals(0,FabricClientMixinAnchors.adapt(mixin,n->target));
 }
 @Test void actualDedicatedServerRemovalUsesTheSameProvenMapWithoutChangingItsCallbackBody()throws Exception{
  ClassNode mixin=StagedFabricMixinFixture.mixin("fabric-lifecycle-events-v1","net/fabricmc/fabric/mixin/event/lifecycle/server/LevelChunkMixin"),target=StagedFabricMixinFixture.game(CHUNK,false);
  MethodNode handler=mixin.methods.stream().filter(m->m.name.equals("onRemoveBlockEntity")&&m.desc.equals("(Ljava/util/Map;Ljava/lang/Object;)Ljava/lang/Object;")).findFirst().orElseThrow();String body=MixinInstructionFingerprint.hash(handler);
  assertEquals(1,FabricClientMixinAnchors.adapt(mixin,n->target));AnnotationNode inject=MixinFit.injectorOf(handler);assertNull(MixinFit.value(inject,"slice"));assertEquals(0,MixinFit.value(MixinFit.atNodes(inject).getFirst(),"ordinal"));assertEquals(body,MixinInstructionFingerprint.hash(handler));
  assertEquals(0,FabricClientMixinAnchors.adapt(mixin,n->target));
 }
 @Test void wrongMapOrCallbackGroupCannotBorrowTheRemovalAnchor()throws Exception{
  ClassNode target=StagedFabricMixinFixture.game(CHUNK,false);for(MethodNode m:target.methods)if(m.name.equals("getBlockEntity"))for(var i:m.instructions)if(i instanceof FieldInsnNode f&&f.name.equals("blockEntities"))f.name="unprovedMap";
  assertEquals(0,FabricClientMixinAnchors.adapt(lifecycle(),n->target));
  ClassNode mixin=lifecycle(),real=StagedFabricMixinFixture.game(CHUNK,false);mixin.methods.stream().filter(m->m.name.equals("onRemoveBlockEntity")&&m.desc.equals("(Ljava/util/Map;Ljava/lang/Object;)Ljava/lang/Object;")).findFirst().orElseThrow().visibleAnnotations.add(new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Group;"));assertEquals(0,FabricClientMixinAnchors.adapt(mixin,n->real));
 }
 @Test void onlyTheActualNoopRedirectCanSuppressTheContextExpandedRenderingCall()throws Exception{
  ClassNode mixin=renderer(),target=StagedFabricMixinFixture.game(RENDER,false);assertEquals(1,FabricClientMixinAnchors.adapt(mixin,n->target));MethodNode handler=StagedFabricMixinFixture.method(mixin,"cancelCollectParts");assertEquals(6,Type.getArgumentTypes(handler.desc).length);assertEquals(7,handler.maxLocals);
  new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(mixin.name,handler);
  ClassNode changed=renderer();StagedFabricMixinFixture.method(changed,"cancelCollectParts").instructions.insert(new InsnNode(Opcodes.NOP));assertEquals(0,FabricClientMixinAnchors.adapt(changed,n->target));
 }
 @Test void theActualMiningHandlerHasOneNativeDecisionAndOneExplicitFabricFallback()throws Exception{
  ClassNode mixin=StagedFabricMixinFixture.mixin("fabric-item-api-v1","net/fabricmc/fabric/mixin/item/client/MultiPlayerGameModeMixin"),target=StagedFabricMixinFixture.game("net/minecraft/client/multiplayer/MultiPlayerGameMode",false);
  assertEquals(1,FabricMiningMixinAdapter.adapt(mixin,n->target));MethodNode handler=StagedFabricMixinFixture.method(mixin,"fabricItemContinueBlockBreakingInject");int nativeCalls=0,fabricCalls=0;
  for(var i:handler.instructions)if(i instanceof MethodInsnNode c){if(c.name.equals("shouldCauseBlockBreakReset"))nativeCalls++;if(c.name.equals("allowContinuingBlockBreaking"))fabricCalls++;}
  assertEquals(1,nativeCalls);assertEquals(1,fabricCalls);new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(mixin.name,handler);
  assertEquals(0,FabricMiningMixinAdapter.adapt(mixin,n->target));
 }
 private ClassNode screens()throws Exception{return StagedFabricMixinFixture.mixin("fabric-screen-api-v1","net/fabricmc/fabric/mixin/screen/GuiMixin");}
 @Test void screenExtractEventsBracketNeoForgesScreenStackCall()throws Exception{
  ClassNode mixin=screens(),gui=StagedFabricMixinFixture.game("net/minecraft/client/gui/Gui",false);
  assertEquals(1,FabricClientMixinAnchors.adapt(mixin,n->gui));
  assertNull(MixinFit.injectorOf(StagedFabricMixinFixture.method(mixin,"onExtractGui")),"the dead direct-call wrap is gone");
  MethodNode moved=StagedFabricMixinFixture.method(mixin,"forbric$onExtractScreens");
  assertEquals(List.of("extractRenderState"),MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(moved),"method")));
  assertEquals("Lnet/neoforged/neoforge/client/ClientHooks;extractScreen(Lnet/minecraft/client/gui/screens/Screen;Ljava/util/Stack;Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
    MixinFit.value(StagedFabricMixinFixture.at(mixin,"forbric$onExtractScreens"),"target"));
  List<String> order=new ArrayList<>();for(var i:moved.instructions)if(i instanceof MethodInsnNode c&&(c.name.equals("beforeExtract")||c.name.equals("afterExtract")||c.name.equals("call"))&&c.getOpcode()!=Opcodes.INVOKESTATIC)order.add(c.name);
  assertEquals(List.of("beforeExtract","call","afterExtract"),order);
  new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(mixin.name,moved);
  assertEquals(0,FabricClientMixinAnchors.adapt(mixin,n->gui),"second adaptation is a no-op");
 }
 @Test void screenExtractIsLeftAloneWhereFabricsOwnAnchorExistsOrTheHandlerChanged()throws Exception{
  ClassNode vanilla=StagedFabricMixinFixture.game("net/minecraft/client/gui/Gui",true);assertEquals(0,FabricClientMixinAnchors.adapt(screens(),n->vanilla));
  ClassNode gui=StagedFabricMixinFixture.game("net/minecraft/client/gui/Gui",false),changed=screens();
  StagedFabricMixinFixture.method(changed,"onExtractGui").instructions.insert(new MethodInsnNode(Opcodes.INVOKEINTERFACE,"com/llamalad7/mixinextras/injector/wrapoperation/Operation","call","([Ljava/lang/Object;)Ljava/lang/Object;",true));
  assertEquals(0,FabricClientMixinAnchors.adapt(changed,n->gui),"a handler that draws twice is not reimplemented");
 }
 @Test void nativeVanillaAndDisabledClientAdaptersRemainUnchanged()throws Exception{
	ClassNode vanillaChunk=StagedFabricMixinFixture.game(CHUNK,true);assertEquals(0,FabricClientMixinAnchors.adapt(lifecycle(),n->vanillaChunk));
  ClassNode vanilla=StagedFabricMixinFixture.game(RENDER,true);assertEquals(0,FabricClientMixinAnchors.adapt(renderer(),n->vanilla));
  System.setProperty(FabricClientMixinAnchors.PROPERTY,"off");ClassNode target=StagedFabricMixinFixture.game(RENDER,false);assertEquals(0,FabricClientMixinAnchors.adapt(renderer(),n->target));
  ClassNode mining=StagedFabricMixinFixture.mixin("fabric-item-api-v1","net/fabricmc/fabric/mixin/item/client/MultiPlayerGameModeMixin");System.setProperty(net.forbric.kernel.transform.FabricItemContractTransformer.PROPERTY,"off");assertEquals(0,FabricMiningMixinAdapter.adapt(mining,n->target));
 }
}
