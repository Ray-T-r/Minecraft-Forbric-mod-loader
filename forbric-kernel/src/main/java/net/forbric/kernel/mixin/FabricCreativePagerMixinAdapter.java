/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;
import java.util.*;
import java.util.function.Function;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.transform.CreativePagerBridgeInjector;

/**
 * Takes a guest's second creative pager out of its mixin and keeps everything else it brings.
 *
 * <p>A mixin onto the creative screen that implements Fabric's {@code FabricCreativeModeInventoryScreen} with a page of
 * its own (the field its {@code getCurrentPage()} reads is the one its {@code switchToPage(int)} writes) fights the
 * carrier's pager, which {@link CreativePagerBridgeInjector} already puts behind that interface. What is removed is
 * decided by data flow, never by a member's name: the guest's bodies for members the carrier answers (the bridge's
 * interface rows, the pager's own selection hooks ({@link #selectionHooks}), and every method the installed interface
 * declares), the non-shadow fields
 * those bodies read or write — the second page state — and, transitively, every member that touches that state or calls
 * a removed member nothing else answers. Everything else stays: an injector that only calls the interface (PageUp and
 * PageDown turning the page through {@code switchToPreviousPage}) now turns the carrier's page, and an unrelated
 * injector, accessor or a duck interface's method is not touched. Members that only removed ones used are collected.
 *
 * <p>The adapter declines (returns 0, and the config policy then leaves the whole mixin out as before) when removing the
 * pager would strand something: a non-private member outside the injectors that touches the state and that neither the
 * carrier nor the target answers — another interface's method, which would otherwise be an {@code AbstractMethodError}
 * — or a constructor or static initializer that reads the state. A write there is dropped with its value.
 *
 * <p><b>Whose page controls are drawn.</b> The page is always the carrier's; the controls a player clicks need not be.
 * When the player's mods use no pager family but the guest's — no installed mod belongs to a Forge family, whose own
 * platform draws the carrier's pager, so the guest's is the one every installed mod was built to be seen with — and the
 * carrier's controls on the merged screen are exactly the two page buttons and the page label
 * ({@code CreativePagerBridgeInjector.carrierControls}), the guest's are drawn and the carrier's stand aside. An injector
 * of the guest pager that writes its page state and reads none of it — the one that adds its page buttons when the
 * screen opens — is then kept with that write dropped, instead of removed with the rest; its buttons turn the page
 * through the interface, so through the carrier's page. Decided once, when the configs are first read
 * ({@link #noteGuestControls}), so the mixin and the screen are changed together. Any Forge-family mod, or a carrier
 * whose controls are shaped otherwise, and the carrier's controls stay, as before.
 */
public final class FabricCreativePagerMixinAdapter {
	public static final String PROPERTY="forbric.fabricCreativeKeyboard";
	static final String TARGET="net/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen";
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

	/**
	 * The pager's selection hooks, by the role its own code gives them: the private, argument-less, void instance methods
	 * its {@code switchToPage(int)} — the public interface method that turns the page — calls on itself. Another mod's
	 * mixin may name such a method to hear every turn (owo-lib's per-page tab memory sits at the head of fabric-api's
	 * {@code updateSelection}); the name is read from the pager, never assumed. Empty for anything not a pager.
	 */
	public static Set<String> selectionHooks(ClassNode mixin){
		if(mixin==null||!matches(mixin))return Set.of();
		MethodNode turn=mixin.methods.stream().filter(m->m.name.equals("switchToPage")&&m.desc.equals("(I)Z")).findFirst().orElse(null);
		Set<String> hooks=new LinkedHashSet<>();
		for(AbstractInsnNode i:turn.instructions)if(i instanceof MethodInsnNode c&&c.owner.equals(mixin.name)&&c.desc.equals("()V")
				&&(c.getOpcode()==Opcodes.INVOKESPECIAL||c.getOpcode()==Opcodes.INVOKEVIRTUAL)){
			MethodNode hook=mixin.methods.stream().filter(m->m.name.equals(c.name)&&m.desc.equals(c.desc)).findFirst().orElse(null);
			if(hook!=null&&(hook.access&Opcodes.ACC_PRIVATE)!=0&&(hook.access&Opcodes.ACC_STATIC)==0&&MixinFit.injectorOf(hook)==null)hooks.add(hook.name+hook.desc);
		}
		return Set.copyOf(hooks);
	}

	/** The selection hooks of every guest pager seen while configs were read (as {@code name + descriptor}). */
	private static final Set<String> NOTED_HOOKS=java.util.concurrent.ConcurrentHashMap.newKeySet();
	/** Records {@code mixin}'s selection hooks, when it is a pager: the creative pager bridge gives the screen these names. */
	static void noteSelectionHooks(ClassNode mixin){NOTED_HOOKS.addAll(selectionHooks(mixin));}
	/** What {@link #noteSelectionHooks} recorded on this boot. */
	public static Set<String> notedSelectionHooks(){return Set.copyOf(NOTED_HOOKS);}
	/** Test seam. */
	public static void forgetSelectionHooks(){NOTED_HOOKS.clear();GUEST_CONTROLS.set(false);GUEST_CONTROLS_DECIDED.set(false);}

	/** Whether the guest pager's own page controls are drawn on this boot (see the class comment). */
	private static final java.util.concurrent.atomic.AtomicBoolean GUEST_CONTROLS=new java.util.concurrent.atomic.AtomicBoolean();
	/** Whether {@link #noteGuestControls} has decided on this boot: configs are discovered more than once, the inputs never change. */
	private static final java.util.concurrent.atomic.AtomicBoolean GUEST_CONTROLS_DECIDED=new java.util.concurrent.atomic.AtomicBoolean();
	/** What {@link #noteGuestControls} decided: the creative pager bridge then keeps the carrier's controls from running. */
	public static boolean guestControlsDrawn(){return GUEST_CONTROLS.get();}

	/**
	 * Decides, from a guest pager seen while configs are read, whether its controls are the ones drawn: no Forge-family mod
	 * is installed, the carrier's controls are exactly the shape the bridge can stand aside ({@code screen}, the merged
	 * screen with its code), and the pager has an injector to keep for them. Decided by the first guest pager seen, once
	 * for the boot.
	 *
	 * @param forgeFamilyMods how many installed mods belong to a Forge family
	 */
	static boolean noteGuestControls(ClassNode mixin,ClassNode screen,int forgeFamilyMods){
		if(!enabled()||mixin==null||!matches(mixin))return GUEST_CONTROLS.get();
		if(!GUEST_CONTROLS_DECIDED.compareAndSet(false,true))return GUEST_CONTROLS.get();
		if(forgeFamilyMods>0){
			ForbricLog.info("[Forbric/CreativePager] %d Forge-family mod(s) are installed, and their platform draws the carrier's "
					+ "pager — NeoForge's page controls stay on the creative screen",forgeFamilyMods);
			return false;
		}
		if(screen==null||CreativePagerBridgeInjector.carrierControls(screen)==null){
			ForbricLog.warn("[Forbric/CreativePager] only Fabric mods are installed, but NeoForge's page controls on the creative "
					+ "screen are not the two buttons and the label the bridge can stand aside — they stay, and %s's own stay out",
					mixin.name.replace('/','.'));
			return false;
		}
		ClassNode trial=new ClassNode();mixin.accept(trial);
		if(adapt(trial,name->name.equals(TARGET)?screen:null,true).keptWriters().isEmpty())return false;
		GUEST_CONTROLS.set(true);
		ForbricLog.info("[Forbric/CreativePager] only Fabric mods are installed: the creative screen draws %s's own page controls "
				+ "and NeoForge's stand aside — the page itself stays NeoForge's, behind the Fabric interface",mixin.name.replace('/','.'));
		return true;
	}

	/** Without class lookup: the carrier's own rows decide what it answers. */
	public static int adapt(ClassNode mixin){return adapt(mixin,name->null);}

	/** @param classes the installed interface and the merged screen by internal name, or null for either */
	public static int adapt(ClassNode mixin,Function<String,ClassNode> classes){
		return adapt(mixin,classes,GUEST_CONTROLS.get()).changed();
	}

	/**
	 * What one adaptation did.
	 *
	 * @param changed      1 when the second pager was taken out, 0 when the adapter declined
	 * @param keptWriters  injectors kept with their write of the page state dropped (the guest's controls)
	 */
	record Result(int changed,List<String> keptWriters){}

	/** @param guestControls whether the guest's own page controls are drawn: then its writer-only injectors are kept */
	static Result adapt(ClassNode mixin,Function<String,ClassNode> classes,boolean guestControls){
		if(!enabled()||!matches(mixin))return new Result(0,List.of());
		Set<String> answered=new LinkedHashSet<>(CreativePagerBridgeInjector.interfaceMembers());
		answered.addAll(selectionHooks(mixin));
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
		if(state.isEmpty())return new Result(0,List.of());
		// The guest's controls: an injector that writes the page state and reads none of it, kept with the write dropped.
		Set<MethodNode> writers=new LinkedHashSet<>();
		for(boolean grew=true;grew;){
			grew=false;
			for(MethodNode m:mixin.methods){
				if(removed.contains(m)||writers.contains(m)||m.name.equals("<init>")||m.name.equals("<clinit>"))continue;
				if(callsGone(m,mixin.name,removed,resolvable)){removed.add(m);grew=true;continue;}
				if(!touches(m,mixin.name,state))continue;
				if(guestControls&&MixinFit.injectorOf(m)!=null&&!reads(m,mixin.name,state))writers.add(m);
				else{removed.add(m);grew=true;}
			}
			for(MethodNode m:new ArrayList<>(writers))if(callsGone(m,mixin.name,removed,resolvable)){writers.remove(m);removed.add(m);grew=true;}
		}
		for(MethodNode m:mixin.methods){
			if(!m.name.equals("<init>")&&!m.name.equals("<clinit>"))continue;
			if(reads(m,mixin.name,state)||callsGone(m,mixin.name,removed,resolvable))return new Result(0,List.of());
		}
		for(MethodNode m:removed){
			boolean contained=(m.access&Opcodes.ACC_PRIVATE)!=0||MixinFit.injectorOf(m)!=null||resolvable.contains(m.name+m.desc);
			if(!contained){
				ForbricLog.info("[Forbric/CreativePager] %s's %s%s uses its second page state and nothing else answers it — the pager is not "
						+ "taken out of this mixin",mixin.name.replace('/','.'),m.name,m.desc);
				return new Result(0,List.of());
			}
		}

		for(MethodNode m:mixin.methods)if(m.name.equals("<init>")||m.name.equals("<clinit>"))dropWrites(m,mixin.name,state);
		List<String> keptWriters=new ArrayList<>();
		for(MethodNode m:writers){dropWrites(m,mixin.name,state);keptWriters.add(m.name);}
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
		ForbricLog.info("[Forbric/CreativePager] %s: took out its second pager (state %s; %s) and kept %s%s",
				mixin.name.replace('/','.'),state,gone,kept.isEmpty()?"no injector":kept,
				keptWriters.isEmpty()?"":" — "+keptWriters+" with its page-state write dropped, so its own page controls are drawn");
		return new Result(1,List.copyOf(keptWriters));
	}

	private static ClassNode lookup(Function<String,ClassNode> classes,String name){
		if(classes==null)return null;
		try{return classes.apply(name);}catch(RuntimeException unavailable){
			Caught.warn(unavailable,"[Forbric/CreativePager] could not read %s; taken as absent",name);return null;}
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
