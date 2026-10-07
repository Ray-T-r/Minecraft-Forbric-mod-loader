/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;
import java.lang.reflect.*;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;import java.util.*;import java.util.concurrent.ConcurrentHashMap;
import org.objectweb.asm.Type;import net.forbric.kernel.boot.DefinedMethodContracts.MethodContract;import net.forbric.kernel.util.ForbricLog;
/** A retired argument observer is permitted only on the final-defined unchanged unused-argument virtual base. */
public final class KernelUnusedArgumentObservers {
    private record Plan(MethodContract oldConsumer,MethodContract mapping) { }
    private static final Map<String,Plan> PLANS=new ConcurrentHashMap<>();
    private static final ClassValue<Set<String>> REPORTED=new ClassValue<>(){@Override protected Set<String> computeValue(Class<?> type){return ConcurrentHashMap.newKeySet();}};
    private KernelUnusedArgumentObservers() { }
    public static String register(MethodContract consumer,MethodContract mapping){Plan plan=new Plan(consumer,mapping);try{String key=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(plan.toString().getBytes(StandardCharsets.UTF_8)));PLANS.putIfAbsent(key,plan);return key;}catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}}
    public static boolean permits(Object receiver,String key) {
        Plan plan=PLANS.get(key);boolean proved=receiver!=null&&plan!=null;
        if(proved)try {
            Method found=null;for(Class<?> type=receiver.getClass();type!=null&&found==null;type=type.getSuperclass())for(Method method:type.getDeclaredMethods())
                if(method.getName().equals(plan.oldConsumer().name())&&Type.getMethodDescriptor(method).equals(plan.oldConsumer().descriptor())&&!Modifier.isStatic(method.getModifiers())){found=method;break;}
            proved=found!=null&&!Modifier.isAbstract(found.getModifiers())&&!Modifier.isSynchronized(found.getModifiers())&&found.getDeclaringClass().getName().equals(plan.oldConsumer().owner())&&DefinedMethodContracts.observed(found.getDeclaringClass().getClassLoader(),plan.oldConsumer());
            if(proved){Class<?> mapping=Class.forName(plan.mapping().owner(),false,receiver.getClass().getClassLoader());proved=DefinedMethodContracts.observed(mapping.getClassLoader(),plan.mapping());}
        }catch(ClassNotFoundException|RuntimeException|LinkageError unavailable){proved=false;}
        if(!proved&&receiver!=null&&REPORTED.get(receiver.getClass()).add(key))ForbricLog.warn("[Forbric/UnusedArgumentObserver] retaining native typed dispatch: the original unused-argument base or constant mapping is overridden/changed; the guest share observer is not called (%s)",key);
        return proved;
    }
    public static void resetForTests(){PLANS.clear();}
}
