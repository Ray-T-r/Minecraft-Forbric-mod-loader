/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Repairs a bare monitor wait after a queue predicate was checked outside that monitor. */
public final class WorkerNotificationInjector implements ClassTransformer {
	private static final String HOOK = "net/forbric/kernel/interop/WorkerPredicateWait";
	private final Function<String, ClassNode> declarations;
	public WorkerNotificationInjector(Function<String, ClassNode> declarations) { this.declarations = declarations; }
	@Override public AnchorSet anchors() { return AnchorSet.scanned("proved queued-worker bare monitor waits"); }
	@Override public byte[] transform(String name, byte[] bytes, TransformContext context) {
		if (bytes == null || "off".equalsIgnoreCase(System.getProperty("forbric.workerNotification", "on"))) return bytes;
		ClassReader reader = new ClassReader(bytes);
		if (!"java/lang/Thread".equals(reader.getSuperName()) && !java.util.Arrays.asList(reader.getInterfaces()).contains("java/lang/Runnable")) return bytes;
		ClassNode worker = new ClassNode(); reader.accept(worker, 0);
		List<Plan> plans = new ArrayList<>();
		for (MethodNode method : worker.methods) {
			if (!method.desc.equals("()V") || method.name.startsWith("<") || (method.access & Opcodes.ACC_STATIC) != 0) continue;
			List<Plan> found = new ArrayList<>();
			for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL && call.desc.equals("()V")) {
				Plan plan = plan(worker, method, call); if (plan != null) found.add(plan);
			}
			// Multiple parks have different timing semantics; never select one by its ordinal.
			if (found.size() == 1) plans.add(found.getFirst());
		}
		if (plans.size() != 1) return bytes;
		Plan plan = plans.getFirst();
		InsnList arguments = new InsnList(); arguments.add(new VarInsnNode(Opcodes.ALOAD, 0));
		arguments.add(new FieldInsnNode(Opcodes.GETFIELD, worker.name, plan.outer.name, plan.outer.desc));
		arguments.add(new LdcInsnNode(Type.getObjectType(plan.outer.desc.substring(1,plan.outer.desc.length()-1))));
        arguments.add(new LdcInsnNode(plan.roles.running().name)); arguments.add(new LdcInsnNode(plan.roles.queue().name));
		arguments.add(new InsnNode(plan.restoreInterrupt ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
		plan.method.instructions.insertBefore(plan.call, arguments);
		plan.call.setOpcode(Opcodes.INVOKESTATIC); plan.call.owner = HOOK; plan.call.name = "awaitNotification";
		plan.call.desc = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;Z)V"; plan.call.itf = false;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); worker.accept(writer); return writer.toByteArray();
	}
	private record Plan(MethodNode method, MethodInsnNode call, FieldInsnNode outer, WorkerPoolShape.Roles roles, boolean restoreInterrupt) { }
	private Plan plan(ClassNode worker, MethodNode method, MethodInsnNode call) {
		if (!(WorkerPoolShape.previous(call) instanceof FieldInsnNode notifier) || notifier.getOpcode() != Opcodes.GETFIELD
				|| !(WorkerPoolShape.previous(notifier) instanceof FieldInsnNode outer) || outer.getOpcode() != Opcodes.GETFIELD
				|| !outer.owner.equals(worker.name) || !outer.desc.equals("L" + notifier.owner + ";") || !WorkerPoolShape.load(WorkerPoolShape.previous(outer),0)) return null;
		ClassNode pool = declarations.apply(notifier.owner); WorkerPoolShape.Roles roles = WorkerPoolShape.roles(pool);
		if (roles == null) return null;
		boolean idlePredicate = false;
		for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode query
				&& query.owner.equals("java/util/Deque") && query.name.equals("isEmpty") && query.desc.equals("()Z")
				&& query.getOpcode()==Opcodes.INVOKEINTERFACE && WorkerPoolShape.previous(query) instanceof FieldInsnNode field && field.getOpcode()==Opcodes.GETFIELD
                && field.owner.equals(pool.name) && field.name.equals(roles.queue().name) && field.desc.equals(roles.queue().desc)
                && WorkerPoolShape.previous(field) instanceof FieldInsnNode owner && owner.owner.equals(worker.name) && owner.name.equals(outer.name)
                && owner.desc.equals(outer.desc) && WorkerPoolShape.load(WorkerPoolShape.previous(owner),0)
                && WorkerPoolShape.next(query) instanceof JumpInsnNode nonempty && nonempty.getOpcode()==Opcodes.IFNE
                && WorkerPoolShape.next(nonempty).getOpcode()==Opcodes.RETURN
                && method.instructions.indexOf(query)<method.instructions.indexOf(call)
                && method.instructions.indexOf(nonempty.label)>method.instructions.indexOf(nonempty)
                && method.instructions.indexOf(nonempty.label)<method.instructions.indexOf(call)) idlePredicate = true;
		if (!idlePredicate || !notifier.desc.equals("L" + call.owner + ";")) return null;
		ClassNode notifierType = declarations.apply(call.owner);
		MethodNode wait = WorkerPoolShape.method(notifierType, call.name, "()V");
		if (wait == null) return null;
        Boolean restore = bareWait(wait);
        return restore == null ? null : new Plan(method, call, outer, roles, restore);
    }
    /** A swallowed InterruptedException (optionally restored), on this monitor, with no predicate or side effects. */
    static Boolean bareWait(MethodNode wait) {
        if((wait.access & Opcodes.ACC_STATIC)!=0)return null;
        List<AbstractInsnNode> code=WorkerPoolShape.code(wait);
        boolean synchronizedMethod=(wait.access&Opcodes.ACC_SYNCHRONIZED)!=0;
        int enters=0, exits=0, waits=0, returns=0, threadCalls=0;MethodInsnNode parked=null;
        for(AbstractInsnNode instruction:code) {
            if(instruction instanceof MethodInsnNode call) {
                if(call.owner.equals("java/lang/Object") && call.name.equals("wait") && call.desc.equals("()V")
                        && call.getOpcode()==Opcodes.INVOKEVIRTUAL && WorkerPoolShape.load(WorkerPoolShape.previous(call),0)) {waits++;parked=call;}
                else if(!(call.owner.equals("java/lang/Thread") && (call.getOpcode()==Opcodes.INVOKESTATIC && call.name.equals("currentThread") && call.desc.equals("()Ljava/lang/Thread;")
                        || call.getOpcode()==Opcodes.INVOKEVIRTUAL && call.name.equals("interrupt") && call.desc.equals("()V"))))return null;
                else threadCalls++;
            } else if(instruction instanceof VarInsnNode variable) {
                if(variable.getOpcode()!=Opcodes.ALOAD && variable.getOpcode()!=Opcodes.ASTORE || variable.getOpcode()==Opcodes.ASTORE && variable.var==0)return null;
            } else if(instruction instanceof JumpInsnNode jump) {
                if(jump.getOpcode()!=Opcodes.GOTO || wait.instructions.indexOf(jump.label)<=wait.instructions.indexOf(jump))return null;
            } else {
                switch(instruction.getOpcode()) {
                    case Opcodes.MONITORENTER -> enters++;
                    case Opcodes.MONITOREXIT -> exits++;
                    case Opcodes.RETURN -> returns++;
                    case Opcodes.DUP, Opcodes.ATHROW -> { }
                    default -> {return null;}
                }
            }
        }
        if(waits!=1 || returns!=1)return null;
        if(synchronizedMethod) {if(enters!=0 || exits!=0 || code.size()<2 || !WorkerPoolShape.load(code.get(0),0) || code.get(1)!=parked)return null;}
        else if(enters!=1 || exits!=2 || code.size()<4 || !WorkerPoolShape.load(code.get(0),0)
                || code.get(1).getOpcode()!=Opcodes.DUP || !(code.get(2) instanceof VarInsnNode monitor) || monitor.getOpcode()!=Opcodes.ASTORE
                || code.get(3).getOpcode()!=Opcodes.MONITORENTER || code.size()<6 || !WorkerPoolShape.load(code.get(4),0) || code.get(5)!=parked)return null;
        List<TryCatchBlockNode> catches=wait.tryCatchBlocks.stream().filter(t->"java/lang/InterruptedException".equals(t.type)).toList();
        if(catches.size()!=1 || wait.tryCatchBlocks.stream().anyMatch(t->t.type!=null && !t.type.equals("java/lang/InterruptedException")))return null;
        TryCatchBlockNode catcher=catches.getFirst();int position=wait.instructions.indexOf(parked);
        if(wait.instructions.indexOf(catcher.start)>position || wait.instructions.indexOf(catcher.end)<=position)return null;
        AbstractInsnNode handler=WorkerPoolShape.next(catcher.handler);
        if(!(handler instanceof VarInsnNode exception) || exception.getOpcode()!=Opcodes.ASTORE)return null;
        AbstractInsnNode next=WorkerPoolShape.next(handler);boolean restore=false;
        if(next instanceof MethodInsnNode current && current.owner.equals("java/lang/Thread") && current.name.equals("currentThread")) {
            next=WorkerPoolShape.next(next);
            if(!(next instanceof MethodInsnNode interrupt) || !interrupt.owner.equals("java/lang/Thread") || !interrupt.name.equals("interrupt"))return null;
            restore=true;next=WorkerPoolShape.next(next);
        }
        // A rethrow, second wait or extra branch in the interrupted handler changes its observable contract.
        if(next==null || next.getOpcode()!=Opcodes.RETURN && next.getOpcode()!=Opcodes.GOTO && !(!synchronizedMethod && next instanceof VarInsnNode monitor && monitor.getOpcode()==Opcodes.ALOAD))return null;
        return threadCalls==(restore?2:0)?restore:null;
    }
}
