/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

class ServerReloadListenerNamesInjectorTest {
	@Test void realServerEventNamesForeignListenersButKeepsTheNativeGuardAndGraph() throws Exception {
		Path jar = TestFixtures.stagedRoot().resolve(TestFixtures.NEO_CARRIER_JAR);
		byte[] original = TestFixtures.requireEntry(Fixture.STAGED, jar, "net/neoforged/neoforge/event/AddServerReloadListenersEvent.class");
		var transform = new ServerReloadListenerNamesInjector();
		byte[] patched = transform.transform(ServerReloadListenerNamesInjector.TARGET, original, null);
		assertNotSame(original, patched); assertSame(patched, transform.transform(ServerReloadListenerNamesInjector.TARGET, patched, null));
		ClassNode node = new ClassNode();new ClassReader(patched).accept(node,0);
		int names=0,throwsRemaining=0;
		for(var method:node.methods){
			new Analyzer<>(new BasicVerifier()).analyze(node.name,method);
			for(var instruction:method.instructions){
				if(instruction.getOpcode()==Opcodes.ATHROW)throwsRemaining++;
				if(instruction instanceof MethodInsnNode call&&call.owner.equals("net/forbric/kernel/runtime/KernelServerReloadNames"))names++;
			}
		}
		assertEquals(1,names); assertTrue(throwsRemaining>0);
	}
}
