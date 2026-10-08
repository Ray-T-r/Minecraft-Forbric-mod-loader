/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;
import java.util.*;
import org.objectweb.asm.tree.*;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.transform.CreativePagerBridgeInjector;

/** Retains Fabric PageUp/PageDown input while the API and rendering share the carrier's pager. */
public final class FabricCreativePagerMixinAdapter {
	public static final String PROPERTY="forbric.fabricCreativeKeyboard";
	private static final String TARGET="net/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen";
	private FabricCreativePagerMixinAdapter(){ }
	public static boolean enabled(){return CreativePagerBridgeInjector.enabled()&&!"off".equalsIgnoreCase(System.getProperty(PROPERTY,"on"));}
    static boolean matches(ClassNode mixin) {
        if(!MixinCallbackShape.targets(mixin,TARGET)||!mixin.interfaces.contains(CreativePagerBridgeInjector.API))return false;
        List<MethodNode> getters=mixin.methods.stream().filter(m->m.name.equals("getCurrentPage")&&m.desc.equals("()I")).toList();
        List<MethodNode> setters=mixin.methods.stream().filter(m->m.name.equals("switchToPage")&&m.desc.equals("(I)Z")).toList();
        if(getters.size()!=1||setters.size()!=1)return false;
        for(var instruction:getters.getFirst().instructions)if(instruction instanceof FieldInsnNode read&&read.owner.equals(mixin.name)&&read.desc.equals("I")
                &&(read.getOpcode()==org.objectweb.asm.Opcodes.GETFIELD||read.getOpcode()==org.objectweb.asm.Opcodes.GETSTATIC))
            for(var operation:setters.getFirst().instructions)if(operation instanceof FieldInsnNode write&&write.owner.equals(read.owner)&&write.name.equals(read.name)&&write.desc.equals(read.desc)
                    &&(write.getOpcode()==org.objectweb.asm.Opcodes.PUTFIELD||write.getOpcode()==org.objectweb.asm.Opcodes.PUTSTATIC))return true;
        return false;
    }

	public static int adapt(ClassNode mixin){
		if(!enabled()||!matches(mixin)||mixin.methods.size()<=2)return 0;
		MethodNode keys=mixin.methods.stream().filter(m->MixinCallbackShape.kind(m,"Inject")&&MixinCallbackShape.selects(m,"keyPressed")).findFirst().orElse(null);
		if(keys==null)return 0;
		int previous=0,next=0;
		for(var i:keys.instructions){
			if(i instanceof FieldInsnNode f&&f.owner.equals(mixin.name))return 0;
			if(i instanceof MethodInsnNode c&&c.owner.equals(mixin.name)){
				if(c.name.equals("switchToPreviousPage")&&c.desc.equals("()Z"))previous++;
				else if(c.name.equals("switchToNextPage")&&c.desc.equals("()Z"))next++;
				else return 0;
			}
		}
		if(previous!=1||next!=1)return 0;
		mixin.methods.removeIf(m->m!=keys&&!m.name.equals("<init>"));mixin.fields.clear();
		ForbricLog.info("[Forbric/CreativePager] retained Fabric's PageUp/PageDown callback; its API and rendering "
				+ "use the same carrier pager instead of creating a second page state");return 1;
	}
}
