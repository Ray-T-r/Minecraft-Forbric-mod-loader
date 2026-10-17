/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.*;
import java.util.function.Function;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.*;

import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.mixin.HandlerFlow;
import net.forbric.kernel.mixin.MixinInstructionFingerprint;

/** A closed private ops/provider memo permits projecting the original callbacks onto the typed reload seam.
 * Callback bodies, event invoker reads, exception regions and holder iteration are copied, never reimplemented. */
public final class LootSourceCallbacks implements Opcodes {
    private static final String OPS="net/minecraft/resources/RegistryOps", PROVIDER="net/minecraft/core/HolderLookup$Provider";
    private static final String ID="net/minecraft/resources/Identifier", TABLE="net/minecraft/world/level/storage/loot/LootTable";
    private static final String DATA="net/minecraft/world/level/storage/loot/LootDataType", RESOURCES="net/minecraft/server/packs/resources/ResourceManager";
    private static final String REGISTRY="net/minecraft/core/Registry", CIR="org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
    private static final String OP="com/llamalad7/mixinextras/injector/wrapoperation/Operation";
    private static final String VALUE_DESC="(Ljava/lang/Object;L"+ID+";L"+OPS+";)Ljava/lang/Object;";
    private static final String ALL_DESC="(L"+DATA+";L"+RESOURCES+";L"+OPS+";L"+CIR+";)V";
    private static volatile Plan offered;
    private static volatile ClassLoader gameLoader;
    private LootSourceCallbacks() {}

    /**
     * The only rewritten expression is the private WeakHashMap.get(ops) provider projection.
     *
     * @param whole    whether the source is the callback group and nothing else — then the whole mixin is superseded; else only
     *                 its four role injectors ({@code stripped}, as name + descriptor) are taken out of it and the rest applies
     */
    public record Plan(String sourceName,String targetName,String helperName,byte[] helperBytes,Map<String,String> helperMethods, NativeInputs nativeInputs,
                       boolean whole,Set<String> stripped) {
        public Plan{stripped=Set.copyOf(stripped);}
        public Plan(String sourceName,String targetName,String helperName,byte[] helperBytes,Map<String,String> helperMethods,NativeInputs nativeInputs){
            this(sourceName,targetName,helperName,helperBytes,helperMethods,nativeInputs,true,Set.of());}
    }
    /**
     * The four callbacks' hosts, as each injector names them: its {@code method} selectors and whether its handler is
     * static, which is what Mixin binds against a class. The producer and its cleanup share one host, and so do the
     * consumer and the completion: each pair binds one same method, never decided by how either selector is written
     * ({@link LootSourceContracts} binds them in the class the source was compiled against and in the current one).
     */
    public record NativeInputs(Selection producer,Selection cleanup,Selection consumer,Selection completion,String opsLocal,String elementsLocal) {}
    /** One injector's {@code method} selectors as written, and whether its handler is static. */
    public record Selection(List<String> selectors,boolean staticHandler) {
        public Selection{selectors=List.copyOf(selectors);}
        static Selection of(MethodNode handler){Object value=field(injection(handler),"method");List<String> selectors=new ArrayList<>();if(value instanceof String one)selectors.add(one);else if(value instanceof List<?> list)for(Object element:list)if(element instanceof String selector)selectors.add(selector);return new Selection(selectors,(handler.access&ACC_STATIC)!=0);}
        /** The one method of {@code target} these selectors bind, as Mixin binds them; null for none, several, or a form Mixin refuses. */
        public MethodNode bound(ClassNode target){List<MethodNode> bound=net.forbric.kernel.mixin.MixinBindings.bound(selectors,target,staticHandler);return bound!=null&&bound.size()==1?bound.getFirst():null;}
    }
    public record Callbacks(MethodHandle afterLoad,MethodHandle allLoaded) {}

    public static synchronized void bind(ClassLoader loader){gameLoader=loader;reset();}
    public static synchronized void reset(){offered=null;LootSourceContracts.reset();}

    /** Called while reading a suppressed class; unrelated or incomplete source groups are ignored. */
    public static boolean offer(ClassLoader loader,ClassNode source,Function<String,byte[]> reader){
        if(!LootTableEventDispatch.enabled()||!(loader instanceof ForbricClassLoader actual))return false;
        Plan plan=plan(source);
        if(plan==null||!LootSourceContracts.offer(plan,reader,loader))return false;
        synchronized(LootSourceCallbacks.class){
            if(offered!=null&&!offered.sourceName.equals(plan.sourceName))return false;
            gameLoader=loader;offered=plan;actual.putGeneratedClass(plan.helperName,plan.helperBytes);
        }
        return true;
    }
    public static String targetName(){Plan p=offered;return p==null?null:p.targetName;}
    public static String sourceName(){Plan p=offered;return p==null?null:p.sourceName;}
    /** Whether the offered group is all of its mixin, so the whole mixin is superseded; false for none or a partial group. */
    public static boolean whole(String sourceName){Plan p=offered;return p!=null&&p.whole&&p.sourceName.equals(sourceName.replace('/','.'));}
    /**
     * Takes the offered group's four role injectors out of {@code mixin} when the group is only part of it, so the rest of
     * the mixin applies as written while the bridge runs the group; returns how many it took. A whole group's mixin is left
     * out by the config policy instead, and an unrelated mixin is untouched.
     */
    public static int stripPartialGroup(ClassNode mixin){return strip(offered,mixin);}
    /** {@link #stripPartialGroup} for {@code p}: its role injectors out of {@code mixin} when it is its partial group. */
    public static int strip(Plan p,ClassNode mixin){
        if(p==null||p.whole||mixin==null||!p.sourceName.equals(mixin.name.replace('/','.')))return 0;
        int before=mixin.methods.size();mixin.methods.removeIf(m->p.stripped.contains(m.name+m.desc));
        int taken=before-mixin.methods.size();
        if(taken>0)net.forbric.kernel.util.ForbricLog.info("[Forbric/LootBridge] took the closed loot callback group's %d injector(s) out of %s; the "
                +"bridge runs them through its generated helper, and the mixin's other members apply as written",taken,p.sourceName);
        return taken;
    }
    public static boolean proved(String sourceName){Plan p=offered;return p!=null&&p.sourceName.equals(sourceName)&&LootSourceContracts.proved(gameLoader);}
    public static void observeDefinition(String name,byte[] bytes){LootSourceContracts.observeDefinition(name,bytes);}
    public static void observeDefinition(ClassLoader loader,String name,byte[] bytes){LootSourceContracts.observeDefinition(loader,name,bytes);}
    public static Callbacks callbacks(ClassLoader loader){
        Plan p=offered;if(p==null||loader!=gameLoader)return null;
        try{
            Class<?> helper=Class.forName(p.helperName.replace('/','.'),true,loader);
            if(helper.getClassLoader()!=loader||!LootSourceContracts.proved(loader))return null;
            var lookup=MethodHandles.publicLookup();
            return new Callbacks(lookup.findStatic(helper,"afterLoad",MethodType.methodType(Object.class,Object.class,Object.class,Object.class)),
                    lookup.findStatic(helper,"allLoaded",MethodType.methodType(void.class,Object.class,Object.class)));
        }catch(ReflectiveOperationException failure){throw new IllegalStateException("proved loot callback helper could not be linked",failure);}
    }

    /** No source class, mod id or handler name admits this rule. The four injector roles and every memo access close it. */
    public static Plan plan(ClassNode input){
        try{if(input==null)return null;ClassWriter snapshot=new ClassWriter(0);input.accept(snapshot);ClassNode expanded=new ClassNode();new ClassReader(snapshot.toByteArray()).accept(expanded,ClassReader.EXPAND_FRAMES);return planChecked(expanded);}catch(RuntimeException unknown){return null;}
    }
    private static Plan planChecked(ClassNode input){
        if(input==null||!"java/lang/Object".equals(input.superName)||!input.interfaces.isEmpty())return null;
        String target=target(input);if(target==null)return null;
        List<MethodNode> injectors=input.methods.stream().filter(LootSourceCallbacks::injector).toList();
        // Each role by what it computes, never by the events it fires or a name: the completion by its typed descriptor, the
        // value callback as the one method the consumer's lambda applies.
        MethodNode put=single(injectors,m->count(m,"java/util/WeakHashMap","put")==1);
        MethodNode consume=single(injectors,m->count(m,"java/util/Map","replaceAll")==1);
        MethodNode value=consume==null?null:applied(input,consume);
        MethodNode all=single(injectors,m->m.desc.equals(ALL_DESC));if(value==null||all==null)return null;
        if((value.access&ACC_STATIC)==0||(all.access&ACC_STATIC)==0||!all.tryCatchBlocks.isEmpty())return null;
        MethodNode remove=single(injectors,m->count(m,"java/util/concurrent/CompletableFuture","thenApply")==1);
        if(put==null||consume==null||remove==null||Set.of(put,consume,remove,all).size()!=4)return null;
        // The memo is the field the producer puts into: private, static, final, a WeakHashMap.
        FieldNode memo=memoOf(input,put);if(memo==null)return null;
        NativeInputs inputs=new NativeInputs(Selection.of(put),Selection.of(remove),Selection.of(consume),Selection.of(all),localName(remove,4),localName(consume,4));if(selector(put)==null||selector(consume)==null||inputs.opsLocal==null||inputs.elementsLocal==null)return null;
        if(!memoProducer(input,put,memo)||!memoCleanup(input,remove,memo)||!mapConsumer(input,consume,value))return null;
        if(!allContext(all)||!anchor(consume,"INVOKE","Ljava/util/Map;forEach(Ljava/util/function/BiConsumer;)V")||!anchor(all,"RETURN",null))return null;
        if(!anchor(put,"INVOKE","L"+PROVIDER+";createSerializationContext(Lcom/mojang/serialization/DynamicOps;)L"+OPS+";")
                ||!anchor(remove,"INVOKE","Ljava/util/concurrent/CompletableFuture;thenApplyAsync(Ljava/util/function/Function;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))return null;
        if(providerReads(value,input.name,memo)==null)return null;
        // The memo is closed: read or written only by its initializer, the producer, the value callback and the cleanup's
        // lambda — however often each does — and no method handle reaches a field of the class.
        MethodNode cleanup=cleanupLambda(input,remove);boolean otherFields=false;
        for(MethodNode m:input.methods)for(AbstractInsnNode i:m.instructions){
            if(i instanceof FieldInsnNode f&&f.owner.equals(input.name)){
                if(!f.name.equals(memo.name)||!f.desc.equals(memo.desc)){otherFields=true;continue;}
                if(!(m==value||m==put||m.name.equals("<clinit>")||cleanup==m))return null;
            }
            if(i instanceof InvokeDynamicInsnNode d)for(Object a:d.bsmArgs)if(a instanceof Handle h&&h.getOwner().equals(input.name)&&h.getTag()<=H_PUTSTATIC)return null;
        }
        if(!memoStored(input,memo))return null;
        Set<MethodNode> closure=closure(input,Set.of(value,all));if(closure==null)return null;
        for(MethodNode m:closure){if((m.access&ACC_STATIC)==0)return null;for(AbstractInsnNode i:m.instructions)if(i instanceof FieldInsnNode f&&f.owner.equals(input.name)&&(m!=value||!f.name.equals(memo.name)||!f.desc.equals(memo.desc)))return null;}
        MethodNode constructor=single(input.methods,m->m.name.equals("<init>"));
        Set<MethodNode> classified=new HashSet<>(closure);classified.addAll(injectors);classified.add(constructor);classified.add(cleanup);InvokeDynamicInsnNode mapIndy=singleIndy(consume);Handle mapHandle=implementation(mapIndy);classified.add(single(input.methods,m->m.name.equals(mapHandle.getName())&&m.desc.equals(mapHandle.getDesc())));
        // The group is all of the mixin when nothing else is in it — no other injector, method or field use, a default
        // constructor and an initializer that only makes the memo: then the whole mixin is superseded. Otherwise only the
        // four role injectors are taken out, which nothing else in the mixin may call, and the rest applies as written.
        Set<MethodNode> roles=Set.of(put,consume,remove,all);
        boolean whole=!otherFields&&injectors.size()==roles.size()&&defaultConstructor(input,constructor)&&memoInitializer(input,memo)
                &&input.methods.stream().allMatch(m->m.name.equals("<clinit>")||classified.contains(m));
        if(!whole)for(MethodNode m:input.methods){
            if(roles.contains(m))continue;
            for(AbstractInsnNode i:m.instructions){
                if(i instanceof MethodInsnNode c&&c.owner.equals(input.name)&&roles.stream().anyMatch(r->r.name.equals(c.name)&&r.desc.equals(c.desc)))return null;
                if(i instanceof InvokeDynamicInsnNode d)for(Object a:d.bsmArgs)if(a instanceof Handle h&&h.getOwner().equals(input.name)&&roles.stream().anyMatch(r->r.name.equals(h.getName())&&r.desc.equals(h.getDesc())))return null;
            }
        }
        Set<String> stripped=new LinkedHashSet<>();for(MethodNode r:List.of(put,consume,remove,all))stripped.add(r.name+r.desc);

        ClassNode generated=new ClassNode();generated.version=input.version;generated.access=ACC_PUBLIC|ACC_FINAL|ACC_SUPER;generated.name=input.name;generated.superName="java/lang/Object";
        for(MethodNode original:closure){MethodNode copy=copy(original);stripAnnotations(copy);generated.methods.add(copy);}
        MethodNode moved=single(generated.methods,m->m.name.equals(value.name)&&m.desc.equals(value.desc));
        // Each (Provider) MEMO.get(ops) becomes (Provider) provider: the lookup's two operands go, and the lookup reads the parameter.
        List<MethodInsnNode> lookups=providerReads(moved,input.name,memo);HandlerFlow movedFlow=HandlerFlow.of(input.name,moved);
        for(MethodInsnNode lookup:lookups){AbstractInsnNode memoRead=movedFlow.producer(lookup,0,2),opsRead=movedFlow.producer(lookup,1,2);
            moved.instructions.remove(memoRead);moved.instructions.remove(opsRead);moved.instructions.set(lookup,new VarInsnNode(ALOAD,2));}
        moved.desc=VALUE_DESC.replace("L"+OPS+";","L"+PROVIDER+";");
        moved.signature=null;if(moved.localVariables!=null)for(LocalVariableNode l:moved.localVariables)if(l.index==2)l.desc="L"+PROVIDER+";";
        for(AbstractInsnNode i:moved.instructions)if(i instanceof FrameNode f&&f.local!=null&&f.local.size()>2)f.local.set(2,PROVIDER);
        String digest=digest(input.name+closure.stream().map(m->m.name+m.desc+MixinInstructionFingerprint.hash(m)).sorted().toList());
        String helper="net/forbric/kernel/runtime/generated/LootCallbacks$"+digest.substring(0,24);
        generated.methods.add(afterWrapper(input.name,moved));generated.methods.add(allWrapper(input.name,all));
        ClassNode mapped=new ClassNode();generated.accept(new ClassRemapper(mapped,new Remapper(){@Override public String map(String name){return input.name.equals(name)?helper:name;}}));
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);mapped.accept(writer);byte[] bytes=writer.toByteArray();
        Map<String,String> methods=new LinkedHashMap<>();for(MethodNode m:mapped.methods)methods.put(m.name+m.desc,m.access+":"+MixinInstructionFingerprint.hash(m));
        methods.put("<shape>",shape(mapped));
        return new Plan(input.name.replace('/','.'),target.replace('/','.'),helper,bytes,Map.copyOf(methods),inputs,whole,stripped);
    }

    static String shape(ClassNode node){return node.name+"|"+node.superName+"|"+node.access+"|"+node.interfaces+"|"+node.fields.stream().map(f->f.name+f.desc+":"+f.access+":"+f.value).sorted().toList();}
    private static MethodNode afterWrapper(String owner,MethodNode source){
        MethodNode m=new MethodNode(ACC_PUBLIC|ACC_STATIC,"afterLoad","(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",null,null);
        m.instructions.add(new VarInsnNode(ALOAD,2));m.instructions.add(new VarInsnNode(ALOAD,1));m.instructions.add(new TypeInsnNode(CHECKCAST,ID));m.instructions.add(new VarInsnNode(ALOAD,0));m.instructions.add(new TypeInsnNode(CHECKCAST,PROVIDER));
        m.instructions.add(new MethodInsnNode(INVOKESTATIC,owner,source.name,source.desc,false));m.instructions.add(new InsnNode(ARETURN));m.maxLocals=3;return m;
    }
    private static MethodNode allWrapper(String owner,MethodNode source){
        MethodNode m=new MethodNode(ACC_PUBLIC|ACC_STATIC,"allLoaded","(Ljava/lang/Object;Ljava/lang/Object;)V",null,null);
        m.instructions.add(new FieldInsnNode(GETSTATIC,DATA,"TABLE","L"+DATA+";"));m.instructions.add(new VarInsnNode(ALOAD,0));m.instructions.add(new TypeInsnNode(CHECKCAST,RESOURCES));m.instructions.add(new InsnNode(ACONST_NULL));
        m.instructions.add(new TypeInsnNode(NEW,CIR));m.instructions.add(new InsnNode(DUP));m.instructions.add(new LdcInsnNode("forbric:source-loot-completion"));m.instructions.add(new InsnNode(ICONST_0));m.instructions.add(new VarInsnNode(ALOAD,1));
        m.instructions.add(new MethodInsnNode(INVOKESPECIAL,CIR,"<init>","(Ljava/lang/String;ZLjava/lang/Object;)V",false));
        m.instructions.add(new MethodInsnNode(INVOKESTATIC,owner,source.name,source.desc,false));m.instructions.add(new InsnNode(RETURN));m.maxLocals=2;return m;
    }
    /**
     * The ops producer, read by data flow: its operation is called with the original provider and ops, and what it returns
     * is put into the memo (keyed by itself, valued by the provider) on every path and returned; nothing else is called,
     * written or thrown.
     */
    private static boolean memoProducer(ClassNode n,MethodNode m,FieldNode f){
        if(!m.tryCatchBlocks.isEmpty()||!m.desc.equals("(L"+PROVIDER+";Lcom/mojang/serialization/DynamicOps;L"+OP+";)L"+OPS+";"))return false;
        HandlerFlow flow=HandlerFlow.of(n.name,m);if(flow==null||!flow.acyclic())return false;int[] slot=slots(m);
        MethodInsnNode call=one(flow.calls(c->c.owner.equals(OP)&&c.name.equals("call"))),put=one(flow.calls(c->c.owner.equals("java/util/WeakHashMap")&&c.name.equals("put")&&c.desc.equals("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")));
        if(call==null||put==null||!flow.onlyCalls(c->c==call||c==put)||!flow.passesParameters(call,slot[2],new int[]{slot[0],slot[1]}))return false;
        if(!memo(flow.argument(put,0),n,f)||HandlerFlow.only(flow.argument(put,1))!=call||!HandlerFlow.onlyParameter(flow.argument(put,2),slot[0]))return false;
        return HandlerFlow.only(flow.returned())==call&&!flow.reaches(0,i->i.getOpcode()==ARETURN,identity(put));
    }
    private static MethodNode cleanupLambda(ClassNode n,MethodNode m){InvokeDynamicInsnNode d=singleIndy(m);if(d==null)return null;Handle h=implementation(d);return h==null||!h.getOwner().equals(n.name)?null:single(n.methods,x->x.name.equals(h.getName())&&x.desc.equals(h.getDesc()));}
    /**
     * The ops cleanup, read by data flow: its operation is called with the future made by {@code thenApply} of the
     * original future and one lambda capturing the ops, then the original function and executor, and its result is
     * returned. The lambda removes those ops from the memo on every path and returns the list it was given. Nothing else
     * is called, linked, written or thrown in either.
     */
    private static boolean memoCleanup(ClassNode n,MethodNode m,FieldNode f){
        if(!m.tryCatchBlocks.isEmpty()||!m.desc.equals("(Ljava/util/concurrent/CompletableFuture;Ljava/util/function/Function;Ljava/util/concurrent/Executor;L"+OP+";L"+OPS+";)Ljava/util/concurrent/CompletableFuture;"))return false;
        MethodNode lambda=cleanupLambda(n,m);if(lambda==null||!lambda.tryCatchBlocks.isEmpty()||!lambda.desc.equals("(L"+OPS+";Ljava/util/List;)Ljava/util/List;")||(lambda.access&ACC_STATIC)==0)return false;
        HandlerFlow inner=HandlerFlow.of(n.name,lambda);if(inner==null||!inner.acyclic())return false;
        MethodInsnNode remove=one(inner.calls(c->c.owner.equals("java/util/WeakHashMap")&&c.name.equals("remove")&&c.desc.equals("(Ljava/lang/Object;)Ljava/lang/Object;")));
        if(remove==null||!inner.onlyCalls(c->c==remove)||!memo(inner.argument(remove,0),n,f)||!HandlerFlow.onlyParameter(inner.argument(remove,1),0))return false;
        if(!HandlerFlow.onlyParameter(inner.returned(),1)||inner.reaches(0,i->i.getOpcode()==ARETURN,identity(remove)))return false;
        HandlerFlow flow=HandlerFlow.of(n.name,m);if(flow==null||!flow.acyclic())return false;int[] slot=slots(m);
        InvokeDynamicInsnNode link=singleIndy(m);
        MethodInsnNode then=one(flow.calls(c->c.owner.equals("java/util/concurrent/CompletableFuture")&&c.name.equals("thenApply")&&c.desc.equals("(Ljava/util/function/Function;)Ljava/util/concurrent/CompletableFuture;")));
        MethodInsnNode call=one(flow.calls(c->c.owner.equals(OP)&&c.name.equals("call")));
        if(link==null||then==null||call==null||!flow.onlyCalls(c->c==then||c==call,d->d==link))return false;
        if(Type.getArgumentTypes(link.desc).length!=1||!HandlerFlow.onlyParameter(flow.operand(link,0,1),slot[4]))return false;
        if(!HandlerFlow.onlyParameter(flow.argument(then,0),slot[0])||HandlerFlow.only(flow.argument(then,1))!=link)return false;
        if(!flow.passesArray(call,slot[3],List.of(o->HandlerFlow.only(o)==then,o->HandlerFlow.onlyParameter(o,slot[1]),o->HandlerFlow.onlyParameter(o,slot[2]))))return false;
        return HandlerFlow.only(flow.returned())==call;
    }
    /**
     * The map consumer, read by data flow: on every path it calls {@code replaceAll} on its map with one lambda capturing
     * the ops, and nothing else. The lambda returns {@code value(element, id, ops)} of the one value callback and calls
     * nothing else.
     */
    private static boolean mapConsumer(ClassNode n,MethodNode m,MethodNode value){
        if(!m.tryCatchBlocks.isEmpty()||!m.desc.equals(ALL_DESC.substring(0,ALL_DESC.length()-2)+"Ljava/util/Map;)V"))return false;
        InvokeDynamicInsnNode d=singleIndy(m);MethodNode lambda=lambdaOf(n,d);if(lambda==null||!lambda.tryCatchBlocks.isEmpty()||Type.getArgumentTypes(lambda.desc).length!=3)return false;
        HandlerFlow inner=HandlerFlow.of(n.name,lambda);if(inner==null||!inner.acyclic())return false;
        MethodInsnNode apply=one(inner.calls(c->c.getOpcode()==INVOKESTATIC&&c.owner.equals(n.name)&&c.name.equals(value.name)&&c.desc.equals(value.desc)));
        if(apply==null||!inner.onlyCalls(c->c==apply)||!HandlerFlow.onlyParameter(inner.argument(apply,0),2)||!HandlerFlow.onlyParameter(inner.argument(apply,1),1)||!HandlerFlow.onlyParameter(inner.argument(apply,2),0)||HandlerFlow.only(inner.returned())!=apply)return false;
        HandlerFlow flow=HandlerFlow.of(n.name,m);if(flow==null||!flow.acyclic())return false;int[] slot=slots(m);
        MethodInsnNode replace=one(flow.calls(c->c.owner.equals("java/util/Map")&&c.name.equals("replaceAll")&&c.desc.equals("(Ljava/util/function/BiFunction;)V")));
        if(replace==null||!flow.onlyCalls(c->c==replace,x->x==d)||Type.getArgumentTypes(d.desc).length!=1||!HandlerFlow.onlyParameter(flow.operand(d,0,1),slot[2]))return false;
        if(!HandlerFlow.onlyParameter(flow.argument(replace,0),slot[4])||HandlerFlow.only(flow.argument(replace,1))!=d)return false;
        return !flow.reaches(0,i->i.getOpcode()==RETURN,identity(replace));
    }
    /** The value callback the consumer's lambda applies: the one static method of {@code n} of the value descriptor it calls. */
    private static MethodNode applied(ClassNode n,MethodNode consume){
        MethodNode lambda=lambdaOf(n,singleIndy(consume));if(lambda==null)return null;MethodInsnNode found=null;
        for(AbstractInsnNode i:lambda.instructions)if(i instanceof MethodInsnNode c&&c.owner.equals(n.name)&&c.desc.equals(VALUE_DESC)){if(found!=null)return null;found=c;}
        MethodInsnNode call=found;return call==null||call.getOpcode()!=INVOKESTATIC?null:single(n.methods,x->x.name.equals(call.name)&&x.desc.equals(call.desc));
    }
    /** The static method of {@code n} a lambda links to; null for none, another owner, or anything but a static handle. */
    private static MethodNode lambdaOf(ClassNode n,InvokeDynamicInsnNode d){Handle h=d==null?null:implementation(d);return h==null||!h.getOwner().equals(n.name)||h.getTag()!=H_INVOKESTATIC?null:single(n.methods,x->x.name.equals(h.getName())&&x.desc.equals(h.getDesc()));}
    /**
     * The value callback's memo lookups {@code MEMO.get(ops)}, however many: each one's receiver pushed straight by a read of
     * the memo and its key straight by the ops parameter, each read by nothing else (not even a copy); every read of the memo
     * and every load of the ops slot is one of those, and the slot is never written. Null otherwise, or for none.
     */
    private static List<MethodInsnNode> providerReads(MethodNode m,String owner,FieldNode memo){
        HandlerFlow flow=HandlerFlow.of(owner,m);if(flow==null)return null;int loads=0,reads=0;
        for(AbstractInsnNode x:m.instructions){
            if(x instanceof VarInsnNode v&&v.var==2){if(v.getOpcode()!=ALOAD)return null;loads++;}
            if(x instanceof IincInsnNode i&&i.var==2)return null;
            if(x instanceof FieldInsnNode f&&f.owner.equals(owner)&&f.name.equals(memo.name)&&f.desc.equals(memo.desc))reads++;
        }
        List<MethodInsnNode> gets=flow.calls(c->c.getOpcode()==INVOKEVIRTUAL&&c.owner.equals("java/util/WeakHashMap")&&c.name.equals("get")&&c.desc.equals("(Ljava/lang/Object;)Ljava/lang/Object;"));
        if(gets.isEmpty()||loads!=gets.size()||reads!=gets.size())return null;
        for(MethodInsnNode get:gets){
            AbstractInsnNode receiver=flow.producer(get,0,2),key=flow.producer(get,1,2);
            if(!(receiver instanceof FieldInsnNode f&&f.getOpcode()==GETSTATIC&&f.owner.equals(owner)&&f.name.equals(memo.name)&&f.desc.equals(memo.desc))||!(key instanceof VarInsnNode k&&k.getOpcode()==ALOAD&&k.var==2))return null;
            if(flow.directUses(receiver)!=1||flow.directUses(key)!=1)return null;
        }
        return gets;
    }
    /** The field the producer's {@code WeakHashMap.put} is called on: a private static final WeakHashMap of the class, or null. */
    private static FieldNode memoOf(ClassNode n,MethodNode producer){
        HandlerFlow flow=HandlerFlow.of(n.name,producer);if(flow==null)return null;
        MethodInsnNode put=one(flow.calls(c->c.owner.equals("java/util/WeakHashMap")&&c.name.equals("put")&&c.desc.equals("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")));
        if(put==null||!(HandlerFlow.only(flow.argument(put,0))instanceof FieldInsnNode read)||read.getOpcode()!=GETSTATIC||!read.owner.equals(n.name))return null;
        FieldNode memo=n.fields.stream().filter(f->f.name.equals(read.name)&&f.desc.equals(read.desc)).findFirst().orElse(null);
        return memo!=null&&memo.desc.equals("Ljava/util/WeakHashMap;")&&(memo.access&(ACC_PRIVATE|ACC_STATIC|ACC_FINAL))==(ACC_PRIVATE|ACC_STATIC|ACC_FINAL)?memo:null;
    }
    /** The class initializer stores one new {@code WeakHashMap} into the memo on every path (whatever else it does to other fields). */
    private static boolean memoStored(ClassNode n,FieldNode f){
        MethodNode m=single(n.methods,x->x.name.equals("<clinit>"));if(m==null||!m.tryCatchBlocks.isEmpty())return false;
        HandlerFlow flow=HandlerFlow.of(n.name,m);if(flow==null||!flow.acyclic())return false;
        FieldInsnNode store=null;
        for(AbstractInsnNode i:m.instructions)if(i instanceof FieldInsnNode put&&put.getOpcode()==PUTSTATIC&&put.owner.equals(n.name)&&put.name.equals(f.name)&&put.desc.equals(f.desc)){if(store!=null)return false;store=put;}
        if(store==null||!(HandlerFlow.only(flow.operand(store,0,1))instanceof TypeInsnNode created)||created.getOpcode()!=NEW||!created.desc.equals("java/util/WeakHashMap"))return false;
        FieldInsnNode stored=store;return !flow.reaches(0,i->i.getOpcode()==RETURN,identity(stored));
    }
    private static boolean allContext(MethodNode m){
        List<AbstractInsnNode> c=code(m);if(c.size()<9)return false;
        if(!(c.get(0) instanceof VarInsnNode firstArg&&firstArg.var==0&&firstArg.getOpcode()==ALOAD)||!(c.get(1) instanceof FieldInsnNode f&&f.getOpcode()==GETSTATIC&&f.owner.equals(DATA)&&f.name.equals("TABLE"))||!(c.get(2) instanceof JumpInsnNode j&&j.getOpcode()==IF_ACMPEQ)||c.get(3).getOpcode()!=RETURN)return false;
        int ci=0,type=0;for(AbstractInsnNode i:c){if(i instanceof VarInsnNode v){if(v.var==2)return false;if(v.var==0){if(v.getOpcode()!=ALOAD)return false;type++;}if(v.var==3){if(v.getOpcode()!=ALOAD)return false;ci++;}}if(i instanceof MethodInsnNode call&&call.owner.equals(CIR)&&(!call.name.equals("getReturnValue")||!call.desc.equals("()Ljava/lang/Object;")))return false;}
        return ci==1&&type==1&&count(m,CIR,"getReturnValue")==1;
    }
    /** The class initializer stores one new {@code WeakHashMap} into the memo on every path, and does nothing else. */
    private static boolean memoInitializer(ClassNode n,FieldNode f){
        MethodNode m=single(n.methods,x->x.name.equals("<clinit>"));if(m==null||!m.tryCatchBlocks.isEmpty())return false;
        HandlerFlow flow=HandlerFlow.of(n.name,m);if(flow==null||!flow.acyclic())return false;
        MethodInsnNode init=one(flow.calls(c->c.getOpcode()==INVOKESPECIAL&&c.owner.equals("java/util/WeakHashMap")&&c.name.equals("<init>")&&c.desc.equals("()V")));
        if(init==null||flow.calls(c->true).size()!=1||!(HandlerFlow.only(flow.argument(init,0))instanceof TypeInsnNode created)||created.getOpcode()!=NEW||!created.desc.equals("java/util/WeakHashMap"))return false;
        FieldInsnNode store=null;
        for(AbstractInsnNode i:m.instructions){
            int opcode=i.getOpcode();if(i instanceof InvokeDynamicInsnNode||opcode==PUTFIELD||opcode>=IASTORE&&opcode<=SASTORE||opcode==ATHROW||opcode==MONITORENTER||opcode==MONITOREXIT)return false;
            if(i instanceof FieldInsnNode put&&opcode==PUTSTATIC){if(store!=null||!put.owner.equals(n.name)||!put.name.equals(f.name)||!put.desc.equals(f.desc))return false;store=put;}
        }
        FieldInsnNode stored=store;return stored!=null&&HandlerFlow.only(flow.operand(stored,0,1))==created&&!flow.reaches(0,i->i.getOpcode()==RETURN,identity(stored));
    }
    /** The constructor calls {@code Object.<init>} on itself on every path, and does nothing else. */
    private static boolean defaultConstructor(ClassNode n,MethodNode m){
        if(m==null||!m.desc.equals("()V")||!m.tryCatchBlocks.isEmpty())return false;HandlerFlow flow=HandlerFlow.of(n.name,m);if(flow==null||!flow.acyclic())return false;
        MethodInsnNode parent=one(flow.calls(c->c.getOpcode()==INVOKESPECIAL&&c.owner.equals("java/lang/Object")&&c.name.equals("<init>")&&c.desc.equals("()V")));
        return parent!=null&&flow.onlyCalls(c->c==parent)&&HandlerFlow.onlyParameter(flow.argument(parent,0),0)&&!flow.reaches(0,i->i.getOpcode()==RETURN,identity(parent));
    }
    private static MethodInsnNode one(List<MethodInsnNode> calls){return calls.size()==1?calls.getFirst():null;}
    private static boolean memo(Set<AbstractInsnNode> origins,ClassNode n,FieldNode f){return HandlerFlow.only(origins)instanceof FieldInsnNode read&&read.getOpcode()==GETSTATIC&&read.owner.equals(n.name)&&read.name.equals(f.name)&&read.desc.equals(f.desc);}
    private static Set<AbstractInsnNode> identity(AbstractInsnNode instruction){Set<AbstractInsnNode> set=Collections.newSetFromMap(new IdentityHashMap<>());set.add(instruction);return set;}
    /** Each parameter's local slot. */
    private static int[] slots(MethodNode m){Type[] args=Type.getArgumentTypes(m.desc);int[] out=new int[args.length];int next=(m.access&ACC_STATIC)!=0?0:1;for(int i=0;i<args.length;i++){out[i]=next;next+=args[i].getSize();}return out;}
    private static Set<MethodNode> closure(ClassNode n,Set<MethodNode> roots){
        Set<MethodNode> out=new LinkedHashSet<>();Deque<MethodNode> q=new ArrayDeque<>(roots);while(!q.isEmpty()){MethodNode m=q.removeFirst();if(!out.add(m))continue;for(AbstractInsnNode i:m.instructions){
            if(i instanceof MethodInsnNode c&&c.owner.equals(n.name)){if(c.getOpcode()!=INVOKESTATIC)return null;MethodNode next=single(n.methods,x->x.name.equals(c.name)&&x.desc.equals(c.desc));if(next==null)return null;q.add(next);}
            if(i instanceof InvokeDynamicInsnNode d){if(!d.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")||!d.bsm.getName().equals("metafactory"))return null;Handle h=implementation(d);if(h==null)return null;if(h.getOwner().equals(n.name)){if(h.getTag()!=H_INVOKESTATIC)return null;MethodNode next=single(n.methods,x->x.name.equals(h.getName())&&x.desc.equals(h.getDesc()));if(next==null)return null;q.add(next);}}
        }}return out;
    }
    private static MethodNode copy(MethodNode original){MethodNode m=new MethodNode(original.access,original.name,original.desc,original.signature,original.exceptions.toArray(String[]::new));original.accept(m);for(AbstractInsnNode i:m.instructions)if(i instanceof InvokeDynamicInsnNode d)d.bsmArgs=d.bsmArgs.clone();return m;}
    private static void stripAnnotations(MethodNode m){m.visibleAnnotations=null;m.invisibleAnnotations=null;m.visibleParameterAnnotations=null;m.invisibleParameterAnnotations=null;m.visibleTypeAnnotations=null;m.invisibleTypeAnnotations=null;}
    private static List<AbstractInsnNode> code(MethodNode m){List<AbstractInsnNode> c=new ArrayList<>();for(AbstractInsnNode i:m.instructions)if(i.getOpcode()>=0)c.add(i);return c;}
    private static MethodNode single(Collection<MethodNode> methods,java.util.function.Predicate<MethodNode> p){MethodNode found=null;for(MethodNode m:methods)if(p.test(m)){if(found!=null)return null;found=m;}return found;}
    private static int count(MethodNode m,String owner,String name){int c=0;for(AbstractInsnNode i:m.instructions)if(i instanceof MethodInsnNode x&&x.owner.equals(owner)&&x.name.equals(name))c++;return c;}
    private static InvokeDynamicInsnNode singleIndy(MethodNode m){InvokeDynamicInsnNode found=null;for(AbstractInsnNode i:m.instructions)if(i instanceof InvokeDynamicInsnNode d){if(found!=null)return null;found=d;}return found;}
    private static Handle implementation(InvokeDynamicInsnNode d){return d.bsmArgs.length==3&&d.bsmArgs[1] instanceof Handle h?h:null;}
    private static List<AnnotationNode> annotations(MethodNode m){List<AnnotationNode> a=new ArrayList<>();if(m.visibleAnnotations!=null)a.addAll(m.visibleAnnotations);if(m.invisibleAnnotations!=null)a.addAll(m.invisibleAnnotations);return a;}
    private static boolean injector(MethodNode m){return annotations(m).stream().anyMatch(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")||a.desc.equals("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;"));}
    private static Object field(AnnotationNode a,String name){if(a.values!=null)for(int i=0;i<a.values.size();i+=2)if(name.equals(a.values.get(i)))return a.values.get(i+1);return null;}
    private static AnnotationNode injection(MethodNode m){return annotations(m).stream().filter(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")||a.desc.equals("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;")).findFirst().orElse(null);}
    private static String selector(MethodNode m){Object value=field(injection(m),"method");return value instanceof List<?> list&&list.size()==1&&list.getFirst() instanceof String name?name:null;}
    private static String localName(MethodNode m,int parameter){List<AnnotationNode> list=new ArrayList<>();if(m.visibleParameterAnnotations!=null&&m.visibleParameterAnnotations.length>parameter&&m.visibleParameterAnnotations[parameter]!=null)list.addAll(m.visibleParameterAnnotations[parameter]);if(m.invisibleParameterAnnotations!=null&&m.invisibleParameterAnnotations.length>parameter&&m.invisibleParameterAnnotations[parameter]!=null)list.addAll(m.invisibleParameterAnnotations[parameter]);List<AnnotationNode> locals=list.stream().filter(a->a.desc.equals("Lcom/llamalad7/mixinextras/sugar/Local;")).toList();if(locals.size()!=1)return null;AnnotationNode local=locals.getFirst();if(local.values==null||local.values.size()!=2||!local.values.getFirst().equals("name"))return null;Object names=field(local,"name");return names instanceof List<?> l&&l.size()==1&&l.getFirst() instanceof String name?name:null;}
    private static boolean anchor(MethodNode m,String kind,String target){Object raw=field(injection(m),"at");AnnotationNode at=raw instanceof AnnotationNode a?a:raw instanceof List<?> l&&l.size()==1&&l.getFirst() instanceof AnnotationNode a?a:null;return at!=null&&kind.equals(field(at,"value"))&&(target==null?field(at,"target")==null:field(at,"target")instanceof String written&&net.forbric.kernel.mixin.MixinFit.sameMember(target,written))&&field(at,"ordinal")==null&&field(at,"shift")==null&&field(at,"args")==null;}
    private static String target(ClassNode n){List<AnnotationNode> all=new ArrayList<>();if(n.visibleAnnotations!=null)all.addAll(n.visibleAnnotations);if(n.invisibleAnnotations!=null)all.addAll(n.invisibleAnnotations);for(AnnotationNode a:all)if(a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")){Object v=field(a,"value");if(v instanceof List<?> l&&l.size()==1&&l.getFirst() instanceof Type t)return t.getInternalName();}return null;}
    private static String digest(String value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
}
