/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import net.fabricmc.api.EnvType;
import net.forbric.kernel.interop.RegistryElementCallbacks;

@ExecutesInjector(RegistryElementCallbackInjector.class)
class RegistryElementCallbackInjectorTest {
    @TempDir Path root;
    private static final String WALKER="unknown/cachecontract/EarlyWalker";
    private static final String LOOP="if(CacheElement.class.isAssignableFrom(BlockState.class)) for(BlockState state:Block.BLOCK_STATE_REGISTRY) ((CacheElement)state).initializeDerivedState();";
    private Map<String,byte[]> compile(String body) throws Exception {
        Map<String,byte[]> compiled=InjectorExecution.compile(root,Map.of(
            "net.minecraft.core.IdMapper","package net.minecraft.core; public class IdMapper<T> implements Iterable<T> {private final java.util.List<T> values=new java.util.ArrayList<>(); public void add(T value){values.add(value);} public java.util.Iterator<T> iterator(){return values.iterator();}}",
            "net.minecraft.world.level.block.Block","package net.minecraft.world.level.block; public class Block {public static final net.minecraft.core.IdMapper<net.minecraft.world.level.block.state.BlockState> BLOCK_STATE_REGISTRY=new net.minecraft.core.IdMapper<>();}",
            "unknown.cachecontract.CacheElement","package unknown.cachecontract; public interface CacheElement {void initializeDerivedState();void initializeSecond();}",
            "net.minecraft.world.level.block.state.BlockState","package net.minecraft.world.level.block.state; public class BlockState {public int calls,secondCalls;public boolean fail,failSecond;public Runnable during;public void initializeDerivedState(){calls++;if(during!=null)during.run();if(fail)throw new IllegalStateException(\"failed initializer\");}public void initializeSecond(){secondCalls++;if(failSecond)throw new IllegalStateException(\"failed second initializer\");}}",
            "unknown.cachecontract.EarlyWalker","package unknown.cachecontract;import net.minecraft.world.level.block.Block;import net.minecraft.world.level.block.state.BlockState;public class EarlyWalker {public static void populate(){"+body+"}}"));
        // A mixin adds the callback interface after the initializer was compiled against vanilla's element class.
        ClassNode state=new ClassNode();new ClassReader(compiled.get("net/minecraft/world/level/block/state/BlockState")).accept(state,0);
        state.interfaces.add("unknown/cachecontract/CacheElement");ClassWriter writer=new ClassWriter(0);state.accept(writer);
        compiled.put(state.name,writer.toByteArray());return compiled;
    }
    private static RegistryElementCallbackInjector injector(Map<String,byte[]> classes){
        return new RegistryElementCallbackInjector(name->{byte[] bytes=classes.get(name.replace('.','/'));if(bytes==null)return null;ClassNode n=new ClassNode();new ClassReader(bytes).accept(n,0);return n;});
    }
    private record Loaded(ClassLoader loader,Class<?> state,Object registry,Class<?> walker){}
    private Loaded load(Map<String,byte[]> originals) throws Throwable {
        Map<String,byte[]> classes=new HashMap<>(originals);byte[] raw=classes.get(WALKER);
        byte[] edited=InjectorExecution.transform(injector(classes),WALKER.replace('/','.'),raw,EnvType.SERVER);
        assertNotSame(raw,edited);classes.put(WALKER,edited);ClassLoader loader=InjectorExecution.load(classes);
        assertEquals("",InjectorExecution.verify(edited,loader));
        Object registry=loader.loadClass("net.minecraft.world.level.block.Block").getField("BLOCK_STATE_REGISTRY").get(null);
        return new Loaded(loader,loader.loadClass("net.minecraft.world.level.block.state.BlockState"),registry,loader.loadClass(WALKER.replace('/','.')));
    }
    private Object add(Loaded loaded) throws Throwable {Object state=InjectorExecution.construct(loaded.state);InjectorExecution.invoke(loaded.registry,"add",state);return state;}
    private int calls(Loaded loaded,Object state) throws Exception{return loaded.state.getField("calls").getInt(state);}

    @Test void lateElementsReceiveTheirOriginalCallbackOnceWithoutReplayingInitializedElements() throws Throwable {
        Loaded l=load(compile(LOOP));Object first=add(l);InjectorExecution.invokeStatic(l.walker,"populate");assertEquals(1,calls(l,first));
        Object late=add(l);assertEquals(1,RegistryElementCallbacks.complete(l.registry));assertEquals(1,calls(l,first));assertEquals(1,calls(l,late));
        assertEquals(0,RegistryElementCallbacks.complete(l.registry));assertEquals(1,calls(l,late));
    }
    @Test void aSuccessfulEmptyWalkStillInitializesLaterRegistrations() throws Throwable {
        Loaded l=load(compile(LOOP));InjectorExecution.invokeStatic(l.walker,"populate");Object late=add(l);
        assertEquals(1,RegistryElementCallbacks.complete(l.registry));assertEquals(1,calls(l,late));
    }
    @Test void allWalksCommitTogetherAndKeepTheirOriginalCallbackOrder() throws Throwable {
        String second=LOOP.replace("initializeDerivedState()","initializeSecond()");
        Loaded l=load(compile(LOOP+second));Object first=add(l);InjectorExecution.invokeStatic(l.walker,"populate");Object late=add(l);
        assertEquals(2,RegistryElementCallbacks.complete(l.registry));assertEquals(1,calls(l,first));assertEquals(1,calls(l,late));
        assertEquals(1,l.state.getField("secondCalls").getInt(first));assertEquals(1,l.state.getField("secondCalls").getInt(late));
        assertEquals(0,RegistryElementCallbacks.complete(l.registry));
        Loaded failed=load(compile(LOOP+second));Object bad=add(failed);failed.state.getField("failSecond").setBoolean(bad,true);
        assertThrows(IllegalStateException.class,()->InjectorExecution.invokeStatic(failed.walker,"populate"));
        Object after=add(failed);assertEquals(0,RegistryElementCallbacks.complete(failed.registry));assertEquals(0,calls(failed,after));
    }

    @Test void aFailedInitialWalkDoesNotPublishItsPartiallyCompletedCallbacks() throws Throwable {
        Loaded l=load(compile(LOOP));Object first=add(l),bad=add(l);l.state.getField("fail").setBoolean(bad,true);
        assertThrows(IllegalStateException.class,()->InjectorExecution.invokeStatic(l.walker,"populate"));
        Object late=add(l);assertEquals(0,RegistryElementCallbacks.complete(l.registry));assertEquals(1,calls(l,first));assertEquals(1,calls(l,bad));assertEquals(0,calls(l,late));
    }
    @Test void aLaterFailedReplayCanBeRetriedWithoutRepeatingSuccessfulElements() throws Throwable {
        Loaded l=load(compile(LOOP));Object first=add(l);InjectorExecution.invokeStatic(l.walker,"populate");Object bad=add(l);l.state.getField("fail").setBoolean(bad,true);
        assertThrows(IllegalStateException.class,()->RegistryElementCallbacks.complete(l.registry));assertEquals(1,calls(l,first));assertEquals(1,calls(l,bad));
        l.state.getField("fail").setBoolean(bad,false);assertEquals(1,RegistryElementCallbacks.complete(l.registry));assertEquals(1,calls(l,first));assertEquals(2,calls(l,bad));
    }
    @Test void reentrantCompletionAndNewRegistrationsDoNotRepeatTheInFlightElementOrMutateSnapshots() throws Throwable {
        Loaded l=load(compile(LOOP));add(l);InjectorExecution.invokeStatic(l.walker,"populate");Object late=add(l);List<Object> born=new ArrayList<>();
        l.state.getField("during").set(late,(Runnable)()->{
            assertEquals(0,RegistryElementCallbacks.complete(l.registry),"the active callback cannot recursively invoke itself");
            try{born.add(add(l));}catch(Throwable failure){throw new AssertionError(failure);}
        });
        assertEquals(1,RegistryElementCallbacks.complete(l.registry));assertEquals(1,calls(l,late));assertEquals(0,calls(l,born.getFirst()));
        assertEquals(1,RegistryElementCallbacks.complete(l.registry));assertEquals(1,calls(l,born.getFirst()));assertEquals(1,calls(l,late));
    }
    @Test void aCallbackCanJoinAWorkerThatChecksCompletionWithoutHoldingTheBookkeepingLock() throws Throwable {
        Loaded l=load(compile(LOOP));add(l);InjectorExecution.invokeStatic(l.walker,"populate");Object late=add(l);
        l.state.getField("during").set(late,(Runnable)()->{
            var worker=java.util.concurrent.CompletableFuture.supplyAsync(()->RegistryElementCallbacks.complete(l.registry));
            try{assertEquals(0,worker.get(5,java.util.concurrent.TimeUnit.SECONDS));}
            catch(Exception failure){throw new AssertionError("completion worker blocked by callback bookkeeping",failure);}
        });
        assertEquals(1,RegistryElementCallbacks.complete(l.registry));assertEquals(1,calls(l,late));
    }

    @Test void conditionsRepeatedCallbacksEarlyReturnsAndExtraWorkHaveNoClosedWalkProof() throws Exception {
        for(String body:List.of(
            "for(BlockState state:Block.BLOCK_STATE_REGISTRY) if(state==null) continue; else ((CacheElement)state).initializeDerivedState();",
            "for(BlockState state:Block.BLOCK_STATE_REGISTRY) if(state.calls==0)((CacheElement)state).initializeDerivedState();",
            "for(BlockState state:Block.BLOCK_STATE_REGISTRY){((CacheElement)state).initializeDerivedState();((CacheElement)state).initializeDerivedState();}",
            LOOP+LOOP,
            "if(CacheElement.class.isAssignableFrom(BlockState.class))return;"+LOOP,
            LOOP+"System.nanoTime();")){
            Map<String,byte[]> classes=compile(body);byte[] raw=classes.get(WALKER);
            assertSame(raw,InjectorExecution.transform(injector(classes),WALKER.replace('/','.'),raw,EnvType.SERVER),body);
        }
    }
    @Test void theActualTwoInterfaceInitializerMatchesWithoutModNamesOrVersionHashes() throws Exception {
        java.nio.file.Path jar=java.nio.file.Path.of("build/compat-inputs/sweep90/mods/lithium-fabric-0.25.3+mc26.2.jar");
        net.forbric.kernel.TestFixtures.require(net.forbric.kernel.TestFixtures.Fixture.THIRD_PARTY,java.nio.file.Files.isRegularFile(jar),"Lithium fixture absent");
        Map<String,byte[]> classes=new HashMap<>();
        try(java.util.zip.ZipFile zip=new java.util.zip.ZipFile(jar.toFile())){
            for(var entries=zip.entries();entries.hasMoreElements();){var entry=entries.nextElement();if(entry.getName().endsWith(".class"))classes.put(entry.getName().substring(0,entry.getName().length()-6),zip.getInputStream(entry).readAllBytes());}
        }
        String source="net/caffeinemc/mods/lithium/common/initialization/BlockInfoInitializer";byte[] raw=classes.get(source);
        byte[] edited=InjectorExecution.transform(injector(classes),source.replace('/','.'),raw,EnvType.SERVER);assertNotSame(raw,edited);
        ClassNode node=new ClassNode();new ClassReader(edited).accept(node,0);MethodNode initializer=node.methods.stream().filter(m->m.name.equals("initializeBlockInfo")).findFirst().orElseThrow();
        new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(node.name,initializer);
        List<String> hooks=java.util.Arrays.stream(initializer.instructions.toArray()).filter(i->i instanceof MethodInsnNode call&&call.owner.equals("net/forbric/kernel/interop/RegistryElementCallbacks")).map(i->((MethodInsnNode)i).name).toList();
        assertEquals(List.of("begin","declare","completed","declare","completed","commit"),hooks);
    }

    @Test void aWrongBackEdgeOrExitAndAnInaccessibleInterfaceAreNotGuessed() throws Exception {
        for(boolean back:new boolean[]{false,true}){
            Map<String,byte[]> classes=compile(LOOP);ClassNode n=new ClassNode();new ClassReader(classes.get(WALKER)).accept(n,0);
            MethodNode root=n.methods.stream().filter(m->m.name.equals("populate")).findFirst().orElseThrow();
            JumpInsnNode backEdge=null,empty=null;
            for(AbstractInsnNode insn:root.instructions){
                if(insn instanceof JumpInsnNode jump&&jump.getOpcode()==Opcodes.GOTO)backEdge=jump;
                if(insn instanceof JumpInsnNode jump&&jump.getOpcode()==Opcodes.IFEQ)empty=jump;
            }
            AbstractInsnNode exit=java.util.Arrays.stream(root.instructions.toArray()).filter(insn->insn.getOpcode()==Opcodes.RETURN).findFirst().orElseThrow();
            LabelNode end=new LabelNode();root.instructions.insertBefore(exit,end);
            if(back)backEdge.label=end;else empty.label=backEdge.label;
            ClassWriter w=new ClassWriter(0);n.accept(w);byte[] raw=w.toByteArray();
            assertSame(raw,InjectorExecution.transform(injector(classes),WALKER.replace('/','.'),raw,EnvType.SERVER));
        }
        Map<String,byte[]> classes=compile(LOOP);ClassNode contract=new ClassNode();new ClassReader(classes.get("unknown/cachecontract/CacheElement")).accept(contract,0);contract.access&=~Opcodes.ACC_PUBLIC;
        ClassWriter writer=new ClassWriter(0);contract.accept(writer);classes.put(contract.name,writer.toByteArray());byte[] raw=classes.get(WALKER);
        assertSame(raw,InjectorExecution.transform(injector(classes),WALKER.replace('/','.'),raw,EnvType.SERVER));
    }
}
