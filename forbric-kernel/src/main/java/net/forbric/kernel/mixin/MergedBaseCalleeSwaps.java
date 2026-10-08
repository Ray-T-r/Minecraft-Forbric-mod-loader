/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.forbric.api.Ecosystem;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Call changes derived from native/current program points; SDK or game-member names are not policy. */
public final class MergedBaseCalleeSwaps {
 private MergedBaseCalleeSwaps(){}
 public record Swap(String target,String method,String owner,String vanillaName,String mergedName,String desc,String because){
  public String vanillaMember(){return "L"+owner+";"+vanillaName+desc;}
  public String mergedMember(){return "L"+owner+";"+mergedName+desc;}
 }
 public record Substitution(String target,String method,String member,String replacement,Set<Ecosystem> ecosystems,String because){}
 public record Replaced(String owner,String caller,String vanilla,String replacement,List<String> arguments,Set<Ecosystem> ecosystems,String because){
  public String vanillaMember(){return "L"+owner+";"+vanilla;}
  public String replacementMember(){return "L"+owner+";"+replacement;}
 }
 private record Readers(Function<String,ClassNode> current,BiFunction<Ecosystem,String,ClassNode> sources){}
 private static final ThreadLocal<Readers> scoped=new ThreadLocal<>();
 public interface Scope extends AutoCloseable { @Override void close(); }
 static Scope using(Function<String,ClassNode> current,BiFunction<Ecosystem,String,ClassNode> sources){Readers before=scoped.get();scoped.set(new Readers(current,sources));return ()->{if(before==null)scoped.remove();else scoped.set(before);};}
 private static Readers readers(){Readers readers=scoped.get();return readers!=null?readers:new Readers(NativeGameReferences::current,NativeGameReferences::reference);}
 static ClassNode source(Ecosystem family,String owner){return readers().sources.apply(family,owner);}
 static ClassNode current(String owner){return readers().current.apply(owner);}
 static BiFunction<Ecosystem,String,ClassNode> sources(){return readers().sources;}
 // Diagnostic views contain only rows actually derived on this loader. They never authorize a match.
 private static final List<Swap> swaps=new ArrayList<>();private static final List<Substitution> substitutions=new ArrayList<>();private static final List<Replaced> replacements=new ArrayList<>();
 private static final Map<MixinRetarget.Rewrite,Replaced> projections=Collections.synchronizedMap(new IdentityHashMap<>());
 public static final List<Swap> KNOWN=Collections.unmodifiableList(swaps);
 public static final List<Substitution> SUBSTITUTED=Collections.unmodifiableList(substitutions);
 public static final List<Replaced> REPLACED=Collections.unmodifiableList(replacements);
 private static synchronized <T> T observed(List<T> rows,T row){if(row!=null&&!rows.contains(row))rows.add(row);return row;}
 static synchronized void reset(){swaps.clear();substitutions.clear();replacements.clear();projections.clear();}
 static void projection(MixinRetarget.Rewrite rewrite,Replaced row){projections.put(rewrite,row);}
 static Replaced projection(MixinRetarget.Rewrite rewrite){return projections.get(rewrite);}

 public static Swap find(String target,String method,String owner,String vanillaName,String desc){return find(target,method,owner,vanillaName,desc,null,null,false);}
 static Swap find(String target,String method,String owner,String vanillaName,String desc,Ecosystem family,MethodNode handler,boolean observation){
  Readers readers=readers();ClassNode current=readers.current.apply(target);if(current==null)return null;
  String wanted="L"+owner+";"+vanillaName+desc;MethodNode live=NativeCallChanges.method(current,method);if(live==null)return null;
  List<Swap> candidates=new ArrayList<>();
  for(Ecosystem ecosystem:family==null?List.of(Ecosystem.values()):List.of(family)){
   ClassNode source=readers.sources.apply(ecosystem,target);MethodNode nativeMethod=NativeCallChanges.method(source,method);if(nativeMethod==null)continue;
   List<NativeCallChanges.Site> old=NativeCallChanges.sites(source,nativeMethod).stream().filter(s->NativeCallChanges.member(s.call()).equals(wanted)).toList();
   List<NativeCallChanges.Site> now=NativeCallChanges.sites(current,live);if(old.isEmpty()||now.stream().anyMatch(s->NativeCallChanges.member(s.call()).equals(wanted)))continue;
   Set<String> names=new LinkedHashSet<>();for(var site:now)if(site.call().owner.equals(owner)&&site.call().desc.equals(desc)&&!site.call().name.equals(vanillaName))names.add(site.call().name);
   for(String replacement:names){
    List<NativeCallChanges.Site> calls=now.stream().filter(s->s.call().owner.equals(owner)&&s.call().name.equals(replacement)&&s.call().desc.equals(desc)).toList();
    if(calls.size()!=old.size()||NativeCallChanges.sites(source,nativeMethod).stream().anyMatch(s->s.call().owner.equals(owner)&&s.call().name.equals(replacement)&&s.call().desc.equals(desc)))continue;
    boolean aligned=true;for(var call:old){var matched=NativeCallChanges.match(call,calls,c->true);if(matched==null||matched.ordinal()!=call.ordinal()||!matched.operands().equals(call.operands())){aligned=false;break;}}
    if(!aligned)continue;
    Function<String,ClassNode> neo=n->{ClassNode indexed=readers.sources.apply(Ecosystem.NEOFORGE,n);return indexed!=null?indexed:NativeGameReferences.runtime(Ecosystem.NEOFORGE,n);};
    boolean equivalent=observation||handler!=null&&NativeDefaultDelegate.equivalent(handler,calls.getFirst().call(),neo);
    if(!equivalent)continue;
    candidates.add(new Swap(target,method,owner,vanillaName,replacement,desc,"every native operand and guarded occurrence is retained; the handler equals the complete native default delegate"));
   }
  }
  List<Swap> distinct=candidates.stream().distinct().toList();return distinct.size()==1?observed(swaps,distinct.getFirst()):null;
 }
 public static Substitution substitution(String target,String method,String anchor,Ecosystem family){
  if(family==null)return null;Readers readers=readers();ClassNode current=readers.current.apply(target),source=readers.sources.apply(family,target);
  MethodNode nativeMethod=NativeCallChanges.method(source,method),live=NativeCallChanges.method(current,method);if(nativeMethod==null||live==null)return null;
  MixinFit.Member wanted=MixinFit.parseMember(anchor);if(wanted==null)return null;
  List<NativeCallChanges.Site> old=NativeCallChanges.sites(source,nativeMethod).stream().filter(s->matches(s.call(),wanted)).toList();
  List<NativeCallChanges.Site> now=NativeCallChanges.sites(current,live);if(old.size()!=1||now.stream().anyMatch(s->matches(s.call(),wanted)))return null;
  List<NativeCallChanges.Site> candidates=now.stream().filter(s->s.call().getOpcode()==old.getFirst().call().getOpcode()
   &&List.of(Type.getArgumentTypes(s.call().desc)).equals(List.of(Type.getArgumentTypes(old.getFirst().call().desc)))
   &&s.operands().equals(old.getFirst().operands())&&NativeCallChanges.oneChangedCall(nativeMethod,live,old.getFirst().call(),s.call())).toList();
  if(candidates.size()!=1)return null;
  Set<Ecosystem> admitted=EnumSet.noneOf(Ecosystem.class);
  for(Ecosystem peer:Ecosystem.values()){
   ClassNode peerOwner=readers.sources.apply(peer,target);MethodNode peerMethod=NativeCallChanges.method(peerOwner,method);if(peerMethod==null)continue;
   List<NativeCallChanges.Site> peerCalls=NativeCallChanges.sites(peerOwner,peerMethod).stream().filter(s->NativeCallChanges.member(s.call()).equals(NativeCallChanges.member(old.getFirst().call()))).toList();
   if(peerCalls.size()==1&&NativeCallChanges.oneChangedCall(peerMethod,live,peerCalls.getFirst().call(),candidates.getFirst().call()))admitted.add(peer);
  }
  return observed(substitutions,new Substitution(target,method,NativeCallChanges.member(old.getFirst().call()),NativeCallChanges.member(candidates.getFirst().call()),Set.copyOf(admitted),"one actual call changed in an otherwise identical executable body, local scopes and exception CFG"));
 }
 public static Replaced replaced(String owner,String name,String desc,Ecosystem family){
  if(family==null)return null;Readers readers=readers();ClassNode current=readers.current.apply(owner),source=readers.sources.apply(family,owner);if(current==null||source==null)return null;
  List<Replaced> found=new ArrayList<>();
  for(MethodNode old:source.methods){
   if(!old.name.equals(name)||desc!=null&&!old.desc.equals(desc)||(old.access&Opcodes.ACC_PRIVATE)==0||NativeCallChanges.method(current,old.name+old.desc)!=null)continue;
   String member="L"+owner+";"+old.name+old.desc;List<Map.Entry<MethodNode,NativeCallChanges.Site>> callers=callers(source,member);if(callers.size()!=1)continue;
   MethodNode originalCaller=callers.getFirst().getKey(),live=NativeCallChanges.method(current,originalCaller.name+originalCaller.desc);if(live==null||((originalCaller.access^live.access)&Opcodes.ACC_STATIC)!=0)continue;
   NativeCallChanges.Site oldCall=callers.getFirst().getValue();List<NativeCallChanges.Site> candidates=new ArrayList<>();
   for(var call:NativeCallChanges.sites(current,live)){
    MethodNode replacement=NativeCallChanges.method(current,call.call().name+call.call().desc);
    if(!call.call().owner.equals(owner)||replacement==null||(replacement.access&Opcodes.ACC_PRIVATE)==0||((old.access^replacement.access)&Opcodes.ACC_STATIC)!=0
      ||NativeCallChanges.method(source,replacement.name+replacement.desc)!=null||!Type.getReturnType(old.desc).equals(Type.getReturnType(replacement.desc))
      ||callers(current,NativeCallChanges.member(call.call())).size()!=1)continue;
    List<NativeCallChanges.Expr> originalInputs=new ArrayList<>(oldCall.operands()),providers=new ArrayList<>(call.operands());
    if(oldCall.call().getOpcode()!=Opcodes.INVOKESTATIC){if(call.call().getOpcode()==Opcodes.INVOKESTATIC||!originalInputs.removeFirst().equals(providers.removeFirst()))continue;}
    List<Type> types=List.of(Type.getArgumentTypes(replacement.desc));List<String> projected=NativeCallChanges.projection(originalInputs,providers,types);
    if(projected==null||!provedGetters(projected,types,family,readers))continue;
    if(NativeCallChanges.match(oldCall,List.of(call),c->true)!=null)candidates.add(call);
   }
   if(candidates.size()!=1)continue;var call=candidates.getFirst();List<NativeCallChanges.Expr> originalInputs=new ArrayList<>(oldCall.operands()),providers=new ArrayList<>(call.operands());
   if(oldCall.call().getOpcode()!=Opcodes.INVOKESTATIC){originalInputs.removeFirst();providers.removeFirst();}
   List<String> projection=NativeCallChanges.projection(originalInputs,providers,List.of(Type.getArgumentTypes(call.call().desc)));
   found.add(new Replaced(owner,originalCaller.name+originalCaller.desc,old.name+old.desc,call.call().name+call.call().desc,projection,Set.of(family),"the native private body has one caller; the new private body's unique guarded call carries every native input through proved providers"));
  }
  return found.size()==1?observed(replacements,found.getFirst()):null;
 }
 private static boolean provedGetters(List<String> projection,List<Type> params,Ecosystem family,Readers readers){
  for(String input:projection){int dot=input.indexOf('.');if(dot<0)continue;int p=Integer.parseInt(input.substring(1,dot));String getter=input.substring(dot+1);
   ClassNode owner=readers.sources.apply(family,params.get(p).getInternalName());MethodNode method=NativeCallChanges.method(owner,getter);if(method==null||!method.tryCatchBlocks.isEmpty())return false;
   List<AbstractInsnNode> code=Arrays.stream(method.instructions.toArray()).filter(i->i.getOpcode()>=0).toList();
   if(code.size()==2 && (code.getFirst() instanceof LdcInsnNode || code.getFirst().getOpcode()>=Opcodes.ACONST_NULL&&code.getFirst().getOpcode()<=Opcodes.DCONST_1)
      &&code.getLast().getOpcode()==Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN))continue;
   if(code.size()!=3||!(code.getFirst() instanceof VarInsnNode self)||self.var!=0||self.getOpcode()!=Opcodes.ALOAD
     ||!(code.get(1) instanceof FieldInsnNode field)||field.getOpcode()!=Opcodes.GETFIELD||code.getLast().getOpcode()!=Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN))return false;
  }return true;
 }
 private static List<Map.Entry<MethodNode,NativeCallChanges.Site>> callers(ClassNode owner,String member){List<Map.Entry<MethodNode,NativeCallChanges.Site>> calls=new ArrayList<>();for(var method:owner.methods)for(var site:NativeCallChanges.sites(owner,method))if(NativeCallChanges.member(site.call()).equals(member))calls.add(Map.entry(method,site));return calls;}
 private static boolean matches(MethodInsnNode call,MixinFit.Member wanted){return call.name.equals(wanted.name())&&(wanted.owner()==null||call.owner.equals(wanted.owner()))&&(wanted.desc()==null||call.desc.equals(wanted.desc()));}
}
