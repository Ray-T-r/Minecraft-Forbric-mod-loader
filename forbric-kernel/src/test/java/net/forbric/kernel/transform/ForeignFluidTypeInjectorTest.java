/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;import java.util.*;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.*;import org.objectweb.asm.tree.*;import org.objectweb.asm.tree.analysis.*;
import net.forbric.kernel.TestFixtures;
/** Actual public SDK branches remain calls, and both retained caches distinguish live fallback results. */
@ResourceLock("system-properties")
class ForeignFluidTypeInjectorTest {
 private static final Path STAGED=TestFixtures.stagedRoot(),MERGED=STAGED.resolve("merged-base/patched-mc-merged-26.2.jar");
 @AfterEach void reset(){System.clearProperty(ForeignFluidTypeInjector.PROPERTY);}
 @Test void bothActualCachedGettersKeepTheirNativeLookupAndOneCommonReturn()throws Exception{
  byte[] original=NativeCoremodParityTest.read(MERGED,ForeignFluidTypeInjector.FLUID_INTERNAL);var transform=new ForeignFluidTypeInjector();byte[] out=transform.transform(ForeignFluidTypeInjector.FLUID,original,null);assertNotSame(original,out);
  ClassNode node=read(out);for(String type:List.of(ForeignFluidTypeInjector.TYPE,ForeignFluidTypeInjector.FORGE_TYPE)){
   MethodNode method=find(node,"getFluidType","()L"+type+";");List<MethodInsnNode> calls=Arrays.stream(method.instructions.toArray()).filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).toList();
   assertEquals(1,calls.stream().filter(c->c.name.equals("getVanillaFluidType")).count(),"native SDK lookup still runs exactly once on each cache miss");
   assertTrue(calls.stream().anyMatch(c->c.owner.equals(ForeignFluidTypeInjector.SCOPES)&&c.name.equals("takeForeign")));
   assertEquals(1,Arrays.stream(method.instructions.toArray()).filter(i->i.getOpcode()==Opcodes.ARETURN).count(),"RETURN and TAIL retain their single exit");
   assertTrue(Arrays.stream(method.instructions.toArray()).anyMatch(i->i instanceof FieldInsnNode f&&f.getOpcode()==Opcodes.PUTFIELD&&f.desc.equals("L"+type+";")),"native cache writes remain");
   new Analyzer<>(new BasicVerifier()).analyze(node.name,method);
  }assertSame(out,transform.transform(ForeignFluidTypeInjector.FLUID,out,null));
 }
 @Test void eachSdkUnsupportedTailRetainsAllBuiltinBranchesAndItsActualOriginalExceptionForNullInput()throws Exception{
  for(var family:List.of(new String[]{"forge-runtime/forge-runtime.jar",ForeignFluidTypeInjector.FORGE_HOOKS,ForeignFluidTypeInjector.FORGE_TYPE,"foreignForgeLookup"},new String[]{"neoforge-runtime/neoforge-runtime.jar",ForeignFluidTypeInjector.NEO_HOOKS,ForeignFluidTypeInjector.TYPE,"foreignNeoLookup"})){
   byte[] bytes=NativeCoremodParityTest.read(STAGED.resolve(family[0]),family[1]);byte[] out=new ForeignFluidTypeInjector().transform(family[1].replace('/','.'),bytes,null);assertNotSame(bytes,out);
   MethodNode before=find(read(bytes),"getVanillaFluidType","(L"+ForeignFluidTypeInjector.FLUID_INTERNAL+";)L"+family[2]+";"),after=find(read(out),before.name,before.desc);
   List<String> previous=realCalls(before),current=realCalls(after).stream().filter(c->!c.startsWith(ForeignFluidTypeInjector.RUNTIME)&&!c.startsWith(ForeignFluidTypeInjector.SCOPES)).toList();assertEquals(previous,current,"all native provider/optional/milk and original exception-constructor calls remain in order");
   assertTrue(Arrays.stream(after.instructions.toArray()).anyMatch(i->i instanceof MethodInsnNode c&&c.owner.equals(ForeignFluidTypeInjector.RUNTIME)&&c.name.equals(family[3])));
   new Analyzer<>(new BasicVerifier()).analyze(family[1],after);assertSame(out,new ForeignFluidTypeInjector().transform(family[1].replace('/','.'),out,null));
  }
 }
 @Test void switchedOffAllPublicLookupAndCacheContractsRemainUnchanged(){System.setProperty(ForeignFluidTypeInjector.PROPERTY,"off");for(var entry:List.of(new String[]{"merged-base/patched-mc-merged-26.2.jar",ForeignFluidTypeInjector.FLUID_INTERNAL},new String[]{"forge-runtime/forge-runtime.jar",ForeignFluidTypeInjector.FORGE_HOOKS},new String[]{"neoforge-runtime/neoforge-runtime.jar",ForeignFluidTypeInjector.NEO_HOOKS})){byte[] bytes=NativeCoremodParityTest.read(STAGED.resolve(entry[0]),entry[1]);assertSame(bytes,new ForeignFluidTypeInjector().transform(entry[1].replace('/','.'),bytes,null));}}
 private static List<String> realCalls(MethodNode method){return Arrays.stream(method.instructions.toArray()).filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).map(c->c.owner+"."+c.name+c.desc).toList();}
 private static ClassNode read(byte[] bytes){ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);return node;}
 private static MethodNode find(ClassNode owner,String name,String desc){return owner.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElseThrow();}
}
