package net.forbric.kernel.transform;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.NativeGameReferences;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
final class NativeMergeFixtures {
 static ClassNode node(byte[] bytes){ClassNode n=new ClassNode();new ClassReader(bytes).accept(n,0);return n;}
 static byte[] bytes(ClassNode n){ClassWriter w=new ClassWriter(ClassWriter.COMPUTE_MAXS);n.accept(w);return w.toByteArray();}
 static NativeGameReferences references(Map<String,byte[]> forge,Map<String,byte[]> neo)throws Exception{
  Map<String,byte[]> resources=new HashMap<>();
  for(Ecosystem family:List.of(Ecosystem.FORGE,Ecosystem.NEOFORGE)){
   String prefix="META-INF/forbric/native-reference/"+family+"/";StringBuilder index=new StringBuilder("# forbric-native-reference-v1\n");
   for(var e:new TreeMap<>(family==Ecosystem.FORGE?forge:neo).entrySet()){
    index.append(e.getKey()).append('\t').append(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(e.getValue()))).append('\n');
    resources.put(prefix+e.getKey()+".class.bin",e.getValue());
   }resources.put(prefix+"index.tsv",index.toString().getBytes(StandardCharsets.UTF_8));
  }return new NativeGameReferences(resources::get);
 }
 static Function<String,ClassNode> current(Map<String,byte[]> source){return name->{
  byte[] bytes=source.get(name);if(bytes!=null)return node(bytes);if(!name.startsWith("java/"))return null;
  try(var in=ClassLoader.getSystemResourceAsStream(name+".class")){return in==null?null:node(in.readAllBytes());}catch(Exception absent){return null;}
 };}
 static NativeMergeShapeRepair repair(Map<String,byte[]> current,NativeGameReferences refs){return new NativeMergeShapeRepair(current(current),refs::get);}
 static MethodNode method(ClassNode n,String name){return n.methods.stream().filter(m->m.name.equals(name)).findFirst().orElse(null);}
 static void delegate(ClassNode n,String iface,String name,String desc){
  MethodNode m=new MethodNode(Opcodes.ACC_PUBLIC,name,desc,null,null);m.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));int slot=1;
  for(Type arg:Type.getArgumentTypes(desc)){m.instructions.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD),slot));slot+=arg.getSize();}
  m.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,iface,name,desc,true));m.instructions.add(new InsnNode(Type.getReturnType(desc).getOpcode(Opcodes.IRETURN)));m.maxLocals=slot;m.maxStack=slot;n.methods.add(m);
 }
}
