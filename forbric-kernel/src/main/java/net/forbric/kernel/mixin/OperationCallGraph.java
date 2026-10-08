/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import java.util.function.Function;
import java.util.function.BiFunction;
import net.forbric.api.Ecosystem;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import net.forbric.kernel.boot.DefinedMethodContracts.MethodContract;

/** A source invocation's exact operands identify one call through two current gateways, including inherited ones. */
final class OperationCallGraph implements Opcodes {
    record Plan(String host,String hostMethod,String firstOwner,String firstMethod,String gateway,int gatewayOpcode,String parentHash,
                String helper,String helperMethod,String forwarded,int forwardOrdinal,String helperHash,String member,List<MethodContract> getters,List<Integer> gatewayInputs,List<Integer> helperInputs) {
        String graph(){return firstOwner+"#"+firstMethod+"->"+helper+"#"+helperMethod+"@"+forwardOrdinal+":"+member+":"+parentHash+":"+helperHash;}
    }
    record Expr(String type,String kind,String symbol,List<Expr> inputs) { }
    record Declaration(ClassNode owner,MethodNode method) { }
    private OperationCallGraph() { }
    static Plan derive(ClassNode source,ClassNode target,String selector,String member,Function<String,ClassNode> classes) {
        return derive(source,target,selector,member,classes,NativeGameReferences::reference);
    }
    static Plan derive(ClassNode source,ClassNode target,String selector,String member,Function<String,ClassNode> classes,BiFunction<Ecosystem,String,ClassNode> natives) {
        MethodNode nativeHost=MixinStubRebind.bound(source,selector),host=MixinStubRebind.bound(target,selector);
        MixinFit.Member wanted=MixinFit.parseMember(member);
        if(nativeHost==null||host==null||wanted==null||!nativeHost.desc.equals(host.desc)||Type.getReturnType(wanted.desc())!=Type.VOID_TYPE)return null;
        List<MethodInsnNode> original=anchors(nativeHost,member);if(original.size()!=1||!anchors(host,member).isEmpty())return null;
        Evidence before=Evidence.create(source.name,nativeHost,classes,Map.of()),now=Evidence.create(target.name,host,classes,Map.of());if(before==null||now==null)return null;
        List<Expr> inputs=before.operands(original.getFirst());if(inputs==null)return null;
        List<Plan> plans=new ArrayList<>();
        for(AbstractInsnNode instruction:host.instructions)if(instruction instanceof MethodInsnNode gateway&&Type.getReturnType(gateway.desc)==Type.VOID_TYPE&&!gateway.name.equals("<init>")) {
            Declaration parent=resolve(classes,gateway.owner,gateway.name,gateway.desc,new HashSet<>());if(parent==null||!parent.method.tryCatchBlocks.isEmpty())continue;
            if(anchors(host,NativeCallChanges.member(gateway)).size()!=1)continue;
            List<Expr> carried=now.operands(gateway);if(carried==null)continue;Map<Integer,Expr> parameters=parameters(parent.method,carried);if(parameters==null)continue;
            Evidence inside=Evidence.create(parent.owner.name,parent.method,classes,parameters);if(inside==null)continue;
            Map<String,Integer> ordinals=new HashMap<>();
            for(AbstractInsnNode next:parent.method.instructions)if(next instanceof MethodInsnNode forward) {
                String forwardMember=NativeCallChanges.member(forward);int ordinal=ordinals.merge(forwardMember,1,Integer::sum)-1;
                if(forward.getOpcode()!=INVOKESTATIC||Type.getReturnType(forward.desc)!=Type.VOID_TYPE)continue;
                Declaration helper=resolve(classes,forward.owner,forward.name,forward.desc,new HashSet<>());if(helper==null||(helper.method.access&ACC_STATIC)==0)continue;
                List<MethodInsnNode> moved=anchors(helper.method,member);if(moved.size()!=1||recurs(helper.method,helper.owner.name)||loopsAcross(helper.method,moved.getFirst()))continue;
                List<Expr> forwarded=inside.operands(forward);if(forwarded==null)continue;Map<Integer,Expr> helperParameters=parameters(helper.method,forwarded);if(helperParameters==null)continue;
                Evidence terminal=Evidence.create(helper.owner.name,helper.method,classes,helperParameters);
                if(terminal==null||!sameRoles(inputs,terminal.operands(moved.getFirst()),target,host,gateway,carried,classes,natives))continue;
                List<Integer> gatewayInputs=new ArrayList<>(),helperInputs=new ArrayList<>();
                for(int a=0;a<carried.size();a++)if(carried.get(a).kind.equals("new"))for(int b=0;b<forwarded.size();b++)if(carried.get(a).equals(forwarded.get(b))){gatewayInputs.add(a);helperInputs.add(b);}
                if(gatewayInputs.isEmpty())continue;
                List<MethodContract> getters=new ArrayList<>();getters.addAll(now.getters);getters.addAll(inside.getters);getters.addAll(terminal.getters);
                plans.add(new Plan(target.name,host.name+host.desc,parent.owner.name,parent.method.name+parent.method.desc,NativeCallChanges.member(gateway),gateway.getOpcode(),MixinInstructionFingerprint.hash(parent.method),
                    helper.owner.name,helper.method.name+helper.method.desc,forwardMember,ordinal,MixinInstructionFingerprint.hash(helper.method),member,List.copyOf(new LinkedHashSet<>(getters)),List.copyOf(gatewayInputs),List.copyOf(helperInputs)));
            }
        }
        return plans.size()==1?plans.getFirst():null;
    }
    /** Carrier changes to primitive arguments remain its actual values; receiver and object identity still locate the source role. */
    private static boolean sameRoles(List<Expr> source,List<Expr> current,ClassNode owner,MethodNode host,MethodInsnNode gateway,List<Expr> carried,Function<String,ClassNode> classes,BiFunction<Ecosystem,String,ClassNode> natives) {
        if(current==null||source.size()!=current.size())return false;boolean nativeChange=false;
        for(int i=0;i<source.size();i++)if(!source.get(i).equals(current.get(i))){String type=source.get(i).type;if(!type.equals(current.get(i).type)||type.startsWith("L")||type.startsWith("["))return false;nativeChange=true;}
        if(!nativeChange)return true;
        for(Ecosystem family:Ecosystem.values()){MethodNode original=NativeCallChanges.method(natives.apply(family,owner.name),host.name+host.desc);if(original==null)continue;
            List<MethodInsnNode> calls=anchors(original,NativeCallChanges.member(gateway));Evidence nativeEvidence=Evidence.create(owner.name,original,classes,Map.of());
            if(calls.size()==1&&nativeEvidence!=null&&carried.equals(nativeEvidence.operands(calls.getFirst())))return true;
        }
        return false;
    }
    private static boolean recurs(MethodNode method,String owner){return Arrays.stream(method.instructions.toArray()).anyMatch(instruction->instruction instanceof MethodInsnNode call&&call.owner.equals(owner)&&call.name.equals(method.name)&&call.desc.equals(method.desc));}
    private static boolean loopsAcross(MethodNode method,MethodInsnNode point){int at=method.instructions.indexOf(point);for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof JumpInsnNode branch&&method.instructions.indexOf(instruction)>at&&method.instructions.indexOf(branch.label)<=at)return true;return false;}
    private static List<MethodInsnNode> anchors(MethodNode method,String member){return Arrays.stream(method.instructions.toArray()).filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).filter(call->NativeCallChanges.member(call).equals(member)).toList();}
    private static Map<Integer,Expr> parameters(MethodNode method,List<Expr> values){Type[] arguments=Type.getArgumentTypes(method.desc);boolean instance=(method.access&ACC_STATIC)==0;if(values.size()!=arguments.length+(instance?1:0))return null;Map<Integer,Expr> result=new HashMap<>();int slot=0,index=0;if(instance)result.put(slot++,values.get(index++));for(Type argument:arguments){result.put(slot,values.get(index++));slot+=argument.getSize();}return result;}
    private static Declaration resolve(Function<String,ClassNode> classes,String owner,String name,String descriptor,Set<String> seen){if(owner==null||!seen.add(owner))return null;ClassNode type=classes.apply(owner);if(type==null)return null;MethodNode method=NativeCallChanges.method(type,name+descriptor);if(method!=null)return new Declaration(type,method);Declaration parent=resolve(classes,type.superName,name,descriptor,seen);if(parent!=null)return parent;Declaration found=null;for(String iface:type.interfaces){Declaration candidate=resolve(classes,iface,name,descriptor,seen);if(candidate!=null){if(found!=null)return null;found=candidate;}}return found;}
    private static final class Origins extends SourceInterpreter {
        final Map<AbstractInsnNode,Expr> parameters=new IdentityHashMap<>();final Map<Integer,Expr> supplied;final String owner;
        Origins(String owner,Map<Integer,Expr> supplied){super(ASM9);this.owner=owner;this.supplied=supplied;}
        @Override public SourceValue newParameterValue(boolean instance,int local,Type type){VarInsnNode identity=new VarInsnNode(type.getOpcode(ILOAD),local);parameters.put(identity,supplied.getOrDefault(local,new Expr(type.getDescriptor(),"parameter",owner+":"+local,List.of())));return new SourceValue(type.getSize(),identity);}
        @Override public SourceValue copyOperation(AbstractInsnNode instruction,SourceValue value){return value;}
    }
    private static final class Evidence {
        final MethodNode method;final Frame<SourceValue>[] frames;final Origins origins;final Function<String,ClassNode> classes;
        final Map<AbstractInsnNode,Expr> cache=new IdentityHashMap<>();final Set<AbstractInsnNode> visiting=Collections.newSetFromMap(new IdentityHashMap<>());final Set<MethodContract> getters=new LinkedHashSet<>();
        Evidence(MethodNode method,Frame<SourceValue>[] frames,Origins origins,Function<String,ClassNode> classes){this.method=method;this.frames=frames;this.origins=origins;this.classes=classes;}
        static Evidence create(String owner,MethodNode method,Function<String,ClassNode> classes,Map<Integer,Expr> parameters){try{Origins origins=new Origins(owner,parameters);return new Evidence(method,new Analyzer<>(origins).analyze(owner,method),origins,classes);}catch(AnalyzerException|RuntimeException invalid){return null;}}
        List<Expr> operands(MethodInsnNode call){return inputs(call,Type.getArgumentTypes(call.desc).length+(call.getOpcode()==INVOKESTATIC?0:1));}
        List<Expr> inputs(AbstractInsnNode instruction,int count){int index=method.instructions.indexOf(instruction);Frame<SourceValue> frame=index<0?null:frames[index];if(frame==null||frame.getStackSize()<count)return null;List<Expr> values=new ArrayList<>();for(int i=frame.getStackSize()-count;i<frame.getStackSize();i++){Expr expression=value(frame.getStack(i));if(expression==null)return null;values.add(expression);}return List.copyOf(values);}
        Expr value(SourceValue value){return value!=null&&value.insns.size()==1?producer(value.insns.iterator().next()):null;}
        Expr producer(AbstractInsnNode instruction){if(cache.containsKey(instruction))return cache.get(instruction);if(!visiting.add(instruction))return null;Expr result=origins.parameters.get(instruction);int opcode=instruction.getOpcode();
            if(result==null&&instruction instanceof FieldInsnNode field&&(opcode==GETFIELD||opcode==GETSTATIC)){List<Expr> inputs=opcode==GETSTATIC?List.of():inputs(instruction,1);if(inputs!=null)result=new Expr(field.desc,"field",field.owner+"."+field.name,inputs);}
            else if(result==null&&instruction instanceof MethodInsnNode call){List<Expr> inputs=operands(call);if(inputs!=null){result=getter(call,inputs);if(result==null)result=new Expr(Type.getReturnType(call.desc).getDescriptor(),"call",NativeCallChanges.member(call),inputs);}}
            else if(result==null&&instruction instanceof TypeInsnNode type&&opcode==NEW)result=construction(type);
            else if(result==null&&instruction instanceof LdcInsnNode literal)result=new Expr(literal.cst instanceof Integer?"I":literal.cst instanceof Float?"F":literal.cst instanceof Long?"J":literal.cst instanceof Double?"D":"Ljava/lang/Object;","constant",literal.cst.getClass().getName()+":"+literal.cst,List.of());
            else if(result==null&&instruction instanceof IntInsnNode integer&&(opcode==BIPUSH||opcode==SIPUSH))result=new Expr("I","integer",Integer.toString(integer.operand),List.of());
            else if(result==null&&opcode>=ICONST_M1&&opcode<=DCONST_1)result=new Expr(opcode<=ICONST_5?"I":opcode<=LCONST_1?"J":opcode<=FCONST_2?"F":"D","constant-op",Integer.toString(opcode),List.of());
            else if(result==null&&opcode>=IADD&&opcode<=DREM){List<Expr> operands=inputs(instruction,2);if(operands!=null)result=new Expr(switch((opcode-IADD)%4){case 1->"J";case 2->"F";case 3->"D";default->"I";},"binary",Integer.toString(opcode),operands);}
            else if(result==null&&opcode>=I2L&&opcode<=I2S){List<Expr> operands=inputs(instruction,1);if(operands!=null)result=new Expr(switch(opcode){case I2L,F2L,D2L->"J";case I2F,L2F,D2F->"F";case I2D,L2D,F2D->"D";default->"I";},"convert",Integer.toString(opcode),operands);}
            visiting.remove(instruction);cache.put(instruction,result);return result;
        }
        Expr getter(MethodInsnNode call,List<Expr> values){if(call.getOpcode()==INVOKESTATIC||values.size()!=1||Type.getArgumentTypes(call.desc).length!=0||!values.getFirst().type.startsWith("L"))return null;
            String receiver=Type.getType(values.getFirst().type).getInternalName();Declaration declaration=resolve(classes,receiver,call.name,call.desc,new HashSet<>());if(declaration==null||!declaration.method.tryCatchBlocks.isEmpty())return null;
            List<AbstractInsnNode> code=Arrays.stream(declaration.method.instructions.toArray()).filter(i->i.getOpcode()>=0).toList();if(code.size()!=3||!(code.getFirst()instanceof VarInsnNode self)||self.getOpcode()!=ALOAD||self.var!=0||!(code.get(1)instanceof FieldInsnNode field)||field.getOpcode()!=GETFIELD||code.getLast().getOpcode()!=Type.getReturnType(call.desc).getOpcode(IRETURN))return null;
            getters.add(new MethodContract(declaration.owner.name,declaration.method.name,declaration.method.desc,MixinInstructionFingerprint.hash(declaration.method)));return new Expr(field.desc,"field",field.owner+"."+field.name,values);
        }
        Expr construction(TypeInsnNode allocation){MethodInsnNode constructor=null;List<Expr> arguments=null;
            if(Arrays.stream(method.instructions.toArray()).filter(instruction->instruction instanceof TypeInsnNode created&&created.getOpcode()==NEW&&created.desc.equals(allocation.desc)).count()!=1)return null;
            for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof MethodInsnNode call&&call.getOpcode()==INVOKESPECIAL&&call.name.equals("<init>")&&call.owner.equals(allocation.desc)){
                var frame=frames[method.instructions.indexOf(call)];int count=Type.getArgumentTypes(call.desc).length;if(frame==null||frame.getStackSize()<=count||!frame.getStack(frame.getStackSize()-count-1).insns.equals(Set.of(allocation)))continue;
                if(constructor!=null)return null;constructor=call;List<Expr> inputs=new ArrayList<>();for(int i=frame.getStackSize()-count;i<frame.getStackSize();i++){Expr input=value(frame.getStack(i));if(input==null)return null;inputs.add(input);}arguments=List.copyOf(inputs);
            }
            return constructor==null?null:new Expr("L"+allocation.desc+";","new",origins.owner+"#"+method.name+method.desc+":"+allocation.desc+constructor.desc,arguments);
        }
    }
}
