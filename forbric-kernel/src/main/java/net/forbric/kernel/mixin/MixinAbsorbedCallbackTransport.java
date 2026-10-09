/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import java.util.function.Function;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import net.forbric.api.CallbackSeams;
import net.forbric.kernel.boot.DefinedMethodContracts;
import net.forbric.kernel.boot.DefinedMethodContracts.MethodContract;
import net.forbric.kernel.classloading.ForbricClassLoader;

/** Keeps an AFTER callback at the native operation, before the extracted helper's additional effects. */
public final class MixinAbsorbedCallbackTransport implements Opcodes {
    private static final String API = "net/forbric/api/CallbackSeams", OP = "com/llamalad7/mixinextras/injector/wrapoperation/Operation";
    private static final String CI = "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";
    private static final Handle LAMBDA = new Handle(H_INVOKESTATIC,"java/lang/invoke/LambdaMetafactory","metafactory",
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",false);
    private static final Map<ClassLoader,Map<String,Installed>> PLANS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final java.util.concurrent.atomic.AtomicLong ORDER = new java.util.concurrent.atomic.AtomicLong();
    private static final class Installed {
        final String key, source, injectorName, injectorDesc; final NativeCallbackSeam.Plan plan; final MethodNode handler, bridge, wrapper, originalHelper;
        final int priority,order;final long sequence=ORDER.getAndIncrement();
        volatile MethodContract helper; volatile List<MethodContract> host = List.of();
        /** What the final definitions say broke the witness, named when each class is defined; null while it holds. */
        volatile String helperDrift, hostDrift;
        Installed(String key,String source,String injectorName,String injectorDesc,NativeCallbackSeam.Plan plan,MethodNode handler,MethodNode bridge,MethodNode wrapper,MethodNode originalHelper,int priority,int order) {
            this.key=key;this.source=source;this.injectorName=injectorName;this.injectorDesc=injectorDesc;this.plan=plan;this.handler=copy(handler);this.bridge=copy(bridge);this.wrapper=copy(wrapper);
            this.originalHelper=copy(originalHelper);
            this.priority=priority;this.order=order;
        }
        boolean witnessed(ClassLoader loader) {
            return helper!=null && host.size()>=5 && DefinedMethodContracts.observed(loader,helper)
                && host.stream().allMatch(method->DefinedMethodContracts.observed(loader,method));
        }
        String helperName(){return plan.helper().replace('/','.')+"."+plan.helperMethod();}
        String site(){return plan.host().replace('/','.')+"."+plan.method();}
        /** The first failing conjunct of {@link #witnessed}, in its order; null when it holds. */
        String broken(ClassLoader loader) {
            if(helper==null)return "the carrier helper "+helperName()+" was not instrumented before its definition";
            if(!DefinedMethodContracts.observed(loader,helper))return helperDrift!=null?helperDrift:"the final body of "+helperName()+" is not the body Forbric instrumented";
            if(host.size()<5)return hostDrift!=null?hostDrift:"the transported callback's host "+site()+" is not proved";
            if(!host.stream().allMatch(method->DefinedMethodContracts.observed(loader,method)))return "the proved methods of "+site()+" were redefined";
            return null;
        }
        /** CallbackSeams' once-per-site report: the callback is skipped there, the carrier helper runs as written. */
        void declined(ClassLoader loader,String ignoredKey,String reason) {
            String broken=broken(loader),cause=broken==null?reason:broken;
            SeamDeclines.report(source,injectorName,injectorDesc,site(),"source callback skipped at "+site()+", whose call it follows the merged game moved into "
                +helperName()+": "+cause+"; the carrier's helper runs as written",List.of("transport=extracted callback","host="+site(),"helper="+helperName(),"declined="+reason,"cause="+cause));
        }
    }
    private MixinAbsorbedCallbackTransport() { }
    public static void release(ClassLoader loader) { PLANS.remove(loader);CallbackSeams.release(loader); }
    public static int adapt(ClassNode mixin, Function<String,ClassNode> current, Function<String,ClassNode> originals, ForbricClassLoader loader) {
        if (!MergedBaseAbsorbedCalls.enabled() || loader==null) return 0;
        List<String> targets=MixinFit.mixinTargets(mixin);if(targets.size()!=1)return 0;
        ClassNode source=originals.apply(targets.getFirst()), target=current.apply(targets.getFirst());if(source==null||target==null)return 0;
        Function<String,ClassNode> unwrapped=owner->unwrapped(loader,current.apply(owner));
        int changed=0;
        for(MethodNode handler:List.copyOf(mixin.methods)) {
            AnnotationNode annotation=MixinFit.injectorOf(handler);
            if(!closed(handler,annotation))continue;
            List<String> selectors=MixinFit.stringList(MixinFit.value(annotation,"method"));List<AnnotationNode> ats=MixinFit.atNodes(annotation);
            if(selectors.size()!=1||ats.size()!=1)continue;AnnotationNode at=ats.getFirst();
            if(!"INVOKE".equals(MixinFit.value(at,"value"))||!"AFTER".equals(MixinFit.asString(MixinFit.value(at,"shift")))
                || MixinFit.value(at,"ordinal") instanceof Integer ordinal&&(ordinal>0||ordinal< -1) || MixinFit.value(at,"args")!=null)continue;
            List<MethodNode> methods=source.methods.stream().filter(m->selectors.getFirst().equals(m.name)||selectors.getFirst().equals(m.name+m.desc)).toList();
            if(methods.size()!=1)continue;MethodNode nativeMethod=methods.getFirst();
            // A static callback may observe an instance host's arguments without capturing its receiver.
            if((nativeMethod.access&ACC_STATIC)!=0&&(handler.access&ACC_STATIC)==0)continue;
            Type[] nativeArgs=Type.getArgumentTypes(nativeMethod.desc), handlerArgs=Type.getArgumentTypes(handler.desc);
            boolean full=handlerArgs.length==nativeArgs.length+1&&Arrays.equals(nativeArgs,Arrays.copyOf(handlerArgs,handlerArgs.length-1));
            if(!full&&handlerArgs.length!=1||handlerArgs.length==0||!handlerArgs[handlerArgs.length-1].equals(Type.getObjectType(CI)))continue;
            NativeCallbackSeam.Plan plan=NativeCallbackSeam.derive(source,target,nativeMethod,MixinFit.asString(MixinFit.value(at,"target")),unwrapped);if(plan==null)continue;
            String key=mixin.name+":"+handler.name+":"+plan.host()+"."+plan.method()+":"+plan.helper()+"."+plan.helperMethod()+":"+plan.member()+":"+plan.sourcePrefix()+":"+MixinInstructionFingerprint.hash(handler)+":"+plan.helperHash();
            String originalName=handler.name, keptName=MixinHandlerShim.asideName(mixin.name,originalName,"$forbricsourcecallback");
            MethodNode retained=copy(handler);retained.name=keptName;removeInjector(retained);
            String id=MixinFit.asString(MixinFit.value(annotation,"id")),pointId=MixinFit.asString(MixinFit.value(at,"id"));
            id=(id==null||id.isEmpty()?nativeMethod.name:id)+(pointId==null||pointId.isEmpty()?"":":"+pointId);
            MethodNode bridge=bridge(mixin.name,retained,nativeMethod,full,id), wrapper=wrapper(mixin.name,bridge,plan,key);
            AnnotationNode wrap=MixinFit.injectorOf(wrapper);
            for(String attribute:List.of("require","expect","allow","remap","order"))if(MixinFit.value(annotation,attribute)!=null){int position=wrap.values.indexOf(attribute);if(position>=0)wrap.values.set(position+1,MixinFit.value(annotation,attribute));else{wrap.values.add(attribute);wrap.values.add(MixinFit.value(annotation,attribute));}}
            Installed installed=new Installed(key,mixin.name,originalName,handler.desc,plan,retained,bridge,wrapper,NativeCallChanges.method(unwrapped.apply(plan.helper()),plan.helperMethod()),priority(mixin),MixinFit.value(annotation,"order") instanceof Integer order?order:1000);
            if(!loader.registerBeforeDefinition(plan.helper(),()->{
                synchronized(PLANS){Map<String,Installed> registry=PLANS.computeIfAbsent(loader,ignored->new LinkedHashMap<>());
                    if(registry.values().stream().anyMatch(p->p.plan.helper().equals(plan.helper())&&p.plan.helperMethod().equals(plan.helperMethod())&&!p.plan.helperHash().equals(plan.helperHash())))
                        throw new IllegalStateException("Conflicting extracted callback source body");
                    registry.putIfAbsent(key,installed);
                }
                CallbackSeams.register(loader,key,plan.host(),plan.helper(),installed::witnessed,installed::declined);
            }))continue;
            mixin.methods.set(mixin.methods.indexOf(handler),retained);mixin.methods.add(bridge);mixin.methods.add(wrapper);changed++;
        }
        return changed;
    }
    private static boolean closed(MethodNode handler,AnnotationNode annotation) {
        if(annotation==null||!annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")
            ||(handler.access&(ACC_ABSTRACT|ACC_NATIVE|ACC_SYNCHRONIZED))!=0
            ||!Type.getReturnType(handler.desc).equals(Type.VOID_TYPE)||Boolean.TRUE.equals(MixinFit.value(annotation,"cancellable"))
            ||MixinFit.value(annotation,"slice")!=null||MixinFit.value(annotation,"locals")!=null
            ||MixinFit.value(annotation,"constraints") instanceof String constraint&&!constraint.isEmpty()
            ||handler.visibleParameterAnnotations!=null||handler.invisibleParameterAnnotations!=null)return false;
        List<AnnotationNode> annotations=new ArrayList<>();if(handler.visibleAnnotations!=null)annotations.addAll(handler.visibleAnnotations);if(handler.invisibleAnnotations!=null)annotations.addAll(handler.invisibleAnnotations);
        return annotations.stream().filter(a->FinalMixinApplications.isInjector(a.desc)).count()==1
            &&annotations.stream().noneMatch(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Group;"));
    }
    private static MethodNode bridge(String owner,MethodNode handler,MethodNode host,boolean full,String identifier) {
        boolean isStatic=(handler.access&ACC_STATIC)!=0;MethodNode out=new MethodNode(ACC_PRIVATE|(handler.access&ACC_STATIC),handler.name+"$invoke",host.desc,null,null);Type[] args=Type.getArgumentTypes(host.desc);int slot=isStatic?0:1;
        if(!isStatic)out.instructions.add(new VarInsnNode(ALOAD,0));
        if(full)for(Type arg:args){out.instructions.add(new VarInsnNode(arg.getOpcode(ILOAD),slot));slot+=arg.getSize();}
        out.instructions.add(new TypeInsnNode(NEW,CI));out.instructions.add(new InsnNode(DUP));out.instructions.add(new LdcInsnNode(identifier));out.instructions.add(new InsnNode(ICONST_0));
        out.instructions.add(new MethodInsnNode(INVOKESPECIAL,CI,"<init>","(Ljava/lang/String;Z)V",false));out.instructions.add(new MethodInsnNode(isStatic?INVOKESTATIC:INVOKESPECIAL,owner,handler.name,handler.desc,false));out.instructions.add(new InsnNode(RETURN));
        out.maxLocals=Arrays.stream(args).mapToInt(Type::getSize).sum()+(isStatic?0:1);out.maxStack=out.maxLocals+4;return out;
    }
    private static MethodNode wrapper(String owner,MethodNode bridge,NativeCallbackSeam.Plan plan,String key) {
        MixinFit.Member helper=MixinFit.parseMember("L"+plan.helper()+";"+plan.helperMethod());Type[] args=Type.getArgumentTypes(helper.desc()),host=Type.getArgumentTypes(bridge.desc);
        Type[] parameters=new Type[args.length+1+host.length];System.arraycopy(args,0,parameters,0,args.length);parameters[args.length]=Type.getObjectType(OP);System.arraycopy(host,0,parameters,args.length+1,host.length);
        boolean isStatic=(bridge.access&ACC_STATIC)!=0;MethodNode out=new MethodNode(ACC_PRIVATE|(bridge.access&ACC_STATIC),bridge.name+"$scope",Type.getMethodDescriptor(Type.VOID_TYPE,parameters),null,null);
        AnnotationNode annotation=new AnnotationNode("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;"),at=new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");at.values=new ArrayList<>(List.of("value","INVOKE","target","L"+plan.helper()+";"+plan.helperMethod()));annotation.values=new ArrayList<>(List.of("method",List.of(plan.method()),"at",at,"require",1));out.visibleAnnotations=new ArrayList<>(List.of(annotation));
        int[] slots=new int[parameters.length];int slot=isStatic?0:1;for(int p=0;p<parameters.length;p++){slots[p]=slot;slot+=parameters[p].getSize();}int scope=slot++,failure=slot++;
        InsnList code=out.instructions;code.add(new LdcInsnNode(Type.getObjectType(plan.host())));code.add(new LdcInsnNode(Type.getObjectType(plan.helper())));code.add(new LdcInsnNode(key));
        if(!isStatic)code.add(new VarInsnNode(ALOAD,0));for(int p=0;p<host.length;p++)code.add(new VarInsnNode(host[p].getOpcode(ILOAD),slots[args.length+1+p]));
        List<Type> captures=new ArrayList<>();if(!isStatic)captures.add(Type.getObjectType(owner));captures.addAll(List.of(host));
        code.add(new InvokeDynamicInsnNode("run",Type.getMethodDescriptor(Type.getObjectType("java/lang/Runnable"),captures.toArray(Type[]::new)),LAMBDA,Type.getMethodType("()V"),new Handle(isStatic?H_INVOKESTATIC:H_INVOKEVIRTUAL,owner,bridge.name,bridge.desc,false),Type.getMethodType("()V")));
        code.add(new MethodInsnNode(INVOKESTATIC,API,"enter","(Ljava/lang/Class;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Runnable;)L"+API+"$Scope;",false));code.add(new VarInsnNode(ASTORE,scope));
        LabelNode start=new LabelNode(),end=new LabelNode(),caught=new LabelNode();code.add(start);code.add(new VarInsnNode(ALOAD,slots[args.length]));push(code,args.length);code.add(new TypeInsnNode(ANEWARRAY,"java/lang/Object"));
        for(int p=0;p<args.length;p++){code.add(new InsnNode(DUP));push(code,p);code.add(new VarInsnNode(args[p].getOpcode(ILOAD),slots[p]));box(code,args[p]);code.add(new InsnNode(AASTORE));}
        code.add(new MethodInsnNode(INVOKEINTERFACE,OP,"call","([Ljava/lang/Object;)Ljava/lang/Object;",true));code.add(new InsnNode(POP));code.add(new VarInsnNode(ALOAD,scope));code.add(new MethodInsnNode(INVOKEVIRTUAL,API+"$Scope","complete","()V",false));code.add(end);close(code,scope);code.add(new InsnNode(RETURN));code.add(caught);code.add(new VarInsnNode(ASTORE,failure));close(code,scope);code.add(new VarInsnNode(ALOAD,failure));code.add(new InsnNode(ATHROW));out.tryCatchBlocks.add(new TryCatchBlockNode(start,end,caught,null));out.maxLocals=slot;out.maxStack=slot+8;return out;
    }
    private static void close(InsnList code,int slot){code.add(new VarInsnNode(ALOAD,slot));code.add(new MethodInsnNode(INVOKEVIRTUAL,API+"$Scope","close","()V",false));}
    private static void push(InsnList code,int value){if(value<=5)code.add(new InsnNode(ICONST_0+value));else code.add(new LdcInsnNode(value));}
    private static void box(InsnList code,Type type){String owner=switch(type.getSort()){case Type.BOOLEAN->"Boolean";case Type.BYTE->"Byte";case Type.CHAR->"Character";case Type.SHORT->"Short";case Type.INT->"Integer";case Type.FLOAT->"Float";case Type.LONG->"Long";case Type.DOUBLE->"Double";default->null;};if(owner!=null)code.add(new MethodInsnNode(INVOKESTATIC,"java/lang/"+owner,"valueOf","("+type.getDescriptor()+")Ljava/lang/"+owner+";",false));}
    public static byte[] transform(ClassLoader loader,String binary,byte[] bytes) {
        List<Installed> plans=plans(loader).stream().filter(p->p.plan.helper().equals(binary.replace('.','/')))
            .sorted(Comparator.comparingInt((Installed p)->p.order).thenComparing(Comparator.comparingInt((Installed p)->p.priority).reversed()).thenComparingLong(p->p.sequence)).toList();if(plans.isEmpty())return bytes;
        ClassNode owner=new ClassNode();new ClassReader(bytes).accept(owner,ClassReader.EXPAND_FRAMES);
        Map<String,String> hashes=new HashMap<>();for(Installed plan:plans){MethodNode body=NativeCallChanges.method(owner,plan.plan.helperMethod());if(body!=null)hashes.putIfAbsent(plan.plan.helperMethod(),MixinInstructionFingerprint.hash(body));}
        if(plans.stream().allMatch(p->p.helper!=null&&p.helper.fingerprint().equals(hashes.get(p.plan.helperMethod()))))return bytes;
        Map<String,AbstractInsnNode> continuations=new HashMap<>();for(Installed plan:plans){MethodNode body=NativeCallChanges.method(owner,plan.plan.helperMethod());if(body!=null)for(AbstractInsnNode i:body.instructions)if(i instanceof MethodInsnNode call&&NativeCallChanges.member(call).equals(plan.plan.member()))continuations.putIfAbsent(plan.plan.helperMethod(),call.getNext());}
        for(Installed plan:plans){MethodNode body=NativeCallChanges.method(owner,plan.plan.helperMethod());
            if(body==null||!Objects.equals(hashes.get(plan.plan.helperMethod()),plan.plan.helperHash()))throw new IllegalStateException("Extracted callback helper changed before weaving: "+binary);
            MethodInsnNode anchor=Arrays.stream(body.instructions.toArray()).filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).filter(c->NativeCallChanges.member(c).equals(plan.plan.member())).findFirst().orElseThrow();int token=body.maxLocals++;
            InsnList enter=new InsnList();enter.add(new LdcInsnNode(Type.getObjectType(owner.name)));enter.add(new LdcInsnNode(plan.key));enter.add(new MethodInsnNode(INVOKESTATIC,API,"beginHelper","(Ljava/lang/Class;Ljava/lang/String;)L"+API+"$Token;",false));enter.add(new VarInsnNode(ASTORE,token));body.instructions.insert(enter);
            InsnList fire=new InsnList();fire.add(new VarInsnNode(ALOAD,token));fire.add(new MethodInsnNode(INVOKEVIRTUAL,API+"$Token","fire","()V",false));body.instructions.insertBefore(continuations.get(plan.plan.helperMethod()),fire);
            for(AbstractInsnNode instruction:body.instructions)if(instruction instanceof FrameNode frame){List<Object> locals=frame.local==null?new ArrayList<>():new ArrayList<>(frame.local);int count=0;for(Object local:locals)count+=local.equals(LONG)||local.equals(DOUBLE)?2:1;while(count++<token)locals.add(TOP);locals.add(API+"$Token");frame.local=locals;}
            body.maxStack+=2;
        }
        for(Installed plan:plans)plan.helper=contract(owner.name,NativeCallChanges.method(owner,plan.plan.helperMethod()));
        ClassWriter out=new ClassWriter(ClassWriter.COMPUTE_MAXS);owner.accept(out);return out.toByteArray();
    }
    /** Successful definitions witness unchanged source handler and generated callback/operation bridges. */
    public static void observeDefinition(ClassLoader loader,String binary,byte[] bytes) {
        List<Installed> helpers=plans(loader).stream().filter(p->p.plan.helper().equals(binary.replace('.','/'))).toList();
        if(!helpers.isEmpty()){ClassNode node=MixinFit.parse(bytes);for(Installed plan:helpers)plan.helperDrift=helperDrift(loader,node,plan);}
        List<Installed> plans=plans(loader).stream().filter(p->p.plan.host().equals(binary.replace('.','/'))).toList();if(plans.isEmpty())return;ClassNode node=MixinFit.parse(bytes);
        for(Installed plan:plans){List<MethodContract> witnesses=new ArrayList<>();Map<String,String> renamed=new HashMap<>();
            List<MethodNode> expected=List.of(plan.handler,plan.bridge,plan.wrapper);boolean valid=true;
            for(MethodNode original:expected){List<MethodNode> found=node.methods.stream().filter(m->m.desc.equals(original.desc)&&m.name.contains(original.name)&&merged(m,plan.source)).toList();if(found.size()!=1||(found.getFirst().access&(ACC_STATIC|ACC_PRIVATE))!=(ACC_PRIVATE|(original.access&ACC_STATIC))){valid=false;break;}renamed.put(original.name,found.getFirst().name);}
            if(valid)for(MethodNode original:expected){MethodNode actual=node.methods.stream().filter(m->m.name.equals(renamed.get(original.name))&&m.desc.equals(original.desc)).findFirst().orElseThrow(), normalized=copy(original);normalize(normalized,plan.source,node.name,renamed);
                if(!MixinInstructionFingerprint.hash(normalized).equals(MixinInstructionFingerprint.hash(actual))){valid=false;break;}witnesses.add(contract(node.name,actual));}
            if(valid)plan.host=List.copyOf(witnesses);
            plan.hostDrift=valid?null:"the transported handler, bridge or wrapper of "+plan.source.replace('/','.')+" did not reach "+plan.site()+" unchanged";
        }
        Set<CallbackOperationProof.Wrapper> wrappers=new HashSet<>();
        for(Installed plan:plans)if(plan.host.size()==3){MethodContract wrapper=plan.host.get(2);wrappers.add(new CallbackOperationProof.Wrapper(wrapper.name(),wrapper.descriptor(),(plan.wrapper.access&ACC_STATIC)!=0));}
        Set<String> transported=new HashSet<>();for(Installed plan:plans)transported.add(plan.source.replace('/','.'));
        for(Installed plan:plans)if(plan.host.size()==3){var bridge=CallbackOperationProof.prove(node,plan.plan.method(),plan.plan.helper(),plan.plan.helperMethod(),wrappers);
            if(bridge.size()>=2){List<MethodContract> complete=new ArrayList<>(plan.host);complete.addAll(bridge);plan.host=List.copyOf(complete);}
            else{MethodNode caller=NativeCallChanges.method(node,plan.plan.method());
                plan.hostDrift="the call to "+plan.helperName()+" in "+plan.site()+" no longer runs directly through the transported Operation"
                    +SeamDeclines.changedBy(caller==null?List.of():SeamDeclines.contributors(node,caller,transported));}
        }
    }
    /** Names who changed the carrier helper after it was instrumented, from its final definition; null if nobody did. */
    private static String helperDrift(ClassLoader loader,ClassNode node,Installed plan){
        if(plan.helper==null||DefinedMethodContracts.observed(loader,plan.helper))return null;
        MethodNode actual=NativeCallChanges.method(node,plan.plan.helperMethod());
        return actual==null?"the carrier helper "+plan.helperName()+" is missing from its defined class"
            :"the final body of "+plan.helperName()+" is not the body Forbric instrumented"+SeamDeclines.changedBy(SeamDeclines.contributors(node,actual,Set.of()));
    }
    private static List<Installed> plans(ClassLoader loader){synchronized(PLANS){Map<String,Installed> plans=PLANS.get(loader);return plans==null?List.of():List.copyOf(plans.values());}}
    private static int priority(ClassNode source){List<AnnotationNode> annotations=new ArrayList<>();if(source.visibleAnnotations!=null)annotations.addAll(source.visibleAnnotations);if(source.invisibleAnnotations!=null)annotations.addAll(source.invisibleAnnotations);for(var annotation:annotations)if(annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")&&MixinFit.value(annotation,"priority")instanceof Integer value)return value;return 1000;}
    /** Remove only this transport's already witnessed emitted body while deriving another source callback. */
    private static ClassNode unwrapped(ClassLoader loader,ClassNode current){if(current==null)return null;ClassNode copy=new ClassNode();current.accept(copy);
        Set<String> restored=new HashSet<>();for(Installed plan:plans(loader))if(plan.plan.helper().equals(current.name)&&restored.add(plan.plan.helperMethod())){
            MethodNode body=NativeCallChanges.method(copy,plan.plan.helperMethod());if(body!=null&&plan.helper!=null&&MixinInstructionFingerprint.hash(body).equals(plan.helper.fingerprint()))
                copy.methods.set(copy.methods.indexOf(body),copy(plan.originalHelper));
        }return copy;
    }
    private static boolean merged(MethodNode method,String source){return method.visibleAnnotations!=null&&method.visibleAnnotations.stream().anyMatch(a->a.desc.equals("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;")&&source.replace('/','.').equals(MixinFit.value(a,"mixin")));}
    private static void normalize(MethodNode method,String source,String target,Map<String,String> renamed){for(AbstractInsnNode i:method.instructions){if(i instanceof MethodInsnNode call&&call.owner.equals(source)){call.owner=target;call.name=renamed.getOrDefault(call.name,call.name);}if(i instanceof FieldInsnNode field&&field.owner.equals(source))field.owner=target;if(i instanceof TypeInsnNode cast&&cast.desc.equals(source))cast.desc=target;if(i instanceof InvokeDynamicInsnNode dynamic){dynamic.desc=dynamic.desc.replace("L"+source+";","L"+target+";");for(int n=0;n<dynamic.bsmArgs.length;n++)if(dynamic.bsmArgs[n]instanceof Handle h&&h.getOwner().equals(source))dynamic.bsmArgs[n]=new Handle(h.getTag(),target,renamed.getOrDefault(h.getName(),h.getName()),h.getDesc(),h.isInterface());}}}
    private static MethodContract contract(String owner,MethodNode method){return new MethodContract(owner,method.name,method.desc,MixinInstructionFingerprint.hash(method));}
    private static MethodNode copy(MethodNode source){MethodNode copy=new MethodNode(source.access,source.name,source.desc,source.signature,source.exceptions.toArray(String[]::new));source.accept(copy);for(var i:copy.instructions)if(i instanceof InvokeDynamicInsnNode d)d.bsmArgs=d.bsmArgs.clone();return copy;}
    private static void removeInjector(MethodNode handler){if(handler.visibleAnnotations!=null)handler.visibleAnnotations.removeIf(a->FinalMixinApplications.isInjector(a.desc));if(handler.invisibleAnnotations!=null)handler.invisibleAnnotations.removeIf(a->FinalMixinApplications.isInjector(a.desc));}
}
