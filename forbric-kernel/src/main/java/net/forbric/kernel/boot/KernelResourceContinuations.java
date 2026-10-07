/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;
import java.lang.reflect.*;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;import java.util.*;import java.util.concurrent.ConcurrentHashMap;
import org.objectweb.asm.Type;import net.forbric.kernel.boot.DefinedMethodContracts.MethodContract;import net.forbric.kernel.util.ForbricLog;

/** Guards a missing factory wrapper lifted to the final, unchanged resource continuation. */
public final class KernelResourceContinuations {
    public record FieldContract(String owner,String name,String descriptor) { }
    public record Plan(FieldContract sourceField,MethodContract nameGetter,MethodContract namespaceGetter,MethodContract pathGetter,
                       List<MethodContract> bodies,String prefix,String suffix,String defaultNamespace,char separator) {
        public Plan {bodies=List.copyOf(bodies);}
    }
    private static final Map<String,Plan> PLANS=new ConcurrentHashMap<>();
    private static final ClassValue<Set<String>> REPORTED=new ClassValue<>(){@Override protected Set<String> computeValue(Class<?> type){return ConcurrentHashMap.newKeySet();}};
    private KernelResourceContinuations() { }
    public static String register(Plan plan){try{String key=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(plan.toString().getBytes(StandardCharsets.UTF_8)));PLANS.putIfAbsent(key,plan);return key;}catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}}
    /** Null means retain the original operation; a one-element array can hold a null source name, preserving its NPE. */
    public static String[] sourceName(Object caller,Object partial,String actualPrefix,String key) {
        Plan plan=PLANS.get(key);boolean proved=caller!=null&&partial!=null&&plan!=null&&plan.prefix().equals(actualPrefix);
        if(proved)try {
            ClassLoader loader=caller.getClass().getClassLoader();
            for(MethodContract contract:plan.bodies()) {
                Class<?> owner=Class.forName(contract.owner(),false,loader);
                if(!DefinedMethodContracts.observed(owner.getClassLoader(),contract)){proved=false;break;}
                for(Method method:owner.getDeclaredMethods())if(method.getName().equals(contract.name())&&Type.getMethodDescriptor(method).equals(contract.descriptor())&&Modifier.isSynchronized(method.getModifiers())){proved=false;break;}
            }
            if(proved) {
                FieldContract contract=plan.sourceField();Class<?> owner=Class.forName(contract.owner().replace('/','.'),false,loader);Field field=owner.getDeclaredField(contract.name());
                if(!Modifier.isFinal(field.getModifiers())||Modifier.isStatic(field.getModifiers())||!Type.getDescriptor(field.getType()).equals(contract.descriptor()))proved=false;
                else {
                    field.setAccessible(true);Object source=field.get(caller);
                    if(!DefinedMethodContracts.validates(source,plan.nameGetter())||!DefinedMethodContracts.validates(partial,plan.namespaceGetter())||!DefinedMethodContracts.validates(partial,plan.pathGetter()))proved=false;
                    else {
                        String name=(String)method(source,plan.nameGetter()).invoke(source);String input=String.valueOf(name)+plan.suffix();int split=input.indexOf(plan.separator());
                        String namespace=split>0?input.substring(0,split):plan.defaultNamespace();String path=split>=0?input.substring(split+1):input;
                        if(namespace.equals(method(partial,plan.namespaceGetter()).invoke(partial))&&path.equals(method(partial,plan.pathGetter()).invoke(partial)))return new String[]{name};
                        proved=false;
                    }
                }
            }
        }catch(ReflectiveOperationException|LinkageError unavailable){proved=false;}
        if(!proved&&caller!=null&&REPORTED.get(caller.getClass()).add(key))ForbricLog.warn("[Forbric/ResourceContinuation] retaining the native resource continuation: its final bodies or actual source/prefix operands differ; the guest factory wrapper is not called (%s)",key);
        return null;
    }
    private static Method method(Object receiver,MethodContract contract)throws NoSuchMethodException{for(Method method:receiver.getClass().getMethods())if(method.getName().equals(contract.name())&&Type.getMethodDescriptor(method).equals(contract.descriptor()))return method;throw new NoSuchMethodException(contract.name());}
    public static void resetForTests(){PLANS.clear();}
}
