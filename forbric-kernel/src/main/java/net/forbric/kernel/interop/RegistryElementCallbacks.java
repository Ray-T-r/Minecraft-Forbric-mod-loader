/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;
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
            // Any result the callback returns was discarded by the walk, and is discarded here too.
            if(!key.contract.isInterface()||!Modifier.isPublic(key.contract.getModifiers())||!Modifier.isPublic(method.getModifiers())
                    ||!Modifier.isPublic(method.getDeclaringClass().getModifiers())||Modifier.isStatic(method.getModifiers())||method.getParameterCount()!=0)
                throw new IllegalArgumentException("Inaccessible registry callback contract");
        }
    }
    /** Held only by a local in the transformed root; exceptions discard it without publishing or thread-local state. */
    private static final class Batch {
        final Class<?> root;final String member;
        final Map<Object,Map<Key,Callback>> registries=new IdentityHashMap<>();
        final List<Object> order=new ArrayList<>();boolean committed;
        Batch(Class<?> root,String member){this.root=Objects.requireNonNull(root);this.member=Objects.requireNonNull(member);}
    }
    private static final Map<Object,Map<Key,Callback>> REGISTRIES=new IdentityHashMap<>();
    /** The registries in the order their first walk was published, so completion runs in a stable order. */
    private static final List<Object> ORDER=new ArrayList<>();
    private RegistryElementCallbacks(){}
    private static Set<Object> identitySet(){return Collections.newSetFromMap(new IdentityHashMap<>());}
    public static Object begin(Class<?> root,String member){return new Batch(root,member);}
    /** Reached where the walk starts (after its interface guard, if any), including when the registry is empty. */
    public static void declare(Object token,Class<?> contract,String member,Object registry){row(batch(token),contract,member,registry);}
    /** Records a successful callback in the local batch, never in the published registry yet. */
    public static void completed(Object element,Object token,Class<?> contract,String member,Object registry){
        if(!contract.isInstance(element))throw new IllegalArgumentException("registry element callback contract");
        Batch batch=batch(token);
        synchronized(batch){row(batch,contract,member,registry).completed.add(element);}
    }
    /**
     * Declares a walk whose per-element body is a consumer, and returns that consumer recording each element it returned
     * from normally. The walk proved the consumer gives each element the callback exactly once on every normal return.
     */
    @SuppressWarnings({"unchecked","rawtypes"})
    public static Consumer recording(Consumer action,Object token,Class<?> contract,String member,Object registry){
        Objects.requireNonNull(action);
        declare(token,contract,member,registry);
        return element->{action.accept(element);completed(element,token,contract,member,registry);};
    }
    private static Batch batch(Object token){
        if(!(token instanceof Batch batch)||batch.committed)throw new IllegalArgumentException("inactive registry callback batch");return batch;
    }
    private static Callback row(Batch batch,Class<?> contract,String member,Object registry){
        if(!(registry instanceof Iterable<?>))throw new IllegalArgumentException("registry element callback contract");
        Key key=new Key(batch.root,batch.member,contract,member);
        synchronized(batch){
            Map<Key,Callback> callbacks=batch.registries.get(registry);
            if(callbacks==null){callbacks=new LinkedHashMap<>();batch.registries.put(registry,callbacks);batch.order.add(registry);}
            return callbacks.computeIfAbsent(key,Callback::new);
        }
    }
    /** Called only at the proved root's normal return; every walk it declared must have completed first. */
    public static synchronized void commit(Object token){
        Batch batch=batch(token);
        synchronized(batch){
            for(Object registry:batch.order){
                Map<Key,Callback> callbacks=REGISTRIES.get(registry);
                if(callbacks==null){callbacks=new LinkedHashMap<>();REGISTRIES.put(registry,callbacks);ORDER.add(registry);}
                for(var pending:batch.registries.get(registry).entrySet()){
                    Callback existing=callbacks.computeIfAbsent(pending.getKey(),ignored->pending.getValue());
                    existing.completed.addAll(pending.getValue().completed);
                }
            }
            batch.committed=true;batch.registries.clear();batch.order.clear();
        }
    }
    /**
     * Completes every registry a proved walk has covered, saying how many late registrations it completed when that is
     * any; the kernel's lifecycle calls this each time it closes a registration window. One registry's failing callback
     * does not stop the others: the first failure is rethrown after all of them ran.
     */
    public static int completeLateRegistrations(){
        List<Object> registries;
        synchronized(RegistryElementCallbacks.class){registries=new ArrayList<>(ORDER);}
        int count=0;RuntimeException runtime=null;Error error=null;
        for(Object registry:registries){
            try{count+=complete(registry);}
            catch(RuntimeException failure){if(runtime==null&&error==null)runtime=failure;}
            catch(Error failure){if(runtime==null&&error==null)error=failure;}
        }
        report(count);
        if(error!=null)throw error;
        if(runtime!=null)throw runtime;
        return count;
    }
    /** {@link #complete}, saying how many late registrations it completed when that is any. */
    public static int completeLateRegistrations(Object registry){
        int count=complete(registry);
        report(count);
        return count;
    }
    private static void report(int count){
        if(count>0)ForbricLog.info("[Forbric/Lifecycle] completed %d registry element callback(s) for late registrations",count);
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
