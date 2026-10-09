/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.*;
import java.util.function.Function;
import net.forbric.api.NativeEventDelivery;
import net.forbric.api.NativeEventDelivery.Contract;
import net.forbric.api.NativeEventDelivery.Hook;
import net.forbric.kernel.util.ByteScan;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** A completed native observer hook covers its equivalent forward only on a proved same-argument path.
 * Source event identities are captured before dispatch; caller names and ecosystem labels are irrelevant. */
public final class NativeDualEventInjector implements ClassTransformer {
    private static final String API="net/forbric/api/NativeEventDelivery",SCOPE=API+"$Scope";
    private final Function<String,byte[]> resources;
    /** The classes {@link #read} parsed, by internal name: the contracts' hook owners and the owners of fields an
     * operand proof reads. They are the game's own resources, which do not change during a run, so each is parsed once
     * per run rather than once per loaded class. The threads that load classes share them, so they are only ever read:
     * member lookups and forward instruction walks, never an InsnList index (built lazily) and never an Analyzer
     * (which runs on the input class's methods alone). A missing resource is not remembered, as before. */
    private final Map<String,ClassNode> parsed=new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.LongAdder inputsParsed=new java.util.concurrent.atomic.LongAdder();
    private volatile Prefilter prefilter;
    public NativeDualEventInjector(Function<String,byte[]> resources){this.resources=resources;}
    @Override public String name(){return "forbric-native-dual-events";}
    @Override public AnchorSet anchors(){return AnchorSet.scanned("registered native observer contracts, proved control flow and operands");}
    /** How many input classes this instance parsed: the ones the constant-pool prefilter could not rule out. */
    long classesParsed(){return inputsParsed.sum();}
    @Override public byte[] transform(String name,byte[] input,TransformContext context){
        List<Contract> contracts=NativeEventDelivery.contracts();
        if(input==null||input.length<10||contracts.isEmpty())return input;
        List<Contract> candidates=prefilter(contracts).candidates(input);if(candidates.isEmpty())return input;
        ClassNode node=new ClassNode();new ClassReader(input).accept(node,ClassReader.EXPAND_FRAMES);inputsParsed.increment();int changes=0;
        for(Contract contract:candidates){
            if(node.name.equals(contract.source().owner())){
                MethodNode hook=method(node,contract.source());MethodInsnNode constructor=construction(hook,contract);
                if(constructor!=null){
                    InsnList capture=new InsnList();capture.add(new InsnNode(Opcodes.DUP));capture.add(new LdcInsnNode(contract.id()));capture.add(new InsnNode(Opcodes.SWAP));
                    capture.add(new MethodInsnNode(Opcodes.INVOKESTATIC,API,"capture","(Ljava/lang/String;Ljava/lang/Object;)V",false));hook.instructions.insert(constructor,capture);changes++;
                }
            }
            ClassNode source=read(contract.source().owner());
            if(source==null||construction(method(source,contract.source()),contract)==null||!publicStatic(method(read(contract.counterpart().owner()),contract.counterpart())))continue;
            for(MethodNode caller:List.copyOf(node.methods)){
                MethodInsnNode sourceCall=paired(node,caller,contract);if(sourceCall==null)continue;
                String wrapper="forbric$nativeDelivery$"+identity(contract.id());
                MethodNode existing=node.methods.stream().filter(m->m.name.equals(wrapper)&&m.desc.equals(sourceCall.desc)).findFirst().orElse(null);
                if(existing==null)node.methods.add(wrapper(wrapper,contract));
                else if(!calls(existing,API,"begin"))continue;
                sourceCall.owner=node.name;sourceCall.name=wrapper;sourceCall.itf=false;changes++;
            }
        }
        if(changes==0)return input;ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);return writer.toByteArray();
    }
    /** A class can change under a contract only by declaring its source hook (it IS the source owner) or by calling
     * it; either way the hook's owner, name and descriptor are CONSTANT_Utf8 entries of its constant pool. A class
     * missing any of the three provably cannot match, so it is not parsed. */
    private record Prefilter(List<Contract> contracts,byte[][] needles){
        static Prefilter of(List<Contract> contracts){
            return new Prefilter(contracts,contracts.stream().flatMap(c->java.util.stream.Stream.of(c.source().owner(),c.source().name(),c.source().descriptor()))
                .map(ByteScan::poolEntry).toArray(byte[][]::new));
        }
        /** The contracts, in registration-snapshot order, whose source hook this class could declare or call. */
        List<Contract> candidates(byte[] input){
            boolean[] named=ByteScan.constantPoolNames(input,needles);List<Contract> out=List.of();
            for(int i=0;i<contracts.size();i++)if(named[3*i]&&named[3*i+1]&&named[3*i+2]){if(out.isEmpty())out=new ArrayList<>(1);out.add(contracts.get(i));}
            return out;
        }
    }
    private Prefilter prefilter(List<Contract> contracts){
        Prefilter current=prefilter;
        if(current==null||current.contracts()!=contracts)prefilter=current=Prefilter.of(contracts);
        return current;
    }
    private ClassNode read(String owner){
        ClassNode cached=parsed.get(owner);if(cached!=null)return cached;
        byte[] bytes=resources.apply(owner+".class");if(bytes==null)return null;ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,ClassReader.EXPAND_FRAMES);
        if(!owner.equals(node.name))return null;
        ClassNode raced=parsed.putIfAbsent(owner,node);return raced==null?node:raced;
    }
    private static MethodNode method(ClassNode owner,Hook hook){if(owner==null)return null;return owner.methods.stream().filter(m->m.name.equals(hook.name())&&m.desc.equals(hook.descriptor())).findFirst().orElse(null);}
    private static boolean publicStatic(MethodNode method){return method!=null&&(method.access&(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC|Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE))==(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC);}
    /** The public source hook constructs exactly its contract event and immediately dispatches that instance. */
    private static MethodInsnNode construction(MethodNode method,Contract contract){
        if(!publicStatic(method)||!method.tryCatchBlocks.isEmpty())return null;
        List<AbstractInsnNode> code=real(method);Type[] args=Type.getArgumentTypes(method.desc);int n=args.length;
        if(code.size()!=7+n||!(code.get(0)instanceof FieldInsnNode bus)||bus.getOpcode()!=Opcodes.GETSTATIC||!bus.desc.equals("L"+contract.dispatch().owner()+";")
            ||!(code.get(1)instanceof TypeInsnNode event)||event.getOpcode()!=Opcodes.NEW||!event.desc.equals(contract.event())||code.get(2).getOpcode()!=Opcodes.DUP)return null;
        if(!(code.get(3+n)instanceof MethodInsnNode init)||init.getOpcode()!=Opcodes.INVOKESPECIAL||!init.owner.equals(contract.event())||!init.name.equals("<init>")
            ||!(code.get(4+n)instanceof MethodInsnNode post)||!matches(post,contract.dispatch())||post.getOpcode()!=Opcodes.INVOKEINTERFACE||code.get(5+n).getOpcode()!=Opcodes.POP||code.get(6+n).getOpcode()!=Opcodes.RETURN)return null;
        Type[] constructed=Type.getArgumentTypes(init.desc);if(constructed.length!=n)return null;
        Map<Integer,Type> slots=new HashMap<>();int slot=0;for(Type arg:args){slots.put(slot,arg);slot+=arg.getSize();}Set<Integer> seen=new HashSet<>();
        for(int i=0;i<n;i++)if(!(code.get(3+i)instanceof VarInsnNode load)||!seen.add(load.var)||!constructed[i].equals(slots.get(load.var))||load.getOpcode()!=constructed[i].getOpcode(Opcodes.ILOAD))return null;
        return init;
    }
    private MethodInsnNode paired(ClassNode owner,MethodNode method,Contract contract){
        List<MethodInsnNode> source=new ArrayList<>(),target=new ArrayList<>();for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof MethodInsnNode call){if(matches(call,contract.source()))source.add(call);if(matches(call,contract.counterpart()))target.add(call);}
        if(source.size()!=1||target.size()!=1||source.getFirst().getOpcode()!=Opcodes.INVOKESTATIC||target.getFirst().getOpcode()!=Opcodes.INVOKESTATIC)return null;
        try{
            Flow flow=new Flow(owner.name,method);int s=method.instructions.indexOf(source.getFirst()),t=method.instructions.indexOf(target.getFirst());
            // Every path to this source must traverse the counterpart's NORMAL completion edge; exceptions and
            // bypass branches cannot borrow an earlier native delivery. Repeated call nodes are ambiguous.
            if(t>=s||flow.frames[s]==null||flow.reaches(0,s,t,t+1)||flow.reaches(t+1,t,-1,-1)||flow.reaches(s+1,s,-1,-1))return null;
            Set<Integer> locals=new HashSet<>();List<Expr> a=operands(owner,method,flow,target.getFirst(),locals),b=operands(owner,method,flow,source.getFirst(),locals);
            if(a==null||!a.equals(b))return null;
            for(int i=t+1;i<s;i++){
                AbstractInsnNode instruction=method.instructions.get(i);
                if(instruction instanceof VarInsnNode store&&store.getOpcode()>=Opcodes.ISTORE&&store.getOpcode()<=Opcodes.ASTORE
                    &&(locals.contains(store.var)||((store.getOpcode()==Opcodes.LSTORE||store.getOpcode()==Opcodes.DSTORE)&&locals.contains(store.var+1))))return null;
                if(instruction instanceof IincInsnNode increment&&locals.contains(increment.var))return null;
            }
            return source.getFirst();
        }catch(AnalyzerException|IllegalArgumentException|ProofFailure unproved){return null;}
    }
    private record Expr(String kind,String symbol,List<Expr> inputs) { }
    private List<Expr> operands(ClassNode owner,MethodNode method,Flow flow,MethodInsnNode call,Set<Integer> locals){
        Frame<SourceValue> frame=flow.frames[method.instructions.indexOf(call)];Type[] args=Type.getArgumentTypes(call.desc);if(frame==null||frame.getStackSize()<args.length)return null;
        List<Expr> out=new ArrayList<>();for(int i=0;i<args.length;i++)out.add(expression(owner,method,flow,frame.getStack(frame.getStackSize()-args.length+i),locals,new HashSet<>()));return out;
    }
    private Expr expression(ClassNode owner,MethodNode method,Flow flow,SourceValue value,Set<Integer> locals,Set<AbstractInsnNode> seen){
        if(value==null||value.insns.size()!=1)throw new ProofFailure();AbstractInsnNode instruction=value.insns.iterator().next();if(!seen.add(instruction))throw new ProofFailure();
        if(instruction instanceof VarInsnNode load&&load.getOpcode()>=Opcodes.ILOAD&&load.getOpcode()<=Opcodes.ALOAD){locals.add(load.var);if(load.getOpcode()==Opcodes.LLOAD||load.getOpcode()==Opcodes.DLOAD)locals.add(load.var+1);return new Expr("local",load.getOpcode()+":"+load.var,List.of());}
        if(instruction instanceof FieldInsnNode field&&(field.getOpcode()==Opcodes.GETFIELD||field.getOpcode()==Opcodes.GETSTATIC)){
            ClassNode definition=field.owner.equals(owner.name)?owner:read(field.owner);FieldNode declared=definition==null?null:definition.fields.stream().filter(f->f.name.equals(field.name)&&f.desc.equals(field.desc)).findFirst().orElse(null);
            if(declared==null||(declared.access&Opcodes.ACC_FINAL)==0)throw new ProofFailure();
            List<Expr> input=List.of();if(field.getOpcode()==Opcodes.GETFIELD){Frame<SourceValue> frame=flow.frames[method.instructions.indexOf(field)];input=List.of(expression(owner,method,flow,frame.getStack(frame.getStackSize()-1),locals,seen));}
            return new Expr("field",field.owner+"."+field.name+field.desc,input);
        }
        if(instruction instanceof LdcInsnNode constant)return new Expr("constant",constant.cst.getClass().getName()+":"+constant.cst,List.of());
        if(instruction.getOpcode()>=Opcodes.ACONST_NULL&&instruction.getOpcode()<=Opcodes.DCONST_1)return new Expr("constant",Integer.toString(instruction.getOpcode()),List.of());
        throw new ProofFailure();
    }
    private static final class ProofFailure extends RuntimeException { }
    private static final class Flow {
        final Map<Integer,Set<Integer>> edges=new HashMap<>();final Frame<SourceValue>[] frames;
        Flow(String owner,MethodNode method)throws AnalyzerException{
            frames=new Analyzer<SourceValue>(new SourceInterpreter()){
                @Override protected void newControlFlowEdge(int from,int to){edges.computeIfAbsent(from,k->new HashSet<>()).add(to);}
                @Override protected boolean newControlFlowExceptionEdge(int from,int to){newControlFlowEdge(from,to);return true;}
            }.analyze(owner,method);
        }
        boolean reaches(int start,int goal,int omittedFrom,int omittedTo){
            Set<Integer> seen=new HashSet<>();ArrayDeque<Integer> work=new ArrayDeque<>();work.add(start);
            while(!work.isEmpty()){int at=work.removeFirst();if(at==goal)return true;if(!seen.add(at))continue;for(int next:edges.getOrDefault(at,Set.of()))if(at!=omittedFrom||next!=omittedTo)work.add(next);}return false;
        }
    }
    private static String identity(String id){
        try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private static MethodNode wrapper(String name,Contract contract){
        MethodNode method=new MethodNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC|Opcodes.ACC_SYNTHETIC,name,contract.source().descriptor(),null,null);
        Type[] args=Type.getArgumentTypes(method.desc);List<Object> locals=new ArrayList<>();int slot=0;for(Type arg:args){locals.add(frameType(arg));slot+=arg.getSize();}int scope=slot;locals.add(SCOPE);
        InsnList code=method.instructions;code.add(new LdcInsnNode(contract.id()));code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,API,"begin","(Ljava/lang/String;)L"+SCOPE+";",false));code.add(new VarInsnNode(Opcodes.ASTORE,scope));
        LabelNode start=new LabelNode(),end=new LabelNode(),handler=new LabelNode();code.add(start);slot=0;for(Type arg:args){code.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD),slot));slot+=arg.getSize();}
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,contract.source().owner(),contract.source().name(),contract.source().descriptor(),false));code.add(end);
        code.add(new VarInsnNode(Opcodes.ALOAD,scope));code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,SCOPE,"close","()V",false));code.add(new InsnNode(Opcodes.RETURN));
        code.add(handler);code.add(new FrameNode(Opcodes.F_NEW,locals.size(),locals.toArray(),1,new Object[]{"java/lang/Throwable"}));code.add(new VarInsnNode(Opcodes.ASTORE,scope+1));
        code.add(new VarInsnNode(Opcodes.ALOAD,scope));code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,SCOPE,"close","()V",false));code.add(new VarInsnNode(Opcodes.ALOAD,scope+1));code.add(new InsnNode(Opcodes.ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start,end,handler,null));method.maxLocals=scope+2;return method;
    }
    private static Object frameType(Type type){return switch(type.getSort()){case Type.LONG->Opcodes.LONG;case Type.DOUBLE->Opcodes.DOUBLE;case Type.FLOAT->Opcodes.FLOAT;case Type.ARRAY->type.getDescriptor();case Type.OBJECT->type.getInternalName();default->Opcodes.INTEGER;};}
    private static boolean matches(MethodInsnNode call,Hook hook){return call.owner.equals(hook.owner())&&call.name.equals(hook.name())&&call.desc.equals(hook.descriptor());}
    private static boolean calls(MethodNode method,String owner,String name){for(AbstractInsnNode i:method.instructions)if(i instanceof MethodInsnNode call&&call.owner.equals(owner)&&call.name.equals(name))return true;return false;}
    private static List<AbstractInsnNode> real(MethodNode method){List<AbstractInsnNode> out=new ArrayList<>();for(AbstractInsnNode i:method.instructions)if(i.getOpcode()>=0)out.add(i);return out;}
}
