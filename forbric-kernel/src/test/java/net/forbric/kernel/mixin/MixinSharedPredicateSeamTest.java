package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.zip.*;
import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.runtime.StagedGameClassLoader;
import net.forbric.kernel.transform.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

@ResourceLock("system-properties")
public class MixinSharedPredicateSeamTest {
 private static final String HOST="net/minecraft/world/entity/Entity",CONTROL=HOST+"FluidInteraction";
 @AfterEach void reset(){System.clearProperty(MixinSharedPredicateSeam.PROPERTY);}
 @Test void actualApiKeepsBothBodiesAndBindsOneExplicitRefAfterTheCompleteNativeProof()throws Exception{Inputs input=inputs();ClassNode guest=input.guest();Map<String,String>bodies=new HashMap<>();for(MethodNode handler:guest.methods)if(handler.name.equals("checkIfUnderSwimmableFluid")||handler.name.equals("checkIfStandingInSwimmableFluid"))bodies.put(handler.name,MixinInstructionFingerprint.hash(handler));assertEquals(2,MixinSharedPredicateSeam.adapt(guest,input.classes(),input.source(),input.carrier()));
  for(MethodNode handler:guest.methods)if(bodies.containsKey(handler.name)){assertEquals(bodies.get(handler.name),MixinInstructionFingerprint.hash(handler));List<String>selectors=MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(handler),"method"));assertEquals(1,selectors.size());assertTrue(selectors.getFirst().contains("forbricsharedsource"));}
  ClassNode target=MixinFit.withSelfAddedMethods(guest,input.classes().apply(HOST));assertEquals(2,guest.methods.stream().filter(m->m.name.contains("forbricsharedsource")&&!m.name.endsWith("typed")&&MixinFit.injectorOf(m)==null).count());assertTrue(target.methods.stream().anyMatch(m->m.name.contains("forbricsharedsource")));assertEquals(0,MixinSharedPredicateSeam.adapt(guest,input.classes(),input.source(),input.carrier()));
 }
 @Test void changedCarrierContinuationQuestionAndGuardRefuseTheOriginalPair()throws Exception{Inputs input=inputs();ClassNode host=input.classes().apply(HOST);MethodNode body=host.methods.stream().filter(m->m.name.equals("updateSwimming")).findFirst().orElseThrow();body.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC,"unproved/Effects","run","()V",false));Inputs original=input;assertEquals(0,MixinSharedPredicateSeam.adapt(input.guest(),name->name.equals(HOST)?host:original.classes().apply(name),input.source(),input.carrier()));
  input=inputs();ClassNode iface=input.classes().apply("net/neoforged/neoforge/common/extensions/IEntityExtension");MethodNode lambda=iface.methods.stream().filter(m->m.name.startsWith("lambda$")&&m.desc.endsWith("D)Z")).findFirst().orElseThrow();lambda.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC,"unproved/Effects","run","()V",false));Inputs second=input;assertEquals(0,MixinSharedPredicateSeam.adapt(input.guest(),name->name.equals(iface.name)?iface:second.classes().apply(name),input.source(),input.carrier()));
 }
 @Test void missingRootsMissingRecordsAndOffLeaveTheOriginalHandlers()throws Exception{Inputs input=inputs();assertEquals(0,MixinSharedPredicateSeam.adapt(input.guest(),input.classes(),name->null,input.carrier()));assertEquals(0,MixinSharedPredicateSeam.adapt(input.guest(),input.classes(),input.source(),name->null));System.setProperty(MixinSharedPredicateSeam.PROPERTY,"off");assertEquals(0,MixinSharedPredicateSeam.adapt(input.guest(),input.classes(),input.source(),input.carrier()));}
 public record Inputs(ClassNode guest,Function<String,ClassNode>classes,Function<String,ClassNode>source,Function<String,ClassNode>carrier){ }
 public static Inputs inputs()throws Exception{Path base=Path.of(System.getProperty("forbric.predicateBase",TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar").toString()));TestFixtures.requireFiles(Fixture.STAGED,"actual native pair",base);Set<Path>paths=new LinkedHashSet<>();paths.add(base);for(var url:StagedGameClassLoader.urls())paths.add(Path.of(url.toURI()));Function<String,byte[]>resources=name->{try{for(Path path:paths){if(Files.isDirectory(path)){Path file=path.resolve(name);if(Files.isRegularFile(file))return Files.readAllBytes(file);}else try(ZipFile jar=new ZipFile(path.toFile())){var entry=jar.getEntry(name);if(entry!=null)return jar.getInputStream(entry).readAllBytes();}}try(var in=MixinSharedPredicateSeamTest.class.getResourceAsStream("/"+name)){return in==null?null:in.readAllBytes();}}catch(java.io.IOException unavailable){throw new RuntimeException(unavailable);}};
  NativeGameReferences refs=new NativeGameReferences(resources);Map<String,ClassNode>cache=new HashMap<>();Function<String,ClassNode>classes=name->{if(cache.containsKey(name))return cache.get(name);byte[]bytes=resources.apply(name+".class");if(bytes==null)return null;bytes=InjectorExecution.transform(new ForeignFluidTypeInjector(),name.replace('/','.'),bytes,EnvType.SERVER);bytes=InjectorExecution.transform(new UntrackedFluidEyeQueryInjector(),name.replace('/','.'),bytes,EnvType.SERVER);bytes=InjectorExecution.transform(new PredicateGetterResultRecorder(),name.replace('/','.'),bytes,EnvType.SERVER);ClassNode node=parse(bytes);cache.put(name,node);return node;};byte[]control=resources.apply(CONTROL+".class");control=InjectorExecution.transform(new UntrackedFluidEyeQueryInjector(),CONTROL.replace('/','.'),control,EnvType.SERVER);control=InjectorExecution.transform(new NativeTagTrackerSourceInjector(name->refs.get(Ecosystem.FABRIC,name),classes),CONTROL.replace('/','.'),control,EnvType.SERVER);assertNotSame(resources.apply(CONTROL+".class"),control);cache.put(CONTROL,parse(control));
  Path api=Path.of(System.getProperty("forbric.fabricApi",TestFixtures.fabricApi().toString()));TestFixtures.requireFiles(Fixture.STAGED,"actual Fabric API",api);ClassNode guest=null;try(ZipFile outer=new ZipFile(api.toFile())){var nested=outer.stream().filter(e->e.getName().startsWith("META-INF/jars/fabric-content-registries-v0-")).findFirst().orElseThrow();try(ZipInputStream inner=new ZipInputStream(outer.getInputStream(nested))){for(var e=inner.getNextEntry();e!=null;e=inner.getNextEntry())if(e.getName().equals("net/fabricmc/fabric/mixin/content/registry/fluid/EntityMixin.class"))guest=parse(inner.readAllBytes());}}assertNotNull(guest);
  return new Inputs(guest,classes,name->refs.get(Ecosystem.FABRIC,name),name->{ClassNode original=refs.get(Ecosystem.NEOFORGE,name);return original==null?parse(resources.apply(name+".class")):original;});
 }
 private static ClassNode parse(byte[]bytes){if(bytes==null)return null;ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,ClassReader.EXPAND_FRAMES);return node;}
}
