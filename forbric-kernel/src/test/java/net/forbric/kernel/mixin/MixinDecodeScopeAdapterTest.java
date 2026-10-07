package net.forbric.kernel.mixin;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;import java.util.*;import java.util.zip.*;
import org.junit.jupiter.api.Test;import org.objectweb.asm.*;import org.objectweb.asm.tree.*;
import net.forbric.kernel.TestFixtures;
class MixinDecodeScopeAdapterTest {
 @Test void theActualRegistryGuardAndClosedJsonMarkerPairAreTransported()throws Exception{
  Path api=Path.of(System.getProperty("forbric.fabricApi",TestFixtures.fabricApi().toString()));Path base=Path.of(System.getProperty("forbric.predicateBase","../forbric-loader/run/merged-base/patched-mc-merged-26.2.jar"));
  TestFixtures.requireFiles(TestFixtures.Fixture.STAGED,"actual Fabric API and indexed game base required",api,base);
  try(ZipFile game=new ZipFile(base.toFile())){
   var classes=(java.util.function.Function<String,ClassNode>)(name->{var entry=game.getEntry(name+".class");if(entry==null)return null;try{return read(game.getInputStream(entry).readAllBytes());}catch(Exception e){throw new IllegalStateException(e);}});
   ClassNode registry=guest(api,"RegistryLoadTaskPendingRegistrationMixin");assertEquals(1,MixinDecodeScopeAdapter.adapt(registry,classes));assertTrue(registry.methods.stream().anyMatch(m->has(m,"Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;")));
   ClassNode json=guest(api,"SimpleJsonResourceReloadListenerMixin");assertEquals(2,MixinDecodeScopeAdapter.adapt(json,classes));assertTrue(json.methods.stream().filter(m->m.name.equals("skipData")).allMatch(m->MixinFit.injectorOf(m)==null));
   assertEquals(1,json.methods.stream().flatMap(m->Arrays.stream(m.instructions.toArray())).filter(i->i instanceof MethodInsnNode c&&c.owner.endsWith("KernelSourceDecodeScopes")&&c.name.equals("record")).count());
  }
 }
 @Test void aConsumerWhichUsesItsOtherArgumentsCannotBeMoved()throws Exception{
  withBase((api,classes)->{
   ClassNode json=guest(api,"SimpleJsonResourceReloadListenerMixin");var skip=json.methods.stream().filter(m->m.name.equals("skipData")).findFirst().orElseThrow();
   InsnList read=new InsnList();read.add(new VarInsnNode(Opcodes.ALOAD,0));read.add(new InsnNode(Opcodes.POP));skip.instructions.insert(read);skip.maxStack++;
   assertEquals(0,MixinDecodeScopeAdapter.adapt(json,classes));
  });
 }
 @Test void changedOperationArgumentsDoNotGetScopedAsTheOriginalParse()throws Exception{
  withBase((api,classes)->{
   ClassNode json=guest(api,"SimpleJsonResourceReloadListenerMixin");var producer=json.methods.stream().filter(m->m.name.equals("applyResourceConditions")).findFirst().orElseThrow();
   var call=Arrays.stream(producer.instructions.toArray()).filter(i->i instanceof MethodInsnNode c&&c.owner.endsWith("/Operation")).findFirst().orElseThrow();
   AbstractInsnNode input=call.getPrevious();while(input.getOpcode()<0)input=input.getPrevious();input=input.getPrevious();while(input.getOpcode()<0)input=input.getPrevious();assertInstanceOf(VarInsnNode.class,input);((VarInsnNode)input).var=0;
   assertEquals(0,MixinDecodeScopeAdapter.adapt(json,classes));
  });
 }
 @Test void preflightChecksTheAdaptedClosedPairAndTheOffControlLeavesItUntouched()throws Exception{
  withBase((api,classes)->{
   ClassNode json=guest(api,"SimpleJsonResourceReloadListenerMixin");byte[] original=bytes(json);
   byte[] adapted=MixinDecodeScopeAdapter.asLoaded(original,path->{ClassNode node=classes.apply(path.substring(0,path.length()-6));return node==null?null:bytes(node);});
   assertNotSame(original,adapted);ClassNode changed=read(adapted);assertTrue(changed.methods.stream().filter(m->m.name.equals("skipData")).allMatch(m->MixinFit.injectorOf(m)==null));
   String previous=System.getProperty(MixinDecodeScopeAdapter.PROPERTY);System.setProperty(MixinDecodeScopeAdapter.PROPERTY,"off");try{assertEquals(0,MixinDecodeScopeAdapter.adapt(json,classes));}finally{if(previous==null)System.clearProperty(MixinDecodeScopeAdapter.PROPERTY);else System.setProperty(MixinDecodeScopeAdapter.PROPERTY,previous);}
  });
 }
 private interface Check{void run(Path api,java.util.function.Function<String,ClassNode>classes)throws Exception;}
 private void withBase(Check check)throws Exception{
  Path api=Path.of(System.getProperty("forbric.fabricApi",TestFixtures.fabricApi().toString())),base=Path.of(System.getProperty("forbric.predicateBase","../forbric-loader/run/merged-base/patched-mc-merged-26.2.jar"));
  TestFixtures.requireFiles(TestFixtures.Fixture.STAGED,"actual Fabric API and indexed game base required",api,base);
  try(ZipFile game=new ZipFile(base.toFile())){check.run(api,name->{var entry=game.getEntry(name+".class");if(entry==null)return null;try{return read(game.getInputStream(entry).readAllBytes());}catch(Exception e){throw new IllegalStateException(e);}});}
 }
 static byte[]bytes(ClassNode node){ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);return writer.toByteArray();}
 static boolean has(MethodNode m,String annotation){return m.visibleAnnotations!=null&&m.visibleAnnotations.stream().anyMatch(a->a.desc.equals(annotation));}
 static ClassNode guest(Path api,String name)throws Exception{try(ZipFile z=new ZipFile(api.toFile())){var module=z.stream().filter(e->e.getName().startsWith("META-INF/jars/fabric-resource-conditions-api-v1-")).findFirst().orElseThrow();try(ZipInputStream inner=new ZipInputStream(z.getInputStream(module))){for(var e=inner.getNextEntry();e!=null;e=inner.getNextEntry())if(e.getName().endsWith("/"+name+".class"))return read(inner.readAllBytes());}}throw new IllegalStateException(name);}
 static ClassNode read(byte[]bytes){ClassNode n=new ClassNode();new ClassReader(bytes).accept(n,0);return n;}
}
