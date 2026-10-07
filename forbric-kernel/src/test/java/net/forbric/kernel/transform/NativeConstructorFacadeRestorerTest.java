package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;import java.util.*;import java.util.zip.*;import net.fabricmc.api.EnvType;import net.forbric.api.Ecosystem;import net.forbric.kernel.TestFixtures;import net.forbric.kernel.mixin.*;import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import org.objectweb.asm.*;import org.objectweb.asm.tree.*;

@ExecutesInjector(NativeConstructorFacadeRestorer.class)
class NativeConstructorFacadeRestorerTest {
 @TempDir Path work;
 @Test void anIndexedConstructorClosureRestoresItsExactParameterScopeAndExecutesOnce()throws Throwable{
  Map<String,String> shared=Map.of("fixture.Builder","package fixture;public class Builder{public static int constructors;private final String label;public Builder(String label){constructors++;this.label=label;}public Builder append(int value){return new Builder(label+value);}public String finish(){return label;}}");
  Map<String,String> nativeSources=new HashMap<>(shared);nativeSources.put("fixture.Facade","package fixture;public class Facade{public static String make(String label,int value){return new Builder(label).append(value).finish();}}");
  Map<String,String> currentSources=new HashMap<>(shared);currentSources.put("fixture.Facade","package fixture;public class Facade{public static String make(String label,int value){return piece(new Builder(label),value);}public static String piece(Builder builder,int value){return builder.append(value).finish();}}");
  Map<String,byte[]> nativeClasses=InjectorExecution.compile(work.resolve("native"),nativeSources),current=InjectorExecution.compile(work.resolve("current"),currentSources);
  byte[] reference=nativeClasses.get("fixture/Facade");var resources=resources(reference);var injector=new NativeConstructorFacadeRestorer(resources::get);
  byte[] input=current.get("fixture/Facade"),output=injector.transform("fixture.Facade",input,null);assertFalse(Arrays.equals(input,output));assertSame(output,injector.transform("fixture.Facade",output,null));current.put("fixture/Facade",output);
  ClassLoader loader=InjectorExecution.load(current);assertEquals("x7",InjectorExecution.invokeStatic(loader.loadClass("fixture.Facade"),"make","x",7));assertEquals(2,loader.loadClass("fixture.Builder").getField("constructors").getInt(null));assertEquals("",InjectorExecution.verify(output,loader));
  assertEquals(MixinInstructionFingerprint.hash(method(parse(reference),"make")),MixinInstructionFingerprint.hash(method(parse(output),"make")));
 }
 @Test void absentOriginalAlteredBodyAndUnverifiableArgumentMappingsRemainUntouched()throws Exception{
  Map<String,byte[]> classes=InjectorExecution.compile(work,Map.of("fixture.Builder","package fixture;public class Builder{public Builder(String s){}public String finish(int n){return \"v\"+n;}}","fixture.Facade","package fixture;public class Facade{public static String make(String s,int n){return piece(new Builder(s),n);}private static String piece(Builder b,int n){return b.finish(n);}}"));
  byte[] bytes=classes.get("fixture/Facade");assertSame(bytes,new NativeConstructorFacadeRestorer(path->null).transform("fixture.Facade",bytes,null));
  assertSame(bytes,new NativeConstructorFacadeRestorer(resources(bytes)::get).transform("fixture.Facade",bytes,null));
  ClassNode node=parse(bytes);MethodNode helper=method(node,"piece");helper.instructions.insert(new InsnNode(Opcodes.POP));helper.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC,"fixture/Effects","mutate","()I",false));
  assertEquals(0,NativeConstructorFacadeRestorer.restore(node,eco->parse(bytes)));
 }
 @Test void actualFuelFacadeBodyMatchesTheOriginalExactlyAndRetainsAllThreeContextArguments()throws Exception{
  Path base=Path.of(System.getProperty("forbric.predicateBase"));TestFixtures.require(TestFixtures.Fixture.STAGED,Files.isRegularFile(base),"native-indexed game required");
  try(ZipFile z=new ZipFile(base.toFile())){
   String owner="net/minecraft/world/level/block/entity/FuelValues";byte[] bytes=z.getInputStream(z.getEntry(owner+".class")).readAllBytes();var injector=new NativeConstructorFacadeRestorer(path->{try{var e=z.getEntry(path);return e==null?null:z.getInputStream(e).readAllBytes();}catch(Exception bad){throw new IllegalStateException(bad);}});
   byte[] result=injector.transform(owner.replace('/','.'),bytes,null);assertFalse(Arrays.equals(bytes,result));ClassNode node=parse(result);MethodNode wrapper=node.methods.stream().filter(m->Type.getArgumentTypes(m.desc).length==3&&m.name.equals("vanillaBurnTimes")).findFirst().orElseThrow();
   MethodNode original=method(parse(z.getInputStream(z.getEntry("META-INF/forbric/native-reference/FABRIC/"+owner+".class.bin")).readAllBytes()),wrapper.name,wrapper.desc);assertEquals(MixinInstructionFingerprint.hash(original),MixinInstructionFingerprint.hash(wrapper));
   byte[] shorted=new FabricFuelValuesInjector().transform(owner.replace('/','.'),result,null);MethodNode scoped=method(parse(shorted),wrapper.name,wrapper.desc);assertTrue(Arrays.stream(scoped.instructions.toArray()).anyMatch(i->i instanceof MethodInsnNode c&&c.name.equals("takePending")));
   MethodNode closure=node.methods.stream().filter(m->m.name.equals(wrapper.name)&&Type.getArgumentTypes(m.desc).length==2&&m.desc.contains("$Builder;")).findFirst().orElseThrow();assertFalse(Arrays.stream(closure.instructions.toArray()).anyMatch(i->i instanceof MethodInsnNode c&&c.name.equals("takePending")));
  }
 }
 private static Map<String,byte[]> resources(byte[] reference)throws Exception{return Map.of("META-INF/forbric/native-reference/FABRIC/index.tsv",("# forbric-native-reference-v1\nfixture/Facade\t"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(reference))+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8),"META-INF/forbric/native-reference/FABRIC/fixture/Facade.class.bin",reference);}
 private static ClassNode parse(byte[] bytes){ClassNode n=new ClassNode();new ClassReader(bytes).accept(n,0);return n;}
 private static MethodNode method(ClassNode n,String name){return n.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();}
 private static MethodNode method(ClassNode n,String name,String desc){return n.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElseThrow();}
}
