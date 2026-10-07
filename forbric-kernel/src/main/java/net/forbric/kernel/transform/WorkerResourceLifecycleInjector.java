/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Discovers a native executor's start/drain/join lifecycle from JDK operations, without singleton reflection. */
public final class WorkerResourceLifecycleInjector implements ClassTransformer {
	private static final String HOOK = "net/forbric/kernel/interop/ManagedWorkerResources";
	private final Function<String, ClassNode> declarations;
	public WorkerResourceLifecycleInjector(Function<String, ClassNode> declarations) { this.declarations = declarations; }
	@Override public AnchorSet anchors() { return AnchorSet.scanned("proved executor start/drain/join lifecycle contracts"); }
	@Override public byte[] transform(String name, byte[] bytes, TransformContext context) {
		if (bytes == null || "off".equalsIgnoreCase(System.getProperty("forbric.workerResources", "on"))) return bytes;
		ClassNode pool = new ClassNode(); new ClassReader(bytes).accept(pool, 0);
		WorkerPoolShape.Roles roles = WorkerPoolShape.roles(pool); if (roles == null) return bytes;
		List<MethodNode> starts = new ArrayList<>(), stops = new ArrayList<>(), drains = new ArrayList<>();
		for (MethodNode method : pool.methods) {
			if (!method.desc.equals("()V") || (method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)) != Opcodes.ACC_PUBLIC) continue;
			boolean start = false, join = false, startFlag = false, stopFlag = false, notification = false;
			for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call) {
				if (call.owner.equals(HOOK)) return bytes;
				ClassNode calledOwner = null;
				if (call.name.equals("start") && call.desc.equals("()V")) {
					calledOwner = declarations.apply(call.owner);
					start |= call.owner.equals("java/lang/Thread") || calledOwner != null && calledOwner.superName.equals("java/lang/Thread");
				}
				join |= call.owner.equals("java/lang/Thread") && call.name.equals("join") && call.desc.equals("()V");
				notification |= call.owner.equals("java/lang/Object") && call.name.equals("notifyAll") && call.desc.equals("()V");
				var value = WorkerPoolShape.previous(call); var receiver = value == null ? null : WorkerPoolShape.previous(value);
				if (call.owner.equals("java/util/concurrent/atomic/AtomicBoolean") && call.name.equals("getAndSet") && call.desc.equals("(Z)Z")
						&& receiver instanceof FieldInsnNode field && field.owner.equals(pool.name) && field.name.equals(roles.running().name)) {
					startFlag |= value.getOpcode() == Opcodes.ICONST_1; stopFlag |= value.getOpcode() == Opcodes.ICONST_0;
				}
			}
			if (start && startFlag && flagEntry(method,pool,roles.running(),true)) starts.add(method);
			if (join && stopFlag && notification && flagEntry(method,pool,roles.running(),false)) stops.add(method);
			List<AbstractInsnNode> code = WorkerPoolShape.code(method);
            if (drainLoop(method,pool,code)) {
				MethodNode step = WorkerPoolShape.method(pool, ((MethodInsnNode)code.get(1)).name, "()Z");
				if (step != null && pollsRunnableQueue(step, pool, roles.queue())) drains.add(method);
			}
		}
		if (starts.size() != 1 || stops.size() != 1 || drains.size() != 1
                || !ownedWorkers(starts.getFirst(),stops.getFirst(),pool,roles)) return bytes;
		for (var instruction : starts.getFirst().instructions.toArray()) if (instruction.getOpcode() == Opcodes.RETURN) {
			InsnList hook = new InsnList(); hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
			hook.add(new LdcInsnNode(drains.getFirst().name)); hook.add(new LdcInsnNode(stops.getFirst().name));
			hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "register", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)V", false));
			starts.getFirst().instructions.insertBefore(instruction, hook);
		}
		for (var instruction : stops.getFirst().instructions.toArray()) if (instruction.getOpcode() == Opcodes.RETURN) {
			InsnList hook = new InsnList(); hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
			hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "unregister", "(Ljava/lang/Object;)V", false));
			stops.getFirst().instructions.insertBefore(instruction, hook);
		}
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); pool.accept(writer); return writer.toByteArray();
	}
    private static boolean drainLoop(MethodNode method,ClassNode pool,List<AbstractInsnNode> c) {
        if(!method.tryCatchBlocks.isEmpty() || (c.size()!=4 && c.size()!=5) || !WorkerPoolShape.load(c.get(0),0)
                || !(c.get(1) instanceof MethodInsnNode poll) || poll.getOpcode()!=Opcodes.INVOKEVIRTUAL
                || !poll.owner.equals(pool.name) || !poll.desc.equals("()Z") || !(c.get(2) instanceof JumpInsnNode decision))return false;
        if(c.size()==4)return decision.getOpcode()==Opcodes.IFEQ && WorkerPoolShape.next(decision.label)==c.get(0) && c.get(3).getOpcode()==Opcodes.RETURN;
        return decision.getOpcode()==Opcodes.IFNE && WorkerPoolShape.next(decision.label)==c.get(4)
                && c.get(3) instanceof JumpInsnNode repeat && repeat.getOpcode()==Opcodes.GOTO && WorkerPoolShape.next(repeat.label)==c.get(0)
                && c.get(4).getOpcode()==Opcodes.RETURN;
    }
    private static boolean flagEntry(MethodNode method,ClassNode pool,FieldNode running,boolean start) {
        List<AbstractInsnNode> c=WorkerPoolShape.code(method);
        return c.size()>6 && WorkerPoolShape.load(c.get(0),0) && WorkerPoolShape.ownField(c.get(1),pool.name,running)
                && c.get(2).getOpcode()==(start?Opcodes.ICONST_1:Opcodes.ICONST_0)
                && c.get(3) instanceof MethodInsnNode swap && swap.getOpcode()==Opcodes.INVOKEVIRTUAL
                && swap.owner.equals("java/util/concurrent/atomic/AtomicBoolean") && swap.name.equals("getAndSet") && swap.desc.equals("(Z)Z")
                && c.get(4) instanceof JumpInsnNode changed && changed.getOpcode()==(start?Opcodes.IFEQ:Opcodes.IFNE)
                && WorkerPoolShape.next(changed.label)==c.get(6) && c.get(5).getOpcode()==Opcodes.RETURN;
    }
    private static boolean pollsRunnableQueue(MethodNode step, ClassNode pool, FieldNode queue) {
        if((step.access & Opcodes.ACC_PRIVATE)==0 || (step.access & Opcodes.ACC_STATIC)!=0)return false;
        List<AbstractInsnNode> c=WorkerPoolShape.code(step);
        if(c.size()<13 || !WorkerPoolShape.load(c.get(0),0) || !WorkerPoolShape.ownField(c.get(1),pool.name,queue)
                || !(c.get(2) instanceof MethodInsnNode poll) || poll.getOpcode()!=Opcodes.INVOKEINTERFACE
                || !poll.owner.equals("java/util/Deque") || !poll.name.equals("pollLast") || !poll.desc.equals("()Ljava/lang/Object;")
                || !(c.get(3) instanceof TypeInsnNode cast) || cast.getOpcode()!=Opcodes.CHECKCAST || !cast.desc.equals("java/lang/Runnable"))return false;
        int storeIndex=c.get(4).getOpcode()==Opcodes.DUP?5:4;
        if(!(c.get(storeIndex) instanceof VarInsnNode store) || store.getOpcode()!=Opcodes.ASTORE || store.var==0
                || storeIndex==4 && !WorkerPoolShape.load(c.get(5),store.var)
                || !(c.get(6) instanceof JumpInsnNode empty) || empty.getOpcode()!=Opcodes.IFNULL
                || !WorkerPoolShape.load(c.get(7),0) || !WorkerPoolShape.load(c.get(8),store.var)
                || !(c.get(9) instanceof MethodInsnNode execute) || execute.getOpcode()!=Opcodes.INVOKEVIRTUAL
                || !execute.owner.equals(pool.name) || !execute.desc.equals("(Ljava/lang/Runnable;)V")
                || c.get(10).getOpcode()!=Opcodes.ICONST_0 || c.get(11).getOpcode()!=Opcodes.IRETURN
                || WorkerPoolShape.next(empty.label)!=c.get(12))return false;
        MethodNode task=WorkerPoolShape.method(pool,execute.name,execute.desc);
        if(task==null || (task.access&Opcodes.ACC_PRIVATE)==0 || (task.access&Opcodes.ACC_STATIC)!=0)return false;
        List<AbstractInsnNode> body=WorkerPoolShape.code(task);
        return body.size()>=2 && WorkerPoolShape.load(body.get(0),1) && body.get(1) instanceof MethodInsnNode run
                && run.getOpcode()==Opcodes.INVOKEINTERFACE && run.owner.equals("java/lang/Runnable") && run.name.equals("run") && run.desc.equals("()V");
    }
    private static boolean constructedForThis(MethodNode start,ClassNode pool,ClassNode worker,int slot) {
        try {
            var frames=new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.SourceInterpreter()).analyze(pool.name,start);
            int constructors=0;
            for(var instruction:start.instructions)if(instruction instanceof MethodInsnNode call && call.getOpcode()==Opcodes.INVOKESPECIAL
                    && call.owner.equals(worker.name) && call.name.equals("<init>")) {
                Type[] arguments=Type.getArgumentTypes(call.desc);
                MethodNode constructor=WorkerPoolShape.method(worker,"<init>",call.desc);
                List<FieldNode> parents=worker.fields.stream().filter(f->(f.access&Opcodes.ACC_STATIC)==0 && f.desc.equals("L"+pool.name+";")).toList();
                if(parents.size()!=1 || (parents.getFirst().access&Opcodes.ACC_FINAL)==0 || constructor==null || !capturesFirstArgument(constructor,worker,parents.getFirst()))return false;
                var frame=frames[start.instructions.indexOf(call)];
                if(arguments.length==0 || !arguments[0].equals(Type.getObjectType(pool.name)) || frame==null
                        || !(WorkerPoolShape.next(call) instanceof VarInsnNode store) || store.getOpcode()!=Opcodes.ASTORE || store.var!=slot)return false;
                var sources=frame.getStack(frame.getStackSize()-arguments.length).insns;
                if(sources.size()!=1 || !WorkerPoolShape.load(sources.iterator().next(),0))return false;
                constructors++;
            }
            return constructors==1;
        }catch(org.objectweb.asm.tree.analysis.AnalyzerException invalid){return false;}
    }
    private static boolean capturesFirstArgument(MethodNode constructor,ClassNode worker,FieldNode parent) {
        int stores=0;
        for(var instruction:constructor.instructions)if(instruction instanceof FieldInsnNode field && field.getOpcode()==Opcodes.PUTFIELD
                && field.owner.equals(worker.name) && field.name.equals(parent.name) && field.desc.equals(parent.desc)) {
            AbstractInsnNode value=WorkerPoolShape.previous(field);
            if(value!=null && value.getOpcode()==Opcodes.POP) {
                var check=WorkerPoolShape.previous(value);var duplicate=check==null?null:WorkerPoolShape.previous(check);
                if(!(check instanceof MethodInsnNode require) || require.getOpcode()!=Opcodes.INVOKESTATIC || !require.owner.equals("java/util/Objects")
                        || !require.name.equals("requireNonNull") || !require.desc.equals("(Ljava/lang/Object;)Ljava/lang/Object;")
                        || duplicate==null || duplicate.getOpcode()!=Opcodes.DUP)return false;
                value=WorkerPoolShape.previous(duplicate);
            }
            if(!WorkerPoolShape.load(value,1) || !WorkerPoolShape.load(WorkerPoolShape.previous(value),0))return false;
            stores++;
        }
        return stores==1;
    }
    /** The started objects belong to this queue's worker and are later joined from the same collection. */
    private boolean ownedWorkers(MethodNode start,MethodNode stop,ClassNode pool,WorkerPoolShape.Roles roles) {
        FieldInsnNode collection=null;int slot=-1;ClassNode worker=null;
        for(var instruction:start.instructions) if(instruction instanceof MethodInsnNode call && call.name.equals("start") && call.desc.equals("()V")) {
            if(worker!=null || !(WorkerPoolShape.previous(call) instanceof VarInsnNode local) || local.getOpcode()!=Opcodes.ALOAD)return false;
            worker=declarations.apply(call.owner);slot=local.var;
            if(worker==null || !"java/lang/Thread".equals(worker.superName))return false;
        }
        if(worker==null || slot<1 || !constructedForThis(start,pool,worker,slot))return false;
        for(var instruction:start.instructions) if(instruction instanceof MethodInsnNode call && call.getOpcode()==Opcodes.INVOKEINTERFACE
                && call.owner.equals("java/util/List") && call.name.equals("add") && call.desc.equals("(Ljava/lang/Object;)Z")
                && WorkerPoolShape.load(WorkerPoolShape.previous(call),slot)
                && WorkerPoolShape.previous(WorkerPoolShape.previous(call)) instanceof FieldInsnNode field
                && field.getOpcode()==Opcodes.GETFIELD && field.owner.equals(pool.name) && field.desc.equals("Ljava/util/List;")
                && WorkerPoolShape.load(WorkerPoolShape.previous(field),0)) {
            if(collection!=null)return false;collection=field;
        }
        if(collection==null)return false;
        boolean running=false,queued=false;FieldInsnNode monitor=null;
        for(MethodNode method:worker.methods)for(var instruction:method.instructions) if(instruction instanceof MethodInsnNode call
                && WorkerPoolShape.previous(call) instanceof FieldInsnNode field && field.getOpcode()==Opcodes.GETFIELD && field.owner.equals(pool.name)
                && WorkerPoolShape.previous(field) instanceof FieldInsnNode outer && outer.getOpcode()==Opcodes.GETFIELD && outer.owner.equals(worker.name)
                && outer.desc.equals("L"+pool.name+";") && WorkerPoolShape.load(WorkerPoolShape.previous(outer),0)) {
            running |= field.name.equals(roles.running().name) && field.desc.equals(roles.running().desc)
                    && call.owner.equals("java/util/concurrent/atomic/AtomicBoolean") && call.name.equals("get") && call.desc.equals("()Z");
            queued |= field.name.equals(roles.queue().name) && field.desc.equals(roles.queue().desc)
                    && call.owner.equals("java/util/Deque") && call.name.equals("pollFirst") && call.desc.equals("()Ljava/lang/Object;");
            MethodNode wait=WorkerPoolShape.method(declarations.apply(call.owner),call.name,call.desc);
            if(call.desc.equals("()V") && field.desc.equals("L"+call.owner+";") && wait!=null && WorkerNotificationInjector.bareWait(wait)!=null) {
                if(monitor!=null && (!monitor.name.equals(field.name) || !monitor.desc.equals(field.desc)))return false;monitor=field;
            }
        }
        if(!running || !queued || monitor==null)return false;
        boolean joined=false,notified=false;int iteratorSlot=-1;VarInsnNode iteratorStore=null;
        for(var instruction:stop.instructions) if(instruction instanceof MethodInsnNode call) {
            if(call.owner.equals("java/util/List") && call.name.equals("iterator") && call.desc.equals("()Ljava/util/Iterator;")
                    && WorkerPoolShape.previous(call) instanceof FieldInsnNode field && field.owner.equals(pool.name) && field.name.equals(collection.name)
                    && WorkerPoolShape.load(WorkerPoolShape.previous(field),0) && WorkerPoolShape.next(call) instanceof VarInsnNode store && store.getOpcode()==Opcodes.ASTORE) {
                if(iteratorStore!=null)return false;iteratorStore=store;iteratorSlot=store.var;
            }
            if(call.owner.equals("java/lang/Object") && call.name.equals("notifyAll") && call.desc.equals("()V")
                    && WorkerPoolShape.previous(call) instanceof FieldInsnNode field && field.owner.equals(pool.name) && field.name.equals(monitor.name)
                    && field.desc.equals(monitor.desc) && WorkerPoolShape.load(WorkerPoolShape.previous(field),0))notified=true;
            if(call.owner.equals("java/lang/Thread") && call.name.equals("join") && call.desc.equals("()V")
                    && WorkerPoolShape.previous(call) instanceof VarInsnNode receiver && receiver.getOpcode()==Opcodes.ALOAD) {
                for(var earlier=WorkerPoolShape.previous(receiver);earlier!=null;earlier=WorkerPoolShape.previous(earlier)) {
                    if(earlier instanceof VarInsnNode store && store.getOpcode()==Opcodes.ASTORE && store.var==receiver.var) {
                        var cast=WorkerPoolShape.previous(store);var next=cast==null?null:WorkerPoolShape.previous(cast);
                        joined=cast instanceof TypeInsnNode type && type.getOpcode()==Opcodes.CHECKCAST && type.desc.equals("java/lang/Thread")
                                && next instanceof MethodInsnNode poll && poll.owner.equals("java/util/Iterator") && poll.name.equals("next") && poll.desc.equals("()Ljava/lang/Object;")
                                && WorkerPoolShape.load(WorkerPoolShape.previous(poll),iteratorSlot);
                        break;
                    }
                }
            }
        }
        if(iteratorStore==null)return false;
        for(var instruction:stop.instructions)if(instruction instanceof VarInsnNode store && store.getOpcode()==Opcodes.ASTORE
                && store.var==iteratorSlot && stop.instructions.indexOf(store)>stop.instructions.indexOf(iteratorStore))return false;
        return joined && notified;
    }
}
