package net.forbric.kernel.transform;
import static org.junit.jupiter.api.Assertions.*;
import static net.forbric.kernel.transform.NativeMergeFixtures.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Opcodes;
class MergedBaseShadowingStubsTest {
 @TempDir Path work;private static final String DESC="(JD)Ljava/lang/String;";
 private Map<String,byte[]> source(boolean intentional)throws Exception{return InjectorExecution.compile(work,Map.of(
  "unfamiliar.Trait","package unfamiliar; public interface Trait { default String obscure(long a,double b){return \"default\";} }",
  "unfamiliar.Base","package unfamiliar; public class Base { public String obscure(long a,double b){return \"real:\"+a+\":\"+b;} }",
  "unfamiliar.Kid","package unfamiliar; public class Kid extends Base implements Trait {"+(intentional?"public String obscure(long a,double b){return Trait.super.obscure(a,b);}":"")+"}"));}
 @Test void anUnknownMergeDelegateReachesTheRealSuperclassWithWideArguments()throws Exception{
  var nativeClasses=source(false);var current=new HashMap<>(nativeClasses);var kid=node(current.get("unfamiliar/Kid"));delegate(kid,"unfamiliar/Trait","obscure",DESC);
  assertTrue(repair(current,references(nativeClasses,nativeClasses)).dropSuperclassStubs(kid));assertNull(method(kid,"obscure"));current.put(kid.name,bytes(kid));
  Class<?> type=InjectorExecution.load(current).loadClass("unfamiliar.Kid");assertEquals("real:7:2.5",type.getMethod("obscure",long.class,double.class).invoke(type.getConstructor().newInstance(),7L,2.5));
 }
 @Test void anIntentionalNativeDelegateOverARealSuperclassIsKept()throws Exception{
  var source=source(true);var kid=node(source.get("unfamiliar/Kid"));var repair=repair(source,references(source,source));assertFalse(repair.dropSuperclassStubs(kid));assertFalse(repair.dropDefaultStubs(kid));
  Class<?> type=InjectorExecution.load(source).loadClass("unfamiliar.Kid");assertEquals("default",type.getMethod("obscure",long.class,double.class).invoke(type.getConstructor().newInstance(),1L,1d));
 }
 @Test void defaultSpecificityIsDiscoveredWithoutInterfaceOrMethodNames()throws Exception{
  var source=InjectorExecution.compile(work,Map.of("elsewhere.Root","package elsewhere; public interface Root { default int unusual(){return 1;} }","elsewhere.Refined","package elsewhere; public interface Refined extends Root { default int unusual(){return 9;} }","elsewhere.Leaf","package elsewhere; public class Leaf implements Refined { }"));
  var leaf=node(source.get("elsewhere/Leaf"));delegate(leaf,"elsewhere/Refined","unusual","()I");assertTrue(repair(source,references(source,source)).dropDefaultStubs(leaf));var current=new HashMap<>(source);current.put(leaf.name,bytes(leaf));
  Class<?> type=InjectorExecution.load(current).loadClass("elsewhere.Leaf");assertEquals(9,type.getMethod("unusual").invoke(type.getConstructor().newInstance()));
 }
 @Test void unrelatedDefaultDiamondsKeepTheirDisambiguatingDelegate()throws Exception{
  var source=InjectorExecution.compile(work,Map.of("diamond.Left","package diamond; public interface Left { default int unusual(){return 3;} }","diamond.Right","package diamond; public interface Right { default int unusual(){return 4;} }","diamond.Leaf","package diamond; public class Leaf implements Left { }"));
  var leaf=node(source.get("diamond/Leaf"));leaf.interfaces.add("diamond/Right");delegate(leaf,"diamond/Left","unusual","()I");assertFalse(repair(source,references(source,source)).dropDefaultStubs(leaf));var current=new HashMap<>(source);current.put(leaf.name,bytes(leaf));
  Class<?> type=InjectorExecution.load(current).loadClass("diamond.Leaf");assertEquals(3,type.getMethod("unusual").invoke(type.getConstructor().newInstance()));
 }
 @Test void incompleteNativeOrCurrentProofAndSynchronizedDelegatesAreKept()throws Exception{
  var source=source(false);var kid=node(source.get("unfamiliar/Kid"));delegate(kid,"unfamiliar/Trait","obscure",DESC);assertFalse(repair(source,references(source,Map.of())).dropSuperclassStubs(kid));
  var incomplete=new HashMap<>(source);incomplete.remove("unfamiliar/Trait");assertFalse(repair(incomplete,references(source,source)).dropSuperclassStubs(kid));method(kid,"obscure").access|=Opcodes.ACC_SYNCHRONIZED;assertFalse(repair(source,references(source,source)).dropSuperclassStubs(kid));
 }
 @Test void inaccessibleOrAbstractSuperclassDeclarationsCannotImplementThePublicInterface()throws Exception{
  var source=source(false);var kid=node(source.get("unfamiliar/Kid"));delegate(kid,"unfamiliar/Trait","obscure",DESC);var parent=node(source.get("unfamiliar/Base"));method(parent,"obscure").access=Opcodes.ACC_PROTECTED;
  var current=new HashMap<>(source);current.put(parent.name,bytes(parent));assertFalse(repair(current,references(source,source)).dropSuperclassStubs(kid));
  method(parent,"obscure").access=Opcodes.ACC_PUBLIC|Opcodes.ACC_ABSTRACT;method(parent,"obscure").instructions.clear();current.put(parent.name,bytes(parent));assertFalse(repair(current,references(source,source)).dropSuperclassStubs(kid));
 }
}
