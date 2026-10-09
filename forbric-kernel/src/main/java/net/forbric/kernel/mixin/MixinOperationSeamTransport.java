/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import java.util.function.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import net.forbric.api.*;
import net.forbric.kernel.boot.DefinedMethodContracts;
import net.forbric.kernel.boot.DefinedMethodContracts.MethodContract;
import net.forbric.kernel.classloading.ForbricClassLoader;

/** Keep a guest's full source body and Operation at the proved top invocation inside inherited gateways. */
public final class MixinOperationSeamTransport implements Opcodes {
    public static final String PROPERTY="forbric.operationSeams";
    private static final String API="net/forbric/api/OperationSeams",NATIVE=API+"$NativeOperation",INVOCATION=API+"$Invocation";
    private static final String OP="com/llamalad7/mixinextras/injector/wrapoperation/Operation",CI="org/spongepowered/asm/mixin/injection/callback/CallbackInfo";
    private static final Handle META=new Handle(H_INVOKESTATIC,"java/lang/invoke/LambdaMetafactory","metafactory","(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",false);
    private static final Map<ClassLoader,Map<String,Installed>> PLANS=Collections.synchronizedMap(new IdentityHashMap<>());
    private static final class Installed {
        final String source,key,injectorName,injectorDesc;final OperationCallGraph.Plan graph;final MethodNode retained,bridge,wrapper,parentOriginal,helperOriginal;
        volatile List<MethodContract> emitted=List.of(),host=List.of();
        /** What the final definitions say broke the witness, named when each class is defined. */
        volatile String hostDrift;final Map<MethodContract,String> drift=new java.util.concurrent.ConcurrentHashMap<>();
        Installed(String source,String key,String injectorName,String injectorDesc,OperationCallGraph.Plan graph,MethodNode retained,MethodNode bridge,MethodNode wrapper,Function<String,ClassNode> current){this.source=source;this.key=key;this.injectorName=injectorName;this.injectorDesc=injectorDesc;this.graph=graph;this.retained=copy(retained);this.bridge=copy(bridge);this.wrapper=copy(wrapper);this.parentOriginal=copy(NativeCallChanges.method(current.apply(graph.firstOwner()),graph.firstMethod()));this.helperOriginal=copy(NativeCallChanges.method(current.apply(graph.helper()),graph.helperMethod()));}
        boolean witnessed(ClassLoader loader){return host.size()==3&&!emitted.isEmpty()&&host.stream().allMatch(value->DefinedMethodContracts.observed(loader,value))&&emitted.stream().allMatch(value->DefinedMethodContracts.observed(loader,value))&&graph.getters().stream().allMatch(value->DefinedMethodContracts.observed(loader,value));}
        String site(){return graph.host().replace('/','.')+"."+graph.hostMethod();}
        String helperName(){return graph.helper().replace('/','.')+"."+graph.helperMethod();}
        /** The first failing conjunct of {@link #witnessed}, in its order; null when it holds. */
        String broken(ClassLoader loader){
            if(host.size()!=3)return hostDrift!=null?hostDrift:"the transported handler, bridge or wrapper of "+source.replace('/','.')+" has not reached "+site()+" unchanged";
            if(emitted.isEmpty())return "the gateway "+graph.firstOwner().replace('/','.')+"."+graph.firstMethod()+" and helper "+helperName()+" were not instrumented before their definition";
            for(MethodContract value:host)if(!DefinedMethodContracts.observed(loader,value))return "the transported "+value.owner()+"."+value.name()+value.descriptor()+" was redefined";
            for(MethodContract value:emitted)if(!DefinedMethodContracts.observed(loader,value))return drift.getOrDefault(value,"the final body of "+value.owner()+"."+value.name()+value.descriptor()+" is not the body Forbric instrumented");
            for(MethodContract value:graph.getters())if(!DefinedMethodContracts.observed(loader,value))return "the proved getter "+value.owner()+"."+value.name()+value.descriptor()+" changed";
            return null;
        }
        /** OperationSeams' once-per-site report: the operation is skipped there, the carrier gateway runs as written. */
        void declined(ClassLoader loader,String ignoredKey,String reason){
            String broken=broken(loader),cause=broken==null?reason:broken;
            SeamDeclines.report(source,injectorName,injectorDesc,site(),"source operation skipped at "+site()+", whose call it wraps the merged game moved into "
                +helperName()+": "+cause+"; the carrier's gateway runs as written",List.of("transport=operation seam","host="+site(),"helper="+helperName(),"declined="+reason,"cause="+cause));
        }
    }
    private MixinOperationSeamTransport() { }
    public static void release(ClassLoader loader){PLANS.remove(loader);OperationSeams.release(loader);}
    public static int adapt(ClassNode mixin,Function<String,ClassNode> classes,BiFunction<Ecosystem,String,ClassNode> natives,ForbricClassLoader loader) {
        if("off".equalsIgnoreCase(System.getProperty(PROPERTY,"on"))||loader==null)return 0;
        List<String> targets=MixinFit.mixinTargets(mixin);if(targets.size()!=1)return 0;ClassNode source=natives.apply(MixinStubRebind.ecosystemOf(mixin.name),targets.getFirst()),target=unwrapped(loader,classes.apply(targets.getFirst()));if(source==null||target==null)return 0;
        Function<String,ClassNode> current=owner->unwrapped(loader,classes.apply(owner));int changed=0;
        for(MethodNode handler:List.copyOf(mixin.methods)) {
            AnnotationNode injector=MixinFit.injectorOf(handler);if(!closed(handler,injector)||!verified(mixin.name,handler))continue;
            List<String> selectors=MixinFit.stringList(MixinFit.value(injector,"method"));List<AnnotationNode> ats=MixinFit.atNodes(injector);if(selectors.size()!=1||ats.size()!=1)continue;
            AnnotationNode at=ats.getFirst();String member=MixinFit.asString(MixinFit.value(at,"target"));if(!"INVOKE".equals(MixinFit.value(at,"value"))||member==null||MixinFit.value(at,"by")!=null||MixinFit.value(at,"args")!=null||MixinFit.value(at,"ordinal") instanceof Integer ordinal&&ordinal>0)continue;
            MethodNode original=MixinStubRebind.bound(source,selectors.getFirst());if(original==null||Type.getReturnType(original.desc)!=Type.VOID_TYPE)continue;
            boolean wrap=injector.desc.equals("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;");String shift=MixinFit.asString(MixinFit.value(at,"shift"));if(wrap&&shift!=null||!wrap&&shift!=null&&!List.of("BEFORE","NONE","AFTER").contains(shift))continue;
            MixinFit.Member wanted=MixinFit.parseMember(member);if(wanted==null)continue;Type[] operands=callTypes(wanted,anchors(original,member).stream().findFirst().map(call->call.getOpcode()).orElse(INVOKEVIRTUAL));
            Type[] hostArgs=Type.getArgumentTypes(original.desc),parameters=Type.getArgumentTypes(handler.desc);int own=wrap?operands.length+1:1;
            Type[] base=wrap?Arrays.copyOf(operands,own):new Type[]{Type.getObjectType(CI)};if(wrap)base[operands.length]=Type.getObjectType(OP);
            boolean full=parameters.length==own+hostArgs.length&&(wrap?Arrays.equals(hostArgs,Arrays.copyOfRange(parameters,own,parameters.length)):Arrays.equals(hostArgs,Arrays.copyOf(parameters,hostArgs.length))&&parameters[hostArgs.length].equals(Type.getObjectType(CI)));
            if(parameters.length!=own&&!full||wrap&&!Arrays.equals(base,Arrays.copyOf(parameters,own))||!wrap&&!full&&!Arrays.equals(base,parameters))continue;
            if(!wrap&&((handler.access^original.access)&ACC_STATIC)!=0)continue;
            String identity=mixin.name+":"+handler.name+":"+MixinInstructionFingerprint.hash(handler)+":";
            Installed known=plans(loader).stream().filter(plan->plan.key.startsWith(identity)&&plan.graph.hostMethod().equals(original.name+original.desc)&&plan.graph.member().equals(member)&&compatible(plan,classes)).findFirst().orElse(null);
            if(known!=null){mixin.methods.set(mixin.methods.indexOf(handler),copy(known.retained));mixin.methods.add(copy(known.bridge));mixin.methods.add(copy(known.wrapper));changed++;continue;}
            OperationCallGraph.Plan graph=OperationCallGraph.derive(source,target,selectors.getFirst(),member,current,natives);if(graph==null)continue;
            String key=mixin.name+":"+handler.name+":"+MixinInstructionFingerprint.hash(handler)+":"+graph.graph();
            Installed existing=plans(loader).stream().filter(plan->plan.key.equals(key)).findFirst().orElse(null);
            if(existing!=null){mixin.methods.set(mixin.methods.indexOf(handler),copy(existing.retained));mixin.methods.add(copy(existing.bridge));mixin.methods.add(copy(existing.wrapper));changed++;continue;}
            MethodNode retained=copy(handler);retained.name=MixinHandlerShim.asideName(mixin.name,handler.name,"$forbricoperation");removeInjector(retained);MixinCallbackShape.uniqueMember(retained);
            String callbackId=MixinFit.asString(MixinFit.value(injector,"id")),pointId=MixinFit.asString(MixinFit.value(at,"id"));callbackId=(callbackId==null||callbackId.isEmpty()?original.name:callbackId)+(pointId==null||pointId.isEmpty()?"":":"+pointId);
            MethodNode bridge=bridge(mixin.name,retained,hostArgs,operands,wrap,full,"AFTER".equals(shift),callbackId),wrapper=wrapper(mixin.name,bridge,hostArgs,graph,key,injector);
            Installed installed=new Installed(mixin.name,key,handler.name,handler.desc,graph,retained,bridge,wrapper,current);
            List<String> owners=new ArrayList<>(new TreeSet<>(List.of(graph.firstOwner(),graph.helper())));
            if(!register(loader,owners,0,()->{synchronized(PLANS){Map<String,Installed> registry=PLANS.computeIfAbsent(loader,ignored->new LinkedHashMap<>());registry.putIfAbsent(key,installed);}OperationSeams.register(loader,key,graph.host(),graph.helper(),graph.graph(),installed::witnessed,graph.gatewayInputs().stream().mapToInt(Integer::intValue).toArray(),graph.helperInputs().stream().mapToInt(Integer::intValue).toArray(),installed::declined);}))continue;
            mixin.methods.set(mixin.methods.indexOf(handler),retained);mixin.methods.add(bridge);mixin.methods.add(wrapper);changed++;
        }
        return changed;
    }
    private static boolean register(ForbricClassLoader loader,List<String> owners,int at,Runnable action){if(at==owners.size()){action.run();return true;}boolean[] result={false};return loader.registerBeforeDefinition(owners.get(at),()->result[0]=register(loader,owners,at+1,action))&&result[0];}
    private static boolean verified(String owner,MethodNode method){try{new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(owner,method);return true;}catch(org.objectweb.asm.tree.analysis.AnalyzerException|RuntimeException invalid){return false;}}
    private static boolean compatible(Installed plan,Function<String,ClassNode> current){for(var pair:List.of(Map.entry(plan.graph.firstOwner(),plan.parentOriginal),Map.entry(plan.graph.helper(),plan.helperOriginal))){MethodNode actual=NativeCallChanges.method(current.apply(pair.getKey()),pair.getValue().name+pair.getValue().desc);if(actual==null)return false;String hash=MixinInstructionFingerprint.hash(actual);if(!hash.equals(MixinInstructionFingerprint.hash(pair.getValue()))&&plan.emitted.stream().noneMatch(value->value.owner().replace('.','/').equals(pair.getKey())&&value.name().equals(actual.name)&&value.descriptor().equals(actual.desc)&&value.fingerprint().equals(hash)))return false;}return plan.graph.getters().stream().allMatch(value->{MethodNode getter=NativeCallChanges.method(current.apply(value.owner().replace('.','/')),value.name()+value.descriptor());boolean valid=getter!=null&&MixinInstructionFingerprint.hash(getter).equals(value.fingerprint());return valid;});}
    private static boolean closed(MethodNode method,AnnotationNode injector){if(injector==null||!(injector.desc.equals(MixinRetarget.INJECT)||injector.desc.equals("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;"))||Type.getReturnType(method.desc)!=Type.VOID_TYPE||Boolean.TRUE.equals(MixinFit.value(injector,"cancellable"))||MixinFit.value(injector,"slice")!=null||MixinFit.value(injector,"locals")!=null||method.invisibleParameterAnnotations!=null||method.visibleParameterAnnotations!=null||(method.access&(ACC_ABSTRACT|ACC_NATIVE|ACC_SYNCHRONIZED))!=0)return false;List<AnnotationNode> all=new ArrayList<>();if(method.visibleAnnotations!=null)all.addAll(method.visibleAnnotations);if(method.invisibleAnnotations!=null)all.addAll(method.invisibleAnnotations);return all.stream().filter(a->FinalMixinApplications.isInjector(a.desc)).count()==1&&all.stream().noneMatch(a->a.desc.endsWith("/Group;"));}
    private static MethodNode bridge(String owner,MethodNode retained,Type[] host,Type[] operands,boolean wrap,boolean full,boolean after,String callbackId) {
        Type[] signature=Arrays.copyOf(host,host.length+2);signature[host.length]=Type.getType(Object.class);signature[host.length+1]=Type.getType(Object[].class);
        boolean instance=(retained.access&ACC_STATIC)==0;MethodNode bridge=new MethodNode(ACC_PRIVATE|(retained.access&ACC_STATIC),retained.name+"$invoke",Type.getMethodDescriptor(Type.getType(Object.class),signature),null,null);
        int[] locals=slots(signature,instance);InsnList code=bridge.instructions;
        if(!wrap&&after)invokeNative(code,locals[host.length],locals[host.length+1]);
        if(instance)code.add(new VarInsnNode(ALOAD,0));
        if(wrap){for(int i=0;i<operands.length;i++)arrayLoad(code,locals[host.length+1],i,operands[i]);code.add(new VarInsnNode(ALOAD,locals[host.length]));code.add(new TypeInsnNode(CHECKCAST,NATIVE));code.add(new InvokeDynamicInsnNode("call","(L"+NATIVE+";)L"+OP+";",META,Type.getMethodType("([Ljava/lang/Object;)Ljava/lang/Object;"),new Handle(H_INVOKEINTERFACE,NATIVE,"invoke","([Ljava/lang/Object;)Ljava/lang/Object;",true),Type.getMethodType("([Ljava/lang/Object;)Ljava/lang/Object;")));}
        else{if(full)for(int i=0;i<host.length;i++)code.add(new VarInsnNode(host[i].getOpcode(ILOAD),locals[i]));code.add(new TypeInsnNode(NEW,CI));code.add(new InsnNode(DUP));code.add(new LdcInsnNode(callbackId));code.add(new InsnNode(ICONST_0));code.add(new MethodInsnNode(INVOKESPECIAL,CI,"<init>","(Ljava/lang/String;Z)V",false));}
        if(full&&wrap)for(int i=0;i<host.length;i++)code.add(new VarInsnNode(host[i].getOpcode(ILOAD),locals[i]));
        code.add(new MethodInsnNode(instance?INVOKESPECIAL:INVOKESTATIC,owner,retained.name,retained.desc,false));
        if(!wrap&&!after)invokeNative(code,locals[host.length],locals[host.length+1]);code.add(new InsnNode(ACONST_NULL));code.add(new InsnNode(ARETURN));bridge.maxLocals=end(signature,instance);bridge.maxStack=bridge.maxLocals+10;MixinCallbackShape.uniqueMember(bridge);return bridge;
    }
    private static void invokeNative(InsnList code,int operation,int arguments){code.add(new VarInsnNode(ALOAD,operation));code.add(new TypeInsnNode(CHECKCAST,NATIVE));code.add(new VarInsnNode(ALOAD,arguments));code.add(new MethodInsnNode(INVOKEINTERFACE,NATIVE,"invoke","([Ljava/lang/Object;)Ljava/lang/Object;",true));code.add(new InsnNode(POP));}
    private static MethodNode wrapper(String owner,MethodNode bridge,Type[] host,OperationCallGraph.Plan graph,String key,AnnotationNode source) {
        MixinFit.Member gateway=MixinFit.parseMember(graph.gateway());Type[] args=callTypes(gateway,graph.gatewayOpcode());
        Type[] signature=Arrays.copyOf(args,args.length+1+host.length);signature[args.length]=Type.getObjectType(OP);System.arraycopy(host,0,signature,args.length+1,host.length);
        boolean instance=(bridge.access&ACC_STATIC)==0;MethodNode wrapper=new MethodNode(ACC_PRIVATE|(bridge.access&ACC_STATIC),bridge.name+"$scope",Type.getMethodDescriptor(Type.VOID_TYPE,signature),null,null);int[] locals=slots(signature,instance);
        AnnotationNode annotation=new AnnotationNode("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;"),at=new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");at.values=new ArrayList<>(List.of("value","INVOKE","target",graph.gateway()));annotation.values=new ArrayList<>(List.of("method",List.of(graph.hostMethod()),"at",at,"require",1));wrapper.visibleAnnotations=new ArrayList<>(List.of(annotation));
        for(String option:List.of("expect","allow","order","remap","constraints")){Object value=MixinFit.value(source,option);if(value!=null)annotation.values.addAll(List.of(option,value));}
        InsnList code=wrapper.instructions;code.add(new LdcInsnNode(Type.getObjectType(graph.host())));code.add(new LdcInsnNode(Type.getObjectType(graph.helper())));code.add(new LdcInsnNode(key));
        List<Type> captured=new ArrayList<>();if(instance){code.add(new VarInsnNode(ALOAD,0));captured.add(Type.getObjectType(owner));}for(int i=0;i<host.length;i++){code.add(new VarInsnNode(host[i].getOpcode(ILOAD),locals[args.length+1+i]));captured.add(host[i]);}
        code.add(new InvokeDynamicInsnNode("invoke",Type.getMethodDescriptor(Type.getObjectType(INVOCATION),captured.toArray(Type[]::new)),META,Type.getMethodType("(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"),new Handle(instance?H_INVOKEVIRTUAL:H_INVOKESTATIC,owner,bridge.name,bridge.desc,false),Type.getMethodType("(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;")));
        code.add(new VarInsnNode(ALOAD,locals[args.length]));code.add(new InvokeDynamicInsnNode("invoke","(L"+OP+";)L"+NATIVE+";",META,Type.getMethodType("([Ljava/lang/Object;)Ljava/lang/Object;"),new Handle(H_INVOKEINTERFACE,OP,"call","([Ljava/lang/Object;)Ljava/lang/Object;",true),Type.getMethodType("([Ljava/lang/Object;)Ljava/lang/Object;")));
        array(code,args,locals);code.add(new MethodInsnNode(INVOKESTATIC,API,"scoped","(Ljava/lang/Class;Ljava/lang/Class;Ljava/lang/String;L"+INVOCATION+";L"+NATIVE+";[Ljava/lang/Object;)Ljava/lang/Object;",false));code.add(new InsnNode(POP));code.add(new InsnNode(RETURN));wrapper.maxLocals=end(signature,instance);wrapper.maxStack=wrapper.maxLocals+10;return wrapper;
    }
    public static byte[] transform(ClassLoader loader,String binary,byte[] bytes) {
        List<Installed> installed=plans(loader).stream().filter(plan->plan.graph.firstOwner().equals(binary.replace('.','/'))||plan.graph.helper().equals(binary.replace('.','/'))).toList();if(installed.isEmpty())return bytes;
        ClassNode owner=new ClassNode();new ClassReader(bytes).accept(owner,ClassReader.EXPAND_FRAMES);Map<String,List<Installed>> graphs=new LinkedHashMap<>();for(Installed plan:installed)graphs.computeIfAbsent(plan.graph.graph(),ignored->new ArrayList<>()).add(plan);
        boolean changed=false;
        for(var group:graphs.entrySet()){Installed first=group.getValue().getFirst();OperationCallGraph.Plan graph=first.graph;String tag=digest(graph.graph());List<MethodContract> emitted=new ArrayList<>();
            if(owner.name.equals(graph.firstOwner())) {
                MethodNode parent=NativeCallChanges.method(owner,graph.firstMethod());if(parent==null)throw new IllegalStateException("Operation gateway disappeared: "+graph.graph());
                if(!MixinInstructionFingerprint.hash(parent).equals(graph.parentHash())){if(first.emitted.stream().anyMatch(value->value.owner().replace('.','/').equals(owner.name)&&value.name().equals(parent.name)&&value.descriptor().equals(parent.desc)&&value.fingerprint().equals(MixinInstructionFingerprint.hash(parent))))continue;throw new IllegalStateException("Operation gateway changed: "+graph.graph());}
                List<MethodInsnNode> calls=anchors(parent,graph.forwarded());if(graph.forwardOrdinal()>=calls.size())throw new IllegalStateException("Operation forwarder disappeared");MethodInsnNode call=calls.get(graph.forwardOrdinal());String name="forbric$selected$"+tag;MethodNode direct=nativeBridge(owner.name,name+"$native",call),selected=callWrapper(owner.name,name,call,direct,graph,"selected");owner.methods.add(direct);owner.methods.add(selected);parent.instructions.set(call,new MethodInsnNode(INVOKESTATIC,owner.name,name,selected.desc,false));emitted.add(contract(owner.name,parent));emitted.add(contract(owner.name,direct));emitted.add(contract(owner.name,selected));changed=true;
            }
            if(owner.name.equals(graph.helper())) {
                MethodNode helper=NativeCallChanges.method(owner,graph.helperMethod());if(helper==null||!MixinInstructionFingerprint.hash(helper).equals(graph.helperHash()))throw new IllegalStateException("Operation helper changed: "+graph.graph());
                List<MethodInsnNode> calls=anchors(helper,graph.member());if(calls.size()!=1)throw new IllegalStateException("Operation occurrence became ambiguous");MethodInsnNode call=calls.getFirst();String name="forbric$operation$"+tag;MethodNode direct=nativeBridge(owner.name,name+"$native",call),apply=callWrapper(owner.name,name,call,direct,graph,"apply");owner.methods.add(direct);owner.methods.add(apply);helper.instructions.set(call,new MethodInsnNode(INVOKESTATIC,owner.name,name,apply.desc,false));emitted.add(contract(owner.name,helper));emitted.add(contract(owner.name,direct));emitted.add(contract(owner.name,apply));changed=true;
            }
            for(Installed plan:group.getValue()){List<MethodContract> all=new ArrayList<>(plan.emitted);all.removeIf(value->value.owner().replace('.','/').equals(owner.name));all.addAll(emitted);plan.emitted=List.copyOf(all);}
        }
        if(!changed)return bytes;ClassWriter out=new ClassWriter(ClassWriter.COMPUTE_MAXS);owner.accept(out);return out.toByteArray();
    }
    private static MethodNode nativeBridge(String owner,String name,MethodInsnNode call){MethodNode method=new MethodNode(ACC_PRIVATE|ACC_STATIC,name,"([Ljava/lang/Object;)Ljava/lang/Object;",null,null);Type[] args=callTypes(new MixinFit.Member(call.owner,call.name,call.desc),call.getOpcode());for(int i=0;i<args.length;i++)arrayLoad(method.instructions,0,i,args[i]);method.instructions.add(new MethodInsnNode(call.getOpcode(),call.owner,call.name,call.desc,call.itf));method.instructions.add(new InsnNode(ACONST_NULL));method.instructions.add(new InsnNode(ARETURN));method.maxLocals=1;method.maxStack=Arrays.stream(args).mapToInt(Type::getSize).sum()+4;return method;}
    private static MethodNode callWrapper(String owner,String name,MethodInsnNode call,MethodNode direct,OperationCallGraph.Plan graph,String kind){Type[] args=callTypes(new MixinFit.Member(call.owner,call.name,call.desc),call.getOpcode());MethodNode method=new MethodNode(ACC_PRIVATE|ACC_STATIC,name,Type.getMethodDescriptor(Type.VOID_TYPE,args),null,null);InsnList code=method.instructions;code.add(new LdcInsnNode(Type.getObjectType(graph.helper())));code.add(new LdcInsnNode(graph.graph()));code.add(new InvokeDynamicInsnNode("invoke","()L"+NATIVE+";",META,Type.getMethodType("([Ljava/lang/Object;)Ljava/lang/Object;"),new Handle(H_INVOKESTATIC,owner,direct.name,direct.desc,false),Type.getMethodType("([Ljava/lang/Object;)Ljava/lang/Object;")));array(code,args,slots(args,false));code.add(new MethodInsnNode(INVOKESTATIC,API,kind,"(Ljava/lang/Class;Ljava/lang/String;L"+NATIVE+";[Ljava/lang/Object;)Ljava/lang/Object;",false));code.add(new InsnNode(POP));code.add(new InsnNode(RETURN));method.maxLocals=end(args,false);method.maxStack=method.maxLocals+8;return method;}
    public static void observeDefinition(ClassLoader loader,String binary,byte[] bytes){observeEmitted(loader,binary,bytes);List<Installed> installed=plans(loader).stream().filter(plan->plan.graph.host().equals(binary.replace('.','/'))).toList();if(installed.isEmpty())return;ClassNode owner=MixinFit.parse(bytes);
        for(Installed plan:installed){List<MethodNode> expected=List.of(plan.retained,plan.bridge,plan.wrapper);Map<String,String> renamed=new HashMap<>();List<MethodContract> witnesses=new ArrayList<>();boolean valid=true;
            for(MethodNode method:expected){List<MethodNode> found=owner.methods.stream().filter(actual->actual.desc.equals(method.desc)&&actual.name.contains(method.name)&&merged(actual,plan.source)).toList();if(found.size()!=1){valid=false;break;}renamed.put(method.name,found.getFirst().name);}
            if(valid)for(MethodNode method:expected){MethodNode actual=NativeCallChanges.method(owner,renamed.get(method.name)+method.desc),normalized=copy(method);normalize(normalized,plan.source,owner.name,renamed);if(!MixinInstructionFingerprint.hash(normalized).equals(MixinInstructionFingerprint.hash(actual))){valid=false;break;}witnesses.add(contract(owner.name,actual));}if(valid)plan.host=List.copyOf(witnesses);
            plan.hostDrift=valid?null:"the transported handler, bridge or wrapper of "+plan.source.replace('/','.')+" did not reach "+plan.site()+" unchanged";
        }
    }
    /** Names who changed an instrumented gateway or helper after Forbric instrumented it, from its final definition. */
    private static void observeEmitted(ClassLoader loader,String binary,byte[] bytes){
        String owner=binary.replace('/','.');ClassNode node=null;
        for(Installed plan:plans(loader))for(MethodContract value:plan.emitted){
            if(!value.owner().equals(owner))continue;
            if(DefinedMethodContracts.observed(loader,value)){plan.drift.remove(value);continue;}
            if(node==null)node=MixinFit.parse(bytes);MethodNode actual=NativeCallChanges.method(node,value.name()+value.descriptor());String name=owner+"."+value.name()+value.descriptor();
            plan.drift.put(value,actual==null?"the instrumented "+name+" is missing from its defined class"
                :"the final body of "+name+" is not the body Forbric instrumented"+SeamDeclines.changedBy(SeamDeclines.contributors(node,actual,Set.of())));
        }
    }
    private static ClassNode unwrapped(ClassLoader loader,ClassNode node){if(node==null)return null;ClassNode result=new ClassNode();node.accept(result);Set<String> restored=new HashSet<>();for(Installed plan:plans(loader)){for(var pair:List.of(Map.entry(plan.graph.firstOwner(),plan.parentOriginal),Map.entry(plan.graph.helper(),plan.helperOriginal)))if(pair.getKey().equals(node.name)&&restored.add(pair.getValue().name+pair.getValue().desc)){MethodNode method=NativeCallChanges.method(result,pair.getValue().name+pair.getValue().desc);if(method!=null&&plan.emitted.stream().anyMatch(value->value.owner().replace('.','/').equals(node.name)&&value.name().equals(method.name)&&value.descriptor().equals(method.desc)&&value.fingerprint().equals(MixinInstructionFingerprint.hash(method))))result.methods.set(result.methods.indexOf(method),copy(pair.getValue()));}}return result;}
    private static List<Installed> plans(ClassLoader loader){synchronized(PLANS){Map<String,Installed> registry=PLANS.get(loader);return registry==null?List.of():List.copyOf(registry.values());}}
    private static boolean merged(MethodNode method,String source){return method.visibleAnnotations!=null&&method.visibleAnnotations.stream().anyMatch(a->a.desc.endsWith("/MixinMerged;")&&source.replace('/','.').equals(MixinFit.value(a,"mixin")));}
    private static void normalize(MethodNode method,String source,String target,Map<String,String> renamed){for(AbstractInsnNode instruction:method.instructions){if(instruction instanceof MethodInsnNode call&&call.owner.equals(source)){call.owner=target;call.name=renamed.getOrDefault(call.name,call.name);}if(instruction instanceof FieldInsnNode field&&field.owner.equals(source))field.owner=target;if(instruction instanceof TypeInsnNode type&&type.desc.equals(source))type.desc=target;if(instruction instanceof InvokeDynamicInsnNode dynamic){dynamic.desc=dynamic.desc.replace("L"+source+";","L"+target+";");for(int i=0;i<dynamic.bsmArgs.length;i++)if(dynamic.bsmArgs[i]instanceof Handle handle&&handle.getOwner().equals(source))dynamic.bsmArgs[i]=new Handle(handle.getTag(),target,renamed.getOrDefault(handle.getName(),handle.getName()),handle.getDesc(),handle.isInterface());}}}
    private static MethodContract contract(String owner,MethodNode method){return new MethodContract(owner,method.name,method.desc,MixinInstructionFingerprint.hash(method));}
    private static MethodNode copy(MethodNode method){MethodNode result=new MethodNode(method.access,method.name,method.desc,method.signature,method.exceptions.toArray(String[]::new));method.accept(result);for(AbstractInsnNode instruction:result.instructions)if(instruction instanceof InvokeDynamicInsnNode dynamic)dynamic.bsmArgs=dynamic.bsmArgs.clone();return result;}
    private static void removeInjector(MethodNode method){if(method.visibleAnnotations!=null)method.visibleAnnotations.removeIf(a->FinalMixinApplications.isInjector(a.desc));if(method.invisibleAnnotations!=null)method.invisibleAnnotations.removeIf(a->FinalMixinApplications.isInjector(a.desc));}
    private static List<MethodInsnNode> anchors(MethodNode method,String member){return Arrays.stream(method.instructions.toArray()).filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).filter(call->NativeCallChanges.member(call).equals(member)).toList();}
    private static Type[] callTypes(MixinFit.Member call,int opcode){Type[] arguments=Type.getArgumentTypes(call.desc());if(opcode==INVOKESTATIC)return arguments;Type[] types=new Type[arguments.length+1];types[0]=Type.getObjectType(call.owner());System.arraycopy(arguments,0,types,1,arguments.length);return types;}
    private static int[] slots(Type[] types,boolean instance){int[] slots=new int[types.length];int slot=instance?1:0;for(int i=0;i<types.length;i++){slots[i]=slot;slot+=types[i].getSize();}return slots;}
    private static int end(Type[] types,boolean instance){return (instance?1:0)+Arrays.stream(types).mapToInt(Type::getSize).sum();}
    private static void array(InsnList code,Type[] types,int[] slots){push(code,types.length);code.add(new TypeInsnNode(ANEWARRAY,"java/lang/Object"));for(int i=0;i<types.length;i++){code.add(new InsnNode(DUP));push(code,i);code.add(new VarInsnNode(types[i].getOpcode(ILOAD),slots[i]));box(code,types[i]);code.add(new InsnNode(AASTORE));}}
    private static void arrayLoad(InsnList code,int local,int index,Type type){code.add(new VarInsnNode(ALOAD,local));push(code,index);code.add(new InsnNode(AALOAD));String boxed=boxed(type);if(boxed==null)code.add(new TypeInsnNode(CHECKCAST,type.getInternalName()));else{code.add(new TypeInsnNode(CHECKCAST,boxed));code.add(new MethodInsnNode(INVOKEVIRTUAL,boxed,type.getClassName()+"Value","()"+type.getDescriptor(),false));}}
    private static String boxed(Type type){return switch(type.getSort()){case Type.BOOLEAN->"java/lang/Boolean";case Type.BYTE->"java/lang/Byte";case Type.CHAR->"java/lang/Character";case Type.SHORT->"java/lang/Short";case Type.INT->"java/lang/Integer";case Type.FLOAT->"java/lang/Float";case Type.LONG->"java/lang/Long";case Type.DOUBLE->"java/lang/Double";default->null;};}
    private static void box(InsnList code,Type type){String boxed=boxed(type);if(boxed!=null)code.add(new MethodInsnNode(INVOKESTATIC,boxed,"valueOf","("+type.getDescriptor()+")L"+boxed+";",false));}
    private static String digest(String text){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0,20);}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static void push(InsnList code,int number){if(number>=0&&number<=5)code.add(new InsnNode(ICONST_0+number));else code.add(new LdcInsnNode(number));}
}
