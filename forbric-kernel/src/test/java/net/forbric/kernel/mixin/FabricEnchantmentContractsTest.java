package net.forbric.kernel.mixin;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.forbric.kernel.transform.FabricItemContractTransformer;
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
  // A default that decides anything else than vanilla's two decisions on the PRIMARY test is not the contract.
  ClassNode node=api();StagedFabricMixinFixture.method(node,"canBeEnchantedWith").instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC,"other/Effects","touch","()V",false));bytes=StagedFabricMixinFixture.bytes(node);assertSame(bytes,transformer.transform(name,bytes,null));
 }
 /** The contract is the default's data flow, not one release's bytes: another spelling of the same decision is rewritten. */
 @Test void theSameDecisionSpelledAnotherWayIsRewrittenAndAnotherDecisionIsNot()throws Exception{
  String name=FabricItemContractTransformer.API.replace('/','.'),context="net/fabricmc/fabric/api/item/v1/EnchantingContext",enchantment="net/minecraft/world/item/enchantment/Enchantment",stack="net/minecraft/world/item/ItemStack";
  for(int variant=0;variant<4;variant++){
   ClassNode node=api();MethodNode method=StagedFabricMixinFixture.method(node,"canBeEnchantedWith");method.instructions.clear();method.localVariables=null;method.tryCatchBlocks.clear();var c=method.instructions;
   // the enchantment read once into a local, the test written the other way round (IF_ACMPEQ), the answers returned on two exits
   c.add(new VarInsnNode(Opcodes.ALOAD,2));c.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,"net/minecraft/core/Holder","value","()Ljava/lang/Object;",true));c.add(new TypeInsnNode(Opcodes.CHECKCAST,enchantment));c.add(new VarInsnNode(Opcodes.ASTORE,4));
   LabelNode primary=new LabelNode();
   c.add(new FieldInsnNode(Opcodes.GETSTATIC,context,"PRIMARY","L"+context+";"));c.add(new VarInsnNode(Opcodes.ALOAD,3));c.add(new JumpInsnNode(Opcodes.IF_ACMPEQ,primary));
   // 1: the decisions swapped; 2: canEnchant asked about another stack (null); 3: the test made on a constant, not the context
   String otherwise=variant==1?"isPrimaryItem":"canEnchant",onPrimary=variant==1?"canEnchant":"isPrimaryItem";
   c.add(new VarInsnNode(Opcodes.ALOAD,4));c.add(variant==2?new InsnNode(Opcodes.ACONST_NULL):new VarInsnNode(Opcodes.ALOAD,1));c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,enchantment,otherwise,"(L"+stack+";)Z",false));c.add(new InsnNode(Opcodes.IRETURN));
   c.add(primary);c.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));c.add(new VarInsnNode(Opcodes.ALOAD,4));c.add(new VarInsnNode(Opcodes.ALOAD,1));c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,enchantment,onPrimary,"(L"+stack+";)Z",false));c.add(new InsnNode(Opcodes.IRETURN));
   if(variant==3){c.remove(c.get(5));c.insert(c.get(4),new InsnNode(Opcodes.ACONST_NULL));}
   method.maxLocals=5;method.maxStack=2;
   byte[] bytes=StagedFabricMixinFixture.bytes(node);byte[] out=transformer.transform(name,bytes,null);
   if(variant==0){assertNotSame(bytes,out);MethodNode rewritten=StagedFabricMixinFixture.method(MixinFit.parse(out),"canBeEnchantedWith");
    assertEquals(1,count(rewritten,FabricItemContractTransformer.NATIVE,"supportsEnchantment"));assertEquals(1,count(rewritten,FabricItemContractTransformer.NATIVE,"isPrimaryItemFor"));}
   else assertSame(bytes,out,"variant "+variant+" decides something else");
  }
 }
 private int count(MethodNode method,String owner,String name){int n=0;for(var i:method.instructions)if(i instanceof MethodInsnNode c&&c.owner.equals(owner)&&c.name.equals(name))n++;return n;}
}
