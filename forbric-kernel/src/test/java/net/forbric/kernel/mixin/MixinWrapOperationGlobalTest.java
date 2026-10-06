/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

class MixinWrapOperationGlobalTest {
 private static final String OP="Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;";
 @Test void arbitraryGuestNamesAndRepeatedPrefixTypesPreserveTheNativeExtraArgument() throws Exception {
  ClassNode mixin=mixin("unlisted/Guest", "intercept");ClassNode target=target("(III)Ljava/lang/String;");
  assertEquals(1,MixinWrapOperationShim.adapt(mixin,n->target));
  MethodNode outer=mixin.methods.stream().filter(m->m.name.equals("intercept")).findFirst().orElseThrow();
  assertEquals("(III"+OP+")Ljava/lang/String;",outer.desc);
  assertTrue(java.util.Arrays.stream(outer.instructions.toArray()).anyMatch(i->i instanceof LdcInsnNode l&&l.cst.equals("0,1")));
  new Analyzer<>(new BasicVerifier()).analyze(mixin.name,outer);
  assertNotNull(mixin.methods.stream().filter(m->m.name.equals("intercept"+MixinHandlerShim.INNER_SUFFIX)).findFirst().orElse(null));
  assertEquals(0,MixinWrapOperationShim.adapt(mixin,n->target));
 }
 @Test void twoExtendedOverloadsAreAmbiguousAndStayUnmodified() {
  ClassNode target=target("(III)Ljava/lang/String;");
  target.methods.getFirst().instructions.insertBefore(target.methods.getFirst().instructions.getFirst(),
    new MethodInsnNode(Opcodes.INVOKESTATIC,"fixture/Owner","operation","(IIII)Ljava/lang/String;",false));
  assertEquals(0,MixinWrapOperationShim.adapt(mixin("another/Guest","otherName"),n->target));
 }
 @Test void anExistingOriginalCallNeverMovesToAnotherOverload() {
  ClassNode target=target("(III)Ljava/lang/String;");
  target.methods.getFirst().instructions.insertBefore(target.methods.getFirst().instructions.getFirst(),
    new MethodInsnNode(Opcodes.INVOKESTATIC,"fixture/Owner","operation","(II)Ljava/lang/String;",false));
  assertEquals(0,MixinWrapOperationShim.adapt(mixin("unlisted/Guest","intercept"),n->target));
 }
 @Test void callbackGroupAlternativesStayUnderTheAuthorsOwnCount() {
  ClassNode mixin=mixin("unlisted/Guest","intercept");
  mixin.methods.getFirst().visibleAnnotations.add(new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Group;"));
  assertEquals(0,MixinWrapOperationShim.adapt(mixin,n->target("(III)Ljava/lang/String;")));
 }
 @Test void onlyAnExactPrefixCanDisambiguateRepeatedTypes() {
  assertArrayEquals(new int[]{0,1},MixinWrapOperationShim.argumentMapping(new Type[]{Type.INT_TYPE,Type.INT_TYPE},
    new Type[]{Type.INT_TYPE,Type.INT_TYPE,Type.INT_TYPE}));
  assertNull(MixinWrapOperationShim.argumentMapping(new Type[]{Type.INT_TYPE,Type.INT_TYPE},
    new Type[]{Type.getObjectType("java/lang/String"),Type.INT_TYPE,Type.INT_TYPE}));
 }
 private static ClassNode mixin(String name,String handlerName) {
  ClassNode n=new ClassNode();n.name=name;n.version=Opcodes.V21;n.superName="java/lang/Object";
  AnnotationNode mixin=new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");mixin.values=new ArrayList<>(List.of("targets",List.of("fixture.Target")));n.visibleAnnotations=List.of(mixin);
  MethodNode h=new MethodNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC,handlerName,"(II"+OP+")Ljava/lang/String;",null,null);
  AnnotationNode at=new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");at.values=new ArrayList<>(List.of("value","INVOKE","target","Lfixture/Owner;operation(II)Ljava/lang/String;"));
  AnnotationNode wrap=new AnnotationNode(MixinWrapOperationShim.WRAP_OPERATION);wrap.values=new ArrayList<>(List.of("method",List.of("run"),"at",at));h.visibleAnnotations=new ArrayList<>(List.of(wrap));
  h.instructions.add(new InsnNode(Opcodes.ACONST_NULL));h.instructions.add(new InsnNode(Opcodes.ARETURN));n.methods.add(h);return n;
 }
 private static ClassNode target(String desc) {
  ClassNode n=new ClassNode();n.name="fixture/Target";n.superName="java/lang/Object";
  MethodNode m=new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"run","()Ljava/lang/String;",null,null);
  m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"fixture/Owner","operation",desc,false));
  m.instructions.add(new InsnNode(Opcodes.ARETURN));n.methods.add(m);return n;
 }
}
