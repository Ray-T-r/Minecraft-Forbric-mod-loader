package net.forbric.kernel.mixin;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.transform.InjectorExecution;
class NativeCallsDerivationTest {
 @TempDir Path work;
 @AfterEach void reset(){MixinRetarget.reset();MergedBaseCalleeSwaps.reset();MixinStubRebind.forget();}
 private static Function<String,byte[]> resource(Map<String,byte[]> classes){return path->classes.get(path.substring(0,path.length()-6));}
 private static BiFunction<Ecosystem,String,ClassNode> references(Map<String,byte[]> source)throws Exception{
  Map<String,byte[]> resources=new HashMap<>();
  for(var family:Ecosystem.values()){
   String prefix="META-INF/forbric/native-reference/"+family+"/";StringBuilder index=new StringBuilder("# forbric-native-reference-v1\n");
   for(var e:source.entrySet()){index.append(e.getKey()).append('\t').append(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(e.getValue()))).append('\n');resources.put(prefix+e.getKey()+".class.bin",e.getValue());}
   resources.put(prefix+"index.tsv",index.toString().getBytes(StandardCharsets.UTF_8));
  }NativeGameReferences reader=new NativeGameReferences(resources::get);return reader::get;
 }
 @Test void anUnknownPrivateReplacementProjectsWideProviderArgumentsAndTheShimExecutes()throws Exception{
  String settings="package sample; public class Settings { private final long amount; public Settings(long v){amount=v;} public long readAmount(){return amount;} }";
  var source=InjectorExecution.compile(work,Map.of("sample.Settings",settings,"sample.User","package sample; public class User { public void run(Settings s){gone(s.readAmount());} private void gone(long value){} }"));
  var current=InjectorExecution.compile(work,Map.of("sample.Settings",settings,"sample.User","package sample; public class User { public void run(Settings s){different(s);} private void different(Settings s){} }","sample.TinyMixin","""
   package sample;
   import org.spongepowered.asm.mixin.Mixin;
   import org.spongepowered.asm.mixin.injection.*;
   import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
   @Mixin(User.class) public class TinyMixin {
    public static long observed;
    @Inject(method="gone(J)V",at=@At("HEAD")) private void intercept(long value,CallbackInfo callback){observed=value;}
   }
   """));
  MixinStubRebind.noteEcosystem("sample/TinyMixin",Ecosystem.FABRIC);
  var parsed=MixinFit.parse(current.get("sample/TinyMixin"));var plan=MixinRetarget.plan(parsed,resource(current),references(source));
  assertEquals(List.of(MixinRetarget.Element.PROJECT),plan.rewrites().stream().map(MixinRetarget.Rewrite::element).toList(),plan.describe());
  current.put("sample/TinyMixin",MixinRetarget.rewritten(current.get("sample/TinyMixin"),plan));ClassLoader game=InjectorExecution.load(current);
  Class<?> config=game.loadClass("sample.Settings"),mixin=game.loadClass("sample.TinyMixin");var shim=mixin.getDeclaredMethod("intercept",config,org.spongepowered.asm.mixin.injection.callback.CallbackInfo.class);shim.setAccessible(true);
  shim.invoke(mixin.getConstructor().newInstance(),config.getConstructor(long.class).newInstance(89L),null);assertEquals(89L,mixin.getField("observed").get(null));
 }
 @Test void unknownCalleesFollowOnlyACompleteNativeDefaultEquivalentHandler()throws Exception{
  String state="package odd; public interface State { int number(); default boolean old(){return number()==7;} default boolean newer(){return number()==7;} }";
  var source=InjectorExecution.compile(work,Map.of("odd.State",state,"odd.User","package odd; public class User { public boolean run(State s){return s.old();} }"));
  var current=InjectorExecution.compile(work,Map.of("odd.State",state,"odd.User","package odd; public class User { public boolean run(State s){return s.newer();} }","odd.PredicateMixin","""
   package odd; import org.spongepowered.asm.mixin.Mixin; import org.spongepowered.asm.mixin.injection.*;
   @Mixin(User.class) public class PredicateMixin { @Redirect(method="run",at=@At(value="INVOKE",target="Lodd/State;old()Z")) private boolean test(State s){return s.number()==7;} }
   """));
  MixinStubRebind.noteEcosystem("odd/PredicateMixin",Ecosystem.FABRIC);var plan=MixinRetarget.plan(MixinFit.parse(current.get("odd/PredicateMixin")),resource(current),references(source));
  assertEquals(1,plan.rewrites().size(),plan.describe());assertEquals("Lodd/State;newer()Z",plan.rewrites().getFirst().to());
  var changed=MixinFit.parse(current.get("odd/PredicateMixin"));MethodNode handler=changed.methods.stream().filter(m->m.name.equals("test")).findFirst().orElseThrow();
  for(var instruction:handler.instructions)if(instruction instanceof JumpInsnNode branch){branch.setOpcode(branch.getOpcode()==Opcodes.IF_ICMPNE?Opcodes.IF_ICMPEQ:Opcodes.IF_ICMPNE);break;}
  assertTrue(MixinRetarget.plan(changed,resource(current),references(source)).isEmpty(),"another predicate is not equivalent to the native default");
 }
 @Test void aPreviouslyUnknownSubstitutionComesFromTheActualSingleChangedCall()throws Exception{
  String helper="package convert; public class Helper { public static Object older(String s){return s;} public static Object newer(String s){return s;} }";
  var source=InjectorExecution.compile(work,Map.of("convert.Helper",helper,"convert.User","package convert; public class User { public static Object run(String s){return Helper.older(s);} }"));
  var current=InjectorExecution.compile(work,Map.of("convert.Helper",helper,"convert.User","package convert; public class User { public static Object run(String s){return Helper.newer(s);} }"));
  Function<String,ClassNode> nodes=name->{byte[] bytes=current.get(name);return bytes==null?null:MixinFit.parse(bytes);};
  try(var scope=MergedBaseCalleeSwaps.using(nodes,references(source))){var derived=MergedBaseCalleeSwaps.substitution("convert/User","run(Ljava/lang/String;)Ljava/lang/Object;","Lconvert/Helper;older(Ljava/lang/String;)Ljava/lang/Object;",Ecosystem.FABRIC);assertNotNull(derived);assertEquals("Lconvert/Helper;newer(Ljava/lang/String;)Ljava/lang/Object;",derived.replacement());}
 }
 @Test void anUnknownConditionalRedirectPreservesSourceParameterMeaningAndForwardsTheCurrentOperation()throws Exception{
  String value="package rearranged; public class Value { public final int id; public Value(int n){id=n;} }";
  String operations="package rearranged; public class Operations { public static String seen; public static boolean older(Value a,Value b){seen=\"old\";return true;} public static boolean newer(Value b,Value a){seen=b.id+\":\"+a.id;return true;} }";
  var source=InjectorExecution.compile(work,Map.of("rearranged.Value",value,"rearranged.Operations",operations,"rearranged.User","package rearranged; public class User { public boolean act(Value a,Value b){return Operations.older(a,b);} }"));
  var current=InjectorExecution.compile(work,Map.of("rearranged.Value",value,"rearranged.Operations",operations,"rearranged.User","package rearranged; public class User { public boolean act(Value a,Value b){return Operations.newer(b,a);} }","rearranged.GuardMixin","""
   package rearranged; import org.spongepowered.asm.mixin.Mixin; import org.spongepowered.asm.mixin.injection.*;
   @Mixin(User.class) public class GuardMixin {
    @Redirect(method="act",at=@At(value="INVOKE",target="Lrearranged/Operations;older(Lrearranged/Value;Lrearranged/Value;)Z"))
    private boolean guarded(Value a,Value b){if(a.id==1)return Operations.older(a,b);return false;}
   }
   """));
  ClassNode mixin=new ClassNode();new ClassReader(current.get("rearranged/GuardMixin")).accept(mixin,ClassReader.EXPAND_FRAMES);MixinStubRebind.noteEcosystem(mixin.name,Ecosystem.FABRIC);
  assertEquals(1,ReplacedCallRedirects.adapt(mixin,name->{byte[] bytes=current.get(name);return bytes==null?null:MixinFit.parse(bytes);},references(source)));
  ClassWriter writer=new ClassWriter(0);mixin.accept(writer);current.put(mixin.name,writer.toByteArray());ClassLoader game=InjectorExecution.load(current);
  Class<?> item=game.loadClass("rearranged.Value"),guard=game.loadClass("rearranged.GuardMixin");Object a=item.getConstructor(int.class).newInstance(1),b=item.getConstructor(int.class).newInstance(2);
  var handler=guard.getDeclaredMethod("guarded",item,item);handler.setAccessible(true);
  assertEquals(true,handler.invoke(guard.getConstructor().newInstance(),b,a));assertEquals("2:1",game.loadClass("rearranged.Operations").getField("seen").get(null));
  assertEquals(false,handler.invoke(guard.getConstructor().newInstance(),a,b));
 }

 @Test void aNewObserverOrAChangedGuardBeforeTheSameDataOperationCannotAuthorizeARedirect()throws Exception{
  String operations="package closed; public class Operations { public static boolean old(Object a,Object b){return true;} public static boolean next(Object b,Object a){return true;} public static void observe(){} }";
  var source=InjectorExecution.compile(work,Map.of("closed.Operations",operations,"closed.Actor","package closed; public class Actor { public boolean act(Object a,Object b){return Operations.old(a,b);} }"));
  String mixin="package closed;import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.*;@Mixin(Actor.class)public class Guest{@Redirect(method=\"act\",at=@At(value=\"INVOKE\",target=\"Lclosed/Operations;old(Ljava/lang/Object;Ljava/lang/Object;)Z\"))private boolean replace(Object a,Object b){return Operations.old(a,b);}}";
  for(String body:List.of("Operations.observe();return Operations.next(b,a);","if(a==null)return false;return Operations.next(b,a);")){
   var current=InjectorExecution.compile(work,Map.of("closed.Operations",operations,"closed.Actor","package closed;public class Actor{public boolean act(Object a,Object b){"+body+"}}","closed.Guest",mixin));
   ClassNode guest=MixinFit.parse(current.get("closed/Guest"));MixinStubRebind.noteEcosystem(guest.name,Ecosystem.FABRIC);
   assertEquals(0,ReplacedCallRedirects.adapt(guest,name->{byte[] bytes=current.get(name);return bytes==null?null:MixinFit.parse(bytes);},references(source)),body);
  }
 }

}
