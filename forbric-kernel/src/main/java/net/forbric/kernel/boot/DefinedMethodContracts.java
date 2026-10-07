/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.mixin.MixinInstructionFingerprint;

/**
 * Final-defined method contracts, scoped to the actual defining class loader. Raw jar bytes are not a runtime
 * witness: a guest can change a default before it is defined, and a concrete receiver can override that default.
 * This ledger stores only code fingerprints and symbols, never class ASTs. Unknown definitions fail closed.
 */
public final class DefinedMethodContracts {
    /** A body contract derived from bytecode, rather than a table of known owners or methods. */
    public record MethodContract(String owner, String name, String descriptor, String fingerprint) {
        public MethodContract {
            owner = Objects.requireNonNull(owner).replace('/', '.');
            Objects.requireNonNull(name);
            Objects.requireNonNull(descriptor);
            Objects.requireNonNull(fingerprint);
        }
    }

    private static final ReferenceQueue<ClassLoader> COLLECTED = new ReferenceQueue<>();
    private record TransparentShape(int kind,String owner,String name,String descriptor) { }
    private record Observation(Set<MethodContract> methods,Map<MethodContract,TransparentShape> transparent) { }
    private static final ConcurrentHashMap<LoaderIdentity, ConcurrentHashMap<String, Observation>> LOADERS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Observation> BOOTSTRAP = new ConcurrentHashMap<>();
    private static final ClassValue<ConcurrentHashMap<MethodContract, Optional<Class<?>>>> RESOLUTIONS = new ClassValue<>() {
        @Override protected ConcurrentHashMap<MethodContract, Optional<Class<?>>> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    private DefinedMethodContracts() { }

    /** The same instruction/control-flow hash used by the final mixin application evidence. */
    public static String fingerprint(MethodNode method) { return MixinInstructionFingerprint.hash(method); }

    /** Call only after a successful definition, with its exact final bytes and actual defining loader. */
    public static void observe(ClassLoader definingLoader, String binary, byte[] bytes) {
        String owner = Objects.requireNonNull(binary).replace('/', '.');
        ConcurrentHashMap<String, Observation> ledger = ledger(definingLoader, true);
        try {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            if (!node.name.replace('/', '.').equals(owner)) { ledger.remove(owner); return; }
            Set<MethodContract> contracts = new HashSet<>();
            Map<MethodContract,TransparentShape> transparent=new HashMap<>();
            for (MethodNode method : node.methods) {
                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
                MethodContract contract=new MethodContract(owner, method.name, method.desc, fingerprint(method));
                contracts.add(contract);TransparentShape shape=transparent(method);if(shape!=null)transparent.put(contract,shape);
            }
            ledger.put(owner, new Observation(Set.copyOf(contracts),Map.copyOf(transparent)));
        } catch (RuntimeException malformed) {
            ledger.remove(owner);
        }
    }

    /** Checks a helper/projection body witness without making a claim about virtual dispatch. */
    public static boolean observed(ClassLoader loader, MethodContract contract) {
        if (contract == null) return false;
        ConcurrentHashMap<String, Observation> ledger = ledger(loader, false);
        if (ledger == null) return false;
        Observation observation = ledger.get(contract.owner());
        return observation != null && observation.methods().contains(contract);
    }

    /**
     * The receiver must dispatch this exact public instance signature to the contract's declaring owner, and that
     * owner's final-defined body must still match. A same-named subclass override or a transformed default fails.
     * Reflection resolution is cached per receiver class; the final body witness is checked fresh on every call.
     */
    public static boolean validates(Object receiver, MethodContract contract) {
        if (receiver == null || contract == null) return false;
        Optional<Class<?>> declaring = RESOLUTIONS.get(receiver.getClass())
                .computeIfAbsent(contract, expected -> resolve(receiver.getClass(), expected));
        return declaring.isPresent() && observed(declaring.get().getClassLoader(), contract);
    }

    /** Opt-in for a pure interface-default forwarder, or a return-this identity with covariant bridges.
     * Every actual dispatch body is checked in its defining loader. The ordinary contract remains owner-strict. */
    public static boolean validatesTransparentDispatch(Object receiver,MethodContract contract){
        if(receiver==null||contract==null)return false;
        try{
            Class<?> expected=Class.forName(contract.owner(),false,receiver.getClass().getClassLoader());
            if(!expected.isInstance(receiver)||!observed(expected.getClassLoader(),contract))return false;
            Method sourceMethod=declared(expected,contract.name(),contract.descriptor());if(!publicInstance(sourceMethod))return false;
            TransparentShape source=shape(expected.getClassLoader(),contract);if(source==null)return false;
            Method actual=dispatch(receiver.getClass(),contract.name(),contract.descriptor());if(actual==null)return false;
            if(source.kind()==1){
                MethodContract witness=new MethodContract(actual.getDeclaringClass().getName(),actual.getName(),Type.getMethodDescriptor(actual),contract.fingerprint());
                if(!source.equals(shape(actual.getDeclaringClass().getClassLoader(),witness))||!observed(actual.getDeclaringClass().getClassLoader(),witness))return false;
                // Identical symbolic owners can denote different interfaces in different defining loaders.
                // Resolve both constant-pool targets in their callers' loaders and witness the actual default.
                Class<?> before=Class.forName(source.owner().replace('/','.'),false,expected.getClassLoader()),after=Class.forName(source.owner().replace('/','.'),false,actual.getDeclaringClass().getClassLoader());
                if(before!=after||!before.isInterface()||!before.isAssignableFrom(expected)||!after.isAssignableFrom(actual.getDeclaringClass()))return false;
                Method target=symbolic(before,source.name(),source.descriptor());
                return publicInstance(target)&&target.isDefault()&&observedMethod(target);
            }
            return source.kind()==2&&identity(receiver,actual,new HashSet<>());
        }catch(RuntimeException|ReflectiveOperationException|LinkageError unknown){return false;}
    }

    private static boolean identity(Object receiver,Method method,Set<String> active)throws ReflectiveOperationException{
        String descriptor=Type.getMethodDescriptor(method),key=method.getDeclaringClass().getName()+"#"+method.getName()+descriptor;
        if(!active.add(key)||method.getParameterCount()!=0||!method.getReturnType().isInstance(receiver))return false;
        Observation observation=observation(method.getDeclaringClass().getClassLoader(),method.getDeclaringClass().getName());if(observation==null)return false;
        for(var entry:observation.transparent().entrySet()){
            MethodContract witness=entry.getKey();if(!witness.name().equals(method.getName())||!witness.descriptor().equals(descriptor)||!observed(method.getDeclaringClass().getClassLoader(),witness))continue;
            TransparentShape shape=entry.getValue();if(shape.kind()==2)return true;if(shape.kind()!=3)return false;
            Class<?> calleeOwner=Class.forName(shape.owner().replace('/','.'),false,method.getDeclaringClass().getClassLoader());
            if(!calleeOwner.isInstance(receiver))return false;
            // invokevirtual/interface resolves its symbolic member first. A private same-named member is
            // nonvirtual even when a subclass exposes an identical public signature.
            Method resolved=symbolic(calleeOwner,shape.name(),shape.descriptor());if(resolved==null||!Modifier.isPublic(resolved.getModifiers())||Modifier.isStatic(resolved.getModifiers()))return false;
            Method callee=dispatch(receiver.getClass(),shape.name(),shape.descriptor());
            return callee!=null&&resolved.getReturnType()==callee.getReturnType()&&java.util.Arrays.equals(resolved.getParameterTypes(),callee.getParameterTypes())&&method.getReturnType().isAssignableFrom(callee.getReturnType())&&method.getReturnType()!=callee.getReturnType()&&identity(receiver,callee,active);
        }return false;
    }

    private static Method dispatch(Class<?> receiver,String name,String descriptor){
        Method found=null;for(Method method:receiver.getMethods()){
            if(!method.getName().equals(name)||!Type.getMethodDescriptor(method).equals(descriptor)||Modifier.isStatic(method.getModifiers())||Modifier.isAbstract(method.getModifiers())||Modifier.isNative(method.getModifiers())||Modifier.isSynchronized(method.getModifiers()))continue;
            if(found!=null)return null;found=method;
        }return found;
    }
    private static Method declared(Class<?> owner,String name,String descriptor){Method found=null;for(Method method:owner.getDeclaredMethods())if(method.getName().equals(name)&&Type.getMethodDescriptor(method).equals(descriptor)){if(found!=null)return null;found=method;}return found;}
    private static boolean publicInstance(Method method){return method!=null&&Modifier.isPublic(method.getModifiers())&&!Modifier.isStatic(method.getModifiers())&&!Modifier.isAbstract(method.getModifiers())&&!Modifier.isNative(method.getModifiers())&&!Modifier.isSynchronized(method.getModifiers());}
    private static Method symbolic(Class<?> owner,String name,String descriptor){for(Class<?> type=owner;type!=null;type=type.getSuperclass()){Method found=declared(type,name,descriptor);if(found!=null)return found;}return dispatch(owner,name,descriptor);}
    private static boolean observedMethod(Method method){Observation observation=observation(method.getDeclaringClass().getClassLoader(),method.getDeclaringClass().getName());return observation!=null&&observation.methods().stream().anyMatch(c->c.name().equals(method.getName())&&c.descriptor().equals(Type.getMethodDescriptor(method)));}
    private static TransparentShape shape(ClassLoader loader,MethodContract contract){Observation observation=observation(loader,contract.owner());return observation==null?null:observation.transparent().get(contract);}
    private static Observation observation(ClassLoader loader,String owner){var ledger=ledger(loader,false);return ledger==null?null:ledger.get(owner);}
    private static TransparentShape transparent(MethodNode method){
        if((method.access&Opcodes.ACC_PUBLIC)==0||(method.access&(Opcodes.ACC_STATIC|Opcodes.ACC_SYNCHRONIZED|Opcodes.ACC_NATIVE|Opcodes.ACC_ABSTRACT))!=0||!method.tryCatchBlocks.isEmpty())return null;
        List<AbstractInsnNode> code=new java.util.ArrayList<>();for(var instruction:method.instructions)if(instruction.getOpcode()>=0)code.add(instruction);
        Type[] arguments=Type.getArgumentTypes(method.desc);Type result=Type.getReturnType(method.desc);
        if(code.isEmpty()||!(code.getFirst()instanceof VarInsnNode self)||self.getOpcode()!=Opcodes.ALOAD||self.var!=0)return null;
        if(arguments.length==0&&(result.getSort()==Type.OBJECT||result.getSort()==Type.ARRAY)){
            if(code.size()==2&&code.getLast().getOpcode()==Opcodes.ARETURN)return new TransparentShape(2,"","","");
            if(code.size()==3&&code.get(1)instanceof MethodInsnNode call&&(call.getOpcode()==Opcodes.INVOKEVIRTUAL||call.getOpcode()==Opcodes.INVOKEINTERFACE)&&call.name.equals(method.name)&&Type.getArgumentTypes(call.desc).length==0&&(Type.getReturnType(call.desc).getSort()==Type.OBJECT||Type.getReturnType(call.desc).getSort()==Type.ARRAY)&&code.getLast().getOpcode()==Opcodes.ARETURN)return new TransparentShape(3,call.owner,call.name,call.desc);
        }
        if(code.size()!=arguments.length+3||!(code.get(code.size()-2)instanceof MethodInsnNode call)||call.getOpcode()!=Opcodes.INVOKESPECIAL||!call.itf||!call.name.equals(method.name)||!call.desc.equals(method.desc)||code.getLast().getOpcode()!=result.getOpcode(Opcodes.IRETURN))return null;
        int slot=1;for(int i=0;i<arguments.length;i++){if(!(code.get(i+1)instanceof VarInsnNode load)||load.var!=slot||load.getOpcode()!=arguments[i].getOpcode(Opcodes.ILOAD))return null;slot+=arguments[i].getSize();}
        return new TransparentShape(1,call.owner,call.name,call.desc);
    }

    private static Optional<Class<?>> resolve(Class<?> receiver, MethodContract contract) {
        try {
            Method found = null;
            for (Method method : receiver.getMethods()) {
                if (!method.getName().equals(contract.name()) || Modifier.isStatic(method.getModifiers())
                        || Modifier.isAbstract(method.getModifiers())
                        || !Type.getMethodDescriptor(method).equals(contract.descriptor())) continue;
                if (found != null) return Optional.empty();
                found = method;
            }
            return found != null && found.getDeclaringClass().getName().equals(contract.owner())
                    ? Optional.of(found.getDeclaringClass()) : Optional.empty();
        } catch (RuntimeException | LinkageError unavailable) {
            return Optional.empty();
        }
    }

    /** Tests may replace an observation to verify that stale witnesses do not survive a mutation. */
    public static void resetForTests() {
        LOADERS.clear(); BOOTSTRAP.clear();
        while (COLLECTED.poll() != null) { /* discard collected loader keys */ }
    }

    private static ConcurrentHashMap<String, Observation> ledger(ClassLoader loader, boolean create) {
        LoaderIdentity expired;
        while ((expired = (LoaderIdentity) COLLECTED.poll()) != null) LOADERS.remove(expired);
        if (loader == null) return BOOTSTRAP;
        LoaderIdentity lookup = new LoaderIdentity(loader, null);
        ConcurrentHashMap<String, Observation> found = LOADERS.get(lookup);
        if (found != null || !create) return found;
        ConcurrentHashMap<String, Observation> made = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, Observation> existing = LOADERS.putIfAbsent(new LoaderIdentity(loader, COLLECTED), made);
        return existing == null ? made : existing;
    }

    /** A class loader's equals/hashCode override cannot make another loader's definition look like this one's. */
    private static final class LoaderIdentity extends WeakReference<ClassLoader> {
        private final int hash;
        LoaderIdentity(ClassLoader loader, ReferenceQueue<ClassLoader> queue) {
            super(loader, queue); hash = System.identityHashCode(loader);
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            return other instanceof LoaderIdentity identity && get() != null && get() == identity.get();
        }
    }
}
