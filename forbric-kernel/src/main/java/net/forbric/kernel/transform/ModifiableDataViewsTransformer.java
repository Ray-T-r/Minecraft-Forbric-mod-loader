/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;
import java.util.*;
import java.util.function.Function;
import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.mixin.*;
import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import net.forbric.kernel.util.Caught;
/**
 * Make the published MinecraftForge data field a view of NeoForge's live state, only from actual getter/constructor
 * evidence; target names are not policy.
 *
 * <p>Two merged shapes lead here. The merge that kept NeoForge's constructor whole lost MinecraftForge's initializer,
 * so the Forge field was null for the life of the object. The merge that keeps both sides' edits carries Forge's own
 * initializer statement next to NeoForge's, so the Forge field holds a second, independent state: Forge's modifier
 * pass writes into it, and the merged getters, which are NeoForge's, never read it. Either way the Forge field becomes
 * {@code KernelModifiableDataViews.<factory>(liveField)}, right after the constructor writes the live field. In the
 * second shape Forge's statement is taken out first, and only when it is exactly the statement MinecraftForge's own
 * constructor initializes the field with; any other writer is a live producer and the field is left alone.
 *
 * <p>An unproved constructor throws for a lost field (it would stay null), and keeps the merge's own independent state,
 * with a warning, for a field the merge initializes itself.
 */
public final class ModifiableDataViewsTransformer implements ClassTransformer {
    private record View(String forge,String neo,String factory) { }
    private static final List<View> VIEWS=List.of(
        view(ForeignType.MODIFIABLE_BIOME_INFO,"","biome"),
        view(ForeignType.MODIFIABLE_STRUCTURE_INFO,"","structure"),
        view(ForeignType.ICONDITION,"$IContext","conditions"));
    private static View view(ForeignType type,String suffix,String factory){return new View(type.internal(Ecosystem.FORGE)+suffix,type.internal(Ecosystem.NEOFORGE)+suffix,factory);}
    private static final String RUNTIME="net/forbric/kernel/runtime/KernelModifiableDataViews";
    private final NativeGameReferences references;
    private final Function<String,byte[]> resources;
    public ModifiableDataViewsTransformer(Function<String,byte[]> resources){this.resources=resources;references=new NativeGameReferences(resources);}
    @Override public String name(){return "forbric-modifiable-data-views";}
    @Override public AnchorSet anchors(){return AnchorSet.scanned("source-proved public mutable data views");}
    @Override public byte[] transform(String name,byte[] bytes,TransformContext context){
        if(bytes==null)return null;ClassNode current=parse(bytes);boolean changed=NativeOwnedComponentInitializers.restore(current,references,resources);
        for(View view:VIEWS){
            List<String> getters=current.methods.stream().filter(m->m.desc.equals("()L"+view.forge+";")).map(m->m.name).toList();
            for(String getterName:getters){
                // Each view is tried on a copy, so a native state that cannot be proved is kept exactly as merged.
                ClassNode trial=new ClassNode();current.accept(trial);
                if(restoreView(trial,view,getterName)){current=trial;changed=true;}
            }
        }
        if(!changed)return bytes;ClassWriter writer=new ClassWriter(0);current.accept(writer);return writer.toByteArray();
    }
    private boolean restoreView(ClassNode current,View view,String getterName){
        MethodNode oldGetter=method(current,getterName+"()L"+view.forge+";");
        FieldInsnNode oldField=oldGetter==null?null:getter(oldGetter,current.name);if(oldField==null)return false;
        List<MethodNode> canonical=current.methods.stream().filter(m->m.name.equals(oldGetter.name)&&m.desc.equals("()L"+view.neo+";")).toList();
        if(canonical.size()!=1)return false;FieldInsnNode liveField=getter(canonical.getFirst(),current.name);if(liveField==null)return false;
        ClassNode source=references.get(Ecosystem.FORGE,current.name),nativeCanonical=references.get(Ecosystem.NEOFORGE,current.name);
        boolean nativeState=writes(current,oldField)!=0;
        if(nativeState&&!removeSourceInitializers(current,source,oldField))return false;
        MethodNode sourceGetter=method(source,oldGetter.name+oldGetter.desc),canonicalGetter=method(nativeCanonical,canonical.getFirst().name+canonical.getFirst().desc);
        if(sourceGetter==null||canonicalGetter==null)return false;
        List<MethodNode> required=source.methods.stream().filter(m->m.name.equals("<init>")&&!fieldWrites(m,oldField).isEmpty()).toList();
        if(required.isEmpty())return false;
        if(!MixinInstructionFingerprint.hash(sourceGetter).equals(MixinInstructionFingerprint.hash(oldGetter))
            ||!MixinInstructionFingerprint.hash(canonicalGetter).equals(MixinInstructionFingerprint.hash(canonical.getFirst())))return unproved(current,oldField,"the native public getter body changed",nativeState);
        List<MethodNode> constructors=current.methods.stream().filter(m->m.name.equals("<init>")&&fieldWrites(m,liveField).size()==1).toList();
        if(constructors.isEmpty())return unproved(current,oldField,"no constructor initializes the canonical state",nativeState);
        for(MethodNode requiredCtor:required)if(constructors.stream().noneMatch(m->m.desc.equals(requiredCtor.desc)))return unproved(current,oldField,"source constructor is not covered: "+requiredCtor.desc,nativeState);
        for(MethodNode ctor:constructors){
            MethodNode nativeCtor=method(nativeCanonical,ctor.name+ctor.desc),originalCtor=method(source,ctor.name+ctor.desc);
            if(nativeCtor==null||originalCtor==null||fieldWrites(originalCtor,oldField).size()!=1
                ||!Objects.equals(prefix(ctor,liveField),prefix(nativeCtor,liveField))||prefix(ctor,liveField)==null)return unproved(current,oldField,"constructor inputs or control flow differ from the verified native state",nativeState);
        }
        for(MethodNode ctor:constructors){
            FieldInsnNode write=fieldWrites(ctor,liveField).getFirst();InsnList init=new InsnList();init.add(new VarInsnNode(Opcodes.ALOAD,0));init.add(new VarInsnNode(Opcodes.ALOAD,0));
            init.add(new FieldInsnNode(Opcodes.GETFIELD,current.name,liveField.name,liveField.desc));init.add(new MethodInsnNode(Opcodes.INVOKESTATIC,RUNTIME,view.factory,"(L"+view.neo+";)L"+view.forge+";",false));init.add(new FieldInsnNode(Opcodes.PUTFIELD,current.name,oldField.name,oldField.desc));ctor.instructions.insert(write,init);ctor.maxStack+=2;
        }
        if(nativeState)ForbricLog.info("[Forbric/DataViews] %s.%s is now a view of NeoForge's live %s — the merge initialized it as a second, "
            +"independent state that MinecraftForge's modifiers wrote and nothing read",current.name.replace('/','.'),oldField.name,liveField.name);
        return true;
    }
    /**
     * Takes out every write of {@code field}, provided each is MinecraftForge's own initializer statement: in a
     * constructor, and the very instruction run the source constructor of the same descriptor initializes it with
     * (from the statement's empty-stack start to its single write). False, and nothing changed, otherwise.
     */
    private static boolean removeSourceInitializers(ClassNode current,ClassNode source,FieldInsnNode field){
        if(source==null)return false;List<List<AbstractInsnNode>> runs=new ArrayList<>();Map<List<AbstractInsnNode>,MethodNode> owners=new IdentityHashMap<>();
        for(MethodNode method:current.methods){
            for(FieldInsnNode write:fieldWrites(method,field)){
                if(!method.name.equals("<init>"))return false;
                MethodNode original=method(source,method.name+method.desc);List<AbstractInsnNode> statement=original==null?null:statement(original,source.name,field);
                if(statement==null)return false;
                List<AbstractInsnNode> code=code(method);int end=code.indexOf(write),start=end-statement.size()+1;
                if(start<0||!tokens(code.subList(start,end+1)).equals(tokens(statement))||entered(method,code.get(start),write))return false;
                List<AbstractInsnNode> run=List.copyOf(code.subList(start,end+1));runs.add(run);owners.put(run,method);
            }
        }
        for(List<AbstractInsnNode> run:runs)for(AbstractInsnNode instruction:run)owners.get(run).instructions.remove(instruction);
        return !runs.isEmpty();
    }
    /** The straight-line statement ending at the single write of {@code field}: from where the stack is last empty. */
    private static List<AbstractInsnNode> statement(MethodNode method,String owner,FieldInsnNode field){
        List<FieldInsnNode> writes=fieldWrites(method,field);if(writes.size()!=1||!method.tryCatchBlocks.isEmpty())return null;
        Frame<BasicValue>[] frames;try{frames=new Analyzer<>(new BasicInterpreter()).analyze(owner,method);}catch(AnalyzerException unanalyzable){ Caught.expected(unanalyzable);return null;}
        AbstractInsnNode[] all=method.instructions.toArray();int put=method.instructions.indexOf(writes.getFirst());
        for(int i=put;i>=0;i--){
            AbstractInsnNode at=all[i];if(at.getOpcode()<0)continue;
            if(at instanceof JumpInsnNode||at instanceof TableSwitchInsnNode||at instanceof LookupSwitchInsnNode||at.getOpcode()==Opcodes.ATHROW
                ||at.getOpcode()>=Opcodes.IRETURN&&at.getOpcode()<=Opcodes.RETURN)return null;
            if(frames[i]==null)return null;
            if(frames[i].getStackSize()==0){
                if(entered(method,at,writes.getFirst()))return null;
                List<AbstractInsnNode> run=new ArrayList<>();for(int j=i;j<=put;j++)if(all[j].getOpcode()>=0)run.add(all[j]);return run;
            }
        }
        return null;
    }
    /** Whether control can enter the run [first, last] other than through {@code first}: a label in it is a branch target. */
    private static boolean entered(MethodNode method,AbstractInsnNode first,AbstractInsnNode last){
        Set<LabelNode> targets=Collections.newSetFromMap(new IdentityHashMap<>());
        for(AbstractInsnNode at:method.instructions){
            if(at instanceof JumpInsnNode jump)targets.add(jump.label);
            if(at instanceof TableSwitchInsnNode table){targets.add(table.dflt);targets.addAll(table.labels);}
            if(at instanceof LookupSwitchInsnNode table){targets.add(table.dflt);targets.addAll(table.labels);}
        }
        for(TryCatchBlockNode block:method.tryCatchBlocks)targets.add(block.handler);
        for(AbstractInsnNode at=first.getNext();at!=null&&at!=last.getNext();at=at.getNext())if(at instanceof LabelNode label&&targets.contains(label))return true;
        return false;
    }
    private static List<String> tokens(List<AbstractInsnNode> code){List<String> out=new ArrayList<>();for(AbstractInsnNode at:code)out.add(token(at));return out;}
    private static String token(AbstractInsnNode at){
        String op=Integer.toString(at.getOpcode());
        if(at instanceof VarInsnNode v)return op+":"+v.var;
        if(at instanceof TypeInsnNode t)return op+":"+t.desc;
        if(at instanceof FieldInsnNode f)return op+":"+f.owner+"."+f.name+f.desc;
        if(at instanceof MethodInsnNode m)return op+":"+m.owner+"."+m.name+m.desc+(m.itf?":itf":"");
        if(at instanceof LdcInsnNode l)return op+":"+l.cst.getClass().getName()+":"+l.cst;
        if(at instanceof IntInsnNode i)return op+":"+i.operand;
        if(at instanceof IincInsnNode i)return op+":"+i.var+":"+i.incr;
        if(at instanceof InvokeDynamicInsnNode d)return op+":"+d.name+d.desc+":"+d.bsm+":"+Arrays.toString(d.bsmArgs);
        if(at instanceof MultiANewArrayInsnNode a)return op+":"+a.desc+":"+a.dims;
        return op;
    }
    private static FieldInsnNode getter(MethodNode m,String owner){
        if((m.access&(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC))!=Opcodes.ACC_PUBLIC||!m.tryCatchBlocks.isEmpty())return null;
        var code=code(m);return code.size()==3&&code.get(0)instanceof VarInsnNode v&&v.var==0&&v.getOpcode()==Opcodes.ALOAD
            &&code.get(1)instanceof FieldInsnNode f&&f.getOpcode()==Opcodes.GETFIELD&&f.owner.equals(owner)&&code.get(2).getOpcode()==Opcodes.ARETURN?f:null;
    }
    private static int writes(ClassNode n,FieldInsnNode f){return n.methods.stream().mapToInt(m->fieldWrites(m,f).size()).sum();}
    private static List<FieldInsnNode> fieldWrites(MethodNode m,FieldInsnNode f){return code(m).stream().filter(i->i instanceof FieldInsnNode x&&x.getOpcode()==Opcodes.PUTFIELD&&x.owner.equals(f.owner)&&x.name.equals(f.name)&&x.desc.equals(f.desc)).map(FieldInsnNode.class::cast).toList();}
    private static String prefix(MethodNode m,FieldInsnNode field){
        if(!m.tryCatchBlocks.isEmpty())return null;List<AbstractInsnNode> code=code(m);List<FieldInsnNode> writes=fieldWrites(m,field);if(writes.size()!=1)return null;
        int end=code.indexOf(writes.getFirst());for(int i=0;i<=end;i++)if(code.get(i)instanceof JumpInsnNode||code.get(i)instanceof LookupSwitchInsnNode||code.get(i)instanceof TableSwitchInsnNode)return null;
        MethodNode copy=new MethodNode(m.access,m.name,m.desc,null,null);m.accept(copy);int real=0;
        for(AbstractInsnNode instruction:copy.instructions.toArray()){if(instruction.getOpcode()>=0)real++;if(real>end+1)copy.instructions.remove(instruction);}
        copy.tryCatchBlocks.clear();copy.localVariables=null;return MixinInstructionFingerprint.hash(copy);
    }
    private static List<AbstractInsnNode> code(MethodNode m){return Arrays.stream(m.instructions.toArray()).filter(i->i.getOpcode()>=0).toList();}
    /** A lost field would stay null, so an unproved constructor is fatal; a field the merge initializes keeps its own state. */
    private static boolean unproved(ClassNode owner,FieldInsnNode field,String reason,boolean nativeState){
        if(!nativeState)throw new LinkageError("Unresolved public data-state view "+owner.name+"."+field.name+field.desc+": the source initializes this field, the merge does not; "+reason);
        ForbricLog.warn("[Forbric/DataViews] %s.%s%s keeps the merge's own independent state, not a view of NeoForge's live one — %s; "
            +"MinecraftForge modifiers applied to it are not seen by the merged getters",owner.name.replace('/','.'),field.name,field.desc,reason);
        return false;
    }
    private static MethodNode method(ClassNode n,String signature){return n==null?null:n.methods.stream().filter(m->(m.name+m.desc).equals(signature)).findFirst().orElse(null);}
    private static ClassNode parse(byte[] bytes){ClassNode n=new ClassNode();new ClassReader(bytes).accept(n,0);return n;}
}
