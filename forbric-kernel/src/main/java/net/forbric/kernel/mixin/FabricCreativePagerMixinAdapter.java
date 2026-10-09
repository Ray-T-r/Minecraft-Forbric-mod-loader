/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;
import java.util.*;
import java.util.function.Function;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.transform.CreativePagerBridgeInjector;

/**
 * Takes a guest's second creative pager out of its mixin and keeps everything else it brings.
 *
 * <p>A mixin onto the creative screen that implements Fabric's {@code FabricCreativeModeInventoryScreen} with a page of
 * its own (the field its {@code getCurrentPage()} reads is the one its {@code switchToPage(int)} writes) fights the
 * carrier's pager, which {@link CreativePagerBridgeInjector} already puts behind that interface. What is removed is
 * decided by data flow, never by a member's name: the guest's bodies for members the carrier answers (the bridge's
 * interface rows and {@code updateSelection}, and every method the installed interface declares), the non-shadow fields
 * those bodies read or write — the second page state — and, transitively, every member that touches that state or calls
 * a removed member nothing else answers. Everything else stays: an injector that only calls the interface (PageUp and
 * PageDown turning the page through {@code switchToPreviousPage}) now turns the carrier's page, and an unrelated
 * injector, accessor or a duck interface's method is not touched. Members that only removed ones used are collected.
 *
 * <p>The adapter declines (returns 0, and the config policy then leaves the whole mixin out as before) when removing the
 * pager would strand something: a non-private member outside the injectors that touches the state and that neither the
 * carrier nor the target answers — another interface's method, which would otherwise be an {@code AbstractMethodError}
 * — or a constructor or static initializer that reads the state. A write there is dropped with its value.
 */
public final class FabricCreativePagerMixinAdapter {
	public static final String PROPERTY="forbric.fabricCreativeKeyboard";
	private static final String TARGET="net/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen";
	private static final String SHADOW="Lorg/spongepowered/asm/mixin/Shadow;";
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

	/** Without class lookup: the carrier's own rows decide what it answers. */
	public static int adapt(ClassNode mixin){return adapt(mixin,name->null);}

	/** @param classes the installed interface and the merged screen by internal name, or null for either */
	public static int adapt(ClassNode mixin,Function<String,ClassNode> classes){
		if(!enabled()||!matches(mixin))return 0;
		Set<String> answered=new LinkedHashSet<>(CreativePagerBridgeInjector.suppliedMembers());
		ClassNode api=lookup(classes,CreativePagerBridgeInjector.API);
		if(api!=null)for(MethodNode m:api.methods)if((m.access&(Opcodes.ACC_STATIC|Opcodes.ACC_PRIVATE))==0&&!m.name.startsWith("<"))answered.add(m.name+m.desc);
		// A call to one of these from a kept member still lands somewhere once the guest's body is gone.
		Set<String> resolvable=new HashSet<>(answered);
		ClassNode screen=lookup(classes,TARGET);
		if(screen!=null)for(MethodNode m:screen.methods)resolvable.add(m.name+m.desc);

		Set<MethodNode> removed=new LinkedHashSet<>();
		for(MethodNode m:mixin.methods)if((m.access&Opcodes.ACC_STATIC)==0&&answered.contains(m.name+m.desc)&&m.instructions.size()>0)removed.add(m);
		Set<String> state=new LinkedHashSet<>();
		for(MethodNode m:removed)for(AbstractInsnNode i:m.instructions)
			if(i instanceof FieldInsnNode f&&f.owner.equals(mixin.name)){FieldNode field=field(mixin,f.name,f.desc);if(field!=null&&!annotated(field.visibleAnnotations,field.invisibleAnnotations,SHADOW))state.add(f.name+":"+f.desc);}
		if(state.isEmpty())return 0;
		for(boolean grew=true;grew;){
			grew=false;
			for(MethodNode m:mixin.methods){
				if(removed.contains(m)||m.name.equals("<init>")||m.name.equals("<clinit>"))continue;
				if(touches(m,mixin.name,state)||callsGone(m,mixin.name,removed,resolvable)){removed.add(m);grew=true;}
			}
		}
		for(MethodNode m:mixin.methods){
			if(!m.name.equals("<init>")&&!m.name.equals("<clinit>"))continue;
			if(reads(m,mixin.name,state)||callsGone(m,mixin.name,removed,resolvable))return 0;
		}
		for(MethodNode m:removed){
			boolean contained=(m.access&Opcodes.ACC_PRIVATE)!=0||MixinFit.injectorOf(m)!=null||resolvable.contains(m.name+m.desc);
			if(!contained){
				ForbricLog.info("[Forbric/CreativePager] %s's %s%s uses its second page state and nothing else answers it — the pager is not "
						+ "taken out of this mixin",mixin.name.replace('/','.'),m.name,m.desc);
				return 0;
			}
		}

		for(MethodNode m:mixin.methods)if(m.name.equals("<init>")||m.name.equals("<clinit>"))dropWrites(m,mixin.name,state);
		List<String> gone=new ArrayList<>();
		for(MethodNode m:removed)gone.add(m.name);
		mixin.methods.removeAll(removed);
		mixin.fields.removeIf(f->state.contains(f.name+":"+f.desc));
		collect(mixin);

		List<String> kept=new ArrayList<>();
		boolean turnsPages=false;
		for(MethodNode m:mixin.methods)if(MixinFit.injectorOf(m)!=null){
			kept.add(m.name);
			for(AbstractInsnNode i:m.instructions)if(i instanceof MethodInsnNode c&&(c.owner.equals(mixin.name)||c.owner.equals(CreativePagerBridgeInjector.API))
					&&Type.getReturnType(c.desc).equals(Type.BOOLEAN_TYPE)&&c.name.startsWith("switchTo"))turnsPages=true;
		}
		if(turnsPages)ForbricLog.info("[Forbric/CreativePager] retained Fabric's PageUp/PageDown callback; its API and rendering "
				+ "use the same carrier pager instead of creating a second page state");
		ForbricLog.info("[Forbric/CreativePager] %s: took out its second pager (state %s; %s) and kept %s",
				mixin.name.replace('/','.'),state,gone,kept.isEmpty()?"no injector":kept);
		return 1;
	}

	private static ClassNode lookup(Function<String,ClassNode> classes,String name){
		if(classes==null)return null;
		try{return classes.apply(name);}catch(RuntimeException unavailable){return null;}
	}
	private static FieldNode field(ClassNode node,String name,String desc){for(FieldNode f:node.fields)if(f.name.equals(name)&&f.desc.equals(desc))return f;return null;}
	private static boolean annotated(List<AnnotationNode> visible,List<AnnotationNode> invisible,String desc){
		for(List<AnnotationNode> list:Arrays.asList(visible,invisible))if(list!=null)for(AnnotationNode a:list)if(a.desc.equals(desc))return true;
		return false;
	}
	private static boolean touches(MethodNode m,String owner,Set<String> state){
		for(AbstractInsnNode i:m.instructions)if(i instanceof FieldInsnNode f&&f.owner.equals(owner)&&state.contains(f.name+":"+f.desc))return true;
		return false;
	}
	private static boolean reads(MethodNode m,String owner,Set<String> state){
		for(AbstractInsnNode i:m.instructions)if(i instanceof FieldInsnNode f&&f.owner.equals(owner)&&state.contains(f.name+":"+f.desc)
				&&(f.getOpcode()==Opcodes.GETFIELD||f.getOpcode()==Opcodes.GETSTATIC))return true;
		return false;
	}
	/** Whether {@code m} reaches a removed member that nothing will answer once it is gone. */
	private static boolean callsGone(MethodNode m,String owner,Set<MethodNode> removed,Set<String> resolvable){
		for(MethodNode r:removed){
			if(resolvable.contains(r.name+r.desc))continue;
			if(references(m,owner,r.name,r.desc))return true;
		}
		return false;
	}
	private static boolean references(MethodNode m,String owner,String name,String desc){
		for(AbstractInsnNode i:m.instructions){
			if(i instanceof MethodInsnNode c&&c.owner.equals(owner)&&c.name.equals(name)&&c.desc.equals(desc))return true;
			if(i instanceof InvokeDynamicInsnNode d)for(Object a:d.bsmArgs)if(a instanceof Handle h&&h.getOwner().equals(owner)&&h.getName().equals(name)&&h.getDesc().equals(desc))return true;
			if(i instanceof LdcInsnNode l&&l.cst instanceof Handle h&&h.getOwner().equals(owner)&&h.getName().equals(name)&&h.getDesc().equals(desc))return true;
		}
		return false;
	}
	private static boolean referencesField(MethodNode m,String owner,String name,String desc){
		for(AbstractInsnNode i:m.instructions){
			if(i instanceof FieldInsnNode f&&f.owner.equals(owner)&&f.name.equals(name)&&f.desc.equals(desc))return true;
			if(i instanceof InvokeDynamicInsnNode d)for(Object a:d.bsmArgs)if(a instanceof Handle h&&h.getTag()<=Opcodes.H_PUTSTATIC&&h.getOwner().equals(owner)&&h.getName().equals(name))return true;
		}
		return false;
	}
	/** A write of the removed state in a constructor or static initializer keeps its operands' evaluation and drops the store. */
	private static void dropWrites(MethodNode m,String owner,Set<String> state){
		for(AbstractInsnNode i:m.instructions.toArray()){
			if(!(i instanceof FieldInsnNode f)||!f.owner.equals(owner)||!state.contains(f.name+":"+f.desc))continue;
			int size=Type.getType(f.desc).getSize();
			InsnList pops=new InsnList();
			pops.add(new InsnNode(size==2?Opcodes.POP2:Opcodes.POP));
			if(f.getOpcode()==Opcodes.PUTFIELD)pops.add(new InsnNode(Opcodes.POP));
			m.instructions.insert(f,pops);m.instructions.remove(f);
		}
	}
	/** Private, synthetic and shadow members no kept member reaches any more; a static initializer left without effect. */
	private static void collect(ClassNode mixin){
		for(boolean again=true;again;){
			again=false;
			for(MethodNode m:new ArrayList<>(mixin.methods)){
				if(m.name.equals("<init>")||m.name.equals("<clinit>")||MixinFit.injectorOf(m)!=null)continue;
				boolean collectable=(m.access&(Opcodes.ACC_PRIVATE|Opcodes.ACC_SYNTHETIC))!=0||annotated(m.visibleAnnotations,m.invisibleAnnotations,SHADOW);
				if(!collectable)continue;
				boolean used=false;
				for(MethodNode other:mixin.methods)if(other!=m&&references(other,mixin.name,m.name,m.desc)){used=true;break;}
				if(!used){mixin.methods.remove(m);again=true;}
			}
			for(FieldNode f:new ArrayList<>(mixin.fields)){
				if((f.access&Opcodes.ACC_PRIVATE)==0&&!annotated(f.visibleAnnotations,f.invisibleAnnotations,SHADOW))continue;
				boolean used=false;
				for(MethodNode other:mixin.methods)if(referencesField(other,mixin.name,f.name,f.desc)){used=true;break;}
				if(!used){mixin.fields.remove(f);again=true;}
			}
		}
		mixin.methods.removeIf(m->m.name.equals("<clinit>")&&inert(m));
	}
	/** Only constants pushed and popped, then the return. */
	private static boolean inert(MethodNode m){
		for(AbstractInsnNode i:m.instructions){
			int op=i.getOpcode();
			if(op<0||op==Opcodes.RETURN||op==Opcodes.POP||op==Opcodes.POP2||op==Opcodes.NOP)continue;
			if(op>=Opcodes.ACONST_NULL&&op<=Opcodes.SIPUSH)continue;
			if(i instanceof LdcInsnNode l&&(l.cst instanceof Number||l.cst instanceof String))continue;
			return false;
		}
		return true;
	}
}
