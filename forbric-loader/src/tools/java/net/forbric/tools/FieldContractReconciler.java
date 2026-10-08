/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.tools;

import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Reconciles a retyped private storage cell only when the actual constructor computation is identical and
 * one declared provider contract satisfies every field view. The stable stronger view reads the owner cell on every
 * operation; invalidation is optional for arbitrary replacements, and retains the native provider's synchronization. No class/field/method is selected by a configured name. */
final class FieldContractReconciler {
    static int reconcile(ClassNode merged,ClassNode a,ClassNode canonical,ContractGraph graph,Map<String,ClassNode> generated){
        Map<String,List<FieldNode>> groups=new LinkedHashMap<>();for(FieldNode f:merged.fields)groups.computeIfAbsent(f.name,k->new ArrayList<>()).add(f);int fixed=0;
        for(var entry:groups.entrySet()){
            List<FieldNode> fields=entry.getValue();if(fields.size()!=2||fields.stream().anyMatch(f->Type.getType(f.desc).getSort()!=Type.OBJECT||(f.access&Opcodes.ACC_PRIVATE)==0))continue;
            List<FieldNode> strongest=fields.stream().filter(f->fields.stream().allMatch(other->graph.assignable(f.desc,other.desc))).toList();
            if(strongest.size()!=1)continue;FieldNode selected=strongest.get(0),nativeField=fields.stream().filter(f->canonical.fields.stream().anyMatch(original->original.name.equals(f.name)&&original.desc.equals(f.desc))).findFirst().orElse(null);
            if(nativeField==null||!nativeField.desc.equals("Ljava/util/function/Supplier;"))continue;ClassNode other=canonical==a?canonical:a;int constructors=0;
            for(MethodNode constructor:new ArrayList<>(merged.methods))if(constructor.name.equals("<init>")){
                MethodNode chosen=ContractGraph.ownMethod(canonical,constructor.name,constructor.desc),alternative=ContractGraph.ownMethod(other,constructor.name,constructor.desc);
                if(chosen==null||alternative==null||!functionalFactory(chosen,selected.name)||!functionalFactory(alternative,selected.name))continue;
                if(!sameConstruction(canonical,chosen,other,alternative,selected.name,graph))throw new IllegalStateException("Incompatible initializer dependency on "+merged.name+"#"+selected.name+": "+chosen.desc);
                if(List.of(a,canonical).stream().flatMap(source->source.methods.stream()).filter(method->!method.name.equals("<init>")).anyMatch(method->ContractGraph.code(method).stream().anyMatch(i->i instanceof FieldInsnNode field&&field.getOpcode()==Opcodes.PUTFIELD&&field.owner.equals(merged.name)&&field.name.equals(selected.name))))throw new IllegalStateException("Mutable provider field cannot share a coherent cell: "+merged.name+"#"+selected.name);
                MethodInsnNode nativeFactory=factory(chosen,selected.name),peerFactory=factory(alternative,selected.name);String nativeProvider=allocation(nativeFactory,graph),peerProvider=allocation(peerFactory,graph);
                ClassNode provider=graph.node(nativeProvider),peer=graph.node(peerProvider);MemoizedSupplierContract.Proof nativeProof=provider==null?null:MemoizedSupplierContract.prove(provider,graph),peerProof=peer==null?null:MemoizedSupplierContract.prove(peer,graph);
                if(nativeProof==null||peerProof==null)throw new IllegalStateException("Provider behavior has no proved Supplier cache/invalidation contract on "+merged.name+"#"+selected.name+": "+nativeProvider+" versus "+peerProvider);
                ClassNode carrier=carrier(merged,nativeField,selected.desc,provider,nativeProof,peerProof,graph);
                ClassNode previous=generated.putIfAbsent(carrier.name,carrier);if(previous!=null&&!tokens(ContractGraph.ownMethod(previous,"get","()Ljava/lang/Object;")).equals(tokens(ContractGraph.ownMethod(carrier,"get","()Ljava/lang/Object;"))))throw new IllegalStateException("Live provider carrier name collision on "+carrier.name);
                graph.replace(carrier);
                MethodNode copy=copy(chosen);
                for(AbstractInsnNode instruction:copy.instructions.toArray())if(instruction instanceof FieldInsnNode write&&write.getOpcode()==Opcodes.PUTFIELD&&write.owner.equals(merged.name)&&write.name.equals(nativeField.name)&&write.desc.equals(nativeField.desc)){
                    InsnList bridge=new InsnList();bridge.add(new VarInsnNode(Opcodes.ALOAD,0));bridge.add(new VarInsnNode(Opcodes.ALOAD,0));
                    bridge.add(new MethodInsnNode(Opcodes.INVOKESTATIC,carrier.name,"wrap","(L"+merged.name+";)"+selected.desc,false));
                    FieldNode alias=fields.stream().filter(f->f!=nativeField).findFirst().orElseThrow();bridge.add(new FieldInsnNode(Opcodes.PUTFIELD,merged.name,alias.name,alias.desc));copy.instructions.insert(write,bridge);
                }
                merged.methods.set(merged.methods.indexOf(constructor),copy);guardNativeResets(merged,nativeField,selected.desc,provider,nativeProof);constructors++;
            }
            if(constructors==0)continue;
            fixed++;System.out.println("[field-contract] "+merged.name+"#"+selected.name+": canonical native provider retained; stronger interface reads the live canonical Supplier cell, "+fields.size()+" nominal views");
        }return fixed;
    }
    private static MethodInsnNode factory(MethodNode method,String field){List<AbstractInsnNode> code=ContractGraph.code(method);for(int i=1;i<code.size();i++)if(code.get(i) instanceof FieldInsnNode write&&write.getOpcode()==Opcodes.PUTFIELD&&write.name.equals(field)&&code.get(i-1) instanceof MethodInsnNode call)return call;return null;}
    private static String allocation(MethodInsnNode factory,ContractGraph graph){MethodNode method=factory==null?null:graph.method(factory.owner,factory.name,factory.desc);List<AbstractInsnNode> code=method==null?List.of():ContractGraph.code(method);return code.isEmpty()||!(code.get(0) instanceof TypeInsnNode create)||create.getOpcode()!=Opcodes.NEW?null:create.desc;}
    private static ClassNode carrier(ClassNode owner,FieldNode field,String contract,ClassNode provider,MemoizedSupplierContract.Proof protocol,MemoizedSupplierContract.Proof peer,ContractGraph graph){
        ClassNode itf=graph.node(Type.getType(contract).getInternalName());if(itf==null||(itf.access&Opcodes.ACC_INTERFACE)==0||(provider.access&Opcodes.ACC_PUBLIC)==0)throw new IllegalStateException("Canonical provider needs a public interface carrier: "+owner.name+" "+contract+" "+provider.name);
        String identity=Integer.toUnsignedString(Objects.hash(field.name,field.desc,contract,provider.name),16),accessor="forbric$providerCell$"+identity;
        MethodNode cell=new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_FINAL|Opcodes.ACC_SYNTHETIC,accessor,"()Ljava/util/function/Supplier;",null,null);
        cell.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));cell.instructions.add(new FieldInsnNode(Opcodes.GETFIELD,owner.name,field.name,field.desc));cell.instructions.add(new InsnNode(Opcodes.ARETURN));
        MethodNode old=ContractGraph.ownMethod(owner,cell.name,cell.desc);if(old==null)owner.methods.add(cell);else if(!tokens(old).equals(tokens(cell)))throw new IllegalStateException("Live cell accessor collision on "+owner.name+"#"+cell.name);
        ClassNode carrier=new ClassNode();carrier.version=Opcodes.V17;carrier.access=Opcodes.ACC_PUBLIC|Opcodes.ACC_FINAL|Opcodes.ACC_SYNTHETIC;carrier.name=owner.name+"$forbricProvider$"+identity;carrier.superName="java/lang/Object";carrier.interfaces.add(itf.name);String delegate="L"+owner.name+";";
        carrier.fields.add(new FieldNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_FINAL,"owner",delegate,null,null));
        MethodNode init=new MethodNode(Opcodes.ACC_PRIVATE,"<init>","("+delegate+")V",null,null);init.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false));init.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));init.instructions.add(new VarInsnNode(Opcodes.ALOAD,1));init.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD,carrier.name,"owner",delegate));init.instructions.add(new InsnNode(Opcodes.RETURN));carrier.methods.add(init);
        MethodNode wrap=new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"wrap","("+delegate+")"+contract,null,null);wrap.instructions.add(new TypeInsnNode(Opcodes.NEW,carrier.name));wrap.instructions.add(new InsnNode(Opcodes.DUP));wrap.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));wrap.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,carrier.name,"<init>",init.desc,false));wrap.instructions.add(new InsnNode(Opcodes.ARETURN));carrier.methods.add(wrap);
        for(MethodNode required:graph.interfaceContracts(carrier).values())if((required.access&Opcodes.ACC_ABSTRACT)!=0){
            boolean getter=required.name.equals("get")&&required.desc.equals("()Ljava/lang/Object;"),reset=peer.resets().contains(required.name+required.desc);
            if(!getter&&!reset)throw new IllegalStateException("No proved live carrier operation for "+owner.name+"#"+required.name+required.desc);
            MethodNode method=new MethodNode(Opcodes.ACC_PUBLIC,required.name,required.desc,null,null);method.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD,carrier.name,"owner",delegate));method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,owner.name,accessor,cell.desc,false));
            if(getter){method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,"java/util/function/Supplier","get","()Ljava/lang/Object;",true));method.instructions.add(new InsnNode(Opcodes.ARETURN));}
            else{
                String nativeReset=protocol.resets().contains(required.name+required.desc)?required.name+required.desc:protocol.resets().size()==1?protocol.resets().iterator().next():null;
                if(nativeReset==null)throw new IllegalStateException("Ambiguous native reset operation on "+provider.name);
                String helper="forbric$resetProvider$"+identity+"$"+Integer.toUnsignedString(required.name.hashCode(),16);MethodNode guarded=guard(helper,provider.name,nativeReset,itf.name,required.name+required.desc);
                MethodNode previous=ContractGraph.ownMethod(owner,helper,guarded.desc);if(previous==null)owner.methods.add(guarded);else if(!tokens(previous).equals(tokens(guarded)))throw new IllegalStateException("Provider reset guard collision on "+owner.name+"#"+helper);
                method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,owner.name,helper,guarded.desc,false));method.instructions.add(new InsnNode(Opcodes.RETURN));
            }
            carrier.methods.add(method);
        }return carrier;
    }
    private static MethodNode guard(String name,String nativeProvider,String nativeReset,String contract,String required){
        MethodNode method=new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC|Opcodes.ACC_SYNTHETIC,name,"(Ljava/util/function/Supplier;)V",null,null);LabelNode other=new LabelNode(),end=new LabelNode();InsnList code=method.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(new TypeInsnNode(Opcodes.INSTANCEOF,nativeProvider));code.add(new JumpInsnNode(Opcodes.IFEQ,other));
        code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(new TypeInsnNode(Opcodes.CHECKCAST,nativeProvider));code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,nativeProvider,nativeReset.substring(0,nativeReset.indexOf('(')),"()V",false));code.add(new InsnNode(Opcodes.RETURN));
        code.add(other);code.add(new FrameNode(Opcodes.F_NEW,1,new Object[]{"java/util/function/Supplier"},0,new Object[0]));code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(new TypeInsnNode(Opcodes.INSTANCEOF,contract));code.add(new JumpInsnNode(Opcodes.IFEQ,end));
        code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(new TypeInsnNode(Opcodes.CHECKCAST,contract));code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,contract,required.substring(0,required.indexOf('(')),"()V",true));code.add(end);code.add(new FrameNode(Opcodes.F_NEW,1,new Object[]{"java/util/function/Supplier"},0,new Object[0]));code.add(new InsnNode(Opcodes.RETURN));return method;
    }
    private static void guardNativeResets(ClassNode owner,FieldNode field,String contract,ClassNode provider,MemoizedSupplierContract.Proof protocol){
        String identity=Integer.toUnsignedString(Objects.hash(field.name,field.desc,contract,provider.name),16);
        List<MethodNode> guards=owner.methods.stream().filter(method->method.name.startsWith("forbric$resetProvider$"+identity+"$")&&method.desc.equals("(Ljava/util/function/Supplier;)V")).toList();
        for(MethodNode method:List.copyOf(owner.methods))if(!guards.contains(method))for(AbstractInsnNode instruction:method.instructions.toArray())if(instruction instanceof MethodInsnNode call&&call.owner.equals(provider.name)&&protocol.resets().contains(call.name+call.desc)){
            AbstractInsnNode cast=previous(call),read=previous(cast);
            if(!(cast instanceof TypeInsnNode type)||type.getOpcode()!=Opcodes.CHECKCAST||!type.desc.equals(provider.name)||!(read instanceof FieldInsnNode access)||access.getOpcode()!=Opcodes.GETFIELD||!access.owner.equals(owner.name)||!access.name.equals(field.name)||!access.desc.equals(field.desc))continue;
            MethodNode matching=guards.stream().filter(guard->ContractGraph.code(guard).stream().anyMatch(i->i instanceof MethodInsnNode operation&&operation.owner.equals(provider.name)&&operation.name.equals(call.name)&&operation.desc.equals(call.desc))).findFirst().orElseThrow();
            method.instructions.remove(cast);call.setOpcode(Opcodes.INVOKESTATIC);call.owner=owner.name;call.name=matching.name;call.desc=matching.desc;call.itf=false;
        }
    }
    private static AbstractInsnNode previous(AbstractInsnNode instruction){AbstractInsnNode previous=instruction.getPrevious();while(previous!=null&&previous.getOpcode()<0)previous=previous.getPrevious();return previous;}

    private static boolean functionalFactory(MethodNode method,String field){
        List<AbstractInsnNode> code=ContractGraph.code(method);
        for(int i=1;i<code.size();i++)if(code.get(i) instanceof FieldInsnNode write&&write.getOpcode()==Opcodes.PUTFIELD&&write.name.equals(field)
                &&code.get(i-1) instanceof MethodInsnNode call&&call.getOpcode()==Opcodes.INVOKESTATIC
                &&Arrays.equals(Type.getArgumentTypes(call.desc),new Type[]{Type.getObjectType("java/util/function/Supplier")}))return true;
        return false;
    }

    private static boolean sameConstruction(ClassNode a,MethodNode am,ClassNode b,MethodNode bm,String field,ContractGraph graph){
        if(!am.tryCatchBlocks.isEmpty()||!bm.tryCatchBlocks.isEmpty())return false;
        List<AbstractInsnNode> x=ContractGraph.code(am),y=ContractGraph.code(bm);if(x.size()!=y.size())return false;
        for(int i=0;i<x.size();i++){
            AbstractInsnNode l=x.get(i),r=y.get(i);
            if(l instanceof FieldInsnNode lf&&r instanceof FieldInsnNode rf&&lf.name.equals(field)&&rf.name.equals(field)&&lf.owner.equals(a.name)&&rf.owner.equals(b.name)){if(lf.getOpcode()!=rf.getOpcode())return false;continue;}
            if(l instanceof MethodInsnNode lc&&r instanceof MethodInsnNode rc&&i+1<x.size()&&x.get(i+1) instanceof FieldInsnNode f&&f.name.equals(field)){
                if(!transparentFactory(lc,graph)||!transparentFactory(rc,graph)||!Arrays.equals(Type.getArgumentTypes(lc.desc),Type.getArgumentTypes(rc.desc))||i==0
                        ||!(x.get(i-1) instanceof InvokeDynamicInsnNode supplier)||!supplier.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")||!supplier.bsm.getName().equals("metafactory")
                        ||!Type.getReturnType(supplier.desc).equals(Type.getObjectType("java/util/function/Supplier"))||supplier.bsmArgs.length!=3||!(supplier.bsmArgs[1] instanceof Handle handle)||!handle.getOwner().equals(a.name))return false;continue;
            }
            if(!instructionToken(x,l).equals(instructionToken(y,r)))return false;
            if(l instanceof InvokeDynamicInsnNode indy)for(Object value:indy.bsmArgs)if(value instanceof Handle handle&&handle.getOwner().equals(a.name)){
                MethodNode ah=ContractGraph.ownMethod(a,handle.getName(),handle.getDesc()),bh=ContractGraph.ownMethod(b,handle.getName(),handle.getDesc());
                if(ah==null||bh==null||!tokens(ah).equals(tokens(bh))||!NonNullReturnProof.method(graph,a.name,ah))return false;
            }
        }
        return true;
    }
    private static boolean transparentFactory(MethodInsnNode call,ContractGraph graph){
        if(call.getOpcode()!=Opcodes.INVOKESTATIC)return false;MethodNode method=graph.method(call.owner,call.name,call.desc);if(method==null||!method.tryCatchBlocks.isEmpty())return false;
        List<AbstractInsnNode> code=ContractGraph.code(method);Type[] args=Type.getArgumentTypes(call.desc);
        if(code.size()!=args.length+4||!(code.get(0) instanceof TypeInsnNode create)||create.getOpcode()!=Opcodes.NEW||code.get(1).getOpcode()!=Opcodes.DUP||code.get(code.size()-1).getOpcode()!=Opcodes.ARETURN)return false;
        int slot=0;for(int i=0;i<args.length;i++){if(!(code.get(i+2) instanceof VarInsnNode load)||load.var!=slot||load.getOpcode()!=args[i].getOpcode(Opcodes.ILOAD))return false;slot+=args[i].getSize();}
        return code.get(code.size()-2) instanceof MethodInsnNode constructor&&constructor.getOpcode()==Opcodes.INVOKESPECIAL&&constructor.owner.equals(create.desc)&&constructor.name.equals("<init>")
                &&localConstructor(graph,create.desc,constructor.desc,new HashSet<>());
    }
    private static boolean localConstructor(ContractGraph graph,String owner,String desc,Set<String> seen){
        if(!seen.add(owner+desc))return false;ClassNode klass=graph.node(owner);if(klass==null||ContractGraph.ownMethod(klass,"<clinit>","()V")!=null)return false;
        MethodNode constructor=ContractGraph.ownMethod(klass,"<init>",desc);if(constructor==null||!constructor.tryCatchBlocks.isEmpty())return false;
        for(AbstractInsnNode i:constructor.instructions){
            if(i instanceof FieldInsnNode f&&((f.getOpcode()==Opcodes.PUTFIELD&&!f.owner.equals(owner))||f.getOpcode()==Opcodes.PUTSTATIC||f.getOpcode()==Opcodes.GETSTATIC))return false;
            if(i instanceof InvokeDynamicInsnNode||i instanceof JumpInsnNode)return false;
            if(i instanceof TypeInsnNode create&&create.getOpcode()==Opcodes.NEW&&!create.desc.equals("java/lang/Object"))return false;
            if(i instanceof MethodInsnNode call){
                if(call.name.equals("<init>")&&call.owner.equals("java/lang/Object")&&call.desc.equals("()V"))continue;
                if(call.name.equals("<init>")&&call.owner.equals(klass.superName)&&localConstructor(graph,call.owner,call.desc,seen))continue;
                return false;
            }
        }return true;
    }

    private static MethodNode copy(MethodNode method){MethodNode copy=new MethodNode(method.access,method.name,method.desc,method.signature,method.exceptions.toArray(String[]::new));method.accept(copy);return copy;}
    static List<String> tokens(MethodNode method){List<AbstractInsnNode> code=ContractGraph.code(method);List<String> result=code.stream().map(i->instructionToken(code,i)).collect(java.util.stream.Collectors.toCollection(ArrayList::new));for(TryCatchBlockNode handler:method.tryCatchBlocks)result.add("handler "+position(code,handler.start)+" "+position(code,handler.end)+" "+position(code,handler.handler)+" "+handler.type);return result;}
    private static String instructionToken(List<AbstractInsnNode> code,AbstractInsnNode instruction){
        if(instruction instanceof JumpInsnNode jump)return instruction.getOpcode()+" jump "+position(code,jump.label);
        if(instruction instanceof TableSwitchInsnNode table)return instruction.getOpcode()+" table "+table.min+" "+table.max+" "+position(code,table.dflt)+" "+table.labels.stream().map(label->position(code,label)).toList();
        if(instruction instanceof LookupSwitchInsnNode lookup)return instruction.getOpcode()+" switch "+lookup.keys+" "+position(code,lookup.dflt)+" "+lookup.labels.stream().map(label->position(code,label)).toList();
        return token(instruction);
    }
    private static int position(List<AbstractInsnNode> code,AbstractInsnNode label){while(label!=null&&label.getOpcode()<0)label=label.getNext();return label==null?code.size():code.indexOf(label);}
    static String token(AbstractInsnNode i){
        if(i instanceof VarInsnNode v)return i.getOpcode()+" var "+v.var;
        if(i instanceof FieldInsnNode f)return i.getOpcode()+" field "+f.owner+"#"+f.name+f.desc;
        if(i instanceof MethodInsnNode m)return i.getOpcode()+" call "+m.owner+"#"+m.name+m.desc+" "+m.itf;
        if(i instanceof InvokeDynamicInsnNode d)return i.getOpcode()+" indy "+d.name+d.desc+d.bsm+Arrays.toString(d.bsmArgs);
        if(i instanceof TypeInsnNode t)return i.getOpcode()+" type "+t.desc;
        if(i instanceof LdcInsnNode l)return i.getOpcode()+" ldc "+l.cst;
        if(i instanceof IntInsnNode v)return i.getOpcode()+" int "+v.operand;
        return i.getOpcode()+" "+i.getClass().getSimpleName();
    }
}
