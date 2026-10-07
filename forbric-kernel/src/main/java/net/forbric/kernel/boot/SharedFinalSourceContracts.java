/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.lang.reflect.Method;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.ClassNode;
import net.forbric.kernel.mixin.SharedFinalSourceCertifier;
import net.forbric.kernel.mixin.SharedFinalSourceCertifier.Witness;

/** Source continuation tokens are scoped to the actual successfully defined class, never a binary-name table. */
public final class SharedFinalSourceContracts {
    private static final ClassValue<ConcurrentHashMap<String,Witness>> WITNESSES=new ClassValue<>(){
        @Override protected ConcurrentHashMap<String,Witness> computeValue(Class<?> type){return new ConcurrentHashMap<>();}
    };
    private static final int FLAGS=Opcodes.ACC_PUBLIC|Opcodes.ACC_PRIVATE|Opcodes.ACC_PROTECTED|Opcodes.ACC_STATIC
            |Opcodes.ACC_FINAL|Opcodes.ACC_SYNCHRONIZED|Opcodes.ACC_BRIDGE|Opcodes.ACC_NATIVE|Opcodes.ACC_ABSTRACT;
    private SharedFinalSourceContracts(){ }
    /** Only after successful defineClass. Failure to close a proof must never fail class definition. */
    public static void observeDefinition(ClassLoader loader,String binary,byte[] bytes){
        String owner=binary.replace('.','/');if(!SharedFinalSourceCertifier.interested(owner))return;
        try{
            ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);if(!node.name.equals(owner))return;
            Class<?> actual=Class.forName(binary.replace('/','.'),false,loader);if(actual.getClassLoader()!=loader)return;
            var ledger=WITNESSES.get(actual);ledger.clear();ledger.putAll(SharedFinalSourceCertifier.certify(node));
        }catch(RuntimeException|ReflectiveOperationException|LinkageError declined){/* preserve the carrier path */}
    }
    public static boolean permits(Object receiver,String key){
        if(receiver==null||key==null)return false;
        for(Class<?> owner=receiver.getClass();owner!=null;owner=owner.getSuperclass()){
            Witness witness=WITNESSES.get(owner).get(key);if(witness==null)continue;
            try{
                for(var contract:witness.methods()){
                    if(!contract.owner().equals(owner.getName())||!DefinedMethodContracts.observed(owner.getClassLoader(),contract))return false;
                    Method found=null;for(Method method:owner.getDeclaredMethods())if(method.getName().equals(contract.name())&&Type.getMethodDescriptor(method).equals(contract.descriptor())){if(found!=null)return false;found=method;}
                    if(found==null||(found.getModifiers()&FLAGS)!=witness.flags().get(contract))return false;
                }return true;
            }catch(RuntimeException|LinkageError unknown){return false;}
        }return false;
    }
}
