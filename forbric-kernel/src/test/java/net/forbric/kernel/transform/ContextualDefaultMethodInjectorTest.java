package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

@ExecutesInjector(ContextualDefaultMethodInjector.class)
class ContextualDefaultMethodInjectorTest {
 public static class World { public int calls; public int value(){calls++;return 17;} }
 public static class Position { }
 public static class Extra { }
 public interface NativeDefaults {
  default int bits(){return 0;}
  default int bits(World world,Position position){return world.value();}
 }
 public interface GuestDefaults extends NativeDefaults {
  default int bits(World world,Position position,Extra ignored){return bits();}
  default int branched(boolean enabled){return enabled?7:9;}
 }
 public static class Ordinary implements GuestDefaults { }
 public static class GuestOverride implements GuestDefaults {
  public int calls;
  @Override public int bits(World world,Position position,Extra ignored){calls++;return 31;}
 }
 @Test void theRegisteredTransformerForwardsContextAndPreservesConcreteVirtualOverrides() throws Exception {
  byte[] original=bytes(GuestDefaults.class);
  var injector=new ContextualDefaultMethodInjector(ContextualDefaultMethodInjectorTest::resource);
  byte[] changed=injector.transform(GuestDefaults.class.getName(),original,null);
  assertFalse(Arrays.equals(original,changed));
  assertSame(changed,injector.transform(GuestDefaults.class.getName(),changed,null));
  Map<String,byte[]> classes=Map.of(GuestDefaults.class.getName(),changed,Ordinary.class.getName(),bytes(Ordinary.class),GuestOverride.class.getName(),bytes(GuestOverride.class));
  ClassLoader loader=new ClassLoader(getClass().getClassLoader()){
   @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
    if(!classes.containsKey(name))return super.loadClass(name,resolve);
    synchronized(getClassLoadingLock(name)){Class<?> type=findLoadedClass(name);if(type==null){byte[] b=classes.get(name);type=defineClass(name,b,0,b.length);}if(resolve)resolveClass(type);return type;}
   }
  };
  World world=new World();Object model=loader.loadClass(Ordinary.class.getName()).getConstructor().newInstance();
  assertEquals(17,model.getClass().getMethod("bits",World.class,Position.class,Extra.class).invoke(model,world,new Position(),new Extra()));
  assertEquals(1,world.calls);
  assertEquals(7,model.getClass().getMethod("branched",boolean.class).invoke(model,true));
  assertEquals(9,model.getClass().getMethod("branched",boolean.class).invoke(model,false));
  Object override=loader.loadClass(GuestOverride.class.getName()).getConstructor().newInstance();
  assertEquals(31,override.getClass().getMethod("bits",World.class,Position.class,Extra.class).invoke(override,world,new Position(),new Extra()));
  assertEquals(1,override.getClass().getField("calls").getInt(override));assertEquals(1,world.calls);
 }
 @Test void anEffectfulDefaultAndMissingHierarchyAreNotRewritten() throws Exception {
  ClassNode node=new ClassNode();new ClassReader(bytes(GuestDefaults.class)).accept(node,0);
  MethodNode method=node.methods.stream().filter(m->m.name.equals("bits")).findFirst().orElseThrow();
  method.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC,"fixture/Effects","touch","()V",false));
  ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);byte[] effectful=writer.toByteArray();
  assertSame(effectful,new ContextualDefaultMethodInjector(ContextualDefaultMethodInjectorTest::resource).transform(node.name,effectful,null));
  byte[] original=bytes(GuestDefaults.class);assertSame(original,new ContextualDefaultMethodInjector(path->null).transform(node.name,original,null));
 }
 private static byte[] bytes(Class<?> type)throws Exception{return resource(type.getName().replace('.','/')+".class");}
 private static byte[] resource(String path){try(var in=ContextualDefaultMethodInjectorTest.class.getClassLoader().getResourceAsStream(path)){return in==null?null:in.readAllBytes();}catch(java.io.IOException failure){throw new IllegalStateException(failure);}}
}
