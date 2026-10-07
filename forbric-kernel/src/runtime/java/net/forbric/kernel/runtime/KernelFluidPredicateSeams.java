/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.forbric.kernel.boot.DefinedMethodContracts;
import net.forbric.kernel.boot.DefinedMethodContracts.MethodContract;
import net.forbric.kernel.boot.KernelPredicateSeams;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import net.forbric.kernel.util.ForbricLog;

/** Executes a current native query once, with an original source predicate only for witnessed adapter defaults. */
public final class KernelFluidPredicateSeams {
	private static final ThreadLocal<Scope> ACTIVE=new ThreadLocal<>();
	private static final StackWalker CALLER=StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
	private static final java.util.Set<String> DIAGNOSTICS=java.util.concurrent.ConcurrentHashMap.newKeySet();
	/** Kept lazy: ordinary adapter queries run without linking the proof engine or ASM. */
	private static final class DefaultContracts {
	static final ClassValue<java.util.Map<String,MethodContract>> DEFAULTS=new ClassValue<>(){
		@Override protected java.util.Map<String,MethodContract>computeValue(Class<?>type){
			String resource=type.getName().replace('.','/')+".class";
			try(var in=type.getClassLoader() instanceof net.forbric.kernel.classloading.ForbricClassLoader loader
					?loader.getGameResourceAsStream(resource):type.getResourceAsStream("/"+resource)){
				if(in==null)return java.util.Map.of();ClassNode node=new ClassNode();new ClassReader(in.readAllBytes()).accept(node,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
				if(!node.name.replace('/','.').equals(type.getName()))return java.util.Map.of();java.util.Map<String,MethodContract>map=new java.util.HashMap<>();
				for(var method:node.methods)map.put(method.name+method.desc,new MethodContract(type.getName(),method.name,method.desc,DefinedMethodContracts.fingerprint(method)));return java.util.Map.copyOf(map);
			}catch(java.io.IOException|RuntimeException unavailable){return java.util.Map.of();}
		}
	};
	}
	private static final class Scope{
		final String question,descriptor,key;int observed;KernelPredicateSeams.Validation declined;
		Scope(String question,String descriptor,String key){this.question=question;this.descriptor=descriptor;this.key=key;}
	}
	private KernelFluidPredicateSeams() { }
	/** Called only by the kernel-owned concrete adapter, before it asks a guest behavior. */
	static Boolean nativeDefault(Object adapter){
		Scope scope=ACTIVE.get();if(scope==null)return null;
		if(!(adapter instanceof net.neoforged.neoforge.fluids.FluidType type)||!KernelFabricFluidBehaviors.ownsNeoType(type))return null;
		var caller=CALLER.walk(frames->frames.skip(1).findFirst()).orElseThrow();
		if(!caller.getMethodName().equals(scope.question)||!caller.getDescriptor().equals(scope.descriptor))return null;
		MethodContract contract=DefaultContracts.DEFAULTS.get(caller.getDeclaringClass()).get(scope.question+scope.descriptor);
		if(contract==null||!DefinedMethodContracts.validates(adapter,contract)){scope.declined=new KernelPredicateSeams.Validation(false,false,"owned adapter final method witness is unavailable or changed: "+caller.getDeclaringClass().getName()+"."+scope.question+scope.descriptor);return null;}
		var owned=KernelPredicateSeams.ownedValidation(adapter,scope.key);if(!owned.accepted()){scope.declined=owned;return null;}
		scope.observed++;return false;
	}
	/** Source helpers preserve the old injector and its exceptions. Native concrete/changed dispatch keeps native. */
	public static boolean query(Object receiver,Object state,String key,Supplier<Boolean> nativeQuery,BooleanSupplier source){
		var contract=KernelPredicateSeams.contract(key);
		if(!KernelFabricFluidBehaviors.enabled())return nativeQuery.get();
		var validation=KernelPredicateSeams.validation(receiver,state,key);
		if(contract==null||!validation.accepted()){diagnostic(receiver,state,key,validation);return nativeQuery.get();}
		Scope previous=ACTIVE.get(),scope=new Scope(contract.question(),contract.descriptor(),key);boolean nativeResult;
		ACTIVE.set(scope);try{nativeResult=nativeQuery.get();}finally{if(previous==null)ACTIVE.remove();else ACTIVE.set(previous);}
		// A composed native wrapper which changes the default result retains precedence as well.
		if(scope.declined!=null)diagnostic(receiver,state,key,scope.declined);
		else if(scope.observed!=1)diagnostic(receiver,state,key,new KernelPredicateSeams.Validation(false,scope.observed==0,scope.observed==0?"native type/default retained; no owned adapter terminal was observed":"owned terminal query was evaluated more than once"));
		else if(nativeResult)diagnostic(receiver,state,key,new KernelPredicateSeams.Validation(false,true,"native operation supplied a different result than the witnessed owned default"));
		return scope.observed==1&&scope.declined==null&&!nativeResult?source.getAsBoolean():nativeResult;
	}
	private static void diagnostic(Object receiver,Object state,String key,KernelPredicateSeams.Validation validation){
		String context=key+":"+identity(receiver)+":"+identity(state)+":"+validation.reason();if(!DIAGNOSTICS.add(context))return;
		String message="[Forbric/Mixin] source predicate callback was not executed; native operation retained: "+validation.reason()+" (query="+key+")";
		if(validation.concreteOverride())ForbricLog.info(message);else ForbricLog.warn(message);
	}
	private static String identity(Object value){return value==null?"null":value.getClass().getName()+"@loader"+System.identityHashCode(value.getClass().getClassLoader());}
}
