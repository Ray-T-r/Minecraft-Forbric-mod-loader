/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.lang.StackWalker.StackFrame;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import net.forbric.kernel.boot.DefinedMethodContracts.MethodContract;

/** Opt-in validation at an already reached native terminal. A conditional interface default is accepted only
 * at its witnessed fallback call; its other branches never grant a transported source evaluation. */
public final class NativeDefaultDispatchPaths {
 private record Factory(MethodContract base,Function<String,ClassNode> current,Function<String,ClassNode> nativeClasses){}
 private record Member(String owner,String name,String descriptor){}
 private record Identity(MethodContract body,int flags,boolean direct){}
 private record Edge(MethodContract body,int flags,Member target,int bytecodeIndex,boolean terminal,Identity identity){}
 private static final Map<String,Factory>FACTORIES=new ConcurrentHashMap<>();
 private static final java.util.concurrent.atomic.AtomicLong REGISTRATIONS=new java.util.concurrent.atomic.AtomicLong();
 private static final ClassValue<Map<String,Optional<Edge>>>EDGES=new ClassValue<>(){@Override protected Map<String,Optional<Edge>>computeValue(Class<?>type){return new ConcurrentHashMap<>();}};
 private NativeDefaultDispatchPaths(){}
 public static String register(MethodContract base,Function<String,ClassNode>current,Function<String,ClassNode>nativeClasses){Objects.requireNonNull(base);Objects.requireNonNull(current);Objects.requireNonNull(nativeClasses);String key;
  try{key=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(base.toString().getBytes(StandardCharsets.UTF_8)))+":"+REGISTRATIONS.incrementAndGet();}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}FACTORIES.put(key,new Factory(base,current,nativeClasses));return key;
 }
 /** The caller must have already validated the actual OWN terminal and its subject/type identities. */
 public static boolean permits(Object subject,Object actualType,String key,List<StackFrame>frames){Factory factory=FACTORIES.get(key);if(factory==null||subject==null||actualType==null||frames==null)return false;
  try{Class<?>base=Class.forName(factory.base.owner(),false,subject.getClass().getClassLoader());if(!base.isInstance(subject)||!DefinedMethodContracts.observed(base.getClassLoader(),factory.base))return false;
   int start=-1;for(int i=0;i<frames.size();i++){StackFrame frame=frames.get(i);if(frame.getDeclaringClass()==base&&frame.getMethodName().equals(factory.base.name())&&frame.getDescriptor().equals(factory.base.descriptor())){start=i;break;}}if(start<1)return false;
   StackFrame terminal=frames.get(start-1),first=frames.get(start);Edge bottom=edge(base,key,factory);if(bottom==null||!bottom.terminal||!valid(first,bottom)||first.getByteCodeIndex()!=bottom.bytecodeIndex)return false;
   Method actualTerminal=dispatch(actualType.getClass(),bottom.target.name,bottom.target.descriptor);Class<?>terminalOwner=Class.forName(bottom.target.owner.replace('/','.'),false,base.getClassLoader());if(actualTerminal==null||!terminalOwner.isInstance(actualType)||actualTerminal.getDeclaringClass()!=terminal.getDeclaringClass()||!terminal.getMethodName().equals(bottom.target.name)||!terminal.getDescriptor().equals(bottom.target.descriptor))return false;
   if(bottom.identity!=null&&!identity(subject,bottom.identity,base.getClassLoader()))return false;
   StackFrame callee=first;int end=start;for(int i=start+1;i<frames.size();i++){StackFrame caller=frames.get(i);if(!caller.getMethodName().equals(factory.base.name())||!caller.getDescriptor().equals(factory.base.descriptor()))break;
    Class<?>declaration=caller.getDeclaringClass();if(Class.forName(declaration.getName(),false,base.getClassLoader())!=declaration)return false;Edge link=edge(declaration,key,factory);if(link==null||link.terminal||!valid(caller,link)||caller.getByteCodeIndex()!=link.bytecodeIndex)return false;
    Class<?>owner=Class.forName(link.target.owner.replace('/','.'),false,declaration.getClassLoader());Method resolved=special(owner,link.target.name,link.target.descriptor);if(resolved==null||!owner.isInterface()||!owner.isAssignableFrom(declaration)||resolved.getDeclaringClass()!=callee.getDeclaringClass())return false;
    callee=caller;end=i;
   }
   Method actual=dispatch(subject.getClass(),factory.base.name(),factory.base.descriptor());return actual!=null&&actual.getDeclaringClass()==frames.get(end).getDeclaringClass();
  }catch(ReflectiveOperationException|RuntimeException|LinkageError unknown){return false;}
 }
 private static boolean valid(StackFrame frame,Edge edge){Method method=declared(frame.getDeclaringClass(),frame.getMethodName(),frame.getDescriptor());return safe(method)&&method.getModifiers()==edge.flags&&DefinedMethodContracts.observed(frame.getDeclaringClass().getClassLoader(),edge.body);}
 private static Edge edge(Class<?>owner,String key,Factory factory){return EDGES.get(owner).computeIfAbsent(key,ignored->Optional.ofNullable(derive(owner,factory))).orElse(null);}
 private static Edge derive(Class<?>actual,Factory factory){try{
  String owner=actual.getName().replace('.','/');ClassNode raw=factory.nativeClasses.apply(owner),current=factory.current.apply(owner);if(raw==null||current==null||!raw.name.equals(current.name)||!current.name.equals(owner))return null;
  MethodNode before=find(raw,factory.base.name(),factory.base.descriptor()),method=find(current,factory.base.name(),factory.base.descriptor());if(method==null||!safe(method)||!method.tryCatchBlocks.isEmpty())return null;
  if(before!=null){if(before.access!=method.access||!DefinedMethodContracts.fingerprint(before).equals(DefinedMethodContracts.fingerprint(method)))return null;}
  else if(actual.isInterface()||!materialized(raw,method,factory))return null;
  Type[]arguments=Type.getArgumentTypes(method.desc);if(arguments.length!=1||arguments[0].getSort()!=Type.OBJECT||!Type.getReturnType(method.desc).equals(Type.BOOLEAN_TYPE))return null;
  List<AbstractInsnNode>code=code(method);Frame<SourceValue>[]frames=new Analyzer<>(new SourceInterpreter()).analyze(owner,method);
  boolean isBase=actual.getName().equals(factory.base.owner());List<MethodInsnNode>calls=new ArrayList<>();for(var instruction:code)if(instruction instanceof MethodInsnNode call&&Type.getReturnType(call.desc).equals(Type.BOOLEAN_TYPE)&&(isBase?call.getOpcode()==Opcodes.INVOKEVIRTUAL||call.getOpcode()==Opcodes.INVOKEINTERFACE:call.getOpcode()==Opcodes.INVOKESPECIAL&&call.itf&&call.name.equals(method.name)&&call.desc.equals(method.desc)))calls.add(call);if(calls.size()!=1)return null;MethodInsnNode call=calls.getFirst();int at=code.indexOf(call);if(at!=code.size()-2||code.getLast().getOpcode()!=Opcodes.IRETURN)return null;
  Frame<SourceValue>frame=frames[method.instructions.indexOf(call)];Type[]passed=Type.getArgumentTypes(call.desc);if(passed.length!=1||frame==null||frame.getStackSize()<2)return null;Identity identity=null;
  if(isBase){if(!parameter(method,frames,frame.getStack(frame.getStackSize()-2),1,new HashSet<>()))return null;SourceValue value=frame.getStack(frame.getStackSize()-1);if(!parameter(method,frames,value,0,new HashSet<>())){MethodInsnNode projection=originCall(method,frames,value,new HashSet<>());if(projection==null||Type.getArgumentTypes(projection.desc).length!=0)return null;Frame<SourceValue>projected=frames[method.instructions.indexOf(projection)];if(projected==null||!parameter(method,frames,projected.getStack(projected.getStackSize()-1),0,new HashSet<>()))return null;ClassNode declaration=factory.current.apply(projection.owner),original=factory.nativeClasses.apply(projection.owner);MethodNode body=declaration==null?null:find(declaration,projection.name,projection.desc),prior=original==null?null:find(original,projection.name,projection.desc);if(body==null||prior==null||body.access!=prior.access||!DefinedMethodContracts.fingerprint(body).equals(DefinedMethodContracts.fingerprint(prior))||!identity(body)||(body.access&(Opcodes.ACC_STATIC|Opcodes.ACC_SYNCHRONIZED|Opcodes.ACC_NATIVE|Opcodes.ACC_ABSTRACT))!=0)return null;boolean direct=(body.access&Opcodes.ACC_PRIVATE)!=0;if(!direct&&(body.access&Opcodes.ACC_PUBLIC)==0)return null;identity=new Identity(new MethodContract(declaration.name,body.name,body.desc,DefinedMethodContracts.fingerprint(body)),body.access,direct);}
  }else{if(!parameter(method,frames,frame.getStack(frame.getStackSize()-2),0,new HashSet<>())||!parameter(method,frames,frame.getStack(frame.getStackSize()-1),1,new HashSet<>()))return null;
   boolean forward=code.size()==4&&load(code.get(0),0)&&load(code.get(1),1);if(!forward){if(!actual.isInterface()||!readOnlyFallback(method,code,at))return null;}
  }
  int offset=offset(code,at);if(offset<0)return null;return new Edge(new MethodContract(owner,method.name,method.desc,DefinedMethodContracts.fingerprint(method)),method.access,new Member(call.owner,call.name,call.desc),offset,isBase,identity);
 }catch(AnalyzerException|RuntimeException unknown){return null;}}
 /** A graft may only materialize the one default the original native class already inherited. A different
  * native superclass implementation, abstract mask, ambiguous default, or added interface is not equivalent. */
 private static boolean materialized(ClassNode raw,MethodNode method,Factory factory){List<AbstractInsnNode>code=code(method);if(code.size()!=4||!load(code.get(0),0)||!load(code.get(1),1)||!(code.get(2)instanceof MethodInsnNode call)||call.getOpcode()!=Opcodes.INVOKESPECIAL||!call.itf||!call.name.equals(method.name)||!call.desc.equals(method.desc)||code.get(3).getOpcode()!=Opcodes.IRETURN)return false;
  Map<String,MethodNode>declarations=new HashMap<>();Set<String>interfaces=new HashSet<>(),classes=new HashSet<>();ClassNode type=raw;
  while(type!=null&&classes.add(type.name)){if(find(type,method.name,method.desc)!=null)return false;for(String iface:type.interfaces)if(!interfaceDeclarations(iface,method.name,method.desc,factory,interfaces,declarations))return false;if(type.superName==null||type.superName.equals("java/lang/Object"))break;type=factory.nativeClasses.apply(type.superName);if(type==null)return false;}
  List<String>mostSpecific=new ArrayList<>(declarations.keySet());mostSpecific.removeIf(parent->declarations.keySet().stream().anyMatch(child->!parent.equals(child)&&subinterface(child,parent,factory,new HashSet<>())));if(mostSpecific.size()!=1||!safe(declarations.get(mostSpecific.getFirst())))return false;
  if(!interfaces.contains(call.owner))return false;Map<String,MethodNode>targetDeclarations=new HashMap<>();if(!interfaceDeclarations(call.owner,method.name,method.desc,factory,new HashSet<>(),targetDeclarations))return false;List<String>targets=new ArrayList<>(targetDeclarations.keySet());targets.removeIf(parent->targetDeclarations.keySet().stream().anyMatch(child->!parent.equals(child)&&subinterface(child,parent,factory,new HashSet<>())));return targets.size()==1&&targets.getFirst().equals(mostSpecific.getFirst());
 }
 private static boolean interfaceDeclarations(String owner,String name,String descriptor,Factory factory,Set<String>seen,Map<String,MethodNode>declarations){if(!seen.add(owner))return true;ClassNode type=factory.nativeClasses.apply(owner);if(type==null||(type.access&Opcodes.ACC_INTERFACE)==0)return false;MethodNode method=find(type,name,descriptor);if(method!=null&&(method.access&Opcodes.ACC_PUBLIC)!=0&&(method.access&Opcodes.ACC_STATIC)==0)declarations.put(owner,method);for(String parent:type.interfaces)if(!interfaceDeclarations(parent,name,descriptor,factory,seen,declarations))return false;return true;}
 private static boolean subinterface(String child,String parent,Factory factory,Set<String>seen){if(!seen.add(child))return false;ClassNode type=factory.nativeClasses.apply(child);if(type==null)return false;for(String iface:type.interfaces)if(iface.equals(parent)||subinterface(iface,parent,factory,seen))return true;return false;}
 private static boolean readOnlyFallback(MethodNode method,List<AbstractInsnNode>code,int call){
  if(code.size()>64)return false;Set<Integer>reachable=new HashSet<>();reachable.add(call);boolean changed;do{changed=false;for(int i=0;i<call;i++)for(int next:successors(code,i))if(reachable.contains(next)&&reachable.add(i))changed=true;}while(changed);
  boolean branch=false;for(int i:reachable){if(i==call)continue;var instruction=code.get(i);int op=instruction.getOpcode();if(instruction instanceof VarInsnNode variable){if(op!=Opcodes.ALOAD||variable.var>1)return false;}
   else if(instruction instanceof FieldInsnNode field){if(op!=Opcodes.GETSTATIC)return false;}
   else if(instruction instanceof MethodInsnNode getter){if(op==Opcodes.INVOKESTATIC||Type.getArgumentTypes(getter.desc).length!=0||Type.getReturnType(getter.desc).getSort()!=Type.OBJECT)return false;}
   else if(instruction instanceof JumpInsnNode jump){if(jump.getOpcode()!=Opcodes.GOTO&&jump.getOpcode()!=Opcodes.IF_ACMPEQ&&jump.getOpcode()!=Opcodes.IF_ACMPNE&&jump.getOpcode()!=Opcodes.IFNULL&&jump.getOpcode()!=Opcodes.IFNONNULL)return false;for(int destination:successors(code,i))if(destination<=i)return false;branch|=jump.getOpcode()!=Opcodes.GOTO;}
   else if(instruction instanceof TypeInsnNode cast){if(op!=Opcodes.CHECKCAST)return false;}
   else if(op!=Opcodes.ACONST_NULL)return false;
  }return branch;
 }
 private static int[]successors(List<AbstractInsnNode>code,int i){var instruction=code.get(i);if(instruction.getOpcode()>=Opcodes.IRETURN&&instruction.getOpcode()<=Opcodes.RETURN||instruction.getOpcode()==Opcodes.ATHROW)return new int[0];if(instruction instanceof JumpInsnNode branch){int target=code.indexOf(next(branch.label));return branch.getOpcode()==Opcodes.GOTO?new int[]{target}:new int[]{i+1,target};}return i+1<code.size()?new int[]{i+1}:new int[0];}
 private static boolean parameter(MethodNode method,Frame<SourceValue>[]frames,SourceValue value,int slot,Set<AbstractInsnNode>active){if(value==null||value.insns.size()!=1)return false;var instruction=value.insns.iterator().next();if(!active.add(instruction))return false;try{Frame<SourceValue>at=frames[method.instructions.indexOf(instruction)];if(at==null)return false;if(instruction instanceof VarInsnNode v){if(v.getOpcode()==Opcodes.ALOAD){SourceValue local=at.getLocal(v.var);return v.var==slot&&local.insns.isEmpty()||parameter(method,frames,local,slot,active);}if(v.getOpcode()==Opcodes.ASTORE)return parameter(method,frames,at.getStack(at.getStackSize()-1),slot,active);}if(instruction instanceof TypeInsnNode cast&&cast.getOpcode()==Opcodes.CHECKCAST)return parameter(method,frames,at.getStack(at.getStackSize()-1),slot,active);return false;}finally{active.remove(instruction);}}
 private static MethodInsnNode originCall(MethodNode method,Frame<SourceValue>[]frames,SourceValue value,Set<AbstractInsnNode>active){if(value==null||value.insns.size()!=1)return null;var instruction=value.insns.iterator().next();if(!active.add(instruction))return null;try{if(instruction instanceof MethodInsnNode call)return call;Frame<SourceValue>at=frames[method.instructions.indexOf(instruction)];if(at==null)return null;if(instruction instanceof VarInsnNode v){if(v.getOpcode()==Opcodes.ALOAD)return originCall(method,frames,at.getLocal(v.var),active);if(v.getOpcode()==Opcodes.ASTORE)return originCall(method,frames,at.getStack(at.getStackSize()-1),active);}if(instruction instanceof TypeInsnNode cast&&cast.getOpcode()==Opcodes.CHECKCAST)return originCall(method,frames,at.getStack(at.getStackSize()-1),active);return null;}finally{active.remove(instruction);}}
 private static boolean identity(MethodNode method){List<AbstractInsnNode>code=code(method);return Type.getArgumentTypes(method.desc).length==0&&method.tryCatchBlocks.isEmpty()&&(code.size()==2||code.size()==3&&code.get(1)instanceof TypeInsnNode cast&&cast.getOpcode()==Opcodes.CHECKCAST)&&load(code.getFirst(),0)&&code.getLast().getOpcode()==Opcodes.ARETURN;}
 private static boolean identity(Object subject,Identity identity,ClassLoader loader)throws ReflectiveOperationException{Class<?>owner=Class.forName(identity.body.owner(),false,loader);Method method=declared(owner,identity.body.name(),identity.body.descriptor());if(method==null||method.getModifiers()!=identity.flags||!owner.isInstance(subject)||!method.getReturnType().isInstance(subject)||!DefinedMethodContracts.observed(owner.getClassLoader(),identity.body))return false;if(identity.direct)return Modifier.isPrivate(method.getModifiers());Method actual=dispatch(subject.getClass(),identity.body.name(),identity.body.descriptor());return actual!=null&&actual.getDeclaringClass()==owner||DefinedMethodContracts.validatesTransparentDispatch(subject,identity.body);}
 private static int offset(List<AbstractInsnNode>code,int end){int offset=0;for(int i=0;i<end;i++){var instruction=code.get(i);if(instruction instanceof MethodInsnNode call)offset+=call.getOpcode()==Opcodes.INVOKEINTERFACE?5:3;else if(instruction instanceof FieldInsnNode||instruction instanceof TypeInsnNode||instruction instanceof JumpInsnNode)offset+=3;else if(instruction instanceof VarInsnNode v){if(v.var>=256)return -1;offset+=v.var<4?1:2;}else if(instruction instanceof InsnNode)offset++;else return -1;}return offset;}
 private static boolean load(AbstractInsnNode node,int slot){return node instanceof VarInsnNode v&&v.getOpcode()==Opcodes.ALOAD&&v.var==slot;}
 private static boolean safe(MethodNode method){return(method.access&Opcodes.ACC_PUBLIC)!=0&&(method.access&(Opcodes.ACC_STATIC|Opcodes.ACC_SYNCHRONIZED|Opcodes.ACC_NATIVE|Opcodes.ACC_ABSTRACT))==0;}
 private static boolean safe(Method method){return method!=null&&Modifier.isPublic(method.getModifiers())&&(method.getModifiers()&(Modifier.STATIC|Modifier.SYNCHRONIZED|Modifier.NATIVE|Modifier.ABSTRACT))==0;}
 private static Method declared(Class<?>owner,String name,String descriptor){Method found=null;for(Method method:owner.getDeclaredMethods())if(method.getName().equals(name)&&Type.getMethodDescriptor(method).equals(descriptor)){if(found!=null)return null;found=method;}return found;}
 private static Method dispatch(Class<?>owner,String name,String descriptor){Method found=null;for(Method method:owner.getMethods())if(method.getName().equals(name)&&Type.getMethodDescriptor(method).equals(descriptor)){if(!safe(method)||found!=null)return null;found=method;}return found;}
 private static Method special(Class<?>owner,String name,String descriptor){Method own=declared(owner,name,descriptor);return own!=null&&own.isDefault()&&safe(own)?own:dispatch(owner,name,descriptor);}
 private static MethodNode find(ClassNode owner,String name,String descriptor){return owner.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(descriptor)).findFirst().orElse(null);}
 private static List<AbstractInsnNode>code(MethodNode method){List<AbstractInsnNode>code=new ArrayList<>();for(var i:method.instructions)if(i.getOpcode()>=0)code.add(i);return code;}
 private static AbstractInsnNode next(AbstractInsnNode instruction){for(var i=instruction.getNext();i!=null;i=i.getNext())if(i.getOpcode()>=0)return i;return null;}
 public static void resetForTests(){FACTORIES.clear();}
}
