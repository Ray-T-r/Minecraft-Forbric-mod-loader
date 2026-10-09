/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop;

import java.lang.reflect.*;
import java.util.*;
import net.forbric.kernel.util.ForbricLog;

/** Completes the original per-element callback for registrations made after a successful closed registry walk. */
public final class RegistryElementCallbacks {
    private record Key(Class<?> root,String rootMember,Class<?> contract,String member){}
    private static final class Callback {
        final Key key;final Method method;
        final Set<Object> completed=identitySet(),inFlight=identitySet();
        Callback(Key key){
            this.key=key;
            try{method=key.contract.getMethod(key.member);}
            catch(NoSuchMethodException failure){throw new IllegalStateException("Registry callback contract changed",failure);}
            if(!key.contract.isInterface()||!Modifier.isPublic(key.contract.getModifiers())||!Modifier.isPublic(method.getModifiers())
                    ||!Modifier.isPublic(method.getDeclaringClass().getModifiers())||Modifier.isStatic(method.getModifiers())||method.getReturnType()!=void.class||method.getParameterCount()!=0)
                throw new IllegalArgumentException("Inaccessible registry callback contract");
        }
    }
    /** Held only by a local in the transformed root; exceptions discard it without publishing or thread-local state. */
    private static final class Batch {
        final Class<?> root;final String member;
        final Map<Object,Map<Key,Callback>> registries=new IdentityHashMap<>();boolean committed;
        Batch(Class<?> root,String member){this.root=Objects.requireNonNull(root);this.member=Objects.requireNonNull(member);}
    }
    private static final Map<Object,Map<Key,Callback>> REGISTRIES=new IdentityHashMap<>();
    private RegistryElementCallbacks(){}
    private static Set<Object> identitySet(){return Collections.newSetFromMap(new IdentityHashMap<>());}
    public static Object begin(Class<?> root,String member){return new Batch(root,member);}
    /** Reached inside the loop's optional class guard, including when the original registry is empty. */
    public static void declare(Object token,Class<?> contract,String member,Object registry){row(batch(token),contract,member,registry);}
    /** Records a successful callback in the local batch, never in the published registry yet. */
    public static void completed(Object element,Object token,Class<?> contract,String member,Object registry){
        if(!contract.isInstance(element))throw new IllegalArgumentException("registry element callback contract");
        row(batch(token),contract,member,registry).completed.add(element);
    }
    private static Batch batch(Object token){
        if(!(token instanceof Batch batch)||batch.committed)throw new IllegalArgumentException("inactive registry callback batch");return batch;
    }
    private static Callback row(Batch batch,Class<?> contract,String member,Object registry){
        if(!(registry instanceof Iterable<?>))throw new IllegalArgumentException("registry element callback contract");
        Key key=new Key(batch.root,batch.member,contract,member);
        return batch.registries.computeIfAbsent(registry,ignored->new LinkedHashMap<>()).computeIfAbsent(key,Callback::new);
    }
    /** Called only at the proved root's normal return; every guarded walk must have completed first. */
    public static synchronized void commit(Object token){
        Batch batch=batch(token);
        for(var registry:batch.registries.entrySet()){
            Map<Key,Callback> callbacks=REGISTRIES.computeIfAbsent(registry.getKey(),ignored->new LinkedHashMap<>());
            for(var pending:registry.getValue().entrySet()){
                Callback existing=callbacks.computeIfAbsent(pending.getKey(),ignored->pending.getValue());
                existing.completed.addAll(pending.getValue().completed);
            }
        }
        batch.committed=true;batch.registries.clear();
    }
    /** {@link #complete}, saying how many late registrations it completed when that is any; the kernel's lifecycle calls this. */
    public static int completeLateRegistrations(Object registry){
        int count=complete(registry);
        if(count>0)ForbricLog.info("[Forbric/Lifecycle] completed %d registry element callback(s) for late registrations",count);
        return count;
    }
    /** Each original root initializes new identities once; snapshots and in-flight identities make reentry harmless. */
    public static int complete(Object registry){
        List<Callback> callbacks;
        synchronized(RegistryElementCallbacks.class){
            Map<Key,Callback> registered=REGISTRIES.get(registry);
            if(registered==null||!(registry instanceof Iterable<?>))return 0;
            callbacks=new ArrayList<>(registered.values());
        }
        List<Object> elements=new ArrayList<>();for(Object element:(Iterable<?>)registry)elements.add(element);
        int count=0;
        for(Callback callback:callbacks)for(Object element:elements){
            synchronized(RegistryElementCallbacks.class){
                if(!callback.key.contract.isInstance(element)||callback.completed.contains(element)||!callback.inFlight.add(element))continue;
            }
            // Guest callbacks run without the bookkeeping lock: a callback may join a worker that checks completion.
            try{
                callback.method.invoke(element);
                synchronized(RegistryElementCallbacks.class){callback.completed.add(element);}count++;
            }catch(IllegalAccessException failure){throw new IllegalStateException("Cannot invoke registry element callback",failure);}
            catch(InvocationTargetException failure){
                Throwable cause=failure.getCause();if(cause instanceof RuntimeException runtime)throw runtime;if(cause instanceof Error error)throw error;
                throw new IllegalStateException("Registry element callback failed",cause);
            }finally{synchronized(RegistryElementCallbacks.class){callback.inFlight.remove(element);}}
        }
        return count;
    }
}
