/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.api;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Published hook contracts whose native counterpart can satisfy an event forward. Any protocol adapter may register
 * a contract; caller class names never authorize delivery. A proved caller scopes one source invocation, and the
 * source's actual constructed event identity is captured before dispatch, so nested posts remain independent. */
public final class NativeEventDelivery {
    public record Hook(String owner,String name,String descriptor) {
        public Hook { Objects.requireNonNull(owner);Objects.requireNonNull(name);Objects.requireNonNull(descriptor); }
    }
    public record Contract(String id,Hook source,Hook counterpart,String event,Hook dispatch) {
        public Contract {
            Objects.requireNonNull(id);Objects.requireNonNull(source);Objects.requireNonNull(counterpart);Objects.requireNonNull(event);Objects.requireNonNull(dispatch);
            if(id.isBlank()||!source.descriptor().equals(counterpart.descriptor())||org.objectweb.asm.Type.getReturnType(source.descriptor()).getSort()!=org.objectweb.asm.Type.VOID)throw new IllegalArgumentException("Native observer hooks need the same void argument contract");
        }
    }
    private static final Map<String,Contract> CONTRACTS=new ConcurrentHashMap<>();
    /** The sorted, immutable view {@link #contracts()} hands out, rebuilt only when a new contract arrives: the
     * transform chain asks for it once per loaded class. A new identity means the contract set changed. */
    private static volatile List<Contract> snapshot=List.of();
    private static final ThreadLocal<ArrayDeque<Scope>> SCOPES=new ThreadLocal<>();
    private NativeEventDelivery() { }
    public static void register(Contract contract) {
        Contract old=CONTRACTS.putIfAbsent(contract.id(),contract);
        if(old!=null&&!old.equals(contract))throw new IllegalArgumentException("Conflicting native delivery contract: "+contract.id());
        if(old==null)refresh();
    }
    private static synchronized void refresh(){snapshot=CONTRACTS.values().stream().sorted(Comparator.comparing(Contract::id)).toList();}
    public static List<Contract> contracts(){return snapshot;}
    /** Called only from a wrapper whose counterpart's completed invocation was proved to dominate the source call. */
    public static Scope begin(String id){
        if(!CONTRACTS.containsKey(id))throw new IllegalStateException("Unregistered native event contract: "+id);
        ArrayDeque<Scope> scopes=SCOPES.get();if(scopes==null)SCOPES.set(scopes=new ArrayDeque<>());
        Scope scope=new Scope(id,Thread.currentThread());scopes.addLast(scope);return scope;
    }
    /** Inserted at the contract's unique event construction, before any listener may post a nested event. */
    public static void capture(String id,Object event){
        ArrayDeque<Scope> scopes=SCOPES.get();if(scopes==null)return;Scope scope=scopes.peekLast();
        if(scope.id.equals(id)&&scope.event==null)scope.event=Objects.requireNonNull(event);
    }
    /** Whether this exact event already has the contract's completed native counterpart delivery. */
    public static boolean covered(String id,Object event){
        if(event==null)return false;ArrayDeque<Scope> scopes=SCOPES.get();if(scopes==null)return false;
        for(Scope scope:scopes)if(scope.id.equals(id)&&scope.event==event)return true;return false;
    }
    public static final class Scope implements AutoCloseable {
        private final String id;private final Thread thread;private Object event;private boolean closed;
        private Scope(String id,Thread thread){this.id=id;this.thread=thread;}
        @Override public void close(){
            if(closed)return;ArrayDeque<Scope> scopes=SCOPES.get();
            if(Thread.currentThread()!=thread||scopes==null||scopes.peekLast()!=this)throw new IllegalStateException("Native delivery scopes must close in invocation order");
            scopes.removeLast();closed=true;if(scopes.isEmpty())SCOPES.remove();
        }
    }
}
