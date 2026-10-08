/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** The actual rebuilt base keeps both native part contracts; the retired compatibility name is inert. */
class DragonPartsInjectorTest {
    private static final Path STAGED=Path.of(System.getProperty("forbric.stagedRoot","../forbric-loader/run"));
    private static final Path MERGED=STAGED.resolve("merged-base/patched-mc-merged-26.2.jar"),FORGE=STAGED.resolve("merged-base/forge-runtime-interop.jar");
    @Test void thePartIsANeoForgePartEntity()throws Exception{
        byte[] original=NativeCoremodParityTest.read(MERGED,DragonPartsInjector.PART_INTERNAL);assertSame(original,new DragonPartsInjector().transform(DragonPartsInjector.PART,original,null));
        assertEquals(DragonPartsInjector.FORGE_PART,node(original).superName);assertEquals(DragonPartsInjector.NEO_PART,node(NativeCoremodParityTest.read(FORGE,DragonPartsInjector.FORGE_PART)).superName,"the proved runtime ancestor bridge preserves both API types");
    }
    @Test void bothNativeGetPartsMethodsKeepTheActualSubEntities()throws Exception{
        byte[] original=NativeCoremodParityTest.read(MERGED,DragonPartsInjector.DRAGON_INTERNAL);assertSame(original,new DragonPartsInjector().transform(DragonPartsInjector.DRAGON,original,null));ClassNode dragon=node(original);
        for(String descriptor:List.of(DragonPartsInjector.NEO_GET_PARTS,DragonPartsInjector.FORGE_GET_PARTS)){
            MethodNode getter=dragon.methods.stream().filter(m->m.name.equals("getParts")&&m.desc.equals(descriptor)).findFirst().orElseThrow();List<AbstractInsnNode> code=Arrays.stream(getter.instructions.toArray()).filter(i->i.getOpcode()>=0).toList();assertEquals(List.of(Opcodes.ALOAD,Opcodes.GETFIELD,Opcodes.ARETURN),code.stream().map(AbstractInsnNode::getOpcode).toList());assertEquals("[L"+DragonPartsInjector.PART_INTERNAL+";",((FieldInsnNode)code.get(1)).desc);
        }
    }
    @Test void theDebugHitboxesKeepTheirNativePartApi()throws Exception{
        byte[] original=NativeCoremodParityTest.read(MERGED,DragonPartsInjector.HITBOXES.replace('.','/'));assertSame(original,new DragonPartsInjector().transform(DragonPartsInjector.HITBOXES,original,null));assertTrue(node(original).methods.stream().flatMap(m->Arrays.stream(m.instructions.toArray())).anyMatch(i->i instanceof MethodInsnNode call&&call.name.equals("getParts")&&call.desc.equals(DragonPartsInjector.FORGE_GET_PARTS)));
    }
    @Test void theClientsTrackingCallbacksAreLeftToThePartTrackingRepairs()throws Exception{
        byte[] original=NativeCoremodParityTest.read(MERGED,ClientPartTrackingInjector.CALLBACKS_INTERNAL);assertSame(original,new DragonPartsInjector().transform(ClientPartTrackingInjector.CALLBACKS,original,null));
        assertSame(original,new ClientPartTrackingInjector().transform(ClientPartTrackingInjector.CALLBACKS,original,null));
        ForgePartTrackingInjector tracking=new ForgePartTrackingInjector(owner->{try{return NativeCoremodParityTest.read(MERGED,owner);}catch(Exception absent){return null;}});assertNotSame(original,tracking.transform(ClientPartTrackingInjector.CALLBACKS,original,null));
    }
    private static ClassNode node(byte[] bytes){ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);return node;}
}
