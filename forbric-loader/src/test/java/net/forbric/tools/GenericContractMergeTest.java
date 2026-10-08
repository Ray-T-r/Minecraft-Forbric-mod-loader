/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.tools;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public class GenericContractMergeTest {
    public static class Log {
        public static final List<String> effects=new ArrayList<>();
        public static void body(String input){effects.add("body:"+input);}
        public static void extra(String input){effects.add("extra:"+input);}
        public static void first(String input){effects.add("first:"+input);}
        public static void second(String input){effects.add("second:"+input);}
    }
    private static final String LOG=Type.getInternalName(Log.class);
    @Test void appendOnlyTailKeepsBaseDataPathAndBothEventsAndRunsOnce()throws Exception{
        ClassNode owner=node("unknown/game/ChangedBody","java/lang/Object");MethodNode vanilla=flow(false,null),base=flow(true,"second"),other=flow(false,"first");
        MethodNode result=TailHookComposition.merge(owner,vanilla,base,other,LOG,LOG,logGraph());assertNotNull(result);
        owner.methods.add(result);Class<?> type=define(Map.of(owner.name,bytes(owner)),owner.name);Log.effects.clear();type.getMethod("send",String.class).invoke(null,"payload");
        assertEquals(List.of("extra:payload","body:payload","first:payload","second:payload"),Log.effects);
    }
    @Test void changedNativeOperandCannotBecomeAnAppendOnlyTail(){
        MethodNode other=flow(false,"first");((VarInsnNode)ContractGraph.code(other).get(0)).var=1;
        assertNull(TailHookComposition.merge(node("unknown/game/ChangedBody","java/lang/Object"),flow(false,null),flow(true,"second"),other,LOG,LOG,logGraph()));
    }
    @Test void branchBypassingTailIsNotRestoredUnconditionally(){
        MethodNode vanilla=flow(false,null),other=flow(false,"first");LabelNode end=new LabelNode();other.instructions.insertBefore(other.instructions.getLast(),end);
        other.instructions.insert(new JumpInsnNode(Opcodes.GOTO,end));vanilla.instructions.insert(new JumpInsnNode(Opcodes.GOTO,new LabelNode()));
        assertNull(TailHookComposition.merge(node("unknown/game/ChangedBody","java/lang/Object"),vanilla,flow(true,"second"),other,LOG,LOG,logGraph()));
    }
    @Test void emptyArrayReverseContractPreservesOldEffectsAndRejectsNonemptyBeforeOldCall()throws Exception{
        ClassNode contract=node("unrelated/api/ConsumerContract","java/lang/Object");contract.access=Opcodes.ACC_PUBLIC|Opcodes.ACC_INTERFACE|Opcodes.ACC_ABSTRACT;
        MethodNode expanded=new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_ABSTRACT,"consume","(Ljava/lang/String;[Ljava/lang/Object;)V",null,null);contract.methods.add(expanded);
        MethodNode oldDefault=new MethodNode(Opcodes.ACC_PUBLIC,"consume","(Ljava/lang/String;)V",null,null);
        oldDefault.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));oldDefault.instructions.add(new VarInsnNode(Opcodes.ALOAD,1));oldDefault.instructions.add(new InsnNode(Opcodes.ICONST_0));oldDefault.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY,"java/lang/Object"));oldDefault.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,contract.name,expanded.name,expanded.desc,true));oldDefault.instructions.add(new InsnNode(Opcodes.RETURN));contract.methods.add(oldDefault);
        ClassNode consumer=node("unrelated/impl/ExistingConsumer","java/lang/Object");consumer.interfaces.add(contract.name);consumer.methods.add(constructor(consumer.name,"java/lang/Object"));
        MethodNode old=flow(false,null);old.name="consume";old.access=Opcodes.ACC_PUBLIC;((VarInsnNode)ContractGraph.code(old).get(0)).var=1;consumer.methods.add(old);
        ContractGraph graph=graph(contract,consumer);assertEquals(1,EmptyArrayContractBridge.repair(consumer,graph));
        Map<String,byte[]> definitions=new HashMap<>();definitions.put(contract.name,bytes(contract));definitions.put(consumer.name,bytes(consumer));Class<?> implementation=define(definitions,consumer.name);Object instance=implementation.getConstructor().newInstance();Method invoke=implementation.getMethod("consume",String.class,Object[].class);
        Log.effects.clear();invoke.invoke(instance,"ok",new Object[0]);assertEquals(List.of("body:ok"),Log.effects);
        InvocationTargetException failure=assertThrows(InvocationTargetException.class,()->invoke.invoke(instance,"drop",new Object[]{"condition"}));assertInstanceOf(UnsupportedOperationException.class,failure.getCause());assertTrue(failure.getCause().getMessage().contains("unrelated/impl/ExistingConsumer#consume"));assertEquals(List.of("body:ok"),Log.effects);
        assertThrows(InvocationTargetException.class,()->invoke.invoke(instance,"null",null));assertEquals(List.of("body:ok"),Log.effects);
    }
    @Test void opaqueExtraArrayProtocolHasNoReverseBridge(){
        ClassNode contract=node("unrelated/api/Other","java/lang/Object");contract.access=Opcodes.ACC_PUBLIC|Opcodes.ACC_INTERFACE|Opcodes.ACC_ABSTRACT;
        contract.methods.add(new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_ABSTRACT,"consume","(Ljava/lang/String;[Ljava/lang/Object;)V",null,null));
        ClassNode impl=node("unrelated/impl/Opaque","java/lang/Object");impl.interfaces.add(contract.name);MethodNode old=flow(false,null);old.name="consume";old.access=Opcodes.ACC_PUBLIC;impl.methods.add(old);
        assertEquals(0,EmptyArrayContractBridge.repair(impl,graph(contract,impl)));
    }
    @Test void inheritedDefaultCanSatisfyAnUnrelatedAbstractSlot(){
        ClassNode abstractContract=node("unrelated/Abstract","java/lang/Object"),defaults=node("unrelated/Defaults","java/lang/Object"),impl=node("unrelated/Implementation","java/lang/Object");
        abstractContract.access=defaults.access=Opcodes.ACC_PUBLIC|Opcodes.ACC_INTERFACE|Opcodes.ACC_ABSTRACT;abstractContract.methods.add(new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_ABSTRACT,"run","()V",null,null));
        MethodNode body=new MethodNode(Opcodes.ACC_PUBLIC,"run","()V",null,null);body.instructions.add(new InsnNode(Opcodes.RETURN));defaults.methods.add(body);impl.interfaces.addAll(List.of(abstractContract.name,defaults.name));assertNotNull(graph(abstractContract,defaults,impl).implementation(impl.name,"run","()V"));
    }
    @Test void nonnullProofUsesReachingDefinitionsInsteadOfEveryPastUseOfAReusedSlot(){
        ClassNode owner=node("unknown/ReusedLocals","java/lang/Object");MethodNode method=new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"get","()Ljava/lang/Object;",null,null);
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));method.instructions.add(new VarInsnNode(Opcodes.ASTORE,0));method.instructions.add(new TypeInsnNode(Opcodes.NEW,"java/lang/Object"));method.instructions.add(new InsnNode(Opcodes.DUP));method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false));method.instructions.add(new VarInsnNode(Opcodes.ASTORE,0));method.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));method.instructions.add(new InsnNode(Opcodes.ARETURN));owner.methods.add(method);
        assertTrue(NonNullReturnProof.method(graph(owner),owner.name,method));
    }
    @Test void aNullableBranchCannotCertifyAProviderThatRejectsNull(){
        ClassNode owner=node("unknown/Nullable","java/lang/Object");MethodNode method=new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"get","(Z)Ljava/lang/Object;",null,null);LabelNode nullable=new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD,0));method.instructions.add(new JumpInsnNode(Opcodes.IFEQ,nullable));method.instructions.add(new TypeInsnNode(Opcodes.NEW,"java/lang/Object"));method.instructions.add(new InsnNode(Opcodes.DUP));method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false));method.instructions.add(new InsnNode(Opcodes.ARETURN));method.instructions.add(nullable);method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));method.instructions.add(new InsnNode(Opcodes.ARETURN));owner.methods.add(method);
        assertFalse(NonNullReturnProof.method(graph(owner),owner.name,method));
    }
    @Test void immutableEquivalentBridgeKeepsBothFieldDeclarationsAndOnlyOneCommonConstructor()throws Exception{
        ClassNode common=node("unrelated/Common","java/lang/Object");common.fields.add(new FieldNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"constructions","I",null,null));MethodNode commonCtor=constructor(common.name,"java/lang/Object");commonCtor.instructions.insertBefore(commonCtor.instructions.getLast(),new FieldInsnNode(Opcodes.GETSTATIC,common.name,"constructions","I"));commonCtor.instructions.insertBefore(commonCtor.instructions.getLast(),new InsnNode(Opcodes.ICONST_1));commonCtor.instructions.insertBefore(commonCtor.instructions.getLast(),new InsnNode(Opcodes.IADD));commonCtor.instructions.insertBefore(commonCtor.instructions.getLast(),new FieldInsnNode(Opcodes.PUTSTATIC,common.name,"constructions","I"));common.methods.add(commonCtor);
        ClassNode a=immutable("unrelated/platformA/Value",common.name),b=immutable("unrelated/platformB/Value",common.name);assertTrue(EquivalentSuperclassBridge.equivalent(a,b));ClassNode bridged=EquivalentSuperclassBridge.bridge(a,b);
        Map<String,byte[]> definitions=Map.of(common.name,bytes(common),a.name,bytes(bridged),b.name,bytes(b));Class<?> type=define(definitions,a.name);Object payload=new Object();Object instance=type.getConstructor(Object.class).newInstance(payload);
        assertTrue(type.getSuperclass().isInstance(instance));assertSame(payload,type.getMethod("value").invoke(instance));assertEquals(1,type.getSuperclass().getSuperclass().getField("constructions").getInt(null));
        Field own=type.getDeclaredField("stored"),peer=type.getSuperclass().getDeclaredField("stored");own.setAccessible(true);peer.setAccessible(true);assertSame(payload,own.get(instance));assertSame(payload,peer.get(instance));
    }
    @Test void mutableOrBehaviorDifferentAncestorsAreNotEquivalent(){
        ClassNode a=immutable("unknown/A","java/lang/Object"),b=immutable("unknown/B","java/lang/Object");b.fields.get(0).access&=~Opcodes.ACC_FINAL;assertFalse(EquivalentSuperclassBridge.equivalent(a,b));
        b=immutable("unknown/B","java/lang/Object");b.methods.get(1).instructions.insert(new InsnNode(Opcodes.NOP));assertFalse(EquivalentSuperclassBridge.equivalent(a,b));
    }
    @Test void correlatedMapViewUsesFreshStorageAndPreservesNativeUnmodifiableSemantics()throws Exception{
        ClassNode contract=mapContract(),nativePeer=mapHolder("unrelated/NativeMap",contract.name,true),target=mapHolder("unrelated/ChangedMap",contract.name,false);
        ContractGraph graph=graph(contract,nativePeer,target);assertEquals(1,new MapContractRepair(graph,List.of(Map.of(nativePeer.name,bytes(nativePeer)))).repair(target));
        Class<?> implementation=define(Map.of(contract.name,bytes(contract),target.name,bytes(target)),target.name);Map<String,List<String>> old=Map.of("old",List.of("stale")),fresh=new HashMap<>();fresh.put("new",List.of("live"));Object object=implementation.getConstructor(Map.class,Map.class).newInstance(old,fresh);
        @SuppressWarnings("unchecked") Map<String,List<String>> view=(Map<String,List<String>>)implementation.getMethod("view").invoke(object);assertEquals(fresh,view);assertFalse(view.containsKey("old"));assertThrows(UnsupportedOperationException.class,()->view.put("bad",List.of()));fresh.put("late",List.of("added"));assertEquals(List.of("added"),view.get("late"));
    }
    @Test void twoCorrelatedMapCandidatesCannotChooseAFieldByItsName(){
        ClassNode contract=mapContract(),nativePeer=mapHolder("unrelated/NativeMap",contract.name,true),target=mapHolder("unrelated/AmbiguousMap",contract.name,false);
        MethodNode secondLookup=mapLookup(target.name,"old");secondLookup.name="otherLookup";MethodNode secondSize=mapSize(target.name,"old");secondSize.name="otherSize";target.methods.add(secondLookup);target.methods.add(secondSize);contract.methods.add(new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_ABSTRACT,secondLookup.name,secondLookup.desc,secondLookup.signature,null));contract.methods.add(new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_ABSTRACT,secondSize.name,secondSize.desc,null,null));
        assertEquals(0,new MapContractRepair(graph(contract,nativePeer,target),List.of(Map.of(nativePeer.name,bytes(nativePeer)))).repair(target));assertNull(ContractGraph.ownMethod(target,"view","()Ljava/util/Map;"));
    }
    private static ClassNode mapContract(){ClassNode contract=node("unrelated/MapContract","java/lang/Object");contract.access=Opcodes.ACC_PUBLIC|Opcodes.ACC_INTERFACE|Opcodes.ACC_ABSTRACT;contract.methods.add(new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_ABSTRACT,"view","()Ljava/util/Map;","()Ljava/util/Map<Ljava/lang/String;Ljava/util/List<Ljava/lang/String;>;>;",null));MethodNode lookup=mapLookup("unused","fresh");contract.methods.add(new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_ABSTRACT,lookup.name,lookup.desc,lookup.signature,null));contract.methods.add(new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_ABSTRACT,"size","()I",null,null));return contract;}
    private static ClassNode mapHolder(String name,String contract,boolean view){ClassNode target=node(name,"java/lang/Object");target.interfaces.add(contract);for(String field:List.of("old","fresh"))target.fields.add(new FieldNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_FINAL,field,"Ljava/util/Map;",null,null));MethodNode init=new MethodNode(Opcodes.ACC_PUBLIC,"<init>","(Ljava/util/Map;Ljava/util/Map;)V",null,null);init.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false));int slot=1;for(String field:List.of("old","fresh")){init.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));init.instructions.add(new VarInsnNode(Opcodes.ALOAD,slot++));init.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD,name,field,"Ljava/util/Map;"));}init.instructions.add(new InsnNode(Opcodes.RETURN));target.methods.add(init);target.methods.add(mapLookup(name,"fresh"));target.methods.add(mapSize(name,"fresh"));if(view){MethodNode method=new MethodNode(Opcodes.ACC_PUBLIC,"view","()Ljava/util/Map;","()Ljava/util/Map<Ljava/lang/String;Ljava/util/List<Ljava/lang/String;>;>;",null);method.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD,name,"fresh","Ljava/util/Map;"));method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"java/util/Collections","unmodifiableMap","(Ljava/util/Map;)Ljava/util/Map;",false));method.instructions.add(new InsnNode(Opcodes.ARETURN));target.methods.add(method);}return target;}
    private static MethodNode mapLookup(String owner,String field){MethodNode method=new MethodNode(Opcodes.ACC_PUBLIC,"lookup","(Ljava/lang/String;)Ljava/util/List;","(Ljava/lang/String;)Ljava/util/List<Ljava/lang/String;>;",null);method.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD,owner,field,"Ljava/util/Map;"));method.instructions.add(new VarInsnNode(Opcodes.ALOAD,1));method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"java/util/Collections","emptyList","()Ljava/util/List;",false));method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,"java/util/Map","getOrDefault","(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",true));method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST,"java/util/List"));method.instructions.add(new InsnNode(Opcodes.ARETURN));return method;}
    private static MethodNode mapSize(String owner,String field){MethodNode method=new MethodNode(Opcodes.ACC_PUBLIC,"size","()I",null,null);method.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD,owner,field,"Ljava/util/Map;"));method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,"java/util/Map","size","()I",true));method.instructions.add(new InsnNode(Opcodes.IRETURN));return method;}
    private static ClassNode immutable(String name,String parent){ClassNode node=node(name,parent);node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_FINAL,"stored","Ljava/lang/Object;",null,null));MethodNode ctor=new MethodNode(Opcodes.ACC_PUBLIC,"<init>","(Ljava/lang/Object;)V",null,null);ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));ctor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,parent,"<init>","()V",false));ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD,1));ctor.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD,name,"stored","Ljava/lang/Object;"));ctor.instructions.add(new InsnNode(Opcodes.RETURN));node.methods.add(ctor);MethodNode get=new MethodNode(Opcodes.ACC_PUBLIC,"value","()Ljava/lang/Object;",null,null);get.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));get.instructions.add(new FieldInsnNode(Opcodes.GETFIELD,name,"stored","Ljava/lang/Object;"));get.instructions.add(new InsnNode(Opcodes.ARETURN));node.methods.add(get);return node;}
    private static MethodNode flow(boolean extra,String tail){MethodNode method=new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"send","(Ljava/lang/String;)V",null,null);if(extra)call(method,"extra");call(method,"body");if(tail!=null)call(method,tail);method.instructions.add(new InsnNode(Opcodes.RETURN));return method;}
    private static void call(MethodNode method,String name){method.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,LOG,name,"(Ljava/lang/String;)V",false));}
    private static ContractGraph logGraph(){ClassNode log=node(LOG,"java/lang/Object");for(String name:List.of("body","extra","first","second"))log.methods.add(new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,name,"(Ljava/lang/String;)V",null,null));return graph(log);}
    private static ClassNode node(String name,String parent){ClassNode node=new ClassNode();node.version=Opcodes.V17;node.access=Opcodes.ACC_PUBLIC;node.name=name;node.superName=parent;return node;}
    private static MethodNode constructor(String name,String parent){MethodNode method=new MethodNode(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);method.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,parent,"<init>","()V",false));method.instructions.add(new InsnNode(Opcodes.RETURN));return method;}
    private static ContractGraph graph(ClassNode... nodes){Map<String,byte[]> map=new LinkedHashMap<>();for(ClassNode node:nodes)map.put(node.name,bytes(node));return new ContractGraph(List.of(map));}
    private static byte[] bytes(ClassNode node){ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);return writer.toByteArray();}
    private static Class<?> define(Map<String,byte[]> definitions,String name)throws Exception{return new ClassLoader(GenericContractMergeTest.class.getClassLoader()){@Override protected Class<?> findClass(String binary)throws ClassNotFoundException{byte[] bytes=definitions.get(binary.replace('.','/'));if(bytes==null)return super.findClass(binary);return defineClass(binary,bytes,0,bytes.length);}}.loadClass(name.replace('/','.'));}
}
