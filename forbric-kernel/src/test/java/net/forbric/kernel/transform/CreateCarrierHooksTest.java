/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;import java.util.*;import net.forbric.kernel.TestFixtures;import net.forbric.kernel.TestFixtures.Fixture;import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;import org.objectweb.asm.tree.*;import org.objectweb.asm.tree.analysis.*;

class CreateCarrierHooksTest {
	@Test void realNativeCarrierHooksRemainVerifiableAndAreIdempotent()throws Exception{
		for(var row:List.of(new Object[]{new BreathingCallbackInjector(),BreathingCallbackInjector.TARGET,"neoforge-runtime/neoforge-runtime.jar",2},new Object[]{new BlockSoundQueryInjector(),BlockSoundQueryInjector.TARGET,"neoforge-runtime/neoforge-runtime.jar",2},new Object[]{new HudContextQueryInjector(),HudContextQueryInjector.TARGET,"merged-base/patched-mc-merged-26.2.jar",1})){
			Path jar=TestFixtures.stagedRoot().resolve((String)row[2]);byte[] original=TestFixtures.requireEntry(Fixture.STAGED,jar,((String)row[1]).replace('.','/')+".class");
			ClassTransformer transformer=(ClassTransformer)row[0];byte[] patched=transformer.transform((String)row[1],original,null);assertNotSame(original,patched);assertSame(patched,transformer.transform((String)row[1],patched,null));ClassNode node=new ClassNode();new ClassReader(patched).accept(node,0);int count=0;
			for(var method:node.methods){if((method.access&(Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE))==0)new Analyzer<>(new BasicVerifier()).analyze(node.name,method);for(var instruction:method.instructions)if(instruction instanceof MethodInsnNode call&&(call.owner.equals("net/forbric/kernel/interop/BreathingCallbackScope")||call.owner.equals("net/forbric/kernel/runtime/KernelBlockSoundQuery")||call.owner.equals("net/forbric/kernel/runtime/KernelHudContextQuery")))count++;}
			assertEquals(row[3],count,(String)row[1]);
		}
	}
}
