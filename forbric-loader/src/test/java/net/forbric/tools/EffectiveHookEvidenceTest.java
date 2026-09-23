package net.forbric.tools;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import static org.junit.jupiter.api.Assertions.*;

class EffectiveHookEvidenceTest {
 @TempDir Path root;
 private static final String HOOK="net/minecraftforge/common/ForgeHooks";
 @Test void separatesRestoredDirectHelperResidualAndUnobserved() throws Exception {
  manifest();
  put("game/Direct",HOOK,Opcodes.INVOKESTATIC);
  put("game/Helper","net/forbric/kernel/runtime/Helper",Opcodes.INVOKESTATIC);
  put("net/forbric/kernel/runtime/Helper",HOOK,Opcodes.INVOKESTATIC);
  put("game/Residual",null,0);
  put("game/Virtual","net/forbric/kernel/runtime/Helper",Opcodes.INVOKEVIRTUAL);
  var e=new EffectiveHookEvidence(root);String hook=HOOK+"#tick()V";
  assertEquals(EffectiveHookEvidence.State.DIRECT_RESTORED,e.state("game/Direct#tick()V",hook));
  assertEquals(EffectiveHookEvidence.State.VIA_DEFINED_HELPER,e.state("game/Helper#tick()V",hook));
  // The helper is wired into game/Helper by a static call, so it restores that caller and no other.
  assertEquals(EffectiveHookEvidence.State.OBSERVED_WITHOUT_HOOK,e.state("game/Residual#tick()V",hook));
  assertEquals(EffectiveHookEvidence.State.OBSERVED_WITHOUT_HOOK,e.state("game/Virtual#tick()V",hook));
  assertEquals(EffectiveHookEvidence.State.UNOBSERVED,e.state("game/NeverLoaded#tick()V",hook));
  assertEquals(EffectiveHookEvidence.State.OBSERVED_WITHOUT_HOOK,e.state("game/Direct#deleted()V",hook));
 }
 @Test void missingEmptyAndChangedEvidenceAreRejected() throws Exception {
  assertThrows(java.io.IOException.class,()->new EffectiveHookEvidence(root));manifest();
  assertThrows(java.io.IOException.class,()->new EffectiveHookEvidence(root));
  String hash=put("game/A",HOOK,Opcodes.INVOKESTATIC);
  Files.write(root.resolve("blobs").resolve(hash+".class"),new byte[]{0});
  assertThrows(java.io.IOException.class,()->new EffectiveHookEvidence(root));
 }
 @Test void aV1PerNameSessionIsNotReadAsTheContentAddressedFormat() throws Exception {
  Files.writeString(root.resolve("definitions.tsv"),"# forbric-defined-classes-v1\n");
  put("game/A",HOOK,Opcodes.INVOKESTATIC);
  var failure=assertThrows(java.io.IOException.class,()->new EffectiveHookEvidence(root));
  assertTrue(failure.getMessage().contains(EffectiveHookEvidence.HEADER),failure.getMessage());
 }
 /** The kernel marks a session whose record it could not write; judging it anyway reports that class UNOBSERVED. */
 @Test void anIncompleteSessionIsRefusedWhole() throws Exception {
  manifest();put("game/A",HOOK,Opcodes.INVOKESTATIC);
  new EffectiveHookEvidence(root);
  Files.writeString(root.resolve("definitions.tsv"),"#incomplete\tgame/B\tjava.nio.file.AccessDeniedException\n",StandardOpenOption.APPEND);
  var failure=assertThrows(java.io.IOException.class,()->new EffectiveHookEvidence(root));
  assertTrue(failure.getMessage().contains("game/B"),failure.getMessage());
 }
 /**
  * The kernel lost a record and could not even write the {@code #incomplete} row (a full disk, a read-only
  * manifest). The rows it has are valid, so this is the session exactly as it looks then -- and accepting it
  * reports the lost class UNOBSERVED and builds the helper set without its calls. The only thing left to say so
  * is the marker the kernel withdrew.
  */
 @Test void aSessionThatNoLongerVouchesForItselfIsRefused() throws Exception {
  manifest();put("game/Early",HOOK,Opcodes.INVOKESTATIC);
  new EffectiveHookEvidence(root);
  Files.delete(root.resolve(EffectiveHookEvidence.INTACT));
  var failure=assertThrows(java.io.IOException.class,()->new EffectiveHookEvidence(root));
  assertTrue(failure.getMessage().contains(EffectiveHookEvidence.INTACT),failure.getMessage());
 }
 @Test void namesThatDifferOnlyInCaseAreDistinctDefinitions() throws Exception {
  manifest();put("game/a",HOOK,Opcodes.INVOKESTATIC);put("game/A",null,0);
  var e=new EffectiveHookEvidence(root);
  assertEquals(EffectiveHookEvidence.State.DIRECT_RESTORED,e.state("game/a#tick()V",HOOK+"#tick()V"));
  assertEquals(EffectiveHookEvidence.State.OBSERVED_WITHOUT_HOOK,e.state("game/A#tick()V",HOOK+"#tick()V"));
 }
 @Test void helperCyclesAndUnobservedHelpersDoNotBecomeProof() throws Exception {
  manifest();put("game/A","net/forbric/kernel/runtime/Cycle",Opcodes.INVOKESTATIC);
  put("net/forbric/kernel/runtime/Cycle","net/forbric/kernel/runtime/Cycle",Opcodes.INVOKESTATIC);
  put("game/B","net/forbric/kernel/runtime/Unknown",Opcodes.INVOKESTATIC);
  var e=new EffectiveHookEvidence(root);
  for(String name:new String[]{"game/A","game/B"})assertEquals(EffectiveHookEvidence.State.OBSERVED_WITHOUT_HOOK,e.state(name+"#tick()V",HOOK+"#tick()V"));
 }
 /** A defined boot or interop helper is as exact a static route as a runtime one. */
 @Test void staticHelpersOutsideTheRuntimePackageAreFollowed() throws Exception {
  manifest();put("game/Boot","net/forbric/kernel/interop/Relay",Opcodes.INVOKESTATIC);
  put("net/forbric/kernel/interop/Relay",HOOK,Opcodes.INVOKESTATIC);
  assertEquals(EffectiveHookEvidence.State.VIA_DEFINED_HELPER,new EffectiveHookEvidence(root).state("game/Boot#tick()V",HOOK+"#tick()V"));
 }
 /**
  * An event-bus forward: a kernel method no game class calls statically (a listener lambda) fires the lost hook.
  * It is reported apart from residual loss, and a game class calling the same hook elsewhere is not a forward.
  */
 @Test void aKernelForwardIsReportedApartFromResidualLoss() throws Exception {
  manifest();put("game/Teleport",null,0);put("game/Other",HOOK,Opcodes.INVOKESTATIC);
  assertEquals(EffectiveHookEvidence.State.OBSERVED_WITHOUT_HOOK,new EffectiveHookEvidence(root).state("game/Teleport#tick()V",HOOK+"#tick()V"));
  put("net/forbric/kernel/runtime/KernelGamePlayerEvents",HOOK,Opcodes.INVOKESTATIC);
  var e=new EffectiveHookEvidence(root);
  assertEquals(EffectiveHookEvidence.State.VIA_KERNEL_BRIDGE,e.state("game/Teleport#tick()V",HOOK+"#tick()V"));
  assertEquals(EffectiveHookEvidence.State.DIRECT_RESTORED,e.state("game/Other#tick()V",HOOK+"#tick()V"));
  assertEquals(EffectiveHookEvidence.State.OBSERVED_WITHOUT_HOOK,e.state("game/Teleport#tick()V",HOOK+"#other()V"));
 }
 /** A census row carries the source's occurrence count: one surviving call of two is not a restoration. */
 @Test void aPartialLossIsNotRestoredByTheCallTheMergeKept() throws Exception {
  manifest();put("game/Partial",HOOK,Opcodes.INVOKESTATIC);
  var e=new EffectiveHookEvidence(root);
  assertEquals(EffectiveHookEvidence.State.OBSERVED_WITHOUT_HOOK,
    e.state("game/Partial#tick()V",HOOK+"#tick()V",Map.of("INVOKESTATIC/itf=false",2)));
  assertEquals(EffectiveHookEvidence.State.DIRECT_RESTORED,
    e.state("game/Partial#tick()V",HOOK+"#tick()V",Map.of("INVOKESTATIC/itf=false",1)));
  assertEquals(EffectiveHookEvidence.State.OBSERVED_WITHOUT_HOOK,
    e.state("game/Partial#tick()V",HOOK+"#tick()V",Map.of("INVOKEVIRTUAL/itf=false",1)),"a changed invocation form is not the same call");
 }
 private void manifest() throws Exception {
  Files.writeString(root.resolve("definitions.tsv"),EffectiveHookEvidence.HEADER+"\n");
  Files.createFile(root.resolve(EffectiveHookEvidence.INTACT));
 }
 private String put(String name,String target,int opcode) throws Exception {
  ClassWriter w=new ClassWriter(0);w.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,name,null,"java/lang/Object",null);
  var m=w.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"tick","()V",null,null);m.visitCode();
  if(target!=null)m.visitMethodInsn(opcode,target,"tick","()V",false);
  m.visitInsn(Opcodes.RETURN);m.visitMaxs(1,0);m.visitEnd();w.visitEnd();byte[] bytes=w.toByteArray();
  String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  Path p=root.resolve("blobs").resolve(hash+".class");Files.createDirectories(p.getParent());Files.write(p,bytes);
  Files.writeString(root.resolve("definitions.tsv"),name+"\t"+hash+"\n",StandardOpenOption.APPEND);
  return hash;
 }
}
