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
 /** Native Mixin throws InjectionError below require/defaultRequire whatever the config's `required` says. */
 @Test void aDefaultRequireMissInAnOptionalConfigIsStillANecessaryLoss() {
  config(1,false);remember(-1,false,List.of(TARGET));observe(target(false,true,"handler$000$probe","()V"));
  assertEquals(1,CompatibilityFindings.confirmedRequired().size());
  assertTrue(CompatibilityFindings.confirmedRequired().getFirst().evidence().contains("config required=false"));
 }
 @Test void anOptionalConfigsExplicitZeroStaysOptional() {
  config(1,false);remember(0,false,List.of(TARGET));observe(target(false,true,"handler$000$probe","()V"));
  assertTrue(CompatibilityFindings.all().stream().noneMatch(f->f.confidence()==CompatibilityFinding.Confidence.CONFIRMED));
 }
 /** MixinExtras injectors are InjectionInfo too: a defaultRequire miss the relaxation silenced is a confirmed loss. */
 @Test void aMissedMixinExtrasInjectorIsReconciledLikeAStandardOne() {
  config(1);remember(extras(WRAP_OPERATION));observe(target(false,true,"handler$000$probe","()V"));
  assertEquals(1,CompatibilityFindings.confirmedRequired().size());
  assertTrue(CompatibilityFindings.confirmedRequired().getFirst().id().startsWith("mixin-injector:"));
 }
 @Test void anAttachedMixinExtrasInjectorDischargesTheWholeMixinSuspicion() {
  config(1);remember(extras(MODIFY_EXPRESSION_VALUE));suspect();observe(target(true,true,"handler$000$probe","()V"));
  assertEquals(CompatibilityFinding.Confidence.RESOLVED,whole().confidence());assertTrue(CompatibilityFindings.confirmedRequired().isEmpty());
 }
 /** Sugar is still not modelled on a MixinExtras injector either. */
 @Test void aSugaredMixinExtrasHandlerWithNoVisibleAttachmentIsOnlySuspected() {
  config(1);remember(sugared(MODIFY_EXPRESSION_VALUE));observe(target(false,true,"handler$000$probe","()V"));
  assertTrue(CompatibilityFindings.confirmedRequired().isEmpty());
  assertEquals(CompatibilityFinding.Confidence.SUSPECTED,CompatibilityFindings.all().stream().filter(f->f.id().startsWith("mixin-injector:")).findFirst().orElseThrow().confidence());
 }
 /** Sugar is not modelled, so a zero-reference required handler cannot be CONFIRMED — but it must not vanish either. */
 @Test void anUnmodelledRequiredHandlerWithNoVisibleAttachmentIsSuspected() {
  config(1);remember(sugared(INJECT));observe(target(false,true,"handler$000$probe","()V"));
  assertTrue(CompatibilityFindings.confirmedRequired().isEmpty());
  CompatibilityFinding injector=CompatibilityFindings.all().stream().filter(f->f.id().startsWith("mixin-injector:")).findFirst().orElseThrow();
  assertEquals(CompatibilityFinding.Confidence.SUSPECTED,injector.confidence());assertTrue(injector.required());assertTrue(injector.evidence().contains("final handler references=0"),injector.evidence().toString());
 }
 /** An expression annotation changes where an @Inject lands, not that it calls its handler directly. */
 @Test void anExpressionPlacedInjectIsStillProvedMissing() {
  config(1);ClassNode n=mixin(-1,false,List.of(TARGET));n.methods.getFirst().visibleAnnotations.add(new AnnotationNode("Lcom/llamalad7/mixinextras/expression/Expression;"));
  remember(n);observe(target(false,true,"handler$000$probe","()V"));
  assertEquals(1,CompatibilityFindings.confirmedRequired().size());
 }
 /** Native InjectionInfo applies defaultRequire only outside a named @Group; the group counts its members. */
 @Test void aGroupMemberWithoutItsOwnRequireOwesNothingIndividually() {
  setup(1,-1,true,List.of(TARGET));observe(target(false,true,"handler$000$probe","()V"));
  assertTrue(CompatibilityFindings.all().stream().noneMatch(f->f.id().startsWith("mixin-injector:")),CompatibilityFindings.all().toString());
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
 /** A plugin config's preflight row is written at the load report, often after the target was already defined. */
 @Test void aHeldBackSuspicionArrivingAfterAFullyAttachedDefinitionIsDischarged() {
  setup(1,-1,false,List.of(TARGET));
  try {
   assertTrue(PluginDeclinedMixins.defer(CONFIG,"example.NoInstancePlugin","ProbeMixin",MIXIN,List.of(TARGET),"preflight unresolved anchors",
     CompatibilityFinding.Confidence.SUSPECTED,true,List.of("probe")));
   assertTrue(PluginDeclinedMixins.defer(MixinCompatibility.driftId(CONFIG,MIXIN),CONFIG,"example.NoInstancePlugin","ProbeMixin",MIXIN,
     List.of(TARGET),"drifted target",CompatibilityFinding.Confidence.SUSPECTED,true,List.of("@Mixin target Target")));
   observe(target(true,true,"handler$000$probe","()V"));
   PluginDeclinedMixins.resolve();
   assertEquals(CompatibilityFinding.Confidence.RESOLVED,whole().confidence());
   assertEquals(CompatibilityFinding.Confidence.SUSPECTED,CompatibilityFindings.all().stream()
     .filter(f->f.id().equals(MixinCompatibility.driftId(CONFIG,MIXIN))).findFirst().orElseThrow().confidence(),
     "attachment never answers a drifted target");
  } finally { PluginDeclinedMixins.reset(); }
 }
 @Test void aHeldBackSuspicionIsNotDischargedByAnIncompleteObservation() {
  setup(1,-1,false,List.of(TARGET,"game.Other"));
  try {
   assertTrue(PluginDeclinedMixins.defer(CONFIG,"example.NoInstancePlugin","ProbeMixin",MIXIN,List.of(TARGET,"game.Other"),
     "preflight unresolved anchors",CompatibilityFinding.Confidence.SUSPECTED,true,List.of("probe")));
   observe(target(true,true,"handler$000$probe","()V"));
   PluginDeclinedMixins.resolve();
   assertEquals(CompatibilityFinding.Confidence.SUSPECTED,whole().confidence());
  } finally { PluginDeclinedMixins.reset(); }
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
 private void config(Integer minimum){config(minimum,true);}
 private void config(Integer minimum,boolean required){String json="{\"required\":"+required+",\"package\":\"example\",\"mixins\":[\"ProbeMixin\"]"+(minimum==null?"":",\"injectors\":{\"defaultRequire\":"+minimum+"}")+"}";MixinCompatibility.rememberOriginalConfig(CONFIG,json.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 private void remember(int require,boolean group,List<String> targets){FinalMixinApplications.remember(mixin(require,group,targets));}
 private static final String INJECT="Lorg/spongepowered/asm/mixin/injection/Inject;",WRAP_OPERATION="Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",MODIFY_EXPRESSION_VALUE="Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;";
 private ClassNode extras(String desc){ClassNode n=mixin(-1,false,List.of(TARGET));n.methods.getFirst().visibleAnnotations=new ArrayList<>(List.of(new AnnotationNode(desc)));return n;}
 private void remember(ClassNode mixin){FinalMixinApplications.remember(mixin);}
 private ClassNode sugared(String desc){ClassNode n=mixin(-1,false,List.of(TARGET));MethodNode m=n.methods.getFirst();m.visibleAnnotations=new ArrayList<>(List.of(new AnnotationNode(desc)));
  m.invisibleParameterAnnotations=new List[]{new ArrayList<>(List.of(new AnnotationNode("Lcom/llamalad7/mixinextras/sugar/Local;")))};return n;}
 private ClassNode mixin(int require,boolean group,List<String> targets){ClassNode n=new ClassNode();n.name=MIXIN.replace('.','/');n.visibleAnnotations=List.of(annotation("Lorg/spongepowered/asm/mixin/Mixin;","targets",targets));n.methods.add(injector("probe",require,group));return n;}
 private MethodNode injector(String name,int require,boolean group){MethodNode m=new MethodNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC,name,"()V",null,null);m.visibleAnnotations=new ArrayList<>(List.of(annotation("Lorg/spongepowered/asm/mixin/injection/Inject;","require",require)));if(group)m.visibleAnnotations.add(annotation("Lorg/spongepowered/asm/mixin/injection/Group;","name","alternatives"));return m;}
 private ClassNode target(boolean call,boolean merged,String name,String desc){ClassNode n=new ClassNode();n.version=Opcodes.V21;n.name=TARGET.replace('.','/');n.superName="java/lang/Object";n.access=Opcodes.ACC_PUBLIC;MethodNode h=new MethodNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC,name,desc,null,null);h.instructions.add(new InsnNode(Opcodes.RETURN));if(merged)h.visibleAnnotations=List.of(annotation("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;","mixin",MIXIN));n.methods.add(h);if(call){MethodNode m=new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"caller","()V",null,null);m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,n.name,name,desc,false));m.instructions.add(new InsnNode(Opcodes.RETURN));n.methods.add(m);}return n;}
 private void observe(ClassNode n){ClassWriter w=new ClassWriter(0);n.accept(w);FinalMixinApplications.observe(TARGET,w.toByteArray(),(mixin,name,desc)->List.of(new FinalMixinApplications.Renamed(name.equals("second")?"handler$001$second":"handler$000$probe",desc)));}
 private void suspect(){MixinCompatibility.record(CONFIG,MIXIN,"preflight unresolved anchors",CompatibilityFinding.Confidence.SUSPECTED,true,List.of("probe"));}
 private CompatibilityFinding whole(){return CompatibilityFindings.all().stream().filter(f->f.id().equals(MixinCompatibility.id(CONFIG,MIXIN))).findFirst().orElseThrow();}
 private static AnnotationNode annotation(String desc,String key,Object value){AnnotationNode a=new AnnotationNode(desc);a.values=new ArrayList<>(List.of(key,value));return a;}
}
