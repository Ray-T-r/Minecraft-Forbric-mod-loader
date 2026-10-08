/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.forbric.api.NativeEventDelivery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;

/** Unknown protocol and caller names execute both native hooks once, with identity-scoped nested dispatch. */
@ExecutesInjector(NativeDualEventInjector.class)
class NativeDualEventInjectorExecutionTest {
    private static final String ID="test:unknown-native-observer",ROOT="future.delivery.",DESC="(Ljava/lang/Object;Ljava/lang/Object;)V";
    private static NativeEventDelivery.Contract contract(){return new NativeEventDelivery.Contract(ID,
        new NativeEventDelivery.Hook("future/delivery/Source","emit",DESC),new NativeEventDelivery.Hook("future/delivery/Target","emit",DESC),"future/delivery/Event",
        new NativeEventDelivery.Hook("future/delivery/Bus","dispatch","(Lfuture/delivery/Event;)Lfuture/delivery/Event;"));}
    private static Map<String,byte[]> classes(Path work)throws Exception{
        NativeEventDelivery.register(contract());return new HashMap<>(InjectorExecution.compile(work,Map.of(
            ROOT+"Event","package future.delivery; public final class Event {public final Object first,second;public Event(Object first,Object second){this.first=first;this.second=second;}}",
            ROOT+"Bus","package future.delivery; public interface Bus {Event dispatch(Event event);}",
            ROOT+"Target","package future.delivery; public final class Target {public static int deliveries;public static void emit(Object first,Object second){deliveries++;}}",
            ROOT+"Source","""
                package future.delivery;
                import java.util.function.Consumer;
                import net.forbric.api.NativeEventDelivery;
                public final class Source {
                    public static int deliveries;public static Consumer<Object> listener;public static Object last;
                    public static final Bus BUS=event->{deliveries++;last=event;if(listener!=null)listener.accept(event);
                        if(!NativeEventDelivery.covered("test:unknown-native-observer",event))Target.emit(event.first,event.second);return event;};
                    public static void emit(Object first,Object second){BUS.dispatch(new Event(first,second));}
                }
                """,
            ROOT+"Caller","""
                package future.delivery;
                public final class Caller {
                    private final Object first;public Caller(Object first){this.first=first;}
                    public void run(Object second){Target.emit(first,second);Source.emit(first,second);}
                }
                """,
            ROOT+"Mutable","""
                package future.delivery;
                public final class Mutable {
                    public Object first;public Mutable(Object first){this.first=first;}
                    public void run(Object second){Target.emit(first,second);Source.emit(first,second);}
                }
                """,
            ROOT+"Conditional","""
                package future.delivery;
                public final class Conditional {
                    public static void run(boolean nativePath,Object first,Object second){if(nativePath)Target.emit(first,second);Source.emit(first,second);}
                    public static void changed(Object first,Object second){Target.emit(first,second);first=new Object();Source.emit(first,second);}
                }
                """),List.of(Path.of(NativeEventDelivery.class.getProtectionDomain().getCodeSource().getLocation().toURI()))));
    }
    private record World(ClassLoader loader,Object caller,Class<?> source,Class<?> target,Object first,Object second){
        void run()throws Exception{caller.getClass().getMethod("run",Object.class).invoke(caller,second);}
        void direct()throws Exception{source.getMethod("emit",Object.class,Object.class).invoke(null,first,second);}
        int targetCount()throws Exception{return target.getField("deliveries").getInt(null);}
        int sourceCount()throws Exception{return source.getField("deliveries").getInt(null);}
        void listener(Consumer<Object> callback)throws Exception{source.getField("listener").set(null,callback);}
    }
    private static World world(Map<String,byte[]> original,boolean repair)throws Exception{
        Map<String,byte[]> definitions=new HashMap<>(original);var transformer=new NativeDualEventInjector(path->original.get(path.substring(0,path.length()-6)));
        if(repair)for(String owner:List.of("future/delivery/Caller","future/delivery/Source")){
            byte[] before=definitions.get(owner),after=transformer.transform(owner.replace('/','.'),before,null);assertNotSame(before,after,owner);definitions.put(owner,after);
            assertSame(after,transformer.transform(owner.replace('/','.'),after,null),"the emitted proof is idempotent");
        }
        ClassLoader loader=new ClassLoader(NativeEventDelivery.class.getClassLoader()){
            @Override protected Class<?> findClass(String name)throws ClassNotFoundException{byte[] bytes=definitions.get(name.replace('.','/'));if(bytes==null)throw new ClassNotFoundException(name);return defineClass(name,bytes,0,bytes.length);}
        };
        for(var entry:definitions.entrySet())assertEquals("",InjectorExecution.verify(entry.getValue(),loader),entry.getKey());
        Object first=new Object(),second=new Object();Class<?> caller=loader.loadClass(ROOT+"Caller");return new World(loader,caller.getConstructor(Object.class).newInstance(first),loader.loadClass(ROOT+"Source"),loader.loadClass(ROOT+"Target"),first,second);
    }
    @Test void nativeCounterpartAndItsSourceReachEachSubscriberOnce(@TempDir Path work)throws Exception{
        Map<String,byte[]> classes=classes(work);World before=world(classes,false);before.run();assertEquals(1,before.sourceCount());assertEquals(2,before.targetCount(),"the original forward duplicates the retained native target hook");
        World after=world(classes,true);after.run();assertEquals(1,after.sourceCount());assertEquals(1,after.targetCount());assertFalse(NativeEventDelivery.covered(ID,after.source.getField("last").get(null)),"the invocation scope has exited");
        after.direct();assertEquals(2,after.sourceCount());assertEquals(2,after.targetCount(),"a source-only producer still needs its bridge");
    }
    @Test void nestedPostsWithTheExactSamePayloadKeepTheirOwnForward(@TempDir Path work)throws Exception{
        World after=world(classes(work),true);AtomicBoolean nested=new AtomicBoolean();after.listener(event->{if(nested.compareAndSet(false,true))try{after.direct();}catch(Exception failure){throw new RuntimeException(failure);}});
        after.run();assertEquals(2,after.sourceCount());assertEquals(2,after.targetCount(),"only the outer actual event identity had a native counterpart");
        after.listener(null);after.direct();assertEquals(3,after.targetCount(),"no leaked state after nested dispatch");
    }
    @Test void nestedNativeCallsAndThrownListenersRestoreScopes(@TempDir Path work)throws Exception{
        World after=world(classes(work),true);AtomicBoolean nested=new AtomicBoolean();after.listener(event->{if(nested.compareAndSet(false,true))try{after.run();}catch(Exception failure){throw new RuntimeException(failure);}});
        after.run();assertEquals(2,after.sourceCount());assertEquals(2,after.targetCount());
        RuntimeException failure=new IllegalStateException("listener failure");after.listener(event->{throw failure;});InvocationTargetException thrown=assertThrows(InvocationTargetException.class,after::run);assertSame(failure,thrown.getCause());
        after.listener(null);after.direct();assertEquals(4,after.targetCount(),"the next independent event forwards after an exceptional exit");
    }
    @Test void changedArgumentsMutableFieldsAndBypassBranchesCannotBorrowANativeDelivery(@TempDir Path work)throws Exception{
        Map<String,byte[]> classes=classes(work);var transformer=new NativeDualEventInjector(path->classes.get(path.substring(0,path.length()-6)));
        for(String owner:List.of("future/delivery/Mutable","future/delivery/Conditional")){byte[] original=classes.get(owner);assertSame(original,transformer.transform(owner.replace('/','.'),original,null));}
        ClassReader reader=new ClassReader(classes.get("future/delivery/Source"));ClassWriter writer=new ClassWriter(0);reader.accept(new ClassVisitor(Opcodes.ASM9,writer){
            @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions){MethodVisitor visitor=super.visitMethod(access,name,descriptor,signature,exceptions);if(!name.equals("emit"))return visitor;return new MethodVisitor(Opcodes.ASM9,visitor){@Override public void visitInsn(int opcode){if(opcode==Opcodes.RETURN)super.visitInsn(Opcodes.NOP);super.visitInsn(opcode);}};}
        },0);
        Map<String,byte[]> changed=new HashMap<>(classes);changed.put("future/delivery/Source",writer.toByteArray());byte[] caller=classes.get("future/delivery/Caller");
        assertSame(caller,new NativeDualEventInjector(path->changed.get(path.substring(0,path.length()-6))).transform(ROOT+"Caller",caller,null),"the native source producer's closed dispatch must also be proved");
    }
}
