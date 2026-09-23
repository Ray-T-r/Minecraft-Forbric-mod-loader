package net.forbric.kernel.mixin;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
@org.junit.jupiter.api.parallel.ResourceLock("system-properties")
class FabricServerLanguageMixinAdapterTest {
 private ClassNode mixin()throws Exception{return StagedFabricMixinFixture.mixin("fabric-resource-loader-v1","net/fabricmc/fabric/mixin/resource/server/LanguageMixin");}
 @AfterEach void clear(){System.clearProperty(FabricServerLanguageMixinAdapter.PROPERTY);}
 @Test void actualAdapterKeepsTheNativeMutableMapAndComponentCapture()throws Exception{
  ClassNode mixin=mixin(),target=StagedFabricMixinFixture.game("net/minecraft/locale/Language",false);
  assertEquals(2,FabricServerLanguageMixinAdapter.adapt(mixin,n->target));
  MethodNode merge=StagedFabricMixinFixture.method(mixin,"forbric$mergeFabricLanguages");assertNull(MixinFit.injectorOf(merge));
  MethodNode shim=StagedFabricMixinFixture.method(mixin,"forbric$captureLanguageMap");assertEquals("(Ljava/util/Map;Ljava/util/Map;)V",shim.desc);
  assertEquals(1,java.util.Arrays.stream(shim.instructions.toArray()).filter(i->i instanceof MethodInsnNode c&&c.name.equals("captureLanguageMap")).count());
  new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(mixin.name,shim);
  MethodNode read=StagedFabricMixinFixture.method(mixin,"readCorrectVanillaResource");assertTrue(MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(read),"method")).getFirst().contains("BiConsumer;Ljava/util/function/BiConsumer;"));
  assertEquals(0,FabricServerLanguageMixinAdapter.adapt(mixin,n->target));
 }
 @Test void nativeVanillaAndDisabledAdapterRemainUnchanged()throws Exception{
  ClassNode mixin=mixin(),nativeBody=StagedFabricMixinFixture.game("net/minecraft/locale/Language",true);assertEquals(0,FabricServerLanguageMixinAdapter.adapt(mixin,n->nativeBody));
  System.setProperty(FabricServerLanguageMixinAdapter.PROPERTY,"off");ClassNode merged=StagedFabricMixinFixture.game("net/minecraft/locale/Language",false);assertEquals(0,FabricServerLanguageMixinAdapter.adapt(mixin,n->merged));
 }
 @Test void unknownMergeBodyOrAlternativeGroupDoesNotReplaceItsReturnContract()throws Exception{
  for(boolean group:new boolean[]{false,true}){
   ClassNode mixin=mixin(),target=StagedFabricMixinFixture.game("net/minecraft/locale/Language",false);MethodNode create=StagedFabricMixinFixture.method(mixin,"create");
   if(group)create.visibleAnnotations.add(new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Group;"));else create.instructions.insert(new InsnNode(Opcodes.NOP));
   assertEquals(1,FabricServerLanguageMixinAdapter.adapt(mixin,n->target));assertNotNull(MixinFit.injectorOf(create));
  }
 }
}
