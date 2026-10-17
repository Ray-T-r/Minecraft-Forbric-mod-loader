/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;
import java.util.*;import java.util.function.Function;
import net.forbric.api.Ecosystem;import net.forbric.kernel.boot.*;
import org.objectweb.asm.*;import org.objectweb.asm.tree.*;

/** A namespace factory wrapper whose wrapped factory call the merged host replaced follows the host's own suffix-parse/prefix
 * continuation into its final sink. The original body remains intact. Its fallback Operation receives the live resource and
 * prefix unchanged.
 *
 * <p>The guest handler is admitted by its data flow alone ({@link HandlerFlow}), never by its instructions: a
 * {@code @WrapOperation} of one {@code (String)} call whose only use of its {@code Operation} is to call it with its own,
 * unchanged {@code String}. Whatever else it computes — its own namespace test, its own path recipe — runs as written, on
 * the argument the native call would have been handed. Everything the move needs about the call is read from the native
 * host and the platform: the wrapped call is the platform's default-namespace factory, its argument a one-hole string recipe
 * over a final field's name, and the merged host builds the same field's value as {@code parse(name + suffix)} followed by
 * the platform's prefix continuation into the same sink. */
public final class MixinResourceContinuationAdapter {
    public static final String PROPERTY="forbric.mixinResourceContinuations";
    private static final String WRAP="Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",OP="com/llamalad7/mixinextras/injector/wrapoperation/Operation";
    private static final String RUNTIME="net/forbric/kernel/boot/KernelResourceContinuations";
    private static final Handle LAMBDA=new Handle(Opcodes.H_INVOKESTATIC,"java/lang/invoke/LambdaMetafactory","metafactory","(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",false);
    private record Source(MethodNode handler,AnnotationNode injection) { }
    private record Move(MethodInsnNode continuation,KernelResourceContinuations.Plan plan,String token,String value,String recipe) { }
    private MixinResourceContinuationAdapter() { }
    public static int adapt(ClassNode mixin,Ecosystem ecosystem,Function<String,ClassNode> classes){return adapt(mixin,classes,n->NativeGameReferences.reference(ecosystem,n));}
    static int adapt(ClassNode mixin,Function<String,ClassNode> classes,Function<String,ClassNode> references) {
        if("off".equalsIgnoreCase(System.getProperty(PROPERTY,"on")))return 0;List<String> owners=MixinFit.mixinTargets(mixin);if(owners.size()!=1)return 0;
        ClassNode target=classes.apply(owners.getFirst()),reference=references.apply(owners.getFirst());if(target==null||reference==null)return 0;
        List<MethodNode> added=new ArrayList<>();int changed=0;
        for(MethodNode method:new ArrayList<>(mixin.methods)) {
            Source source=source(mixin,method);if(source==null)continue;Move move=move(source,target,reference,classes);if(move==null)continue;
            added.addAll(wrap(mixin,source,move));changed++;
        }
        mixin.methods.addAll(added);return changed;
    }
    private static Source source(ClassNode mixin,MethodNode method) {
        AnnotationNode injection=MixinFit.injectorOf(method);Type[] args=Type.getArgumentTypes(method.desc);
        if(injection==null||!WRAP.equals(injection.desc)||(method.access&(Opcodes.ACC_STATIC|Opcodes.ACC_SYNCHRONIZED))!=0||!method.tryCatchBlocks.isEmpty()
                ||args.length!=2||!args[0].equals(Type.getType(String.class))||!args[1].equals(Type.getObjectType(OP))||MixinFit.value(injection,"slice")!=null)return null;
        if(annotations(method).stream().filter(a->FinalMixinApplications.isInjector(a.desc)).count()!=1||annotations(method).stream().anyMatch(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Group;")))return null;
        List<AnnotationNode> points=MixinFit.atNodes(injection);if(points.size()!=1||!"INVOKE".equals(MixinFit.value(points.getFirst(),"value"))||MixinFit.value(points.getFirst(),"ordinal")!=null||MixinFit.value(points.getFirst(),"shift")!=null)return null;
        return forwardsItsOwnArgument(mixin.name,method)?new Source(method,injection):null;
    }
    /**
     * Whether the handler's {@code Operation} (slot 2) is used only as the receiver of {@code call}s handed a fresh
     * one-element array holding its own, unchanged {@code String} (slot 1): the wrapper may then answer every such call
     * with the continuation the merged host makes, whatever else the handler computes around it.
     */
    static boolean forwardsItsOwnArgument(String owner,MethodNode handler) {
        HandlerFlow flow=HandlerFlow.of(owner,handler);if(flow==null)return false;
        AbstractInsnNode operation=null;
        for(AbstractInsnNode instruction:handler.instructions)if(instruction instanceof VarInsnNode load&&load.var==2&&load.getOpcode()==Opcodes.ALOAD){operation=load;break;}
        boolean called=false;
        for(int i=0;i<handler.instructions.size();i++) {
            AbstractInsnNode instruction=handler.instructions.get(i);
            Set<AbstractInsnNode>[] operands=operandsOf(flow,instruction);
            for(int k=0;k<operands.length;k++) {
                if(!HandlerFlow.onlyParameter(operands[k],2)&&operands[k].stream().noneMatch(o->HandlerFlow.isParameter(o,2)))continue;
                if(!(instruction instanceof MethodInsnNode call)||k!=0||!call.owner.equals(OP)||!call.name.equals("call")||!call.desc.equals("([Ljava/lang/Object;)Ljava/lang/Object;")
                        ||!flow.passesParameters(call,2,new int[]{1}))return false;
                called=true;
            }
        }
        return operation==null||called;
    }
    @SuppressWarnings("unchecked")
    private static Set<AbstractInsnNode>[] operandsOf(HandlerFlow flow,AbstractInsnNode instruction) {
        int count=consumed(instruction);Set<AbstractInsnNode>[] out=new Set[count];
        for(int k=0;k<count;k++)out[k]=flow.operand(instruction,k,count);
        return out;
    }
    /** The values an instruction takes off the stack for good, a store or a return included: where an operand can escape. */
    private static int consumed(AbstractInsnNode instruction) {
        int opcode=instruction.getOpcode();
        if(instruction instanceof MethodInsnNode call)return Type.getArgumentTypes(call.desc).length+(opcode==Opcodes.INVOKESTATIC?0:1);
        if(instruction instanceof InvokeDynamicInsnNode dynamic)return Type.getArgumentTypes(dynamic.desc).length;
        if(instruction instanceof FieldInsnNode)return opcode==Opcodes.PUTFIELD?2:opcode==Opcodes.PUTSTATIC||opcode==Opcodes.GETFIELD?1:0;
        return switch(opcode){
            case Opcodes.ARETURN,Opcodes.ATHROW,Opcodes.MONITORENTER,Opcodes.MONITOREXIT,Opcodes.IFNULL,Opcodes.IFNONNULL,Opcodes.INSTANCEOF,Opcodes.CHECKCAST -> 1;
            case Opcodes.IF_ACMPEQ,Opcodes.IF_ACMPNE -> 2;
            case Opcodes.AASTORE -> 3;
            default -> 0;
        };
    }
    private static Move move(Source source,ClassNode target,ClassNode reference,Function<String,ClassNode> classes) {
        // The host is the one method Mixin binds in the merged class, and the method the wrap was written for — the one it
        // binds in the class the mod was compiled against — is that same member: however the selector is spelled. One
        // host, since the point is rewritten for every method the injector binds.
        MethodNode host=MixinTargetSelectors.one(source.handler(),target),old=MixinCallbackShape.written(source.handler(),reference);
        if(host==null||old==null||!host.name.equals(old.name)||!host.desc.equals(old.desc))return null;
        AnnotationNode point=MixinFit.atNodes(source.injection()).getFirst();List<Move> matches=new ArrayList<>();
        // The point, however written, selects one (String) call in the written method — the platform's default-namespace
        // factory — and the merged host no longer makes that call.
        List<MethodInsnNode> olds=MixinCallbackShape.calls(point,old);if(olds.size()!=1)return null;MethodInsnNode defaultFactory=olds.getFirst();
        String valueName=defaultFactory.owner;if(defaultFactory.getOpcode()!=Opcodes.INVOKESTATIC||!defaultFactory.desc.equals("(Ljava/lang/String;)L"+valueName+";"))return null;
        String named=member(defaultFactory);if(calls(host).stream().anyMatch(c->member(c).equals(named)))return null;
        ClassNode value=classes.apply(valueName);if(value==null||(value.access&Opcodes.ACC_FINAL)==0)return null;
        MethodNode defaultMethod=declared(value,defaultFactory.name,defaultFactory.desc);String defaultNamespace=defaultMethod==null?null:defaultNamespace(defaultMethod,value.name);if(defaultNamespace==null)return null;
        // Its argument natively: a one-hole string recipe over a final field's name, the result stored into a field.
        if(!(previous(defaultFactory)instanceof InvokeDynamicInsnNode originalRecipe))return null;String recipe=recipe(originalRecipe);
        if(recipe==null||recipe.indexOf('\u0001')<0||recipe.indexOf('\u0001')!=recipe.lastIndexOf('\u0001'))return null;
        if(!(previous(originalRecipe)instanceof MethodInsnNode name)||name.getOpcode()!=Opcodes.INVOKEVIRTUAL||!name.desc.equals("()Ljava/lang/String;")
                ||!(previous(name)instanceof FieldInsnNode field)||field.getOpcode()!=Opcodes.GETFIELD||!field.owner.equals(reference.name)||!load(previous(field),0))return null;
        ClassNode names=classes.apply(name.owner);if(names==null||(names.access&Opcodes.ACC_FINAL)==0)return null;
        MethodNode nameGetter=declared(names,name.name,name.desc);if(nameGetter==null||pureField(nameGetter,names)==null)return null;
        FieldOwner sourceField=field(target,field.name,field.desc,classes,new HashSet<>());if(sourceField==null||(sourceField.field().access&Opcodes.ACC_FINAL)==0)return null;
        AbstractInsnNode oldSink=next(defaultFactory);if(!(oldSink instanceof FieldInsnNode oldField)||oldField.getOpcode()!=Opcodes.PUTFIELD)return null;
        int hole=recipe.indexOf('\u0001');String prefix=recipe.substring(0,hole),suffix=recipe.substring(hole+1);
        for(MethodInsnNode call:calls(host)) {
            // The merged host's parse of name + suffix: a static (String) factory of the same type over the same producer.
            if(call.getOpcode()!=Opcodes.INVOKESTATIC||!call.owner.equals(value.name)||!call.desc.equals("(Ljava/lang/String;)L"+value.name+";")
                    ||!(previous(call)instanceof InvokeDynamicInsnNode input)||!("\u0001"+suffix).equals(recipe(input))||!sameNameProducer(previous(input),name,field,target))continue;
            MethodNode parse=declared(value,call.name,call.desc);if(parse==null)continue;
            List<AbstractInsnNode> p=code(parse);if(p.size()!=4||!load(p.get(0),0)||!(p.get(1)instanceof IntInsnNode delimiter)||delimiter.getOpcode()!=Opcodes.BIPUSH||!(p.get(2)instanceof MethodInsnNode split)||split.getOpcode()!=Opcodes.INVOKESTATIC||!split.owner.equals(value.name)||!split.desc.equals("(Ljava/lang/String;C)L"+value.name+";")||p.get(3).getOpcode()!=Opcodes.ARETURN)continue;
            MethodNode splitter=declared(value,split.name,split.desc);MethodInsnNode fallback=splitter==null?null:splitter(splitter,value.name);if(fallback==null||!member(fallback).equals(named))continue;
            AbstractInsnNode literal=next(call),tail=literal==null?null:next(literal),sink=tail==null?null:next(tail);
            if(!(literal instanceof LdcInsnNode constant)||!prefix.equals(constant.cst)||!(tail instanceof MethodInsnNode continuation)||continuation.getOpcode()!=Opcodes.INVOKEVIRTUAL||!continuation.owner.equals(value.name)||!continuation.desc.equals("(Ljava/lang/String;)L"+value.name+";")||!(sink instanceof FieldInsnNode newField)||!same(oldField,newField))continue;
            MethodNode updater=declared(value,continuation.name,continuation.desc);Properties properties=properties(value,updater);if(properties==null)continue;
            MethodNode namespaceGetter=fieldGetter(value,properties.namespace()),pathGetter=fieldGetter(value,properties.path());if(namespaceGetter==null||pathGetter==null)continue;
            LinkedHashSet<MethodNode> closure=closure(value,List.of(parse,updater,defaultMethod,namespaceGetter,pathGetter));if(closure==null)continue;
            List<DefinedMethodContracts.MethodContract> bodies=new ArrayList<>();for(MethodNode method:closure)bodies.add(contract(value,method));bodies.add(contract(names,nameGetter));
            var plan=new KernelResourceContinuations.Plan(new KernelResourceContinuations.FieldContract(sourceField.owner(),field.name,field.desc),contract(names,nameGetter),contract(value,namespaceGetter),contract(value,pathGetter),bodies,prefix,suffix,defaultNamespace,(char)delimiter.operand);
            matches.add(new Move(continuation,plan,KernelResourceContinuations.register(plan),value.name,recipe));
        }
        return matches.size()==1?matches.getFirst():null;
    }
    private record FieldOwner(String owner,FieldNode field) { }
    private record Properties(FieldInsnNode namespace,FieldInsnNode path) { }
    private static Properties properties(ClassNode value,MethodNode updater) {
        if(updater==null)return null;List<AbstractInsnNode> c=code(updater);
        if(c.size()!=7||!load(c.get(0),0)||!load(c.get(1),1)||!load(c.get(2),0)||!(c.get(3)instanceof FieldInsnNode path)||path.getOpcode()!=Opcodes.GETFIELD||!(c.get(4)instanceof InvokeDynamicInsnNode concat)||!"\u0001\u0001".equals(recipe(concat))||!(c.get(5)instanceof MethodInsnNode setter)||c.get(6).getOpcode()!=Opcodes.ARETURN)return null;
        MethodNode changed=declared(value,setter.name,setter.desc);List<AbstractInsnNode> s=changed==null?List.of():code(changed);
        if(s.size()!=10||!(s.get(0)instanceof TypeInsnNode allocation)||allocation.getOpcode()!=Opcodes.NEW||!allocation.desc.equals(value.name)||s.get(1).getOpcode()!=Opcodes.DUP||!load(s.get(2),0)||!(s.get(3)instanceof FieldInsnNode namespace)||namespace.getOpcode()!=Opcodes.GETFIELD||!load(s.get(4),0)||!(s.get(5)instanceof FieldInsnNode sameNamespace)||!same(namespace,sameNamespace)||!load(s.get(6),1)||!(s.get(7)instanceof MethodInsnNode validate)||!validate.desc.equals("(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")||!(s.get(8)instanceof MethodInsnNode ctor)||!ctor.name.equals("<init>")||!ctor.desc.equals("(Ljava/lang/String;Ljava/lang/String;)V")||s.get(9).getOpcode()!=Opcodes.ARETURN)return null;
        return new Properties(namespace,path);
    }
    private static MethodInsnNode splitter(MethodNode method,String owner) {
        List<AbstractInsnNode> c=code(method);if(c.size()!=29||!load(c.get(0),0)||!variable(c.get(1),Opcodes.ILOAD,1)||!call(c.get(2),Opcodes.INVOKEVIRTUAL,"java/lang/String","indexOf","(I)I")||!variable(c.get(3),Opcodes.ISTORE,2)||!variable(c.get(4),Opcodes.ILOAD,2)||!jump(c.get(5),Opcodes.IFLT,c.get(26))||!load(c.get(6),0)||!variable(c.get(7),Opcodes.ILOAD,2)||c.get(8).getOpcode()!=Opcodes.ICONST_1||c.get(9).getOpcode()!=Opcodes.IADD||!call(c.get(10),Opcodes.INVOKEVIRTUAL,"java/lang/String","substring","(I)Ljava/lang/String;")||!variable(c.get(11),Opcodes.ASTORE,3)||!variable(c.get(12),Opcodes.ILOAD,2)||!jump(c.get(13),Opcodes.IFEQ,c.get(23))||!load(c.get(14),0)||c.get(15).getOpcode()!=Opcodes.ICONST_0||!variable(c.get(16),Opcodes.ILOAD,2)||!call(c.get(17),Opcodes.INVOKEVIRTUAL,"java/lang/String","substring","(II)Ljava/lang/String;")||!variable(c.get(18),Opcodes.ASTORE,4)||!load(c.get(19),4)||!load(c.get(20),3)||!(c.get(21)instanceof MethodInsnNode create)||!create.owner.equals(owner)||!create.desc.equals("(Ljava/lang/String;Ljava/lang/String;)L"+owner+";")||c.get(22).getOpcode()!=Opcodes.ARETURN||!load(c.get(23),3)||!(c.get(24)instanceof MethodInsnNode factory)||c.get(25).getOpcode()!=Opcodes.ARETURN||!load(c.get(26),0)||!(c.get(27)instanceof MethodInsnNode again)||!same(factory,again)||c.get(28).getOpcode()!=Opcodes.ARETURN)return null;return factory;
    }
    private static String defaultNamespace(MethodNode method,String owner) {List<AbstractInsnNode> c=code(method);return c.size()==8&&c.get(0)instanceof TypeInsnNode allocation&&allocation.getOpcode()==Opcodes.NEW&&allocation.desc.equals(owner)&&c.get(1).getOpcode()==Opcodes.DUP&&c.get(2)instanceof LdcInsnNode a&&a.cst instanceof String&&c.get(3)instanceof LdcInsnNode b&&a.cst.equals(b.cst)&&load(c.get(4),0)&&c.get(5)instanceof MethodInsnNode validator&&validator.desc.equals("(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")&&c.get(6)instanceof MethodInsnNode ctor&&ctor.name.equals("<init>")&&ctor.desc.equals("(Ljava/lang/String;Ljava/lang/String;)V")&&c.get(7).getOpcode()==Opcodes.ARETURN?(String)a.cst:null;}
    private static List<MethodNode> wrap(ClassNode mixin,Source source,Move move) {
        MethodNode original=source.handler();String name=original.name,aside=MixinHandlerShim.asideName(mixin.name,name,"$forbricresource"),delegate=MixinHandlerShim.asideName(mixin.name,name,"$forbriccontinuation");String value=move.value();
        String descriptor="(L"+value+";Ljava/lang/String;L"+OP+";)L"+value+";";MethodNode outer=new MethodNode(original.access,name,descriptor,null,null);
        boolean visible=original.visibleAnnotations!=null&&original.visibleAnnotations.remove(source.injection());if(!visible)original.invisibleAnnotations.remove(source.injection());if(visible)outer.visibleAnnotations=new ArrayList<>(List.of(source.injection()));else outer.invisibleAnnotations=new ArrayList<>(List.of(source.injection()));set(MixinFit.atNodes(source.injection()).getFirst(),"target",member(move.continuation()));
        LabelNode fallback=new LabelNode();InsnList c=outer.instructions;c.add(new VarInsnNode(Opcodes.ALOAD,0));c.add(new VarInsnNode(Opcodes.ALOAD,1));c.add(new VarInsnNode(Opcodes.ALOAD,2));c.add(new LdcInsnNode(move.token()));c.add(new MethodInsnNode(Opcodes.INVOKESTATIC,RUNTIME,"sourceName","(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)[Ljava/lang/String;",false));c.add(new VarInsnNode(Opcodes.ASTORE,4));c.add(new VarInsnNode(Opcodes.ALOAD,4));c.add(new JumpInsnNode(Opcodes.IFNULL,fallback));
        c.add(new VarInsnNode(Opcodes.ALOAD,0));c.add(new VarInsnNode(Opcodes.ALOAD,4));c.add(new InsnNode(Opcodes.ICONST_0));c.add(new InsnNode(Opcodes.AALOAD));
        c.add(new InvokeDynamicInsnNode("makeConcatWithConstants","(Ljava/lang/String;)Ljava/lang/String;",new Handle(Opcodes.H_INVOKESTATIC,"java/lang/invoke/StringConcatFactory","makeConcatWithConstants","(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",false),move.recipe()));
        c.add(new VarInsnNode(Opcodes.ALOAD,3));c.add(new VarInsnNode(Opcodes.ALOAD,1));c.add(new VarInsnNode(Opcodes.ALOAD,2));
        String captured="(L"+OP+";L"+value+";Ljava/lang/String;)L"+OP+";",impl="(L"+OP+";L"+value+";Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;";
        c.add(new InvokeDynamicInsnNode("call",captured,LAMBDA,Type.getMethodType("([Ljava/lang/Object;)Ljava/lang/Object;"),new Handle(Opcodes.H_INVOKESTATIC,mixin.name,delegate,impl,false),Type.getMethodType("([Ljava/lang/Object;)Ljava/lang/Object;")));
        c.add(MixinHandlerShim.callOwn(mixin,false,aside,original.desc));c.add(new InsnNode(Opcodes.ARETURN));c.add(fallback);c.add(new VarInsnNode(Opcodes.ALOAD,3));c.add(new InsnNode(Opcodes.ICONST_2));c.add(new TypeInsnNode(Opcodes.ANEWARRAY,"java/lang/Object"));for(int i=0;i<2;i++){c.add(new InsnNode(Opcodes.DUP));c.add(new InsnNode(Opcodes.ICONST_0+i));c.add(new VarInsnNode(Opcodes.ALOAD,1+i));c.add(new InsnNode(Opcodes.AASTORE));}c.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,OP,"call","([Ljava/lang/Object;)Ljava/lang/Object;",true));c.add(new TypeInsnNode(Opcodes.CHECKCAST,value));c.add(new InsnNode(Opcodes.ARETURN));outer.maxLocals=5;outer.maxStack=8;
        MethodNode bridge=new MethodNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC|Opcodes.ACC_SYNTHETIC,delegate,impl,null,null);bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));bridge.instructions.add(new InsnNode(Opcodes.ICONST_2));bridge.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY,"java/lang/Object"));for(int i=0;i<2;i++){bridge.instructions.add(new InsnNode(Opcodes.DUP));bridge.instructions.add(new InsnNode(Opcodes.ICONST_0+i));bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD,1+i));bridge.instructions.add(new InsnNode(Opcodes.AASTORE));}bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,OP,"call","([Ljava/lang/Object;)Ljava/lang/Object;",true));bridge.instructions.add(new InsnNode(Opcodes.ARETURN));bridge.maxLocals=4;bridge.maxStack=5;original.name=aside;return List.of(outer,bridge);
    }
    private static LinkedHashSet<MethodNode> closure(ClassNode owner,List<MethodNode> roots) {LinkedHashSet<MethodNode> found=new LinkedHashSet<>();ArrayDeque<MethodNode> queue=new ArrayDeque<>(roots);while(!queue.isEmpty()){MethodNode method=queue.remove();if(!found.add(method))continue;if((method.access&(Opcodes.ACC_SYNCHRONIZED|Opcodes.ACC_NATIVE|Opcodes.ACC_ABSTRACT))!=0)return null;for(MethodInsnNode call:calls(method))if(call.owner.equals(owner.name)){MethodNode next=declared(owner,call.name,call.desc);if(next==null)return null;queue.add(next);}}return found;}
    private static FieldInsnNode pureField(MethodNode method,ClassNode owner){List<AbstractInsnNode> c=code(method);return c.size()==3&&load(c.get(0),0)&&c.get(1)instanceof FieldInsnNode field&&field.getOpcode()==Opcodes.GETFIELD&&field.owner.equals(owner.name)&&field.desc.equals("Ljava/lang/String;")&&owner.fields.stream().anyMatch(f->f.name.equals(field.name)&&f.desc.equals(field.desc)&&(f.access&Opcodes.ACC_FINAL)!=0)&&c.get(2).getOpcode()==Opcodes.ARETURN?field:null;}
    private static MethodNode fieldGetter(ClassNode owner,FieldInsnNode field){List<MethodNode> matches=owner.methods.stream().filter(m->m.desc.equals("()Ljava/lang/String;")&&pureField(m,owner)!=null&&same(pureField(m,owner),field)).toList();return matches.size()==1?matches.getFirst():null;}
    private static FieldOwner field(ClassNode owner,String name,String desc,Function<String,ClassNode> classes,Set<String> seen){if(owner==null||!seen.add(owner.name))return null;for(FieldNode field:owner.fields)if(field.name.equals(name)&&field.desc.equals(desc))return new FieldOwner(owner.name,field);return field(owner.superName==null?null:classes.apply(owner.superName),name,desc,classes,seen);}
    private static boolean sameNameProducer(AbstractInsnNode instruction,MethodInsnNode name,FieldInsnNode field,ClassNode target){if(!(instruction instanceof MethodInsnNode method)||!same(method,name))return false;AbstractInsnNode previous=previous(method);return previous instanceof FieldInsnNode read&&read.getOpcode()==Opcodes.GETFIELD&&read.owner.equals(target.name)&&read.name.equals(field.name)&&read.desc.equals(field.desc)&&load(previous(read),0);}
    private static String recipe(InvokeDynamicInsnNode dynamic){return dynamic.bsm.getOwner().equals("java/lang/invoke/StringConcatFactory")&&dynamic.bsm.getName().equals("makeConcatWithConstants")&&dynamic.desc.equals("(Ljava/lang/String;)Ljava/lang/String;")&&dynamic.bsmArgs.length==1&&dynamic.bsmArgs[0]instanceof String text?text:dynamic.bsm.getOwner().equals("java/lang/invoke/StringConcatFactory")&&dynamic.desc.equals("(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")&&dynamic.bsmArgs.length==1&&dynamic.bsmArgs[0]instanceof String text?text:null;}
    private static DefinedMethodContracts.MethodContract contract(ClassNode owner,MethodNode method){return new DefinedMethodContracts.MethodContract(owner.name,method.name,method.desc,DefinedMethodContracts.fingerprint(method));}
    private static MethodNode declared(ClassNode owner,String name,String desc){return owner.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElse(null);}
    private static List<AbstractInsnNode> code(MethodNode method){return DefaultMethodOverloadBridge.real(method);}
    private static List<MethodInsnNode> calls(MethodNode method){return Arrays.stream(method.instructions.toArray()).filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).toList();}
    private static String member(MethodInsnNode call){return "L"+call.owner+";"+call.name+call.desc;}
    private static boolean same(MethodInsnNode a,MethodInsnNode b){return a.getOpcode()==b.getOpcode()&&a.owner.equals(b.owner)&&a.name.equals(b.name)&&a.desc.equals(b.desc)&&a.itf==b.itf;}
    private static boolean same(FieldInsnNode a,FieldInsnNode b){return a.getOpcode()==b.getOpcode()&&a.owner.equals(b.owner)&&a.name.equals(b.name)&&a.desc.equals(b.desc);}
    private static boolean variable(AbstractInsnNode instruction,int opcode,int slot){return instruction instanceof VarInsnNode var&&var.getOpcode()==opcode&&var.var==slot;}
    private static boolean load(AbstractInsnNode instruction,int slot){return variable(instruction,Opcodes.ALOAD,slot);}
    private static boolean call(AbstractInsnNode instruction,int opcode,String owner,String name,String desc){return instruction instanceof MethodInsnNode c&&c.getOpcode()==opcode&&c.owner.equals(owner)&&c.name.equals(name)&&c.desc.equals(desc);}
    private static boolean jump(AbstractInsnNode instruction,int opcode,AbstractInsnNode target){return instruction instanceof JumpInsnNode j&&j.getOpcode()==opcode&&next(j.label)==target;}
    private static AbstractInsnNode next(AbstractInsnNode instruction){do{instruction=instruction.getNext();}while(instruction!=null&&instruction.getOpcode()<0);return instruction;}
    private static AbstractInsnNode previous(AbstractInsnNode instruction){do{instruction=instruction.getPrevious();}while(instruction!=null&&instruction.getOpcode()<0);return instruction;}
    private static List<AnnotationNode> annotations(MethodNode method){List<AnnotationNode> all=new ArrayList<>();if(method.visibleAnnotations!=null)all.addAll(method.visibleAnnotations);if(method.invisibleAnnotations!=null)all.addAll(method.invisibleAnnotations);return all;}
    private static void set(AnnotationNode annotation,String key,Object value){for(int i=0;i+1<annotation.values.size();i+=2)if(key.equals(annotation.values.get(i))){annotation.values.set(i+1,value);return;}annotation.values.add(key);annotation.values.add(value);}
}
