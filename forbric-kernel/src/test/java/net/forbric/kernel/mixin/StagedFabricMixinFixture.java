package net.forbric.kernel.mixin;
import java.io.*;
import java.nio.file.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;
/** Reads unmodified upstream modules; no test-written stand-in for their injection contracts. */
final class StagedFabricMixinFixture {
 static ClassNode mixin(String module,String name)throws Exception{
  Path api=Path.of("run/client-merged-pack/mods/fabric-api-0.155.2+26.2.jar");assumeTrue(Files.isRegularFile(api),"actual Fabric API fixture required");
  try(ZipFile z=new ZipFile(api.toFile())){
   ZipEntry e=z.stream().filter(x->x.getName().startsWith("META-INF/jars/"+module+"-")).findFirst().orElseThrow();
   try(ZipInputStream inner=new ZipInputStream(z.getInputStream(e))){for(ZipEntry entry;(entry=inner.getNextEntry())!=null;)if(entry.getName().equals(name+".class"))return MixinFit.parse(inner.readAllBytes());}
  }
  throw new AssertionError("actual mixin not found: "+name);
 }
 static ClassNode living(boolean vanilla)throws Exception{
  return game("net/minecraft/world/entity/LivingEntity",vanilla);
 }
 static ClassNode game(String name,boolean vanilla)throws Exception{
  Path p=vanilla?Path.of(System.getProperty("user.home"),"Library/Application Support/minecraft/versions/26.2/26.2.jar"):
    Path.of(System.getenv().getOrDefault("FORBRIC_OLD","../forbric-loader"),"run/merged-base/patched-mc-merged-26.2.jar");
  assumeTrue(Files.isRegularFile(p),"actual game required");
  try(ZipFile z=new ZipFile(p.toFile())){return MixinFit.parse(z.getInputStream(z.getEntry(name+".class")).readAllBytes());}
 }
 static MethodNode method(ClassNode c,String name){return c.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();}
 static AnnotationNode at(ClassNode c,String name){return MixinFit.atNodes(MixinFit.injectorOf(method(c,name))).getFirst();}
 static byte[] bytes(ClassNode c){ClassWriter w=new ClassWriter(0);c.accept(w);return w.toByteArray();}
}
