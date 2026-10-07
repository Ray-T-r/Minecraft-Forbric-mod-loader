/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.tools.ToolProvider;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@net.forbric.kernel.transform.ExecutesInjector(net.forbric.kernel.transform.WorkerResourceLifecycleInjector.class)
class CreateWorkerShutdownTest {
	@TempDir Path root;
	@Test void realFlywheelWorkersDrainAndJoinWithoutCallingTheLazyFactory() throws Exception {
		Path jar = Path.of(System.getProperty("forbric.createFlyJar", "build/compat-inputs/create-fly/create-fly.jar"));
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(jar), "Create Fly jar absent: " + jar);
		String prefix="com/zurrtum/create/client/flywheel/impl/";
		Map<String,String> sources=Map.of(
				"com/google/common/base/Preconditions.java", "package com.google.common.base; public class Preconditions {public static void checkArgument(boolean ok,Object reason){if(!ok)throw new IllegalArgumentException(String.valueOf(reason));}}",
				"net/minecraft/util/Mth.java", "package net.minecraft.util; public class Mth {public static int clamp(int value,int min,int max){return Math.max(min,Math.min(value,max));}}",
				"org/slf4j/Logger.java", "package org.slf4j; public interface Logger {default void info(String s){} default void info(String s,Object o){} default void error(String s){} default void error(String s,Object o){} default void error(String s,Throwable t){} }",
				prefix+"FlwImpl.java", "package com.zurrtum.create.client.flywheel.impl; public class FlwImpl {public static final org.slf4j.Logger LOGGER=new org.slf4j.Logger(){};}",
				prefix+"task/FlwTaskExecutor.java", "package com.zurrtum.create.client.flywheel.impl.task; public class FlwTaskExecutor {private static final AtomicLazy INSTANCE=new AtomicLazy(); private static class AtomicLazy {private final java.util.concurrent.atomic.AtomicReference<Object> reference=new java.util.concurrent.atomic.AtomicReference<>();} public static void set(Object executor){INSTANCE.reference.set(executor);} public static Object get(){throw new AssertionError(\"must not create an executor at exit\");} }");
		List<String> args=new ArrayList<>(List.of("--release","21","-d",root.toString()));
		for(var source:sources.entrySet()){Path file=root.resolve(source.getKey());Files.createDirectories(file.getParent());Files.writeString(file,source.getValue());args.add(file.toString());}
		assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)));
		try(var loader=new URLClassLoader(new URL[]{root.toUri().toURL(),jar.toUri().toURL()},ClassLoader.getPlatformClassLoader()) {
			@Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException {
				if(name.equals(WorkerPredicateWait.class.getName()))return WorkerPredicateWait.class;
				if(name.equals(ManagedWorkerResources.class.getName()))return ManagedWorkerResources.class;
				return super.loadClass(name,resolve);
			}
			@Override protected Class<?> findClass(String name)throws ClassNotFoundException {
				if(!name.equals((prefix+"task/ParallelTaskExecutor").replace('/','.')) && !name.equals((prefix+"task/ParallelTaskExecutor$WorkerThread").replace('/','.')))return super.findClass(name);
				try(var input=getResourceAsStream(name.replace('.','/')+".class")){
					byte[] raw=input.readAllBytes();
					java.util.function.Function<String,org.objectweb.asm.tree.ClassNode> declarations=internal->{
						try(var resource=getResourceAsStream(internal.replace('.','/')+".class")){
							if(resource==null)return null;var node=new org.objectweb.asm.tree.ClassNode();new org.objectweb.asm.ClassReader(resource.readAllBytes()).accept(node,0);return node;
						}catch(java.io.IOException absent){return null;}
					};
					byte[] bytes=new net.forbric.kernel.transform.WorkerNotificationInjector(declarations).transform(name,raw,null);
					bytes=new net.forbric.kernel.transform.WorkerResourceLifecycleInjector(declarations).transform(name,bytes,null);
					assertNotSame(raw,bytes);return defineClass(name,bytes,0,bytes.length);
				}catch(java.io.IOException e){throw new ClassNotFoundException(name,e);}
			}
		}){
			ManagedWorkerResources.close(loader); // Empty lazy reference must remain empty.
			Class<?> pool=loader.loadClass((prefix+"task/ParallelTaskExecutor").replace('/','.'));
			var stop=pool.getMethod("stopWorkers");
			for(int round=0;round<20;round++) {
			Object executor=pool.getConstructor(String.class,int.class).newInstance("Flywheel regression",2);
			try{
				pool.getMethod("startWorkers").invoke(executor);
				Class<?> holder=loader.loadClass((prefix+"task/FlwTaskExecutor").replace('/','.'));
				holder.getMethod("set",Object.class).invoke(null,executor);
				AtomicInteger completed=new AtomicInteger();
				for(int i=0;i<50;i++)pool.getMethod("execute",Runnable.class).invoke(executor,(Runnable)completed::incrementAndGet);
				var field=pool.getDeclaredField("threads");field.setAccessible(true);
				List<?> workers=new ArrayList<>((List<?>)field.get(executor));
				ManagedWorkerResources.close(loader);
				assertEquals(50,completed.get());
				for(Object worker:workers)assertFalse(((Thread)worker).isAlive());
				ManagedWorkerResources.close(loader); // Native stop is idempotent.
			}finally{var threads=pool.getDeclaredField("threads");threads.setAccessible(true);if(!((List<?>)threads.get(executor)).isEmpty())stop.invoke(executor);}
			}
		}
	}
}
