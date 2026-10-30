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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
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
 @Test @ResourceLock("native-game-references")
 void runtimeEvidenceUsesItsOwnedFamilyAndRawBytesWithoutDefiningTheClass(@TempDir java.nio.file.Path work) throws Exception {
  java.nio.file.Path jar=work.resolve("platform.jar");byte[] raw=bytes(OWNER,7),current=bytes(OWNER,11);
  try(var out=new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(jar))){out.putNextEntry(new java.util.zip.ZipEntry(OWNER+".class"));out.write(raw);out.closeEntry();}
  try(var loader=new net.forbric.kernel.classloading.ForbricClassLoader(new java.net.URL[]{jar.toUri().toURL()},getClass().getClassLoader())){
   loader.setTransformer((name,input)->current);NativeGameReferences.bind(loader);
   assertNull(NativeGameReferences.runtime(Ecosystem.NEOFORGE,OWNER),"unclassified resources are not platform evidence");
   loader.addRuntimeJarFamily(jar,net.forbric.kernel.classloading.LoaderProbePolicy.Family.NEOFORGE);
   var original=NativeGameReferences.runtime(Ecosystem.NEOFORGE,OWNER);var transformed=NativeGameReferences.current(OWNER);
   assertNotNull(original);assertNotNull(transformed);
   assertEquals(7,((org.objectweb.asm.tree.LdcInsnNode)original.methods.getFirst().instructions.getFirst()).cst);
   assertEquals(11,((org.objectweb.asm.tree.LdcInsnNode)transformed.methods.getFirst().instructions.getFirst()).cst);
   assertNull(NativeGameReferences.runtime(Ecosystem.FORGE,OWNER));assertNull(NativeGameReferences.reference(Ecosystem.NEOFORGE,OWNER),"raw external helpers cannot impersonate indexed game snapshots");
   assertFalse(loader.isClassLoadedByName(OWNER.replace('/','.')));
  }finally{NativeGameReferences.bind(null);}
 }
 /** The vanilla-reference sidecar answers FABRIC: its copies for what the base changed, the base itself for the rest. */
 @Test void theSidecarAnswersFabricInPreferenceToTheEmbeddedIndex(@TempDir java.nio.file.Path work) throws Exception {
  String same="unlisted/Same";byte[] original=bytes(OWNER,7),merged=bytes(OWNER,11),unchanged=bytes(same,3);
  Map<String,byte[]> sidecarEntries=new HashMap<>();
  sidecarEntries.put(PREFIX+"index.tsv",("# forbric-native-reference-v1\n"+OWNER+"\t"+sha(original)+"\n"+same+"\t"+sha(unchanged)+"\n").getBytes(StandardCharsets.UTF_8));
  sidecarEntries.put(PREFIX+OWNER+".class.bin",original);
  // The base embeds a FABRIC index that disagrees, which the sidecar must win over, and a NEOFORGE one it must keep.
  Map<String,byte[]> baseEntries=new HashMap<>(index(bytes(OWNER,99)));baseEntries.put(PREFIX+OWNER+".class.bin",bytes(OWNER,99));
  baseEntries.put(OWNER+".class",merged);baseEntries.put(same+".class",unchanged);
  baseEntries.put("META-INF/forbric/native-reference/NEOFORGE/index.tsv",("# forbric-native-reference-v1\n"+OWNER+"\t"+sha(merged)+"\n").getBytes(StandardCharsets.UTF_8));
  java.nio.file.Path base=jar(work.resolve("base.jar"),baseEntries),sidecar=jar(work.resolve("sidecar.jar"),sidecarEntries);
  NativeReferenceSources sources=NativeReferenceSources.open(base,sidecar);
  assertTrue(sources.describe().contains("native references: FABRIC from the vanilla-reference sidecar sidecar.jar, FORGE none, NEOFORGE from the base's embedded index"),sources.describe());
  var reader=new NativeGameReferences(baseEntries::get,null,sources);
  assertEquals(7,constant(reader.get(Ecosystem.FABRIC,OWNER)),"the sidecar's copy, not the embedded one");
  assertEquals(3,constant(reader.get(Ecosystem.FABRIC,same)),"a hash-only row is answered from the base and verified");
  assertEquals(11,constant(reader.get(Ecosystem.NEOFORGE,OWNER)),"the base keeps answering for what only it carries");
  assertEquals(99,constant(new NativeGameReferences(baseEntries::get).get(Ecosystem.FABRIC,OWNER)),"without sources: the embedded index");
  // Measured against another base: a hash-only row whose class this base changed is no answer, never a wrong one.
  baseEntries.put(same+".class",bytes(same,4));java.nio.file.Path other=jar(work.resolve("other.jar"),baseEntries);
  assertNull(new NativeGameReferences(baseEntries::get,null,NativeReferenceSources.open(other,sidecar)).get(Ecosystem.FABRIC,same));
 }
 /** A base with no index answers for its own ecosystem from its own bytes; which one is read off its Block. */
 @Test void aBaseWithoutAnIndexAnswersForItsOwnEcosystemFromItsBytes(@TempDir java.nio.file.Path work) throws Exception {
  String block="net/minecraft/world/level/block/Block";
  Map<String,String[]> cases=new java.util.LinkedHashMap<>();
  cases.put("NEOFORGE",new String[]{"net/minecraft/world/level/ItemLike","net/neoforged/neoforge/common/extensions/IBlockExtension"});
  cases.put("FORGE",new String[]{"net/minecraftforge/common/extensions/IForgeBlock"});
  cases.put("FABRIC",new String[]{"net/minecraft/world/level/ItemLike"});
  cases.put("none",new String[]{"net/neoforged/neoforge/common/extensions/IBlockExtension","net/minecraftforge/common/extensions/IForgeBlock"});
  for(var c:cases.entrySet()){
   Map<String,byte[]> entries=new HashMap<>();entries.put(block+".class",type(block,c.getValue()));entries.put(OWNER+".class",bytes(OWNER,5));
   java.nio.file.Path base=jar(work.resolve(c.getKey()+".jar"),entries);NativeReferenceSources sources=NativeReferenceSources.open(base,null);
   var reader=new NativeGameReferences(entries::get,null,sources);
   for(Ecosystem ecosystem:Ecosystem.values()){
    boolean own=ecosystem.name().equals(c.getKey());
    assertEquals(own,reader.get(ecosystem,OWNER)!=null,c.getKey()+" base, "+ecosystem);
    assertEquals(own,java.util.regex.Pattern.compile("\\b"+ecosystem.name()+" from the base's own bytes").matcher(sources.describe()).find(),sources.describe());
   }
  }
  // A base that embeds any index is the merge: it answers through the index, never through its bytes.
  Map<String,byte[]> entries=new HashMap<>(index(bytes(OWNER,7)));entries.put(block+".class",type(block,cases.get("NEOFORGE")));entries.put(OWNER+".class",bytes(OWNER,5));
  var reader=new NativeGameReferences(entries::get,null,NativeReferenceSources.open(jar(work.resolve("merged.jar"),entries),null));
  assertNull(reader.get(Ecosystem.NEOFORGE,OWNER));assertNull(reader.get(Ecosystem.FABRIC,OWNER),"the embedded row does not verify against the base bytes");
 }
 private static int constant(org.objectweb.asm.tree.ClassNode node){assertNotNull(node);return (Integer)((org.objectweb.asm.tree.LdcInsnNode)node.methods.getFirst().instructions.getFirst()).cst;}
 private static String sha(byte[] bytes) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
 private static byte[] type(String name,String[] interfaces){ClassWriter writer=new ClassWriter(0);writer.visit(Opcodes.V21,Opcodes.ACC_PUBLIC,name,null,"java/lang/Object",interfaces);writer.visitEnd();return writer.toByteArray();}
 private static java.nio.file.Path jar(java.nio.file.Path file,Map<String,byte[]> entries) throws Exception {
  try(var out=new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(file))){for(var e:entries.entrySet()){out.putNextEntry(new java.util.zip.ZipEntry(e.getKey()));out.write(e.getValue());out.closeEntry();}}return file;
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
