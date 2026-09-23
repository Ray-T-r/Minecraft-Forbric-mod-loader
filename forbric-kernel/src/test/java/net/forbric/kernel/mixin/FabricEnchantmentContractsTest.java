package net.forbric.kernel.mixin;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.forbric.kernel.transform.FabricItemContractTransformer;
import net.forbric.kernel.transform.DuplicateLambdaPruneInjector;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
@ResourceLock("system-properties")
class FabricEnchantmentContractsTest {
 private final FabricItemContractTransformer transformer=new FabricItemContractTransformer(n->true);
 @AfterEach void reset(){System.clearProperty(FabricItemContractTransformer.PROPERTY);}
 private ClassNode api()throws Exception{return StagedFabricMixinFixture.mixin("fabric-item-api-v1",FabricItemContractTransformer.API);}
 private ClassNode transformed(String name,ClassNode node){return MixinFit.parse(transformer.transform(name.replace('/','.'),StagedFabricMixinFixture.bytes(node),null));}
 @Test void theActualDefaultFallsBackToBothNativeItemDecisions()throws Exception{
  ClassNode changed=transformed(FabricItemContractTransformer.API,api());MethodNode method=StagedFabricMixinFixture.method(changed,"canBeEnchantedWith");
  assertEquals(1,count(method,FabricItemContractTransformer.NATIVE,"supportsEnchantment"));assertEquals(1,count(method,FabricItemContractTransformer.NATIVE,"isPrimaryItemFor"));
  assertEquals(0,count(method,"net/minecraft/world/item/enchantment/Enchantment","canEnchant"));
  new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(changed.name,method);
 }
 @Test void absentNativeApiDisabledAdapterAndUnknownDefaultBodyAreNotRewritten()throws Exception{
  byte[] bytes=StagedFabricMixinFixture.bytes(api());String name=FabricItemContractTransformer.API.replace('/','.');
  assertSame(bytes,new FabricItemContractTransformer(n->n.equals(FabricItemContractTransformer.API)).transform(name,bytes,null));
  System.setProperty(FabricItemContractTransformer.PROPERTY,"off");assertSame(bytes,transformer.transform(name,bytes,null));System.clearProperty(FabricItemContractTransformer.PROPERTY);
  ClassNode node=api();StagedFabricMixinFixture.method(node,"canBeEnchantedWith").instructions.insert(new InsnNode(Opcodes.NOP));bytes=StagedFabricMixinFixture.bytes(node);assertSame(bytes,transformer.transform(name,bytes,null));
 }
 @Test void theRealMethodReferenceGetsOneTypedWrapperWithoutChangingCaptureOrder()throws Exception{
  ClassNode node=transformed(FabricItemContractTransformer.HELPER,StagedFabricMixinFixture.game(FabricItemContractTransformer.HELPER,false));
  MethodNode bridge=StagedFabricMixinFixture.method(node,FabricItemContractTransformer.PRIMARY_HELPER);assertEquals(FabricItemContractTransformer.PRIMARY_DESC,bridge.desc);assertEquals(1,count(bridge,FabricItemContractTransformer.NATIVE,"isPrimaryItemFor"));
  int handles=0;for(var instruction:StagedFabricMixinFixture.method(node,"getAvailableEnchantmentResults").instructions)if(instruction instanceof InvokeDynamicInsnNode d)for(Object argument:d.bsmArgs)if(argument instanceof Handle h&&h.getName().equals(FabricItemContractTransformer.PRIMARY_HELPER)){handles++;assertEquals(Opcodes.H_INVOKESTATIC,h.getTag());assertEquals(FabricItemContractTransformer.PRIMARY_DESC,h.getDesc());}
  assertEquals(1,handles);byte[] bytes=StagedFabricMixinFixture.bytes(node);assertSame(bytes,transformer.transform(node.name.replace('/','.'),bytes,null));
 }
 @Test void allThreeActualFabricMixinsAttachToTheCorrespondingNativeDecision()throws Exception{
  for(String[] pair:List.of(new String[]{"EnchantCommandMixin","net/minecraft/server/commands/EnchantCommand"},new String[]{"EnchantRandomlyFunctionMixin","net/minecraft/world/level/storage/loot/functions/EnchantRandomlyFunction"},new String[]{"EnchantmentHelperMixin",FabricItemContractTransformer.HELPER})){
   ClassNode mixin=StagedFabricMixinFixture.mixin("fabric-item-api-v1","net/fabricmc/fabric/mixin/item/"+pair[0]);ClassNode target=StagedFabricMixinFixture.game(pair[1],false);
   target=MixinFit.parse(new DuplicateLambdaPruneInjector().transform(pair[1].replace('/','.'),StagedFabricMixinFixture.bytes(target),null));
   if(pair[1].equals(FabricItemContractTransformer.HELPER))target=transformed(pair[1],target);ClassNode finalTarget=target;
   assertEquals(1,FabricEnchantmentMixinAdapter.adapt(mixin,n->finalTarget),pair[0]);
   MethodNode handler=mixin.methods.stream().filter(m->MixinFit.injectorOf(m)!=null&&m.name.contains("Enchanting")).findFirst().orElseThrow();
   assertEquals(2,Type.getArgumentTypes(handler.desc).length);assertEquals(1,count(handler,FabricItemContractTransformer.STACK,"canBeEnchantedWith"));
   new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(mixin.name,handler);
   assertEquals(0,FabricEnchantmentMixinAdapter.adapt(mixin,n->finalTarget));
  }
 }
 @Test void modifiedHandlerAndAlternativeGroupKeepTheirOriginalContract()throws Exception{
  for(boolean group:new boolean[]{false,true}){
   ClassNode mixin=StagedFabricMixinFixture.mixin("fabric-item-api-v1","net/fabricmc/fabric/mixin/item/EnchantCommandMixin"),target=StagedFabricMixinFixture.game("net/minecraft/server/commands/EnchantCommand",false);
   MethodNode method=StagedFabricMixinFixture.method(mixin,"callAllowEnchantingEvent");
   if(group)method.visibleAnnotations.add(new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Group;"));else method.instructions.insert(new InsnNode(Opcodes.NOP));
   assertEquals(0,FabricEnchantmentMixinAdapter.adapt(mixin,n->target));
  }
 }
 private int count(MethodNode method,String owner,String name){int n=0;for(var i:method.instructions)if(i instanceof MethodInsnNode c&&c.owner.equals(owner)&&c.name.equals(name))n++;return n;}
}
