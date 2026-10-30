/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;import java.util.*;import java.util.function.*;import java.util.zip.*;
import org.junit.jupiter.api.*;import org.objectweb.asm.*;import org.objectweb.asm.tree.*;
import net.forbric.api.Ecosystem;import net.forbric.kernel.TestFixtures;import net.forbric.kernel.boot.KernelCrossHostPredicateContracts;import net.forbric.kernel.transform.VanillaAirRefillInjector;
class MixinCrossHostPredicateIslandTest implements Opcodes {
 @AfterEach void reset(){MixinCrossHostPredicateIsland.resetForTests();}
 static ClassNode node(byte[]bytes){if(bytes==null)return null;ClassNode n=new ClassNode();new ClassReader(bytes).accept(n,0);return n;}
 static byte[] read(ZipFile jar,String path){try{var e=jar.getEntry(path);return e==null?null:jar.getInputStream(e).readAllBytes();}catch(Exception failure){throw new IllegalStateException(failure);}}
 static final class Inputs implements AutoCloseable{
  final ZipFile base,neo;final ClassNode source;final NativeGameReferences original;final Map<String,ClassNode>cache=new HashMap<>();
  Inputs()throws Exception{Path api=Path.of(System.getProperty("forbric.fabricApi",TestFixtures.fabricApi().toString())),basePath=Path.of(System.getProperty("forbric.predicateBase",TestFixtures.stagedBase().toString())),neoPath=Path.of(System.getProperty("forbric.predicateNeo",TestFixtures.stagedRoot().resolve(TestFixtures.NEO_CARRIER_JAR).toString()));if(!Files.exists(neoPath))neoPath=Path.of("forbric-loader/run/" + TestFixtures.NEO_CARRIER_JAR);if(!Files.exists(neoPath))neoPath=Path.of("../forbric-loader/run/" + TestFixtures.NEO_CARRIER_JAR);TestFixtures.requireFiles(TestFixtures.Fixture.STAGED,"whole predicate source/current/native",api,basePath,neoPath);base=new ZipFile(basePath.toFile());neo=new ZipFile(neoPath.toFile());original=new NativeGameReferences(p->read(base,p));ClassNode found=null;try(var outer=new ZipFile(api.toFile())){var module=outer.stream().filter(e->e.getName().startsWith("META-INF/jars/fabric-content-registries-v0-")).findFirst().orElseThrow();try(var inner=new ZipInputStream(outer.getInputStream(module))){for(var e=inner.getNextEntry();e!=null;e=inner.getNextEntry())if(e.getName().equals("net/fabricmc/fabric/mixin/content/registry/fluid/LivingEntityMixin.class"))found=node(inner.readAllBytes());}}source=Objects.requireNonNull(found);
   // Protocol metadata from the physical-view injector; runtime admission still requires the complete registered
   // physical schema, its final definitions and actual ready field. This node test never claims that view is ready.
   ClassNode interaction=current("net/minecraft/world/entity/EntityFluidInteraction");MethodNode marker=new MethodNode(ACC_PRIVATE,"$testPhysicalProtocol","()V",null,null);marker.instructions.add(new VarInsnNode(ALOAD,0));marker.instructions.add(new LdcInsnNode("test-view-schema"));marker.instructions.add(new MethodInsnNode(INVOKESTATIC,"net/forbric/kernel/runtime/KernelFabricTagViews","begin","(Ljava/lang/Object;Ljava/lang/String;)V",false));marker.instructions.add(new InsnNode(RETURN));interaction.methods.add(marker);
  }
  ClassNode current(String name){if(cache.containsKey(name))return cache.get(name);byte[]bytes=read(base,name+".class");if(bytes==null)bytes=read(neo,name+".class");ClassNode n=node(bytes);cache.put(name,n);return n;}
  int bind(BiPredicate<String,Runnable>register){return MixinCrossHostPredicateIsland.adapt(source,this::current,k->original.get(Ecosystem.FABRIC,k),register);}
  public void close()throws Exception{base.close();neo.close();}
 }
 @Test void actualDefaultClosureIncludesItsPrivateOrPublicIdentityAndTheBoundSpecialType()throws Exception{try(var in=new Inputs()){assertEquals(1,in.bind((name,r)->{r.run();return true;}));var plan=MixinCrossHostPredicateIsland.plans().iterator().next();assertEquals(1,plan.schema().excluded().size());assertTrue(plan.schema().actorGuards().stream().anyMatch(m->m.name().equals("self")));assertTrue(plan.schema().flags().values().stream().noneMatch(f->(f&(ACC_NATIVE|ACC_ABSTRACT|ACC_SYNCHRONIZED))!=0));}}
 /**
  * The kernel's vanilla-refill decoration of the actual hook stays in the executable host and is recorded in the plan, so the
  * island's protected decision hands the event vanilla's refill too (KernelAirRefill.waterRefill) instead of the carrier's zero;
  * the undecorated hook keeps the zero; an unknown shape of the decoration declines.
  */
 @Test void theActualVanillaRefillDecorationIsKeptAndTheIslandsProtectedDecisionFollowsIt()throws Exception{
  String host="net/neoforged/neoforge/common/CommonHooks";
  try(var in=new Inputs()){byte[]raw=read(in.neo,host+".class");ClassNode decorated=node(new VanillaAirRefillInjector().transform(host.replace('/','.'),raw,null));in.cache.put(host,decorated);
   assertEquals(1,in.bind((name,r)->{r.run();return true;}));var plan=MixinCrossHostPredicateIsland.plans().iterator().next().plan();
   assertTrue(plan.vanillaRefill(),"the decorated hook's protected branch hands its event vanilla's refill");
   assertTrue(Arrays.stream(plan.method().instructions.toArray()).anyMatch(i->i instanceof MethodInsnNode c&&c.name.equals(VanillaAirRefillInjector.PROTECTED)),"the decoration stays in the executable host");
   assertTrue(Arrays.stream(plan.method().instructions.toArray()).anyMatch(i->i instanceof MethodInsnNode c&&c.name.equals(VanillaAirRefillInjector.UPDATE)),"its air update too");
   assertEquals(List.of(VanillaAirRefillInjector.WATER),islandRefills(decorated,host),"the island's protected decision asks vanilla's water-branch refill");
   MixinCrossHostPredicateIsland.resetForTests();}
  try(var in=new Inputs()){in.cache.put(host,node(read(in.neo,host+".class")));assertEquals(1,in.bind((name,r)->{r.run();return true;}));var plan=MixinCrossHostPredicateIsland.plans().iterator().next().plan();
   assertFalse(plan.vanillaRefill(),"NeoForge's own hook zeroes the refill");assertEquals(List.of(),islandRefills(node(read(in.neo,host+".class")),host),"switched off, the island hands the carrier's zero");
   MixinCrossHostPredicateIsland.resetForTests();}
  for(String unknown:List.of(VanillaAirRefillInjector.PROTECTED,VanillaAirRefillInjector.UPDATE))try(var in=new Inputs()){ClassNode decorated=node(new VanillaAirRefillInjector().transform(host.replace('/','.'),read(in.neo,host+".class"),null));for(var m:decorated.methods)for(var i:m.instructions)if(i instanceof MethodInsnNode c&&c.name.equals(unknown))c.name="otherRefill";in.cache.put(host,decorated);assertEquals(0,in.bind((name,r)->{r.run();return true;}),unknown);assertTrue(MixinCrossHostPredicateIsland.plans().isEmpty());}
 }
 /** The kernel refill calls the island's prefix adds to {@code hook} once CrossHostPredicateIslandInjector has put it into the hook's class. */
 private static List<String> islandRefills(ClassNode hook,String host){ClassWriter w=new ClassWriter(0);hook.accept(w);byte[]in=w.toByteArray(),out=new CrossHostPredicateIslandInjector().transform(host.replace('/','.'),in,null);assertNotSame(in,out,"the island is put into the hook");
  List<String>calls=refills(node(out));for(String before:refills(hook))calls.remove(before);return calls;}
 private static List<String> refills(ClassNode hook){List<String>calls=new ArrayList<>();for(var m:hook.methods)if(m.name.equals("onLivingBreathe"))for(var i:m.instructions)if(i instanceof MethodInsnNode c&&c.owner.equals(VanillaAirRefillInjector.RUNTIME))calls.add(c.name);return calls;}
 @Test void typedEffectsAndSynchronizedDefaultsCannotBeDiscarded()throws Exception{try(var in=new Inputs()){ClassNode type=in.current("net/neoforged/neoforge/common/extensions/ILivingEntityExtension");MethodNode predicate=type.methods.stream().filter(m->m.name.equals("canDrownInFluidType")).findFirst().orElseThrow();predicate.instructions.insert(new MethodInsnNode(INVOKESTATIC,"test/Changed","sideEffect","()V",false));assertEquals(0,in.bind((name,r)->{r.run();return true;}));}try(var in=new Inputs()){ClassNode type=in.current("net/neoforged/neoforge/common/extensions/ILivingEntityExtension");type.methods.stream().filter(m->m.name.equals("canDrownInFluidType")).findFirst().orElseThrow().access|=ACC_SYNCHRONIZED;assertEquals(0,in.bind((name,r)->{r.run();return true;}));}}
 @Test void lateRejectionAndPublicationFailureKeepTheSourceAndPlanUnchanged()throws Exception{try(var in=new Inputs()){var handler=in.source.methods.stream().filter(m->m.name.equals("customFluidDrowning")).findFirst().orElseThrow();String before=MixinInstructionFingerprint.hash(handler);int count=in.source.methods.size();assertEquals(0,in.bind((name,r)->false));assertEquals(count,in.source.methods.size());assertEquals(before,MixinInstructionFingerprint.hash(handler));assertTrue(MixinCrossHostPredicateIsland.plans().isEmpty());assertThrows(IllegalStateException.class,()->in.bind((name,r)->{throw new IllegalStateException("registration refused");}));assertTrue(MixinCrossHostPredicateIsland.plans().isEmpty());assertEquals(count,in.source.methods.size());}}
}
