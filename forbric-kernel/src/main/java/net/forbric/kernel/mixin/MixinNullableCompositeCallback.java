package net.forbric.kernel.mixin;

import java.util.*;
import java.util.function.Function;
import net.forbric.api.Ecosystem;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** A no-result fallback callback follows an exactly absent composite lookup; native alternatives retain priority. */
public final class MixinNullableCompositeCallback {
	public static final String PROPERTY="forbric.mixinNullableCompositeCallbacks";
	private static final String INJECT="Lorg/spongepowered/asm/mixin/injection/Inject;",LOCAL="Lcom/llamalad7/mixinextras/sugar/Local;";
	private static final String CIR="Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;",SUFFIX="$forbricnullablecomposite";
	static record Shape(ClassNode type,MethodNode empty){}
	private record Plan(MethodNode host,MethodInsnNode lookup,int slot,Shape shape,MixinNullableLookupContracts.LookupProof proof){}
	public record Installed(String mixin,String owner,String host,String descriptor,String handler,String outerDescriptor,String innerDescriptor){}
	private record Evidence(Installed installed,ClassNode source,MethodNode original,Shape shape,String lookup){}
	private static String recordContract(Shape shape,MixinNullableLookupContracts.LookupProof proof){
		return net.forbric.kernel.boot.KernelCompositeCallbacks.register(shape.type.name,
				shape.type.methods.stream().filter(m->(m.access&(Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE))==0)
					.map(m->new net.forbric.kernel.boot.DefinedMethodContracts.MethodContract(shape.type.name,m.name,m.desc,MixinInstructionFingerprint.hash(m))).toList(),proof.methods(),proof.fields());
	}
	private static final Map<String,Evidence> INSTALLED=new java.util.concurrent.ConcurrentHashMap<>();
	private MixinNullableCompositeCallback(){}
	public static int adapt(ClassNode mixin,Ecosystem ecosystem,Function<String,ClassNode> classes){return adapt(mixin,classes,owner->NativeGameReferences.reference(ecosystem,owner),owner->NativeGameReferences.reference(Ecosystem.NEOFORGE,owner),owner->NativeGameReferences.runtime(Ecosystem.NEOFORGE,owner));}
	static int adapt(ClassNode mixin,Function<String,ClassNode> classes,Function<String,ClassNode> references){
		return adapt(mixin,classes,references,owner->NativeGameReferences.reference(Ecosystem.NEOFORGE,owner),owner->NativeGameReferences.runtime(Ecosystem.NEOFORGE,owner));
	}
	public static int adapt(ClassNode mixin,Function<String,ClassNode> classes,Function<String,ClassNode> references,Function<String,ClassNode> nativeReferences,Function<String,ClassNode> runtimeReferences){
		if("off".equalsIgnoreCase(System.getProperty(PROPERTY,"on")))return 0;
		List<String> targets=MixinFit.mixinTargets(mixin);if(targets.size()!=1)return 0;
		ClassNode target=classes.apply(targets.getFirst()),reference=references.apply(targets.getFirst());if(target==null||reference==null)return 0;
		List<MethodNode> added=new ArrayList<>();
		for(MethodNode handler:new ArrayList<>(mixin.methods)){
			AnnotationNode injection=MixinFit.injectorOf(handler);if(injection==null||!INJECT.equals(injection.desc)||!Boolean.TRUE.equals(MixinFit.value(injection,"cancellable"))
					||MixinFit.value(injection,"slice")!=null||annotations(handler).stream().anyMatch(a->a.desc.equals(MixinRetarget.GROUP)))continue;
			List<AnnotationNode> points=MixinFit.atNodes(injection);List<String> selectors=MixinFit.stringList(MixinFit.value(injection,"method"));
			if(points.size()!=1||selectors.size()!=1||!"INVOKE_ASSIGN".equals(MixinFit.value(points.getFirst(),"value"))
					||MixinFit.value(points.getFirst(),"shift")!=null||MixinFit.value(points.getFirst(),"ordinal")!=null)continue;
			MethodNode original=MixinStubRebind.bound(reference,selectors.getFirst()),host=MixinStubRebind.bound(target,selectors.getFirst());
			if(original==null||host==null||!original.desc.equals(host.desc)||((host.access^handler.access)&Opcodes.ACC_STATIC)!=0)continue;
			Type[] parameters=Type.getArgumentTypes(handler.desc),arguments=Type.getArgumentTypes(host.desc);int capture=arguments.length+1;
			if(parameters.length!=capture+1||!parameters[arguments.length].getDescriptor().equals(CIR)||parameters[capture].getSort()!=Type.OBJECT
					||!Type.getReturnType(handler.desc).equals(Type.VOID_TYPE)||!Arrays.equals(arguments,Arrays.copyOf(parameters,arguments.length)))continue;
			AnnotationNode local=MixinStubRebind.sugar(handler,capture,LOCAL);if(local==null||!onlyLocal(handler,capture)||!nullFallback(handler,capture))continue;
			String member=MixinFit.asString(MixinFit.value(points.getFirst(),"target"));List<MethodInsnNode> nativeCalls=calls(original,member);
			if(nativeCalls.size()!=1||!Type.getReturnType(nativeCalls.getFirst().desc).equals(parameters[capture])||!calls(host,member).isEmpty())continue;
			List<Plan> candidates=new ArrayList<>();
			for(AbstractInsnNode instruction:host.instructions)if(instruction instanceof MethodInsnNode lookup&&Type.getReturnType(lookup.desc).getSort()==Type.OBJECT){
				Shape shape=shape(classes.apply(Type.getReturnType(lookup.desc).getInternalName()),parameters[capture]);if(shape==null)continue;
				AbstractInsnNode store=next(lookup);if(!(store instanceof VarInsnNode variable)||store.getOpcode()!=Opcodes.ASTORE)continue;
				if(!sameLookupInputs(target.name,reference,original,nativeCalls.getFirst(),host,lookup))continue;
				var helpers=MixinNullableLookupContracts.prove(reference,original,nativeCalls.getFirst(),target,host,lookup,shape,classes,references,nativeReferences,runtimeReferences);if(helpers==null)continue;
				candidates.add(new Plan(host,lookup,variable.var,shape,helpers));
			}
			if(candidates.size()!=1)continue;Plan plan=candidates.getFirst();String originalName=handler.name,innerDescriptor=handler.desc;
			ClassNode snapshot=new ClassNode();mixin.accept(snapshot);MethodNode originalBody=snapshot.methods.stream().filter(m->m.name.equals(originalName)&&m.desc.equals(innerDescriptor)).findFirst().orElseThrow();
			MethodNode outer=wrap(mixin,handler,injection,points.getFirst(),capture,plan);added.add(outer);
			Installed installed=new Installed(mixin.name,target.name,host.name,host.desc,originalName,outer.desc,innerDescriptor);
			INSTALLED.put(target.name+"#"+host.name+host.desc+"#"+mixin.name,new Evidence(installed,snapshot,originalBody,plan.shape,"L"+plan.lookup.owner+";"+plan.lookup.name+plan.lookup.desc));
		}
		mixin.methods.addAll(added);return added.size();
	}
	/** A caller can stand an older kernel fallback down only after this precise migrated outer is in the final host. */
	public static void certify(ClassNode target){for(MethodNode host:target.methods)installedOnFinalHost(target,host);}
	public static boolean installedOnFinalHost(ClassNode target,MethodNode host){
		for(Evidence evidence:INSTALLED.values()){
			Installed plan=evidence.installed;
			if(!plan.owner.equals(target.name)||!plan.host.equals(host.name)||!plan.descriptor.equals(host.desc))continue;
			for(MethodNode outer:target.methods){
				if(!outer.desc.equals(plan.outerDescriptor)||!mergedFrom(outer,plan.mixin)||!outer.name.endsWith("$"+plan.handler))continue;
				long entries=Arrays.stream(host.instructions.toArray()).filter(i->i instanceof MethodInsnNode c&&c.owner.equals(target.name)&&c.name.equals(outer.name)&&c.desc.equals(outer.desc)).count();
				List<MethodInsnNode> originals=Arrays.stream(outer.instructions.toArray()).filter(i->i instanceof MethodInsnNode c&&c.owner.equals(target.name)&&c.desc.equals(plan.innerDescriptor)&&c.name.contains(SUFFIX)).map(MethodInsnNode.class::cast).toList();
				if(entries!=1||originals.size()!=1||!outerContract(outer,originals.getFirst(),evidence.shape)||!captureFromLookup(target,host,outer,evidence.lookup))continue;
				MethodNode inner=target.methods.stream().filter(m->m.name.equals(originals.getFirst().name)&&m.desc.equals(plan.innerDescriptor)).findFirst().orElse(null);
				if(inner!=null&&originalBody(target,inner,evidence)){var key=(LdcInsnNode)DefaultMethodOverloadBridge.real(outer).get(1);var guest=target.methods.stream().filter(m->mergedFrom(m,evidence.installed.mixin)).map(m->new net.forbric.kernel.boot.DefinedMethodContracts.MethodContract(target.name,m.name,m.desc,MixinInstructionFingerprint.hash(m))).toList();net.forbric.kernel.boot.KernelCompositeCallbacks.certify((String)key.cst,new net.forbric.kernel.boot.DefinedMethodContracts.MethodContract(target.name,outer.name,outer.desc,MixinInstructionFingerprint.hash(outer)),new net.forbric.kernel.boot.DefinedMethodContracts.MethodContract(target.name,host.name,host.desc,MixinInstructionFingerprint.hash(host)),guest);return true;}
			}
		}return false;
	}
	private static boolean captureFromLookup(ClassNode target,MethodNode host,MethodNode outer,String lookup){
		try{Frame<SourceValue>[] frames=new Analyzer<>(new SourceInterpreter()).analyze(target.name,host);for(var instruction:host.instructions)if(instruction instanceof MethodInsnNode call&&call.owner.equals(target.name)&&call.name.equals(outer.name)&&call.desc.equals(outer.desc)){Frame<SourceValue> at=frames[host.instructions.indexOf(call)];return at!=null&&at.getStackSize()>0&&lookupOrigin(host,frames,at.getStack(at.getStackSize()-1),lookup,new HashSet<>());}return false;}
		catch(AnalyzerException|RuntimeException unproved){return false;}
	}
	private static boolean lookupOrigin(MethodNode host,Frame<SourceValue>[] frames,SourceValue value,String lookup,Set<AbstractInsnNode> active){if(value==null||value.insns.size()!=1)return false;var instruction=value.insns.iterator().next();if(!active.add(instruction))return false;try{Frame<SourceValue> at=frames[host.instructions.indexOf(instruction)];if(at==null)return false;
		if(instruction instanceof MethodInsnNode call)return lookup.equals("L"+call.owner+";"+call.name+call.desc);
		if(instruction instanceof VarInsnNode variable){if(variable.getOpcode()==Opcodes.ALOAD)return lookupOrigin(host,frames,at.getLocal(variable.var),lookup,active);if(variable.getOpcode()==Opcodes.ASTORE)return lookupOrigin(host,frames,at.getStack(at.getStackSize()-1),lookup,active);}
		if(instruction instanceof TypeInsnNode cast&&cast.getOpcode()==Opcodes.CHECKCAST)return lookupOrigin(host,frames,at.getStack(at.getStackSize()-1),lookup,active);return false;
	}finally{active.remove(instruction);}}
	private static boolean outerContract(MethodNode outer,MethodInsnNode inner,Shape shape){
		List<AbstractInsnNode> code=DefaultMethodOverloadBridge.real(outer);Type[] args=Type.getArgumentTypes(outer.desc);boolean isStatic=(outer.access&Opcodes.ACC_STATIC)!=0;int capture=args.length-1,offset=isStatic?0:1;
		if(code.size()!=capture+10+offset||!(code.get(0)instanceof VarInsnNode value)||value.getOpcode()!=Opcodes.ALOAD||value.var!=DefaultMethodOverloadBridge.slots(args,isStatic)[capture]
				||!(code.get(1)instanceof LdcInsnNode key)||!(key.cst instanceof String token)||!net.forbric.kernel.boot.KernelCompositeCallbacks.provesShape(token,shape.type.name,
					shape.type.methods.stream().filter(m->(m.access&(Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE))==0).map(m->new net.forbric.kernel.boot.DefinedMethodContracts.MethodContract(shape.type.name,m.name,m.desc,MixinInstructionFingerprint.hash(m))).toList())
				||!(code.get(2)instanceof MethodInsnNode proof)||!proof.owner.equals("net/forbric/kernel/boot/KernelCompositeCallbacks")||!proof.name.equals("permits")||!proof.desc.equals("(Ljava/lang/Object;Ljava/lang/String;)Z")
				||!(code.get(3)instanceof JumpInsnNode guard)||guard.getOpcode()!=Opcodes.IFEQ||next(guard.label)!=code.getLast()
				||!(code.get(4)instanceof VarInsnNode reload)||reload.getOpcode()!=Opcodes.ALOAD||reload.var!=value.var
				||!(code.get(5)instanceof MethodInsnNode empty)||empty.getOpcode()!=Opcodes.INVOKEVIRTUAL||!empty.owner.equals(shape.type.name)||!empty.name.equals(shape.empty.name)||!empty.desc.equals("()Z")
				||!(code.get(6)instanceof JumpInsnNode branch)||branch.getOpcode()!=Opcodes.IFEQ||next(branch.label)!=code.getLast()||code.getLast().getOpcode()!=Opcodes.RETURN)return false;
		int at=7;int[] slots=DefaultMethodOverloadBridge.slots(args,isStatic);if(!isStatic){if(!(code.get(at++)instanceof VarInsnNode self)||self.getOpcode()!=Opcodes.ALOAD||self.var!=0)return false;}
		for(int i=0;i<capture;i++)if(!(code.get(at++)instanceof VarInsnNode load)||load.getOpcode()!=args[i].getOpcode(Opcodes.ILOAD)||load.var!=slots[i])return false;
		return code.get(at++).getOpcode()==Opcodes.ACONST_NULL&&code.get(at)==inner;
	}
	/** Mixin's own merge metadata supplies aliases; a changed original body/helper cannot disable the older fallback. */
	private static boolean originalBody(ClassNode target,MethodNode actual,Evidence evidence){
		Map<String,String> aliases=new HashMap<>();aliases.put(actual.name+actual.desc,evidence.original.name);
		Set<String> seen=new HashSet<>();return sameSourceBody(target,actual,evidence.original,evidence,aliases,seen);
	}
	private static boolean sameSourceBody(ClassNode target,MethodNode actual,MethodNode expected,Evidence evidence,Map<String,String> aliases,Set<String> seen){
		if(!seen.add(expected.name+expected.desc))return true;
		Set<String> referenced=new HashSet<>();for(var instruction:expected.instructions){if(instruction instanceof MethodInsnNode call&&call.owner.equals(evidence.source.name))referenced.add(call.name+call.desc);if(instruction instanceof InvokeDynamicInsnNode dynamic)for(Object argument:dynamic.bsmArgs)if(argument instanceof Handle handle&&handle.getOwner().equals(evidence.source.name))referenced.add(handle.getName()+handle.getDesc());}
		for(String member:referenced){MethodNode source=evidence.source.methods.stream().filter(m->(m.name+m.desc).equals(member)).findFirst().orElse(null);if(source==null)return false;List<MethodNode> candidates=target.methods.stream().filter(m->m.desc.equals(source.desc)&&m.name.contains(source.name)&&mergedFrom(m,evidence.installed.mixin)).toList();if(candidates.size()!=1)return false;MethodNode candidate=candidates.getFirst();aliases.put(candidate.name+candidate.desc,source.name);if(!sameSourceBody(target,candidate,source,evidence,aliases,seen))return false;}
		MethodNode normalized=new MethodNode(actual.access,actual.name,actual.desc,null,null);actual.accept(normalized);
		boolean ownCast=Arrays.stream(expected.instructions.toArray()).anyMatch(i->i instanceof TypeInsnNode cast&&cast.desc.equals(evidence.source.name));
		if(ownCast&&Arrays.stream(expected.instructions.toArray()).anyMatch(i->i instanceof TypeInsnNode cast&&cast.desc.equals(target.name)))return false;
		for(var instruction:normalized.instructions){
			if(instruction instanceof MethodInsnNode call&&call.owner.equals(target.name)&&aliases.containsKey(call.name+call.desc)){call.name=aliases.get(call.name+call.desc);call.owner=evidence.source.name;}
			else if(instruction instanceof FieldInsnNode field&&field.owner.equals(target.name)&&evidence.source.fields.stream().anyMatch(f->f.name.equals(field.name)&&f.desc.equals(field.desc)))field.owner=evidence.source.name;
			else if(instruction instanceof TypeInsnNode cast&&ownCast&&cast.desc.equals(target.name))cast.desc=evidence.source.name;
			else if(instruction instanceof InvokeDynamicInsnNode dynamic){
				// MethodNode.accept copies the node but shares its bootstrap argument array. Proof normalization must
				// never retarget the executable lambda in the actual class.
				dynamic.bsmArgs=dynamic.bsmArgs.clone();
				for(int i=0;i<dynamic.bsmArgs.length;i++)if(dynamic.bsmArgs[i]instanceof Handle handle&&handle.getOwner().equals(target.name)&&aliases.containsKey(handle.getName()+handle.getDesc()))dynamic.bsmArgs[i]=new Handle(handle.getTag(),evidence.source.name,aliases.get(handle.getName()+handle.getDesc()),handle.getDesc(),handle.isInterface());
			}
		}
		return MixinInstructionFingerprint.hash(normalized).equals(MixinInstructionFingerprint.hash(expected));
	}
	private static boolean mergedFrom(MethodNode method,String mixin){return annotations(method).stream().anyMatch(a->a.desc.equals("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;")&&mixin.replace('/','.').equals(MixinFit.value(a,"mixin")));}
	private static boolean onlyLocal(MethodNode handler,int capture){for(int i=0;i<Type.getArgumentTypes(handler.desc).length;i++)if(MixinFit.sugar(handler,i)&&i!=capture)return false;return true;}
	private static boolean nullFallback(MethodNode handler,int capture){
		List<AbstractInsnNode> code=DefaultMethodOverloadBridge.real(handler);int[] slots=DefaultMethodOverloadBridge.slots(Type.getArgumentTypes(handler.desc),(handler.access&Opcodes.ACC_STATIC)!=0);
		if(code.size()<4||!(code.get(0)instanceof VarInsnNode load)||load.getOpcode()!=Opcodes.ALOAD||load.var!=slots[capture]
				||!(code.get(1)instanceof JumpInsnNode jump)||jump.getOpcode()!=Opcodes.IFNULL||next(jump.label)!=code.get(3)||code.get(2).getOpcode()!=Opcodes.RETURN)return false;
		for(AbstractInsnNode instruction:code.subList(3,code.size()))if(instruction instanceof VarInsnNode variable&&variable.var==slots[capture]||instruction instanceof IincInsnNode increment&&increment.var==slots[capture])return false;
		return true;
	}
	private static Shape shape(ClassNode type,Type old){
		if(type==null||!"java/lang/Record".equals(type.superName)||(type.access&Opcodes.ACC_FINAL)==0)return null;
		List<FieldNode> fields=type.fields.stream().filter(f->(f.access&Opcodes.ACC_STATIC)==0).toList();
		if(fields.size()<2||fields.size()>4||fields.stream().anyMatch(f->(f.access&Opcodes.ACC_FINAL)==0||Type.getType(f.desc).getSort()!=Type.OBJECT&&Type.getType(f.desc).getSort()!=Type.ARRAY)
				||fields.stream().filter(f->f.desc.equals(old.getDescriptor())).count()!=1)return null;
		List<MethodNode> empty=type.methods.stream().filter(m->m.desc.equals("()Z")&&(m.access&(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC))==Opcodes.ACC_PUBLIC&&m.tryCatchBlocks.isEmpty()&&emptyTruth(type,m,fields)).toList();
		return empty.size()==1?new Shape(type,empty.getFirst()):null;
	}
	/** A closed evaluator proves all nullness combinations, not an empty-sounding name or one observed return. */
	private static boolean emptyTruth(ClassNode owner,MethodNode method,List<FieldNode> fields){
		List<AbstractInsnNode> code=DefaultMethodOverloadBridge.real(method);if(code.size()>64)return false;
		for(int mask=0;mask<(1<<fields.size());mask++){
			List<Integer> stack=new ArrayList<>();int pc=0,steps=0;Integer answer=null;
			while(pc>=0&&pc<code.size()&&steps++<64){AbstractInsnNode instruction=code.get(pc);int opcode=instruction.getOpcode();
				if(instruction instanceof VarInsnNode load&&opcode==Opcodes.ALOAD&&load.var==0)stack.add(-1);
				else if(instruction instanceof FieldInsnNode field&&opcode==Opcodes.GETFIELD&&field.owner.equals(owner.name)&&!stack.isEmpty()&&stack.removeLast()==-1){int index=-1;for(int i=0;i<fields.size();i++)if(fields.get(i).name.equals(field.name)&&fields.get(i).desc.equals(field.desc))index=i;if(index<0)return false;stack.add((mask>>index)&1);}
				else if(instruction instanceof JumpInsnNode branch){if(opcode==Opcodes.GOTO){pc=code.indexOf(next(branch.label));continue;}if(opcode!=Opcodes.IFNULL&&opcode!=Opcodes.IFNONNULL||stack.isEmpty())return false;int value=stack.removeLast();if(value<0)return false;if((value==0)==(opcode==Opcodes.IFNULL)){pc=code.indexOf(next(branch.label));continue;}}
				else if(opcode==Opcodes.ICONST_0||opcode==Opcodes.ICONST_1)stack.add(opcode-Opcodes.ICONST_0);
				else if(opcode==Opcodes.IRETURN&&!stack.isEmpty()){answer=stack.removeLast();if(!stack.isEmpty())return false;break;}
				else return false;pc++;
			}if(answer==null||answer!=(mask==0?1:0))return false;
		}return true;
	}
	private static boolean sameLookupInputs(String owner,ClassNode reference,MethodNode original,MethodInsnNode old,MethodNode host,MethodInsnNode current){
		try{
			Keys before=new Keys(owner,original,null),after=new Keys(owner,host,null);List<String> expected=before.arguments(old),actual=after.arguments(current);if(expected!=null&&old.getOpcode()==current.getOpcode()&&Arrays.equals(Type.getArgumentTypes(old.desc),Type.getArgumentTypes(current.desc))&&expected.equals(actual))return true;
			if(!old.owner.equals(reference.name)||old.getOpcode()!=Opcodes.INVOKESTATIC)return false;
			MethodNode helper=reference.methods.stream().filter(m->m.name.equals(old.name)&&m.desc.equals(old.desc)).findFirst().orElse(null);if(helper==null||expected==null)return false;
			Map<Integer,String> parameters=new HashMap<>();int slot=0;Type[] args=Type.getArgumentTypes(helper.desc);for(int i=0;i<args.length;i++){parameters.put(slot,expected.get(i));slot+=args[i].getSize();}
			Keys inside=new Keys(owner,helper,parameters);List<MethodInsnNode> matches=new ArrayList<>();for(var instruction:helper.instructions)if(instruction instanceof MethodInsnNode call&&call.getOpcode()==current.getOpcode()&&Type.getReturnType(call.desc).equals(Type.getReturnType(old.desc))&&prefix(Type.getArgumentTypes(call.desc),Type.getArgumentTypes(current.desc))&&actual!=null&&Objects.equals(inside.arguments(call),actual.subList(0,Type.getArgumentTypes(call.desc).length+(call.getOpcode()==Opcodes.INVOKESTATIC?0:1))))matches.add(call);
			return actual!=null&&matches.size()==1;
		}catch(AnalyzerException|RuntimeException unknown){return false;}
	}
	private static boolean prefix(Type[] original,Type[] current){return original.length<=current.length&&Arrays.equals(original,Arrays.copyOf(current,original.length));}
		static final class Keys{
		final MethodNode method;final Frame<SourceValue>[] frames;final Map<Integer,String> parameters=new HashMap<>();
		Keys(String owner,MethodNode method,Map<Integer,String> mapped)throws AnalyzerException{this.method=method;int slot=(method.access&Opcodes.ACC_STATIC)==0?1:0,index=0;if(slot==1)parameters.put(0,"this");for(Type type:Type.getArgumentTypes(method.desc)){parameters.put(slot,"parameter:"+index+++":"+type.getDescriptor());slot+=type.getSize();}if(mapped!=null)parameters.putAll(mapped);frames=new Analyzer<>(new SourceInterpreter()).analyze(owner,method);}
		List<String> arguments(MethodInsnNode call){Frame<SourceValue> frame=frames[method.instructions.indexOf(call)];int count=Type.getArgumentTypes(call.desc).length+(call.getOpcode()==Opcodes.INVOKESTATIC?0:1);if(frame==null||frame.getStackSize()<count)return null;List<String> result=new ArrayList<>();for(int i=frame.getStackSize()-count;i<frame.getStackSize();i++){String key=key(frame.getStack(i),-1,new HashSet<>());if(key==null)return null;result.add(key);}return result;}
		String key(SourceValue value,int parameter,Set<AbstractInsnNode> active){if(value==null)return null;if(value.insns.isEmpty())return parameters.get(parameter);if(value.insns.size()!=1)return null;AbstractInsnNode instruction=value.insns.iterator().next();if(!active.add(instruction))return null;try{Frame<SourceValue> at=frames[method.instructions.indexOf(instruction)];if(at==null)return null;int opcode=instruction.getOpcode();if(instruction instanceof VarInsnNode variable){if(opcode>=Opcodes.ILOAD&&opcode<=Opcodes.ALOAD)return key(at.getLocal(variable.var),variable.var,active);if(opcode>=Opcodes.ISTORE&&opcode<=Opcodes.ASTORE)return key(at.getStack(at.getStackSize()-1),-1,active);return null;}if(instruction instanceof FieldInsnNode field&&(opcode==Opcodes.GETFIELD||opcode==Opcodes.GETSTATIC)){String receiver=opcode==Opcodes.GETSTATIC?"static":key(at.getStack(at.getStackSize()-1),-1,active);return receiver==null?null:receiver+"/field:"+field.owner+":"+field.name+field.desc;}if(instruction instanceof MethodInsnNode call){List<String> args=new ArrayList<>();int count=Type.getArgumentTypes(call.desc).length+(opcode==Opcodes.INVOKESTATIC?0:1);for(int i=at.getStackSize()-count;i<at.getStackSize();i++){String key=key(at.getStack(i),-1,active);if(key==null)return null;args.add(key);}return "call:"+call.owner+":"+call.name+call.desc+"("+String.join(",",args)+")";}if(opcode>=Opcodes.IADD&&opcode<=Opcodes.DREM||opcode>=Opcodes.ISHL&&opcode<=Opcodes.LXOR){String left=key(at.getStack(at.getStackSize()-2),-1,active),right=key(at.getStack(at.getStackSize()-1),-1,active);return left==null||right==null?null:"binary:"+opcode+"("+left+","+right+")";}if(instruction instanceof TypeInsnNode cast&&opcode==Opcodes.CHECKCAST)return key(at.getStack(at.getStackSize()-1),-1,active);if(opcode>=Opcodes.I2L&&opcode<=Opcodes.I2S){String operand=key(at.getStack(at.getStackSize()-1),-1,active);return operand==null?null:"convert:"+opcode+"("+operand+")";}if(instruction instanceof LdcInsnNode literal)return"constant:"+literal.cst;if(opcode>=Opcodes.ICONST_M1&&opcode<=Opcodes.DCONST_1||opcode==Opcodes.ACONST_NULL)return"constant:"+opcode;return null;}finally{active.remove(instruction);}}
	}
	private static MethodNode wrap(ClassNode mixin,MethodNode handler,AnnotationNode injection,AnnotationNode point,int capture,Plan plan){
		String original=handler.name,inner=MixinHandlerShim.asideName(mixin.name,original,SUFFIX);Type[] args=Type.getArgumentTypes(handler.desc);args[capture]=Type.getObjectType(plan.shape.type.name);
		MethodNode outer=new MethodNode(handler.access,original,Type.getMethodDescriptor(Type.VOID_TYPE,args),null,handler.exceptions.toArray(String[]::new));
		outer.visibleAnnotations=handler.visibleAnnotations==null?null:new ArrayList<>(handler.visibleAnnotations);outer.invisibleAnnotations=handler.invisibleAnnotations==null?null:new ArrayList<>(handler.invisibleAnnotations);
		outer.visibleParameterAnnotations=handler.visibleParameterAnnotations;outer.invisibleParameterAnnotations=handler.invisibleParameterAnnotations;
		put(injection,"method",List.of(plan.host.name+plan.host.desc));
		put(point,"target","L"+plan.lookup.owner+";"+plan.lookup.name+plan.lookup.desc);AnnotationNode local=MixinStubRebind.sugar(outer,capture,LOCAL);put(local,"index",plan.slot);remove(local,"name");remove(local,"ordinal");remove(local,"argsOnly");
		int[] slots=DefaultMethodOverloadBridge.slots(args,(handler.access&Opcodes.ACC_STATIC)!=0);LabelNode done=new LabelNode();
			outer.instructions.add(new VarInsnNode(Opcodes.ALOAD,slots[capture]));outer.instructions.add(new LdcInsnNode(recordContract(plan.shape,plan.proof)));
		outer.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"net/forbric/kernel/boot/KernelCompositeCallbacks","permits","(Ljava/lang/Object;Ljava/lang/String;)Z",false));outer.instructions.add(new JumpInsnNode(Opcodes.IFEQ,done));
		outer.instructions.add(new VarInsnNode(Opcodes.ALOAD,slots[capture]));outer.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,plan.shape.type.name,plan.shape.empty.name,plan.shape.empty.desc,false));outer.instructions.add(new JumpInsnNode(Opcodes.IFEQ,done));
		if((handler.access&Opcodes.ACC_STATIC)==0)outer.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));for(int i=0;i<capture;i++)outer.instructions.add(new VarInsnNode(args[i].getOpcode(Opcodes.ILOAD),slots[i]));outer.instructions.add(new InsnNode(Opcodes.ACONST_NULL));outer.instructions.add(MixinHandlerShim.callOwn(mixin,(handler.access&Opcodes.ACC_STATIC)!=0,inner,handler.desc));outer.instructions.add(done);outer.instructions.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));outer.instructions.add(new InsnNode(Opcodes.RETURN));outer.maxLocals=slots[capture]+1;outer.maxStack=outer.maxLocals+1;
		handler.name=inner;if(handler.visibleAnnotations!=null)handler.visibleAnnotations.remove(injection);if(handler.invisibleAnnotations!=null)handler.invisibleAnnotations.remove(injection);handler.visibleParameterAnnotations=null;handler.invisibleParameterAnnotations=null;return outer;
	}
	private static List<AnnotationNode> annotations(MethodNode method){List<AnnotationNode> out=new ArrayList<>();if(method.visibleAnnotations!=null)out.addAll(method.visibleAnnotations);if(method.invisibleAnnotations!=null)out.addAll(method.invisibleAnnotations);return out;}
	private static List<MethodInsnNode> calls(MethodNode method,String member){List<MethodInsnNode> out=new ArrayList<>();for(var instruction:method.instructions)if(instruction instanceof MethodInsnNode call&&("L"+call.owner+";"+call.name+call.desc).equals(member))out.add(call);return out;}
	private static AbstractInsnNode next(AbstractInsnNode instruction){for(var next=instruction.getNext();next!=null;next=next.getNext())if(next.getOpcode()>=0)return next;return null;}
	private static void put(AnnotationNode node,String name,Object value){if(node.values==null)node.values=new ArrayList<>();for(int i=0;i<node.values.size();i+=2)if(name.equals(node.values.get(i))){node.values.set(i+1,value);return;}node.values.add(name);node.values.add(value);}
	private static void remove(AnnotationNode node,String name){if(node.values!=null)for(int i=node.values.size()-2;i>=0;i-=2)if(name.equals(node.values.get(i))){node.values.remove(i+1);node.values.remove(i);}}
}
