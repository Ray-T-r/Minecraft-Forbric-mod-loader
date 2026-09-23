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
 /** Injectors whose every successful injection emits a direct call to the merged handler in the target class.
  * MixinExtras' are built on Mixin's InjectionInfo, so require/defaultRequire mean the same thing for them; left
  * out, the kernel's defaultRequire relaxation made their misses silent with nothing recorded at all. */
 private static final Set<String> STANDARD = Set.of(PREFIX+"Inject;", PREFIX+"Redirect;", PREFIX+"ModifyArg;",
   PREFIX+"ModifyArgs;", PREFIX+"ModifyConstant;", PREFIX+"ModifyVariable;",
   EXTRAS+"injector/ModifyExpressionValue;", EXTRAS+"injector/ModifyReturnValue;", EXTRAS+"injector/ModifyReceiver;",
   EXTRAS+"injector/WrapWithCondition;", EXTRAS+"injector/v2/WrapWithCondition;",
   EXTRAS+"injector/wrapoperation/WrapOperation;", EXTRAS+"injector/wrapmethod/WrapMethod;");
 private static final String MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";
 private record Config(String name, boolean required, int minimum) { }
 private record Injector(String name, String desc, int minimum, boolean understood, String bodyHash) {
  String symbol() { return name+desc; }
 }
 private record Plan(String mixin, Config config, List<String> targets, List<Injector> injectors, boolean complete) { }
 record Renamed(String name, String desc) { }
 interface Renames { List<Renamed> find(String mixin, String name, String desc); }
 private enum Outcome { ATTACHED, OPTIONAL, EQUIVALENT, MISSING, UNKNOWN }
 private static final Map<String, Set<Config>> CONFIGS = new ConcurrentHashMap<>();
 private static final Map<String, Plan> PLANS = new ConcurrentHashMap<>();
 private static final Map<String, Set<String>> TARGETS = new ConcurrentHashMap<>();
 private static final Map<String, Map<String, Outcome>> OUTCOMES = new ConcurrentHashMap<>();
 private record DeferredDefinition(byte[] bytes,Renames names) { }
 private static volatile DeferredDefinition watchdog;
 private FinalMixinApplications() { }
 public static void reset() { CONFIGS.clear(); PLANS.clear(); TARGETS.clear(); OUTCOMES.clear(); watchdog=null;WatchdogDumpEquivalence.reset(); }

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
   List<AnnotationNode> standard=annotations.stream().filter(a->STANDARD.contains(a.desc)).toList();
   // A MixinExtras form this does not model leaves the whole-mixin verdict open; the handler's own attachment
   // is still a direct call and still counted.
   boolean extension=annotations.stream().anyMatch(a->a.desc.startsWith(EXTRAS)&&!STANDARD.contains(a.desc));
   if(extension)complete=false;
   for(AnnotationNode annotation:standard) {
    // As InjectionInfo.readInjectionPoints: an explicit require wins; otherwise defaultRequire applies only
    // outside a named @Group, whose members are counted by the group and individually require nothing.
    Object declared=value(annotation,"require");int minimum=declared instanceof Number n?n.intValue():-1;
    if(minimum<0)minimum=grouped?0:config.minimum();
    injectors.add(new Injector(method.name,method.desc,minimum,!grouped&&!sugar&&standard.size()==1,
      MixinEquivalentImplementations.needsFingerprint(binary,method)?MixinInstructionFingerprint.hash(method):""));
    if(grouped||sugar||extension||standard.size()!=1)complete=false;
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
    if(injector.understood()&&references>=0)
     state=references>0?(references>=injector.minimum()?Outcome.ATTACHED:Outcome.UNKNOWN):injector.minimum()==0?Outcome.OPTIONAL:Outcome.MISSING;
    String replacement=state==Outcome.MISSING?MixinEquivalentImplementations.proof(mixin,injector.name(),injector.desc(),injector.bodyHash(),target):null;
    boolean pending=state==Outcome.MISSING&&WatchdogDumpEquivalence.helperUnknown()
      &&WatchdogDumpEquivalence.candidate(mixin,injector.name(),injector.desc(),injector.bodyHash(),target);
    if(pending)state=Outcome.UNKNOWN;
    if(replacement!=null)state=Outcome.EQUIVALENT;
    observed.put(binary+"#"+injector.symbol(),state);
    String id=id(plan,injector,binary),mod=owner(plan.config().name());
    // Natively an injector below its require/defaultRequire throws InjectionError, an Error no config-level
    // `required:false` catches: the author declared that injection mandatory whatever the config says.
    boolean required=plan.config().required()||injector.minimum()>=1;
    if(pending)CompatibilityFindings.record(new CompatibilityFinding(id,mod,
      "Mixin injection "+injector.name(),"mixin-application:"+plan.config().name(),CompatibilityFinding.Confidence.SUSPECTED,
      required,"The audited watchdog report uses a native replacement whose final renderer has not been defined yet",
      List.of("target="+binary,"pending final helper="+WatchdogDumpEquivalence.HELPER)));
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
   if(all&&CompatibilityFindings.all().stream().anyMatch(f->f.id().equals(MixinCompatibility.id(plan.config().name(),mixin))
      && f.modId().equals(owner(plan.config().name()))&&f.confidence()==CompatibilityFinding.Confidence.SUSPECTED))
    MixinCompatibility.resolve(plan.config().name(),mixin,"all modelled injectors are attached, originally optional or verified as replaced across all observed targets");
  }
 }
 private static List<Renamed> renamed(String mixin,String name,String desc) {
  ClassInfo info=ClassInfo.fromCache(mixin.replace('.','/'));if(info==null)return List.of();
  return info.getMethods().stream().filter(m->m.getOriginalName().equals(name)&&m.getOriginalDesc().equals(desc))
    .map(m->new Renamed(m.getName(),m.getDesc())).toList();
 }
 private static int references(ClassNode target,MethodNode handler) {
  int result=0;
  for(MethodNode method:target.methods) {
   if(method==handler)continue; // A self-call is not an injection into the target.
   for(AbstractInsnNode instruction:method.instructions) {
    if(instruction instanceof MethodInsnNode call&&call.owner.equals(target.name)&&call.name.equals(handler.name)&&call.desc.equals(handler.desc))result++;
    else if(instruction instanceof InvokeDynamicInsnNode dynamic) { for(Object argument:dynamic.bsmArgs)if(argument instanceof Handle h&&same(target,handler,h))result++; }
    else if(instruction instanceof LdcInsnNode constant&&constant.cst instanceof Handle h&&same(target,handler,h))result++;
   }
  }
  return result;
 }
 private static boolean same(ClassNode target,MethodNode handler,Handle handle) {return handle.getOwner().equals(target.name)&&handle.getName().equals(handler.name)&&handle.getDesc().equals(handler.desc);}
 private static String id(Plan plan,Injector injector,String target) {return "mixin-injector:"+plan.config().name()+":"+plan.mixin()+"#"+injector.symbol()+"@"+target;}
 private static String owner(String config) {String mod=MixinConfigOwners.modIdOf(config);return mod==null?"config:"+config:mod;}
 private static List<AnnotationNode> annotations(MethodNode method) {List<AnnotationNode> out=new ArrayList<>();if(method.visibleAnnotations!=null)out.addAll(method.visibleAnnotations);if(method.invisibleAnnotations!=null)out.addAll(method.invisibleAnnotations);return out;}
 private static boolean hasSugar(List<AnnotationNode>[] parameters) {if(parameters!=null)for(var parameter:parameters)if(parameter!=null)for(var a:parameter)if(a.desc.startsWith("Lcom/llamalad7/mixinextras/"))return true;return false;}
 private static Object value(AnnotationNode annotation,String key) {if(annotation.values!=null)for(int i=0;i<annotation.values.size();i+=2)if(key.equals(annotation.values.get(i)))return annotation.values.get(i+1);return null;}
}
