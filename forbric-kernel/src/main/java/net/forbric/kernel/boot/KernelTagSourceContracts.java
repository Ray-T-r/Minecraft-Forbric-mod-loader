/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.forbric.kernel.boot.DefinedMethodContracts.MethodContract;
import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.Type;

/** Definition witnesses for a native-source tag view fed by current physical samples. */
public final class KernelTagSourceContracts {
	public record FieldShape(String owner,String name,String descriptor,int flags){ }
	public record Schema(String owner,String mapField,String readyField,String tracker,String tag,
			MethodContract update,List<MethodContract>definitions,List<MethodContract>vectorMethods,
			Map<String,Integer>accessFlags,List<FieldShape>fields,MethodContract fluidQuery,MethodContract fluidTypeGetter){
		public Schema{definitions=List.copyOf(definitions);vectorMethods=List.copyOf(vectorMethods);accessFlags=Map.copyOf(accessFlags);fields=List.copyOf(fields);}
	}
	private static final Map<String,Schema>SCHEMAS=new ConcurrentHashMap<>();
	private static final Set<String>DIAGNOSTICS=ConcurrentHashMap.newKeySet();
	private KernelTagSourceContracts(){ }
	public static void register(String key,Schema schema){Schema old=SCHEMAS.putIfAbsent(key,schema);if(old!=null&&!old.equals(schema))throw new IllegalStateException("Ambiguous native source tag schema "+key);}
	public static Schema schema(String key){return SCHEMAS.get(key);}
	/** Constructor and update guards do not invoke a game query. */
	public static boolean validates(Object receiver,String key){
		Schema schema=SCHEMAS.get(key);if(schema==null||receiver==null)return false;
		try{
			ClassLoader loader=receiver.getClass().getClassLoader();Class<?>owner=Class.forName(schema.owner().replace('/','.'),false,loader);
			Field map=owner.getDeclaredField(schema.mapField()),ready=owner.getDeclaredField(schema.readyField());
			if(!owner.isInstance(receiver)||!Modifier.isFinal(map.getModifiers())||Modifier.isStatic(map.getModifiers())||map.getType()!=Map.class||ready.getType()!=boolean.class||Modifier.isStatic(ready.getModifiers()))return decline(receiver,key,"source fields are unavailable or changed",false);
			if(!DefinedMethodContracts.validates(receiver,schema.update()))return decline(receiver,key,"native update dispatch/final hash is unavailable or changed",false);
			for(MethodContract method:schema.definitions()){
				Class<?>declaration=Class.forName(method.owner(),false,loader);if(!DefinedMethodContracts.observed(declaration.getClassLoader(),method))return decline(receiver,key,"final source dependency is unavailable or changed: "+method.owner()+"."+method.name()+method.descriptor(),false);
				Integer expected=schema.accessFlags().get(method.owner()+"#"+method.name()+method.descriptor());if(expected!=null&&!sameAccess(declaration,method,expected))return decline(receiver,key,"source method synchronization/native/static flags changed: "+method.owner()+"."+method.name()+method.descriptor(),false);
			}
			for(FieldShape shape:schema.fields()){
				Class<?>declaration=Class.forName(shape.owner().replace('/','.'),false,loader);Field field=declaration.getDeclaredField(shape.name());
				int relevant=Modifier.PUBLIC|Modifier.PRIVATE|Modifier.PROTECTED|Modifier.STATIC|Modifier.FINAL|Modifier.VOLATILE|Modifier.TRANSIENT;
				if(!Type.getDescriptor(field.getType()).equals(shape.descriptor())||(field.getModifiers()&relevant)!=(shape.flags()&relevant))return decline(receiver,key,"source field shape changed: "+shape.owner()+"."+shape.name(),false);
			}
			return true;
		}catch(ReflectiveOperationException|RuntimeException|LinkageError unknown){return decline(receiver,key,"source field/definition proof is unavailable",false);}
	}
	public static boolean validatesVector(Object receiver,Object vector,String key){
		Schema schema=SCHEMAS.get(key);if(schema==null||vector==null)return vector==null;
		for(MethodContract method:schema.vectorMethods())if(!DefinedMethodContracts.validates(vector,method))return decline(receiver,key,"native concrete or changed vector dispatch takes precedence: "+method.owner()+"."+method.name()+method.descriptor(),true);
		return true;
	}
	private static boolean sameAccess(Class<?>owner,MethodContract contract,int expected){
		int flags=Modifier.SYNCHRONIZED|Modifier.STATIC|Modifier.NATIVE|Modifier.ABSTRACT;
		// JVM class initialization has no reflective Method; its final bytecode was already witnessed above.
		if(contract.name().equals("<clinit>"))return(expected&Modifier.STATIC)!=0&&(expected&(Modifier.NATIVE|Modifier.ABSTRACT))==0;
		if(contract.name().equals("<init>")){for(Constructor<?>constructor:owner.getDeclaredConstructors())if(Type.getConstructorDescriptor(constructor).equals(contract.descriptor()))return(constructor.getModifiers()&flags)==(expected&flags);return false;}
		for(Method method:owner.getDeclaredMethods())if(method.getName().equals(contract.name())&&Type.getMethodDescriptor(method).equals(contract.descriptor()))return(method.getModifiers()&flags)==(expected&flags);return false;
	}
	public static boolean validatesFluid(Object receiver,Object fluid,String key){
		Schema schema=SCHEMAS.get(key);if(schema==null||fluid==null)return false;
		if(!DefinedMethodContracts.validates(fluid,schema.fluidQuery())||!DefinedMethodContracts.validates(fluid,schema.fluidTypeGetter())){
			try{Class<?>query=declaring(fluid,schema.fluidQuery()),getter=declaring(fluid,schema.fluidTypeGetter());boolean concrete=query!=null&&!query.getName().equals(schema.fluidQuery().owner())||getter!=null&&!getter.getName().equals(schema.fluidTypeGetter().owner());
				return decline(receiver,key,concrete?"native concrete fluid dispatch takes precedence":"fluid dispatch/final hash is unavailable or changed",concrete);
			}catch(RuntimeException|LinkageError unknown){return decline(receiver,key,"fluid dispatch proof is unavailable",false);}
		}return true;
	}
	private static Class<?>declaring(Object object,MethodContract contract){for(Method m:object.getClass().getMethods())if(m.getName().equals(contract.name())&&Type.getMethodDescriptor(m).equals(contract.descriptor())&&!Modifier.isStatic(m.getModifiers()))return m.getDeclaringClass();return null;}
	public static boolean decline(Object receiver,String key,String reason,boolean concrete){String id=key+":"+(receiver==null?"null":receiver.getClass().getName()+"@"+System.identityHashCode(receiver.getClass().getClassLoader()))+":"+reason;
		if(DIAGNOSTICS.add(id)){String text="[Forbric/Mixin] source tag view was not updated; native type queries retained: "+reason+" (source="+key+")";if(concrete)ForbricLog.info(text);else ForbricLog.warn(text);}return false;
	}
	public static void resetForTests(){SCHEMAS.clear();DIAGNOSTICS.clear();}
}
