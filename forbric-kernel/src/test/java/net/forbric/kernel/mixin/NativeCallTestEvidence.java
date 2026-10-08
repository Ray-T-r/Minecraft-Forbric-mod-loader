package net.forbric.kernel.mixin;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import java.util.zip.ZipFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
/** Actual staged originals and public carrier helper bytes, each verified through the native hash-index reader. */
public final class NativeCallTestEvidence {
 private NativeCallTestEvidence(){}
 private static byte[] read(Path jar,String path){try(ZipFile zip=new ZipFile(jar.toFile())){var entry=zip.getEntry(path);return entry==null?null:zip.getInputStream(entry).readAllBytes();}catch(Exception absent){return null;}}
 public static BiFunction<Ecosystem,String,ClassNode> staged(){
  Path root=TestFixtures.stagedRoot(),merged=root.resolve("merged-base/patched-mc-merged-26.2.jar");NativeGameReferences refs=new NativeGameReferences(path->read(merged,path));
  Map<String,ClassNode> cache=new HashMap<>();
  return (family,owner)->{
   if(family==null||owner==null)return null;
   String key=family+":"+owner;if(cache.containsKey(key))return cache.get(key);ClassNode result=refs.get(family,owner);
   if(result==null){
    byte[] bytes=null;
    if(owner.startsWith("java/")){try(var stream=ClassLoader.getSystemResourceAsStream(owner+".class")){bytes=stream==null?null:stream.readAllBytes();}catch(Exception absent){}}
    else {
     Path nativeJar=switch(family){case FABRIC->TestFixtures.vanillaJar();case FORGE->TestFixtures.forgeMergeInput();case NEOFORGE->root.resolve("neoforge-patched/patched-mc-neoforge-26.2.jar");};
     bytes=read(nativeJar,owner+".class");
     if(bytes==null&&family==Ecosystem.NEOFORGE)bytes=read(root.resolve("neoforge-runtime/neoforge-runtime.jar"),owner+".class");
     if(bytes==null&&family==Ecosystem.FORGE)bytes=read(root.resolve("forge-runtime/forge-runtime.jar"),owner+".class");
    }
    if(bytes!=null)result=verified(family,owner,bytes);
   }
   cache.put(key,result);return result;
  };
 }
 private static ClassNode withDebug(byte[] bytes){ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,ClassReader.SKIP_FRAMES);return node;}
 public static ClassNode verified(Ecosystem family,String owner,byte[] bytes){try{
  String prefix="META-INF/forbric/native-reference/"+family+"/";String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  byte[] index=("# forbric-native-reference-v1\n"+owner+"\t"+digest+"\n").getBytes(StandardCharsets.UTF_8);
  NativeGameReferences refs=new NativeGameReferences(path->path.equals(prefix+"index.tsv")?index:path.equals(prefix+owner+".class.bin")?bytes:null);return refs.get(family,owner);
 }catch(Exception impossible){throw new AssertionError(impossible);}}
 public static MergedBaseCalleeSwaps.Scope scope(Function<String,byte[]> current){return MergedBaseCalleeSwaps.using(owner->{byte[] bytes=current.apply(owner+".class");return bytes==null?null:withDebug(bytes);},staged());}
 public static MixinFit.Result evaluate(byte[] mixin,Function<String,byte[]> current){try(var scope=scope(current)){return MixinFit.evaluate(mixin,current);}}
 public static MixinRetarget.Adoption adopt(byte[] bytes,MixinFit.Result fit,Function<String,byte[]> current,Function<byte[],MixinFit.Result> evaluator){try(var scope=scope(current)){return MixinRetarget.adopt(bytes,fit,current,evaluator);}}
 public static MixinRetarget.Plan plan(ClassNode mixin,Function<String,byte[]> current){return MixinRetarget.plan(mixin,current,staged());}
 public static MergedBaseCalleeSwaps.Substitution modelRow(){
  String owner="net/minecraft/client/resources/model/ModelManager";
  byte[] bytes=read(TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar"),owner+".class");
  if(bytes==null)throw new AssertionError("staged model fixture required");
  try(var scope=MergedBaseCalleeSwaps.using(name->name.equals(owner)?withDebug(bytes):null,staged())){
   var row=MergedBaseCalleeSwaps.substitution(owner,"lambda$loadBlockModels$2(Ljava/util/Map$Entry;)Lcom/mojang/datafixers/util/Pair;","Lnet/minecraft/client/resources/model/cuboid/CuboidModel;fromStream(Ljava/io/Reader;)Lnet/minecraft/client/resources/model/cuboid/CuboidModel;",Ecosystem.FABRIC);
   if(row==null)throw new AssertionError("actual native/current model substitution proof failed");return row;
  }
 }
 public static MergedBaseCalleeSwaps.Replaced structureRow(){
  Path root=TestFixtures.stagedRoot();String owner="net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate";byte[] bytes=read(root.resolve("merged-base/patched-mc-merged-26.2.jar"),owner+".class");
  if(bytes==null)throw new AssertionError("staged structure fixture required");
  try(var scope=MergedBaseCalleeSwaps.using(name->name.equals(owner)?MixinFit.parse(bytes):null,staged())){var row=MergedBaseCalleeSwaps.replaced(owner,"placeEntities",null,Ecosystem.FABRIC);if(row==null)throw new AssertionError("actual native/current structure replacement proof failed");return row;}
 }
}
