/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;import java.util.*;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.tree.ClassNode;
import net.forbric.api.Ecosystem;import net.forbric.kernel.transform.InjectorExecution;
/** Actual receiver, object identity and native input evidence decide an entirely unknown inherited operation path. */
class OperationCallGraphUnknownTest {
 @TempDir Path work;
 static final String ROOT="solitary/Root",PARENT="solitary/Corridor",WIDGET="solitary/Widget",CONTEXT="solitary/Context";
 static final String MEMBER="L"+WIDGET+";show(L"+CONTEXT+";I)V";
 private record Fixture(ClassNode source,ClassNode target,Map<String,ClassNode> classes){}
 private Fixture fixture(String getter,String parentCalls,String helper,String currentCall)throws Exception{
  Map<String,String> common=new HashMap<>();
  common.put("solitary.Context","package solitary;public class Context{public final int seed;public Context(int seed){this.seed=seed;}}");
  common.put("solitary.Widget","package solitary;public class Widget{public void show(Context context,int amount){}}");
  common.put("solitary.Layers","package solitary;public class Layers{public static final Widget OVERLAY=new Widget();}");
  common.put("solitary.Corridor","package solitary;public class Corridor{public Widget top=new Widget(),other=new Widget();public Widget getTop(){return "+getter+";}public void dispatch(Context context,int seed,int amount){"+parentCalls+"}private static void execute(Widget widget,Context context,int seed,int amount){"+helper+"}}");
  Map<String,String> before=new HashMap<>(common),now=new HashMap<>(common);
  before.put("solitary.Root","package solitary;public class Root extends Corridor{public void run(int seed,int amount){Corridor owner=this;Context context=new Context(seed);owner.top.show(context,amount);}}");
  now.put("solitary.Root","package solitary;public class Root extends Corridor{public void run(int seed,int amount){Context context=new Context(seed);"+currentCall+"}}");
  Map<String,byte[]> source=InjectorExecution.compile(work,before),current=InjectorExecution.compile(work,now);Map<String,ClassNode> classes=new HashMap<>();current.forEach((name,bytes)->classes.put(name,MixinFit.parse(bytes)));
  return new Fixture(MixinFit.parse(source.get(ROOT)),classes.get(ROOT),classes);
 }
 private Fixture normal()throws Exception{return fixture("top","execute(Layers.OVERLAY,context,seed,amount);execute(getTop(),context,seed,amount);","widget.show(context,amount);","dispatch(context,seed,amount);");}
 private OperationCallGraph.Plan derive(Fixture fixture){return OperationCallGraph.derive(fixture.source,fixture.target,"run",MEMBER,fixture.classes::get,(family,owner)->null);}
 @Test void unknownInheritedGatewayDerivesTheTopOrdinalFromItsActualReceiverAndFreshContext()throws Exception{
  var fixture=normal();var plan=derive(fixture);assertNotNull(plan);assertEquals(PARENT,plan.firstOwner());assertEquals(PARENT,plan.helper());assertEquals(1,plan.forwardOrdinal());assertEquals(MEMBER,plan.member());assertEquals(List.of(1),plan.gatewayInputs());assertEquals(List.of(1),plan.helperInputs());assertEquals(1,plan.getters().size());
 }
 @Test void anotherGetterFieldAndAChangedObjectContextAreRefused()throws Exception{
  assertNull(derive(fixture("other","execute(Layers.OVERLAY,context,seed,amount);execute(getTop(),context,seed,amount);","widget.show(context,amount);","dispatch(context,seed,amount);")));
  assertNull(derive(fixture("top","execute(Layers.OVERLAY,context,seed,amount);execute(getTop(),context,seed,amount);","widget.show(new Context(seed),amount);","dispatch(context,seed,amount);")),"a second context allocation is another object even with the same constructor arguments");
 }
 @Test void duplicateTopInvocationMissingAnchorAndIncompleteClassesCannotChooseAPoint()throws Exception{
  assertNull(derive(fixture("top","execute(getTop(),context,seed,amount);execute(getTop(),context,seed,amount);","widget.show(context,amount);","dispatch(context,seed,amount);")));
  var fixture=normal();assertNull(OperationCallGraph.derive(fixture.source,fixture.target,"run","L"+WIDGET+";missing(L"+CONTEXT+";I)V",fixture.classes::get));fixture.classes.remove(PARENT);assertNull(derive(fixture));
 }
 @Test void aHelperLoopOrRecursiveInvocationCannotMoveASingleSourceOperation()throws Exception{
  for(String body:List.of("for(int n=0;n<2;n++)widget.show(context,amount);","execute(widget,context,seed,amount);widget.show(context,amount);"))
   assertNull(derive(fixture("top","execute(Layers.OVERLAY,context,seed,amount);execute(getTop(),context,seed,amount);",body,"dispatch(context,seed,amount);")),body);
 }
 @Test void changedPrimitivesRequireActualIndexedNativeGatewayInputEvidence()throws Exception{
  var fixture=fixture("top","execute(Layers.OVERLAY,context,seed,amount);execute(getTop(),context,seed,amount);","widget.show(context,amount);","dispatch(context,seed,amount*2);");assertNull(derive(fixture));
  byte[] target=StagedFabricMixinFixture.bytes(fixture.target);ClassNode nativeTarget=NativeCallTestEvidence.verified(Ecosystem.FORGE,ROOT,target);
  var proof=OperationCallGraph.derive(fixture.source,fixture.target,"run",MEMBER,fixture.classes::get,(family,owner)->family==Ecosystem.FORGE&&owner.equals(ROOT)?nativeTarget:null);assertNotNull(proof);assertEquals(1,proof.forwardOrdinal());
 }
}
