/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.classloading;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/**
 * What Mixin is shown when it asks for a class's bytes.
 *
 * <p>Every ask re-read the jar and re-ran the WHOLE transform chain, and Mixin asks repeatedly for the same
 * classes: each anchor it resolves walks its target's superclass chain, and the game's own types sit under
 * nearly everything.
 *
 * <p>The correctness that matters more than the saving: whatever is remembered must be the TRANSFORMED bytes.
 * Handing Mixin the untransformed class would have it weave against something the loader never defines.
 */
class ForbricClassLoaderPreMixinCacheTest {
	@Test void aLateRegistrationRebuildsCachedBytesAndRejectsDefinedTargets(@TempDir Path dir)throws Exception{
		Path jar=jarWith(dir,"com/example/Late");java.util.concurrent.atomic.AtomicBoolean registered=new java.util.concurrent.atomic.AtomicBoolean();AtomicInteger transforms=new AtomicInteger();byte[] changed=classBytes("com/example/Changed");
		try(ForbricClassLoader loader=new ForbricClassLoader(new URL[]{jar.toUri().toURL()},getClass().getClassLoader())){
			loader.setTransformer((name,bytes)->{transforms.incrementAndGet();return registered.get()?changed:bytes;});byte[] initial=loader.getPreMixinClassBytes("com/example/Late");assertSame(initial,loader.getPreMixinClassBytes("com.example.Late"));assertEquals(1,transforms.get());
			assertTrue(loader.registerBeforeDefinition("com/example/Late",()->registered.set(true)));assertArrayEquals(changed,loader.getPreMixinClassBytes("com.example.Late"));assertEquals(2,transforms.get());
			loader.defineRuntimeClass("com.example.Defined",classBytes("com/example/Defined"));AtomicInteger mutation=new AtomicInteger();var generation=loader.bytecodeGeneration("com.example.Defined");assertFalse(loader.registerBeforeDefinition("com/example/Defined",mutation::incrementAndGet));assertEquals(0,mutation.get());assertTrue(loader.isBytecodeGenerationCurrent("com.example.Defined",generation));
		}
	}
	@Test void aConcurrentOldReaderCannotReturnOrRepublishPreRegistrationBytes(@TempDir Path dir)throws Exception{
		Path jar=jarWith(dir,"com/example/Late");java.util.concurrent.atomic.AtomicBoolean registered=new java.util.concurrent.atomic.AtomicBoolean();java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);AtomicInteger calls=new AtomicInteger();byte[] changed=classBytes("com/example/Changed");
		try(ForbricClassLoader loader=new ForbricClassLoader(new URL[]{jar.toUri().toURL()},getClass().getClassLoader())){loader.setTransformer((name,bytes)->{boolean snapshot=registered.get();if(calls.incrementAndGet()==1){entered.countDown();await(release);}return snapshot?changed:bytes;});var workers=java.util.concurrent.Executors.newFixedThreadPool(2);
			try{var stale=workers.submit(()->loader.getPreMixinClassBytes("com.example.Late"));assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));assertTrue(loader.registerBeforeDefinition("com/example/Late",()->registered.set(true)));byte[] current=loader.getPreMixinClassBytes("com.example.Late");assertArrayEquals(changed,current);release.countDown();assertArrayEquals(changed,stale.get(5,java.util.concurrent.TimeUnit.SECONDS));assertSame(current,loader.getPreMixinClassBytes("com/example/Late"));}
			finally{release.countDown();workers.shutdownNow();}
		}
	}
	@Test void aThrowingRegistrationDoesNotLeavePendingStateOrPublishItsTemporaryBytes(@TempDir Path dir)throws Exception{
		Path jar=jarWith(dir,"com/example/Late");java.util.concurrent.atomic.AtomicBoolean state=new java.util.concurrent.atomic.AtomicBoolean();AtomicInteger calls=new AtomicInteger();
		try(ForbricClassLoader loader=new ForbricClassLoader(new URL[]{jar.toUri().toURL()},getClass().getClassLoader())){loader.setTransformer((name,bytes)->{calls.incrementAndGet();return state.get()?classBytes("com/example/Temporary"):bytes;});byte[] original=loader.getPreMixinClassBytes("com.example.Late");
			assertThrows(IllegalArgumentException.class,()->loader.registerBeforeDefinition("com/example/Late",()->{state.set(true);try{throw new IllegalArgumentException("registration failed");}finally{state.set(false);}}));assertArrayEquals(original,loader.getPreMixinClassBytes("com.example.Late"));assertEquals(2,calls.get());assertTrue(loader.registerBeforeDefinition("com.example.Late",()->{}));
		}
	}
	private static void await(java.util.concurrent.CountDownLatch latch){try{if(!latch.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("cache test timed out");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}}
	@Test void aReentrantPlanCannotRegisterAfterThisDefinitionsLocalBytesHaveBeenBuilt(@TempDir Path dir)throws Exception{
		Path jar=jarWith(dir,"com/example/Late");AtomicInteger mutation=new AtomicInteger();java.util.concurrent.atomic.AtomicBoolean accepted=new java.util.concurrent.atomic.AtomicBoolean(true);
		try(ForbricClassLoader loader=new ForbricClassLoader(new URL[]{jar.toUri().toURL()},getClass().getClassLoader())){loader.setTransformer((name,bytes)->{accepted.set(loader.registerBeforeDefinition(name,mutation::incrementAndGet));return bytes;});assertNotNull(loader.loadClass("com.example.Late"));assertFalse(accepted.get());assertEquals(0,mutation.get());assertFalse(loader.registerBeforeDefinition("com/example/Late",mutation::incrementAndGet));}
	}
	@Test void readersDoNotSeeTheMiddleOfARegistration(@TempDir Path dir)throws Exception{
		Path jar=jarWith(dir,"com/example/Late");java.util.concurrent.CountDownLatch pending=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);java.util.concurrent.atomic.AtomicBoolean state=new java.util.concurrent.atomic.AtomicBoolean();AtomicInteger transforms=new AtomicInteger();byte[] changed=classBytes("com/example/Changed");
		try(ForbricClassLoader loader=new ForbricClassLoader(new URL[]{jar.toUri().toURL()},getClass().getClassLoader())){loader.setTransformer((name,bytes)->{transforms.incrementAndGet();return state.get()?changed:bytes;});var workers=java.util.concurrent.Executors.newFixedThreadPool(2);
			try{var registration=workers.submit(()->loader.registerBeforeDefinition("com.example.Late",()->{state.set(true);pending.countDown();await(release);}));assertTrue(pending.await(5,java.util.concurrent.TimeUnit.SECONDS));var reader=workers.submit(()->loader.getPreMixinClassBytes("com.example.Late"));Thread.sleep(20);assertFalse(reader.isDone());assertEquals(0,transforms.get());release.countDown();assertTrue(registration.get(5,java.util.concurrent.TimeUnit.SECONDS));assertArrayEquals(changed,reader.get(5,java.util.concurrent.TimeUnit.SECONDS));}
			finally{release.countDown();workers.shutdownNow();}
		}
	}
	@Test void aRegistrationCannotReentrantlyDefineItsOwnTarget(@TempDir Path dir)throws Exception{
		Path jar=jarWith(dir,"com/example/Late");try(ForbricClassLoader loader=new ForbricClassLoader(new URL[]{jar.toUri().toURL()},getClass().getClassLoader())){loader.setTransformer((name,bytes)->bytes);
			assertThrows(IllegalStateException.class,()->loader.registerBeforeDefinition("com.example.Late",()->loader.defineRuntimeClass("com.example.Late",classBytes("com/example/Late"))));assertFalse(loader.isClassLoadedByName("com.example.Late"));assertTrue(loader.registerBeforeDefinition("com.example.Late",()->{}));assertNotNull(loader.loadClass("com.example.Late"));
		}
	}
	@Test void crossClassInspectionRunsWithoutHoldingAnotherTargetsClassLock(@TempDir Path dir)throws Exception{
		Path jar=dir.resolve("two.jar");try(var output=new JarOutputStream(Files.newOutputStream(jar))){for(String name:java.util.List.of("A","B")){output.putNextEntry(new ZipEntry("com/example/"+name+".class"));output.write(classBytes("com/example/"+name));output.closeEntry();}}
		try(ForbricClassLoader loader=new ForbricClassLoader(new URL[]{jar.toUri().toURL()},getClass().getClassLoader())){var seen=new java.util.concurrent.ConcurrentHashMap<String,AtomicInteger>();var barrier=new java.util.concurrent.CyclicBarrier(2);loader.setTransformer((name,bytes)->{if(seen.computeIfAbsent(name,ignored->new AtomicInteger()).incrementAndGet()==1){try{barrier.await(5,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception failure){throw new AssertionError(failure);}assertNotNull(loader.getPreMixinClassBytes(name.endsWith("A")?"com.example.B":"com.example.A"));}return bytes;});var workers=java.util.concurrent.Executors.newFixedThreadPool(2);
			try{var a=workers.submit(()->loader.getPreMixinClassBytes("com.example.A"));var b=workers.submit(()->loader.getPreMixinClassBytes("com.example.B"));assertNotNull(a.get(5,java.util.concurrent.TimeUnit.SECONDS));assertNotNull(b.get(5,java.util.concurrent.TimeUnit.SECONDS));}finally{workers.shutdownNow();}
		}
	}

	private static byte[] classBytes(String internalName) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static Path jarWith(Path dir, String internalName) throws Exception {
		Path jar = dir.resolve("owned.jar");
		try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(out)) {
			jos.putNextEntry(new ZipEntry(internalName + ".class"));
			jos.write(classBytes(internalName));
			jos.closeEntry();
		}
		return jar;
	}

	@Test
	void theChainRunsOncePerClass(@TempDir Path dir) throws Exception {
		Path jar = jarWith(dir, "com/example/Target");
		AtomicInteger ran = new AtomicInteger();

		try (ForbricClassLoader loader = new ForbricClassLoader(
				new URL[] {jar.toUri().toURL()}, getClass().getClassLoader())) {
			loader.setTransformer((name, bytes) -> {
				ran.incrementAndGet();
				return bytes;
			});

			assertNotNull(loader.getPreMixinClassBytes("com.example.Target"));
			loader.getPreMixinClassBytes("com.example.Target");
			loader.getPreMixinClassBytes("com.example.Target");

			assertEquals(1, ran.get(), "the transform chain re-ran for a class it had already produced");
		}
	}

	@Test
	void whatIsRememberedIsTheTransformedClass(@TempDir Path dir) throws Exception {
		// The hazard worth more than the saving: Mixin weaving against bytes the loader never defines.
		Path jar = jarWith(dir, "com/example/Target");
		byte[] rewritten = classBytes("com/example/Rewritten");

		try (ForbricClassLoader loader = new ForbricClassLoader(
				new URL[] {jar.toUri().toURL()}, getClass().getClassLoader())) {
			// Name-sensitive, as every real transformer is: it compares BINARY names and declines anything else.
			loader.setTransformer((name, bytes) -> "com.example.Target".equals(name) ? rewritten : bytes);

			assertArrayEquals(rewritten, loader.getPreMixinClassBytes("com.example.Target"));
			// The INTERNAL name too: fabric-item-api's tooltip-order scrape asks the bytecode provider with
			// Type.getInternalName(ItemStack.class), and got untransformed bytes (every dotted-name transformer
			// declined the slashed name) — "Found no component types" on a base that had 34 restored.
			assertArrayEquals(rewritten, loader.getPreMixinClassBytes("com/example/Target"),
					"a slashed name must reach the same transformed bytes as the dotted one");
			assertArrayEquals(rewritten, loader.getPreMixinClassBytes("com.example.Target"),
					"the remembered answer has to be the transformed one, not the raw class");
		}
	}

	@Test
	void installingTheChainForgetsWhatWasAnsweredWithoutIt(@TempDir Path dir) throws Exception {
		// Anything answered before the chain existed was answered UNTRANSFORMED. Keeping it would hand that out
		// for the rest of the run.
		Path jar = jarWith(dir, "com/example/Target");
		byte[] rewritten = classBytes("com/example/Rewritten");

		try (ForbricClassLoader loader = new ForbricClassLoader(
				new URL[] {jar.toUri().toURL()}, getClass().getClassLoader())) {
			byte[] beforeChain = loader.getPreMixinClassBytes("com.example.Target");
			assertNotNull(beforeChain);

			loader.setTransformer((name, bytes) -> rewritten);

			assertArrayEquals(rewritten, loader.getPreMixinClassBytes("com.example.Target"));
		}
	}

	@Test
	void installingASecondChainForgetsWhatTheFirstProduced(@TempDir Path dir) throws Exception {
		// The case the clear in setTransformer exists for. With one install it is redundant — nothing is
		// remembered before a chain exists — so this is the only place it is load-bearing, and a second install
		// handing out the FIRST chain's output for the rest of the run is the failure it prevents.
		Path jar = jarWith(dir, "com/example/Target");
		byte[] first = classBytes("com/example/First");
		byte[] second = classBytes("com/example/Second");

		try (ForbricClassLoader loader = new ForbricClassLoader(
				new URL[] {jar.toUri().toURL()}, getClass().getClassLoader())) {
			loader.setTransformer((name, bytes) -> first);
			assertArrayEquals(first, loader.getPreMixinClassBytes("com.example.Target"));

			loader.setTransformer((name, bytes) -> second);

			assertArrayEquals(second, loader.getPreMixinClassBytes("com.example.Target"));
		}
	}

	@Test
	void generatingAClassForgetsWhatWasAnsweredForThatName(@TempDir Path dir) throws Exception {
		Path jar = jarWith(dir, "com/example/Target");

		try (ForbricClassLoader loader = new ForbricClassLoader(
				new URL[] {jar.toUri().toURL()}, getClass().getClassLoader())) {
			AtomicInteger ran = new AtomicInteger();
			loader.setTransformer((name, bytes) -> {
				ran.incrementAndGet();
				return bytes;
			});

			loader.getPreMixinClassBytes("com.example.Target");
			loader.putGeneratedClass("com/example/Target", classBytes("com/example/Target"));
			loader.getPreMixinClassBytes("com.example.Target");

			assertEquals(2, ran.get(),
					"a name whose class was regenerated must not keep answering with the old bytes");
		}
	}
}
