/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;
import java.util.*;import java.util.function.Function;
import org.objectweb.asm.*;import org.objectweb.asm.tree.*;import org.objectweb.asm.tree.analysis.*;
import net.forbric.api.Ecosystem;import net.forbric.kernel.boot.*;

/** A pure shared-value modifier of a completely unused old parameter follows a proved typed constant branch.
 * Its result is observed then discarded. The carrier's actual typed argument and virtual operation remain intact. */
public final class MixinUnusedArgumentObserverAdapter {
    public static final String PROPERTY="forbric.mixinUnusedArgumentObservers";
    private static final String MODIFY="Lorg/spongepowered/asm/mixin/injection/ModifyArg;",WRAP="Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
    private static final String SHARE="Lcom/llamalad7/mixinextras/sugar/Share;",REF="com/llamalad7/mixinextras/sugar/ref/LocalRef",OP="com/llamalad7/mixinextras/injector/wrapoperation/Operation";
    private static final String EXPRESSION="Lcom/llamalad7/mixinextras/expression/Expression;",DEFINITIONS="Lcom/llamalad7/mixinextras/expression/Definitions;",DEFINITION="Lcom/llamalad7/mixinextras/expression/Definition;";
    private record Source(MethodNode handler,AnnotationNode injection,FieldInsnNode constant,MethodInsnNode consumer,String share) { }
    private record Mapping(MethodNode method,ClassNode owner,FieldInsnNode constant,MethodInsnNode value,TypeInsnNode cast) { }
    private record Move(MethodInsnNode call,int ordinal,Mapping mapping,MethodNode consumer,String token) { }
    private MixinUnusedArgumentObserverAdapter() { }
    public static int adapt(ClassNode mixin,Ecosystem ecosystem,Function<String,ClassNode> classes){return adapt(mixin,classes,n->NativeGameReferences.reference(ecosystem,n));}
    static int adapt(ClassNode mixin,Function<String,ClassNode> classes,Function<String,ClassNode> references) {
        if("off".equalsIgnoreCase(System.getProperty(PROPERTY,"on")))return 0;List<String> owners=MixinFit.mixinTargets(mixin);if(owners.size()!=1)return 0;
        ClassNode current=classes.apply(owners.getFirst()),old=references.apply(owners.getFirst());if(current==null||old==null)return 0;
        List<MethodNode> wrappers=new ArrayList<>();for(MethodNode handler:new ArrayList<>(mixin.methods)) {
            Source source=source(handler);if(source==null||!typedShares(mixin,source))continue;
            List<String> selectors=MixinFit.stringList(MixinFit.value(source.injection(),"method"));if(selectors.size()!=1)continue;
            MethodNode original=MixinStubRebind.bound(old,selectors.getFirst()),host=MixinStubRebind.bound(current,selectors.getFirst());
            MethodNode originalConsumer=declared(old,source.consumer().name,source.consumer().desc),consumer=declared(current,source.consumer().name,source.consumer().desc);
            if(original==null||host==null||originalConsumer==null||consumer==null||!unused(originalConsumer)||!unused(consumer))continue;
            List<MethodInsnNode> calls=calls(original).stream().filter(c->same(c,source.consumer())&&previous(c)instanceof FieldInsnNode f&&same(f,source.constant())).toList();
            if(calls.size()!=1||calls(host).stream().anyMatch(c->same(c,source.consumer())))continue;
            List<AbstractInsnNode> before=code(original);int position=before.indexOf(calls.getFirst());if(position<12||!oldGuard(before,position,source))continue;
            MethodInsnNode shallow=(MethodInsnNode)before.get(position-4);Mapping mapping=mapping(shallow,source,classes,new HashSet<>());if(mapping==null)continue;
            List<Move> found=new ArrayList<>();for(MethodInsnNode candidate:calls(host)) {
                if(candidate.getOpcode()!=Opcodes.INVOKEVIRTUAL||!candidate.owner.equals(current.name)||!candidate.desc.equals("(L"+mapping.cast().desc+";)V"))continue;
                if(!typedConstant(candidate,mapping)||!newGuard(code(host),candidate,before,position,source))continue;
                int ordinal=0;for(MethodInsnNode call:calls(host)) {if(call==candidate)break;if(same(call,candidate))ordinal++;}
                String token=KernelUnusedArgumentObservers.register(contract(current,consumer),contract(mapping.owner(),mapping.method()));found.add(new Move(candidate,ordinal,mapping,consumer,token));
            }
            if(found.size()==1)wrappers.add(wrap(mixin,source,found.getFirst()));
        }
        mixin.methods.addAll(wrappers);return wrappers.size();
    }
    private static Source source(MethodNode method) {
        AnnotationNode injection=MixinFit.injectorOf(method);Type[] args=Type.getArgumentTypes(method.desc);List<AbstractInsnNode> c=code(method);
        if(injection==null||!MODIFY.equals(injection.desc)||(method.access&(Opcodes.ACC_STATIC|Opcodes.ACC_SYNCHRONIZED))!=0||!method.tryCatchBlocks.isEmpty()||MixinFit.value(injection,"slice")!=null||args.length!=2||args[0].getSort()!=Type.OBJECT||!args[1].equals(Type.getObjectType(REF))||!Type.getReturnType(method.desc).equals(args[0])||c.size()!=10)return null;
        AnnotationNode share=parameter(method,1);if(share==null||!SHARE.equals(share.desc)||!(MixinFit.value(share,"value")instanceof String key)||MixinFit.value(share,"namespace")!=null)return null;
        if(!load(c.get(0),2)||!call(c.get(1),Opcodes.INVOKEINTERFACE,REF,"get","()Ljava/lang/Object;")||!(c.get(2)instanceof TypeInsnNode cast)||cast.getOpcode()!=Opcodes.CHECKCAST||!cast.desc.equals(args[0].getInternalName())||!variable(c.get(3),Opcodes.ASTORE,3)||!load(c.get(4),3)||!jump(c.get(5),Opcodes.IFNULL,c.get(8))||!load(c.get(6),3)||!jump(c.get(7),Opcodes.GOTO,c.get(9))||!load(c.get(8),1)||c.get(9).getOpcode()!=Opcodes.ARETURN)return null;
        List<AnnotationNode> all=annotations(method);if(all.stream().filter(a->FinalMixinApplications.isInjector(a.desc)).count()!=1||all.stream().anyMatch(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Group;")))return null;
        List<AnnotationNode> points=MixinFit.atNodes(injection);if(points.size()!=1||!"MIXINEXTRAS:EXPRESSION".equals(MixinFit.value(points.getFirst(),"value")))return null;
        AnnotationNode expression=all.stream().filter(a->EXPRESSION.equals(a.desc)).findFirst().orElse(null);List<String> values=expression==null?List.of():MixinFit.stringList(MixinFit.value(expression,"value"));if(values.size()!=1)return null;
        java.util.regex.Matcher match=java.util.regex.Pattern.compile("this\\.([A-Za-z0-9_$]+)\\(([A-Za-z0-9_$]+)\\)").matcher(values.getFirst());if(!match.matches())return null;
        Map<String,String> fields=new HashMap<>(),methods=new HashMap<>();for(AnnotationNode annotation:all) {
            List<AnnotationNode> definitions=annotation.desc.equals(DEFINITION)?List.of(annotation):annotation.desc.equals(DEFINITIONS)&&MixinFit.value(annotation,"value")instanceof List<?> list?list.stream().filter(AnnotationNode.class::isInstance).map(AnnotationNode.class::cast).toList():List.of();
            for(AnnotationNode definition:definitions){Object id=MixinFit.value(definition,"id");List<String> fs=MixinFit.stringList(MixinFit.value(definition,"field")),ms=MixinFit.stringList(MixinFit.value(definition,"method"));if(id instanceof String name){if(fs.size()==1)fields.put(name,fs.getFirst());if(ms.size()==1)methods.put(name,ms.getFirst());}}
        }
        FieldInsnNode field=field(fields.get(match.group(2)));MethodInsnNode consumer=member(methods.get(match.group(1)));
        return field!=null&&consumer!=null&&field.desc.equals(args[0].getDescriptor())&&consumer.desc.equals("("+args[0].getDescriptor()+")V")?new Source(method,injection,field,consumer,key):null;
    }
    private static boolean unused(MethodNode method){if((method.access&(Opcodes.ACC_STATIC|Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE|Opcodes.ACC_SYNCHRONIZED))!=0)return false;for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof VarInsnNode var&&var.var==1&&(var.getOpcode()>=Opcodes.ILOAD&&var.getOpcode()<=Opcodes.ALOAD||var.getOpcode()==Opcodes.RET)||instruction instanceof IincInsnNode increment&&increment.var==1)return false;return true;}
    private static boolean oldGuard(List<AbstractInsnNode> c,int at,Source source) {
        int start=at-12;return load(c.get(start),0)&&c.get(start+1)instanceof MethodInsnNode first&&first.desc.equals("()Z")&&jump(c.get(start+2),Opcodes.IFEQ,c.get(start+14))&&load(c.get(start+3),0)&&c.get(start+4)instanceof MethodInsnNode second&&second.desc.equals("()Z")&&jump(c.get(start+5),Opcodes.IFEQ,c.get(start+10))&&load(c.get(start+6),0)&&c.get(start+7)instanceof FieldInsnNode constant&&same(constant,source.constant())&&c.get(start+8)instanceof MethodInsnNode shallow&&shallow.desc.equals("("+source.constant().desc+")Z")&&jump(c.get(start+9),Opcodes.IFNE,c.get(start+14))&&load(c.get(start+10),0)&&c.get(start+11)instanceof FieldInsnNode original&&same(original,source.constant())&&c.get(start+13)instanceof JumpInsnNode end&&end.getOpcode()==Opcodes.GOTO;
    }
    private static boolean newGuard(List<AbstractInsnNode> current,MethodInsnNode candidate,List<AbstractInsnNode> old,int oldCall,Source source) {
        int start=oldCall-12;MethodInsnNode first=(MethodInsnNode)old.get(start+1),second=(MethodInsnNode)old.get(start+4),shallow=(MethodInsnNode)old.get(start+8);int site=current.indexOf(candidate);
        if(site<4||!load(current.get(site-4),0))return false;AbstractInsnNode destination=current.get(site-4);
        int found=0;for(int i=0;i+9<current.size();i++)if(load(current.get(i),0)&&current.get(i+1)instanceof MethodInsnNode a&&same(a,first)&&current.get(i+2)instanceof JumpInsnNode skip&&skip.getOpcode()==Opcodes.IFEQ&&load(current.get(i+3),0)&&current.get(i+4)instanceof MethodInsnNode b&&same(b,second)&&jump(current.get(i+5),Opcodes.IFEQ,destination)&&load(current.get(i+6),0)&&current.get(i+7)instanceof FieldInsnNode field&&same(field,source.constant())&&current.get(i+8)instanceof MethodInsnNode test&&same(test,shallow)&&jump(current.get(i+9),Opcodes.IFEQ,destination)&&next(skip.label)==current.get(i+10))found++;
        return found==1;
    }
    private static boolean typedConstant(MethodInsnNode candidate,Mapping mapping){AbstractInsnNode cast=previous(candidate),value=previous(cast),field=previous(value);return cast instanceof TypeInsnNode c&&c.getOpcode()==Opcodes.CHECKCAST&&c.desc.equals(mapping.cast().desc)&&value instanceof MethodInsnNode v&&same(v,mapping.value())&&field instanceof FieldInsnNode f&&same(f,mapping.constant());}
    private static Mapping mapping(MethodInsnNode edge,Source source,Function<String,ClassNode> classes,Set<String> visited) {
        ClassNode owner=classes.apply(edge.owner);MethodNode method=resolve(owner,edge.name,edge.desc,classes,new HashSet<>());if(method==null||!visited.add(edge.owner+edge.name+edge.desc))return null;
        Type tag=Type.getType(source.constant().desc);List<AbstractInsnNode> c=code(method);
        if((method.access&Opcodes.ACC_STATIC)!=0&&method.desc.startsWith("("+tag.getDescriptor()+")"))for(int i=0;i+6<c.size();i++) {
            if(load(c.get(i),0)&&c.get(i+1)instanceof FieldInsnNode field&&same(field,source.constant())&&c.get(i+2)instanceof JumpInsnNode next&&next.getOpcode()==Opcodes.IF_ACMPNE&&c.get(i+3)instanceof FieldInsnNode typed&&typed.getOpcode()==Opcodes.GETSTATIC&&c.get(i+4)instanceof MethodInsnNode value&&value.getOpcode()==Opcodes.INVOKEINTERFACE&&value.desc.equals("()Ljava/lang/Object;")&&c.get(i+5)instanceof TypeInsnNode cast&&cast.getOpcode()==Opcodes.CHECKCAST&&c.get(i+6).getOpcode()==Opcodes.ARETURN&&Type.getReturnType(method.desc).equals(Type.getObjectType(cast.desc)))return new Mapping(method,owner,typed,value,cast);
        }
        List<Mapping> matches=new ArrayList<>();for(MethodInsnNode call:calls(method))if(Arrays.asList(Type.getArgumentTypes(call.desc)).contains(tag)){Mapping match=mapping(call,source,classes,visited);if(match!=null)matches.add(match);}
        return matches.size()==1?matches.getFirst():null;
    }
    /** No share reference escapes. Every write is JVM-typed as the old parameter or null. */
    private static boolean typedShares(ClassNode mixin,Source source) {
        String tag=Type.getArgumentTypes(source.handler().desc)[0].getDescriptor();boolean writer=false;
        for(MethodNode method:mixin.methods)for(int index=0;index<Type.getArgumentTypes(method.desc).length;index++) {
            AnnotationNode annotation=parameter(method,index);if(annotation==null||!SHARE.equals(annotation.desc)||!source.share().equals(MixinFit.value(annotation,"value")))continue;
            AnnotationNode injection=MixinFit.injectorOf(method);if(injection==null||!Objects.equals(MixinFit.value(injection,"method"),MixinFit.value(source.injection(),"method"))||MixinFit.value(annotation,"namespace")!=null)return false;
            int[] slots=DefaultMethodOverloadBridge.slots(Type.getArgumentTypes(method.desc),(method.access&Opcodes.ACC_STATIC)!=0);int reference=slots[index];Sources interpreter=new Sources(method);Frame<SourceValue>[] frames;
            try{frames=new Analyzer<>(interpreter).analyze(mixin.name,method);}catch(AnalyzerException|RuntimeException invalid){return false;}
            AbstractInsnNode token=interpreter.parameters.get(reference);
            for(int n=0;n<method.instructions.size();n++)if(method.instructions.get(n)instanceof MethodInsnNode call) {
                Frame<SourceValue> frame=frames[n];if(frame==null)continue;int inputs=Type.getArgumentTypes(call.desc).length+(call.getOpcode()==Opcodes.INVOKESTATIC?0:1);int start=frame.getStackSize()-inputs;
                for(int v=start;v<frame.getStackSize();v++)if(frame.getStack(v).insns.contains(token)) {
                    if(v!=start||!call.owner.equals(REF)||call.getOpcode()!=Opcodes.INVOKEINTERFACE)return false;
                    if(call.name.equals("get")&&call.desc.equals("()Ljava/lang/Object;"))continue;
                    if(!call.name.equals("set")||!call.desc.equals("(Ljava/lang/Object;)V")||!typed(frame.getStack(start+1),tag,interpreter))return false;writer=true;
                }
            }
            for(int n=0;n<method.instructions.size();n++) {
                AbstractInsnNode instruction=method.instructions.get(n);Frame<SourceValue> frame=frames[n];if(frame==null)continue;
                if(instruction instanceof InvokeDynamicInsnNode dynamic){int arguments=Type.getArgumentTypes(dynamic.desc).length;for(int v=frame.getStackSize()-arguments;v<frame.getStackSize();v++)if(frame.getStack(v).insns.contains(token))return false;}
                if(instruction.getOpcode()==Opcodes.ARETURN||instruction.getOpcode()==Opcodes.PUTSTATIC||instruction.getOpcode()==Opcodes.PUTFIELD||instruction.getOpcode()==Opcodes.AASTORE)if(frame.getStack(frame.getStackSize()-1).insns.contains(token))return false;
                if(instruction.getOpcode()==Opcodes.GETFIELD&&frame.getStack(frame.getStackSize()-1).insns.contains(token))return false;
                if(instruction.getOpcode()==Opcodes.PUTFIELD&&frame.getStack(frame.getStackSize()-2).insns.contains(token))return false;
            }
        }
        return writer;
    }
    private static boolean typed(SourceValue value,String tag,Sources interpreter){if(value.insns.isEmpty())return false;for(AbstractInsnNode instruction:value.insns){if(instruction.getOpcode()==Opcodes.ACONST_NULL)continue;if(instruction instanceof TypeInsnNode cast&&cast.getOpcode()==Opcodes.CHECKCAST&&("L"+cast.desc+";").equals(tag))continue;if(instruction instanceof FieldInsnNode field&&field.desc.equals(tag))continue;if(instruction instanceof MethodInsnNode method&&Type.getReturnType(method.desc).getDescriptor().equals(tag))continue;String type=interpreter.types.get(instruction);if(tag.equals(type))continue;return false;}return true;}
    private static final class Sources extends SourceInterpreter {
        final Map<Integer,AbstractInsnNode> parameters=new HashMap<>();final Map<AbstractInsnNode,String> types=new IdentityHashMap<>();
        Sources(MethodNode method){super(Opcodes.ASM9);}
        @Override public SourceValue newParameterValue(boolean instance,int local,Type type){InsnNode token=new InsnNode(Opcodes.NOP);parameters.put(local,token);types.put(token,type.getDescriptor());return new SourceValue(type.getSize(),token);}
        @Override public SourceValue copyOperation(AbstractInsnNode instruction,SourceValue value){return value;}
        @Override public SourceValue unaryOperation(AbstractInsnNode instruction,SourceValue value){
            if(instruction.getOpcode()==Opcodes.CHECKCAST&&value.insns.stream().anyMatch(i->("L"+REF+";").equals(types.get(i))))return value;
            return super.unaryOperation(instruction,value);
        }
    }
    private static MethodNode wrap(ClassNode mixin,Source source,Move move) {
        String old=source.handler().name,aside=MixinHandlerShim.asideName(mixin.name,old,"$forbricobserver");Type typed=Type.getArgumentTypes(move.call().desc)[0];String receiver="L"+move.call().owner+";";
        MethodNode wrapper=new MethodNode(source.handler().access,old,"("+receiver+typed.getDescriptor()+"L"+OP+";L"+REF+";)V",null,null);
        AnnotationNode at=new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");at.values=new ArrayList<>(List.of("value","INVOKE","target",member(move.call()),"ordinal",move.ordinal()));AnnotationNode injection=new AnnotationNode(WRAP);injection.values=new ArrayList<>(List.of("method",MixinFit.value(source.injection(),"method"),"at",at));wrapper.visibleAnnotations=new ArrayList<>(List.of(injection));
        @SuppressWarnings("unchecked")List<AnnotationNode>[] parameters=new List[4];parameters[3]=new ArrayList<>(List.of(parameter(source.handler(),1)));wrapper.invisibleParameterAnnotations=parameters;
        LabelNode nativeCall=new LabelNode();InsnList c=wrapper.instructions;c.add(new VarInsnNode(Opcodes.ALOAD,1));c.add(new LdcInsnNode(move.token()));c.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"net/forbric/kernel/boot/KernelUnusedArgumentObservers","permits","(Ljava/lang/Object;Ljava/lang/String;)Z",false));c.add(new JumpInsnNode(Opcodes.IFEQ,nativeCall));c.add(new VarInsnNode(Opcodes.ALOAD,0));c.add(new FieldInsnNode(Opcodes.GETSTATIC,source.constant().owner,source.constant().name,source.constant().desc));c.add(new VarInsnNode(Opcodes.ALOAD,4));c.add(MixinHandlerShim.callOwn(mixin,false,aside,source.handler().desc));c.add(new InsnNode(Opcodes.POP));c.add(nativeCall);c.add(new VarInsnNode(Opcodes.ALOAD,3));c.add(new InsnNode(Opcodes.ICONST_2));c.add(new TypeInsnNode(Opcodes.ANEWARRAY,"java/lang/Object"));for(int i=0;i<2;i++){c.add(new InsnNode(Opcodes.DUP));c.add(new InsnNode(Opcodes.ICONST_0+i));c.add(new VarInsnNode(Opcodes.ALOAD,1+i));c.add(new InsnNode(Opcodes.AASTORE));}c.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,OP,"call","([Ljava/lang/Object;)Ljava/lang/Object;",true));c.add(new InsnNode(Opcodes.POP));c.add(new InsnNode(Opcodes.RETURN));wrapper.maxLocals=5;wrapper.maxStack=5;
        source.handler().visibleAnnotations=without(source.handler().visibleAnnotations,source.injection());source.handler().invisibleAnnotations=without(source.handler().invisibleAnnotations,source.injection());source.handler().name=aside;return wrapper;
    }
    private static List<AnnotationNode> without(List<AnnotationNode> input,AnnotationNode injection){if(input==null)return null;return new ArrayList<>(input.stream().filter(a->a!=injection&&!a.desc.equals(EXPRESSION)&&!a.desc.equals(DEFINITIONS)&&!a.desc.equals(DEFINITION)).toList());}
    private static MethodNode resolve(ClassNode owner,String name,String desc,Function<String,ClassNode> classes,Set<String> seen){if(owner==null||!seen.add(owner.name))return null;MethodNode method=declared(owner,name,desc);if(method!=null)return method;if(owner.superName!=null){method=resolve(classes.apply(owner.superName),name,desc,classes,seen);if(method!=null)return method;}for(String i:owner.interfaces){method=resolve(classes.apply(i),name,desc,classes,seen);if(method!=null)return method;}return null;}
    private static DefinedMethodContracts.MethodContract contract(ClassNode owner,MethodNode method){return new DefinedMethodContracts.MethodContract(owner.name,method.name,method.desc,DefinedMethodContracts.fingerprint(method));}
    private static MethodNode declared(ClassNode owner,String name,String desc){return owner.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElse(null);}
    private static List<AbstractInsnNode> code(MethodNode method){return DefaultMethodOverloadBridge.real(method);}
    private static List<MethodInsnNode> calls(MethodNode method){return Arrays.stream(method.instructions.toArray()).filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).toList();}
    private static FieldInsnNode field(String value){if(value==null)return null;int owner=value.indexOf(';'),desc=value.indexOf(':',owner);return owner>1&&desc>owner?new FieldInsnNode(Opcodes.GETSTATIC,value.substring(1,owner),value.substring(owner+1,desc),value.substring(desc+1)):null;}
    private static MethodInsnNode member(String value){if(value==null)return null;int owner=value.indexOf(';'),desc=value.indexOf('(',owner);return owner>1&&desc>owner?new MethodInsnNode(Opcodes.INVOKEVIRTUAL,value.substring(1,owner),value.substring(owner+1,desc),value.substring(desc),false):null;}
    private static String member(MethodInsnNode call){return "L"+call.owner+";"+call.name+call.desc;}
    private static boolean same(MethodInsnNode a,MethodInsnNode b){return a.getOpcode()==b.getOpcode()&&a.owner.equals(b.owner)&&a.name.equals(b.name)&&a.desc.equals(b.desc)&&a.itf==b.itf;}
    private static boolean same(FieldInsnNode a,FieldInsnNode b){return a.getOpcode()==b.getOpcode()&&a.owner.equals(b.owner)&&a.name.equals(b.name)&&a.desc.equals(b.desc);}
    private static boolean variable(AbstractInsnNode instruction,int opcode,int slot){return instruction instanceof VarInsnNode var&&var.getOpcode()==opcode&&var.var==slot;}
    private static boolean load(AbstractInsnNode instruction,int slot){return variable(instruction,Opcodes.ALOAD,slot);}
    private static boolean call(AbstractInsnNode instruction,int opcode,String owner,String name,String desc){return instruction instanceof MethodInsnNode c&&c.getOpcode()==opcode&&c.owner.equals(owner)&&c.name.equals(name)&&c.desc.equals(desc);}
    private static boolean jump(AbstractInsnNode instruction,int opcode,AbstractInsnNode target){return instruction instanceof JumpInsnNode j&&j.getOpcode()==opcode&&next(j.label)==target;}
    private static AbstractInsnNode next(AbstractInsnNode instruction){do{instruction=instruction.getNext();}while(instruction!=null&&instruction.getOpcode()<0);return instruction;}
    private static AbstractInsnNode previous(AbstractInsnNode instruction){do{instruction=instruction.getPrevious();}while(instruction!=null&&instruction.getOpcode()<0);return instruction;}
    private static AnnotationNode parameter(MethodNode method,int index){List<AnnotationNode> all=new ArrayList<>();if(method.visibleParameterAnnotations!=null&&index<method.visibleParameterAnnotations.length&&method.visibleParameterAnnotations[index]!=null)all.addAll(method.visibleParameterAnnotations[index]);if(method.invisibleParameterAnnotations!=null&&index<method.invisibleParameterAnnotations.length&&method.invisibleParameterAnnotations[index]!=null)all.addAll(method.invisibleParameterAnnotations[index]);return all.size()==1?all.getFirst():null;}
    private static List<AnnotationNode> annotations(MethodNode method){List<AnnotationNode> all=new ArrayList<>();if(method.visibleAnnotations!=null)all.addAll(method.visibleAnnotations);if(method.invisibleAnnotations!=null)all.addAll(method.invisibleAnnotations);return all;}
}
