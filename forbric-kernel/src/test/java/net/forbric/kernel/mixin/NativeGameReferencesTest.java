/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import net.forbric.api.Ecosystem;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class NativeGameReferencesTest {
 private static final String OWNER="unlisted/PlatformClass",PREFIX="META-INF/forbric/native-reference/FABRIC/";
 @Test void originalBytesAreResourcesAndCannotBeConfusedWithTheMergedDefinition() throws Exception {
  byte[] original=bytes(OWNER,7),merged=bytes(OWNER,11);Map<String,byte[]> resources=index(original);
  resources.put(OWNER+".class",merged);resources.put(PREFIX+OWNER+".class.bin",original);
  var node=new NativeGameReferences(resources::get).get(Ecosystem.FABRIC,OWNER);
  assertNotNull(node);assertEquals(OWNER,node.name);
  assertEquals(7,((org.objectweb.asm.tree.LdcInsnNode)node.methods.getFirst().instructions.getFirst()).cst);
 }
 @Test void anUnchangedClassCanUseTheIndexedMergedBytes() throws Exception {
  byte[] source=bytes(OWNER,7);Map<String,byte[]> resources=index(source);resources.put(OWNER+".class",source);
  assertNotNull(new NativeGameReferences(resources::get).get(Ecosystem.FABRIC,OWNER));
 }
 @Test void aChangedClassWithoutItsOriginalBlobIsNotNativeEvidence() throws Exception {
  Map<String,byte[]> resources=index(bytes(OWNER,7));resources.put(OWNER+".class",bytes(OWNER,11));
  assertNull(new NativeGameReferences(resources::get).get(Ecosystem.FABRIC,OWNER));
 }
 @Test void tamperingOrAnOwnerMismatchNeverAuthorizesMigration() throws Exception {
  Map<String,byte[]> resources=index(bytes(OWNER,7));resources.put(PREFIX+OWNER+".class.bin",bytes(OWNER,11));
  assertNull(new NativeGameReferences(resources::get).get(Ecosystem.FABRIC,OWNER));
  byte[] wrong=bytes("unlisted/AnotherClass",7);resources=index(wrong);resources.put(PREFIX+OWNER+".class.bin",wrong);
  assertNull(new NativeGameReferences(resources::get).get(Ecosystem.FABRIC,OWNER));
 }
 @Test void missingUnknownAndDuplicateIndexesDoNotGuess() throws Exception {
  assertNull(new NativeGameReferences(p->null).get(Ecosystem.FABRIC,OWNER));
  Map<String,byte[]> resources=index(bytes(OWNER,7));
  byte[] index=resources.get(PREFIX+"index.tsv");resources.put(PREFIX+"index.tsv",("# unsupported\n"+new String(index,StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
  assertNull(new NativeGameReferences(resources::get).get(Ecosystem.FABRIC,OWNER));
  resources=index(bytes(OWNER,7));String text=new String(resources.get(PREFIX+"index.tsv"),StandardCharsets.UTF_8);
  resources.put(PREFIX+"index.tsv",(text+text.substring(text.indexOf('\n')+1)).getBytes(StandardCharsets.UTF_8));
  assertNull(new NativeGameReferences(resources::get).get(Ecosystem.FABRIC,OWNER));
 }
 private static Map<String,byte[]> index(byte[] source) throws Exception {
  Map<String,byte[]> result=new HashMap<>();String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
  result.put(PREFIX+"index.tsv",("# forbric-native-reference-v1\n"+OWNER+"\t"+hash+"\n").getBytes(StandardCharsets.UTF_8));return result;
 }
 private static byte[] bytes(String owner,int value){
  ClassWriter writer=new ClassWriter(0);writer.visit(Opcodes.V21,Opcodes.ACC_PUBLIC,owner,null,"java/lang/Object",null);
  var method=writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"value","()I",null,null);
  method.visitCode();method.visitLdcInsn(value);method.visitInsn(Opcodes.IRETURN);method.visitMaxs(1,0);method.visitEnd();writer.visitEnd();return writer.toByteArray();
 }
}
