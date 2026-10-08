/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;
import java.util.*;
import java.util.function.Function;
import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.mixin.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
/** Restore a lost public data view only from actual getter/constructor evidence; target names are not policy. */
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
            List<MethodNode> wanted=current.methods.stream().filter(m->m.desc.equals("()L"+view.forge+";")).toList();
            for(MethodNode oldGetter:wanted){
                FieldInsnNode oldField=getter(oldGetter,current.name);if(oldField==null)continue;
                List<MethodNode> canonical=current.methods.stream().filter(m->m.name.equals(oldGetter.name)&&m.desc.equals("()L"+view.neo+";")).toList();
                if(canonical.size()!=1)continue;FieldInsnNode liveField=getter(canonical.getFirst(),current.name);if(liveField==null||writes(current,oldField)!=0)continue;
                ClassNode source=references.get(Ecosystem.FORGE,current.name),nativeCanonical=references.get(Ecosystem.NEOFORGE,current.name);
                MethodNode sourceGetter=method(source,oldGetter.name+oldGetter.desc),canonicalGetter=method(nativeCanonical,canonical.getFirst().name+canonical.getFirst().desc);
                if(sourceGetter==null||canonicalGetter==null)continue;
                List<MethodNode> required=source.methods.stream().filter(m->m.name.equals("<init>")&&!fieldWrites(m,oldField).isEmpty()).toList();
                if(required.isEmpty())continue;
                if(!MixinInstructionFingerprint.hash(sourceGetter).equals(MixinInstructionFingerprint.hash(oldGetter))
                    ||!MixinInstructionFingerprint.hash(canonicalGetter).equals(MixinInstructionFingerprint.hash(canonical.getFirst())))throw unproved(current,oldField,"the native public getter body changed");
                List<MethodNode> constructors=current.methods.stream().filter(m->m.name.equals("<init>")&&fieldWrites(m,liveField).size()==1).toList();
                if(constructors.isEmpty())throw unproved(current,oldField,"no constructor initializes the canonical state");boolean proved=true;
                for(MethodNode requiredCtor:required)if(constructors.stream().noneMatch(m->m.desc.equals(requiredCtor.desc)))throw unproved(current,oldField,"source constructor is not covered: "+requiredCtor.desc);
                for(MethodNode ctor:constructors){
                    MethodNode nativeCtor=method(nativeCanonical,ctor.name+ctor.desc),originalCtor=method(source,ctor.name+ctor.desc);
                    if(nativeCtor==null||originalCtor==null||fieldWrites(originalCtor,oldField).size()!=1
                        ||!Objects.equals(prefix(ctor,liveField),prefix(nativeCtor,liveField))||prefix(ctor,liveField)==null){proved=false;break;}
                }
                if(!proved)throw unproved(current,oldField,"constructor inputs or control flow differ from the verified native state");
                for(MethodNode ctor:constructors){
                    FieldInsnNode write=fieldWrites(ctor,liveField).getFirst();InsnList init=new InsnList();init.add(new VarInsnNode(Opcodes.ALOAD,0));init.add(new VarInsnNode(Opcodes.ALOAD,0));
                    init.add(new FieldInsnNode(Opcodes.GETFIELD,current.name,liveField.name,liveField.desc));init.add(new MethodInsnNode(Opcodes.INVOKESTATIC,RUNTIME,view.factory,"(L"+view.neo+";)L"+view.forge+";",false));init.add(new FieldInsnNode(Opcodes.PUTFIELD,current.name,oldField.name,oldField.desc));ctor.instructions.insert(write,init);ctor.maxStack+=2;
                }
                changed=true;
            }
        }
        if(!changed)return bytes;ClassWriter writer=new ClassWriter(0);current.accept(writer);return writer.toByteArray();
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
    private static LinkageError unproved(ClassNode owner,FieldInsnNode field,String reason){return new LinkageError("Unresolved public data-state view "+owner.name+"."+field.name+field.desc+": the source initializes this field, the merge does not; "+reason);}
    private static MethodNode method(ClassNode n,String signature){return n==null?null:n.methods.stream().filter(m->(m.name+m.desc).equals(signature)).findFirst().orElse(null);}
    private static ClassNode parse(byte[] bytes){ClassNode n=new ClassNode();new ClassReader(bytes).accept(n,0);return n;}
}
