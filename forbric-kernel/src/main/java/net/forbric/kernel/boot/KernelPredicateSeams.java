/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.objectweb.asm.Type;
import net.forbric.kernel.boot.DefinedMethodContracts.MethodContract;

/** Final-defined dispatch witnesses for a native-proved source predicate behind a current default query. */
public final class KernelPredicateSeams {
	public sealed interface Value permits Root, Projection, Opaque { }
	public record Root(int index) implements Value { }
	public record Projection(Value receiver,String owner,String name,String descriptor) implements Value { }
	/** A default getter executed by the native operation, never by the guard. */
	public record Opaque(Value receiver) implements Value { }
	public record Guard(Value receiver,MethodContract method,boolean direct) { }
	public record Contract(List<Guard> guards,String question,String descriptor) {
		public Contract { guards=List.copyOf(guards); }
	}
	public record Validation(boolean accepted,boolean concreteOverride,String reason){ }
	private static final Map<String,Contract> CONTRACTS=new ConcurrentHashMap<>();
	private KernelPredicateSeams() { }
	public static String register(Contract contract) {
		try { String key=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contract.toString().getBytes(StandardCharsets.UTF_8)));CONTRACTS.putIfAbsent(key,contract);return key; }
		catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
	}
	public static Contract contract(String key){return CONTRACTS.get(key);}
	/** No query/getter is invoked here: object projection uses only proved final fields. */
	public static boolean validates(Object receiver,Object argument,String key){return validation(receiver,argument,key).accepted();}
	public static Validation validation(Object receiver,Object argument,String key){
		Contract contract=CONTRACTS.get(key);if(contract==null)return declined(false,"query contract is unavailable");Object[]roots={receiver,argument};Map<Value,Object>memo=new HashMap<>();
		try{
			for(Guard guard:contract.guards()){
				if(guard.receiver() instanceof Opaque)continue;
				Object object=value(guard.receiver(),roots,memo);if(object==null)return declined(false,"proved immutable receiver projection is unavailable or changed");
				if(guard.direct()){
					Class<?>owner=Class.forName(guard.method().owner(),false,object.getClass().getClassLoader());
					if(!owner.isInstance(object)||!DefinedMethodContracts.observed(owner.getClassLoader(),guard.method()))return declined(false,"direct default/helper final method witness is unavailable or changed: "+guard.method().owner()+"."+guard.method().name()+guard.method().descriptor());
				}else if(!DefinedMethodContracts.validates(object,guard.method()))return dispatchFailure(object,guard.method());
			}
			return new Validation(true,false,"proved");
		}catch(ReflectiveOperationException|RuntimeException|LinkageError unproved){return declined(false,"dispatch/projection proof is unavailable: "+unproved.getClass().getSimpleName());}
	}
	/** Deferred dispatch on the owned type discovered by native; a getter is still never invoked by this guard. */
	public static Validation ownedValidation(Object adapter,String key){
		Contract contract=CONTRACTS.get(key);if(contract==null||adapter==null)return declined(false,"owned terminal query contract is unavailable");
		for(Guard guard:contract.guards())if(guard.receiver()instanceof Opaque){
			if(guard.direct())return declined(false,"unproved direct dispatch on the owned terminal type");
			if(!DefinedMethodContracts.validates(adapter,guard.method()))return dispatchFailure(adapter,guard.method());
		}return new Validation(true,false,"proved");
	}
	private static Validation dispatchFailure(Object receiver,MethodContract contract){
		for(var method:receiver.getClass().getMethods())if(method.getName().equals(contract.name())&&Type.getMethodDescriptor(method).equals(contract.descriptor())&&!Modifier.isStatic(method.getModifiers())){
			if(!method.getDeclaringClass().getName().equals(contract.owner()))return declined(true,"native concrete override: "+method.getDeclaringClass().getName()+"."+method.getName()+contract.descriptor());
			return declined(false,"final method witness is unavailable or changed: "+contract.owner()+"."+contract.name()+contract.descriptor());
		}return declined(false,"native dispatch target is unavailable: "+contract.owner()+"."+contract.name()+contract.descriptor());
	}
	private static Validation declined(boolean concrete,String reason){return new Validation(false,concrete,reason);}
	private static Object value(Value value,Object[]roots,Map<Value,Object>memo)throws ReflectiveOperationException{
		if(memo.containsKey(value))return memo.get(value);Object result;
		if(value instanceof Root root)result=roots[root.index()];
		else if(value instanceof Projection projection){
			Object receiver=value(projection.receiver(),roots,memo);if(receiver==null)return null;
			Class<?>owner=Class.forName(projection.owner().replace('/','.'),false,receiver.getClass().getClassLoader());Field field=owner.getDeclaredField(projection.name());
			if(!owner.isInstance(receiver)||!Modifier.isFinal(field.getModifiers())||Modifier.isStatic(field.getModifiers())||!Type.getDescriptor(field.getType()).equals(projection.descriptor()))return null;
			field.setAccessible(true);result=field.get(receiver);
		}else return null;
		memo.put(value,result);return result;
	}
	public static void resetForTests(){CONTRACTS.clear();}
}
