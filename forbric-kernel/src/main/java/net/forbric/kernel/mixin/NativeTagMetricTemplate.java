/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;

/** A closed native per-cell metric region with variable renaming proved against current eye/height/flow inputs. */
final class NativeTagMetricTemplate {
	record Input(int sourceSlot,int currentSlot,Type type){ }
	record Template(MethodNode source,MethodNode current,MethodInsnNode typeGetter,MethodInsnNode fluidGetter,
			MethodInsnNode flowGetter,AbstractInsnNode cellStart,AbstractInsnNode cellEnd,MethodNode metric,
			List<Input>inputs,String tracker,String fluid,String vector,int currentTrackerSlot,int currentNoPushSlot){ }
	private NativeTagMetricTemplate(){ }
	static Template derive(ClassNode original,ClassNode current,String tracker,String metricName){
		List<MethodNode>sources=original.methods.stream().filter(m->{Type[]a=Type.getArgumentTypes(m.desc);return a.length==2&&a[0].getSort()==Type.OBJECT&&a[1].equals(Type.BOOLEAN_TYPE)&&Type.getReturnType(m.desc).equals(Type.VOID_TYPE);}).toList();
		List<Template>proved=new ArrayList<>();for(MethodNode source:sources){Type actor=Type.getArgumentTypes(source.desc)[0];for(MethodNode live:current.methods){Type[]a=Type.getArgumentTypes(live.desc);if(live.name.equals(source.name)&&a.length==2&&a[0].equals(actor)&&a[1].equals(Type.getObjectType("java/util/function/Predicate"))){Template template=one(source,live,tracker,metricName);if(template!=null)proved.add(template);}}}
		return proved.size()==1?proved.getFirst():null;
	}
	private static Template one(MethodNode source,MethodNode current,String tracker,String name){try{
		if(!source.tryCatchBlocks.isEmpty()||!current.tryCatchBlocks.isEmpty())return null;
		FieldInsnNode oldHeight=height(source,tracker),newHeight=height(current,tracker),oldEye=eye(source,tracker),newEye=eye(current,tracker);if(oldHeight==null||newHeight==null||oldEye==null||newEye==null||!member(oldHeight).equals(member(newHeight))||!member(oldEye).equals(member(newEye)))return null;
		List<AbstractInsnNode>oldHeightCode=back(oldHeight,8),newHeightCode=back(newHeight,8);Map<Integer,Integer>variables=new HashMap<>();if(!same(oldHeightCode,newHeightCode,variables))return null;
		if(!(oldHeightCode.getFirst()instanceof VarInsnNode oldTracker)||!(newHeightCode.getFirst()instanceof VarInsnNode newTracker))return null;
		JumpInsnNode oldNull=nullGuard(source,oldEye,oldTracker.var),newNull=nullGuard(current,newEye,newTracker.var);if(oldNull==null||newNull==null)return null;
		AbstractInsnNode oldEyeStart=firstIntegerLoad(next(oldNull),oldEye),newEyeStart=firstIntegerLoad(next(newNull),newEye);if(oldEyeStart==null||newEyeStart==null)return null;
		if(!same(code(oldEyeStart,oldEye),code(newEyeStart,newEye),variables))return null;
		for(var instruction:code(oldEyeStart,oldEye))if(instruction instanceof JumpInsnNode jump&&next(jump.label)!=oldHeightCode.getFirst())return null;
		for(var instruction:code(newEyeStart,newEye))if(instruction instanceof JumpInsnNode jump&&next(jump.label)!=newHeightCode.getFirst())return null;
		var oldPush=next(oldHeight);var newPush=next(newHeight);if(!(oldPush instanceof VarInsnNode oldBool&&oldBool.getOpcode()==Opcodes.ILOAD)||!(newPush instanceof VarInsnNode newBool&&newBool.getOpcode()==Opcodes.ILOAD)||!(next(oldPush)instanceof JumpInsnNode oldSkip)||!(next(newPush)instanceof JumpInsnNode newSkip)||oldSkip.getOpcode()!=Opcodes.IFNE||newSkip.getOpcode()!=Opcodes.IFNE)return null;
		if(!bind(variables,oldBool.var,newBool.var))return null;
		MethodInsnNode oldFlow=flow(source,oldHeight,oldSkip),newFlow=flow(current,newHeight,newSkip);if(oldFlow==null||newFlow==null||!call(oldFlow).equals(call(newFlow))||!same(back(oldFlow,4),back(newFlow,4),variables))return null;
		AbstractInsnNode oldEnd=next(oldSkip.label),newEnd=next(newSkip.label);if(!(oldEnd instanceof IincInsnNode)||!(newEnd instanceof IincInsnNode))return null;
		AbstractInsnNode oldStart=previous(oldNull),newStart=previous(newNull);List<AbstractInsnNode>tail=codeUntil(oldStart,oldEnd);if(tail.isEmpty())return null;
		List<AbstractInsnNode>removed=back(oldFlow,4);Set<AbstractInsnNode>exclude=new HashSet<>(removed);Set<Integer>written=new HashSet<>();Map<Integer,Type>free=new LinkedHashMap<>();
		for(var instruction:tail){if(exclude.contains(instruction))continue;if(instruction instanceof VarInsnNode variable){if(variable.getOpcode()>=Opcodes.ISTORE&&variable.getOpcode()<=Opcodes.ASTORE)written.add(variable.var);else if(variable.getOpcode()>=Opcodes.ILOAD&&variable.getOpcode()<=Opcodes.ALOAD&&!written.contains(variable.var)){Type type=variable.getOpcode()==Opcodes.ALOAD?Type.getObjectType(tracker):variable.getOpcode()==Opcodes.DLOAD?Type.DOUBLE_TYPE:variable.getOpcode()==Opcodes.FLOAD?Type.FLOAT_TYPE:variable.getOpcode()==Opcodes.LLOAD?Type.LONG_TYPE:Type.INT_TYPE;free.putIfAbsent(variable.var,type);}}}
		if(free.keySet().stream().anyMatch(slot->!variables.containsKey(slot)))return null;String vector=Type.getReturnType(oldFlow.desc).getInternalName();List<Input>inputs=new ArrayList<>();List<Type>arguments=new ArrayList<>();Map<Integer,Integer>slots=new HashMap<>();int slot=0;
		List<IincInsnNode>oldLoops=new ArrayList<>(),newLoops=new ArrayList<>();for(var i:source.instructions)if(i instanceof IincInsnNode increment)oldLoops.add(increment);for(var i:current.instructions)if(i instanceof IincInsnNode increment)newLoops.add(increment);
		if(oldLoops.size()!=newLoops.size())return null;for(int i=0;i<oldLoops.size();i++)if(oldLoops.get(i).incr!=newLoops.get(i).incr||!bind(variables,oldLoops.get(i).var,newLoops.get(i).var))return null;
		Origins oldOrigins=new Origins(source,Map.of()),newOrigins=new Origins(current,inverse(variables));
		for(Integer local:free.keySet())if(local!=oldTracker.var&&local!=oldBool.var&&!oldOrigins.local(oldStart,local).equals(newOrigins.local(newStart,variables.get(local))))return null;
		for(var entry:free.entrySet()){slots.put(entry.getKey(),slot);inputs.add(new Input(entry.getKey(),variables.get(entry.getKey()),entry.getValue()));arguments.add(entry.getValue());slot+=entry.getValue().getSize();}int raw=slot;arguments.add(Type.getObjectType(vector));slot++;
		for(Integer local:written)if(!slots.containsKey(local)){slots.put(local,slot);slot++;}
		MethodNode metric=new MethodNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC,name,Type.getMethodDescriptor(Type.VOID_TYPE,arguments.toArray(Type[]::new)),null,null);
		Map<LabelNode,LabelNode>labels=new IdentityHashMap<>();for(var instruction:tail)if(instruction instanceof LabelNode label)labels.put(label,new LabelNode());LabelNode end=new LabelNode();
		for(var instruction:tail)if(instruction instanceof JumpInsnNode jump&&!labels.containsKey(jump.label))labels.put(jump.label,end);
		for(var instruction:tail){if(instruction instanceof FrameNode||instruction instanceof LineNumberNode)continue;
			if(instruction==removed.getFirst())metric.instructions.add(new VarInsnNode(Opcodes.ALOAD,raw));if(exclude.contains(instruction))continue;
			AbstractInsnNode copy=instruction.clone(labels);if(copy instanceof VarInsnNode variable)variable.var=slots.get(variable.var);
			metric.instructions.add(copy);
		}metric.instructions.add(end);metric.instructions.add(new InsnNode(Opcodes.RETURN));metric.maxLocals=slot;metric.maxStack=8;
		MethodInsnNode typeGetter=null,fluidGetter=null;for(var instruction:current.instructions)if(instruction instanceof MethodInsnNode candidate&&Type.getReturnType(candidate.desc).getSort()==Type.OBJECT&&Type.getReturnType(candidate.desc).getInternalName().equals(ForeignType.FLUID_TYPE.internal(Ecosystem.NEOFORGE))){
			if(previous(candidate)instanceof MethodInsnNode fluid&&Type.getArgumentTypes(fluid.desc).length==0&&Type.getReturnType(fluid.desc).getSort()==Type.OBJECT){if(typeGetter!=null)return null;typeGetter=candidate;fluidGetter=fluid;}}
		if(typeGetter==null)return null;
		AbstractInsnNode cellStart=null;for(var instruction:current.instructions)if(instruction instanceof MethodInsnNode getter&&Type.getReturnType(getter.desc).equals(Type.getObjectType(newFlow.owner))&&Type.getArgumentTypes(getter.desc).length==1){List<AbstractInsnNode>loads=back(getter,3);if(loads.size()!=3||!(loads.getFirst()instanceof VarInsnNode)||!(loads.get(1)instanceof VarInsnNode)||cellStart!=null)return null;cellStart=loads.getFirst();}
		if(cellStart==null)return null;
		return new Template(source,current,typeGetter,fluidGetter,newFlow,cellStart,newEnd,metric,List.copyOf(inputs),tracker,Type.getReturnType(fluidGetter.desc).getInternalName(),vector,newTracker.var,newBool.var);
	}catch(RuntimeException|AnalyzerException malformed){return null;}}
	private static Map<Integer,Integer>inverse(Map<Integer,Integer>source){Map<Integer,Integer>result=new HashMap<>();source.forEach((a,b)->result.put(b,a));return result;}
	private static final class Stable extends SourceInterpreter{
		Stable(){super(Opcodes.ASM9);}
		@Override public SourceValue copyOperation(AbstractInsnNode instruction,SourceValue value){return value;}
		@Override public SourceValue newParameterValue(boolean instance,int local,Type type){return new SourceValue(type.getSize(),new VarInsnNode(type.getOpcode(Opcodes.ILOAD),local));}
	}
	private static final class Origins{
		final MethodNode method;final Frame<SourceValue>[]frames;final Map<Integer,Integer>rename;
		Origins(MethodNode method,Map<Integer,Integer>rename)throws AnalyzerException{this.method=method;this.rename=rename;frames=new Analyzer<>(new Stable()).analyze("source/Physical",method);}
		String local(AbstractInsnNode at,int slot){Frame<SourceValue>frame=frames[method.instructions.indexOf(at)];return frame==null?"unreachable":value(frame.getLocal(slot),new HashSet<>());}
		String value(SourceValue source,Set<AbstractInsnNode>active){List<String>roots=new ArrayList<>();for(var i:source.insns){if(!active.add(i)){roots.add("cycle");continue;}try{
			if(i instanceof VarInsnNode parameter&&!method.instructions.contains(i)){roots.add("argument:"+parameter.var+":"+parameter.getOpcode());continue;}
			if(i instanceof IincInsnNode increment){roots.add("increment:"+rename.getOrDefault(increment.var,increment.var)+":"+increment.incr);continue;}
			Frame<SourceValue>frame=frames[method.instructions.indexOf(i)];String operation=""+i.getOpcode();int operands=0;
			if(i instanceof MethodInsnNode call){operation+="/"+call(call);operands=Type.getArgumentTypes(call.desc).length+(call.getOpcode()==Opcodes.INVOKESTATIC?0:1);}
			else if(i instanceof FieldInsnNode field){operation+="/"+member(field);operands=field.getOpcode()==Opcodes.GETFIELD?1:0;}
			else if(i instanceof TypeInsnNode allocation)operation+="/"+allocation.desc;
			else if(i instanceof LdcInsnNode constant)operation+="/"+constant.cst;
			else if(i instanceof IntInsnNode constant)operation+="/"+constant.operand;
			else if(i.getOpcode()==Opcodes.DADD||i.getOpcode()==Opcodes.DSUB||i.getOpcode()==Opcodes.IADD||i.getOpcode()==Opcodes.ISUB)operands=2;
			else if(i.getOpcode()==Opcodes.I2D||i.getOpcode()==Opcodes.F2D)operands=1;
			else if(!(i instanceof InsnNode))throw new IllegalArgumentException();
			List<String>arguments=new ArrayList<>();for(int j=operands;j>0;j--)arguments.add(value(frame.getStack(frame.getStackSize()-j),active));roots.add(operation+arguments);
		}finally{active.remove(i);}}Collections.sort(roots);return roots.toString();}
	}
	private static FieldInsnNode height(MethodNode method,String tracker){FieldInsnNode found=null;for(var i:method.instructions)if(i instanceof FieldInsnNode f&&f.getOpcode()==Opcodes.PUTFIELD&&f.owner.equals(tracker)&&f.desc.equals("D")&&previous(f)instanceof MethodInsnNode call&&call.owner.equals("java/lang/Math")&&call.name.equals("max")&&call.desc.equals("(DD)D")){if(found!=null)return null;found=f;}return found;}
	private static FieldInsnNode eye(MethodNode method,String tracker){FieldInsnNode found=null;for(var i:method.instructions)if(i instanceof FieldInsnNode f&&f.getOpcode()==Opcodes.PUTFIELD&&f.owner.equals(tracker)&&f.desc.equals("Z")&&previous(f).getOpcode()==Opcodes.ICONST_1){if(found!=null)return null;found=f;}return found;}
	private static JumpInsnNode nullGuard(MethodNode method,AbstractInsnNode before,int tracker){for(var i=before.getPrevious();i!=null;i=i.getPrevious())if(i instanceof JumpInsnNode jump&&(jump.getOpcode()==Opcodes.IFNULL||jump.getOpcode()==Opcodes.IFNONNULL)&&previous(jump)instanceof VarInsnNode load&&load.getOpcode()==Opcodes.ALOAD&&load.var==tracker)return jump;return null;}
	private static AbstractInsnNode firstIntegerLoad(AbstractInsnNode start,AbstractInsnNode end){for(var i=start;i!=null&&i!=end;i=i.getNext())if(i.getOpcode()==Opcodes.ILOAD)return i;return null;}
	private static MethodInsnNode flow(MethodNode method,AbstractInsnNode start,JumpInsnNode skip){MethodInsnNode found=null;for(var i=start.getNext();i!=null&&i!=skip.label;i=i.getNext())if(i instanceof MethodInsnNode call&&Type.getReturnType(call.desc).getSort()==Type.OBJECT&&Type.getArgumentTypes(call.desc).length==2&&Arrays.stream(Type.getArgumentTypes(call.desc)).allMatch(t->t.getSort()==Type.OBJECT)){if(found!=null)return null;found=call;}return found;}
	private static List<AbstractInsnNode>back(AbstractInsnNode end,int count){List<AbstractInsnNode>result=new ArrayList<>();var i=end;for(int n=0;n<count;n++){if(i==null)return List.of();result.addFirst(i);i=previous(i);}return result;}
	private static List<AbstractInsnNode>code(AbstractInsnNode start,AbstractInsnNode end){List<AbstractInsnNode>result=new ArrayList<>();for(var i=start;i!=null;i=i.getNext()){if(i.getOpcode()>=0)result.add(i);if(i==end)return result;}return List.of();}
	private static List<AbstractInsnNode>codeUntil(AbstractInsnNode start,AbstractInsnNode end){List<AbstractInsnNode>result=new ArrayList<>();for(var i=start;i!=null&&i!=end;i=i.getNext())result.add(i);return result;}
	private static boolean same(List<AbstractInsnNode>a,List<AbstractInsnNode>b,Map<Integer,Integer>variables){if(a.size()!=b.size()||a.isEmpty())return false;for(int i=0;i<a.size();i++){var x=a.get(i);var y=b.get(i);if(x.getOpcode()!=y.getOpcode())return false;if(x instanceof VarInsnNode l&&y instanceof VarInsnNode r){if(!bind(variables,l.var,r.var))return false;}else if(x instanceof FieldInsnNode l&&y instanceof FieldInsnNode r){if(!member(l).equals(member(r)))return false;}else if(x instanceof MethodInsnNode l&&y instanceof MethodInsnNode r){if(!call(l).equals(call(r)))return false;}else if(x instanceof JumpInsnNode&&y instanceof JumpInsnNode){/* All region branches must leave to the following height block; checked in template closure. */}else if(!(x instanceof InsnNode&&y instanceof InsnNode))return false;}return true;}
	private static boolean bind(Map<Integer,Integer>variables,int oldSlot,int liveSlot){Integer old=variables.putIfAbsent(oldSlot,liveSlot);return(old==null||old==liveSlot)&&variables.entrySet().stream().noneMatch(e->e.getKey()!=oldSlot&&e.getValue()==liveSlot);}
	private static String member(FieldInsnNode f){return f.owner+"."+f.name+f.desc;}private static String call(MethodInsnNode call){return call.owner+"."+call.name+call.desc+":"+call.itf;}
	private static AbstractInsnNode previous(AbstractInsnNode i){for(var n=i.getPrevious();n!=null;n=n.getPrevious())if(n.getOpcode()>=0)return n;return null;}
	private static AbstractInsnNode next(AbstractInsnNode i){for(var n=i instanceof LabelNode?i:i.getNext();n!=null;n=n.getNext())if(n.getOpcode()>=0)return n;return null;}
}
