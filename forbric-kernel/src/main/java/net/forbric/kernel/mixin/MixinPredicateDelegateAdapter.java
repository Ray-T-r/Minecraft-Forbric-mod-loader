/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import java.util.function.Function;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.boot.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Lifts a closed static delegate graph so a pure OR decorator can follow its native Boolean result.
 * Existing query values, callbacks, ordering and virtual external calls stay in the native graph. */
public final class MixinPredicateDelegateAdapter {
    public static final String PROPERTY = "forbric.mixinPredicateDelegates";
    private static final String WRAP = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
    private static final String OP = "com/llamalad7/mixinextras/injector/wrapoperation/Operation";
    private static final String ENTRY = "java/util/Map$Entry", LOCAL = "Lcom/llamalad7/mixinextras/sugar/Local;";
    private static final String RUNTIME = "net/forbric/kernel/boot/KernelPredicateDelegates";
    private static final Handle LAMBDA = new Handle(Opcodes.H_INVOKESTATIC,"java/lang/invoke/LambdaMetafactory","metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",false);
    private record Source(MethodNode handler, AnnotationNode injection, MethodInsnNode getter, String keyType) { }
    private record Graph(ClassNode owner, MethodNode root, List<MethodNode> methods, MethodNode predicate, List<DefinedMethodContracts.MethodContract> witnesses) { }
    private MixinPredicateDelegateAdapter() { }
    public static int adapt(ClassNode mixin, Ecosystem ecosystem, Function<String,ClassNode> classes) {
        return adapt(mixin,classes,name->NativeGameReferences.reference(ecosystem,name));
    }
    static int adapt(ClassNode mixin,Function<String,ClassNode> classes,Function<String,ClassNode> references) {
        if("off".equalsIgnoreCase(System.getProperty(PROPERTY,"on")))return 0;
        List<String> owners=MixinFit.mixinTargets(mixin);if(owners.size()!=1)return 0;
        ClassNode current=classes.apply(owners.getFirst()), original=references.apply(owners.getFirst());
        if(current==null||original==null)return 0;
        List<MethodNode> added=new ArrayList<>();int changed=0;
        for(MethodNode handler:new ArrayList<>(mixin.methods)) {
            Source source=source(handler);if(source==null)continue;
            List<String> selectors=MixinFit.stringList(MixinFit.value(source.injection(),"method"));if(selectors.size()!=1)continue;
            MethodNode before=MixinStubRebind.bound(original,selectors.getFirst()),after=MixinStubRebind.bound(current,selectors.getFirst());
            if(before==null||after==null||!before.desc.equals(after.desc)||!before.desc.equals("(Ljava/lang/String;)V"))continue;
            String anchor=MixinFit.asString(MixinFit.value(MixinFit.atNodes(source.injection()).getFirst(),"target"));
            List<MethodInsnNode> oldCalls=calls(before).stream().filter(c->member(c).equals(anchor)).toList();
            if(oldCalls.size()!=1||calls(after).stream().anyMatch(c->member(c).equals(anchor)))continue;
            if(!captureAtOriginal(source,before,oldCalls.getFirst()))continue;
            List<MethodInsnNode> providers=calls(before).stream().filter(MixinPredicateDelegateAdapter::mapProvider).toList();if(providers.size()!=1)continue;
            List<MethodInsnNode> delegates=calls(after).stream().filter(c->c.getOpcode()==Opcodes.INVOKESTATIC
                    && c.desc.equals("(Ljava/lang/String;Ljava/util/function/Consumer;Ljava/util/function/Consumer;)V")).toList();
            if(delegates.size()!=1)continue;
            MethodInsnNode delegate=delegates.getFirst();Graph graph=graph(delegate,providers.getFirst(),source,classes);if(graph==null)continue;
            String token=KernelPredicateDelegates.register(graph.owner().name,graph.witnesses());
            added.addAll(install(mixin,source,delegate,graph,token));changed++;
        }
        mixin.methods.addAll(added);return changed;
    }
    private static Source source(MethodNode method) {
        AnnotationNode injection=MixinFit.injectorOf(method);Type[] args=Type.getArgumentTypes(method.desc);
        if(injection==null||!WRAP.equals(injection.desc)||(method.access&(Opcodes.ACC_STATIC|Opcodes.ACC_SYNCHRONIZED))!=0
                ||!method.tryCatchBlocks.isEmpty()||MixinFit.value(injection,"slice")!=null||args.length!=4
                ||!args[0].equals(Type.getType(String.class))||!args[1].equals(Type.getType(CharSequence.class))
                ||!args[2].equals(Type.getObjectType(OP))||!args[3].equals(Type.getObjectType(ENTRY))
                ||!Type.getReturnType(method.desc).equals(Type.BOOLEAN_TYPE))return null;
        List<AnnotationNode> all=annotations(method);
        if(all.stream().filter(a->FinalMixinApplications.isInjector(a.desc)).count()!=1
                ||all.stream().anyMatch(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Group;")))return null;
        List<AnnotationNode> ats=MixinFit.atNodes(injection);if(ats.size()!=1||!"INVOKE".equals(MixinFit.value(ats.getFirst(),"value"))
                ||MixinFit.value(ats.getFirst(),"ordinal")!=null||MixinFit.value(ats.getFirst(),"shift")!=null)return null;
        List<AbstractInsnNode> c=code(method);
        if(c.size()!=32||!load(c.get(0),4)||!call(c.get(1),Opcodes.INVOKEINTERFACE,ENTRY,"getKey","()Ljava/lang/Object;")
                ||!(c.get(2)instanceof TypeInsnNode key)||key.getOpcode()!=Opcodes.CHECKCAST||!(c.get(3)instanceof MethodInsnNode getter)
                ||getter.getOpcode()!=Opcodes.INVOKEVIRTUAL||!getter.owner.equals(key.desc)||!getter.desc.equals("()Ljava/lang/String;")
                ||!variable(c.get(4),Opcodes.ASTORE,5)||!load(c.get(5),3)||c.get(6).getOpcode()!=Opcodes.ICONST_2
                ||!(c.get(7)instanceof TypeInsnNode array)||array.getOpcode()!=Opcodes.ANEWARRAY||!array.desc.equals("java/lang/Object")
                ||c.get(8).getOpcode()!=Opcodes.DUP||c.get(9).getOpcode()!=Opcodes.ICONST_0||!load(c.get(10),1)||c.get(11).getOpcode()!=Opcodes.AASTORE
                ||c.get(12).getOpcode()!=Opcodes.DUP||c.get(13).getOpcode()!=Opcodes.ICONST_1||!load(c.get(14),2)||c.get(15).getOpcode()!=Opcodes.AASTORE
                ||!call(c.get(16),Opcodes.INVOKEINTERFACE,OP,"call","([Ljava/lang/Object;)Ljava/lang/Object;")
                ||!(c.get(17)instanceof TypeInsnNode box)||box.getOpcode()!=Opcodes.CHECKCAST||!box.desc.equals("java/lang/Boolean")
                ||!call(c.get(18),Opcodes.INVOKEVIRTUAL,"java/lang/Boolean","booleanValue","()Z")||!jump(c.get(19),Opcodes.IFNE,c.get(28))
                ||!(c.get(20)instanceof LdcInsnNode literal)||!(literal.cst instanceof String)||!load(c.get(21),5)
                ||!call(c.get(22),Opcodes.INVOKEVIRTUAL,"java/lang/String","equals","(Ljava/lang/Object;)Z")||!jump(c.get(23),Opcodes.IFNE,c.get(30))
                ||!load(c.get(24),5)||!load(c.get(25),2)||!call(c.get(26),Opcodes.INVOKEVIRTUAL,"java/lang/String","contains","(Ljava/lang/CharSequence;)Z")
                ||!jump(c.get(27),Opcodes.IFEQ,c.get(30))||c.get(28).getOpcode()!=Opcodes.ICONST_1||!jump(c.get(29),Opcodes.GOTO,c.get(31))
                ||c.get(30).getOpcode()!=Opcodes.ICONST_0||c.get(31).getOpcode()!=Opcodes.IRETURN)return null;
        return new Source(method,injection,getter,key.desc);
    }
    private static boolean captureAtOriginal(Source source,MethodNode original,MethodInsnNode call) {
        AnnotationNode local=parameter(source.handler(),3);if(local==null||!LOCAL.equals(local.desc))return false;
        List<String> names=MixinFit.stringList(MixinFit.value(local,"name"));if(names.size()!=1||original.localVariables==null)return false;
        int pos=original.instructions.indexOf(call);
        return original.localVariables.stream().filter(v->v.name.equals(names.getFirst())&&v.desc.equals("L"+ENTRY+";")
                &&original.instructions.indexOf(v.start)<=pos&&pos<original.instructions.indexOf(v.end)).count()==1;
    }
    private static Graph graph(MethodInsnNode edge,MethodInsnNode provider,Source source,Function<String,ClassNode> classes) {
        ClassNode owner=classes.apply(edge.owner);if(owner==null||!"java/lang/Object".equals(owner.superName))return null;
        MethodNode root=declared(owner,edge.name,edge.desc);if(root==null)return null;
        LinkedHashSet<MethodNode> methods=new LinkedHashSet<>();ArrayDeque<MethodNode> pending=new ArrayDeque<>();pending.add(root);
        while(!pending.isEmpty()) {
            MethodNode method=pending.remove();if(!methods.add(method))continue;
            if((method.access&Opcodes.ACC_STATIC)==0||(method.access&(Opcodes.ACC_NATIVE|Opcodes.ACC_ABSTRACT|Opcodes.ACC_SYNCHRONIZED))!=0)return null;
            for(AbstractInsnNode instruction:method.instructions) {
                if(instruction.getOpcode()==Opcodes.MONITORENTER||instruction.getOpcode()==Opcodes.MONITOREXIT)return null;
                if(instruction instanceof MethodInsnNode call&&call.owner.equals(owner.name)) {
                    MethodNode callee=declared(owner,call.name,call.desc);if(callee==null||call.getOpcode()!=Opcodes.INVOKESTATIC||(callee.access&Opcodes.ACC_PRIVATE)==0)return null;pending.add(callee);
                }
                if(instruction instanceof FieldInsnNode field&&field.owner.equals(owner.name)
                        &&owner.fields.stream().noneMatch(f->f.name.equals(field.name)&&f.desc.equals(field.desc)
                        &&(f.access&(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC))==(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC)))return null;
                if(instruction instanceof MethodInsnNode call&&!call.owner.equals(owner.name)&&samePackage(call.owner,owner.name)) {
                    ClassNode type=classes.apply(call.owner);MethodNode member=type==null?null:declared(type,call.name,call.desc);
                    if(member==null||(member.access&Opcodes.ACC_PUBLIC)==0||(type.access&Opcodes.ACC_PUBLIC)==0)return null;
                }
                if(instruction instanceof FieldInsnNode field&&!field.owner.equals(owner.name)&&samePackage(field.owner,owner.name)) {
                    ClassNode type=classes.apply(field.owner);
                    if(type==null||(type.access&Opcodes.ACC_PUBLIC)==0||type.fields.stream().noneMatch(f->f.name.equals(field.name)&&f.desc.equals(field.desc)&&(f.access&Opcodes.ACC_PUBLIC)!=0))return null;
                }
                if(instruction instanceof InvokeDynamicInsnNode dynamic) for(Object argument:dynamic.bsmArgs) {
                    if(argument instanceof Handle handle&&handle.getOwner().equals(owner.name)) {
                        if(!dynamic.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")||handle.getTag()!=Opcodes.H_INVOKESTATIC)return null;
                        MethodNode callee=declared(owner,handle.getName(),handle.getDesc());if(callee==null||(callee.access&Opcodes.ACC_PRIVATE)==0)return null;pending.add(callee);
                    }
                }
                if(instruction instanceof LdcInsnNode constant&&(constant.cst instanceof Handle||constant.cst instanceof ConstantDynamic))return null;
            }
        }
        if(methods.stream().flatMap(m->calls(m).stream()).filter(c->same(c,provider)).count()!=1)return null;
        ClassNode providerOwner=classes.apply(provider.owner);MethodNode providerMethod=providerOwner==null?null:declared(providerOwner,provider.name,provider.desc);
        if(providerMethod==null||!immutableMapProvider(providerMethod))return null;
        List<MethodNode> predicates=methods.stream().filter(m->m.desc.equals("(Ljava/lang/String;L"+source.keyType()+";)Z")
                &&calls(m).stream().anyMatch(c->same(c,source.getter()))&&readOnlyParameters(m,2)).toList();
        if(predicates.size()!=1)return null;
        List<DefinedMethodContracts.MethodContract> witnesses=new ArrayList<>();
        for(MethodNode method:methods)witnesses.add(new DefinedMethodContracts.MethodContract(owner.name,method.name,method.desc,DefinedMethodContracts.fingerprint(method)));
        witnesses.add(new DefinedMethodContracts.MethodContract(provider.owner,provider.name,provider.desc,DefinedMethodContracts.fingerprint(providerMethod)));
        return new Graph(owner,root,List.copyOf(methods),predicates.getFirst(),List.copyOf(witnesses));
    }
    private static boolean readOnlyParameters(MethodNode method,int slots) {
        for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof VarInsnNode var&&var.getOpcode()>=Opcodes.ISTORE&&var.getOpcode()<=Opcodes.ASTORE&&var.var<slots
                ||instruction instanceof IincInsnNode increment&&increment.var<slots)return false;return true;
    }
    private static boolean immutableMapProvider(MethodNode method) {
        List<AbstractInsnNode> c=code(method);
        return(method.access&Opcodes.ACC_STATIC)!=0&&method.tryCatchBlocks.isEmpty()&&c.size()==3&&c.get(0)instanceof FieldInsnNode field
                &&field.getOpcode()==Opcodes.GETSTATIC&&field.desc.equals("Ljava/util/Map;")
                &&call(c.get(1),Opcodes.INVOKESTATIC,"java/util/Map","copyOf","(Ljava/util/Map;)Ljava/util/Map;")&&c.get(2).getOpcode()==Opcodes.ARETURN;
    }
    private static List<MethodNode> install(ClassNode mixin,Source source,MethodInsnNode edge,Graph graph,String token) {
        String oldName=source.handler().name,aside=MixinHandlerShim.asideName(mixin.name,oldName,"$forbricpredicate");
        Map<String,String> names=new HashMap<>();for(MethodNode method:graph.methods())names.put(method.name+method.desc,
                MixinHandlerShim.asideName(mixin.name,oldName+"$"+method.name,"$forbricdelegate"));
        String constant=MixinHandlerShim.asideName(mixin.name,oldName,"$forbricresult");List<MethodNode> added=new ArrayList<>();
        for(MethodNode original:graph.methods()) {
            MethodNode copy=new MethodNode();original.accept(copy);copy.name=names.get(original.name+original.desc);copy.desc=context(original.desc);copy.access=Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC|Opcodes.ACC_SYNTHETIC;
            copy.visibleAnnotations=null;copy.invisibleAnnotations=null;copy.visibleParameterAnnotations=null;copy.invisibleParameterAnnotations=null;copy.localVariables=null;copy.signature=null;
            for(AbstractInsnNode instruction:copy.instructions.toArray()) {
                if(instruction instanceof FrameNode)copy.instructions.remove(instruction);
                else if(instruction instanceof VarInsnNode variable)variable.var++;
                else if(instruction instanceof IincInsnNode increment)increment.var++;
            }
            copy.maxLocals++;
            for(AbstractInsnNode instruction:copy.instructions.toArray()) {
                if(instruction instanceof MethodInsnNode call&&call.owner.equals(graph.owner().name)) {
                    prependContext(copy,call,Type.getArgumentTypes(call.desc));call.name=names.get(call.name+call.desc);call.owner=mixin.name;call.desc=context(call.desc);call.itf=false;
                } else if(instruction instanceof InvokeDynamicInsnNode dynamic) {
                    dynamic.bsmArgs=dynamic.bsmArgs.clone();
                    boolean owned=false;for(Object argument:dynamic.bsmArgs)if(argument instanceof Handle h&&h.getOwner().equals(graph.owner().name))owned=true;
                    if(owned) {
                        prependContext(copy,dynamic,Type.getArgumentTypes(dynamic.desc));dynamic.desc=context(dynamic.desc);
                        for(int i=0;i<dynamic.bsmArgs.length;i++)if(dynamic.bsmArgs[i]instanceof Handle handle&&handle.getOwner().equals(graph.owner().name))
                            dynamic.bsmArgs[i]=new Handle(handle.getTag(),mixin.name,names.get(handle.getName()+handle.getDesc()),context(handle.getDesc()),false);
                    }
                }
            }
            if(original==graph.predicate())decorate(copy,mixin,aside,source.handler().desc,constant);
            copy.maxStack+=9;added.add(copy);
        }
        MethodNode value=new MethodNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC|Opcodes.ACC_SYNTHETIC,constant,"(Z[Ljava/lang/Object;)Ljava/lang/Object;",null,null);
        value.instructions.add(new VarInsnNode(Opcodes.ILOAD,0));value.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"java/lang/Boolean","valueOf","(Z)Ljava/lang/Boolean;",false));value.instructions.add(new InsnNode(Opcodes.ARETURN));value.maxLocals=2;value.maxStack=1;added.add(value);
        Type[] nativeArgs=Type.getArgumentTypes(edge.desc),outerArgs=Arrays.copyOf(nativeArgs,nativeArgs.length+1);outerArgs[nativeArgs.length]=Type.getObjectType(OP);
        MethodNode outer=new MethodNode(source.handler().access,oldName,Type.getMethodDescriptor(Type.VOID_TYPE,outerArgs),null,null);
        boolean visible=source.handler().visibleAnnotations!=null&&source.handler().visibleAnnotations.remove(source.injection());if(!visible)source.handler().invisibleAnnotations.remove(source.injection());
        if(visible)outer.visibleAnnotations=new ArrayList<>(List.of(source.injection()));else outer.invisibleAnnotations=new ArrayList<>(List.of(source.injection()));
        set(MixinFit.atNodes(source.injection()).getFirst(),"target",member(edge));
        int[] slots=DefaultMethodOverloadBridge.slots(outerArgs,false);LabelNode fallback=new LabelNode();
        outer.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));outer.instructions.add(new LdcInsnNode(token));outer.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,RUNTIME,"permits","(Ljava/lang/Object;Ljava/lang/String;)Z",false));outer.instructions.add(new JumpInsnNode(Opcodes.IFEQ,fallback));
        outer.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));for(int i=0;i<nativeArgs.length;i++)outer.instructions.add(new VarInsnNode(Opcodes.ALOAD,slots[i]));
        outer.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,mixin.name,names.get(graph.root().name+graph.root().desc),context(graph.root().desc),false));outer.instructions.add(new InsnNode(Opcodes.RETURN));
        outer.instructions.add(fallback);outer.instructions.add(new VarInsnNode(Opcodes.ALOAD,slots[nativeArgs.length]));outer.instructions.add(new InsnNode(Opcodes.ICONST_3));outer.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY,"java/lang/Object"));
        for(int i=0;i<3;i++){outer.instructions.add(new InsnNode(Opcodes.DUP));outer.instructions.add(new InsnNode(Opcodes.ICONST_0+i));outer.instructions.add(new VarInsnNode(Opcodes.ALOAD,slots[i]));outer.instructions.add(new InsnNode(Opcodes.AASTORE));}
        outer.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,OP,"call","([Ljava/lang/Object;)Ljava/lang/Object;",true));outer.instructions.add(new InsnNode(Opcodes.POP));outer.instructions.add(new InsnNode(Opcodes.RETURN));outer.maxLocals=5;outer.maxStack=7;added.add(outer);
        source.handler().name=aside;return added;
    }
    private static void decorate(MethodNode method,ClassNode mixin,String callback,String descriptor,String constant) {
        int result=method.maxLocals++;
        for(AbstractInsnNode instruction:method.instructions.toArray())if(instruction.getOpcode()==Opcodes.IRETURN) {
            InsnList c=new InsnList();c.add(new VarInsnNode(Opcodes.ISTORE,result));c.add(new VarInsnNode(Opcodes.ALOAD,0));c.add(new TypeInsnNode(Opcodes.CHECKCAST,mixin.name));c.add(new LdcInsnNode(""));c.add(new VarInsnNode(Opcodes.ALOAD,1));c.add(new VarInsnNode(Opcodes.ILOAD,result));
            c.add(new InvokeDynamicInsnNode("call","(Z)L"+OP+";",LAMBDA,Type.getMethodType("([Ljava/lang/Object;)Ljava/lang/Object;"),new Handle(Opcodes.H_INVOKESTATIC,mixin.name,constant,"(Z[Ljava/lang/Object;)Ljava/lang/Object;",false),Type.getMethodType("([Ljava/lang/Object;)Ljava/lang/Object;")));
            c.add(new TypeInsnNode(Opcodes.NEW,"java/util/AbstractMap$SimpleImmutableEntry"));c.add(new InsnNode(Opcodes.DUP));c.add(new VarInsnNode(Opcodes.ALOAD,2));c.add(new InsnNode(Opcodes.ACONST_NULL));c.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,"java/util/AbstractMap$SimpleImmutableEntry","<init>","(Ljava/lang/Object;Ljava/lang/Object;)V",false));
            c.add(MixinHandlerShim.callOwn(mixin,false,callback,descriptor));method.instructions.insertBefore(instruction,c);
        }
    }
    private static void prependContext(MethodNode method,AbstractInsnNode point,Type[] args) {
        int[] slots=new int[args.length];int next=method.maxLocals;for(int i=0;i<args.length;i++){slots[i]=next;next+=args[i].getSize();}method.maxLocals=next;
        InsnList c=new InsnList();for(int i=args.length-1;i>=0;i--)c.add(new VarInsnNode(args[i].getOpcode(Opcodes.ISTORE),slots[i]));c.add(new VarInsnNode(Opcodes.ALOAD,0));for(int i=0;i<args.length;i++)c.add(new VarInsnNode(args[i].getOpcode(Opcodes.ILOAD),slots[i]));method.instructions.insertBefore(point,c);
    }
    private static String context(String descriptor) {return "(Ljava/lang/Object;"+descriptor.substring(1);}
    private static boolean samePackage(String a,String b){return a.substring(0,a.lastIndexOf('/')+1).equals(b.substring(0,b.lastIndexOf('/')+1));}
    private static boolean mapProvider(MethodInsnNode call){return call.getOpcode()==Opcodes.INVOKESTATIC&&call.desc.equals("()Ljava/util/Map;");}
    private static List<MethodInsnNode> calls(MethodNode method){return Arrays.stream(method.instructions.toArray()).filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).toList();}
    private static List<AbstractInsnNode> code(MethodNode method){return DefaultMethodOverloadBridge.real(method);}
    private static MethodNode declared(ClassNode owner,String name,String desc){return owner.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElse(null);}
    private static String member(MethodInsnNode call){return "L"+call.owner+";"+call.name+call.desc;}
    private static boolean same(MethodInsnNode a,MethodInsnNode b){return a.getOpcode()==b.getOpcode()&&a.owner.equals(b.owner)&&a.name.equals(b.name)&&a.desc.equals(b.desc)&&a.itf==b.itf;}
    private static boolean variable(AbstractInsnNode instruction,int opcode,int slot){return instruction instanceof VarInsnNode var&&var.getOpcode()==opcode&&var.var==slot;}
    private static boolean load(AbstractInsnNode instruction,int slot){return variable(instruction,Opcodes.ALOAD,slot);}
    private static boolean call(AbstractInsnNode instruction,int opcode,String owner,String name,String desc){return instruction instanceof MethodInsnNode c&&c.getOpcode()==opcode&&c.owner.equals(owner)&&c.name.equals(name)&&c.desc.equals(desc);}
    private static boolean jump(AbstractInsnNode instruction,int opcode,AbstractInsnNode target){return instruction instanceof JumpInsnNode j&&j.getOpcode()==opcode&&next(j.label)==target;}
    private static AbstractInsnNode next(AbstractInsnNode instruction){do{instruction=instruction.getNext();}while(instruction!=null&&instruction.getOpcode()<0);return instruction;}
    private static AnnotationNode parameter(MethodNode method,int index){List<AnnotationNode> all=new ArrayList<>();if(method.visibleParameterAnnotations!=null&&index<method.visibleParameterAnnotations.length&&method.visibleParameterAnnotations[index]!=null)all.addAll(method.visibleParameterAnnotations[index]);if(method.invisibleParameterAnnotations!=null&&index<method.invisibleParameterAnnotations.length&&method.invisibleParameterAnnotations[index]!=null)all.addAll(method.invisibleParameterAnnotations[index]);return all.size()==1?all.getFirst():null;}
    private static List<AnnotationNode> annotations(MethodNode method){List<AnnotationNode> all=new ArrayList<>();if(method.visibleAnnotations!=null)all.addAll(method.visibleAnnotations);if(method.invisibleAnnotations!=null)all.addAll(method.invisibleAnnotations);return all;}
    private static void set(AnnotationNode annotation,String key,Object value){for(int i=0;i+1<annotation.values.size();i+=2)if(key.equals(annotation.values.get(i))){annotation.values.set(i+1,value);return;}annotation.values.add(key);annotation.values.add(value);}
}
