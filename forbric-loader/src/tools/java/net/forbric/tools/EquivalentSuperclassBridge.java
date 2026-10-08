/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.tools;

import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;

/** A bridge between two immutable, behavior-identical ancestor definitions, proved from their actual bytes. */
final class EquivalentSuperclassBridge {
    static boolean equivalent(ClassNode child,ClassNode peer){
        if(child==null||peer==null||!Objects.equals(child.superName,peer.superName)||child.name.equals(peer.name)
                ||(child.access&(Opcodes.ACC_INTERFACE|Opcodes.ACC_FINAL))!=0||(peer.access&(Opcodes.ACC_INTERFACE|Opcodes.ACC_FINAL))!=0
                ||(peer.access&Opcodes.ACC_PUBLIC)==0||!child.interfaces.equals(peer.interfaces)||child.fields.size()!=peer.fields.size()
                ||child.methods.size()!=peer.methods.size()||!Objects.equals(child.signature,peer.signature))return false;
        for(FieldNode field:child.fields){
            if((field.access&Opcodes.ACC_STATIC)!=0||(field.access&Opcodes.ACC_FINAL)==0||field.desc.contains(child.name))return false;
            FieldNode other=peer.fields.stream().filter(f->f.name.equals(field.name)&&f.desc.equals(field.desc)&&f.access==field.access&&Objects.equals(f.signature,field.signature)).findFirst().orElse(null);
            if(other==null)return false;
        }
        for(MethodNode method:child.methods){
            MethodNode other=ContractGraph.ownMethod(peer,method.name,method.desc);
            if(other==null||method.access!=other.access||!Objects.equals(method.signature,other.signature)||!method.exceptions.equals(other.exceptions)
                    ||method.name.equals("<clinit>")||method.desc.contains(child.name)||!method.tryCatchBlocks.isEmpty()
                    ||!fingerprint(child,method).equals(fingerprint(peer,other)))return false;
            // A constructor's nominal self type is observable too: super(Self.class) must not be
            // normalized into an equal superclass initializer for a different runtime type.
            for(AbstractInsnNode i:method.instructions){
                if(i instanceof TypeInsnNode type&&type.desc.equals(child.name))return false;
                if(i instanceof LdcInsnNode value&&value.cst instanceof Type type&&type.getDescriptor().contains(child.name))return false;
            }
            if(method.name.equals("<init>")){
                if((other.access&Opcodes.ACC_PUBLIC)==0||constructorSuper(method,child.superName)<0||!assignmentSuffix(method,child))return false;
            }
        }
        return child.methods.stream().anyMatch(m->m.name.equals("<init>"));
    }
    static ClassNode bridge(ClassNode original,ClassNode peer){
        if(!equivalent(original,peer))throw new IllegalArgumentException("Ancestors are not immutable behavioral equivalents: "+original.name+" and "+peer.name);
        ClassNode child=new ClassNode();original.accept(child);child.superName=peer.name;
        child.signature=signature(original,peer.name);
        for(MethodNode constructor:child.methods)if(constructor.name.equals("<init>")){
            int at=constructorSuper(constructor,original.superName);List<AbstractInsnNode> code=ContractGraph.code(constructor);
            InsnList replacement=new InsnList();replacement.add(new VarInsnNode(Opcodes.ALOAD,0));int slot=1;
            for(Type argument:Type.getArgumentTypes(constructor.desc)){replacement.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD),slot));slot+=argument.getSize();}
            replacement.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,peer.name,"<init>",constructor.desc,false));
            for(int i=at+1;i<code.size();i++)replacement.add(code.get(i).clone(new HashMap<>()));
            constructor.instructions=replacement;constructor.localVariables=null;constructor.maxLocals=slot;constructor.maxStack=slot;
        }
        return child;
    }
    private static int constructorSuper(MethodNode method,String owner){
        List<AbstractInsnNode> code=ContractGraph.code(method);int found=-1;
        for(int i=0;i<code.size();i++)if(code.get(i) instanceof MethodInsnNode call&&call.getOpcode()==Opcodes.INVOKESPECIAL&&call.owner.equals(owner)&&call.name.equals("<init>")){if(found>=0)return-1;found=i;}
        if(found<0)return-1;
        for(int i=0;i<=found;i++)if(code.get(i) instanceof JumpInsnNode||code.get(i) instanceof InvokeDynamicInsnNode||code.get(i) instanceof FieldInsnNode)return-1;
        return found;
    }
    private static boolean assignmentSuffix(MethodNode constructor,ClassNode owner){
        List<AbstractInsnNode> code=ContractGraph.code(constructor);int i=constructorSuper(constructor,owner.superName)+1;Set<String> assigned=new HashSet<>();
        Set<Integer> arguments=new HashSet<>();int slot=1;for(Type t:Type.getArgumentTypes(constructor.desc)){arguments.add(slot);slot+=t.getSize();}
        while(i<code.size()-1){
            if(i+2>=code.size()||!(code.get(i) instanceof VarInsnNode self)||self.getOpcode()!=Opcodes.ALOAD||self.var!=0
                    ||!(code.get(i+1) instanceof VarInsnNode value)||!arguments.contains(value.var)
                    ||!(code.get(i+2) instanceof FieldInsnNode field)||field.getOpcode()!=Opcodes.PUTFIELD||!field.owner.equals(owner.name)
                    ||!assigned.add(field.name+field.desc))return false;
            FieldNode declared=owner.fields.stream().filter(f->f.name.equals(field.name)&&f.desc.equals(field.desc)).findFirst().orElse(null);
            if(declared==null)return false;i+=3;
        }
        return i==code.size()-1&&code.get(i).getOpcode()==Opcodes.RETURN&&assigned.size()==owner.fields.size();
    }
    private static String fingerprint(ClassNode owner,MethodNode method){
        ClassNode wrapper=new ClassNode();wrapper.version=Opcodes.V17;wrapper.access=Opcodes.ACC_PUBLIC;wrapper.name=owner.name;wrapper.superName=owner.superName;
        MethodNode copy=new MethodNode(method.access,method.name,method.desc,null,null);method.accept(copy);
        copy.visibleAnnotations=null;copy.invisibleAnnotations=null;copy.localVariables=null;
        for(AbstractInsnNode i:copy.instructions.toArray())if(i.getOpcode()<0&&!(i instanceof LabelNode))copy.instructions.remove(i);
        wrapper.methods.add(copy);ClassWriter bytes=new ClassWriter(0);
        wrapper.accept(new ClassRemapper(bytes,new SimpleRemapper(Map.of(owner.name,"proof/Ancestor"))));
        return HexFormat.of().formatHex(bytes.toByteArray());
    }
    private static String signature(ClassNode original,String peer){
        if(original.signature==null)return null;String value=original.signature;int start=0;
        if(value.startsWith("<")){int depth=0;for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c=='<')depth++;if(c=='>')depth--;if(depth==0){start=i+1;break;}}}
        int end=start,depth=0;for(;end<value.length();end++){char c=value.charAt(end);if(c=='<')depth++;if(c=='>')depth--;if(c==';'&&depth==0){end++;break;}}
        List<String> variables=new ArrayList<>();new org.objectweb.asm.signature.SignatureReader(value).accept(new org.objectweb.asm.signature.SignatureVisitor(Opcodes.ASM9){@Override public void visitFormalTypeParameter(String name){variables.add(name);}});
        String arguments=variables.isEmpty()?"":"<"+String.join("",variables.stream().map(v->"T"+v+";").toList())+">";
        return value.substring(0,start)+"L"+peer+arguments+";"+value.substring(end);
    }
}
