package net.forbric.kernel.transform;
import static org.junit.jupiter.api.Assertions.*;
import static net.forbric.kernel.transform.NativeMergeFixtures.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
class MergedBaseLostSwitchMapTest {
 @TempDir Path work;
 private record Fixture(Map<String,byte[]> originals,Map<String,byte[]> current,String holder,String field){}
 private Fixture fixture(boolean reorder)throws Exception{
  var source=InjectorExecution.compile(work,Map.of("unknown.Mode","package unknown; public enum Mode { FIRST, SECOND, THIRD }","unknown.Picker","package unknown; public class Picker { public static int choose(Mode mode){switch(mode){case SECOND:return 17;case FIRST:return 31;default:return 47;}} }"));
  var map=reads(node(source.get("unknown/Picker"))).getFirst();String holder=map.owner,old=map.name,name="unfamiliarCompilerTable";
  for(var entry:new ArrayList<>(source.entrySet())){
   var cls=node(entry.getValue());for(var f:cls.fields)if(cls.name.equals(holder)&&f.name.equals(old))f.name=name;
   for(var m:cls.methods)for(var i:m.instructions)if(i instanceof FieldInsnNode f&&f.owner.equals(holder)&&f.name.equals(old))f.name=name;
   source.put(entry.getKey(),bytes(cls));
  }
  var current=new HashMap<>(source);var replacement=new ClassNode();replacement.version=Opcodes.V21;replacement.access=Opcodes.ACC_PUBLIC;replacement.name=holder;replacement.superName="java/lang/Object";current.put(holder,bytes(replacement));
  if(reorder)current.put("unknown/Mode",InjectorExecution.compile(work,Map.of("unknown.Mode","package unknown; public enum Mode { THIRD, FIRST, SECOND }")).get("unknown/Mode"));
  return new Fixture(source,current,holder,name);
 }
 private boolean apply(Fixture f,ClassNode user)throws Exception{return repair(f.current,references(f.originals,f.originals)).inlineLostSwitchMaps(user);}
 @Test void nativeCaseAssignmentsSurviveUnknownNamesAndChangedEnumOrdinals()throws Exception{
  Fixture f=fixture(true);var user=node(f.current.get("unknown/Picker"));assertTrue(apply(f,user));assertTrue(reads(user).isEmpty());f.current.put(user.name,bytes(user));
  ClassLoader game=InjectorExecution.load(f.current);Class<?> enumeration=game.loadClass("unknown.Mode"),picker=game.loadClass("unknown.Picker");var choose=picker.getMethod("choose",enumeration);
  assertEquals(31,choose.invoke(null,enumeration.getField("FIRST").get(null)));assertEquals(17,choose.invoke(null,enumeration.getField("SECOND").get(null)));assertEquals(47,choose.invoke(null,enumeration.getField("THIRD").get(null)));
  assertInstanceOf(NullPointerException.class,assertThrows(InvocationTargetException.class,()->choose.invoke(null,new Object[]{null})).getCause());assertFalse(apply(f,user));
 }
 @Test void existingFieldsAndCasesMissingFromTheNativeMapAreKept()throws Exception{
  Fixture f=fixture(false);var user=node(f.current.get("unknown/Picker"));assertFalse(repair(f.originals,references(f.originals,f.originals)).inlineLostSwitchMaps(user));
  for(var m:user.methods)for(var i:m.instructions)if(i instanceof LookupSwitchInsnNode lookup)lookup.keys.set(0,1000);else if(i instanceof TableSwitchInsnNode table){table.min+=1000;table.max+=1000;}
  assertFalse(apply(f,user));assertFalse(reads(user).isEmpty());
 }
 @Test void nativeDisagreementAndInitializerSideEffectsAreNotGuessedAway()throws Exception{
  Fixture f=fixture(false);var user=node(f.current.get("unknown/Picker"));var different=new HashMap<>(f.originals);var holder=node(different.get(f.holder));
  for(var i:method(holder,"<clinit>").instructions)if(i.getOpcode()==Opcodes.ICONST_1){method(holder,"<clinit>").instructions.set(i,new InsnNode(Opcodes.ICONST_3));break;}
  different.put(holder.name,bytes(holder));assertFalse(repair(f.current,references(f.originals,different)).inlineLostSwitchMaps(user));
  holder=node(f.originals.get(f.holder));var init=method(holder,"<clinit>");init.instructions.insert(new InsnNode(Opcodes.POP2));init.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC,"java/lang/System","nanoTime","()J",false));different.put(holder.name,bytes(holder));
  assertFalse(repair(f.current,references(different,different)).inlineLostSwitchMaps(user));
 }
 @Test void unavailableNativeProvenanceCannotAuthorizeAnArrayRewrite()throws Exception{
  Fixture f=fixture(false);var user=node(f.current.get("unknown/Picker"));assertFalse(repair(f.current,references(Map.of(),f.originals)).inlineLostSwitchMaps(user));
  assertFalse(NativeMergeShapeRepair.production(user,path->f.current.get(path.replace(".class",""))).inlineLostSwitchMaps(user));
 }
 private static List<FieldInsnNode> reads(ClassNode n){List<FieldInsnNode> fields=new ArrayList<>();for(var m:n.methods)for(var i:m.instructions)if(i instanceof FieldInsnNode f&&f.getOpcode()==Opcodes.GETSTATIC&&f.desc.equals("[I"))fields.add(f);return fields;}
}
