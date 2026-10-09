/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.api;

import java.lang.ref.*;
import java.util.*;
import java.util.function.Predicate;
import net.forbric.kernel.util.ForbricLog;

/**
 * Scoped source operations at a proved call site inside a carrier gateway. Native gates retain their order.
 *
 * <p>Fail-open: an invocation whose proof does not hold -- another transformer changed the gateway or helper's
 * final body -- is declined, never failed. The carrier's gateway then runs exactly as written and the source operation
 * does not run for that invocation. A site reports its first decline once, with what broke it.
 */
public final class OperationSeams {
    @FunctionalInterface public interface NativeOperation { Object invoke(Object[] arguments) throws Throwable; }
    @FunctionalInterface public interface Invocation { Object invoke(Object operation,Object[] arguments) throws Throwable; }
    /** The one report a site makes, the first time one of its invocations is declined. */
    @FunctionalInterface public interface Declined { void report(ClassLoader loader,String key,String reason); }
    private record Proof(String host,String helper,String graph,Predicate<ClassLoader> witness,int[] gatewayInputs,int[] helperInputs,Declined declined) { }
    private static final ReferenceQueue<ClassLoader> QUEUE=new ReferenceQueue<>();
    private static final class LoaderKey extends WeakReference<ClassLoader> {
        final int hash;LoaderKey(ClassLoader loader){super(loader,QUEUE);hash=System.identityHashCode(loader);}
        @Override public int hashCode(){return hash;}
        @Override public boolean equals(Object other){return this==other||other instanceof LoaderKey key&&get()!=null&&get()==key.get();}
    }
    private static final Map<LoaderKey,Map<String,Proof>> PROOFS=new HashMap<>();
    /** Sites, per host loader, that already reported their decline. Guarded by the class lock. */
    private static final Map<LoaderKey,Set<String>> DECLINED=new HashMap<>();
    private static final Set<String> BOOTSTRAP_DECLINED=new HashSet<>();
    private static final ThreadLocal<State> ACTIVE=new ThreadLocal<>();
    private static final class State { Frame frame;Marker marker; }
    private static final class Frame {
        final Frame previous;final ClassLoader loader;final Proof proof;final Invocation invocation;final Object[] arguments;boolean reserved;
        Frame(Frame previous,ClassLoader loader,Proof proof,Invocation invocation,Object[] arguments){this.previous=previous;this.loader=loader;this.proof=proof;this.invocation=invocation;this.arguments=arguments.clone();}
    }
    private static final class Marker {
        final Marker previous;final Class<?> helper;final String graph;final List<Frame> frames;boolean fired;
        Marker(Marker previous,Class<?> helper,String graph,List<Frame> frames){this.previous=previous;this.helper=helper;this.graph=graph;this.frames=frames;}
    }
    private OperationSeams() { }
    private static void expunge(){for(Reference<? extends ClassLoader> key;(key=QUEUE.poll())!=null;){PROOFS.remove(key);DECLINED.remove(key);}}
    public static synchronized void register(ClassLoader loader,String key,String host,String helper,String graph,Predicate<ClassLoader> witness) {
        register(loader,key,host,helper,graph,witness,new int[0],new int[0]);
    }
    public static synchronized void register(ClassLoader loader,String key,String host,String helper,String graph,Predicate<ClassLoader> witness,int[] gatewayInputs,int[] helperInputs) {
        register(loader,key,host,helper,graph,witness,gatewayInputs,helperInputs,null);
    }
    /** As above; {@code declined} names what broke the proof, once per site (null logs a generic warning instead). */
    public static synchronized void register(ClassLoader loader,String key,String host,String helper,String graph,Predicate<ClassLoader> witness,int[] gatewayInputs,int[] helperInputs,Declined declined) {
        if(gatewayInputs.length!=helperInputs.length)throw new IllegalArgumentException("Operation identity projection arity");
        expunge();Objects.requireNonNull(loader);Proof proof=new Proof(host.replace('/','.'),helper.replace('/','.'),Objects.requireNonNull(graph),Objects.requireNonNull(witness),gatewayInputs.clone(),helperInputs.clone(),declined);
        Proof before=PROOFS.computeIfAbsent(new LoaderKey(loader),ignored->new HashMap<>()).putIfAbsent(key,proof);
        if(before!=null&&(!before.host.equals(proof.host)||!before.helper.equals(proof.helper)||!before.graph.equals(graph)))throw new IllegalStateException("Conflicting operation source: "+key);
    }
    public static synchronized void release(ClassLoader loader){expunge();LoaderKey key=new LoaderKey(loader);PROOFS.remove(key);DECLINED.remove(key);}
    private static synchronized Proof proof(ClassLoader loader,String key){expunge();Map<String,Proof> registry=PROOFS.get(new LoaderKey(loader));return registry==null?null:registry.get(key);}
    /** Runs the source operation at its proved site while the proof holds; otherwise the native gateway alone. */
    public static Object scoped(Class<?> host,Class<?> helper,String key,Invocation source,NativeOperation gateway,Object[] arguments)throws Throwable {
        Proof proof=proof(host.getClassLoader(),key);
        if(proof==null||!proof.host.equals(host.getName())||!proof.helper.equals(helper.getName())||host.getClassLoader()!=helper.getClassLoader()
                ||!proof.witness.test(host.getClassLoader()))return declined(host,helper,key,proof,gateway,arguments);
        State state=ACTIVE.get();if(state==null){state=new State();ACTIVE.set(state);}Frame frame=new Frame(state.frame,host.getClassLoader(),proof,Objects.requireNonNull(source),arguments);state.frame=frame;
        try{return gateway.invoke(arguments);}finally{if(state.frame!=frame)throw new IllegalStateException("Operation scopes closed out of order");state.frame=frame.previous;if(state.frame==null&&state.marker==null)ACTIVE.remove();}
    }
    /** No frame is pushed, so neither the selection nor the operation inside the gateway can find this source. */
    private static Object declined(Class<?> host,Class<?> helper,String key,Proof proof,NativeOperation gateway,Object[] arguments)throws Throwable {
        String reason=proof==null?"no operation proof is registered for this site in its host's loader"
            :!proof.host.equals(host.getName())?"invoked from "+host.getName()+", proved for "+proof.host
            :!proof.helper.equals(helper.getName())?"invoked with helper "+helper.getName()+", proved for "+proof.helper
            :host.getClassLoader()!=helper.getClassLoader()?"helper "+helper.getName()+" is defined by another loader than its host"
            :"the final body witness does not hold";
        boolean first;ClassLoader loader=host.getClassLoader();
        synchronized(OperationSeams.class){expunge();first=(loader==null?BOOTSTRAP_DECLINED:DECLINED.computeIfAbsent(new LoaderKey(loader),ignored->new HashSet<>())).add(key);}
        if(first)report(loader,key,proof,reason);
        return gateway.invoke(arguments);
    }
    /** A report that itself fails is only logged: the gateway must still run. */
    private static void report(ClassLoader loader,String key,Proof proof,String reason){
        if(proof!=null&&proof.declined!=null){
            try{proof.declined.report(loader,key,reason);return;}
            catch(RuntimeException|LinkageError failed){reason=reason+"; its report failed: "+failed;}
        }
        ForbricLog.warn("[Forbric/Mixin] source operation skipped at its proved site, the carrier gateway runs as written: %s (source=%s)",reason,key);
    }
    /** The proved top invocation is selected separately from iterations and other callers of the same helper. */
    public static Object selected(Class<?> helper,String graph,NativeOperation forward,Object[] arguments)throws Throwable {
        State state=ACTIVE.get();if(state==null)return forward.invoke(arguments);List<Frame> frames=new ArrayList<>();
        for(Frame frame=state.frame;frame!=null;frame=frame.previous)if(!frame.reserved&&frame.loader==helper.getClassLoader()&&frame.proof.helper.equals(helper.getName())&&frame.proof.graph.equals(graph)&&identitiesMatch(frame,arguments)){frame.reserved=true;frames.add(frame);}
        if(frames.isEmpty())return forward.invoke(arguments);Marker marker=new Marker(state.marker,helper,graph,List.copyOf(frames));state.marker=marker;
        try{return forward.invoke(arguments);}finally{if(state.marker!=marker)throw new IllegalStateException("Operation selections closed out of order");state.marker=marker.previous;frames.forEach(frame->frame.reserved=false);}
    }
    private static boolean identitiesMatch(Frame frame,Object[] arguments){for(int i=0;i<frame.proof.gatewayInputs.length;i++){int from=frame.proof.gatewayInputs[i],to=frame.proof.helperInputs[i];if(from<0||from>=frame.arguments.length||to<0||to>=arguments.length||frame.arguments[from]!=arguments[to])return false;}return true;}
    /** The original receiver and all original arguments remain mutable through the guest's own Operation. */
    public static Object apply(Class<?> helper,String graph,NativeOperation original,Object[] arguments)throws Throwable {
        State state=ACTIVE.get();Marker marker=state==null?null:state.marker;
        if(marker==null||marker.helper!=helper||!marker.graph.equals(graph)||marker.fired)return original.invoke(arguments);
        marker.fired=true;NativeOperation call=original;
        for(Frame frame:marker.frames){NativeOperation next=call;call=values->frame.invocation.invoke(next,values);}
        return call.invoke(arguments);
    }
}
