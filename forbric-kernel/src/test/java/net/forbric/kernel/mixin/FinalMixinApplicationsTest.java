package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.forbric.api.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

@ResourceLock("ModCatalog") @ResourceLock("system-properties")
class FinalMixinApplicationsTest {
 private static final String CONFIG="application-test.json", MIXIN="example.ProbeMixin", TARGET="game.Target";
 @BeforeEach @AfterEach void reset() {MixinCompatibility.reset();CompatibilityFindings.reset();MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned(CONFIG,"probe",Ecosystem.FABRIC)));}
 @Test void silentlyRelaxedNecessaryInjectorIsConfirmedOnlyAfterFinalDefinition() {
  setup(1, -1, false, List.of(TARGET));suspect();
  assertEquals(0,CompatibilityFindings.confirmedRequired().size());observe(target(false,true,"handler$000$probe","()V"));
  assertEquals(1,CompatibilityFindings.confirmedRequired().size());assertTrue(CompatibilityFindings.confirmedRequired().getFirst().id().startsWith("mixin-injector:"));
 }
 @Test void explicitOptionalZeroDoesNotBecomeNecessaryFailure() {
  setup(1,0,false,List.of(TARGET));suspect();observe(target(false,true,"handler$000$probe","()V"));
  assertTrue(CompatibilityFindings.confirmedRequired().isEmpty());assertEquals(CompatibilityFinding.Confidence.RESOLVED,whole().confidence());
 }
 @Test void missingDefaultMeansZeroAsInNativeMixin() {
  config(null);remember(-1,false,List.of(TARGET));suspect();observe(target(false,true,"handler$000$probe","()V"));
  assertTrue(CompatibilityFindings.confirmedRequired().isEmpty());assertEquals(CompatibilityFinding.Confidence.RESOLVED,whole().confidence());
 }
 @Test void finalAdapterAttachmentDischargesSuspicion() {
  setup(1,-1,false,List.of(TARGET));suspect();observe(target(true,true,"handler$000$probe","()V"));
  assertEquals(CompatibilityFinding.Confidence.RESOLVED,whole().confidence());assertTrue(CompatibilityFindings.confirmedRequired().isEmpty());
 }
 @Test void aPluginDecliningTheMixinDoesNotLookLikeZeroInjection() {
  setup(1,-1,false,List.of(TARGET));suspect();MixinCompatibility.resolve(CONFIG,MIXIN,"plugin deliberately declined");
  observe(target(false,false,"handler$000$probe","()V"));assertEquals(CompatibilityFinding.Confidence.RESOLVED,whole().confidence());assertTrue(CompatibilityFindings.confirmedRequired().isEmpty());
 }
 @Test void groupedAlternativesRemainUncertain() {
  setup(1,-1,true,List.of(TARGET));suspect();observe(target(false,true,"handler$000$probe","()V"));
  assertEquals(CompatibilityFinding.Confidence.SUSPECTED,whole().confidence());assertTrue(CompatibilityFindings.confirmedRequired().isEmpty());
 }
 @Test void unknownRenamingAndDifferentDescriptorsCannotProveLossOrRecovery() {
  setup(1,-1,false,List.of(TARGET));suspect();observe(target(true,true,"handler$000$probe","(I)V"));
  assertEquals(CompatibilityFinding.Confidence.SUSPECTED,whole().confidence());assertTrue(CompatibilityFindings.confirmedRequired().isEmpty());
 }
 @Test void otherTargetsMustAlsoBeObservedBeforeResolvingWholeMixin() {
  setup(1,-1,false,List.of(TARGET,"game.Other"));suspect();observe(target(true,true,"handler$000$probe","()V"));
  assertEquals(CompatibilityFinding.Confidence.SUSPECTED,whole().confidence());
 }
 @Test void attachmentsDoNotEraseAnIndependentConfirmedApplyFailure() {
  setup(1,-1,false,List.of(TARGET));MixinCompatibility.record(CONFIG,MIXIN,"other apply failure",CompatibilityFinding.Confidence.CONFIRMED,true,List.of("failure"));
  observe(target(true,true,"handler$000$probe","()V"));assertEquals(CompatibilityFinding.Confidence.CONFIRMED,whole().confidence());
 }
 @Test void selfRecursionIsNotAnAttachmentAndMethodHandleIs() {
  setup(1,-1,false,List.of(TARGET));ClassNode target=target(false,true,"handler$000$probe","()V");
  target.methods.getFirst().instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC,"game/Target","handler$000$probe","()V",false));
  observe(target);assertEquals(1,CompatibilityFindings.confirmedRequired().size());
  MethodNode use=new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"use","()V",null,null);
  use.instructions.add(new LdcInsnNode(new Handle(Opcodes.H_INVOKESTATIC,"game/Target","handler$000$probe","()V",false)));use.instructions.add(new InsnNode(Opcodes.POP));use.instructions.add(new InsnNode(Opcodes.RETURN));target.methods.add(use);
  observe(target);assertTrue(CompatibilityFindings.confirmedRequired().isEmpty());
 }
 @Test void aSecondNecessaryHandlerFailureCannotBeHiddenByTheFirstAttachment() {
  config(1);ClassNode m=mixin(-1,false,List.of(TARGET));MethodNode second=injector("second",-1,false);m.methods.add(second);FinalMixinApplications.remember(m);suspect();
  ClassNode target=target(true,true,"handler$000$probe","()V");MethodNode bad=new MethodNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC,"handler$001$second","()V",null,null);bad.instructions.add(new InsnNode(Opcodes.RETURN));bad.visibleAnnotations=new ArrayList<>(List.of(annotation("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;","mixin",MIXIN)));target.methods.add(bad);
  observe(target);assertEquals(1,CompatibilityFindings.confirmedRequired().size());assertEquals(CompatibilityFinding.Confidence.SUSPECTED,whole().confidence());
 }
 @Test void aPartiallyObservedMinimumRemainsUnproven() {
  setup(2,-1,false,List.of(TARGET));suspect();observe(target(true,true,"handler$000$probe","()V"));
  assertEquals(CompatibilityFinding.Confidence.SUSPECTED,whole().confidence());assertTrue(CompatibilityFindings.confirmedRequired().isEmpty());
 }
 private void setup(Integer minimum,int require,boolean group,List<String> targets){config(minimum);remember(require,group,targets);}
 private void config(Integer minimum){String json="{\"required\":true,\"package\":\"example\",\"mixins\":[\"ProbeMixin\"]"+(minimum==null?"":",\"injectors\":{\"defaultRequire\":"+minimum+"}")+"}";MixinCompatibility.rememberOriginalConfig(CONFIG,json.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 private void remember(int require,boolean group,List<String> targets){FinalMixinApplications.remember(mixin(require,group,targets));}
 private ClassNode mixin(int require,boolean group,List<String> targets){ClassNode n=new ClassNode();n.name=MIXIN.replace('.','/');n.visibleAnnotations=List.of(annotation("Lorg/spongepowered/asm/mixin/Mixin;","targets",targets));n.methods.add(injector("probe",require,group));return n;}
 private MethodNode injector(String name,int require,boolean group){MethodNode m=new MethodNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC,name,"()V",null,null);m.visibleAnnotations=new ArrayList<>(List.of(annotation("Lorg/spongepowered/asm/mixin/injection/Inject;","require",require)));if(group)m.visibleAnnotations.add(annotation("Lorg/spongepowered/asm/mixin/injection/Group;","name","alternatives"));return m;}
 private ClassNode target(boolean call,boolean merged,String name,String desc){ClassNode n=new ClassNode();n.version=Opcodes.V21;n.name=TARGET.replace('.','/');n.superName="java/lang/Object";n.access=Opcodes.ACC_PUBLIC;MethodNode h=new MethodNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC,name,desc,null,null);h.instructions.add(new InsnNode(Opcodes.RETURN));if(merged)h.visibleAnnotations=List.of(annotation("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;","mixin",MIXIN));n.methods.add(h);if(call){MethodNode m=new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"caller","()V",null,null);m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,n.name,name,desc,false));m.instructions.add(new InsnNode(Opcodes.RETURN));n.methods.add(m);}return n;}
 private void observe(ClassNode n){ClassWriter w=new ClassWriter(0);n.accept(w);FinalMixinApplications.observe(TARGET,w.toByteArray(),(mixin,name,desc)->List.of(new FinalMixinApplications.Renamed(name.equals("second")?"handler$001$second":"handler$000$probe",desc)));}
 private void suspect(){MixinCompatibility.record(CONFIG,MIXIN,"preflight unresolved anchors",CompatibilityFinding.Confidence.SUSPECTED,true,List.of("probe"));}
 private CompatibilityFinding whole(){return CompatibilityFindings.all().stream().filter(f->f.id().equals(MixinCompatibility.id(CONFIG,MIXIN))).findFirst().orElseThrow();}
 private static AnnotationNode annotation(String desc,String key,Object value){AnnotationNode a=new AnnotationNode(desc);a.values=new ArrayList<>(List.of(key,value));return a;}
}
