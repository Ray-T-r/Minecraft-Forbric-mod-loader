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
import net.forbric.kernel.mixin.MixinInstructionFingerprint;

/** A closed private ops/provider memo permits projecting the original callbacks onto the typed reload seam.
 * Callback bodies, event invoker reads, exception regions and holder iteration are copied, never reimplemented. */
public final class LootSourceCallbacks implements Opcodes {
    private static final String OPS="net/minecraft/resources/RegistryOps", PROVIDER="net/minecraft/core/HolderLookup$Provider";
    private static final String ID="net/minecraft/resources/Identifier", TABLE="net/minecraft/world/level/storage/loot/LootTable";
    private static final String DATA="net/minecraft/world/level/storage/loot/LootDataType", RESOURCES="net/minecraft/server/packs/resources/ResourceManager";
    private static final String REGISTRY="net/minecraft/core/Registry", CIR="org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
    private static final String EVENTS="net/fabricmc/fabric/api/loot/v3/LootTableEvents", OP="com/llamalad7/mixinextras/injector/wrapoperation/Operation";
    private static final String VALUE_DESC="(Ljava/lang/Object;L"+ID+";L"+OPS+";)Ljava/lang/Object;";
    private static final String ALL_DESC="(L"+DATA+";L"+RESOURCES+";L"+OPS+";L"+CIR+";)V";
    private static volatile Plan offered;
    private static volatile ClassLoader gameLoader;
    private LootSourceCallbacks() {}

    /** The only rewritten expression is the unique private WeakHashMap.get(ops) provider projection. */
    public record Plan(String sourceName,String targetName,String helperName,byte[] helperBytes,Map<String,String> helperMethods, NativeInputs nativeInputs) {}
    public record NativeInputs(String producerSelector,String consumerSelector,String opsLocal,String elementsLocal) {}
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
        if(input.fields.size()!=1)return null;FieldNode memo=input.fields.getFirst();
        if(!memo.desc.equals("Ljava/util/WeakHashMap;")||(memo.access&(ACC_PRIVATE|ACC_STATIC|ACC_FINAL))!=(ACC_PRIVATE|ACC_STATIC|ACC_FINAL))return null;
        List<MethodNode> injectors=input.methods.stream().filter(LootSourceCallbacks::injector).toList();if(injectors.size()!=4)return null;
        MethodNode value=single(input.methods,m->m.desc.equals(VALUE_DESC)&&event(m,"REPLACE")&&event(m,"MODIFY"));
        MethodNode all=single(injectors,m->m.desc.equals(ALL_DESC)&&event(m,"ALL_LOADED"));if(value==null||all==null)return null;
        if((value.access&ACC_STATIC)==0||(all.access&ACC_STATIC)==0||!all.tryCatchBlocks.isEmpty())return null;
        MethodNode put=single(injectors,m->count(m,"java/util/WeakHashMap","put")==1);
        MethodNode consume=single(injectors,m->count(m,"java/util/Map","replaceAll")==1);
        MethodNode remove=single(injectors,m->count(m,"java/util/concurrent/CompletableFuture","thenApply")==1);
        if(put==null||consume==null||remove==null||Set.of(put,consume,remove,all).size()!=4||!sameSelector(put,remove))return null;
        NativeInputs inputs=new NativeInputs(selector(put),selector(consume),localName(remove,4),localName(consume,4));if(inputs.producerSelector==null||inputs.consumerSelector==null||inputs.opsLocal==null||inputs.elementsLocal==null)return null;
        if(!memoProducer(input,put,memo)||!memoCleanup(input,remove,memo)||!mapConsumer(input,consume,value))return null;
        if(!allContext(all)||!sameSelector(consume,all)||!anchor(consume,"INVOKE","Ljava/util/Map;forEach(Ljava/util/function/BiConsumer;)V")||!anchor(all,"RETURN",null))return null;
        if(!anchor(put,"INVOKE","L"+PROVIDER+";createSerializationContext(Lcom/mojang/serialization/DynamicOps;)L"+OPS+";")
                ||!anchor(remove,"INVOKE","Ljava/util/concurrent/CompletableFuture;thenApplyAsync(Ljava/util/function/Function;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))return null;
        List<AbstractInsnNode> expr=providerRead(value,input.name,memo);if(expr==null)return null;
        int accesses=0;for(MethodNode m:input.methods)for(AbstractInsnNode i:m.instructions){
            if(i instanceof FieldInsnNode f&&f.owner.equals(input.name)){
                if(!f.name.equals(memo.name)||!f.desc.equals(memo.desc))return null;accesses++;
                if(!(m==value||m==put||m.name.equals("<clinit>")||cleanupLambda(input,remove)==m))return null;
            }
            if(i instanceof InvokeDynamicInsnNode d)for(Object a:d.bsmArgs)if(a instanceof Handle h&&h.getOwner().equals(input.name)&&h.getTag()<=H_PUTSTATIC)return null;
        }
        if(accesses!=4||!memoInitializer(input,memo))return null;
        MethodNode constructor=single(input.methods,m->m.name.equals("<init>"));
        if(constructor==null||!tokens(constructor).equals(List.of("v25:0","m183:java/lang/Object.<init>()V","o177")))return null;
        Set<MethodNode> closure=closure(input,Set.of(value,all));if(closure==null)return null;
        Set<MethodNode> classified=new HashSet<>(closure);classified.addAll(injectors);classified.add(constructor);classified.add(cleanupLambda(input,remove));InvokeDynamicInsnNode mapIndy=singleIndy(consume);Handle mapHandle=implementation(mapIndy);classified.add(single(input.methods,m->m.name.equals(mapHandle.getName())&&m.desc.equals(mapHandle.getDesc())));
        for(MethodNode m:input.methods)if(!m.name.equals("<clinit>")&&!classified.contains(m))return null;
        for(MethodNode m:closure){if((m.access&ACC_STATIC)==0)return null;for(AbstractInsnNode i:m.instructions)if(i instanceof FieldInsnNode f&&f.owner.equals(input.name)&&m!=value)return null;}

        ClassNode generated=new ClassNode();generated.version=input.version;generated.access=ACC_PUBLIC|ACC_FINAL|ACC_SUPER;generated.name=input.name;generated.superName="java/lang/Object";
        for(MethodNode original:closure){MethodNode copy=copy(original);stripAnnotations(copy);generated.methods.add(copy);}
        MethodNode moved=single(generated.methods,m->m.name.equals(value.name)&&m.desc.equals(value.desc));
        List<AbstractInsnNode> projected=providerRead(moved,input.name,memo);AbstractInsnNode first=projected.getFirst();moved.instructions.insertBefore(first,new VarInsnNode(ALOAD,2));
        for(AbstractInsnNode instruction:projected)moved.instructions.remove(instruction);
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
        return new Plan(input.name.replace('/','.'),target.replace('/','.'),helper,bytes,Map.copyOf(methods),inputs);
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
    private static boolean memoProducer(ClassNode n,MethodNode m,FieldNode f){
        if(!m.tryCatchBlocks.isEmpty()||!m.desc.equals("(L"+PROVIDER+";Lcom/mojang/serialization/DynamicOps;L"+OP+";)L"+OPS+";"))return false;
        return tokens(m).equals(List.of("v25:2","o5","t189:java/lang/Object","o89","o3","v25:0","o83","o89","o4","v25:1","o83","m185:"+OP+".call([Ljava/lang/Object;)Ljava/lang/Object;","t192:"+OPS,"v58:3","f178:"+n.name+"."+f.name+f.desc,"v25:3","v25:0","m182:java/util/WeakHashMap.put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;","o87","v25:3","o176"));
    }
    private static MethodNode cleanupLambda(ClassNode n,MethodNode m){InvokeDynamicInsnNode d=singleIndy(m);if(d==null)return null;Handle h=implementation(d);return h==null||!h.getOwner().equals(n.name)?null:single(n.methods,x->x.name.equals(h.getName())&&x.desc.equals(h.getDesc()));}
    private static boolean memoCleanup(ClassNode n,MethodNode m,FieldNode f){
        if(!m.tryCatchBlocks.isEmpty()||!m.desc.equals("(Ljava/util/concurrent/CompletableFuture;Ljava/util/function/Function;Ljava/util/concurrent/Executor;L"+OP+";L"+OPS+";)Ljava/util/concurrent/CompletableFuture;"))return false;
        MethodNode lambda=cleanupLambda(n,m);if(lambda==null||!lambda.tryCatchBlocks.isEmpty()||!lambda.desc.equals("(L"+OPS+";Ljava/util/List;)Ljava/util/List;"))return false;
        if(!tokens(lambda).equals(List.of("f178:"+n.name+"."+f.name+f.desc,"v25:0","m182:java/util/WeakHashMap.remove(Ljava/lang/Object;)Ljava/lang/Object;","o87","v25:1","o176")))return false;
        return tokens(m).equals(List.of("v25:3","o6","t189:java/lang/Object","o89","o3","v25:0","v25:4","d:(L"+OPS+";)Ljava/util/function/Function;","m182:java/util/concurrent/CompletableFuture.thenApply(Ljava/util/function/Function;)Ljava/util/concurrent/CompletableFuture;","o83","o89","o4","v25:1","o83","o89","o5","v25:2","o83","m185:"+OP+".call([Ljava/lang/Object;)Ljava/lang/Object;","t192:java/util/concurrent/CompletableFuture","o176"));
    }
    private static boolean mapConsumer(ClassNode n,MethodNode m,MethodNode value){
        if(!m.tryCatchBlocks.isEmpty()||!m.desc.equals(ALL_DESC.substring(0,ALL_DESC.length()-2)+"Ljava/util/Map;)V"))return false;
        InvokeDynamicInsnNode d=singleIndy(m);Handle h=d==null?null:implementation(d);if(h==null||!h.getOwner().equals(n.name))return false;
        MethodNode lambda=single(n.methods,x->x.name.equals(h.getName())&&x.desc.equals(h.getDesc()));
        if(lambda==null||!lambda.tryCatchBlocks.isEmpty()||!tokens(lambda).equals(List.of("v25:2","v25:1","v25:0","m184:"+n.name+"."+value.name+value.desc,"t192:net/minecraft/world/level/storage/loot/Validatable","o176")))return false;
        return tokens(m).equals(List.of("v25:4","v25:2","d:(L"+OPS+";)Ljava/util/function/BiFunction;","m185:java/util/Map.replaceAll(Ljava/util/function/BiFunction;)V","o177"));
    }
    private static List<AbstractInsnNode> providerRead(MethodNode m,String owner,FieldNode memo){
        List<AbstractInsnNode> code=code(m);List<AbstractInsnNode> result=null;int loads=0;
        for(int i=0;i<code.size();i++){AbstractInsnNode x=code.get(i);if(x instanceof VarInsnNode v&&v.var==2){if(v.getOpcode()!=ALOAD)return null;loads++;}
            if(x instanceof FieldInsnNode f&&f.owner.equals(owner)&&f.name.equals(memo.name)){
                if(result!=null||i+3>=code.size()||f.getOpcode()!=GETSTATIC)return null;
                if(!(code.get(i+1) instanceof VarInsnNode v&&v.getOpcode()==ALOAD&&v.var==2)||!(code.get(i+2) instanceof MethodInsnNode c&&c.getOpcode()==INVOKEVIRTUAL&&c.owner.equals("java/util/WeakHashMap")&&c.name.equals("get")&&c.desc.equals("(Ljava/lang/Object;)Ljava/lang/Object;"))||!(code.get(i+3) instanceof TypeInsnNode t&&t.getOpcode()==CHECKCAST&&t.desc.equals(PROVIDER)))return null;
                result=new ArrayList<>(code.subList(i,i+4));
            }
        }return loads==1?result:null;
    }
    private static boolean allContext(MethodNode m){
        List<AbstractInsnNode> c=code(m);if(c.size()<9)return false;
        if(!(c.get(0) instanceof VarInsnNode firstArg&&firstArg.var==0&&firstArg.getOpcode()==ALOAD)||!(c.get(1) instanceof FieldInsnNode f&&f.getOpcode()==GETSTATIC&&f.owner.equals(DATA)&&f.name.equals("TABLE"))||!(c.get(2) instanceof JumpInsnNode j&&j.getOpcode()==IF_ACMPEQ)||c.get(3).getOpcode()!=RETURN)return false;
        int ci=0,type=0;for(AbstractInsnNode i:c){if(i instanceof VarInsnNode v){if(v.var==2)return false;if(v.var==0){if(v.getOpcode()!=ALOAD)return false;type++;}if(v.var==3){if(v.getOpcode()!=ALOAD)return false;ci++;}}if(i instanceof MethodInsnNode call&&call.owner.equals(CIR)&&(!call.name.equals("getReturnValue")||!call.desc.equals("()Ljava/lang/Object;")))return false;}
        return ci==1&&type==1&&count(m,CIR,"getReturnValue")==1;
    }
    private static boolean memoInitializer(ClassNode n,FieldNode f){MethodNode m=single(n.methods,x->x.name.equals("<clinit>"));return m!=null&&m.tryCatchBlocks.isEmpty()&&tokens(m).equals(List.of("t187:java/util/WeakHashMap","o89","m183:java/util/WeakHashMap.<init>()V","f179:"+n.name+"."+f.name+f.desc,"o177"));}
    private static Set<MethodNode> closure(ClassNode n,Set<MethodNode> roots){
        Set<MethodNode> out=new LinkedHashSet<>();Deque<MethodNode> q=new ArrayDeque<>(roots);while(!q.isEmpty()){MethodNode m=q.removeFirst();if(!out.add(m))continue;for(AbstractInsnNode i:m.instructions){
            if(i instanceof MethodInsnNode c&&c.owner.equals(n.name)){if(c.getOpcode()!=INVOKESTATIC)return null;MethodNode next=single(n.methods,x->x.name.equals(c.name)&&x.desc.equals(c.desc));if(next==null)return null;q.add(next);}
            if(i instanceof InvokeDynamicInsnNode d){if(!d.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")||!d.bsm.getName().equals("metafactory"))return null;Handle h=implementation(d);if(h==null)return null;if(h.getOwner().equals(n.name)){if(h.getTag()!=H_INVOKESTATIC)return null;MethodNode next=single(n.methods,x->x.name.equals(h.getName())&&x.desc.equals(h.getDesc()));if(next==null)return null;q.add(next);}}
        }}return out;
    }
    private static MethodNode copy(MethodNode original){MethodNode m=new MethodNode(original.access,original.name,original.desc,original.signature,original.exceptions.toArray(String[]::new));original.accept(m);for(AbstractInsnNode i:m.instructions)if(i instanceof InvokeDynamicInsnNode d)d.bsmArgs=d.bsmArgs.clone();return m;}
    private static void stripAnnotations(MethodNode m){m.visibleAnnotations=null;m.invisibleAnnotations=null;m.visibleParameterAnnotations=null;m.invisibleParameterAnnotations=null;m.visibleTypeAnnotations=null;m.invisibleTypeAnnotations=null;}
    private static List<AbstractInsnNode> code(MethodNode m){List<AbstractInsnNode> c=new ArrayList<>();for(AbstractInsnNode i:m.instructions)if(i.getOpcode()>=0)c.add(i);return c;}
    private static List<String> tokens(MethodNode m){List<String> s=new ArrayList<>();for(AbstractInsnNode i:code(m)){String t="o"+i.getOpcode();if(i instanceof VarInsnNode v)t="v"+v.getOpcode()+":"+v.var;else if(i instanceof MethodInsnNode c)t="m"+c.getOpcode()+":"+c.owner+"."+c.name+c.desc;else if(i instanceof FieldInsnNode f)t="f"+f.getOpcode()+":"+f.owner+"."+f.name+f.desc;else if(i instanceof TypeInsnNode x)t="t"+x.getOpcode()+":"+x.desc;else if(i instanceof InvokeDynamicInsnNode d)t="d:"+d.desc;s.add(t);}return s;}
    private static MethodNode single(Collection<MethodNode> methods,java.util.function.Predicate<MethodNode> p){MethodNode found=null;for(MethodNode m:methods)if(p.test(m)){if(found!=null)return null;found=m;}return found;}
    private static int count(MethodNode m,String owner,String name){int c=0;for(AbstractInsnNode i:m.instructions)if(i instanceof MethodInsnNode x&&x.owner.equals(owner)&&x.name.equals(name))c++;return c;}
    private static boolean event(MethodNode m,String field){for(AbstractInsnNode i:m.instructions)if(i instanceof FieldInsnNode f&&f.getOpcode()==GETSTATIC&&f.owner.equals(EVENTS)&&f.name.equals(field))return true;return false;}
    private static InvokeDynamicInsnNode singleIndy(MethodNode m){InvokeDynamicInsnNode found=null;for(AbstractInsnNode i:m.instructions)if(i instanceof InvokeDynamicInsnNode d){if(found!=null)return null;found=d;}return found;}
    private static Handle implementation(InvokeDynamicInsnNode d){return d.bsmArgs.length==3&&d.bsmArgs[1] instanceof Handle h?h:null;}
    private static List<AnnotationNode> annotations(MethodNode m){List<AnnotationNode> a=new ArrayList<>();if(m.visibleAnnotations!=null)a.addAll(m.visibleAnnotations);if(m.invisibleAnnotations!=null)a.addAll(m.invisibleAnnotations);return a;}
    private static boolean injector(MethodNode m){return annotations(m).stream().anyMatch(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")||a.desc.equals("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;"));}
    private static Object field(AnnotationNode a,String name){if(a.values!=null)for(int i=0;i<a.values.size();i+=2)if(name.equals(a.values.get(i)))return a.values.get(i+1);return null;}
    private static AnnotationNode injection(MethodNode m){return annotations(m).stream().filter(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")||a.desc.equals("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;")).findFirst().orElse(null);}
    private static String selector(MethodNode m){Object value=field(injection(m),"method");return value instanceof List<?> list&&list.size()==1&&list.getFirst() instanceof String name?name:null;}
    private static String localName(MethodNode m,int parameter){List<AnnotationNode> list=new ArrayList<>();if(m.visibleParameterAnnotations!=null&&m.visibleParameterAnnotations.length>parameter&&m.visibleParameterAnnotations[parameter]!=null)list.addAll(m.visibleParameterAnnotations[parameter]);if(m.invisibleParameterAnnotations!=null&&m.invisibleParameterAnnotations.length>parameter&&m.invisibleParameterAnnotations[parameter]!=null)list.addAll(m.invisibleParameterAnnotations[parameter]);List<AnnotationNode> locals=list.stream().filter(a->a.desc.equals("Lcom/llamalad7/mixinextras/sugar/Local;")).toList();if(locals.size()!=1)return null;AnnotationNode local=locals.getFirst();if(local.values==null||local.values.size()!=2||!local.values.getFirst().equals("name"))return null;Object names=field(local,"name");return names instanceof List<?> l&&l.size()==1&&l.getFirst() instanceof String name?name:null;}
    private static boolean sameSelector(MethodNode a,MethodNode b){return Objects.equals(field(injection(a),"method"),field(injection(b),"method"));}
    private static boolean anchor(MethodNode m,String kind,String target){Object raw=field(injection(m),"at");AnnotationNode at=raw instanceof AnnotationNode a?a:raw instanceof List<?> l&&l.size()==1&&l.getFirst() instanceof AnnotationNode a?a:null;return at!=null&&kind.equals(field(at,"value"))&&Objects.equals(target,field(at,"target"))&&field(at,"ordinal")==null&&field(at,"shift")==null&&field(at,"args")==null;}
    private static String target(ClassNode n){List<AnnotationNode> all=new ArrayList<>();if(n.visibleAnnotations!=null)all.addAll(n.visibleAnnotations);if(n.invisibleAnnotations!=null)all.addAll(n.invisibleAnnotations);for(AnnotationNode a:all)if(a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")){Object v=field(a,"value");if(v instanceof List<?> l&&l.size()==1&&l.getFirst() instanceof Type t)return t.getInternalName();}return null;}
    private static String digest(String value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
}
