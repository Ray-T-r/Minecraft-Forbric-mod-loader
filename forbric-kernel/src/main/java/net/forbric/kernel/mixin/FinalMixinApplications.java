/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.transformer.ClassInfo;

/** Observes successful class definitions, after all adapters and Mixin. Zero standard-handler references
 * prove that handler was not attached. A reference proves attachment, not runtime execution of the feature. */
public final class FinalMixinApplications {
 private static final String PREFIX = "Lorg/spongepowered/asm/mixin/injection/";
 private static final String EXTRAS = "Lcom/llamalad7/mixinextras/";
 /** Mixin's own injectors. Every successful injection emits a direct call to the merged handler in the target class,
  * and the kernel's anchor repairs and replacement proofs are audited against them, so an unproved miss is a
  * confirmed loss. */
 private static final Set<String> STANDARD = Set.of(PREFIX+"Inject;", PREFIX+"Redirect;", PREFIX+"ModifyArg;",
   PREFIX+"ModifyArgs;", PREFIX+"ModifyConstant;", PREFIX+"ModifyVariable;");
 /** MixinExtras' injectors are built on Mixin's InjectionInfo, so require/defaultRequire mean the same thing, and
  * each successful injection is a direct call to the merged handler too: zero references still proves the handler
  * did not attach. Left out, the kernel's defaultRequire relaxation made their misses silent. What zero references
  * does not settle is whether the feature is lost. Stock fabric-api misses this way on the merged base in many
  * places, and the kernel supplies some of those features outside any mixin: eleven of HudMixin's @WrapOperation
  * handlers attach nowhere, and KernelHudBridge draws what they would have. None of those replacements is a
  * structural proof here yet. Confirming them would stop every dedicated server carrying fabric-api at boot, so an
  * unproved miss stays SUSPECTED. */
 private static final Set<String> EXTRAS_INJECTORS = Set.of(
   EXTRAS+"injector/ModifyExpressionValue;", EXTRAS+"injector/ModifyReturnValue;", EXTRAS+"injector/ModifyReceiver;",
   EXTRAS+"injector/WrapWithCondition;", EXTRAS+"injector/v2/WrapWithCondition;",
   EXTRAS+"injector/wrapoperation/WrapOperation;", EXTRAS+"injector/wrapmethod/WrapMethod;");
 private static final String MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";
 private record Config(String name, boolean required, int minimum) { }
 private record Injector(String name, String desc, int minimum, boolean understood, boolean audited, String bodyHash) {
  String symbol() { return name+desc; }
 }
 private record Plan(String mixin, Config config, List<String> targets, List<Injector> injectors, boolean complete) { }
 record Renamed(String name, String desc) { }
 interface Renames { List<Renamed> find(String mixin, String name, String desc); }
 /** NEVER_RUNS: attached, but only in methods nothing in the merged game calls (MergedBaseUncalledMethods). */
 private enum Outcome { ATTACHED, OPTIONAL, EQUIVALENT, MISSING, NEVER_RUNS, UNKNOWN }
 private static final Map<String, Set<Config>> CONFIGS = new ConcurrentHashMap<>();
 private static final Map<String, Plan> PLANS = new ConcurrentHashMap<>();
 private static final Map<String, Set<String>> TARGETS = new ConcurrentHashMap<>();
 private static final Map<String, Map<String, Outcome>> OUTCOMES = new ConcurrentHashMap<>();
 /** Mixins whose every modelled injector was seen attached, optional or replaced on every target. Kept because a
  * plugin config's preflight row is only written when the load report settles it, often after the definition. */
 private static final Set<String> DISCHARGED = ConcurrentHashMap.newKeySet();
 static final String DISCHARGE = "all modelled injectors are attached, originally optional or verified as replaced across all observed targets";
 private record DeferredDefinition(byte[] bytes,Renames names) { }
 private static volatile DeferredDefinition watchdog;
 private FinalMixinApplications() { }
 public static void reset() { CONFIGS.clear(); PLANS.clear(); TARGETS.clear(); OUTCOMES.clear(); DISCHARGED.clear(); watchdog=null;WatchdogDumpEquivalence.reset(); }

 /** Whether the final classes already discharged a whole-mixin preflight suspicion about {@code mixin}. */
 static boolean discharged(String mixin) { return DISCHARGED.contains(mixin); }

 static void config(String name, com.electronwill.nightconfig.core.UnmodifiableConfig json) {
  String pkg=json.getOrElse("package", "");
  Number minimum=json.getOrElse(List.of("injectors","defaultRequire"), Integer.valueOf(0));
  Config declaration=new Config(name,Boolean.TRUE.equals(json.get("required")),Math.max(0,minimum.intValue()));
  for(String part:List.of("mixins","client","server")) {
   Object value=json.get(part);if(!(value instanceof List<?> entries))continue;
   for(Object entry:entries)if(entry instanceof String mixin) {
    String binary=(pkg.isBlank()?mixin:pkg+"."+mixin).replace('/','.');
    CONFIGS.computeIfAbsent(binary,k->ConcurrentHashMap.newKeySet()).add(declaration);
   }
  }
 }

 /** Whether {@code desc} is an injector annotation this ledger counts (Mixin's own and MixinExtras'). */
 static boolean isInjector(String desc) { return STANDARD.contains(desc)||EXTRAS_INJECTORS.contains(desc); }

 /** The configs that declare {@code binary} (dotted), as read from their original JSON; empty when unknown. */
 static Set<String> configNames(String binary) {
  Set<Config> configs=CONFIGS.get(binary);if(configs==null)return Set.of();
  Set<String> names=new java.util.LinkedHashSet<>();for(Config c:configs)names.add(c.name());return names;
 }

 /** Called on the final adapter output handed to Mixin; no guessed pre-adapter descriptors. */
 static void remember(ClassNode mixin) {
  String binary=mixin.name.replace('/','.');Set<Config> configs=CONFIGS.get(binary);
  if(configs==null || configs.size()!=1)return; // Dynamic/ambiguous ownership is not a proved contract.
  Config config=configs.iterator().next();List<String> targets=MixinFit.mixinTargets(mixin).stream().map(n->n.replace('/','.')).toList();
  if(targets.isEmpty())return;
  List<Injector> injectors=new ArrayList<>();boolean complete=true;
  for(MethodNode method:mixin.methods) {
   List<AnnotationNode> annotations=annotations(method);
   boolean grouped=annotations.stream().anyMatch(a->a.desc.equals(PREFIX+"Group;"));
   boolean sugar=hasSugar(method.visibleParameterAnnotations)||hasSugar(method.invisibleParameterAnnotations);
   List<AnnotationNode> injecting=annotations.stream().filter(a->STANDARD.contains(a.desc)||EXTRAS_INJECTORS.contains(a.desc)).toList();
   // A MixinExtras form this does not model leaves the whole-mixin verdict open; the handler's own attachment
   // is still a direct call and still counted.
   boolean extension=annotations.stream().anyMatch(a->a.desc.startsWith(EXTRAS)&&!EXTRAS_INJECTORS.contains(a.desc));
   if(extension)complete=false;
   for(AnnotationNode annotation:injecting) {
    // As InjectionInfo.readInjectionPoints: an explicit require wins; otherwise defaultRequire applies only
    // outside a named @Group, whose members are counted by the group and individually require nothing.
    Object declared=value(annotation,"require");int minimum=declared instanceof Number n?n.intValue():-1;
    if(minimum<0)minimum=grouped?0:config.minimum();
    injectors.add(new Injector(method.name,method.desc,minimum,!grouped&&!sugar&&injecting.size()==1,STANDARD.contains(annotation.desc),
      MixinEquivalentImplementations.needsFingerprint(binary,method)?MixinInstructionFingerprint.hash(method):""));
    if(grouped||sugar||extension||injecting.size()!=1)complete=false;
   }
  }
  if(injectors.isEmpty())return;
  Plan plan=new Plan(binary,config,List.copyOf(targets),List.copyOf(injectors),complete);
  PLANS.put(binary,plan);
  for(String target:targets)TARGETS.computeIfAbsent(target,k->ConcurrentHashMap.newKeySet()).add(binary);
 }

 public static void onClassDefined(String binary,byte[] bytes) {
  if(!TARGETS.containsKey(binary)&&!WatchdogDumpEquivalence.HELPER.equals(binary))return;
  try { observe(binary,bytes,FinalMixinApplications::renamed); }
  catch(RuntimeException|LinkageError unavailable) {
   for(String mixin:TARGETS.getOrDefault(binary,Set.of())) {
    Plan plan=PLANS.get(mixin);if(plan==null)continue;
    MixinCompatibility.record(plan.config().name(),mixin,"Final injection attachment could not be established",
      CompatibilityFinding.Confidence.SUSPECTED,plan.config().required(),List.of(binary,unavailable.toString()));
   }
  }
 }
 static void observe(String binary,byte[] bytes,Renames names) {
  if(WatchdogDumpEquivalence.HELPER.equals(binary)) {
   ClassNode helper=new ClassNode();new ClassReader(bytes).accept(helper,ClassReader.SKIP_FRAMES|ClassReader.SKIP_DEBUG);
   WatchdogDumpEquivalence.observeHelper(helper);
   DeferredDefinition previous=watchdog;
   if(previous!=null)observe(WatchdogDumpEquivalence.TARGET,previous.bytes(),previous.names());
   return;
  }
  if(!TARGETS.containsKey(binary))return;
  if(WatchdogDumpEquivalence.TARGET.equals(binary))watchdog=new DeferredDefinition(bytes.clone(),names);
  ClassNode target=new ClassNode();new ClassReader(bytes).accept(target,ClassReader.SKIP_FRAMES|ClassReader.SKIP_DEBUG);
  Map<String,List<MethodNode>> merged=new HashMap<>();
  for(MethodNode method:target.methods)for(AnnotationNode annotation:annotations(method))if(annotation.desc.equals(MERGED)) {
   Object owner=value(annotation,"mixin");if(owner instanceof String mixin)merged.computeIfAbsent(mixin.replace('/','.'),k->new ArrayList<>()).add(method);
  }
  for(String mixin:TARGETS.get(binary)) {
   Plan plan=PLANS.get(mixin);if(plan==null||!merged.containsKey(mixin))continue;
   Map<String,Outcome> observed=OUTCOMES.computeIfAbsent(mixin,k->new ConcurrentHashMap<>());
   for(Injector injector:plan.injectors()) {
    List<Renamed> rename=names.find(mixin,injector.name(),injector.desc());
    List<MethodNode> candidates=merged.get(mixin).stream().filter(m->rename.stream().anyMatch(n->n.name().equals(m.name)&&n.desc().equals(m.desc))).toList();
    Outcome state=Outcome.UNKNOWN;
    int references=candidates.size()==1?references(target,candidates.getFirst()):-1;
    // Attached is not run: every call of the handler in a method nothing in the merged game calls. Judged whatever the
    // injector's shape, because a call in dead code is dead however it is counted; a mandatory one is then a loss.
    String dead=references>0?neverRuns(plan,target,candidates.getFirst()):null;
    if(dead!=null)
     state=injector.minimum()==0?Outcome.OPTIONAL:Outcome.NEVER_RUNS;
    else if(injector.understood()&&references>=0)
     state=references>0?(references>=injector.minimum()?Outcome.ATTACHED:Outcome.UNKNOWN):injector.minimum()==0?Outcome.OPTIONAL:Outcome.MISSING;
    boolean lost=state==Outcome.MISSING||state==Outcome.NEVER_RUNS;
    String replacement=lost?MixinEquivalentImplementations.proof(mixin,injector.name(),injector.desc(),injector.bodyHash(),target):null;
    boolean pending=lost&&WatchdogDumpEquivalence.helperUnknown()
      &&WatchdogDumpEquivalence.candidate(mixin,injector.name(),injector.desc(),injector.bodyHash(),target);
    if(pending)state=Outcome.UNKNOWN;
    if(replacement!=null)state=Outcome.EQUIVALENT;
    observed.put(binary+"#"+injector.symbol(),state);
    String id=id(plan,injector,binary),mod=owner(plan.config().name());
    // A mixin the kernel does the whole job of: its injector's miss is that job moving, not a loss, once the
    // replacement is seen; until then it is recorded as usual and resolved when SupersededMixins proves it.
    // An injector this does not model (sugar, a group) is still visibly unattached when nothing in the final class
    // calls its merged handler; that is the miss a superseding repair answers for, as much as a modelled one's.
    boolean unattached=lost||state==Outcome.UNKNOWN&&!pending&&references==0;
    String superseded=unattached?SupersededMixins.provedReplacement(mixin):null;
    if(unattached&&superseded==null&&SupersededMixins.replacementFor(mixin)!=null)SupersededMixins.awaitProof(plan.config().name(),mixin);
    // Natively an injector below its require/defaultRequire throws InjectionError, an Error no config-level
    // `required:false` catches: the author declared that injection mandatory whatever the config says.
    boolean required=plan.config().required()||injector.minimum()>=1;
    if(superseded!=null)CompatibilityFindings.record(new CompatibilityFinding(id,mod,
      "Mixin injection "+injector.name(),"mixin-application:"+plan.config().name(),CompatibilityFinding.Confidence.RESOLVED,
      plan.config().required()||injector.minimum()>=1,superseded,List.of("target="+binary,"handler="+injector.symbol(),superseded)));
    else if(pending)CompatibilityFindings.record(new CompatibilityFinding(id,mod,
      "Mixin injection "+injector.name(),"mixin-application:"+plan.config().name(),CompatibilityFinding.Confidence.SUSPECTED,
      required,"The audited watchdog report uses a native replacement whose final renderer has not been defined yet",
      List.of("target="+binary,"pending final helper="+WatchdogDumpEquivalence.HELPER)));
    // Proved from the final class and the merged game's census, not observed: a guest mod calling the dead method itself
    // is outside both. So it marks the mod's row and never asks the player to quit or stops a STRICT server; the author's
    // requirement stays in the evidence. MixinExtras kinds stay SUSPECTED, as for MISSING (KernelHudBridge draws some).
    else if(state==Outcome.NEVER_RUNS)CompatibilityFindings.record(new CompatibilityFinding(id,mod,
      "Mixin injection "+injector.name(),"mixin-application:"+plan.config().name(),
      injector.audited()&&injector.understood()?CompatibilityFinding.Confidence.CONFIRMED:CompatibilityFinding.Confidence.SUSPECTED,
      false,"injector "+injector.name()+" is attached only in "+dead+", so it never runs",
      List.of("target="+binary,"mixin="+mixin,"handler="+injector.symbol(),"attached only in "+dead,"original minimum="+injector.minimum(),
        "config required="+plan.config().required(),"-D"+MixinFit.LIVENESS_PROPERTY+"=off counts it as attached")));
    else if(state==Outcome.MISSING&&!injector.audited())CompatibilityFindings.record(new CompatibilityFinding(id,mod,
      "Mixin injection "+injector.name(),"mixin-application:"+plan.config().name(),CompatibilityFinding.Confidence.SUSPECTED,
      required,"A required MixinExtras injector has no attachment in the actual defined class; no audited replacement says whether its feature is lost",
      List.of("target="+binary,"mixin="+mixin,"handler="+injector.symbol(),"original minimum="+injector.minimum(),"final handler references=0",
        "config required="+plan.config().required(),"MixinExtras injector: replacements unaudited")));
    else if(state==Outcome.MISSING)CompatibilityFindings.record(new CompatibilityFinding(id,mod,
      "Mixin injection "+injector.name(),"mixin-application:"+plan.config().name(),CompatibilityFinding.Confidence.CONFIRMED,
      required,"A required injector has no attachment in the actual defined class",
      List.of("target="+binary,"mixin="+mixin,"handler="+injector.symbol(),"original minimum="+injector.minimum(),"final handler references=0",
        "config required="+plan.config().required())));
    else if(state==Outcome.EQUIVALENT)CompatibilityFindings.record(new CompatibilityFinding(id,mod,
      "Mixin injection "+injector.name(),"mixin-application:"+plan.config().name(),CompatibilityFinding.Confidence.RESOLVED,
      required,"The missing injector is replaced by a verified implementation",List.of("target="+binary,replacement)));
    else if(state==Outcome.ATTACHED||state==Outcome.OPTIONAL)CompatibilityFindings.resolve(id,mod,
      state==Outcome.ATTACHED?"final defined class contains a reference to the exact merged handler":"original injector explicitly permits zero attachments");
    // Unproved either way, but an author-mandated count that the final class does not visibly meet is worth a
    // detail line: the relaxation made it silent, and otherwise nothing at all records it.
    else if(injector.minimum()>=1&&references<injector.minimum())CompatibilityFindings.record(new CompatibilityFinding(id,mod,
      "Mixin injection "+injector.name(),"mixin-application:"+plan.config().name(),CompatibilityFinding.Confidence.SUSPECTED,
      required,"A required injector's attachment in the actual defined class could not be established",
      List.of("target="+binary,"mixin="+mixin,"handler="+injector.symbol(),"original minimum="+injector.minimum(),
        references<0?"merged handler not identified ("+candidates.size()+" candidates)":"final handler references="+references,
        injector.understood()?"partial count":"grouped, sugar or several injector annotations")));
   }
   // An old whole-mixin suspicion may concern another handler or target. Discharge it only after every
   // understood declaration has been observed on every target, and never erase a confirmed apply failure.
   boolean all=plan.complete()&&plan.targets().stream().allMatch(t->plan.injectors().stream().allMatch(i->{
    Outcome o=observed.get(t+"#"+i.symbol());return o==Outcome.ATTACHED||o==Outcome.OPTIONAL||o==Outcome.EQUIVALENT;
   }));
   if(all)DISCHARGED.add(mixin);else DISCHARGED.remove(mixin);
   if(all&&CompatibilityFindings.all().stream().anyMatch(f->f.id().equals(MixinCompatibility.id(plan.config().name(),mixin))
      && f.modId().equals(owner(plan.config().name()))&&f.confidence()==CompatibilityFinding.Confidence.SUSPECTED))
    MixinCompatibility.resolve(plan.config().name(),mixin,DISCHARGE);
  }
 }
 private static List<Renamed> renamed(String mixin,String name,String desc) {
  ClassInfo info=ClassInfo.fromCache(mixin.replace('.','/'));if(info==null)return List.of();
  return info.getMethods().stream().filter(m->m.getOriginalName().equals(name)&&m.getOriginalDesc().equals(desc))
    .map(m->new Renamed(m.getName(),m.getDesc())).toList();
 }
 private static int references(ClassNode target,MethodNode handler) {
  int result=0;
  for(MethodNode method:target.methods)if(method!=handler)result+=references(target,method,handler); // A self-call is not an injection into the target.
  return result;
 }
 private static int references(ClassNode target,MethodNode method,MethodNode handler) {
  int result=0;
  for(AbstractInsnNode instruction:method.instructions) {
   if(instruction instanceof MethodInsnNode call&&call.owner.equals(target.name)&&call.name.equals(handler.name)&&call.desc.equals(handler.desc))result++;
   else if(instruction instanceof InvokeDynamicInsnNode dynamic) { for(Object argument:dynamic.bsmArgs)if(argument instanceof Handle h&&same(target,handler,h))result++; }
   else if(instruction instanceof LdcInsnNode constant&&constant.cst instanceof Handle h&&same(target,handler,h))result++;
  }
  return result;
 }
 /** "Hud.extractHotbarAndDecorations (nothing in the merged game calls it; …)" when every method of the final class that
  * calls {@code handler} is one MergedBaseUncalledMethods lists for the config's ecosystem; null when any may run. */
 private static String neverRuns(Plan plan,ClassNode target,MethodNode handler) {
  if(!MixinFit.asksLiveness()||!MergedBaseUncalledMethods.lists(target.name))return null;
  net.forbric.api.Ecosystem ecosystem=MixinConfigOwners.ecosystemOf(plan.config().name());if(ecosystem==null)return null;
  List<String> hosts=new ArrayList<>();
  for(MethodNode method:target.methods) {
   if(method==handler||references(target,method,handler)==0)continue;
   String why=MergedBaseUncalledMethods.neverRuns(target,method,ecosystem);if(why==null)return null;
   hosts.add(target.name.substring(target.name.lastIndexOf('/')+1)+"."+method.name+" ("+why+")");
  }
  return hosts.isEmpty()?null:String.join("; ",hosts);
 }
 private static boolean same(ClassNode target,MethodNode handler,Handle handle) {return handle.getOwner().equals(target.name)&&handle.getName().equals(handler.name)&&handle.getDesc().equals(handler.desc);}
 private static String id(Plan plan,Injector injector,String target) {return "mixin-injector:"+plan.config().name()+":"+plan.mixin()+"#"+injector.symbol()+"@"+target;}
 private static String owner(String config) {String mod=MixinConfigOwners.modIdOf(config);return mod==null?"config:"+config:mod;}
 private static List<AnnotationNode> annotations(MethodNode method) {List<AnnotationNode> out=new ArrayList<>();if(method.visibleAnnotations!=null)out.addAll(method.visibleAnnotations);if(method.invisibleAnnotations!=null)out.addAll(method.invisibleAnnotations);return out;}
 private static boolean hasSugar(List<AnnotationNode>[] parameters) {if(parameters!=null)for(var parameter:parameters)if(parameter!=null)for(var a:parameter)if(a.desc.startsWith("Lcom/llamalad7/mixinextras/"))return true;return false;}
 private static Object value(AnnotationNode annotation,String key) {if(annotation.values!=null)for(int i=0;i<annotation.values.size();i+=2)if(key.equals(annotation.values.get(i)))return annotation.values.get(i+1);return null;}
}
