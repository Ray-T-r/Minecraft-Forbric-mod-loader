package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URL;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.jar.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.*;
import net.fabricmc.api.EnvType;
import net.forbric.kernel.classloading.ForbricClassLoader;

@ResourceLock("ForbricMixinService")
class ForbricMixinServiceCacheTest {
 @AfterEach void unbind(){ForbricMixinService.bind(null,EnvType.SERVER);}
 @Test void adapterAndLoaderShareTheRegistrationGeneration(@TempDir Path directory)throws Exception{
  byte[] before=bytes("fixture/cache/Target",1),after=bytes("fixture/cache/Target",2);AtomicBoolean registered=new AtomicBoolean();
  try(var loader=loader(directory,before)){AtomicInteger transforms=new AtomicInteger();loader.setTransformer((name,raw)->{transforms.incrementAndGet();return registered.get()?after:raw;});ForbricMixinService.bind(loader,EnvType.SERVER);
   byte[] first=ForbricMixinService.adapterResource().apply("fixture/cache/Target.class");assertArrayEquals(before,first);assertSame(first,ForbricMixinService.adapterResource().apply("fixture.cache.Target.class"));assertEquals(1,transforms.get());
   assertTrue(ForbricMixinService.registerBeforeDefinition("fixture/cache/Target",()->registered.set(true)));byte[] second=ForbricMixinService.adapterResource().apply("fixture/cache/Target.class");assertArrayEquals(after,second);assertSame(second,loader.getPreMixinClassBytes("fixture.cache.Target"));assertEquals(2,transforms.get());
   loader.defineRuntimeClass("fixture.cache.Target",second);var version=loader.bytecodeGeneration("fixture/cache/Target");AtomicInteger forbidden=new AtomicInteger();assertFalse(ForbricMixinService.registerBeforeDefinition("fixture.cache.Target",forbidden::incrementAndGet));assertEquals(0,forbidden.get());assertTrue(loader.isBytecodeGenerationCurrent("fixture/cache/Target",version));assertSame(second,ForbricMixinService.adapterResource().apply("fixture/cache/Target.class"));
  }
 }
 @Test void anAdapterReaderAlreadyBuildingOldBytesRechecksBeforeReturning(@TempDir Path directory)throws Exception{
  byte[] before=bytes("fixture/cache/Target",1),after=bytes("fixture/cache/Target",2);AtomicBoolean state=new AtomicBoolean();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger transforms=new AtomicInteger();
  try(var loader=loader(directory,before)){loader.setTransformer((name,raw)->{boolean old=state.get();if(transforms.incrementAndGet()==1){entered.countDown();await(release);}return old?after:raw;});ForbricMixinService.bind(loader,EnvType.SERVER);var workers=Executors.newSingleThreadExecutor();
   try{var stale=workers.submit(()->ForbricMixinService.adapterResource().apply("fixture/cache/Target.class"));assertTrue(entered.await(5,TimeUnit.SECONDS));assertTrue(ForbricMixinService.registerBeforeDefinition("fixture/cache/Target",()->state.set(true)));byte[] fresh=ForbricMixinService.adapterResource().apply("fixture/cache/Target.class");release.countDown();assertSame(fresh,stale.get(5,TimeUnit.SECONDS));assertArrayEquals(after,fresh);assertSame(fresh,ForbricMixinService.adapterResource().apply("fixture/cache/Target.class"));}
   finally{release.countDown();workers.shutdownNow();}
  }
 }
 @Test void failedRegistrationAndAnotherLoaderCannotReuseAnOldAdapterEntry(@TempDir Path directory)throws Exception{
  byte[] before=bytes("fixture/cache/Target",1),other=bytes("fixture/cache/Target",7);AtomicBoolean state=new AtomicBoolean();
  try(var first=loader(directory.resolve("first"),before);var second=loader(directory.resolve("second"),other)){AtomicInteger transforms=new AtomicInteger();first.setTransformer((name,raw)->{transforms.incrementAndGet();return state.get()?other:raw;});ForbricMixinService.bind(first,EnvType.SERVER);assertArrayEquals(before,ForbricMixinService.adapterResource().apply("fixture/cache/Target.class"));
   assertThrows(IllegalStateException.class,()->ForbricMixinService.registerBeforeDefinition("fixture.cache.Target",()->{state.set(true);try{throw new IllegalStateException("failed plan");}finally{state.set(false);}}));assertArrayEquals(before,ForbricMixinService.adapterResource().apply("fixture/cache/Target.class"));assertEquals(2,transforms.get());
   second.setTransformer((name,raw)->raw);ForbricMixinService.bind(second,EnvType.SERVER);assertArrayEquals(other,ForbricMixinService.adapterResource().apply("fixture/cache/Target.class"));
  }
 }
 private static ForbricClassLoader loader(Path directory,byte[] bytes)throws Exception{Files.createDirectories(directory);Path jar=directory.resolve("classes.jar");try(var output=new JarOutputStream(Files.newOutputStream(jar))){output.putNextEntry(new JarEntry("fixture/cache/Target.class"));output.write(bytes);output.closeEntry();}return new ForbricClassLoader(new URL[]{jar.toUri().toURL()},ForbricMixinServiceCacheTest.class.getClassLoader());}
 private static byte[] bytes(String owner,int value){ClassWriter writer=new ClassWriter(0);writer.visit(Opcodes.V21,Opcodes.ACC_PUBLIC,owner,null,"java/lang/Object",null);writer.visitField(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC|Opcodes.ACC_FINAL,"VALUE","I",null,value).visitEnd();writer.visitEnd();return writer.toByteArray();}
 private static void await(CountDownLatch latch){try{if(!latch.await(5,TimeUnit.SECONDS))throw new AssertionError("cache test timed out");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}}
}
