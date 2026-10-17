/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * A connection's channel declarations are each sent once per phase: a {@code minecraft:register} naming only channels the
 * side already declared on that connection in the current phase is left out, whichever ecosystem sends it; the first
 * declaration, one carrying any new channel, an unregister and a new phase all go out as before.
 */
class ChannelDeclarationsOnceTest {
	private static Set<String> set(String... ids) {
		return new LinkedHashSet<>(List.of(ids));
	}

	@Test void aRepeatedDeclarationIsLeftOutAndOnlyThat() {
		Object connection = new Object();
		assertFalse(PayloadInterop.repeatsWhatWasDeclared(connection, "CONFIGURATION", true, set("c:version", "fabric:sync")), "the first goes out");
		assertTrue(PayloadInterop.repeatsWhatWasDeclared(connection, "CONFIGURATION", true, set("fabric:sync", "c:version")), "the same again does not");
		assertTrue(PayloadInterop.repeatsWhatWasDeclared(connection, "CONFIGURATION", true, set("c:version")), "a part of it neither");
		assertFalse(PayloadInterop.repeatsWhatWasDeclared(connection, "CONFIGURATION", true, set("c:version", "forge:login")),
				"one new channel and it goes out, whole");
		assertTrue(PayloadInterop.repeatsWhatWasDeclared(connection, "CONFIGURATION", true, set("forge:login")));
		assertFalse(PayloadInterop.repeatsWhatWasDeclared(connection, "CONFIGURATION", false, set("forge:login")), "an unregister goes out");
		assertFalse(PayloadInterop.repeatsWhatWasDeclared(connection, "CONFIGURATION", true, set("forge:login")),
				"and what it took away is declared again when asked");
		assertFalse(PayloadInterop.repeatsWhatWasDeclared(connection, "PLAY", true, set("c:version")), "a new phase starts afresh");
		assertFalse(PayloadInterop.repeatsWhatWasDeclared(new Object(), "PLAY", true, set("c:version")), "another connection has its own record");
		assertFalse(PayloadInterop.repeatsWhatWasDeclared(connection, "PLAY", true, set()), "an empty declaration is not judged");
	}

	/** The send's head asks before anything else, and goes on as shipped when the answer is no. */
	@Test void theSendAsksFirstAndGoesOnAsShipped() throws Exception {
		ClassNode connection = new ClassNode();
		connection.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "net/minecraft/network/Connection", null, "java/lang/Object", null);
		MethodNode send = (MethodNode) connection.visitMethod(Opcodes.ACC_PRIVATE, "sendPacket",
				"(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", null, null);
		send.instructions.add(new InsnNode(Opcodes.RETURN));
		send.maxLocals = 4;
		ClassWriter plain = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
			@Override protected String getCommonSuperClass(String a, String b) { return "java/lang/Object"; }
		};
		connection.accept(plain);
		ClassNode read = new ClassNode();
		new ClassReader(plain.toByteArray()).accept(read, ClassReader.EXPAND_FRAMES);
		MethodNode method = read.methods.stream().filter(m -> m.name.equals("sendPacket")).findFirst().orElseThrow();
		assertTrue(net.forbric.kernel.transform.CommonNetworkInteropInjectorAccess.dropRepeatedDeclarations(read, method));
		AbstractInsnNode first = method.instructions.getFirst();
		while (first.getOpcode() < 0) first = first.getNext();
		first = first.getNext().getNext();
		assertTrue(first instanceof MethodInsnNode call && call.name.equals("declarationAlreadyMade"), "the hook is the first call");
		method.maxStack = Math.max(method.maxStack, 2);   // the transformer raises it to the hook's two operands
		new Analyzer<>(new BasicVerifier()).analyze(read.name, method);
		assertFalse(net.forbric.kernel.transform.CommonNetworkInteropInjectorAccess.dropRepeatedDeclarations(read, method), "once only");
	}
}
