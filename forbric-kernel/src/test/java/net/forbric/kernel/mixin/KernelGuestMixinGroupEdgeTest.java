/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

@ResourceLock("system-properties")
@ResourceLock("ModCatalog")
class KernelGuestMixinGroupEdgeTest {
    private static final String TARGET="net/minecraft/world/GroupTarget", CI="Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
    @AfterEach void reset(){ForbricMixinService.setGuestConfigs(List.of());MixinAddedMembers.reset();ForeignMixinTargets.reset();MixinRetarget.reset();MixinCompatibility.reset();CompatibilityFindings.reset();}
    private static AnnotationNode annotation(String desc,Object... values){AnnotationNode node=new AnnotationNode(desc);node.values=new ArrayList<>(List.of(values));return node;}
    private static ClassNode node(String name){ClassNode node=new ClassNode();node.name=name;node.version=Opcodes.V21;node.access=Opcodes.ACC_PUBLIC;node.superName="java/lang/Object";return node;}
    private static ClassNode mixin(String name,String selector,int minimum){
        ClassNode node=node(name);node.visibleAnnotations=new ArrayList<>(List.of(annotation("Lorg/spongepowered/asm/mixin/Mixin;","value",List.of(Type.getObjectType(TARGET)))));
        MethodNode callback=new MethodNode(Opcodes.ACC_PRIVATE,"callback","("+CI+")V",null,null);
        callback.instructions.add(new InsnNode(Opcodes.RETURN));
        callback.visibleAnnotations=new ArrayList<>(List.of(annotation("Lorg/spongepowered/asm/mixin/injection/Inject;","method",List.of(selector),"at",List.of(annotation("Lorg/spongepowered/asm/mixin/injection/At;","value","INVOKE","target","Lexample/Calls;call()V")))));
        callback.invisibleAnnotations=new ArrayList<>(List.of(annotation("Lorg/spongepowered/asm/mixin/injection/Group;","name","alternatives","min",minimum)));
        node.methods.add(callback);return node;
    }
    private static byte[] bytes(ClassNode node){ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);return writer.toByteArray();}
    private static byte[] config(String entry){return ("{\"package\":\"example\",\"required\":true,\"injectors\":{\"defaultRequire\":1},\"mixins\":[\""+entry+"\"]}").getBytes(StandardCharsets.UTF_8);}
    @Test void aMethodAnotherEarlierMixinAddsCannotBecomeAnImpossibleGroup() {
        ClassNode target=node(TARGET),adder=node("example/Adder"),consumer=mixin("example/Consumer","future()V",1);
        adder.visibleAnnotations=new ArrayList<>(List.of(annotation("Lorg/spongepowered/asm/mixin/Mixin;","value",List.of(Type.getObjectType(TARGET)),"priority",900)));
        MethodNode future=new MethodNode(Opcodes.ACC_PUBLIC,"future","()V",null,null);future.visibleAnnotations=List.of(annotation("Lorg/spongepowered/asm/mixin/Unique;"));
        future.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"example/Calls","call","()V",false));future.instructions.add(new InsnNode(Opcodes.RETURN));adder.methods.add(future);
        Map<String,byte[]> resources=new HashMap<>(Map.of(TARGET+".class",bytes(target),"example/Adder.class",bytes(adder),"example/Consumer.class",bytes(consumer),"adder.mixins.json",config("Adder"),"consumer.mixins.json",config("Consumer")));
        ForbricMixinService.setGuestConfigs(List.of("adder.mixins.json","consumer.mixins.json"));MixinAddedMembers.reset();ForeignMixinTargets.reset();
        MixinAddedMembers.View added=MixinAddedMembers.before("consumer.mixins.json","Consumer",resources::get);
        assertTrue(added.method(TARGET,"future","()V"));
        assertFalse(MixinGroupConstraints.failures(consumer,resources::get).isEmpty(),"negative control against the base alone");
        assertTrue(MixinGroupConstraints.failures(consumer,resources::get,added).isEmpty());
        assertTrue(KernelGuestMixinAdapter.unfitMixins("consumer.mixins.json",resources.get("consumer.mixins.json"),resources::get).isEmpty());
        assertTrue(CompatibilityFindings.all().stream().noneMatch(f->f.detail().contains("unsatisfiable injection group")));
        MixinPlayerWorldCallbackAdapter.set(MixinFit.injectorOf(consumer.methods.getFirst()),"method",List.of("future"));
        assertTrue(MixinGroupConstraints.failures(consumer,resources::get,added).isEmpty(),"View cannot prove bare-name future descriptors absent");
    }
    @Test void improvingAnchorsByRetargetingCannotSkipTheGroupsRemainingMinimum() {
        ClassNode target=node(TARGET),consumer=mixin("example/Consumer","host()V",2);
        MethodNode stub=new MethodNode(Opcodes.ACC_PUBLIC,"host","()V",null,null);stub.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));stub.instructions.add(new InsnNode(Opcodes.ICONST_0));stub.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,TARGET,"host","(Z)V",false));stub.instructions.add(new InsnNode(Opcodes.RETURN));target.methods.add(stub);
        MethodNode live=new MethodNode(Opcodes.ACC_PUBLIC,"host","(Z)V",null,null);live.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"example/Calls","call","()V",false));live.instructions.add(new InsnNode(Opcodes.RETURN));target.methods.add(live);
        Map<String,byte[]> resources=Map.of(TARGET+".class",bytes(target),"example/Consumer.class",bytes(consumer),"consumer.mixins.json",config("Consumer"));
        byte[] raw=bytes(consumer);MixinFit.Result before=MixinFit.evaluate(raw,resources::get);
        MixinRetarget.Adoption adoption=MixinRetarget.adopt(raw,before,resources::get,b->MixinFit.evaluate(b,resources::get));
        assertNotNull(adoption,"the retarget genuinely improves the original anchor fit");
        assertEquals(MixinFit.Verdict.FIT,adoption.after().verdict());
        assertEquals(1,MixinGroupConstraints.failures(MixinFit.parse(adoption.rewritten()),resources::get).size(),"one native call still cannot satisfy min=2");
        assertEquals(List.of("Consumer"),KernelGuestMixinAdapter.unfitMixins("consumer.mixins.json",resources.get("consumer.mixins.json"),resources::get));
        assertTrue(CompatibilityFindings.all().stream().anyMatch(f->f.confidence()==CompatibilityFinding.Confidence.CONFIRMED && f.detail().contains("group bounds after carrier retargeting") && f.required()));
        assertEquals(0,MixinRetarget.applyRemembered(consumer.name,MixinFit.parse(raw)),"a refused rewrite is not committed to Mixin's provider");
    }
}
