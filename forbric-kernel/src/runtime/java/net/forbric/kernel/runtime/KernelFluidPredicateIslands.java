/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.util.function.IntSupplier;
import net.forbric.kernel.boot.DefinedMethodContracts;
import net.forbric.kernel.boot.DefinedMethodContracts.MethodContract;
import net.forbric.kernel.boot.KernelCrossHostPredicateContracts;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** A scoped original predicate runs inside its native void host, before event construction and exactly once. */
public final class KernelFluidPredicateIslands {
    public static final String PROPERTY="forbric.crossHostPredicateIslands";
    private static final ThreadLocal<Scope> ACTIVE=new ThreadLocal<>();
    private static final StackWalker CALLER=StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final class DefaultContracts{static final ClassValue<java.util.Map<String,MethodContract>> DEFAULTS=new ClassValue<>(){
        @Override protected java.util.Map<String,MethodContract>computeValue(Class<?>type){try(var input=type.getClassLoader()instanceof net.forbric.kernel.classloading.ForbricClassLoader loader?loader.getGameResourceAsStream(type.getName().replace('.','/')+".class"):type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")){if(input==null)return java.util.Map.of();ClassNode node=new ClassNode();new ClassReader(input.readAllBytes()).accept(node,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);java.util.Map<String,MethodContract>out=new java.util.HashMap<>();for(MethodNode m:node.methods)out.put(m.name+m.desc,new MethodContract(type.getName(),m.name,m.desc,DefinedMethodContracts.fingerprint(m)));return java.util.Map.copyOf(out);}catch(java.io.IOException|RuntimeException unknown){return java.util.Map.of();}}
    };}
    public static final class Scope implements AutoCloseable{final Scope previous;final Object actor,world;final String key;final IntSupplier source;boolean used;Scope(Object actor,Object world,String key,IntSupplier source){this.previous=ACTIVE.get();this.actor=actor;this.world=world;this.key=key;this.source=source;}@Override public void close(){if(previous==null)ACTIVE.remove();else ACTIVE.set(previous);}}
    private KernelFluidPredicateIslands(){}
    public static Scope enter(Object actor,Object world,String key,IntSupplier source){Scope scope=new Scope(actor,world,key,source);ACTIVE.set(scope);return scope;}
    public static int current(Object actor,Object world,Object interaction,String key){Scope scope=ACTIVE.get();if(scope==null||scope.used||scope.actor!=actor||scope.world!=world||!scope.key.equals(key)||"off".equalsIgnoreCase(System.getProperty(PROPERTY,"on"))||!KernelFabricFluidBehaviors.enabled())return -1;
        Class<?> caller=CALLER.walk(frames->frames.skip(1).findFirst()).orElseThrow().getDeclaringClass();var schema=KernelCrossHostPredicateContracts.schema(key);
        if(schema==null||!KernelCrossHostPredicateContracts.validates(actor,world,interaction,key,caller,type->{
            if(!(type instanceof net.neoforged.neoforge.fluids.FluidType fluid)||!KernelFabricFluidBehaviors.ownsNeoType(fluid))return 0;
            MethodContract method=DefaultContracts.DEFAULTS.get(type.getClass()).get(schema.ownedQuestion()+schema.ownedDescriptor());if(method==null||!DefinedMethodContracts.validates(type,method))return -1;for(var actual:type.getClass().getMethods())if(actual.getName().equals(schema.ownedQuestion())&&Type.getMethodDescriptor(actual).equals(schema.ownedDescriptor())){int flags=actual.getModifiers();return (flags&(java.lang.reflect.Modifier.SYNCHRONIZED|java.lang.reflect.Modifier.NATIVE|java.lang.reflect.Modifier.ABSTRACT|java.lang.reflect.Modifier.STATIC))==0?1:-1;}return -1;
        }))return -1;
        scope.used=true;return scope.source.getAsInt();
    }
}
