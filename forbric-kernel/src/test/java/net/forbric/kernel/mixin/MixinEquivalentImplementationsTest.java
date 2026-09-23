package net.forbric.kernel.mixin;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.forbric.api.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
@ResourceLock("ModCatalog")
class MixinEquivalentImplementationsTest {
 private static final String READER="net/minecraft/server/packs/resources/SimpleJsonResourceReloadListener";
 private static final String CONFIG="fabric-resource-conditions-api-v1.mixins.json";
 @BeforeEach @AfterEach void reset(){MixinCompatibility.reset();CompatibilityFindings.reset();MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned(CONFIG,"conditions",Ecosystem.FABRIC)));}
 private ClassNode mixin()throws Exception{return StagedFabricMixinFixture.mixin("fabric-resource-conditions-api-v1",MixinEquivalentImplementations.CONDITIONS.replace('.','/'));}
 private ClassNode reader(boolean repair)throws Exception{
  ClassNode raw=StagedFabricMixinFixture.game(READER,false);if(!repair)return raw;
  byte[] bytes=new net.forbric.kernel.transform.ForbricMergedBaseCompatTransformer().transform(READER.replace('/','.'),StagedFabricMixinFixture.bytes(raw),null);return MixinFit.parse(bytes);
 }
 private String proof(ClassNode target,ClassNode mixin){MethodNode original=StagedFabricMixinFixture.method(mixin,"skipData");return MixinEquivalentImplementations.proof(mixin.name.replace('/','.'),original.name,original.desc,MixinInstructionFingerprint.hash(original),target);}
 @Test void actualUpstreamSkipContractAndBothActualRepairedReadersEstablishReplacement()throws Exception{
  assertNull(proof(reader(false),mixin()));assertNotNull(proof(reader(true),mixin()));
 }
 @Test void aMissingFilterOrChangedNativeConsumerCannotBeCalledEquivalent()throws Exception{
  ClassNode target=reader(true),mixin=mixin();
  for(MethodNode m:target.methods)for(var i:m.instructions.toArray())if(i instanceof MethodInsnNode c&&c.name.equals("ifSuccessWithoutAForeignSkipMarker")){m.instructions.remove(c);assertNull(proof(target,mixin));return;}
  fail("actual filter fixture absent");
 }
 @Test void changesToEitherNativeConsumerInvalidateTheProof()throws Exception{
  for(String name:List.of("lambda$scanDirectory$0","lambda$scanDirectoryWithModifier$0")){
   ClassNode target=reader(true);MethodNode consumer=target.methods.stream().filter(m->m.name.equals(name)&&m.desc.endsWith("Ljava/util/Optional;)V")).findFirst().orElseThrow();consumer.instructions.insert(new InsnNode(Opcodes.NOP));assertNull(proof(target,mixin()));
  }
 }
 @Test void unknownHandlerBehaviorCannotBorrowTheKnownReplacement()throws Exception{
  ClassNode mixin=mixin();StagedFabricMixinFixture.method(mixin,"skipData").instructions.insert(new InsnNode(Opcodes.NOP));assertNull(proof(reader(true),mixin));
 }
 @Test void finalLedgerReportsTheReplacementAndRevertsToConfirmedWhenItsWitnessIsMissing()throws Exception{
  MixinCompatibility.rememberOriginalConfig(CONFIG,"{\"required\":true,\"package\":\"net.fabricmc.fabric.mixin.resource.conditions\",\"mixins\":[\"SimpleJsonResourceReloadListenerMixin\"],\"injectors\":{\"defaultRequire\":1}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  ClassNode mixin=mixin();FinalMixinApplications.remember(mixin);ClassNode target=reader(true);
  MethodNode original=StagedFabricMixinFixture.method(mixin,"skipData"),merged=new MethodNode(original.access,original.name,original.desc,original.signature,original.exceptions.toArray(String[]::new));original.accept(merged);merged.name="handler$test$skipData";
  AnnotationNode annotation=new AnnotationNode("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;");annotation.values=new ArrayList<>(List.of("mixin",MixinEquivalentImplementations.CONDITIONS));merged.visibleAnnotations=List.of(annotation);target.methods.add(merged);
  var names=(FinalMixinApplications.Renames)(m,n,d)->n.equals("skipData")?List.of(new FinalMixinApplications.Renamed(merged.name,merged.desc)):List.of();
  FinalMixinApplications.observe(READER.replace('/','.'),StagedFabricMixinFixture.bytes(target),names);
  assertTrue(CompatibilityFindings.confirmedRequired().isEmpty());assertTrue(CompatibilityFindings.all().stream().anyMatch(f->f.id().startsWith("mixin-injector:")&&f.confidence()==CompatibilityFinding.Confidence.RESOLVED));
  for(MethodNode method:target.methods)for(var i:method.instructions)if(i instanceof MethodInsnNode c&&c.name.equals("ifSuccessWithoutAForeignSkipMarker")){c.name="unproved";break;}
  FinalMixinApplications.observe(READER.replace('/','.'),StagedFabricMixinFixture.bytes(target),names);assertEquals(1,CompatibilityFindings.confirmedRequired().size());
 }
 @Test void fingerprintsIgnoreOnlyMetadataNotExecutableInstructions()throws Exception{
  MethodNode method=StagedFabricMixinFixture.method(mixin(),"skipData");String hash=MixinInstructionFingerprint.hash(method);
  method.maxStack+=10;method.maxLocals+=10;method.access|=Opcodes.ACC_SYNTHETIC;
  LabelNode label=new LabelNode();method.instructions.insert(label);method.instructions.insert(label,new LineNumberNode(999,label));
  assertEquals(hash,MixinInstructionFingerprint.hash(method));method.instructions.insert(new InsnNode(Opcodes.NOP));assertNotEquals(hash,MixinInstructionFingerprint.hash(method));
 }
}
