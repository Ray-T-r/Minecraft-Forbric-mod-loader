package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import net.forbric.kernel.TestFixtures;

/** Real event buses: widget mutations reach the live screen list and keyboard consumption crosses the bridge once. */
class KernelGameScreenEventsTest {
 @TempDir Path temporary;
 private static final String NEO="net.neoforged.neoforge.client.event.ScreenEvent$";
 private static final String FORGE="net.minecraftforge.client.event.ScreenEvent$";

 @Test void initCallbacksChangeTheOriginalWidgetList() throws Exception {
  TestFixtures.requireForgeCarrier("MinecraftForge's screen events and their bus");
  for(String phase:List.of("Pre","Post")) try(var cl=loader()) {
   Object bus=bus(cl);
   install(cl,bus,"installScreenInit"+phase);
   Class<?> screen=cl.loadClass("net.minecraft.client.gui.screens.Screen");
   Object screenToken=screen.getConstructor().newInstance();
   Class<?> listener=cl.loadClass("net.minecraft.client.gui.components.events.GuiEventListener");
   Object widget=java.lang.reflect.Proxy.newProxyInstance(cl,new Class<?>[]{listener},(p,m,a)->null);
   List<Object> children=new ArrayList<>();
   AtomicInteger adds=new AtomicInteger(),removes=new AtomicInteger(),seen=new AtomicInteger();
   Consumer<Object> add=w->{children.add(w);adds.incrementAndGet();};
   Consumer<Object> remove=w->{children.removeIf(x->x==w);removes.incrementAndGet();};
   Object event=cl.loadClass(NEO+"Init$"+phase).getConstructor(screen,List.class,Consumer.class,Consumer.class)
     .newInstance(screenToken,children,add,remove);
   Class<?> forge=cl.loadClass(FORGE+"Init$"+phase);
   Consumer<Object> action=e->{
    try {
     seen.incrementAndGet();
     assertSame(screenToken,forge.getMethod("getScreen").invoke(e));
     forge.getMethod("addListener",listener).invoke(e,widget);
     assertTrue(((List<?>)forge.getMethod("getListenersList").invoke(e)).stream().anyMatch(w->w==widget));
     forge.getMethod("removeListener",listener).invoke(e,widget);
     forge.getMethod("addListener",listener).invoke(e,widget);
    } catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
   };
   Object forgeBus=forge.getField("BUS").get(null);
   if(phase.equals("Pre")) {
    Predicate<Object> veto=e->{action.accept(e);return true;};
    cl.loadClass("net.minecraftforge.eventbus.api.bus.CancellableEventBus").getMethod("addListener",Predicate.class).invoke(forgeBus,veto);
   } else cl.loadClass("net.minecraftforge.eventbus.api.bus.EventBus").getMethod("addListener",Consumer.class).invoke(forgeBus,action);
   post(cl,bus,event);
   assertEquals(1,seen.get());assertEquals(2,adds.get());assertEquals(1,removes.get());
   assertEquals(1,children.size());assertSame(widget,children.getFirst());
   if(phase.equals("Pre")) assertEquals(true,cl.loadClass("net.neoforged.bus.api.ICancellableEvent").getMethod("isCanceled").invoke(event));
  }
 }

 @Test void allFourKeyPhasesForwardTheSameKeyAndRespectCancellation() throws Exception {
  TestFixtures.requireForgeCarrier("MinecraftForge's screen events and their bus");
  for(String phase:List.of("KeyPressed$Pre","KeyPressed$Post","KeyReleased$Pre","KeyReleased$Post")) try(var cl=loader()) {
   Object bus=bus(cl);
   install(cl,bus,"installScreen"+phase.replace("$",""));
   Class<?> screen=cl.loadClass("net.minecraft.client.gui.screens.Screen");
   Object screenToken=screen.getConstructor().newInstance();
   Class<?> keyType=cl.loadClass("net.minecraft.client.input.KeyEvent");
   Object key=keyType.getConstructor(int.class,int.class,int.class).newInstance(82,7,2);
   Class<?> forge=cl.loadClass(FORGE+phase), neo=cl.loadClass(NEO+phase);
   AtomicInteger seen=new AtomicInteger();AtomicBoolean cancel=new AtomicBoolean();
   Predicate<Object> callback=e->{
    try {seen.incrementAndGet();assertSame(key,forge.getMethod("getInfo").invoke(e));assertSame(screenToken,forge.getMethod("getScreen").invoke(e));}
    catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    return cancel.get();
   };
   cl.loadClass("net.minecraftforge.eventbus.api.bus.CancellableEventBus").getMethod("addListener",Predicate.class)
     .invoke(forge.getField("BUS").get(null),callback);
   Class<?> cancellable=cl.loadClass("net.neoforged.bus.api.ICancellableEvent");
   Object first=neo.getConstructor(screen,keyType).newInstance(screenToken,key);
   post(cl,bus,first);assertEquals(false,cancellable.getMethod("isCanceled").invoke(first));assertEquals(1,seen.get());
   cancel.set(true);
   Object second=neo.getConstructor(screen,keyType).newInstance(screenToken,key);
   post(cl,bus,second);assertEquals(true,cancellable.getMethod("isCanceled").invoke(second));assertEquals(2,seen.get());
   Object alreadyCancelled=neo.getConstructor(screen,keyType).newInstance(screenToken,key);
   cancellable.getMethod("setCanceled",boolean.class).invoke(alreadyCancelled,true);
   post(cl,bus,alreadyCancelled);
   assertEquals(2,seen.get(),"an already consumed NeoForge key must not reach Forge again");
  }
 }
 private URLClassLoader loader() throws Exception {
  // Only a screen identity is needed by the real events. The in-game IPN canary exercises actual screen lifecycle.
  Path stubs=Files.createTempDirectory(temporary,"screen-identity");
  ClassWriter writer=new ClassWriter(0);
  writer.visit(Opcodes.V21,Opcodes.ACC_PUBLIC,"net/minecraft/client/gui/screens/Screen",null,"java/lang/Object",null);
  var init=writer.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);
  init.visitCode();init.visitVarInsn(Opcodes.ALOAD,0);init.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false);
  init.visitInsn(Opcodes.RETURN);init.visitMaxs(1,1);init.visitEnd();writer.visitEnd();
  Path file=stubs.resolve("net/minecraft/client/gui/screens/Screen.class");Files.createDirectories(file.getParent());Files.write(file,writer.toByteArray());
  return StagedGameClassLoader.create(List.of(stubs.toUri().toURL(),Path.of("build/classes/java/main").toAbsolutePath().toUri().toURL()));
 }
 private static Object bus(ClassLoader cl)throws Exception {
  Object builder=cl.loadClass("net.neoforged.bus.api.BusBuilder").getMethod("builder").invoke(null);
  return builder.getClass().getMethod("build").invoke(builder);
 }
 private static void install(ClassLoader cl,Object bus,String method)throws Exception {
  cl.loadClass("net.forbric.kernel.runtime.KernelGameClientEvents").getMethod(method,Object.class).invoke(null,bus);
 }
 private static void post(ClassLoader cl,Object bus,Object event)throws Exception {
  cl.loadClass("net.neoforged.bus.api.IEventBus").getMethod("post",cl.loadClass("net.neoforged.bus.api.Event")).invoke(bus,event);
 }
}
