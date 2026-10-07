/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import net.forbric.kernel.interop.ManagedWorkerResources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;

@ExecutesInjector(WorkerResourceLifecycleInjector.class)
class WorkerResourceLifecycleInjectorTest {
    private static final String POOL="unrelated/resources/Broker";
    private static final String SOURCE="""
        package unrelated.resources;
        import java.util.*;
        import java.util.concurrent.*;
        import java.util.concurrent.atomic.*;
        public class Broker {
            private final AtomicBoolean phase=new AtomicBoolean(false);
            private final Deque<Runnable> inbox=new ConcurrentLinkedDeque<>();
            private final List<Thread> people=new ArrayList<>();
            private final AtomicInteger outstanding=new AtomicInteger();
            private final Bell bell=new Bell();
            public void begin() {if(phase.getAndSet(true))return;for(int i=0;i<2;i++){Participant person=new Participant();person.setDaemon(true);person.start();people.add(person);}}
            public void finish() {if(!phase.getAndSet(false))return;synchronized(bell){bell.notifyAll();}for(Thread person:people){try{person.join();}catch(InterruptedException ignored){}}people.clear();inbox.clear();}
            public void submit(Runnable task){if(!phase.get())throw new IllegalStateException();outstanding.incrementAndGet();inbox.add(task);bell.signal();}
            public void exhaust(){while(!step()) {}}
            private boolean step(){Runnable task=inbox.pollLast();if(task!=null){perform(task);return false;}return outstanding.get()==0;}
            private void perform(Runnable task){try{task.run();}finally{outstanding.decrementAndGet();}}
            public int live(){return (int)people.stream().filter(Thread::isAlive).count();}
            class Participant extends Thread {
                public void run(){while(phase.get()){Runnable task=inbox.pollFirst();if(task!=null){perform(task);}else{idle();}}}
                private void idle(){if(!inbox.isEmpty())return;bell.park();}
            }
            static class Bell {
                public synchronized void park(){try{wait();}catch(InterruptedException ignored){}}
                public synchronized void signal(){notifyAll();}
            }
        }
        """;
    @TempDir Path work;
    private static Function<String,ClassNode> declarations(Map<String,byte[]> classes) {
        return name->{byte[] bytes=classes.get(name.replace('.','/'));if(bytes==null)return null;ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);return node;};
    }
    private static byte[] bytes(ClassNode node){ClassWriter writer=new ClassWriter(0);node.accept(writer);return writer.toByteArray();}
    /** Renames every vendor class, field, and authored method, while keeping JDK interface/override contracts intact. */
    private static Map<String,byte[]> rename(Map<String,byte[]> original) {
        Map<String,String> mapping=new HashMap<>();
        original.keySet().forEach(name->mapping.put(name,"anywhere/else/Service"+name.substring(POOL.length())));
        int fieldIndex=0,methodIndex=0;
        for(byte[] bytes:original.values()){
            ClassNode owner=new ClassNode();new ClassReader(bytes).accept(owner,0);
            for(FieldNode field:owner.fields)mapping.put(owner.name+"."+field.name,"member"+(fieldIndex++));
            for(MethodNode method:owner.methods)if(!method.name.startsWith("<")&&!method.name.equals("run"))mapping.put(owner.name+"."+method.name+method.desc,"operation"+(methodIndex++));
        }
        Map<String,byte[]> renamed=new HashMap<>();
        for(byte[] bytes:original.values()){
            ClassNode owner=new ClassNode();new ClassReader(bytes).accept(owner,0);
            ClassNode result=new ClassNode();owner.accept(new ClassRemapper(result,new SimpleRemapper(Opcodes.ASM9,mapping)));renamed.put(result.name,bytes(result));
        }
        return renamed;
    }
    @Test void renamedPoolDrainsEverySubmittedTaskAndJoinsItsOwnThreads() throws Throwable {
        Map<String,byte[]> original=rename(InjectorExecution.compile(work,Map.of(POOL,SOURCE)));
        String owner="anywhere/else/Service";
        ClassNode source=declarations(original).apply(owner);
        String begin=source.methods.stream().filter(m->m.desc.equals("()V") && WorkerPoolShape.code(m).stream().anyMatch(i->i instanceof MethodInsnNode call && call.name.equals("start"))).findFirst().orElseThrow().name;
        String finish=source.methods.stream().filter(m->m.desc.equals("()V") && WorkerPoolShape.code(m).stream().anyMatch(i->i instanceof MethodInsnNode call && call.name.equals("join"))).findFirst().orElseThrow().name;
        String submit=source.methods.stream().filter(m->m.desc.equals("(Ljava/lang/Runnable;)V") && (m.access&Opcodes.ACC_PUBLIC)!=0).findFirst().orElseThrow().name;
        String live=source.methods.stream().filter(m->m.desc.equals("()I")).findFirst().orElseThrow().name;
        Map<String,byte[]> edited=new HashMap<>(original);
        WorkerNotificationInjector notification=new WorkerNotificationInjector(declarations(original));
        WorkerResourceLifecycleInjector lifecycle=new WorkerResourceLifecycleInjector(declarations(original));
        int notificationEdits=0,lifecycleEdits=0;
        for(var entry:original.entrySet()){
            byte[] notified=notification.transform(entry.getKey(),entry.getValue(),null);if(notified!=entry.getValue())notificationEdits++;
            byte[] managed=lifecycle.transform(entry.getKey(),notified,null);if(managed!=notified)lifecycleEdits++;
            edited.put(entry.getKey(),managed);
        }
        assertEquals(1,notificationEdits);assertEquals(1,lifecycleEdits);
        assertSame(edited.get(owner),lifecycle.transform(owner,edited.get(owner),null),"idempotence");
        ClassLoader loader=InjectorExecution.load(edited);
        for(byte[] bytes:edited.values())assertEquals("",InjectorExecution.verify(bytes,loader));
        Class<?> pool=loader.loadClass(owner.replace('/','.'));
        for(int round=0;round<10;round++) {
            Object instance=InjectorExecution.construct(pool);AtomicInteger completed=new AtomicInteger();
            try {
                InjectorExecution.invoke(instance,begin);
                for(int i=0;i<100;i++)InjectorExecution.invoke(instance,submit,(Runnable)completed::incrementAndGet);
                ManagedWorkerResources.close(loader);
                assertEquals(100,completed.get());assertEquals(0,InjectorExecution.invoke(instance,live));
                ManagedWorkerResources.close(loader);
            } finally {InjectorExecution.invoke(instance,finish);}
        }
    }
    @Test void wrongDrainEdgeDifferentRunnableAndDifferentThreadCollectionAreRejected() throws Exception {
        Map<String,byte[]> original=InjectorExecution.compile(work,Map.of(POOL,SOURCE));
        WorkerResourceLifecycleInjector injector=new WorkerResourceLifecycleInjector(declarations(original));
        ClassNode pool=declarations(original).apply(POOL);MethodNode drain=WorkerPoolShape.method(pool,"exhaust","()V");
        List<AbstractInsnNode> drainCode=WorkerPoolShape.code(drain);JumpInsnNode repeat=(JumpInsnNode)drainCode.get(drainCode.size()==4?2:3);LabelNode done=new LabelNode();drain.instructions.insertBefore(drainCode.getLast(),done);repeat.label=done;
        byte[] raw=bytes(pool);assertSame(raw,injector.transform(POOL,raw,null),"single-step is not a drain loop");
        pool=declarations(original).apply(POOL);MethodNode step=WorkerPoolShape.method(pool,"step","()Z");
        for(var instruction:step.instructions)if(instruction instanceof MethodInsnNode call&&call.name.equals("perform")){
            ((VarInsnNode)WorkerPoolShape.previous(call)).setOpcode(Opcodes.ALOAD);((VarInsnNode)WorkerPoolShape.previous(call)).var=0;
        }
        raw=bytes(pool);assertSame(raw,injector.transform(POOL,raw,null),"the deque's Runnable must be the executed one");
        pool=declarations(original).apply(POOL);pool.fields.add(new FieldNode(Opcodes.ACC_PRIVATE,"peer","L"+POOL+";",null,null));
        for(var instruction:WorkerPoolShape.method(pool,"begin","()V").instructions.toArray())if(instruction instanceof MethodInsnNode call && call.name.equals("<init>") && call.owner.equals(POOL+"$Participant"))
            pool.methods.stream().filter(m->m.name.equals("begin")).findFirst().orElseThrow().instructions.insertBefore(call,new FieldInsnNode(Opcodes.GETFIELD,POOL,"peer","L"+POOL+";"));
        raw=bytes(pool);assertSame(raw,injector.transform(POOL,raw,null),"the started worker must own this executor's queue, not a peer's");
        pool=declarations(original).apply(POOL);pool.fields.add(new FieldNode(Opcodes.ACC_PRIVATE,"others","Ljava/util/List;",null,null));
        for(var instruction:WorkerPoolShape.method(pool,"finish","()V").instructions)if(instruction instanceof FieldInsnNode field&&field.name.equals("people"))field.name="others";
        raw=bytes(pool);assertSame(raw,injector.transform(POOL,raw,null),"joining another collection cannot close these workers");
        MethodNode finish=WorkerPoolShape.method(pool,"finish","()V");
        for(var instruction:finish.instructions.toArray())if(instruction instanceof MethodInsnNode call && call.owner.equals("java/util/List") && call.name.equals("iterator")) {
            InsnList decoy=new InsnList();decoy.add(new VarInsnNode(Opcodes.ALOAD,0));decoy.add(new FieldInsnNode(Opcodes.GETFIELD,POOL,"people","Ljava/util/List;"));
            decoy.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,"java/util/List","iterator","()Ljava/util/Iterator;",true));decoy.add(new InsnNode(Opcodes.POP));
            finish.instructions.insertBefore(WorkerPoolShape.previous(WorkerPoolShape.previous(call)),decoy);break;
        }
        raw=bytes(pool);assertSame(raw,injector.transform(POOL,raw,null),"an unused iterator over the workers cannot justify joining another iterator");
    }
}
