package net.forbric.kernel.mixin;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.management.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import net.forbric.api.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
@ResourceLock("ModCatalog")
class WatchdogDumpEquivalenceTest {
 @TempDir Path temporary;
 private static final String CONFIG="fabric-crash-report-info-v1.mixins.json";
 @BeforeEach @AfterEach void reset(){MixinCompatibility.reset();CompatibilityFindings.reset();MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned(CONFIG,"crash-info",Ecosystem.FABRIC)));}
 private Path neo(){return Path.of(System.getenv().getOrDefault("NEO_RT",Path.of(System.getenv().getOrDefault("FORBRIC_OLD","../forbric-loader"),"run/neoforge-runtime/neoforge-runtime.jar").toString()));}
 private ClassNode helper()throws Exception{try(ZipFile z=new ZipFile(neo().toFile())){return MixinFit.parse(z.getInputStream(z.getEntry(WatchdogDumpEquivalence.HELPER.replace('.','/')+".class")).readAllBytes());}}
 private ClassNode target(boolean changedHandler)throws Exception{
  MixinCompatibility.rememberOriginalConfig(CONFIG,"{\"required\":true,\"package\":\"net.fabricmc.fabric.mixin.crash.report.info\",\"mixins\":[\"ServerWatchdogMixin\"],\"injectors\":{\"defaultRequire\":1}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  ClassNode mixin=StagedFabricMixinFixture.mixin("fabric-crash-report-info-v1",WatchdogDumpEquivalence.MIXIN.replace('.','/'));
  MethodNode original=StagedFabricMixinFixture.method(mixin,WatchdogDumpEquivalence.HANDLER);if(changedHandler)original.instructions.insert(new InsnNode(Opcodes.NOP));FinalMixinApplications.remember(mixin);
  ClassNode target=StagedFabricMixinFixture.game(WatchdogDumpEquivalence.TARGET.replace('.','/'),false);
  MethodNode handler=new MethodNode(original.access,"modify$canary$dump",original.desc,null,null);original.accept(handler);handler.name="modify$canary$dump";
  AnnotationNode merged=new AnnotationNode("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;");merged.values=List.of("mixin",WatchdogDumpEquivalence.MIXIN);handler.visibleAnnotations=List.of(merged);target.methods.add(handler);return target;
 }
 private void observeTarget(ClassNode target){FinalMixinApplications.observe(WatchdogDumpEquivalence.TARGET,StagedFabricMixinFixture.bytes(target),(m,n,d)->List.of(new FinalMixinApplications.Renamed("modify$canary$dump",WatchdogDumpEquivalence.DESC)));}
 private void observeHelper(ClassNode helper){FinalMixinApplications.observe(WatchdogDumpEquivalence.HELPER,StagedFabricMixinFixture.bytes(helper),(m,n,d)->List.of());}
 private CompatibilityFinding finding(){return CompatibilityFindings.all().stream().filter(f->f.id().startsWith("mixin-injector:")).findFirst().orElseThrow();}
 @Test void actualDefinitionIsPendingUntilTheActualHelperIsDefinedAndRechecksChangedHelpers()throws Exception{
  observeTarget(target(false));assertEquals(CompatibilityFinding.Confidence.SUSPECTED,finding().confidence());assertTrue(CompatibilityFindings.confirmedRequired().isEmpty());
  ClassNode helper=helper();observeHelper(helper);assertEquals(CompatibilityFinding.Confidence.RESOLVED,finding().confidence());
  StagedFabricMixinFixture.method(helper,"getEntireStacktrace").instructions.insert(new InsnNode(Opcodes.NOP));observeHelper(helper);assertEquals(CompatibilityFinding.Confidence.CONFIRMED,finding().confidence());
 }
 @Test void helperFirstAndUnknownHandlerOrCallerBodiesCannotBorrowTheProof()throws Exception{
  observeHelper(helper());ClassNode target=target(false);observeTarget(target);assertEquals(CompatibilityFinding.Confidence.RESOLVED,finding().confidence());
  target.methods.stream().filter(m->m.name.equals("createWatchdogCrashReport")&&Type.getArgumentTypes(m.desc).length==3).findFirst().orElseThrow().instructions.insert(new InsnNode(Opcodes.NOP));observeTarget(target);assertEquals(CompatibilityFinding.Confidence.CONFIRMED,finding().confidence());
  reset();observeHelper(helper());observeTarget(target(true));assertEquals(CompatibilityFinding.Confidence.CONFIRMED,finding().confidence());
 }
 @Test void aDefinedUnknownHelperIsConfirmedMissingInsteadOfDeferredForever()throws Exception{
  ClassNode helper=helper();helper.methods.removeIf(m->m.name.equals("getEntireStacktrace"));observeHelper(helper);observeTarget(target(false));assertEquals(CompatibilityFinding.Confidence.CONFIRMED,finding().confidence());
 }
 @Test void bothActualUpstreamRenderersRetainEveryFrameBeyondTheJdkEightFrameLimit()throws Exception{
  Path fabric=temporary.resolve("crash-info.jar");try(ZipFile z=new ZipFile("run/client-merged-pack/mods/fabric-api-0.155.2+26.2.jar")){var entry=z.stream().filter(e->e.getName().startsWith("META-INF/jars/fabric-crash-report-info-v1-")).findFirst().orElseThrow();Files.write(fabric,z.getInputStream(entry).readAllBytes());}
  try(URLClassLoader loader=new URLClassLoader(new URL[]{neo().toUri().toURL(),fabric.toUri().toURL()},ClassLoader.getPlatformClassLoader())){
   var nativeRender=loader.loadClass(WatchdogDumpEquivalence.HELPER).getMethod("getEntireStacktrace",ThreadInfo.class);var fabricRender=loader.loadClass("net.fabricmc.fabric.impl.crash.report.info.ThreadPrinting").getMethod("fullThreadInfoToString",ThreadInfo.class);
   deep(30,()->{ThreadInfo info=ManagementFactory.getThreadMXBean().getThreadInfo(Thread.currentThread().threadId(),1000);try{
    String nativeText=(String)nativeRender.invoke(null,info),fabricText=(String)fabricRender.invoke(null,info);assertTrue(info.getStackTrace().length>30);
    for(StackTraceElement frame:info.getStackTrace()){assertTrue(nativeText.contains(frame.toString()),frame.toString());assertTrue(fabricText.contains(frame.toString()),frame.toString());}
    assertTrue(nativeText.split("\\.deep\\(",-1).length>30);assertTrue(fabricText.split("\\.deep\\(",-1).length>30);assertTrue(info.toString().split("\\.deep\\(",-1).length<30);
   }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}});
  }
 }
 private static void deep(int depth,Runnable action){if(depth==0)action.run();else deep(depth-1,action);}
}
