/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import java.util.function.Function;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.boot.DefinedMethodContracts;
import net.forbric.kernel.boot.KernelPredicateSeams;
import net.forbric.kernel.boot.KernelPredicateSeams.*;
import net.forbric.kernel.util.ForbricLog;

/** An exact native instruction/CFG substitution can expose a source atom behind a pure current type query.
 * The old injector is woven into the synthetic source helper; no mod, target or member admission table is used. */
public final class MixinNativePredicateSeam {
	public static final String PROPERTY="forbric.nativePredicateSeams";
	private static final String WRAP="Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",OP="com/llamalad7/mixinextras/injector/wrapoperation/Operation";
	private static final String RUNTIME="net/forbric/kernel/runtime/KernelFluidPredicateSeams";
	private static final Handle LAMBDA=new Handle(Opcodes.H_INVOKESTATIC,"java/lang/invoke/LambdaMetafactory","metafactory","(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",false);
	private record Member(String owner,String name,String descriptor){ }
	private record Decl(String owner,MethodNode method){DefinedMethodContracts.MethodContract contract(){return new DefinedMethodContracts.MethodContract(owner,method.name,method.desc,DefinedMethodContracts.fingerprint(method));}}
	private record Atom(FieldInsnNode constant,MethodInsnNode predicate){ }
	private record Plan(AnnotationNode injector,Atom atom,MethodInsnNode query,List<String> methods,String key){ }
	private record Terminal(Value receiver,String name,String descriptor){ }
	private MixinNativePredicateSeam(){ }
	public static boolean enabled(){return !"off".equalsIgnoreCase(System.getProperty(PROPERTY,"on"));}
	public static int adapt(ClassNode mixin,Function<String,ClassNode>classes,Function<String,ClassNode>nativeClasses){
		if(!enabled()||mixin==null)return 0;List<String>targets=MixinFit.mixinTargets(mixin);if(targets.size()!=1)return 0;
		ClassNode current=classes.apply(targets.getFirst()),original=nativeClasses.apply(targets.getFirst());if(current==null||original==null)return 0;
		int count=0;List<MethodNode>added=new ArrayList<>();
		for(MethodNode handler:new ArrayList<>(mixin.methods)){
			Plan plan=plan(handler,current,original,classes);if(plan==null)continue;
			String helper=MixinHandlerShim.asideName(mixin.name,handler.name,"$forbricsourcepredicate");String helperDesc="(L"+plan.atom().predicate().owner+";)Z";
			MethodNode source=new MethodNode(Opcodes.ACC_PRIVATE,helper,helperDesc,null,null);source.visibleAnnotations=new ArrayList<>(List.of(new AnnotationNode("Lorg/spongepowered/asm/mixin/Unique;")));
			source.instructions.add(new VarInsnNode(Opcodes.ALOAD,1));source.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC,plan.atom().constant().owner,plan.atom().constant().name,plan.atom().constant().desc));
			var old=plan.atom().predicate();source.instructions.add(new MethodInsnNode(old.getOpcode(),old.owner,old.name,old.desc,old.itf));source.instructions.add(new InsnNode(Opcodes.IRETURN));source.maxLocals=2;source.maxStack=2;
			set(plan.injector(),"method",List.of(helper+helperDesc));
			added.add(source);added.add(wrapper(mixin,handler,source,plan));count++;
			ForbricLog.info("[Forbric/Mixin] %s.%s retains its source predicate in a native-proved seam; unchanged owned type defaults and concrete native overrides are arbitrated at the actual query",mixin.name.replace('/','.'),handler.name);
		}mixin.methods.addAll(added);return count;
	}
	private static Plan plan(MethodNode handler,ClassNode current,ClassNode original,Function<String,ClassNode>classes){
		AnnotationNode injector=MixinFit.injectorOf(handler);if(injector==null||!WRAP.equals(injector.desc)||(handler.access&Opcodes.ACC_STATIC)!=0
				||MixinFit.value(injector,"slice")!=null||has(handler,"Lorg/spongepowered/asm/mixin/injection/Group;"))return null;
		Type[]args=Type.getArgumentTypes(handler.desc);if(args.length!=3||!args[2].equals(Type.getObjectType(OP))||!Type.getReturnType(handler.desc).equals(Type.BOOLEAN_TYPE))return null;
		List<AnnotationNode>ats=MixinFit.atNodes(injector);if(ats.size()!=1||!"INVOKE".equals(MixinFit.value(ats.getFirst(),"value"))||MixinFit.value(ats.getFirst(),"ordinal")!=null||MixinFit.value(ats.getFirst(),"shift")!=null)return null;
		Member anchor=parse(MixinFit.asString(MixinFit.value(ats.getFirst(),"target")));if(anchor==null||!anchor.owner().equals(args[0].getInternalName())||!anchor.descriptor().equals("("+args[1].getDescriptor()+")Z"))return null;
		List<String>selectors=MixinFit.stringList(MixinFit.value(injector,"method"));if(selectors.isEmpty())return null;Atom shared=null;MethodInsnNode query=null;List<String>methods=new ArrayList<>();Contract contract=null;
		for(String selector:selectors){
			List<MethodNode>old=original.methods.stream().filter(m->selector.equals(m.name)||selector.equals(m.name+m.desc)).toList();if(old.size()!=1)return null;MethodNode nativeBody=old.getFirst();
			MethodNode live=current.methods.stream().filter(m->m.name.equals(nativeBody.name)&&m.desc.equals(nativeBody.desc)).findFirst().orElse(null);if(live==null||(live.access&Opcodes.ACC_STATIC)!=0)return null;
			List<MethodInsnNode>anchors=new ArrayList<>();for(var i:nativeBody.instructions)if(i instanceof MethodInsnNode call&&same(call,anchor))anchors.add(call);if(anchors.size()!=1)return null;
			MethodInsnNode atom=anchors.getFirst();if(!(previous(atom)instanceof FieldInsnNode constant&&constant.getOpcode()==Opcodes.GETSTATIC&&constant.desc.equals(args[1].getDescriptor()))||!(previous(constant)instanceof VarInsnNode state&&state.getOpcode()==Opcodes.ALOAD))return null;
			List<MethodInsnNode>candidates=new ArrayList<>();
			for(var i:live.instructions)if(i instanceof MethodInsnNode call&&(call.getOpcode()==Opcodes.INVOKEVIRTUAL||call.getOpcode()==Opcodes.INVOKEINTERFACE)
					&&call.desc.equals("("+args[0].getDescriptor()+")Z")&&previous(call)instanceof VarInsnNode load&&load.getOpcode()==Opcodes.ALOAD&&load.var==state.var
					&&previous(load)instanceof VarInsnNode receiver&&receiver.getOpcode()==Opcodes.ALOAD&&receiver.var==0&&exactSubstitution(nativeBody,atom,live,call))candidates.add(call);
			if(candidates.size()!=1)return null;MethodInsnNode candidate=candidates.getFirst();
			QueryChain proof=new QueryChain(classes,ForeignType.FLUID_TYPE.internal(Ecosystem.NEOFORGE));Contract derived=proof.prove(new Member(candidate.owner,candidate.name,candidate.desc));if(derived==null)return null;
			if(shared!=null&&(!shared.constant().owner.equals(constant.owner)||!shared.constant().name.equals(constant.name)||!shared.predicate().owner.equals(atom.owner)||!shared.predicate().name.equals(atom.name)||!shared.predicate().desc.equals(atom.desc)
					||!query.owner.equals(candidate.owner)||!query.name.equals(candidate.name)||!query.desc.equals(candidate.desc)||!contract.equals(derived)))return null;
			shared=new Atom(constant,atom);query=candidate;contract=derived;methods.add(live.name+live.desc);
		}
		return shared==null?null:new Plan(injector,shared,query,List.copyOf(methods),KernelPredicateSeams.register(contract));
	}
	/** Every instruction and branch outside the three-instruction atom is identical after substitution. */
	private static boolean exactSubstitution(MethodNode original,MethodInsnNode atom,MethodNode live,MethodInsnNode query){
		MethodNode clone=new MethodNode(original.access,original.name,original.desc,original.signature,original.exceptions.toArray(String[]::new));original.accept(clone);
		int index=original.instructions.indexOf(atom);var call=clone.instructions.get(index);var constant=previous(call);var state=previous(constant);
		InsnList replacement=new InsnList();replacement.add(new VarInsnNode(Opcodes.ALOAD,0));replacement.add(new VarInsnNode(Opcodes.ALOAD,((VarInsnNode)state).var));replacement.add(new MethodInsnNode(query.getOpcode(),query.owner,query.name,query.desc,query.itf));
		clone.instructions.insertBefore(state,replacement);clone.instructions.remove(state);clone.instructions.remove(constant);clone.instructions.remove(call);
		return sameControlFlow(clone,live);
	}
	/** Instruction bisimulation normalizes only goto layout and inverted conditional branches. Effects, operands,
	 * stack operations, loop edges and return values must match; no Boolean/data-flow rewrite is guessed. */
	private static boolean sameControlFlow(MethodNode a,MethodNode b){
		if(!a.tryCatchBlocks.isEmpty()||!b.tryCatchBlocks.isEmpty())return false;
		ArrayDeque<AbstractInsnNode[]>pending=new ArrayDeque<>();Set<List<AbstractInsnNode>>seen=new HashSet<>();pending.add(new AbstractInsnNode[]{next(a.instructions.getFirst()),next(b.instructions.getFirst())});
		while(!pending.isEmpty()){
			var pair=pending.removeFirst();AbstractInsnNode left=follow(pair[0]),right=follow(pair[1]);if(left==null||right==null){if(left!=right)return false;continue;}
			if(!seen.add(List.of(left,right)))continue;
			if(left instanceof JumpInsnNode x&&right instanceof JumpInsnNode y){
				int canonical=Math.min(x.getOpcode(),opposite(x.getOpcode()));if(canonical!=Math.min(y.getOpcode(),opposite(y.getOpcode())))return false;
				pending.add(new AbstractInsnNode[]{x.getOpcode()==canonical?next(x.label):next(x.getNext()),y.getOpcode()==canonical?next(y.label):next(y.getNext())});
				pending.add(new AbstractInsnNode[]{x.getOpcode()==canonical?next(x.getNext()):next(x.label),y.getOpcode()==canonical?next(y.getNext()):next(y.label)});
			}else{
				if(left instanceof JumpInsnNode||right instanceof JumpInsnNode||left instanceof TableSwitchInsnNode||right instanceof TableSwitchInsnNode||left instanceof LookupSwitchInsnNode||right instanceof LookupSwitchInsnNode)return false;
				if(!instruction(left).equals(instruction(right)))return false;
				if(left.getOpcode()!=Opcodes.IRETURN&&left.getOpcode()!=Opcodes.ARETURN&&left.getOpcode()!=Opcodes.RETURN&&left.getOpcode()!=Opcodes.ATHROW)pending.add(new AbstractInsnNode[]{next(left.getNext()),next(right.getNext())});
			}
		}return true;
	}
	private static AbstractInsnNode next(AbstractInsnNode node){for(var i=node;i!=null;i=i.getNext())if(i.getOpcode()>=0)return i;return null;}
	private static AbstractInsnNode follow(AbstractInsnNode node){Set<AbstractInsnNode>seen=new HashSet<>();while(node instanceof JumpInsnNode jump&&jump.getOpcode()==Opcodes.GOTO){if(!seen.add(node))return node;node=next(jump.label);}return node;}
	private static int opposite(int opcode){return switch(opcode){case Opcodes.IFEQ->Opcodes.IFNE;case Opcodes.IFNE->Opcodes.IFEQ;case Opcodes.IFLT->Opcodes.IFGE;case Opcodes.IFGE->Opcodes.IFLT;case Opcodes.IFGT->Opcodes.IFLE;case Opcodes.IFLE->Opcodes.IFGT;case Opcodes.IF_ICMPEQ->Opcodes.IF_ICMPNE;case Opcodes.IF_ICMPNE->Opcodes.IF_ICMPEQ;case Opcodes.IF_ICMPLT->Opcodes.IF_ICMPGE;case Opcodes.IF_ICMPGE->Opcodes.IF_ICMPLT;case Opcodes.IF_ICMPGT->Opcodes.IF_ICMPLE;case Opcodes.IF_ICMPLE->Opcodes.IF_ICMPGT;case Opcodes.IF_ACMPEQ->Opcodes.IF_ACMPNE;case Opcodes.IF_ACMPNE->Opcodes.IF_ACMPEQ;case Opcodes.IFNULL->Opcodes.IFNONNULL;case Opcodes.IFNONNULL->Opcodes.IFNULL;default->opcode;};}
	private static String instruction(AbstractInsnNode i){String value="";if(i instanceof VarInsnNode n)value=""+n.var;else if(i instanceof IincInsnNode n)value=n.var+":"+n.incr;else if(i instanceof FieldInsnNode n)value=n.owner+":"+n.name+n.desc;else if(i instanceof MethodInsnNode n)value=n.owner+":"+n.name+n.desc+":"+n.itf;else if(i instanceof TypeInsnNode n)value=n.desc;else if(i instanceof LdcInsnNode n)value=n.cst.getClass().getName()+":"+n.cst;else if(i instanceof IntInsnNode n)value=""+n.operand;else if(!(i instanceof InsnNode))return "unsupported:"+System.identityHashCode(i);return i.getOpcode()+":"+value;}
	/** A closed return-expression grammar. The only opaque method is the default terminal-type getter: its dispatch
	 * is guarded but is executed by native, never by the guard. No branch, boolean transform, store or effect is accepted. */
	private static final class QueryChain{
		final Function<String,ClassNode>classes;final String type;final List<Guard>guards=new ArrayList<>();final Set<Member>active=new HashSet<>();
		final Map<Value,String>types=new HashMap<>();
		QueryChain(Function<String,ClassNode>classes,String type){this.classes=classes;this.type=type;}
		Contract prove(Member entry){
			try{types.put(new Root(0),entry.owner());types.put(new Root(1),Type.getArgumentTypes(entry.descriptor())[0].getInternalName());Object result=expand(entry,new Root(0),List.of(new Root(1)),false);if(!(result instanceof Terminal terminal)||!(terminal.receiver()instanceof Opaque))return null;
				return new Contract(guards,terminal.name(),terminal.descriptor());}catch(RuntimeException invalid){return null;}
		}
		Object expand(Member member,Value receiver,List<Value>arguments,boolean direct){
			Decl declaration=resolve(member,classes,new HashSet<>());if(declaration==null||!active.add(member))throw new IllegalArgumentException();
			try{
				MethodNode body=declaration.method();if((body.access&(Opcodes.ACC_STATIC|Opcodes.ACC_NATIVE|Opcodes.ACC_ABSTRACT|Opcodes.ACC_SYNCHRONIZED))!=0||!body.tryCatchBlocks.isEmpty())throw new IllegalArgumentException();
				if(member.owner().equals(type)&&Type.getReturnType(member.descriptor()).equals(Type.BOOLEAN_TYPE)){
					List<AbstractInsnNode>code=new ArrayList<>();for(var i:body.instructions)if(i.getOpcode()>=0)code.add(i);
					if(code.size()==3&&code.getFirst()instanceof VarInsnNode load&&load.getOpcode()==Opcodes.ALOAD&&load.var==0
							&&code.get(1)instanceof FieldInsnNode field&&field.getOpcode()==Opcodes.GETFIELD&&field.desc.equals("Z")&&code.getLast().getOpcode()==Opcodes.IRETURN)return new Terminal(receiver,member.name(),member.descriptor());
				}
				guards.add(new Guard(receiver,declaration.contract(),direct||(body.access&Opcodes.ACC_PRIVATE)!=0));
				if(Type.getReturnType(body.desc).equals(Type.getObjectType(type))){if(!arguments.isEmpty())throw new IllegalArgumentException();return new Opaque(receiver);}
				List<Object>stack=new ArrayList<>();Map<Integer,Value>locals=new HashMap<>();locals.put(0,receiver);Type[]parameters=Type.getArgumentTypes(body.desc);int slot=1;for(int i=0;i<parameters.length;i++){locals.put(slot,arguments.get(i));slot+=parameters[i].getSize();}
				Object returned=null;
				for(var insn:body.instructions){
					if(insn.getOpcode()<0)continue;if(returned!=null)throw new IllegalArgumentException();
					if(insn instanceof VarInsnNode load&&load.getOpcode()==Opcodes.ALOAD&&locals.containsKey(load.var))stack.add(locals.get(load.var));
					else if(insn instanceof TypeInsnNode cast&&cast.getOpcode()==Opcodes.CHECKCAST){if(!(peek(stack)instanceof Value value))throw new IllegalArgumentException();types.put(value,cast.desc);}
					else if(insn instanceof FieldInsnNode field&&field.getOpcode()==Opcodes.GETFIELD){Value object=asValue(pop(stack));String owner=fieldOwner(field,classes,new HashSet<>());if(owner==null)throw new IllegalArgumentException();stack.add(new Projection(object,owner,field.name,field.desc));}
					else if(insn instanceof MethodInsnNode call&&call.getOpcode()!=Opcodes.INVOKESTATIC){Type[]passed=Type.getArgumentTypes(call.desc);List<Value>values=new ArrayList<>();for(int i=passed.length-1;i>=0;i--)values.add(0,asValue(pop(stack)));Value object=asValue(pop(stack));String owner=call.owner;
						Decl declared=resolve(new Member(owner,call.name,call.desc),classes,new HashSet<>());if(call.getOpcode()!=Opcodes.INVOKESPECIAL&&declared!=null&&(declared.method().access&Opcodes.ACC_PRIVATE)==0&&types.containsKey(object))owner=types.get(object);
						Object result=expand(new Member(owner,call.name,call.desc),object,values,call.getOpcode()==Opcodes.INVOKESPECIAL);if(result instanceof Value value&&Type.getReturnType(call.desc).getSort()==Type.OBJECT)types.put(value,Type.getReturnType(call.desc).getInternalName());stack.add(result);}
					else if(insn.getOpcode()==Opcodes.ARETURN||insn.getOpcode()==Opcodes.IRETURN){returned=pop(stack);if(!stack.isEmpty())throw new IllegalArgumentException();}
					else throw new IllegalArgumentException();
				}if(returned==null)throw new IllegalArgumentException();return returned;
			}finally{active.remove(member);}
		}
	}
	private static String fieldOwner(FieldInsnNode field,Function<String,ClassNode>classes,Set<String>seen){if(!seen.add(field.owner))return null;ClassNode owner=classes.apply(field.owner);if(owner==null)return null;
		for(FieldNode found:owner.fields)if(found.name.equals(field.name)&&found.desc.equals(field.desc))return(found.access&Opcodes.ACC_FINAL)!=0&&(found.access&Opcodes.ACC_STATIC)==0?owner.name:null;
		return owner.superName==null?null:fieldOwner(new FieldInsnNode(field.getOpcode(),owner.superName,field.name,field.desc),classes,seen);}
	private static Decl resolve(Member member,Function<String,ClassNode>classes,Set<String>seen){if(!seen.add(member.owner()))return null;ClassNode owner=classes.apply(member.owner());if(owner==null)return null;
		for(MethodNode method:owner.methods)if(method.name.equals(member.name())&&method.desc.equals(member.descriptor()))return new Decl(owner.name,method);
		if(owner.superName!=null){Decl inherited=resolve(new Member(owner.superName,member.name(),member.descriptor()),classes,seen);if(inherited!=null)return inherited;}
		Decl found=null;for(String iface:owner.interfaces){Decl inherited=resolve(new Member(iface,member.name(),member.descriptor()),classes,seen);if(inherited!=null){if(found!=null&&!found.equals(inherited))return null;found=inherited;}}return found;
	}
	private static MethodNode wrapper(ClassNode mixin,MethodNode handler,MethodNode source,Plan plan){
		String name=MixinHandlerShim.asideName(mixin.name,handler.name,"$forbricnativequery"),state="L"+plan.atom().predicate().owner+";",host="L"+mixin.name+";";
		MethodNode method=new MethodNode(Opcodes.ACC_PRIVATE,name,"(L"+plan.query().owner+";"+state+"L"+OP+";)Z",null,null);
		AnnotationNode inject=new AnnotationNode(WRAP);inject.values=new ArrayList<>(plan.injector().values);set(inject,"method",plan.methods());AnnotationNode at=new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");at.values=new ArrayList<>(List.of("value","INVOKE","target","L"+plan.query().owner+";"+plan.query().name+plan.query().desc));set(inject,"at",List.of(at));method.visibleAnnotations=new ArrayList<>(List.of(inject));
		method.instructions.add(new VarInsnNode(Opcodes.ALOAD,1));method.instructions.add(new VarInsnNode(Opcodes.ALOAD,2));method.instructions.add(new LdcInsnNode(plan.key()));
		method.instructions.add(new VarInsnNode(Opcodes.ALOAD,3));method.instructions.add(new InsnNode(Opcodes.ICONST_2));method.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY,"java/lang/Object"));
		method.instructions.add(new InsnNode(Opcodes.DUP));method.instructions.add(new InsnNode(Opcodes.ICONST_0));method.instructions.add(new VarInsnNode(Opcodes.ALOAD,1));method.instructions.add(new InsnNode(Opcodes.AASTORE));
		method.instructions.add(new InsnNode(Opcodes.DUP));method.instructions.add(new InsnNode(Opcodes.ICONST_1));method.instructions.add(new VarInsnNode(Opcodes.ALOAD,2));method.instructions.add(new InsnNode(Opcodes.AASTORE));
		method.instructions.add(new InvokeDynamicInsnNode("get","(L"+OP+";[Ljava/lang/Object;)Ljava/util/function/Supplier;",LAMBDA,Type.getMethodType("()Ljava/lang/Object;"),new Handle(Opcodes.H_INVOKEINTERFACE,OP,"call","([Ljava/lang/Object;)Ljava/lang/Object;",true),Type.getMethodType("()Ljava/lang/Boolean;")));
		method.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));method.instructions.add(new VarInsnNode(Opcodes.ALOAD,2));method.instructions.add(new InvokeDynamicInsnNode("getAsBoolean","("+host+state+")Ljava/util/function/BooleanSupplier;",LAMBDA,Type.getMethodType("()Z"),new Handle(Opcodes.H_INVOKEVIRTUAL,mixin.name,source.name,source.desc,false),Type.getMethodType("()Z")));
		method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,RUNTIME,"query","(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Ljava/util/function/Supplier;Ljava/util/function/BooleanSupplier;)Z",false));method.instructions.add(new InsnNode(Opcodes.IRETURN));method.maxLocals=4;method.maxStack=9;return method;
	}
	private static Object pop(List<Object>stack){return stack.removeLast();}private static Object peek(List<Object>stack){return stack.getLast();}private static Value asValue(Object object){if(!(object instanceof Value value))throw new IllegalArgumentException();return value;}
	private static boolean has(MethodNode method,String annotation){return method.visibleAnnotations!=null&&method.visibleAnnotations.stream().anyMatch(a->a.desc.equals(annotation))||method.invisibleAnnotations!=null&&method.invisibleAnnotations.stream().anyMatch(a->a.desc.equals(annotation));}
	private static Member parse(String selector){if(selector==null||!selector.startsWith("L"))return null;int owner=selector.indexOf(';'),desc=selector.indexOf('(',owner);return owner>0&&desc>owner?new Member(selector.substring(1,owner),selector.substring(owner+1,desc),selector.substring(desc)):null;}
	private static boolean same(MethodInsnNode call,Member member){return call.owner.equals(member.owner())&&call.name.equals(member.name())&&call.desc.equals(member.descriptor());}
	private static AbstractInsnNode previous(AbstractInsnNode insn){for(var i=insn.getPrevious();i!=null;i=i.getPrevious())if(i.getOpcode()>=0)return i;return null;}
	private static void set(AnnotationNode annotation,String key,Object value){if(annotation.values==null)annotation.values=new ArrayList<>();for(int i=0;i<annotation.values.size();i+=2)if(annotation.values.get(i).equals(key)){annotation.values.set(i+1,value);return;}annotation.values.add(key);annotation.values.add(value);}
}
