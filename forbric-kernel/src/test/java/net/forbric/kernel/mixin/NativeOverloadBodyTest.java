package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.forbric.api.Ecosystem;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

class NativeOverloadBodyTest implements Opcodes {
    private static final String OWNER="unknown/NativeDispatch",CALLEE="unknown/Operation";
    @Test void anUnknownNativeContextGateKeepsTheCompleteSourceBodyAndTheCallerInputs() {
        ClassNode source=owner(false),current=owner(true);
        try(var scope=MergedBaseCalleeSwaps.using(name->current,(family,name)->family==Ecosystem.FABRIC?source:current)) {
            assertSame(method(current,"(IZ)V"),NativeOverloadBody.destination(current,source,method(source,"(I)V"),method(current,"(I)V")));
        }
    }
    @Test void changingAnOriginalProducerOrTheForwardedInputIsNotAContextExtension() {
        for(boolean producer:List.of(false,true)) {
            ClassNode source=owner(false),current=owner(true);
            MethodNode changed=producer?method(current,"(IZ)V"):current.methods.getFirst();
            for(var instruction:changed.instructions)if(instruction instanceof VarInsnNode load&&load.getOpcode()==ILOAD&&load.var==1){changed.instructions.insert(load,new InsnNode(ICONST_1));changed.instructions.insert(load.getNext(),new InsnNode(IADD));changed.maxStack++;break;}
            try(var scope=MergedBaseCalleeSwaps.using(name->current,(family,name)->family==Ecosystem.FABRIC?source:current)) {
                assertNull(NativeOverloadBody.destination(current,source,method(source,"(I)V"),method(current,"(I)V")));
            }
        }
    }
    @Test void aChangedOrMissingNativeWitnessAndAnAdditionalEntryRemainUnproved() {
        ClassNode source=owner(false),current=owner(true),changedNative=owner(true);
        method(changedNative,"(IZ)V").instructions.insert(new InsnNode(NOP));
        for(ClassNode nativeBody:Arrays.asList(null,changedNative))try(var scope=MergedBaseCalleeSwaps.using(name->current,(family,name)->family==Ecosystem.FABRIC?source:nativeBody)) {
            assertNull(NativeOverloadBody.destination(current,source,method(source,"(I)V"),method(current,"(I)V")));
        }
        MethodNode other=new MethodNode(ACC_PUBLIC,"anotherEntry","(I)V",null,null);current.methods.getFirst().accept(other);current.methods.add(other);
        try(var scope=MergedBaseCalleeSwaps.using(name->current,(family,name)->family==Ecosystem.FABRIC?source:current)) {
            assertNull(NativeOverloadBody.destination(current,source,method(source,"(I)V"),method(current,"(I)V")));
        }
    }
    private static MethodNode method(ClassNode owner,String descriptor){return owner.methods.stream().filter(method->method.name.equals("apply")&&method.desc.equals(descriptor)).findFirst().orElseThrow();}
    private static ClassNode owner(boolean widened) {
        ClassNode node=new ClassNode();node.version=V17;node.access=ACC_PUBLIC;node.name=OWNER;node.superName="java/lang/Object";
        MethodNode entry=new MethodNode(ACC_PUBLIC,"enter","(I)V",null,null);entry.instructions.add(new VarInsnNode(ALOAD,0));entry.instructions.add(new VarInsnNode(ILOAD,1));if(widened)entry.instructions.add(new InsnNode(ICONST_1));entry.instructions.add(new MethodInsnNode(INVOKEVIRTUAL,OWNER,"apply",widened?"(IZ)V":"(I)V",false));entry.instructions.add(new InsnNode(RETURN));entry.maxStack=3;entry.maxLocals=2;node.methods.add(entry);
        MethodNode original=new MethodNode(ACC_PROTECTED,"apply","(I)V",null,null);original.instructions.add(new VarInsnNode(ILOAD,1));original.instructions.add(new VarInsnNode(ISTORE,2));original.instructions.add(new VarInsnNode(ILOAD,2));original.instructions.add(new MethodInsnNode(INVOKESTATIC,CALLEE,"accept","(I)V",false));original.instructions.add(new InsnNode(RETURN));original.maxStack=1;original.maxLocals=3;node.methods.add(original);
        if(widened) {
            MethodNode body=new MethodNode(ACC_PROTECTED,"apply","(IZ)V",null,null);body.instructions.add(new VarInsnNode(ILOAD,1));body.instructions.add(new VarInsnNode(ISTORE,3));
            LabelNode done=new LabelNode();body.instructions.add(new VarInsnNode(ILOAD,2));body.instructions.add(new MethodInsnNode(INVOKESTATIC,CALLEE,"carrierGate","(Z)Z",false));body.instructions.add(new JumpInsnNode(IFNE,done));body.instructions.add(new VarInsnNode(ILOAD,3));body.instructions.add(new MethodInsnNode(INVOKESTATIC,CALLEE,"accept","(I)V",false));body.instructions.add(done);body.instructions.add(new InsnNode(RETURN));body.maxStack=1;body.maxLocals=4;node.methods.add(body);
        }
        return node;
    }
}
