/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;
import java.util.*;import java.util.function.Function;
import net.forbric.api.Ecosystem;import net.forbric.kernel.boot.*;
import org.objectweb.asm.*;import org.objectweb.asm.tree.*;

/** A conditional namespace factory wrapper follows an exact suffix-parse/prefix continuation into its final sink.
 * The original body remains intact. Its fallback Operation receives the live resource and prefix unchanged. */
public final class MixinResourceContinuationAdapter {
    public static final String PROPERTY="forbric.mixinResourceContinuations";
    private static final String WRAP="Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",OP="com/llamalad7/mixinextras/injector/wrapoperation/Operation";
    private static final String RUNTIME="net/forbric/kernel/boot/KernelResourceContinuations";
    private static final Handle LAMBDA=new Handle(Opcodes.H_INVOKESTATIC,"java/lang/invoke/LambdaMetafactory","metafactory","(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",false);
    private record Source(MethodNode handler,AnnotationNode injection,FieldInsnNode field,MethodInsnNode name,MethodInsnNode parse,MethodInsnNode transform,String recipe,int separator) { }
    private record Move(MethodInsnNode continuation,KernelResourceContinuations.Plan plan,String token) { }
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
        AnnotationNode injection=MixinFit.injectorOf(method);Type[] args=Type.getArgumentTypes(method.desc);List<AbstractInsnNode> c=code(method);
        if(injection==null||!WRAP.equals(injection.desc)||(method.access&(Opcodes.ACC_STATIC|Opcodes.ACC_SYNCHRONIZED))!=0||!method.tryCatchBlocks.isEmpty()
                ||args.length!=2||!args[0].equals(Type.getType(String.class))||!args[1].equals(Type.getObjectType(OP))||c.size()!=26||MixinFit.value(injection,"slice")!=null)return null;
        if(annotations(method).stream().filter(a->FinalMixinApplications.isInjector(a.desc)).count()!=1||annotations(method).stream().anyMatch(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Group;")))return null;
        List<AnnotationNode> points=MixinFit.atNodes(injection);if(points.size()!=1||!"INVOKE".equals(MixinFit.value(points.getFirst(),"value"))||MixinFit.value(points.getFirst(),"ordinal")!=null||MixinFit.value(points.getFirst(),"shift")!=null)return null;
        if(!load(c.get(0),0)||!(c.get(1)instanceof FieldInsnNode field)||field.getOpcode()!=Opcodes.GETFIELD||!field.owner.equals(mixin.name)
                ||!(c.get(2)instanceof MethodInsnNode name)||name.getOpcode()!=Opcodes.INVOKEVIRTUAL||!name.desc.equals("()Ljava/lang/String;")
                ||!(c.get(3)instanceof IntInsnNode separator)||separator.getOpcode()!=Opcodes.BIPUSH
                ||!call(c.get(4),Opcodes.INVOKEVIRTUAL,"java/lang/String","indexOf","(I)I")||c.get(5).getOpcode()!=Opcodes.ICONST_M1||!jump(c.get(6),Opcodes.IF_ICMPEQ,c.get(16))
                ||!load(c.get(7),0)||!(c.get(8)instanceof FieldInsnNode again)||!same(field,again)||!(c.get(9)instanceof MethodInsnNode getter)||!same(name,getter)
                ||!(c.get(10)instanceof MethodInsnNode parse)||parse.getOpcode()!=Opcodes.INVOKESTATIC||!parse.desc.equals("(Ljava/lang/String;)L"+parse.owner+";")
                ||!variable(c.get(11),Opcodes.ASTORE,3)||!load(c.get(12),3)||!(c.get(13)instanceof InvokeDynamicInsnNode lambda)
                ||!(c.get(14)instanceof MethodInsnNode transform)||transform.getOpcode()!=Opcodes.INVOKEVIRTUAL||!transform.owner.equals(parse.owner)||!transform.desc.equals("(Ljava/util/function/UnaryOperator;)L"+parse.owner+";")
                ||c.get(15).getOpcode()!=Opcodes.ARETURN||!load(c.get(16),2)||c.get(17).getOpcode()!=Opcodes.ICONST_1
                ||!(c.get(18)instanceof TypeInsnNode array)||array.getOpcode()!=Opcodes.ANEWARRAY||!array.desc.equals("java/lang/Object")
                ||c.get(19).getOpcode()!=Opcodes.DUP||c.get(20).getOpcode()!=Opcodes.ICONST_0||!load(c.get(21),1)||c.get(22).getOpcode()!=Opcodes.AASTORE
                ||!call(c.get(23),Opcodes.INVOKEINTERFACE,OP,"call","([Ljava/lang/Object;)Ljava/lang/Object;")||!(c.get(24)instanceof TypeInsnNode cast)||!cast.desc.equals(parse.owner)||c.get(25).getOpcode()!=Opcodes.ARETURN)return null;
        if(!lambda.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")||!lambda.desc.equals("()Ljava/util/function/UnaryOperator;")||lambda.bsmArgs.length!=3||!(lambda.bsmArgs[1]instanceof Handle handle)||!handle.getOwner().equals(mixin.name)||handle.getTag()!=Opcodes.H_INVOKESTATIC)return null;
        MethodNode helper=declared(mixin,handle.getName(),handle.getDesc());String recipe=helper==null?null:stringRecipe(helper);
        return recipe!=null&&recipe.indexOf('\u0001')==recipe.lastIndexOf('\u0001')&&recipe.indexOf('\u0001')>=0?new Source(method,injection,field,name,parse,transform,recipe,separator.operand):null;
    }
    private static Move move(Source source,ClassNode target,ClassNode reference,Function<String,ClassNode> classes) {
        List<String> selectors=MixinFit.stringList(MixinFit.value(source.injection(),"method"));if(selectors.size()!=1)return null;
        List<MethodNode> current=target.methods.stream().filter(m->selectors.contains(m.name)||selectors.contains(m.name+m.desc)).toList();
        String oldTarget=MixinFit.asString(MixinFit.value(MixinFit.atNodes(source.injection()).getFirst(),"target"));List<Move> matches=new ArrayList<>();
        int hole=source.recipe().indexOf('\u0001');String prefix=source.recipe().substring(0,hole),suffix=source.recipe().substring(hole+1);
        ClassNode value=classes.apply(source.parse().owner),names=classes.apply(source.name().owner);if(value==null||names==null||(value.access&Opcodes.ACC_FINAL)==0||(names.access&Opcodes.ACC_FINAL)==0)return null;
        MethodNode nameGetter=declared(names,source.name().name,source.name().desc);if(nameGetter==null||pureField(nameGetter,names)==null)return null;
        MethodNode parse=declared(value,source.parse().name,source.parse().desc),transform=declared(value,source.transform().name,source.transform().desc);if(parse==null||transform==null)return null;
        List<AbstractInsnNode> p=code(parse);if(p.size()!=4||!load(p.get(0),0)||!(p.get(1)instanceof IntInsnNode delimiter)||delimiter.operand!=source.separator()||!(p.get(2)instanceof MethodInsnNode split)||split.getOpcode()!=Opcodes.INVOKESTATIC||!split.owner.equals(value.name)||!split.desc.equals("(Ljava/lang/String;C)L"+value.name+";")||p.get(3).getOpcode()!=Opcodes.ARETURN)return null;
        MethodNode splitter=declared(value,split.name,split.desc);MethodInsnNode defaultFactory=splitter==null?null:splitter(splitter,value.name);if(defaultFactory==null||!member(defaultFactory).equals(oldTarget))return null;
        MethodNode defaultMethod=declared(value,defaultFactory.name,defaultFactory.desc);String defaultNamespace=defaultMethod==null?null:defaultNamespace(defaultMethod,value.name);if(defaultNamespace==null)return null;
        FieldOwner sourceField=field(target,source.field().name,source.field().desc,classes,new HashSet<>());if(sourceField==null||(sourceField.field().access&Opcodes.ACC_FINAL)==0)return null;
        for(MethodNode host:current) {
            MethodNode old=declared(reference,host.name,host.desc);if(old==null)continue;
            List<MethodInsnNode> olds=calls(old).stream().filter(c->member(c).equals(oldTarget)).toList();if(olds.size()!=1||calls(host).stream().anyMatch(c->member(c).equals(oldTarget)))continue;
            if(!(previous(olds.getFirst())instanceof InvokeDynamicInsnNode originalRecipe)||!source.recipe().equals(recipe(originalRecipe))||!sameNameProducer(previous(originalRecipe),source,target))continue;
            AbstractInsnNode oldSink=next(olds.getFirst());if(!(oldSink instanceof FieldInsnNode oldField)||oldField.getOpcode()!=Opcodes.PUTFIELD)continue;
            for(MethodInsnNode call:calls(host)) {
                if(!same(call,source.parse())||!(previous(call)instanceof InvokeDynamicInsnNode input)||!("\u0001"+suffix).equals(recipe(input))||!sameNameProducer(previous(input),source,target))continue;
                AbstractInsnNode literal=next(call),tail=literal==null?null:next(literal),sink=tail==null?null:next(tail);
                if(!(literal instanceof LdcInsnNode constant)||!prefix.equals(constant.cst)||!(tail instanceof MethodInsnNode continuation)||continuation.getOpcode()!=Opcodes.INVOKEVIRTUAL||!continuation.owner.equals(value.name)||!continuation.desc.equals("(Ljava/lang/String;)L"+value.name+";")||!(sink instanceof FieldInsnNode newField)||!same(oldField,newField))continue;
                MethodNode updater=declared(value,continuation.name,continuation.desc);Properties properties=properties(value,updater,transform);if(properties==null)continue;
                MethodNode namespaceGetter=fieldGetter(value,properties.namespace()),pathGetter=fieldGetter(value,properties.path());if(namespaceGetter==null||pathGetter==null)continue;
                LinkedHashSet<MethodNode> closure=closure(value,List.of(parse,transform,updater,defaultMethod,namespaceGetter,pathGetter));if(closure==null)continue;
                List<DefinedMethodContracts.MethodContract> bodies=new ArrayList<>();for(MethodNode method:closure)bodies.add(contract(value,method));bodies.add(contract(names,nameGetter));
                var plan=new KernelResourceContinuations.Plan(new KernelResourceContinuations.FieldContract(sourceField.owner(),source.field().name,source.field().desc),contract(names,nameGetter),contract(value,namespaceGetter),contract(value,pathGetter),bodies,prefix,suffix,defaultNamespace,(char)source.separator());
                matches.add(new Move(continuation,plan,KernelResourceContinuations.register(plan)));
            }
        }
        return matches.size()==1?matches.getFirst():null;
    }
    private record FieldOwner(String owner,FieldNode field) { }
    private record Properties(FieldInsnNode namespace,FieldInsnNode path) { }
    private static Properties properties(ClassNode value,MethodNode updater,MethodNode transform) {
        if(updater==null)return null;List<AbstractInsnNode> c=code(updater),f=code(transform);
        if(c.size()!=7||!load(c.get(0),0)||!load(c.get(1),1)||!load(c.get(2),0)||!(c.get(3)instanceof FieldInsnNode path)||path.getOpcode()!=Opcodes.GETFIELD||!(c.get(4)instanceof InvokeDynamicInsnNode concat)||!"\u0001\u0001".equals(recipe(concat))||!(c.get(5)instanceof MethodInsnNode setter)||c.get(6).getOpcode()!=Opcodes.ARETURN)return null;
        if(f.size()!=8||!load(f.get(0),0)||!load(f.get(1),1)||!load(f.get(2),0)||!(f.get(3)instanceof FieldInsnNode samePath)||!same(path,samePath)||!call(f.get(4),Opcodes.INVOKEINTERFACE,"java/util/function/UnaryOperator","apply","(Ljava/lang/Object;)Ljava/lang/Object;")||!(f.get(5)instanceof TypeInsnNode cast)||!cast.desc.equals("java/lang/String")||!(f.get(6)instanceof MethodInsnNode sameSetter)||!same(setter,sameSetter)||f.get(7).getOpcode()!=Opcodes.ARETURN)return null;
        MethodNode changed=declared(value,setter.name,setter.desc);List<AbstractInsnNode> s=changed==null?List.of():code(changed);
        if(s.size()!=10||!(s.get(0)instanceof TypeInsnNode allocation)||allocation.getOpcode()!=Opcodes.NEW||!allocation.desc.equals(value.name)||s.get(1).getOpcode()!=Opcodes.DUP||!load(s.get(2),0)||!(s.get(3)instanceof FieldInsnNode namespace)||namespace.getOpcode()!=Opcodes.GETFIELD||!load(s.get(4),0)||!(s.get(5)instanceof FieldInsnNode sameNamespace)||!same(namespace,sameNamespace)||!load(s.get(6),1)||!(s.get(7)instanceof MethodInsnNode validate)||!validate.desc.equals("(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")||!(s.get(8)instanceof MethodInsnNode ctor)||!ctor.name.equals("<init>")||!ctor.desc.equals("(Ljava/lang/String;Ljava/lang/String;)V")||s.get(9).getOpcode()!=Opcodes.ARETURN)return null;
        return new Properties(namespace,path);
    }
    private static MethodInsnNode splitter(MethodNode method,String owner) {
        List<AbstractInsnNode> c=code(method);if(c.size()!=29||!load(c.get(0),0)||!variable(c.get(1),Opcodes.ILOAD,1)||!call(c.get(2),Opcodes.INVOKEVIRTUAL,"java/lang/String","indexOf","(I)I")||!variable(c.get(3),Opcodes.ISTORE,2)||!variable(c.get(4),Opcodes.ILOAD,2)||!jump(c.get(5),Opcodes.IFLT,c.get(26))||!load(c.get(6),0)||!variable(c.get(7),Opcodes.ILOAD,2)||c.get(8).getOpcode()!=Opcodes.ICONST_1||c.get(9).getOpcode()!=Opcodes.IADD||!call(c.get(10),Opcodes.INVOKEVIRTUAL,"java/lang/String","substring","(I)Ljava/lang/String;")||!variable(c.get(11),Opcodes.ASTORE,3)||!variable(c.get(12),Opcodes.ILOAD,2)||!jump(c.get(13),Opcodes.IFEQ,c.get(23))||!load(c.get(14),0)||c.get(15).getOpcode()!=Opcodes.ICONST_0||!variable(c.get(16),Opcodes.ILOAD,2)||!call(c.get(17),Opcodes.INVOKEVIRTUAL,"java/lang/String","substring","(II)Ljava/lang/String;")||!variable(c.get(18),Opcodes.ASTORE,4)||!load(c.get(19),4)||!load(c.get(20),3)||!(c.get(21)instanceof MethodInsnNode create)||!create.owner.equals(owner)||!create.desc.equals("(Ljava/lang/String;Ljava/lang/String;)L"+owner+";")||c.get(22).getOpcode()!=Opcodes.ARETURN||!load(c.get(23),3)||!(c.get(24)instanceof MethodInsnNode factory)||c.get(25).getOpcode()!=Opcodes.ARETURN||!load(c.get(26),0)||!(c.get(27)instanceof MethodInsnNode again)||!same(factory,again)||c.get(28).getOpcode()!=Opcodes.ARETURN)return null;return factory;
    }
    private static String defaultNamespace(MethodNode method,String owner) {List<AbstractInsnNode> c=code(method);return c.size()==8&&c.get(0)instanceof TypeInsnNode allocation&&allocation.getOpcode()==Opcodes.NEW&&allocation.desc.equals(owner)&&c.get(1).getOpcode()==Opcodes.DUP&&c.get(2)instanceof LdcInsnNode a&&a.cst instanceof String&&c.get(3)instanceof LdcInsnNode b&&a.cst.equals(b.cst)&&load(c.get(4),0)&&c.get(5)instanceof MethodInsnNode validator&&validator.desc.equals("(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")&&c.get(6)instanceof MethodInsnNode ctor&&ctor.name.equals("<init>")&&ctor.desc.equals("(Ljava/lang/String;Ljava/lang/String;)V")&&c.get(7).getOpcode()==Opcodes.ARETURN?(String)a.cst:null;}
    private static List<MethodNode> wrap(ClassNode mixin,Source source,Move move) {
        MethodNode original=source.handler();String name=original.name,aside=MixinHandlerShim.asideName(mixin.name,name,"$forbricresource"),delegate=MixinHandlerShim.asideName(mixin.name,name,"$forbriccontinuation");String value=source.parse().owner;
        String descriptor="(L"+value+";Ljava/lang/String;L"+OP+";)L"+value+";";MethodNode outer=new MethodNode(original.access,name,descriptor,null,null);
        boolean visible=original.visibleAnnotations!=null&&original.visibleAnnotations.remove(source.injection());if(!visible)original.invisibleAnnotations.remove(source.injection());if(visible)outer.visibleAnnotations=new ArrayList<>(List.of(source.injection()));else outer.invisibleAnnotations=new ArrayList<>(List.of(source.injection()));set(MixinFit.atNodes(source.injection()).getFirst(),"target",member(move.continuation()));
        LabelNode fallback=new LabelNode();InsnList c=outer.instructions;c.add(new VarInsnNode(Opcodes.ALOAD,0));c.add(new VarInsnNode(Opcodes.ALOAD,1));c.add(new VarInsnNode(Opcodes.ALOAD,2));c.add(new LdcInsnNode(move.token()));c.add(new MethodInsnNode(Opcodes.INVOKESTATIC,RUNTIME,"sourceName","(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)[Ljava/lang/String;",false));c.add(new VarInsnNode(Opcodes.ASTORE,4));c.add(new VarInsnNode(Opcodes.ALOAD,4));c.add(new JumpInsnNode(Opcodes.IFNULL,fallback));
        c.add(new VarInsnNode(Opcodes.ALOAD,0));c.add(new VarInsnNode(Opcodes.ALOAD,4));c.add(new InsnNode(Opcodes.ICONST_0));c.add(new InsnNode(Opcodes.AALOAD));
        c.add(new InvokeDynamicInsnNode("makeConcatWithConstants","(Ljava/lang/String;)Ljava/lang/String;",new Handle(Opcodes.H_INVOKESTATIC,"java/lang/invoke/StringConcatFactory","makeConcatWithConstants","(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",false),source.recipe()));
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
    private static boolean sameNameProducer(AbstractInsnNode instruction,Source source,ClassNode target){if(!(instruction instanceof MethodInsnNode method)||!same(method,source.name()))return false;AbstractInsnNode previous=previous(method);return previous instanceof FieldInsnNode field&&field.getOpcode()==Opcodes.GETFIELD&&field.owner.equals(target.name)&&field.name.equals(source.field().name)&&field.desc.equals(source.field().desc)&&load(previous(field),0);}
    private static String stringRecipe(MethodNode method){List<AbstractInsnNode> c=code(method);return(method.access&Opcodes.ACC_STATIC)!=0&&method.tryCatchBlocks.isEmpty()&&c.size()==3&&load(c.get(0),0)&&c.get(1)instanceof InvokeDynamicInsnNode dynamic&&c.get(2).getOpcode()==Opcodes.ARETURN?recipe(dynamic):null;}
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
