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
 @Test void ignoredInvertedAndWrongCallbackVerdictsCannotOwnTheNativeFallback()throws Exception{
  for(int variant=0;variant<3;variant++){final int change=variant;withBase((api,classes)->{
   ClassNode registry=guest(api,"RegistryLoadTaskPendingRegistrationMixin");MethodNode guard=registry.methods.stream().filter(m->MixinFit.injectorOf(m)!=null).findFirst().orElseThrow();
   MethodInsnNode evaluator=Arrays.stream(guard.instructions.toArray()).filter(i->i instanceof MethodInsnNode c&&c.getOpcode()==Opcodes.INVOKESTATIC&&Type.getReturnType(c.desc).equals(Type.BOOLEAN_TYPE)).map(MethodInsnNode.class::cast).findFirst().orElseThrow();
   if(change==0){while(evaluator.getNext()!=null)guard.instructions.remove(evaluator.getNext());guard.instructions.add(new InsnNode(Opcodes.POP));guard.instructions.add(new InsnNode(Opcodes.RETURN));}
   if(change==1){AbstractInsnNode branch=evaluator.getNext();while(branch.getOpcode()<0)branch=branch.getNext();JumpInsnNode original=(JumpInsnNode)branch;guard.instructions.set(branch,new JumpInsnNode(Opcodes.IFEQ,original.label));}
   if(change==2){MethodInsnNode cancel=Arrays.stream(guard.instructions.toArray()).filter(i->i instanceof MethodInsnNode c&&c.name.equals("setReturnValue")).map(MethodInsnNode.class::cast).findFirst().orElseThrow();VarInsnNode ci=null;for(var i=evaluator.getNext();i!=cancel;i=i.getNext())if(i instanceof VarInsnNode v&&v.getOpcode()==Opcodes.ALOAD){ci=v;break;}assertNotNull(ci);InsnList wrong=new InsnList();wrong.add(new InsnNode(Opcodes.ACONST_NULL));wrong.add(new VarInsnNode(Opcodes.ASTORE,ci.var));guard.instructions.insertBefore(ci,wrong);}
   assertEquals(0,MixinDecodeScopeAdapter.adapt(registry,classes),"variant "+change);
  });}
 }
 @Test void falseParseAndReassignedOriginalArgumentsCannotOwnTheClosedMarkerPair()throws Exception{
  for(int variant=0;variant<2;variant++){final int change=variant;withBase((api,classes)->{
   ClassNode json=guest(api,"SimpleJsonResourceReloadListenerMixin");MethodNode producer=json.methods.stream().filter(m->m.name.equals("applyResourceConditions")).findFirst().orElseThrow();
   if(change==0){MethodInsnNode evaluator=Arrays.stream(producer.instructions.toArray()).filter(i->i instanceof MethodInsnNode c&&c.getOpcode()==Opcodes.INVOKESTATIC&&Type.getReturnType(c.desc).equals(Type.BOOLEAN_TYPE)).map(MethodInsnNode.class::cast).findFirst().orElseThrow();AbstractInsnNode branch=evaluator.getNext();while(branch.getOpcode()<0)branch=branch.getNext();producer.instructions.set(branch,new JumpInsnNode(Opcodes.GOTO,((JumpInsnNode)branch).label));producer.instructions.insert(evaluator,new InsnNode(Opcodes.POP));}
   else{InsnList reassigned=new InsnList();reassigned.add(new InsnNode(Opcodes.ACONST_NULL));reassigned.add(new VarInsnNode(Opcodes.ASTORE,0));producer.instructions.insert(reassigned);}
   assertEquals(0,MixinDecodeScopeAdapter.adapt(json,classes));
  });}
 }
 @Test void finalCertificationRechecksTheVerdictAndTheMarkerConsumer()throws Exception{
  withBase((api,classes)->{
   ClassNode registry=guest(api,"RegistryLoadTaskPendingRegistrationMixin");assertEquals(1,MixinDecodeScopeAdapter.adapt(registry,classes));
   MethodNode source=registry.methods.stream().filter(m->Arrays.stream(m.instructions.toArray()).anyMatch(i->i instanceof MethodInsnNode c&&c.name.equals("record")&&c.owner.endsWith("KernelSourceDecodeScopes"))).findFirst().orElseThrow();
   assertTrue(certified(registry,source));MethodInsnNode record=Arrays.stream(source.instructions.toArray()).filter(i->i instanceof MethodInsnNode c&&c.name.equals("record")).map(MethodInsnNode.class::cast).findFirst().orElseThrow();AbstractInsnNode branch=record.getNext();while(branch.getOpcode()<0)branch=branch.getNext();source.instructions.set(branch,new JumpInsnNode(Opcodes.IFEQ,((JumpInsnNode)branch).label));assertFalse(certified(registry,source));
   ClassNode json=guest(api,"SimpleJsonResourceReloadListenerMixin");assertEquals(2,MixinDecodeScopeAdapter.adapt(json,classes));MethodNode producer=json.methods.stream().filter(m->Arrays.stream(m.instructions.toArray()).anyMatch(i->i instanceof MethodInsnNode c&&c.name.equals("record")&&c.owner.endsWith("KernelSourceDecodeScopes"))).findFirst().orElseThrow();assertTrue(certified(json,producer));
   MethodNode consumer=json.methods.stream().filter(m->m.name.equals("skipData")).findFirst().orElseThrow();for(var instruction:consumer.instructions)if(instruction instanceof MethodInsnNode call&&call.name.equals("cancel")){consumer.instructions.set(instruction,new InsnNode(Opcodes.POP));break;}assertFalse(certified(json,producer));
  });
 }
 private static boolean certified(ClassNode node,MethodNode method){
  MixinDecodeScopeAdapter.certify(node);byte[] data=bytes(node);class Loader extends ClassLoader{Loader(){super(MixinDecodeScopeAdapterTest.class.getClassLoader());}Class<?> define(){return defineClass(node.name.replace('/','.'),data,0,data.length);}}Loader loader=new Loader();Class<?> type=loader.define();net.forbric.kernel.boot.DefinedMethodContracts.observe(loader,type.getName(),data);return net.forbric.kernel.boot.KernelDecodeGuardWitnesses.validates(type,method.name,method.desc);
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
