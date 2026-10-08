/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.classloading;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import net.forbric.api.VirtualProperties;
/** Final definitions, including guest changes, are the only source of mutable getter-field proofs. */
final class DefinedGetterFields {
    static void observe(ClassLoader loader,String name,byte[] bytes){
        ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        Map<String,VirtualProperties.Field> getters=new HashMap<>();
        for(MethodNode method:node.methods){
            if((method.access&(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC))!=Opcodes.ACC_PUBLIC||Type.getArgumentTypes(method.desc).length!=0||!method.tryCatchBlocks.isEmpty())continue;
            List<AbstractInsnNode> code=Arrays.stream(method.instructions.toArray()).filter(i->i.getOpcode()>=0).toList();
            if(code.size()!=3||!(code.get(0)instanceof VarInsnNode v&&v.getOpcode()==Opcodes.ALOAD&&v.var==0)
                ||!(code.get(1)instanceof FieldInsnNode field&&field.getOpcode()==Opcodes.GETFIELD&&field.owner.equals(node.name))
                ||code.get(2).getOpcode()!=Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN)||!field.desc.equals(Type.getReturnType(method.desc).getDescriptor()))continue;
            List<FieldNode> storage=node.fields.stream().filter(f->f.name.equals(field.name)&&f.desc.equals(field.desc)&&(f.access&(Opcodes.ACC_STATIC|Opcodes.ACC_FINAL))==0).toList();
            if(storage.size()==1)getters.put(method.name+method.desc,new VirtualProperties.Field(field.owner,field.name,field.desc));
        }
        VirtualProperties.observe(loader,name,getters);
    }
}
