/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

class InvocationOrdinalsTest {
 private static final String OWNER="unknown/Owner",SHORT="(I)Ljava/lang/Object;",WIDE="(II)Ljava/lang/Object;";
 private static final MixinAtWidenedCall.Member MEMBER=new MixinAtWidenedCall.Member(OWNER,"produce",SHORT);
 @Test void anOrdinalFollowsItsValueSinkEvenWhenTwoSitesChangeOrder(){
  MethodNode original=method(SHORT,"one","two"),current=method(WIDE,"two","one");
  assertEquals(1,InvocationOrdinals.correspondence(OWNER,original,current,MEMBER,WIDE,0));
  assertEquals(0,InvocationOrdinals.correspondence(OWNER,original,current,MEMBER,WIDE,1));
 }
 @Test void missingAdditionalOrAmbiguousSinksCannotAuthorizeAnOrdinal(){
  MethodNode original=method(SHORT,"one","two");
  assertNull(InvocationOrdinals.correspondence(OWNER,original,method(WIDE,"one","extra"),MEMBER,WIDE,0));
  assertNull(InvocationOrdinals.correspondence(OWNER,original,method(WIDE,"one","two","extra"),MEMBER,WIDE,0));
  assertNull(InvocationOrdinals.correspondence(OWNER,original,method(WIDE,"one","one"),MEMBER,WIDE,0));
  assertNull(InvocationOrdinals.correspondence(OWNER,original,method(WIDE,"one","two"),MEMBER,WIDE,2));
 }
 @Test void aDiscardedValueIsNotAStableSourceSink(){
  MethodNode current=method(WIDE,"one","two");
  for(var op:current.instructions)if(op instanceof FieldInsnNode f&&f.name.equals("one")){current.instructions.set(op,new InsnNode(Opcodes.POP));break;}
  assertNull(InvocationOrdinals.correspondence(OWNER,method(SHORT,"one","two"),current,MEMBER,WIDE,0));
 }
 @Test void theActualPayloadOrdinalsKeepTheirOriginalProtocolFields() throws Exception {
  var vanilla=StagedFabricMixinFixture.game("net/minecraft/network/protocol/common/ClientboundCustomPayloadPacket",true);
  var merged=StagedFabricMixinFixture.game("net/minecraft/network/protocol/common/ClientboundCustomPayloadPacket",false);
  MethodNode original=StagedFabricMixinFixture.method(vanilla,"<clinit>"),current=StagedFabricMixinFixture.method(merged,"<clinit>");
  var named=MixinAtWidenedCall.parse("Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;codec(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload$FallbackProvider;Ljava/util/List;)Lnet/minecraft/network/codec/StreamCodec;");
  String extended="(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload$FallbackProvider;Ljava/util/List;Lnet/minecraft/network/ConnectionProtocol;Lnet/minecraft/network/protocol/PacketFlow;)Lnet/minecraft/network/codec/StreamCodec;";
  assertEquals(0,InvocationOrdinals.correspondence(merged.name,original,current,named,extended,0));
  assertEquals(1,InvocationOrdinals.correspondence(merged.name,original,current,named,extended,1));
 }
 private static MethodNode method(String desc,String...sinks){
  MethodNode m=new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"<clinit>","()V",null,null);
  for(String sink:sinks){m.instructions.add(new InsnNode(Opcodes.ICONST_1));if(desc.equals(WIDE))m.instructions.add(new InsnNode(Opcodes.ICONST_2));
   m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,OWNER,"produce",desc,false));
   m.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC,OWNER,sink,"Ljava/lang/Object;"));}
  m.instructions.add(new InsnNode(Opcodes.RETURN));m.maxStack=4;return m;
 }
}
