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
  ClassNode mixin=beds(),target=StagedFabricMixinFixture.living(false);assertEquals(1,FabricEntityMixinAnchors.adapt(mixin,n->target),"the occupation");
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
  assertEquals(0,FabricEntityMixinAnchors.adapt(bed,n->living),"the grouped occupation stays");
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
}
