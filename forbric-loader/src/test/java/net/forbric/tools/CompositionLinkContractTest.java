/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.tools;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

class CompositionLinkContractTest {
    @TempDir Path directory;
    @Test void anActualRemovedAncestorMemberIsAnExplicitCompositionObligation()throws Exception{
        Object checker=check("nativeAction","unknown/Removed");Method check=MergedLinkChecker.class.getDeclaredMethod("check");check.setAccessible(true);assertEquals(List.of(),check.invoke(checker));Field references=MergedLinkChecker.class.getDeclaredField("compositionReferences");references.setAccessible(true);assertEquals(Set.of("unknown/Game.nativeAction()V requires unknown/Game native ancestor unknown/Removed"),references.get(checker));
    }
    @Test void anUnknownMemberStillFailsTheLinkContract()throws Exception{
        Object checker=check("missingAction","unknown/Removed");Method check=MergedLinkChecker.class.getDeclaredMethod("check");check.setAccessible(true);List<?> missing=(List<?>)check.invoke(checker);assertEquals(1,missing.size());assertTrue(missing.get(0).toString().contains("METHOD unknown/Game.missingAction()V"));
    }
    @Test void aManifestCannotInventAnAncestorThatTheOriginalDefinitionDidNotUse(){InvocationTargetException failure=assertThrows(InvocationTargetException.class,()->check("nativeAction","unknown/Invented"));assertInstanceOf(java.io.IOException.class,failure.getCause());assertTrue(failure.getCause().getMessage().contains("differs from actual definitions"));}
    private Object check(String method,String removed)throws Exception{
        ClassNode game=node("unknown/Game","unknown/Retained");MethodNode body=new MethodNode(Opcodes.ACC_PUBLIC,"run","()V",null,null);body.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));body.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,game.name,method,"()V",false));body.instructions.add(new InsnNode(Opcodes.RETURN));game.methods.add(body);
        ClassNode nativeGame=node(game.name,"unknown/Removed"),retained=node("unknown/Retained","java/lang/Object"),ancestor=node("unknown/Removed","java/lang/Object");MethodNode original=new MethodNode(Opcodes.ACC_PUBLIC,"nativeAction","()V",null,null);original.instructions.add(new InsnNode(Opcodes.RETURN));ancestor.methods.add(original);
        Path merged=jar("merged.jar",Map.of(game.name+".class",bytes(game),"META-INF/forbric/native-reference/FORGE/"+game.name+".class.bin",bytes(nativeGame),"META-INF/forbric/required-ancestor-compositions.tsv",("# forbric-required-ancestor-composition-v1\n"+game.name+"\t"+game.superName+"\t"+removed+"\n").getBytes(StandardCharsets.UTF_8))),runtime=jar("runtime.jar",Map.of(retained.name+".class",bytes(retained),ancestor.name+".class",bytes(ancestor)));
        Constructor<?> constructor=MergedLinkChecker.class.getDeclaredConstructor();constructor.setAccessible(true);Object checker=constructor.newInstance();Method load=MergedLinkChecker.class.getDeclaredMethod("loadPath",String.class,boolean.class);load.setAccessible(true);load.invoke(checker,merged.toString(),true);load.invoke(checker,runtime.toString(),false);return checker;
    }
    private Path jar(String name,Map<String,byte[]> entries)throws Exception{Path file=directory.resolve(name);try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(file))){for(var entry:entries.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}}return file;}
    private static ClassNode node(String name,String parent){ClassNode node=new ClassNode();node.version=Opcodes.V17;node.access=Opcodes.ACC_PUBLIC;node.name=name;node.superName=parent;return node;}
    private static byte[] bytes(ClassNode node){ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);return writer.toByteArray();}
}
