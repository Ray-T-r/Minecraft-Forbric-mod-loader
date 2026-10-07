package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;
import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.*;

/** Real Mixin execution of the actual API pair and the native-derived explicit-reference helpers.
 * Full native activation/final-token execution is separately covered by the physical fluid probe and scope tests. */
@ResourceLock("system-properties")
class MixinSharedPredicateSeamWeaveTest {
 @TempDir static Path work;
 private static final String HOST="fixture/shared/Actor",SOURCE="fixture/shared/mixin/SourceMixin",CONFIG="shared.mixins.json";
 private static final String RAW_HOST="net/minecraft/world/entity/Entity",RAW_SOURCE="net/fabricmc/fabric/mixin/content/registry/fluid/EntityMixin";
 private static Path onJar; private static WeaveHarness.Result on,off;
 @BeforeAll static void weave()throws Exception{
  Path src=Files.createDirectories(work.resolve("src"));List<Path>files=new ArrayList<>();
  files.add(write(src,"Token.java","package fixture.shared;public final class Token<T>{public final String name;public Token(String name){this.name=name;}public String toString(){return name;}}"));
  files.add(write(src,"Tags.java","package fixture.shared;public class Tags{public static final Token<Object> WATER=new Token<>(\"water\"),A=new Token<>(\"a\"),B=new Token<>(\"b\");}"));
  files.add(write(src,"Behavior.java","package fixture.shared;public interface Behavior{boolean canSwimInFluid(Token<?> tag,Actor actor);}"));
  files.add(write(src,"Registry.java","package fixture.shared;public class Registry{public static int listeners;public static RuntimeException failure;public static Behavior getFluidBehavior(Token<?> tag){return(t,a)->{listeners++;if(failure!=null)throw failure;return true;};}}"));
  files.add(write(src,"Controller.java","package fixture.shared;public class Controller{public Token<?> eye=Tags.A;public int queries;public boolean isEyeInFluid(Token<?> tag){queries++;return eye==tag;}}"));
  files.add(write(src,"State.java","package fixture.shared;public class State{public final Token<?> feet;public int asks;public State(Token<?> feet){this.feet=feet;}public boolean is(Token<?> tag){asks++;return tag==feet;}}"));
  files.add(write(src,"Ref.java","package fixture.shared;public class Ref<T>implements com.llamalad7.mixinextras.sugar.ref.LocalRef<T>{private T value;public T get(){return value;}public void set(T value){this.value=value;}}"));
  // recordGuard is the production implementation; no activation is opened by this helper-level scenario.
  Path repo=Path.of(System.getProperty("user.dir"));files.add(repo.resolve("src/runtime/java/net/forbric/kernel/runtime/KernelSharedPredicateScopes.java"));files.add(repo.resolve("src/runtime/java/net/forbric/kernel/runtime/KernelFluidPredicateSeams.java"));
  files.add(write(src,"FluidType.java","package net.neoforged.neoforge.fluids;public class FluidType{}"));
  files.add(write(src,"KernelFabricFluidBehaviors.java","package net.forbric.kernel.runtime;import net.neoforged.neoforge.fluids.FluidType;public class KernelFabricFluidBehaviors{public static boolean enabled(){return true;}public static boolean ownsNeoType(FluidType value){return false;}}"));
  files.add(write(src,"Actor.java", """
   package fixture.shared;import java.util.*;import java.lang.reflect.*;
   public class Actor{
    private final Controller fluidInteraction=new Controller();public boolean blocked;public int guards;
    public boolean isUnderWater(){return false;}public boolean isPassenger(){guards++;return blocked;}public boolean canStartSwimming(){return false;}
    public void updateSwimming(){boolean ignored=canStartSwimming()&&!isPassenger();}
    public String probe()throws Exception{
     Method u=null,g=null;for(Method method:Actor.class.getDeclaredMethods()){if(method.getName().contains("forbricsharedsource")&&method.getParameterCount()==1)u=method;if(method.getName().contains("forbricsharedsource")&&method.getParameterCount()==2&&method.getParameterTypes()[1]==State.class)g=method;}
     if(u==null||g==null)return "off; listeners="+Registry.listeners+"; guards="+guards;
     u.setAccessible(true);g.setAccessible(true);Field touched=Actor.class.getDeclaredField("wasTouchingCustomFluid");touched.setAccessible(true);touched.set(this,new LinkedHashSet<>(List.of(Tags.A,Tags.B)));
     Ref<Set<Token<?>>> shared=new Ref<>();State same=new State(Tags.A);boolean under=(boolean)u.invoke(this,shared);boolean standing=(boolean)g.invoke(this,shared,same);
     if(!under||!standing||!shared.get().equals(Set.of(Tags.A)))throw new AssertionError("same Ref source continuation lost");
     State cross=new State(Tags.A);fluidInteraction.eye=Tags.B;shared=new Ref<>();if(!(boolean)u.invoke(this,shared)||(boolean)g.invoke(this,shared,cross)||!shared.get().equals(Set.of(Tags.B)))throw new AssertionError("cross-tag continuation lost");
     blocked=true;shared=new Ref<>();if((boolean)u.invoke(this,shared))throw new AssertionError("passenger guard lost");
     return "same=true; cross=false; passenger=false; listeners="+Registry.listeners+"; guards="+guards+"; memberships="+(same.asks+cross.asks);
    }
   }
   """));
  Path config=write(src,CONFIG,"{\"required\":false,\"minVersion\":\"0.8\",\"package\":\"fixture.shared.mixin\",\"compatibilityLevel\":\"JAVA_21\",\"mixins\":[\"SourceMixin\"],\"injectors\":{\"defaultRequire\":0}}");
  Path raw=WeaveHarness.fixture(work,"shared-pair",files,Map.of(CONFIG,config));Map<String,byte[]>base=entries(raw);
  onJar=prepare(base,"on");on=run(onJar,"on");off=run(prepare(base,"off"),"off");
 }
 @Test void actualBodiesUseTheSameExplicitRefAcrossTheWovenHelpers()throws Exception{
  assertTrue(on.printed(WeaveHarnessMain.DONE+" same=true; cross=false; passenger=false; listeners=3; guards=3; memberships=4"),on.describe());WeaveHarness.assertWovenAndVerified(on,HOST,onJar);
  ClassNode defined=parse(on.defined(HOST));assertEquals(2,defined.methods.stream().filter(m->m.name.contains("checkIfUnderSwimmableFluid")||m.name.contains("checkIfStandingInSwimmableFluid")).filter(m->m.visibleAnnotations!=null&&m.visibleAnnotations.stream().anyMatch(a->a.desc.endsWith("/MixinMerged;"))).filter(m->m.name.contains("modifyExpressionValue$")||m.name.contains("wrapOperation$")).count());
 }
 @Test void stageOffKeepsTheMissingLegacyAnchorsWithoutCallingTheGuest(){assertTrue(off.printed(WeaveHarnessMain.DONE+" off; listeners=0; guards=0"),off.describe());}
 private static Path prepare(Map<String,byte[]>base,String setting)throws Exception{
  var input=MixinSharedPredicateSeamTest.inputs();ClassNode guest=input.guest();
  System.setProperty(MixinSharedPredicateSeam.PROPERTY,setting);try{assertEquals(setting.equals("off")?0:2,MixinSharedPredicateSeam.adapt(guest,input.classes(),input.source(),input.carrier()));}finally{System.clearProperty(MixinSharedPredicateSeam.PROPERTY);}
  // Isolate the two source helpers: host/query scope and its final certificate are covered in whole-program tests.
  guest.methods.removeIf(m->!m.name.equals("<init>")&&!m.name.equals("checkIfUnderSwimmableFluid")&&!m.name.equals("checkIfStandingInSwimmableFluid")&&!(m.name.contains("forbricsharedsource")&&m.visibleAnnotations!=null&&m.visibleAnnotations.stream().anyMatch(a->a.desc.endsWith("/Unique;"))&&!m.name.endsWith("$typed")));
  guest.fields.removeIf(f->!f.name.equals("wasTouchingCustomFluid")&&!f.name.equals("fluidInteraction"));guest.interfaces.clear();
  Map<String,String>owners=new HashMap<>();owners.put(RAW_SOURCE,SOURCE);owners.put(RAW_HOST,HOST);owners.put(RAW_HOST+"FluidInteraction","fixture/shared/Controller");owners.put("net/minecraft/world/level/material/FluidState","fixture/shared/State");owners.put("net/minecraft/tags/TagKey","fixture/shared/Token");owners.put("net/minecraft/tags/FluidTags","fixture/shared/Tags");owners.put("net/fabricmc/fabric/impl/content/registry/fluid/EntityFluidInteractionRegistryImpl","fixture/shared/Registry");owners.put("net/fabricmc/fabric/api/registry/fluid/FluidBehavior","fixture/shared/Behavior");
  ClassNode renamed=new ClassNode();guest.accept(new ClassRemapper(renamed,new Remapper(){@Override public String map(String owner){return owners.getOrDefault(owner,owner);}}));renamed.version=Opcodes.V21;
  for(MethodNode method:renamed.methods)for(var annotations:Arrays.asList(method.visibleAnnotations,method.invisibleAnnotations))if(annotations!=null)for(AnnotationNode a:annotations)remapStrings(a,owners);
  ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);renamed.accept(writer);Map<String,byte[]>bytes=new HashMap<>(base);bytes.put(SOURCE+".class",writer.toByteArray());Path jar=work.resolve("prepared-"+setting+".jar");try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(jar))){for(var entry:bytes.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}}return jar;
 }
 private static void remapStrings(AnnotationNode a,Map<String,String>owners){if(a.values==null)return;for(int i=1;i<a.values.size();i+=2)a.values.set(i,remapValue(a.values.get(i),owners));}
 private static Object remapValue(Object value,Map<String,String>owners){if(value instanceof String s){for(var e:owners.entrySet())s=s.replace("L"+e.getKey()+";","L"+e.getValue()+";");return s;}if(value instanceof AnnotationNode nested){remapStrings(nested,owners);return nested;}if(value instanceof List<?>list){List<Object>copy=new ArrayList<>();for(Object entry:list)copy.add(remapValue(entry,owners));return copy;}return value;}
 private static WeaveHarness.Result run(Path jar,String setting)throws Exception{return WeaveHarness.run(work,setting,jar,CONFIG,"renamed-actual-shared-pair",Ecosystem.FABRIC,EnvType.SERVER,"fixture.shared.Actor","probe",Map.of(MixinSharedPredicateSeam.PROPERTY,setting));}
 private static Map<String,byte[]>entries(Path jar)throws Exception{Map<String,byte[]>map=new HashMap<>();try(ZipFile zip=new ZipFile(jar.toFile())){for(var e:Collections.list(zip.entries()))map.put(e.getName(),zip.getInputStream(e).readAllBytes());}return map;}
 private static ClassNode parse(byte[]bytes){ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,ClassReader.EXPAND_FRAMES);return node;}
 private static Path write(Path dir,String name,String code)throws Exception{Files.createDirectories(dir);Path file=dir.resolve(name);Files.writeString(file,code);return file;}
}
