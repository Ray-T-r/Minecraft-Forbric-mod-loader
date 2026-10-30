package net.forbric.kernel.mixin;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.zip.*;
import net.forbric.api.*;
import net.forbric.kernel.TestFixtures;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/**
 * Replacement proofs read structure, never names or hashes. A value modifier whose call the merged method no longer makes is
 * no loss when the method hands the same-named call an equal computation of the same value (fabric-crash-report-info's
 * full thread dump, which NeoForge renders with an instruction-for-instruction equal method); a resource-condition guard
 * whose consumer cannot attach is resolved by the repair that supersedes its whole mixin, once that repair is seen.
 */
@ResourceLock("ModCatalog") @ResourceLock("native-game-references")
class MixinEquivalentImplementationsTest {
 @TempDir Path temporary;
 private static final String READER="net/minecraft/server/packs/resources/SimpleJsonResourceReloadListener";
 private static final String CONDITIONS="net/fabricmc/fabric/mixin/resource/conditions/SimpleJsonResourceReloadListenerMixin";
 private static final String CONFIG="fabric-resource-conditions-api-v1.mixins.json";
 private static final String WATCHDOG="net/minecraft/server/dedicated/ServerWatchdog",DUMP_MIXIN="net/fabricmc/fabric/mixin/crash/report/info/ServerWatchdogMixin";
 private static final String HANDLER="printEntireThreadDump",HANDLER_DESC="(Ljava/lang/Object;)Ljava/lang/Object;";
 @BeforeEach @AfterEach void reset(){MixinCompatibility.reset();CompatibilityFindings.reset();MixinDelegationEquivalence.reset();MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned(CONFIG,"conditions",Ecosystem.FABRIC)));}
 private ClassNode conditionsMixin()throws Exception{return StagedFabricMixinFixture.mixin("fabric-resource-conditions-api-v1",CONDITIONS);}

 // ---- the value modifier the merged method already applies -----------------------------------------------------------

 private static Path neo(){return TestFixtures.stagedRoot().resolve(TestFixtures.NEO_CARRIER_JAR);}
 /** A class by internal name from the merged base, the NeoForge carrier, or fabric-api's crash-report module. */
 private static Function<String,ClassNode> classes()throws Exception{
  TestFixtures.requireFiles(TestFixtures.Fixture.STAGED,"staged merged base, NeoForge carrier and Fabric API",neo(),TestFixtures.fabricApi());
  Map<String,byte[]> all=new HashMap<>();
  for(Path jar:List.of(TestFixtures.mergedBase(),neo()))try(ZipFile z=new ZipFile(jar.toFile())){
   for(String name:List.of(WATCHDOG,"net/neoforged/neoforge/logging/ThreadInfoUtil","net/neoforged/neoforge/logging/ThreadInfoUtil$1")){ZipEntry e=z.getEntry(name+".class");if(e!=null)all.put(name,z.getInputStream(e).readAllBytes());}}
  try(ZipFile z=new ZipFile(TestFixtures.fabricApi().toFile())){ZipEntry module=z.stream().filter(e->e.getName().startsWith("META-INF/jars/fabric-crash-report-info-v1-")).findFirst().orElseThrow();
   try(ZipInputStream in=new ZipInputStream(z.getInputStream(module))){for(ZipEntry e=in.getNextEntry();e!=null;e=in.getNextEntry())if(e.getName().endsWith(".class"))all.put(e.getName().substring(0,e.getName().length()-6),in.readAllBytes());}}
  return name->{byte[] b=all.get(name);if(b==null)return null;ClassNode n=new ClassNode();new ClassReader(b).accept(n,0);return n;};
 }
 private static ClassNode remapped(ClassNode node,Map<String,String> names){ClassNode out=new ClassNode();node.accept(new org.objectweb.asm.commons.ClassRemapper(out,new org.objectweb.asm.commons.SimpleRemapper(names)));return out;}
 private static String dumpProof(ClassNode mixin,Function<String,ClassNode> classes){
  MixinDelegationEquivalence.remember(mixin);MethodNode handler=StagedFabricMixinFixture.method(mixin,HANDLER);
  return MixinDelegationEquivalence.proof(mixin.name,handler.name,handler.desc,classes.apply(WATCHDOG),classes);
 }

 @Test void theActualThreadDumpModifierIsAlreadyAppliedByTheMergedReport()throws Exception{
  Function<String,ClassNode> classes=classes();
  assertNotNull(dumpProof(StagedFabricMixinFixture.mixin("fabric-crash-report-info-v1",DUMP_MIXIN),classes));
 }

 /** The same mixin and renderer relocated to names that share nothing with fabric-api's are proved alike. */
 @Test void underUnrelatedNamesTheSameProofHolds()throws Exception{
  Function<String,ClassNode> classes=classes();
  Map<String,String> names=Map.of(DUMP_MIXIN,"org/qzx/report/QzxDumpHooks","net/fabricmc/fabric/impl/crash/report/info/ThreadPrinting","org/qzx/report/QzxRenderer",
    "net/fabricmc/fabric/impl/crash/report/info/ThreadPrinting$1","org/qzx/report/QzxRenderer$1");
  ClassNode mixin=remapped(StagedFabricMixinFixture.mixin("fabric-crash-report-info-v1",DUMP_MIXIN),names);
  Function<String,ClassNode> relocated=name->{for(var e:names.entrySet())if(e.getValue().equals(name))return remapped(classes.apply(e.getKey()),names);return classes.apply(name);};
  assertNotNull(dumpProof(mixin,relocated));
 }

 /** A renderer that differs in one instruction, or a handler without its type guard, proves nothing. */
 @Test void aDifferentRendererOrAnUnguardedHandlerProvesNothing()throws Exception{
  Function<String,ClassNode> classes=classes();
  String printing="net/fabricmc/fabric/impl/crash/report/info/ThreadPrinting";
  Function<String,ClassNode> altered=name->{ClassNode n=classes.apply(name);if(n!=null&&name.equals(printing))for(MethodNode m:n.methods)for(var i:m.instructions)if(i instanceof LdcInsnNode l&&l.cst instanceof String s&&s.contains("suspended")){l.cst=s+"!";break;}return n;};
  assertNull(dumpProof(StagedFabricMixinFixture.mixin("fabric-crash-report-info-v1",DUMP_MIXIN),altered),"one constant differs");
  ClassNode unguarded=StagedFabricMixinFixture.mixin("fabric-crash-report-info-v1",DUMP_MIXIN);
  for(var i:StagedFabricMixinFixture.method(unguarded,HANDLER).instructions.toArray())if(i instanceof TypeInsnNode t&&t.getOpcode()==Opcodes.INSTANCEOF){t.desc="java/lang/String";}
  assertNull(dumpProof(unguarded,classes),"a guard on another type: the delegate is not what the value gets");
  ClassNode target=classes.apply(WATCHDOG);
  for(MethodNode m:target.methods)for(var i:m.instructions)if(i instanceof MethodInsnNode c&&c.name.equals("getEntireStacktrace"))c.name="getShortStacktrace";
  Function<String,ClassNode> otherCall=name->name.equals(WATCHDOG)?target:classes.apply(name);
  assertNull(dumpProof(StagedFabricMixinFixture.mixin("fabric-crash-report-info-v1",DUMP_MIXIN),otherCall),"the report calls another method");
 }

 /** The ledger reads the classes as the current pipeline holds them and resolves the finding with no name of its own. */
 @Test void theLedgerResolvesTheModifiersMissAsAVerifiedReplacement()throws Exception{
  Function<String,ClassNode> classes=classes();
  Path module=temporary.resolve("crash-info.jar");
  try(ZipFile z=new ZipFile(TestFixtures.fabricApi().toFile())){ZipEntry e=z.stream().filter(x->x.getName().startsWith("META-INF/jars/fabric-crash-report-info-v1-")).findFirst().orElseThrow();Files.write(module,z.getInputStream(e).readAllBytes());}
  String config="fabric-crash-report-info-v1.mixins.json";
  MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned(config,"crash-info",Ecosystem.FABRIC)));
  MixinCompatibility.rememberOriginalConfig(config,"{\"required\":true,\"package\":\"net.fabricmc.fabric.mixin.crash.report.info\",\"mixins\":[\"ServerWatchdogMixin\"],\"injectors\":{\"defaultRequire\":1}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  try(var loader=new net.forbric.kernel.classloading.ForbricClassLoader(new java.net.URL[]{TestFixtures.mergedBase().toUri().toURL(),neo().toUri().toURL(),module.toUri().toURL()},getClass().getClassLoader())){
   NativeGameReferences.bind(loader);
   ClassNode mixin=StagedFabricMixinFixture.mixin("fabric-crash-report-info-v1",DUMP_MIXIN);FinalMixinApplications.remember(mixin);
   ClassNode target=classes.apply(WATCHDOG);MethodNode original=StagedFabricMixinFixture.method(mixin,HANDLER),merged=new MethodNode(original.access,"modify$qzx$dump",original.desc,null,null);original.accept(merged);merged.name="modify$qzx$dump";
   AnnotationNode annotation=new AnnotationNode("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;");annotation.values=new ArrayList<>(List.of("mixin",DUMP_MIXIN.replace('/','.')));merged.visibleAnnotations=List.of(annotation);target.methods.add(merged);
   FinalMixinApplications.observe(WATCHDOG.replace('/','.'),StagedFabricMixinFixture.bytes(target),(m,n,d)->List.of(new FinalMixinApplications.Renamed(merged.name,merged.desc)));
   assertTrue(CompatibilityFindings.confirmedRequired().isEmpty(),CompatibilityFindings.all().toString());
   assertTrue(CompatibilityFindings.all().stream().anyMatch(f->f.id().contains("#"+HANDLER)&&f.confidence()==CompatibilityFinding.Confidence.RESOLVED),CompatibilityFindings.all().toString());
  }finally{NativeGameReferences.bind(null);}
 }

 /** A synthetic pair sharing no name with anything upstream: an equal body proves it, one that differs does not. */
 @Test void aSyntheticModifierAndAnEqualCarrierRendererAreProvedAndAnUnequalOneIsNot()throws Exception{
  for(boolean equal:new boolean[]{true,false}){
   Map<String,ClassNode> nodes=new HashMap<>();
   nodes.put("qzx/guest/Render",renderer("qzx/guest/Render","[",equal?"]":"}"));nodes.put("qzx/host/Shape",renderer("qzx/host/Shape","[","]"));
   ClassNode host=new ClassNode();host.visit(Opcodes.V21,Opcodes.ACC_PUBLIC,"qzx/host/Report",null,"java/lang/Object",null);
   MethodNode report=new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"report","(Ljava/lang/StringBuilder;Ljava/lang/Integer;)V",null,null);
   report.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));report.instructions.add(new VarInsnNode(Opcodes.ALOAD,1));
   report.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"qzx/host/Shape","render","(Ljava/lang/Integer;)Ljava/lang/String;",false));
   report.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"java/lang/StringBuilder","append","(Ljava/lang/String;)Ljava/lang/StringBuilder;",false));
   report.instructions.add(new InsnNode(Opcodes.POP));report.instructions.add(new InsnNode(Opcodes.RETURN));report.maxStack=2;report.maxLocals=2;host.methods.add(report);nodes.put(host.name,host);
   ClassNode mixin=new ClassNode();mixin.visit(Opcodes.V21,Opcodes.ACC_PUBLIC,"qzx/guest/Hooks",null,"java/lang/Object",null);
   MethodNode modify=new MethodNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC,"qzxShape","(Ljava/lang/Object;)Ljava/lang/Object;",null,null);
   LabelNode keep=new LabelNode();var c=modify.instructions;
   c.add(new VarInsnNode(Opcodes.ALOAD,0));c.add(new TypeInsnNode(Opcodes.INSTANCEOF,"java/lang/Integer"));c.add(new JumpInsnNode(Opcodes.IFEQ,keep));
   c.add(new VarInsnNode(Opcodes.ALOAD,0));c.add(new TypeInsnNode(Opcodes.CHECKCAST,"java/lang/Integer"));c.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"qzx/guest/Render","render","(Ljava/lang/Integer;)Ljava/lang/String;",false));c.add(new InsnNode(Opcodes.ARETURN));
   c.add(keep);c.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));c.add(new VarInsnNode(Opcodes.ALOAD,0));c.add(new InsnNode(Opcodes.ARETURN));modify.maxStack=1;modify.maxLocals=1;
   AnnotationNode at=new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");at.values=new ArrayList<>(List.of("value","INVOKE","target","Ljava/lang/StringBuilder;append(Ljava/lang/Object;)Ljava/lang/StringBuilder;"));
   AnnotationNode arg=new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/ModifyArg;");arg.values=new ArrayList<>(List.of("method",List.of("report"),"at",at));
   modify.visibleAnnotations=new ArrayList<>(List.of(arg));mixin.methods.add(modify);
   AnnotationNode target=new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");target.values=new ArrayList<>(List.of("value",List.of(Type.getObjectType(host.name))));mixin.invisibleAnnotations=new ArrayList<>(List.of(target));
   MixinDelegationEquivalence.remember(mixin);
   String proof=MixinDelegationEquivalence.proof(mixin.name,modify.name,modify.desc,host,nodes::get);
   if(equal)assertNotNull(proof);else assertNull(proof);
  }
 }
 /** {@code static String render(Integer v){return open + v + close;}} in {@code owner}, through a StringBuilder. */
 private static ClassNode renderer(String owner,String open,String close){
  ClassNode n=new ClassNode();n.visit(Opcodes.V21,Opcodes.ACC_PUBLIC,owner,null,"java/lang/Object",null);
  MethodNode m=new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"render","(Ljava/lang/Integer;)Ljava/lang/String;",null,null);var c=m.instructions;
  c.add(new TypeInsnNode(Opcodes.NEW,"java/lang/StringBuilder"));c.add(new InsnNode(Opcodes.DUP));c.add(new LdcInsnNode(open));c.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,"java/lang/StringBuilder","<init>","(Ljava/lang/String;)V",false));
  c.add(new VarInsnNode(Opcodes.ALOAD,0));c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"java/lang/StringBuilder","append","(Ljava/lang/Object;)Ljava/lang/StringBuilder;",false));
  c.add(new LdcInsnNode(close));c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"java/lang/StringBuilder","append","(Ljava/lang/String;)Ljava/lang/StringBuilder;",false));
  c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"java/lang/StringBuilder","toString","()Ljava/lang/String;",false));c.add(new InsnNode(Opcodes.ARETURN));m.maxStack=3;m.maxLocals=1;n.methods.add(m);return n;
 }

 // ---- the condition guard's consumer: resolved by the repair that supersedes the mixin ------------------------------

 @Test void theGuardsConsumerMissIsResolvedOnlyOnceTheSupersedingRepairIsSeen()throws Exception{
  MixinCompatibility.rememberOriginalConfig(CONFIG,"{\"required\":true,\"package\":\"net.fabricmc.fabric.mixin.resource.conditions\",\"mixins\":[\"SimpleJsonResourceReloadListenerMixin\"],\"injectors\":{\"defaultRequire\":1}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  ClassNode mixin=conditionsMixin();
  Function<String,byte[]> modules=path->{try(ZipFile z=new ZipFile(TestFixtures.fabricApi().toFile())){ZipEntry e=z.stream().filter(x->x.getName().startsWith("META-INF/jars/fabric-resource-conditions-api-v1-")).findFirst().orElseThrow();
   try(ZipInputStream in=new ZipInputStream(z.getInputStream(e))){for(ZipEntry x=in.getNextEntry();x!=null;x=in.getNextEntry())if(x.getName().equals(path))return in.readAllBytes();}}catch(Exception absent){return null;}return null;};
  SupersededMixins.noteMixin(mixin,modules);
  FinalMixinApplications.remember(mixin);ClassNode target=StagedFabricMixinFixture.game(READER,false);
  MethodNode original=StagedFabricMixinFixture.method(mixin,"skipData"),merged=new MethodNode(original.access,original.name,original.desc,original.signature,original.exceptions.toArray(String[]::new));original.accept(merged);merged.name="handler$qzx$skipData";
  AnnotationNode annotation=new AnnotationNode("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;");annotation.values=new ArrayList<>(List.of("mixin",CONDITIONS.replace('/','.')));merged.visibleAnnotations=List.of(annotation);target.methods.add(merged);
  var names=(FinalMixinApplications.Renames)(m,n,d)->n.equals("skipData")?List.of(new FinalMixinApplications.Renamed(merged.name,merged.desc)):List.of();
  FinalMixinApplications.observe(READER.replace('/','.'),StagedFabricMixinFixture.bytes(target),names);
  assertTrue(CompatibilityFindings.all().stream().noneMatch(f->f.id().contains("#skipData")&&f.confidence()==CompatibilityFinding.Confidence.RESOLVED),"not before the repair is seen");
  Path carrier=neo();TestFixtures.requireFiles(TestFixtures.Fixture.STAGED,"staged NeoForge carrier",carrier);byte[] ops;
  try(ZipFile z=new ZipFile(carrier.toFile())){ops=z.getInputStream(z.getEntry("net/neoforged/neoforge/common/conditions/ConditionalOps.class")).readAllBytes();}
  byte[] repaired=new net.forbric.kernel.transform.ForbricMergedBaseCompatTransformer().transform("net.neoforged.neoforge.common.conditions.ConditionalOps",ops,null);
  SupersededMixins.observeDefinition("net.neoforged.neoforge.common.conditions.ConditionalOps",repaired);
  assertTrue(CompatibilityFindings.all().stream().anyMatch(f->f.id().contains("#skipData")&&f.confidence()==CompatibilityFinding.Confidence.RESOLVED),CompatibilityFindings.all().toString());
 }

 @Test void fingerprintsIgnoreOnlyMetadataNotExecutableInstructions()throws Exception{
  MethodNode method=StagedFabricMixinFixture.method(conditionsMixin(),"skipData");String hash=MixinInstructionFingerprint.hash(method);
  method.maxStack+=10;method.maxLocals+=10;method.access|=Opcodes.ACC_SYNTHETIC;
  LabelNode label=new LabelNode();method.instructions.insert(label);method.instructions.insert(label,new LineNumberNode(999,label));
  assertEquals(hash,MixinInstructionFingerprint.hash(method));method.instructions.insert(new InsnNode(Opcodes.NOP));assertNotEquals(hash,MixinInstructionFingerprint.hash(method));
 }
}
