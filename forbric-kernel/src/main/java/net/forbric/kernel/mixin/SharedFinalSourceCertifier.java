/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.forbric.kernel.boot.DefinedMethodContracts.MethodContract;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.MethodRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.*;

/** Closes a remembered source continuation against the bytes that were actually defined. */
public final class SharedFinalSourceCertifier {
    private static final String MERGED="Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";
    private static final String OP="com/llamalad7/mixinextras/injector/wrapoperation/Operation";
    private static final int FLAGS=Opcodes.ACC_PUBLIC|Opcodes.ACC_PRIVATE|Opcodes.ACC_PROTECTED|Opcodes.ACC_STATIC
            |Opcodes.ACC_FINAL|Opcodes.ACC_SYNCHRONIZED|Opcodes.ACC_BRIDGE|Opcodes.ACC_NATIVE|Opcodes.ACC_ABSTRACT;
    private static final ConcurrentHashMap<String,ConcurrentHashMap<String,Plan>> PLANS=new ConcurrentHashMap<>();
    private record Plan(String owner,String source,List<MethodNode> methods,MethodInsnNode under,MethodInsnNode ground) { }
    public record Witness(List<MethodContract> methods,Map<MethodContract,Integer> flags) { }
    private SharedFinalSourceCertifier() { }

    /** Call before changing either source body; instruction/bootstrap arrays are copied immediately. */
    public static void remember(String key,String targetOwner,String mixinOwner,MethodNode originalUnder,
            MethodNode originalGround,MethodNode underHelper,MethodNode groundHelper,List<MethodNode> remainingGenerated,
            MethodInsnNode underAnchor,MethodInsnNode groundAnchor) {
        Objects.requireNonNull(key);String owner=targetOwner.replace('.','/'),source=mixinOwner.replace('.','/');
        List<MethodNode> methods=new ArrayList<>(List.of(copy(originalUnder),copy(originalGround),copy(underHelper),copy(groundHelper)));
        for(MethodNode method:remainingGenerated)methods.add(copy(method));
        Plan plan=new Plan(owner,source,List.copyOf(methods),callCopy(underAnchor),callCopy(groundAnchor));
        PLANS.computeIfAbsent(owner,ignored->new ConcurrentHashMap<>()).put(key,plan);
    }
    public static boolean interested(String owner) { return PLANS.containsKey(owner.replace('.','/')); }

    /** No final witness is emitted unless both original bodies and the entire generated continuation close. */
    public static Map<String,Witness> certify(ClassNode target) {
        Map<String,Plan> plans=PLANS.get(target.name);if(plans==null)return Map.of();
        Map<String,Witness> result=new HashMap<>();for(var entry:plans.entrySet())try{
            Witness witness=certify(target,entry.getValue());if(witness!=null)result.put(entry.getKey(),witness);
        }catch(RuntimeException unsupported){/* unknown final shape declines the source continuation */}
        return Map.copyOf(result);
    }
    private static Witness certify(ClassNode target,Plan plan) {
        Map<String,String> aliases=new HashMap<>();List<MethodNode> actual=new ArrayList<>();
        for(int i=0;i<plan.methods.size();i++){
            MethodNode expected=plan.methods.get(i);String desc=descriptor(expected.desc,plan.source,plan.owner);
            List<MethodNode> matches=target.methods.stream().filter(m->m.desc.equals(desc)
                    &&(m.name.equals(expected.name)||m.name.endsWith("$"+expected.name))
                    &&(mergedFrom(m,plan.source)||(iRole(expected,plan.methods)>=2&&isUnique(expected)&&m.name.equals(expected.name)))).toList();
            if(matches.size()!=1)return null;MethodNode found=matches.getFirst();
            if((found.access&FLAGS)!=(expected.access&FLAGS))return null;
            aliases.put(found.name+found.desc,expected.name);actual.add(found);
        }
        List<MethodNode> normalized=new ArrayList<>();for(MethodNode method:actual)normalized.add(normalize(method,plan,aliases,true));
        List<MethodNode> expected=new ArrayList<>();for(MethodNode method:plan.methods)expected.add(normalize(method,plan,Map.of(),false));
        // Mixin removes a redundant checkcast of its own receiver. It cannot change any other cast or operand.
        for(int i=0;i<2;i++){stripSelfCasts(normalized.get(i),plan.owner);stripSelfCasts(expected.get(i),plan.owner);
            if(!same(normalized.get(i),expected.get(i)))return null;}
        MethodNode u=normalized.get(2);MethodInsnNode under=remapCall(plan.under,plan);
        List<AbstractInsnNode> uc=code(u);List<MethodInsnNode> anchors=uc.stream().filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).filter(c->sameCall(c,under)).toList();
        if(anchors.size()!=1)return null;int at=uc.indexOf(anchors.getFirst());
        if(at+4>=uc.size()||!load(uc.get(at+1),0)||uc.get(at+2).getOpcode()!=Opcodes.SWAP||!load(uc.get(at+3),1)
                ||!(uc.get(at+4)instanceof MethodInsnNode invoke)||invoke.getOpcode()!=Opcodes.INVOKESPECIAL||invoke.itf
                ||!invoke.owner.equals(plan.owner)||!invoke.name.equals(expected.get(0).name)||!invoke.desc.equals(expected.get(0).desc))return null;
        for(int i=1;i<=4;i++)u.instructions.remove(uc.get(at+i));if(!same(u,expected.get(2)))return null;
        MethodNode relay=ground(target,plan,normalized.get(3),expected.get(3),expected.get(1));if(relay==null)return null;
        for(int i=4;i<expected.size();i++)if(!same(normalized.get(i),expected.get(i)))return null;
        List<MethodContract> contracts=new ArrayList<>();Map<MethodContract,Integer> flags=new HashMap<>();
        List<MethodNode> complete=new ArrayList<>(actual);complete.add(relay);
        for(MethodNode method:complete){MethodContract contract=new MethodContract(target.name,method.name,method.desc,MixinInstructionFingerprint.hash(method));contracts.add(contract);flags.put(contract,method.access&FLAGS);}
        return new Witness(List.copyOf(contracts),Map.copyOf(flags));
    }
    private static int iRole(MethodNode method,List<MethodNode> methods){return methods.indexOf(method);}
    private static MethodNode ground(ClassNode target,Plan plan,MethodNode actual,MethodNode expected,MethodNode handler){
        List<AbstractInsnNode> before=code(expected),after=code(actual);if(!actual.tryCatchBlocks.isEmpty()||before.size()!=4||after.size()!=11
                ||!load(before.get(0),2)||!(before.get(1)instanceof FieldInsnNode tag)||tag.getOpcode()!=Opcodes.GETSTATIC
                ||!(before.get(2)instanceof MethodInsnNode atom)||!sameCall(atom,remapCall(plan.ground,plan))||before.get(3).getOpcode()!=Opcodes.IRETURN
                ||!load(after.get(0),2)||!(after.get(1)instanceof FieldInsnNode finalTag)||!sameField(tag,finalTag)
                ||!(after.get(2)instanceof VarInsnNode tagStore)||tagStore.getOpcode()!=Opcodes.ASTORE
                ||!(after.get(3)instanceof VarInsnNode stateStore)||stateStore.getOpcode()!=Opcodes.ASTORE
                ||tagStore.var<3||stateStore.var<3||tagStore.var==stateStore.var||!load(after.get(4),0)||!load(after.get(5),stateStore.var)||!load(after.get(6),tagStore.var)
                ||!(after.get(7)instanceof InvokeDynamicInsnNode dynamic)||!load(after.get(8),1)
                ||!(after.get(9)instanceof MethodInsnNode invoke)||invoke.getOpcode()!=Opcodes.INVOKESPECIAL||invoke.itf||!invoke.owner.equals(plan.owner)||!invoke.name.equals(handler.name)||!invoke.desc.equals(handler.desc)
                ||after.get(10).getOpcode()!=Opcodes.IRETURN)return null;
        String relayDesc="([Ljava/lang/Object;)Ljava/lang/Boolean;";
        if(!dynamic.name.equals("call")||!dynamic.desc.equals("()L"+OP+";")
                ||dynamic.bsm.getTag()!=Opcodes.H_INVOKESTATIC||dynamic.bsm.isInterface()
                ||!dynamic.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")||!dynamic.bsm.getName().equals("metafactory")
                ||!dynamic.bsm.getDesc().equals("(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;")
                ||dynamic.bsmArgs.length!=3||!Type.getMethodType("([Ljava/lang/Object;)Ljava/lang/Object;").equals(dynamic.bsmArgs[0])
                ||!(dynamic.bsmArgs[1]instanceof Handle implementation)||implementation.getTag()!=Opcodes.H_INVOKESTATIC||implementation.isInterface()
                ||!implementation.getOwner().equals(target.name)||!implementation.getDesc().equals(relayDesc)
                ||!Type.getMethodType(relayDesc).equals(dynamic.bsmArgs[2]))return null;
        List<MethodNode> matches=target.methods.stream().filter(m->m.name.equals(implementation.getName())&&m.desc.equals(relayDesc)).toList();
        return matches.size()==1&&relay(matches.getFirst(),atom)?matches.getFirst():null;
    }
    private record Value(int kind,int index,String cast) { }
    /** A small straight-line evaluator checks exact array operands and a single invocation, rather than a call name. */
    private static boolean relay(MethodNode method,MethodInsnNode atom){
        if((method.access&(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC))!=(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC)
                ||(method.access&(Opcodes.ACC_SYNCHRONIZED|Opcodes.ACC_NATIVE|Opcodes.ACC_ABSTRACT))!=0||!method.tryCatchBlocks.isEmpty()
                ||atom.getOpcode()!=Opcodes.INVOKEVIRTUAL&&atom.getOpcode()!=Opcodes.INVOKEINTERFACE)return false;
        Type[] arguments=Type.getArgumentTypes(atom.desc);if(arguments.length!=1||arguments[0].getSort()!=Type.OBJECT||!Type.getReturnType(atom.desc).equals(Type.BOOLEAN_TYPE))return false;
        List<Value> stack=new ArrayList<>();int invokes=0,checks=0,boxes=0;List<AbstractInsnNode> code=code(method);
        for(int pc=0;pc<code.size();pc++){
            AbstractInsnNode instruction=code.get(pc);int opcode=instruction.getOpcode();
            if(load(instruction,0))stack.add(new Value(0,0,""));
            else if(instruction instanceof IntInsnNode constant&&(opcode==Opcodes.BIPUSH||opcode==Opcodes.SIPUSH))stack.add(new Value(1,constant.operand,""));
            else if(opcode>=Opcodes.ICONST_0&&opcode<=Opcodes.ICONST_5)stack.add(new Value(1,opcode-Opcodes.ICONST_0,""));
            else if(instruction instanceof LdcInsnNode constant&&constant.cst instanceof String)stack.add(new Value(2,0,""));
            else if(opcode==Opcodes.DUP){if(stack.isEmpty())return false;stack.add(stack.getLast());}
            else if(opcode==Opcodes.SWAP){if(stack.size()<2)return false;Value a=stack.removeLast(),b=stack.removeLast();stack.add(a);stack.add(b);}
            else if(opcode==Opcodes.POP){if(stack.isEmpty())return false;stack.removeLast();}
            else if(opcode==Opcodes.AALOAD){if(stack.size()<2)return false;Value index=stack.removeLast(),array=stack.removeLast();if(array.kind!=0||index.kind!=1||index.index<0||index.index>1)return false;stack.add(new Value(3,index.index,""));}
            else if(instruction instanceof TypeInsnNode cast&&opcode==Opcodes.CHECKCAST){if(stack.isEmpty())return false;Value value=stack.removeLast();if(value.kind!=3||!value.cast.isEmpty()||!cast.desc.equals(value.index==0?atom.owner:arguments[0].getInternalName()))return false;stack.add(new Value(3,value.index,cast.desc));}
            else if(instruction instanceof MethodInsnNode call){
                if(call.getOpcode()==Opcodes.INVOKESTATIC&&!call.itf&&call.owner.equals("com/llamalad7/mixinextras/injector/wrapoperation/WrapOperationRuntime")&&call.name.equals("checkArgumentCount")&&call.desc.equals("([Ljava/lang/Object;ILjava/lang/String;)V")){
                    if(checks++!=0||invokes!=0||stack.size()!=3)return false;Value text=stack.removeLast(),count=stack.removeLast(),array=stack.removeLast();if(text.kind!=2||count.kind!=1||count.index!=2||array.kind!=0)return false;
                }else if(sameCall(call,atom)){
                    if(invokes++!=0||stack.size()!=2)return false;Value tag=stack.removeLast(),state=stack.removeLast();if(state.kind!=3||state.index!=0||!state.cast.equals(atom.owner)||tag.kind!=3||tag.index!=1||!tag.cast.equals(arguments[0].getInternalName()))return false;stack.add(new Value(4,0,""));
                }else if(call.getOpcode()==Opcodes.INVOKESTATIC&&!call.itf&&call.owner.equals("java/lang/Boolean")&&call.name.equals("valueOf")&&call.desc.equals("(Z)Ljava/lang/Boolean;")){
                    if(boxes++!=0||stack.size()!=1||stack.removeLast().kind!=4)return false;stack.add(new Value(5,0,""));
                }else return false;
            }else if(opcode==Opcodes.ARETURN){return pc==code.size()-1&&invokes==1&&boxes==1&&stack.size()==1&&stack.getLast().kind==5;}
            else return false;
        }return false;
    }
    private static boolean mergedFrom(MethodNode method,String owner){for(var list:Arrays.asList(method.visibleAnnotations,method.invisibleAnnotations))if(list!=null)for(AnnotationNode annotation:list)if(annotation.desc.equals(MERGED)&&owner.replace('/','.').equals(MixinFit.value(annotation,"mixin")))return true;return false;}
    private static boolean isUnique(MethodNode method){for(var list:Arrays.asList(method.visibleAnnotations,method.invisibleAnnotations))if(list!=null&&list.stream().anyMatch(a->a.desc.equals("Lorg/spongepowered/asm/mixin/Unique;")))return true;return false;}
    private static boolean same(MethodNode a,MethodNode b){return MixinInstructionFingerprint.hash(a).equals(MixinInstructionFingerprint.hash(b));}
    private static boolean load(AbstractInsnNode node,int slot){return node instanceof VarInsnNode var&&var.getOpcode()==Opcodes.ALOAD&&var.var==slot;}
    private static boolean sameCall(MethodInsnNode a,MethodInsnNode b){return a.getOpcode()==b.getOpcode()&&a.owner.equals(b.owner)&&a.name.equals(b.name)&&a.desc.equals(b.desc)&&a.itf==b.itf;}
    private static boolean sameField(FieldInsnNode a,FieldInsnNode b){return a.getOpcode()==b.getOpcode()&&a.owner.equals(b.owner)&&a.name.equals(b.name)&&a.desc.equals(b.desc);}
    private static List<AbstractInsnNode> code(MethodNode method){List<AbstractInsnNode> result=new ArrayList<>();for(AbstractInsnNode node:method.instructions)if(node.getOpcode()>=0)result.add(node);return result;}
    private static void stripSelfCasts(MethodNode method,String owner){
        // Slot zero is only the guaranteed receiver when the body has never assigned it.
        for(AbstractInsnNode node:method.instructions)if(node instanceof VarInsnNode var&&var.var==0&&var.getOpcode()>=Opcodes.ISTORE&&var.getOpcode()<=Opcodes.ASTORE||node instanceof IincInsnNode increment&&increment.var==0)return;
        for(AbstractInsnNode node:method.instructions.toArray())if(node instanceof TypeInsnNode cast&&cast.getOpcode()==Opcodes.CHECKCAST&&cast.desc.equals(owner)){AbstractInsnNode previous=node.getPrevious();while(previous!=null&&previous.getOpcode()<0)previous=previous.getPrevious();if(previous!=null&&load(previous,0))method.instructions.remove(node);}
    }
    private static String descriptor(String desc,String source,String target){return new Remapper(){@Override public String map(String name){return name.equals(source)?target:name;}}.mapMethodDesc(desc);}
    private static MethodNode normalize(MethodNode method,Plan plan,Map<String,String> aliases,boolean actual){
        MethodNode input=copy(method),out=new MethodNode(method.access,method.name,descriptor(method.desc,plan.source,plan.owner),null,null);
        Remapper remapper=new Remapper(){@Override public String map(String name){return name.equals(plan.source)?plan.owner:name;}
            @Override public String mapMethodName(String owner,String name,String desc){return actual&&owner.equals(plan.owner)?aliases.getOrDefault(name+desc,name):name;}};
        input.accept(new MethodRemapper(out,remapper));return out;
    }
    private static MethodInsnNode remapCall(MethodInsnNode call,Plan plan){return new MethodInsnNode(call.getOpcode(),call.owner.equals(plan.source)?plan.owner:call.owner,call.name,descriptor(call.desc,plan.source,plan.owner),call.itf);}
    private static MethodInsnNode callCopy(MethodInsnNode call){return new MethodInsnNode(call.getOpcode(),call.owner,call.name,call.desc,call.itf);}
    private static MethodNode copy(MethodNode method){MethodNode result=new MethodNode(method.access,method.name,method.desc,method.signature,method.exceptions==null?null:method.exceptions.toArray(String[]::new));method.accept(result);
        for(AbstractInsnNode node:result.instructions)if(node instanceof InvokeDynamicInsnNode dynamic)dynamic.bsmArgs=dynamic.bsmArgs.clone();return result;}
    public static void resetForTests(){PLANS.clear();}
}
