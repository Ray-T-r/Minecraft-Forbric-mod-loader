/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.util.*;
import java.util.function.Function;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MixinInstructionFingerprint;
import net.forbric.kernel.mixin.NativeGameReferences;

/** Source projection is insufficient: the actual helper, typed bridge, and complete host call closure must match. */
public final class LootSourceContracts implements Opcodes {
    private static final String BRIDGE="net/forbric/kernel/runtime/KernelLootBridge", DISPATCH="net/forbric/kernel/boot/LootTableEventDispatch";
    private static final String PROVIDER="net/minecraft/core/HolderLookup$Provider", TABLE="net/minecraft/world/level/storage/loot/LootTable";
    private static final String ID="net/minecraft/resources/Identifier", REGISTRY="net/minecraft/core/WritableRegistry", DATA="net/minecraft/world/level/storage/loot/LootDataType";
    private static final String LOAD="(L"+PROVIDER+";L"+ID+";L"+TABLE+";)L"+TABLE+";";
    private static final String TAGS="(Lnet/minecraft/server/packs/resources/ResourceManager;L"+REGISTRY+";)V";
    private record Contract(ClassLoader loader,LootSourceCallbacks.Plan plan,Map<String,String> host,Map<String,String> bridge,Map<String,Map<String,String>> domain){}
    private static volatile Contract contract;
    private static final ClassLoader UNSCOPED_TEST_LOADER=new ClassLoader(null){};
    private static final java.lang.ref.ReferenceQueue<ClassLoader> collected=new java.lang.ref.ReferenceQueue<>();
    private static final Map<LoaderIdentity,Map<String,Map<String,String>>> observed=new java.util.concurrent.ConcurrentHashMap<>();
    private static final class LoaderIdentity extends java.lang.ref.WeakReference<ClassLoader>{
        private final int hash;LoaderIdentity(ClassLoader loader,boolean retained){super(loader,retained?collected:null);hash=System.identityHashCode(loader);}
        @Override public int hashCode(){return hash;}@Override public boolean equals(Object other){return this==other||(other instanceof LoaderIdentity key&&get()!=null&&get()==key.get());}
    }
    private static Map<String,Map<String,String>> ledger(ClassLoader loader,boolean create){for(java.lang.ref.Reference<? extends ClassLoader> gone;(gone=collected.poll())!=null;)observed.remove(gone);ClassLoader actual=loader==null?UNSCOPED_TEST_LOADER:loader;LoaderIdentity key=new LoaderIdentity(actual,false);Map<String,Map<String,String>> values=observed.get(key);if(values==null&&create){Map<String,Map<String,String>> fresh=new java.util.concurrent.ConcurrentHashMap<>();Map<String,Map<String,String>> raced=observed.putIfAbsent(new LoaderIdentity(actual,true),fresh);values=raced==null?fresh:raced;}return values;}

    private LootSourceContracts(){}
    public static void reset(){contract=null;observed.clear();}
    static boolean offer(LootSourceCallbacks.Plan plan,Function<String,byte[]> reader,ClassLoader loader){
        ClassNode nativeNode=new NativeGameReferences(reader).get(Ecosystem.FABRIC,plan.targetName().replace('.','/'));
        return offer(plan,reader,nativeNode,loader);
    }
    /** Injectable native snapshot for isolated proof tests; production uses hash-verified native-reference resources. */
    public static boolean offer(LootSourceCallbacks.Plan plan,Function<String,byte[]> reader,ClassNode nativeNode){return offer(plan,reader,nativeNode,UNSCOPED_TEST_LOADER);}
    public static boolean offer(LootSourceCallbacks.Plan plan,Function<String,byte[]> reader,ClassNode nativeNode,ClassLoader loader){
        if(plan==null||nativeNode==null)return false;
        try{
            ClassNode host=read(reader.apply(plan.targetName().replace('.','/')+".class"));ClassNode bridge=read(reader.apply(BRIDGE+".class"));
            Set<MethodNode> roots=hostRoots(nativeNode,host,plan.nativeInputs());Map<String,ClassNode> domain=LootRegistryDomain.prove(reader);if(roots==null||!typedBridge(bridge)||domain==null)return false;
            Set<MethodNode> closure=closure(host,roots);if(closure==null)return false;
            Map<String,Map<String,String>> domainHashes=new LinkedHashMap<>();domain.forEach((owner,node)->domainHashes.put(owner,fingerprints(node)));Contract candidate=new Contract(loader,plan,fingerprints(closure),fingerprints(bridge),Map.copyOf(domainHashes));
            Contract previous=contract;if(previous!=null&&(!previous.plan.sourceName().equals(plan.sourceName())||previous.loader!=loader))return false;
            contract=candidate;return true;
        }catch(RuntimeException unreadable){return false;}
    }
    public static boolean isWitness(String name){Contract c=contract;if(c==null||name==null)return false;String internal=name.replace('.','/');return internal.equals(c.plan.targetName().replace('.','/'))||internal.equals(BRIDGE)||internal.equals(c.plan.helperName())||c.domain.containsKey(internal);}
    public static void observeDefinition(String name,byte[] bytes){observeDefinition(UNSCOPED_TEST_LOADER,name,bytes);}
    public static void observeDefinition(ClassLoader definingLoader,String name,byte[] bytes){if(bytes==null)return;String internal=name.replace('.','/');if(!internal.equals(BRIDGE)&&!internal.equals(LootRegistryDomain.DATA)&&!internal.equals(LootRegistryDomain.KEYS)&&!isWitness(name))return;
        try{ledger(definingLoader,true).put(internal,fingerprints(read(bytes)));}catch(RuntimeException unreadable){ledger(definingLoader,true).remove(internal);}
    }
    public static boolean helperProved(){Contract c=contract;return c!=null&&matches(c.loader,c.plan.helperName(),c.plan.helperMethods());}
    public static boolean proved(){Contract c=contract;return c!=null&&proved(c.loader);}
    public static boolean proved(ClassLoader loader){Contract c=contract;return c!=null&&c.loader==loader&&LootTableEventDispatch.enabled()&&helperProved()&&matches(loader,BRIDGE,c.bridge)&&matches(loader,c.plan.targetName().replace('.','/'),c.host)&&c.domain.entrySet().stream().allMatch(e->matches(loader,e.getKey(),e.getValue()));}
    private static boolean matches(ClassLoader loader,String owner,Map<String,String> expected){Map<String,Map<String,String>> definitions=ledger(loader,false);Map<String,String> actual=definitions==null?null:definitions.get(owner);if((owner.equals(BRIDGE)||(contract!=null&&(owner.equals(contract.plan.helperName()))))&&actual!=null&&!actual.keySet().equals(expected.keySet()))return false;return actual!=null&&expected.entrySet().stream().allMatch(e->Objects.equals(actual.get(e.getKey()),e.getValue()));}
    private static Map<String,String> fingerprints(ClassNode node){Map<String,String> out=new LinkedHashMap<>(fingerprints(node.methods));out.put("<shape>",LootSourceCallbacks.shape(node));return Map.copyOf(out);}
    private static Map<String,String> fingerprints(Collection<MethodNode> methods){Map<String,String> out=new LinkedHashMap<>();for(MethodNode m:methods)out.put(m.name+m.desc,m.access+":"+MixinInstructionFingerprint.hash(m));return Map.copyOf(out);}
    private static ClassNode read(byte[] bytes){if(bytes==null)throw new IllegalArgumentException("missing class");ClassNode n=new ClassNode();new ClassReader(bytes).accept(n,ClassReader.EXPAND_FRAMES);return n;}
    private static Set<MethodNode> hostRoots(ClassNode original,ClassNode current,LootSourceCallbacks.NativeInputs inputs){
        if(original==null||current==null||!original.name.equals(current.name))return null;
        MethodNode nativeTags=single(original.methods,m->calls(m,"net/minecraft/tags/TagLoader","loadTagsForRegistry",TAGS)==1);
        MethodNode tags=single(current.methods,m->calls(m,BRIDGE,"loadTagsForRegistry",TAGS)==1);
        MethodNode load=single(current.methods,m->calls(m,BRIDGE,"loadLootTable",LOAD)==1);
        if(nativeTags==null||!selected(nativeTags,inputs.consumerSelector())||tags==null||!tags.desc.equals("(L"+DATA+";Lnet/minecraft/resources/RegistryOps;Lnet/minecraft/server/packs/resources/ResourceManager;)L"+REGISTRY+";")||load==null||!load.desc.equals("(L"+DATA+";L"+PROVIDER+";Ljava/util/Map;L"+ID+";Ljava/util/Optional;)V")||!nativeTags.tryCatchBlocks.isEmpty()||!tags.tryCatchBlocks.isEmpty()||!load.tryCatchBlocks.isEmpty())return null;
        if(total(current,BRIDGE,"loadLootTable",LOAD)!=1||total(current,BRIDGE,"loadTagsForRegistry",TAGS)!=1)return null;
        // Completion is on the same actual registry returned after registration and tags; nothing follows the seam.
        List<AbstractInsnNode> t=code(tags);int at=index(t,BRIDGE,"loadTagsForRegistry",TAGS);if(at<2||at!=t.size()-3||t.getLast().getOpcode()!=ARETURN)return null;
        if(!(t.get(at-1) instanceof VarInsnNode registry&&registry.getOpcode()==ALOAD)||!(t.get(at+1) instanceof VarInsnNode returned&&returned.getOpcode()==ALOAD&&returned.var==registry.var))return null;
        // Both source and current registration consumers carry byte-identical registration bodies, despite lambda renaming.
        MethodNode nativeRegister=registrationConsumer(original,nativeTags);MethodNode currentRegister=registrationConsumer(current,tags);
        if(nativeRegister==null||currentRegister==null||!MixinInstructionFingerprint.hash(nativeRegister).equals(MixinInstructionFingerprint.hash(currentRegister)))return null;
        // The richer native stream offers each survivor once to the bridge, puts that returned value into the registration map,
        // and preserves native cancellation. Capture identities are not inferred from names or types alone.
        List<AbstractInsnNode> l=code(load);int call=index(l,BRIDGE,"loadLootTable",LOAD);
        if(call<3||!(l.get(call-3) instanceof VarInsnNode p&&p.getOpcode()==ALOAD&&p.var==1)||!(l.get(call-2) instanceof VarInsnNode id&&id.getOpcode()==ALOAD&&id.var==3)||!(l.get(call-1) instanceof VarInsnNode table&&table.getOpcode()==ALOAD))return null;
        if(!(l.get(call+1) instanceof VarInsnNode saved&&saved.getOpcode()==ASTORE)||!(l.get(call+2) instanceof VarInsnNode nullValue&&nullValue.getOpcode()==ALOAD&&nullValue.var==saved.var)||!(l.get(call+3) instanceof JumpInsnNode nonnull&&nonnull.getOpcode()==IFNULL)||nextOpcode(nonnull.label)!=RETURN)return null;
        int put=index(l,"java/util/Map","put","(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");if(put<3||calls(load,"java/util/Map","put","(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")!=1)return null;
        if(!(l.get(put-3) instanceof VarInsnNode map&&map.getOpcode()==ALOAD&&map.var==2)||!(l.get(put-2) instanceof VarInsnNode putId&&putId.getOpcode()==ALOAD&&putId.var==id.var)||!(l.get(put-1) instanceof VarInsnNode value&&value.getOpcode()==ALOAD&&value.var==saved.var))return null;
        int registeredMap=-1;for(int i=0;i<t.size();i++)if(t.get(i) instanceof InvokeDynamicInsnNode d&&own(d,current,currentRegister)){if(i<3||!(t.get(i-3) instanceof VarInsnNode receiver&&receiver.getOpcode()==ALOAD))return null;registeredMap=receiver.var;}if(registeredMap<0)return null;
        // The bridge lambda gets the actual provider extracted from the same ops parameter, and writes into the map later registered.
        int captured=0;for(int i=0;i<t.size();i++)if(t.get(i) instanceof InvokeDynamicInsnNode d&&own(d,current,load)){
            if(i<4||!(t.get(i-4) instanceof VarInsnNode mapReceiver&&mapReceiver.getOpcode()==ALOAD)||!(t.get(i-3) instanceof VarInsnNode data&&data.getOpcode()==ALOAD&&data.var==0)||!(t.get(i-2) instanceof VarInsnNode provider&&provider.getOpcode()==ALOAD)||!(t.get(i-1) instanceof VarInsnNode mapArg&&mapArg.getOpcode()==ALOAD&&mapArg.var==registeredMap))return null;
            if(mapReceiver.var==mapArg.var)return null; // optional entries and final survivors are distinct maps
            boolean providerOrigin=false;for(int k=2;k<i-2;k++)if(t.get(k) instanceof VarInsnNode store&&store.getOpcode()==ASTORE&&store.var==provider.var&&t.get(k-1) instanceof MethodInsnNode extract&&extract.getOpcode()==INVOKESTATIC&&extract.desc.equals("(Lnet/minecraft/resources/RegistryOps;)L"+PROVIDER+";")&&t.get(k-2) instanceof VarInsnNode ops&&ops.getOpcode()==ALOAD&&ops.var==1)providerOrigin=true;
            if(!providerOrigin)return null;captured++;
        }
        if(captured!=1)return null;
        // Native reload owns a single serialization-context creation and passes that very ops object through the async graph.
        MethodNode reload=single(original.methods,m->calls(m,PROVIDER,"createSerializationContext","(Lcom/mojang/serialization/DynamicOps;)Lnet/minecraft/resources/RegistryOps;")==1);
        if(reload==null||!selected(reload,inputs.producerSelector())||!reload.tryCatchBlocks.isEmpty()||calls(reload,"java/util/concurrent/CompletableFuture","thenApplyAsync","(Ljava/util/function/Function;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;")!=1)return null;
        List<AbstractInsnNode> r=code(reload);int origin=index(r,PROVIDER,"createSerializationContext","(Lcom/mojang/serialization/DynamicOps;)Lnet/minecraft/resources/RegistryOps;");
        if(origin<2||!(r.get(origin-2) instanceof VarInsnNode provider&&provider.getOpcode()==ALOAD)||!(r.get(origin+1) instanceof VarInsnNode ops&&ops.getOpcode()==ASTORE))return null;
        int forwarded=0;for(int i=0;i<r.size();i++)if(r.get(i) instanceof InvokeDynamicInsnNode d&&d.desc.startsWith("(Lnet/minecraft/resources/RegistryOps;")){if(i<3||!(r.get(i-3) instanceof VarInsnNode capturedOps&&capturedOps.getOpcode()==ALOAD&&capturedOps.var==ops.var))return null;forwarded++;}
        if(forwarded!=1)return null;
        MethodInsnNode async=null;for(AbstractInsnNode instruction:reload.instructions)if(instruction instanceof MethodInsnNode c&&c.owner.equals("java/util/concurrent/CompletableFuture")&&c.name.equals("thenApplyAsync"))async=c;
        if(!namedLocal(reload,inputs.opsLocal(),ops.var,async))return null;
        MethodInsnNode each=null;for(AbstractInsnNode instruction:nativeTags.instructions)if(instruction instanceof MethodInsnNode c&&c.owner.equals("java/util/Map")&&c.name.equals("forEach")){if(each!=null)return null;each=c;}
        List<AbstractInsnNode> nativeCode=code(nativeTags);int mapEach=index(nativeCode,"java/util/Map","forEach","(Ljava/util/function/BiConsumer;)V");if(mapEach<4||!(nativeCode.get(mapEach-4) instanceof VarInsnNode entries&&entries.getOpcode()==ALOAD)||!namedLocal(nativeTags,inputs.elementsLocal(),entries.var,each))return null;
        String sourcePrefix="(L"+DATA+";Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/resources/RegistryOps;)";if(!nativeTags.desc.startsWith(sourcePrefix))return null;
        MethodNode actualReload=single(current.methods,m->selected(m,inputs.producerSelector()));if(actualReload==null||!closedAsyncDomain(current,actualReload,tags))return null;
        Set<MethodNode> roots=new LinkedHashSet<>(List.of(tags,load,actualReload));for(MethodNode m:current.methods)if(m.name.equals("<clinit>"))roots.add(m);return roots;
    }
    private static MethodNode registrationConsumer(ClassNode owner,MethodNode root){MethodNode found=null;for(AbstractInsnNode i:root.instructions)if(i instanceof InvokeDynamicInsnNode d&&d.bsmArgs.length==3&&d.bsmArgs[1] instanceof Handle h&&h.getOwner().equals(owner.name)){
        MethodNode m=single(owner.methods,x->x.name.equals(h.getName())&&x.desc.equals(h.getDesc()));if(m!=null&&calls(m,REGISTRY,"register","(Lnet/minecraft/resources/ResourceKey;Ljava/lang/Object;Lnet/minecraft/core/RegistrationInfo;)Lnet/minecraft/core/Holder$Reference;")==1){if(found!=null)return null;found=m;}
    }return found;}
    private static boolean typedBridge(ClassNode bridge){
        if(bridge==null||!bridge.name.equals(BRIDGE))return false;
        MethodNode load=single(bridge.methods,m->m.desc.equals(LOAD)&&m.name.equals("loadLootTable"));MethodNode tags=single(bridge.methods,m->m.desc.equals(TAGS)&&m.name.equals("loadTagsForRegistry"));
        if(load==null||tags==null||!load.tryCatchBlocks.isEmpty()||!tags.tryCatchBlocks.isEmpty())return false;
        if(calls(load,"net/neoforged/neoforge/event/EventHooks","loadLootTable",LOAD)!=1||calls(load,DISPATCH,"afterLoad","(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")!=1)return false;
        if(calls(tags,"net/minecraft/tags/TagLoader","loadTagsForRegistry",TAGS)!=1||calls(tags,DISPATCH,"allLoaded","(Ljava/lang/Object;Ljava/lang/Object;)V")!=1)return false;
        List<AbstractInsnNode> l=code(load);int after=index(l,DISPATCH,"afterLoad","(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
        if(after<6||!(l.get(after-6) instanceof VarInsnNode p&&p.getOpcode()==ALOAD&&p.var==0)||!(l.get(after-4) instanceof VarInsnNode keyId&&keyId.getOpcode()==ALOAD&&keyId.var==1)||!(l.get(after-3) instanceof MethodInsnNode key&&key.owner.equals("net/minecraft/resources/ResourceKey")&&key.name.equals("create"))||!(l.get(after-2) instanceof VarInsnNode id&&id.getOpcode()==ALOAD&&id.var==1)||!(l.get(after-1) instanceof VarInsnNode table&&table.getOpcode()==ALOAD))return false;
        return true;
    }
    private static boolean own(InvokeDynamicInsnNode d,ClassNode owner,MethodNode method){return d.bsmArgs.length==3&&d.bsmArgs[1] instanceof Handle h&&h.getOwner().equals(owner.name)&&h.getName().equals(method.name)&&h.getDesc().equals(method.desc);}
    private static Set<MethodNode> closure(ClassNode owner,Set<MethodNode> roots){Set<MethodNode> out=new LinkedHashSet<>();Deque<MethodNode> q=new ArrayDeque<>(roots);while(!q.isEmpty()){MethodNode m=q.removeFirst();if(!out.add(m))continue;for(AbstractInsnNode i:m.instructions){if(i instanceof MethodInsnNode c&&c.owner.equals(owner.name)){MethodNode next=single(owner.methods,x->x.name.equals(c.name)&&x.desc.equals(c.desc));if(next==null)return null;q.add(next);}if(i instanceof InvokeDynamicInsnNode d)for(Object a:d.bsmArgs)if(a instanceof Handle h&&h.getOwner().equals(owner.name)){MethodNode next=single(owner.methods,x->x.name.equals(h.getName())&&x.desc.equals(h.getDesc()));if(next==null)return null;q.add(next);}}}return out;}
    private static boolean closedAsyncDomain(ClassNode owner,MethodNode reload,MethodNode tags){
        List<AbstractInsnNode> c=code(reload);int at=index(c,DATA,"values","()Ljava/util/stream/Stream;");if(at<0||calls(reload,DATA,"values","()Ljava/util/stream/Stream;")!=1||at+6>=c.size()||!(c.get(at+1)instanceof VarInsnNode ops&&ops.getOpcode()==ALOAD)||!(c.get(at+2)instanceof VarInsnNode resource&&resource.getOpcode()==ALOAD&&resource.var==2)||!(c.get(at+3)instanceof VarInsnNode executor&&executor.getOpcode()==ALOAD&&executor.var==3)||!(c.get(at+4)instanceof InvokeDynamicInsnNode dispatch)||!(c.get(at+5)instanceof MethodInsnNode map&&map.owner.equals("java/util/stream/Stream")&&map.name.equals("map"))||!(c.get(at+6)instanceof MethodInsnNode list&&list.owner.equals("java/util/stream/Stream")&&list.name.equals("toList")))return false;
        Handle first=implementation(dispatch);MethodNode leaf=first==null||!first.getOwner().equals(owner.name)?null:single(owner.methods,m->m.name.equals(first.getName())&&m.desc.equals(first.getDesc()));if(leaf==null||!leaf.tryCatchBlocks.isEmpty())return false;List<AbstractInsnNode> l=code(leaf);if(l.size()!=6||!aload(l.get(0),3)||!aload(l.get(1),0)||!aload(l.get(2),1)||!aload(l.get(3),2)||!(l.get(4)instanceof MethodInsnNode call&&call.getOpcode()==INVOKESTATIC&&call.owner.equals(owner.name))||l.get(5).getOpcode()!=ARETURN)return false;
        MethodNode schedule=single(owner.methods,m->m.name.equals(call.name)&&m.desc.equals(call.desc));if(schedule==null||!schedule.tryCatchBlocks.isEmpty())return false;List<AbstractInsnNode> s=code(schedule);if(s.size()!=7||!aload(s.get(0),0)||!aload(s.get(1),1)||!aload(s.get(2),2)||!(s.get(3)instanceof InvokeDynamicInsnNode supplier)||!own(supplier,owner,tags)||!aload(s.get(4),3)||!(s.get(5)instanceof MethodInsnNode async&&async.getOpcode()==INVOKESTATIC&&async.owner.equals("java/util/concurrent/CompletableFuture")&&async.name.equals("supplyAsync"))||s.get(6).getOpcode()!=ARETURN)return false;
        return true;
    }
    private static Handle implementation(InvokeDynamicInsnNode d){return d.bsmArgs.length==3&&d.bsmArgs[1]instanceof Handle h&&h.getTag()==H_INVOKESTATIC?h:null;}
    private static boolean aload(AbstractInsnNode i,int slot){return i instanceof VarInsnNode v&&v.getOpcode()==ALOAD&&v.var==slot;}
    private static boolean selected(MethodNode method,String selector){return method.name.equals(selector)||(method.name+method.desc).equals(selector);}
    private static boolean namedLocal(MethodNode method,String name,int slot,AbstractInsnNode anchor){if(anchor==null||method.localVariables==null)return false;int point=method.instructions.indexOf(anchor);int found=0;for(LocalVariableNode local:method.localVariables)if(local.name.equals(name)&&local.index==slot&&method.instructions.indexOf(local.start)<=point&&point<method.instructions.indexOf(local.end))found++;return found==1;}
    private static int nextOpcode(AbstractInsnNode node){while(node!=null&&node.getOpcode()<0)node=node.getNext();return node==null?-1:node.getOpcode();}
    private static int total(ClassNode c,String o,String n,String d){return c.methods.stream().mapToInt(m->calls(m,o,n,d)).sum();}
    private static int calls(MethodNode m,String o,String n,String d){int count=0;for(AbstractInsnNode i:m.instructions)if(i instanceof MethodInsnNode c&&c.owner.equals(o)&&c.name.equals(n)&&c.desc.equals(d))count++;return count;}
    private static int index(List<AbstractInsnNode> code,String o,String n,String d){for(int i=0;i<code.size();i++)if(code.get(i) instanceof MethodInsnNode c&&c.owner.equals(o)&&c.name.equals(n)&&c.desc.equals(d))return i;return -1;}
    private static List<AbstractInsnNode> code(MethodNode m){List<AbstractInsnNode> c=new ArrayList<>();for(AbstractInsnNode i:m.instructions)if(i.getOpcode()>=0)c.add(i);return c;}
    private static MethodNode single(Collection<MethodNode> ms,java.util.function.Predicate<MethodNode> p){MethodNode found=null;for(MethodNode m:ms)if(p.test(m)){if(found!=null)return null;found=m;}return found;}
}
